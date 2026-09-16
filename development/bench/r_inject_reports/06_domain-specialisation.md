# 06 — Domain specialisation: does the waterfall inform the tree?

The June suite asked whether the model uses the corpus when a task matches a seeded pattern outright. This run asks
the harder question the specialisation arc exists for: when a task is OFF the corpus's domain but ON its shape, and
the runtime mints a domain child under that shape, does the model see the details of the injected shape and
behaviors and use them to design a better tree than it would on its own — and does the second occurrence of the
same domain see what the first one accrued?

Three off-domain tasks from the OOD corpus (marathon training plan, catering recipe scaling, deckbuilder game
balance — no source documents; the instruction is the whole task) plus the June suite's legal-issue-detection as the
in-domain control, each run three ways on ONE store, in this order:

- **baseline** — auto-classify off: no classifier, no prepend;
- **first occurrence** — auto-classify on: the domain child is minted; the prepend renders the parent shape's full
  entry plus the child line;
- **second occurrence** — the same task again on the same store: lands on the child; whatever the child accrued from
  the first campaign is visible or not.

Models: main and sub `google/gemini-3-flash-preview`; reranker the engine default. Every claim below is in
`specialisation-results/2026-09-16_091814/<task>-<arm>.edn` (the runner's record, the rendered prepend verbatim, the
classified event, the generated tree, the model's iteration reasoning, the outputs, and the child's state after the
run).

## Headline

| task | arm | status | wall | tokens (Phase 1 + 2) | tree nodes | classification | child claims after |
|---|---|---|---:|---:|---:|---|---:|
| marathon training | baseline | ✓ | 41.0 s | 35,846 | 8 | — | — |
| marathon training | first | ✓ | 62.8 s | 56,608 | 6 | first child `marathon-training-plan` under ETL pipeline | 2 |
| marathon training | second | ✗ max iterations | 97.0 s | 138,772 | 0 | landed on the child | 4 |
| recipe scaling | baseline | ✓ | 19.1 s | 19,450 | 4 | — | — |
| recipe scaling | first | ✓ | 43.1 s | 43,592 | 7 | sibling child `catering-recipe-scaling` under ETL pipeline | 2 |
| recipe scaling | second | ✓ | 34.9 s | 40,067 | 4 | landed on the child | 3 |
| game balance | baseline | ✓ | 23.4 s | 30,385 | 5 | — | — |
| game balance | first | ✓ | 47.3 s | 38,215 | 5 | first child `game-balance-simulation` under sequential pipeline | 2 |
| game balance | second | ✓ | 34.3 s | 31,907 | 6 | plain match — the CHILD itself was top-1 at 1.00 | — |
| legal issue detection (control) | baseline | ✓ | 29.8 s | 21,127 | 3 | — | — |
| legal issue detection (control) | first | ✓ | 42.0 s | 51,521 | 5 | `Legal-issue-detection` 1.00, covered — no child | — |
| legal issue detection (control) | second | ✓ | 51.1 s | 101,658 | 7 | `Legal-issue-detection` 0.98, covered — no child | — |

Token cost of the prepend on these small tasks is +25% to +145% over baseline, in line with the June suite's
finding that the proportional overhead is largest on the smallest tasks. Node-trace totals (which include the
classifier's own reranker calls) are in the EDNs.

## What the model saw, and what it did with it

**The control is untouched.** Legal-issue detection matched its seed at 1.00 with a `covered` verdict, no child was
minted, and the prepend headers read exactly as the June suite's (`#### Top match — Legal-issue-detection`). The
first-occurrence tree adds typed output schemas and a separate synthesis stage over the baseline's single call, the
June pattern again.

**First occurrences: the parent shape is used, and named.** The marathon first-occurrence reasoning, produced before
the code: *"I am adopting the ETL (Extract, Transform, Load) pattern combined with Iterative Refinement
(draft-critique-revise) … Calibrate → Draft → Critique & Refine → Load: use a `:code` node to package the final
result … I am skipping the `map-each` pattern for individual weeks because the schedule requires global
consistency."* That is the parent's worked pattern (sequential stages, `:code` at the tail, declared intermediates)
applied to a domain the corpus had never seen, with a stated rejection of an alternative the prepend also offered.
The baseline marathon tree reached a similar shape on its own (eight nodes, typed schemas) — on this task the
prepend changed the argument more than the topology. The recipe first occurrence went from the baseline's single
scaling call to a scale → parallel (workflow, costing) → deterministic format tree with typed intermediates, the
parent's shape verbatim.

**Second occurrences: the child line is read.** The recipe second-occurrence reasoning: *"I will employ an ETL
pipeline sequence via `emit-tree!`, which aligns perfectly with the top corpus match already specialized for
'catering-recipe-scaling'."* The model saw the child line — the runtime's own assignment, by label — and treated the
parent's shape as the proven shape for this domain. Its tree was leaner than the first occurrence's (four nodes,
40 k tokens against 44 k) with the same output structure.

**What the second occurrence did NOT see: the child's own evidence.** After the first campaign each child carried,
beside its birth signature, a `strength` claim recording the worked tree that succeeded (the CV-2 enrichment);
after the second, two or three claims. None of that reached the model. The waterfall renders the parent's full
entry plus the child line until the child's body is consolidated (its evidence count is still zero), so the
child's accrued worked pattern stays invisible on the second, third and later occurrences until a consolidation
runs. The model is told it is working on the child; it is not shown what the child already learned. This is the
one clear usefulness gap in the run.

**The game-balance second occurrence exposed a rendering seam.** The child minted on the first occurrence became
retrievable at the next index rebuild and was itself the top-1 candidate at fitness 1.00, `covered`. The
classification landed on it as a plain match — correct — but the retrieval gate's recurrence band (one to two
occurrences: matchable, not surfaced) removed it from the displayed candidates, so the prepend showed the ETL
parent as the top match at 0.70 and no child line at all. The task was assigned to its child and the model was
never told. The child line should follow the assignment, not the provenance.

## The one failure

The marathon second occurrence ran five Phase 1 iterations without finalising and was recorded as a failure with
two weakness claims on the child — the loop recording a failure as evidence, as designed. Every iteration emitted a
tree whose `:code` node was rejected by the checkpointed executor: *"Durable emit-tree! code nodes require quoted
(fn ...) source; an already-evaluated closure cannot be recorded as durable source."* The first occurrence wrote the
same `:code` node inline and succeeded; the second built its function so that the executor received an evaluated
closure rather than source, and the error message did not lead the model to the fix in five tries. This is a
pre-existing executor constraint and a pre-existing recovery weakness, not the specialisation path; it costs
139 k tokens here and is the strongest argument in this run for a targeted repair hint on that rejection.

## What this run tells us

1. The model reads the injected shape and behaviors and names them as the reason for its design, on domains the
   corpus never covered, in the first occurrence — the arc's core claim holds live.
2. The child line is read and used on the second occurrence when the landing goes through the parent.
3. The child's own accrued evidence is not shown until consolidation, and a child that surfaces on its own inside
   the recurrence band loses its child line entirely. Both are render decisions, cheap to change: show the child's
   strengths when it has any, and derive the child line from the assigned class's parent edge rather than from the
   assignment's provenance.
4. One pre-existing failure mode (an unquoted `:code` closure rejected five times over) dominated the run's cost.

## Reproducing this report's data

Harness: `development/src/rs6_usefulness_bench.clj` (`run-bench!`), launched through the bench runner with the
index rebuilt from the seeded corpus. Results: `specialisation-results/2026-09-16_091814/` — `SUMMARY.md`,
`summary.edn`, one EDN per task and arm.
