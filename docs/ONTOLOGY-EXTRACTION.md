# Ontology extraction

ORC uses the public interface and pure algorithms in `ai.obney.ontology`. The
component pins the standalone library to a Git commit. [Dependency declaration](../components/ontology-service/deps.edn)

The library owns ontology and concept identity, typed predicates, source versions,
contributions, resolution, atomic graph validation, revision snapshots and RDF.
ORC owns source loading and model extraction, embedding/index adapters,
classification, behavior composition, claims, evidence, Living Descriptions,
consolidation, harvesting and prompt injection. ORC declares its application
predicates as data and interprets composition in its own read view. Label and alias matches require the same entity type across sources.

ORC uses stable request keys to recover the library-generated IDs for its managed
tree-class and behavioral-subtree ontologies. Static vocabulary initialization
and domain-child birth use `ai.obney.ontology.grain/prepare-vocabulary`.
Grain commits the graph events, ORC birth record, and any researcher checkpoint
under their combined compare-and-append guards. A failed birth leaves no
partially visible child. Public point queries, hierarchy traversal and vector
search read the same stored concepts. ORC's semantic and hybrid search results
carry the stored ontology and concept IDs. Specifying a graph restricts traversal,
embedding results, and index hits to that graph.

Older ORC graph events and source registries use a different format.
Rebuild old extracted graphs from sources, or explicitly transform them into
current contributions. Queries for evolutionary sources and mappings now require
an ontology ID. Each accepted source contribution is atomic and durable; a
multi-source workflow may have committed earlier contributions when a later
source fails. Retry deduplication uses committed source versions.

Run the standalone suite with `clojure -M:test` from a checkout of the pinned
ontology revision. The ORC
contribution integration test exercises a real Grain store and substitutes only
the model extractor. The claims, hierarchy, and behavior composition regression
tests exercise the application side of the boundary.

After changing the Git pin, refresh the graph specification from the resolved
dependency before running Allium. [Spec verification](../specs/COVERAGE.md#ontology-extraction-ownership-18-september-2026)
