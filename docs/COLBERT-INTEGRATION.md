# ColBERT Integration

> **Reference Document** - The pure-JVM ColBERT signal for enhanced retrieval.
>
> Decision record: [ADR 0002 — Pure-JVM ColBERT signal](adr/0002-pure-jvm-colbert-signal.md). Related: [COMPONENT-MAP.md](COMPONENT-MAP.md) (Layer 5), `components/colbert/`

## Start here: you're already retrieving from the ontology

You're already retrieving from the ontology with semantic search. Every `hybrid-search` call against your concept graph fuses two signals — graph BFS (spreading activation through related concepts) and DJL embeddings (single-vector semantic similarity) — entirely in the JVM. **For most corpora, the built-in DJL embeddings are plenty.**

But as your corpus grows large, or your queries get subtle — long technical documents, near-synonyms, phrasing that doesn't line up with the stored text — single-vector embeddings start missing relevant results. A whole passage gets squashed into one 384-dimensional vector, and the nuance that would have matched your query gets averaged away.

**The ColBERT signal adds token-level late-interaction matching as a third retrieval signal** — it compares your query token-by-token against each candidate, then fuses with graph + embeddings via the same RRF the ontology already uses. It's a drop-in upgrade: the *same* `hybrid-search` call, one more signal in the set. And like the rest of ORC, it runs entirely on the JVM — no Python environment, no subprocess, no extra setup beyond adding the component to your classpath.

### When you DON'T need ColBERT

Keep it honest — the 2-signal (graph + embedding) setup is the right default and is enough when:

- Your corpus is small-to-moderate and your concepts are well-separated.
- Queries closely resemble the stored concept text (label/description matches work).
- You want the leanest classpath — graph + DJL embeddings need no extra component.

If that's you, do nothing. You're already getting hybrid retrieval.

### When you DO want ColBERT

Reach for the third signal when:

- **Your corpus grows large** — more candidates means more ways a single averaged vector blurs the distinction between near-matches.
- **Your queries are subtle** — long passages, technical vocabulary, paraphrases, or multi-aspect questions where *which tokens* match matters, not just overall topical similarity.

In those cases late-interaction token-level matching measurably improves precision.

### The one-line change

You don't learn a new API. `hybrid-search` already accepts a `:signals` set, defaulting to all three. To go from 2 signals to 3, **add `:colbert` to the `:signals` set** (and point it at a ColBERT index):

```clojure
;; Before — 2 signals (graph + embedding)
(ontology/hybrid-search ctx
  {:query-text "arbitration clause"
   :signals    #{:graph :embedding}
   :ontology-id my-ontology-id
   :limit 10})

;; After — 3 signals (add :colbert, point at an index)
(ontology/hybrid-search ctx
  {:query-text       "arbitration clause"
   :signals          #{:graph :embedding :colbert}   ;; <- the one change
   :colbert-index-id my-index-id
   :ontology-id      my-ontology-id
   :limit 10})
```

Same call shape, same RRF fusion, same result map — just one more ranked list folded in.

### The one cost: a component on the classpath (and a one-time model download)

Add the `colbert` component (via the `orc-colbert` package, alongside `orc-ontology` — see [PACKAGES.md](PACKAGES.md)). On first use the encoder checkpoint (~133 MB) downloads from HuggingFace into a local cache; after that everything is offline. That's the whole price of admission. (Graph + embeddings never touch it.)

### Graceful degradation: it just falls back

You don't have to guard your code. If the `colbert` component isn't on the classpath, retrieval automatically falls back to 2 signals — **no errors, no exceptions.** `hybrid-search` resolves ColBERT dynamically at call time via `(find-ns 'ai.obney.orc.colbert.interface)`; when it's absent that resolves to `nil`, the ColBERT signal is simply omitted from the RRF fusion, and graph + embeddings carry the search. The same contract covers a failing signal: an unreadable index artifact throws loudly at the colbert layer, and `hybrid-search` degrades to the remaining signals rather than erroring. So you can ship `:signals #{:graph :embedding :colbert}` everywhere and let the environment decide whether the third signal participates.

---

> **ColBERT is an optional third retrieval signal (Layer 5 in [COMPONENT-MAP.md](COMPONENT-MAP.md)).** ORC's ontology component works fully without it — graph BFS + DJL embeddings give you a 2-signal hybrid search. Add ColBERT when you need late-interaction token-level matching for larger corpora.
>
> **Pure JVM.** The ColBERT signal runs the encoder checkpoint natively on DJL OnnxRuntime — no Python environment, no subprocess. The only external touch is a one-time model download into a local cache (overridable for air-gapped use).
>
> **Graceful degradation.** When the `colbert` component is absent from the classpath, `hybrid-search` automatically runs on 2 signals (graph + embedding). No exception thrown — source: `retrieval.clj` dynamically resolves ColBERT via `(find-ns 'ai.obney.orc.colbert.interface)`, returning `nil` when absent.

## Overview

This document describes the ColBERT signal in the ORC retrieval stack. ColBERT provides **late interaction** — matching a query against a candidate token-by-token at query time, instead of comparing one pooled vector per text — which outperforms single-vector embeddings for complex queries.

### What This Integration Provides

| Capability | Description | Use Case |
|------------|-------------|----------|
| **Late-Interaction Retrieval** | Token-level matching (not single-vector) | Better semantic matching than MiniLM |
| **Three-Signal Hybrid Search** | Graph BFS + MiniLM + ColBERT via RRF | Comprehensive retrieval |
| **Reranking** | In-memory rerank without index | Candidate selection; the classifier's domain penalty |
| **Batch Search** | Many queries, one index load | Whole-transcript hybrid search |

---

## Architecture: the JVM ColBERT signal

### Encoder checkpoint

The encoder checkpoint is `answerdotai/answerai-colbert-small-v1` (Apache-2.0, 96-dim per-token embeddings), running as fp32 ONNX on **DJL OnnxRuntime** with **DJL HuggingFace tokenizers** (both pinned 0.31.1, matching the ontology component's DJL). Queries are MASK-expanded to `IndexConfiguration.maximum_query_tokens` — **configuration** since CC-17, shipped default **464**, override with `-Dcolbert.query.max-tokens` or per index; the checkpoint's own `query_maxlen` 32 truncated 100% of this system's real queries (see `doc/build-timeline/evidence/cc17`). Documents truncate at `doc_maxlen` 300. Every token row is unit-normalized inside the ONNX graph.

### Exact MaxSim scoring

**MaxSim** is the late-interaction score: each query token takes its best-matching candidate token, and those best matches are summed. ORC scores with *exact* brute-force MaxSim over every passage — no approximate index structure — with the reference implementation's punctuation semantics preserved exactly (punctuation-token similarities are zeroed, not dropped, so they contribute 0 rather than shifting the sum). At ontology-descriptions scale (tens to hundreds of documents), exact MaxSim is milliseconds per query and strictly higher fidelity than approximate indexing.

### The index artifact

An **index artifact** is the on-disk representation of a ColBERT index: a directory holding a versioned `index-meta.json` (format marker, checkpoint id, passage table, per-document metadata) and `embeddings.bin` (float32 little-endian token embeddings). It is **derived data** — always rebuildable from the `:colbert/index-created` events that carry the full source corpus; never the source of truth.

- Default index root: `.orc-colbert-indexes/` relative to the working directory (gitignored).
- Override: `-Dcolbert.index.root=/abs/path`.
- Loaded artifacts are cached in memory per canonical path, so batch search loads the artifact once for the whole batch.
- A directory without the format marker (including any legacy Python-era PLAID layout) fails **loudly** with a precise `:colbert-index-artifact-unreadable` error naming the path and the rebuild remedy; `hybrid-search` degrades to 2 signals and the ontology's reindex processor rebuilds the artifact in the current format.

### Model resolution: auto-download, cache, override

The encoder checkpoint resolves in this order:

1. `-Dcolbert.model.path=/abs/dir` — a directory containing `model.onnx`, `tokenizer.json`, and the config files. Skips the network entirely (air-gapped or pinned-checkout use).
2. The local cache `~/.cache/orc/colbert/answerai-colbert-small-v1/`, when it already holds every artifact at the expected byte size.
3. Download the missing artifacts (~133 MB total, dominated by `model.onnx`) from the HuggingFace CDN into the cache, each verified by byte size.

**Fresh environments pay the download once** — including the first test run on a fresh machine; after that, resolution is fully offline. There is nothing to install: no interpreter, no virtualenv, no setup script.

### Parity provenance

The JVM implementation is numerically verified against the reference Python ColBERT implementation on the same checkpoint: identical tokenization on every parity document and query, and a max rerank score delta of ~6e-6 (mean ~2e-6) over an 840-pair corpus — inside fp32 accumulation-order noise. Index-backed `search` is bit-identical to index-free `rerank` on the same corpus.

### Three-Signal Hybrid Search

```
                       Query
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
┌─────────────┐  ┌─────────────┐  ┌──────────────┐
│ Graph BFS   │  │ MiniLM      │  │ ColBERT      │
│ (spreading  │  │ (384-dim    │  │ (JVM ONNX    │
│ activation) │  │ sentence)   │  │ exact MaxSim)│
└──────┬──────┘  └──────┬──────┘  └──────┬───────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
               ┌────────────────┐
               │ RRF Fusion     │
               │ (k=60, weights)│
               └────────────────┘
                        │
                        ▼
                 Ranked Results
```

ColBERT is a **third signal** in the existing RRF hybrid search:
- **Graph BFS** - Spreading activation through ontology concepts
- **MiniLM** - 384-dim sentence embeddings (existing DJL integration)
- **ColBERT** - Token-level late interaction (JVM ONNX, exact MaxSim)

The existing `compute-rrf-scores` function handles fusion without modification.

### 2-signal vs 3-signal

> **Consumers must load the colbert interface eagerly.** The ontology component
> resolves ColBERT *lazily* (it never requires the colbert component itself), and
> its resolution uses `find-ns` — which sees only namespaces something else has
> already loaded. A consumer that issues `:colbert/*` commands or wants the third
> signal must `(:require [ai.obney.orc.colbert.interface])` in a namespace that
> loads at startup: that one require registers the `:colbert/*` defcommands into
> the global command registry (before any context snapshots it) and makes
> `hybrid-search` see the signal. Without it, `:colbert/create-index` fails with
> "Unknown Command" and `hybrid-search` silently degrades to 2-signal.

By default `hybrid-search` enables all three signals. Pass `:signals #{:graph :embedding}` to run without ColBERT — no index required, no colbert component required:

```clojure
;; 2-signal (graph BFS + DJL embeddings — no ColBERT needed)
(ontology/hybrid-search ctx
  {:query-text "arbitration clause"
   :signals    #{:graph :embedding}
   :ontology-id my-ontology-id
   :limit 10})

;; 3-signal (graph BFS + DJL + ColBERT — requires the colbert component)
(ontology/hybrid-search ctx
  {:query-text      "arbitration clause"
   :signals         #{:graph :embedding :colbert}
   :colbert-index-id my-index-id
   :ontology-id      my-ontology-id
   :limit 10})
```

**When to add ColBERT:** when 2-signal retrieval is insufficient for corpus size and semantic complexity — late-interaction token-level matching provides measurably better precision for long documents and technical vocabulary.

**How graceful degradation works** (`retrieval.clj`):

```clojure
(defn- resolve-colbert-search-fn
  "Dynamically resolve the ColBERT search-for-rrf function if available.

   Returns the function or nil if ColBERT component is not loaded."
  []
  (try
    (when-let [ns (find-ns 'ai.obney.orc.colbert.interface)]  ;; nil when absent
      (ns-resolve ns 'search-for-rrf))
    (catch Exception _ nil)))
```

If the `colbert` component is not on the classpath, `(find-ns 'ai.obney.orc.colbert.interface)` returns `nil`, the search function resolves to `nil`, and `hybrid-search` simply omits the ColBERT signal from the RRF fusion — no exception thrown.

---

## Component Structure

```
components/colbert/
├── deps.edn                          # DJL api + tokenizers + onnxruntime (0.31.1)
└── src/ai/obney/orc/colbert/
    ├── interface.clj                 # Public API
    ├── interface/schemas.clj         # Malli schemas + events
    └── core/
        ├── model_store.clj           # Checkpoint resolution: override → cache → download
        ├── encoder.clj               # DJL OnnxRuntime encoder + HF tokenizer
        ├── maxsim.clj                # Exact MaxSim scoring
        ├── corpus.clj                # Document splitting (token-count chunks + overlap)
        ├── index_store.clj           # Versioned index artifact read/write + cache
        ├── operations.clj            # create-index! / search / search-batch / rerank
        ├── read_models.clj           # Event → state projections
        ├── commands.clj              # Grain command handlers (event emission)
        └── queries.clj               # Grain query handlers
```

---

## Public API

```clojure
(ns ai.obney.orc.colbert.interface)

;; === Index Management ===

(defn create-index!
  "Create a ColBERT index from documents — writes the index artifact on disk.
   Emits :colbert/index-created (with the full source corpus, for rebuild)."
  [ctx {:keys [collection document-ids document-metadatas
               index-name split-documents? max-document-length]}])

(defn delete-index!
  "Soft-delete an index (source data remains in the event store)."
  [ctx index-id])

(defn list-indexes
  "List all indexes (optionally :include-deleted)."
  [ctx & {:keys [include-deleted]}])

(defn activate-index!
  "Atomically point a stable alias at a fully readable index. A rejected
   activation preserves the alias's prior index."
  [ctx alias index-id])

(defn get-active-index
  "Return the event-sourced alias pointer and its resolved index."
  [ctx alias])

(defn search-active
  "Resolve an alias once and search exactly that immutable snapshot."
  [ctx {:keys [alias query k]}])

;; === Search Operations ===

(defn search
  "Search indexed corpus using ColBERT late interaction.
   Returns: [{:content :score :rank :document_id :document_metadata}]"
  [ctx {:keys [query index-id k]}])

(defn search-batch
  "Batch-search many queries with the index artifact loaded once.
   Returns a vector of result-lists aligned to :queries."
  [ctx {:keys [queries index-id k]}])

(defn rerank
  "Rerank documents in-memory (no index required).
   Returns: [{:content :score :rank}]"
  [ctx {:keys [query documents k]}])

;; === Hybrid Search (RRF Integration) ===

(defn search-for-rrf
  "Search and return [{:uri :score}] for RRF fusion — the integration
   point ontology hybrid-search resolves dynamically."
  [ctx {:keys [query index-id k normalize? weight]}])

(defn search-for-rrf-batch
  "Batched search-for-rrf: one index load for all queries."
  [ctx {:keys [queries index-id k normalize? weight]}])

;; === Score Normalization ===

(defn normalize-colbert-score
  "Normalize a raw MaxSim score to [0,1] against the theoretical ceiling,
   which IS the configured maximum_query_tokens (that many unit vectors)."
  [score & {:keys [max-score method]}])

(defn maximum-query-tokens
  "The configured per-query row count / MaxSim ceiling."
  ([] ...) ([explicit] ...))

(defn query-truncation
  "Was this query cut, and by how much?
   {:query-token-count :maximum-query-tokens :query-truncated?
    :discarded-token-count}"
  [ctx {:keys [query maximum-query-tokens]}])

(defn normalize-result-scores
  "Normalize a result batch relative to its own max score."
  [results & {:keys [min-score-threshold]}])

;; === Lifecycle ===

(defn stop-bridge!
  "Release the JVM encoders (DJL model + tokenizer handles). The name is
   historical; call during application shutdown or bench teardown."
  [])
```

---

## Event Schemas

The index artifact on disk is a materialized view; the event store is the source of truth. `:colbert/index-created` carries the full source corpus, so a wiped disk is always recoverable.

```clojure
(defschemas events
  ;; Index lifecycle
  {:colbert/index-created
   [:map
    [:index-id :uuid]
    [:index-name :string]
    [:index-path :string]
    [:documents [:vector :string]]        ;; full source data — enables rebuild
    [:document-ids [:vector :string]]
    [:document-metadatas {:optional true} [:maybe [:vector [:map-of :keyword :any]]]]
    [:document-count :int]
    [:passage-count :int]
    [:model-name :string]
    [:config [:map ...]]
    [:created-at :string]]

   :colbert/index-deleted
   [:map
    [:index-id :uuid]
    [:deleted-at :string]]

   :colbert/index-activated
   [:map
    [:alias :string]
    [:index-id :uuid]
    [:previous-index-id {:optional true} :uuid]
    [:activated-at :string]]

   :colbert/index-activation-failed
   [:map
    [:alias :string]
    [:index-id :uuid]
    [:active-index-id {:optional true} :uuid]
    [:error :string]
    [:failed-at :string]]

   ;; Search audit
   :colbert/search-performed
   [:map
    [:search-id :uuid]
    [:index-id :uuid]
    [:query :string]
    [:k :int]
    [:result-count :int]
    [:latency-ms :int]
    [:top-score {:optional true} :double]
    [:performed-at :string]]

   :colbert/rerank-performed
   [:map
    [:rerank-id :uuid]
    [:query :string]
    [:input-count :int]
    [:output-count :int]
    [:latency-ms :int]
    [:top-score {:optional true} :double]
    [:performed-at :string]]})
```

---

## Usage Examples

### Stable production alias

Build a new immutable index, validate it, then switch consumers without a gap
or a partially visible artifact:

```clojure
(def built (colbert/create-index! ctx
             {:collection documents
              :document-ids document-ids
              :index-name "knowledge-2026-08-06"}))

(colbert/activate-index! ctx "knowledge-current" (:index-id built))

(colbert/search-active ctx
  {:alias "knowledge-current" :query "restart recovery" :k 10})
```

`search-active` resolves the alias once and searches one snapshot. If a later
activation targets a missing, deleted, or unreadable index, the command emits
`:colbert/index-activation-failed` and the previous pointer remains active.

### Basic Search

```clojure
(require '[ai.obney.orc.colbert.interface :as colbert])

;; Create index from documents (writes the index artifact,
;; emits :colbert/index-created)
(def result
  (colbert/create-index! ctx
    {:collection ["Document 1 text..." "Document 2 text..."]
     :document-ids ["doc1" "doc2"]
     :document-metadatas [{:source "wiki"} {:source "internal"}]
     :index-name "my-docs"
     :split-documents? true}))

;; Search
(def results
  (colbert/search ctx
    {:query "What is machine learning?"
     :index-id (:index-id result)
     :k 5}))
;; => [{:content "Machine learning is..." :score 30.87 :rank 1
;;      :document_id "doc1" :document_metadata {:source "wiki"}}
;;     ...]
```

### Hybrid Search with Ontology

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Three-signal hybrid search — same call as 2-signal, one more signal
(ontology/hybrid-search ctx
  {:query-text       "lead qualification"
   :signals          #{:graph :embedding :colbert}
   :colbert-index-id index-id
   :ontology-id      my-ontology-id
   :limit 10})
```

### Reranking Candidates

```clojure
;; Generate multiple candidates
(def candidates (sheet/execute ctx brainstorm-sheet {...}))

;; Rerank by relevance (no index needed)
(colbert/rerank ctx
  {:query original-query
   :documents (map :content candidates)
   :k 3})
```

**ColBERT is the default scorer for the ontology classifier's domain penalty.**
Beyond ad-hoc reranking, `colbert/rerank` (in-memory MaxSim, no index) is the
**default** backend for the self-learning classifier's contrastive **domain
penalty** (ADR 0016 amendment). After the LLM rerank, each candidate's
judge-grounded `:avoid-when` guards and positive signals are scored against the
task in **one batched `colbert/rerank` call per rerank** (the distinct guard set
across all candidates), and the contrastive penalty bites when the task matches
a candidate's avoid-condition more than its use-case. Because this checkpoint's
MASK query expansion gives even unrelated pairs a high score floor, the penalty
normalizes **`:batch-relative`** by default — each guard's score is divided by
the max raw score within the candidate's own rerank call, which restores the
contrastive dynamic range a fixed ceiling would compress away. The penalty's
`:margin` knob ships at `0.010`, re-derived empirically for this checkpoint's
batch-relative scale (it fires the witnessed force-fit cases with headroom and
spares every witnessed clean case). The scorer is a pluggable injected
capability — `:colbert` (default) or `:embedding` (model-swappable) — selected
because ColBERT's token-level matching empirically separated the load-bearing
case (a `refactor` task vs. a `rename-move-symbol` guard) where single-vector
cosine did not. See
[SELF-IMPROVING-LOOP.md § How novelty is handled](SELF-IMPROVING-LOOP.md#2-how-novelty-is-handled--detect-and-defer--the-emergence-loop).

---

## Operational notes

- **Score scale:** the theoretical ceiling IS `maximum_query_tokens` unit-normalized query rows, so it moves with the configuration (464 by default since CC-17; raw scores then live in roughly [440, 460]). At the checkpoint's old `query_maxlen` 32 they lived in roughly [30, 32]. Rank fusion (RRF) is scale-free; score-*contrast* consumers should use batch-relative normalization (`normalize-result-scores`, or the domain penalty's `:batch-relative` method) rather than the fixed ceiling.
- **Warm vs cold:** the first encoder use in a JVM pays a one-time model load (seconds); after that, small-corpus index builds and searches are milliseconds.
- **Fine-tuning:** the JVM runtime is inference-only. If domain fine-tuning is ever wanted, the path is offline training in Python (e.g. PyLate) → ONNX export → served by this same JVM runtime. ORC ships no training surface.

## Related Documentation

- [ADR 0002 — Pure-JVM ColBERT signal](adr/0002-pure-jvm-colbert-signal.md) - the decision record (why the Python bridge left)
- [PACKAGES.md](PACKAGES.md) - the `orc-colbert` package (pull alongside `orc-ontology`)
- [COMPONENT-MAP.md](COMPONENT-MAP.md) - Layer 5 in the opt-in component map
- [ONTOLOGY.md](ONTOLOGY.md) - the concept graph the signals search over
- `CONTEXT.md` (Retrieval section) - the ubiquitous language used here
