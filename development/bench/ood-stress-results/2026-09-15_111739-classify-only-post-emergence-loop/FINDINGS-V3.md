# OOD Stress — Sweep 3 (post-emergence-loop, classify-only) Findings

Run on the `feature/r-inject-specialisation` branch (RR-1 … RR-33 landed, emergence loop EL-1a/1b/2/3, CV-1,
RR-1/2/3 reranker work, CC-20/23 all present). Same 21-task corpus, same classify-only mode as Sweep 1
(June 9). `classify-task` only; `classify-behaviors` was not captured (the corrected experiment from
FINDINGS-V2 is still open).

## Headline

The classify-only picture is unchanged by the emergence loop. Reading the three-state `:outcome` and
`:assigned-via` the classifier now records (the June summary flags predate them):

| outcome / route | count |
|---|---:|
| `:matched` via `:match`, top-1 fitness 0.85–1.00 | 20 |
| `:novel` via `:mint` | 1 (haiku-audit, fitness 0.30) |
| `:uncertain` | 0 |
| `:bundle` / `:walk-down` routes | 0 |

Every off-domain task except one is a confident structural match. The grounded domain rank (ADR 0016,
the reranker reading each candidate's `:avoid-when` plus the deterministic contrastive penalty) fired on
none of them: `:domain-penalty` is 0.0 on every candidate of every task except one 0.009. The reason is
visible in the seeds — the `:avoid-when` guards are written in terms of tree SHAPE ("the work is
per-chunk independent — use chunked-extraction instead", "the chain is 1 stage"), not domain, so a
marathon plan, a recipe scaling and a CDC pipeline all pass the ETL-pipeline class's guards.

## Then vs now (same slugs)

- haiku-audit: June `walk-down` 0.90 → now `:novel` / `:mint` (fitness 0.30). The one improvement, and
  it is the reranker itself judging "creative/literary transformation rather than risk/severity
  classification".
- marathon-training: June Scheduling 0.92 → now ETL pipeline (`fc82d884`) 0.85, reasoning "sequential
  transformation passes (calculate paces, schedule runs, add strength)".
- symphony-to-quartet: June `153f1c69` 0.95 → now Iterative Refinement (`aef71f08`) 0.95, "draft →
  critique → revise is ideal for music arranging".
- The rerank failure of June (code-003) is gone (0 rerank failures); code-003 now matches Iterative
  Refinement at 0.85.
- Three classes absorb 13 of the 20 matches: ETL pipeline (`fc82d884`, 5), Producer/validator
  (`acbcf0ca`, 5), Iterative Refinement (`aef71f08`, 4).
- The two sanity checks still match at 1.00.

## What this does and does not say

It says: the shape-broad corpus still absorbs domain-diverse tasks at high confidence, and the domain
guard cannot fire because no seed's guard names a domain. It does NOT say the runtime "force-mints" or
fabricates behaviors (it doesn't — detect-and-defer holds), nor what the model does with the off-domain
prepend, nor whether harvest later specialises. Those need the full-bench run and the behavioral axis.

Evidence: per-instruction envelopes in this directory (`:outcome`, `:assigned-via`, `:ranked-candidates`
with `:domain-penalty`, `:reasoning`).
