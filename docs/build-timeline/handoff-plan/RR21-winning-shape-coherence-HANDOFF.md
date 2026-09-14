# RR-21 implementation handoff: convergence over winning shapes, reported before it gates

## Goal

Replace the never-witnessed coherence measure (distinct fingerprints across EVERY
tree a class ever emitted, over a denominator of campaigns) with the ratified one:
distinct successful terminal shapes divided by successful campaigns, one winning
shape per successful campaign. Report it durably on every verdict occurrence,
distinguish "did not qualify" from "not yet measurable", and stop it from
blocking promotion until its real distribution has been observed.

## Read first

1. `AGENTS.md`, `docs/ORC-PRINCIPLES.md`.
2. `docs/issues/rr-durable/RR-21-convergence-measured-over-winning-shapes-reported-before-it-.md`.
3. Dossier G11 (and G10, G4) in `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`.
4. `CONTEXT.md`: Shape, Winning shape, Occurrence, Recurrence, Cancelled/Abandoned campaign.
5. Spec excerpts below (verbatim) and the full `PromoteWellScoredClass` /
   `ReportSuccessfulShapeCoherence` rules in `specs/ontology.allium` (~1070–1141) and
   the harvest config block (~433–446).
6. RR-19 and RR-20 landed mechanisms:
   `docs/build-timeline/handoff-plan/RR19-outcome-recurrence-HANDOFF.md`,
   `docs/build-timeline/handoff-plan/RR20-worked-pattern-outcome-shape-HANDOFF.md`.

## Verified mechanism map (read from the landed code)

- **Campaign verdict** (RR-19): `:ontology/tree-class-occurrence-recorded`
  `{:source-sheet-id :source-tick-id :source-node-id :assigned-tree-id :verdict …}`,
  exactly one per classified researcher campaign that reached `:success | :failure |
  :timeout`. Cancelled and abandoned campaigns have none.
- **Per-execution shape** (RR-20): each Phase-2 tree execution emits one
  `:sheet/rlm-tree-execution-completed` bookend with `:status`, `:tree-fingerprint`
  (the SHAPE), `:generated-tree-source`, `:source-sheet-id`, `:source-tick-id`. A
  campaign with N repair rounds emits N bookends in durable order. RR-20's
  `todo-processors/success-bookend-fingerprints` already joins an occurrence's success
  bookends by `[source-sheet-id source-tick-id]` (materialise reads with `(into [] …)`;
  the store returns a reducible).
- **Winning shape** = the fingerprint of the LAST `:status :success` bookend, in durable
  event order, among the bookends of a campaign whose verdict is `:success`. Shapes that
  campaign abandoned earlier (failed bookends, or earlier success bookends superseded by
  a later one) are process evidence, never winning shapes. A successful campaign with no
  success bookend carrying a fingerprint contributes to `successful_campaigns` but not
  to `successful_shape_observations`.
- **Today's measure (the defect)**: `harvest.clj` `distinct-tree-shapes` (~362) counts
  distinct fingerprints across ALL bookends joined to the class's verdict occurrences;
  `coherent-enough?` (~239) divides it by `occurrences`; `harvest-candidate?` (~250)
  still REQUIRES `coherent-enough?` (the gate blocks on it, contrary to the spec's
  report-only rollout); `harvest-gate-report` (~284–336) reports `:coherence` with
  `:abstained | :qualified | :rejected` and only `u/log`s it from `maybe-harvest!`
  (~560–600). No durable report exists. `consolidator.clj`
  `tree-class-aggregate-metrics` (~982–1028) keeps its own descriptive
  `:distinct-tree-shapes` for reflection — leave that alone.
- **Trigger**: `harvest.clj` processor `on-tree-class-check-harvest` fires on
  `:ontology/tree-class-occurrence-recorded` and calls `maybe-harvest!`, which only does
  work at or above `:min-occurrences`. The spec's report rule has NO threshold: every
  verdict occurrence yields one report.
- **Config**: `harvest/default-harvest-config` `:max-shapes-ratio 0.5` = spec
  `maximum_shape_ratio`.
- **Seams / prior art**: `components/ontology/test/ai/obney/orc/ontology/el4_harvest_test.clj`
  (`slice2-gate-*`, `hp2-distinct-tree-shapes-production-faithful`,
  `cc24b-coherence-abstention-*`, `cc24b-shapeless-class-logs-the-abstention`) and the
  RR-19/RR-20 namespaces for occurrence + bookend fixtures.

## Exact behavioral change

1. Add `harvest/winning-shape-coherence` `[ctx class-id]` → a map
   `{:successful-campaigns n :successful-shape-observations n
   :distinct-successful-shapes n :ratio (or a ratio/number, nil when not measurable)
   :status :qualified | :rejected | :not-measurable}` computed from the class's
   `:success` verdict occurrences joined (by `[source-sheet-id source-tick-id]`) to their
   bookends, winning shape as defined above; measurable iff
   `successful-campaigns > 0` and `successful-shape-observations > 0`; qualified iff
   `ratio <= maximum-shape-ratio`.
2. `harvest-gate-report` takes that map as `:winning-shape-coherence` in its metrics
   and reports `:coherence {:verdict :qualified|:rejected|:not-measurable :ratio …
   :successful-campaigns … :successful-shape-observations …
   :distinct-successful-shapes … :max-shapes-ratio …}`. The old `:abstained` and
   `:distinct-tree-shapes` keys go; `distinct-tree-shapes` (the all-trees count) is
   retired from harvest (keep the consolidator's descriptive aggregate).
3. `harvest-candidate?` no longer includes the coherence clause (report-only rollout,
   spec `PromoteWellScoredClass` has no coherence `requires`). `maybe-harvest!` feeds
   the new measure into the report and log.
4. Durable report: new closed schemas — command `:ontology/report-shape-coherence` and
   event `:ontology/shape-coherence-reported` carrying `:tree-class
   :verdict-occurrences :successful-campaigns :successful-shape-observations
   :distinct-successful-shapes :ratio (maybe number) :status :maximum-shape-ratio
   :recorded-at`, tagged `[:description-target tree-class]`. Emitted by the harvest
   check processor for EVERY `:ontology/tree-class-occurrence-recorded` (no threshold),
   with `:verdict-occurrences` = the class's verdict-qualified occurrence total at that
   point. Idempotent per occurrence: one report per `[tree-class source-sheet-id
   source-tick-id]` (carry the occurrence pair on the event; make the command a no-op
   on replay through an event-store CAS, as RR-19's occurrence command does). No bare
   production appends; no process-local state.
5. The event carries a number that lets the distribution be read back (`:ratio`, and
   the counts); no threshold calibration is done in this slice.

## TDD cycle list (seeded from the orchestrator's `/propagate`; RED confirmed)

Propagated namespace (contract — never weaken, delete, skip, narrow or over-mock it):
`components/ontology/test/ai/obney/orc/ontology/rr21_winning_shape_coherence_test.clj`.
Orchestrator RED run: **6 tests / 37 assertions, 35 failures, 0 errors.**
It resolves `harvest/winning-shape-coherence` at runtime (`measure` helper) so every
test reports on its own until the function exists.

1. RED→GREEN `coherence-counts-one-winning-shape-per-successful-campaign` (5): add
   `winning-shape-coherence` over RR-19 occurrences + RR-20 bookends.
2. RED→GREEN `the-terminal-successful-shape-is-the-winning-shape` (3): last success
   bookend in durable order wins.
3. RED→GREEN `failed-timed-out-cancelled-and-abandoned-campaigns-enter-neither-side` (4).
4. RED→GREEN `a-grab-bag-is-rejected-and-a-shapeless-class-is-not-measurable` (7).
5. RED→GREEN `coherence-is-reported-but-never-gates-promotion` (5): metrics key
   `:winning-shape-coherence`, gate drops the clause, report verdicts.
6. RED→GREEN `the-observed-distribution-is-recorded-durably-on-every-verdict-occurrence`
   (13): schemas, command with CAS, processor emission on every occurrence.
7. Update `el4_harvest_test.clj` where assertions encode the retired measure
   (`slice2-gate-fails-grab-bag`, the `cc24b-coherence-*` trio,
   `hp2-distinct-tree-shapes-production-faithful`, any `:distinct-tree-shapes` metrics
   fixtures): each changed assertion listed and justified in your report; the
   abstention tests become "not-measurable is reported and never gates" tests.
8. Run: the propagated namespace, `el4-harvest-test`, `rr19-outcome-recurrence-test`,
   `rr20-*` namespaces (all five), `consolidator-test`, `consolidation-trigger-test`,
   then `clojure -J-Djava.awt.headless=true -M:poly test project:orc brick:ontology` and
   `… project:orc-ontology brick:ontology` (sequential, solo), allium check/analyse at
   115 info / 35 warning / 0 error / 0 findings, `git diff --check`, orphan-JVM check.

Focused runs must use the local Grain pins:
`/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <logname> <ns> …`.
One JVM test process at a time; wait for each in the foreground with a long timeout.

## Spec excerpts (verbatim)

```
    -- Coherence: a tight cluster, not a grab-bag. A winning shape is the terminal
    -- shape that carried a successful campaign to success. The measured ratio is
    -- distinct successful terminal shapes divided by successful campaigns.
    -- Failed and timed-out campaigns remain recurrence, quality and weakness
    -- evidence, but enter neither side of this ratio and therefore cannot make a
    -- failure-heavy class look more coherent. Shapes abandoned during repair are
    -- process evidence, not winning shapes.
    -- ADR 0029 (TRACKED GAP, measured): this axis has never been WITNESSED —
    -- ... Until its real
    -- distribution has been observed it is computed and REPORTED without
    -- blocking promotion, because a threshold that has never fired cannot be
    -- distinguished from one that never will: harvesting nothing looks
    -- identical to nothing qualifying.

rule ReportSuccessfulShapeCoherence {
    when: TreeClassOccurrenceRecorded(tree_class, occurrences)
    let successful_campaigns = successful_campaign_count(tree_class)
    let successful_shapes = successful_terminal_shape_count(tree_class)
    let distinct_shapes = distinct_successful_terminal_shape_count(tree_class)
    let measurable = successful_campaigns > 0 and successful_shapes > 0
    let ratio = if measurable: distinct_shapes / successful_campaigns else: null
    let report_status = shape_coherence_report_status(
        successful_campaigns,
        successful_shapes,
        distinct_shapes,
        maximum_shape_ratio
    )

    ensures: ShapeCoherenceReported(
        tree_class: tree_class,
        verdict_occurrences: occurrences,
        successful_campaigns: successful_campaigns,
        successful_shape_observations: successful_shapes,
        distinct_successful_shapes: distinct_shapes,
        ratio: ratio,
        status: report_status,
        recorded_at: now
    )
}

    maximum_shape_ratio: Decimal = 0.5
```

## Allium obligation reconciliation

`allium plan specs/ontology.allium` names `rule-success.ReportSuccessfulShapeCoherence`;
`allium plan specs/orc-service.allium` names `entity-optional.CampaignIteration.emitted_shape`
(already covered by RR-20's `campaign-iteration-emitted-shape-is-optional-and-recorded-trees-carry-it`
— a finding, not new work). Preserve the coverage line exactly:
`2 obligations, 2 covered, 0 uncovered`, with the propagated namespace's final numbers,
and report every generated mock, stub, TODO or skeleton.

## Do NOT touch

- `specs/*.allium`; report divergences with a classification, the orchestrator tends.
- Threshold calibration or turning the coherence clause back into a blocker (a later,
  data-driven slice). RR-22 (key bindings, R-Inject truncation), RR-23 (tag scoping).
- RR-19's occurrence command/processor and RR-20's shape-claim writers; read them, do not
  change them.
- The local Grain checkout, dependency pins, the root `deps.edn` `:dev` alias, other
  worktrees, and the primary checkout `/Users/darylroberts/Desktop/Code/orc`.
- Existing generated tests: never weaken, delete, skip, narrow or over-mock them.

## Live QA owned by the orchestrator

Independent re-run of the propagated proof, an adversarial probe over the winning-shape
join (repair chains, superseded successes, cancelled/abandoned campaigns with success
bookends, a class with only failed verdicts), the report's idempotency under
re-delivery, the full ontology graphs and the `orc-service` brick, allium against
`specs/COVERAGE.md`, weed check mode, and the obligation audit.

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
- `2 obligations, 2 covered, 0 uncovered` plus the propagated namespace's final numbers.
- Every generated mock, stub, TODO or skeleton; every divergence classification
  (spec bug / code bug / aspirational / intentional gap); anything not verified.
