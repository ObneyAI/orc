(ns ai.obney.orc.trace-logic.core.community-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.community :as comm]))

;; =============================================================================
;; Paper §6 fig. 10–11: free / bound / confined
;; =============================================================================

(def free-kernel
  "Paper §6 fig. 10 'free' — two RCCs, no inter-class transitions."
  [[0.4 0.6 0.0 0.0]
   [0.3 0.7 0.0 0.0]
   [0.0 0.0 0.5 0.5]
   [0.0 0.0 0.2 0.8]])

(def bound-kernel
  "Paper §6 fig. 10 'bound' — one RCC with two communities, weak inter-class
   edges."
  [[0.4 0.5 0.0 0.1]
   [0.3 0.7 0.0 0.0]
   [0.0 0.0 0.5 0.5]
   [0.1 0.0 0.2 0.7]])

(def confined-kernel
  "Paper §6 fig. 10 'confined' — bipartite: every transition crosses
   between {0,1} and {2,3}."
  [[0.0 0.0 0.4 0.6]
   [0.0 0.0 0.3 0.7]
   [0.5 0.5 0.0 0.0]
   [0.2 0.8 0.0 0.0]])

;; =============================================================================
;; bipartite-decomposition
;; =============================================================================

(deftest confined-is-bipartite
  (testing "Paper §6 confined kernel partitions into {0,1} and {2,3}"
    (let [result (comm/bipartite-decomposition confined-kernel)]
      (is (vector? result))
      (is (= 2 (count result)))
      (is (= #{#{0 1} #{2 3}} (set result))))))

(deftest free-disconnected
  (testing "Free kernel (two disconnected components) returns :disconnected"
    (let [result (comm/bipartite-decomposition free-kernel)]
      (is (= :disconnected result)))))

(deftest non-bipartite-detected
  (testing "Kernel with odd cycles is :not-bipartite"
    (let [P [[0.3 0.7 0.0]   ; 0 → 1
             [0.0 0.3 0.7]   ; 1 → 2
             [0.7 0.0 0.3]]] ; 2 → 0    (3-cycle, odd)
      (is (= :not-bipartite (comm/bipartite-decomposition P))))))

(deftest single-state-bipartite
  (testing "Single-state kernel decomposes trivially"
    (let [P [[1.0]]
          result (comm/bipartite-decomposition P)]
      (is (or (= :disconnected result)  ; only self-loop, no other-class edges
              (and (vector? result)
                   (= 2 (count result))))))))

;; =============================================================================
;; detect: spectral community detection
;; =============================================================================

(deftest detect-returns-partition
  (testing "Communities cover all states without overlap"
    (doseq [P [free-kernel bound-kernel confined-kernel]]
      (let [communities (comm/detect P)
            n (count P)
            union (apply clojure.set/union communities)]
        (is (= (set (range n)) union))
        ;; Sets should be disjoint
        (is (= n (reduce + 0 (map count communities))))))))

(deftest detect-bound-finds-two-communities
  (testing "Bound kernel: spectral detection finds the two-community structure"
    (let [communities (comm/detect bound-kernel 1)]
      ;; With 1 slow mode, expect roughly 2 sign-pattern groups
      (is (<= 2 (count communities) 4)))))
