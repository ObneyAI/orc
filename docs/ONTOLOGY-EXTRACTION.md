# Ontology extraction

The general graph library lives in the sibling `ontology-exp` project under
`ai.obney.ontology`. ORC depends on its public interface and pure algorithms.

The library owns ontology and concept identity, typed predicates, source versions,
contributions, resolution, atomic graph validation, revision snapshots and RDF.
ORC owns source loading and model extraction, embedding/index adapters,
classification, behavior composition, claims, evidence, Living Descriptions,
consolidation, harvesting and prompt injection. ORC declares its application
predicates as data and interprets composition in its own read view.

This change does not preserve old graph event formats or source-registry APIs.
Rebuild old extracted graphs from sources, or explicitly transform them into
current contributions. Queries for evolutionary sources and mappings now require
an ontology ID. Each accepted source contribution is atomic and durable; a
multi-source workflow may have committed earlier contributions when a later
source fails. Retry deduplication uses committed source versions.

Run the standalone suite with `clojure -M:test` in `ontology-exp`. The ORC
contribution integration test exercises a real Grain store and substitutes only
the model extractor. The claims, hierarchy, and behavior composition regression
tests exercise the application side of the boundary.

The sibling local dependency is intentional for this extraction checkout.
Publishing the library and selecting a release coordinate is a separate action.
