(ns ai.obney.orc.orc-service.rlm-tree-executor-test
  "Tests for RLM tree executor - Phase 2 execution of generated trees.

   The tree executor takes a canonical ORC DSL tree, spawns a child tick,
   and returns results via promise. This enables two-phase RLM execution
   where Phase 1 generates the tree and Phase 2 executes it.

   NOTE: Tests use dscloj-provider nil to use the mock executor,
   which proves the execution flow without requiring LLM API keys."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test Context
;; =============================================================================

(defn- create-test-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/tree-executor-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        ;; Use dscloj-provider nil to use mock executor (no LLM API required)
        ;; IMPORTANT: Include event-pubsub in base-ctx so processors can publish returned events
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :dscloj-provider nil
                  :event-pubsub ps  ;; Must be in context for processors to publish result events
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-test-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

(defmacro with-test-context [[ctx-sym] & body]
  `(binding [tp-core/*default-dscloj-provider* nil]
     (let [~ctx-sym (create-test-context)]
       (try
         ~@body
         (finally
           (stop-test-context ~ctx-sym))))))

;; =============================================================================
;; Issue 001: Tracer Bullet - Simple tree executes via child tick
;; =============================================================================

(deftest simple-tree-executes-and-returns-outputs
  (testing "Simple final-only tree returns outputs"
    (with-test-context [ctx]
      ;; Create a minimal tree that just returns a final value
      ;; This is the canonical ORC DSL form (what rlm-dsl->orc-dsl produces)
      (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:summary]}]])
            ;; Provide the required output value via sandbox-vars
            sandbox-vars {:summary "Test summary from Phase 1"}
            ;; Execute the tree
            result (tree-executor/execute-tree tree ctx
                     {:sandbox-vars sandbox-vars
                      :timeout-ms 10000})]
        ;; Should return success (proves child tick mechanism works)
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        ;; Outputs should contain the expected keys
        ;; NOTE: Mock executor produces mock values; real executor would produce actual values
        (is (contains? (:outputs result) :summary)
            "Should return outputs containing :summary key")))))

(deftest tree-with-blackboard-inputs-executes
  (testing "Tree can access blackboard inputs passed to child tick"
    (with-test-context [ctx]
      (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:result]}]])
            ;; Blackboard inputs for the child tick
            blackboard {:input-data "Hello from blackboard"}
            sandbox-vars {:result "Processed input"}
            result (tree-executor/execute-tree tree ctx
                     {:blackboard blackboard
                      :sandbox-vars sandbox-vars
                      :timeout-ms 10000})]
        ;; Should return success (proves child tick mechanism works with blackboard inputs)
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        ;; Outputs should contain both sandbox-vars keys and blackboard keys
        (is (contains? (:outputs result) :result)
            "Should return outputs containing :result key")
        (is (contains? (:outputs result) :input-data)
            "Should return outputs containing blackboard input :input-data key")))))

;; =============================================================================
;; Issue 002: Phase 2 Integration - emit-tree! triggers automatic execution
;; =============================================================================

(deftest emit-tree-triggers-phase2-execution
  (testing "RLM node with emit-tree! returns :success with outputs (not :tree-generated)"
    (with-test-context [ctx]
      ;; Mock dscloj/predict to return code that calls emit-tree!
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      {:outputs {:code "(emit-tree!
                                          [:sequence
                                            [:llm {:instruction \"Extract summary\"
                                                   :reads [:document]
                                                   :writes [:summary]}]
                                            [:final {:keys [:summary]}]])"}
                       :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})]
        (let [node {:type :repl-researcher
                    :instruction "Analyze the document and extract a summary"
                    :reads [:document]
                    :writes [:summary]
                    :rlm {:recursive? false}
                    :max-iterations 5}
              blackboard {:document {:key :document
                                     :schema :string
                                     :value "This is a test document about AI."
                                     :version 1}}
              ;; Execute the RLM node - should trigger Phase 2 automatically
              result (executor/execute-repl-researcher-rlm
                       node blackboard :openrouter ctx)]
          ;; Should return :success, NOT :tree-generated
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result)
                   " - Phase 2 should execute automatically after emit-tree!"))
          ;; Should have outputs containing the expected key
          (is (contains? (:outputs result) :summary)
              "Should return outputs containing :summary key from Phase 2 execution"))))))

;; =============================================================================
;; Issue 003: Tree Compilation - LLM nodes actually execute
;; =============================================================================

(defn- create-test-context-with-provider
  "Create test context with a non-nil dscloj-provider so LLM nodes actually execute."
  []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/tree-executor-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        ;; Use a provider keyword so the executor actually calls dscloj/predict
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :dscloj-provider :openrouter  ;; Non-nil provider triggers real execution
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defmacro with-provider-context [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context-with-provider)]
     (try
       ~@body
       (finally
         (stop-test-context ~ctx-sym)))))

(deftest tree-with-llm-node-executes-sub-llm-call
  (testing "Generated tree with [:llm ...] node executes the LLM call during Phase 2"
    (with-provider-context [ctx]
      ;; Track sub-LLM calls to verify they actually happen
      (let [sub-llm-calls (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        ;; Record the call for verification
                        (swap! sub-llm-calls conj {:provider provider
                                                   :inputs inputs})
                        ;; Return mock summary
                        {:outputs {:summary "AI document analyzed successfully"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          ;; Create a tree that has an :llm node (not just :final)
          ;; This tree should cause a sub-LLM call during Phase 2
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:llm {:instruction "Summarize the document"
                               :reads [:document]
                               :writes [:summary]}]
                        [:final {:keys [:summary]}]])
                blackboard {:document "This is a test document about artificial intelligence."}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 10000})]
            ;; Phase 2 should succeed
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            ;; The :llm node should have triggered a sub-LLM call
            (is (pos? (count @sub-llm-calls))
                "Should have made at least one sub-LLM call during Phase 2")
            ;; The output should contain the LLM-generated summary
            (is (= "AI document analyzed successfully" (:summary (:outputs result)))
                "Should return the summary from the sub-LLM call")))))))

;; =============================================================================
;; Issue 004: Map-Each Decomposition in Generated Trees
;; =============================================================================

(deftest tree-with-map-each-processes-collection
  (testing "Generated tree with map-each iterates over collection and aggregates results"
    (with-provider-context [ctx]
      ;; Track sub-LLM calls
      (let [sub-llm-calls (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [chunk-text (get-in inputs [:chunk :value])]
                          ;; Record the call
                          (swap! sub-llm-calls conj {:chunk chunk-text})
                          ;; Return a summary for this chunk
                          {:outputs {:chunk-summary (str "Summary of: " chunk-text)}
                           :usage {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30}}))]
          ;; Create a tree that:
          ;; 1. Has chunks in blackboard
          ;; 2. Uses map-each to process each chunk
          ;; 3. Collects results
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks :as :chunk :into :summaries}
                         [:llm {:instruction "Summarize the chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]}]]
                        [:final {:keys [:summaries]}]])
                ;; Provide 3 chunks to process
                blackboard {:chunks ["chunk1 content" "chunk2 content" "chunk3 content"]}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 15000})]
            ;; Phase 2 should succeed
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            ;; Should have made 3 sub-LLM calls (one per chunk)
            (is (= 3 (count @sub-llm-calls))
                (str "Expected 3 sub-LLM calls, got: " (count @sub-llm-calls)))
            ;; Results should be aggregated
            (is (= 3 (count (:summaries (:outputs result))))
                "Should have 3 summaries in output")))))))

;; =============================================================================
;; O02: Per-node usage propagation (Phase 2 observability)
;; =============================================================================

(deftest tree-execution-result-includes-per-node-usage
  (testing "When emit-tree! runs sub-LLMs via map-each, each sub-LLM's usage
            propagates into the result so callers can see per-node token costs,
            not just a single aggregate. This is the universal token-tracking
            piece (extends :sheet/node-execution-completed event with :usage)."
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      ;; Each sub-LLM call returns a deterministic usage map.
                      ;; If 3 chunks → 3 calls → total should sum to 3 × 75 = 225 tokens.
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 50
                               :completion_tokens 25
                               :total_tokens 75}})]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]}]]
                      [:final {:keys [:summaries]}]])
              blackboard {:chunks ["a" "b" "c"]}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 15000})
              usage (:usage result)
              by-node (:by-node usage)]
          (is (= :success (:status result))
              (str "Phase 2 should succeed. Status: " (:status result)
                   " error: " (:error result)))
          (is (map? by-node)
              ":usage should contain a :by-node map of per-node token costs")
          ;; Expect at least 3 entries — one per chunk's sub-LLM call.
          (is (>= (count by-node) 3)
              (str ":by-node should have at least 3 entries (one per chunk), got: "
                   (count by-node) " — keys: " (keys by-node)))
          ;; Each entry should carry a usage map shape.
          (doseq [[k v] by-node]
            (is (some? (or (:total-tokens v) (:total_tokens v)))
                (str "by-node entry for " k " should have :total-tokens, got: " v)))
          ;; Sum of per-node tokens should approximately equal the aggregate.
          (let [sum-by-node (apply + (keep #(or (:total-tokens %) (:total_tokens %))
                                            (vals by-node)))
                aggregate-total (or (:total-tokens usage) (:total_tokens usage) 0)]
            (is (and (pos? sum-by-node) (pos? aggregate-total))
                "Both per-node sum and aggregate total should be > 0")
            (is (<= (Math/abs (- sum-by-node aggregate-total))
                    (max 5 (* 0.01 aggregate-total)))
                (str ":by-node totals (" sum-by-node ") should approximately equal "
                     ":total-tokens (" aggregate-total ")"))))))))

(deftest rlm-tree-node-completed-events-fire-with-paths
  (testing "Each sub-LLM completion in a Phase 2 tree also emits an
            :sheet/rlm-tree-node-completed event with the structured node-path.
            This is the RLM-specific learning-signal event (separate from the
            generic node-execution-completed) that future judge/fingerprint
            work will consume."
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 40 :completion_tokens 20 :total_tokens 60}})]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]}]]
                      [:final {:keys [:summaries]}]])
              blackboard {:chunks ["x" "y" "z"]}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard :timeout-ms 15000})
              ;; Read events emitted during this tick from the event store
              tick-id (:trace-id result)
              all-events (when tick-id
                           (into [] (es/read (:event-store ctx)
                                      {:tags #{[:tick tick-id]}
                                       :tenant-id (:tenant-id ctx)})))
              rlm-events (filter #(= :sheet/rlm-tree-node-completed (:event/type %))
                                 all-events)]
          (is (= :success (:status result)))
          (is (>= (count rlm-events) 3)
              (str "Expected at least 3 rlm-tree-node-completed events (one per "
                   "map-each iteration), got: " (count rlm-events)))
          ;; Each event should carry a structured node-path
          (doseq [e rlm-events]
            (is (vector? (:node-path e))
                "Event :node-path should be a vector of path segments")
            (is (some #(= :leaf (:type %)) (:node-path e))
                "Path should include a :leaf segment")
            (is (some #(= :map-each (:type %)) (:node-path e))
                "Path should include a :map-each segment (since this leaf runs under map-each)")
            (is (some? (:usage e))
                "Event should carry :usage"))
          ;; The 3 events should have distinct map-each indices (0, 1, 2)
          (let [indices (set (map (fn [e]
                                    (->> (:node-path e)
                                         (filter #(= :map-each (:type %)))
                                         first
                                         :index))
                                  rlm-events))]
            (is (= #{0 1 2} indices)
                (str "Expected indices #{0 1 2}, got: " indices))))))))

;; =============================================================================
;; O03: Tree-execution bookend + input profile (Phase 2 observability slice 2)
;; =============================================================================

(deftest rlm-tree-node-completed-events-carry-input-profile
  (testing "Each :sheet/rlm-tree-node-completed event includes :input-profile
            describing per-read-key characteristics (length, word-count,
            line-count). This is the per-node signal future judges/pattern
            matchers will correlate to outcomes."
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 30 :completion_tokens 15 :total_tokens 45}})]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]}]]
                      [:final {:keys [:summaries]}]])
              ;; Use chunks of distinct lengths so we can verify :length tracking
              blackboard {:chunks ["aaa" "longer chunk content" (apply str (repeat 50 "x"))]}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard :timeout-ms 15000})
              tick-id (:trace-id result)
              all-events (when tick-id
                           (into [] (es/read (:event-store ctx)
                                      {:tags #{[:tick tick-id]}
                                       :tenant-id (:tenant-id ctx)})))
              rlm-events (filter #(= :sheet/rlm-tree-node-completed (:event/type %))
                                 all-events)]
          (is (= :success (:status result)))
          (is (>= (count rlm-events) 3)
              (str "Expected 3 RLM per-node events, got: " (count rlm-events)))
          (doseq [e rlm-events]
            (is (map? (:input-profile e))
                ":input-profile should be a map")
            (let [chunk-profile (get-in e [:input-profile :chunk])]
              (is (map? chunk-profile)
                  ":input-profile should have an entry for the :chunk read key")
              (is (some? (:length chunk-profile))
                  ":input-profile entry should have :length")))
          ;; The lengths should reflect the actual chunk sizes (3, 20, 50).
          (let [lengths (set (keep (fn [e] (get-in e [:input-profile :chunk :length]))
                                   rlm-events))]
            (is (= #{3 20 50} lengths)
                (str "Expected chunk lengths #{3 20 50}, got: " lengths))))))))

(deftest rlm-tree-execution-completed-bookend-event-fires
  (testing "When a Phase 2 tree finishes, a single
            :sheet/rlm-tree-execution-completed bookend event is emitted with
            :trajectory (full per-event log), :total-usage, and a placeholder
            :task-fingerprint (nil for now)."
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 25 :completion_tokens 10 :total_tokens 35}})]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]}]]
                      [:final {:keys [:summaries]}]])
              blackboard {:chunks ["c1" "c2"]}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard :timeout-ms 15000})
              tick-id (:trace-id result)
              all-events (when tick-id
                           (into [] (es/read (:event-store ctx)
                                      {:tags #{[:tick tick-id]}
                                       :tenant-id (:tenant-id ctx)})))
              bookend-events (filter #(= :sheet/rlm-tree-execution-completed (:event/type %))
                                     all-events)]
          (is (= :success (:status result)))
          (is (= 1 (count bookend-events))
              (str "Expected exactly 1 bookend event, got: " (count bookend-events)))
          (let [bookend (first bookend-events)]
            (is (vector? (:trajectory bookend))
                ":trajectory should be a vector")
            (is (pos? (count (:trajectory bookend)))
                ":trajectory should be non-empty")
            ;; Per Q2 decision: trajectory captures ALL event types for the tick.
            ;; Should include at minimum node-execution-started and node-execution-completed
            ;; for the leaf-llm calls under map-each.
            (let [event-types (set (map :event-type (:trajectory bookend)))]
              (is (contains? event-types :sheet/node-execution-started)
                  "Trajectory should include :sheet/node-execution-started entries")
              (is (contains? event-types :sheet/node-execution-completed)
                  "Trajectory should include :sheet/node-execution-completed entries"))
            (is (contains? bookend :task-fingerprint)
                "Bookend should have :task-fingerprint key (placeholder, may be nil)")
            (is (some? (:total-usage bookend))
                "Bookend should carry :total-usage")
            (is (some? (:timestamp bookend))
                "Bookend should carry :timestamp")))))))

;; =============================================================================
;; P01: max-concurrency parallel execution in map-each
;; =============================================================================

(deftest map-each-max-concurrency-runs-iterations-in-parallel
  (testing "When :max-concurrency N is set on map-each over M items (M > N),
            multiple child iterations are in flight simultaneously"
    (with-provider-context [ctx]
      ;; Instrument the mock LLM to track in-flight concurrency.
      ;; Each call: increment counter on entry, sleep ~100ms, decrement on exit.
      ;; We track the peak in-flight count (high-water mark).
      (let [in-flight (atom 0)
            peak (atom 0)
            call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [_provider _module inputs _opts]
                        (swap! call-count inc)
                        (let [now-in-flight (swap! in-flight inc)]
                          (swap! peak max now-in-flight))
                        ;; Hold the call open so concurrency can be observed
                        (Thread/sleep 200)
                        (swap! in-flight dec)
                        {:outputs {:chunk-summary
                                   (str "Summary of: " (get-in inputs [:chunk :value]))}
                         :usage {:prompt_tokens 20
                                 :completion_tokens 10
                                 :total_tokens 30}})]
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks
                                    :as :chunk
                                    :into :summaries
                                    :max-concurrency 3}
                         [:llm {:instruction "Summarize the chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]}]]
                        [:final {:keys [:summaries]}]])
                ;; 6 items so we'd expect 2 batches of 3 if parallelism works
                blackboard {:chunks ["c1" "c2" "c3" "c4" "c5" "c6"]}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 30000})]
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            (is (= 6 @call-count)
                (str "Should have made 6 sub-LLM calls, got: " @call-count))
            ;; THE behavior we care about: peak in-flight > 1.
            ;; Ideally peak >= 3 (matching max-concurrency).
            ;; If the bug exists, peak will be 1.
            (is (> @peak 1)
                (str "Expected >1 sub-LLM call in flight at peak (max-concurrency=3), "
                     "got peak=" @peak " - map-each is running serially!"))
            (is (>= @peak 3)
                (str "Expected peak in-flight to equal max-concurrency (3), got: " @peak))))))))

;; =============================================================================
;; Issue 005: Code Nodes in Generated Trees
;; =============================================================================

(deftest tree-with-code-node-executes-computation
  (testing "Generated tree with code node executes pure computation"
    (with-provider-context [ctx]
      ;; Create a tree that uses a code node to chunk a document
      ;; This is the :chunk-document pattern from the DSL
      (let [;; The chunk-document DSL generates a code node with an inline function
            tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:chunk-document {:from :document :size 10 :into :chunks}]
                    [:final {:keys [:chunks]}]])
            blackboard {:document "Hello world, this is a test document for chunking."}
            result (tree-executor/execute-tree tree ctx
                     {:blackboard blackboard
                      :timeout-ms 10000})]
        ;; Should succeed
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        ;; Should have chunks in output
        (is (some? (:chunks (:outputs result)))
            "Should have :chunks in output")
        ;; Chunks should be a vector of strings (partitioned by size 10)
        (let [chunks (:chunks (:outputs result))]
          (is (vector? chunks) "Chunks should be a vector")
          (is (pos? (count chunks)) "Should have at least one chunk"))))))

(deftest tree-with-failing-llm-node-propagates-error
  (testing "When LLM node fails in Phase 2, error propagates back correctly"
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      ;; Simulate LLM failure
                      (throw (ex-info "Rate limit exceeded" {:status 429})))]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:llm {:instruction "Analyze document"
                             :reads [:document]
                             :writes [:analysis]}]
                      [:final {:keys [:analysis]}]])
              blackboard {:document "Test document"}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          ;; Should return failure status
          (is (= :failure (:status result))
              (str "Expected :failure status, got: " (:status result)))
          ;; Should have an error message
          (is (some? (:error result))
              "Should have an error message"))))))

;; =============================================================================
;; Issue 006: Ephemeral Function Cleanup
;; =============================================================================

(deftest ephemeral-functions-cleaned-up-after-execution
  (testing "Ephemeral functions are removed from registry after tree execution"
    (with-provider-context [ctx]
      ;; Get initial registry state (deref the var, then deref the atom)
      (let [initial-count (count @@#'tree-executor/ephemeral-fn-registry)]
        ;; Execute a tree with a code node (chunk-document creates one)
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:chunk-document {:from :document :size 10 :into :chunks}]
                      [:final {:keys [:chunks]}]])
              blackboard {:document "Hello world, this is a test document."}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          ;; Execution should succeed
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          ;; Registry should be back to initial count (functions cleaned up)
          (is (= initial-count (count @@#'tree-executor/ephemeral-fn-registry))
              (str "Expected ephemeral registry to be cleaned up. "
                   "Initial: " initial-count ", Current: "
                   (count @@#'tree-executor/ephemeral-fn-registry))))))))

;; =============================================================================
;; Issue 007: Sub-LLM Usage Tracking
;; =============================================================================

(deftest sub-llm-usage-is-tracked-and-aggregated
  (testing "Usage from sub-LLM calls is included in final result"
    (with-provider-context [ctx]
      ;; Track sub-LLM calls with mock usage
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      ;; Return mock output with usage
                      {:outputs {:summary "Test summary"}
                       :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:llm {:instruction "Summarize"
                             :reads [:document]
                             :writes [:summary]}]
                      [:final {:keys [:summary]}]])
              blackboard {:document "Test document content"}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          ;; Execution should succeed
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          ;; Should have usage data in result
          (is (some? (:usage result))
              "Result should include :usage from sub-LLM calls")
          ;; Usage should have expected structure
          (when (:usage result)
            (is (pos? (get-in result [:usage :total-tokens] 0))
                "Usage should have positive total-tokens")))))))

(deftest multiple-sub-llm-calls-aggregate-usage
  (testing "Usage from multiple sub-LLM calls is aggregated"
    (with-provider-context [ctx]
      (let [call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! call-count inc)
                        ;; Each call returns 100 tokens
                        {:outputs {:chunk-summary "Summary of chunk"}
                         :usage {:prompt_tokens 50 :completion_tokens 50 :total_tokens 100}})]
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks :as :chunk :into :summaries}
                         [:llm {:instruction "Summarize chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]}]]
                        [:final {:keys [:summaries]}]])
                ;; 3 chunks = 3 sub-LLM calls = 300 total tokens
                blackboard {:chunks ["chunk1" "chunk2" "chunk3"]}
                result (tree-executor/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 15000})]
            ;; Execution should succeed
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            ;; Should have made 3 calls
            (is (= 3 @call-count)
                (str "Expected 3 sub-LLM calls, got: " @call-count))
            ;; Should have aggregated usage
            (is (some? (:usage result))
                "Result should include :usage")
            (when (:usage result)
              ;; 3 calls x 100 tokens each = 300 total
              (is (= 300 (get-in result [:usage :total-tokens] 0))
                  (str "Expected 300 total tokens, got: " (get-in result [:usage :total-tokens]))))))))))

;; =============================================================================
;; D-003: Budget-aware Phase 2 timeout with cancellation
;; =============================================================================

(deftest budget-exhausted-in-phase1-skips-phase2
  (testing "When Phase 1 elapsed >= :timeout-ms, Phase 2 is skipped entirely with clean :failure"
    (with-provider-context [ctx]
      (let [call-count (atom 0)]
        ;; Mock predict: first call (Phase 1 code-gen) sleeps > budget then
        ;; returns valid emit-tree! code. Any subsequent call would indicate
        ;; Phase 2 sub-LLM was invoked — which should NOT happen here because
        ;; budget is already exhausted.
        (with-redefs [dscloj/predict
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            (do (Thread/sleep 150)
                                {:outputs {:code "(emit-tree!
                                                   [:sequence
                                                     [:llm {:instruction \"x\"
                                                            :reads [:doc]
                                                            :writes [:summary]}]
                                                     [:final {:keys [:summary]}]])"}
                                 :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})
                            {:outputs {:summary "should not be reached"}
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})))]
          (let [node {:type :repl-researcher
                      :instruction "Anything"
                      :reads [:doc]
                      :writes [:summary]
                      :rlm {:recursive? false}
                      :max-iterations 1
                      :timeout-ms 100}  ;; Tiny budget — Phase 1's 150ms sleep exhausts it
                blackboard {:doc {:key :doc :schema :string :value "hello" :version 1}}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)]
            (is (= :failure (:status result))
                (str "Expected :failure when budget exhausted in Phase 1, got: "
                     (:status result) " error: " (:error result)))
            (is (re-find #"Budget exhausted in Phase 1" (str (:error result)))
                ":error should mention budget exhaustion")
            (is (= 1 @call-count)
                "Exactly ONE predict call should fire (Phase 1). Phase 2 must NOT trigger sub-LLM calls.")
            (is (number? (:phase1-elapsed-ms result))
                ":phase1-elapsed-ms must be present in the response")
            (is (>= (:phase1-elapsed-ms result) 100)
                ":phase1-elapsed-ms reflects actual Phase 1 wall-time")))))))

(deftest phase2-timeout-dispatches-cancel-tick
  (testing "When Phase 2 exceeds remaining budget, :sheet cancel-tick is dispatched on the child tick"
    (with-provider-context [ctx]
      ;; Phase 1: fast — return valid emit-tree! code immediately.
      ;; Phase 2: each sub-LLM call sleeps long enough to exceed the small budget.
      (with-redefs [dscloj/predict
                    (let [call-count (atom 0)]
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            ;; Phase 1 code-gen — quick, returns emit-tree!
                            {:outputs {:code "(emit-tree!
                                               [:sequence
                                                 [:llm {:instruction \"slow-summarize\"
                                                        :reads [:doc]
                                                        :writes [:summary]}]
                                                 [:final {:keys [:summary]}]])"}
                             :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                            ;; Phase 2 sub-LLM — slow, sleeps past the budget
                            (do (Thread/sleep 800)
                                {:outputs {:summary "would-be-summary"}
                                 :usage {:prompt_tokens 10 :completion_tokens 10 :total_tokens 20}})))))]
        (let [node {:type :repl-researcher
                    :instruction "Summarize the doc"
                    :reads [:doc]
                    :writes [:summary]
                    :rlm {:recursive? false}
                    :max-iterations 1
                    ;; Total budget 500ms. Phase 1 is fast (<100ms), Phase 2's
                    ;; first sub-LLM sleeps 800ms → cancellation must fire.
                    :timeout-ms 500}
              blackboard {:doc {:key :doc :schema :string :value "hello" :version 1}}
              result (executor/execute-repl-researcher-rlm
                       node blackboard :openrouter ctx)
              ;; After Phase 2 timeout + 500ms drain, the child tick should have
              ;; been cancelled. Find the :sheet/tick-cancelled event for that tick.
              all-events (into [] (es/read (:event-store ctx)
                                    {:types #{:sheet/tick-cancelled}
                                     :tenant-id (:tenant-id ctx)}))]
          (is (= :timeout (:status result))
              (str "Expected :timeout, got: " (:status result) " error: " (:error result)))
          (is (number? (:phase1-elapsed-ms result))
              ":phase1-elapsed-ms present on timeout response")
          (is (number? (:phase2-elapsed-ms result))
              ":phase2-elapsed-ms present on timeout response")
          (is (pos? (count all-events))
              "Expected at least one :sheet/tick-cancelled event after Phase 2 timeout")
          ;; Verify cancellation targeted the actual child tick (not some other tick)
          (when (pos? (count all-events))
            (let [cancelled-tick-ids (set (map :tick-id all-events))]
              (is (contains? cancelled-tick-ids (:phase2-tick-id result))
                  (str "Cancelled tick-ids " cancelled-tick-ids
                       " should include the Phase 2 child tick " (:phase2-tick-id result))))))))))

(deftest happy-path-response-includes-elapsed-ms-fields
  (testing "Normal Phase 1 + Phase 2 success carries :phase1-elapsed-ms + :phase2-elapsed-ms"
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (let [call-count (atom 0)]
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            ;; Phase 1
                            {:outputs {:code "(emit-tree!
                                               [:sequence
                                                 [:llm {:instruction \"summarize\"
                                                        :reads [:doc]
                                                        :writes [:summary]}]
                                                 [:final {:keys [:summary]}]])"}
                             :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                            ;; Phase 2 sub-LLM
                            {:outputs {:summary "fast summary"}
                             :usage {:prompt_tokens 30 :completion_tokens 20 :total_tokens 50}}))))]
        (let [node {:type :repl-researcher
                    :instruction "Summarize"
                    :reads [:doc]
                    :writes [:summary]
                    :rlm {:recursive? false}
                    :max-iterations 1
                    :timeout-ms 60000}  ;; Generous budget — happy path
              blackboard {:doc {:key :doc :schema :string :value "hello" :version 1}}
              result (executor/execute-repl-researcher-rlm
                       node blackboard :openrouter ctx)]
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          (is (number? (:phase1-elapsed-ms result))
              ":phase1-elapsed-ms must be present on happy path")
          (is (number? (:phase2-elapsed-ms result))
              ":phase2-elapsed-ms must be present on happy path")
          (is (>= (:phase1-elapsed-ms result) 0))
          (is (>= (:phase2-elapsed-ms result) 0))
          (is (some? (:budget result))
              "Budget metadata available for debugging/observability")
          (is (= :node (get-in result [:budget :source]))
              "Budget source reflects the node's :timeout-ms taking precedence"))))))

;; =============================================================================
;; U4: extract-all-keys position-independent parsing of :code nodes
;; =============================================================================
;;
;; When rlm-dsl translates a [:code ...] DSL node it emits `:fn` FIRST in the
;; canonical sheet/code form:
;;     (sheet/code :fn <fn> :reads [...] :writes [...])
;;
;; extract-all-keys must extract :reads and :writes regardless of whether
;; :fn appears first, last, or anywhere else in the args list. A naive
;; (take-while #(not= :fn %) args) approach returns an empty arg list
;; whenever :fn is first, which means the :reads and :writes keys are
;; never declared on the child sheet — the :code node then can't read its
;; inputs or write its outputs.
;;
;; The fix: parse the args uniformly with (apply hash-map (rest tree)).
;; An inline-fn or qualified-symbol-string value at :fn fits a hash-map
;; entry just as well as any other keyword-value pair.

(deftest code-node-with-fn-first-declares-reads-and-writes-on-child-sheet
  (testing "U4: When a :code node's canonical form has :fn first (rlm-dsl
            emits this shape for model-authored :code), extract-all-keys
            still extracts :reads and :writes so the child sheet can declare
            those blackboard keys. Otherwise the :code execution fails to
            read its inputs or write its outputs.

            Uses string inputs (not numbers) to avoid an unrelated pre-existing
            bug: execute-tree's schema inference returns invalid Malli `:number`
            for number values, breaking declare-key for any number input. That
            bug is orthogonal to U4 and tracked separately."
    (with-provider-context [ctx]
      ;; Use the rlm-dsl translator so we exercise the actual canonical form
      ;; the framework sees when the model emits a [:code ...] tree node.
      (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:code {:fn (fn [{:keys [inputs]}]
                                  {:upper (clojure.string/upper-case (:text inputs))})
                            :reads [:text]
                            :writes [:upper]}]
                    [:final {:keys [:upper]}]])
            blackboard {:text "hello"}
            result (tree-executor/execute-tree tree ctx
                     {:blackboard blackboard
                      :timeout-ms 10000})]
        ;; If extract-all-keys mis-parses the args (skips after :fn), the
        ;; child sheet won't declare :upper and the :code node will
        ;; either fail to write or :final won't surface it.
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result)
                 " error: " (:error result)))
        (is (= "HELLO" (get-in result [:outputs :upper]))
            "The :code node should read :text=\"hello\" and write :upper=\"HELLO\"")))))

;; =============================================================================
;; U7: :code output reconciliation against declared :writes
;; =============================================================================
;;
;; When a model-authored :code fn returns a non-map value (e.g. a string from
;; clojure.string/upper-case, a number from frequencies, etc.) AND exactly one
;; :writes key is declared, the framework should auto-wrap the value under
;; that write key rather than failing with "function must return a map".
;;
;; This matches the natural Clojure idiom: a simple transform `(fn [{:keys
;; [inputs]}] (clojure.string/upper-case (:text inputs)))` should compose
;; with `:writes [:upper]` without requiring the model to remember to wrap.

(deftest code-node-with-single-write-and-scalar-return-auto-wraps
  (testing "U7: When :code's :fn returns a scalar (non-map) AND :writes has
            exactly one key, the framework auto-wraps the scalar under that
            write key. The natural Clojure idiom for simple transforms
            (string ops, arithmetic, counts) returns a scalar — making the
            model emit `{:upper x}` instead of just `x` is friction."
    (with-provider-context [ctx]
      (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:code {:fn (fn [{:keys [inputs]}]
                                  ;; Returns a scalar string, NOT a map
                                  (clojure.string/upper-case (:text inputs)))
                            :reads [:text]
                            :writes [:upper]}]
                    [:final {:keys [:upper]}]])
            blackboard {:text "hello"}
            result (tree-executor/execute-tree tree ctx
                     {:blackboard blackboard
                      :timeout-ms 10000})]
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result)
                 " error: " (:error result)))
        (is (= "HELLO" (get-in result [:outputs :upper]))
            "Scalar result \"HELLO\" should be wrapped under :upper")))))

;; =============================================================================
;; U6: Phase-2 child-sheet schema preservation via :blackboard-schemas
;; =============================================================================
;;
;; When the parent passes :blackboard-schemas {key schema} to execute-tree,
;; the child sheet's declare-key uses that schema rather than type-inferring
;; from the value. This preserves :field-type :image, :output-schemas, and
;; any other schema-driven routing across the Phase-1 → Phase-2 boundary.

(deftest blackboard-schemas-option-preserves-parent-schema-on-child-sheet
  (testing "U6: execute-tree's :blackboard-schemas option overrides naive
            type-inference so that a parent-declared schema like
            [:string {:field-type :image}] survives into the child sheet's
            declare-key. Without this, image inputs would be declared with
            plain :string and lose multimodal routing."
    (let [declared-schemas (atom {})]
      (with-redefs [ai.obney.grain.command-processor-v2.interface/process-command
                    (let [orig ai.obney.grain.command-processor-v2.interface/process-command]
                      (fn [ctx]
                        (let [cmd (:command ctx)]
                          (when (= :sheet/declare-key (:command/name cmd))
                            (swap! declared-schemas assoc (:key cmd) (:schema cmd))))
                        (orig ctx)))]
        (with-provider-context [ctx]
          (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence [:final {:keys [:image]}]])
                blackboard {:image "data:image/png;base64,abc123"}
                blackboard-schemas {:image [:string {:field-type :image}]}
                _ (tree-executor/execute-tree tree ctx
                    {:blackboard blackboard
                     :blackboard-schemas blackboard-schemas
                     :timeout-ms 10000})]
            (is (= [:string {:field-type :image}]
                   (get @declared-schemas :image))
                (str "Expected :image declared with [:string {:field-type :image}] "
                     "(parent's schema preserved), got: "
                     (pr-str (get @declared-schemas :image))))))))))

;; =============================================================================
;; U13: :parallel node compilation support
;; =============================================================================
;;
;; The framework prompt documents `:parallel - Execute children concurrently`
;; as a tree-DSL node type and rlm_dsl/rlm-dsl->orc-dsl translates `:parallel`
;; → `(sheet/parallel ...)`. But compile-tree-node had no `sheet/parallel`
;; branch, so any tree the model emitted with `:parallel` failed Phase-2 with
;; "Unknown tree node type: sheet/parallel".
;;
;; Real benchmark-blocking bug surfaced when document_analysis under
;; gemini-3-flash-preview chose :parallel and Phase-2 crashed every time.

(deftest tree-with-parallel-node-executes-children
  (testing "U13: A tree containing [:parallel ...] compiles and executes,
            running children to completion. Without U13, this fails with
            'Unknown tree node type: sheet/parallel'."
    (with-provider-context [ctx]
      (with-redefs [dscloj/predict
                    (let [c (atom 0)]
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! c inc)]
                          {:outputs {(if (= n 1) :left-result :right-result) (str "ans-" n)}
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                     [:sequence
                      [:parallel
                       [:llm {:instruction "left" :reads [:input] :writes [:left-result]}]
                       [:llm {:instruction "right" :reads [:input] :writes [:right-result]}]]
                      [:final {:keys [:left-result :right-result]}]])
              blackboard {:input "hello"}
              result (tree-executor/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          (is (contains? (:outputs result) :left-result)
              "Should populate :left-result write key")
          (is (contains? (:outputs result) :right-result)
              "Should populate :right-result write key"))))))

(comment
  ;; Run individual test
  (clojure.test/run-tests 'ai.obney.orc.orc-service.rlm-tree-executor-test))

;; =============================================================================
;; R-4: compile-tree-node for sheet/llm preserves :model
;;
;; LIVE evidence: in document_redaction recursive runs, sub-model injection
;; DOES fire (the canonical tree has :model "openai/gpt-5.1-chat" on each
;; sheet/llm form per [DEBUG RLM] logs). But the AI execution events show
;; ALL Phase-2 :llm calls hitting google/gemini-3-flash-preview, not
;; openai/gpt-5.1-chat.
;;
;; Root cause: compile-tree-node's sheet/llm branch reads :instruction,
;; :reads, :writes, and :retry from opts — but DROPS :model. The leaf
;; node is created without the model attribute, so execute-llm-leaf
;; falls back to litellm's :openrouter default (gemini-3-flash).
;;
;; Fix: when :model is present on sheet/llm, emit a
;; :sheet/set-node-executor command with :executor :ai :model M.
;; =============================================================================

(deftest compile-tree-node-for-sheet-llm-preserves-model
  (testing "When (sheet/llm :model \"X\" ...) is compiled, the projected leaf node has :model \"X\""
    (with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            ;; Declare keys
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :a :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :b :string))
            ;; Compile a (sheet/llm :model "X" ...) tree node
            compile-fn (resolve 'ai.obney.orc.orc-service.core.rlm-tree-executor/compile-tree-node)
            tree '(sheet/llm
                    :instruction "test"
                    :reads [:a]
                    :writes [:b]
                    :model "openai/gpt-5.1-chat")
            result (compile-fn ctx sheet-id tree nil)
            leaf-id (:node-id result)
            ;; Read the projected leaf node from the read-model
            rm-fn (resolve 'ai.obney.orc.orc-service.core.read-models/get-node)
            node (rm-fn ctx sheet-id leaf-id)]
        (is (some? node) "Leaf node exists in read-model")
        (is (= "openai/gpt-5.1-chat" (:model node))
            "Model attribute preserved from (sheet/llm :model ...) onto the projected leaf")
        (is (= :ai (:executor node))
            "Executor set to :ai (so the leaf is dispatched as an LLM call)")))))

(deftest compile-tree-node-for-sheet-llm-without-model-leaves-no-model
  (testing "When :model is absent, the projected leaf node has no :model (no regression for terminal mode)"
    (with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :a :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :b :string))
            compile-fn (resolve 'ai.obney.orc.orc-service.core.rlm-tree-executor/compile-tree-node)
            tree '(sheet/llm
                    :instruction "test"
                    :reads [:a]
                    :writes [:b])
            result (compile-fn ctx sheet-id tree nil)
            leaf-id (:node-id result)
            rm-fn (resolve 'ai.obney.orc.orc-service.core.read-models/get-node)
            node (rm-fn ctx sheet-id leaf-id)]
        (is (some? node) "Leaf node exists in read-model")
        (is (nil? (:model node))
            "Model is nil when not specified on the sheet/llm form")))))
