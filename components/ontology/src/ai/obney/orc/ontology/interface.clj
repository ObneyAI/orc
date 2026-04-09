(ns ai.obney.orc.ontology.interface
  "Public API for the ontology component.

   Three-layer ontology system:
   1. Failure Ontology - Why things go wrong (maps to 4 evaluation judges)
   2. Success Ontology - What makes things work (structural, instruction, data flow patterns)
   3. Problem Ontology - What types of problems exist (classification, retrieval, generation)

   Main capabilities:
   - Static ontology initialization from predefined concepts
   - Event-sourced concept graph with relationships
   - Tree profile tracking (strengths, weaknesses, problem mappings)
   - Node-type pattern learning aggregation
   - Evaluation classification to failure URIs
   - TTL/SKOS/OWL serialization for graph database export
   - Embedding generation and semantic search (Phase 4)
   - Hybrid search combining graph BFS + embeddings via RRF"
  (:require [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.graph :as graph]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.field-analyzer :as field-analyzer]
            [ai.obney.orc.ontology.core.field-analyzer-workflow :as fa-workflow]
            [ai.obney.orc.ontology.core.discovery :as discovery]
            [ai.obney.orc.ontology.core.rule-extraction :as rule-extraction]
            [ai.obney.orc.ontology.core.commands] ;; Register defcommand handlers for tree profiles
            [ai.obney.orc.ontology.core.evolutionary-commands] ;; Register defcommand handlers for evolutionary builder
            [ai.obney.orc.ontology.core.todo-processors] ;; Register todo processors for auto-learning
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Static Ontology Access
;; =============================================================================

(defn get-static-concepts
  "Get concepts from static ontology definitions.
   Options:
   - :scope - Filter by :failure, :success, or :problem"
  ([]
   (static/get-all-static-concepts))
  ([{:keys [scope]}]
   (if scope
     (static/get-concepts-by-scope scope)
     (static/get-all-static-concepts))))

(defn get-static-relationships
  "Get all relationships from static ontology definitions."
  []
  (static/get-all-static-relationships))

(defn get-static-concept-by-uri
  "Find a static concept by its URI."
  [uri]
  (static/get-concept-by-uri uri))

(defn get-failure-concept-for-dimension
  "Map evaluation dimension name to failure concept URI.
   E.g., 'Grounding' -> 'failure:Grounding'"
  [dimension-name]
  (static/get-failure-concept-for-dimension dimension-name))

;; =============================================================================
;; Concept Graph Queries (Event-Sourced)
;; =============================================================================

(defn get-concepts
  "Get concepts from the event-sourced concept graph.
   Options:
   - :scope - Filter by scope
   - :broader-uri - Filter by parent concept"
  [ctx & [opts]]
  (rm/get-concepts ctx opts))

(defn get-concept-by-uri
  "Get a single concept by URI from the event-sourced graph."
  [ctx uri]
  (rm/get-concept-by-uri ctx uri))

(defn get-narrower-concepts
  "Get all concepts narrower than (children of) the given URI."
  [ctx uri]
  (rm/get-narrower-concepts ctx uri))

(defn get-broader-concepts
  "Get all concepts broader than (parents of) the given URI."
  [ctx uri]
  (rm/get-broader-concepts ctx uri))

(defn concept-statistics
  "Get statistics about the concept graph."
  [ctx]
  (rm/concept-statistics ctx))

;; =============================================================================
;; Tree Profile Queries
;; =============================================================================

(defn get-tree-profile
  "Get profile for a specific tree including strengths, weaknesses, and problem mappings."
  [ctx tree-id]
  (rm/get-tree-profile ctx tree-id))

(defn get-all-tree-profiles
  "Get all tree profiles."
  [ctx]
  (rm/get-all-tree-profiles ctx))

(defn find-trees-by-problem
  "Find trees that solve a specific problem type."
  [ctx problem-uri]
  (rm/find-trees-by-problem ctx problem-uri))

(defn find-trees-with-weakness
  "Find trees that have a specific weakness/failure pattern."
  [ctx failure-uri]
  (rm/find-trees-with-weakness ctx failure-uri))

(defn tree-profile-statistics
  "Get statistics about tree profiles."
  [ctx]
  (rm/tree-profile-statistics ctx))

;; =============================================================================
;; Node Learning Queries
;; =============================================================================

(defn get-node-type-learnings
  "Get aggregated learnings for a specific node type (e.g., :llm, :code)."
  [ctx node-type]
  (rm/get-node-type-learnings ctx node-type))

(defn get-all-node-learnings
  "Get all node learnings aggregated by type."
  [ctx]
  (rm/get-all-node-learnings ctx))

(defn node-learning-statistics
  "Get statistics about node-level learning."
  [ctx]
  (rm/node-learning-statistics ctx))

;; =============================================================================
;; Serialization (TTL Export)
;; =============================================================================

(defn concepts->turtle
  "Serialize concepts to SKOS Turtle format.
   Arguments:
   - concepts: Map of URI -> concept-map
   - opts: {:base-uri \"...\" :include-scheme? true/false}"
  [concepts & [opts]]
  (serialization/concepts->turtle concepts opts))

(defn tree-profile->turtle
  "Serialize a single tree profile to OWL Turtle format."
  [profile & [opts]]
  (serialization/tree-profile->turtle profile opts))

(defn tree-profiles->turtle
  "Serialize multiple tree profiles to OWL Turtle format."
  [profiles & [opts]]
  (serialization/tree-profiles->turtle profiles opts))

(defn node-experiences->turtle
  "Serialize node learning experiences to OWL Turtle format."
  [experiences & [opts]]
  (serialization/node-experiences->turtle experiences opts))

(defn export-turtle
  "Full ontology export to Turtle format.
   Options:
   - :scope - Filter concepts (:failure, :success, :problem)
   - :include-profiles? - Include tree profiles (default true)
   - :include-experiences? - Include node experiences (default true)
   - :base-uri - Base URI for the ontology"
  [ctx & [opts]]
  (serialization/full-export ctx opts))

(defn validate-turtle
  "Basic validation of generated Turtle syntax."
  [turtle-str]
  (serialization/validate-turtle turtle-str))

;; =============================================================================
;; Classification
;; =============================================================================

(defn classify-evaluation
  "Classify evaluation results into failure ontology URIs.

   Arguments:
   - evaluation-result: Map with :score and :dimensions
     - :score - Overall score (0.0-1.0)
     - :dimensions - Vector of {:name :score :feedback}

   Options:
   - :threshold - Score below which to flag as failure (default 0.7)
   - :min-confidence - Minimum confidence to include (default 0.1)

   Returns:
   {:failures [{:uri :confidence :evidence :dimension}]
    :primary-failure-uri \"failure:Hallucination\"
    :overall-score 0.5}"
  [evaluation-result & [opts]]
  (classifier/classify-evaluation evaluation-result opts))

(defn classify-trace-evaluations
  "Classify multiple trace evaluations, grouping failures by type."
  [evaluations & [opts]]
  (classifier/classify-trace-evaluations evaluations opts))

(defn estimate-severity
  "Estimate severity level for a classified failure."
  [failure]
  (classifier/estimate-severity failure))

(defn extract-triggers
  "Extract trigger phrases from failure evidence."
  [failures]
  (classifier/extract-triggers failures))

;; =============================================================================
;; Graph Traversal (Phase 3)
;; =============================================================================

(defn bfs-spreading-activation
  "BFS traversal with exponential decay activation scores.

   Algorithm:
   1. Initialize seeds with activation 1.0
   2. Process nodes level by level (BFS)
   3. For each node, propagate to neighbors:
      neighbor_activation = current_activation × edge_weight × decay^depth
   4. Stop when activation < min_activation or depth > max_depth
   5. Return all visited nodes sorted by activation score

   Args:
     graph: Graph map with :nodes and :edges (or concept map to convert)
     seed-uris: Collection of starting URIs
     config: {:max-depth N :decay N :min-activation N :max-results N}

   Returns:
     Vector of {:uri :score :path :depth :source-seed :edge-type}"
  [graph-or-concepts seed-uris & [config]]
  (let [g (if (contains? graph-or-concepts :edges)
            graph-or-concepts
            (graph/concepts->graph graph-or-concepts))]
    (graph/bfs-spreading-activation g seed-uris config)))

(defn link-expansion
  "Fast 2-3 hop expansion for real-time suggestions.

   Use for: autocomplete, quick suggestions, low-latency scenarios

   Args:
     graph: Graph map with :nodes and :edges (or concept map)
     seed-uris: Collection of starting URIs
     opts: {:hops N} - Number of expansion hops (1-3)

   Returns:
     Vector of {:uri :score :path :depth}"
  [graph-or-concepts seed-uris & {:keys [hops] :or {hops 2}}]
  (let [g (if (contains? graph-or-concepts :edges)
            graph-or-concepts
            (graph/concepts->graph graph-or-concepts))]
    (graph/link-expansion g seed-uris :hops hops)))

(defn compute-rrf-scores
  "Reciprocal Rank Fusion for merging multiple ranked result lists.

   Formula: score(d) = sum(1 / (k + rank_i))
   where k = 60 (per original RRF paper)

   Args:
     batches: Vector of result batches, each batch is vector of {:uri :score}
     opts: {:k N} - RRF constant (default 60)

   Returns:
     Vector of [uri fused-score] sorted by fused score descending"
  [batches & {:keys [k] :or {k 60}}]
  (graph/compute-rrf-scores batches :k k))

(defn merge-batches
  "Merge multiple search batches using RRF or weighted-sum fusion.

   Args:
     batches: Vector of result batches
     opts: {:method \"rrf\"/\"weighted-sum\" :weights [...]}

   Returns:
     Vector of {:uri :score :sources}"
  [batches & {:keys [method weights] :or {method "rrf"}}]
  (graph/merge-batches batches :method method :weights weights))

(defn concepts->graph
  "Convert concept read model to graph representation for traversal algorithms.

   Args:
     concepts: Map of URI -> concept-map from concepts read model

   Returns:
     {:nodes #{uri...} :edges {uri [{:to uri :predicate pred :weight w}...]}}"
  [concepts]
  (graph/concepts->graph concepts))

(defn quick-search
  "Quick graph search without full ORC workflow.

   Args:
     graph: Graph map or concept map
     seed-uris: Vector of seed URIs
     opts: {:strategy \"bfs\"/\"link_expansion\" :max-depth N :decay N :hops N}

   Returns:
     Vector of {:uri :score :path :depth}"
  [graph seed-uris & {:keys [strategy max-depth decay hops]
                      :or {strategy "bfs" max-depth 3 decay 0.5 hops 2}}]
  (graph/quick-search graph seed-uris
                      :strategy strategy :max-depth max-depth :decay decay :hops hops))

;; =============================================================================
;; Few-Shot Retrieval (Phase 3)
;; =============================================================================

(defn expand-concept-neighborhood
  "Expand from seed concept URIs using BFS spreading activation.

   Args:
     seed-uris: Collection of starting concept URIs
     opts: {:max-depth N :decay N :graph graph}

   Returns:
     Vector of {:uri :score :path :depth} sorted by score"
  [seed-uris & {:keys [max-depth decay graph]
                :or {max-depth 2 decay 0.6}}]
  (retrieval/expand-concept-neighborhood seed-uris
                                         :max-depth max-depth
                                         :decay decay
                                         :graph graph))

(defn find-similar-trees
  "Find trees similar to a given problem type and/or pattern requirements.

   Uses graph traversal to find related concepts, then filters tree profiles
   by success rate and matching patterns.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Problem URI to match (e.g., \"problem:Classification\")
       :required-patterns - Set of success pattern URIs that must be present
       :exclude-patterns - Set of success pattern URIs to exclude
       :min-success-rate - Minimum success rate (default 0.7)
       :limit - Maximum results (default 10)

   Returns:
     Vector of {:tree-id :profile :score :matching-patterns :problem-match}"
  [event-store opts]
  (retrieval/find-similar-trees event-store opts))

(defn find-failure-patterns
  "Find common failure patterns for a problem type or across all trees.

   Aggregates weaknesses from tree profiles and groups by failure URI.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Filter to trees solving this problem type
       :failure-uri - Filter to specific failure type
       :min-frequency - Minimum occurrence frequency (default 0.2)
       :limit - Maximum results (default 20)

   Returns:
     Vector of {:failure-uri :total-occurrences :avg-frequency
                :severity-distribution :common-triggers :tree-count}"
  [event-store opts]
  (retrieval/find-failure-patterns event-store opts))

(defn find-success-patterns
  "Find effective success patterns for a problem type.

   Aggregates strengths from high-performing trees.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Filter to trees solving this problem type
       :min-success-rate - Minimum tree success rate (default 0.8)
       :min-confidence - Minimum pattern confidence (default 0.7)
       :limit - Maximum results (default 10)

   Returns:
     Vector of {:pattern-uri :label :description :avg-confidence
                :avg-score :tree-count :evidence-count}"
  [event-store opts]
  (retrieval/find-success-patterns event-store opts))

(defn build-ontology-context
  "Build comprehensive ontology context for MCP sheet builder integration.

   This provides all the information needed to guide tree generation:
   - Few-shot examples (similar successful trees)
   - Success patterns to use
   - Failure patterns to avoid
   - Related concepts for grounding
   - Self-learning patterns (when enabled)

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Primary problem being solved
       :required-patterns - Success patterns that should be used
       :user-domain - Domain context (e.g., \"education\", \"healthcare\")
       :tree-id - Current tree ID for self-learning mode
       :self-learning? - Enable self-reinforcing learning from tree's own patterns

   Returns:
     {:few-shot-trees [...] - Similar successful trees with profiles
      :recommended-patterns [...] - Success patterns to use (includes self-patterns when enabled)
      :patterns-to-avoid [...] - Common failure patterns (includes self-weaknesses when enabled)
      :related-concepts [...] - Neighborhood expansion for context
      :problem-hierarchy {...} - Problem type with parent/child
      :self-patterns {...} - Tree's own patterns (when self-learning enabled)}

   Self-learning mode example:
   ```clojure
   (build-ontology-context ctx
     {:problem-type \"problem:PointNavigation\"
      :tree-id sheet-id
      :self-learning? true})
   ```"
  [event-store opts]
  (retrieval/build-ontology-context event-store opts))

(defn format-context-for-llm
  "Format ontology context for injection into LLM prompts.

   Converts the rich ontology context from build-ontology-context into
   a markdown-formatted string suitable for inclusion in an LLM system prompt.

   Args:
     context: Map from build-ontology-context
     opts: Configuration map
       :include #{:patterns :failures :related :hierarchy :examples}
       :max-items - Max items per section (default 5)

   Returns:
     Formatted markdown string for prompt injection, or nil if empty

   Example:
     (let [ctx (build-ontology-context event-store {:problem-type \"problem:Classification\"})]
       (format-context-for-llm ctx {:include #{:patterns :related} :max-items 3}))"
  [context opts]
  (retrieval/format-context-for-llm context opts))

(defn hybrid-retrieval
  "Hybrid retrieval combining multiple signals.

   Combines:
   1. Problem type graph expansion
   2. Pattern matching from tree profiles
   3. Failure pattern analysis

   Args:
     event-store: Grain event store
     query-concepts: Set of seed concept URIs
     opts: Retrieval configuration

   Returns:
     {:trees [...] :patterns [...] :failures [...] :merged-scores [...]}"
  [event-store query-concepts opts]
  (retrieval/hybrid-retrieval event-store query-concepts opts))

;; =============================================================================
;; Temporal Relevance (Phase 3)
;; =============================================================================

(defn compute-temporal-relevance
  "Compute temporal relevance score.

   Returns:
   - 1.0 for current year
   - 1.1 for future years (slight boost)
   - Decaying score for past years (min 0.1)
   - 0.5 for entities without temporal info"
  [entity-year reference-year & {:keys [decay-per-year] :or {decay-per-year 0.15}}]
  (graph/compute-temporal-relevance entity-year reference-year
                                    :decay-per-year decay-per-year))

(defn apply-temporal-scoring
  "Apply temporal relevance scoring to search results.

   Final score = graph_score × temporal_relevance

   Args:
     results: Vector of {:uri :score ...}
     temporal-entities: Map of uri -> effective-year
     reference-year: Current year for relevance calculation

   Returns:
     Results with adjusted scores"
  [results temporal-entities reference-year]
  (graph/apply-temporal-scoring results temporal-entities reference-year))

;; =============================================================================
;; Read Model Builders (for direct use in tests/REPL)
;; =============================================================================

(defn build-concepts
  "Build concept graph from events."
  [initial-state events]
  (rm/concepts initial-state events))

(defn build-tree-profiles
  "Build tree profiles from events."
  [initial-state events]
  (rm/tree-profiles initial-state events))

(defn build-node-experiences
  "Build node experiences from events."
  [initial-state events]
  (rm/node-experiences initial-state events))

;; =============================================================================
;; Event Type Sets (for external query filtering)
;; =============================================================================

(def concept-events
  "Event types that affect the concept graph."
  rm/concept-events)

(def tree-profile-events
  "Event types that affect tree profiles."
  rm/tree-profile-events)

(def node-learning-events
  "Event types that affect node learning."
  rm/node-learning-events)

(def all-ontology-events
  "All ontology-related event types."
  rm/all-ontology-events)

(def embedding-events
  "Event types that affect embedding read models."
  rm/embedding-events)

;; =============================================================================
;; Embedding Functions (Phase 4)
;; =============================================================================

(defn embed-text
  "Generate an embedding vector for text.

   Args:
     text: String to embed
     opts: {:model-id \"sentence-transformers/all-MiniLM-L6-v2\"}

   Returns:
     Vector of doubles (384 dimensions for default model), or nil on error."
  [text & [opts]]
  (embedding/embed-text text (or opts {})))

(defn embed-texts-batch
  "Generate embeddings for multiple texts.

   Args:
     texts: Collection of strings to embed
     opts: {:model-id \"...\"}

   Returns:
     Vector of embedding vectors"
  [texts & [opts]]
  (embedding/embed-texts-batch texts (or opts {})))

(defn cosine-similarity
  "Compute cosine similarity between two embedding vectors.

   Returns:
     Similarity score in range [-1, 1], or 0.0 if either vector is zero."
  [a b]
  (embedding/cosine-similarity a b))

(defn detect-embeddable-fields
  "Analyze a Malli schema to find fields suitable for embedding.

   Heuristics:
   - :string fields with semantic names (:description, :feedback, :label)
   - [:vector :string] fields (indicators, triggers)
   - Fields with {:description ...} metadata

   Args:
     schema: Malli schema to analyze

   Returns:
     {:embeddable-fields [:description :feedback ...]
      :reasoning {'description' 'reason...'}}

   Returns nil if schema is not a map schema."
  [schema]
  (embedding/detect-embeddable-fields schema))

(defn concept->embedding-text
  "Convert a concept to text suitable for embedding.

   Combines specified fields into a single text string.

   Args:
     concept: Concept map with :label, :description, :indicators, etc.
     fields: Set of field keywords to include (default: #{:label :description})

   Returns:
     Combined text string for embedding"
  [concept & [fields]]
  (embedding/concept->embedding-text concept (or fields #{:label :description})))

(defn tree-profile->embedding-text
  "Convert a tree profile to text suitable for embedding.

   Summarizes strengths, weaknesses, and problem types."
  [profile]
  (embedding/tree-profile->embedding-text profile))

(defn get-embedding-model
  "Get an embedding model by ID, loading it if necessary.

   Thread-safe with double-checked locking pattern.

   Args:
     model-id: Optional model identifier (default: all-MiniLM-L6-v2)

   Returns:
     Loaded DJL ZooModel"
  [& [model-id]]
  (if model-id
    (embedding/get-embedding-model model-id)
    (embedding/get-embedding-model)))

(defn close-all-embedding-models!
  "Close all loaded embedding models. Useful for REPL cleanup."
  []
  (embedding/close-all-embedding-models!))

;; =============================================================================
;; Embedding Read Model Queries (Phase 4)
;; =============================================================================

(defn get-concept-embedding
  "Get embedding for a specific concept by URI."
  [ctx uri]
  (rm/get-concept-embedding ctx uri))

(defn get-all-concept-embeddings
  "Get all concept embeddings, optionally filtered by scope."
  [ctx & [opts]]
  (rm/get-all-concept-embeddings ctx opts))

(defn get-tree-profile-embedding
  "Get embedding for a specific tree profile."
  [ctx tree-id]
  (rm/get-tree-profile-embedding ctx tree-id))

(defn get-all-tree-profile-embeddings
  "Get all tree profile embeddings."
  [ctx]
  (rm/get-all-tree-profile-embeddings ctx))

(defn get-embedding-config
  "Get embedding model configuration for a scope."
  [ctx scope]
  (rm/get-embedding-config ctx scope))

(defn embedding-statistics
  "Get statistics about embeddings."
  [ctx]
  (rm/embedding-statistics ctx))

;; =============================================================================
;; Semantic Search (Phase 4)
;; =============================================================================

(defn semantic-search-concepts
  "Search concepts using embedding similarity.

   Generates an embedding for the query text and finds similar concepts
   based on cosine similarity with stored concept embeddings.

   Args:
     event-store: Grain event store (required)
     query-text: Natural language search query
     opts:
       :scope - Filter to specific ontology scope (:failure, :success, :problem)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum cosine similarity threshold (default 0.5)
       :model-id - Embedding model to use (default: all-MiniLM-L6-v2)

   Returns:
     Vector of {:uri :similarity :label :description} sorted by similarity

   Requires concept embeddings to be generated first via ontology/embed-concepts-batch."
  [event-store query-text & {:keys [scope limit min-similarity model-id]
                              :or {limit 10 min-similarity 0.5}
                              :as opts}]
  (retrieval/semantic-search-concepts event-store query-text
                                       :scope scope
                                       :limit limit
                                       :min-similarity min-similarity
                                       :model-id model-id))

(defn semantic-search-tree-profiles
  "Search tree profiles using embedding similarity.

   Finds trees with similar characteristics based on their embedded profiles.

   Args:
     event-store: Grain event store
     query-text: Description of desired tree characteristics
     opts:
       :limit - Maximum results (default 5)
       :min-similarity - Minimum similarity threshold (default 0.4)

   Returns:
     Vector of {:tree-id :similarity :profile} sorted by similarity"
  [event-store query-text & {:keys [limit min-similarity]
                              :or {limit 5 min-similarity 0.4}
                              :as opts}]
  (retrieval/semantic-search-tree-profiles event-store query-text
                                            :limit limit
                                            :min-similarity min-similarity))

;; =============================================================================
;; Hybrid Search (Graph + Embeddings via RRF) (Phase 4)
;; =============================================================================

(defn hybrid-search
  "Hybrid search combining graph-based BFS with embedding similarity via RRF.

   This is the primary search function that leverages both:
   1. Graph structure - BFS spreading activation from seed concepts
   2. Semantic similarity - Embedding-based similarity to query text

   RRF (Reciprocal Rank Fusion) merges both ranked lists to produce
   results that capture both structural relationships AND semantic meaning.

   Args:
     event-store: Grain event store
     opts:
       :seed-uris - Collection of starting concept URIs for graph expansion
       :query-text - Natural language query for embedding search
       :scope - Filter to specific ontology scope
       :limit - Maximum results (default 10)
       :min-similarity - Minimum embedding similarity (default 0.3)
       :max-depth - BFS expansion depth (default 2)
       :decay - BFS decay factor (default 0.6)
       :weights - {:graph N :embedding N} weights for RRF

   Returns:
     {:results [{:uri :score :graph-rank :embedding-rank :label :description}]
      :graph-results [...]
      :embedding-results [...]
      :method \"rrf\"}"
  [event-store opts]
  (retrieval/hybrid-search event-store opts))

(defn hybrid-search-failures
  "Search failure concepts using hybrid graph + embedding search.

   Specialized search for finding relevant failure types.

   Args:
     event-store: Grain event store
     query-text: Description of the failure to find
     opts:
       :seed-failure-uri - Optional seed failure URI for graph expansion
       :limit - Maximum results (default 5)

   Returns:
     Vector of failure concepts with scores"
  [event-store query-text & {:keys [seed-failure-uri limit]
                              :or {limit 5}}]
  (retrieval/hybrid-search-failures event-store query-text
                                     :seed-failure-uri seed-failure-uri
                                     :limit limit))

(defn hybrid-search-patterns
  "Search success patterns using hybrid graph + embedding search.

   Specialized search for finding relevant success patterns.

   Args:
     event-store: Grain event store
     query-text: Description of the pattern to find
     opts:
       :seed-pattern-uri - Optional seed pattern URI for graph expansion
       :limit - Maximum results (default 5)

   Returns:
     Vector of success pattern concepts with scores"
  [event-store query-text & {:keys [seed-pattern-uri limit]
                              :or {limit 5}}]
  (retrieval/hybrid-search-patterns event-store query-text
                                     :seed-pattern-uri seed-pattern-uri
                                     :limit limit))

(defn build-ontology-context-with-embeddings
  "Build comprehensive ontology context using hybrid search.

   Enhanced version of build-ontology-context that leverages embeddings
   when available to provide more semantically relevant results.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Primary problem URI
       :problem-description - Natural language description (for embedding search)
       :required-patterns - Success patterns that should be used
       :user-domain - Domain context

   Returns:
     Same structure as build-ontology-context but with embedding-enhanced results"
  [event-store opts]
  (retrieval/build-ontology-context-with-embeddings event-store opts))

;; =============================================================================
;; Dynamic Field Analysis (Phase 4B)
;; =============================================================================

(defn analyze-fields-for-embedding
  "Explicitly analyze schema to determine embeddable fields using LLM.

   Unlike detect-embeddable-fields (heuristic-based), this uses an LLM
   to examine actual data samples and determine which fields contain
   semantic content worth embedding for similarity search.

   This is an EXPLICIT step - call it BEFORE embedding to get recommendations.
   Results can be reviewed, cached, or modified before use.

   Workflow:
   1. Call this function with schema + sample data
   2. Review the recommended fields and reasoning
   3. Optionally modify the field list
   4. Use embed-with-fields to embed data

   Args:
     ctx: Application context with sheet service
     schema: Malli [:map ...] schema to analyze
     opts:
       :sample-data - Sample records matching schema (recommended: 3-5)
       :confidence-threshold - Min confidence to include (default: 0.7)
       :sheet-id - Pre-built workflow sheet ID (optional, for reuse)

   Returns:
     {:embeddable-fields [:field1 :field2 ...]
      :confidence-scores {:field1 0.92 :field2 0.85}
      :reasoning {:field1 \"Contains natural language...\" ...}
      :method :llm
      :trace-id #uuid \"...\"}"
  [ctx schema & {:keys [sample-data confidence-threshold sheet-id]
                 :or {confidence-threshold 0.7}}]
  (fa-workflow/analyze-schema ctx sheet-id schema (or sample-data [])
                               :confidence-threshold confidence-threshold))

(defn build-field-analyzer-workflow!
  "Build the field analyzer ORC workflow for reuse.

   Returns a sheet-id that can be passed to analyze-fields-for-embedding
   for efficient repeated analysis.

   Args:
     ctx: Application context with sheet service

   Returns:
     sheet-id (uuid)"
  [ctx]
  (fa-workflow/build-field-analyzer! ctx))

(defn detect-embeddable-fields-heuristic
  "Schema + data heuristic detection (no LLM).

   Use this when:
   - LLM analysis is not needed (simple/known schemas)
   - LLM is unavailable or too slow
   - Testing or development

   Args:
     schema: Malli schema to analyze
     sample-data: Optional sample records
     opts:
       :threshold - Min confidence (default: 0.6)

   Returns:
     {:embeddable-fields [...]
      :confidence-scores {...}
      :reasoning {...}
      :method :heuristic}"
  [schema & [sample-data opts]]
  (field-analyzer/detect-embeddable-fields-heuristic
   schema (or sample-data [])
   :threshold (or (:threshold opts) 0.6)))

(defn embed-with-fields
  "Embed data using specified fields.

   Combines the values of specified fields into text and generates an embedding.

   Args:
     data: Record to embed
     fields: Collection of field names to include
     opts: {:model-id \"...\"}

   Returns:
     Embedding vector (384 dimensions for default model)"
  [data fields & {:keys [model-id] :as opts}]
  (let [text (field-analyzer/combine-fields-for-embedding data fields)]
    (embedding/embed-text text (or opts {}))))

(defn analyze-and-embed
  "Convenience function: analyze schema and embed data in one call.

   For simple cases where you don't need to review/modify the field selection.
   Uses the hybrid approach: tries LLM analysis, falls back to heuristics.

   Args:
     ctx: Application context (or nil for heuristic-only)
     schema: Malli schema
     data: Record to embed
     opts:
       :sample-data - Sample records for analysis
       :confidence-threshold - Min confidence (default: 0.7)
       :fallback-to-heuristic - If true, use heuristics when LLM fails (default: true)

   Returns:
     {:embedding [...] (384-dim vector)
      :fields-used [...]
      :method :llm or :heuristic}"
  [ctx schema data & {:keys [sample-data confidence-threshold fallback-to-heuristic]
                      :or {confidence-threshold 0.7 fallback-to-heuristic true}}]
  (let [analysis (if ctx
                   (try
                     (analyze-fields-for-embedding ctx schema
                                                    :sample-data (or sample-data [data])
                                                    :confidence-threshold confidence-threshold)
                     (catch Exception e
                       (when fallback-to-heuristic
                         (detect-embeddable-fields-heuristic schema [data]))))
                   (detect-embeddable-fields-heuristic schema [data]))
        fields (:embeddable-fields analysis)
        emb (when (seq fields)
              (embed-with-fields data fields))]
    {:embedding emb
     :fields-used fields
     :method (:method analysis)}))

;; =============================================================================
;; Pattern Discovery
;; =============================================================================

(defn get-low-scoring-evaluations
  "Get evaluation events with low aggregate scores.

   Reads :evaluation/trace-evaluated events from the event store and
   filters to those below the score threshold.

   Args:
   - event-store: Grain event store
   - sheet-id: UUID of the sheet to analyze
   - options:
     - :threshold - Score threshold (default 0.6)
     - :limit - Max evaluations to return (default 100)

   Returns vector of evaluation event bodies."
  ([ctx sheet-id]
   (discovery/get-low-scoring-evaluations ctx sheet-id {}))
  ([ctx sheet-id options]
   (discovery/get-low-scoring-evaluations ctx sheet-id options)))

(defn build-discovery-workflow!
  "Build the pattern discovery workflow. Returns sheet-id.

   This is idempotent - calling multiple times with the same definition
   will return the same sheet-id."
  [ctx]
  (discovery/build-discovery-workflow! ctx))

(defn discover-patterns
  "High-level API to run pattern discovery on a sheet's evaluations.

   Analyzes low-scoring evaluation feedback from the evaluation component
   to identify recurring failure patterns not covered by the current ontology.

   Args:
   - ctx: Context with event-store
   - sheet-id: Sheet to analyze
   - options:
     - :min-traces - Minimum traces required to run (default 20)
     - :score-threshold - Analyze traces below this score (default 0.6)

   Returns map with:
   - :discovered - count of new subtypes
   - :analyzed-traces - count of traces analyzed
   - :subtypes - vector of discovered subtype maps (with :parent-uri :proposed-uri :label :description :indicators :evidence-count)
   - :skipped - true if insufficient traces (with :reason :found :required)"
  ([ctx sheet-id]
   (discovery/discover-patterns ctx sheet-id {}))
  ([ctx sheet-id options]
   (discovery/discover-patterns ctx sheet-id options)))

;; =============================================================================
;; Cache Preloading (Warm Startup)
;; =============================================================================

(defn preload-ontology-cache!
  "Preload ontology read models into L1 cache for fast first queries.

   Call this at application startup to avoid cold-start latency.
   Grain's rmp/project provides L1/L2 caching, but the first call
   must build the projection from events.

   Preloads:
   - Concepts graph
   - Tree profiles
   - Concept embeddings
   - ColBERT index mappings

   Returns map with:
   - :preloaded - vector of preloaded read model names
   - :elapsed-ms - time taken to preload"
  [ctx]
  (let [start (System/currentTimeMillis)]
    ;; Force projection of all read models - this populates L1 cache
    (rmp/project ctx :ontology/concepts)
    (rmp/project ctx :ontology/tree-profiles)
    (rmp/project ctx :ontology/node-experiences)
    (rmp/project ctx :ontology/concept-embeddings)
    (rmp/project ctx :ontology/tree-profile-embeddings)
    (rmp/project ctx :ontology/ontology-colbert-indexes)

    (let [elapsed (- (System/currentTimeMillis) start)]
      {:preloaded [:concepts :tree-profiles :node-experiences
                   :concept-embeddings :tree-profile-embeddings
                   :ontology-colbert-indexes]
       :elapsed-ms elapsed})))

(defn get-colbert-index-for-ontology
  "Get the ColBERT index-id and metadata associated with an ontology.

   Returns nil if no ColBERT index exists for this ontology.

   Example:
   ```clojure
   (get-colbert-index-for-ontology ctx ontology-id)
   ;; => {:colbert-index-id uuid
   ;;     :index-name \"ontology-abc123\"
   ;;     :colbert-fields [:label :description]
   ;;     :document-count 150
   ;;     :indexed-at \"2024-01-15T...\"}
   ```"
  [ctx ontology-id]
  (rm/get-colbert-index-for-ontology ctx ontology-id))

;; =============================================================================
;; Self-Learning Retrieval (Domain-Agnostic)
;; =============================================================================

(defn find-self-patterns
  "Retrieve patterns from the current tree for self-reinforcing learning.

   Unlike find-success-patterns which aggregates across trees, this returns
   the patterns accumulated by THIS specific tree. This enables single-tree
   training scenarios where the tree learns from its own execution history.

   This function is domain-agnostic - works for drone control, legal review,
   sales outreach, construction planning, or any other domain.

   Args:
     ctx: System context with :event-store
     tree-id: UUID of the tree to get patterns for
     opts: {:min-confidence double} - minimum confidence threshold (default 0.2)

   Returns:
     {:strengths [{:uri :label :description :confidence :count
                   :context-conditions :action-taken :domain-type :expected-outcome} ...]
      :weaknesses [{:uri :description :frequency :severity :triggers
                    :failure-context :attempted-action :domain-type} ...]}

   Returns nil if tree has no profile or ctx/tree-id is nil.

   Example usage for different domains:
   ```clojure
   ;; Drone control
   (find-self-patterns ctx drone-tree-id {:min-confidence 0.3})
   ;; => {:strengths [{:context-conditions {:velocity 0.3 :battery 60}
   ;;                  :action-taken {:type \"stabilize\" :target [0 0 1.0]}
   ;;                  :domain-type \"drone-control\"}]}

   ;; Legal review
   (find-self-patterns ctx legal-tree-id {:min-confidence 0.5})
   ;; => {:strengths [{:context-conditions {:contract-value 1500000 :risk-score 0.3}
   ;;                  :action-taken {:type \"approve\" :reviewer \"senior-partner\"}
   ;;                  :domain-type \"legal-review\"}]}
   ```"
  [ctx tree-id opts]
  (retrieval/find-self-patterns ctx tree-id opts))

(defn build-actionable-context
  "Build complete actionable context combining self-patterns for LLM injection.

   This is the main entry point for generating context that enables
   true learning improvement during training. Works for any domain.

   Args:
     ctx: Context with event-store
     tree-id: Current tree ID
     problem-type: Problem type URI
     opts: Options for formatting
       :max-items - Max patterns to include (default 5)
       :include-patterns - Include success patterns (default true)
       :include-failures - Include failure patterns (default true)

   Returns:
     Map with:
       :formatted-context - Markdown string for LLM injection
       :self-patterns - Raw self-pattern data
       :has-patterns? - Whether any patterns were found
       :strength-count - Number of strength patterns
       :weakness-count - Number of weakness patterns

   The formatted-context produces actionable rules like:
   ```markdown
   ### Learned Rules from Success Patterns
   - When battery=15, altitude=2.0: return-home to [0 0 0] (90% success, 5 episodes)
   - When velocity=0.3, battery=60: navigate to [2 0 1.0] (85% success, 3 episodes)

   ### Patterns to Avoid
   - **Logical reasoning defects** (frequency: 100%)
   ```"
  [ctx tree-id problem-type & [opts]]
  (retrieval/build-actionable-context ctx tree-id problem-type opts))

;; =============================================================================
;; Learned Rules API (Self-Learning Read Model)
;; =============================================================================

(def learned-rule-events
  "Event types that affect the learned-rules read model"
  rm/learned-rule-events)

(defn get-tree-rules
  "Get learned rules for a specific tree.

   Returns vector of rules extracted from successful episodes:
   [{:rule-id uuid
     :condition {:field value ...}
     :action {:type \"action\" :target ...}
     :confidence 0.92
     :success-rate 0.95
     :evidence-episodes [uuid ...]
     :problem-type \"problem:Navigation\"
     :domain-type \"drone-control\"
     :extracted-at \"2024-01-15T...\"}]"
  [ctx tree-id]
  (rm/get-tree-rules ctx tree-id))

(defn get-all-learned-rules
  "Get all learned rules for all trees.

   Returns map of {tree-id -> [rule ...]}"
  [ctx]
  (rm/get-all-learned-rules ctx))

(defn find-rules-by-problem
  "Find rules that were extracted for a specific problem type.

   Returns flat vector of rules across all trees."
  [ctx problem-type]
  (rm/find-rules-by-problem ctx problem-type))

(defn find-rules-by-condition
  "Find rules that match given condition criteria.

   conditions: Map of condition key-value pairs to match
   Returns rules where all specified conditions are present."
  [ctx conditions]
  (rm/find-rules-by-condition ctx conditions))

(defn learned-rules-statistics
  "Get statistics about learned rules.

   Returns:
   {:total-rules N
    :trees-with-rules N
    :by-problem-type {\"problem:Nav\" 5 ...}
    :by-domain-type {\"drone-control\" 8 ...}
    :avg-confidence 0.88
    :avg-success-rate 0.92}"
  [ctx]
  (rm/learned-rules-statistics ctx))

;; =============================================================================
;; Rule Extraction API (Self-Learning)
;; =============================================================================

(defn extract-rules
  "High-level API to extract rules from a tree's successful episodes.

   Domain-agnostic: Works with any domain by accepting domain-type and domain-description.

   Args:
   - ctx: Context with event-store
   - tree-id: Tree to analyze
   - options:
     - :domain-type - Domain identifier, e.g. 'drone-control', 'legal-review' (required)
     - :domain-description - Human-readable context for LLM (required)
     - :min-episodes - Minimum episodes required (default 5)

   Returns map with:
   - :extracted - count of rules extracted
   - :analyzed-episodes - count of episodes analyzed
   - :rules - vector of rule maps (with condition-description and action-description)
   - :skipped - true if insufficient episodes
   - :domain-type - Domain that was analyzed"
  [ctx tree-id opts]
  (rule-extraction/extract-rules ctx tree-id opts))

;; =============================================================================
;; Apartment Search Data Access
;; =============================================================================

(def apartment-listing-events
  "Event types that affect apartment listings read model."
  rm/apartment-listing-events)

(def apartment-search-events
  "Event types that affect apartment searches read model."
  rm/apartment-search-events)

(defn get-listing
  "Get an apartment listing by ID."
  [ctx listing-id]
  (rm/get-listing ctx listing-id))

(defn get-listing-by-url
  "Get an apartment listing by source URL."
  [ctx source-url]
  (rm/get-listing-by-url ctx source-url))

(defn get-listings-by-city
  "Get all apartment listings for a city.
   Options:
   - :limit - Maximum listings to return"
  [ctx city & [opts]]
  (rm/get-listings-by-city ctx city opts))

(defn get-listings-by-site
  "Get all apartment listings from a specific site.
   Options:
   - :limit - Maximum listings to return"
  [ctx source-site & [opts]]
  (rm/get-listings-by-site ctx source-site opts))

(defn get-listings-by-price-range
  "Get apartment listings within a price range.
   Args:
   - min-price: Minimum price (numeric)
   - max-price: Maximum price (numeric)
   Options:
   - :city - Filter by city
   - :limit - Maximum listings to return"
  [ctx min-price max-price & [opts]]
  (rm/get-listings-by-price-range ctx min-price max-price opts))

(defn get-price-history
  "Get price history for a listing."
  [ctx listing-id]
  (rm/get-price-history ctx listing-id))

(defn get-recent-searches
  "Get recent apartment searches.
   Options:
   - :location - Filter by location
   - :limit - Maximum searches to return (default 20)"
  [ctx & [opts]]
  (rm/get-recent-searches ctx opts))

(defn apartment-statistics
  "Get statistics about stored apartment listings.
   Returns:
   - :total-listings - Total number of listings
   - :listings-by-city - Map of city to count
   - :listings-by-site - Map of site to count
   - :total-searches - Total search sessions
   - :price-range - {:min :max :avg} for numeric prices"
  [ctx]
  (rm/apartment-statistics ctx))
