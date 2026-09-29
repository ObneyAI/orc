# Grill — domain-child convergence (decisions, in order)

Inputs: `docs/issues/domain-child-convergence/PLAN.md` (dossier, seams, agenda); the R-Inject specialisation
decisions D1–D7b and ADR 0006; RS-P1/P1b/P2 findings; RS-6 sweeps; the convergence sweep
(`development/bench/ood-stress-results/2026-09-16_093519-rs6-convergence-sweep/FINDINGS.md`); usefulness report 06.
Branch `feature/domain-child-convergence`.

## C0 — "converged" means one domain family per domain per tenant

Evidence: exact repeats converge (RS-6 run B, identity stable 18/21); paraphrases do not (0 landings / 24) because
retrieval's top-1 shape moves between paraphrases (±0.20 reranker variance is normal) and identity was scoped to the
parent; harvest needs ten occurrences of ONE class, unreachable under fragmentation.

Decision: a domain converges when every occurrence of it lands on the domain child minted first for it, under
whichever shape minted it; the shape stays context, the family is the learning identity. Glossary: **Domain family**.
Rejected: one child per domain per parent (the definition that produced the negative result); cross-parent
"same domain" edges with harvest-time aggregation (every consumer reads one class id today — largest change surface
for the same outcome).

## C1 — identity: parent + label derivation for the first mint; landing by canonical label tenant-wide

Evidence: mechanism (a) of the convergence sweep — the same label minted under two parents because retrieval's
top-1 moved; RS-P1b — a shown label is reused 16/17 (the label is a stable key when chosen among known labels, the
parent is not); four existing stores hold children under the parent + label derivation.

Decision: a family's identity is still derived from its birth parent and canonical label (every existing identity
stays valid; ADR 0006's derivation clause stands); a later occurrence looks the canonical label up across ALL of the
tenant's domain children (the concept graph: agent-authored tree-class concepts and their skos:broader edges), not
only the matched parent's, and lands there; the family's parent edge remains its first shape, which the render shows
as context. Landing on an exact canonical-label match consults no judge (the cheap path); whether a NEW label names
an existing family is the merge judge's question (C3). User addition: the ontology's hybrid search (graph BFS +
embeddings, RRF-fused — `ontology/hybrid-search`) over the tree-class concepts is the neighbourhood retrieval the
merge judge draws candidates from, so relationships in the graph and embedding similarity of labels and birth
signatures both surface near families by rank; retrieval proposes, the judge decides (judged-not-inferred).
Rejected: label-only identity (orphans every existing child; merges silently with no judge); prefer-the-top-k-owner
only (a strict subset of the tenant-wide lookup — the owning parent is often outside the top five).
Spec: `DomainChildIdentityIsStable` 335–347, trigger comment 919–935, the rules' `domain_children(shape_class)` →
`domain_family(canonical_label)`; ADR 0007 supersedes ADR 0006's lookup clause. Glossary: Domain child's identity
sentence names the birth parent.

## C2 — labels at task-family granularity plus a bounded label list, AND a rich self-description at birth

Evidence: label reuse 0/12 on paraphrases vs 16/17 on repeats (labels are stable keys only for exact matches; prose
calibrates a live model weakly — RS-P1); the usefulness report shows the model reads and names rich bodies (purpose,
strengths with worked DSL, representative uses), not labels; a newborn family's body today is one line (the birth
signature), so any similarity over newborns is similarity over almost nothing; minted concepts carry no embedding
(embed-concept exists as a command, nothing invokes it on creation), so the hybrid search's embedding leg cannot see
them.

Decision (user: "as much rich information as we can use the better; connections between behaviors; descriptions
must not be surface level"): (1) the reranker's label instruction moves to task-family granularity (subject matter
and output kind, never the instance's material) and the reranker is shown a bounded tenant-wide label list — the
cheap exact path; (2) load-bearing: every family is born with a rich, self-contained self-description — purpose
(subject matter, material, output kind, drawn from the verdict reasoning and the task), the shape it was born under
and that shape's role, the birth signature, and the slots that fill as evidence lands (worked pattern, successes,
failures, related families) — written through the claim path (CC-6), and its concept embedded at birth so
`hybrid-search` (graph BFS + embeddings) finds it by meaning and by graph proximity. Descriptions and the merge judge
compare substance, never labels alone.
Rejected: instruction-only calibration; a label list alone (blind to the paraphrase case).

## C3 — a judged merge on every would-be mint

Evidence: every off-domain group's paraphrases were one domain with distinct coined labels; the standing rule
forbids string canonicalisation; the bundle prototype recorded that rank fusion cannot separate a variant from junk
(fusion chooses candidates, a judge decides); RS-P1b showed the model answers a discrete question well when shown
the right candidates (16/17).

Decision: when the classification would mint (first child under a shape, or a sibling), retrieve the nearest
existing families by RANK from `hybrid-search` over the tree-class ontology (query: task signature + the verdict's
domain reasoning; seeds: the retrieved shapes' URIs so graph proximity counts), take `merge_candidate_count`
(default 5) with no similarity cutoff, and ask one discrete question with the candidates' rich descriptions in view:
same family as one of these (named), a new family, or unknown — reason before verdict. Same → land on that family;
new → mint; unknown → defer on the domain axis (`merge-unresolved`), structural assignment stands, nothing minted.
Zero calls on landings and covered matches; an empty neighbourhood is "new" without a call. Order: covered-seed
protection (C4) → exact-label landing (C1) → merge judge → mint. Prototype RS-P3 gates the slice (same-group merge
rate, cross-group false merges, families per group, unknown rate; arm A rich-description neighbourhood vs arm B
label list).
Rejected: no merge step; the judge on landings too (a call per recurrence for a rare collision); a deterministic
embedding threshold (inferred, not judged).
Spec: rule `MergeIntoDomainFamily`, invariant `DomainFamilyMergeIsJudged`, config `merge_candidate_count`.
Glossary: **Merge verdict**.

## C4 — covered-seed protection, ordered first

Evidence: the third legal paraphrase retrieved briefing-generation at 0.90 (`partial`) and minted a mis-parented
child while the legal seed sat in the top-k `covered`; only top-1's verdict was consumed. D7b: `covered` on a parent
WITH children is not evidence (20/21 regardless).

Decision: before the domain axis, if top-1 would mint and another tree-class candidate in the ranking is judged
`covered`, has no domain children, and is at or above the ordinary match threshold, that candidate is assigned as a
plain match and the event records `domain_selection` (preferred, over, reason). No new threshold. Order: protection
→ exact-label landing (C1) → merge judge (C3) → mint.
Rejected: no protection; covered-regardless-of-children (re-imports the D7b bias); a fitness margin (a threshold
over a score).
Spec: the mint rules gain the requires; `DecidedRankingIsRecorded` records the selection. Glossary:
**Covered-seed protection**.

## C5 — a domain family is a leaf on the domain axis

Evidence (Slice 0, pinned in data): a newborn reached as top-1 with `partial` becomes a parent and mints a
grandchild; reached by walk-down or as a covered plain match it carries no label or verdict, so no child line
renders; the full-bench marathon campaign minted under the recipe family born one task earlier; harvest promotes
one class at ten occurrences.

Decision: any match or walk-down that resolves to a family is a LANDING — provenance landed_on_domain_child,
parent = the family's own skos:broader (its birth shape), label = its concept label, verdict not consulted; the
domain axis never re-applies beneath a family, so there are no grandchildren, the event carries the domain facts
however the family was reached, and the child line renders in every reach route. A task that reaches a family but
is judged a different domain is the merge judge's case and mints under the retrieved SHAPE, never under the family.
Rejected: re-application (unbounded depth, fragmentation under a one-line "shape"); keeping walk-down provenance
for a family (two provenances for one fact).
Spec: invariant `DomainFamilyIsALeafOnTheDomainAxis`; `LandOnDomainChild` second trigger; the trigger comment
defines shape_class as a non-family tree-class. Seam: an injected family-parent lookup (fail closed).

## C6 — a newborn family is matchable but not surfaced until the gate

Evidence: the gate passes total 0 for "a curated seed or a brand-new mint" alike; a newborn's body is one signature
line with confidence 0.0; the full-bench marathon task retrieved a minute-old family as a confident candidate; a
family at one or two occurrences is matchable but hidden from the display. Seeds never create a concept; families
carry agent-authored provenance and the domain-child-minted event.

Decision: at total 0 a class with agent-authored provenance stays in the matching candidates but is removed from
the surfaced list until it reaches the retrieval gate; curated seeds keep passing at 0; matching stays ungated
(CV-1). Landing on a newborn happens through the graph (C1) and the reached-family rule (C5); the child line comes
from the event's domain facts, not from the surfaced list.
Rejected: leave the band; a lower band for families (a new threshold with no evidence); hiding newborns from
matching (the CV-1 deadlock).
Spec: config text 615–617 ("a curated class at zero is surfaced; a newborn family is matchable but surfaced only
from the gate"). Seam: `newborn?` in the gate, default = concept provenance. Glossary: **Newborn family**.

## C7 — the family's own body is shown as soon as it has substance; the closure rejection gets a hint

Evidence: usefulness report 06 — the second recipe occurrence read the child line but "was never shown what the
child had learned", although the CV-2 enrichment had recorded the first campaign's worked tree as a strength on
the family (evidence count 0, so RS-4's consolidated-only rule hid it); the marathon second occurrence burned five
iterations and 139k tokens on the closure rejection with no hint.

Decision (render): the parent shape stays the primary entry until consolidation (D5), and beneath the child line the
family's own body renders whenever it has substance beyond the birth line — purpose, strengths, weaknesses, worked
pattern — capped like a seed body; the injection record lists both the parent and the family at their body
versions. Decision (hint): both a preventive bullet in the Phase-1 "Common pitfalls" (scoped to checkpointed runs
where the block can be conditional) and a reactive arm in the error-hint table keyed on the sandbox's own ex-data
keyword / exported constant — never a regex over the message.
Rejected: family primary from its first strength (too little evidence to lead with); reactive-only or
preventive-only.

## C8 — proof shape: deterministic gates; sweeps report metrics against expectations stated beforehand

Decision: the gate is deterministic — every new rule branch through `classify-task` with stubbed reranker and
merge payloads (covered-seed protection, tenant-wide landing, merge same/new/unknown, family-as-leaf on all three
reach routes, newborn matchable-but-hidden, the render with the family's body), store-backed proofs for the birth
description and the cross-parent landing, Allium error-free with every obligation covered. The RS-7 sweeps (400
tasks, two passes, 30 end-to-end campaigns; baseline arm on the pre-fix tree, post-fix arm on the final tree; same
frozen corpus, order, reindex policy and models) REPORT: per off-domain group families minted, landings on later
variants, cross-parent scatter, label variants; in-domain mints and confounder mints under a seed; deferrals per
axis; behavioral fresh mints; exact-repeat identity stability; merge calls and verdict split; tokens and latency;
and for the end-to-end subset whether later variants land and see accrued substance and complete at least as well.
Expectations are written BEFORE the post-fix run — families per off-domain group toward one, later-variant landing
rate up, in-domain mints to zero, exact-repeat stability not below run B's 18/21, no cross-group false merge
without a recorded reason, merge unknowns reported — and a miss is a documented reason in the findings, never a
failed build (D6, ADR 0029's "never fired vs never will").
Rejected: metrics with no stated finish line; numeric pass/fail gates over a live model's variance.

## C3' — every landing on an existing family is judged (revises C1's landing and C3's "never on a landing")

Evidence: the RS-7 post-fix arm's pass 1 (238 tasks) placed 209 tasks in 17 families shared across groups; every
landing was an unjudged label landing. After the reuse rule was corrected, two live checks on 42 confusable tasks still
put tasks from different groups in one family (2 and 5 shared families), through three unjudged paths: a label a family
already carries, a family reached by match or walk-down, and a shape's existing child label. The merge judge (RS-P3:
about 1 false merge in 23) ran only on would-be mints.

Decision (user, 2026-09-29): no task enters an existing family without the merge judge. The family a landing would
choose is always among the judge's candidates; same lands on the named family, new mints, unknown defers. A family
reached by match or walk-down is judged as a match on its own parent shape. Covered matches and covered-seed protection
stay judge-free.
Rejected: judge only reached families (leaves label reuse, the dominant path, unjudged); keep the design and tune the
reranker's text (two checks after the rule fix still merged across groups; the proof must not rest on prose).
Cost: one judge call (about 1.5k tokens) per recurrence, against the about 20k-token rerank each classification makes.
Spec: `DomainFamilyMergeIsJudged` and `DomainFamilyIsALeafOnTheDomainAxis` revised; `LandOnDomainFamily` and
`LandOnReachedDomainFamily` replaced by `MergeIntoDomainFamily` (with a proposed family) and
`JudgeReachedDomainFamily`; `MintDomainFamily` guards an identity that already exists.
