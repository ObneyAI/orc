# Extraction Bench (S16 — G2 gate harness)

Drives the [S17 deterministic skeleton](../../../components/ontology/src/ai/obney/orc/ontology/core/deterministic_skeleton.clj)
against a corpus of fixture sources, then compares the resulting graph
against each fixture's hand-authored **expected graph** using the
[S09 URDNA2015 canonicalizer](../../../components/ontology/src/ai/obney/orc/ontology/core/ttl_canonicalize.clj).

This is a sibling of the existing [`development/bench/`](../README.md)
RLM generalization bench — that one tests RLM TREE adaptation;
this one tests SUBSTRATE graph extraction. They share infrastructure
ideas but are independent.

## What this bench is

A G2 hard gate. Every builder path (today: the S17 skeleton; future:
old sheets, S18 RLM discovery, variants) runs against the same fixture
corpus, the same expected graphs, the same comparison logic. The G2
gate is binary:

```clojure
(defn passes-G2? [result]
  (and (empty? (get-in result [:triple-diff :missing]))
       (>= (or (:cq-pass-rate result) 0) 0.8)))
```

**Recall is the load-bearing half** — every expected triple must be
produced. Extras are tolerated (the substrate emits broader
metadata than hand-authored expected graphs cover). The CQ pass-rate
half binds the gate to S15's CQ runner.

## Public API

| Fn | Returns | Purpose |
|-----|---------|---------|
| `(list-fixtures)` | sorted `[fixture-name ...]` | discover fixtures |
| `(load-fixture name)` | `{:source :spec :expected-ttl :notes}` | read one fixture's 4 files |
| `(run-fixture! name)` | result map (below) | drive the skeleton + compare |
| `(passes-G2? result)` | bool | the G2 gate |
| `(run-all!)` | `[results...]` + writes `extraction-RESULTS.md` | full sweep |

### Result shape

```clojure
{:fixture-name              "document-analysis"
 :status                    :pass             ; :pass | :triple-diff-found
                                              ; | :skeleton-failed
                                              ; | :fixture-incomplete
                                              ; | :diff-anomaly
 :triple-diff               {:missing #{} :extra #{...}}
 :cq-pass-rate              0.80              ; or nil (no spec / no CQs)
 :evidence-score-distribution {:total :scored :mean :min :max}
 :shacl-export              "<TTL...>"        ; or nil
 :expected-graph-status     :auto-derived     ; or :hitl-reviewed
 :timing                    {:total-ms 614
                             :stage-timings {:parse 3 :normalize 1 ...}}
 :skeleton-result           {<full S17 build! result>}}
```

## Running

From a Clojure REPL (with `:dev` alias):

```clojure
(require '[extraction.harness :as h])

;; One fixture
(h/run-fixture! "document-analysis")

;; The full corpus + write the report
(h/run-all!)

;; G2 gate check
(h/passes-G2? (h/run-fixture! "document-analysis"))
```

From the command line:

```bash
clj -M:dev -e '
(require (quote [extraction.harness :as h]))
(h/run-all!)'
```

The report lands at `development/bench/extraction/extraction-RESULTS.md`.

## Running the tests

```bash
clj -M:dev:test -e '
(require (quote [clojure.test :as t]))
(require (quote [extraction.harness-test]))
(t/run-tests (quote extraction.harness-test))'
```

The test suite verifies (8 deftests, 42 assertions):
- Fixture discovery + loading
- Happy-path `run-fixture!` produces `:pass`
- Triple-diff direction (recall gap → `:missing`, not `:extra`)
- URDNA2015 byte-equivalent expected.ttl still produces empty `:missing`
- G2 gate semantics (both halves required)
- HITL-marker reflects review state honestly
- `run-all!` produces a report with the documented shape

## Fixture layout

Each fixture is a directory under `fixtures/`:

```
fixtures/<name>/
├── source.ttl     — input source (TTL today; csv/json/sql adapter
│                    extension is an HITL-extension target)
├── spec.edn       — {:purpose ... :competency-questions [...]}
│                    per S14 ORSD spec
├── expected.ttl   — known-good graph the substrate must produce
├── notes.md       — derivation provenance + HITL-REVIEW-REQUIRED marker
```

## Three AFK-derived fixtures (current corpus)

| Fixture | Derived from | Bench source |
|---------|--------------|--------------|
| `document-analysis` | VAA RFP entities (VictoriaAirportAuthority, Contractor, etc.) | `development/bench/document_analysis.clj` |
| `risk-analysis` | RFP penalty/security/restriction clauses | `development/bench/risk_analysis.clj` |
| `legal-issue-detection` | Employment-agreement legal-concern issues | `development/bench/legal_issue_detection.clj` |

All three carry `HITL-REVIEW-REQUIRED` in their `notes.md`. See
[`HITL.md`](HITL.md) for the extension surface — how to add a new
fixture, how to validate an expected graph, and how to mark a fixture
HITL-reviewed once validated.

## What's AFK-built vs HITL

| AFK (this slice) | HITL (extension) |
|------------------|------------------|
| Harness fns (`run-fixture!`, `run-all!`, `passes-G2?`) | Validating each `expected.ttl` represents semantic ground truth |
| S09 canonicalizer reuse + triple-set diff | Authoring new fixtures from new source documents |
| Report generator + extraction-RESULTS.md | Extending fixture coverage (relationships, quantities, sequences) |
| 3 initial AFK-derived fixtures | Adapters for non-TTL source formats (CSV, JSON, SQL) |

## How the gate evolves

- **Today (S16)**: high-recall gate (`:missing #{}` required) + CQ pass-rate (>= 0.8). Extras tolerated.
- **Post-G2 tighten**: precision becomes a first-class half — extras flagged at a configurable threshold.
- **Pre-rebuild deprecation**: the gate also scores the OLD sheets path, producing a baseline the rebuilt path must beat (per PRD M8 / grill-session Q9).

## See also

- [`HITL.md`](HITL.md) — the extension surface
- S17 commit `6ddfd469` — the deterministic skeleton this harness drives
- S09 commit `9dec70ac` — the canonicalizer this harness compares with
- S15 commit `52a73a50` — the CQ runner driving the pass-rate half
- S13 commit `34ba2888` — the evidence aggregation surfaced in the report
- S11 commit `39e55fb8` — the SHACL TTL export captured per-fixture
- [`docs/build-timeline/issues/ontology-rebuild/S16-extraction-bench.md`](../../../docs/build-timeline/issues/ontology-rebuild/S16-extraction-bench.md) — the slice spec
