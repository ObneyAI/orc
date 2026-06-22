# EB4 Handoff — Extract subbehavior sheet (author + apply per-row transform)

Fresh-context brief for EB4 (`docs/build-timeline/issues/evolutionary-builder/EB4-extract-subbehavior.md`).
Crafted post-merge from the REAL EB3 contract + the REAL V20/DT4 signatures. Work
DIRECTLY on `feature/ontology-architecture` (NOT a worktree). DO NOT COMMIT — leave
staged; the orchestrator runs `/inspect-orc` then commits. Implement via `/tdd`.

## The goal
The **Extract** subbehavior as a delegatable ORC sheet composed of REAL nodes:
`:code` (sample real rows + key-shape) → `:llm` (author the per-row transform,
grounded in the real key-shape + the model-spec's grain/scope, `:reasoning` FIRST)
→ `:code` (V20 apply-step over the FULL source, per-row error counting). It
re-houses DT4 + the DT4-grounding field-grounding fix. Delegated a `model-spec`
(EB3) + a `source` descriptor; produces a field-grounded transform applied over the
whole source → a sane SCOPED concept count.

## Read first
1. `components/ontology/src/ai/obney/orc/ontology/core/model_subbehavior.clj` — the SHEET PATTERN to mirror: `model-subbehavior-name` / `-def` / `register-model-subbehavior!` (`dsl/workflow` + `dsl/build-workflow!` + `dsl/sheet-id-for-name`). Also `model-spec-contract-schema` (~L106) — the structured shape EB4 READS as input (`:entity-types`/`:scope-filter`/`:edges`/`:embed-fields`).
2. `components/ontology/src/ai/obney/orc/ontology/core/discovery_tree.clj`:
   - `mechanical-sample-rows` (~L413) — REUSE for the first `:code` node: the AUTHORITATIVE real key-shape (does NOT trust the profile; csv → string-keyed maps, sql/excel → keyword-keyed). This is the DT4-grounding fix.
   - The DT4 transform-authoring prompt (~L282–376) + the "honest negative" note (~L376–396): the model authored structurally-correct transforms that MIS-GROUNDED field names until the real key-shape was surfaced into the prompt. The `:llm` node MUST be given the real sample-row keys.
   - `extraction-transform-contract` (~L118–121): `[:transform-source :selector]`.
3. `components/ontology/src/ai/obney/orc/ontology/core/rlm_discovery.clj`:`apply-extraction-transform!` (~L991) — REUSE for the final `:code` node: takes `{:descriptor {:type :csv|:sql|:excel :path …} :transform-source "<clj source: (fn [row] -> {:concept-drafts [...] :relationship-drafts [...]})>" :selector …}`, streams the FULL source via V19 `stream-all`, applies per-row, returns the draft set + per-row error counts. Do NOT fork it.
4. `components/ontology/src/ai/obney/orc/ontology/core/survey_subbehavior.clj` + the EB1 `delegate_composition_test` — the delegate/registry round-trip pattern.

## Node design (the sheet)
- `:reads [:model-spec :source]` ; `:writes [:concept-drafts :relationship-drafts :extraction-report]` (or the shape that mirrors `apply-extraction-transform!`'s return — confirm against the real fn).
- **Node 1 `:code`** — call `mechanical-sample-rows` on `:source` → `:sample-rows` (real key-shape). Native Clojure return (C1: `:code` → parsed naturally).
- **Node 2 `:llm`** — author the transform. `:reads [:model-spec :sample-rows]`, `:writes [:reasoning :transform-source :selector]` (`:reasoning` FIRST, #13). The prompt MUST include the real sample-row KEYS (from node 1) + the model-spec's entity-types/grain/scope, and instruct: emit a `(fn [row] …)` source string that mints NODES (not edges), honors the grain (one concept per entity, not per raw row) + scope-filter, grounds field access in the ACTUAL keys. `:transform-source` is a STRING → crosses `:delegate` fine (no structured-map C1 issue).
- **Node 3 `:code`** — call `apply-extraction-transform!` with `{:descriptor source :transform-source :selector}` → drafts + per-row error count. Native return.

## Do NOT
- Fork `apply-extraction-transform!` or `mechanical-sample-rows` (reuse).
- Hand-correct the authored transform (the P1 verify-not-assume criterion is that the AUTONOMOUS transform works). No hardcoded field names / vertical knowledge (#12).
- Touch EB1/EB2/EB3 or unrelated files. Commit. Create a worktree.

## Prototype (SOFT — do first, briefly)
Light probe: feed the `:llm` authoring node a REAL model-spec (capture one from EB3's live verify or run EB3) + real `mechanical-sample-rows` output for a real source, and check it emits a field-grounded `(fn [row] …)` that references the ACTUAL keys (not guessed ones). Confirms the grounding before full TDD. Capture the probe.

## Verify (orchestrator independently re-runs)
- `/tdd` red→green: subbehavior sheet built + registered; delegated a model-spec + source; the 3-node pipeline runs; contract round-trips (drafts on the parent bb, read back from the projection — discipline 7).
- **LIVE verify (REAL Grain + REAL LLM, the P1 acceptance):** on a real source (a real CSV/SQL), the AUTONOMOUS transform (NO hand-correction) yields a SANE SCOPED concept count — NOT a raw-row dump, nodes not edges, fields grounded, per-row errors counted, no abort. Capture verbatim (`docs/build-timeline/live-verify/EB4-extract.md`).
- Green under BOTH `clj -M:poly test brick:ontology` AND direct `:dev:test` of the EB4 test ns. (Note: the heavy ColBERT `build!` tests are now on the on-demand lane; EB4's own test should be a fast unit/contract test — if its LIVE verify is slow/LLM-driven, it belongs in `development/ontology-integration`, NOT the brick gate. Decide per its runtime.)
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees `orc-gepa-metric`/`orc-main`/etc. — kill only your own by PID).

## Report back (raw data)
The sheet + 3-node design as built; the SOFT-probe result; the LIVE-verify capture (the autonomous transform's authored source + the resulting scoped concept count + per-row errors — verbatim); whether EB4's test is a fast unit test (gate) or a live test (on-demand lane) + why; dual-runner totals; "0 orphan JVMs"; every file changed by path; honest negatives. DO NOT COMMIT. Binding Core Disciplines block 1–13 (in the EB4 issue) in force verbatim.
