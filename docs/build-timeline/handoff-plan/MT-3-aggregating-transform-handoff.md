# Handoff — MT-3: Model-authored aggregating transform (long-form → top-N flat attribute)

**Issue:** [`../issues/meaningful-multi-table-extraction/MT-3-aggregating-transform.md`](../issues/meaningful-multi-table-extraction/MT-3-aggregating-transform.md)
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` = shell env var only. **Run ONE bounded build at a time; `pgrep -f 'clojure|cpcache'` JVM hygiene; confirm 0 this-repo orphan JVMs after any live run.**
**Blocked-by:** MT-2 (LANDED, commit `41762f10`) — the `:long-form` shape tag + `:roles` reach the per-container extract via `:selected-containers` (each selected entry carries `:shape`/`:roles`). MT-1 (`classify-container-shape`) tags the shape. Do NOT re-derive either.

## The mechanism is ALREADY de-risked — the /prototype PROVED it on real data

`development/src/mt3_aggregate_prototype.clj` ran on the REAL O\*NET `Skills` container (62,580 rows) and established EVERY risk (do NOT re-litigate — build to this):

1. **A model-authored ROLLUP SPEC is the right shape** (Option B, per the issue — the Model authors WHICH columns, the deterministic skeleton executes the bounded stream):
   ```
   {:key-col "O*NET-SOC Code" :element-col "Element Name" :value-col "Data Value"
    :filter-col "Scale ID" :filter-val "IM"      ; the SCALE dimension — see below
    :n 10 :attr-name "topSkills" :entity-type "occupation"}
   ```
2. **The SCALE/FILTER dimension is REQUIRED, not optional.** O\*NET measurement long-forms carry the SAME entity×element TWICE — once per scale (`Scale ID` = `IM` Importance vs `LV` Level) with different `Data Value`s. A top-N without `filter-col`/`filter-val` MIXES scales → garbage. The spec MUST support it; the AUTHOR discovers it from the sample (the prototype's LLM did — it filtered to `IM` and reasoned why).
3. **Bounded streaming top-N is correct + bounded.** Folding the spec over the lazy row stream, keeping only per-key top-N: 62,580 rows → **894 drafts (one per SOC)**, peak accumulator **894 keys × N = 8,940 entries** (NOT 62k rows in heap). The streamed top-N MATCHED an independent full-scan hand-aggregation for 3 spot-checked occupations (`match? true`).
4. **The LLM reliably authors the spec** — given the long-form sample + advisory role hints, it picked key/element/value/n/attr-name AND `filter-col:"Scale ID"`/`filter-val:"IM"` correctly, and picked `Data Value` (a real measure) as `:value-col` — NOT an ID. **This is how MT-3 dodges the MT-1 Task-ID-as-measure bug:** the AUTHOR verifies the value column against the SAMPLE; role hints are ADVISORY, never trusted blindly (#1/#5).
5. **`:value-col` values are STRINGS in the source** (`"4.12"`) — the aggregation must numeric-coerce (the prototype's `num` helper: number? passthrough / numeric-string parse / else nil→skip).

The prototype's `stream-aggregate` is the reference for the deterministic executor. It is THROWAWAY — reimplement cleanly under TDD; do not ship it.

## The real signatures you build on (grounded — verified in code + the prototype)

- **The per-container extract unit** (`extract_subbehavior.clj`): a THREE-node sheet `:code` SAMPLE → `:llm` AUTHOR → `:code` APPLY (`extract-per-container-def`, ns docstring ~21). SAMPLE = `sample-rows-for-container-code` (~483). AUTHOR = the `:llm` node whose prompt is `authoring-prompt`/`dt/transform-node-prompt` (~285/~355). APPLY = `apply-extraction-transform-for-container-code` (~498) → `rlm-discovery/apply-extraction-transform!`.
- **The container carries the shape tag INTO the child tick.** MT-2's `orchestrate-extract-containers` drives each child tick with `{"container" <selected-entry>}`, and the selected-entry carries `:shape`/`:roles` (MT-2 `container_select/selected-entry`). So the AUTHOR + APPLY nodes can read `(:shape container)` = `:long-form` to gate the aggregating path. VERIFY this threads through the child-tick blackboard to the AUTHOR/APPLY `:code`/`:llm` node inputs (the container is a `:reads` input); if the shape tag is dropped at the child-tick boundary, thread it explicitly (mirror how `model-spec`/`max-windows` already thread).
- **The streaming machinery to REUSE (do NOT fork — #8):** `rlm-discovery/apply-extraction-transform!` (~1080) already resolves the descriptor → uniform `container-contract` → `:stream-all` → lazy windows → `normalize-window-rows` → a single flat fold with per-row `dual-key` + error counting. The aggregating apply is the SAME stream with a DIFFERENT fold (reduce into a bounded per-key top-N accumulator instead of mapcat-ing per-row drafts). Factor the shared "descriptor → lazy dual-keyed row-seq" so both folds reuse it; only the fold differs.
- **The SCI sandbox already whitelists the aggregation primitives** (`apply-step-safe-core` ~887: `group-by frequencies reduce sort-by take merge-with assoc update conj into` …) — but for MT-3 the aggregation is DETERMINISTIC SKELETON code (not model-eval'd), so the sandbox is only relevant if the AUTHOR still emits any `:code`. The rollup SPEC is DATA (a map), not a fn-string — so it does NOT need SCI eval (simpler + safer than the per-row transform).
- **GM-1 `grain-reify-block`** confirms the intended output: a long-form container's rollup is the keyed subject's FLAT attribute (`topSkills: [...]`), NOT a reified Observation / Skill node.

## Read-first (in order)
1. `development/src/mt3_aggregate_prototype.clj` — the proven mechanism (rollup spec + `stream-aggregate` + the scale-filter + the cross-check). Your reference.
2. `ontology/core/rlm_discovery.clj` — `apply-extraction-transform!` (~1080, the streaming fold to reuse) + `apply-step-safe-core`/`eval-transform-fn` (~887/949) + `carry-linking-values` (~1020, still applies to the emitted drafts).
3. `ontology/core/extract_subbehavior.clj` — `extract-per-container-def` (SAMPLE→AUTHOR→APPLY) + `apply-extraction-transform-for-container-code` (~498) + `sample-rows-for-container-code` (~483) + how the container (with `:shape`/`:roles`) reaches the nodes.
4. `ontology/core/discovery_tree.clj` — `transform-node-prompt` (~665) + `transform-contract-keys` (~116): the AUTHOR contract to EXTEND (gated on `:long-form`) so the AUTHOR can emit an `:aggregation-spec` instead of a per-row `:transform-source`.
5. `ontology/core/container_select.clj` (MT-2) — the `:selected-entry` `:shape`/`:roles` the AUTHOR reads.

## The change (shape — build to the proven mechanism, re-orchestrate not rewrite)

- **A pure deterministic executor** (new ns `ontology/core/container_aggregate.clj`): `stream-aggregate` `(spec lazy-rows)` → `{:concept-drafts [one per key] :rows-seen :rows-kept :distinct-keys :peak-acc-entries :rows-errored}`. Bounded per-key top-N (keys × N), numeric-coerces `:value-col`, applies the `:filter-col`/`:filter-val` scale dimension, honest counts (a row missing key/element/value is skipped + counted, never fabricated — #4/#5). Pure + total.
- **An `:aggregation-spec` AUTHOR contract** (extend DT4, GATED on `(:shape container) = :long-form`): the AUTHOR emits `{:key-col :element-col :value-col :n :attr-name :entity-type :filter-col? :filter-val?}` (role hints ADVISORY, verified against the sample — #1). Reasoning FIRST (#13). Non-long-form containers keep the EXISTING per-row `:transform-source` path UNCHANGED (behavior-preserving).
- **The APPLY node routes** on which the AUTHOR produced: an `:aggregation-spec` → `stream-aggregate` over the shared lazy row-seq; a `:transform-source` → the existing `apply-extraction-transform!`. Both feed the SAME `:concept-drafts` contract downstream (canonicalize / reconcile / GC-11a carry unchanged — the emitted per-key draft still gets linking-values carried where present).

## TDD cycle list (tests FIRST, red→green, behavior through PUBLIC fns)
1. **`stream-aggregate` group-by + top-N (pure, synthetic long-form):** rows for 2 keys × several elements with a SCALE column; assert ONE draft per key, the flat attr array = the correct top-N element labels by value, and the OFF-scale rows excluded by `filter-col`/`filter-val`. (The deterministic heart.)
2. **Boundedness + coercion + honest counts:** string values coerced; peak-acc-entries = keys×N regardless of row count (feed many rows per key); a row missing key/element/value is skipped + counted in `:rows-errored`/`:rows-kept`; empty input → honest zero, no crash.
3. **AUTHOR emits an aggregation-spec for `:long-form`, a per-row transform otherwise (stubbed :llm):** a `:long-form` container → the AUTHOR contract yields an `:aggregation-spec`; a non-long-form container → the existing `:transform-source` path (behavior-preserving — assert an existing extract test still green).
4. **APPLY routes to stream-aggregate when a spec is present (public APPLY seam):** given an `:aggregation-spec`, the APPLY node produces per-KEY drafts (not per-row); given a `:transform-source`, it produces per-row drafts (existing path unchanged).

(The LIVE proof — real O\*NET `Skills`→`topSkills` + `Knowledge`→`topKnowledge`, one draft/SOC, correct vs spot-check, bounded, per-row path unregressed — is the `/inspect-orc`.)

## Do NOT touch
- The MT-1 classifier / MT-2 selection — consume their tags, don't modify.
- The per-row `apply-extraction-transform!` fold for NON-long-form containers — behavior-preserving.
- Reconcile / Axiom / Embed / the CQ-gate / GC-11a linking carry (the per-key draft flows through them unchanged).
- No baked O\*NET/CIP/SOC column or scale-value names; NO hardcoded aggregation — the AUTHOR picks every column + the filter from the sample (#12).

## Live-QA (`/inspect-orc` — the orchestrator runs this, adversarially)
- REAL O\*NET `Skills`: ONE occupation draft per SOC carrying a POPULATED `topSkills` flat array (top-N by importance) — NOT ~62k per-row fragments, NOT 0 (a 0/empty topSkills is a FAIL — #4). Witnessed.
- Same for `Knowledge` (`topKnowledge`) — domain-agnostic, the AUTHOR picks the columns; and confirm the AUTHOR did NOT mistake an ID column for the value (the MT-1 bug MT-3 must not inherit).
- The full container completes without OOM; the top-N for 2-3 occupations MATCHES an independent hand-aggregation (spot-check).
- A non-long-form (`:entity`) container's per-row extraction is UNREGRESSED. Ontology brick gate green; 0 orphan this-repo JVMs (`pgrep -f`).

## Dependency rule
If a later tracer needs an earlier one's REAL signature (the `stream-aggregate` return shape, the `:aggregation-spec` contract keys, the APPLY routing predicate), land + inspect the earlier tracer first, then craft the next from the real signature — do NOT pre-write against a guess.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (an empty/mixed-scale top-N that "ran" is wrong).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (0 populated topSkills is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause (a row missing a column is counted, never fabricated).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the `stream-all` streaming machinery + the extract sheet; do NOT fork the apply path.
9. Adversarial qualitative verdict — hunt for a wrong/empty/scale-mixed top-N masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the streamed bounded top-N executor AND the Model-authored rollup spec.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — the Model picks the key/element/rank/filter columns from the sample; name NO O\*NET/CIP/SOC column; no hardcoded aggregation.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
