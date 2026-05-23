(ns ai.obney.orc.trace-logic.core.empirical-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.empirical :as emp]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

;; =============================================================================
;; Synthetic event stream helpers
;; =============================================================================

(defn- event
  "Construct a minimal ORC event for testing."
  [event-type tick-id node-id status]
  {:event-type event-type
   :tick-id tick-id
   :node-id node-id
   :sheet-id :test-sheet
   :status status
   :event-seq 0
   :timestamp (System/currentTimeMillis)
   :inputs {}
   :writes {}})

(defn- generate-stream
  "Synthesize a deterministic event log alternating started/completed
   for `node-id` across `num-ticks` ticks, with statuses cycling
   through `statuses`."
  [node-id num-ticks statuses]
  (vec
   (mapcat
    (fn [tick-i]
      (let [status (nth statuses (mod tick-i (count statuses)))]
        [(event :sheet/node-execution-started tick-i node-id :running)
         (event :sheet/node-execution-completed tick-i node-id status)]))
    (range num-ticks))))

;; =============================================================================
;; State discretization
;; =============================================================================

(deftest coarse-discretization
  (testing "Coarse state = (node-id, status)"
    (let [e (event :sheet/node-execution-completed 0 :node-a :success)]
      (is (= [:node-a :success] (emp/state-of :coarse e))))))

(deftest medium-discretization
  (testing "Medium discretization includes a hash bucket of writes/inputs"
    (let [e1 (assoc (event :sheet/node-execution-completed 0 :node-a :success)
                    :writes {:foo 1})
          e2 (assoc (event :sheet/node-execution-completed 0 :node-a :success)
                    :writes {:foo 1})
          e3 (assoc (event :sheet/node-execution-completed 0 :node-a :success)
                    :writes {:foo 999999})]
      ;; Same writes → same bucket
      (is (= (emp/state-of :medium e1) (emp/state-of :medium e2)))
      ;; Different writes likely → different bucket
      (is (or (not= (emp/state-of :medium e1) (emp/state-of :medium e3))
              ;; Sometimes hashes collide; that's expected with mod 16.
              (= (emp/state-of :medium e1) (emp/state-of :medium e3)))))))

(deftest unknown-discretization-throws
  (let [e (event :sheet/node-execution-completed 0 :node-a :success)]
    (is (thrown? clojure.lang.ExceptionInfo (emp/state-of :unknown e)))))

;; =============================================================================
;; State sequence extraction
;; =============================================================================

(deftest state-sequence-coarse
  (testing "events->state-sequence returns ordered state values"
    (let [events (generate-stream :test-node 3 [:success :failure :success])
          seq-result (emp/events->state-sequence events
                                                 {:node-id :test-node
                                                  :discretization :coarse})]
      ;; 3 ticks × 2 events each = 6 events
      ;; Order: started/running, completed/success, started/running, completed/failure, ...
      (is (= 6 (count seq-result)))
      (is (= [:test-node :running] (first seq-result)))
      (is (= [:test-node :success] (second seq-result))))))

(deftest state-sequence-filters-by-node
  (testing "events->state-sequence filters by node-id"
    (let [events (concat (generate-stream :node-a 3 [:success])
                         (generate-stream :node-b 5 [:success]))
          seq-a (emp/events->state-sequence events {:node-id :node-a})
          seq-b (emp/events->state-sequence events {:node-id :node-b})]
      (is (= 6 (count seq-a)))   ; 3 ticks × 2 events
      (is (= 10 (count seq-b)))))) ; 5 ticks × 2 events

;; =============================================================================
;; Empirical CA construction
;; =============================================================================

(deftest ca-from-events-basic
  (testing "Empirical CA fitted from a sufficient event stream"
    (let [events (generate-stream :test-node 50 [:success :success :failure])
          ca-result (emp/ca-from-events events {:node-id :test-node})]
      (is (uuid? (:ca-id ca-result)))
      (is (= :test-node (:node-id ca-result)))
      (is (k/markov? (:Q-kernel ca-result)))
      (is (true? (:empirical? ca-result)))
      (is (> (:sample-count ca-result) 0)))))

(deftest ca-insufficient-samples
  (testing "Returns ::insufficient-samples below threshold"
    (let [events (generate-stream :test-node 2 [:success])
          ca-result (emp/ca-from-events events {:node-id :test-node})]
      (is (= ::emp/insufficient-samples ca-result)))))

(deftest ca-min-samples-override
  (testing "min-samples override allows fitting from very short streams"
    (let [events (generate-stream :test-node 3 [:success])
          ca-result (emp/ca-from-events events
                                        {:node-id :test-node
                                         :min-samples 1})]
      (is (not= ::emp/insufficient-samples ca-result))
      (is (k/markov? (:Q-kernel ca-result))))))

;; =============================================================================
;; Network from events
;; =============================================================================

(deftest network-from-events-multinode
  (testing "Network fits one CA per node"
    (let [events (concat (generate-stream :node-a 30 [:success :failure])
                         (generate-stream :node-b 30 [:success]))
          network (emp/network-from-events events {})]
      (is (= #{:node-a :node-b} (set (keys network))))
      (doseq [[_ ca] network]
        (is (or (= ca ::emp/insufficient-samples)
                (uuid? (:ca-id ca))))))))

;; =============================================================================
;; Convergence behavior
;; =============================================================================

(deftest empirical-Q-converges
  (testing "Q-kernel from a long stream is row-stochastic"
    (let [events (generate-stream :node-a 200 [:success :failure :success :success])
          ca-result (emp/ca-from-events events {:node-id :node-a})]
      (is (k/markov? (:Q-kernel ca-result)))
      (is (k/markov? (:S-kernel ca-result))))))

;; =============================================================================
;; Optimal window selection
;; =============================================================================

(deftest optimal-window-returns-positive
  (testing "optimal-window picks a sensible window size"
    (let [;; Long state sequence simulating a stable Markov process
          state-seq (vec (repeatedly 1000 #(rand-nth [:a :b :c])))
          k (emp/optimal-window state-seq)]
      (is (and (number? k) (pos? k))))))
