# RS-P1 — does the reranker answer the separated question? Findings

Probe: `development/src/rs_p1_coverage_probe.clj` (throwaway). The shipped reranker instruction with its output
contract widened to six keys (`domain_reasoning`, `domain_coverage`, `domain_label` beside the three shipped ones),
run over the 21-task OOD corpus twice in one JVM on the current tree, reranker output teed per call. Results:
`probe-results.edn`, run log `probe-run.log`.

## What held

- **Mechanics.** 225 of 225 candidate entries across both passes carried a valid discrete verdict and a label through
  the function-calling string payload. The parse is not the risk.
- **Sanity checks.** Both in-distribution tasks came back `covered` on both passes with stable labels
  (`legal-clause-review` twice; `saas-contract-comparison` / `contract-comparison`).
- **Reason-before-verdict** was honoured: every entry carried a `domain_reasoning` naming a representative use or a gap.

## What did not hold

- **`uncovered` is rare.** Pass 1: 12 covered / 7 partial / 2 uncovered; pass 2: 11 / 9 / 0 (+1 reranker fallback,
  `:uncertain`). On a corpus curated to be off-domain, the model called the top match `covered` for the marathon plan,
  the symphony arrangement, the streaming aggregation and the game balance. Its reasoning treats "a sequence of
  transformation passes" as a domain. The instruction's definition of `covered` ("a representative use or the content
  names this task's domain") let content-level shape language count as domain. `partial` is where the model puts most
  genuine domain drift (recipe scaling, refactoring, property tests, flaky-test diagnosis).
- **A mint rule of "uncovered only" would fire on 2 of 19 off-domain tasks.** "Partial or uncovered" fires on 11 of
  19 on at least one pass and 7 of 19 on both.
- **Verdicts flip between passes for 4 of 19** (defend-controversial-choice, schema-migration, optimize-slow-query,
  marathon-training): covered on one pass, partial on the other. A mint decision keyed on a single verdict is therefore
  non-deterministic for those tasks — the second occurrence could land on the parent leaf while the first minted a
  child — unless convergence does not depend on the verdict repeating.
- **Labels are semantically stable and lexically unstable.** Exact agreement 5 of 21; near-pairs everywhere
  (`jvm-oom-diagnosis` / `jvm-oom-diagnostics`, `sql-query-optimization` / `sql-performance-tuning`,
  `recipe-scaling-and-catering-workflow` / `culinary-recipe-scaling`). An identity derived from the raw label would
  scatter; the label must be canonicalised by a judged choice, not by string rules.

## Verdict on D4's assumption

Holds for the mechanism, fails for the calibration and for identity derivation from the raw label. Consequences to
decide (posed as Q7): mint on `partial` as well as `uncovered`; canonicalise the label by showing the reranker the
parent's existing children labels and asking it to reuse one when it fits (a judged choice, converging by
construction); make convergence independent of the verdict repeating by letting a matched leaf that has domain children
always consider them (walk-down into domain children regardless of the specificity gate), with EL-1b bundling as the
backstop. Also tighten `covered`'s definition to subject matter, material and output kind, and re-probe with sibling
labels supplied (RS-P1b) before RS-1 is briefed.

## Harness finding (for RS-6's weed)

The first attempt died on a corrupt ColBERT index in the fresh worktree: `embeddings.bin` byte length 4,379,136 against
metadata expecting 4,365,312 — two rebuilders (the runner's `maybe-rebuild!` and the seed mints' `force-rebuild!`)
writing the same index root during the first build. A 45 s settle after `runner/start!` avoided it. The index writer
has no single-writer guard; recorded, not fixed here.
