# RR-22 implementation handoff: offered patterns declare their key bindings and are offered whole

## Goal

A worked pattern offered to a model must be USABLE: its code is present (RR-6 made the
source real, RR-20 offers the exact source), it is offered WHOLE (never truncated —
ratified decision, see the issue), and it declares what it reads and writes so binding
it to a new task is a mechanical rebind rather than a re-derivation. Behaviours stay
advice: nothing here executes a pattern on the model's behalf.

## Read first

1. `AGENTS.md`, `docs/ORC-PRINCIPLES.md`, `docs/RLM-GUIDE.md` (the tree DSL a model writes).
2. `docs/issues/rr-durable/RR-22-offered-patterns-declare-their-key-bindings.md` (five
   acceptance criteria; the second is the ratified no-truncation rule).
3. Dossier G13 and G6 in `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`.
4. Spec excerpt below; `specs/ontology.allium` `OfferedPatternsAreUsable` (~314).
5. RR-6 and RR-20 landed handoffs (`docs/build-timeline/handoff-plan/RR6-*`, `RR20-*`).

## Verified mechanism map (read from the landed code)

- **What is offered**: the assembled description's `:strengths[]` entries
  (`components/ontology/src/ai/obney/orc/ontology/core/read_models.clj` `principle-entry`,
  ~965–1000) carry `:recommended-pattern` = the shape claim's `:recommendation` = the exact
  `:generated-tree-source` text (RR-20). Schema: `interface/schemas.clj` `principle-entry`
  (~42–72, open map, already has optional `:verdict-corroborations`).
- **Where the model sees it**: R-Inject —
  `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`
  `format-principle-entry` (~803) renders "Worked example DSL (corpus reference — adapt to
  your task)" and TRUNCATES with `(truncate recommended-pattern 1200)` (helper ~793,
  appends "…[truncated]"); `format-seed-body` (~833) ranks strengths by `:confidence` and
  keeps `traits-cap` (2) of them; `apply-r05-classifier-context` composes the prompt
  (`:instruction`) — the public seam `r_inject_classifier_context_test.clj` uses
  (`(tp/apply-r05-classifier-context node ctx)` with `ontology/get-description` redefined).
- **The DSL declares keys per node**: node option maps carry `:reads`/`:writes`
  (`[:llm {:reads [:doc] :writes [:summary]}]`, `[:code {:reads … :writes … :fn (fn …)}]`),
  `[:final {:keys [...]}]` names the outputs; `rlm_dsl.clj` `rlm-dsl->orc-dsl` (~84–170)
  is the authoritative walk. Ontology does NOT depend on orc-service (only its sheets
  namespaces do), so a pure binding-derivation over the parsed EDN belongs in ontology
  (or a shared pure namespace), not in `rlm_dsl`.
- **Prior art / seams**: `r_inject_classifier_context_test.clj`,
  `format_rlm_principles_test.clj` (ontology `format-context-for-llm`),
  `description_events_test.clj` `all-seed-recommended-patterns-validate-via-rlm-dsl` (every
  seed pattern parses), `cc15_reranker_enrichment_contract_test.clj` (exact-equality on
  `compact-strengths` — do not forward new fields through it),
  `rr20_public_lifecycle_test.clj` (scripted-provider checkpointed researcher through
  `sheet/execute`, the Seam-1 pattern to reuse for the live adopt).

## Exact behavioral change

1. **Offered whole.** Remove the 1,200-character truncation of `:recommended-pattern` in
   `format-principle-entry`; delete `truncate` if nothing else uses it. No emergency
   bound is reintroduced. (`traits-cap` — how MANY strengths are shown — is not truncation
   of a pattern and stays.)
2. **Declared key bindings.** Add a pure function (ontology) that derives a pattern's
   bindings from its exact source text: parse the EDN; walk nodes in tree order; collect
   `:reads` and `:writes` of every node and the `:keys` of `[:final …]`; `:reads` =
   keys read that no earlier node in tree order wrote (the pattern's external inputs);
   `:writes` = every key written; `:outputs` = the final's keys (when present). Unparseable
   or placeholder-bearing text yields nil bindings (never throws into assembly).
3. **Carried additively.** `principle-entry` forwards `:pattern-reads`, `:pattern-writes`
   (and `:pattern-outputs` when present) as optional vectors of keywords on the assembled
   strength entry when the pattern parses; schema additions optional; NOT forwarded
   through `compact-strengths` (cc15 stays byte-identical).
4. **Rendered for the model.** `format-principle-entry` prints, under the worked example,
   one line naming the declared bindings, e.g. "Reads: :doc · Writes: :summary · Outputs:
   :summary — rebind these keys to your task's blackboard; the logic stays as written."
   Wording must keep the pattern ADVICE (mimic/adapt), never an instruction to execute it.
5. **Nothing executes a behaviour.** No new primitive, no automatic adoption.

## TDD cycle list (contract tests supplied by the orchestrator; RED confirmed)

Contract namespaces (never weaken, delete, skip, narrow or over-mock):
- `components/ontology/test/ai/obney/orc/ontology/rr22_pattern_key_bindings_test.clj`
- `components/orc-service/test/ai/obney/orc/orc_service/rr22_offered_pattern_whole_test.clj`
Orchestrator RED run: **4 tests / 19 assertions, 14 failures, 0 errors.** Already green before implementation (findings, not success): the 6,000-character fixture sanity check, "the pattern is still offered as its exact source" (RR-20), "an entry without a pattern declares no bindings", and "never a mandate" — keep them as durable guards.

1. RED→GREEN `pattern-bindings-are-derived-from-the-exact-source` (ontology): external reads,
   writes, outputs; a placeholder/unparseable pattern → nil.
2. RED→GREEN `assembled-strength-entry-carries-its-bindings-additively` (ontology): through
   the real claim command + `ontology/get-description`; existing fields byte-identical;
   `compact-strengths` untouched.
3. RED→GREEN `an-offered-pattern-is-never-truncated` (orc-service, R-Inject seam): a 6,000-char
   pattern appears whole in `:instruction`, no "…[truncated]".
4. RED→GREEN `the-rendered-pattern-names-its-key-bindings` (orc-service, R-Inject seam).
5. RED→GREEN `a-model-can-adopt-a-pattern-against-different-keys` (orc-service, Seam-1):
   default-checkpointed researcher whose scripted provider receives the prompt (assert the
   whole pattern and its bindings are in the provider's input), then emits the pattern
   rebound to new keys; the campaign succeeds and the emitted tree's shape fingerprint equals
   the offered pattern's (same logic, different keys) — proving the rebind is mechanical.
6. Run: both contract namespaces, `r-inject-classifier-context-test`,
   `format-rlm-principles-test`, `description-events-test`, `cc15-reranker-enrichment-contract-test`,
   `cc3-assembly-properties-test`, `rr20-*` (five), `checkpointed-researcher-test` (alone),
   then the two solo ontology graphs; allium at baseline; `git diff --check`; orphan check.

Harness: `run-focused.sh` (local Grain pins), one JVM at a time, foreground waits;
`es/read` returns a reducible (`into []` first); never edit `specs/*.allium`.

## Spec excerpt (verbatim)

```
    @invariant OfferedPatternsAreUsable
        -- A pattern offered to a model as proven can actually be used: the code
        -- within it is present rather than elided, and what it reads and writes
        -- is declared, so binding it to a new task is a mechanical step rather
        -- than a reconstruction. A behavior remains evidence a model reasons
        -- with and never a pipeline it is compelled down.
```

## Allium obligation reconciliation

The issue names no `allium plan` obligation ("this slice repairs behaviour that predates
the campaign model"); `OfferedPatternsAreUsable` is a contract `@invariant` with no
generated obligation. Report `0 obligations, 0 covered, 0 uncovered` and the contract
namespaces' final numbers; every mock/stub/TODO listed.

## Do NOT touch

`specs/*.allium`; RR-23 (tag scoping); RR-20's claim writers and RR-21's report beyond
reading them; the Grain checkout and pins; other worktrees; existing generated tests.

## Report back

Per-cycle RED/GREEN counts; files changed and boundaries exercised; every changed
pre-existing assertion with justification; the `truncate` helper's fate; anything
unverified.
