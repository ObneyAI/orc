# MT-3 — Model-authored aggregating transform (long-form → top-N flat attribute)

## Parent
[Meaningful multi-table extraction](README.md)

## What to build
Extend extraction so the Model can author an AGGREGATING transform for a `:long-form`-tagged container (not only a per-row transform). The aggregating transform:
- GROUPS rows by the entity key (e.g. the occupation code),
- RANKS the group's elements by an importance/value column,
- keeps the TOP-N element labels as a FLAT array attribute on ONE draft per key (e.g. `topSkills: [...]`).

It is applied STREAMED/BOUNDED — a large long-form container (O\*NET `Skills` ≈ 92k rows) must never materialize whole; accumulate per-key top-N in bounded memory as rows stream. The Model authors the rollup (which column is the key / element / rank, and N); the extract path executes it over the stream.

## Acceptance criteria
- [ ] On REAL O\*NET `Skills`: emits ONE occupation draft per SOC code carrying a populated `topSkills` flat array (top-N by importance), NOT ~91 per-row fragments. Verified LIVE.
- [ ] Works the same for `Knowledge` (`topKnowledge`) — domain-agnostic, the Model picks the columns; no baked names.
- [ ] Streamed/bounded: the full 92k-row container completes without OOM (bounded per-key accumulation); the top-N is CORRECT vs a spot-checked hand-aggregation of a few occupations.
- [ ] The per-row transform path is UNCHANGED for non-long-form containers (behavior-preserving; re-orchestrate not rewrite).
- [ ] TDD red→green: the group-by/top-N rollup on a long-form sample (assert one draft/key + correct top-N); the streaming application on the full container. Ontology brick gate green; 0 orphan JVMs.

## /prototype
YES — prototype the aggregating-transform SHAPE + the streaming application on the REAL `Skills` container FIRST: confirm (a) the Model reliably authors a correct group-by/top-N given the long-form sample + shape tag, and (b) the streamed per-key top-N accumulation is bounded + correct on 92k rows. Do not assume the per-row `stream-all` path can host aggregation — establish the mechanism before TDD. This is the riskiest, most novel slice.

## Blocked by
MT-2 (the long-form container must be selected + shape-tagged first).

## Handoff focus
Read-first: `extract_subbehavior.clj` `extract-per-container-def` (SAMPLE→AUTHOR→APPLY, the per-row `stream-all` apply) + the `apply-extraction-transform!` path; `discovery_tree.clj` DT4 `transform-node-prompt` (the AUTHOR prompt — extend for the aggregating case, gated on the `:long-form` tag); `docs/RLM-GUIDE.md` `:code` model-authored-transform precedent; GM-1 `grain-reify-block` (confirms flat, no reify). Re-orchestrate — extend the existing extract path to accept a group-emitting transform; do NOT fork. HITL (novel mechanism — review the authored rollup + the bounded streaming on real data). One bounded build; `pgrep -f` hygiene.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (0 populated topSkills is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — extend the existing extract path; do NOT fork.
9. Adversarial qualitative verdict — hunt for a wrong/empty top-N masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the streamed bounded application AND the Model-authored rollup logic.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — the Model picks the key/element/rank columns from the sample; name NO O\*NET/CIP/SOC column; no hardcoded aggregation.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
