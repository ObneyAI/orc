# Extraction reliability — issue index

Pre-existing extraction-quality bugs root-caused 2026-07-01 while investigating the "3-source concept-count variance" that destabilizes the benchmark (and would corrupt the A2-vs-B comparison). NOT caused by the graph-grounded-modeling (GM) work — these sit underneath it. Local issue files only, per project policy.

## Root-cause summary (evidence, not assumption)

The benchmark instability had TWO independent contributors:

1. **Runaway-agent memory contention** — a subagent's monitor loop kept relaunching builds; 2+ concurrent builds (~9GB) caused native-OOM kills, timeouts, and the transient `institution`/`inst` fragmentation seen in contended runs. *Eliminated by killing the runaway; a clean solo run gives a stable 447 / institution 309 / no split.* (Operational, not a code bug — no issue filed; the lesson: run one bounded build at a time, `pgrep -f` hygiene.)

2. **Two real code bugs** (this directory):
   - **ER-1 — O\*NET heterogeneous-sheet key mismatch** (the residual variance cause). The built-in troubleshoot `:llm` diagnosed it exactly: the per-container transform looks for generic keys (`O*NET-SOC Code`, `Element ID`, `Category`) but O\*NET's cross-reference/junction sheets carry *per-sheet prefixed* columns (`Abilities Element ID`, `Work Activities Element ID`) → every row filtered → **0 concepts** on those sheets (381/139 rows streamed, 0 errored). The EB9 resilience DETECTS + diagnoses the 0-draft failure but the `:fallback` re-author does NOT recover with the actual columns.
   - **ER-2 — C1 model-spec `:entity-types` reaches the extractor unparsed.** For crosswalk + O\*NET the extractor's `:model-spec` `:entity-types` was an unparsed EDN STRING (342/332 chars iterating to `nil`) while ipeds parsed cleanly. `normalize-model-spec` (extract_subbehavior:824, called central_evolver:458) didn't coerce it on that path. Extraction recovered here, but it can starve GC-1 canonicalization of keying info.

## Slice table + cadence

| Slice | What | Type | Status | /prototype | /handoff |
|-------|------|------|--------|-----------|----------|
| **ER-1 + ER-2** | Normalize the model-spec at the EXTRACT boundary → the AUTHOR grounds in parsed entity-types, keys correctly per sheet, O\*NET junction sheets extract | HITL | ✅ **DONE** (commit `06101ec7`) | done | done |
| **ER-3** | Intermittent vocabulary fragmentation — the SAME entity minted under 2-3 type-names run-to-run (`institution` / `educational-institution` / `unitid`) → GC-1 keys the URI on the type-name → no merge → concept-count inflation | HITL | 🔎 next (root-cause) | YES | after root-cause |

**ER-1/ER-2 resolution (2026-07-01):** root-caused (the pipeline threads the RAW model-spec to the AUTHOR; `normalize-model-spec` ran POST-extraction) and fixed with a one-boundary normalize in `orchestrate-extract-containers`. Validated LIVE (junction sheets 0 → 762/278, reliable across 2 runs) + TDD red→green + brick gate green. The two issues merged into one small fix.

**ER-3 (the remaining variance):** intermittent — run 1 fragmented (`institution`/`educational-institution`/`unitid`, 3×200 → 1066 concepts), run 2 clean (`institution 309` → 575). GC-6 synthesize-vocab is supposed to give ONE canonical `:type` per entity (with source names as `:aliases`) and the Model reads `:vocabulary`, so the divergence means one of: synth-vocab didn't emit a single type, the Model/AUTHOR ignored the vocabulary and tagged a variant `:entity-type`, or GM-1's graph-context nudged variant minting. Root-cause needs instrumentation (capture synth-vocab's output + the per-draft `:entity-type` tags on a FRAGMENTED run — it's intermittent, so may need several catches). DO NOT ASSUME the cause. See [[project_graph_fragmentation_root_cause]].

**Relationship:** ER-2 may partially mitigate ER-1 (a clean model-spec gives the AUTHOR better grounding, less generic-key guessing). Investigate that in ER-1's prototype; if a coerced model-spec alone fixes the junction-sheet extraction, ER-1 shrinks. Sequence: ER-1 first (the user's priority — the concrete variance fix), reusing ER-2's coercion if it lands.

## The build loop
Each slice: **/handoff → /prototype (where flagged) → /tdd (tests FIRST, red→green) → /inspect-orc** (adversarial re-verify on REAL data before commit). 13 Core Disciplines embedded verbatim per issue. **Run ONE bounded build at a time** (the contention lesson) with `pgrep -f` JVM hygiene.
