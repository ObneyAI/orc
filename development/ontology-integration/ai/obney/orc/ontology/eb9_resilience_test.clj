(ns ai.obney.orc.ontology.eb9-resilience-test
  "EB9 — subbehavior-internal resilience INTEGRATION test (on-demand lane,
   `:dev:test`).

   This is the REAL-LLM + REAL-Grain + REAL-source proof of the EB9 core
   acceptance — it is NOT on the fast `clj -M:poly test brick:ontology` gate (that
   gate carries the hermetic STRUCTURE test in
   `components/ontology/test/.../resilience_test.clj`). It lives here, on the
   `development/ontology-integration` on-demand lane, because it drives a REAL
   OpenRouter LLM author + a REAL OpenRouter troubleshoot over real child ticks +
   a REAL CSV source — a non-deterministic, network-dependent, multi-second live
   run that does NOT belong in the fast brick gate (the gate-hygiene rule).

   It runs the EXACT same induced-failure path the live verify driver does
   (`development/src/eb9_resilience_live_verify.clj`) and asserts the EB9 core
   acceptance through the subbehavior's PUBLIC `:reads`/`:writes` contract, reading
   the result back off the PARENT tick blackboard via the projection (discipline 7)
   — NOT from the execute return value:

     - RECOVERABLE: a primary mis-scope → the sanity gate rejects it → the
       `:fallback`'s ROBUST author recovers → downstream sees a NON-EMPTY scoped
       draft set, status :success, NO diagnosis (troubleshoot never ran);
     - UNRECOVERABLE: both paths mis-scope → the troubleshoot `:llm` lands a
       STRUCTURED :diagnosis AND the step returns a CLEAN :failure → downstream
       :concept-drafts read back EMPTY (NOT poisoned), the diagnosis is present.

   When `OPENROUTER_API_KEY` is NOT set (or the real source is absent), the test
   SKIPS cleanly. The captured evidence is
   `docs/build-timeline/live-verify/EB9-resilience.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [eb9-resilience-live-verify :as eb9]))

(defn- key-present? [] (some? (System/getenv "OPENROUTER_API_KEY")))
(defn- source-present? [] (.exists (io/file (:path eb9/csv-source))))

(deftest resilience-recovers-or-fails-with-diagnosis-live
  (testing "EB9 (REAL LLM + REAL Grain + REAL source): the resilient Extract
            self-corrects via :fallback on a recoverable induced failure, and
            fails CLEANLY WITH A DIAGNOSIS (downstream not poisoned) when it cannot"
    (cond
      (not (key-present?))
      (is true
          "SKIP (no OPENROUTER_API_KEY): EB9 live integration runs only with the key set; the gated live verify is the real proof.")

      (not (source-present?))
      (is true
          (str "SKIP (real source absent at " (:path eb9/csv-source)
               "): EB9 live integration needs the real CSV crosswalk."))

      :else
      (let [r (eb9/run-all! {})
            rec (:recoverable r)
            un (:unrecoverable r)]
        ;; --- RECOVERABLE: the :fallback robust path recovers -----------------
        (is (= :success (:status rec))
            "recoverable induced failure → the resilient step self-corrects (status :success)")
        (is (pos? (:concept-count rec))
            "downstream sees a NON-EMPTY scoped draft set (recovered via the robust path)")
        (is (nil? (:diagnosis rec))
            "no diagnosis on the recoverable path — the troubleshoot never ran (recovered)")

        ;; --- UNRECOVERABLE: clean failure WITH a diagnosis, no poison --------
        (is (= :failure (:status un))
            "unrecoverable induced failure → the step returns a CLEAN :failure (not a fake success)")
        (is (empty? (or (:concept-drafts un) []))
            "downstream :concept-drafts read back EMPTY — NOT poisoned with a fake success")
        (is (map? (:diagnosis un))
            "the troubleshoot :llm landed a STRUCTURED :diagnosis before the clean failure")
        (is (some? (:root-cause (:diagnosis un)))
            "the diagnosis carries a root-cause (Investigation) — the model reasoned about WHY")))))
