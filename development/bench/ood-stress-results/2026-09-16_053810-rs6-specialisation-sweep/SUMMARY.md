# RS-6 specialisation sweep — two passes through the live wedge

## Aggregate

| metric | pass 1 | pass 2 |
|---|---|---|
| total | 21 | 21 |
| by outcome | {:matched 19, :novel 2} | {:matched 20, :novel 1} |
| by assigned-via | {:mint-domain-child 5, :mint-sibling-domain-child 10, :bundle 1, :match 3, :mint 1, :land-on-domain-child 1} | {:land-on-domain-child 15, :mint-sibling-domain-child 1, :walk-down 1, :match 3, :mint 1} |
| by coverage | {:partial 9, :uncovered 4, nil 2, :covered 6} | {:covered 17, :uncovered 2, nil 2} |
| by deferral reason | {} | {} |
| June direct-match | 4 | 18 |
| June fresh-mint flag | 16 | 2 |
| June rerank-fallback | 0 | 0 |
| mean latency ms | 18010 | 18816 |

## Per instruction — pass 1

| slug | outcome | via | top-1 fitness | coverage | label | assigned | parent | deferral |
|---|---|---|---|---|---|---|---|---|
| behavioral-fresh-mint-001-haiku-audit | :matched | :mint-domain-child | 0.90 | :partial | security-audit-haikus | b10e360a-12c7-3f09-bb4e-855c7fb6fd95 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| behavioral-fresh-mint-002-melody-encoding-pi | :matched | :mint-domain-child | 0.85 | :uncovered | algorithmic-melody-generation | 85d9b29b-0b9d-3c98-a88c-1a153d00e8f9 | aef71f08-76bf-373c-862a-1250ef23f450 |  |
| behavioral-fresh-mint-003-recipe-iambic-pentameter | :matched | :mint-domain-child | 0.90 | :partial | iambic-pentameter-recipe | e2281910-609b-3df9-ab7a-a4a8e9d3dc83 | 2ead65e6-6373-38de-81c4-ef7bf067afc9 |  |
| code-001-refactor-auth-middleware | :matched | :mint-sibling-domain-child | 0.90 | :partial | backend-middleware-refactoring | 6e2729ea-a90c-3404-9ee9-1a552ec275e9 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| code-002-investigate-flaky-test | :novel | :bundle | 0.60 |  |  | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |  |
| code-003-generate-property-tests | :matched | :mint-domain-child | 0.90 | :uncovered | property-based-test-generation | adc8fc2b-4e2d-3c99-81fe-98c23304c146 | 0f80961b-9318-329c-91c3-8f77cda96c43 |  |
| code-004-debug-memory-leak | :matched | :mint-sibling-domain-child | 0.95 | :partial | jvm-service-diagnosis | 83d95a28-f21d-32c2-9276-eaffc1aad2fc | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| conv-001-vector-vs-sql-tradeoffs | :matched | :match | 0.95 | :covered |  | d41af303-dcb2-3599-9198-0c006d1cc364 |  |  |
| conv-002-architecture-recommendation | :matched | :mint-domain-child | 0.95 | :partial | software-architecture-recommendation | 23af8d1d-44bc-3ff8-8e97-862b4ba6b1a9 | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| conv-003-junior-debug-strategy | :novel | :mint | 0.30 |  |  | 6e2ef015-ff3c-4955-bf98-d8f6212cb10f |  |  |
| conv-004-defend-controversial-choice | :matched | :land-on-domain-child | 0.90 | :covered | software-architecture-recommendation | 23af8d1d-44bc-3ff8-8e97-862b4ba6b1a9 | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| data-001-schema-migration | :matched | :mint-sibling-domain-child | 0.92 | :partial | database-migration-runbook | e00ad5c1-ef58-3ae4-8c46-c2a399af9a64 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| data-002-etl-cdc-to-dim | :matched | :mint-sibling-domain-child | 0.95 | :covered | data-warehouse-etl-design | e8d2a6ae-002d-3309-9d75-01e518ff6947 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| data-003-optimize-slow-query | :matched | :mint-sibling-domain-child | 0.95 | :partial | sql-query-optimization | 6db2a133-024a-3a29-afce-0b2f384c80b7 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| data-004-streaming-aggregation | :matched | :mint-sibling-domain-child | 0.90 | :covered | streaming-etl-design | b62a26ab-49c4-3913-8155-a76b70272862 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| domain-001-game-balance | :matched | :mint-sibling-domain-child | 0.95 | :uncovered | game-balance-simulation | 1466189a-628f-3709-a3a2-f5639ac93c0d | acbcf0ca-6478-3a63-8b93-31b93bf0902c |  |
| domain-002-recipe-scaling | :matched | :mint-sibling-domain-child | 0.90 | :partial | recipe-scaling-and-logistics | f44659cf-2420-3e77-aad3-564d4735f516 | fc82d884-b2c7-38a7-9783-ac2bba41237f |  |
| domain-003-marathon-training | :matched | :mint-sibling-domain-child | 0.90 | :uncovered | marathon-training-plan | b4ebfb53-2915-3390-a74a-adb9f1321de3 | aef71f08-76bf-373c-862a-1250ef23f450 |  |
| domain-004-symphony-to-quartet | :matched | :mint-sibling-domain-child | 0.95 | :partial | musical-arrangement-and-transcription | dc7146bd-69e5-3aee-9c9c-3a694861c72b | aef71f08-76bf-373c-862a-1250ef23f450 |  |
| extra-001-legal-issue-sanity | :matched | :match | 1.00 | :covered |  | 00000000-c1c1-4001-b004-d0c0a0a0a0a4 |  |  |
| extra-002-contract-comparison-sanity | :matched | :match | 1.00 | :covered |  | 00000000-c1c1-4001-b003-d0c0a0a0a0a3 |  |  |

## Pass 2 vs pass 1 — identity stability

| slug | pass-1 via | pass-2 via | identity stable? | pass-1 label | pass-2 label |
|---|---|---|---|---|---|
| behavioral-fresh-mint-001-haiku-audit | :mint-domain-child | :land-on-domain-child | true | security-audit-haikus | security-audit-haikus |
| behavioral-fresh-mint-002-melody-encoding-pi | :mint-domain-child | :land-on-domain-child | true | algorithmic-melody-generation | algorithmic-melody-generation |
| behavioral-fresh-mint-003-recipe-iambic-pentameter | :mint-domain-child | :mint-sibling-domain-child | false | iambic-pentameter-recipe | poetic-recipe-generation |
| code-001-refactor-auth-middleware | :mint-sibling-domain-child | :land-on-domain-child | true | backend-middleware-refactoring | backend-middleware-refactoring |
| code-002-investigate-flaky-test | :bundle | :walk-down | false |  |  |
| code-003-generate-property-tests | :mint-domain-child | :land-on-domain-child | true | property-based-test-generation | property-based-test-generation |
| code-004-debug-memory-leak | :mint-sibling-domain-child | :land-on-domain-child | true | jvm-service-diagnosis | jvm-service-diagnosis |
| conv-001-vector-vs-sql-tradeoffs | :match | :match | true |  |  |
| conv-002-architecture-recommendation | :mint-domain-child | :land-on-domain-child | true | software-architecture-recommendation | software-architecture-recommendation |
| conv-003-junior-debug-strategy | :mint | :mint | false |  |  |
| conv-004-defend-controversial-choice | :land-on-domain-child | :land-on-domain-child | true | software-architecture-recommendation | software-architecture-recommendation |
| data-001-schema-migration | :mint-sibling-domain-child | :land-on-domain-child | true | database-migration-runbook | database-migration-runbook |
| data-002-etl-cdc-to-dim | :mint-sibling-domain-child | :land-on-domain-child | true | data-warehouse-etl-design | data-warehouse-etl-design |
| data-003-optimize-slow-query | :mint-sibling-domain-child | :land-on-domain-child | true | sql-query-optimization | sql-query-optimization |
| data-004-streaming-aggregation | :mint-sibling-domain-child | :land-on-domain-child | true | streaming-etl-design | streaming-etl-design |
| domain-001-game-balance | :mint-sibling-domain-child | :land-on-domain-child | true | game-balance-simulation | game-balance-simulation |
| domain-002-recipe-scaling | :mint-sibling-domain-child | :land-on-domain-child | true | recipe-scaling-and-logistics | recipe-scaling-and-logistics |
| domain-003-marathon-training | :mint-sibling-domain-child | :land-on-domain-child | true | marathon-training-plan | marathon-training-plan |
| domain-004-symphony-to-quartet | :mint-sibling-domain-child | :land-on-domain-child | true | musical-arrangement-and-transcription | musical-arrangement-and-transcription |
| extra-001-legal-issue-sanity | :match | :match | true |  |  |
| extra-002-contract-comparison-sanity | :match | :match | true |  |  |

## Mints (pass 1)

| slug | parent | label | child | via |
|---|---|---|---|---|
| behavioral-fresh-mint-001-haiku-audit | fc82d884-b2c7-38a7-9783-ac2bba41237f | security-audit-haikus | b10e360a-12c7-3f09-bb4e-855c7fb6fd95 | :mint-domain-child |
| behavioral-fresh-mint-002-melody-encoding-pi | aef71f08-76bf-373c-862a-1250ef23f450 | algorithmic-melody-generation | 85d9b29b-0b9d-3c98-a88c-1a153d00e8f9 | :mint-domain-child |
| behavioral-fresh-mint-003-recipe-iambic-pentameter | 2ead65e6-6373-38de-81c4-ef7bf067afc9 | iambic-pentameter-recipe | e2281910-609b-3df9-ab7a-a4a8e9d3dc83 | :mint-domain-child |
| code-001-refactor-auth-middleware | fc82d884-b2c7-38a7-9783-ac2bba41237f | backend-middleware-refactoring | 6e2729ea-a90c-3404-9ee9-1a552ec275e9 | :mint-sibling-domain-child |
| code-003-generate-property-tests | 0f80961b-9318-329c-91c3-8f77cda96c43 | property-based-test-generation | adc8fc2b-4e2d-3c99-81fe-98c23304c146 | :mint-domain-child |
| code-004-debug-memory-leak | fc82d884-b2c7-38a7-9783-ac2bba41237f | jvm-service-diagnosis | 83d95a28-f21d-32c2-9276-eaffc1aad2fc | :mint-sibling-domain-child |
| conv-002-architecture-recommendation | acbcf0ca-6478-3a63-8b93-31b93bf0902c | software-architecture-recommendation | 23af8d1d-44bc-3ff8-8e97-862b4ba6b1a9 | :mint-domain-child |
| data-001-schema-migration | fc82d884-b2c7-38a7-9783-ac2bba41237f | database-migration-runbook | e00ad5c1-ef58-3ae4-8c46-c2a399af9a64 | :mint-sibling-domain-child |
| data-002-etl-cdc-to-dim | fc82d884-b2c7-38a7-9783-ac2bba41237f | data-warehouse-etl-design | e8d2a6ae-002d-3309-9d75-01e518ff6947 | :mint-sibling-domain-child |
| data-003-optimize-slow-query | fc82d884-b2c7-38a7-9783-ac2bba41237f | sql-query-optimization | 6db2a133-024a-3a29-afce-0b2f384c80b7 | :mint-sibling-domain-child |
| data-004-streaming-aggregation | fc82d884-b2c7-38a7-9783-ac2bba41237f | streaming-etl-design | b62a26ab-49c4-3913-8155-a76b70272862 | :mint-sibling-domain-child |
| domain-001-game-balance | acbcf0ca-6478-3a63-8b93-31b93bf0902c | game-balance-simulation | 1466189a-628f-3709-a3a2-f5639ac93c0d | :mint-sibling-domain-child |
| domain-002-recipe-scaling | fc82d884-b2c7-38a7-9783-ac2bba41237f | recipe-scaling-and-logistics | f44659cf-2420-3e77-aad3-564d4735f516 | :mint-sibling-domain-child |
| domain-003-marathon-training | aef71f08-76bf-373c-862a-1250ef23f450 | marathon-training-plan | b4ebfb53-2915-3390-a74a-adb9f1321de3 | :mint-sibling-domain-child |
| domain-004-symphony-to-quartet | aef71f08-76bf-373c-862a-1250ef23f450 | musical-arrangement-and-transcription | dc7146bd-69e5-3aee-9c9c-3a694861c72b | :mint-sibling-domain-child |
