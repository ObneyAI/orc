(ns ai.obney.orc.colbert.normalization-test
  "Slice 3, cycle 3 (colbert side): the re-derived fixed normalization ceiling.

   The answerai-colbert-small-v1 encoder produces UNIT-NORMED token rows (P-0
   CHECK 3) and queries are MASK-expanded to query_maxlen = 32 rows, so MaxSim
   = sum over 32 query rows of max dot <= 32.0 — the theoretical ceiling. The
   old 40.0 default belonged to the colbertv2 bridge era. Empirically (P-0
   CHECK 4) all observed scores sit in [29.96, 31.30] under the 32.0 bound.

   Pure arithmetic — no encoder, no fixtures needed."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.interface :as colbert]))

(deftest default-ceiling-is-the-theoretical-maxsim-bound-32
  (testing "linear default: score/32, so half the ceiling normalizes to 0.5"
    (is (= 0.5 (operations/normalize-colbert-score 16.0)))
    (is (= 1.0 (operations/normalize-colbert-score 32.0))))
  (testing "clamped to [0,1] at the bounds"
    (is (= 1.0 (operations/normalize-colbert-score 40.0))
        "scores above the ceiling clamp to 1.0")
    (is (= 0.0 (operations/normalize-colbert-score -1.0))))
  (testing "the interface passes the same default through"
    (is (= 0.5 (colbert/normalize-colbert-score 16.0)))))

(deftest explicit-max-score-and-methods-are-unchanged
  (testing "an explicit :max-score still wins (back-compat for explicit configs)"
    (is (= 0.4 (operations/normalize-colbert-score 16.0 :max-score 40.0))))
  (testing ":sigmoid centers on half the (new default) ceiling"
    (is (= 0.5 (operations/normalize-colbert-score 16.0 :method :sigmoid)))
    (is (< 0.99 (operations/normalize-colbert-score 32.0 :method :sigmoid)))))
