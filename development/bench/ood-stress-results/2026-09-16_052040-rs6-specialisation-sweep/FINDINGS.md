# RS-6 specialisation sweep — run A (two passes through the live wedge)

Tree: RS-1 … RS-5 landed, plus the RS-6 weed fixes (the reranker is shown a class's existing domain children; the
domain-axis deferral is once per occurrence). The three-state outcome was not yet recorded on the classified event
in this run (the `outcome` column is empty; run B on the final tree carries it). Every instruction went through the
real auto-classify wedge with a fresh sheet and tick, so mints are durable; pass 2 ran on the same store.

## Headline

Pass 1 — 21 tasks: 15 domain mints (5 first children, 10 siblings), 1 landing on a child minted earlier in the same
pass, 2 plain matches (the legal-issue sanity check at 1.00 covered and the vector-vs-SQL conversation at 1.00
covered), 1 bundle, 1 fresh mint (the melody encoding, fitness 0.40), and 1 withheld classification (the
contract-comparison sanity check: structural top-1 1.00 covered, but the BEHAVIORAL reranker fell back, so the
existing rule that withholds assignment on a fitness-axis fallback deferred the tick — the deferral is visible as
an event naming the axis).

Pass 2 — the same 21 tasks: 12 landings on the child pass 1 minted (identity identical), 3 plain matches (both
sanity checks at 1.00 covered), 2 bundles, 3 sibling mints, 1 withheld (a raw-ColBERT reranker fallback on the
recipe-iambic task). Identity stable for 15 of 21; the six that moved are itemised below. With children present the
reranker called the parent covered on 17 of 21 (pass 1: 7 covered, 8 partial, 3 uncovered), the D7b finding
reproduced live, and the judged label decided instead.

## What the shape-broad corpus did

The June and September classify-only sweeps showed the corpus absorbing 20 of 21 tasks as confident structural
matches. That still happens (top-1 fitness 0.90–1.00 on 17 tasks) — but the match is now the SHAPE, and the task
lands on a domain child of it: game balance, recipe scaling, marathon training and symphony arrangement each got a
child under the shape that absorbed them, labelled by the reranker, with an edge to the parent and the task's
signature as its first representative use. The second pass converged on those children by label.

## The six that did not converge

| task | what happened | reading |
|---|---|---|
| melody-encoding | pass 1 fresh mint at 0.40; pass 2 sibling mint under a shape | the pass-1 mints changed the index (birth claims tick the rebuild), so retrieval differed |
| recipe-iambic | pass 1 first child; pass 2 withheld (reranker fell back to raw ColBERT) | live fallback variance; visible as a deferral, not a silent miss |
| property-tests | pass 1 first child; pass 2 bundle at 0.60 | retrieval variance dropped the top match below threshold |
| memory-leak | same label `jvm-oom-diagnosis` both passes, but under a DIFFERENT parent shape | identity is parent-scoped by decision D3: the same domain under two shapes is two children |
| defend-controversial-choice | pass 1 landed on `software-architecture-recommendation`; pass 2 coined `software-architecture-memo` | label choice varied between reuse and coining for a task that fits neither well |
| contract-comparison sanity | pass 1 withheld by a behavioral fallback; pass 2 match at 1.00 covered | the sanity check holds on the structural axis in both passes |

## Observations for the roadmap (not fixed here)

- Parent-scoped identity means retrieval variance in the PARENT scatters a domain across shapes; a label matching an
  existing child under another top-k candidate could steer the parent choice. Open question, not a defect.
- Label reuse pressure works when the sibling label fits (14 landings by label) and over-reaches when a task fits
  no sibling (the controversial-choice task landed on a recommendation label in pass 1).
- The behavioral-axis fallback withholding a confident structural assignment is pre-existing behaviour (EL-3),
  visible as a deferral; its rate is live reranker variance.

Evidence: `combined.edn` (every per-instruction record less the rendered block), `SUMMARY.md`. The per-instruction
envelopes with rendered blocks are kept for run B on the final tree
(`2026-09-16_053810-rs6-specialisation-sweep/pass-1`, `pass-2`), which supersedes this run.
