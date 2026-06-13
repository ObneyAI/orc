# Fixture: risk-analysis

**HITL-REVIEW-REQUIRED:** This expected graph is derived from
`development/bench/risk_analysis.clj` + the penalty / obligation clauses
surfaced in `development/bench/generalization-results/risk-analysis_*.edn`.
Human review needed to validate it represents the SEMANTIC ground truth
for the VAA RFP risk model — not just what the previous pipeline
produced. Edit `expected.ttl` to reflect intended ground truth, then
REMOVE this marker.

## Derivation provenance

- **Bench source**: `development/bench/risk_analysis.clj` (task definition:
  280K RFP, extract `:risks`, `:obligations`, `:penalties`).
- **Real-run artifact**: `development/bench/generalization-results/
  risk-analysis_2026-06-02_121715.edn` (latest captured run as of S16
  implementation). The `:outputs` surfaced specific penalty clauses
  (2%/month late interest, revenue-understatement audit penalty, force
  majeure labour exclusion, ILOC 5% security mechanism, 10km non-compete).
- **What we mirrored into `expected.ttl`**: five risk-bearing
  `skos:Concept`s — one per major penalty / restriction surfaced in the
  bench run. Plus one `owl:disjointWith` axiom — a penalty (cost-bearing
  for the Contractor) and a security mechanism (a guarantor for the VAA)
  are structurally distinct risk vectors.

## What this fixture tests

- Substrate CONCEPT extraction across structured risk-domain entities.
- S07 axiom round-trip with a real-domain disjointness pair.
- S15 CQ runner with 5 pass + 1 adversarial Layer-1-fail CQ.
- S13 evidence aggregation events.

## Substrate convention notes

- Disjointness axioms emit in CANONICAL endpoint order (URI-sorted,
  src < target). The substrate's `disjointness->turtle` deduplicates
  the symmetric pair to a single directed triple in that order.
  Expected.ttl reflects this convention.

## Known limitations of the AFK-derived graph

- Magnitudes (the "2%", "1%", "5%", "10km", "18mo" numeric facets) are
  in the rdfs:comment text but NOT modeled as S05 quantity-attributes
  — that's an HITL extension target.
- Relationships (e.g., "applies-to" linking a penalty to a defaulting
  party) are not extracted yet. HITL extension target.
- The bench's actual run surfaced ~12 distinct risk clauses; this
  AFK seed includes only 5 — chosen for clarity. HITL review should
  expand coverage.
