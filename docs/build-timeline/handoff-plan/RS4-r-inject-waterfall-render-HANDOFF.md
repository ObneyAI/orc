# RS-4 handoff — R-Inject renders the waterfall top down

Issue: `docs/issues/r-inject-specialisation/RS-4-r-inject-renders-the-waterfall-top-down.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-r-inject-specialisation` (branch `feature/r-inject-specialisation`).
Runner: a `clojure -M:dev:test -e` run from THIS worktree (see RS-2's brief for the exact form), one JVM at a time,
0 orphans after every run; never kill a JVM you did not start. Do not run `poly test` or any whole-brick build. Do not
commit, push or stash. Never edit `specs/*.allium`.

## Goal

The model is shown the waterfall top down. When the runtime has assigned the task to a domain child that has no
consolidated body, the prepend's structural section renders the PARENT's full entry as the shape the task matched —
exactly as a plain match renders today — followed by one line naming the child the task is assigned to, its label,
and that this campaign's outcome is the child's first evidence; the four-move menu's SPECIALIZE bullet points at that
assignment instead of inviting a structural mint the runtime already made. When the child HAS a consolidated body, the
child renders as the primary entry and the parent drops to one shape-context line. Every other render (plain match,
walk-down's own mint, top-level fresh mint, uncertain, the behavioral section) is byte-identical; the holdout arms and
the injection record are unchanged.

Today a newborn domain child renders WRONG: RS-2 sets `:was-fresh-mint? true` on a domain mint, and both
`structural-display-candidates` and the first branch of `format-structural-section` short-circuit on that flag, so the
model is told "No high-confidence structural match" and the parent's shape is thrown away — the exact failure D5 names.
And the wedge's `:r05-classifier` payload carries none of the domain facts, so the renderer cannot know it happened.

## Read first

- `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md` D1, D5, D7, D7b.
- `docs/prd/r-inject-specialisation.md` — "R-Inject render (D5)" and Seam 5.
- `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`:
  - `maybe-auto-classify-and-set-context` — the tail `(assoc node :context {:tree-id … :r05-classifier {:structural … :behavioral …}})`
    (the WHOLE payload; nothing domain-shaped reaches it today);
  - `apply-r05-classifier-context`, the block template (the four-move menu, SPECIALIZE bullet), `format-structural-section`,
    `structural-display-candidates`, `format-structural-candidate`, `format-seed-body`, `fetch-tree-body`, `->uuid`,
    `injected-candidates`, `record-injection!`.
  - The checkpointed path stamps the whole `:context` onto the outcome command as `:classification-context`, so the payload
    is ALSO the durable `:classification-context` on the classified event (schema: opaque `:map`).
- `components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj` `assign-domain-child` — the result keys:
  `:assigned-via` ∈ `#{:mint-domain-child :land-on-domain-child :mint-sibling-domain-child}`, `:parent-tree-id` (a UUID),
  `:domain-label` (canonical), `:assigned-tree-id` (the child), `:was-fresh-mint?` (true on the two mints, false on a
  landing), `:domain-verdict`, `:domain-children-considered`, `:domain-deferral`. `:top-candidates` is UNTOUCHED by a
  domain outcome — top-1's `:document-metadata :target-id` is still the PARENT (a STRING id; coerce with `->uuid`).
- `components/ontology/src/ai/obney/orc/ontology/core/read_models.clj` — the claim-derived body: `:consolidated-from-event-count`
  is the LAST claim event's `:evidence-event-count`. The birth claim RS-3's wedge records carries 0; a harvest or
  reflection claim carries ≥ 1. So: **a child "has a consolidated body" iff `get-description ctx :tree-class child-id`
  returns a body whose `:consolidated-from-event-count` is ≥ 1.** Nil-ness is NOT the discriminator (the birth claim
  already assembles a body with one representative use).
- Tests: `components/orc-service/test/ai/obney/orc/orc_service/r_inject_classifier_context_test.clj` (builders `mk-node`,
  `mk-structural-candidate`; the `str/includes?`-on-injected-values seam; `wedge-stashes-r05-classifier-payload` for the
  structured payload seam), `cc13_injection_record_test.clj`, `w2p1_claim_holdout_test.clj`,
  `bounded_campaign_operations_test.clj` — the two WHOLE-MAP literals of `:classification-context` (search
  `expected-classification-context`): they must stay green UNCHANGED, which is why the domain facts are added
  omit-when-absent (below).
- `components/orc-service/test/ai/obney/orc/orc_service/rs3_domain_child_durable_test.clj` — `tree-class-candidate` and
  the sync-context pattern, if you need a wedge-through test with a typed domain candidate.

## The exact change

**The payload carries the domain facts, omit-when-absent.** In `maybe-auto-classify-and-set-context`, `:structural`
gains ONE optional key `:domain`, present ONLY when `(:assigned-via result)` is one of the three domain outcomes:
`{:assigned-via <kw> :parent-tree-id <uuid> :child-tree-id <uuid> :domain-label <canonical string>}`. A plain match,
bundle, walk-down, mint, uncertain or deferral payload is byte-identical to today (the bounded-campaign literals prove
it). Because the payload is the durable `:classification-context`, this is an event-body addition — optional, so every
pre-RS-4 event stays valid.

**The renderer branches on `:domain`, before the fresh-mint branch.** In `format-structural-section` (and
`structural-display-candidates`, which the injection record shares): when `(:domain structural)` is present —

- read the child's body: `(fetch-tree-body ctx child-tree-id)`; `consolidated?` = body present and
  `(:consolidated-from-event-count body)` ≥ 1;
- **newborn (not consolidated):** the display candidates are the top candidates as for a plain match (the top one IS the
  parent; do not filter it out on `:was-fresh-mint?`); render `### Structural patterns (top N from corpus retrieval)`
  and the parent entry through `format-structural-candidate` exactly as a match does, then ONE line built only from
  payload values, e.g. `Assigned to domain child <child-tree-id> (label: <domain-label>) under the top match — this
  campaign's outcome is that child's first evidence.`;
- **consolidated child:** the child renders as the primary entry (build the candidate from the payload: target the child,
  content = the child's body `:summary`, the top match's `:reasoning` and `:fitness-score`), then one shape-context line
  naming the parent `<parent-tree-id>` and its body `:summary` first sentence — no second full entry;
- the SPECIALIZE bullet of the four-move menu becomes a function of the payload: with `:domain` present it reads
  `SPECIALIZE — the runtime has already assigned this task to a domain child of the top match (label: <domain-label>);
  design for that domain under the parent's shape, do NOT mint a structural child yourself.`; without `:domain` the
  bullet is byte-identical to today. Move the constant block into a function taking the payload; keep every other byte.

**Injection record unchanged in shape.** `:task-class` already records `(:assigned-tree-id …)` = the child;
`:candidates` come from `structural-display-candidates`, which now returns the parent candidates for a newborn (report
this as the one observable difference in the record: a domain mint records the parent candidates it rendered, where
before it recorded none). Holdout arms: `:holdout` renders nothing, `:claim-holdout` suppresses claims — both untouched.

## TDD cycle list

1. **RED** `rs4_waterfall_render_test.clj` (orc-service; `(tp/apply-r05-classifier-context node {})`, bodies stubbed
   with `with-redefs [ontology/get-description …]` keyed by target id, as `r_inject_classifier_context_test` does): a
   payload whose `:structural` carries `:domain {:assigned-via :mint-domain-child …}`, `:was-fresh-mint? true`, and
   top-1 targeting the parent with a stubbed parent body (summary, one strength, one representative use) and a stubbed
   child body with `:consolidated-from-event-count 0` → the instruction contains the parent's injected summary and
   strength trait (the full entry), contains the child id string and the label, and the child line's index is AFTER the
   parent entry's index; the fresh-mint sentence ("No high-confidence structural match") is ABSENT. RED today (fresh-mint
   branch). GREEN.
2. **RED**: the SPECIALIZE bullet: with `:domain` the instruction contains the label inside the SPECIALIZE line and does
   not contain the `mint-behavior!` SPECIALIZE wording of the structural menu; without `:domain` the whole block is
   byte-identical to the current output (capture the current output from a plain-match payload BEFORE your change in a
   fixture-free way: assert equality between the block rendered by a plain-match payload and by the same payload with
   `:domain` dissoc'd — i.e. the pure function is stable — and keep the existing suite green). GREEN.
3. **RED**: the consolidated child: child body `:consolidated-from-event-count 3` with its own summary and strength →
   the child's summary and trait are rendered as the primary entry, the parent's summary appears in exactly ONE
   shape-context line (count occurrences of the injected parent summary = 1), and the child line from cycle 1 is absent.
   GREEN.
4. **RED** (wedge seam, mirror `wedge-stashes-r05-classifier-payload`): stub `ontology/classify-task` to return a
   `:mint-domain-child` result → `(get-in node [:context :r05-classifier :structural :domain])` equals the four-key map,
   key by key; a stubbed plain `:match` result → `:domain` is ABSENT (`contains?` false). GREEN.
5. **Guard**: `r-inject-classifier-context-test`, `cc13-injection-record-test`, `w2p1-claim-holdout-test`,
   `rr22-offered-pattern-whole-test`, `rr1-classify-placement-test`, `bounded-campaign-operations-test` (the two
   `expected-classification-context` literals), `rs3-domain-child-durable-test`, `cc23-classification-observability-test`
   green unchanged.

Propagate note (orchestrator): no new spec obligation (a consumer surface); the injection-record contract
(`cc13`) and the holdout byte-identity (`w2p1`) are the contracts this slice must not move. Expected coverage line:
`0 obligations, 0 covered, 0 uncovered`; report the guard suites' RESULT lines.

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

- `specs/*.allium`; the reranker (RS-1); the classifier (RS-2) and the wedge's command dispatch (RS-3) — only the
  `:context` payload tail changes; the behavioral section and its fresh-mint template; harvest, the consolidator, the
  retrieval gate, the description body slot; the holdout controls and `record-injection!`'s row shape.

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; the rendered block from cycle 1 and cycle 3 verbatim
(as data, not summarised); the injection record row from a newborn render; anything in `docs/*.md` describing R-Inject
that is now stale (list, do not edit — the orchestrator already knows `RLM-GUIDE.md` and `SELF-IMPROVING-LOOP.md`
still describe the retired `/tmp` trace sidecar); what you could NOT verify; the orphan-JVM check.
