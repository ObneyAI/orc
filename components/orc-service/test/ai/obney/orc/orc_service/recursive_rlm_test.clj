(ns ai.obney.orc.orc-service.recursive-rlm-test
  "Tests for R-1: Core recursive loop (recursive `emit-tree!`).

   Pure deep-module tests for `compute-tree-result-summary` and
   `merge-tree-result-into-sandbox`, plus integration tests proving the loop
   recurs after Phase 2 in recursive mode and preserves terminal behavior in
   non-recursive mode."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.llm.interface :as llm]
            ;; Loading interface.schemas registers the malli command schemas
            ;; (:sheet/create-sheet, :sheet/tick-tree, etc.) that the command
            ;; processor uses during Phase 2 execution. Without this, the tree
            ;; executor's :sheet/create-sheet command fails malli/schema lookup
            ;; and Phase 2 silently aborts before any tree events are emitted.
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
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
  "Create test context with a non-nil llm-provider so LLM nodes actually execute
   (we replace llm/predict with mocks per-test via with-redefs)."
  []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/recursive-rlm-test-" (random-uuid))
        cache (lmdb-store/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
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
  [{:keys [node-id status duration-ms partial-summary error]
    :or {duration-ms 100}}]
  (cond-> {:event/type :sheet/node-execution-completed
           :node-id node-id
           :status status
           :duration-ms duration-ms}
    partial-summary (assoc :partial-summary partial-summary)
    error (assoc :error error)))

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
;; R-7a: :outputs-previews — value preview surfaced in tree-results summary
;;
;; Observability gap from the image_analysis A-Z-zero audit: a tree completes
;; structurally-successfully but its payload is semantically broken, and the
;; model has no way to notice because the summary only carries :outputs-keys
;; (the KEY names), not a preview of the VALUES. Adding :outputs-previews
;; gives the model visible-by-default evidence of what landed in each key
;; without requiring drill-down primitives.
;; =============================================================================

(deftest summary-includes-map-output-preview
  (testing "map outputs appear in :outputs-previews as {:keys [sorted-keys] :sample-3 [[k v-preview] ...]}"
    (let [report {:title "Q3 Findings" :body "Detailed body text..." :score 4}
          phase2-result {:status :success
                         :outputs {:report report}
                         :duration-ms 100
                         :trace-id (random-uuid)
                         :usage {:total-tokens 50}}
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})]
                     :tree-raw [:sequence [:llm {}] [:final {}]]
                     :writes [:report]})
          preview (get-in summary [:outputs-previews :report])]
      (is (map? preview))
      (is (= [:body :score :title] (:keys preview))
          ":keys lists all map keys in sorted order")
      (is (= 3 (count (:sample-3 preview)))
          ":sample-3 carries up to 3 [k v-preview] pairs")
      (is (every? vector? (:sample-3 preview))
          "each sample entry is a [k v-preview] pair"))))

(deftest summary-includes-scalar-output-preview
  (testing "scalar outputs (numbers, booleans, keywords) are pr-str'd into :outputs-previews"
    (let [phase2-result {:status :success
                         :outputs {:count 42
                                   :ok? true
                                   :tag :final}
                         :duration-ms 100
                         :trace-id (random-uuid)
                         :usage {:total-tokens 50}}
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})]
                     :tree-raw [:sequence [:llm {}] [:final {}]]
                     :writes [:count :ok? :tag]})]
      (is (= "42" (get-in summary [:outputs-previews :count])))
      (is (= "true" (get-in summary [:outputs-previews :ok?])))
      (is (= ":final" (get-in summary [:outputs-previews :tag]))))))

(deftest summary-includes-collection-output-preview
  (testing "vector outputs appear in :outputs-previews as {:count N :sample-3 [first three pr-str'd]}"
    (let [lines (mapv #(str "line-" %) (range 50))   ; 50 strings
          phase2-result {:status :success
                         :outputs {:lines lines}
                         :duration-ms 100
                         :trace-id (random-uuid)
                         :usage {:total-tokens 50}}
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})]
                     :tree-raw [:sequence [:llm {}] [:final {}]]
                     :writes [:lines]})
          preview (get-in summary [:outputs-previews :lines])]
      (is (map? preview)
          "collection preview is a map (not the collection itself)")
      (is (= 50 (:count preview))
          ":count is the full element count, not the truncated sample size")
      (is (= 3 (count (:sample-3 preview)))
          ":sample-3 carries the first 3 entries")
      (is (every? string? (:sample-3 preview))
          "each sample entry is pr-str'd (a string)"))))

(deftest summary-includes-string-output-preview
  (testing "string outputs appear in :outputs-previews truncated to 500 chars with overflow marker"
    (let [long-text (apply str (repeat 100 "ONTARIO POWER AUTHORITY "))   ; 2400 chars
          phase2-result {:status :success
                         :outputs {:answer long-text}
                         :duration-ms 100
                         :trace-id (random-uuid)
                         :usage {:total-tokens 50}}
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})]
                     :tree-raw [:sequence [:llm {}] [:final {}]]
                     :writes [:answer]})
          preview (get-in summary [:outputs-previews :answer])]
      (is (string? preview)
          ":outputs-previews :answer is a string preview")
      (is (= (subs long-text 0 500) (subs preview 0 500))
          "first 500 chars of the preview match the first 500 chars of the value verbatim")
      (is (re-find #"truncated.*full 2400 chars" preview)
          "overflow marker shows the full original length so the model knows there's more"))))

;; =============================================================================
;; T2-Hardening-A: per-leaf failure detail surfaces inline as :failed-leaves
;;
;; Today's summary surfaces map-each per-child failures via :failure-indices
;; + :failure-reasons (sourced from D-008's :partial-summary on the map-each
;; parent's completion event). But non-map-each direct-leaf failures — a
;; :code or :llm leaf throwing or returning :status :failure — surface only
;; as aggregate :nodes-failed N. The model on the next iteration sees the
;; tree failed but not WHICH leaf failed with what error.
;;
;; Fix: reuse the existing rlm-drill-down/tree-failures-from-events function
;; (which already filters direct-leaf failures from tick-events) and surface
;; the result inline as :failed-leaves on the summary. Map-each path stays
;; unchanged — :failed-leaves is the parallel surface for the other branch.
;; Stays factual per the orc principle: descriptive, not prescriptive.
;; =============================================================================

(deftest summary-surfaces-failed-leaves-for-non-map-each-failure
  (testing "T2-Hardening-A: when a non-map-each leaf throws, :failed-leaves carries the node-id + error string inline"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000005"
          good-leaf-id (random-uuid)
          bad-leaf-id (random-uuid)
          phase2-result {:status :failure
                         :outputs {:proposed_schedule {:Monday {:AM "E1" :PM "E2"}}}
                         :duration-ms 2500
                         :trace-id tick-id
                         :usage {:prompt-tokens 200 :completion-tokens 100 :total-tokens 300}}
          tick-events [(mk-node-completion-event {:node-id good-leaf-id
                                                  :status :success})
                       (mk-node-completion-event {:node-id bad-leaf-id
                                                  :status :failure
                                                  :error "Duplicate key: null"})]
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw [:sequence
                                [:llm {:writes [:proposed_schedule]}]
                                [:code {:fn "<inline-fn>"
                                        :writes [:schedule :violations :rationale]}]
                                [:final {:keys [:schedule :violations :rationale]}]]
                     :writes [:schedule :violations :rationale]})]
      (is (= :failure (:status summary)))
      (is (vector? (:failed-leaves summary))
          ":failed-leaves is a vector inline on the summary")
      (is (= 1 (count (:failed-leaves summary)))
          "exactly one direct-leaf failure surfaces (the :code leaf)")
      (let [entry (first (:failed-leaves summary))]
        (is (= bad-leaf-id (:node-id entry))
            ":node-id identifies which leaf failed so the model can correlate to its emitted tree")
        (is (= "Duplicate key: null" (:error entry))
            ":error carries the verbatim runtime error string from the executor")))))

(deftest summary-omits-failed-leaves-on-success
  (testing "T2-Hardening-A: successful trees produce no :failed-leaves field"
    (let [phase2-result {:status :success
                         :outputs {:summary "the answer"}
                         :duration-ms 4500
                         :trace-id (random-uuid)
                         :usage {:total-tokens 50}}
          tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})
                       (mk-node-completion-event {:node-id (random-uuid) :status :success})]
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw [:sequence [:llm {}] [:final {}]]
                     :writes [:summary]})]
      (is (= :success (:status summary)))
      (is (not (contains? summary :failed-leaves))
          "no :failed-leaves field when nothing failed (cond-> conditional add)"))))

(deftest summary-map-each-failures-stay-on-existing-channel
  (testing "T2-Hardening-A: map-each per-child failures surface via :failure-indices/:failure-reasons; :failed-leaves stays absent (no double-reporting)"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000006"
          map-each-id (random-uuid)
          phase2-result {:status :partial
                         :outputs {:summary "from 22 chunks"}
                         :duration-ms 5500
                         :trace-id tick-id
                         :usage {:total-tokens 1200}}
          partial-sum {:total 3 :succeeded 1 :failed 2
                       :failure-indices [1 2]
                       :failure-reasons {1 "Rate limit" 2 "Schema validation"}}
          leaf-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})
                       (mk-node-completion-event {:node-id (random-uuid) :status :failure})
                       (mk-node-completion-event {:node-id (random-uuid) :status :failure})]
          map-each-event (mk-node-completion-event {:node-id map-each-id
                                                    :status :partial
                                                    :partial-summary partial-sum})
          tick-events (vec (concat leaf-events [map-each-event]))
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw [:sequence [:map-each {} [:llm {}]] [:final {}]]
                     :writes [:summary]})]
      (is (= :partial (:status summary)))
      (is (= [1 2] (:failure-indices summary))
          "map-each path stays on :failure-indices (existing channel)")
      (is (= {1 "Rate limit" 2 "Schema validation"} (:failure-reasons summary)))
      (is (vector? (:failed-leaves summary))
          ":failed-leaves is still surfaced because the leaf failure events DO match the direct-leaf filter")
      ;; Important nuance: the underlying drill-down function considers each
      ;; leaf-completion event with :status :failure as a direct-leaf failure
      ;; AS LONG AS it doesn't carry :partial-summary. The map-each parent
      ;; carries :partial-summary so it's filtered out. The map-each CHILDREN's
      ;; completion events DON'T carry :partial-summary themselves, so they
      ;; ARE direct-leaf failures from the data's point of view. The existing
      ;; :failure-indices coverage is a higher-level summary FROM the parent's
      ;; :partial-summary block; :failed-leaves is the per-event view. They
      ;; can coexist (one row per failed child) without inconsistency.
      ;;
      ;; If product-side decides one or the other should suppress, that's a
      ;; downstream rendering concern, not a compute-tree-result-summary one.
      (is (= 2 (count (:failed-leaves summary)))
          "Two leaf-failure events produce two :failed-leaves entries"))))

(deftest summary-failed-leaves-multiple-distinct-errors
  (testing "T2-Hardening-A: when multiple non-map-each leaves fail with different errors, each gets its own entry"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000007"
          leaf-a (random-uuid)
          leaf-b (random-uuid)
          phase2-result {:status :failure
                         :outputs {}
                         :duration-ms 1200
                         :trace-id tick-id
                         :usage {:total-tokens 100}}
          tick-events [(mk-node-completion-event {:node-id leaf-a
                                                  :status :failure
                                                  :error "Could not resolve symbol: foo"})
                       (mk-node-completion-event {:node-id leaf-b
                                                  :status :failure
                                                  :error "Code executor result could not be reconciled with declared :writes"})]
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw [:sequence [:code {:fn "<inline-fn>" :writes [:a]}]
                                          [:code {:fn "<inline-fn>" :writes [:b]}]]
                     :writes [:a :b]})]
      (is (= :failure (:status summary)))
      (is (= 2 (count (:failed-leaves summary))))
      (is (= #{leaf-a leaf-b}
             (set (map :node-id (:failed-leaves summary))))
          "both failing leaves surface, identified by node-id")
      (is (= #{"Could not resolve symbol: foo"
               "Code executor result could not be reconciled with declared :writes"}
             (set (map :error (:failed-leaves summary))))
          "verbatim error strings preserve the executor's diagnostic"))))

;; =============================================================================
;; C-Loop-4: detect-nil-writes — pure deep module
;;
;; A tree can return :status :success with declared writes that are nil or
;; empty-collection (the risk-analysis case: a broken :code aggregator wrote
;; :obligations nil + :penalties nil while still returning :success). The
;; model on the next iteration needs to SEE this signal — but not have it
;; enforced, because "empty" can be the correct answer for some tasks.
;;
;; "Empty" is: nil, "", [], (), {}, #{}.
;; NOT empty: 0, false (could be real answers).
;; =============================================================================

(deftest detect-nil-writes-mixed-input
  (testing "returns only the declared keys whose values are nil/empty"
    (let [outputs {:issues [{:id 1}]                ; populated vector — NOT nil
                   :ambiguities []                  ; empty vector — IS nil
                   :missing nil                     ; nil — IS nil
                   :recommendations "use X"         ; populated string — NOT nil
                   :notes ""                        ; empty string — IS nil
                   :metadata {}                     ; empty map — IS nil
                   :tags #{}                        ; empty set — IS nil
                   :history ()                      ; empty seq — IS nil
                   :score 0                         ; numeric zero — NOT nil
                   :flag? false}                    ; boolean false — NOT nil
          writes-declared [:issues :ambiguities :missing :recommendations
                           :notes :metadata :tags :history :score :flag?]
          nil-writes (executor/detect-nil-writes
                       {:outputs outputs
                        :writes-declared writes-declared})]
      (is (= #{:ambiguities :missing :notes :metadata :tags :history}
             (set nil-writes))
          "nil-writes contains keys with nil / empty-string / empty-vec / empty-map / empty-set / empty-seq values")
      (is (not (some #{:issues :recommendations :score :flag?} nil-writes))
          "populated values + numeric zero + boolean false are NOT considered nil/empty"))))

(deftest detect-nil-writes-no-nil-returns-empty
  (testing "all declared writes populated → returns empty"
    (let [outputs {:summary "the answer"
                   :items [1 2 3]
                   :count 42}
          writes-declared [:summary :items :count]
          nil-writes (executor/detect-nil-writes
                       {:outputs outputs
                        :writes-declared writes-declared})]
      (is (empty? nil-writes)
          "no nil/empty writes → empty result; iteration-history prompt won't surface the signal"))))

(deftest detect-nil-writes-only-checks-declared-keys
  (testing "keys NOT in :writes-declared are ignored even if nil"
    (let [outputs {:declared-and-empty []           ; declared, IS nil
                   :undeclared-and-nil nil          ; undeclared, ignored
                   :input-blackboard-key "doc text"}; undeclared, ignored
          writes-declared [:declared-and-empty]
          nil-writes (executor/detect-nil-writes
                       {:outputs outputs
                        :writes-declared writes-declared})]
      (is (= [:declared-and-empty] nil-writes)
          "only :declared-and-empty surfaces; :undeclared-and-nil is filtered out (it's not the model's declared contract)"))))

(deftest detect-nil-writes-missing-key-counts-as-nil
  (testing "declared key absent from :outputs map → counted as nil"
    (let [outputs {:present "value"}
          writes-declared [:present :absent]
          nil-writes (executor/detect-nil-writes
                       {:outputs outputs
                        :writes-declared writes-declared})]
      (is (= [:absent] nil-writes)
          "declared but absent key is equivalent to nil — the model's contract said it would produce :absent and it didn't"))))

;; =============================================================================
;; C-Loop-4: compute-tree-result-summary surfaces :nil-writes
;;
;; The summary entry is what the model sees on its NEXT iteration via
;; (get-var :tree-results). Adding :nil-writes here is the load-bearing
;; surface — once it's in the summary, the model sees the signal verbatim
;; without any prompt-template change. RED#3 then teaches the model what
;; the signal MEANS via prompt text.
;; =============================================================================

(deftest summary-includes-nil-writes-on-status-success-with-nil-declared-writes
  (testing "the risk-analysis-style case: :status :success but two declared writes came back nil/empty"
    (let [phase2-result {:status :success
                         :outputs {:issues [{:id 1}]
                                   :ambiguities []
                                   :missing nil
                                   :recommendations "use X"}
                         :duration-ms 1000
                         :trace-id (random-uuid)
                         :usage {:total-tokens 100}}
          tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})]
          tree-raw [:sequence [:llm {}] [:final {}]]
          writes [:issues :ambiguities :missing :recommendations]
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw tree-raw
                     :writes writes})]
      (is (= :success (:status summary))
          "status is still :success — the model decides whether 'empty' is correct, the system doesn't force-fail")
      (is (= #{:ambiguities :missing} (set (:nil-writes summary)))
          "both empty-vec :ambiguities and nil :missing surface as nil-writes signals"))))

(deftest summary-omits-nil-writes-when-empty
  (testing "all declared writes populated → :nil-writes is absent or empty"
    (let [phase2-result {:status :success
                         :outputs {:summary "the answer" :items [1 2 3]}
                         :duration-ms 100
                         :trace-id (random-uuid)
                         :usage {:total-tokens 50}}
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :success})]
                     :tree-raw [:sequence [:llm {}] [:final {}]]
                     :writes [:summary :items]})]
      (is (or (not (contains? summary :nil-writes))
              (empty? (:nil-writes summary)))
          "when every declared write is populated, :nil-writes should be absent or empty — no noise to surface to the model"))))

(deftest summary-nil-writes-on-failure-status
  (testing ":nil-writes is computed regardless of :status — :failure can also leave declared writes nil"
    (let [phase2-result {:status :failure
                         :outputs {:partial-data "got this far"}     ; :final-result never written
                         :duration-ms 500
                         :trace-id (random-uuid)
                         :usage {:total-tokens 25}}
          tick-events [(mk-node-completion-event {:node-id (random-uuid) :status :failure})]
          summary (executor/compute-tree-result-summary
                    {:phase2-result phase2-result
                     :tick-events tick-events
                     :tree-raw [:sequence [:llm {}] [:final {}]]
                     :writes [:partial-data :final-result]})]
      (is (= [:final-result] (:nil-writes summary))
          ":final-result was declared but never written — surfaces alongside the failure information"))))

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

(deftest merge-clears-dispatch-marker-but-preserves-tree-raw
  (testing ":generated-tree (dispatch marker) is cleared but :generated-tree-raw is preserved so the final! response + bench EDN capture can record what the model designed"
    ;; The implementation deliberately preserves :generated-tree-raw — the
    ;; final! return path and downstream consumers (notably the bench EDN
    ;; capture) read this to record the tree the model actually produced.
    ;; Bench EDNs like legal-issue-detection_2026-06-01_225737.edn confirm
    ;; :generated-tree-raw is the load-bearing field that records the design.
    (let [sandbox-vars {:generated-tree :some-form
                        :generated-tree-raw [:sequence [:final {}]]
                        :unrelated "kept"}
          phase2-result {:status :success :outputs {:result "x"}
                         :duration-ms 100 :trace-id (random-uuid)}
          new-vars (executor/merge-tree-result-into-sandbox
                     sandbox-vars phase2-result [:result] {:status :success})]
      (is (not (contains? new-vars :generated-tree))
          ":generated-tree (canonical dispatch marker) must be cleared so the dispatch doesn't re-fire on the next iteration")
      (is (= [:sequence [:final {}]] (:generated-tree-raw new-vars))
          ":generated-tree-raw is PRESERVED — the bench EDN capture + final! response read this to record what the model designed")
      (is (= "kept" (:unrelated new-vars))
          "other unrelated keys are preserved"))))

(deftest merge-does-not-erase-preserved-value-with-nil-recovery-output
  (testing "a focused recovery tree cannot erase successful work from an earlier tree"
    (let [new-vars (executor/merge-tree-result-into-sandbox
                    {:preserved-value "PRESERVED"}
                    {:status :success
                     :outputs {:answer "RECOVERED" :preserved-value nil}}
                    [:answer :preserved-value]
                    {:status :success})]
      (is (= "RECOVERED" (:answer new-vars)))
      (is (= "PRESERVED" (:preserved-value new-vars))))))

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

(deftest checkpointed-mode-yields-and-resumes-after-one-iteration
  (testing "A one-iteration quantum returns :running and resumes without replaying completed work"
    (with-test-ctx [ctx]
      (let [calls (atom 0)
            node {:type :repl-researcher
                  :instruction "Remember, then finish"
                  :writes [:summary]
                  :rlm {:recursive? true
                        :checkpointed? true
                        :quantum {:max-iterations 1}}
                  :max-iterations 5}]
        (with-redefs [llm/predict
                      (fn [_provider _module _inputs _opts]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"saved\")"}
                             :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}}
                          2 {:outputs {:code "(final! {:summary (get-var :memo)})"}
                             :usage {:prompt_tokens 4 :completion_tokens 2 :total_tokens 6}}
                          (throw (ex-info "completed iteration was replayed" {}))))]
          (let [yielded (executor/execute-repl-researcher-rlm
                         node {} :openrouter ctx)
                resumed (executor/execute-repl-researcher-rlm
                         node {} :openrouter
                         (assoc ctx :researcher-checkpoint (:checkpoint yielded)))]
            (is (= :running (:status yielded)))
            (is (= 1 (get-in yielded [:checkpoint :next-iteration])))
            (is (= "saved" (get-in yielded [:checkpoint :sandbox-vars :memo])))
            (is (= :success (:status resumed)))
            (is (= "saved" (get-in resumed [:outputs :summary])))
            (is (= 2 @calls)
                "The completed first iteration is not sent to the provider again")))))))

(deftest multi-iteration-quantum-persists-each-completed-iteration
  (with-test-ctx [ctx]
    (let [persisted (atom [])
          calls (atom 0)
          node {:type :repl-researcher
                :instruction "two steps"
                :writes [:summary]
                :rlm {:checkpointed? true :quantum {:max-iterations 2}}
                :max-iterations 4}]
      (with-redefs [llm/predict
                    (fn [& _]
                      (swap! calls inc)
                      {:outputs {:code "(store! :memo :step)"}})]
        (let [result (executor/execute-repl-researcher-rlm
                      node {} :openrouter
                      (assoc ctx :persist-researcher-checkpoint!
                             #(swap! persisted conj %)))]
          (is (= :running (:status result)))
          (is (= [1] (mapv :next-iteration @persisted)))
          (is (= 2 (get-in result [:checkpoint :next-iteration])))
          (is (< (:revision (first @persisted))
                 (get-in result [:checkpoint :revision])))
          (is (= 2 @calls)))))))

(deftest checkpoint-history-distinguishes-created-and-updated-variables
  (with-test-ctx [ctx]
    (let [calls (atom 0)
          node {:type :repl-researcher
                :instruction "update state"
                :writes [:summary]
                :rlm {:checkpointed? true :quantum {:max-iterations 2}}
                :max-iterations 3}]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code (if (= 1 (swap! calls inc))
                                         "(store! :memo 1)"
                                         "(store! :memo 2)")}})]
        (let [result (executor/execute-repl-researcher-rlm node {} :test ctx)
              history (get-in result [:checkpoint :history])]
          (is (= :running (:status result)))
          (is (some #{:memo} (:vars-created (first history))))
          (is (some #{:memo} (:vars-updated (second history))))
          (is (= 2 (get-in result [:checkpoint :sandbox-vars :memo]))))))))

(deftest checkpoint-values-reject-jvm-local-state
  (is (executor/checkpoint-durable-value?
       {:nested [1 "two" #{:three}]
        :id (random-uuid)}))
  (is (false? (executor/checkpoint-durable-value? {:callback (fn [] :local)})))
  (is (false? (executor/checkpoint-durable-value? {:pending (future :value)}))))

(deftest registered-checkpoint-codec-survives-rehydration
  (with-test-ctx [ctx]
    (let [codec {:tag :java-duration
                 :match? #(instance? java.time.Duration %)
                 :encode str
                 :decode #(java.time.Duration/parse %)}
          duration (java.time.Duration/ofSeconds 42)
          durable (executor/encode-checkpoint-value [codec] {:duration duration})
          node {:type :repl-researcher
                :instruction "read restored duration"
                :writes [:summary]
                :rlm {:checkpointed? true}
                :max-iterations 3}
          checkpoint {:version 1
                      :revision 1
                      :next-iteration 1
                      :history []
                      :sandbox-vars durable
                      :var-creation-times {:duration 0}
                      :usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
                      :cumulative-tree-ms 0
                      :iteration-attempts {}
                      :campaign-started-at-ms (System/currentTimeMillis)
                      :campaign-deadline-ms (+ (System/currentTimeMillis) 5000)}]
      (is (= {:duration duration}
             (executor/decode-checkpoint-value [codec] durable)))
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code "(final! {:summary (str (get-var :duration))})"}})]
        (let [result (executor/execute-repl-researcher-rlm
                      node {} :openrouter
                      (assoc ctx
                             :researcher-checkpoint checkpoint
                             :researcher-checkpoint-codecs [codec]))]
          (is (= :success (:status result)) (pr-str result))
          (is (= "PT42S" (get-in result [:outputs :summary]))))))))

(deftest checkpointed-provider-deadline-is-independent
  (with-test-ctx [ctx]
    (let [started (System/nanoTime)
          node {:type :repl-researcher
                :instruction "timeout"
                :writes [:summary]
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 10
                                 :iteration-ms 500
                                 :campaign-ms 2000}
                      :iteration-retry {:max-attempts 1}}}]
      (with-redefs [llm/predict (fn [& _] (Thread/sleep 250) {:outputs {:code "nil"}})]
        (let [result (executor/execute-repl-researcher-rlm node {} :openrouter ctx)
              elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
          (is (= :timeout (:status result)))
          (is (= :provider (:timeout-kind result)))
          (is (< elapsed-ms 200.0)
              (str "provider timeout should return before the 250ms call; elapsed=" elapsed-ms)))))))

(deftest timed-out-iteration-retries-from-the-prior-checkpoint
  (with-test-ctx [ctx]
    (let [calls (atom 0)
          node {:type :repl-researcher
                :instruction "retry"
                :writes [:summary]
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 10 :campaign-ms 2000}
                      :iteration-retry {:max-attempts 3}}
                :max-iterations 3}]
      (with-redefs [llm/predict
                    (fn [& _]
                      (if (= 1 (swap! calls inc))
                        (do (Thread/sleep 200) {:outputs {:code "never"}})
                        {:outputs {:code "(final! {:summary \"recovered\"})"}}))]
        (let [timed-out-attempt (executor/execute-repl-researcher-rlm
                                 node {} :openrouter ctx)
              checkpoint (:checkpoint timed-out-attempt)
              resumed (executor/execute-repl-researcher-rlm
                       node {} :openrouter
                       (assoc ctx :researcher-checkpoint checkpoint))]
          (is (= :running (:status timed-out-attempt)))
          (is (= 0 (:next-iteration checkpoint)))
          (is (= 1 (get-in checkpoint [:iteration-attempts 0])))
          (is (= :success (:status resumed)))
          (is (= "recovered" (get-in resumed [:outputs :summary])))
          (is (= 2 @calls)))))))

(deftest phase2-retries-use-distinct-ticks-under-one-logical-action
  (with-test-ctx [ctx]
    (let [stable-tick-ids (atom [])
          actions (atom {})
          tree-attempts (atom 0)
          tick-id (random-uuid)
          node-id (random-uuid)
          node {:type :repl-researcher
                :instruction "retry child"
                :writes [:summary]
                :rlm {:checkpointed? true
                      :recursive? false
                      :iteration-retry {:max-attempts 3}}
                :max-iterations 3}
          context (assoc ctx
                         :tick-id tick-id
                         :node-id node-id
                         :persist-researcher-action!
                         #(swap! actions assoc (:action-id %) %))]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code "(emit-tree! [:final {:keys [:summary]}])"}})
                    tree-executor/execute-tree
                    (fn [_ _ options]
                      (swap! stable-tick-ids conj (:stable-tick-id options))
                      (if (= 1 (swap! tree-attempts inc))
                        {:status :timeout :error "first child timed out"
                         :trace-id (first @stable-tick-ids) :sheet-id (random-uuid)}
                        {:status :success :outputs {:summary "done"}
                         :trace-id (second @stable-tick-ids) :sheet-id (random-uuid)}))]
        (let [first-result (executor/execute-repl-researcher-rlm
                           node {} :openrouter context)
              resumed (executor/execute-repl-researcher-rlm
                       node {} :openrouter
                       (assoc context
                              :researcher-checkpoint (:checkpoint first-result)
                              :researcher-actions @actions))
              phase2-actions (filter #(= :phase2 (:action-kind %)) (vals @actions))]
          (is (= :running (:status first-result)))
          (is (= :success (:status resumed)) (pr-str resumed))
          (is (= 2 (count @stable-tick-ids)))
          (is (every? uuid? @stable-tick-ids))
          (is (apply not= @stable-tick-ids))
          (is (= 1 (count phase2-actions)))
          (is (= (str tick-id "/" node-id "/0/phase2/1")
                 (:action-id (first phase2-actions)))))))))

(deftest iteration-deadline-bounds-sandbox-and-discards-local-mutations
  (with-test-ctx [ctx]
    (let [node {:type :repl-researcher
                :instruction "bounded sandbox"
                :writes [:summary]
                :mcp-tools ["slow"]
                :tool-contracts {"slow" {:arguments [:map]
                                         :result :any
                                         :checkpoint-safe? true}}
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000 :iteration-ms 20 :campaign-ms 2000}
                      :iteration-retry {:max-attempts 1}}
                :max-iterations 3}
          started (System/nanoTime)]
      (with-redefs [llm/predict (fn [& _]
                                  {:outputs {:code "(do (store! :before :local) (slow {}) (store! :after :late))"}})]
        (let [result (executor/execute-repl-researcher-rlm
                      node {} :openrouter
                      (assoc ctx
                             :tick-id (random-uuid)
                             :node-id (random-uuid)
                             :call-tool-fn
                             (fn [_ _ _] (Thread/sleep 250) {:ok true})))
              elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
          (is (= :timeout (:status result)))
          (is (= :iteration (:timeout-kind result)))
          (is (empty? (:iterations result)))
          (is (< elapsed-ms 200.0)))))))

(deftest completed-provider-action-is-replayed-after-pre-checkpoint-crash
  (with-test-ctx [ctx]
    (let [calls (atom 0)
          recorded (atom nil)
          tick-id (random-uuid)
          node-id (random-uuid)
          node {:type :repl-researcher
                :instruction "provider frontier"
                :writes [:summary]
                :rlm {:checkpointed? true}
                :max-iterations 3}
          base-context (assoc ctx :tick-id tick-id :node-id node-id)]
      (with-redefs [llm/predict
                    (fn [& _]
                      (swap! calls inc)
                      {:outputs {:code "(store! :memo \"from-provider\")"}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (executor/execute-repl-researcher-rlm
         node {} :openrouter
         (assoc base-context :persist-researcher-action! #(reset! recorded %))))
      (let [action @recorded]
        (is (= :provider (:action-kind action)))
        (with-redefs [llm/predict (fn [& _]
                                    (throw (ex-info "provider action was repeated" {})))]
          (let [result (executor/execute-repl-researcher-rlm
                        node {} :openrouter
                        (assoc base-context :researcher-actions
                               {(:action-id action) action}))]
            (is (= :running (:status result)))
            (is (= "from-provider" (get-in result [:checkpoint :sandbox-vars :memo])))
            (is (= (get-in action [:result :provider-latency-ms])
                   (get-in result [:checkpoint :history 0 :provider-latency-ms])))
            (is (= 1 @calls))))))))

(deftest terminal-iteration-checkpoint-survives-before-node-completion
  (with-test-ctx [ctx]
    (let [calls (atom 0)
          persisted (atom nil)
          node {:type :repl-researcher
                :instruction "finish durably"
                :writes [:summary]
                :rlm {:checkpointed? true}
                :max-iterations 2}]
      (with-redefs [llm/predict
                    (fn [& _]
                      (swap! calls inc)
                      {:outputs {:code "(final! {:summary \"finished\"})"}})]
        (let [interrupted (executor/execute-repl-researcher-rlm
                           node {} :openrouter
                           (assoc ctx :persist-researcher-checkpoint!
                                  (fn [checkpoint]
                                    (reset! persisted checkpoint)
                                    (throw (ex-info "simulated process loss" {})))))]
          (is (= :failure (:status interrupted)))
          (is (= :success (get-in @persisted [:terminal-result :status])))))
      (with-redefs [llm/predict (fn [& _]
                                  (throw (ex-info "terminal provider was repeated" {})))]
        (let [resumed (executor/execute-repl-researcher-rlm
                       node {} :openrouter
                       (assoc ctx :researcher-checkpoint @persisted))]
          (is (= :success (:status resumed)) (pr-str resumed))
          (is (= "finished" (get-in resumed [:outputs :summary])))
          (is (= 1 @calls)))))))

(deftest resumed-campaign-keeps-original-absolute-deadline
  (with-test-ctx [ctx]
    (let [calls (atom 0)
          checkpoint {:version 1
                      :next-iteration 1
                      :history [{:code "done"}]
                      :sandbox-vars {}
                      :var-creation-times {}
                      :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}
                      :cumulative-tree-ms 0
                      :campaign-started-at-ms 1
                      :campaign-deadline-ms 2}
          node {:type :repl-researcher
                :instruction "expired"
                :writes [:summary]
                :rlm {:checkpointed? true}
                :max-iterations 5}]
      (with-redefs [llm/predict (fn [& _] (swap! calls inc))]
        (let [result (executor/execute-repl-researcher-rlm
                      node {} :openrouter (assoc ctx :researcher-checkpoint checkpoint))]
          (is (= :timeout (:status result)))
          (is (= :campaign (:timeout-kind result)))
          (is (zero? @calls)))))))

(deftest checkpointed-tools-require-and-receive-idempotency-contract
  (with-test-ctx [ctx]
    (let [seen-context (atom nil)
          tick-id (random-uuid)
          node-id (random-uuid)
          base-node {:type :repl-researcher
                     :instruction "call"
                     :writes [:summary]
                     :mcp-tools ["get-index"]
                     :rlm {:checkpointed? true}
                     :max-iterations 3}
          run (fn [node]
                (with-redefs [llm/predict
                              (fn [& _]
                                {:outputs {:code "(do (get-index {}) (store! :memo :done))"}})]
                  (executor/execute-repl-researcher-rlm
                   node {} :openrouter
                   (assoc ctx
                          :tick-id tick-id
                          :node-id node-id
                          :call-tool-fn
                          (fn [_tool _args tool-context]
                            (reset! seen-context tool-context)
                            {:ok true})))))]
      (let [unsafe-result (run base-node)]
        (is (= :failure (:status unsafe-result)) (pr-str unsafe-result))
        (is (re-find #"not declared :checkpoint-safe"
                     (str (:error unsafe-result)))))
      (let [safe-result (run (assoc base-node :tool-contracts
                                    {"get-index" {:arguments [:map]
                                                  :result :any
                                                  :checkpoint-safe? true}}))]
        (is (= :running (:status safe-result)))
        (is (= (str tick-id "/" node-id "/0/tool/1")
               (:orc/idempotency-key @seen-context)))))))

(deftest configured-unsafe-tool-is-allowed-until-invoked
  (with-test-ctx [ctx]
    (let [node {:type :repl-researcher
                :instruction "finish without tool"
                :writes [:summary]
                :mcp-tools ["unused-effect"]
                :tool-contracts {"unused-effect" {:arguments [:map] :result :any}}
                :rlm {:checkpointed? true}
                :max-iterations 2}]
      (with-redefs [llm/predict (fn [& _]
                                  {:outputs {:code "(final! {:summary \"done\"})"}})]
        (let [result (executor/execute-repl-researcher-rlm
                      node {} :openrouter ctx)]
          (is (= :success (:status result)) (pr-str result))
          (is (= "done" (get-in result [:outputs :summary]))))))))

(deftest completed-phase2-child-is-not-repeated-after-resume
  (with-test-ctx [ctx]
    (let [phase1-calls (atom 0)
          phase2-calls (atom 0)
          phase1-module? #(boolean (some (fn [output] (= :code (:name output)))
                                         (:outputs %)))
          node {:type :repl-researcher
                :instruction "research then finish"
                :reads [:document]
                :writes [:summary]
                :rlm {:recursive? true :checkpointed? true}
                :max-iterations 4}
          blackboard {:document {:key :document :schema :string :value "x" :version 1}}]
      (with-redefs [llm/predict
                    (fn [_provider module _inputs _options]
                      (if (phase1-module? module)
                        (case (swap! phase1-calls inc)
                          1 {:outputs {:code "(emit-tree! [:sequence [:llm {:instruction \"summarize\" :reads [:document] :writes [:summary] :output-schemas {:summary :string}}] [:final {:keys [:summary]}]])"}}
                          2 {:outputs {:code "(final! {:summary (get-var :summary)})"}})
                        (do (swap! phase2-calls inc)
                            {:outputs {:summary "child-result"}})))]
        (let [yielded (executor/execute-repl-researcher-rlm
                       node blackboard :openrouter ctx)
              resumed (executor/execute-repl-researcher-rlm
                       node blackboard :openrouter
                       (assoc ctx :researcher-checkpoint (:checkpoint yielded)))]
          (is (= :running (:status yielded)))
          (is (uuid? (get-in yielded [:checkpoint :history 0 :child-trace-id])))
          (is (= :success (:status resumed)))
          (is (= "child-result" (get-in resumed [:outputs :summary])))
          (is (= 1 @phase2-calls)
              "the completed child tree is incorporated from the checkpoint, not run again"))))))

(deftest completed-phase2-action-survives-pre-checkpoint-crash
  (with-test-ctx [ctx]
    (let [phase1-calls (atom 0)
          phase2-calls (atom 0)
          actions (atom {})
          first-result (atom nil)
          phase1-module? #(boolean (some (fn [output] (= :code (:name output)))
                                         (:outputs %)))
          node {:type :repl-researcher
                :instruction "child frontier"
                :reads [:document]
                :writes [:summary]
                :rlm {:checkpointed? true}
                :max-iterations 3}
          blackboard {:document {:key :document :schema :string :value "x" :version 1}
          }
          context (assoc ctx
                         :tick-id (random-uuid)
                         :node-id (random-uuid)
                         :persist-researcher-action!
                         (fn [action] (swap! actions assoc (:action-id action) action)))]
      (with-redefs [llm/predict
                    (fn [_ module _ _]
                      (if (phase1-module? module)
                        (do (swap! phase1-calls inc)
                            {:outputs {:code "(emit-tree! [:sequence [:llm {:instruction \"x\" :reads [:document] :writes [:summary] :output-schemas {:summary :string}}] [:final {:keys [:summary]}]])"}})
                        (do (swap! phase2-calls inc)
                            {:outputs {:summary "once"}})))]
        (reset! first-result
                (executor/execute-repl-researcher-rlm node blackboard :openrouter context)))
      (is (= :running (:status @first-result)) (pr-str @first-result))
      (is (= #{:provider :phase2} (set (map :action-kind (vals @actions)))))
      (with-redefs [llm/predict (fn [& _]
                                  (throw (ex-info "completed work repeated" {})))]
        (let [result (executor/execute-repl-researcher-rlm
                      node blackboard :openrouter
                      (assoc context
                             :persist-researcher-action! nil
                             :researcher-actions @actions))]
          (is (= :running (:status result)))
          (is (= "once" (get-in result [:checkpoint :sandbox-vars :summary])))
          (is (= 1 @phase1-calls))
          (is (= 1 @phase2-calls)))))))

(deftest completed-phase2-tick-is-rejoined-before-action-recording
  (with-test-ctx [ctx]
    (let [phase1-calls (atom 0)
          phase2-calls (atom 0)
          actions (atom {})
          phase1-module? #(boolean (some (fn [output] (= :code (:name output)))
                                         (:outputs %)))
          node {:type :repl-researcher
                :instruction "recover child completion"
                :reads [:document]
                :writes [:summary]
                :rlm {:checkpointed? true}
                :max-iterations 3}
          blackboard {:document {:key :document :schema :string :value "x" :version 1}}
          base-context (assoc ctx :tick-id (random-uuid) :node-id (random-uuid))]
      (with-redefs [llm/predict
                    (fn [_ module _ _]
                      (if (phase1-module? module)
                        (do
                          (swap! phase1-calls inc)
                          {:outputs {:code "(emit-tree! [:sequence [:llm {:instruction \"x\" :reads [:document] :writes [:summary] :output-schemas {:summary :string}}] [:final {:keys [:summary]}]])"}})
                        (do
                          (swap! phase2-calls inc)
                          {:outputs {:summary "durable-child"}
                           :usage {:prompt_tokens 3 :completion_tokens 1 :total_tokens 4}})))]
        (let [interrupted
              (executor/execute-repl-researcher-rlm
               node blackboard :openrouter
               (assoc base-context :persist-researcher-action!
                      (fn [action]
                        (if (= :phase2 (:action-kind action))
                          (throw (ex-info "simulated crash before parent action record" {}))
                          (swap! actions assoc (:action-id action) action)))))]
          (is (= :failure (:status interrupted)) (pr-str interrupted))))
      (with-redefs [llm/predict (fn [& _]
                                  (throw (ex-info "provider or child was repeated" {})))]
        (let [resumed (executor/execute-repl-researcher-rlm
                       node blackboard :openrouter
                       (assoc base-context
                              :researcher-actions @actions
                              :persist-researcher-action!
                              #(swap! actions assoc (:action-id %) %)))]
          (is (= :running (:status resumed)) (pr-str resumed))
          (is (= "durable-child" (get-in resumed [:checkpoint :sandbox-vars :summary])))
          (is (= 4 (get-in (first (filter #(= :phase2 (:action-kind %))
                                           (vals @actions)))
                           [:result :usage :total-tokens])))
          (is (= 1 @phase1-calls))
          (is (= 1 @phase2-calls)))))))

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
        (with-redefs [llm/predict
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
                                                           :writes [:summary]
                                                           :output-schemas {:summary :string}}]
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
        (with-redefs [llm/predict
                      (fn [_provider _module _inputs _opts]
                        (swap! call-count inc)
                        {:outputs {:code "(emit-tree!
                                            [:sequence
                                              [:llm {:instruction \"x\"
                                                     :reads [:document]
                                                     :writes [:summary]
                                                     :output-schemas {:summary :string}}]
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
        (with-redefs [llm/predict
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= 1 n)
                            {:outputs {:code "(emit-tree!
                                                [:sequence
                                                  [:llm {:instruction \"go\"
                                                         :reads [:document]
                                                         :writes [:summary]
                                                         :output-schemas {:summary :string}}]
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
        (with-redefs [llm/predict
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
                                                           :writes [:summary]
                                                           :output-schemas {:summary :string}}]
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

;; =============================================================================
;; T2-Hardening-A: end-to-end — Phase 2 :code throw surfaces inline as
;; :failed-leaves on the :tree-results entry the next Phase-1 iteration sees.
;; =============================================================================

(deftest recursive-mode-failed-leaves-surface-on-tree-results
  (testing "T2-Hardening-A: when a Phase-2 :code leaf throws, the next iteration's :tree-results entry carries :failed-leaves with :node-id + :error"
    (with-test-ctx [ctx]
      (let [outer-call-count (atom 0)
            phase1-module? (fn [module]
                             (boolean (some #(= :code (:name %)) (:outputs module))))]
        (with-redefs [llm/predict
                      (fn [_provider module _inputs _opts]
                        (cond
                          (phase1-module? module)
                          (let [n (swap! outer-call-count inc)]
                            (if (= 1 n)
                              ;; Iter 1: emit a tree with a :code :fn that
                              ;; intentionally throws — exercises the same
                              ;; runtime path a model-authored buggy
                              ;; validator hits in production.
                              {:outputs {:code "(emit-tree!
                                                  [:sequence
                                                    [:code {:fn (fn [_]
                                                                  (throw (ex-info \"intentional fault\" {})))
                                                            :reads []
                                                            :writes [:produced]
                                                            :output-schemas {:produced :string}}]
                                                    [:final {:keys [:produced]}]])"}
                               :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                              ;; Iter 2: read :tree-results, project its
                              ;; failed-leaf shape into declared outputs so
                              ;; the test can assert via (:outputs result).
                              {:outputs {:code "(let [tr (get-var :tree-results)
                                                       e (first tr)
                                                       fl (:failed-leaves e)
                                                       first-fl (first fl)]
                                                   (final!
                                                     {:tr-count (count tr)
                                                      :tr-status (:status e)
                                                      :tr-failed-leaf-count (count fl)
                                                      :tr-failed-leaf-error (:error first-fl)
                                                      :tr-failed-leaf-has-node-id?
                                                      (some? (:node-id first-fl))}))"}
                               :usage {:prompt_tokens 60 :completion_tokens 30 :total_tokens 90}}))

                          :else
                          ;; No Phase 2 sub-LLM in this test — the :code node
                          ;; throws before any LLM child runs.
                          {:outputs {} :usage {}}))]
          (let [node {:type :repl-researcher
                      :instruction "Intentional fault test"
                      :reads []
                      :writes [:tr-count :tr-status :tr-failed-leaf-count
                               :tr-failed-leaf-error :tr-failed-leaf-has-node-id?]
                      :rlm {:recursive? true}
                      :max-iterations 5}
                blackboard {}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)
                outs (:outputs result)]
            (is (= :success (:status result))
                (str "Expected :success after iter 2 final!, got: " (:status result)
                     " error: " (:error result)))
            (is (= 2 @outer-call-count)
                "Two Phase 1 outer predict calls — first emits, second inspects after recur")
            (is (>= (:tr-count outs) 1)
                ":tree-results visible to iter 2 has at least one entry (the iter-1 failed tree)")
            (is (= :failure (:tr-status outs))
                "the iter-1 tree's summary records :status :failure (its :code leaf threw)")
            (is (>= (:tr-failed-leaf-count outs) 1)
                "T2-Hardening-A: at least one :failed-leaves entry inline on the summary")
            (is (string? (:tr-failed-leaf-error outs))
                ":error on the failed-leaf carries the runtime error string verbatim")
            (is (re-find #"(?i)intentional fault" (or (:tr-failed-leaf-error outs) ""))
                "the error string includes the model-authored throw's message")
            (is (true? (:tr-failed-leaf-has-node-id? outs))
                ":node-id is populated on the failed-leaf entry")))))))
