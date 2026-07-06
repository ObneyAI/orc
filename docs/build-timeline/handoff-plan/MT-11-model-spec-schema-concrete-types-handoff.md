# Handoff — MT-11: declare model-spec fields with concrete types + descriptions (the real parse root fix)

**Parent:** the model-spec parse reliability (the ROOT behind MT-10's downstream coercion). Plan: `/Users/darylroberts/.claude/plans/precious-sleeping-kurzweil.md`.
**Blocked-by:** none. **PROTOTYPE ALREADY PASSED** (see below) — this is the full rollout + TDD.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene.

## The problem (investigation + prototype PROVEN — don't re-derive)
The model-spec's `:entity-types` was declared with a concrete OUTER shape `[:vector [:map …]]` but **`:any` leaf values** and **no `:description`s**, and the prompt DEMANDED `:grain-strategy` be a **bare keyword** (`:canonical-row-filter`). A bare keyword is valid EDN but INVALID JSON, so when DSCloj got JSON from the model (intermittently), it broke both parsers → `:entity-types` arrived as an unparsed string → empty-vocab hard stop → the acceptance died ~half the runs. MT-10 patched this downstream (coerce the mess); THIS fixes it at the source per the canonical pattern (`json_ontology.clj:297-304`, `docs/ORC-SERVICE-GUIDE.md:388-449`): concrete leaf types + descriptions + a string `:enum`.

## Already done + PROVEN (uncommitted, in the working tree — inherit it)
1. `model_subbehavior.clj` `model-spec-contract-schema` — `:entity-types` leaves now concrete: `:type` → `[:string {:description …}]`, `:uri-keying-fields` → `[:vector {:description …} :string]`, **`:grain-strategy` → `[:enum "canonical-row-filter" "breakdown-as-entity"]`** (string enum — the load-bearing change; the model emits a QUOTED string, valid in JSON AND EDN).
2. `discovery_tree.clj` (~362-371) — the grain-strategy prompt + `contract-block` now request the **string** value, not a bare keyword.

**PROTOTYPE RESULT (10 live O\*NET model-extract calls, `mt9-retry-diagnostic`):** 10/10 `:entity-types` arrived as `VECTOR(parsed-ok)` on the FIRST attempt, all `grain-strategies` were quoted strings, `vocabulary-retries: 0` on every call. That is 100% first-attempt parse vs ~50% before. The approach is validated; DO NOT revert it.

## Your work — extend the SAME pattern to the rest of the family + TDD
### A. `model_subbehavior.clj` `model-spec-contract-schema` — the remaining fields
- `:edges` inner map: `:source-type` / `:target-type` / `:predicate` → `[:string {:description …}]` (currently `:any`).
- `:scope-filter` inner map: `:field` → `[:string {:description …}]`, `:values` → `[:vector {:description …} :string]` (currently `:any` / `[:vector :any]`).
- Keep `{:closed false}` on every inner map (tolerate `:canonical-row-marker`/`:breakdown-key` extras). Keep `{:optional true}` where it is.

### B. `synthesize_vocab_subbehavior.clj` (~92-98) — the twin `:canonical-entity-types`
Same `:any`-leaf shape, same admitted intermittent parse (its docstring ~100-107). Concretize: `:type` → `[:string {:description …}]`, `:uri-keying-fields` → `[:vector {:description …} :string]`, `:aliases` → `[:vector {:description …} :string]`, `:description` → `[:string {:description …}]`. (No grain-strategy / bare-keyword here — check its prompt; only tighten if it demands a bare keyword.)

### C. Do NOT touch / do NOT remove
The MT-10 `coerce-entity-types`, `normalize-model-spec`, `normalize-grain-strategy`, and the MT-7d/MT-9 retry all STAY as shrinking safety nets. Do not touch the retry/hard-stop/binding/grain logic. No error-string matching. Domain-agnostic — the descriptions name no O\*NET field (generic guidance only).

## TDD (guard tests — these are declaration guards + a rendering assertion, tests FIRST)
The behavioral proof is the live prototype/acceptance; the unit tests GUARD the declaration so it can't silently regress to `:any`:
1. **Schema shape guard** (extend `model_subbehavior` test ns, or a new one): assert `model-spec-contract-schema`'s `:entity-types` entry-map leaves are concretely typed — `:grain-strategy` is an `[:enum "canonical-row-filter" "breakdown-as-entity"]`, `:type` resolves to `:string`, `:uri-keying-fields` to a `[:vector :string]`; and NONE of the entity/edge/scope leaves are bare `:any`. RED first (before your A-edits the edges/scope leaves are still `:any`).
2. **Rendering assertion** — call `executor/malli-schema->description` (public in `orc-service`) on the new `:entity-types` schema and assert it renders the enum as `"one of: canonical-row-filter, breakdown-as-entity"` (proving the model is told the exact values, not "any value"). This is the mechanism that fixes it — lock it.
3. **Grain-strategy string still normalizes** — `normalize-grain-strategy "canonical-row-filter"` → `:canonical-row-filter` (the enum string round-trips to the frozen keyword). Likely already covered; cite or add.
4. **Backward-compat**: existing model / extract / vocab / `coerce-entity-types` tests stay green (the coercion fallback must still handle a legacy string if one ever arrives). Run the full ontology brick gate.

## Gate + hygiene
`clj -M:poly test brick:ontology` green (run via detached `nohup … &`, NOT the Bash-tool background — those are being reaped this session). ONE test JVM at a time; kill orphan this-repo JVMs after; known consolidator flake — isolate ×3 if it's the only red.

## The reviewer's `/inspect-orc` (NOT yours)
Re-run the diagnostic (parse-rate stays ~100%) AND the MT-7c bounded acceptance (`clj -J-Xmx6g -M:dev:test -m mt7c-acceptance`, detached) — both runs reliably clear model-extract, acceptance `pass? true`, and the coercion/retry safety nets essentially stop firing.

## Deliverable — final message MUST include:
1. The guard tests red→green (final brick-gate line, exit 0).
2. The exact schema diff (all family fields) + the synthesize-vocab twin diff.
3. The `malli-schema->description` rendering assertion output (the enum → "one of: …").
4. Confirmation the MT-10 coercion/retry safety nets are untouched.
5. Anything not verified; any deviation.
6. Confirm: no commit/push; 0 orphan this-repo JVMs.

## Core Disciplines (binding — verbatim)
1. NEVER assume; the parse root is prototype-PROVEN (JSON-invalid bare keyword). 2. Verify QUALITY not completion — a schema that renders "any value" is the bug; assert the concrete rendering. 3. Instrument to root cause (done). 4. Live REAL everything; the prototype/acceptance are the proof; no false green. 5. No silent fallback removed — the safety nets stay. 6. TDD, tests first (guards). 7. No error-string/phrase matching; concrete Malli types only. 8. Re-orchestrate — extend the existing schema/prompt; do NOT fork. 9. Adversarial verdict — hunt a leaf still `:any` or a prompt still demanding a bare keyword. 10. Deterministic schema around the LLM — verify the rendering + the live parse. 11. Key = env var; never truncate model output; JVM hygiene (`pgrep -f`, one build at a time). 12. Domain-agnostic — descriptions name no O\*NET field. 13. `:reasoning` first on `:llm` nodes (unchanged).
