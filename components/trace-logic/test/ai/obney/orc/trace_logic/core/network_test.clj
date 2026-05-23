(ns ai.obney.orc.trace-logic.core.network-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.network :as net]
            [ai.obney.orc.trace-logic.core.ca :as ca]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) 1.0e-9))

(defn- mat-approx= [m1 m2]
  (and (= (count m1) (count m2))
       (every? identity
               (for [i (range (count m1))]
                 (every? identity
                         (map approx= (get m1 i) (get m2 i)))))))

;; Three CAs over the same 2-state space.
(defn make-test-ca [node-id]
  (ca/->conscious-agent
   {:node-id node-id
    :P-kernel [[0.6 0.4] [0.3 0.7]]
    :D-kernel [[0.5 0.5] [0.4 0.6]]
    :A-kernel [[0.7 0.3] [0.5 0.5]]}))

(deftest network-construction
  (testing "->network indexes CAs by node-id"
    (let [ca-1 (make-test-ca :leaf-1)
          ca-2 (make-test-ca :leaf-2)
          n (net/->network [ca-1 ca-2])]
      (is (= ca-1 (net/get-ca n :leaf-1)))
      (is (= ca-2 (net/get-ca n :leaf-2)))
      (is (= 2 (count (net/all-cas n)))))))

(deftest network-aggregate-Q
  (testing "aggregate-Q composes qualia kernels along a path"
    (let [ca-1 (make-test-ca :n1)
          ca-2 (make-test-ca :n2)
          n (net/->network [ca-1 ca-2])
          aggregate (net/aggregate-Q n [:n1 :n2])
          expected (k/compose (ca/qualia-kernel ca-1)
                              (ca/qualia-kernel ca-2))]
      (is (mat-approx= expected aggregate)))))

(deftest network-aggregate-S
  (testing "aggregate-S composes strategy kernels along a path"
    (let [ca-1 (make-test-ca :n1)
          ca-2 (make-test-ca :n2)
          n (net/->network [ca-1 ca-2])
          aggregate (net/aggregate-S n [:n1 :n2])
          expected (k/compose (ca/strategy-kernel ca-1)
                              (ca/strategy-kernel ca-2))]
      (is (mat-approx= expected aggregate)))))

(deftest network-leq-reflexive
  (testing "Every network ≤ itself"
    (let [n (net/->network [(make-test-ca :n1) (make-test-ca :n2)])]
      (is (net/leq? n n)))))
