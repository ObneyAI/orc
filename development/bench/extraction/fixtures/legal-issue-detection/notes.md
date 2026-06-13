# Fixture: legal-issue-detection

**HITL-REVIEW-REQUIRED:** This expected graph is derived from
`development/bench/legal_issue_detection.clj` + the issue list captured
in `development/bench/generalization-results/legal-issue-detection_2026-06-09_172808.edn`.
Human review needed to validate it represents the SEMANTIC ground truth
for the employment agreement issue set — not just what the previous
pipeline produced. Edit `expected.ttl` to reflect intended ground truth,
then REMOVE this marker.

## Derivation provenance

- **Bench source**: `development/bench/legal_issue_detection.clj` (task
  definition: 7K employment agreement, extract `:issues`, `:ambiguities`,
  `:missing`, `:recommendations`).
- **Real-run artifact**: `development/bench/generalization-results/
  legal-issue-detection_2026-06-09_172808.edn`. The `:outputs` `:issues`
  field surfaced 7 distinct legal concerns; this fixture mirrors a
  representative subset of 5 into substrate concepts.
- **What we mirrored into `expected.ttl`**: five `skos:Concept`s — one
  per surfaced issue. Plus one `owl:disjointWith` axiom — a discretionary
  clause and a restrictive clause are structurally distinct legal risk
  vectors.

## What this fixture tests

- Substrate CONCEPT extraction with sequence-shaped output (5 issues
  → 5 concepts).
- S07 axiom round-trip on small fixture.
- S15 CQ runner with 5 pass + 1 adversarial Layer-1-fail CQ (the
  Severance Provision the source omits — matching the bench run's
  surfaced "missing protections").
- Small-doc pattern: the bench RLM chose "no tree" for this task;
  the substrate skeleton runs uniformly regardless.

## Substrate convention notes

- Disjointness axioms emit in CANONICAL endpoint order (URI-sorted,
  src < target). See `disjointness->turtle` in
  `components/ontology/src/ai/obney/orc/ontology/core/serialization.clj`.
  Expected.ttl reflects this convention.

## Known limitations of the AFK-derived graph

- Citations (verbatim source-text linkage per issue) are mentioned in
  rdfs:comment text but not modeled as relationships — HITL extension
  target (would surface as relationship edges from each issue concept
  to a quote/clause node).
- Severity (issue magnitude) and area-of-law (the categorical attribute
  the bench prompt requested) are not extracted — HITL extension
  target.
- The bench flagged 7 issues; this seed includes only 5 for clarity.
  HITL review should consider expanding coverage to all 7.
