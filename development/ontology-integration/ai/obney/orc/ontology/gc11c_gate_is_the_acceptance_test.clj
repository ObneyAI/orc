(ns ai.obney.orc.ontology.gc11c-gate-is-the-acceptance-test
  "GC-11c — the acceptance verdict reads the CQ-GATE, NOT the connectivity-proof.

   The original goal's finish line is: a connected graph whose CQ-GATE ANSWERS
   the cross-source competency question over the spine. `connectivity-proof`
   (`find-connectivity-chain`) is a spine-aware DEBUG/observability aid — a
   hand-rolled program→cip→soc walk that bakes in role-guessing and a
   `skos:narrower` hierarchy hop. It must NOT be the acceptance gate (that would
   make a domain-shaped chain-walker the arbiter of success). The acceptance is
   the CQ-gate verdict (`:cq-verdict` — the S15 retrieve-then-judge runner).

   These cycles TDD the `acceptance-verdict` PUBLIC fn on SYNTHETIC captured-map
   fixtures (Discipline 2/6 — no Grain, no LLM). The load-bearing property:
     - the verdict reads the CQ-gate (a graph whose gate ANSWERED the
       cross-source CQ PASSES even with `connectivity-proof` reporting
       :no-complete-chain — because connectivity-proof is DEBUG, not the gate),
     - the verdict FAILS when the CQ-gate did NOT answer (no :pass verdict),
     - the verdict does NOT depend on `:connectivity` (flipping connectivity
       between a real chain and :no-complete-chain does NOT change :pass?),
     - the verdict does NOT depend on `graph-health/:fragmented?` (GC-11c demoted
       `:one-connected-graph` to a NON-GATING debug signal — the spine deliberately
       shares identity-tails between a code-node and the concept it joins, making
       `:fragmented?` a false positive; flipping it does NOT change :pass?)."
  (:require [clojure.test :refer [deftest testing is]]
            [eb12-graph-b-central-evolver :as b]))

;; =============================================================================
;; Base captured map — an honest, connected, non-fragmented graph with real kinds.
;; The CQ-gate ANSWERED the cross-source CQ (a judge-grounded :pass with evidence).
;; =============================================================================

(def ^:private gate-answered-cq-verdict
  [{:cq-index 0
    :cq-text "Which occupations do the educational programs lead to?"
    :verdict :pass
    :reasoning "The program concept is identified-by a code node that the
                crosswalk row is also identified-by, which in turn bridges to the
                occupation via a second code node — the cross-source path is in
                the retrieved evidence."
    :evidence-uris ["program/p1" "code/cipA" "crosswalk/c1" "code/socB" "occupation/o1"]
    :judged-by? true
    :layer :layer-2-semantic-exists}])

(def ^:private gate-answered-capture
  {:status :complete
   :cq-verdict gate-answered-cq-verdict
   :stats {:concept-count 1842
           :concepts-by-kind {:institution 412
                              :degree_program 980
                              :field_of_study 220
                              :soc_occupation 230}
           :graph-health {:fragmented? false
                          :fragmented-identity-count 0
                          :fragmented-identities []}}
   ;; connectivity-proof present but DEBUG-only — the verdict must not read it.
   :connectivity {:program {:uri "degree_program/236753-51.3801"}
                  :cip {:uri "field_of_study/51.3801"}
                  :soc {:uri "soc_occupation/29-1141"}}})

;; =============================================================================
;; Cycle 2a — the CQ-gate is THE acceptance: a graph whose gate ANSWERED the
;; cross-source CQ PASSES, and the criterion set includes the gate-answers
;; criterion (NOT a connectivity chain-reads-back criterion).
;; =============================================================================

(deftest gate-answered-cross-source-cq-passes
  (testing "a graph whose CQ-gate ANSWERED (a judge-grounded :pass verdict) reads
            :pass? true, and the criteria are gated on the CQ-GATE answering — not
            on the connectivity-proof chain walk"
    (let [{:keys [pass? reasons]} (b/acceptance-verdict gate-answered-capture)
          criteria (set (map :criterion reasons))]
      (is (true? pass?) "the gate-answered graph must PASS")
      (is (contains? criteria :cq-gate-answers)
          "the acceptance includes the CQ-gate-answers criterion (the finish line)")
      (is (not (contains? criteria :chain-reads-back))
          "the connectivity-proof chain-reads-back is NO LONGER an acceptance
           criterion — it is demoted to a DEBUG aid"))))

;; =============================================================================
;; Cycle 2b — the gate is load-bearing: when the CQ-gate did NOT answer (no :pass
;; verdict — e.g. every CQ is :unknown), the acceptance FAILS on the gate
;; criterion, EVEN IF connectivity-proof reports a chain.
;; =============================================================================

(deftest gate-did-not-answer-fails-even-with-a-connectivity-chain
  (testing "when the CQ-gate did NOT answer (no :pass — all :unknown), the verdict
            FAILS on :cq-gate-answers even though connectivity-proof reports a real
            chain — the GATE is the arbiter, not the chain walk"
    (let [unknown-capture
          (assoc gate-answered-capture
                 :cq-verdict [{:cq-index 0
                               :cq-text "Which occupations do programs lead to?"
                               :verdict :unknown
                               :reasoning "the graph lacks program→occupation facts"
                               :evidence-uris []
                               :judged-by? true
                               :layer :layer-3-explicit-unknown}])
          {:keys [pass? reasons]} (b/acceptance-verdict unknown-capture)
          failing (->> reasons (remove :pass?) (map :criterion) set)]
      (is (false? pass?) "no :pass verdict → the build must FAIL")
      (is (contains? failing :cq-gate-answers)
          "the failing criterion is the CQ-gate, not connectivity"))))

;; =============================================================================
;; Cycle 2c — THE demotion (RED revert guard): the verdict does NOT depend on
;; :connectivity. Flipping connectivity from a real chain to :no-complete-chain
;; does NOT change :pass? — because the gate answered. (Reverting the demotion —
;; re-adding a :chain-reads-back hard criterion that reads :connectivity — would
;; turn this RED.)
;; =============================================================================

(deftest verdict-is-independent-of-connectivity-proof
  (testing "the acceptance :pass? is INVARIANT to the connectivity-proof: a
            gate-answered graph PASSES whether connectivity reports a real chain
            OR :no-complete-chain — connectivity-proof is DEBUG, not the gate"
    (let [with-chain    gate-answered-capture
          without-chain (assoc gate-answered-capture
                               :connectivity {:no-complete-chain true
                                              :roles-detected {}
                                              :program-count 980})
          v-chain    (b/acceptance-verdict with-chain)
          v-no-chain (b/acceptance-verdict without-chain)]
      (is (true? (:pass? v-chain))  "passes WITH a connectivity chain")
      (is (true? (:pass? v-no-chain))
          "STILL passes with :no-complete-chain — the gate answered; connectivity
           is debug-only and must not flip the verdict")
      (is (= (:pass? v-chain) (:pass? v-no-chain))
          "the verdict is INVARIANT to the connectivity-proof (the demotion)"))))

;; =============================================================================
;; Cycle 2d — the REAL honest-negative floors still hold (no false-green
;; regressions): a crash terminal and a 0-concept build still FAIL, independent of
;; whatever the CQ-gate reports. (Fragmentation is NO LONGER such a floor — see 2e.)
;; =============================================================================

(deftest crash-and-zero-build-still-fail-independent-of-the-gate
  (testing "a non-honest terminal and a 0-concept build still FAIL even if the
            CQ-gate reports a :pass — the structural floors are preserved"
    (let [crash (assoc gate-answered-capture :status :error)
          zero (assoc gate-answered-capture
                      :stats {:concept-count 0 :concepts-by-kind {}
                              :graph-health {:fragmented? false}})
          fail-set (fn [cap] (->> (b/acceptance-verdict cap) :reasons
                                  (remove :pass?) (map :criterion) set))]
      (is (false? (:pass? (b/acceptance-verdict crash))))
      (is (contains? (fail-set crash) :honest-terminal)
          "a crash terminal still fails honest-terminal regardless of the gate")
      (is (false? (:pass? (b/acceptance-verdict zero))))
      (is (contains? (fail-set zero) :non-zero-build)
          "a 0-concept build still fails the non-zero floor"))))

;; =============================================================================
;; Cycle 2e — THE graph-health demotion (RED revert guard). GC-11c proved the
;; GC-11b spine deliberately mints code-nodes that share an identity-tail with the
;; concept they join (`soccode/39-2021` + `occupation/39-2021`), so
;; `graph-health/:fragmented?` is GUARANTEED true on any spine-connected graph (a
;; false positive — empirically all 42 flags on the EB12 artifact were spine joins).
;; Per the locked D3 decision the SEMANTIC CQ-gate is THE acceptance, so
;; `:one-connected-graph` is NON-GATING: flipping `:fragmented?` true↔false does
;; NOT change `:pass?` when the gate answered. (Reverting the demotion — re-adding
;; `:fragmented?` to the hard AND — turns this RED.)
;; =============================================================================

(deftest verdict-is-independent-of-graph-health-fragmented
  (testing "the acceptance :pass? is INVARIANT to graph-health/:fragmented?: a
            gate-answered graph PASSES whether :fragmented? is false OR true —
            the spine makes fragmented? a false positive; the gate is the gate"
    (let [clean    gate-answered-capture
          flagged  (assoc gate-answered-capture
                          :stats (assoc-in (:stats gate-answered-capture)
                                           [:graph-health :fragmented?] true))
          v-clean   (b/acceptance-verdict clean)
          v-flagged (b/acceptance-verdict flagged)]
      (is (true? (:pass? v-clean))   "passes with :fragmented? false")
      (is (true? (:pass? v-flagged))
          "STILL passes with :fragmented? true — the gate answered; graph-health
           is a NON-GATING debug aid and must not flip the verdict")
      (is (= (:pass? v-clean) (:pass? v-flagged))
          "the verdict is INVARIANT to graph-health/:fragmented? (the demotion)")
      ;; the criterion is still SURFACED for observability — just not gating
      (let [reason (->> (:reasons v-flagged)
                        (filter #(= :one-connected-graph (:criterion %))) first)]
        (is (some? reason) "the :one-connected-graph signal is still reported")
        (is (false? (:gating? reason))
            "and it is explicitly marked :gating? false")))))
