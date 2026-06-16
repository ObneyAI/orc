(ns ai.obney.orc.orc-service.recursive-rlm-drill-down-test
  "Tests for R-2: Drill-down primitives. Pure unit tests on the query
   functions in `core/rlm-drill-down`, plus integration tests proving the
   SCI sandbox wires them only when :rlm {:recursive? true}."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.rlm-drill-down :as drill]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]))

;; =============================================================================
;; Fixture helpers — build event vectors that mirror the real shapes
;; =============================================================================

(defn- mk-tick-started [tick-id ts]
  {:event/type :sheet/tree-tick-started
   :event/timestamp ts
   :tick-id tick-id})

(defn- mk-tick-completed [tick-id ts root-status outputs]
  {:event/type :sheet/tree-tick-completed
   :event/timestamp ts
   :tick-id tick-id
   :root-status root-status
   :outputs outputs})

(defn- mk-node-completed [tick-id node-id status & {:keys [writes duration-ms usage partial-summary]
                                                    :or {writes {} duration-ms 100}}]
  (cond-> {:event/type :sheet/node-execution-completed
           :tick-id tick-id
           :node-id node-id
           :status status
           :writes writes
           :duration-ms duration-ms}
    usage (assoc :usage usage)
    partial-summary (assoc :partial-summary partial-summary)))

(defn- mk-rlm-tree-node-completed [tick-id node-id & {:keys [node-path usage input-profile]
                                                     :or {node-path [{:type :leaf}]
                                                          usage {:total-tokens 50}}}]
  (cond-> {:event/type :sheet/rlm-tree-node-completed
           :tick-id tick-id
           :node-id node-id
           :node-path node-path
           :usage usage}
    input-profile (assoc :input-profile input-profile)))

(defn- mk-bookend [tick-id ts trajectory total-usage]
  {:event/type :sheet/rlm-tree-execution-completed
   :event/timestamp ts
   :tick-id tick-id
   :trajectory trajectory
   :total-usage total-usage
   :task-fingerprint nil})

;; =============================================================================
;; tree-detail-from-events — :success case
;; =============================================================================

(deftest tree-detail-for-success-has-basic-shape
  (testing "Successful tree: detail includes tick-id, status, outputs, tree-raw, and per-node entries"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000a01"
          leaf-id (random-uuid)
          tree-raw [:sequence [:llm {:writes [:summary]}] [:final {:keys [:summary]}]]
          events [(mk-tick-started tick-id (java.time.Instant/parse "2026-05-20T10:00:00Z"))
                  (mk-node-completed tick-id leaf-id :success
                                     :writes {:summary "the answer"}
                                     :duration-ms 800
                                     :usage {:total-tokens 50})
                  (mk-rlm-tree-node-completed tick-id leaf-id
                                              :node-path [{:type :leaf :node-id leaf-id}]
                                              :usage {:total-tokens 50})
                  (mk-tick-completed tick-id (java.time.Instant/parse "2026-05-20T10:00:01Z")
                                     :success {:summary "the answer"})
                  (mk-bookend tick-id (java.time.Instant/parse "2026-05-20T10:00:01Z")
                              [{:event-type :sheet/tree-tick-started}
                               {:event-type :sheet/node-execution-completed}
                               {:event-type :sheet/tree-tick-completed}]
                              {:total-tokens 50})]
          detail (drill/tree-detail-from-events events tree-raw)]
      (is (some? detail) "Returns a map (not nil)")
      (is (= tick-id (:tick-id detail)))
      (is (= :success (:status detail)))
      (is (= tree-raw (:tree-raw detail)))
      (is (= {:summary "the answer"} (:outputs detail)))
      (is (vector? (:nodes detail)) ":nodes is a vector")
      (is (= 1 (count (:nodes detail))) "one leaf, one entry in :nodes")
      (let [node (first (:nodes detail))]
        (is (= leaf-id (:node-id node)))
        (is (= :success (:status node)))
        (is (= 800 (:duration-ms node)))
        (is (= {:summary "the answer"} (:writes node)))))))

(deftest tree-detail-for-partial-surfaces-partial-summary
  (testing "Partial tree: detail includes :partial-summary on the map-each node entry"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000a02"
          map-id (random-uuid)
          leaf1 (random-uuid)
          leaf2 (random-uuid)
          leaf3 (random-uuid)
          partial-sum {:total 3 :succeeded 2 :failed 1
                       :failure-indices [1]
                       :failure-reasons {1 "Rate limit exhausted"}}
          tree-raw [:sequence
                    [:map-each {:from :chunks :as :chunk :into :results}
                     [:llm {:writes [:summary]}]]
                    [:final {:keys [:results]}]]
          events [(mk-tick-started tick-id (java.time.Instant/parse "2026-05-20T11:00:00Z"))
                  (mk-node-completed tick-id leaf1 :success :duration-ms 200)
                  (mk-node-completed tick-id leaf2 :failure :duration-ms 150)
                  (mk-node-completed tick-id leaf3 :success :duration-ms 250)
                  (mk-node-completed tick-id map-id :partial
                                     :partial-summary partial-sum
                                     :duration-ms 800
                                     :writes {:results [{} {}]})
                  (mk-tick-completed tick-id (java.time.Instant/parse "2026-05-20T11:00:01Z")
                                     :partial {:results [{} {}]})
                  (mk-bookend tick-id (java.time.Instant/parse "2026-05-20T11:00:01Z")
                              [] {:total-tokens 600})]
          detail (drill/tree-detail-from-events events tree-raw)]
      (is (= :partial (:status detail)))
      (is (= 4 (count (:nodes detail))) "3 leaves + 1 map-each parent = 4 node entries")
      ;; Find the map-each node entry by node-id
      (let [map-entry (first (filter #(= map-id (:node-id %)) (:nodes detail)))]
        (is (some? map-entry))
        (is (= :partial (:status map-entry)))
        (is (= partial-sum (:partial-summary map-entry))
            ":partial-summary carries verbatim from the map-each's completion event")))))

;; =============================================================================
;; tree-failures-from-events
;; =============================================================================

(deftest tree-failures-returns-empty-when-everything-succeeded
  (testing "All-success tree → tree-failures returns []"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000b01"
          leaf-id (random-uuid)
          events [(mk-node-completed tick-id leaf-id :success)
                  (mk-tick-completed tick-id (java.time.Instant/now) :success {})]]
      (is (= [] (drill/tree-failures-from-events events))
          "No failures → empty vector (not nil) so consumers can use seq/count uniformly"))))

(deftest tree-failures-extracts-direct-leaf-failures
  (testing "Failed leaves → vector of {:node-id :error :input-profile?} entries"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000b02"
          leaf-ok (random-uuid)
          leaf-fail (random-uuid)
          events [(mk-node-completed tick-id leaf-ok :success)
                  (assoc (mk-node-completed tick-id leaf-fail :failure)
                         :error "Rate limit exhausted")
                  (mk-rlm-tree-node-completed tick-id leaf-fail
                                              :input-profile {:chunk {:type :length :length 8000}})
                  (mk-tick-completed tick-id (java.time.Instant/now) :failure {})]
          failures (drill/tree-failures-from-events events)]
      (is (= 1 (count failures)) "Exactly one failure entry")
      (let [f (first failures)]
        (is (= leaf-fail (:node-id f)))
        (is (= :failure (:status f)))
        (is (= "Rate limit exhausted" (:error f)))
        (is (= {:chunk {:type :length :length 8000}} (:input-profile f))
            "Joins input-profile from the matching :sheet/rlm-tree-node-completed event")))))

(deftest tree-failures-extracts-map-each-partial-summary-failures
  (testing "Map-each :partial-summary → expands to per-index failure entries with input-profiles"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000b03"
          map-id (random-uuid)
          ip-7 {:chunk {:type :length :length 6000}}
          ip-17 {:chunk {:type :length :length 12400}}
          partial-sum {:total 24 :succeeded 22 :failed 2
                       :failure-indices [7 17]
                       :failure-reasons {7 "Rate limit"
                                         17 "Schema validation"}
                       :failure-input-profiles {7 ip-7 17 ip-17}}
          events [(mk-node-completed tick-id map-id :partial
                                     :partial-summary partial-sum)
                  (mk-tick-completed tick-id (java.time.Instant/now) :partial {})]
          failures (drill/tree-failures-from-events events)]
      (is (= 2 (count failures)) "Two failures from the :partial-summary")
      (let [f7 (first (filter #(= 7 (:index %)) failures))
            f17 (first (filter #(= 17 (:index %)) failures))]
        (is (= "Rate limit" (:error f7)))
        (is (= ip-7 (:input-profile f7))
            "Joins :failure-input-profiles entry to the failure")
        (is (= "Schema validation" (:error f17)))
        (is (= ip-17 (:input-profile f17)))))))

;; =============================================================================
;; tree-trajectory-from-events
;; =============================================================================

(deftest tree-trajectory-comes-from-bookend-event
  (testing "Trajectory: chronological per-event log surfaced from the bookend event's :trajectory"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000c01"
          trajectory [{:event-type :sheet/tree-tick-started
                       :timestamp "2026-05-20T12:00:00Z"}
                      {:event-type :sheet/node-execution-started
                       :timestamp "2026-05-20T12:00:00.500Z"
                       :node-id (random-uuid)}
                      {:event-type :sheet/node-execution-completed
                       :timestamp "2026-05-20T12:00:01.200Z"
                       :node-id (random-uuid)
                       :status :success}
                      {:event-type :sheet/tree-tick-completed
                       :timestamp "2026-05-20T12:00:01.300Z"}]
          events [(mk-tick-completed tick-id (java.time.Instant/now) :success {})
                  (mk-bookend tick-id (java.time.Instant/now) trajectory {:total-tokens 75})]
          result (drill/tree-trajectory-from-events events)]
      (is (vector? result))
      (is (= 4 (count result)))
      (is (= (first trajectory) (first result))
          "Entries are passed through verbatim from the bookend event"))))

(deftest tree-trajectory-empty-when-no-bookend
  (testing "No bookend event → returns nil (caller can decide to fallback)"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000c02"
          events [(mk-tick-completed tick-id (java.time.Instant/now) :success {})]]
      (is (nil? (drill/tree-trajectory-from-events events))))))

;; =============================================================================
;; node-output-from-events
;; =============================================================================

(deftest node-output-returns-writes-for-matching-node
  (testing "Returns the :writes map of the node's :sheet/node-execution-completed event"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000d01"
          leaf-a (random-uuid)
          leaf-b (random-uuid)
          events [(mk-node-completed tick-id leaf-a :success :writes {:summary "A's answer"})
                  (mk-node-completed tick-id leaf-b :success :writes {:summary "B's answer"})]]
      (is (= {:summary "A's answer"} (drill/node-output-from-events events leaf-a)))
      (is (= {:summary "B's answer"} (drill/node-output-from-events events leaf-b))))))

(deftest node-output-returns-nil-for-unknown-node
  (testing "Unknown node-id → nil (not exception)"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000d02"
          events [(mk-node-completed tick-id (random-uuid) :success)]]
      (is (nil? (drill/node-output-from-events events (random-uuid)))))))

(deftest node-output-surfaces-raw-response-for-parse-failures
  (testing "A parse-failure completion carrying :raw-response returns the
            verbatim raw text alongside the (nil-filled) writes and error"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000d03"
          failed-leaf (random-uuid)
          ok-leaf (random-uuid)
          raw "# Briefing Report\n\nFree-form markdown with no field markers..."
          events [(-> (mk-node-completed tick-id failed-leaf :failure
                                         :writes {:report nil})
                      (assoc :raw-response raw
                             :error "LLM output unparseable for keys [:report]"))
                  (mk-node-completed tick-id ok-leaf :success
                                     :writes {:summary "fine"})]
          result (drill/node-output-from-events events failed-leaf)]
      (is (= raw (:raw-response result)) "full verbatim raw response, untruncated")
      (is (= {:report nil} (:writes result)))
      (is (= "LLM output unparseable for keys [:report]" (:error result)))
      ;; success-node shape is unchanged
      (is (= {:summary "fine"} (drill/node-output-from-events events ok-leaf))))))

;; =============================================================================
;; node-input-profile-from-events
;; =============================================================================

(deftest node-input-profile-returns-profile-from-rlm-event
  (testing "Returns :input-profile from the matching :sheet/rlm-tree-node-completed event"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000e01"
          leaf-id (random-uuid)
          profile {:chunk {:type :length :length 8000 :word-count 1200}}
          events [(mk-node-completed tick-id leaf-id :success)
                  (mk-rlm-tree-node-completed tick-id leaf-id
                                              :input-profile profile)]]
      (is (= profile (drill/node-input-profile-from-events events leaf-id))))))

(deftest node-input-profile-returns-nil-when-absent
  (testing "Node without an :sheet/rlm-tree-node-completed event → nil"
    (let [tick-id #uuid "00000000-0000-0000-0000-000000000e02"
          leaf-id (random-uuid)
          ;; Only :sheet/node-execution-completed, no RLM event
          events [(mk-node-completed tick-id leaf-id :success)]]
      (is (nil? (drill/node-input-profile-from-events events leaf-id))))))

;; =============================================================================
;; Integration: SCI sandbox wiring — primitives are gated on :recursive?
;; =============================================================================
;;
;; These tests stay focused on the BINDING-LEVEL contract: the 5 drill-down
;; primitives are exposed ONLY when :recursive? true. Full event-store wiring
;; with a live recursion is exercised in recursive_rlm_test.clj.

(defn- eval-in-sandbox
  "Build an RLM context (no real provider needed — these tests only call
   drill-down primitives, never `llm`) and evaluate `code-string` inside it."
  [opts code-string]
  (let [ctx (rlm-sandbox/build-rlm-context (merge {:provider :openrouter
                                                   :blackboard {}
                                                   :declared-writes [:summary]}
                                                  opts))]
    (rlm-sandbox/execute-rlm-code ctx code-string)))

(deftest drill-down-primitives-not-bound-in-non-recursive-mode
  (testing "Calling (tree-detail) without :recursive? true raises unresolved symbol"
    (let [r (eval-in-sandbox {:recursive? false} "(tree-detail)")]
      (is (some? (:error r)))
      (is (re-find #"tree-detail" (str (:error r)))
          (str "Expected error mentioning tree-detail, got: " (:error r))))))

(deftest each-drill-down-primitive-unresolved-when-non-recursive
  (testing "All 5 drill-down primitives are unresolved when :recursive? false"
    (doseq [prim ["(tree-detail)"
                  "(tree-trajectory)"
                  "(tree-failures)"
                  "(node-output :foo)"
                  "(node-input-profile :foo)"]]
      (let [r (eval-in-sandbox {:recursive? false} prim)]
        (is (some? (:error r))
            (str "Expected error for " prim " in non-recursive mode"))))))

(deftest drill-down-primitives-resolve-but-return-nil-with-empty-history
  (testing "In :recursive? mode with no :tree-results, primitives return nil (not error)"
    ;; Note: tree-failures requires a tree-result entry to look up; with none,
    ;; the closure short-circuits via (when-let [entry ...]) and returns nil.
    (doseq [prim ["(tree-detail)"
                  "(tree-trajectory)"
                  "(tree-failures)"
                  "(node-output :foo)"
                  "(node-input-profile :foo)"]]
      (let [r (eval-in-sandbox {:recursive? true} prim)]
        (is (nil? (:error r))
            (str "Expected no error for " prim " in recursive mode, got: " (:error r)))
        (is (nil? (:raw-result r))
            (str "Expected nil result for " prim " with empty history, got: " (:raw-result r)))))))
