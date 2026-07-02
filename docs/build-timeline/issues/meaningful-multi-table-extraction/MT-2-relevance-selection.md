# MT-2 — Survey-driven relevance rank + bounded container selection

## Parent
[Meaningful multi-table extraction](README.md)

## What to build
Replace the blind `(take cap (list-source-containers source))` in `orchestrate-extract-containers` with a SELECTED, ranked container list:
1. Classify every container (MT-1) → drop bridge/reference noise, keep entity/long-form/wide-stats with shape tags.
2. The Survey/Model RANKS the survivors by relevance to the GOAL (it already profiles the source + sees the goal). Relevance only — never identity.
3. Take the top-ranked survivors up to the cap. The Extract step consumes THIS list (with each container's shape tag carried forward for MT-3).

The selection is produced at/after Survey (which holds the per-medium tool catalog) and threaded to Extract; the report records total-vs-selected honestly (no silent truncation — surface what was dropped and why).

## Acceptance criteria
- [ ] On REAL O\*NET at the dev cap: the selected containers are the occupation-centric ones (`Occupation Data`, `Skills`, `Knowledge`, …), NOT `Abilities to Work Activities`/`Work Context`. Verified LIVE.
- [ ] A goal-scoped run ranks goal-relevant containers above irrelevant entity tables (the LLM relevance actually discriminates, not a rubber-stamp).
- [ ] The dropped noise is reported honestly (`:containers-total` vs `:containers-selected` + drop reasons), no false-green.
- [ ] Domain/format-agnostic; the cap (MC-0) + GC-13 parallelism preserved (rank-then-bound). TDD red→green through the selection seam (stub the classifier + a controlled container set; assert the ranked/bounded output). Ontology brick gate green; 0 orphan JVMs.

## /prototype
Not required (MT-1 prototype de-risks the shapes). If the LLM relevance rank proves unreliable on the survivors, root-cause before shipping (do not paper over with a hardcoded table list — #12).

## Blocked by
MT-1 (needs the shape verdict + tags).

## Handoff focus
Read-first: `extract_subbehavior.clj` `orchestrate-extract-containers` (the `take cap` site) + `list-source-containers`; `survey_subbehavior.clj` + `discovery_tree.clj` `profile-node-prompt` (the Survey seam that will host the ranking); `central_evolver.clj` the Survey→Model→Extract pipeline (how the selected list threads to Extract, mirroring the GC-6 `:vocabulary` / GM-1 `:graph-context` threading). Re-orchestrate — reuse Survey + the pipeline blackboard threading; do NOT fork. This is HITL (it changes what the pipeline extracts — review the selection on real O\*NET before commit). One bounded build; `pgrep -f` hygiene.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse Survey + the pipeline threading; do NOT fork.
9. Adversarial qualitative verdict — hunt for a relevant container silently dropped or noise silently kept; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic pre-filter AND the LLM relevance rank.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — structural + goal-relevance only; name NO O\*NET/CIP/SOC column or entity; NO hardcoded table list.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
