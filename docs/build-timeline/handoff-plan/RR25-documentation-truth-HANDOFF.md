# RR-25 implementation handoff: documentation truth pass

## Goal

Every statement in code comments, docs and specs about the RR-durable arc's mechanisms
must be true after RR-7 through RR-24. Three false statements named in the issue are
corrected, ADR 0004 is referenced where the false claim lived, and the drift the arc
itself accumulated (counters, coherence, patterns, checkpointing default, live stream)
is brought into line — verified by review and by a durable grep test proving the false
phrasings are gone.

## Read first

1. `AGENTS.md` ("Pure implementation refactors, build maintenance, and documentation edits …
   still require checking whether the work exposes drift in an existing spec").
2. `docs/issues/rr-durable/RR-25-documentation-truth-pass.md` (five acceptance criteria).
3. `docs/adr/0004-campaign-effects-are-at-least-once-and-attributable.md` and dossier R1/R4
   (`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`): no LLM
   provider honours an idempotency key; the vendor SDK plumbing is dead; exactly-once
   exists only with callee participation.
4. `CONTEXT.md` (Recursive researcher section) — the ubiquitous language every corrected
   sentence must use verbatim.
5. The RR-19…RR-24 issue Verification sections (`docs/issues/rr-durable/RR-19-*.md` …
   `RR-24-*.md`) — the landed behaviour each doc must now describe.

## Verified statements to correct (located by the orchestrator; confirm each before editing)

The issue's three:
1. **Idempotency key described as provider deduplication.** The key is real at OUR
   boundary — it lets a resumed campaign recognise an already-dispatched call and lets a
   checkpoint-safe tool deduplicate its own effect (callee participation). Sites that
   pass it toward a provider as if the provider deduplicated:
   `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` ~3200
   (`:orc/idempotency-key action-id` on the researcher provider call) and ~3792
   (`(assoc :orc/idempotency-key provider-action-id)`), plus any comment/docstring around
   them and in `researcher_effects.clj` ~85–92 ("local idempotency metadata"). Docs:
   `docs/RLM-GUIDE.md` ~258–268 (tool boundary — keep, it IS callee participation; make
   the provider sentence honest), `docs/ORC-SERVICE-GUIDE.md`, `docs/ARCHITECTURE.md`,
   `docs/EVENT-STORE-PATTERNS.md` where the provider call is described. Search
   `grep -rn -i "idempot" components/*/src docs/*.md specs/*.allium` and classify every
   hit: keep (our boundary / tool contract) or reword (provider dedup). Reference ADR 0004
   at each reworded site.
2. **Harvest documented as unshipped.** `docs/SELF-IMPROVING-LOOP.md` ~519 ("is pulled
   when there is volume to harvest, and is not yet shipped on this branch") — harvest is
   live (EL-4, ADR 0015) and, since RR-19/RR-21, gated on verdict occurrences with
   coherence report-only.
3. **Per-emit cadence claim.** `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`
   ~3081 ("Emit :rlm/tree-generated event when tree is generated") and any docstring on
   `:rlm/tree-generated` (`interface/schemas.clj` ~1890–1912) — it fires once per
   campaign, carrying the last tree (docs/RLM-GUIDE.md ~1018 and docs/STREAMING.md ~295
   already say so; make code comments agree).

Arc drift (each site names the pre-arc behaviour):
4. `components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj` ~503:
   "the SAME counter the consolidator reads, ticked on every :ontology/task-classified" →
   ticked on every verdict occurrence (`:ontology/tree-class-occurrence-recorded`, RR-19).
5. `components/ontology/src/ai/obney/orc/ontology/core/read_models.clj` ~2267: "which
   the :ontology/task-classified reducer populates UNCONDITIONALLY" — check which map the
   sentence refers to (`:sheet->class` / `:occurrence->class` are still classification-fed;
   `:class->recent-occurrences` is verdict-fed since RR-19) and make it exact.
6. `docs/SELF-IMPROVING-LOOP.md`: any description of the tree-class counter, threshold,
   harvest gate ("recurrence" is counted at verdict), coherence ("winning shapes",
   report-only), the worked pattern (per shape, outcome-keyed, verdict-corroborated,
   offered whole with key bindings), and classification cadence (once per campaign,
   RR-18).
7. `docs/RLM-GUIDE.md`: checkpointing default (RR-15: recursive researchers checkpoint by
   default; `:checkpointed? false` is the opt-out — verify ~248 already says so), the
   live stream as a projection of the durable record (RR-16), trace/judge iteration
   evidence (RR-17), mints carrying iteration provenance (RR-24), patterns whole with
   bindings (RR-22).
8. `docs/STREAMING.md`: RR-16 projection statement (verify ~295 region).
9. `docs/ORC-SERVICE-GUIDE.md`: recovery (`resume-in-progress!`, RR-8), drain/bounded
   operations (RR-9), and the campaign vocabulary.
10. `docs/ONTOLOGY.md` / `docs/LIVING-DESCRIPTIONS.md`: harvest, coherence, worked
    pattern, evidence bases (`:emitted-artifact-outcome`, `:campaign-verdict`).

Do NOT touch `specs/*.allium` (report any spec sentence you believe is stale as a
divergence; the orchestrator tends). Do not change behaviour: comments, docstrings and
markdown only. Keep wording date-free and story-ordered (arc conventions).

## Contract test (orchestrator-supplied; RED confirmed)

`components/orc-service/test/ai/obney/orc/orc_service/rr25_documentation_truth_test.clj` —
**4 tests / 15 assertions, 12 failures, 0 errors.** Already green before implementation (findings): the provider-deduplication phrasing grep finds NO live hit today — criterion 1 is satisfied by making the TRUE statement present (the executor's provider-call sites must state the key's real purpose and reference ADR 0004, both RED), and by classifying every remaining `idempot` hit in your report; the RLM guide's checkpointing-default sentence and the STREAMING/RLM-GUIDE cadence sentences are already correct. RED for real: the harvest "not yet shipped" sentence and the missing verdict-occurrence description in `docs/SELF-IMPROVING-LOOP.md`; the per-emit cadence comment at `todo_processors.clj` ~3081 and the missing once-per-campaign statement; the classifier docstring's "ticked on every :ontology/task-classified"; the winning-shape / report-only description and the whole-pattern-with-bindings description in the docs. The test exempts `docs/build-timeline/`, `docs/issues/`, `docs/prd/`, `docs/adr/` and `docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md` (historical and obligation records).. It greps the repo (src + docs, excluding `docs/build-timeline/`,
`docs/issues/`, `docs/prd/`, `docs/adr/` which are historical records) for the false
phrasings and asserts they are gone, and asserts ADR 0004 is referenced from the
reworded provider-key sites.

## TDD cycle list

1. RED→GREEN each grep assertion, one statement at a time, with the doc/comment edit that
   makes it true (not merely absent — rewrite the sentence to the landed behaviour).
2. Review pass over the arc-drift list (items 4–10): for each doc, read it end to end
   against the RR-19…RR-24 Verification sections; list every sentence changed.
3. Run: the contract namespace, `format-rlm-principles-test`, `description-events-test`
   (docs embedded in seeds), the two solo ontology graphs are NOT required for a docs-only
   slice unless a docstring edit touched a `.clj` that tests compile — run
   `run-focused.sh` over `rr19…rr24` namespaces plus `task-classifier`/`read-models`
   consumers to prove the comment-only edits compile; allium at baseline; `git diff --check`.

## Report back

Every sentence changed (file, before, after, one line each); every `idempot` hit and its
classification; any spec sentence you believe stale (divergence report); anything not
verified. `0 obligations, 0 covered, 0 uncovered`.
