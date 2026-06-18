(ns ai.obney.orc.ontology.dt8-cq-loop-recovery-test
  "DT8 — CQ-driven loop + focused recovery. The LOOP LOGIC is tested
   DETERMINISTICALLY (Discipline #4/#6 backbone): the build! CQ-verdict sequence,
   the focused re-extract, the per-CQ evaluation, and the node-recovery node-run
   are STUBBED via injected seams so the adaptive branching is exercised without a
   live LLM (the live-LLM proof is the DT8 live-verify / prototype). These tests
   verify behavior through the public fns (cq-driven-loop!, focused-reextract!,
   focused-node-recovery!, the branch deciders) — never prompt-string internals.

   The four backbone behaviors (acceptance criteria):
     1. :failed-cq -> focused re-extract supplies data -> re-gate -> :complete.
     2. an UNANSWERABLE CQ (re-extract supplies nothing) -> honest termination,
        the unanswerable CQ surfaced, NO false-green, NO spin.
     3. a node failure -> focused single-node recovery (reads surviving vars),
        NOT a full rebuild.
     4. budget exhaustion -> terminates with the reason (always terminates).

   Domain-agnostic fixtures — no education/CIP/SOC specifics (Discipline #12)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]))

;; =============================================================================
;; failing-cq-verdicts — the per-CQ gap read (re-extract targeting + unanswerable)
;; =============================================================================

(deftest failing-cq-verdicts-selects-fail-and-unknown-not-pass
  (testing "a CQ is a GAP when its S15 verdict is :fail or :unknown; a :pass is not"
    (let [evald [{:cq-text "A" :verdict :pass}
                 {:cq-text "B" :verdict :fail}
                 {:cq-text "C" :verdict :unknown}
                 {:cq-text "D" :verdict :pass}]
          gaps (dt/failing-cq-verdicts evald)]
      (is (= #{"B" "C"} (set (map :cq-text gaps))))
      (is (= [] (dt/failing-cq-verdicts [])))
      (is (= [] (dt/failing-cq-verdicts nil))))))

;; =============================================================================
;; Branch deciders — DT8 fills the DT1 stubs with real branch DECISIONS
;; =============================================================================

(deftest cq-reextract-branch-taken-only-on-failed-cq
  (testing "the cq-reextract branch is TAKEN on :failed-cq, NOT on :complete"
    (let [taken (dt/cq-reextract-branch-stub {} {:build-status :failed-cq :graph-health {}})
          not-taken (dt/cq-reextract-branch-stub {} {:build-status :complete :graph-health {}})]
      (is (true? (:taken? taken)))
      (is (= :failed-cq (:reason taken)))
      (is (false? (:taken? not-taken)))
      (is (= :cq-gate-passed (:reason not-taken))))))

(deftest recovery-branch-taken-only-on-a-node-failure
  (testing "the recovery branch is TAKEN when a node failed, NOT on the happy path"
    (let [taken (dt/recovery-branch-stub {} {:failed-node :model :error "boom"})
          not-taken (dt/recovery-branch-stub {} {:failed-node nil :error nil})]
      (is (true? (:taken? taken)))
      (is (= :node-failure (:reason taken)))
      (is (= :model (:failed-node taken)))
      (is (false? (:taken? not-taken))))))

;; =============================================================================
;; 1. :failed-cq -> focused re-extract supplies data -> re-gate -> :complete
;; =============================================================================

(deftest failing-cq-triggers-focused-reextract-and-re-gate-passes
  (testing "a failing CQ triggers a FOCUSED re-extract (NOT a full rebuild) and the
            re-gate then shows the CQ passing (:complete)"
    (let [reextract-calls (atom 0)
          build-calls (atom 0)
          ;; re-gate build returns :complete (the supplied data closed the gap).
          build-fn (fn [_ctx _p]
                     (swap! build-calls inc)
                     {:status :complete :graph-health {:pass-rate 1.0}})
          ;; the focused re-extract supplies new data (graph grew).
          reextract-fn (fn [_ctx opts]
                         (swap! reextract-calls inc)
                         ;; targeted at the failing CQ (proof it was passed through).
                         (is (= ["Q1"] (mapv :cq-text (:failing-cqs opts))))
                         {:status :ok :concepts-added 2 :relationships-added 1})
          evaluate-cqs-fn (fn [_ctx _oid _judge]
                            {:evaluated [{:cq-text "Q1" :verdict :fail}
                                         {:cq-text "Q2" :verdict :pass}]})
          result (dt/cq-driven-loop!
                  {} {:ontology-id (random-uuid) :source {:type :csv :path "x"}
                      :goal "g" :blackboard {}
                      :build-fn build-fn :reextract-fn reextract-fn
                      :reconcile-fn false :evaluate-cqs-fn evaluate-cqs-fn
                      :initial-build-result {:status :failed-cq :graph-health {:pass-rate 0.5}}})]
      (is (= :complete (:status result)) "the re-gate passes")
      (is (= :cq-gate-passed (get-in result [:cq-loop :termination-reason])))
      (is (= 1 @reextract-calls) "exactly ONE focused re-extract closed the gap")
      (is (empty? (get-in result [:cq-loop :unanswerable-cqs])))
      (is (= 1 (count (get-in result [:cq-loop :history])))))))

;; =============================================================================
;; 2. UNANSWERABLE CQ -> honest termination (no false-green, no spin)
;; =============================================================================

(deftest unanswerable-cq-terminates-honestly-without-spinning
  (testing "a CQ no re-extract can supply data for (graph never grows) is surfaced
            as UNANSWERABLE and the loop terminates honestly — NOT :complete, NOT
            an infinite loop, and it does NOT keep re-extracting for it"
    (let [reextract-calls (atom 0)
          build-fn (fn [_ctx _p] {:status :failed-cq :graph-health {:pass-rate 0.0}})
          ;; the re-extract supplies NOTHING — the source genuinely lacks the data.
          reextract-fn (fn [_ctx _opts]
                         (swap! reextract-calls inc)
                         {:status :ok :concepts-added 0 :relationships-added 0})
          evaluate-cqs-fn (fn [_ctx _oid _judge]
                            {:evaluated [{:cq-text "Impossible" :verdict :unknown}]})
          result (dt/cq-driven-loop!
                  {} {:ontology-id (random-uuid) :source {:type :csv :path "x"}
                      :goal "g" :blackboard {}
                      :cq-loop-config {:max-iterations 5}
                      :build-fn build-fn :reextract-fn reextract-fn
                      :reconcile-fn false :evaluate-cqs-fn evaluate-cqs-fn
                      :initial-build-result {:status :failed-cq :graph-health {:pass-rate 0.0}}})]
      (is (= :failed-cq (:status result)) "NOT false-green: stays :failed-cq")
      (is (= :all-remaining-unanswerable (get-in result [:cq-loop :termination-reason])))
      (is (= ["Impossible"] (get-in result [:cq-loop :unanswerable-cqs]))
          "the unanswerable CQ is SURFACED honestly (V17)")
      (is (= 1 @reextract-calls)
          "NO spin: it attempts the re-extract ONCE, learns the data is absent,
           and does NOT keep re-extracting (would have hit 5 if it spun)"))))

;; =============================================================================
;; 3. Node failure -> focused single-node recovery (reads surviving vars)
;; =============================================================================

(deftest node-failure-triggers-focused-single-node-recovery
  (testing "a Model-node failure re-runs JUST the Model node reading the surviving
            Profile output (focused recovery), not a full rebuild"
    (let [survived-profile {:entity-candidates ["thing"]}
          received (atom nil)
          run-node (fn [_ctx params]
                     (reset! received params)
                     {:status :ok :output {:entity-types [{:type "thing"}]}})
          result (dt/focused-node-recovery!
                  {} {:failed-node :model
                      :error "model node blew up"
                      :blackboard {:profile {:output survived-profile}}
                      :source {:type :csv :path "x"} :goal "g"
                      :run-node run-node})]
      (is (= :ok (:status result)) "the focused recovery succeeded")
      (is (true? (:recovery? result)))
      (is (= :model (:recovered-node result)))
      ;; it re-ran ONLY the failed node, reading the SURVIVING predecessor output
      (is (= :model (:node-key @received)) "re-ran ONLY the failed node")
      (is (= survived-profile (get-in @received [:extra-inputs :profile]))
          "the recovery read the SURVIVING Profile output (no rebuild)")
      (is (re-find #"RECOVERY RE-RUN" (:prompt @received))
          "the recovery prompt names it a recovery resuming from surviving work"))))

(deftest node-recovery-that-fails-again-surfaces-honestly
  (testing "a focused recovery that ALSO fails surfaces honestly (no false green)"
    (let [run-node (fn [_ctx _p] {:status :failed :error "still broken"})
          result (dt/focused-node-recovery!
                  {} {:failed-node :transform :error "first failure"
                      :blackboard {:model {:output {:entity-types []}}}
                      :source {:type :csv :path "x"} :goal "g"
                      :run-node run-node})]
      (is (= :failed (:status result)))
      (is (true? (:recovery? result)))
      (is (= "still broken" (:error result))))))

;; =============================================================================
;; 4. Budget exhaustion -> terminates with the reason (always terminates)
;; =============================================================================

(deftest loop-is-budget-bounded-and-terminates-with-the-reason
  (testing "even when every re-extract grows the graph but the gate never passes,
            the loop terminates at :max-iterations with :budget-exhausted"
    (let [reextract-calls (atom 0)
          ;; gate NEVER passes; each re-extract grows the graph (so it is NOT
          ;; unanswerable) -> the loop would spin forever WITHOUT the budget bound.
          build-fn (fn [_ctx _p] {:status :failed-cq :graph-health {:pass-rate 0.1}})
          reextract-fn (fn [_ctx _opts]
                         (swap! reextract-calls inc)
                         {:status :ok :concepts-added 1 :relationships-added 0})
          evaluate-cqs-fn (fn [_ctx _oid _judge]
                            {:evaluated [{:cq-text "Hard" :verdict :fail}]})
          result (dt/cq-driven-loop!
                  {} {:ontology-id (random-uuid) :source {:type :csv :path "x"}
                      :goal "g" :blackboard {}
                      :cq-loop-config {:max-iterations 2}
                      :build-fn build-fn :reextract-fn reextract-fn
                      :reconcile-fn false :evaluate-cqs-fn evaluate-cqs-fn
                      :initial-build-result {:status :failed-cq :graph-health {:pass-rate 0.1}}})]
      (is (= :failed-cq (:status result)) "NOT false-green")
      (is (= :budget-exhausted (get-in result [:cq-loop :termination-reason])))
      (is (= 2 (get-in result [:cq-loop :iterations])) "stopped at max-iterations")
      (is (= 2 @reextract-calls) "exactly max-iterations re-extracts, then stop"))))

(deftest loop-not-entered-when-build-is-already-complete
  (testing "if the initial build is :complete the loop is a no-op pass-through"
    (let [reextract-calls (atom 0)
          result (dt/cq-driven-loop!
                  {} {:ontology-id (random-uuid) :source {:type :csv :path "x"}
                      :goal "g" :blackboard {}
                      :build-fn (fn [_ _] (throw (ex-info "build! must NOT be re-run" {})))
                      :reextract-fn (fn [_ _] (swap! reextract-calls inc) {:status :ok})
                      :reconcile-fn false
                      :evaluate-cqs-fn (fn [_ _ _] {:evaluated []})
                      :initial-build-result {:status :complete :graph-health {:pass-rate 1.0}}})]
      (is (= :complete (:status result)))
      (is (= :cq-gate-passed (get-in result [:cq-loop :termination-reason])))
      (is (zero? @reextract-calls) "no re-extract when the gate already passed")
      (is (zero? (get-in result [:cq-loop :iterations]))))))

;; =============================================================================
;; A failed re-extract surfaces honestly (no false-green growth)
;; =============================================================================

(deftest a-failed-reextract-is-treated-as-no-growth-and-surfaces
  (testing "if the focused re-extract itself FAILS, it supplied no data, so the CQ
            is treated as unanswerable and the loop terminates honestly"
    (let [build-fn (fn [_ _] {:status :failed-cq :graph-health {:pass-rate 0.0}})
          reextract-fn (fn [_ _] {:status :failed :error "transform node failed"
                                  :concepts-added 0 :relationships-added 0})
          evaluate-cqs-fn (fn [_ _ _] {:evaluated [{:cq-text "Q" :verdict :fail}]})
          result (dt/cq-driven-loop!
                  {} {:ontology-id (random-uuid) :source {:type :csv :path "x"}
                      :goal "g" :blackboard {}
                      :cq-loop-config {:max-iterations 4}
                      :build-fn build-fn :reextract-fn reextract-fn
                      :reconcile-fn false :evaluate-cqs-fn evaluate-cqs-fn
                      :initial-build-result {:status :failed-cq :graph-health {:pass-rate 0.0}}})]
      (is (= :failed-cq (:status result)))
      (is (= :all-remaining-unanswerable (get-in result [:cq-loop :termination-reason])))
      (is (= ["Q"] (get-in result [:cq-loop :unanswerable-cqs]))))))
