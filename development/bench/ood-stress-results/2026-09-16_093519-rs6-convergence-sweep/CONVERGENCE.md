# RS-6 convergence sweep — do paraphrases of one domain converge on one child?

## Per group

| group | mints | landings | plain matches | distinct assigned ids | converged? | labels | coverage |
|---|---|---|---|---|---|---|---|
| contractcompare | 0 | 0 | 3 | 1 | true | [nil nil nil] | [:covered :covered :covered] |
| gamebalance | 2 | 0 | 0 | 3 | false | ["game-balance-simulation" nil "game-economy-tuning"] | [:uncovered nil :partial] |
| haikusecurity | 2 | 0 | 0 | 3 | false | ["security-vulnerability-haikus" "security-finding-haikus" nil] | [:partial :uncovered nil] |
| legal | 1 | 0 | 2 | 2 | false | [nil nil "legal-clause-review"] | [:covered :covered :partial] |
| marathon | 2 | 0 | 0 | 2 | false | [nil "half-marathon-training-plan" "marathon-training-plan"] | [nil :uncovered :uncovered] |
| queryopt | 2 | 0 | 0 | 2 | false | ["postgresql-query-optimization" nil "sql-query-optimization"] | [:partial nil :covered] |
| recipe | 3 | 0 | 0 | 3 | false | ["recipe-scaling-and-catering-prep" "bakery-production-scaling" "commercial-recipe-scaling"] | [:uncovered :uncovered :uncovered] |
| schemamigration | 3 | 0 | 0 | 3 | false | ["postgresql-schema-migration" "database-migration-planning" "dynamodb-schema-migration"] | [:uncovered :partial :covered] |

## Behavioral axis

| entries | matched | fresh-mint markers | distinct behaviors matched | tasks with no behavior |
|---|---|---|---|---|
| 56 | 56 | 0 | 14 | 2 |

## Per instruction

| slug | outcome | via | top-1 fitness | coverage | label | assigned | parent |
|---|---|---|---|---|---|---|---|
| 01-marathon-a |  |  | 0.90 |  |  |  |  |
| 02-recipe-a | :matched | :mint-domain-child | 0.95 | :uncovered | recipe-scaling-and-catering-prep | b8fa45a8-a3a9-3012-8cd2-a3813d5d76d3 | 0f80961b-9318-329c-91c3-8f77cda96c43 |
| 03-gamebalance-a | :matched | :mint-domain-child | 0.95 | :uncovered | game-balance-simulation | 1466189a-628f-3709-a3a2-f5639ac93c0d | acbcf0ca-6478-3a63-8b93-31b93bf0902c |
| 04-schemamigration-a | :matched | :mint-domain-child | 0.90 | :uncovered | postgresql-schema-migration | 121f6193-8e4e-3563-90ae-06f7d8a5db9f | fc82d884-b2c7-38a7-9783-ac2bba41237f |
| 05-haikusecurity-a | :matched | :mint-sibling-domain-child | 0.90 | :partial | security-vulnerability-haikus | 2751af28-4a9f-3d14-b4e2-b5ec7babaa56 | acbcf0ca-6478-3a63-8b93-31b93bf0902c |
| 06-queryopt-a | :matched | :mint-sibling-domain-child | 0.95 | :partial | postgresql-query-optimization | a97892f4-e9f9-314f-b8fb-8ee53b718f82 | fc82d884-b2c7-38a7-9783-ac2bba41237f |
| 07-legal-a | :matched | :match | 1.00 | :covered |  | 00000000-c1c1-4001-b004-d0c0a0a0a0a4 |  |
| 08-contractcompare-a | :matched | :match | 0.98 | :covered |  | 00000000-c1c1-4001-b003-d0c0a0a0a0a3 |  |
| 09-marathon-b | :matched | :mint-domain-child | 0.80 | :uncovered | half-marathon-training-plan | 7b19daa9-32e4-332f-9731-ecc2d3bfb5de | 2ead65e6-6373-38de-81c4-ef7bf067afc9 |
| 10-recipe-b | :matched | :mint-sibling-domain-child | 0.90 | :uncovered | bakery-production-scaling | 67c8a5ff-cf06-35e7-9664-02b7b1e779ba | 0f80961b-9318-329c-91c3-8f77cda96c43 |
| 11-gamebalance-b | :novel | :mint | 0.30 |  |  | 9b3858ee-aaa6-4a1e-b9a5-2819164001ef |  |
| 12-schemamigration-b | :matched | :mint-domain-child | 0.90 | :partial | database-migration-planning | 4d12814c-6cf7-3277-9d52-8c39878ea669 | aef71f08-76bf-373c-862a-1250ef23f450 |
| 13-haikusecurity-b | :matched | :mint-sibling-domain-child | 0.90 | :uncovered | security-finding-haikus | 0b1739ba-09ac-3ac0-bc83-3b09344c144e | 0f80961b-9318-329c-91c3-8f77cda96c43 |
| 14-queryopt-b |  |  |  |  |  |  |  |
| 15-legal-b | :matched | :match | 1.00 | :covered |  | 00000000-c1c1-4001-b004-d0c0a0a0a0a4 |  |
| 16-contractcompare-b | :matched | :match | 1.00 | :covered |  | 00000000-c1c1-4001-b003-d0c0a0a0a0a3 |  |
| 17-marathon-c | :matched | :mint-sibling-domain-child | 0.95 | :uncovered | marathon-training-plan | b4ebfb53-2915-3390-a74a-adb9f1321de3 | aef71f08-76bf-373c-862a-1250ef23f450 |
| 18-recipe-c | :matched | :mint-sibling-domain-child | 0.90 | :uncovered | commercial-recipe-scaling | ab16b9fb-2082-34fe-b803-0a2b93b36785 | 0f80961b-9318-329c-91c3-8f77cda96c43 |
| 19-gamebalance-c | :matched | :mint-sibling-domain-child | 0.95 | :partial | game-economy-tuning | 7c5f8bcf-486d-3c58-b6c9-4e1488e6d4aa | aef71f08-76bf-373c-862a-1250ef23f450 |
| 20-schemamigration-c | :matched | :mint-sibling-domain-child | 0.95 | :covered | dynamodb-schema-migration | f3e55008-c94c-3a4f-9f12-6b4a0513b10e | fc82d884-b2c7-38a7-9783-ac2bba41237f |
| 21-haikusecurity-c | :novel | :mint | 0.10 |  |  | 0bde72ea-5790-4eb0-bc5f-459d85354d04 |  |
| 22-queryopt-c | :matched | :mint-sibling-domain-child | 0.95 | :covered | sql-query-optimization | 6db2a133-024a-3a29-afce-0b2f384c80b7 | fc82d884-b2c7-38a7-9783-ac2bba41237f |
| 23-legal-c | :matched | :mint-domain-child | 0.90 | :partial | legal-clause-review | fd142fde-45b8-3f63-8cc7-b12a786eb813 | 153f1c69-e1d8-3592-8e62-391a7fab2dac |
| 24-contractcompare-c | :matched | :match | 0.99 | :covered |  | 00000000-c1c1-4001-b003-d0c0a0a0a0a3 |  |
