(ns ai.obney.orc.colbert.bridge-timeout-test
  "V16 — index-creation timeout scaling.

   Index creation (`:create_index`) is O(corpus size). The bridge's default
   request timeout (60 s) is tuned for query latency; at real-corpus scale
   (~2,509 docs in V02's live verify) index creation legitimately exceeds it
   and the bridge throws a TimeoutException, which the colbert command surfaces
   as an anomaly. The fix is a scale-appropriate timeout for index creation
   specifically — NOT a blanket larger constant on every method (that would
   regress query latency expectations).

   These tests pin the timeout-SELECTION logic deterministically (no Python
   bridge needed). The live verify proves it end-to-end against the real bridge."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.colbert.core.bridge :as bridge]))

;; =============================================================================
;; Index-creation timeout is scale-appropriate (root-cause: not the 60s default)
;; =============================================================================

(deftest index-timeout-has-generous-floor-test
  (testing "a small corpus still gets a floor far above the 60s query default"
    ;; A tiny corpus must NOT be capped at the 60s query default — index
    ;; creation has fixed startup costs (model load, PLAID setup) that exceed
    ;; 60s independent of corpus size. Floor is several minutes.
    (let [t (bridge/index-timeout-ms 3)]
      (is (>= t (* 5 60 1000))
          "tiny corpus should still get a multi-minute floor, not the 60s default"))))

(deftest index-timeout-scales-with-corpus-size-test
  (testing "a large corpus gets MORE budget than a small one (corpus-size-aware)"
    (let [small (bridge/index-timeout-ms 10)
          large (bridge/index-timeout-ms 25000)]
      (is (> large small)
          "25k-doc corpus must get a larger timeout than a 10-doc corpus")))
  (testing "the 2,509-doc scale that timed out at 60s in V02 now gets a multi-minute budget"
    (let [t (bridge/index-timeout-ms 2509)]
      (is (> t 60000)
          "the exact V02 scale must exceed the old 60s default")
      (is (>= t (* 5 60 1000))
          "the V02 scale must clear the multi-minute floor"))))

;; =============================================================================
;; The fix is targeted, NOT a blanket constant on every method
;; =============================================================================

(deftest query-default-timeout-unchanged-test
  (testing "the query/search default timeout is still the short 60s — no blanket raise"
    (is (= 60000 bridge/default-timeout-ms)
        "query path must keep its 60s default; raising it would regress query latency")))

(deftest index-timeout-differs-from-query-default-test
  (testing "index creation does NOT reuse the query default — it is scaled separately"
    (is (not= bridge/default-timeout-ms (bridge/index-timeout-ms 2509))
        "index timeout must be distinct from (and larger than) the query default")))

;; =============================================================================
;; Configurability — an operator can override the index budget
;; =============================================================================

(deftest index-timeout-is-configurable-test
  (testing "an explicit override wins over the computed value"
    (is (= 999999 (bridge/index-timeout-ms 2509 {:timeout-ms 999999}))
        "explicit :timeout-ms override must be honored")))
