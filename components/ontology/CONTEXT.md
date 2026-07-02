# Ontology — Ubiquitous Language (glossary)

Glossary only — no implementation. Terms used verbatim in code, tests, and docs.

## Source & containers

- **Source** — one input dataset the pipeline ingests (a CSV file, a SQL database, a directory of Excel workbooks). Identified by a `{:type :path}` descriptor.
- **Container** — a single addressable table/sheet/file *within* a source: one `list-containers` entry. The uniform unit across CSV / SQL / Excel (one code path, no per-format branch). A CSV source has one container; a SQL database or Excel directory has many.
- **Meaningful container** — a container carrying real entity or measure data worth extracting into the graph. Contrast with the two noise kinds below. Container selection keeps meaningful containers and drops noise.
- **Bridge container** (a.k.a. junction / cross-reference) — a container that only PAIRS two non-primary entities (two code/ID columns, no measure of its own). Per the grain/reify rule this is an EDGE, never a node; as a whole container it is usually noise to skip.
- **Reference container** (a.k.a. lookup) — a small container that only DEFINES codes/labels or a taxonomy (a code→label dictionary). Usually noise to skip for entity extraction.
- **Container selection** — choosing which containers of a many-container source to extract, replacing the previous blind "first N by name". Survey-driven: profile each container's shape cheaply, drop noise, rank the remainder by relevance to the goal, bounded by a cap.

## Container shapes (structural, domain-agnostic)

- **Entity container** — one row per real-world entity, with the entity's identifying key(s) + attribute columns. Models to one node per row.
- **Long-form container** — names a measure/element in one column and its value in another (a key column + an element/label column + a value column; the same entity repeats across many rows, once per element). The element/label column is a LAYOUT artifact, not a second entity — its values are the keyed entity's own flat attributes.
- **Wide-stats container** — one subject per row carrying MANY measure columns. Also flat attributes on that subject (no reification) unless a single measure is co-qualified by two independent entities.

## Extraction

- **Aggregating transform** — an extraction transform that emits ONE draft per GROUP of rows (group-by an entity key, then roll the group up — e.g. rank a long-form container's elements by importance and keep the top-N as a flat array attribute), as opposed to the per-row transform that emits one draft per row. Model-authored; applied streamed/bounded so a large container never materializes whole.
- **Occurrence merge** — combining drafts of the SAME real-world entity that arrive from DIFFERENT containers (e.g. an occupation named in one container and rated in another, joined on its identifying key) into one node, unioning their attributes.
- **Meaningful multi-table extraction** — the capability of ingesting a many-container source correctly: select the meaningful containers (drop bridge/reference noise, rank by goal-relevance), aggregate long-form containers into flat attributes, and occurrence-merge per-entity drafts across containers into one node.
