(ns ai.obney.orc.colbert.maxsim-test
  "Slice 1, cycle 2: exact MaxSim over tiny hand-computable matrices — pure,
   no model, no I/O. Nails the ZERO-NOT-DROP punctuation semantics: a
   skiplisted doc position contributes a 0.0 candidate to each query token's
   max, it is NOT excluded (P-0: the Python reference zeroes punctuation rows
   before normalize and its max still ranges over them)."
  (:require [ai.obney.orc.colbert.core.logic :as logic]
            [clojure.test :refer [deftest testing is]]))

(defn- rows [& vs] (mapv float-array vs))

(deftest maxsim-hand-computed
  (testing "2 query rows x 3 doc rows, one skiplisted position"
    (is (= 1.0 (logic/max-sim (rows [1 0] [0 1])
                               (rows [1 0] [0 -1] [-1 0])
                               [5 999 7]
                               #{999})))))

(deftest maxsim-without-skiplist-hits
  (testing "no skiplisted positions: plain max dot per query row, summed"
    (is (= 1.0 (logic/max-sim (rows [1 0] [0 1])
                               (rows [1 0] [0 -1])
                               [5 6]
                               #{999}))))
  (testing "no skiplisted position present: an all-negative max STAYS negative
            (only a skiplisted position introduces the 0.0 candidate)"
    (is (= -0.5 (logic/max-sim (rows [1 0])
                                (rows [-1 0] [-0.5 0])
                                [5 6]
                                #{999})))))

(deftest zero-not-drop-changes-the-result
  (testing "all real dots negative + a skiplisted position present: the 0.0
            candidate WINS the max; pure exclusion would return the negative"
    (let [q (rows [1 0])
          d (rows [-1 0] [0 1])
          zero-not-drop (logic/max-sim q d [5 999] #{999})
          pure-exclusion-would-be -1.0]
      (is (= 0.0 zero-not-drop) "skiplisted position contributes a 0.0 candidate")
      (is (> zero-not-drop pure-exclusion-would-be)
          "dropping the position instead would change the score"))))

(deftest all-doc-rows-skiplisted
  (testing "every doc position skiplisted: each query token's max is 0.0"
    (is (= 0.0 (logic/max-sim (rows [1 0] [0 1])
                               (rows [1 0] [0 1])
                               [999 1000]
                               #{999 1000})))))

(deftest dot-is-exact-float-array-math
  (is (= 11.0 (logic/dot (float-array [1 2 3]) (float-array [3 1 2])))))
