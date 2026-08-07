(ns ai.obney.orc.colbert.normalization-test
  "Slice 3, cycle 3 (colbert side): the re-derived fixed normalization ceiling.
   Amended by CC-17.

   The answerai-colbert-small-v1 encoder produces UNIT-NORMED token rows (P-0
   CHECK 3) and queries are MASK-expanded to `maximum_query_tokens` rows, so
   MaxSim = sum over that many query rows of max dot <= maximum_query_tokens —
   the theoretical ceiling. The old 40.0 default belonged to the colbertv2
   bridge era; the literal 32.0 that replaced it was the checkpoint's own
   query_maxlen.

   CC-17 made maximum_query_tokens CONFIGURATION and moved the shipped default
   off the checkpoint value, so the assertion here is now the INVARIANT the
   32.0 was only ever an instance of: the default ceiling IS the configured
   maximum_query_tokens. A frozen literal would clamp every real score to 1.0
   the moment an operator retunes the limit.

   Pure arithmetic — no ONNX inference; the limit resolves without loading the
   graph."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.interface :as colbert]))

(defn- ceiling ^double [] (operations/maxsim-ceiling))

(deftest default-ceiling-is-the-configured-maximum-query-tokens
  (testing "the ceiling is DERIVED from the limit, never a frozen literal"
    (is (= (double (encoder/configured-maximum-query-tokens)) (ceiling))
        "maxsim-ceiling tracks the configured maximum_query_tokens")
    (println "  [N] maxsim ceiling under test =" (ceiling)))
  (testing "linear default: score/ceiling, so half the ceiling normalizes to 0.5"
    (is (= 0.5 (operations/normalize-colbert-score (/ (ceiling) 2.0))))
    (is (= 1.0 (operations/normalize-colbert-score (ceiling)))))
  (testing "clamped to [0,1] at the bounds"
    (is (= 1.0 (operations/normalize-colbert-score (* 1.25 (ceiling))))
        "scores above the ceiling clamp to 1.0")
    (is (= 0.0 (operations/normalize-colbert-score -1.0))))
  (testing "the interface passes the same default through"
    (is (= 0.5 (colbert/normalize-colbert-score (/ (ceiling) 2.0)))))
  (testing "an operator override moves the ceiling with it"
    (let [prev (System/getProperty encoder/maximum-query-tokens-property)]
      (try
        (System/setProperty encoder/maximum-query-tokens-property "128")
        (is (= 128.0 (operations/maxsim-ceiling)))
        (is (= 0.5 (operations/normalize-colbert-score 64.0)))
        (finally
          (if prev
            (System/setProperty encoder/maximum-query-tokens-property prev)
            (System/clearProperty encoder/maximum-query-tokens-property)))))))

(deftest explicit-max-score-and-methods-are-unchanged
  (testing "an explicit :max-score still wins (back-compat for explicit configs)"
    (is (= 0.4 (operations/normalize-colbert-score 16.0 :max-score 40.0))))
  (testing "an explicit NIL :max-score falls back to the derived ceiling"
    (is (= (operations/normalize-colbert-score 16.0)
           (operations/normalize-colbert-score 16.0 :max-score nil))))
  (testing ":sigmoid centers on half the ceiling"
    (is (= 0.5 (operations/normalize-colbert-score (/ (ceiling) 2.0) :method :sigmoid)))
    (is (< 0.99 (operations/normalize-colbert-score (ceiling) :method :sigmoid)))))
