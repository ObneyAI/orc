(ns ai.obney.orc.ontology.eb8-validate-cq-test
  "EB8 — Validate+CQ subbehavior INTEGRATION test (on-demand lane, `:dev:test`).

   This is the REAL-Grain + REAL-OpenRouter-LLM (CQ derivation + the S15 judge) +
   REAL-ColBERT/embedding-retrieval proof of the EB8 acceptance criteria — it is
   NOT on the fast `clj -M:poly test brick:ontology` gate (that gate carries the
   hermetic structure/contract/reuse + persist→read-back + controlled-judge gate
   test in `components/ontology/test/.../validate_cq_subbehavior_test.clj`). It
   lives here, on the `development/ontology-integration` on-demand lane, because it
   drives the REAL OpenRouter LLM (the `:llm` DERIVE node AND the S15 retrieve-then-
   judge gate) over a real child tick — a heavyweight, network-dependent live run
   that does NOT belong in the fast brick gate (the gate-hygiene rule: a test
   driving the real LLM is an INTEGRATION test).

   It runs the EXACT same path the live verify driver does
   (`development/src/eb8_validate_cq_subbehavior_live_verify.clj`) and asserts the
   EB8 acceptance through the subbehavior's PUBLIC `:reads`/`:writes` contract,
   reading the derived CQs off the PARENT tick blackboard + the persisted spec +
   the gate verdicts off the projection (discipline 7) — NOT from the execute
   return value:

     - the central tree DELEGATED to Validate+CQ and completed;
     - DERIVE: the `:llm` node derived a non-empty CQ set that crossed `:delegate`
       as a parsed VECTOR (surfaced for HITL review);
     - PERSIST: the CQs ARE the ORSD spec `get-ontology-spec` reads (#7);
     - GATE: the S15 runner judged the persisted CQs with the REAL LLM judge →
       per-CQ verdicts (read back via `get-cq-evaluation-latest`) + graph-health,
       with `:unknown` first-class;
     - OVERRIDE: consumer-supplied CQs persist instead of the derived set.

   Requires OPENROUTER_API_KEY. The captured evidence is
   `docs/build-timeline/live-verify/EB8-validate-cq.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [eb8-validate-cq-subbehavior-live-verify :as eb8]))

(deftest validate-cq-derives-persists-and-gates-live
  (testing "EB8 (REAL Grain + REAL OpenRouter derive/judge + REAL retrieval): the
            delegated Validate+CQ subbehavior derives grounded CQs, persists them as
            the ORSD spec the gate reads, and gates the graph semantically"
    (if-not (System/getenv "OPENROUTER_API_KEY")
      (println "[EB8 integration] OPENROUTER_API_KEY not set — skipping the live derive/judge run (reported honestly).")
      ;; BOUNDED run (#11): the live path drives OpenRouter (the DERIVE node + ~N
      ;; judge calls). future + deref timeout so a stall surfaces as a clear
      ;; assertion failure rather than hanging the suite.
      (let [fut (future (eb8/run-all! {}))
            r (deref fut 290000 ::timeout)
            _ (is (not= ::timeout r)
                  "the EB8 live run completed within the bounded timeout")
            r (if (= ::timeout r) {} r)
            a (:delegate r) b (:gate r) c (:override r)]
        ;; --- the delegated run completed via the public contract --------------
        (is (= :success (:central-status a))
            "the central tree delegated to Validate+CQ and completed")
        (is (get-in a [:registry :registry-match?])
            "the Validate+CQ sheet registered with a deterministic name→id round-trip")

        ;; --- DERIVE: a non-empty CQ set crossed :delegate as a parsed VECTOR ----
        (is (:derived-is-vector? a)
            "the derived CQs cross :delegate as a parsed VECTOR (the :llm-node C1 fix)")
        (is (seq (:derived-cqs a))
            "the DERIVE node produced a non-empty CQ set (surfaced for HITL review)")
        (is (every? string? (:derived-cqs a))
            "each derived CQ is a natural-language question STRING")

        ;; --- PERSIST: the CQs ARE the ORSD spec (read back, #7) ----------------
        (is (= (vec (:derived-cqs a)) (vec (:persisted-cqs a)))
            "the derived CQs ARE the ORSD spec's :competency-questions (the gate spec, #7)")
        (is (= eb8/the-goal (get-in a [:persisted-spec :purpose]))
            "the goal was stamped as the spec :purpose")

        ;; --- GATE: the S15 runner judged the persisted CQs semantically --------
        (is (= (count (:derived-cqs a)) (:evaluated-count b))
            "the S15 gate evaluated every persisted CQ")
        (is (= (count (:cq-verdict b)) (count (:cq-verdict-read-back b)))
            "the verdicts were read back from the projection (#7)")
        (is (every? #(contains? #{:pass :fail :unknown} (:verdict %)) (:cq-verdict b))
            "every verdict is one of the three-way :pass/:fail/:unknown (semantic, not a lint)")
        (is (some? (:graph-health b))
            "the graph-health metric was derived")
        (is (contains? (:graph-health b) :unknown-rate)
            "unknown-rate is a first-class graph-health metric (not folded into fail)")

        ;; --- OVERRIDE: consumer CQs persist instead of the derived set ----------
        (is (:override-held? c)
            "consumer-supplied CQs OVERRIDE the derived set (persisted as the ORSD spec)")
        (is (= eb8/consumer-override-cqs (:persisted-cqs c))
            "the SUPPLIED CQs (not the derived ones) persisted on the override run")))))
