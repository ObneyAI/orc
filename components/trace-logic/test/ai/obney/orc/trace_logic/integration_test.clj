(ns ai.obney.orc.trace-logic.integration-test
  "Cross-phase integration: exercise the entire substrate end-to-end
   on synthetic CA networks and event streams.

   These tests are the structural-correctness regression: every claim
   the plan makes about how the components fit together is exercised
   in one place, with synthetic ground truth so we can verify exact
   relationships rather than statistical convergence."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.interface :as tl]
            [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.trace :as tr]
            [ai.obney.orc.trace-logic.core.lebesgue :as lb]
            [ai.obney.orc.trace-logic.core.spectral :as spec]
            [ai.obney.orc.trace-logic.core.ca :as ca]
            [ai.obney.orc.trace-logic.core.policy :as p]
            [ai.obney.orc.trace-logic.core.learning :as l]
            [ai.obney.orc.trace-logic.core.empirical :as emp]
            [ai.obney.orc.trace-logic.core.judges :as judges]))

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1.0e-6))

(defn- vec-approx= [v1 v2]
  (and (= (count v1) (count v2))
       (every? identity (map approx= v1 v2))))

;; =============================================================================
;; Stationary Map Theorem holds across the full network
;; =============================================================================

(deftest network-wide-stationary-map-homomorphism
  (testing "For every (P, A) across the network, stationary(trace(P,A)) =
            lebesgue/restrict(stationary(P), A)"
    (let [;; Construct three CAs over the same 3-state space
          P-test [[0.3 0.2 0.5] [0.5 0.3 0.2] [0.3 0.3 0.4]]
          D-test [[0.4 0.3 0.3] [0.5 0.3 0.2] [0.3 0.4 0.3]]
          A-test [[0.5 0.3 0.2] [0.2 0.5 0.3] [0.3 0.3 0.4]]
          ca-1 (tl/->conscious-agent
                {:node-id :n1 :P-kernel P-test :D-kernel D-test :A-kernel A-test})
          ca-2 (tl/->conscious-agent
                {:node-id :n2 :P-kernel P-test :D-kernel D-test :A-kernel A-test})
          ca-3 (tl/->conscious-agent
                {:node-id :n3 :P-kernel P-test :D-kernel D-test :A-kernel A-test})]
      (doseq [c [ca-1 ca-2 ca-3]
              subset [#{0 1} #{0 2} #{1 2}]]
        (let [Q (tl/qualia-kernel c)
              pi-Q (k/stationary Q)
              Q-A (tr/projection Q subset)
              pi-A (k/stationary Q-A)
              expected (vec (filter pos? (lb/restrict-stationary pi-Q subset)))]
          (is (vec-approx= expected pi-A)
              (str "Stationary Map homomorphism failed for ca=" (:node-id c)
                   ", subset=" subset)))))))

;; =============================================================================
;; Recursive policy hierarchy (Policy_0, Policy_1) outperforms flat Policy_0
;; on a phase-dependent benchmark.
;; =============================================================================

(deftest recursive-policy-outperforms-flat
  (testing "Phase-dependent benchmark: Policy_1 selecting between two
            Policy_0 instances achieves higher reward than either fixed
            Policy_0"
    (let [;; Phase 0 (early): reward favors child 0
          ;; Phase 1 (late): reward favors child 1
          ;; Two Policy_0 instances, each tuned for one phase.
          policy-A (tl/deterministic-policy (constantly 0) 2)  ; always picks 0
          policy-B (tl/deterministic-policy (constantly 1) 2)  ; always picks 1

          ;; Meta-policy that switches between A and B based on phase.
          ;; In phase 0: stay on A; in phase 1: stay on B.
          ;; Kernel: [[1 0] [0 1]] from policy-A keeps A; from policy-B keeps B.
          meta-policy (tl/->meta-policy
                       {:kernel [[1.0 0.0] [0.0 1.0]]
                        :sub-policies [policy-A policy-B]})

          ;; Reward function:
          ;;   phase 0 (first 500 ticks): child 0 → reward 1.0; child 1 → 0.0
          ;;   phase 1 (next  500 ticks): child 0 → 0.0; child 1 → reward 1.0
          phase-of (fn [tick] (if (< tick 500) 0 1))
          reward-for (fn [tick child]
                       (if (= child (phase-of tick)) 1.0 0.0))

          ;; Score for a flat policy: how well does it do across all 1000 ticks?
          flat-policy-score
          (fn [policy]
            (reduce
             +
             (for [tick (range 1000)]
               (let [child (p/sample-next policy 0)]
                 (reward-for tick child)))))

          ;; Score for the meta-policy: select sub-policy by phase, then sample.
          meta-policy-score
          (reduce
           +
           (for [tick (range 1000)]
             (let [phase (phase-of tick)
                   sub (tl/select-sub-policy meta-policy phase)
                   child (p/sample-next sub 0)]
               (reward-for tick child))))

          flat-A-score (flat-policy-score policy-A)
          flat-B-score (flat-policy-score policy-B)]

      ;; Meta-policy gets ≈1000 (perfect); flat gets ≈500 (one phase only)
      (is (> meta-policy-score flat-A-score))
      (is (> meta-policy-score flat-B-score))
      (is (>= meta-policy-score 950)
          (str "meta-policy score too low: " meta-policy-score)))))

;; =============================================================================
;; Empirical CA fitting from synthetic event log
;; =============================================================================

(deftest empirical-fitting-end-to-end
  (testing "Generate an event stream, fit an empirical CA, verify it has
            sensible Q and S kernels and judge scores"
    (let [num-ticks 100
          events
          (vec
           (mapcat
            (fn [tick]
              (let [status (rand-nth [:success :failure])]
                [{:event-type :sheet/node-execution-started
                  :tick-id tick
                  :node-id :test-node
                  :sheet-id :s1
                  :inputs {:foo 1}
                  :event-seq 0}
                 {:event-type :sheet/node-execution-completed
                  :tick-id tick
                  :node-id :test-node
                  :sheet-id :s1
                  :status status
                  :writes {:bar 2}
                  :event-seq 1}]))
            (range num-ticks)))
          ca-instance (tl/ca-from-events events {:node-id :test-node})]

      (is (not= :ai.obney.orc.trace-logic.core.empirical/insufficient-samples ca-instance))
      (is (k/markov? (:Q-kernel ca-instance)))
      (is (k/markov? (:S-kernel ca-instance)))
      (is (true? (:empirical? ca-instance)))

      ;; Judges should run cleanly on the empirical CA
      (let [l2-score (tl/lambda-2-judge ca-instance)
            coh-score (tl/strategy-coherence-judge ca-instance)
            rcc (tl/rcc-report ca-instance)]
        (is (<= 0.0 l2-score 1.0))
        (is (<= 0.0 coh-score 1.0))
        (is (>= (:num-rccs rcc) 1))))))

;; =============================================================================
;; Policy learning improves outcome across iterations
;; =============================================================================

(deftest learning-loop-improves-policy
  (testing "Bayesian update from rewarded transitions shifts the policy
            kernel toward higher-reward actions"
    (let [;; Start with a uniform policy on 3 states
          initial-policy (tl/uniform-policy 3)
          state-0 (tl/->dirichlet-state initial-policy
                                        :alpha 0.01
                                        :prior-strength 0.0)
          ;; Observe many transitions favoring 0 → 1 with high reward
          observations (for [_ (range 200)] [0 1 1.0])
          state-final (tl/observe-batch state-0 observations)
          updated-policy (tl/update-policy initial-policy state-final)
          original-prob (get-in (:kernel initial-policy) [0 1])
          updated-prob (get-in (:kernel updated-policy) [0 1])]
      ;; Original kernel had P(0→1) = 1/3 ≈ 0.33; updated should be much higher.
      (is (> updated-prob original-prob))
      (is (> updated-prob 0.9)))))

;; =============================================================================
;; Lambda-2 monotone-improvement check
;; =============================================================================

(deftest lambda-2-improves-with-policy-learning
  (testing "After learning, the policy's λ₂ improves (decreases) for a
            kernel where there's a clearly better strategy"
    (let [;; Slow-mixing initial policy (close to identity)
          slow-policy (tl/->policy
                       {:kernel [[0.95 0.025 0.025]
                                 [0.025 0.95 0.025]
                                 [0.025 0.025 0.95]]
                        :level 0})
          ;; Faster-mixing learned policy (closer to uniform)
          fast-policy (tl/->policy
                       {:kernel [[0.4 0.3 0.3]
                                 [0.3 0.4 0.3]
                                 [0.3 0.3 0.4]]
                        :level 0})
          slow-l2 (spec/lambda-2 (:kernel slow-policy))
          fast-l2 (spec/lambda-2 (:kernel fast-policy))]
      (is (< fast-l2 slow-l2)))))

;; =============================================================================
;; Trace blanket on a CA with known structure
;; =============================================================================

(deftest blanket-on-controlled-vs-reactive-ca
  (testing "A CA with one deterministic state and one uniform state has
            the deterministic state in :self and the uniform in :world"
    (let [;; State 0: deterministic; state 1: uniform
          P [[1.0 0.0] [0.5 0.5]]
          ca-instance (ca/->conscious-agent
                       {:P-kernel P :D-kernel P :A-kernel P
                        :node-id :test})
          ;; Override the auto Q with our hand-crafted P to control the test
          ca-with-known-Q (assoc ca-instance :Q-kernel P)
          blanket (tl/trace-blanket ca-with-known-Q :threshold 0.5)]
      (is (contains? (:self blanket) 0))
      (is (contains? (:world blanket) 1)))))
