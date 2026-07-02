# Handoff — MT-2: Survey-driven relevance rank + bounded container selection

**Issue:** [`../issues/meaningful-multi-table-extraction/MT-2-relevance-selection.md`](../issues/meaningful-multi-table-extraction/MT-2-relevance-selection.md)
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` = shell env var only. **Run ONE bounded build at a time; `pgrep -f 'clojure|cpcache'` JVM hygiene (see the reference memory); confirm 0 this-repo orphan JVMs after any live run.**
**Blocked-by:** MT-1 (LANDED, commit `2529db57`) — the pure `classify-container-shape` classifier is the deterministic heart; this slice consumes its real verdict/tags. Do NOT re-derive or re-implement it.

## The problem (established — don't re-derive)

`extract_subbehavior.clj` `orchestrate-extract-containers` (~1484) picks containers **blindly**:

```clojure
all-containers (list-source-containers source)
cap (or max-containers default-max-containers)
containers (vec (take cap all-containers))     ; <-- the blind take-first-N-alphabetical
```

On the O\*NET directory `list-source-containers` returns ~40 sheets sorted ALPHABETICALLY (`source_tools_excel/do-excel-dir-sheets` → `sort-by getName`). `take cap` therefore grabs whatever sorts first — junction **bridges** (`Abilities to Work Activities`) and tiny **reference** lookups (`Scales Reference` = 32 rows) — the exact noise A2's manual model discards, while the occupation-centric tables that matter may fall outside the cap. MT-1 proved the classifier separates these deterministically; MT-2 wires it into selection and adds an LLM relevance rank over the survivors.

## The design (settled by the issue — build it faithfully, do not re-litigate)

Mirror the **GC-6 `:vocabulary`** and **GM-1 `:graph-context`** threading EXACTLY: compute a selection centrally (at/after Survey), thread it as an OPTIONAL input through `delegate-model-extract!` → the pipeline sheet → the Extract delegate → `orchestrate-extract-containers`, which consumes it instead of `(take cap …)`. Absent → fall back to today's blind take (backward-compat; the csv single-container path and every existing test stay green).

Selection = **structural pre-filter (deterministic) → LLM relevance rank (over survivors) → bounded take**, with an HONEST total-vs-selected report.

### The real signatures you build on (grounded — verified in the code)

- **Uniform, format-agnostic container access** (`orc_service/core/source_tools.clj` `container-contract`, ~289): `(container-contract {:type … :path …})` → `{:list-containers (fn [] [{:name … :path … :sheet …}]) :sample-rows (fn ([container])([container opts])) …}`. `:list-containers` is ALWAYS `[{:name …}]` across csv/sql/excel. `:sample-rows` takes ONE `list-containers` entry + opts `{:limit N}` and returns a WRAPPER MAP `{:rows [{col val} …] :row-count N …}` (excel also carries `:header`) — NOT a bare vector; the adapter must unwrap `:rows` (this exact mismatch was caught LIVE — the agent coded to a wrong "bare vector" note here and it crashed on the real contract with `keys`-on-a-MapEntry; `container_select/normalize-sample-result` now unwraps it). This is the ONE surface to use — do NOT reach for the excel-specific `sheet-columns`/`count-rows` symbols (that would break format-agnosticism, discipline #12). `list-source-containers` (`extract_subbehavior.clj` ~179) already wraps `container-contract`'s `:list-containers`.
- **Row-count without a count tool:** the uniform contract exposes NO `count-rows`. Get the tiny?-signal by **over-sampling**: request `:sample-rows` with `{:limit N}` for N comfortably above MT-1's `tiny-row-count` (50) — e.g. `{:limit 64}`. Pass `(count rows)` to the classifier as `:row-count`: if fewer than the limit came back it's the EXACT count (→ a genuine tiny reference is caught); if the limit came back the table has ≥N rows (→ not tiny, correct). The header is `(vec (distinct (mapcat keys rows)))`. Sampling ~64 rows also gives the classifier a fine distinct-ratio signal (MT-1 fixtures used 30).
- **MT-1 classifier** (`ontology/core/container_shape.clj` `classify-container-shape`, pure): `{:header … :sample … :row-count …}` → `{:shape :entity|:long-form|:wide-stats|:bridge|:reference :keep? bool :roles {…} :stats […]}`. `:keep? false` = structural noise (bridge/reference). Carry `:shape` + `:roles` forward for MT-3.
- **The vocabulary-threading template to copy** (`central_evolver.clj`): `delegate-synthesize-vocab!` (~679, a standalone `:llm` subbehavior delegated by name) is the pattern for the rank sheet; `delegate-model-extract!` (~403) shows how an optional structured input is declared in `:bb-schema` (`[:maybe …]`), listed in `:reads`, and forwarded via `:inputs`; `model-extract-pipeline-def` (~117) shows the pipeline sheet's blackboard + the Extract delegate `:reads [:model-spec :source :max-containers :max-windows]` you extend; the MAIN build path computes `graph-context`/`vocabulary` before calling `delegate-model-extract!` (`~804` focal-close and the initial-build call site — grep `delegate-model-extract!`/`model-extract-fn`) — add the `delegate-select-containers!` call alongside, threading `:selected-containers` in.

## Read-first (in order)
1. `ontology/core/extract_subbehavior.clj` — `orchestrate-extract-containers` (~1438; the `take cap` at ~1484 is the consume site) + `list-source-containers` (~179).
2. `ontology/core/container_shape.clj` + `test/…/container_shape_test.clj` — the MT-1 classifier you consume (do not modify).
3. `orc_service/core/source_tools.clj` — `container-contract` (~289): `:list-containers` + `:sample-rows` uniform shapes.
4. `ontology/core/central_evolver.clj` — `delegate-synthesize-vocab!` (~679, the rank-sheet template), `delegate-model-extract!` (~403, the input-threading template), `model-extract-pipeline-def` (~117), and the initial-build + `focal-close!` (~793) call sites of `delegate-model-extract!`.
5. `ontology/core/survey_subbehavior.clj` — the delegatable-`:llm`-sheet shape + reasoning-first + structured-`[:map …]`-write-crosses-`:delegate` discipline the rank sheet mirrors.

## The change (shape — 4 vertical tracer bullets, each red→green before the next)

**New pure logic lives in a new ns** `ontology/core/container_select.clj` (keep it pure + injectable so it unit-tests without a live LLM; effects — sampling + the LLM rank — are injected capabilities defaulting to the real impl, faked in tests). Threading edits touch `central_evolver.clj` + `extract_subbehavior.clj` only.

- **`classify-source-containers`** `(source {:keys [list-fn sample-fn sample-limit]})` → `[{:name :container :shape :keep? :roles :header :row-count}]`. `list-fn`/`sample-fn` default to `container-contract`; inject fakes in tests. Over-samples (default limit 64), builds header from row keys, calls `classify-container-shape`.
- **`select-containers`** `(candidates {:keys [goal cap rank-fn]})` → `{:selected [{:name :shape :roles}] :dropped [{:name :shape :reason}] :report {…}}`. Deterministic pre-filter drops `:keep? false` (reason = the shape). `rank-fn` (injected) orders the SURVIVORS by relevance to `goal`; RECONCILE its output against the known survivor names — keep only known names, and append any survivor the ranker omitted at the END (honest, never a silent drop). Bounded `take cap`. Report carries `:containers-total`/`:survivors`/`:selected` counts + the dropped list with reasons (no false-green).
- **The real `rank-fn` = a delegated `:llm` rank sheet** (mirror `synthesize-vocab`): reads `:goal` + the candidate summaries (name, shape, columns, approx-row-count — the LLM sees real headers, but CODE names none — discipline #12), writes `:reasoning` FIRST (#13) + an ORDERED `:selected-container-names`. Relevance ONLY, never identity. `delegate-select-containers!` central seam runs `classify-source-containers` → `select-containers` (with the delegated rank as `rank-fn`) and returns `:selected-containers`. If the rank `:delegate` fails, degrade HONESTLY to structural survivors in list order with a surfaced reason (NOT a silent swallow — #5).
- **Thread `:selected-containers`** through `delegate-model-extract!` (`[:maybe …]` bb-schema key + `:reads` + `:inputs`) → `model-extract-pipeline-def` (blackboard key + the Extract delegate `:reads`) → `orchestrate-extract-containers`: when present, `containers` = the selected list (already bounded + shape-tagged); when absent, the current `(take cap …)` fallback. Compute the selection in the MAIN build path next to `vocabulary`/`graph-context` and pass it into `delegate-model-extract!`.

## TDD cycle list (tests FIRST, red→green, behavior through PUBLIC fns)
1. **`classify-source-containers` (injected sampler):** a controlled 4-container set (one entity, one long-form, one bridge, one tiny-reference) via a FAKE `list-fn`/`sample-fn` → each carries the right `:shape`/`:keep?`/`:roles`; the over-sample→row-count path caps the tiny one correctly. (Bridges MT-1 to the uniform contract; no live LLM.)
2. **`select-containers` pre-filter + bound (fake rank-fn = identity order):** bridge/reference dropped with reasons; survivors kept with shape tags; `take cap` respected; report totals honest.
3. **`select-containers` rank reconciliation (fake rank-fn that drops + reorders + invents a name):** invented name ignored; dropped survivor appended at end (never lost); order otherwise follows the ranker. (The adversarial heart — proves no silent drop, no LLM-invented identity.)
4. **`orchestrate-extract-containers` consumes `:selected-containers`:** given a selected list, the orchestrator drives child ticks for EXACTLY those containers (not `take cap` of the raw list); absent → the existing take-cap path is unchanged (assert an existing extract test still green). Behavior through the public orchestrator seam.

(The LIVE proof — real O\*NET selects occupation-centric tables, junctions dropped, goal discriminates, report honest — is the `/inspect-orc`, not a unit test.)

## Do NOT touch
- `container_shape.clj` / `container_shape_test.clj` — MT-1, landed + verified. Consume, don't modify.
- GM-1 (graph-context) / GC-6 (vocabulary) threading — mirror the pattern, don't alter the existing keys.
- The per-container Extract unit (SAMPLE→AUTHOR→APPLY), Reconcile, Axiom, Embed, the CQ-gate — MT-2 changes only WHICH containers reach Extract, not how one is extracted.
- No baked O\*NET/CIP/SOC column or table names anywhere; NO hardcoded table allow/deny list (#12).

## Live-QA (`/inspect-orc` — the orchestrator runs this, adversarially)
- A REAL O\*NET build at the dev cap: the SELECTED containers are the occupation-centric ones (`Occupation Data`, `Skills`, `Knowledge`, …), NOT `Abilities to Work Activities`/`Work Context`/tiny references. Witnessed, not asserted.
- Two goal-scoped runs whose goals emphasize DIFFERENT facets → the rank ORDER differs (the LLM relevance actually discriminates — not a rubber-stamp that returns list order regardless).
- The report surfaces `:containers-total` vs `:selected` + drop reasons — an honest negative if a relevant table was dropped or noise kept.
- Absent-selection path (a csv single-container source) unchanged. Ontology brick gate green (`clj -M:poly test brick:ontology`, and `:all-bricks` if central_evolver/source-tools touch boundaries). 0 orphan this-repo JVMs (`pgrep -f`).

## Dependency rule
If a later step needs an EARLIER step's REAL produced signature (e.g. the rank sheet's actual `:writes` key, or `classify-source-containers`' real return shape), do NOT pre-write against a guessed shape — land + inspect the earlier tracer, then craft the next from the real signature.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause (an LLM-rank failure degrades HONESTLY with a surfaced reason, never a silent swallow).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse Survey + the pipeline threading + the uniform container contract; do NOT fork.
9. Adversarial qualitative verdict — hunt for a relevant container silently dropped or noise silently kept; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic pre-filter AND the LLM relevance rank.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — structural + goal-relevance only; name NO O\*NET/CIP/SOC column or entity; NO hardcoded table list.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
