# Handoff — MT-7b: vocabulary proposal path (explicit new-type admission)

**Issue:** [`../issues/meaningful-multi-table-extraction/MT-7b-vocabulary-proposal-path.md`](../issues/meaningful-multi-table-extraction/MT-7b-vocabulary-proposal-path.md)
**Design (settled — do NOT re-litigate):** [`components/ontology/docs/adr/0001-canonical-vocabulary-binding.md`](../../../components/ontology/docs/adr/0001-canonical-vocabulary-binding.md) + CONTEXT.md §Vocabulary & identity ("Vocabulary proposal").
**Blocked-by:** MT-7a — LANDED (`71861f75`) + live-verified. This handoff is written from 7a's REAL signatures.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene; 0 this-repo orphan JVMs after live runs.

## The 7a signatures you build on (REAL — verified landed)
In `ai.obney.orc.ontology.core.vocabulary-binding` (alias `vb`):
- `(normalize-name k)` → normalized string | nil (THE one normalization; GC-1 delegates to it).
- `(canonical-types model-spec)` → `[{:type … :uri-keying-fields … :aliases …} …]`.
- `(resolve-entity-type vocab entity-type)` → canonical `:type` spelling | nil (normalized-EXACT vs `:type` + `:aliases`).
- `(bind-draft-types vocab drafts)` → `{:drafts [snapped…] :excluded [{:entity-type <as-emitted> :count n}…]}`.
- `(vocabulary-binding-guidance)` → the static prompt block, appended at BOTH `:instruction` assembly points in `extract-per-container-def` (the resilient `author-apply` builder + the flat `"author"` node).
In `extract_subbehavior.clj`:
- The AUTHOR nodes write `[:reasoning :transform-source :selector :aggregation-spec]` and read `[:model-spec :sample-rows :container]`.
- The APPLY seam (`apply-transform-for-container-code`): `vocab (vb/canonical-types model-spec)` → spec-type resolve/snap pre-gate → `bind-draft-types` post-apply → report `:freelanced-drafts {:count :types :rejected-aggregation-type}` (schema key `{:optional true}` in `extraction-report-schema`).
- The orchestrator (`orchestrate-extract-containers`): per-container child ticks (isolated blackboards, GC-13 bounded-parallel), reads each child's `:extraction-report` off its blackboard, accumulates → canonicalize (`canonicalize-drafts model-spec …`) → the aggregate report.

## The change (per the ADR — proposals local, deterministic post-extract reconciliation)

1. **The proposal write.** Add an OPTIONAL AUTHOR write `:entity-type-proposal` — a DATA map `{:type <name> :uri-keying-fields [<real sampled column> …] :description <self-contained>}` (`[:maybe [:map {:closed false}]]` on the per-container blackboard; C1-string tolerated via a parse mirroring `parse-aggregation-spec`). Extend `vocabulary-binding-guidance` (or a sibling block): "if this container's rows are an entity type NOT in the vocabulary, do NOT freelance — declare it ONCE as `entity-type-proposal` with keying fields copied from the REAL sampled columns, and type your drafts with exactly that proposed name."
2. **Local admission at the APPLY seam** (pure fn in `vocabulary_binding.clj`, e.g. `admit-proposal` → validated proposal | rejection-with-reason):
   - VALIDATE deterministically: every `:uri-keying-fields` entry resolves against the SAMPLE rows' keys (case-tolerant via `normalize-name` — mirror how `recover-via-value` matches); non-blank `:type`.
   - NAME COLLISION with the existing vocabulary (`resolve-entity-type` hits) → the proposal IS the existing type: snap, no new type.
   - Valid + novel → bind THIS container's drafts against `vocab + [proposal]` (local only — no shared mutable state across concurrent ticks). Invalid → the proposal is REJECTED with a reason; drafts typed with it are excluded as freelanced (honest — the existing 7a path).
   - Surface on the per-container report: `:entity-type-proposal {:proposed … :admitted?/:rejected-reason …}`.
3. **Post-extract reconciliation in the orchestrator** (pure fn, e.g. `reconcile-proposals [proposals]`):
   - normalized-NAME collision between containers' proposals → merged (first in container order wins the spelling; variants become `:aliases`).
   - same normalized KEYING-FIELD SET, different names → BOTH kept, surfaced `:requires-review` — **never auto-merged** (two distinct types can share a key field; the post-landing dedup cascade is the right court).
   - The admitted set EXTENDS the model-spec's entity-types LOCALLY in the orchestrator **before `canonicalize-drafts` runs**, so GC-1 mints canonical URIs for proposed-type drafts (their keying fields are declared). Name-merged variants: rewrite those drafts' `:entity-type` to the winning spelling before canonicalize (a deterministic snap via the alias map).
   - The aggregate report carries the LEDGER: `:vocabulary-proposals {:proposed n :admitted [{:type :uri-keying-fields :aliases} …] :merged n :requires-review […] :rejected […]}`.
4. **Scope boundary (state it honestly in the report + your final message):** admitted proposals extend the vocabulary for EXTRACT (binding + canonicalize). Downstream consumers reading the Model's original `:model-spec` off the pipeline blackboard (axiom/embed) do NOT see proposals — threading them further is explicitly OUT of this slice (a later slice can consume the ledger). Do not silently mutate the pipeline's model-spec.

## TDD cycle list (tests FIRST, red→green, PUBLIC fns; extend `vocabulary_binding_test.clj` or a sibling ns)
1. **`admit-proposal` (pure):** valid proposal (keying fields present in sample, novel name) → admitted; keying field absent → rejected with reason; name collision with vocab (case/separator variant) → snap-to-existing (NOT a new type); C1 string-form proposal parsed.
2. **`reconcile-proposals` (pure):** name-collision variants merge (first wins, aliases recorded, order-deterministic); same-keying-different-names → both kept + `:requires-review`; disjoint proposals pass through; empty → honest zero.
3. **APPLY seam (public, real csv fixture mirroring the 7a tests):** a container whose author emits a valid proposal + drafts typed with it → drafts LAND (bound to the proposal), per-container report surfaces the admission; an INVALID proposal → those drafts excluded as freelanced (the 7a path) + the rejection surfaced.
4. **Orchestrator (public, stubbed child ticks mirroring the existing orchestrator tests):** two containers proposing name-variants → ONE admitted type, drafts from both containers canonicalize to ONE URI scheme (assert via the canonicalized output); the ledger in the aggregate report; a keying-collision pair → both kept + `:requires-review`.

(The LIVE proof — a real O\*NET run where an unsampled container's entities land via a proposal instead of failing, ledger surfaced — is the reviewer's `/inspect-orc`.)

## Do NOT touch
- 7a's binding/normalization semantics (extend, don't alter); GC-1 canonicalize internals (you extend its INPUT model-spec locally, not its logic); MT-1/2/6 gates; the chunk pager; MT-4/4b; the Model subbehavior.
- NO fuzzy matching anywhere; NO shared mutable vocabulary across concurrent ticks; NO domain names in code.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (a proposal auto-merge collapsing two DISTINCT same-key types reads as "deduped" but is an over-merge; an admitted duplicate type re-fragments).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause (invalid proposal → rejected honestly; keying-collision → surfaced, never silently resolved).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; NO hardcoded phrase matching / NO fuzzy proposal matching.
8. Re-orchestrate, NOT rewrite — proposals ride the existing author contract + orchestrator accumulation; admission is a local vocab extension before the existing canonicalize; do NOT fork.
9. Adversarial qualitative verdict — hunt for a duplicate admitted type, an over-merged pair, or a hidden rejection; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the proposal content is LLM-authored; admission/dedup is deterministic; verify BOTH on real runs.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — proposals validated structurally against the runtime sample; name NO O\*NET/CIP/SOC column or entity; the general system is TESTED with O\*NET, not built FOR it.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
