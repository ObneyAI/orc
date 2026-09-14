# RR-23 implementation handoff: tag-scope the hot evidence queries

## Goal

Stop the self-learning loop's hottest reads from scanning every event of a type for the
tenant. Scope each by the tag it already carries; give the tree bookend the one tag it
lacks (the campaign that produced it) at its emit site; prove results identical to the
unscoped versions and prove the cost no longer grows with unrelated store size.

## Read first

1. `AGENTS.md`, `docs/ORC-PRINCIPLES.md`.
2. `docs/issues/rr-durable/RR-23-tag-scope-the-hot-evidence-queries.md`.
3. Dossier G19 in `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`.
4. The local Grain event store: `/private/tmp/orc-rr8-grain-recover3/components/event-store-v3/src/ai/obney/grain/event_store_v3/core/in_memory.clj`
   (tags match as a SUBSET — multiple tags are AND) and
   `/private/tmp/orc-rr8-grain-recover3/components/event-store-sqlite-v3/src/ai/obney/grain/event_store_sqlite_v3/core.clj`
   (~196–232: a tagged read drives from the `event_tags` primary key `(tenant_id, tag,
   event_id)` and probes events per matching tag row — tag scoping is a real cost
   reduction on SQLite; an untagged typed read scans the tenant's stream by type).
   `es/read` also accepts `:as-of` (upper bound by event id) and `:after`.
5. RR-19/RR-20/RR-21 handoffs for the consumers being scoped.

## Verified mechanism map (read from the landed code)

Tags each producer already writes (verified):
- `:ontology/task-classified` → `#{[:tick source-tick-id] [:description-target assigned-tree-id]}`.
- `:ontology/tree-class-occurrence-recorded` (RR-19) → `#{[:tick source-tick-id] [:node source-node-id] [:description-target assigned-tree-id]}`.
- `:ontology/shape-coherence-reported` (RR-21) → `#{[:tick source-tick-id] [:description-target tree-class]}`.
- `:ontology/behavioral-subtree-minted` → `[:behavioral-subtree-minted target-id]` + `[:harvested-tree-class class-id]`.
- `:judge/score-emitted` → `#{[:sheet …] [:node …] [:tick …]}`; `:judge/composite-score-computed` (check its tags in `evaluation/core/commands.clj`).
- `:sheet/rlm-tree-execution-completed` (bookend) → `#{[:sheet sheet-id] [:tick tick-id]}` — the EPHEMERAL Phase-2 sheet/tick; the campaign's `:source-sheet-id`/`:source-tick-id` are body fields only, so a bookend cannot be found by the campaign that produced it. Emit site: `components/orc-service/src/ai/obney/orc/orc_service/core/commands.clj` `record-rlm-tree-execution-completion` (~2008).
- `:rlm/researcher-iteration-recorded` → check its tags (`orc-service/core/commands.clj` ~1272–1389); the consolidator joins it by `[sheet-id tick-id node-id]`.

Untagged type-only scans on the hot paths (verified by grep; line numbers approximate):
- `components/ontology/src/ai/obney/orc/ontology/core/harvest.clj`: `class-occurrences-through` (~371, occurrences by class → scope by `[:description-target class-id]`), `winning-shape-coherence` bookends (~450, needs the new bookend tag), `occurrence-scores` occurrences (~488) and judge scores (~495, scope per occurrence by `[:tick tick]`), `latest-classified-behavior-id` (~514, task-classified by class → `[:description-target class-id]`), `already-harvested?` (~546–556, minted by class → `[:harvested-tree-class class-id]`).
- `components/ontology/src/ai/obney/orc/ontology/core/consolidator.clj`: the reflection gather (~841–880: task-classifieds, tree executions, judge scores, iteration records), `gather-recent-*` (~933–940), `tree-class-aggregate-metrics` (~999–1035), and two `:sheet/node-execution-completed` scans (~1894, ~2029).
- `components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj` `success-bookend-fingerprints` (~621, bookends by occurrence → needs the new tag).
- `components/evaluation/src/ai/obney/orc/evaluation/core/commands.clj` `existing-composite-score?` (~65–74, composite scores untagged). NOTE: `existing-judge-score?` (~51–63) is ALREADY tick-scoped — the issue's second criterion is partly satisfied; record it as already covered and scope the composite check.
- Out of scope (not hot-path evidence): `commands.clj` concept/relationship scans (~420, ~479).

## Exact behavioral change

1. **Bookend tag.** `record-rlm-tree-execution-completion` adds `[:source-tick source-tick-id]`
   when `:source-tick-id` is present (tag values must be UUIDs). Existing tags stay.
   `execute-tree` already passes `:source-tick-id`.
2. **Scope every hot read above by its tag** (occurrence pair or class), keeping the body
   filters that remain necessary (a tag narrows, the filter decides). Where a read is
   per-occurrence inside a loop, read once per occurrence by tag rather than once for the
   whole type. Where a read wants "this class's occurrences", use
   `[:description-target class-id]`. Where it wants "this campaign's bookends", use
   `[:source-tick source-tick-id]` — and for stores written BEFORE this slice (no tag),
   results must still be correct: read the tag-scoped set and, only when a caller opts in
   for legacy replay, fall back to the typed scan; document the choice.
3. **Equivalence proven, not assumed.** For each scoped function, a test builds a store
   with several classes/campaigns/judges and asserts the scoped result equals the
   unscoped computation over the same store (pure comparison against a reference
   implementation kept in the TEST, not in production).
4. **Cost measured at two store sizes.** Inject a counting read seam (the test wraps
   `es/read` to count events materialised per call — an injected capability faked in
   tests, per the disciplines) and show, for the promotion path
   (`maybe-harvest!`), the reflection gather, and the composite duplicate check, that
   events read do not grow when the store gains N unrelated events (a second class's
   campaigns) — same counts at size S and 4S for the scoped functions, and strictly
   growing counts for the reference unscoped versions. Record the numbers in the report.
5. No behaviour change beyond query scoping and the added tag; no read-model rework (G19).

## TDD cycle list (contract namespace supplied by the orchestrator; RED confirmed)

`components/ontology/test/ai/obney/orc/ontology/rr23_tag_scoped_evidence_test.clj` — **5 tests / 14 assertions, 5 failures, 0 errors.** Measured baseline on the unscoped tree (events materialised, store of 12 campaigns vs 30): promotion path 48 → 120, reflection gather 48 → 120, coherence measure 36 → 90 — cost grows with unrelated events, which is the defect. Already green before implementation (findings, kept as guards): the two equivalence tests (`scoped-harvest-reads-equal-their-unscoped-reference`, `scoped-reflection-gather-equals-its-unscoped-reference`) pass today because both sides are unscoped; they must STAY green once the production reads are scoped.

1. RED→GREEN `the-bookend-is-tagged-by-the-campaign-that-produced-it` (orc-service emit).
2. RED→GREEN `scoped-harvest-reads-equal-their-unscoped-reference` (occurrences, scores, winning shapes, harvested?).
3. RED→GREEN `scoped-reflection-gather-equals-its-unscoped-reference`.
4. RED→GREEN `the-composite-duplicate-check-is-scoped-to-its-execution`.
5. RED→GREEN `hot-query-cost-does-not-grow-with-unrelated-store-size` (two sizes, counting seam).
6. Run: the contract namespace, `el4-harvest-test`, `rr19-*`, `rr20-*` (five), `rr21-*`,
   `consolidator-test`, `consolidator-claim-path-test`, `consolidation-trigger-test`,
   `judge-runtime-test`, `deterministic-ontology-e2e-test`, `rr20-public-lifecycle-test`,
   then the two solo ontology graphs; allium at baseline; `git diff --check`; orphan check.

Harness: `run-focused.sh`, one JVM at a time, foreground waits; `es/read` returns a
reducible; never edit `specs/*.allium`.

## Allium obligation reconciliation

The issue names no generated obligation. Report `0 obligations, 0 covered, 0 uncovered`
with the contract namespace's final numbers and the measured event counts.

## Do NOT touch

`specs/*.allium`; RR-24; read-model reworks; the Grain checkout and pins; other
worktrees; existing generated tests.

## Report back

Per-cycle RED/GREEN; every scoped call site (file, function, tag used, legacy fallback
decision); the two-size measurement table; every changed pre-existing assertion with
justification; anything unverified.
