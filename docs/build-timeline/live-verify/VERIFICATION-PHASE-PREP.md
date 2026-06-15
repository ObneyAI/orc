# Verification-Phase Prep — Notes Into the Next Grill

Consolidated notes to seed the verification-phase grill. Pulls together: where
the ontology rebuild stands, what's live-proven vs gapped, the intent-alignment
findings, an important architecture-framing clarification, and the open
questions the grill should resolve to define "verified."

Companion docs (all under `docs/build-timeline/live-verify/`):
- `SURFACE-AREA-AND-PROOF.md` — every improvement + proof tier.
- `ontology-workflow-before-after.md` — before/after architecture + diagrams.
- `replacement-readiness-audit.md` — independent adversarial audit.
- `intent-alignment-checklist.md` / `intent-alignment-verdict.md` — grills vs build.
- `2026-06-13-ontology-rebuild-live-verify-results.md` — live-run logs.

---

## 0. Framing correction: "deterministic skeleton" ≠ "no LLM"

We are STILL building the ontology/taxonomy WITH LLMs. Two layers (the round-2/3
"hybrid Path 2" decision):

- **Deterministic skeleton = the orchestration spine.** Fixed stages
  `parse → normalize → dedup → validate → embed → index → exit-criterion`, fixed
  order, loud per-stage failures, two gates (G1/G2). This is what makes a build
  reproducible, gateable, debuggable. It is NOT an LLM chain (the OLD builder
  was one). It is the contract-owner.
- **LLM/RLM work happens INSIDE the stages:**
  - **Discovery (S18)** — recursive-RLM designs the per-source extraction tree
    and does the actual concept/relationship/axiom extraction (heavily LLM).
    Discovery FEEDS the skeleton.
  - **Dedup cascade (S12)** — LLM merge/keep judge in the ambiguity band (tier 9).
  - **CQ runner (S15)** — LLM judge for semantic verdicts.
  - Deterministic-only stages: normalize, evidence (S13), lints (S10/11),
    embed/index orchestration, the cascade's cheap tiers.

PRD wording: *"skeleton owns the contracts … discovery phases recursive-RLM
ONLY."* The verification phase must verify BOTH axes: (a) the deterministic
contracts/gates hold, and (b) the LLM-discovery produces good ontologies.

---

## 1. Where we are (committed on `feature/ontology-architecture`)

**20 slices shipped** (S01–S21) + a live-verify hardening pass. Recent commits:
- `828095c0` live-verify hardening (S21 lexical, S19 ergonomics+find-edges,
  S15 open-world, S18 discovery execution)
- `dea0809d` S15 grounding resilience + adversarial CQ live verify
- `3fb2b411`, `6898944b`, capstone + intent docs

**Test posture:** ontology + orc-service ontology suites green. Full two-brick
sweep: 916 tests / 4216 assertions, **18 failures — all pre-existing
`repl_researcher_test` drift** (generic executor, present on main, not ontology
code, not ours; proven by stash-and-compare).

**Live-proven (real LLM):** S12 dedup 12/12 · S15 CQ runner 18/18 adversarial ·
S18 discovery end-to-end → skeleton `build! :complete` · S19 tools converge to
correct answer.

---

## 2. Intent alignment vs the four pillars (the yardstick)

Yardstick: *"any source (csv/sql/text) → general ontology for any retrieval;
auto-find fields to embed via embedding model or ColBERT; search via
BFS+embeddings+ColBERT late-interaction; discover/learn/maintain/access."*

| Pillar | Verdict |
|---|---|
| P3 — BFS+embedding+ColBERT fused search | ✅ Aligned & improved (BFS leak fixed S02, lexical bootstrap S21) |
| P4 — discover/learn/maintain/access | ✅ Aligned (axiom-ingest gap aside) |
| P1 — any source → general ontology | ⚠️ Substrate/frame aligned; NEW path live-proven on TEXT only |
| P2 — auto-detect embed/ColBERT fields | ⚠️ Capability EXISTS in old builder; NOT wired into new skeleton |

---

## 3. The gap inventory (what the verification phase must close or accept)

Ordered by intent-importance:

1. **P2 — auto-embed/ColBERT field detection not in the new skeleton.**
   `detect-embeddable-fields` / `analyze-fields-for-embedding` /
   old-builder `:auto-detect-colbert-fields?` exist, but
   `deterministic_skeleton.clj:362-390` embed/index stages default to
   skip/delegate and never call them. Named directly in the goal.
2. **P1 — discovery proven on TEXT only.** csv/sql/json generality of the
   discovery+skeleton path is unverified (old sheets cover formats).
3. **Discover-axiom ingest dropped.** Discovery extracts axioms; ingest records
   `:axioms-skipped` (`rlm_discovery.clj`). Discovered axioms never reach S07.
4. **G2 has no real baseline.** Extraction bench (S16) uses auto-derived expected
   graphs (0/3 HITL-reviewed), no old-sheets comparison. Cannot yet MEASURE
   "better than old."
5. **G1 is a test, not a build-time gate.** Round-trip equivalence is verified in
   tests, not enforced during `build!`.
6. **Pre-existing repl_researcher_test drift (~12-18).** Generic executor, not
   ontology, not blocking — but blocks a clean "100% green" before push.
7. **Operational requirements undocumented for consumers:** running Grain
   tick-engine (Phase-2 discovery needs it), `:dscloj-provider`, OpenRouter,
   Python+rdflib (G1), orientation-card injection when granting RLM tools.
8. **Deferred (non-blocking):** determinism knob (pin a recorded discovery tree);
   co-occurrence consumption; true MinHash/LSH (currently Jaccard proxy);
   normalize stage is verify-only for non-TTL.

---

## 4. Open questions for the verification-phase grill

These are the decisions the grill should resolve so "verified" is well-defined:

**A. What does "verified" MEAN, per pillar?**
- P1: how many + which source types must we live-verify discovery on? (csv, sql,
  json minimum?) What's the acceptance bar per source?
- P2: is "auto-detect runs in the new path + embeds/indexes the right fields +
  those fields are then retrievable" the bar? Heuristic-only, or
  heuristic+LLM (`analyze-fields-for-embedding`)?
- P3: already verified — do we need a retrieval-quality benchmark (precision/
  recall over a known graph) or is functional fusion enough?
- P4: which lifecycle operations need live proof beyond what we have?

**B. The G2 baseline — how do we measure "better than the old 5 sheets"?**
- Do we run the OLD sheets + NEW discovery on the SAME sources and compare
  concept/relationship precision+recall against HITL-authored expected graphs?
- Who authors the HITL expected graphs, and how many fixtures?
- Is "no worse on recall, better on representation (axioms/units/evidence)" the
  bar, or must it strictly dominate?

**C. Verification corpus design.**
- What real sources (not synthetic) do we verify against? (The bench already has
  document_analysis / risk_analysis / legal docs — reuse?)
- Per source: expected concepts, relationships, axioms, embeddable fields, and a
  set of competency questions (the CQ runner becomes the acceptance test).

**D. The deterministic/LLM split — verify both.**
- Deterministic contracts: per-stage failure shapes, the two gates, idempotency,
  scoping isolation. (Mostly unit-covered; what needs live?)
- LLM discovery quality: does discovery produce semantically faithful ontologies
  across sources/domains? How many runs, what variance is acceptable, what's the
  judge/grounding for "faithful"? (The CQ runner + evidence are candidates.)

**E. Gate enforcement policy.**
- Should G1/G2 be build-time gates (block `build!`) or developer-run? CQ
  exit-criterion already gates `build!` — extend that posture to G1/G2?

**F. Cutover policy.**
- Coexist indefinitely, or set criteria for retiring the old sheets? (The audit's
  must-fix list: axiom ingest, G2 baseline, public seam — seam already exists via
  `discover-and-build!`.)

**G. The OOD / force-fit honesty (from the self-improving-loop known limitations).**
- The classifier shows force-fit on far-OOD tasks. Does the verification phase
  need an OOD stress corpus for discovery too (not just classification)?

---

## 5. Suggested verification-phase shape (straw-man for the grill to refine)

1. **Close P2** — wire auto-embed/ColBERT detection into the skeleton's
   embed/index stages (heuristic default + optional LLM analyze), with a live
   verify that detected fields become retrievable via embedding + ColBERT.
2. **P1 multi-source live verify** — run discovery on a csv + a sql + a text
   source; assert each produces a faithful general ontology + answers its CQs.
3. **Axiom ingest** — wire discovered axiom-drafts → S07 commands (coercion
   discipline as for scope/confidence-class).
4. **G2 baseline** — old-sheets vs new-discovery on shared sources w/ HITL
   expected graphs; define the "better" bar.
5. **Retrieval-quality bench** — precision/recall of hybrid-search over a known
   graph (optional, per grill decision).
6. **repl_researcher drift** — fix or quarantine before push.
7. **Consumer ops doc** — the runtime requirements list.

---

## 6. One-line status for the grill opening

"The substrate, fused retrieval (BFS+embedding+ColBERT), and the
discover/learn/maintain/access lifecycle are live-proven and net-better than the
old system. The ontology is still DISCOVERED BY LLMs (recursive-RLM discovery +
LLM dedup/CQ judges) inside a deterministic, gateable skeleton. Three intent
gaps remain in the new path — auto-embed-field detection, multi-source proof,
and axiom ingest — and we need to define what 'verified' and 'better than old'
concretely mean. That definition is this grill's job."
