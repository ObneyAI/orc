# R-Inject specialisation — grill decisions

Branch `feature/r-inject-specialisation`, off the RR-durable arc at RR-33. Posed one question at a time with the
prior research in hand (FUTURE-VISION Theme 6, C-2d, E2 / ADR 0014, E3 and RG-2 / RG-4, EL-1b, harvest through RR-21)
and one fresh measurement: the 21-task OOD corpus re-run on the current tree
(`development/bench/ood-stress-results/2026-09-15_111739-classify-only-post-emergence-loop/FINDINGS-V3.md`).
ADR: `docs/adr/0006-domain-children-are-minted-at-classification-from-a-judged-coverage-verdict.md`.

## D1 — the target is the learning identity, not the prepend

The re-run shows 20 of 21 off-domain tasks matching a leaf shape class at fitness 0.85–1.00 with the domain guard firing
on none (every seed's `:avoid-when` is written in terms of shape). Detect-and-defer removed fabrication; what remains is
that a confident match assigns the campaign's identity, and after RR-18–RR-21 that identity is what recurrence,
consolidation, the living description and harvest accrue under — so a marathon plan reinforces "ETL pipeline".
Decision: specialise the identity, from evidence; the retrieval side is mitigated by advice-not-gate and the four moves.
Rejected: start from the retrieval side (Path B alone); author the hierarchy (Path A).

## D2 — the domain child is created at classification, from the reranker's coverage judgement

The waterfall has both halves built (structural: `:parent-tree-id` + walk-down; behavioral: E3 children + harvest
minting under the nearest abstract behavior) but never connects for a domain-novel task: the structural leaves are shape
instances, a direct leaf match returns as-is (Decision 3 mints only after descending), and harvest can only promote a
class that exists. Decision: when the top match is a shape-confident leaf whose domain is not covered, mint a domain
child under it; consolidation and harvest need no change. Rejected: split retroactively at consolidation (late, and
re-attribution fights RR-19's fact model).

## D3 — a domain child is born with a stable identity, its parent edge and a self-description

Today a walk-down mint is a random uuid whose convergence relies on retrieval, but the retrieval gate hides a runtime
class between its first and third verdict; and the concept-graph edge is projected only from a description event
carrying `:parent-tree-id`, which nothing on the mint path records. Decision: identity derived from the parent leaf and
the canonical domain label (E3's stable-id rule one layer down); at mint time record the CV-1 signature claim plus a
description slot carrying the label and the parent, so the SKOS edge projects immediately; EL-1b bundling among the
leaf's children remains the safety net for label variance. Glossary: **Domain child**. Spec:
`DomainChildIdentityIsStable`, `rule MintDomainChild`.

## D4 — domain coverage is a discrete verdict in the existing rerank call

The reranker is already told to weight domain and to treat avoid-when as a hard rule, and still scored the marathon plan
0.85 against ETL pipeline: one number cannot say "right shape, wrong domain". Decision: two typed per-candidate fields
— `domain_coverage` ∈ {covered, partial, uncovered, unknown} with reason-before-verdict naming the representative use or
guard matched or the gap, and `domain_label` — in the same call (RR-1's budget stands). Mint only on a leaf match with
`uncovered`; `partial` assigns the leaf and records the label as a claim; `unknown` / missing / malformed defers exactly
as a reranker fallback does (`DeferralIsVisible`, naming the domain axis). Glossary: **Domain coverage**. Spec:
`enum DomainCoverage`, `DomainCoverageIsJudgedNotInferred`. Rejected: a separate meta-judge call; deterministic
embedding-distance coverage with a threshold.

## D5 — R-Inject renders the waterfall top down for a newborn child

Decision: render the parent leaf's full entry as the matched shape, then one line naming the assignment to the domain
child and that this campaign's outcome is its first evidence; SPECIALIZE points at that fact. Once the child has a
consolidated body it renders as the primary entry and the parent drops to a shape-context line (RR-3's parent-full,
child-competes arrangement). Rejected: today's fresh-mint text (loses the shape); child only (nothing actionable).

## D6 — evidence that closes the arc

Decision: (1) deterministic tests through the live classifier path with a stubbed reranker payload — mint with derived
identity and edge, identity stability, partial → leaf + label claim, unknown → deferral naming the domain axis, prepend
renders parent shape + child line; (2) a Seam-4 synthesised-event test that a domain child recurring past the retrieval
gate is retrievable as a sibling and harvests under the nearest abstract behavior; (3) the classify-only sweep re-run
live on the final tree with the harness reading the three-state outcome and the coverage verdict — sanity checks still
1.00, uncovered leaf matches mint children, a second pass derives identical identities; (4) one bounded full-bench run
on three off-domain tasks, observation not gate. **Standing rule (user):** deterministic proofs assert on structured
data the runtime emits — typed reranker fields, event bodies, identities, graph edges, rendered values the test
injected — never on regex or phrase matches over model prose. Rejected: deterministic only; the sweep as a threshold
gate (biased corpus, live model — ADR 0029's "never fired vs never will").

## Q7 — POSED, NOT YET ANSWERED (verbatim, so it survives compaction)

RS-P1 (`development/bench/ood-stress-results/rs-p1-coverage-probe/FINDINGS.md`) held mechanically (225/225 valid
verdicts, sanity checks covered and stable) and failed on calibration: over 19 off-domain tasks the top match was judged
`uncovered` 2 then 0 times, `partial` 7 then 9, the mint decision flipped between passes for 4, and labels agreed
exactly on 5 of 21 while being semantically stable (`jvm-oom-diagnosis` / `jvm-oom-diagnostics`). The model treats
"a sequence of transformation passes" as a domain; it puts genuine drift in `partial`.

**Q7. How do the mint rule and the identity converge, given what the reranker actually says?**

- **A. Mint on partial too, canonicalise by judged choice, converge by walk-down.** Mint a domain child on a leaf
  match judged `partial` or `uncovered`; `covered` stays on the leaf. Show the reranker the parent's existing children
  labels and require `domain_label` to be one of them when one fits, or a new one otherwise (a judged choice, not a
  string rule). Make convergence independent of the verdict repeating: a matched leaf that has domain children always
  considers them, so walk-down descends into the child the second time regardless of the specificity gate; EL-1b
  bundling remains the backstop. Tighten `covered`'s definition to subject matter, material and output kind. Cost:
  over-minting on `partial` — cheap, children are identities not bodies, and the retrieval gate hides one-offs.
- **B. Keep "uncovered only" and recalibrate the instruction.** Leaves the flip and label problems untouched; wording
  calibration of a live model is the fragile path.
- **C. Mint on partial, derive identity from a rule-canonicalised label.** Rejected on the standing rule: string rules
  over model prose.

**Recommendation: A**, verified by RS-P1b before RS-1 is briefed: re-run pass two with pass one's labels supplied as
the parent's existing children and measure how often the model reuses them. Spec consequence if agreed:
`rule MintDomainChild` requires `coverage in {partial, uncovered}`, and `DomainChildIdentityIsStable` states that the
label is chosen among the parent's existing children before a new one is coined.

**Status:** awaiting the user's answer. Work paused on 2026-09-15 to assess Grain PR #22 (see
`.rr-durable-notes/PR22-ASSESSMENT.md` in the arc worktree and the memory note `rr-durable-open-grill-q6-q7`).

## Slices

To be cut by /to-issues from the PRD (`docs/prd/r-inject-specialisation.md`).
