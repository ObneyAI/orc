# RS-5 handoff — The chain reaches retrieval and harvest

Issue: `docs/issues/r-inject-specialisation/RS-5-the-chain-reaches-retrieval-and-harvest.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-r-inject-specialisation` (branch `feature/r-inject-specialisation`).
Runner: a `clojure -M:dev:test -e` run from THIS worktree (see RS-2's brief for the exact form), one JVM at a time,
0 orphans after every run; never kill a JVM you did not start. Do not run `poly test` or any whole-brick build. Do not
commit, push or stash. Never edit `specs/*.allium`. RS-4 is being implemented by another agent on the SAME worktree:
do not touch `apply-r05-classifier-context` or anything under its render section, and do not touch the wedge's
`:context` payload tail.

## Goal

Prove, on synthesised durable events, that a minted domain child is a first-class tree-class for everything
downstream of classification — the retrieval index feed, the retrieval gate's recurrence band, walk-down from its
parent, and harvest into a behavioral child — WITHOUT changing harvest's gate, the consolidator's policy or the
retrieval gate. Where a reader on that path drops the child or mislabels it, fix the reader in this slice and report
the fix as a finding. Two such gaps are already known (below); find any others.

## Read first

- `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md` D1, D3, D6, D7.
- `docs/prd/r-inject-specialisation.md` — Seam 4; `docs/issues/rr-durable/README.md` Seam-4 definition.
- `components/ontology/src/ai/obney/orc/ontology/core/commands.clj` `mint-domain-child` (RS-3): the child's concept
  carries the judged label as its `:label`; NO description event is written at birth. The child's only body is the one
  the descriptions read-model ASSEMBLES under granularity `:tree-class` from the CV-1 `:representative-use` claim the
  wedge records (`record-claim-deltas`, `:evidence-event-count 0`).
- `components/ontology/src/ai/obney/orc/ontology/core/read_models.clj` — `descriptions*` for
  `:ontology/claim-deltas-recorded`, `assemble-body`, `assemble-summary` (the summary IS the retrieval document
  content), `get-consolidation-total`, the reindex counter (`events-since-last-rebuild`: a claim event ticks it; a
  `:colbert/index-created` for `"ontology-descriptions"` resets it).
- `components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj` — `collect-current-descriptions`,
  `effective-granularity`, `build-document-collection` (document id `"<granularity>:<pr-str target>"`, metadata
  `{:granularity :target-id :confidence :last-update}`; `:confidence` is the strengths' average, so a newborn child's is
  0.0), `should-rebuild?` (threshold 10 events / timer / cold start), `force-rebuild!` (wired ONLY to the behavioral
  mint), `on-description-updated-maybe-reindex`.
- `components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj` — `gate-candidates` (`tree-class-candidate?`,
  band: total 0 passes, 1–2 filtered, ≥ retrieval-gate 3 passes), `get-tree-class-children` (RS-3: `:tree-class` scope
  first), `pick-best-child` (**gap A: hardcodes `:granularity :tree-fingerprint` on the synthetic child candidate**, so
  a walk-down-selected domain child is invisible to every `:tree-class` consumer downstream — the gate, RS-2's
  `maybe-assign-domain-child`, the render's scope choice).
- `components/ontology/src/ai/obney/orc/ontology/core/harvest.clj` — `default-harvest-config`, `harvest-candidate?`,
  `harvest-gate-report`, `class-occurrences-through`, `occurrence-scores`, `winning-shape-coherence`,
  `latest-classified-behavior-id` + `nearest-abstract-behavior` (needs a classified event ON THE CHILD carrying
  `:behavioral-subtrees` — the wedge attaches them at the child identity when behaviors were classified),
  `already-harvested?`, `harvest-body` (**gap B: transplants only the assembled body; the domain label lives on the
  concept, so the harvested behavioral child carries no label**), `harvest-name`, `mint-harvested!`, `maybe-harvest!`,
  the processor `on-tree-class-check-harvest`.
- `components/ontology/src/ai/obney/orc/ontology/interface/schemas.clj` — `description-body` (L84–130) and
  `:ontology/mint-behavioral-subtree`.
- Tests (prior art, reuse their helpers verbatim): `components/ontology/test/ai/obney/orc/ontology/el4_harvest_test.clj`
  (`occurrence!`, `verdict-occurrence!`, `judge-score!`, `seed-parent-behavior!`, `setup-good-class!`,
  `minted-harvest-events`, `with-harvest-processor-ctx`; `slice3-harvests-the-good-class`,
  `slice3-processor-drives-harvest-end-to-end`), `rr21_winning_shape_coherence_test.clj` (`campaign!`, `verdict!`,
  the LMDB `:cache` note — without it `:sheet/complete-node-execution` silently records no occurrence),
  `el1b_convergence_capture_test.clj` (`gate-passes-curated-seed-tree-class-at-total-zero`,
  `gate-surfaces-recurring-tree-class-once-it-crosses`), `rs3_domain_child_birth_test.clj` (`mint-command`,
  `dispatch!`, `claim-command`), `walk_down_classifier_test.clj`, `seeds_test.clj`, `cc3_body_assembly_test.clj`,
  `consolidator_test.clj`.

## The exact change

**No new production path; two reader fixes, one carried field.**

1. **Walk-down's synthetic child candidate carries the scope it was read under** (gap A). In `pick-best-child`, the
   candidate's `:document-metadata :granularity` is `:tree-class` when the child's description was found under
   `:tree-class` scope and `:tree-fingerprint` when found under the seeded scope (`get-tree-class-children` returns which;
   extend its map with `:scope`). Seeded children (walk-down suite) are byte-identical.
2. **The harvested body carries the domain label** (gap B). `description-body` gains an optional `:domain-label :string`
   (omit-when-absent, so every existing body is byte-shaped). `harvest-body` reads the class's tree-class concept
   (`get-concept-by-uri` on `"tree-class:<id>"`) and, when its `:label` is a non-blank string other than the id, stamps
   `:domain-label`. `harvest-name`, the gate, the consolidator, `mint-behavioral-subtree`'s identity derivation: untouched.
3. **No forced reindex on a domain mint** — intentional. The child's birth claim ticks the reindex counter like any
   claim write; it becomes searchable at the next rebuild (threshold, timer or cold start). Graph readers (walk-down,
   RS-2's sibling lookup) never need the index. State this in your report as the documented behaviour; do not add a
   force-rebuild.

Anything ELSE you find that drops or mislabels the child on this path: fix it, red-first, and report it as a finding
with the reader's name.

## TDD cycle list

Seam 4 throughout: real in-memory store, real commands, real read-models, the LLM stubbed as `el4_harvest_test`'s
`stub-predict-fixture` does; NO ColBERT (assert on the document collection the feed BUILDS, not on a search). New file
`components/ontology/test/ai/obney/orc/ontology/rs5_domain_child_chain_test.clj`.

1. **Index feed.** Mint a domain child (RS-3's `mint-command` + `dispatch!`), record the CV-1 birth claim
   (`claim-command`, a `:representative-use` whose content is a signature string the test chooses) →
   `(#'ont-tp/collect-current-descriptions ctx)` contains an entry `{:granularity :tree-class :target-id child}` whose
   body `:summary` contains the injected signature and whose `:consolidated-from-event-count` is 0;
   `(#'ont-tp/build-document-collection …)` yields a document id `":tree-class:<child>"`-shaped entry with metadata
   `:granularity :tree-class`, `:target-id child`, `:confidence 0.0`. Expected GREEN on first write — report it as a
   finding (the feed already reads assembled bodies), not as a cycle.
2. **Retrieval-gate band on the child.** Through `classify-task` with `search-descriptions` stubbed to return the child
   as a `:tree-class` candidate above threshold (el1b's `tree-class-candidate`) and the REAL `get-consolidation-total*`:
   with 0 synthesised occurrences the child is the match; after 2 `verdict-occurrence!` events on the child it is
   filtered (deferral or fallback, whichever the suite's prior art asserts); after 3 it is the match again. Expected
   GREEN on first write for a seeded class — the point is the CHILD identity flows through the same band; report.
3. **RED — walk-down candidate scope.** A parent with ONE narrower concept, the minted domain child, described only under
   `:tree-class` scope → `(#'tc/pick-best-child …)` (or the walk-down path of `classify-task` with the parent as top-1 and
   `rerank!` stubbed to prefer the child) yields a candidate whose `:document-metadata :granularity` is `:tree-class` and
   `:target-id` is the child. RED (hardcoded `:tree-fingerprint`). GREEN by change 1. Guard: `walk-down-classifier-test`
   green unchanged (seeded children still `:tree-fingerprint`).
4. **RED — harvest promotes the child with its label.** Mirror `slice3-harvests-the-good-class` exactly, replacing
   `record-tree-class-desc!` with a MINTED domain child (label e.g. `"marathon-training-plan"`) plus its birth claim;
   `seed-parent-behavior!`; twelve `occurrence!` on the CHILD identity with the first carrying `:behavioral-subtrees`
   naming the parent behavior; quality scores above the floor → `maybe-harvest!` mints ONE behavioral subtree whose
   `:parent-behavior` is the seeded parent, whose body `:representative-uses` contains the injected signature, and whose
   body `:domain-label` equals the label. RED on `:domain-label` (absent). GREEN by change 2. Then the processor-driven
   variant (`with-harvest-processor-ctx`, as `slice3-processor-drives-harvest-end-to-end`) mints once.
5. **Guard — nothing gated moved.** `harvest-candidate?`, `harvest-gate-report`, `default-harvest-config`,
   `gate-candidates`, `get-consolidation-total*` and everything in `consolidator.clj` are byte-identical (`git diff`
   on those forms is empty — paste the empty diff). Regression: `el4-harvest-test`, `rr21-winning-shape-coherence-test`,
   `rr19-outcome-recurrence-test`, `rr20-occurrence-corroboration-test`, `seeds-test`, `walk-down-classifier-test`,
   `tree-class-hierarchy-test`, `cc3-body-assembly-test`, `cc3-assembly-properties-test`, `consolidator-test`,
   `el1b-convergence-capture-test`, `rs2-domain-child-classifier-test`, `rs3-domain-child-birth-test`.

Propagate note (orchestrator): no new obligation is owned here; the harvest rules' and `PromoteWellScoredClass`'s
existing obligations must stay covered — report their suites' RESULT lines and the line
`0 obligations, 0 covered, 0 uncovered` for new ones.

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
- **No regex or phrase matching over model-authored prose — in tests or in production.** Assert on structured data the
  runtime emits: typed reranker fields, event bodies, identities, concept-graph edges, rendered values the test itself
  injected. The reranker is stubbed with a typed payload the test constructs; only the live sweep hears the real model.
- **Fitness keeps meaning shape-and-intent fit.** Coverage is a separate discrete verdict; nothing reads a domain
  judgement off the fitness number or off any similarity threshold.


## Do NOT touch

- `specs/*.allium`; the reranker (RS-1); RS-2's branch logic; RS-3's mint command and the wedge; RS-4's render and the
  `:context` payload (another agent owns them on this worktree); harvest's gate (`harvest-candidate?`,
  `harvest-gate-report`, `default-harvest-config`), `maybe-harvest!`'s pre-gate, the consolidator, `gate-candidates`
  and the retrieval-gate constant; the reindex trigger (`should-rebuild?`, `force-rebuild!` wiring); the description
  body slot (claims only).

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim (and, for cycles 1–2, the green-on-first-write
finding stated as such); the document-collection entry from cycle 1 and the minted behavioral subtree's body from
cycle 4 as data; the empty `git diff` from cycle 5; every additional reader gap you found and fixed, by function name;
anything in `docs/*.md` describing harvest, retrieval or walk-down now stale (list, do not edit — the orchestrator
already knows `el4_harvest_test`'s docstring names the wrong topics); what you could NOT verify; the orphan-JVM check.
