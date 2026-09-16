# RS-6 specialisation sweep — run B (the final tree, two passes through the live wedge)

Tree: RS-1 … RS-5 landed plus every RS-6 fix (the reranker is shown a class's existing domain children; one
domain-axis deferral per occurrence; the classified event records the three-state outcome). Every instruction went
through the real auto-classify wedge with a fresh sheet and tick; pass 2 ran on the same store, so a landing means
the runtime found the child pass 1 minted.

## Headline

| | pass 1 | pass 2 |
|---|---:|---:|
| outcome matched / novel | 19 / 2 | 20 / 1 |
| first domain mints | 5 | 0 |
| sibling domain mints | 10 | 1 |
| landings on an existing child | 1 | 15 |
| plain matches (covered) | 3 | 3 |
| bundle / walk-down / fresh mint | 1 / 0 / 1 | 0 / 1 / 1 |
| withheld (fitness-axis fallback) | 0 | 0 |
| coverage verdicts | 9 partial, 4 uncovered, 6 covered, 2 none | 17 covered, 2 uncovered, 2 none |

Both sanity checks (legal-issue detection, contract comparison) matched their seeded class at fitness 1.00 with a
`covered` verdict in both passes — the shape-and-domain fit the arc must not disturb. Fifteen of the twenty-one
tasks minted a domain child in pass 1 under the shape that absorbed them, and pass 2 landed on the identical child
for every one of those but one. Identity was stable for 18 of 21.

## What changed since the classify-only sweeps

The corpus still absorbs the off-domain tasks as confident shape matches (top-1 fitness 0.90–1.00 on most), which
the June and September sweeps recorded as the unresolved symptom. Now the match is the SHAPE and the task lands on
a domain child of it: the ETL-pipeline class alone absorbed eight tasks in pass 1 (auth-middleware refactoring,
memory-leak diagnosis, schema migration, CDC ETL, query optimisation, streaming aggregation, recipe scaling,
security-audit haikus) and each became its own labelled child under it; iterative refinement took the melody,
marathon and symphony tasks; the producer/validator class took architecture recommendation and game balance. Each
child has an edge to its parent, its label on its concept, and the task's signature as its first representative
use — and the second pass converged on it by label with the reranker calling the parent `covered` (17 of 21), the
D7b behaviour: once children exist, the label decides.

## The three that did not converge

| task | pass 1 → pass 2 | reading |
|---|---|---|
| recipe in iambic pentameter | first child `iambic-pentameter-recipe` → sibling `poetic-recipe-generation` | the reranker was shown the existing label and coined a variant anyway; the one label-reuse miss in 15 |
| flaky-test investigation | bundle → walk-down | no domain axis on either path (bundle and walk-down are not tree-class matches); retrieval variance between passes |
| junior debug strategy | fresh mint → fresh mint | novel both times at low fitness; a fresh mint carries a new identity each time (pre-existing behaviour, not this arc's) |

## Observations for the roadmap (not fixed here)

- Label reuse held on 14 of 15 repeats once the existing children were shown to the reranker (run A, before that
  wiring was live at the reranker, saw a label change on the controversial-choice task; run B did not).
- Identity is parent-scoped (D3): run A showed the same label minted under two different parents when retrieval
  moved the top match between passes; run B did not reproduce it. A label matching an existing child under
  another top-k candidate could steer the parent choice — an open question, not a defect.
- A fitness-axis reranker fallback on either axis withholds the whole assignment (EL-3) and is visible as a
  deferral; run A saw two, run B none. Live variance, measured.

Evidence: `pass-1/` and `pass-2/` per-instruction envelopes (classified/deferred event bodies with outcome,
provenance, verdict, label, children considered; the payload's domain map; the rendered block), `combined.edn`,
`SUMMARY.md`. Run A (`2026-09-16_052040-rs6-specialisation-sweep/FINDINGS.md`) is the same sweep before the
outcome was recorded on the event.
