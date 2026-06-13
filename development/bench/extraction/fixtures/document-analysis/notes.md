# Fixture: document-analysis

**HITL-REVIEW-REQUIRED:** This expected graph is derived from
`development/bench/document_analysis.clj` + the captured entities in
`development/bench/generalization-results/document-analysis_*.edn`.
Human review needed to validate it represents the SEMANTIC ground truth
for the Victoria Airport Authority RFP — not just what the previous
pipeline produced. Edit `expected.ttl` to reflect intended ground truth,
then REMOVE this marker.

## Derivation provenance

- **Bench source**: `development/bench/document_analysis.clj` (task
  definition: 280K RFP, extract `:summary`, `:key-dates`, `:entities`).
- **Real-run artifact**: `development/bench/generalization-results/
  document-analysis_2026-06-02_120336.edn` (latest captured run as of
  S16 implementation). The `:outputs` map surfaced entities including
  `Victoria Airport Authority`, `Contractor`, `Monies` (defined term),
  RFP-specific monetary thresholds, the contractor / VAA disjointness
  (parties to the contract).
- **What we mirrored into `expected.ttl`**: four `skos:Concept`s — the
  buyer (VictoriaAirportAuthority), the counter-party (Contractor), the
  subject of contract (ParkingManagementServices), and the defined-term
  concept (Monies). Plus one `owl:disjointWith` axiom (S07) — parties to
  a contract are distinct legal entities.

## What this fixture tests

- Substrate CONCEPT extraction across TTL ingest (S09).
- S07 axiom round-trip (disjointWith).
- S15 CQ runner with 4 pass + 1 adversarial unknown CQ.
- S13 evidence aggregation events (automatic per S17).
- S11 SHACL artifact attachment.

## Known limitations of the AFK-derived graph

- The expected graph is INTENTIONALLY small (4 concepts) so the
  high-recall G2 gate is feasible without first solving every
  RFP-extraction nuance. A HITL-reviewed expected graph will likely
  expand this to cover: monetary thresholds (interest rates,
  revenue-understatement penalty), key dates (issue date, response
  deadline), and the operational obligations the bench's real run
  surfaced under `:obligations` (S04 metadata + relationship edges).
- No relationships (subject/predicate/object triples) are exercised
  here yet — that's an HITL-extension target.
