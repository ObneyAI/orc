(ns ai.obney.orc.orc-service.rlm-tree-executor-test
  "Tests for RLM tree executor - Phase 2 execution of generated trees.

   The tree executor takes a canonical ORC DSL tree, spawns a child tick,
   and returns results via promise. This enables two-phase RLM execution
   where Phase 1 generates the tree and Phase 2 executes it.

   NOTE: Tests use llm-provider nil to use the mock executor,
   which proves the execution flow without requiring LLM API keys."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.commands :as service-commands]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

;; =============================================================================
;; Test Context
;; =============================================================================

(defn- create-test-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/tree-executor-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :llm-provider nil
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
  `(binding [service-logic/*default-llm-provider* nil]
     (let [~ctx-sym (create-test-context)]
       (try
         ~@body
         (finally
           (stop-test-context ~ctx-sym))))))


(deftest simple-tree-executes-and-returns-outputs
  (testing "Simple final-only tree returns outputs"
    (with-test-context [ctx]
      (let [tree (service-logic/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:summary]}]])
            sandbox-vars {:summary "Test summary from Phase 1"}
            result (tp-core/execute-tree tree ctx
                     {:sandbox-vars sandbox-vars
                      :timeout-ms 10000})]
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        (is (contains? (:outputs result) :summary)
            "Should return outputs containing :summary key")))))

(deftest phase-two-tree-inherits-operation-correlation
  (testing "an RLM child tick persists the hosting operation UUID"
    (with-test-context [ctx]
      (let [correlation-id (random-uuid)
            tree (service-logic/rlm-dsl->orc-dsl
                  [:sequence [:final {:keys [:summary]}]])
            result (tp-core/execute-tree
                    tree (assoc ctx :orc/correlation-id correlation-id)
                    {:sandbox-vars {:summary "correlated"}
                     :timeout-ms 10000})
            events (into []
                         (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx)
                                   :tags #{[:correlation correlation-id]}}))]
        (is (= :success (:status result)))
        (is (= "correlated" (get-in result [:outputs :summary])))
        (is (= 1 (count (filter #(= :sheet/tree-tick-started (:event/type %))
                                events))))
        (is (every? #(= correlation-id (:correlation-id %)) events))))))

(deftest tree-with-blackboard-inputs-executes
  (testing "Tree can access blackboard inputs passed to child tick"
    (with-test-context [ctx]
      (let [tree (service-logic/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:result]}]])
            blackboard {:input-data "Hello from blackboard"}
            sandbox-vars {:result "Processed input"}
            result (tp-core/execute-tree tree ctx
                     {:blackboard blackboard
                      :sandbox-vars sandbox-vars
                      :timeout-ms 10000})]
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        (is (contains? (:outputs result) :result)
            "Should return outputs containing :result key")
        (is (contains? (:outputs result) :input-data)
            "Should return outputs containing blackboard input :input-data key")))))


(deftest emit-tree-triggers-phase2-execution
  (testing "RLM node with emit-tree! returns :success with outputs (not :tree-generated)"
    (with-test-context [ctx]
      (with-redefs [llm/predict
                    (fn [provider module inputs opts]
                      {:outputs {:code "(emit-tree!
                                          [:sequence
                                            [:llm {:instruction \"Extract summary\"
                                                   :reads [:document]
                                                   :writes [:summary]
                                                   :output-schemas {:summary :string}}]
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
              result (tp-core/execute-repl-researcher-rlm
                       node blackboard :openrouter ctx)]
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result)
                   " - Phase 2 should execute automatically after emit-tree!"))
          (is (contains? (:outputs result) :summary)
              "Should return outputs containing :summary key from Phase 2 execution"))))))


(defn- create-test-context-with-provider
  "Create test context with a non-nil llm-provider so LLM nodes actually execute."
  []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/tree-executor-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :llm-provider :openrouter
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
      (let [sub-llm-calls (atom [])]
        (with-redefs [llm/predict
                      (fn [provider module inputs opts]
                        (swap! sub-llm-calls conj {:provider provider
                                                   :inputs inputs})
                        {:outputs {:summary "AI document analyzed successfully"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          (let [tree (service-logic/rlm-dsl->orc-dsl
                       [:sequence
                        [:llm {:instruction "Summarize the document"
                               :reads [:document]
                               :writes [:summary]
                               :output-schemas {:summary :string}}]
                        [:final {:keys [:summary]}]])
                blackboard {:document "This is a test document about artificial intelligence."}
                result (tp-core/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 10000})]
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            (is (pos? (count @sub-llm-calls))
                "Should have made at least one sub-LLM call during Phase 2")
            (is (= "AI document analyzed successfully" (:summary (:outputs result)))
                "Should return the summary from the sub-LLM call")))))))


(deftest tree-with-map-each-processes-collection
  (testing "Generated tree with map-each iterates over collection and aggregates results"
    (with-provider-context [ctx]
      (let [sub-llm-calls (atom [])]
        (with-redefs [llm/predict
                      (fn [provider module inputs opts]
                        (let [chunk-text (get-in inputs [:chunk :value])]
                          (swap! sub-llm-calls conj {:chunk chunk-text})
                          {:outputs {:chunk-summary (str "Summary of: " chunk-text)}
                           :usage {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30}}))]
          (let [tree (service-logic/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks :as :chunk :into :summaries
                                    :output-schemas {:chunk :string
                                                     :summaries [:vector [:map {:closed true} [:chunk-summary :string]]]}}
                         [:llm {:instruction "Summarize the chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]
                                :output-schemas {:chunk-summary :string}}]]
                        [:final {:keys [:summaries]}]])
                blackboard {:chunks ["chunk1 content" "chunk2 content" "chunk3 content"]}
                result (tp-core/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 15000})]
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            (is (= 3 (count @sub-llm-calls))
                (str "Expected 3 sub-LLM calls, got: " (count @sub-llm-calls)))
            (is (= 3 (count (:summaries (:outputs result))))
                "Should have 3 summaries in output")))))))


(deftest tree-execution-result-includes-per-node-usage
  (testing "When emit-tree! runs sub-LLMs via map-each, each sub-LLM's usage
            propagates into the result so callers can see per-node token costs,
            not just a single aggregate. This is the universal token-tracking
            piece (extends :sheet/node-execution-completed event with :usage)."
    (with-provider-context [ctx]
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 50
                               :completion_tokens 25
                               :total_tokens 75}})]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries
                                  :output-schemas {:chunk :string
                                                   :summaries [:vector [:map {:closed true} [:chunk-summary :string]]]}}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]
                              :output-schemas {:chunk-summary :string}}]]
                      [:final {:keys [:summaries]}]])
              blackboard {:chunks ["a" "b" "c"]}
              result (tp-core/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 15000})
              usage (:usage result)
              by-node (:by-node usage)]
          (is (= :success (:status result))
              (str "Phase 2 should succeed. Status: " (:status result)
                   " error: " (:error result)))
          (is (map? by-node)
              ":usage should contain a :by-node map of per-node token costs")
          (is (>= (count by-node) 3)
              (str ":by-node should have at least 3 entries (one per chunk), got: "
                   (count by-node) " — keys: " (keys by-node)))
          (doseq [[k v] by-node]
            (is (some? (or (:total-tokens v) (:total_tokens v)))
                (str "by-node entry for " k " should have :total-tokens, got: " v)))
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
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 40 :completion_tokens 20 :total_tokens 60}})]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries
                                  :output-schemas {:chunk :string
                                                   :summaries [:vector [:map {:closed true} [:chunk-summary :string]]]}}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]
                              :output-schemas {:chunk-summary :string}}]]
                      [:final {:keys [:summaries]}]])
              blackboard {:chunks ["x" "y" "z"]}
              result (tp-core/execute-tree tree ctx
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
              (str "Expected at least 3 rlm-tree-node-completed events (one per "
                   "map-each iteration), got: " (count rlm-events)))
          (doseq [e rlm-events]
            (is (vector? (:node-path e))
                "Event :node-path should be a vector of path segments")
            (is (some #(= :leaf (:type %)) (:node-path e))
                "Path should include a :leaf segment")
            (is (some #(= :map-each (:type %)) (:node-path e))
                "Path should include a :map-each segment (since this leaf runs under map-each)")
            (is (some? (:usage e))
                "Event should carry :usage"))
          (let [indices (set (map (fn [e]
                                    (->> (:node-path e)
                                         (filter #(= :map-each (:type %)))
                                         first
                                         :index))
                                  rlm-events))]
            (is (= #{0 1 2} indices)
                (str "Expected indices #{0 1 2}, got: " indices))))))))


(deftest rlm-tree-node-completed-events-carry-input-profile
  (testing "Each :sheet/rlm-tree-node-completed event includes :input-profile
            describing per-read-key characteristics (length, word-count,
            line-count). This is the per-node signal future judges/pattern
            matchers will correlate to outcomes."
    (with-provider-context [ctx]
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 30 :completion_tokens 15 :total_tokens 45}})]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries
                                  :output-schemas {:chunk :string
                                                   :summaries [:vector [:map {:closed true} [:chunk-summary :string]]]}}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]
                              :output-schemas {:chunk-summary :string}}]]
                      [:final {:keys [:summaries]}]])
              blackboard {:chunks ["aaa" "longer chunk content" (apply str (repeat 50 "x"))]}
              result (tp-core/execute-tree tree ctx
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
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:chunk-summary "ok"}
                       :usage {:prompt_tokens 25 :completion_tokens 10 :total_tokens 35}})]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:map-each {:from :chunks :as :chunk :into :summaries
                                  :output-schemas {:chunk :string
                                                   :summaries [:vector [:map {:closed true} [:chunk-summary :string]]]}}
                       [:llm {:instruction "Summarize"
                              :reads [:chunk]
                              :writes [:chunk-summary]
                              :output-schemas {:chunk-summary :string}}]]
                      [:final {:keys [:summaries]}]])
              blackboard {:chunks ["c1" "c2"]}
              result (tp-core/execute-tree tree ctx
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


(deftest map-each-max-concurrency-runs-iterations-in-parallel
  (testing "When :max-concurrency N is set on map-each over M items (M > N),
            multiple child iterations are in flight simultaneously"
    (with-provider-context [ctx]
      (let [in-flight (atom 0)
            peak (atom 0)
            call-count (atom 0)
            seen-chunks (atom [])]
        (with-redefs [llm/predict
                      (fn [_provider _module inputs _opts]
                        (if (contains? inputs :chunk)
                          (do
                            (swap! call-count inc)
                            (swap! seen-chunks conj (:chunk inputs))
                            (let [now-in-flight (swap! in-flight inc)]
                              (swap! peak max now-in-flight))
                            (Thread/sleep 200)
                            (swap! in-flight dec)
                            {:outputs {:chunk-summary
                                       (str "Summary of: "
                                            (get-in inputs [:chunk :value]))}
                             :usage {:prompt_tokens 20
                                     :completion_tokens 10
                                     :total_tokens 30}})
                          {:outputs {:summary "canceled prior-context call"}
                           :usage {:prompt_tokens 0
                                   :completion_tokens 0
                                   :total_tokens 0}}))]
          (let [tree (service-logic/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks
                                    :as :chunk
                                    :into :summaries
                                    :max-concurrency 3
                                    :output-schemas {:chunk :string
                                                     :summaries [:vector [:map {:closed true} [:chunk-summary :string]]]}}
                         [:llm {:instruction "Summarize the chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]
                                :output-schemas {:chunk-summary :string}}]]
                        [:final {:keys [:summaries]}]])
                blackboard {:chunks ["c1" "c2" "c3" "c4" "c5" "c6"]}
                result (tp-core/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 30000})]
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            (is (= 6 @call-count)
                (str "Should have made 6 sub-LLM calls, got: " @call-count))
            (is (= (frequencies (:chunks blackboard))
                   (frequencies @seen-chunks))
                (str "Each map item should be dispatched exactly once, got: "
                     (pr-str @seen-chunks)))
            (is (> @peak 1)
                (str "Expected >1 sub-LLM call in flight at peak (max-concurrency=3), "
                     "got peak=" @peak " - map-each is running serially!"))
            (is (>= @peak 3)
                (str "Expected peak in-flight to equal max-concurrency (3), got: " @peak))))))))


(deftest tree-with-code-node-executes-computation
  (testing "Generated tree with code node executes pure computation"
    (with-provider-context [ctx]
      (let [
            tree (service-logic/rlm-dsl->orc-dsl
                   [:sequence
                    [:chunk-document {:from :document :size 10 :into :chunks}]
                    [:final {:keys [:chunks]}]])
            blackboard {:document "Hello world, this is a test document for chunking."}
            result (tp-core/execute-tree tree ctx
                     {:blackboard blackboard
                      :timeout-ms 10000})]
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result) " error: " (:error result)))
        (is (some? (:chunks (:outputs result)))
            "Should have :chunks in output")
        (let [chunks (:chunks (:outputs result))]
          (is (vector? chunks) "Chunks should be a vector")
          (is (pos? (count chunks)) "Should have at least one chunk"))))))

(deftest tree-with-failing-llm-node-propagates-error
  (testing "When LLM node fails in Phase 2, error propagates back correctly"
    (with-provider-context [ctx]
      (with-redefs [llm/predict
                    (fn [provider module inputs opts]
                      (throw (ex-info "Rate limit exceeded" {:status 429})))]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:llm {:instruction "Analyze document"
                             :reads [:document]
                             :writes [:analysis]
                             :output-schemas {:analysis :string}}]
                      [:final {:keys [:analysis]}]])
              blackboard {:document "Test document"}
              result (tp-core/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          (is (= :failure (:status result))
              (str "Expected :failure status, got: " (:status result)))
          (is (some? (:error result))
              "Should have an error message"))))))


(deftest ephemeral-functions-cleaned-up-after-execution
  (testing "Ephemeral functions are removed from registry after tree execution"
    (with-provider-context [ctx]
      (let [initial-count (count @@#'service-logic/ephemeral-fn-registry)]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:chunk-document {:from :document :size 10 :into :chunks}]
                      [:final {:keys [:chunks]}]])
              blackboard {:document "Hello world, this is a test document."}
              result (tp-core/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          (is (= initial-count (count @@#'service-logic/ephemeral-fn-registry))
              (str "Expected ephemeral registry to be cleaned up. "
                   "Initial: " initial-count ", Current: "
                   (count @@#'service-logic/ephemeral-fn-registry))))))))


(deftest sub-llm-usage-is-tracked-and-aggregated
  (testing "Usage from sub-LLM calls is included in final result"
    (with-provider-context [ctx]
      (with-redefs [llm/predict
                    (fn [provider module inputs opts]
                      {:outputs {:summary "Test summary"}
                       :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:llm {:instruction "Summarize"
                             :reads [:document]
                             :writes [:summary]
                             :output-schemas {:summary :string}}]
                      [:final {:keys [:summary]}]])
              blackboard {:document "Test document content"}
              result (tp-core/execute-tree tree ctx
                       {:blackboard blackboard
                        :timeout-ms 10000})]
          (is (= :success (:status result))
              (str "Expected :success, got: " (:status result) " error: " (:error result)))
          (is (some? (:usage result))
              "Result should include :usage from sub-LLM calls")
          (when (:usage result)
            (is (pos? (get-in result [:usage :total-tokens] 0))
                "Usage should have positive total-tokens")))))))

(deftest multiple-sub-llm-calls-aggregate-usage
  (testing "Usage from multiple sub-LLM calls is aggregated"
    (with-provider-context [ctx]
      (let [call-count (atom 0)]
        (with-redefs [llm/predict
                      (fn [provider module inputs opts]
                        (swap! call-count inc)
                        {:outputs {:chunk-summary "Summary of chunk"}
                         :usage {:prompt_tokens 50 :completion_tokens 50 :total_tokens 100}})]
          (let [tree (service-logic/rlm-dsl->orc-dsl
                       [:sequence
                        [:map-each {:from :chunks :as :chunk :into :summaries
                                    :output-schemas {:chunk :string
                                                     :summaries [:vector [:map {:closed true} [:chunk-summary :string]]]}}
                         [:llm {:instruction "Summarize chunk"
                                :reads [:chunk]
                                :writes [:chunk-summary]
                                :output-schemas {:chunk-summary :string}}]]
                        [:final {:keys [:summaries]}]])
                blackboard {:chunks ["chunk1" "chunk2" "chunk3"]}
                result (tp-core/execute-tree tree ctx
                         {:blackboard blackboard
                          :timeout-ms 15000})]
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result) " error: " (:error result)))
            (is (= 3 @call-count)
                (str "Expected 3 sub-LLM calls, got: " @call-count))
            (is (some? (:usage result))
                "Result should include :usage")
            (when (:usage result)
              (is (= 300 (get-in result [:usage :total-tokens] 0))
                  (str "Expected 300 total tokens, got: " (get-in result [:usage :total-tokens]))))))))))


(deftest budget-exhausted-in-phase1-skips-phase2
  (testing "When Phase 1 elapsed >= :timeout-ms, Phase 2 is skipped entirely with clean :failure"
    (with-provider-context [ctx]
      (let [call-count (atom 0)]
        (with-redefs [llm/predict
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            (do (Thread/sleep 150)
                                {:outputs {:code "(emit-tree!
                                                   [:sequence
                                                     [:llm {:instruction \"x\"
                                                            :reads [:doc]
                                                            :writes [:summary]
                                                            :output-schemas {:summary :string}}]
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
                      :timeout-ms 100}
                blackboard {:doc {:key :doc :schema :string :value "hello" :version 1}}
                result (tp-core/execute-repl-researcher-rlm
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
      (with-redefs [llm/predict
                    (let [call-count (atom 0)]
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            {:outputs {:code "(emit-tree!
                                               [:sequence
                                                 [:llm {:instruction \"slow-summarize\"
                                                        :reads [:doc]
                                                        :writes [:summary]
                                                        :output-schemas {:summary :string}}]
                                                 [:final {:keys [:summary]}]])"}
                             :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                            (do (Thread/sleep 800)
                                {:outputs {:summary "would-be-summary"}
                                 :usage {:prompt_tokens 10 :completion_tokens 10 :total_tokens 20}})))))]
        (let [node {:type :repl-researcher
                    :instruction "Summarize the doc"
                    :reads [:doc]
                    :writes [:summary]
                    :rlm {:recursive? false}
                    :max-iterations 1
                    :timeout-ms 500}
              blackboard {:doc {:key :doc :schema :string :value "hello" :version 1}}
              result (tp-core/execute-repl-researcher-rlm
                       node blackboard :openrouter ctx)
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
          (when (pos? (count all-events))
            (let [cancelled-tick-ids (set (map :tick-id all-events))]
              (is (contains? cancelled-tick-ids (:phase2-tick-id result))
                  (str "Cancelled tick-ids " cancelled-tick-ids
                       " should include the Phase 2 child tick " (:phase2-tick-id result))))))))))

(deftest happy-path-response-includes-elapsed-ms-fields
  (testing "Normal Phase 1 + Phase 2 success carries :phase1-elapsed-ms + :phase2-elapsed-ms"
    (with-provider-context [ctx]
      (with-redefs [llm/predict
                    (let [call-count (atom 0)]
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            {:outputs {:code "(emit-tree!
                                               [:sequence
                                                 [:llm {:instruction \"summarize\"
                                                        :reads [:doc]
                                                        :writes [:summary]
                                                        :output-schemas {:summary :string}}]
                                                 [:final {:keys [:summary]}]])"}
                             :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                            {:outputs {:summary "fast summary"}
                             :usage {:prompt_tokens 30 :completion_tokens 20 :total_tokens 50}}))))]
        (let [node {:type :repl-researcher
                    :instruction "Summarize"
                    :reads [:doc]
                    :writes [:summary]
                    :rlm {:recursive? false}
                    :max-iterations 1
                    :timeout-ms 60000}
              blackboard {:doc {:key :doc :schema :string :value "hello" :version 1}}
              result (tp-core/execute-repl-researcher-rlm
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
      (let [tree (service-logic/rlm-dsl->orc-dsl
                   [:sequence
                    [:code {:fn (fn [{:keys [inputs]}]
                                  {:upper (clojure.string/upper-case (:text inputs))})
                            :reads [:text]
                            :writes [:upper]
                            :output-schemas {:upper :string}}]
                    [:final {:keys [:upper]}]])
            blackboard {:text "hello"}
            result (tp-core/execute-tree tree ctx
                     {:blackboard blackboard
                      :timeout-ms 10000})]
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result)
                 " error: " (:error result)))
        (is (= "HELLO" (get-in result [:outputs :upper]))
            "The :code node should read :text=\"hello\" and write :upper=\"HELLO\"")))))


(deftest code-node-with-single-write-and-scalar-return-auto-wraps
  (testing "U7: When :code's :fn returns a scalar (non-map) AND :writes has
            exactly one key, the framework auto-wraps the scalar under that
            write key. The natural Clojure idiom for simple transforms
            (string ops, arithmetic, counts) returns a scalar — making the
            model emit `{:upper x}` instead of just `x` is friction."
    (with-provider-context [ctx]
      (let [tree (service-logic/rlm-dsl->orc-dsl
                   [:sequence
                    [:code {:fn (fn [{:keys [inputs]}]
                                  (clojure.string/upper-case (:text inputs)))
                            :reads [:text]
                            :writes [:upper]
                            :output-schemas {:upper :string}}]
                    [:final {:keys [:upper]}]])
            blackboard {:text "hello"}
            result (tp-core/execute-tree tree ctx
                     {:blackboard blackboard
                      :timeout-ms 10000})]
        (is (= :success (:status result))
            (str "Expected :success, got: " (:status result)
                 " error: " (:error result)))
        (is (= "HELLO" (get-in result [:outputs :upper]))
            "Scalar result \"HELLO\" should be wrapped under :upper")))))


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
          (let [tree (service-logic/rlm-dsl->orc-dsl
                       [:sequence [:final {:keys [:image]}]])
                blackboard {:image "data:image/png;base64,abc123"}
                blackboard-schemas {:image [:string {:field-type :image}]}
                _ (tp-core/execute-tree tree ctx
                    {:blackboard blackboard
                     :blackboard-schemas blackboard-schemas
                     :timeout-ms 10000})]
            (is (= [:string {:field-type :image}]
                   (get @declared-schemas :image))
                (str "Expected :image declared with [:string {:field-type :image}] "
                     "(parent's schema preserved), got: "
                     (pr-str (get @declared-schemas :image))))))))))


(defn- execute-ordered-parallel-probe
  "Run the real generated-tree boundary with a bounded predictor arrival order.
   The response function receives the actual requested field and call ordinal."
  [first-key response-fn]
  (with-provider-context [ctx]
    (let [released (promise)
          calls (atom [])
          arrivals (atom 0)
          gate-timeouts (atom [])]
      (with-redefs [llm/predict
                    (fn [_provider module inputs _opts]
                      (let [requested (mapv :name (:outputs module))
                            k (first requested)]
                        (when (not= k first-key)
                          (when (= ::expired (deref released 5000 ::expired))
                            (swap! gate-timeouts conj k)
                            (throw (ex-info "Parallel test arrival gate expired" {:requested requested}))))
                        (let [ordinal (swap! arrivals inc)
                              outputs (response-fn k ordinal)]
                          (swap! calls conj {:ordinal ordinal :requested requested :module module :inputs inputs :outputs outputs})
                          (when (= k first-key) (deliver released true))
                          {:outputs outputs
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
        (let [tree (service-logic/rlm-dsl->orc-dsl
                     [:sequence
                      [:parallel
                       [:llm {:instruction "left" :reads [:input] :writes [:left-result]
                              :output-schemas {:left-result :string}}]
                       [:llm {:instruction "right" :reads [:input] :writes [:right-result]
                              :output-schemas {:right-result :string}}]]
                      [:final {:keys [:left-result :right-result]}]])
              result (tp-core/execute-tree tree ctx
                       {:blackboard {:input "hello"} :timeout-ms 10000})
              events (h/read-all-events ctx)]
          {:result result :events events :calls (vec (sort-by :ordinal @calls))
           :gate-timeouts @gate-timeouts})))))

(deftest tree-with-parallel-node-executes-children
  (testing "Generated parallel children preserve their contracts in either predictor arrival order"
    (doseq [first-key [:left-result :right-result]]
      (testing (str first-key " arrives first")
        (let [expected {:left-result "left-answer" :right-result "right-answer"}
              {:keys [result events calls gate-timeouts]}
              (execute-ordered-parallel-probe first-key
                (fn [k _ordinal] {k (get expected k)}))
              owners (into {} (for [event events
                                    :when (= :sheet/node-io-set (:event/type event))
                                    k (:writes event)
                                    :when (contains? expected k)]
                                [k (:node-id event)]))
              writes (filter #(= :sheet/execution-value-written (:event/type %)) events)]
          (is (empty? gate-timeouts) "A gate timeout is a harness failure, never an accepted ordering.")
          (is (= [[first-key] [(if (= first-key :left-result) :right-result :left-result)]]
                 (mapv :requested calls)) "Each child calls its predictor once in the forced order.")
          (is (= :success (:status result)) (pr-str result))
          (is (contains? (:outputs result) :left-result))
          (is (contains? (:outputs result) :right-result))
          (is (= expected (select-keys (:outputs result) (keys expected))))
          (is (= 2 (count (set (vals owners)))) "Distinct children own the two declared writes.")
          (is (every? #(= (get owners (:key %)) (:node-id %))
                      (filter #(contains? expected (:key %)) writes))
              "No sibling writes a value owned by the other child.")
          (doseq [[k value] expected]
            (let [owned-writes (filter #(and (= k (:key %)) (= (get owners k) (:node-id %))) writes)]
              (is (= 1 (count owned-writes)) (str "Exactly one canonical write by the owner of " k))
              (is (= value (:value (first owned-writes))) (str "Correct owned value for " k)))))))))



(deftest compile-tree-node-for-sheet-llm-preserves-model
  (testing "When (sheet/llm :model \"X\" ...) is compiled, the projected leaf node has :model \"X\""
    (with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :a :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :b :string))
            compile-fn #'tp-core/compile-tree-node
            tree '(sheet/llm
                    :instruction "test"
                    :reads [:a]
                    :writes [:b]
                    :model "openai/gpt-5.1-chat")
            result (compile-fn ctx sheet-id tree nil)
            leaf-id (:node-id result)
            rm-fn #'rm/get-node
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
            compile-fn #'tp-core/compile-tree-node
            tree '(sheet/llm
                    :instruction "test"
                    :reads [:a]
                    :writes [:b])
            result (compile-fn ctx sheet-id tree nil)
            leaf-id (:node-id result)
            rm-fn #'rm/get-node
            node (rm-fn ctx sheet-id leaf-id)]
        (is (some? node) "Leaf node exists in read-model")
        (is (nil? (:model node))
            "Model is nil when not specified on the sheet/llm form")))))


(deftest execute-tree-short-circuits-on-tick-tree-anomaly
  (testing "When :sheet/tick-tree dispatch returns an anomaly, execute-tree returns :failure within milliseconds (not after the full timeout)"
    (with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "qp1-anomaly-test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))
            original-process-command @#'cp/process-command
            anomaly-on-tick (fn [{:keys [command] :as ctx*}]
                              (if (= :sheet/tick-tree (:command/name command))
                                {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                                 :cognitect.anomalies/message "Invalid Event(s): Failed Schema Validation"
                                 :error/explain {:body {:strengths ["..."]} :path [:body :strengths 0]}}
                                (original-process-command ctx*)))
            tree '(sheet/sequence
                    (sheet/code :fn identity
                                :reads [:input]
                                :writes [:output]
                                :output-schemas {:output :string}))
            short-timeout 3000
            start (System/currentTimeMillis)
            result (with-redefs [cp/process-command anomaly-on-tick]
                     (tp-core/execute-tree
                       tree
                       (assoc ctx :sheet-id sheet-id)
                       {:sandbox-vars {} :blackboard {:input "hi"} :timeout-ms short-timeout}))
            elapsed (- (System/currentTimeMillis) start)]
        (testing "result reports :failure (not :timeout)"
          (is (= :failure (:status result))
              (str "Expected :status :failure when the tick-tree command is rejected; got "
                   (pr-str (:status result))
                   " — this is the silent-hang-then-timeout bug")))
        (testing "result :error names the anomaly cause"
          (is (re-find #"(?i)tick-tree.{0,30}reject|Failed Schema|Invalid Event"
                       (or (:error result) ""))
              (str "Expected :error to identify the rejection; got: " (pr-str (:error result)))))
        (testing "execute-tree returned promptly (no timeout-wait)"
          (is (< elapsed (* 0.8 short-timeout))
              (str "Execute-tree should short-circuit within ~"
                   (long (* 0.8 short-timeout))
                   " ms when the dispatch is rejected; took "
                   elapsed " ms — likely deref'd the timeout, indicating the bug")))))))

(deftest stable-child-running-bookend-is-not-reconstructed-as-failure
  (let [tick-id (random-uuid)
        registered? (atom false)
        completion (doto (promise)
                     (deliver {:status :success
                               :outputs {:summary "eventual"}
                               :trace-id tick-id}))
        events [{:event/type :sheet/tree-tick-started
                 :tick-id tick-id
                 :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00Z")}
                {:event/type :sheet/tree-tick-completed
                 :tick-id tick-id
                 :root-status :running
                 :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00.100Z")}]
        ctx {:event-store ::fake-store :tenant-id (random-uuid)}]
    (with-redefs [es/read (fn [& _] events)
                  service-logic/register-completion!
                  (fn [_]
                    (reset! registered? true)
                    completion)]
      (let [result (tp-core/execute-tree
                    '(sheet/final :keys [:summary])
                    ctx
                    {:stable-tick-id tick-id :timeout-ms 1000})]
        (is @registered? "a running bookend must rejoin the existing child")
        (is (= :success (:status result)) (pr-str result))))))

(deftest running-stable-child-uses-durable-duration-after-await
  (let [tick-id (random-uuid)
        started {:event/type :sheet/tree-tick-started
                 :tick-id tick-id
                 :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00Z")}
        running {:event/type :sheet/tree-tick-completed
                 :tick-id tick-id
                 :root-status :running
                 :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00.100Z")}
        terminal {:event/type :sheet/tree-tick-completed
                  :tick-id tick-id
                  :sheet-id (random-uuid)
                  :root-status :success
                  :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00.250Z")}
        events (atom [started running])
        reads (atom 0)
        second-reconstruction-read (promise)
        completion (promise)
        ctx {:event-store ::fake-store :tenant-id (random-uuid)}]
    (with-redefs [es/read (fn [& _]
                           (let [snapshot @events
                                 read-number (swap! reads inc)]
                             (when (= 3 read-number)
                               (deliver second-reconstruction-read true))
                             snapshot))
                  service-logic/register-completion!
                  (fn [_]
                    (doto (Thread.
                           (fn []
                             @second-reconstruction-read
                             (reset! events [started running terminal])
                             (deliver completion {:status :success
                                                  :outputs {:summary "eventual"}
                                                  :trace-id tick-id})))
                      (.setDaemon true)
                      (.start))
                    completion)]
      (let [result (tp-core/execute-tree
                    '(sheet/final :keys [:summary])
                    ctx
                    {:stable-tick-id tick-id :timeout-ms 1000})]
        (is (= :success (:status result)) (pr-str result))
        (is (= 250 (:duration-ms result)) (pr-str result))))))

(deftest reconstructed-stable-child-retains-durable-duration
  (let [tick-id (random-uuid)
        events [{:event/type :sheet/tree-tick-started
                 :tick-id tick-id
                 :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00Z")}
                {:event/type :sheet/tree-tick-completed
                 :tick-id tick-id
                 :sheet-id (random-uuid)
                 :root-status :success
                 :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00.250Z")}]
        ctx {:event-store ::fake-store :tenant-id (random-uuid)}]
    (with-redefs [es/read (fn [& _] events)]
      (let [result (tp-core/execute-tree
                    '(sheet/final :keys [:summary])
                    ctx
                    {:stable-tick-id tick-id :timeout-ms 1000})]
        (is (= :success (:status result)) (pr-str result))
        (is (= 250 (:duration-ms result)) (pr-str result))))))

(deftest child-reconstruction-paths-share-terminal-status-table
  (let [tick-id (random-uuid)
        sheet-id (random-uuid)
        started {:event/type :sheet/tree-tick-started
                 :tick-id tick-id
                 :sheet-id sheet-id
                 :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00Z")}
        ctx {:event-store ::fake-store :tenant-id (random-uuid)}
        terminal-statuses [:success :failure :tree-generated :partial :timeout :blocked
                           :cancelled]]
    (doseq [root-status terminal-statuses]
      (testing (str root-status " maps identically through both durable reconstruction paths")
        (let [events [started
                      {:event/type :sheet/tree-tick-completed
                       :tick-id tick-id
                       :sheet-id sheet-id
                       :root-status root-status
                       :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00.250Z")}]]
          (with-redefs [es/read (fn [& _] events)
                        rm/get-tick-execution-context (fn [& _] nil)
                        rm/final-values (fn [& _] {})
                        rm/final-sources (fn [& _] {})]
            (let [stable-result (tp-core/execute-tree
                                 '(sheet/final :keys [:summary])
                                 ctx
                                 {:stable-tick-id tick-id :timeout-ms 1000})
                  runtime-result (service-commands/durable-terminal-result ctx tick-id)]
              (is (= root-status (:status stable-result)) (pr-str stable-result))
              (is (= root-status (:status runtime-result)) (pr-str runtime-result)))))))
    (testing ":running is intermediate through both durable reconstruction paths"
      (let [registered? (atom false)
            completion (doto (promise)
                         (deliver {:status :success :trace-id tick-id}))
            events [started
                    {:event/type :sheet/tree-tick-completed
                     :tick-id tick-id
                     :sheet-id sheet-id
                     :root-status :running
                     :event/timestamp (java.time.Instant/parse "2026-01-01T00:00:00.250Z")}]]
        (with-redefs [es/read (fn [& _] events)
                      service-logic/register-completion! (fn [_]
                                                     (reset! registered? true)
                                                     completion)]
          (let [stable-result (tp-core/execute-tree
                               '(sheet/final :keys [:summary])
                               ctx
                               {:stable-tick-id tick-id :timeout-ms 1000})]
            (is @registered? "stable reconstruction must await a running child")
            (is (= :success (:status stable-result)) (pr-str stable-result))
            (is (nil? (service-commands/durable-terminal-result ctx tick-id)))))))))
