# RS-6 usefulness benchmark — baseline vs first vs second occurrence

| task | arm | status | wall s | tokens (usage) | tokens (node-trace) | iterations | tree nodes | via | label | prepend chars | reasoning names label | reasoning mentions corpus | child claims | child consolidated count |
|---|---|---|---:|---:|---:|---:|---:|---|---|---:|---|---|---:|---:|
| domain-003-marathon-training | :baseline | :success | 41.0 | 35846 | 41624 | 3 | 8 |  |  |  | false | false |  |  |
| domain-003-marathon-training | :r-inject-1 | :success | 62.8 | 56608 | 65390 | 2 | 6 | :mint-domain-child | marathon-training-plan | 32110 | false | false | 2 | 0 |
| domain-003-marathon-training | :r-inject-2 | :failure | 97.0 | 138772 | 151009 | 0 | 0 | :land-on-domain-child | marathon-training-plan | 31863 | false | false | 4 | 0 |
| domain-002-recipe-scaling | :baseline | :success | 19.1 | 19450 | 20961 | 2 | 4 |  |  |  | false | false |  |  |
| domain-002-recipe-scaling | :r-inject-1 | :success | 43.1 | 43592 | 47530 | 2 | 7 | :mint-sibling-domain-child | catering-recipe-scaling | 23323 | false | false | 2 | 0 |
| domain-002-recipe-scaling | :r-inject-2 | :success | 34.9 | 40067 | 41410 | 2 | 4 | :land-on-domain-child | catering-recipe-scaling | 23293 | true | true | 3 | 0 |
| domain-001-game-balance | :baseline | :success | 23.4 | 30385 | 31351 | 3 | 5 |  |  |  | false | false |  |  |
| domain-001-game-balance | :r-inject-1 | :success | 47.3 | 38215 | 40763 | 2 | 5 | :mint-domain-child | game-balance-simulation | 18500 | true | false | 2 | 0 |
| domain-001-game-balance | :r-inject-2 | :success | 34.3 | 31907 | 31907 | 2 | 6 | :match |  | 14723 | false | true |  |  |
| legal-issue-detection | :baseline | :success | 29.8 | 21127 | 25328 | 2 | 3 |  |  |  | false | false |  |  |
| legal-issue-detection | :r-inject-1 | :success | 42.0 | 51521 | 56692 | 2 | 5 | :match |  | 31560 | false | true |  |  |
| legal-issue-detection | :r-inject-2 | :success | 51.1 | 101658 | 101658 | 5 | 7 | :match |  | 20795 | false | false |  |  |
