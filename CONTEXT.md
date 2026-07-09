# ORC

Behavior-tree workflow execution engine for LLM-powered workflows, built on Grain event sourcing. This glossary is the ubiquitous language — use these terms verbatim in code, tests, docs, and UI.

## Language

### Retrieval

**Signal**:
One independent ranked list of candidates feeding hybrid search. The three signals are graph, embedding, and ColBERT.
_Avoid_: source, backend, retriever

**Hybrid search**:
Retrieval that fuses the ranked lists of multiple signals into one ranking.
_Avoid_: multi-search, combined search

**ColBERT signal**:
The late-interaction signal in hybrid search. Names the matching method, not a particular model, index format, or runtime.
_Avoid_: RAGatouille, Python signal, PLAID

**Late interaction**:
Matching a query against a candidate token-by-token at query time, instead of comparing one pooled vector per text.
_Avoid_: token search, deep rerank

**MaxSim**:
The late-interaction score: each query token takes its best-matching candidate token, and those best matches are summed.
_Avoid_: similarity score (ambiguous with embedding cosine)

**Encoder checkpoint**:
The trained model that turns text into per-token embeddings for the ColBERT signal.
_Avoid_: the model (ambiguous with workflow LLMs)

**Index artifact**:
The on-disk representation of a ColBERT index. Derived data — always rebuildable from the events that describe its corpus; never the source of truth.
_Avoid_: index files, the .ragatouille directory

**Graceful degradation**:
Hybrid search's contract that an absent or failing ColBERT signal silently narrows the search to the remaining signals, never erroring.
_Avoid_: fallback mode
