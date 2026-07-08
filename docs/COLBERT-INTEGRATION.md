# ColBERT/RAGatouille Integration

> **Reference Document** - Native Clojure ColBERT integration for enhanced retrieval.
>
> Related: FUTURE-VISION.md Theme 9, components/colbert/

## Start here: you're already retrieving from the ontology

You're already retrieving from the ontology with semantic search. Every `hybrid-search` call against your concept graph fuses two signals — graph BFS (spreading activation through related concepts) and DJL embeddings (single-vector semantic similarity) — entirely in the JVM, no Python, no extra setup. **For most corpora, the built-in DJL embeddings are plenty.**

But as your corpus grows large, or your queries get subtle — long technical documents, near-synonyms, phrasing that doesn't line up with the stored text — single-vector embeddings start missing relevant results. A whole passage gets squashed into one 384-dimensional vector, and the nuance that would have matched your query gets averaged away.

**ColBERT adds token-level late-interaction matching as a third retrieval signal** — it compares your query token-by-token against each candidate, then fuses with graph + embeddings via the same RRF the ontology already uses. It's a drop-in upgrade: the *same* `hybrid-search` call, one more signal in the set.

### When you DON'T need ColBERT

Keep it honest — the 2-signal (graph + embedding) setup is the right default and is enough when:

- Your corpus is small-to-moderate and your concepts are well-separated.
- Queries closely resemble the stored concept text (label/description matches work).
- You want zero Python in the stack — graph + DJL embeddings run purely on the JVM.

If that's you, do nothing. You're already getting hybrid retrieval.

### When you DO want ColBERT

Reach for the third signal when:

- **Your corpus grows large** — more candidates means more ways a single averaged vector blurs the distinction between near-matches.
- **Your queries are subtle** — long passages, technical vocabulary, paraphrases, or multi-aspect questions where *which tokens* match matters, not just overall topical similarity.

In those cases late-interaction token-level matching measurably improves precision.

### The one-line change

You don't learn a new API. `hybrid-search` already accepts a `:signals` set, defaulting to all three. To go from 2 signals to 3, **add `:colbert` to the `:signals` set** (and point it at a ColBERT index):

```clojure
;; Before — 2 signals (graph + embedding), pure JVM
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

### The one cost: a Python environment

ColBERT is a transformer neural network, so it can't run on the JVM. The `colbert` component spawns a subprocess bridge into a `.venv-colbert` Python environment (RAGatouille + ColBERT under PyTorch). That virtualenv is the single price of admission for the third signal — see [Dependencies](#dependencies) below. (Graph + embeddings never touch it.)

### Graceful degradation: it just falls back

You don't have to guard your code. If the `colbert` component isn't on the classpath, retrieval automatically falls back to 2 signals — **no errors, no exceptions.** `hybrid-search` resolves ColBERT dynamically at call time via `(find-ns 'ai.obney.orc.colbert.interface)`; when it's absent that resolves to `nil`, the ColBERT signal is simply omitted from the RRF fusion, and graph + embeddings carry the search. So you can ship `:signals #{:graph :embedding :colbert}` everywhere and let the environment decide whether the third signal participates.

---

> **ColBERT is an optional third retrieval signal (Layer 5 in [COMPONENT-MAP.md](COMPONENT-MAP.md)).** ORC's ontology component works fully without it — graph BFS + DJL embeddings give you a 2-signal hybrid search. Add ColBERT when you need late-interaction token-level matching for larger corpora.
>
> **Python required.** ColBERT requires a `.venv-colbert` Python environment (the `colbert` component spawns a subprocess bridge).
>
> **Graceful degradation.** When the `colbert` component is absent from the classpath, `hybrid-search` automatically runs on 2 signals (graph + embedding). No exception thrown — source: `retrieval.clj` dynamically resolves ColBERT via `(find-ns 'ai.obney.orc.colbert.interface)`, returning `nil` when absent.

## Overview

This document describes the integration of ColBERT (via RAGatouille) into the ORC behavior tree system. ColBERT provides **late-interaction retrieval**—token-level semantic matching that outperforms single-vector embeddings for complex queries.

### What This Integration Provides

| Capability | Description | Use Case |
|------------|-------------|----------|
| **Late-Interaction Retrieval** | Token-level matching (not single-vector) | Better semantic matching than MiniLM |
| **Three-Signal Hybrid Search** | Graph BFS + MiniLM + ColBERT via RRF | Comprehensive retrieval |
| **Tree Profile Indexing** | Index tree self-descriptions | Few-shot example retrieval |
| **Reranking** | In-memory rerank without index | Candidate selection |
| **Domain Training** | Fine-tune on pairs/triplets | Custom retrievers |

---

## 2-signal vs 3-signal

By default `hybrid-search` enables all three signals. Pass `:signals #{:graph :embedding}` to run without ColBERT — no index required, no Python process:

```clojure
;; 2-signal (graph BFS + DJL embeddings — no ColBERT needed)
(ontology/hybrid-search ctx
  {:query-text "arbitration clause"
   :signals    #{:graph :embedding}
   :ontology-id my-ontology-id
   :limit 10})

;; 3-signal (graph BFS + DJL + ColBERT — requires colbert component + Python)
(ontology/hybrid-search ctx
  {:query-text      "arbitration clause"
   :signals         #{:graph :embedding :colbert}
   :colbert-index-id my-index-id
   :ontology-id      my-ontology-id
   :limit 10})
```

**When to add ColBERT:** when 2-signal retrieval is insufficient for corpus size and semantic complexity — late-interaction token-level matching provides measurably better precision for long documents and technical vocabulary.

**How graceful degradation works** (`retrieval.clj:702`):

```clojure
(defn- resolve-colbert-search-fn
  "Dynamically resolve the ColBERT search-for-rrf function if available.

   Returns the function or nil if ColBERT component is not loaded."
  []
  (try
    (when-let [ns (find-ns 'ai.obney.orc.colbert.interface)]  ;; line 708 — nil when absent
      (ns-resolve ns 'search-for-rrf))
    (catch Exception _ nil)))
```

If the `colbert` component is not on the classpath, `(find-ns 'ai.obney.orc.colbert.interface)` returns `nil`, the search function resolves to `nil`, and `hybrid-search` simply omits the ColBERT signal from the RRF fusion — no exception thrown.

---

## Architecture

### Three-Signal Hybrid Search

```
                       Query
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ Graph BFS   │  │ MiniLM      │  │ ColBERT     │
│ (spreading  │  │ (384-dim    │  │ (token-level│
│ activation) │  │ sentence)   │  │ late-inter) │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘
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

ColBERT is added as a **third signal** to the existing RRF hybrid search:
- **Graph BFS** - Spreading activation through ontology concepts
- **MiniLM** - 384-dim sentence embeddings (existing DJL integration)
- **ColBERT** - Token-level late-interaction (NEW)

The existing `compute-rrf-scores` function handles fusion without modification.

### Python Bridge Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Clojure (orchestration, data prep, event sourcing)          │
│                                                             │
│  corpus-processor → training-data-processor → event-store   │
│         ↓                    ↓                    ↓         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Python Bridge (JSON-RPC over stdin/stdout)          │   │
│  │ - load_model(alias, model_name)                     │   │
│  │ - create_index(alias, collection, index_name)       │   │
│  │ - search(alias, query, k)                           │   │
│  │ - rerank(alias, query, documents, k)                │   │
│  │ - prepare_training_data(alias, raw_data, ...)       │   │
│  │ - train(alias, batch_size, maxsteps, ...)           │   │
│  └─────────────────────────────────────────────────────┘   │
│         ↓                    ↓                    ↓         │
│  result-formatter ← hybrid-search ← index-metadata          │
└─────────────────────────────────────────────────────────────┘
```

---

## Why Python Bridge (Not Pure Clojure)

ColBERT is fundamentally different from a callable API. Here's why Python is unavoidable:

### 1. Neural Network Execution Requires PyTorch

ColBERT is a **transformer neural network** (modified BERT, 110M parameters) that runs on PyTorch:

```python
model = BertModel.from_pretrained("bert-base-uncased")  # 110M parameters
embeddings = model(input_ids, attention_mask)           # GPU tensor operations
```

**Why not ONNX/DJL?**
- ONNX export loses some ColBERT-specific operations
- DJL can load BERT but lacks ColBERT's specialized tokenization and late-interaction scoring
- The "bag of embeddings" (128-dim per token) requires ColBERT's specific architecture

### 2. PLAID Index is a Specialized Algorithm

PLAID (Partition-Lexicon-Approximate Index Design) is ColBERT's compression and search algorithm:

```python
# PLAID compresses 128-dim embeddings to 2-bit codes
# Uses k-means clustering with custom centroid scoring
# Implements a specialized inverted index for fast retrieval
```

This isn't a data format—it's an **algorithm** requiring:
- K-means trained centroids (learned from your data)
- Compressed residual vectors
- Custom scoring functions

**Reimplementing PLAID in Clojure would be 2000+ lines** of low-level vector math.

### 3. Training Requires PyTorch Autograd

Fine-tuning ColBERT involves:
```python
loss.backward()      # Compute gradients through entire network
optimizer.step()     # Update 110M+ parameters
```

No JVM equivalent exists—PyTorch's autograd is deeply integrated with CUDA kernels.

### Alternatives Considered

| Alternative | Why It Doesn't Work |
|-------------|---------------------|
| **ONNX Runtime on JVM** | No training, no PLAID index, limited operator support |
| **DJL with BERT** | Missing ColBERT's tokenizer, late-interaction scoring, PLAID |
| **Reimplement in Clojure** | ~10,000+ lines, unmaintainable |
| **HTTP Service** | Same Python underneath, adds network overhead |

**The Python bridge is the pragmatic choice:** Full ColBERT functionality with a thin, well-defined boundary. The bridge adds ~50ms latency per call (negligible vs model inference time).

---

## Component Structure

```
components/colbert/
├── deps.edn
└── src/ai/obney/workshop/colbert/
    ├── interface.clj                 # Public API
    ├── interface/schemas.clj         # Malli schemas + events
    └── core/
        ├── bridge.clj                # Python subprocess management
        ├── corpus_processor.clj      # Document splitting
        ├── training_data_processor.clj # Triplet generation
        ├── negative_miner.clj        # Hard negative mining (DJL-based)
        ├── index_metadata.clj        # JSON persistence layer
        ├── results.clj               # Result formatting
        ├── read_models.clj           # Event → state projections
        ├── commands.clj              # Grain command handlers
        └── queries.clj               # Grain query handlers

# Python bridge script
scripts/colbert_bridge.py            # JSON-RPC subprocess
```

---

## Public API

```clojure
(ns ai.obney.orc.colbert.interface)

;; === Index Management ===

(defn create-index!
  "Create a ColBERT index from documents.
   Returns index-id (UUID). Emits :colbert/index-created event."
  [ctx {:keys [collection document-ids document-metadatas
               index-name split-documents? max-document-length]
        :or {split-documents? true
             max-document-length 256}}])

(defn load-index
  "Load an existing index by name or path."
  [ctx index-name-or-path])

(defn delete-index!
  "Delete an index and its metadata."
  [ctx index-id])

;; === Search Operations ===

(defn search
  "Search indexed corpus using ColBERT late-interaction.
   Returns: [{:content :score :rank :document-id :document-metadata}]"
  [ctx {:keys [query index-id k]
        :or {k 10}}])

(defn rerank
  "Rerank documents in-memory (no index required).
   Returns: [{:content :score :rank}]"
  [ctx {:keys [query documents k]
        :or {k 10}}])

;; === Hybrid Search (RRF Integration) ===

(defn hybrid-search
  "Combine ColBERT with existing ontology search via RRF."
  [ctx {:keys [query index-id ontology-scope k weights]
        :or {k 10 weights {:colbert 1.0 :embedding 1.0 :graph 1.0}}}])

;; === Training ===

(defn prepare-training-data!
  "Convert raw data to ColBERT triplet format.
   Supports: pairs, labeled-pairs, triplets.
   Optionally mines hard negatives."
  [ctx {:keys [raw-data all-documents data-out-path
               mine-hard-negatives? hard-negative-minimum-rank]}])

(defn train!
  "Train/fine-tune a ColBERT model.
   Returns path to best checkpoint."
  [ctx {:keys [model-name pretrained-model-name training-data-path
               batch-size maxsteps learning-rate]}])

;; === Training Data Extraction (Phase 4) ===

(defn extract-training-pairs-from-traces
  "Extract query→output pairs from successful tree executions.
   Joins :sheet/execution-traced with :evaluation/trace-evaluated.
   Returns: {:pairs [[query positive] ...] :stats {...} :trace-ids [...]}"
  [ctx {:keys [sheet-id min-score since limit input-keys output-keys format-fn]
        :or {min-score 0.7
             limit 1000
             format-fn json/write-str}}])
```

---

## Event Schemas

```clojure
(defschemas events
  ;; Index lifecycle
  {:colbert/index-created
   [:map
    [:index-id :uuid]
    [:index-name :string]
    [:document-count :int]
    [:passage-count :int]
    [:model-name :string]
    [:config [:map-of :keyword :any]]
    [:created-at :string]]

   :colbert/index-deleted
   [:map
    [:index-id :uuid]
    [:deleted-at :string]]

   ;; Search audit
   :colbert/search-performed
   [:map
    [:search-id :uuid]
    [:index-id :uuid]
    [:query :string]
    [:k :int]
    [:result-count :int]
    [:latency-ms :int]
    [:top-score :double]
    [:performed-at :string]]

   ;; Training
   :colbert/training-started
   [:map
    [:training-id :uuid]
    [:model-name :string]
    [:base-model :string]
    [:training-data-size :int]
    [:config [:map-of :keyword :any]]
    [:started-at :string]]

   :colbert/training-completed
   [:map
    [:training-id :uuid]
    [:checkpoint-path :string]
    [:final-step :int]
    [:duration-ms :int]
    [:completed-at :string]]

   ;; Training data preparation (Phase 4)
   :colbert/training-data-prepared
   [:map
    [:preparation-id :uuid]
    [:input-format [:enum :pairs :labeled-pairs :triplets]]
    [:num-queries :int]
    [:num-triplets :int]
    [:hard-negatives-mined? :boolean]
    [:num-new-negatives {:optional true} :int]
    [:output-path :string]
    [:prepared-at :string]]})
```

---

## Usage Examples

### Basic Search

```clojure
(require '[ai.obney.orc.colbert.interface :as colbert])

;; Create index from documents
(def index-id
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
     :index-id index-id
     :k 5}))
;; => [{:content "Machine learning is..." :score 0.87 :rank 1 :document-id "doc1"}
;;     ...]
```

### Tree Profile Retrieval

```clojure
;; Index all tree self-descriptions
(colbert/index-tree-profiles! ctx)

;; Find trees for a new problem
(colbert/search ctx
  {:query "lead qualification scoring classification"
   :index-id "tree-profiles"
   :k 5})
```

### Hybrid Search with Ontology

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Three-signal hybrid search
(ontology/hybrid-search-with-colbert ctx
  {:query "lead qualification"
   :colbert-index-id index-id
   :weights {:graph 1.0 :embedding 1.0 :colbert 1.0}
   :k 10})
```

### Reranking Candidates

```clojure
;; Generate multiple candidates
(def candidates (sheet/execute ctx brainstorm-sheet {...}))

;; Rerank by relevance
(colbert/rerank ctx
  {:query original-query
   :documents (map :content candidates)
   :k 3})
```

**ColBERT is the default scorer for the ontology classifier's domain penalty.**
Beyond ad-hoc reranking, `colbert/rerank` (in-memory MaxSim, no index) is the
**default** backend for the self-learning classifier's contrastive **domain
penalty** (ADR
0016 amendment). After the
LLM rerank, each candidate's judge-grounded `:avoid-when` guards and positive
signals are scored against the task in **one batched `colbert/rerank` call per
rerank** (the distinct guard set across all candidates), the MaxSim scores are
mapped to `[0,1]` via `colbert/normalize-colbert-score`, and the contrastive
penalty bites when the task matches a candidate's avoid-condition more than its
use-case. The scorer is a pluggable injected capability — `:colbert` (default) or
`:embedding` (model-swappable) — selected because ColBERT's token-level matching
empirically separated the load-bearing case (a `refactor` task vs. a
`rename-move-symbol` guard) where single-vector cosine did not. See
[SELF-IMPROVING-LOOP.md § How novelty is handled](SELF-IMPROVING-LOOP.md#2-how-novelty-is-handled--detect-and-defer--the-emergence-loop).

### Training Domain Retriever

```clojure
(require '[ai.obney.orc.colbert.interface :as colbert])
(require '[ai.obney.orc.colbert.core.training-data-processor :as tdp])

;; Step 1: Extract training pairs from successful tree executions
(def training-data
  (colbert/extract-training-pairs-from-traces ctx
    {:sheet-id my-sheet
     :min-score 0.8
     :limit 500}))
;; => {:pairs [[query1 output1] ...] :stats {...} :trace-ids [...]}

;; Step 2: Process pairs into triplets (Clojure-side)
(def processed (tdp/process-training-data (:pairs training-data) :pairs))

;; Step 3: Export to JSONL
(tdp/export-training-data (:triplets processed) "./training-data/triplets.jsonl")

;; Step 4: (Optional) Prepare with hard negatives via Python bridge
(colbert/prepare-training-data! ctx
  {:raw-data (:pairs training-data)
   :mine-hard-negatives? true
   :data-out-path "./training-data/"})

;; Step 5: Fine-tune
(colbert/train! ctx
  {:model-name "my-domain-colbert"
   :pretrained-model-name "colbert-ir/colbertv2.0"
   :training-data-path "./training-data/"
   :batch-size 32
   :maxsteps 5000})
```

---

## Algorithm Parity (RAGatouille → Clojure)

### Document Splitting

```clojure
;; Python: chunk_overlap = min(chunk_size/4, min(chunk_size/2, 64))
(defn calculate-chunk-overlap [chunk-size]
  (min (/ chunk-size 4)
       (min (/ chunk-size 2) 64)))

;; Examples:
;; chunk-size=256 → overlap=64
;; chunk-size=128 → overlap=32
```

### Triplet Generation

```clojure
;; Python: negs_per_positive = max(1, 20 // len(positives))
;; Max 20 triplets per query, distributed across positives

(defn make-individual-triplets [data-map]
  (for [[query {:keys [positives negatives]}] data-map
        :let [negs-per-positive (max 1 (quot 20 (count positives)))]
        positive positives
        negative (take negs-per-positive negatives)]
    [query positive negative]))
```

### Hard Negative Mining

```clojure
;; Skip top min-rank (10), return ranks 10-110
(defn mine-single-query [index query {:keys [min-rank] :or {min-rank 10}}]
  (let [max-rank (min 110 (quot (index-size index) 10))
        all-results (query-index index query max-rank)]
    (subvec all-results min-rank max-rank)))
```

---

## Training Data Processor (Phase 4)

The `training_data_processor.clj` module provides **pure Clojure** training data processing with **EXACT Python parity** to RAGatouille's `training_data_processor.py`.

### Key Functions

```clojure
(require '[ai.obney.orc.colbert.core.training-data-processor :as tdp])

;; Validate input data
(tdp/validate-training-data [["q1" "p1"] ["q2" "p2"]] :pairs)
;; => {:valid? true :count 2}

;; Process pairs into data-map
(tdp/process-raw-pairs [["q" "p1"] ["q" "p2"]])
;; => {"q" {:positives ["p1" "p2"] :negatives []}}

;; Process labeled pairs (label 1=positive, 0=negative)
(tdp/process-raw-labeled-pairs [["q" "pos" 1] ["q" "neg" 0]])
;; => {"q" {:positives ["pos"] :negatives ["neg"]}}

;; Generate triplets (max 20 per query, Python parity)
(tdp/make-individual-triplets {"q" {:positives ["p"] :negatives ["n1" "n2"]}})
;; => [["q" "p" "n1"] ["q" "p" "n2"] ...]

;; Export to JSONL for ColBERT training
(tdp/export-training-data triplets "./output/triplets.jsonl")
;; => {:path "..." :num-triplets 42}

;; High-level API
(tdp/process-training-data raw-data :pairs
  :validate? true
  :deduplicate? true)
;; => {:data-map {...} :triplets [...] :stats {:num-queries N :num-triplets M}}
```

### Triplet Distribution Algorithm

The algorithm exactly matches Python's `_make_individual_triplets`:

```clojure
;; negs_per_positive = max(1, 20 // len(positives))
;; - 1 positive  → 20 negs each
;; - 2 positives → 10 negs each
;; - 5 positives → 4 negs each
;; - 20+ positives → 1 neg each

;; Negatives cycle when fewer than needed
(tdp/make-individual-triplets {"q" {:positives ["p1"]
                                     :negatives ["n1" "n2" "n3"]}})
;; => 20 triplets, cycling through n1, n2, n3, n1, n2, n3...
```

### Extracting Training Data from Executions

```clojure
(require '[ai.obney.orc.colbert.interface :as colbert])

;; Extract pairs from successful tree executions
(def training-data
  (colbert/extract-training-pairs-from-traces ctx
    {:sheet-id my-sheet-id
     :min-score 0.8      ; Only passing traces (score >= 0.8)
     :limit 500
     :input-keys ["question" "context"]   ; Optional: which inputs to include
     :output-keys ["answer"]              ; Optional: which outputs to include
     :format-fn (fn [m] (str (:question m) "\n" (:context m)))}))

;; => {:pairs [["What is X?" "X is Y..."] ...]
;;     :stats {:total-traces 100 :passing-traces 75 :avg-score 0.89}
;;     :trace-ids [#uuid "..." ...]}

;; Process into triplets (Clojure-side, no Python needed)
(def result (tdp/process-training-data (:pairs training-data) :pairs))

;; Export for ColBERT training
(tdp/export-training-data (:triplets result) "./training-data/triplets.jsonl")
```

---

## Dependencies

### Python

```txt
# requirements.txt
ragatouille>=0.0.9
colbert-ai>=0.2.19
torch>=1.13
sentence-transformers
```

### Clojure

```clojure
;; deps.edn additions
{:deps
 {org.clojure/data.json {:mvn/version "2.4.0"}}}
```

---

## Implementation Phases

### Phase 1: Bridge Foundation
- Create `components/colbert/` structure
- Implement `scripts/colbert_bridge.py`
- Implement `bridge.clj` subprocess management
- Basic `search` and `rerank` operations
- Event schemas for index/search

### Phase 2: Index Management
- `corpus_processor.clj` - document splitting
- `index_metadata.clj` - JSON persistence
- `create-index!` with full options
- Index CRUD operations
- Event sourcing for index lifecycle

### Phase 3: Ontology Integration
- Extend `ontology/hybrid-search` with ColBERT
- Tree profile indexing
- Few-shot retrieval enhancement
- RRF weights configuration

### Phase 4: Training Pipeline ✓ (Partial)
- ✅ `training_data_processor.clj` - Pure Clojure triplet generation with Python parity
- ✅ `extract-training-pairs-from-traces` - Extract training data from successful executions
- ✅ Training event schemas (`:colbert/training-data-prepared`, etc.)
- ✅ Validation functions for all formats (pairs, labeled-pairs, triplets)
- ⬜ `negative_miner.clj` - Hard negative mining with DJL (future)
- ⬜ Cross-run persistence for checkpoints (future)

### Phase 5: ORC Integration
- `colbert-search` code executor
- `colbert-rerank` code executor
- Example workflows in development/
- Documentation

---

## Related Documentation

- `FUTURE-VISION.md` - Theme 9: Enhanced Retrieval with ColBERT
- `ARCHITECTURE.md` - Overall system architecture
- `docs/DSL-REFERENCE.md` - ORC behavior tree DSL
- RAGatouille: https://github.com/bclavie/RAGatouille
