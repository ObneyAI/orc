# OOD Stress Test Results

> **Honest curation disclosure.** This corpus is hand-curated by
> the project maintainer to exercise the R-Inject classifier on
> instructions that the baseline corpus DOES NOT cleanly cover.
> The selection has bias toward eliciting fresh-mint outcomes;
> the aggregate rates here are informative but should not be read
> as production traffic statistics. Each row is marked `[?]` in
> the audit columns so a HITL reviewer can grep for unaudited
> rows and fill them in after spot-checking the saved envelope.

## Aggregate rates

| metric | count | rate |
|---|---:|---:|
| direct-match | 12 | 57.1% |
| walk-down fired | 20 | 95.2% |
| fresh-mint | 1 | 4.8% |
| rerank-failure | 1 | 4.8% |
| **total** | 21 | — |

## Latency

- mean: 7217 ms
- median: 6365 ms
- p95: 8426 ms

## Per-instruction

| slug | outcome | confidence | parent-chain-depth | assigned-tree-id | should-mint? [?] | substantive? [?] | review-notes [?] |
|---|---|---:|---:|---|---|---|---|
| behavioral-fresh-mint-001-haiku-audit | walk-down | 0.85 | 0 | 153f1c69... | [?] | [?] | [?] |
| behavioral-fresh-mint-002-melody-encoding-pi | walk-down | 0.92 | 0 | 2ead65e6... | [?] | [?] | [?] |
| behavioral-fresh-mint-003-recipe-iambic-pentameter | walk-down | 0.85 | 0 | fc82d884... | [?] | [?] | [?] |
| code-001-refactor-auth-middleware | walk-down | 0.95 | 0 | fc82d884... | [?] | [?] | [?] |
| code-002-investigate-flaky-test | walk-down | 0.92 | 0 | fb026cba... | [?] | [?] | [?] |
| code-003-generate-property-tests | walk-down | 0.92 | 0 | 75676f28... | [?] | [?] | [?] |
| code-004-debug-memory-leak | walk-down | 0.95 | 0 | 00000000... | [?] | [?] | [?] |
| conv-001-vector-vs-sql-tradeoffs | walk-down | 0.95 | 0 | d41af303... | [?] | [?] | [?] |
| conv-002-architecture-recommendation | walk-down | 0.85 | 0 | fc82d884... | [?] | [?] | [?] |
| conv-003-junior-debug-strategy | walk-down | 0.92 | 0 | 5a08300e... | [?] | [?] | [?] |
| conv-004-defend-controversial-choice | walk-down | 0.95 | 0 | 153f1c69... | [?] | [?] | [?] |
| data-001-schema-migration | walk-down | 0.85 | 0 | fc82d884... | [?] | [?] | [?] |
| data-002-etl-cdc-to-dim | walk-down | 0.92 | 0 | d41af303... | [?] | [?] | [?] |
| data-003-optimize-slow-query | walk-down | 0.92 | 0 | fc82d884... | [?] | [?] | [?] |
| data-004-streaming-aggregation | rerank-fallback | 0.00 | 0 | 971ded84... | [?] | [?] | [?] |
| domain-001-game-balance | walk-down | 0.65 | 0 | 1c0f6cd8... | [?] | [?] | [?] |
| domain-002-recipe-scaling | walk-down | 0.70 | 0 | cda1f7c2... | [?] | [?] | [?] |
| domain-003-marathon-training | walk-down | 0.95 | 0 | 5a08300e... | [?] | [?] | [?] |
| domain-004-symphony-to-quartet | walk-down | 0.85 | 0 | 153f1c69... | [?] | [?] | [?] |
| extra-001-legal-issue-sanity | walk-down | 1.00 | 0 | 00000000... | [?] | [?] | [?] |
| extra-002-contract-comparison-sanity | walk-down | 1.00 | 0 | 00000000... | [?] | [?] | [?] |
