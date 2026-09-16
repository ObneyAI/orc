# RR-20 implementation handoff: the worked pattern is keyed on outcome and shape

## Goal

A class's recorded worked pattern must be one a campaign SUCCEEDED with. Today the
post-emit enrichment writes whichever tree was emitted LAST into ONE claim slot per
class, never reads the outcome, and the harvest selector ranks by confidence with no
filter on what the evidence rests on — so a campaign that emits a broken tree,
watches it fail, and repairs it can crystallize the FAILED tree as the class's proven
pattern. After this slice: a successful shape records and reinforces a worked
pattern; a failed shape is recorded as failed and never displaces a success; a class
that genuinely succeeds with two shapes keeps both; the selector prefers
success-backed, occurrence-corroborated patterns over bare emitted artefacts.

## Read first

1. `AGENTS.md`, `docs/ORC-PRINCIPLES.md`.
2. `docs/issues/rr-durable/RR-20-the-worked-pattern-is-keyed-on-outcome-and-shape.md`.
3. `docs/prd/rr-durable-self-learning.md` — "Evidence and the self-learning loop".
4. Dossier decisions G4 (this slice), G11 (coherence — NOT this slice, RR-21) and G6
   (durable source) in `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`.
5. `CONTEXT.md` glossary: Worked pattern, Shape, Winning shape, Occurrence,
   Recurrence, Iteration record.
6. Spec excerpts below (verbatim); the full rules in `specs/ontology.allium`
   (`WorkedPatternsAreProvenNotMerelyRecent`, `OfferedPatternsAreUsable`,
   `PromoteWellScoredClass`) and `specs/orc-service.allium` (`CampaignIteration`,
   `RecordedTreesCarryTheirShape`).
7. RR-6 landed representation:
   `docs/build-timeline/handoff-plan/RR6-generated-code-durable-source-HANDOFF.md`
   § "Produced representation".
8. RR-19 landed mechanism (verdict occurrences):
   `docs/build-timeline/handoff-plan/RR19-outcome-recurrence-HANDOFF.md` and
   `components/ontology/test/ai/obney/orc/ontology/rr19_outcome_recurrence_test.clj`.

## Verified mechanism map (read from the landed code, not assumed)

**Producer of the emitted tree + its outcome (per Phase-2 tree execution):**
`components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj`
`execute-tree` dispatches `:sheet/record-rlm-tree-execution-completion`
(`core/commands.clj` ~line 2008) which emits `:sheet/rlm-tree-execution-completed`
carrying, all optional: `:status` (`:success :failure :partial :timeout`),
`:tree-fingerprint` (string, `rlm_fingerprint/fingerprint` of the raw tree — a SHAPE:
`:fn` and `:instruction` values are normalised to placeholders), `:generated-tree`
(authored quoted tree), `:generated-tree-source` (exact EDN text, the re-emission
authority), `:source-sheet-id` (the classified host sheet), `:source-tick-id` (the
hosting turn's tick). `:sheet-id`/`:tick-id` on the event are the EPHEMERAL Phase-2
sheet/tick. One bookend per tree execution, so a campaign with N repair rounds emits
N bookends, each with its own status.

**The immutable iteration record** (`components/orc-service/src/ai/obney/orc/orc_service/interface/schemas.clj`
`researcher-iteration-record`, ~lines 141–200, carried on
`:rlm/researcher-iteration-recorded` under `:iteration-record`): `:iteration-index`,
`:attempt-ordinal`, `:status` ∈ `:success :failure :timeout :blocked`, `:code`,
`:emitted-tree`, `:emitted-tree-source`, `:tree-fingerprint`,
`:emitted-tree-recorded?`, `:generated-code-recorded?`, bounded error/reasoning,
`:variable-delta`, `:result-profile`. The schema already enforces
`RecordedTreesCarryTheirShape` (an emitted tree recorded implies a non-empty
`:tree-fingerprint`).

**The campaign's verdict** (RR-19): `:ontology/tree-class-occurrence-recorded`
`{:source-sheet-id :source-tick-id :source-node-id :assigned-tree-id :verdict
:source-completion-event-id :recorded-at}` — exactly one per classified researcher
campaign that reached `:success | :failure | :timeout`; the campaign's FIRST terminal
node completion is the only verdict.

**The current worked-pattern writer (the defect):**
`components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj`
processor `:ontology/on-emit-enrich-tree-class` → `enrich-tree-class-with-emitted-dsl!`
(~lines 284–430). On every bookend carrying `:generated-tree` + `:source-sheet-id` it
resolves the class via `rm/get-tree-class-for-sheet` (the SHEET→class join, latest
classification for that host sheet — not the occurrence pair) and writes ONE claim
operation via `:ontology/record-claim-deltas`: identity is the fixed literal content
`emitted-pattern-trait` (`"emits this worked tree for tasks of this class"`), kind
`:strength`, `:recommendation` = `(pr-str generated-tree)`, `:episodes []`,
`:evidence-basis :emitted-artifact`. Branches: no claim → `:add`; same DSL →
`:support`; different DSL → `:edit` (overwrites the recommendation in place). It never
reads `:status`.

**The claim system it writes into** (`interface/schemas.clj` `claim-delta`, `claim`,
`evidence-basis`, `claim-kind`; `core/read_models.clj` `add-claim`, `reinforce-claim`,
`edit-claim`, `earned-status`, `principle-entry`; `core/evidence_guard.clj`
`declared-basis`, `declared-bases-admitted`): a delta names `:episodes` as
`[source-sheet-id source-tick-id]` occurrence pairs; a delta naming NO episode is
admitted only on a declared mechanical basis (`:legacy-corpus`,
`:classification-signature`, `:emitted-artifact`, `:authored`); a delta that DOES name
episodes is resolved by CC-4 against real judge evidence for those occurrences and
refused otherwise. `:evidence-basis` is set at claim creation only and preserved by
`:edit` (anti-laundering). Support is earned (+1 per support/edit); `:validated`
requires support ≥ threshold AND ≥1 post-guard (judge-resolved) episode; mechanical
claims can never validate. The assembled description's `:strengths[]` entries carry
`:trait :confidence (derived from support) :evidence-count :good-when
:recommended-pattern :first-observed-at :last-reinforced-at` — they do NOT carry the
claim's `:evidence-basis`, `:claim-id` or `:episodes` today.

**The selector (the second half of the defect):**
`components/ontology/src/ai/obney/orc/ontology/core/harvest.clj`
`best-recommended-pattern` (~line 474): highest-`:confidence` strength with a
`:recommended-pattern`, no evidence-basis or outcome filter; used by `harvest-body`.
`distinct-tree-shapes` (~line 362) counts fingerprints across ALL bookends joined by
the class's verdict-occurrence pairs — that is RR-21's coherence numerator, NOT yours.

**Existing seams / prior art:** `components/orc-service/test/ai/obney/orc/orc_service/cv2_emitted_tree_enrichment_test.clj`
(cycles 1–5: bookend carries tree; enrichment records recommended pattern; harvest-body
returns it; timeout/no-emit no-op; identical re-emit does not duplicate) and
`cc6_cv2_claim_enrichment_test.clj` (`emitted-dsl-lands-as-a-strength-claim`,
`an-identical-re-emit-reinforces-the-existing-claim`, `a-revised-emit-edits-the-same-claim`,
`enrichment-refuses-to-collapse-a-legacy-bodied-class`);
`components/ontology/test/ai/obney/orc/ontology/el4_harvest_test.clj` (gate/body);
`cc6_declared_evidence_basis_test.clj` (basis admission rules). These are the seams to
extend; several of their assertions encode the defect (last-emit-wins, one slot per
class) and will legitimately change — say so explicitly in your report, test by test.

**Every consumer of the pattern (independently enumerated; touch only what the
change requires, and report each):**

- `harvest.clj` `best-recommended-pattern` (~474) → `harvest-body` (~484, assoc at
  ~501) → `maybe-harvest!` (~571) → mint.
- R-Inject prompt: `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`
  `format-principle-entry` (~802; renders "Worked example DSL" and TRUNCATES the
  pattern to 1200 chars at ~824) and `format-seed-body` (~833; ranks/caps strengths by
  `:confidence`). The truncation makes an offered pattern unusable; its removal is a RATIFIED RR-22
  acceptance criterion (`OfferedPatternsAreUsable`). Do not fix it here; REPORT any
  place you see a pattern clipped.
- Reranker boundary: `components/ontology/src/ai/obney/orc/ontology/interface.clj`
  `compact-strengths` (~647, an explicit allowlist) and
  `core/reranker.clj` `compact-principle-entry` (~149). If you forward a new
  strength field through the allowlist, the exact-equality test
  `cc15_reranker_enrichment_contract_test.clj` (~62–66) must be updated deliberately.
- Body schema `principle-entry` (`interface/schemas.clj` ~42–65) and
  `description-body` (~82–85) are OPEN maps — an additive optional field is
  schema-safe. Tests that assert single-field equality on `:recommended-pattern`
  (`cc3_body_assembly_test`, `cc3_assembly_properties_test`, `description_events_test`,
  `r07_investigation_behavioral_seed_test`, `el4_harvest_test` `good-body`) stay valid.
- The only OTHER mechanical claim writer is CV-1 `capture-classification-signature!`
  (`orc-service/core/todo_processors.clj` ~456, kind `:representative-use`, basis
  `:classification-signature`); leave it alone.
- `consolidator.clj` `tree-class-aggregate-metrics` (~982–1028) joins bookends to
  `:ontology/task-classified` for a DESCRIPTIVE success/failure/shape baseline; it is
  reflection input, not the gate — do not retarget it in this slice.
- No code today correlates a bookend's `:status`/`:tree-fingerprint` with the RR-19
  occurrence `:verdict`; the join you add in the selector (shape ↔ successful
  campaign) is new and must be by `[source-sheet-id source-tick-id]`.

## Exact behavioral change

1. **Key the worked-pattern claim per shape, not per class.** The claim identity becomes
   `[:strength <trait for this fingerprint>]` where the trait names the shape (the
   `:tree-fingerprint`) so a class keeps one claim per distinct successful shape; the
   `:recommendation` is the exact `:generated-tree-source` text (RR-6's re-emission
   authority), never `pr-str` of a decoded tree. A shape that recurs across campaigns
   reinforces its own claim (`:support`), never another shape's.
2. **Read the outcome.** Enrichment fires from the bookend's `:status` (and, when the
   campaign's verdict is durable, from the RR-19 occurrence) — a `:success` bookend
   records/reinforces the shape as a worked pattern; a `:failure`/`:timeout`/`:partial`
   bookend records the shape as a FAILED shape (kind `:weakness`, with the shape named
   and the exact source retained as evidence, distinct claim identity) and never
   touches a success-backed claim; a bookend with no status records nothing new.
   `:edit` must no longer be used to replace one shape's recommendation with another's.
3. **Resolve the class by occurrence, not by sheet.** Use the
   `[source-sheet-id source-tick-id]` pair (`:occurrence->class` in
   `tree-class-judge-averages`, the SJ-1 join) instead of `get-tree-class-for-sheet`,
   so a bookend can never be attributed to a different turn's classification on the
   same static host sheet. Keep the legacy-body no-op and the unclassified no-op.
4. **The selector prefers proven patterns.** `best-recommended-pattern` (and whatever
   `harvest-body` / R-Inject formatting consumes) must prefer, in order: success-backed
   shape claims corroborated by verdict occurrences (RR-19 `:success` occurrences whose
   bookend carries that fingerprint) over bare emitted artefacts; never offer a
   failed-only shape as the recommended pattern; ties broken by earned support, then
   recency. Whatever field you add to the assembled strength entry to make this
   decidable (e.g. the claim's `:evidence-basis` and the shape it names) must be
   ADDITIVE on the body schema — existing consumers must see byte-identical shapes for
   existing fields.
5. **A campaign that fails then repairs** records the repaired (successful) shape as
   proven and the first shape as failed — two claims, neither displacing the other.
6. Declare exactly what each new/changed delta rests on. A shape claim that names NO
   episode must carry a mechanical basis (extend `evidence-basis` deliberately if
   `:emitted-artifact` is no longer honest for a success-backed shape — a new member
   is a closed-set change: add it to the enum, `declared-bases-admitted`, and the
   docstrings together, and report it). Do NOT name occurrence episodes on a
   mechanical delta unless judge evidence for that occurrence exists; the guard will
   refuse it and that refusal is correct.

Use the repository's schema-validated Grain command/event/processor boundaries
(`:ontology/record-claim-deltas` under its claim-set-version CAS). No bare production
appends. No process-local state.

## TDD cycle list (seeded from the orchestrator's `/propagate` run; RED confirmed before dispatch)

Propagated namespace (contract — never weaken, delete, skip, narrow or over-mock it;
if an assertion is wrong, report it as a spec/test-translation divergence and stop):
`components/orc-service/test/ai/obney/orc/orc_service/rr20_worked_pattern_outcome_shape_test.clj`.
Orchestrator RED run on the RR-19-closed tree: **6 tests / 23 assertions, 15 failures, 0 errors.**

- GREEN before implementation (a finding, not success):
  `campaign-iteration-emitted-shape-is-optional-and-recorded-trees-carry-it` — the two
  `allium plan` obligations are already enforced by the RR-5/RR-6
  `researcher-iteration-record` schema. Leave it as the durable guard.
- RED (the behavioural bridge for `WorkedPatternsAreProvenNotMerelyRecent`):
  1. `a-failed-shape-is-recorded-as-failed-and-never-offered-as-the-worked-pattern` (3)
  2. `a-repair-records-the-repair-as-proven-and-the-first-attempt-as-failed` (3)
  3. `a-later-failure-never-displaces-an-earlier-proven-shape` (3)
  4. `a-class-that-succeeds-with-two-shapes-keeps-both` (4)
  5. `a-pattern-is-offered-as-its-exact-recorded-source` (2)

Work them one at a time, in this order, RED → GREEN → refactor:

1. Cycle 1 (test 5 then test 1): the enrichment writer reads the bookend's `:status`
   and records the exact `:generated-tree-source` — a `:success` bookend records the
   shape as a worked pattern; a non-success bookend records the shape as a failed shape
   (kind `:weakness`, exact source as `:recommendation`) and offers nothing.
2. Cycle 2 (tests 2 and 3): per-shape claim identity keyed on `:tree-fingerprint`, so a
   failed shape and a proven shape are distinct claims and neither operation touches the
   other; `:edit` is no longer used to swap one shape's source for another's.
3. Cycle 3 (test 4): two proven shapes coexist; a repeated success reinforces only its
   own claim (`:support`), never duplicates or displaces.
4. Cycle 4: resolve the class by `[source-sheet-id source-tick-id]` (the SJ-1
   `:occurrence->class` join), with a RED test of your own: two turns on one static host
   sheet classified to different classes, each bookend enriches its own class.
5. Cycle 5: `harvest/best-recommended-pattern` prefers success-backed shapes corroborated
   by RR-19 `:success` occurrences whose bookend carries that fingerprint, over bare
   emitted artefacts; never returns a failed-only shape; ties by earned support then
   recency. Add whatever ADDITIVE, optional field the assembled strength entry needs to
   make that decidable; existing consumers see byte-identical existing fields.
6. Cycle 6: public lifecycle proof (Seam-1 + Seam-3): a default-checkpointed researcher
   whose scripted provider emits a failing tree then a repaired tree that succeeds; assert
   raw events (bookends, iteration records, the RR-19 occurrence), the claim set, the
   assembled body and `harvest-body` — not return values.
7. Update the existing CV-2/CC-6 seams whose assertions encode last-emit-wins or
   one-slot-per-class (`cycle5-identical-re-emit-does-not-duplicate`,
   `a-revised-emit-edits-the-same-claim`, and any `pr-str`-equality assertions), listing
   every changed assertion with its justification in the report.
8. Run: the propagated namespace, `cv2_emitted_tree_enrichment_test`,
   `cc6_cv2_claim_enrichment_test`, `cc6_declared_evidence_basis_test`,
   `el4_harvest_test`, `cc3_assembly_properties_test`, `cc15_reranker_enrichment_contract_test`,
   then `clojure -J-Djava.awt.headless=true -M:poly test brick:ontology` and the changed
   orc-service namespaces; `allium check specs` / `allium analyse specs` must stay at
   115 info / 35 warning / 0 error / 0 findings; `git diff --check`.

Focused runs must use the local Grain pins (the root `:dev` alias is gitlib-pinned):
`/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <logname> <ns> …`
writes `<logname>.log` next to itself. Run ONE JVM test process at a time.

## Spec excerpts (verbatim)

`specs/ontology.allium`:

```
    @invariant WorkedPatternsAreProvenNotMerelyRecent
        -- The pattern recorded for a class is one a campaign SUCCEEDED with. A
        -- shape that failed is recorded as having failed and never displaces one
        -- that worked, and a later attempt does not overwrite an earlier proven
        -- one merely by being later. A class that genuinely succeeds with more
        -- than one shape keeps each of them, so what is offered as proven is
        -- distinguishable from what was merely most recent.

    @invariant OfferedPatternsAreUsable
        -- A pattern offered to a model as proven can actually be used: the code
        -- within it is present rather than elided, and what it reads and writes
        -- is declared, so binding it to a new task is a mechanical step rather
        -- than a reconstruction. A behavior remains evidence a model reasons
        -- with and never a pipeline it is compelled down.
```

`specs/orc-service.allium`:

```
entity CampaignIteration {
    ...
    generated_code_recorded: Boolean
    emitted_tree_recorded: Boolean
    emitted_shape: String?
    ...
}

invariant RecordedTreesCarryTheirShape {
    for iteration in CampaignIterations:
        iteration.emitted_tree_recorded implies iteration.emitted_shape != null
}
```

## Allium obligation reconciliation

`allium plan specs/orc-service.allium` names the two issue obligations:
`entity-optional.CampaignIteration.emitted_shape`,
`invariant.RecordedTreesCarryTheirShape`. As with RR-18, the machine-generated
obligations are structural; the named ontology invariant
`WorkedPatternsAreProvenNotMerelyRecent` is the acceptance constraint applied when
propagating the public integration proof. Preserve the coverage line exactly:
`2 obligations, 2 covered, 0 uncovered` (both covered at the iteration-record schema
seam, already green); the propagated behavioural bridge is the acceptance constraint and
must end GREEN. Report every generated mock, stub, TODO or skeleton.

## Do NOT touch

- `specs/*.allium` — report divergences with a classification; the orchestrator tends.
- RR-21 (coherence ratio / `distinct-tree-shapes` / `ReportSuccessfulShapeCoherence`),
  RR-22 (declared key bindings), RR-23 (tag-scoping). If you need a shape-per-success
  count, expose the DATA (which fingerprint won which successful campaign); do not
  build the ratio or the report.
- The RR-19 occurrence command/processor semantics; read them, do not change them.
- The local Grain checkout `/private/tmp/orc-rr8-grain-recover3`, dependency pins, the
  root `deps.edn` `:dev` alias.
- Other worktrees, especially `/Users/darylroberts/Desktop/Code/orc`.
- Existing generated tests: never weaken, delete, skip, narrow or over-mock them.
  Assertions in cv2/cc6 seams that encode last-emit-wins may change ONLY with each
  change listed and justified in the report.

## Live QA owned by the orchestrator

Independent re-run of the public fail-then-repair proof against SQLite, a forced
identical-shape re-emit race, an adversarial "failed shape only" class (selector must
return nil, harvest must not mint), the ontology brick in both projects, the affected
orc-service namespaces, allium check/analyse against `specs/COVERAGE.md`, weed check
mode, and the obligation audit, before ticking any checklist item.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions.
  Reproduce -> minimize -> fix the actual cause. Don't blame the network or the model — the cause is in the code or
  the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can
  fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red -> green -> refactor, one test at a time.** Vertical tracer-bullet slices, never
  horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive
  refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, network, storage)
  as capabilities that **default to the real impl and are faked in tests**.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing. Then
  turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's
  "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

Standing rules for this arc:

- **Never weaken a generated test to make it pass.** A `/propagate`-generated test is contract. If it is wrong, the
  spec is wrong: report it, the orchestrator `/tend`s and re-propagates.
- **A generated test green before you implement is a finding**, not success — already-covered or vacuous. Report it.

## Report back

- RED command/result before each production change and GREEN command/result after it.
- Files changed and exact public boundaries exercised; every changed pre-existing
  assertion with its justification.
- `2 obligations, 2 covered, 0 uncovered` (schema seam) plus the propagated namespace's final
  `6 tests / 23 assertions` result.
- Every generated mock, stub, TODO or skeleton.
- Every divergence classification (spec bug / code bug / aspirational / intentional gap)
  and anything not verified.
- Any `evidence-basis` enum change, with every site it touched.
