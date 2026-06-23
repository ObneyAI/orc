(ns ai.obney.orc.ontology.eb10-central-evolver-test
  "EB10 — Central evolver loop INTEGRATION test (on-demand lane, `:dev:test`).

   This is the REAL-Grain + REAL-OpenRouter-LLM (Survey/Model/Extract/derive/ROUTE
   nodes + the S15 judge) + REAL-ColBERT/embedding-retrieval keystone proof of the
   EB10 acceptance — it is NOT on the fast `clj -M:poly test brick:ontology` gate
   (that gate carries the hermetic structure/composition/loop-logic test in
   `components/ontology/test/.../eb10_central_evolver_test.clj`). It lives here, on
   the `development/ontology-integration` on-demand lane, because it drives the REAL
   OpenRouter LLM across many `:delegate` child ticks + the S15 retrieve-then-judge
   gate — a heavyweight, network-dependent live run that does NOT belong in the fast
   brick gate (the gate-hygiene rule: a test driving the real LLM is an INTEGRATION
   test).

   It runs the EXACT path the live verify driver does
   (`development/src/eb10_central_evolver_live_verify.clj`) and asserts the EB10
   acceptance through the central evolver's PUBLIC surface — the composed central
   tree `:delegate`s to the REAL subbehaviors end-to-end to a CQ verdict on a real
   source, the loop is the CQ-objective (gate in-process with the real judge), and
   the loop ALWAYS terminates with a surfaced reason.

   Requires OPENROUTER_API_KEY. The captured evidence is
   `docs/build-timeline/live-verify/EB10-central-loop.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [eb10-central-evolver-live-verify :as eb10]))

(deftest central-evolver-runs-real-subbehaviors-to-a-cq-verdict-live
  (testing "EB10 (REAL Grain + REAL OpenRouter + REAL retrieval): the composed
            central evolver :delegate`s to the real EB2-EB9 subbehaviors end-to-end
            to a CQ verdict on a real source; the CQ gate is the loop OBJECTIVE
            (run in-process with the real judge); the loop ALWAYS terminates"
    (if-not (System/getenv "OPENROUTER_API_KEY")
      (println "[EB10 integration] OPENROUTER_API_KEY not set — skipping the live run (reported honestly).")
      ;; BOUNDED run (#11): the live path drives OpenRouter across many child ticks
      ;; + the S15 judge. future + deref timeout so a stall surfaces as a clear
      ;; assertion failure rather than hanging the suite.
      (let [fut (future (eb10/run-all! {}))
            r (deref fut 580000 ::timeout)
            _ (is (not= ::timeout r)
                  "the EB10 live run completed within the bounded timeout")
            r (if (= ::timeout r) {} r)]
        ;; --- the central evolver completed end-to-end ---
        (is (contains? #{:complete :failed-cq} (:status r))
            "the central evolver ran to a CQ verdict (a pass or an honest failed-cq)")
        (is (= :greenfield (get-in r [:branch-points :greenfield-vs-maintain :selected]))
            "a fresh ontology-id selects the greenfield arm (full evolver runs)")

        ;; --- SURVEY: the per-source profile crossed :delegate as a parsed map ---
        (is (seq (:survey-profiles r))
            "Survey delegated per source and produced a profile")
        (is (every? map? (:survey-profiles r))
            "each survey profile crossed :delegate as a parsed MAP (not a JSON string)")

        ;; --- DERIVE: a non-empty CQ set was derived (HITL) ---
        (is (seq (:competency-questions r))
            "the Validate+CQ DERIVE produced a non-empty CQ set (surfaced for HITL)")

        ;; --- the graph was BUILT through the real subbehaviors ---
        (is (pos? (:concepts-landed r))
            "the real Extract/Reconcile subbehaviors landed concepts (no false-empty)")

        ;; --- the CQ verdict is the OBJECTIVE (run in-process with the real judge) ---
        (is (seq (:cq-verdict r))
            "the in-process S15 gate produced per-CQ verdicts (the OBJECTIVE)")
        (is (every? #(contains? #{:pass :fail :unknown} (:verdict %)) (:cq-verdict r))
            "every verdict is one of the three-way :pass/:fail/:unknown (semantic)")
        (is (some? (:graph-health r))
            "the graph-health metric was derived")
        (is (contains? (:graph-health r) :unknown-rate)
            ":unknown-rate is first-class in graph-health")

        ;; --- the loop ALWAYS terminates with a surfaced reason ---
        (is (contains? #{:cq-gate-passed :all-remaining-unanswerable :budget-exhausted}
                       (get-in r [:cq-loop :termination-reason]))
            "the loop terminated with a surfaced reason (never spins, never false-green)")
        ;; --- no false green: a :failed-cq status carries an honest reason ---
        (when (= :failed-cq (:status r))
          (is (contains? #{:all-remaining-unanswerable :budget-exhausted}
                         (get-in r [:cq-loop :termination-reason]))
              "a :failed-cq is an HONEST termination (unanswerable / budget), not a fake green"))))))
