(ns ai.obney.orc.ontology.eb4-extract-test
  "EB4 — Extract subbehavior INTEGRATION test (on-demand lane, `:dev:test`).

   This is the REAL-LLM + REAL-Grain + REAL-source proof of the P1
   verify-not-assume criterion — it is NOT on the fast `clj -M:poly test
   brick:ontology` gate (that gate carries the hermetic contract test in
   `components/ontology/test/.../extract_subbehavior_test.clj`). It lives here, on
   the `development/ontology-integration` on-demand lane, because it drives a REAL
   OpenRouter LLM call + a REAL CSV source over a real child tick — a non-
   deterministic, network-dependent, multi-second live run that does NOT belong in
   the fast brick gate (the gate-hygiene rule).

   It runs the EXACT same delegated path the live verify driver does
   (`development/src/eb4_extract_subbehavior_live_verify.clj`) and asserts the P1
   criterion through the subbehavior's PUBLIC `:reads`/`:writes` contract, reading
   the draft set back off the PARENT tick blackboard via the projection
   (discipline 7) — NOT from the execute return value:

     - the AUTONOMOUS transform (NO hand-correction) yields a SANE SCOPED concept
       count over the FULL source — NOT a raw-row dump (< the 6,098 source rows,
       and > 0 — a non-empty, scoped draft set);
     - nodes not edges (concept-drafts present);
     - per-row errors counted, no abort (rows-ok = rows-streamed, rows-errored 0);
     - the draft set crosses `:delegate` as VECTORS (C1).

   When `OPENROUTER_API_KEY` is NOT set (or the real source file is absent), the
   test SKIPS cleanly with an explanatory assertion rather than failing — so the
   on-demand lane stays green in a no-key environment while the gated live run
   (key present) exercises the real path. The captured evidence is
   `docs/build-timeline/live-verify/EB4-extract.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [eb4-extract-subbehavior-live-verify :as eb4]))

(defn- key-present? [] (some? (System/getenv "OPENROUTER_API_KEY")))
(defn- source-present? [] (.exists (io/file (:path eb4/csv-source))))

(deftest extract-autonomous-transform-yields-sane-scoped-count-live
  (testing "EB4 P1 (REAL LLM + REAL Grain + REAL source): the delegated Extract
            subbehavior's AUTONOMOUS transform yields a sane scoped draft set over
            the FULL source — no hand-correction, no abort, vectors across delegate"
    (cond
      (not (key-present?))
      (is true
          "SKIP (no OPENROUTER_API_KEY): EB4 live integration runs only with the key set; the gated live verify is the real proof.")

      (not (source-present?))
      (is true
          (str "SKIP (real source absent at " (:path eb4/csv-source)
               "): EB4 live integration needs the real CSV crosswalk."))

      :else
      (let [r (eb4/run-all! {})
            m (:csv r)
            p (:probe r)
            report (:extraction-report m)]
        ;; --- the delegated run completed via the public contract -----------
        (is (= :success (:central-status m))
            "the central tree delegated to Extract and completed")
        (is (get-in m [:registry :registry-match?])
            "the Extract sheet registered with a deterministic name→id round-trip")

        ;; --- C1: the draft set crosses :delegate as VECTORS ----------------
        (is (:drafts-are-vectors? m)
            "concept-drafts + relationship-drafts cross :delegate as parsed VECTORS")

        ;; --- P1: a SANE SCOPED count, NOT a raw-row dump, nodes present -----
        (is (pos? (:concept-count m))
            "the AUTONOMOUS transform produced a NON-EMPTY draft set (not a false-empty)")
        (is (< (:concept-count m) 6098)
            "the draft count is SCOPED — far below the 6,098 source rows (not a raw-row dump)")
        (is (pos? (:relationship-count m))
            "edges (relationship-drafts) were emitted between the concept nodes")

        ;; --- field-grounding: the AUTONOMOUS source uses the REAL keys ------
        (is (:references-cip? p)
            "the autonomous transform grounds field access in the REAL key CIP_Code")
        (is (:references-soc? p)
            "the autonomous transform grounds field access in the REAL key SOC_Code")
        (is (not (:references-invented? p))
            "the autonomous transform does NOT invent a key (the DT4 honest-negative is fixed)")

        ;; --- no abort, per-row errors counted (the V20 apply-step) ----------
        (is (= (:rows-ok report) (:rows-streamed report))
            "every row was applied (rows-ok = rows-streamed) — no abort")
        (is (zero? (:rows-errored report))
            "per-row errors were counted and the run had none (no abort, no false green)")))))
