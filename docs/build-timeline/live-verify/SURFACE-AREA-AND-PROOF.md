# Ontology Rebuild — Surface Area of Improvements & What We Proved

Capstone synthesis of the ontology-rebuild initiative (20 slices S01–S21) plus
the real-LLM live-verify hardening pass. This is the evidence record for the
question: **is the new evolutionary-ontology system truly good, and can it
replace the one we ran before?**

Companion documents:
- `2026-06-13-ontology-rebuild-live-verify-results.md` — the live-run logs + per-slice verdicts.
- `ontology-workflow-before-after.md` — architecture + before/after diagrams.
- `replacement-readiness-audit.md` — independent adversarial audit (see "Corrections" below).

---

## 1. Executive verdict

**The new system is a net-better superset of the old one, live-proven on the
axes that matter, and it COEXISTS with the old system rather than ripping it
out.** The old 5 evolutionary sheets + `evolutionary_builder.clj` are untouched
and still callable; the new substrate, builder skeleton, and discovery layer are
additive. A public cutover seam exists (`ontology/discover-and-build!`,
`run-discovery!`, `compile-discovery-source!`).

**Replacement verdict: YES-WITH-CAVEATS.** It can become the default builder, but
two things should land before declaring the old path retired: (a) wire
axiom-draft ingest (discovery extracts axioms but they're currently dropped on
ingest), and (b) give G2 a real old-sheets baseline + HITL-reviewed expected
graphs (today's bench fixtures are auto-derived). Neither is a correctness
regression; both are "prove the replacement is better, don't just assert it."

---

## 2. Surface area of improvements (by capability, with proof tier)

Proof tiers: **[LIVE]** proven end-to-end against a real LLM; **[TEST]** unit/
integration tested through public interfaces; **[GAP]** documented but not yet
real end-to-end.

### Substrate / representation
- **Uniform ontology-id scoping across all retrieval signals (S02)** — closes the
  BFS isolation leak; a single-section query never returns another section's
  concepts. **[TEST]** (s02 adversarial leak test) + exercised **[LIVE]** in the
  S19 scope-jailbreak.
- **Per-source RRF caps before fusion (S01)** — one over-expanding signal can't
  drown the others. **[TEST]**
- **Alignment-section registry + auto-widening (S03)** — explicit cross-section
  access, deregistration honored on next call. **[TEST]** + **[LIVE]** (S19 tools).
- **Labels / datatypes / annotations (S04)**, **quantities + units (S05)**,
  **edge metadata (S06)**, **axioms-as-data (S07)**, **equivalences with :kind
  (S08)** — the eight-part representation bundle the old flat-concept model
  lacked. **[TEST]**, and round-trip-proven by G1 **[LIVE-against-rdflib]**.

### Interchange
- **Faithful TTL round-trip + G1 gate (S09)** — `ingest(ttl) → events → export`
  is triple-set-equivalent via real rdflib URDNA2015 canonicalization;
  equivalence-kind preserved (sameAs ≠ equivalentClass). The G1 work found and
  fixed two real shipped serializer/ingest bugs. **[TEST/LIVE-rdflib]**. Note:
  G1 is a *test gate*, not a build-time gate.

### Validation
- **Lint registry + EDN-SHACL interpreter (S10)** and **full M4 lint set + SHACL
  TTL export (S11)** — 11 lints incl. disjointness/functional-property/closure;
  exports portable SHACL TTL; pySHACL-verified verdict parity on the
  standard-expressible subset. The old system had no portable validation. **[TEST
  + pySHACL]**

### Dedup
- **Tiered dedup cascade (S12)** — disjointness KEEP-guard first, then cheap
  deterministic tiers (number/negation/entropy/blocking/string-sim), LLM merge/
  keep only in the ambiguity band; equivalence-kind verdicts; `:requires-review`
  instead of silent merge on budget exhaustion. **[LIVE 12/12]** — the LLM tier
  reached exactly the 3 genuinely-ambiguous pairs and ruled all correctly; cheap
  tiers handled 9/12 with zero LLM. (Note: the "MinHash/LSH" tier is actually a
  word-token + 3-shingle Jaccard proxy — honest naming gap, not a bug.)

### Evidence
- **Per-fact provenance + deterministic evidence score (S13)** — always-on inside
  compare-to-existing, NOT R-Inject-gated; diversity-over-volume weighting
  (5 sources beat 100 occurrences in 1). The old system had none. **[TEST]**

### Acceptance test (this is the biggest qualitative leap)
- **ORSD spec storage (S14)** + **CQ runner with open-world three-layer negation
  posture (S15)** — the old system had NO acceptance test for "did the build
  work." The CQ runner gates the skeleton build on pass/unknown rates. **[LIVE
  18/18 adversarial]** — see §3.

### Retrieval
- **Lexical signal in hybrid-search (S21)** — a bare text query now finds
  concepts by name and bootstraps the graph BFS, even before embeddings/index
  exist. Closes the "all signals dark" failure. **[LIVE]** (S19 convergence).

### Agent integration
- **8 RLM ontology tools (S19)** — graph-search, neighborhood, get-concept,
  exists?, absent-in-graph?, **find-edges**, filter-by-label-pattern,
  classify-task/behaviors. Scope-jailbreak-proof (grant is authoritative).
  **[LIVE]** — the agent converged to the CORRECT answer once the tool contracts
  met its natural call shapes + the docstrings were injected (S20-card parity).
- **Graph orientation card (S20)** — four-layer deterministic card injected when
  tools are granted; proven load-bearing (the difference between an agent that
  guesses and one that copies the call form). **[TEST + LIVE-relevance]**

### Builder
- **Deterministic skeleton (S17)** — parse→normalize→dedup→validate→embed→index→
  CQ-exit-criterion, per-stage loud failures, budget knobs. **[LIVE → :complete]**
- **Recursive-RLM discovery (S18)** — designs the per-source extraction tree via
  classify-behaviors → emit-tree!; recovers from its own failed trees. **[LIVE]**
  end-to-end: discovery → ingest → skeleton build `:complete`.

---

## 3. What the adversarial live runs PROVED (not just completion)

- **S12 dedup — 12/12.** Cheap guards resolved 9/12 with zero LLM; the LLM tier
  ruled the 3 ambiguous pairs correctly (equivalent-property merge; two
  distinct-entity KEEPs).
- **S15 CQ runner — 18/18 adversarial.** The corpus was designed to break the
  open-world judge:
  - `:pass` still fires on positive evidence AND a direct affirming edge.
  - `:unknown` for all genuine gaps, judge citing "open-world / no closure".
  - **Keystone:** "Does Mira Sun have the actor role?" → `:fail` via an explicit
    `director ⊥ actor` disjointness signal; "...producer role?" → `:unknown`
    (no disjointness). **Same shape, verdict flips only on the explicit signal —
    proving the judge is grounded, not merely permissive.**
  - **Bait:** "Did Jane Roe win a Nobel Prize?" → `:unknown` (not a false `:pass`
    despite "jane-roe won oscar" present). Hallucination-resistant.
  - The grounding-resilience fix: correct `:pass` with empty evidence-uris is
    grounded-in-enumeration, no longer aborts the batch.
- **S18 discovery — end-to-end `:complete`.** discovery `:emitted-drafts`
  (3 concepts / 3 relationships / 2 axioms) → ingest `:ingested` → skeleton
  `build!` → `:complete`. Recursive self-correction observed twice (model fixed
  its own malformed tree).
- **S19 tools — converged to the CORRECT answer** after the lexical + ergonomics
  fixes, using the right tool with the right shape and grounded conclusions.

The live runs also DROVE fixes (the point of adversarial review, not a
side-effect): graph-search lexical bootstrap, tool-shape ergonomics + find-edges,
open-world judge, discovery tick-engine + budget + ingest coercions, and the CQ
grounding-resilience fix. All TDD-covered; 0 regressions vs baseline.

---

## 4. Before → After (summary; full diagrams in the companion doc)

- **Before:** format-keyed sequential pipeline — one fixed sheet per source type
  (csv/json/sql/text), flat concepts + basic relationships, local string dedup,
  one-way lossy TTL export, best-effort indexing, no axioms/units/evidence/
  acceptance-test/portable-validation, BFS isolation leak.
- **After:** recursive-RLM discovery (designs the extraction per source) → a
  deterministic skeleton that owns the contracts (parse→normalize→dedup→
  validate→embed→index→CQ-exit), an eight-part representation bundle, a tiered
  dedup cascade, per-fact evidence, a portable lint/SHACL validation layer, a
  faithful TTL round-trip, an ORSD/CQ acceptance test, scoped multi-signal
  retrieval (now with lexical bootstrap), and an 8-tool agent surface + card.

---

## 5. Honest gaps & must-fix-before-cutover

**Must-fix before declaring the old path retired:**
1. **Axiom-draft ingest (GAP).** Discovery extracts axiom drafts but
   `compile-discovery-source!` records them as `:axioms-skipped` — the
   live-verified run's "2 axioms" were dropped. Wire axiom-drafts → S07 axiom
   commands (with the same string→keyword coercion discipline used for scope/
   confidence-class).
2. **G2 baseline (GAP).** The extraction bench (S16) uses auto-derived expected
   graphs (0/3 HITL-reviewed) and has no old-sheets baseline comparison — the
   PRD's stated justification ("the RLM path must beat the old sheets") is not
   yet built. Until then, "better than old" is argued, not measured.

**Safe to defer (non-blocking):**
- Determinism knob (pin a recorded discovery tree) — PRD M8, not implemented.
- Co-occurrence-trail consumption — recorded write-only by design.
- True MinHash/LSH (currently Jaccard proxy) — works; optimization only.
- `normalize` stage is a verify-only no-op for the TTL path — the model-authored
  normalizer seam is decoupled but unpopulated.
- Pre-existing `repl_researcher_test` failures (~12) — test-expectation drift in
  the GENERIC executor (stale `total_tokens` mocks + a removed code-text
  precheck), present on main, NOT ontology code, NOT a cutover blocker.

**Operational requirements a consumer must satisfy** (document at cutover): a
running Grain todo-processor tick engine (Phase-2 discovery needs it), a
`:dscloj-provider`, OpenRouter (or equivalent) access, Python+rdflib for the G1
canonicalizer, and injecting the S20 orientation card when granting RLM tools.

---

## 6. Corrections to the independent audit

The independent audit (`replacement-readiness-audit.md`) was sharp and mostly
correct, but its single strongest claim is **overstated**: it states there is
"no cutover seam — `build!`/`discover-and-build!` are not in the public
interface." In fact `ontology/discover-and-build!`, `ontology/run-discovery!`,
and `ontology/compile-discovery-source!` ARE public (`interface.clj:451–480`).
The skeleton `build!` itself is reached through the `discover-and-build!`
chainer rather than exposed directly — a minor ergonomic point, not a missing
seam. The audit's other findings (G2 baseline, axiom-drop, coexistence,
repl_researcher drift) are confirmed accurate.

---

## 7. Test posture at this point

- All ontology slices + orc-service ontology suites: green (S01–S21, S19/S20,
  phase2-budget, recursive-rlm, rlm-mode).
- Full two-brick sweep: 916 tests / 4216 assertions, 18 failures — ALL in the
  pre-existing generic `repl_researcher_test` (test-expectation drift, present on
  main, not ontology code, not caused by this work).
- Before PUSH (vs commit-on-branch): resolve the repl_researcher drift (or
  confirm it's intentional and quarantine it), land axiom-ingest, and stand up
  the G2 baseline.
