# Extraction reliability — issue index

Pre-existing extraction-quality bugs root-caused 2026-07-01 while investigating the "3-source concept-count variance" that destabilizes the benchmark (and would corrupt the A2-vs-B comparison). NOT caused by the graph-grounded-modeling (GM) work — these sit underneath it. Local issue files only, per project policy.

## Root-cause summary (evidence, not assumption)

The benchmark instability had TWO independent contributors:

1. **Runaway-agent memory contention** — a subagent's monitor loop kept relaunching builds; 2+ concurrent builds (~9GB) caused native-OOM kills, timeouts, and the transient `institution`/`inst` fragmentation seen in contended runs. *Eliminated by killing the runaway; a clean solo run gives a stable 447 / institution 309 / no split.* (Operational, not a code bug — no issue filed; the lesson: run one bounded build at a time, `pgrep -f` hygiene.)

2. **Two real code bugs** (this directory):
   - **ER-1 — O\*NET heterogeneous-sheet key mismatch** (the residual variance cause). The built-in troubleshoot `:llm` diagnosed it exactly: the per-container transform looks for generic keys (`O*NET-SOC Code`, `Element ID`, `Category`) but O\*NET's cross-reference/junction sheets carry *per-sheet prefixed* columns (`Abilities Element ID`, `Work Activities Element ID`) → every row filtered → **0 concepts** on those sheets (381/139 rows streamed, 0 errored). The EB9 resilience DETECTS + diagnoses the 0-draft failure but the `:fallback` re-author does NOT recover with the actual columns.
   - **ER-2 — C1 model-spec `:entity-types` reaches the extractor unparsed.** For crosswalk + O\*NET the extractor's `:model-spec` `:entity-types` was an unparsed EDN STRING (342/332 chars iterating to `nil`) while ipeds parsed cleanly. `normalize-model-spec` (extract_subbehavior:824, called central_evolver:458) didn't coerce it on that path. Extraction recovered here, but it can starve GC-1 canonicalization of keying info.

## Slice table + cadence

| Slice | What | Type | Blocked by | /prototype | /handoff |
|-------|------|------|-----------|-----------|----------|
| **ER-1** | O\*NET heterogeneous-sheet key adaptation — recover from a 0-draft key mismatch by re-authoring grounded in the sheet's ACTUAL columns | HITL | — | **YES** — reproduce the junction-sheet 0-draft live; prototype the recover-with-real-columns before TDD | now (self-contained) |
| **ER-2** | Coerce/guard model-spec `:entity-types` unparsed-string at the extract boundary | AFK | — | light | now (self-contained) |

**Relationship:** ER-2 may partially mitigate ER-1 (a clean model-spec gives the AUTHOR better grounding, less generic-key guessing). Investigate that in ER-1's prototype; if a coerced model-spec alone fixes the junction-sheet extraction, ER-1 shrinks. Sequence: ER-1 first (the user's priority — the concrete variance fix), reusing ER-2's coercion if it lands.

## The build loop
Each slice: **/handoff → /prototype (where flagged) → /tdd (tests FIRST, red→green) → /inspect-orc** (adversarial re-verify on REAL data before commit). 13 Core Disciplines embedded verbatim per issue. **Run ONE bounded build at a time** (the contention lesson) with `pgrep -f` JVM hygiene.
