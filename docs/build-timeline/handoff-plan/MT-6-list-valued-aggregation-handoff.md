# Handoff — MT-6: list-valued aggregation (repeating-key attribute tables → a flat list, not per-row nodes)

**Issue:** new slice off [Meaningful multi-table extraction](../issues/meaningful-multi-table-extraction/README.md) (MT-6, surfaced by the MT-5 acceptance).
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` = shell env var only. **Run ONE bounded build at a time; `pgrep -f 'clojure|cpcache'` JVM hygiene; confirm 0 this-repo orphan JVMs after any live run.**
**Blocked-by:** MT-3 (aggregation machinery) + the MT-3-streaming commit `d7474837` (the chunk pager + `aggregate-init`/`-step`/`-finalize`).

## The problem (established by the MT-5 acceptance — don't re-derive)
Occupation-ATTRIBUTE tables — `Alternate Titles` (occupation → its other job titles, 24,999 rows), `Task Statements` (18,796), `Sample of Reported Titles` (7,955) — have a REPEATING entity key (O\*NET-SOC Code) + a near-unique DESCRIPTIVE element column, but NO numeric value column. MT-3 only aggregates `:long-form` (which needs a numeric column to rank a top-N), so these fall to the PER-ROW path → one node per row → ~52k nodes → the comprehensive-build memory pressure AND an acceptance violation (they are not occupations; A2 models alternate titles as labels ON the occupation). Turning up heap is NOT the fix — these nodes shouldn't exist.

## Why NOT a structural MT-1 rule (the /prototype KILLED it — do not revisit)
`development/src/mt6_listform_prototype.clj` proved a structural "repeating-key → :list-form" rule OVER-FIRES: 15 re-classifications, ~14 wrong (it drags reference dictionaries `DWA/IWA/UNSPSC Reference`, a bridge `Basic Interests to RIASEC`, and `Job Zones`/`Task Statements` via the Task-ID-as-measure bug into aggregation). **A list-valued attribute table and a reference dictionary are STRUCTURALLY IDENTICAL** (repeating key + near-unique element — e.g. `Alternate Titles` SOC→title vs `DWA Reference` ElementID→title). The distinction is SEMANTIC — only the domain-aware LLM (or goal relevance) can tell which repeating key is a real entity to enrich. So the decision belongs to the AUTHOR, guarded deterministically — NOT a brittle MT-1 shape rule.

## The change (shape — LLM-authored, deterministically guarded; re-orchestrate, not fork)

1. **Collect (list) mode in the aggregation** (`container_aggregate.clj`): when the rollup spec has NO `:value-col` (or blank), `aggregate-step` COLLECTS the element values into a per-key list instead of a top-N-by-value. BOUNDED + deduped: keep at most `max-list-size` (a new const, e.g. 25) DISTINCT element values per key (once at the cap, ignore new ones — the accumulator stays keys × max-list-size, never a 25k-row blowup). `aggregate-finalize` emits the flat list attribute. `:value-col` present → the existing top-N path, unchanged. Update `stream-aggregate`/`init`/`step`/`finalize` (the chunk pager already drives these — it inherits the collect mode for free).
2. **The APPLY gate becomes SAMPLE-DRIVEN, not shape-tag-driven** (`container_aggregate.clj` `aggregating-apply?` + `extract_subbehavior.clj` `apply-transform-for-container-code`): fire the aggregating path when the AUTHOR produced a VALID spec AND the spec's `:key-col` genuinely REPEATS in the sample (distinct-ratio well below near-unique — many rows per key). A near-unique key (1 row per entity, e.g. `Occupation Data`) → do NOT aggregate even if a spec was emitted → per-row. This SUBSUMES the old `:long-form` gate (Skills' SOC repeats → still aggregates) and ADDS the list case (Alternate Titles' SOC repeats), independent of MT-1's (sometimes-wrong) `:shape`. Add a `key-repeats?` predicate `(sample key-col)` → distinct-ratio < `unique-key-threshold`. The APPLY `:code` node must READ the sample to run the gate — add `:sample-rows` to its `:reads` (the SAMPLE node already writes it). If no sample is available, FALL BACK to the old `long-form-container?` tag (behavior-preserving).
3. **The AUTHOR guidance** (`container_aggregate/aggregation-author-guidance`): stop gating the aggregation instruction on `:shape = :long-form`. Instead: instruct the AUTHOR to INSPECT the sample — if the entity key appears in MANY rows (tall table: many rows per entity, one row per entity×element), AGGREGATE: emit a spec with a numeric `:value-col` for a ranked top-N when the element rows carry a real measure/score, OR NO `:value-col` for a LIST-collect when they are descriptive labels with no ranking (alternate titles, tasks). If each row is a DISTINCT entity (one row per key), author the per-row `transform-source` instead. Reasoning FIRST (#13); domain-agnostic (#12) — the AUTHOR picks every column from the sample, names none baked.

## The real signatures you build on
- `container_aggregate.clj`: `aggregate-init`/`aggregate-step`/`aggregate-finalize` (extend for collect mode), `stream-aggregate`, `aggregating-apply?`/`valid-aggregation-spec?`/`long-form-container?`/`parse-aggregation-spec`, `aggregation-author-guidance`, `full-coverage-max-windows`, `unique-key-threshold` (add if not present — the near-unique cutoff, mirror MT-1's 0.9). MT-1's `container_shape.clj` has the distinct-ratio/`numeric-like?` helpers to mirror for `key-repeats?`.
- `rlm_discovery.clj`: `apply-aggregation-transform!` (the chunk pager — already drives init/step/finalize; collect mode flows through automatically). `apply-extraction-transform!` (per-row, unchanged).
- `extract_subbehavior.clj`: `apply-transform-for-container-code` (~508, the router: `spec`/`aggregate?`/`result`), the AUTHOR `:llm` node (`:reads` + `:writes :aggregation-spec`), the APPLY `:code` node (`:reads` — ADD `:sample-rows`). Both the resilient + flat extract paths.

## Read-first (in order)
1. `development/src/mt6_listform_prototype.clj` — WHY the structural rule was rejected (the evidence).
2. `container_aggregate.clj` — the whole ns (collect mode + the gate).
3. `extract_subbehavior.clj` `apply-transform-for-container-code` + the AUTHOR/APPLY node defs (the routing + `:reads`).
4. `container_aggregate_test.clj` — the existing aggregation tracers to extend.
5. MT-3 handoff (`MT-3-aggregating-transform-handoff.md`) — the mechanism this extends.

## TDD cycle list (tests FIRST, red→green, behavior through PUBLIC fns)
1. **Collect mode (pure `stream-aggregate`, no value-col):** rows with a repeating key + a descriptive element, NO value → ONE draft per key carrying a flat list of the element values; DISTINCT + BOUNDED at `max-list-size` (feed >max distinct elements for one key → capped, no blowup). A spec WITH a value-col still does top-N (existing behavior unchanged).
2. **`key-repeats?` + the sample-driven gate:** a sample where the key-col repeats (many rows per value) → `aggregating-apply?` true for a valid spec; a sample where the key-col is near-unique (1 row per value) → `aggregating-apply?` FALSE even for a valid spec (per-row). No sample → falls back to `long-form-container?` (behavior-preserving).
3. **APPLY routes list-collect for a repeating-key container / per-row for a unique-key one (public APPLY seam, real csv):** a repeating-key sample + a no-value spec → per-KEY list drafts; a unique-key sample + a spec → per-ROW drafts (the gate declined). A `:long-form` value-spec + repeating key → top-N (unregressed).

(The LIVE proof — real O\*NET `Alternate Titles` → ~900 occupation drafts each with an `altTitles` flat list (NOT 24,999 nodes), `Occupation Data` still per-row, the big long-form tables still top-N — is the `/inspect-orc`.)

## Do NOT touch
- MT-1's `container_shape.clj` (the /prototype proved a structural rule is wrong; leave the classifier — the gate is now sample-driven). MT-2 selection, MT-4/4b merge, the streaming pager.
- The per-row `apply-extraction-transform!` (behavior-preserving for 1-row-per-entity tables).
- No baked O\*NET/CIP/SOC column or entity names; the AUTHOR picks every column + the mode from the sample (#12).

## Live-QA (`/inspect-orc` — the orchestrator runs this, adversarially)
- REAL O\*NET `Alternate Titles`: ONE occupation draft per SOC carrying a populated `altTitles` (or similar) FLAT LIST — NOT ~25k per-row nodes. `Task Statements` likewise a list (tasks). Witnessed via the drafts.
- `Occupation Data` (unique SOC) STILL per-row (1016 drafts) — the gate correctly declined to aggregate a 1-row-per-entity table.
- The big long-form tables (Skills/Knowledge/Work Activities) STILL top-N (unregressed).
- The comprehensive build's draft volume from these attribute tables collapses from ~52k to ~a few k (bounded); re-run the acceptance and confirm no OOM from them + the graph is occupation-nodes-only. Ontology brick gate green; 0 orphan this-repo JVMs (`pgrep -f`).

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (a list that silently truncated, or a unique-key table wrongly aggregated to 1-element lists).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause (a list over the cap surfaces truncation, never silent).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — extend the aggregation + the gate; do NOT fork the apply path.
9. Adversarial qualitative verdict — hunt for a unique-key table wrongly aggregated, a reference dictionary pulled in, or a silently-truncated list; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the gate + collect are deterministic; the mode choice is the LLM's — verify BOTH.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — the AUTHOR picks the key/element/mode from the sample; name NO O\*NET/CIP/SOC column or entity; no hardcoded table list.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
