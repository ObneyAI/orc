(ns ai.obney.orc.orc-service.recursive-rlm-test
  "Tests for R-1: Core recursive loop (recursive `emit-tree!`).

   Pure deep-module tests for `compute-tree-result-summary` and
   `merge-tree-result-into-sandbox`, plus integration tests proving the loop
   recurs after Phase 2 in recursive mode and preserves terminal behavior in
   non-recursive mode."
  (:require [clojure.test :refer [deftest testing is]]
            [dscloj.core :as dscloj]
            ;; Loading interface.schemas registers the malli command schemas
            ;; (:sheet/create-sheet, :sheet/tick-tree, etc.) that the command
            ;; processor uses during Phase 2 execution. Without this, the tree
            ;; executor's :sheet/create-sheet command fails malli/schema lookup
            ;; and Phase 2 silently aborts before any tree events are emitted.
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as lmdb-store]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test context (mirrors rlm-tree-executor-test pattern)
;; =============================================================================

(defn- create-test-context-with-provider
  "Create test context with a non-nil dscloj-provider so LLM nodes actually execute
   (we replace dscloj/predict with mocks per-test via with-redefs)."
  []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/recursive-rlm-test-" (random-uuid))
        cache (lmdb-store/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :dscloj-provider :openrouter
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
  (when-let [cache (:cache ctx)] (lmdb-store/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

(defmacro with-test-ctx [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context-with-provider)]
     (try ~@body
          (finally (stop-test-context ~ctx-sym)))))

;; =============================================================================
;; compute-tree-result-summary — pure deep module
;; =============================================================================

(defn- mk-node-completion-event
  "Build a fixture :sheet/node-execution-completed event."
  [{:keys [node-id status duration-ms partial-summary]
    :or {duration-ms 100}}]
  (cond-> {:event/type :sheet/node-execution-completed
           :node-id node-id
           :status status
           :duration-ms duration-ms}
    partial-summary (assoc :partial-summary partial-summary)))

(deftest summary-for-success-status
  (testing "all nodes succeeded → summary has no failure or timeout fields"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000001"
          phase2-result {:status :success
                         :outputs {:summary "the answer" :document "input doc"}
                         :duration-ms 4523
                         :trace-id tick-id
                         :usage {:prompt-tokens 100 :completion-tokens 50 :total-tokens 150}}
          tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})
                       (mk-node-completion-event {:node-id (random-uuid) :status :success})
                       (mk-node-completion-event {:node-id (random-uuid) :status :success})]
          tree-raw [:sequence [:llm {:instruction "x"}] [:final {:keys [:summary]}]]
          writes [:summary]
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw tree-raw
                     :writes writes})]
      (is (= tick-id (:tick-id summary)))
      (is (= tree-raw (:tree-raw summary)))
      (is (= :success (:status summary)))
      (is (= 4523 (:elapsed-ms summary)))
      (is (= [:summary] (:outputs-keys summary))
          "outputs-keys lists only the :writes-declared keys, not input blackboard keys like :document")
      (is (= 3 (:nodes-succeeded summary)))
      (is (= 0 (:nodes-failed summary)))
      (is (= 3 (:nodes-total summary)))
      (is (= 150 (get-in summary [:usage :total-tokens])))
      (is (not (contains? summary :failure-indices))
          "no :failure-indices on a fully-successful tree")
      (is (not (contains? summary :failure-reasons))
          "no :failure-reasons on a fully-successful tree")
      (is (not (contains? summary :phase2-elapsed-ms))
          "no timeout-specific fields on a successful tree")
      (is (not (contains? summary :budget-remaining-ms))
          "no timeout-specific fields on a successful tree"))))

(deftest summary-for-partial-status
  (testing ":partial → :failure-indices + :failure-reasons present (from D-008 partial-summary)"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000002"
          map-each-id (random-uuid)
          phase2-result {:status :partial
                         :outputs {:summary "answer from 22 chunks"}
                         :duration-ms 5500
                         :trace-id tick-id
                         :usage {:prompt-tokens 800 :completion-tokens 400 :total-tokens 1200}}
          ;; 24 leaf nodes (22 success + 2 failure) PLUS the map-each completion
          ;; event carrying :partial-summary (verbatim from D-008).
          partial-sum {:total 24 :succeeded 22 :failed 2
                       :failure-indices [7 17]
                       :failure-reasons {7 "Rate limit exhausted"
                                         17 "Schema validation failed"}}
          leaf-events (concat
                        (repeat 22 (mk-node-completion-event {:node-id (random-uuid) :status :success}))
                        (repeat 2 (mk-node-completion-event {:node-id (random-uuid) :status :failure})))
          map-each-event (mk-node-completion-event {:node-id map-each-id
                                                    :status :partial
                                                    :partial-summary partial-sum})
          tick-events (vec (concat leaf-events [map-each-event]))
          tree-raw [:sequence [:map-each {:from :chunks :into :results} [:llm {}]] [:final {}]]
          writes [:summary]
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw tree-raw
                     :writes writes})]
      (is (= :partial (:status summary)))
      (is (= 22 (:nodes-succeeded summary))
          "Counts the 22 successful leaf completions (the map-each itself reports :partial)")
      (is (= 2 (:nodes-failed summary)))
      (is (= [7 17] (:failure-indices summary))
          "failure-indices come VERBATIM from the map-each's :partial-summary")
      (is (= {7 "Rate limit exhausted" 17 "Schema validation failed"}
             (:failure-reasons summary))
          "failure-reasons come VERBATIM from the map-each's :partial-summary")
      (is (not (contains? summary :phase2-elapsed-ms))
          "no timeout-specific fields on a :partial result"))))

(deftest summary-for-failure-status
  (testing ":failure → :succeeded 0, failure detail present"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000003"
          map-each-id (random-uuid)
          phase2-result {:status :failure
                         :outputs {}                  ;; nothing produced
                         :duration-ms 3200
                         :trace-id tick-id
                         :usage {:prompt-tokens 600 :completion-tokens 100 :total-tokens 700}}
          partial-sum {:total 3 :succeeded 0 :failed 3
                       :failure-indices [0 1 2]
                       :failure-reasons {0 "Err A" 1 "Err B" 2 "Err C"}}
          leaf-events (repeat 3 (mk-node-completion-event {:node-id (random-uuid) :status :failure}))
          map-each-event (mk-node-completion-event {:node-id map-each-id
                                                    :status :failure
                                                    :partial-summary partial-sum})
          tick-events (vec (concat leaf-events [map-each-event]))
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw [:sequence [:map-each {} [:llm {}]]]
                     :writes [:result]})]
      (is (= :failure (:status summary)))
      (is (= 0 (:nodes-succeeded summary)))
      (is (= 3 (:nodes-failed summary)))
      (is (= 3 (:nodes-total summary)))
      (is (= [0 1 2] (:failure-indices summary)))
      (is (= {0 "Err A" 1 "Err B" 2 "Err C"} (:failure-reasons summary)))
      (is (= [] (:outputs-keys summary))
          "empty :outputs → no writes-declared keys appear in summary"))))

(deftest summary-for-timeout-status
  (testing ":timeout → phase2-elapsed-ms + budget-remaining-ms + nodes-completed-before-cancel"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000004"
          ;; Phase 2 returns :timeout from D-003's timeout-default
          phase2-result {:status :timeout
                         :error "Tree execution timed out"
                         :duration-ms 4523
                         :trace-id tick-id
                         :phase2-elapsed-ms 4500
                         :budget-remaining-ms 0
                         :usage {:prompt-tokens 200 :completion-tokens 100 :total-tokens 300}}
          ;; 4 leaves managed to complete before cancellation, others didn't get an event
          leaf-events (repeat 4 (mk-node-completion-event {:node-id (random-uuid) :status :success}))
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events leaf-events
                     :tree-raw [:sequence [:map-each {} [:llm {}]] [:final {}]]
                     :writes [:summary]})]
      (is (= :timeout (:status summary)))
      (is (= 4523 (:elapsed-ms summary)))
      (is (= 4500 (:phase2-elapsed-ms summary))
          "phase2-elapsed-ms surfaced from Phase 2 result (D-003)")
      (is (= 0 (:budget-remaining-ms summary))
          "budget-remaining-ms surfaced from Phase 2 result (D-003)")
      (is (= 4 (:nodes-completed-before-cancel summary))
          "nodes-completed-before-cancel = count of leaf node-execution-completed events at cancel time"))))

;; =============================================================================
;; merge-tree-result-into-sandbox — pure deep module
;; =============================================================================

(deftest merge-merges-only-writes-declared-keys
  (testing "Phase 2 outputs filtered to :writes-declared keys; input blackboard keys NOT re-merged"
    (let [sandbox-vars {:generated-tree :some-canonical-form
                        :generated-tree-raw [:sequence [:final {}]]
                        :existing-var "preserved"}
          phase2-result {:status :success
                         ;; :outputs includes both input keys (:document) AND :writes-declared (:summary)
                         :outputs {:document "(original input doc)"
                                   :summary "the result"}
                         :duration-ms 1000
                         :trace-id #uuid "00000000-0000-0000-0000-000000000010"
                         :usage {:total-tokens 100}}
          writes [:summary]
          summary {:tick-id #uuid "00000000-0000-0000-0000-000000000010"
                   :status :success
                   :elapsed-ms 1000
                   :outputs-keys [:summary]}
          new-vars (executor/merge-tree-result-into-sandbox
                     sandbox-vars phase2-result writes summary)]
      (is (= "the result" (:summary new-vars))
          ":summary (:writes-declared) merged into sandbox-vars")
      (is (not (contains? new-vars :document))
          ":document (input blackboard key, NOT in :writes) NOT re-merged")
      (is (= "preserved" (:existing-var new-vars))
          "pre-existing sandbox-vars entries are preserved"))))

(deftest merge-clears-generated-tree-keys
  (testing ":generated-tree and :generated-tree-raw are cleared from sandbox-vars after merge"
    (let [sandbox-vars {:generated-tree :some-form
                        :generated-tree-raw [:sequence [:final {}]]
                        :unrelated "kept"}
          phase2-result {:status :success :outputs {:result "x"}
                         :duration-ms 100 :trace-id (random-uuid)}
          new-vars (executor/merge-tree-result-into-sandbox
                     sandbox-vars phase2-result [:result] {:status :success})]
      (is (not (contains? new-vars :generated-tree))
          ":generated-tree must be cleared so the dispatch doesn't re-fire")
      (is (not (contains? new-vars :generated-tree-raw))
          ":generated-tree-raw must also be cleared")
      (is (= "kept" (:unrelated new-vars))
          "other keys are preserved"))))

(deftest merge-appends-to-tree-results-history
  (testing "first merge: :tree-results nil → [summary1]; second merge appends summary2"
    (let [sandbox-vars-0 {}
          phase2-result-1 {:status :success :outputs {:summary "first"}
                           :duration-ms 100 :trace-id (random-uuid)}
          summary-1 {:tick-id (random-uuid) :status :success :elapsed-ms 100}
          ;; First merge — :tree-results doesn't exist yet
          sandbox-vars-1 (executor/merge-tree-result-into-sandbox
                           sandbox-vars-0 phase2-result-1 [:summary] summary-1)
          _ (is (= [summary-1] (:tree-results sandbox-vars-1))
                ":tree-results created with first entry")
          ;; Second merge — :tree-results should append, not replace
          phase2-result-2 {:status :partial :outputs {:summary "second"}
                           :duration-ms 200 :trace-id (random-uuid)}
          summary-2 {:tick-id (random-uuid) :status :partial :elapsed-ms 200}
          sandbox-vars-2 (executor/merge-tree-result-into-sandbox
                           sandbox-vars-1 phase2-result-2 [:summary] summary-2)]
      (is (= [summary-1 summary-2] (:tree-results sandbox-vars-2))
          ":tree-results appends, preserving order (first → second)")
      (is (= "second" (:summary sandbox-vars-2))
          "last-write-wins on output keys — :summary is the second tree's value")
      (is (= 2 (count (:tree-results sandbox-vars-2)))
          "history accumulates across sequential merges"))))

;; =============================================================================
;; Integration: recursive dispatch in execute-repl-researcher-rlm
;; =============================================================================

(deftest recursive-mode-recurs-after-phase2-and-reaches-final
  (testing "With :rlm {:recursive? true}, the loop recurs after emit-tree! and the model reaches final! on iteration 2"
    (with-test-ctx [ctx]
      (let [outer-call-count (atom 0)
            outer-prompt-snapshots (atom [])
            phase1-module? (fn [module]
                             ;; Phase 1 (outer RLM) module has :outputs [{:name :code ...}]
                             ;; Phase 2 sub-LLM modules have :outputs named after :writes (e.g. :summary)
                             (boolean (some #(= :code (:name %)) (:outputs module))))]
        ;; First Phase 1 call returns an emit-tree! that produces a :summary.
        ;; Phase 2 sub-LLM call gets a stub :summary response.
        ;; Second Phase 1 call (after recur) calls (final! ...) to terminate.
        (with-redefs [dscloj/predict
                      (fn [_provider module inputs _opts]
                        (cond
                          (phase1-module? module)
                          (let [n (swap! outer-call-count inc)
                                instructions (or (:instructions module) "")
                                hist (or (:history inputs) "")]
                            (swap! outer-prompt-snapshots conj
                                   {:instructions instructions :history hist})
                            (if (= 1 n)
                              {:outputs {:code "(emit-tree!
                                                  [:sequence
                                                    [:llm {:instruction \"summarize\"
                                                           :reads [:document]
                                                           :writes [:summary]}]
                                                    [:final {:keys [:summary]}]])"}
                               :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                              ;; Second outer iteration: must see :tree-results in
                              ;; the system prompt's Available Variables. Return
                              ;; final! to terminate.
                              {:outputs {:code "(final! {:summary \"final wrap-up\"
                                                        :iteration-count 2})"}
                               :usage {:prompt_tokens 60 :completion_tokens 30 :total_tokens 90}}))

                          :else
                          ;; Phase 2 sub-LLM — return a mock :summary so the tree
                          ;; can produce outputs that get merged in for iteration 2
                          ;; to inspect.
                          {:outputs {:summary "tree-produced summary"}
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))]
          (let [node {:type :repl-researcher
                      :instruction "Summarize then wrap up"
                      :reads [:document]
                      :writes [:summary :iteration-count]
                      :rlm {:recursive? true}
                      :max-iterations 5}
                blackboard {:document {:key :document
                                       :schema :string
                                       :value "test document"
                                       :version 1}}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)]
            (is (= :success (:status result))
                (str "Expected :success after recur + final!, got: " (:status result)
                     " error: " (:error result)))
            (is (= 2 @outer-call-count)
                "Exactly TWO Phase 1 outer predict calls — original + post-tree recur iteration that calls final!")
            (is (contains? (:outputs result) :summary)
                "Final outputs contain :summary")
            (is (= 2 (get-in result [:outputs :iteration-count]))
                "Final outputs reflect the second iteration's final!")
            ;; Verify :tree-results was visible to the second outer LLM call.
            ;; The variable surfaces in the "Available Variables" section of
            ;; the system prompt (module :instructions).
            (let [second-instructions (:instructions (nth @outer-prompt-snapshots 1))]
              (is (re-find #":tree-results" second-instructions)
                  "Second outer iteration's prompt instructions must surface :tree-results as an available variable"))))))))

(deftest recursive-mode-max-iterations-exhausts-gracefully
  (testing "Model keeps emitting trees without final! → :max-iterations exhausts cleanly"
    (with-test-ctx [ctx]
      (let [call-count (atom 0)]
        ;; Mock predict to ALWAYS return emit-tree! (never final!). Loop must
        ;; exhaust :max-iterations and return :failure with a clear message.
        (with-redefs [dscloj/predict
                      (fn [_provider _module _inputs _opts]
                        (swap! call-count inc)
                        {:outputs {:code "(emit-tree!
                                            [:sequence
                                              [:llm {:instruction \"x\"
                                                     :reads [:document]
                                                     :writes [:summary]}]
                                              [:final {:keys [:summary]}]])"}
                         :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}})]
          (let [node {:type :repl-researcher
                      :instruction "Loop test"
                      :reads [:document]
                      :writes [:summary]
                      :rlm {:recursive? true}
                      :max-iterations 3}  ;; small for fast test
                blackboard {:document {:key :document :schema :string :value "x" :version 1}}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)]
            (is (= :failure (:status result))
                (str "Expected :failure when max-iterations exhausted, got: " (:status result)))
            (is (re-find #"Max iterations reached without final" (str (:error result)))
                "Error message must explicitly call out the missing final!")
            (is (>= @call-count 3)
                "predict must have been called at least :max-iterations times")))))))

(deftest recursive-mode-response-carries-cumulative-timing-fields
  (testing "Successful recursive run carries :cumulative-thinking-ms and :cumulative-tree-ms separately"
    (with-test-ctx [ctx]
      (let [call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            {:outputs {:code "(emit-tree!
                                                [:sequence
                                                  [:llm {:instruction \"go\"
                                                         :reads [:document]
                                                         :writes [:summary]}]
                                                  [:final {:keys [:summary]}]])"}
                             :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                            {:outputs {:code "(final! {:summary \"done\"})"}
                             :usage {:prompt_tokens 60 :completion_tokens 30 :total_tokens 90}})))]
          (let [node {:type :repl-researcher
                      :instruction "Go"
                      :reads [:document]
                      :writes [:summary]
                      :rlm {:recursive? true}
                      :max-iterations 5}
                blackboard {:document {:key :document :schema :string :value "x" :version 1}}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)]
            (is (= :success (:status result)))
            (is (number? (:cumulative-thinking-ms result))
                ":cumulative-thinking-ms must be present on recursive-mode response")
            (is (number? (:cumulative-tree-ms result))
                ":cumulative-tree-ms must be present on recursive-mode response")
            (is (>= (:cumulative-tree-ms result) 0)
                ":cumulative-tree-ms is non-negative (>= 0)")
            (is (>= (:cumulative-thinking-ms result) 0)
                ":cumulative-thinking-ms is non-negative (>= 0)")))))))

;; =============================================================================
;; R-2: Drill-down primitives — end-to-end against the live event store
;; =============================================================================

(deftest recursive-mode-drill-down-primitives-read-event-store-after-recur
  (testing "Iter 2 can call drill-down primitives against the real event store"
    (with-test-ctx [ctx]
      (let [outer-call-count (atom 0)
            phase1-module? (fn [module]
                             (boolean (some #(= :code (:name %)) (:outputs module))))]
        (with-redefs [dscloj/predict
                      (fn [_provider module _inputs _opts]
                        (cond
                          (phase1-module? module)
                          (let [n (swap! outer-call-count inc)]
                            (if (= 1 n)
                              ;; Iter 1: emit a small tree that produces :summary
                              {:outputs {:code "(emit-tree!
                                                  [:sequence
                                                    [:llm {:instruction \"summarize\"
                                                           :reads [:document]
                                                           :writes [:summary]}]
                                                    [:final {:keys [:summary]}]])"}
                               :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                              ;; Iter 2: call EACH drill-down primitive, fold into final!.
                              ;; The code captures structural properties — not raw values
                              ;; that depend on internal ids/timing.
                              {:outputs {:code "(let [d (tree-detail)
                                                       t (tree-trajectory)
                                                       f (tree-failures)]
                                                   (final! {:summary (get (:outputs d) :summary)
                                                            :detail-status (:status d)
                                                            :node-count (count (:nodes d))
                                                            :trajectory-count (count t)
                                                            :failure-count (count f)}))"}
                               :usage {:prompt_tokens 60 :completion_tokens 30 :total_tokens 90}}))

                          :else
                          ;; Phase 2 sub-LLM produces :summary
                          {:outputs {:summary "tree-produced summary"}
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))]
          (let [node {:type :repl-researcher
                      :instruction "Summarize then introspect"
                      :reads [:document]
                      :writes [:summary :detail-status :node-count
                               :trajectory-count :failure-count]
                      :rlm {:recursive? true}
                      :max-iterations 5}
                blackboard {:document {:key :document :schema :string
                                       :value "doc text" :version 1}}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)
                outs (:outputs result)]
            (is (= :success (:status result))
                (str "Expected :success, got: " (:status result)
                     " error: " (:error result)))
            (is (= 2 @outer-call-count)
                "Two outer predict calls — iter 1 emits, iter 2 inspects + final!")
            ;; tree-detail returned an object — these fields prove it's the real
            ;; event-store projection, not a stub.
            (is (= :success (:detail-status outs))
                ":detail-status was pulled out of (tree-detail) result")
            (is (pos? (:node-count outs))
                "(tree-detail) returned a :nodes vector with at least one entry")
            (is (= "tree-produced summary" (:summary outs))
                ":outputs map from (tree-detail) carried the sub-LLM's :summary value")
            ;; trajectory + failures should be queryable. Trajectory may be empty
            ;; if the bookend event isn't yet in the store; failures must be 0 for
            ;; a successful tree.
            (is (number? (:trajectory-count outs))
                "(tree-trajectory) result has a counted shape")
            (is (= 0 (:failure-count outs))
                "(tree-failures) returned empty for a successful tree")))))))
