# Replacement-Readiness Audit — NEW evolutionary-ontology system vs OLD

*Independent, adversarial, read-only review. Question asked: not "is it done?" but
"can a consumer SAFELY switch off the OLD ontology system and onto the NEW one, and
is the NEW one genuinely BETTER?" Every claim cites real file:line. Where the peer
before/after doc or the PRD overstated something, it is called out.*

Date: 2026-06-15 · Branch: `feature/ontology-architecture`

---

## TL;DR Verdict

**NOT-YET a *replacement*; YES as a *coexisting superset that is net-better on representation, validation, and interchange*.**

The framing in the prompt — "replace the OLD system" — is the wrong frame for the
current code state, and that is the single most load-bearing finding: **the NEW
system does not replace anything. It coexists.** The OLD `build-from-sources` /
`evolve` path and all 5 sheets are untouched and still the only thing wired into the
public `interface.clj`. The NEW skeleton (`build!`) and discovery (`discover-and-build!`)
are **not exposed in the public interface at all** — they are reachable only via
internal namespaces. So "switching" today is not a button a consumer can press; it is
an integration project they would have to wire themselves.

That is actually the *safe* posture (zero regression risk to existing consumers), but
it means the honest answer to "can it replace the old system?" is: **the parts that
would let it replace the old system are real and mostly proven, but the cutover seam
(public API, the G2 baseline comparison, axiom ingest) is not finished.**

---

## 1. Coexistence vs replacement

**Verdict: COEXIST. No replacement, no migration path, no breakage risk — but also no cutover.**

- OLD builder untouched: `evolutionary_builder.clj` last touched 2026-06-09 with
  fixes only (nil embedding-fields, UUID normalization, JSON wiring) — no breaking
  refactor (`git log` on the file). Public entry points `build-from-sources`
  (`evolutionary_builder.clj:644`) and `evolve` (`:853`) intact.
- All 5 sheets intact and still dispatched by the OLD builder via `requiring-resolve`
  (`evolutionary_builder.clj:137-175`); each exposes its `build-*-pipeline!` /
  `run-*` entry points.
- Public interface exposes ONLY the OLD path: `build-ontology-from-sources`
  (`interface.clj:~2300`) → resolves `evolutionary-builder/build-from-sources`;
  `evolve-ontology` (`:~2319`) → `evolutionary-builder/evolve`. The NEW `build!`
  (`deterministic_skeleton.clj:469`) and `discover-and-build!` (`rlm_discovery.clj:506`)
  are **not in interface.clj**.
- The skeleton itself documents the coexistence stance: "the existing sheets keep
  working unchanged. The skeleton coexists; deprecation is Phase-4 work"
  (`deterministic_skeleton.clj:62-66`).

**Consequence:** switching is safe (old keeps working) but is NOT a supported,
documented motion. A consumer who wants the new substrate must call internal core
namespaces and wire embed/index/event-store/CQ themselves. There is no
`interface.clj`-level "build with the new pipeline" entry, and no migration guide.

---

## 2. Regression risk on the OLD self-learning path + RLM bench

**Verdict: LOW regression risk; the one red flag is NOT in the ontology system.**

- The OLD three-layer Failure/Success/Problem framing (`docs/ONTOLOGY.md:18-24`) and
  the RLM generalization bench live in `development/bench/` and the static/failure
  ontologies — untouched by the rebuild.
- The rebuild DID touch the shared `executor.clj` (commit `828095c0` "Live-verify
  hardening" + `2345d097` keyword-normalization). This is the generic repl-researcher
  executor that the RLM bench and self-improving loop ride on — so it is a real shared
  blast radius, not isolated to ontology.
- **The 12 `repl_researcher_test` failures are pre-existing test-expectation drift in
  that shared executor, NOT ontology code, and NOT new** (see §7). They exist on
  `main` too.
- The S18 live-verify also patched `executor.clj` (`resolve-phase2-budget`) — a real
  product fix to the recursive Phase-2 budget path that benefits the bench/loop.

So the rebuild's risk to the OLD headline use is concentrated in the shared executor,
and the only failing tests there are stale mocks (§7), not behavioral regressions.

---

## 3. PROVEN live vs unit-tested vs PRD-only

**(a) Proven end-to-end against a real LLM** (`2026-06-13-...-live-verify-results.md`):
- S12 dedup cascade — 12/12, cheap tiers handled 9 with zero LLM, LLM tier ruled the
  3 ambiguous pairs correctly (lines 32-61).
- S15 CQ runner open-world posture — applied + live-verified; Mira-Sun flips to
  `:unknown` (lines 453-483).
- S19 tools — run #6 reached the CORRECT answer with grounded tool use (lines 266-309).
- S18 discovery — END-TO-END: `:emitted-drafts` (3 concepts/3 rels/2 axioms) →
  `compile-discovery-source!` `:ingested` → skeleton `build!` `:complete` (lines 439-451).

**(b) Shape/unit-tested only:**
- G1 TTL round-trip (8 deftests, `s09_ttl_round_trip_test.clj`) — real rdflib URDNA2015
  canonicalization, but never run against a real consumer's arbitrary TTL.
- All 11 M4 lints (`lints/builtin.clj`) — positive/negative fixtures, not live-task verified.
- Evidence scoring determinism (`evidence.clj:35-168`) — pure-fn tests.

**(c) PRD-claimed but NOT in code** (be ruthless):
- **Determinism knob (pin a recorded discovery tree), PRD M8 / story 24** —
  ABSENT. No pin/record/replay machinery in `rlm_discovery.clj`. Peer doc flagged
  this; CONFIRMED absent.
- **Concept-pair co-occurrence recorded as events "from day one", PRD M5** — ABSENT.
  No `co-occur*` anywhere in cascade/skeleton/evidence. CONFIRMED absent.
- **Axiom ingest** — discovery produces axiom drafts but they are recorded as
  `:axioms-skipped` and never dispatched to the S07 command surface
  (`rlm_discovery.clj:455-460,493`). So the S18 live run's "2 axioms" were extracted
  but DROPPED on ingest. This is the most material (c): the representation's
  headline new capability (axioms-as-data) is NOT reachable through the discovery
  path that the live-verify celebrated.

---

## 4. The honest gaps — verify each

| Peer-flagged gap | Verdict | Evidence |
|---|---|---|
| Determinism knob not implemented | **CONFIRMED absent** | no pin/replay fn in `rlm_discovery.clj` |
| Co-occurrence unverified | **CONFIRMED absent** | no `co-occur*` in cascade/evidence/skeleton |
| Axiom drafts not ingested | **CONFIRMED, and worse than framed** — it silently drops the live-extracted axioms | `rlm_discovery.clj:455-460,493` |
| MinHash is actually Jaccard | **CONFIRMED** — deterministic word-token + 3-shingle Jaccard, no hashing/sketching | `dedup_cascade.clj:176-192` |
| normalize is a no-op for non-TTL | **CONFIRMED** — verify-and-summarize for TTL; the non-TTL normalizer seam is empty | `deterministic_skeleton.clj:158-181` |

**Additional gaps I found:**
- **G2 ground truth is circular** (see §5) — the expected graphs are `:auto-derived`
  (seeded from skeleton output), 0/3 HITL-reviewed (`extraction-RESULTS.md`).
- **No public-interface entry for the new path** (§1) — the cutover seam is missing.
- **S18 live-verify uncovered THREE harness/product plumbing bugs** (tick engine not
  started, budget mis-wired, scope/confidence-class coercion) that were only found
  because of a live run (live-verify lines 370-451). The fixes are real, but it means
  the discovery path was fragile right up until the live run — recently stabilized,
  thinly proven (one 555-char source).

---

## 5. The two hard gates — real or theater?

**G1 (TTL round-trip): REAL implementation, ENFORCED-AS-TEST-ONLY, with an external dependency.**
- `ttl_canonicalize.clj:13,76,98-100` shells out to `python3` + `rdflib`
  (`to_canonical_graph`, URDNA2015). Real, standards-based. Missing rdflib →
  anomaly, no silent fallback (`:104`).
- 8 deftests assert triple-set equivalence incl. equivalence-kind preservation
  (`s09_ttl_round_trip_test.clj:335-509`).
- **But it is a test, not a build gate.** Nothing wires G1 into `build!`'s exit path.
  A build CAN complete with a wrong graph as far as G1 is concerned — G1 only fires
  when someone runs the ontology test brick. Not theater (the test is rigorous), but
  it does not *gate the build* the way the before/after doc's "hard `ingest→export`
  gate" phrasing implies.

**G2 (extraction bench): REAL harness, NOT a gate, and the ground truth is circular — THEATER-RISK.**
- `passes-G2?` exists (`development/bench/extraction/harness.clj:292`): `(empty? missing)
  AND cq-pass-rate ≥ 0.8`.
- 3/3 fixtures "pass" — BUT all 3 expected graphs are `:auto-derived` from the skeleton
  itself, 0/3 HITL-reviewed (`extraction-RESULTS.md`). Passing G2 currently proves
  the skeleton is **self-consistent**, not correct.
- **No old-sheets baseline comparison exists.** The PRD's defining G2 claim — "the old
  sheets as the baseline the RLM path must beat" (PRD `:491-495`; story 25) — is NOT
  implemented. The harness compares skeleton output to its own auto-derived expected
  TTL only. So the central justification for replacing the old path ("the new path
  beats the old on a real bench") **has not been demonstrated.**

This is the most serious single finding: the one gate whose entire purpose is to
license the replacement does not do the comparison that would license it.

---

## 6. Production-readiness blockers if a consumer switched tomorrow

1. **No public entry point** — they'd call internal core namespaces (§1).
2. **Discovery Phase-2 needs a running Grain tick engine.** The discovery harness bug
   (`tp/start` never called → Phase 2 hangs the full budget, live-verify lines 394-416)
   was a harness gap, but it documents the requirement: a consumer MUST run their
   todo-processor pipeline + thread `:dscloj-provider` or discovery silently times out.
   This is NOT documented for consumers anywhere I found.
3. **G1 hard dependency on Python3 + rdflib** — TTL round-trip / canonicalization breaks
   without it; no JVM fallback (`ttl_canonicalize.clj:76`).
4. **OpenRouter / `:dscloj-provider` requirement** for discovery, dedup LLM tier, CQ judge.
5. **S20 orientation card is load-bearing, not optional** — the live-verify proved the
   agent guesses tool shapes and fails without the docstring injection (lines 285-309).
   A consumer granting ontology tools MUST inject the card or autonomous use collapses.
   This is a real operational constraint, honestly surfaced.
6. **Axioms extracted by discovery are dropped on ingest** (§3c) — a consumer expecting
   the "axioms-as-data" capability through the build path won't get it yet.

---

## 7. The repl_researcher_test failures

**Verdict: pre-existing test-expectation drift in the GENERIC executor — NOT ontology, NOT a product bug, does NOT block replacement.**

- 12 failures / 8 tests (`repl_researcher_test.clj`). Two clusters:
  - **Plain-text `FINAL_ANSWER` in `:code`** (`immediate-final-answer-in-code-text-test:52`,
    `tool-call-then-answer-test:89`, `namespaced-tools-test`, `immediate-final-answer...`):
    the executor was deliberately changed to "always execute, even if code contains
    FINAL_ANSWER pattern" (`executor.clj:1384`) and now detects FINAL_ANSWER only in
    the SCI execution result/stdout (`:1396-1399`). The mocks still return
    `:code "FINAL_ANSWER: 42"` as plain text, which is not valid Clojure → errors in
    the sandbox → never converges. The test NAME ("caught in code-text check") refers
    to a removed precheck. **Stale mock, not a bug.**
  - **`usage-tracking-test:133-135`** expects 300/150/450 but gets 400/200/600 — exactly
    one extra iteration, the downstream symptom of the same removed short-circuit.
- These exist on `main` (the test file is unchanged since `2345d097`, and the failing
  test is present in `main`'s version) — so they are **not introduced by the rebuild**.
- They are a hygiene debt on the generic RLM executor that the rebuild rode on top of,
  worth fixing for signal cleanliness, but orthogonal to the ontology replacement decision.

---

## VERDICT

### Can it replace the old system?
**NOT-YET (as a drop-in replacement); YES-WITH-CAVEATS as a coexisting, net-better superset.**

Three load-bearing reasons:
1. **There is no cutover seam.** The new pipeline is not in the public interface; the
   old path is the only one wired (§1). "Replacement" isn't a decision a consumer can
   currently act on.
2. **The gate that would license replacement doesn't do its job.** G2 has no old-sheets
   baseline and uses circular auto-derived ground truth (§5) — "the new path beats the
   old" is asserted in the PRD but never demonstrated.
3. **A headline new capability is dropped on the build path.** Discovery extracts axioms
   but ingest skips them (§3c, §4) — so the representation upgrade isn't fully reachable
   end-to-end yet.

### Net-better axes (clear wins, evidence-cited)
- **Representation fidelity** — 8-part bundle with full TTL round-trip incl.
  equivalence-kind preservation, live-tested via rdflib URDNA2015
  (`s09_ttl_round_trip_test.clj`, `ttl_canonicalize.clj`).
- **Dedup correctness** — tiered cascade with disjointness KEEP-guard, number/negation
  guards, `:requires-review` instead of silent merge, live 12/12 (`dedup_cascade.clj`;
  live-verify S12).
- **Acceptance test + portable validation** — CQ runner with calibrated open-world
  `:unknown` (live-verified) + 11 SHACL-shaped lints with real SHACL export
  (`cq_runner.clj`, `lints/builtin.clj`, `lints/interpreter.clj`). The OLD system had
  NO acceptance test and no portable validation.

### Regression / parity risks
- Shared `executor.clj` is in the blast radius (§2); its only failing tests are stale
  mocks (§7) but the surface is shared with the bench/self-improving loop.
- Discovery path is thinly proven (one 555-char source, after a chain of just-fixed
  plumbing bugs) — fragile-until-recently, not battle-tested.
- Old↔new behavioral parity on real extraction is **unmeasured** (no G2 baseline).

### Must-fix-before-cutover (ordered)
1. **Implement the G2 old-sheets baseline comparison + get HITL-reviewed expected
   graphs** (PRD `:491-495`). Without it, replacement is vibes, not verified.
2. **Wire axiom-draft ingest** into the S07 command surface (`rlm_discovery.clj:455-460`)
   so the discovery path delivers the representation it advertises.
3. **Expose a public-interface entry** for the new build/discover path + document the
   tick-engine, `:dscloj-provider`, rdflib, and orientation-card requirements for consumers.
4. **Decide G1/G2 enforcement**: wire round-trip + bench as actual build/CI gates, or
   explicitly document them as developer-run checks (today they are test/harness-only).

### Safe-to-defer
- Determinism knob (PRD M8) — absent, but only matters for consumers needing
  reproducible builds; not a correctness blocker.
- Co-occurrence event recording (PRD M5) — absent; explicitly a "activates later"
  feature in the PRD itself.
- MinHash vs Jaccard — functionally adequate at current scale; rename/optimize later.
- non-TTL `normalize` seam — empty but decoupled; populate when a non-TTL adapter needs it.
- The 12 `repl_researcher_test` stale mocks — fix for signal hygiene, not blocking.

---

## Overstated vs actual code (peer doc + PRD)

- **PRD/peer "hard `ingest→export` gate" and "build gated on CQ acceptance"** — partly
  overstated. CQ DOES gate `build!`'s exit (`deterministic_skeleton.clj:436-443`, real).
  But G1 round-trip is a **test, not a build gate**; the phrasing implies the build is
  pinned by it.
- **PRD G2 "old sheets as the baseline the RLM path must beat"** — OVERSTATED / not built.
  No baseline comparison exists; ground truth is auto-derived (§5).
- **Peer doc "axiom drafts not yet ingested" (listed as a minor gap)** — UNDERSTATED.
  It means the live-verified discovery run's extracted axioms are silently dropped; the
  representation upgrade isn't reachable through the proven path.
- **Peer doc Capability-Delta "Acceptance test … build gated"** — accurate for CQ; just
  note G1 isn't a build gate.
- The peer doc's own "Adversarial Note" (determinism knob, co-occurrence, MinHash) is
  honest and I CONFIRMED all of it. Credit where due — it did not whitewash.
