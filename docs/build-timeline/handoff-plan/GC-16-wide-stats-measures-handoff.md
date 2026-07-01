# Handoff — GC-16: wide-stats sources model measures as attributes, not per-row entities

> **⛔ SUPERSEDED (2026-07-01)** — the GC-16 guardrail approach failed live verification and was reverted. See [`../issues/graph-grounded-modeling/README.md`](../issues/graph-grounded-modeling/README.md). Do NOT implement this handoff.

**Issue:** [`../issues/multi-container-graph-correctness/GC-16-wide-stats-measures-as-attributes.md`](../issues/multi-container-graph-correctness/GC-16-wide-stats-measures-as-attributes.md)
**Type:** HITL · **Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` is a shell env var only — never printed/committed.

## What's already established (root-caused with LIVE evidence — this is implement-the-fix)

The full 5-source graph-B build crashes on **pseo** (PSEO earnings Excel). The Model `:llm` authored an `earnings-outcome` ENTITY-TYPE for pseo, keyed on 6 breakdown dims (`institution cipcode "Degree\nAward\nLevel" grad_cohort geo_level industry`), `:grain-strategy :breakdown-as-entity` → one concept per (program×cohort×geo×industry) row → ~2000 earnings concepts from a 2/2 sliver → embedding ~6k bloated concepts exhausts native memory → JVM dies with no Java OOM. Earnings should be native-number `:attributes` on the program. Plus attribute-bloat (whole ~90-col row on every concept, each key duplicated keyword+string).

Reproduce the evidence yourself (fast, ~40s, halts before embed):
- model-spec capture + draft dump scripts pattern: wrap `extract/orchestrate-extract-containers` to dump `(get-in context [:inputs :model-spec])` and the per-kind draft counts, then `(.halt ...)`. Run `:only [:pseo] :max-containers 2 :max-windows 2 :store :in-memory`.

## The three changes (all DOMAIN-AGNOSTIC — name NO pseo column)

1. **Model prompt — measures-vs-entities guidance.** `discovery_tree.clj` DT3 model-node-prompt (~278–371; surfaced via `model_subbehavior.clj` `model-prompt` ~354). Add: wide numeric/aggregate columns (statistics, percentiles, earnings, counts, gaps) are MEASURES → carry as `:attributes` on the entity, NEVER mint as a breakdown-entity; model only the real entity nouns. Keep the existing grain-strategy / canonical-row-filter guidance.

2. **Structural guardrail (deterministic, no LLM) — the backstop.** A pure post-Model step (in the model-spec post-processing / skeleton) that, BEFORE extraction:
   - DETECTS a measure-explosion entity-type GENERALLY — `:grain-strategy :breakdown-as-entity` keying identity on columns the SURVEY PROFILE classifies as MEASURE/non-identifying (so it would mint ≈1 concept/row). Use the profile's identifying-keys vs the measure columns — NOT a hardcoded list.
   - REPAIRS it — drop the measure-entity-type; re-route its measure columns to `:attributes` on the PARENT entity it points to (the model-spec edge, e.g. `outcome_for_program → educational-program`), at the parent's canonical-row grain. Malli-validate the repaired spec.
   - A LEGITIMATE breakdown-entity (real identity grain, keyed on identifying columns) is left UNTOUCHED.

3. **Attribute selectivity.** Fix the dual-key carry (`rlm_discovery.clj` `carry-linking-values` ~1020–1078 + the dual-key row ~1231–1240) + the AUTHOR transform so `:attributes` hold only the entity's keying VALUES + declared measures — single-form keys (no keyword+string duplicates, no malformed `:Degree\nAward\nLevel`), not the whole row.

## /prototype FIRST (the guardrail detection is novel — de-risk before TDD)

Prototype the structural guardrail's DETECT step on the REAL pseo model-spec + profile: confirm it flags the `earnings-outcome` entity-type as a measure-explosion (and would NOT flag `institution`/`field-of-study`/`educational-program`). Then prototype the REPAIR and re-run pseo extraction (2/2) to MEASURE the new concept count (expect ~order-of-magnitude smaller, earnings as program attributes). Capture the before/after counts. Only then formalize via TDD.

## TDD cycle list (tests FIRST, red→green, behavior through public fns)

1. **Guardrail DETECT (pure):** synthetic model-spec with a measure-explosion entity-type (breakdown-keyed on profile-measure columns) + a profile → detected. A legitimate breakdown-entity (keyed on identifying columns) → NOT detected. RED before the fn exists.
2. **Guardrail REPAIR (pure):** the detected measure-entity is dropped and its measure columns become `:attributes` on the parent entity (by the model-spec edge), Malli-valid; measures are PRESENT (not lost — #4/#5); a spec with no measure-explosion passes through UNCHANGED. Reverting the guardrail → the measure-entity survives (RED).
3. **Attribute selectivity (pure):** given a row + an entity's keying/measures, the produced `:attributes` carry only keying VALUES + measures, single-form (no keyword+string dup, no whole-row bloat).
4. **(Integration, stubbed):** the model-spec post-processing applies the guardrail in the pipeline (guardrail runs between Model and Extract) — a measure-explosion spec is repaired before extraction sees it.

(The LIVE pseo sane-extraction + 5-source-completes proof is the orchestrator's `/inspect-orc`, below.)

## Do NOT touch

- The crash/embed robustness (native-OOM hard death) — that is DOCKED as GC-17, a separate slice.
- The other 4 sources' modeling, the CQ-gate, reconcile/axiom, GC-13 parallel extraction, the eb12 driver.
- No hardcoded pseo column names anywhere (the whole point is generality).

## Live-QA the orchestrator (me) will run after you return (`/inspect-orc`)

Re-run your unit suite; revert the guardrail to confirm RED; reproduce the pseo model-spec + draft dump MYSELF and confirm the sane concept count + earnings-as-program-attributes + single-form selective attributes; run the FULL 5-source build and confirm it COMPLETES (no crash), 0 dangling, earnings/wage-bearing concepts > 0, gate can answer an earnings CQ; confirm the 3-source build is UNCHANGED (3896/0-dangling — no regression to the clean sources); ontology brick gate green; 0 orphan this-repo JVMs.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — a smaller graph that's still wrong (earnings missing/misattached) is a FAIL; ask "how could this pass while still being wrong?" and test it.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL pseo Excel is mandatory; no false green (folded measures must be PRESENT as queryable attributes, never silently dropped).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — extend the Model prompt + add a skeleton guardrail step + fix the dual-key carry; do NOT fork extraction.
9. Adversarial qualitative verdict — hunt for where the output is WRONG (earnings lost, misattached, attached to the wrong grain); surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the prompt-guided modeling AND the deterministic guardrail.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — the prompt + guardrail name NO pseo column; profile/model-spec-driven at runtime. NO hardcoded column lists or phrase matching (Malli-structural + profile classification only).
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts) — the Model + AUTHOR already do; keep it.
