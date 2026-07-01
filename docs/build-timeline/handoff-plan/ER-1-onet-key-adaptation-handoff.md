# Handoff — ER-1: O\*NET heterogeneous-sheet key adaptation (recover from a key-mismatch 0-draft)

**Issue:** [`../issues/extraction-reliability/ER-1-onet-heterogeneous-sheet-key-adaptation.md`](../issues/extraction-reliability/ER-1-onet-heterogeneous-sheet-key-adaptation.md)
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` = shell env var only. **Run ONE bounded build at a time; `pgrep -f` JVM hygiene (see the reference memory).**

## The root cause (established — don't re-derive)

The per-container AUTHOR (`:llm`) emits a `:transform-source` STRING keyed on GENERIC O\*NET columns (`O*NET-SOC Code`, `Element ID`, `Category`); O\*NET's junction sheets carry PER-SHEET PREFIXED columns (`Abilities Element ID`, `Work Activities Element ID`). The AUTHOR's prompt-instructed "validate on real rows" is IMAGINARY (an `:llm` can't execute Clojure), so the mismatch is only discovered in APPLY → 0 concepts. The EB9 `with-resilience` (`extract_subbehavior.clj` `extract-per-container-def` ~639: 0-draft gate → troubleshoot `:diagnosis` → `:fallback` re-author) DETECTS + DIAGNOSES it exactly but the fallback re-author still doesn't ground in the real columns → the sheet stays at 0.

## /prototype FIRST (the gate — establish the fix shape before TDD)

1. **Reproduce deterministically:** instrument `orchestrate-extract-containers` to dump per-container `:extraction-report` + the authored `:transform-source` keys + the sample's actual header; run an O\*NET-only (or 3-source) 2/2 build until a junction sheet ("Abilities to Work Activities" / "Abilities to Work Context") hits `status :failure, concepts 0`. Confirm the transform's keys vs the sample header.
2. **Test the cheap hypothesis first (ER-2 interaction):** does coercing the model-spec `:entity-types` (ER-2) alone give the AUTHOR enough grounding to key correctly? If a clean model-spec fixes the junction-sheet extraction, ER-1 shrinks to just the deterministic validation. Measure it.
3. **Prototype the recover path:** a deterministic pre-APPLY check — run the authored transform over the real sample IN CODE (the sandbox eval exists in `apply-extraction-transform!`); if it yields 0 on non-empty in-scope rows, RE-AUTHOR with the sheet's ACTUAL header columns surfaced EXPLICITLY (from `source_tools_excel/sheet-columns`), and require the re-authored transform's keys to be present in the header. Confirm the previously-0 sheet now yields concepts.

## The change (shape — refine from the prototype)
- A deterministic **key-presence validation** of the authored transform against the real sample header, BEFORE APPLY streams the whole container (turn the AUTHOR's imaginary self-validation into a real one).
- On failure, **recover**: re-author with the actual columns surfaced explicitly (reuse the EB9 `:fallback` seam — re-orchestrate, don't fork), then re-validate. If still unmappable, fail HONESTLY (surface the mismatch in `:extraction-report`, never a silent 0).
- DOMAIN-AGNOSTIC: read the runtime header/sample; NO baked O\*NET column names; structural/fuzzy match of transform-key-needs vs actual columns.

## TDD cycle list (tests FIRST, red→green, behavior through public fns)
1. **Key-presence check (pure):** given a transform's read-keys + a sample header, detect present vs missing keys; a transform reading a key absent from the header is flagged. (The deterministic heart.)
2. **Recover trigger (stubbed author):** a 0-draft-on-non-empty-rows result triggers the re-author path with the actual columns; a genuine 0 (out-of-scope rows) does NOT falsely trigger.
3. **Honest terminal:** an unmappable sheet surfaces `:failure` + the key-mismatch diagnosis in `:extraction-report` — never a silent 0 masked as success.

(The LIVE "junction sheet now yields concepts + O\*NET yield is stable" proof is the `/inspect-orc` — a real O\*NET 2/2 build.)

## Do NOT touch
- GM-1 (graph_context_snapshot / model_subbehavior / central_evolver graph-context) — separate, verified, uncommitted; ER-1 is `extract_subbehavior` / `discovery_tree` / `rlm_discovery` (different files, no conflict).
- The pseo wide-stats modeling (GM line). The CQ-gate, embed/index.
- No baked O\*NET/pseo column names.

## Live-QA (`/inspect-orc`)
Reproduce a junction-sheet 0-draft MYSELF; confirm the fix makes it yield concepts; O\*NET-only 2/2 build twice → stable yield (the variance from junction 0s is gone); an unmappable sheet fails honestly (not silent 0); the clean 3-source build is not regressed; ontology brick gate green; 0 orphan this-repo JVMs (`pgrep -f`).

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (a silent 0-concept sheet is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the EB9 resilience + the excel tool's real-column detection; do NOT fork the extract pipeline.
9. Adversarial qualitative verdict — hunt for silent 0-draft sheets masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic key-check AND the re-author reasoning.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — read the runtime sample/header; name NO O\*NET column; no hardcoded column lists or phrase matching.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
