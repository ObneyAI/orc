# RS-6 convergence sweep — do paraphrases of one domain converge on one child? (they do not)

Eight domain groups, three paraphrases each (a different concrete instance of the same domain: a marathon plan
for three different runners; a ratatouille, a sourdough bakery and a festival curry to scale; three schema
migrations on three databases …), interleaved so no group's members were adjacent, through the live wedge on one
store. Two groups are in-domain sanity groups (legal clause review, contract comparison). The question: does the
runtime mint ONE child per domain and land the rest, or does it over-mint?

## Headline

| group | mints | landings | plain matches | distinct assigned classes | converged? |
|---|---:|---:|---:|---:|---|
| marathon | 2 | 0 | 0 | 2 (+1 withheld) | no |
| recipe | 3 | 0 | 0 | 3 | no |
| game balance | 2 | 0 | 0 | 3 (one novel fresh mint) | no |
| schema migration | 3 | 0 | 0 | 3 | no |
| security haikus | 2 | 0 | 0 | 3 (one novel fresh mint) | no |
| query optimisation | 2 | 0 | 0 | 2 (+1 withheld) | no |
| legal (in-domain) | 1 | 0 | 2 | 2 | no — one paraphrase minted a child under the WRONG shape |
| contract comparison (in-domain) | 0 | 0 | 3 | 1 | yes |

**Zero landings in twenty-four tasks.** Every off-domain group produced two or three distinct children where the
design intends one. The behavioral axis is the opposite story: 56 behavioral entries across the sweep, all 56
matched an existing behavior (14 distinct), zero fresh-mint markers — behaviors converge; shapes-with-domains do not.

## Why — two mechanisms, both visible in the records

**1. The parent moves.** Identity is parent + label (decision D3). Paraphrases of one domain do not always retrieve
the same top shape: the three marathon plans matched iterative refinement, a sequential pipeline and iterative
refinement again; the three schema migrations matched the ETL pipeline, iterative refinement and the ETL
pipeline. A label that exists under one parent is invisible when the next paraphrase lands under another, so the
same domain is minted again under the new shape. The existing-children list the reranker is shown is
per-candidate, so it cannot help across parents.

**2. The label is coined at instance granularity.** Even under the SAME parent and with the sibling's label shown,
the reranker coined a new label when the instance differed: `recipe-scaling-and-catering-prep`, then
`bakery-production-scaling`, then `commercial-recipe-scaling`, all under one parent;
`postgresql-query-optimization` then `sql-query-optimization`; `security-vulnerability-haikus` then
`security-finding-haikus`. The instruction says to reuse a listed label "if one names THIS task's domain"; the
model reads a sourdough bakery and a festival curry as different domains. RS-P1b measured reuse at 16 of 17 on
REPEATS of the same instruction; on PARAPHRASES the reuse rate here is 0 of 12 opportunities.

Two tasks were withheld by a reranker fallback on either axis (visible deferrals, the EL-3 rule); two off-domain
paraphrases were judged novel at fitness 0.30 and 0.10 and fresh-minted (correct: no shape fit).

**The in-domain miss matters most.** The third legal paraphrase (an executive termination and severance clause)
matched the briefing-generation class at 0.90 rather than the legal-issue-detection seed, was judged `partial`,
and minted `legal-clause-review` under the wrong shape — a domain child for a domain the corpus already covers
with a dedicated seed. Retrieval variance on an in-domain task became a permanent, mis-parented child.

## What this means

The arc's mechanism works on exact repeats (the two-pass sweeps: identity stable on 18 of 21) and fails on
paraphrases, which is the realistic case. Left as is, real traffic would grow many near-duplicate children per
domain across several parents, each with thin evidence, and harvest would never see ten occurrences of any one of
them. This is the calibration problem to solve before the loop runs on real workloads; it is not a defect in the
durable machinery (every mint, edge and claim landed exactly as specified).

Candidate directions, for a grill rather than a decision here:

- **Label reuse across parents.** Show the reranker the tenant's existing domain-child labels (bounded, most
  recent or most used), not only the candidate's; when the judged label already exists under another top-k
  candidate, prefer that candidate as the parent. Keeps parent + label identity and removes the "parent moved" mint.
- **Coarser labels by instruction.** Ask for the domain at task-family granularity (subject matter and output
  kind, never the instance's material: "recipe-scaling", not "bakery-production-scaling"), and require reuse
  unless subject matter AND output kind both differ.
- **A judged merge step at mint time.** Before a sibling is minted, retrieve the nearest existing labels (an
  embedding or ColBERT neighbourhood, not a string rule) and ask one more discrete question: same domain as this
  existing child, or genuinely new? Land on a "same" verdict; mint on "new". This is the same judged-not-inferred
  discipline the arc used for coverage, applied to the label.
- **Protect covered seeds.** An in-domain task pulled to a neighbouring shape at high fitness should not mint a
  child when a seeded class with a `covered` verdict sits in the top-k; the covered candidate should win the
  assignment.

Evidence: `pass-1/` per-instruction envelopes, `analysis.edn`, `CONVERGENCE.md`. Corpus:
`development/bench/ood-corpus-convergence/` (24 hand-authored paraphrases in 8 groups).
