# RR-5 iteration-evidence bounds

## Question

What character limits can bound durable iteration reasoning and error excerpts
without guessing or truncating any checked-in evidence from real recursive
campaigns?

## Corpus and method

Two checked-in populations were measured by reading Clojure/EDN as data,
walking every map, and counting literal string values at `:reasoning` and
`:error`:

1. All 227 Clojure test files under `components/**/test/`.
2. The six banked live-verification EDN runs under
   `development/bench/c_loop_1_live_verify/` and
   `development/bench/gap3_loop_live_verify/`, plus
   `components/ontology/test/cc21b_evidence_window_corpus.edn`.

The first population checks breadth across synthetic contracts. The second is
the sizing anchor because it contains observed recursive-campaign evidence,
not hand-authored short assertions.

| Population | Field | N | p50 | p90 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| all component tests | reasoning | 176 | 13 | 35 | 52 | 97 | 108 |
| all component tests | error | 34 | 21 | 52 | 66 | 78 | 78 |
| banked live evidence | reasoning | 63 | 330 | 391 | 396 | 438 | 438 |
| banked live evidence | error | 5 | 25 | 175 | 175 | 175 | 175 |

The live reasoning sample ranged from 223 to 438 characters. The live error
sample ranged from 25 to 175 characters.

## Decision

- `iteration-reasoning-max-chars` = **438**
- `iteration-error-excerpt-max-chars` = **175**

These are the observed maxima of the checked-in live evidence, so the current
bank loses no text while future outliers are bounded. Truncation keeps the
exact prefix and never applies to generated code or emitted-tree structure.
The constants must be recalibrated from new banked evidence if later campaigns
show the current sample is unrepresentative; they are not enlarged merely to
silence a failing size assertion.

## Limitation

Only five banked live errors were available. The 175-character error limit is
therefore an explicit, measured small-sample anchor, not a claim about the
distribution of all provider and tool failures. RR-26's whole-spec measurement
pass remains responsible for rechecking the distribution across the completed
campaign system.
