(ns ai.obney.orc.colbert.maxsim-test
  "Slice 1, cycle 2: exact MaxSim over tiny hand-computable matrices — pure,
   no model, no I/O. Nails the ZERO-NOT-DROP punctuation semantics: a
   skiplisted doc position contributes a 0.0 candidate to each query token's
   max, it is NOT excluded (P-0: the Python reference zeroes punctuation rows
   before normalize and its max still ranges over them)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.colbert.core.maxsim :as maxsim]))

(defn- rows [& vs] (mapv float-array vs))

(deftest maxsim-hand-computed
  (testing "2 query rows x 3 doc rows, one skiplisted position"
    ;; q1=[1 0], q2=[0 1]
    ;; d rows: d0=[1 0] (id 5), d1=[0 -1] (id 999, SKIPLISTED), d2=[-1 0] (id 7)
    ;; q1 candidates: dot d0 = 1.0, d1 skiplisted -> 0.0, dot d2 = -1.0  => max 1.0
    ;; q2 candidates: dot d0 = 0.0, d1 skiplisted -> 0.0 (real dot -1), dot d2 = 0.0 => max 0.0
    ;; MaxSim = 1.0 + 0.0 = 1.0
    (is (= 1.0 (maxsim/max-sim (rows [1 0] [0 1])
                               (rows [1 0] [0 -1] [-1 0])
                               [5 999 7]
                               #{999})))))

(deftest maxsim-without-skiplist-hits
  (testing "no skiplisted positions: plain max dot per query row, summed"
    ;; q1=[1 0]: dots 1.0, 0.0 => 1.0 ; q2=[0 1]: dots 0.0, -1.0 => 0.0
    (is (= 1.0 (maxsim/max-sim (rows [1 0] [0 1])
                               (rows [1 0] [0 -1])
                               [5 6]
                               #{999}))))
  (testing "no skiplisted position present: an all-negative max STAYS negative
            (only a skiplisted position introduces the 0.0 candidate)"
    ;; q=[1 0]; d0=[-1 0] (dot -1.0), d1=[-0.5 0] (dot -0.5); no skiplist hit
    (is (= -0.5 (maxsim/max-sim (rows [1 0])
                                (rows [-1 0] [-0.5 0])
                                [5 6]
                                #{999})))))

(deftest zero-not-drop-changes-the-result
  (testing "all real dots negative + a skiplisted position present: the 0.0
            candidate WINS the max; pure exclusion would return the negative"
    ;; q=[1 0]; d0=[-1 0] (id 5, dot -1.0); d1=[0 1] (id 999, SKIPLISTED)
    (let [q (rows [1 0])
          d (rows [-1 0] [0 1])
          zero-not-drop (maxsim/max-sim q d [5 999] #{999})
          pure-exclusion-would-be -1.0]
      (is (= 0.0 zero-not-drop) "skiplisted position contributes a 0.0 candidate")
      (is (> zero-not-drop pure-exclusion-would-be)
          "dropping the position instead would change the score"))))

(deftest all-doc-rows-skiplisted
  (testing "every doc position skiplisted: each query token's max is 0.0"
    (is (= 0.0 (maxsim/max-sim (rows [1 0] [0 1])
                               (rows [1 0] [0 1])
                               [999 1000]
                               #{999 1000})))))

(deftest dot-is-exact-float-array-math
  (is (= 11.0 (maxsim/dot (float-array [1 2 3]) (float-array [3 1 2])))))
