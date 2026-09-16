# RS-6 bounded full-bench observation — three off-domain tasks, end to end

Three corpus tasks (game balance, recipe scaling, marathon training) ran through the real bench runner with
auto-classify on: Phase 1 tree design with the live R-Inject prepend, Phase 2 execution, the campaign verdict, and
whatever the ontology processors did afterwards. Recorded as observation, not as a gate.

| task | status | duration | tokens | classification | child minted | verdict occurrence |
|---|---|---:|---:|---|---|---|
| game balance | success | 42.5 s | 56,947 | matched, first domain child under the seeded shape, verdict partial, label `game-balance-simulation` | yes | one, success, on the child |
| recipe scaling | success | 66.3 s | 103,541 | matched, first domain child under the ETL-pipeline shape, verdict partial, label `recipe-scaling-and-catering` | yes | one, success, on the child |
| marathon training | success | 51.9 s | 62,671 | matched, first domain child under the RECIPE-SCALING CHILD minted one task earlier, verdict partial, label `marathon-training-plan` | yes | one, success, on the child |

## What this shows

- The whole chain runs live on a real campaign: the wedge minted the child before the render, the prepend rendered
  the parent's entry plus the child line (the rendered block is in each observation file), the model designed and
  executed a tree, and the campaign's terminal verdict was recorded as an occurrence ON THE CHILD — the recurrence
  fact harvest counts. Each child now has its first evidence, as decision D5 states.
- No fitness-axis or domain-axis deferral on any of the three.

## Two observations for the roadmap (not fixed here)

- **A newborn child is retrievable immediately, as if curated.** The marathon task's top match was the
  recipe-scaling child minted one task earlier: its birth claim was indexed at the next rebuild, and the retrieval
  gate surfaces a class with zero occurrences (the band meant for curated seeds). The task then minted a domain
  child UNDER that newborn — a grandchild whose parent's only body is a one-line signature. Whether a newborn's
  birth claim should count as seed-band evidence, or whether a child should be surfaced only after its first
  occurrence, is a calibration question for the retrieval gate's zero band.
- **The harness read no claims for these sheets.** The birth claim exists (the deterministic RS-3 suite proves it
  from the store) but claim events are tagged by their target, not the sheet, so this harness's sheet-scoped read
  does not see them; a follow-up harness should read claims by the assigned child.

Evidence: `observation-<slug>.edn` (classified event, mint, occurrence, render candidates and block, generated
tree, outputs, usage), `observations.edn`.
