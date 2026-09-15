# PRD: R-Inject specialisation — domain children on the identity waterfall

**Source of truth:** `specs/ontology.allium` (`contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`,
`rule MintDomainChild`, invariants `DomainCoverageIsJudgedNotInferred` and `DomainChildIdentityIsStable`).
**Decisions:** `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md` (D1–D6).
**ADR:** `docs/adr/0006-domain-children-are-minted-at-classification-from-a-judged-coverage-verdict.md`.
**Evidence:** `development/bench/ood-stress-results/2026-09-15_111739-classify-only-post-emergence-loop/FINDINGS-V3.md`.
**Branch:** `feature/r-inject-specialisation`, off the RR-durable arc at RR-33.

## Problem Statement

When a researcher campaign starts, its task is classified against the corpus so the model is shown proven patterns and
so the campaign's outcome teaches the right class. The corpus's classes describe **shape** (an ETL pipeline, a
producer-and-validator loop, draft-critique-revise). A marathon training plan, a recipe scaling and a change-data-capture
pipeline all *look* like a sequential pipeline, so all three match the same class at high fitness. Measured twice on a
21-task out-of-distribution corpus: 14 of 21 confident matches in June, 20 of 21 in September after the emergence loop
landed, with the domain guard firing on none because every seed's guard is written in terms of shape.

Since the durable arc, a classification is the identity that everything learns under: recurrence counts at the verdict,
consolidation reflects over the class's occurrences, the living description accrues the class's strengths and
weaknesses, and harvest promotes the class into a named behavior. So a marathon plan that succeeds reinforces "ETL
pipeline" with marathon strengths, the class's winning shapes and worked patterns blur across domains, and the
waterfall the corpus was designed around — research, then research-Wikipedia, then research-a-specific-wiki — never
forms below the shape layer. Nothing is fabricated (detect-and-defer holds), the model is only advised (references
inform, never gate), but the loop learns the wrong thing from the right work.

## Solution

Specialise the **learning identity**, from evidence, at the moment the classifier can see the gap. When a task matches
a leaf shape class on fitness but the reranker judges that the class's declared domain does not cover the task, the
runtime mints a **domain child** under that leaf: a class with one stable identity derived from the parent and the
task's canonical domain label, its parent edge in the concept graph from birth, and a self-description made of its label
and the signature that minted it. The campaign's recurrence, consolidation and harvest then accrue on the child. The
model is shown the waterfall top down: the parent shape it matched, with its proven pattern, strengths and guards, and
one line naming the child it will teach. Harvest is unchanged: a domain child that recurs and scores well is promoted
into a behavioral child under the nearest abstract behavior exactly as any class is today, which is how
"research-Wikipedia" becomes a named behavior without anyone authoring it.

Domain coverage is a **discrete verdict with reasons** the reranker gives beside fitness — covered, partial, uncovered,
or unknown — never a number and never a threshold over a similarity score. Fitness keeps meaning shape-and-intent fit.
Unknown defers on the domain axis exactly as a reranker fallback defers on fitness: the shape class is assigned, no child
is minted, and the deferral is recorded naming the domain axis.

## User Stories

1. As a workflow author, I want a task that matches a shape class but sits in a domain the corpus has never seen to get
   its own class under that shape, so that what my campaigns learn is not blurred into unrelated domains.
2. As a workflow author, I want the second and third campaigns in the same domain to land on the same domain child as
   the first, so that the class accrues evidence instead of scattering one identity per occurrence.
3. As a workflow author, I want a domain child to exist whether or not retrieval happened to find it, so that the
   retrieval gate's quiet period between a class's first and third verdict does not scatter my campaigns.
4. As a workflow author, I want a task whose domain is only partly covered by the matched class to stay on that class
   and record the domain it drifted toward, so that the consolidator sees the drift as evidence before a split is forced.
5. As a workflow author, I want the runtime never to mint a child when the reranker could not judge coverage, so that
   uncertainty never fabricates an identity.
6. As a workflow author, I want a coverage deferral recorded durably and named as such, so that "uncertain about the
   domain" and "nothing happened" are different facts I can count.
7. As a model designing a tree, I want to be shown the shape my task matched with its worked pattern, strengths and
   guards, so that I keep the proven shape while pinning my domain.
8. As a model designing a tree, I want to be told which domain child my campaign has been assigned to and that its
   outcome is the child's first evidence, so that I know which identity my work teaches.
9. As a model designing a tree, I want the domain child's own body shown as the primary reference once it has one, with
   the parent as shape context, so that the reference I read is the most specific proven one.
10. As a model, I want the four moves to still be mine, so that a domain child assignment informs my design rather than
    forcing it.
11. As the self-learning loop, I want recurrence, consolidation counters and harvest to accrue on the domain child, so
    that promotion is decided per domain.
12. As the self-learning loop, I want a domain child that recurs past the retrieval gate to be retrievable as a
    sibling of its parent's other children, so that later tasks in that domain match it directly.
13. As the self-learning loop, I want harvest to promote a well-scored recurring domain child into a behavioral child
    under the nearest abstract behavior, so that the waterfall reaches the behavioral layer without authored seeds.
14. As the self-learning loop, I want the walk-down classifier to reach domain children from their parent, so that a
    later task can descend to the domain-specific class rather than stopping at the shape.
15. As the self-learning loop, I want the domain verdict and label recorded on the classified event, so that every
    decision can be read back and counted.
16. As a curator, I want a domain child to describe itself from birth with its label and the signature that minted it,
    so that I can read what it is for before any consolidation has run.
17. As a curator, I want the parent edge in the concept graph from the moment a child is minted, so that the hierarchy I
    inspect is the hierarchy the classifier uses.
18. As a curator, I want the reranker's coverage reasoning to name the representative use or guard it matched or the
    gap it found, so that a wrong verdict is diagnosable.
19. As a curator, I want domain children to be cheap identities rather than corpus bodies, so that many of them cost
    nothing in the reranker prompt.
20. As a curator, I want the authored behavioral children's granularity rule left as it is, so that harvest, not
    classification, decides which children earn a body.
21. As an operator, I want no additional model call per classification, so that the rerank budget and retry policy
    stand.
22. As an operator, I want the live sweep to read the three-state outcome and the coverage verdict, so that the
    instrument that found the gap can confirm the fix.
23. As an operator, I want a second pass of the same corpus to derive identical child identities, so that stability is
    demonstrated live and not only in a unit test.
24. As a maintainer, I want every deterministic proof to assert on structured data the runtime emits, so that the
    tests do not break on a model's phrasing.
25. As a maintainer, I want the domain-child mint to leave walk-down's own leaf mint untouched, so that the two paths
    are distinguishable in the classified event's provenance.
26. As a maintainer, I want the sanity-check tasks in the corpus to keep matching at 1.00 with coverage judged covered,
    so that the change is shown not to disturb in-distribution classification.

## Implementation Decisions

- **Reranker output (D4).** The rerank call's per-candidate result gains two typed fields, `domain_coverage` (one of
  covered, partial, uncovered, unknown) and `domain_label` (a short canonical label of the task's domain), with the
  reasoning required to name the representative use or guard matched or the gap found. Fitness keeps its meaning. The
  parse step canonicalises the new keys as it does the existing ones and treats a missing or malformed verdict as
  unknown rather than coercing it. The instruction is edited in place; stance and score definition are unchanged.
- **Classifier (D2, D4).** After the existing match, bundle, walk-down and deferral logic has produced an outcome, the
  classifier applies the coverage verdict of the assigned candidate. On a `matched` outcome whose assigned class has no
  children and whose verdict is `uncovered`, it produces a domain-child assignment: the derived child identity, the
  parent, the label, and provenance `:mint-domain-child` distinct from walk-down's `:mint`. On `partial` it assigns the
  leaf and carries the label for a claim. On `unknown` it returns the shape assignment with a domain-axis deferral to be
  recorded. `covered` changes nothing. Walk-down's leaf mint and the bundle band are untouched.
- **Identity (D3).** The child's identity is derived deterministically from the parent class identity and the canonical
  label, by the same derivation the behavioral mint uses for name and parent. Label canonicalisation is deterministic
  and documented; EL-1b bundling among the parent's children remains the safety net for label variance.
- **Recording (D3).** The classified event carries the coverage verdict, the label, the reasoning and the parent. A
  domain-child mint additionally records, at classification time and before the campaign runs: the signature claim
  CV-1 already records, and a description slot for the child carrying the label as summary and the parent as the SKOS
  broader link, so the concept-graph projector creates the edge immediately. Both are claim operations on the existing
  claim path; no second writer of a body slot. A `partial` verdict records the label as a representative-use claim on
  the parent class.
- **Deferral (D4).** An unknown verdict is recorded through the existing deferral command and event, extended to name
  the domain axis alongside the structural and behavioral axes it already names.
- **R-Inject render (D5).** When the assigned class is a domain child with no consolidated body, the structural section
  renders the parent's full entry as the matched shape and one line stating the child assignment and that this
  campaign's outcome is its first evidence; the four-move menu's SPECIALIZE wording points at that fact. When the child
  has a consolidated body, the child renders as the primary entry and the parent as a shape-context line. Existing
  holdout arms and injection recording are unchanged.
- **Harvest and consolidation.** No change. A domain child is a tree class like any other to the counters, the
  consolidator, the retrieval gate and harvest; harvest's nearest-abstract-behavior walk already anchors the promoted
  child under the right parent.
- **Bench harness (D6).** The classify-only sweep helper reads the three-state outcome, the assignment provenance and
  the coverage verdict, and reports them per instruction and in aggregate alongside the June-era flags so the two runs
  stay comparable.
- **Spec.** Already tended: `enum DomainCoverage`, `value DomainVerdict`, `judge_domain_coverage` on the contract,
  `rule MintDomainChild`, and the two invariants. `allium plan` emits eight obligations for them.

## Testing Decisions

A good test drives a public surface — the classifier function, the wedge through the orc-service test helpers, the
ontology commands and read models, the harvest gate, the prepend renderer — and asserts on what the runtime durably
emits: event bodies, identities, concept-graph edges, rendered values the test itself injected. **Standing rule from the
grill: no assertion uses regex or phrase matching over model-authored prose.** The reranker is stubbed with a typed
payload the test constructs; the live sweep is the only place the real reranker speaks.

- **Seam 1 — classifier, pure.** `classify-task` with the reranker stubbed to return the new fields. Prior art:
  `el3_three_state_outcome_test`, `walk_down_classifier_test`, `el1b_convergence_capture_test`,
  `cc23_ranked_candidates_test`. Covers mint on uncovered leaf match, partial, unknown, covered, identity derivation,
  and that walk-down's own mint is unchanged.
- **Seam 2 — reranker contract.** The rerank workflow's parse step with a constructed payload, including a payload
  missing the new fields. Prior art: `reranker_test`, `cc15_reranker_enrichment_contract_test`,
  `rerank_failure_surfacing_test`.
- **Seam 3 — wedge through the live processor path.** The orc-service auto-classify wedge with `rerank!` redefined,
  asserting the classified event, the signature and description claims, the deferral event's axis, and the concept-graph
  edge after the projector runs. Prior art: `r_inject_classifier_context_test`, `cc6_cv1_claim_capture_test`,
  `el3_wedge_skip_uncertain_test`, `cc23_classification_observability_test`.
- **Seam 4 — learning consumers on synthesised events.** A domain child recurring past the retrieval gate is
  retrievable as a sibling and harvests under the nearest abstract behavior. Prior art: `el4_harvest_test`,
  `rr21_winning_shape_coherence_test`, `seeds_test` (children and parents).
- **Seam 5 — R-Inject render.** The deterministic prepend suite, asserting on the parent entry's identity and the child
  line built from injected values. Prior art: `r_inject_classifier_context_test`, `cc13_injection_record_test`.
- **Seam 6 — live.** The 21-task classify-only sweep on the final tree (harness updated), and one bounded full-bench run
  on three off-domain tasks, recorded as observation.

## Out of Scope

- Authoring domain-specialised seeds (the R04 handoff's Path A). Rejected in D1.
- A separate meta-judge call (Path B as a mechanism). Rejected in D4; its signal is folded into the rerank call.
- Retroactive splitting of a class from consolidation evidence. Rejected in D2.
- Changing the retrieval gate, the bundle band, the harvest thresholds or the coherence measure. They are consumed as
  they are; calibration remains the data-driven slice ADR 0029 describes.
- The behavioral layer's authored children and their granularity rule (RG-2 / RG-4). Unchanged; harvest decides bodies.
- Behavioral classification (`classify-behaviors`) and the corrected two-classifier experiment from FINDINGS-V2. The
  bench harness records the behavioral envelope where it is cheap to do so, but no behavioral-axis change is made.
- Making the sweep's counts an acceptance threshold. Rejected in D6.

## Further Notes

- The June sweep and the September re-run disagree with the user docs' "resolved" claim about out-of-distribution
  force-fit. The docs will be corrected in this arc's documentation pass to state the current behavior precisely:
  nothing is fabricated, and the identity now specialises by domain.
- `allium check` reports one information-level diagnostic that `MintDomainChild` listens for a trigger no local surface
  provides; the classified event is emitted by the orc-service wedge through an ontology command, the same cross-module
  shape 24 other rules in the spec already have. It is not an error and is recorded here so nobody "fixes" it.
- The Grain pins on this branch are the arc's (nine sites at the PR #22 head). The RR-15 merge-gate bump applies here
  too when PR #22 merges.
