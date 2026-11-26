# RS-P3 — does a judged merge step converge paraphrases onto one family without merging different domains?

Probe: `development/src/rs_p3_family_merge_probe.clj`. The convergence sweep's 24 paraphrases (8 groups × 3),
replayed in order against a growing family set, two arms: `:rich` (nearest families by ColBERT rank of each
family's rich description — purpose from the verdict reasoning, birth shape, birth signature — then one discrete
question over those descriptions) and `:labels` (the same question shown only the families' labels). Ground truth
is the corpus group; 23 of 24 records usable (one deferred record carries no label). Model
`google/gemini-3-flash-preview`, `merge-candidate-count` 5, no similarity cutoff.

## Result (run 2, parser corrected)

| arm | same, correct | new, correct | cross-group false merge | new when a family existed | unknown | families per group | tokens |
|---|---:|---:|---:|---:|---:|---|---:|
| rich | 14 | 8 | 1 | 0 | 0 | 1 in all 8 groups | 35,828 |
| labels | 15 | 8 | 0 | 0 | 0 | 1 in all 8 groups | 17,518 |

Both arms converged every group onto exactly one family, where the runtime today converges none (0 landings /
24). The one rich-arm false merge: the second task of the run (recipe scaling) was shown a single candidate (the
marathon family) and merged into it, reasoning verbatim: "While the subject matter differs (cooking vs. running),
the output kind is the same: a structured, multi-component plan/protocol derived from specific input parameters."
The judge treated a shared output kind as sufficient; the instruction defined a family as shared subject matter AND
output kind but did not state the converse explicitly.

## Run 1 (parser defect — reported, not hidden)

The first run dropped 16 rich-arm verdicts: with function calling the provider returns the structured answer as a
map, the parser expected a JSON string, and every unparsed answer counted as `unknown` (`probe-results-run1-parser-bug.edn`).
Its labels arm, which was parsed, showed **3 cross-group false merges** (contract comparison merged into the legal
family twice; a schema migration merged into query optimisation) — merges that did not recur in run 2. So the
labels-only judge is live-variable between 0 and 3 false merges on 23 tasks, and two of its three misses merged an
in-domain task into another in-domain family.

## Verdict

The judged merge step holds: convergence 8 of 8 groups in both arms, one false merge in 46 judged decisions
across the corrected run. Proceed with CV-C on the rich arm, with two changes drawn from the misses:

1. The instruction must state the converse: a different subject matter is a new family even when the output kind
   matches, and a different output kind is a new family even when the subject matter matches; "same" requires both.
2. Covered-seed protection (C4) runs before the merge judge, so an in-domain task with its seed in the ranking never
   reaches the judge; the run-1 labels misses (contract comparison into legal) are the case that ordering prevents.

Cost: 1,558 tokens per judged decision (rich) vs 762 (labels); the rich context is what removed the cross-group
merges between runs, and it is the only arm that carries C2's substance. Unknown rate 0 in the corrected run.
