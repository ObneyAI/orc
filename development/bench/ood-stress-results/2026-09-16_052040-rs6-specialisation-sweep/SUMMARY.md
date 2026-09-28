# RS-6 specialisation sweep — two passes through the live wedge

## Aggregate

| metric | pass 1 | pass 2 |
|---|---|---|
| total | 21 | 21 |
| by outcome | {nil 21} | {nil 21} |
| by assigned-via | {:mint-domain-child 5, :mint 1, :mint-sibling-domain-child 10, :match 2, :bundle 1, :land-on-domain-child 1, nil 1} | {:land-on-domain-child 12, :mint-sibling-domain-child 3, nil 1, :bundle 2, :match 3} |
| by coverage | {:uncovered 3, nil 3, :partial 8, :covered 7} | {:covered 17, nil 3, :partial 1} |
| by deferral reason | {} | {} |
| June direct-match | 4 | 15 |
| June fresh-mint flag | 16 | 3 |
| June rerank-fallback | 0 | 1 |
| mean latency ms | 19759 | 16361 |

## Per instruction — pass 1

| slug | outcome | via | top-1 fitness | coverage | label | assigned | parent | deferral |
|---|---|---|---|---|---|---|---|---|
| behavioral-fresh-mint-001-haiku-audit |  | :mint-domain-child | 0.80 | :uncovered | security-finding-haikus | 76406cb7-a0ac-342e-a22f-00815ff80ea7 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| behavioral-fresh-mint-002-melody-encoding-pi |  | :mint | 0.40 |  |  | 78e1fe66-3f37-4644-9089-aa63a37ae5cd |  |  |
| behavioral-fresh-mint-003-recipe-iambic-pentameter |  | :mint-domain-child | 0.70 | :uncovered | creative-recipe-writing | 0a3783c7-d68f-384a-b05b-e917c1dbf595 | 2ead65e6-6373-38de-81c4-ef7bf067afc9 |  |
| code-001-refactor-auth-middleware |  | :mint-sibling-domain-child | 0.90 | :partial | software-refactoring-design | ac3a9d40-e3e5-345e-8ee2-befa78765cd4 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| code-002-investigate-flaky-test |  | :mint-domain-child | 0.90 | :uncovered | test-flakiness-root-cause-analysis | f4121ddb-a51a-3447-a7c3-31e4cfcad03b | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| code-003-generate-property-tests |  | :mint-domain-child | 0.90 | :partial | property-based-test-generation | adc8fc2b-4e2d-3c99-81fe-98c23304c146 | 0f80961b-9318-329c-91c3-8f77cda96c43 |  |
| code-004-debug-memory-leak |  | :mint-sibling-domain-child | 0.95 | :partial | jvm-oom-diagnosis | ccd55243-3f59-3068-bcb9-63ad88ad57b2 | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| conv-001-vector-vs-sql-tradeoffs |  | :match | 1.00 | :covered |  | d41af303-dcb2-3599-9198-0c006d1cc364 |  |  |
| conv-002-architecture-recommendation |  | :mint-sibling-domain-child | 0.95 | :covered | software-architecture-recommendation | 23af8d1d-44bc-3ff8-8e97-862b4ba6b1a9 | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| conv-003-junior-debug-strategy |  | :bundle | 0.60 |  |  | b377a44e-6d21-3e3a-a33f-7227f899e401 |  |  |
| conv-004-defend-controversial-choice |  | :land-on-domain-child | 0.95 | :covered | software-architecture-recommendation | 23af8d1d-44bc-3ff8-8e97-862b4ba6b1a9 | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| data-001-schema-migration |  | :mint-sibling-domain-child | 0.95 | :partial | postgres-database-migration | b18a48ef-a93d-361d-8508-368594e83cbc | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| data-002-etl-cdc-to-dim |  | :mint-sibling-domain-child | 1.00 | :covered | etl-pipeline-design | fde83193-f140-3bc2-ac45-183509379bae | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| data-003-optimize-slow-query |  | :mint-sibling-domain-child | 0.95 | :covered | sql-performance-optimization | 3ddbefaf-c68b-38c8-8f50-14a1bcdf1a23 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| data-004-streaming-aggregation |  | :mint-sibling-domain-child | 0.95 | :covered | streaming-etl-design | b62a26ab-49c4-3913-8155-a76b70272862 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| domain-001-game-balance |  | :mint-sibling-domain-child | 0.95 | :partial | game-balance-optimization | cba762da-332e-374b-911d-78432b009851 | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| domain-002-recipe-scaling |  | :mint-sibling-domain-child | 0.95 | :partial | recipe-scaling-and-costing | 5e9add28-ae04-31ba-9fa6-625cf989f21c | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| domain-003-marathon-training |  | :mint-domain-child | 0.95 | :partial | marathon-training-plan | b4ebfb53-2915-3390-a74a-adb9f1321de3 | aef71f08-76bf-373c-862a-1250ef23f450 |  |
| domain-004-symphony-to-quartet |  | :mint-sibling-domain-child | 0.95 | :partial | musical-arrangement-transcription | d302a6c0-3eb5-3575-8ee9-189cbeb9684c | aef71f08-76bf-373c-862a-1250ef23f450 |  |
| extra-001-legal-issue-sanity |  | :match | 1.00 | :covered |  | 00000000-c1c1-4001-b004-d0c0a0a0a0a4 |  |  |
| extra-002-contract-comparison-sanity |  |  | 1.00 |  |  |  |  |  |

## Pass 2 vs pass 1 — identity stability

| slug | pass-1 via | pass-2 via | identity stable? | pass-1 label | pass-2 label |
|---|---|---|---|---|---|
| behavioral-fresh-mint-001-haiku-audit | :mint-domain-child | :land-on-domain-child | true | security-finding-haikus | security-finding-haikus |
| behavioral-fresh-mint-002-melody-encoding-pi | :mint | :mint-sibling-domain-child | false |  | musical-melody-encoding |
| behavioral-fresh-mint-003-recipe-iambic-pentameter | :mint-domain-child |  | false | creative-recipe-writing |  |
| code-001-refactor-auth-middleware | :mint-sibling-domain-child | :land-on-domain-child | true | software-refactoring-design | software-refactoring-design |
| code-002-investigate-flaky-test | :mint-domain-child | :land-on-domain-child | true | test-flakiness-root-cause-analysis | test-flakiness-root-cause-analysis |
| code-003-generate-property-tests | :mint-domain-child | :bundle | false | property-based-test-generation |  |
| code-004-debug-memory-leak | :mint-sibling-domain-child | :mint-sibling-domain-child | false | jvm-oom-diagnosis | jvm-oom-diagnosis |
| conv-001-vector-vs-sql-tradeoffs | :match | :match | true |  |  |
| conv-002-architecture-recommendation | :mint-sibling-domain-child | :land-on-domain-child | true | software-architecture-recommendation | software-architecture-recommendation |
| conv-003-junior-debug-strategy | :bundle | :bundle | true |  |  |
| conv-004-defend-controversial-choice | :land-on-domain-child | :mint-sibling-domain-child | false | software-architecture-recommendation | software-architecture-memo |
| data-001-schema-migration | :mint-sibling-domain-child | :land-on-domain-child | true | postgres-database-migration | postgres-database-migration |
| data-002-etl-cdc-to-dim | :mint-sibling-domain-child | :land-on-domain-child | true | etl-pipeline-design | etl-pipeline-design |
| data-003-optimize-slow-query | :mint-sibling-domain-child | :land-on-domain-child | true | sql-performance-optimization | sql-performance-optimization |
| data-004-streaming-aggregation | :mint-sibling-domain-child | :land-on-domain-child | true | streaming-etl-design | streaming-etl-design |
| domain-001-game-balance | :mint-sibling-domain-child | :land-on-domain-child | true | game-balance-optimization | game-balance-optimization |
| domain-002-recipe-scaling | :mint-sibling-domain-child | :land-on-domain-child | true | recipe-scaling-and-costing | recipe-scaling-and-costing |
| domain-003-marathon-training | :mint-domain-child | :land-on-domain-child | true | marathon-training-plan | marathon-training-plan |
| domain-004-symphony-to-quartet | :mint-sibling-domain-child | :land-on-domain-child | true | musical-arrangement-transcription | musical-arrangement-transcription |
| extra-001-legal-issue-sanity | :match | :match | true |  |  |
| extra-002-contract-comparison-sanity |  | :match | false |  |  |

## Mints (pass 1)

| slug | parent | label | child | via |
|---|---|---|---|---|

