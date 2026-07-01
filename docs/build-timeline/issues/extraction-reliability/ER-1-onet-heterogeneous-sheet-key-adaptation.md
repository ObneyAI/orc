# ER-1 — O\*NET heterogeneous-sheet key adaptation (the extraction-variance root cause)

## Parent
[Extraction reliability index](README.md)

## The bug (root-caused with the system's OWN troubleshoot diagnosis — not assumed)

The 3-source build's concept count swings run-to-run (138 / 447 / 629 across clean runs) because O\*NET's Excel sheets are HETEROGENEOUS and some yield 0 concepts. Instrumenting `orchestrate-extract-containers` to dump each container's `:extraction-report` caught it: on the "Abilities to Work Activities" (381 rows) and "Abilities to Work Context" (139 rows) sheets, `status :failure, concepts 0, rows-errored 0`, and the built-in troubleshoot `:llm` diagnosed the exact cause:

> "Key mismatch between the transform logic and the input data. The transform looks for `O*NET-SOC Code`, `Element ID`, and `Category`, but the input rows contain `Abilities Element ID` and `Work Activities Element ID`." (Explicitly ruled out empty-data and runtime errors.)

So the per-container AUTHOR authored a transform keyed on GENERIC O\*NET column names, but O\*NET's cross-reference/junction sheets use PER-SHEET PREFIXED names → the transform's key lookup returns null → every row filtered → 0 concepts. Which sheets `:max-containers` samples determines the yield → the variance.

**Why the AUTHOR gets it wrong:** the DT4 `transform-node-prompt` (`discovery_tree.clj` ~779-785) tells the AUTHOR to "validate on the real sampled rows and fix key-access if empty" — but the AUTHOR is an `:llm` emitting a transform-source STRING; it cannot actually execute Clojure, so its "validation" is imaginary. The real validation only happens in APPLY (`apply-extraction-transform!`), after the fact. **The EB9 resilience (`extract-per-container-def`, `with-resilience` ~639, the 0-draft gate + troubleshoot + `:fallback` re-author ~682) DETECTS + DIAGNOSES the 0-draft failure but its fallback re-author still does NOT ground in the sheet's actual columns → the sheet stays at 0.** The system knows the fix (the diagnosis literally says "use `Abilities Element ID`") but never applies it.

## What to build

Make a 0-draft key-mismatch RECOVER, deterministically, by re-grounding in the container's ACTUAL columns:
- On the resilience 0-draft path, surface the container's REAL column names (from the sheet header — `source_tools_excel/sheet-columns` already detects it) EXPLICITLY to the re-author, and require the re-authored transform to key off columns that ACTUALLY EXIST in the sample (a deterministic check: every key the transform reads must be present in the sample header; if not, re-author or fail honestly with the mismatch surfaced — never a silent 0).
- Consider a deterministic pre-APPLY validation: run the authored transform over the real sample IN CODE (the sandbox eval already exists) and if it yields 0 on non-empty in-scope rows, trigger the recover path BEFORE APPLY wastes the full stream. This turns the AUTHOR's imaginary self-validation into a real one.
- DOMAIN-AGNOSTIC: no baked O\*NET column names — the fix reads the actual header/sample columns at runtime and fuzzy/structurally matches the transform's key needs against them.

## /prototype (REQUIRED before TDD)
Reproduce the junction-sheet 0-draft live (instrument `orchestrate-extract-containers`, run 3-source or O\*NET-only 2/2, catch a sheet whose columns are prefixed). Then prototype the recover-with-real-columns path and confirm the previously-0 sheet now yields concepts. Also test whether ER-2's model-spec coercion alone improves the AUTHOR's grounding (if a clean model-spec fixes it, ER-1 shrinks to the deterministic validation).

## Acceptance criteria
- [ ] A container whose actual columns don't match the authored transform's keys RECOVERS (re-authored against the real columns) and yields concepts — proven LIVE on the real O\*NET junction sheets (e.g. "Abilities to Work Activities").
- [ ] A genuinely-unmappable sheet fails HONESTLY with the key mismatch surfaced (`:extraction-report` `:failure` + diagnosis) — never a silent 0 that looks like success (#4/#5).
- [ ] O\*NET yield is STABLE across runs on the same sampled sheets (the variance from junction-sheet 0s is gone).
- [ ] Domain-agnostic — no baked column names; reads the runtime sample/header.
- [ ] TDD (red→green) on the deterministic key-presence check (pure: transform-keys vs sample-header) + the recover trigger; behavior through public fns. Recursive-only RLM; ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
None — self-contained. May reuse ER-2's coercion.

## Handoff focus
Read-first: `extract_subbehavior.clj` (`extract-per-container-def` ~582 — the SAMPLE→AUTHOR→APPLY + `with-resilience` gate/troubleshoot/fallback; `apply-extraction-transform!`), `discovery_tree.clj` DT4 `transform-node-prompt` (~713-812), `source_tools_excel.clj` (`sheet-columns` / header detection ~34, `sample-rows`), `rlm_discovery.clj` (`apply-extraction-transform!`). Re-orchestrate the existing resilience — do NOT fork the extract pipeline. Run ONE bounded build at a time; `pgrep -f` JVM hygiene.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (a silent 0-concept sheet is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the EB9 resilience + the excel source tool's real-column detection; do NOT fork.
9. Adversarial qualitative verdict — hunt for silent 0-draft sheets masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic key-check AND the re-author's reasoning.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — read the runtime sample/header; name NO O\*NET column; no hardcoded column lists or phrase matching.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
