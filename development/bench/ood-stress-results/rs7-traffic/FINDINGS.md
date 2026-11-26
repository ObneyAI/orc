# RS-7 — does the domain-family work converge realistic traffic?

Corpus: 238 accepted tasks generated from 40 hand-written briefs (4 in-domain, 2 near-domain confounders, 34
off-domain), frozen at `corpus-sha256 7f90ac6a…f18b` (`development/bench/ood-corpus-traffic/REVIEW.md`). Ground truth is
the brief's group, never a model's label. Each arm classifies all 238 tasks twice (pass 2 runs on the tree pass 1
grew), then runs 30 end-to-end campaigns (15 groups, first and last variant). Classification passes ran on a
persistent Postgres store; the end-to-end stage in memory. Both arms share the corpus, the task order and the reindex
policy (every 25 tasks); `compare-runs` confirmed the match.

- **Old code:** the merge of PR #38 (`51caa798`), plus development-only harness files (`rs7/baseline-harness`).
- **New code:** the convergence arc through `ff814b44` (CV-A to CV-E, the retrieval and resume fixes, decisions C3'
  and C5').

## Classification

| | Old, pass 1 | Old, pass 2 | New, pass 1 | New, pass 2 |
|---|---:|---:|---:|---:|
| In-domain tasks on their expected seed | 0 of 24 | 0 of 24 | 17 of 24 | 16 of 24 |
| In-domain tasks that minted a family | 10 | 7 | 1 | 0 |
| Distinct families | 87 | 46 | 40 | 43 |
| Off-domain groups on exactly one family | 6 of 36 | 13 of 36 | 15 of 36 | 19 of 36 |
| Families shared across groups | 1 | 0 | 4 | 2 |
| Tasks in shared families | 3 | 0 | 29 | 9 |
| Deferrals | 8 | 4 | 3 | 6 |
| Mean tokens per classification | 14.4k | 12.5k | 29.5k | 25.3k |
| p95 classification latency | 21 s | 17 s | 33 s | 29 s |

Pass-to-pass: a task landed in the same place both times 62% of the time with the old code and 74% with the new; a
group's pass-2 tasks landed on its majority family 55% versus 74%.

What changed: in-domain tasks now reach their curated seeds (the old code never did, because retrieval starved the
reranker and families crowded seeds out), paraphrases converge on one family far more often, and the tree carries
about half as many families. What got worse: more tasks sit in families shared across groups (29 in pass 1, mostly
API migration guides with database schema migrations), and each classification costs about twice the tokens, from the
merge judge on every landing and wider retrieval.

## How the new code got here (every arm is kept)

1. `post_fix-run1-inverted-reuse-rule`: the reranker's label-reuse rule was inverted; 17 families spanned groups.
2. Targeted checks showed unjudged landings were the dominant false merge: decision C3' (judge every landing).
3. `post_fix2-families-in-shape-ranking`: families crowded seeds out of retrieval (2 of 24 in-domain on seed):
   decision C5' (families out of the shape ranking).
4. `post_fix3`: a covered seed was pulled into a family by its label, the orchestrator's spec error; fixed.
5. `post_fix4`: the arm reported above.

The old code's first run was discarded as well: the runner's 10 MB cache filled and every later task failed.

## End-to-end campaigns

30 campaigns per run (15 groups, first and last variant), in memory. Every failure in every clean run was the model
running out of iterations ("Max iterations reached without final!"), task difficulty rather than an engine error.

| Run | Code | Succeeded | Median injected guidance |
|---|---|---:|---:|
| Baseline arm | old | 17 of 30 | about 20k characters |
| Post-fix arm 4 | new | 12 of 30 | about 42k characters |
| A/B, five behaviours | new, plus render dedupe | 15 of 30 | 40k characters |
| A/B, one behaviour | new, plus render dedupe | 14 of 30 | 21k characters |

The same new code scored 12 and then 15 on the same 30 campaigns, so run-to-run variance at this sample size is
several campaigns; the old code's 17 sits within it. **There is no evidence that this arc changed campaign success.**

The guidance roughly doubled because fixing the reranker's starvation lets the designed five behaviours reach the
prepend (the old benchmark effectively showed one) and because a class's two axis rows rendered twice (fixed:
`cb5d8c69`). A controlled A/B on the same code isolated the behaviour count: paired by campaign, 11 succeeded both
ways, 4 only with five behaviours, 3 only with one. **Five behaviours cost about 50% more tokens per campaign (112k
against 75k) with no measured change in success.** That is a finding for R-Inject's behavioural cap, not a
change made here.

Runs set aside, kept on disk: the first A/B attempt (the machine lost its network; every capped campaign failed with
`ConnectException`), and a five-behaviour attempt killed by an external termination signal after 8 campaigns.

## Conclusion

The arc does what it set out to do on realistic traffic: in-domain tasks reach their curated seeds (0 to 17 of 24),
paraphrases converge on a family of their own far more often (off-domain groups on exactly one family 6 to 15 of 36
in pass 1, 13 to 19 in pass 2), the tree holds about half as many families, and a task lands in the same place across
passes more often (62% to 74%). The costs: about twice the tokens per classification, and more tasks in families shared
across groups (29 in pass 1, 9 in pass 2), led by API migration guides merging with database schema migrations.
Campaign success is unchanged within this benchmark's noise.
