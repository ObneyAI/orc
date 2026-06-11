(ns ai.obney.orc.ontology.core.retrieval
  "Few-shot retrieval for finding similar trees and patterns.

   Provides:
   - find-similar-trees - Find trees by problem type + success patterns
   - find-failure-patterns - Aggregate failure patterns for a problem type
   - build-ontology-context - Context for MCP sheet builder integration
   - expand-concept-neighborhood - BFS expansion from seed concepts
   - semantic-search-concepts - Embedding-based similarity search
   - hybrid-search - Combines graph BFS + embeddings + ColBERT via RRF

   Uses BFS spreading activation and RRF fusion from graph.clj"
  (:require [ai.obney.orc.ontology.core.graph :as graph]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.string :as str]))

;; =============================================================================
;; Forward Declarations
;; =============================================================================

;; Forward declare functions that are defined later but used earlier
(declare find-self-patterns format-rich-pattern)

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-retrieval-config
  "Default configuration for retrieval operations."
  {:min-success-rate 0.7       ;; Minimum success rate for similar trees
   :min-confidence   0.5       ;; Minimum pattern confidence
   :min-frequency    0.2       ;; Minimum failure frequency to report
   :max-results      10        ;; Maximum results to return
   :bfs-max-depth    2         ;; BFS expansion depth
   :bfs-decay        0.6       ;; BFS decay factor
   :rrf-k            60})      ;; RRF fusion constant

;; =============================================================================
;; Concept Graph Building
;; =============================================================================

(defn build-concept-graph
  "Build a concept graph from event store or static ontology.

   Args:
     ctx: Context with :event-store and :tenant-id (optional, uses static if nil)

   Returns:
     Graph map with :nodes and :edges suitable for graph algorithms"
  [{:keys [event-store] :as ctx}]
  (let [;; Get concepts from event store or use static
        concepts (if event-store
                   (rmp/project ctx :ontology/concepts)
                   ;; Build from static ontology
                   (reduce (fn [acc concept]
                             (assoc acc (:uri concept)
                                    {:uri (:uri concept)
                                     :label (:label concept)
                                     :scope (:scope concept)
                                     :broader (set (or (:broader concept) []))
                                     :narrower #{}
                                     :related #{}}))
                           {}
                           (static/get-all-static-concepts)))]
    ;; Convert to graph format
    (graph/concepts->graph concepts)))

(defn build-static-concept-graph
  "Build concept graph from static ontology only (no event store required)."
  []
  (let [all-concepts (static/get-all-static-concepts)
        all-relationships (static/get-all-static-relationships)

        ;; Build initial concept map
        concept-map (reduce (fn [acc c]
                              (assoc acc (:uri c)
                                     {:uri (:uri c)
                                      :label (:label c)
                                      :scope (:scope c)
                                      :broader (set (or (:broader c) []))
                                      :narrower #{}
                                      :related #{}}))
                            {}
                            all-concepts)

        ;; Add narrower links (inverse of broader)
        concept-map (reduce (fn [acc c]
                              (reduce (fn [a broader-uri]
                                        (update-in a [broader-uri :narrower]
                                                   (fnil conj #{}) (:uri c)))
                                      acc
                                      (or (:broader c) [])))
                            concept-map
                            all-concepts)

        ;; Add explicit relationships
        concept-map (reduce (fn [acc {:keys [source target predicate]}]
                              (case predicate
                                "skos:related"
                                (-> acc
                                    (update-in [source :related] (fnil conj #{}) target)
                                    (update-in [target :related] (fnil conj #{}) source))
                                acc))
                            concept-map
                            all-relationships)]

    (graph/concepts->graph concept-map)))

;; =============================================================================
;; Expand Concept Neighborhood
;; =============================================================================

(defn expand-concept-neighborhood
  "Expand from seed concept URIs using BFS spreading activation.

   Args:
     seed-uris: Collection of starting concept URIs
     opts: {:max-depth N :decay N :graph graph} - optional graph, otherwise uses static

   Returns:
     Vector of {:uri :score :path :depth} sorted by score"
  [seed-uris & {:keys [max-depth decay graph]
                :or {max-depth 2 decay 0.6}}]
  (let [g (or graph (build-static-concept-graph))]
    (graph/bfs-spreading-activation g seed-uris
                                    {:max-depth max-depth
                                     :decay decay
                                     :min-activation 0.01})))

(defn expand-from-problem-type
  "Expand concept neighborhood starting from a problem type URI.

   Returns related problems, success patterns, and failure types."
  [problem-uri & {:keys [max-depth] :or {max-depth 2}}]
  (expand-concept-neighborhood [problem-uri] :max-depth max-depth))

;; =============================================================================
;; Find Similar Trees
;; =============================================================================

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
  [ctx {:keys [problem-type required-patterns exclude-patterns
                       min-success-rate limit]
                :or {min-success-rate 0.7 limit 10}}]
  (let [;; Get all tree profiles
        profiles (rm/get-all-tree-profiles ctx)

        ;; Build concept graph for neighborhood expansion
        graph (build-static-concept-graph)

        ;; If problem-type specified, expand to related problems
        related-problems (when problem-type
                           (->> (graph/bfs-spreading-activation
                                 graph [problem-type]
                                 {:max-depth 2 :decay 0.5})
                                (map :uri)
                                (set)))

        ;; Expand required-patterns to include related patterns
        expanded-patterns (when (seq required-patterns)
                            (->> required-patterns
                                 (mapcat #(map :uri
                                               (graph/bfs-spreading-activation
                                                graph [%] {:max-depth 1 :decay 0.8})))
                                 (set)))]

    (->> profiles
         vals
         ;; Filter by problem match
         (filter (fn [p]
                   (or (nil? problem-type)
                       (some (fn [solve]
                               (and (>= (:success-rate solve) min-success-rate)
                                    (or (= problem-type (:problem-uri solve))
                                        (contains? related-problems (:problem-uri solve)))))
                             (:solves p)))))

         ;; Filter by required patterns
         (filter (fn [p]
                   (or (nil? required-patterns)
                       (empty? required-patterns)
                       (let [tree-patterns (set (map :pattern (:strengths p)))]
                         (some #(or (contains? tree-patterns %)
                                    (contains? (set expanded-patterns) %))
                               required-patterns)))))

         ;; Filter out excluded patterns
         (filter (fn [p]
                   (or (nil? exclude-patterns)
                       (empty? exclude-patterns)
                       (let [tree-patterns (set (map :pattern (:strengths p)))]
                         (not (some #(contains? tree-patterns %) exclude-patterns))))))

         ;; Score and rank
         (map (fn [p]
                (let [;; Problem match score
                      problem-match (when problem-type
                                      (some (fn [solve]
                                              (cond
                                                (= problem-type (:problem-uri solve))
                                                {:uri (:problem-uri solve)
                                                 :score 1.0
                                                 :success-rate (:success-rate solve)}

                                                (contains? related-problems (:problem-uri solve))
                                                {:uri (:problem-uri solve)
                                                 :score 0.8
                                                 :success-rate (:success-rate solve)}))
                                            (:solves p)))

                      ;; Pattern match score
                      matching-patterns (when (seq required-patterns)
                                          (let [tree-patterns (set (map :pattern (:strengths p)))]
                                            (filter #(or (contains? tree-patterns %)
                                                         (contains? (set expanded-patterns) %))
                                                    required-patterns)))

                      ;; Strength score - average confidence of strengths
                      strength-score (if (seq (:strengths p))
                                       (/ (reduce + 0 (map :confidence (:strengths p)))
                                          (count (:strengths p)))
                                       0.0)

                      ;; Weakness penalty - average frequency of weaknesses
                      weakness-penalty (if (seq (:weaknesses p))
                                         (* 0.2 (/ (reduce + 0 (map :frequency (:weaknesses p)))
                                                   (count (:weaknesses p))))
                                         0.0)

                      ;; Combined score
                      score (+ (* 0.4 (or (:score problem-match) 0.5))
                               (* 0.3 strength-score)
                               (* 0.2 (/ (count matching-patterns)
                                         (max 1 (count required-patterns))))
                               (- (* 0.1 weakness-penalty)))]

                  {:tree-id (:tree-id p)
                   :profile p
                   :score score
                   :matching-patterns (vec matching-patterns)
                   :problem-match problem-match})))

         ;; Sort by score descending
         (sort-by :score >)

         ;; Take limit
         (take limit)

         vec)))

;; =============================================================================
;; Find Failure Patterns
;; =============================================================================

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
     Vector of {:failure-uri :subtype-uri :total-occurrences :avg-frequency
                :severity-distribution :common-triggers :tree-count}"
  [ctx {:keys [problem-type failure-uri min-frequency limit]
                :or {min-frequency 0.2 limit 20}}]
  (let [;; Get all tree profiles
        profiles (rm/get-all-tree-profiles ctx)

        ;; Filter to problem type if specified
        relevant-profiles (if problem-type
                            (filter (fn [[_ p]]
                                      (some #(= problem-type (:problem-uri %))
                                            (:solves p)))
                                    profiles)
                            profiles)

        ;; Collect all weaknesses
        all-weaknesses (->> relevant-profiles
                            vals
                            (mapcat :weaknesses)
                            (filter #(>= (:frequency %) min-frequency))
                            (filter #(or (nil? failure-uri)
                                         (= failure-uri (:failure %))
                                         (= failure-uri (:subtype %)))))

        ;; Group by failure URI
        by-failure (group-by :failure all-weaknesses)]

    (->> by-failure
         (map (fn [[uri weaknesses]]
                (let [subtypes (frequencies (keep :subtype weaknesses))
                      severities (frequencies (map :severity weaknesses))
                      triggers (->> weaknesses
                                    (mapcat :triggers)
                                    frequencies
                                    (sort-by val >)
                                    (take 5)
                                    (map first))]
                  {:failure-uri uri
                   :subtype-distribution subtypes
                   :total-occurrences (count weaknesses)
                   :avg-frequency (/ (reduce + 0 (map :frequency weaknesses))
                                     (count weaknesses))
                   :severity-distribution severities
                   :common-triggers (vec triggers)
                   :tree-count (count (distinct (keep :tree-id weaknesses)))})))

         ;; Sort by total occurrences
         (sort-by :total-occurrences >)

         (take limit)

         vec)))

(defn find-patterns-to-avoid
  "Find failure patterns that should be avoided for a problem type.

   Identifies high-frequency, high-severity failures with actionable triggers."
  [ctx {:keys [problem-type] :as opts}]
  (->> (find-failure-patterns ctx opts)
       (filter #(or (> (:avg-frequency %) 0.3)
                    (get-in % [:severity-distribution :critical])
                    (get-in % [:severity-distribution :high])))
       (map (fn [fp]
              {:failure-uri (:failure-uri fp)
               :description (:description (static/get-concept-by-uri (:failure-uri fp)))
               :frequency (:avg-frequency fp)
               :severity (first (keys (sort-by val > (:severity-distribution fp))))
               :triggers (:common-triggers fp)}))
       vec))

;; =============================================================================
;; Find Success Patterns
;; =============================================================================

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
  [ctx {:keys [problem-type min-success-rate min-confidence limit]
                :or {min-success-rate 0.8 min-confidence 0.7 limit 10}}]
  (let [;; Get all tree profiles
        profiles (rm/get-all-tree-profiles ctx)

        ;; Filter to high-performing trees for this problem type
        high-performers (if problem-type
                          (->> profiles
                               vals
                               (filter (fn [p]
                                         (some (fn [solve]
                                                 (and (= problem-type (:problem-uri solve))
                                                      (>= (:success-rate solve) min-success-rate)))
                                               (:solves p)))))
                          ;; Without problem type, use average success rate
                          (->> profiles
                               vals
                               (filter (fn [p]
                                         (when-let [solves (seq (:solves p))]
                                           (>= (/ (reduce + 0 (map :success-rate solves))
                                                  (count solves))
                                               min-success-rate))))))

        ;; Collect all strengths above confidence threshold
        all-strengths (->> high-performers
                           (mapcat :strengths)
                           (filter #(>= (:confidence %) min-confidence)))

        ;; Group by pattern URI
        by-pattern (group-by :pattern all-strengths)]

    (->> by-pattern
         (map (fn [[uri strengths]]
                (let [concept (static/get-concept-by-uri uri)]
                  {:pattern-uri uri
                   :label (:label concept)
                   :description (:description concept)
                   :avg-confidence (/ (reduce + 0 (map :confidence strengths))
                                      (count strengths))
                   :avg-score (/ (reduce + 0 (map :avg-score strengths))
                                 (count strengths))
                   :tree-count (count strengths)
                   :evidence-count (reduce + 0 (map :evidence-count strengths))})))

         ;; Sort by tree count then avg-confidence
         (sort-by (juxt :tree-count :avg-confidence) >)

         (take limit)

         vec)))

;; =============================================================================
;; Build Ontology Context
;; =============================================================================

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
      :recommended-patterns [...] - Success patterns to use
      :patterns-to-avoid [...] - Common failure patterns
      :related-concepts [...] - Neighborhood expansion for context
      :problem-hierarchy {...} - Problem type with parent/child
      :self-patterns {...} - Tree's own patterns (when self-learning enabled)}"
  [ctx {:keys [problem-type required-patterns user-domain tree-id self-learning?]
        :as opts}]
  (let [;; Self-learning: include tree's own patterns
        self-patterns (when (and self-learning? tree-id)
                        (find-self-patterns ctx tree-id {:min-confidence 0.2}))

        ;; Find similar successful trees (very low thresholds to capture all patterns)
        few-shot-trees (when ctx
                         (find-similar-trees ctx
                                             {:problem-type problem-type
                                              :required-patterns required-patterns
                                              :min-success-rate 0.0  ;; Accept any tree with problem mapping
                                              :limit 5}))

        ;; Find recommended success patterns (very low thresholds for bootstrapping)
        cross-tree-patterns (if ctx
                              (find-success-patterns ctx
                                                     {:problem-type problem-type
                                                      :min-success-rate 0.0  ;; Accept any tree
                                                      :min-confidence 0.1   ;; Accept low confidence patterns
                                                      :limit 8})
                              ;; Fallback to static patterns
                              (->> (static/get-concepts-by-scope :success)
                                   (filter #(not= "success:Root" (:uri %)))
                                   (filter #(not (contains? #{"success:StructuralPattern"
                                                              "success:InstructionPattern"
                                                              "success:DataFlowPattern"}
                                                            (:uri %))))
                                   (map (fn [c]
                                          {:pattern-uri (:uri c)
                                           :label (:label c)
                                           :description (:description c)
                                           :avg-confidence 0.5}))))

        ;; Merge self-patterns with cross-tree patterns
        ;; Self-patterns appear first (most relevant to this tree)
        recommended-patterns (vec (concat (:strengths self-patterns)
                                          cross-tree-patterns))

        ;; Find patterns to avoid from cross-tree sources
        cross-tree-avoid (when ctx
                           (find-patterns-to-avoid ctx
                                                   {:problem-type problem-type}))

        ;; Merge self-weaknesses with cross-tree patterns to avoid
        patterns-to-avoid (vec (concat (:weaknesses self-patterns)
                                       cross-tree-avoid))

        ;; Get related concepts via graph expansion
        related-concepts (when problem-type
                           (->> (expand-concept-neighborhood [problem-type] :max-depth 2)
                                (remove #(= problem-type (:uri %)))
                                (take 10)))

        ;; Build problem hierarchy
        problem-hierarchy (when problem-type
                            (let [concept (static/get-concept-by-uri problem-type)]
                              {:uri problem-type
                               :label (:label concept)
                               :description (:description concept)
                               :broader (mapv (fn [uri]
                                                {:uri uri
                                                 :label (:label (static/get-concept-by-uri uri))})
                                              (or (:broader concept) []))
                               :narrower (->> (static/get-narrower-concepts problem-type)
                                              (mapv (fn [c]
                                                      {:uri (:uri c)
                                                       :label (:label c)})))}))]

    {:few-shot-trees (vec few-shot-trees)
     :recommended-patterns recommended-patterns
     :patterns-to-avoid patterns-to-avoid
     :related-concepts (vec related-concepts)
     :problem-hierarchy problem-hierarchy
     :self-patterns self-patterns  ;; Include for debugging/inspection
     :context-metadata {:problem-type problem-type
                        :required-patterns required-patterns
                        :user-domain user-domain
                        :tree-id tree-id
                        :self-learning? self-learning?
                        :generated-at (str (java.time.Instant/now))}}))

;; =============================================================================
;; Multi-Source Retrieval with RRF
;; =============================================================================

(defn retrieve-with-rrf
  "Retrieve and merge results from multiple sources using RRF.

   Useful when combining results from:
   - Graph-based expansion
   - Pattern matching
   - Problem type matching

   Args:
     batches: Vector of result batches, each [{:uri :score} ...]
     opts: {:k N} - RRF constant (default 60)

   Returns:
     Merged and re-ranked results"
  [batches & {:keys [k] :or {k 60}}]
  (graph/merge-batches batches :method "rrf"))

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
  [ctx query-concepts {:keys [max-depth] :or {max-depth 2} :as opts}]
  (let [;; Graph expansion batch
        graph-batch (->> (expand-concept-neighborhood (vec query-concepts)
                                                      :max-depth max-depth)
                         (take 20))

        ;; Similar trees batch
        tree-batch (->> (find-similar-trees ctx
                                            {:problem-type (first query-concepts)
                                             :limit 10})
                        (map (fn [t] {:uri (str (:tree-id t))
                                      :score (:score t)})))

        ;; Success patterns batch
        pattern-batch (->> (find-success-patterns ctx opts)
                           (map (fn [p] {:uri (:pattern-uri p)
                                         :score (:avg-confidence p)})))

        ;; Merge all batches with RRF
        merged (retrieve-with-rrf [graph-batch tree-batch pattern-batch])]

    {:graph-expansion graph-batch
     :similar-trees tree-batch
     :success-patterns pattern-batch
     :merged-scores merged}))

;; =============================================================================
;; Semantic Search with Embeddings
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
       :ontology-id - Filter by single ontology-id
       :ontology-ids - Filter by multiple ontology-ids (returns union)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum cosine similarity threshold (default 0.5)
       :model-id - Embedding model to use (default: all-MiniLM-L6-v2)

   Returns:
     Vector of {:uri :similarity :label :description} sorted by similarity

   Requires concept embeddings to be generated first via ontology/embed-concepts-batch."
  [ctx query-text & {:keys [scope ontology-id ontology-ids limit min-similarity model-id]
                      :or {limit 10 min-similarity 0.5}}]
  (when (and ctx query-text)
    (let [;; Generate query embedding
          query-embedding (embedding/embed-text query-text
                                                 (when model-id {:model-id model-id}))

          ;; Get stored concept embeddings (filtered by scope and/or ontology-id)
          filter-opts (cond-> {}
                        scope (assoc :scope scope)
                        ontology-id (assoc :ontology-id ontology-id)
                        ontology-ids (assoc :ontology-ids ontology-ids))
          concept-embeddings (rm/get-all-concept-embeddings ctx
                                                             (when (seq filter-opts) filter-opts))]

      (when (and query-embedding (seq concept-embeddings))
        ;; Search using embedding similarity
        (->> (embedding/search-concepts-by-embedding
               query-embedding
               concept-embeddings
               :limit limit
               :min-similarity min-similarity)

             ;; Enrich with concept metadata from static ontology
             (map (fn [{:keys [uri similarity]}]
                    (let [concept (static/get-concept-by-uri uri)]
                      {:uri uri
                       :similarity similarity
                       :label (:label concept)
                       :description (:description concept)
                       :scope (:scope concept)})))

             vec)))))

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
  [ctx query-text & {:keys [limit min-similarity]
                      :or {limit 5 min-similarity 0.4}}]
  (when (and ctx query-text)
    (let [query-embedding (embedding/embed-text query-text)
          tree-embeddings (rm/get-all-tree-profile-embeddings ctx)]

      (when (and query-embedding (seq tree-embeddings))
        (->> tree-embeddings
             (map (fn [[tree-id data]]
                    (when (:embedding data)
                      {:tree-id tree-id
                       :similarity (embedding/cosine-similarity query-embedding
                                                                 (:embedding data))})))
             (remove nil?)
             (filter #(>= (:similarity %) min-similarity))
             (sort-by :similarity >)
             (take limit)
             ;; Add full profile data
             (map (fn [{:keys [tree-id] :as result}]
                    (assoc result :profile (rm/get-tree-profile ctx tree-id))))
             vec)))))

;; =============================================================================
;; ColBERT Integration Helpers
;; =============================================================================

(defn- resolve-colbert-search-fn
  "Dynamically resolve the ColBERT search-for-rrf function if available.

   Returns the function or nil if ColBERT component is not loaded."
  []
  (try
    (when-let [ns (find-ns 'ai.obney.orc.colbert.interface)]
      (ns-resolve ns 'search-for-rrf))
    (catch Exception _ nil)))

(defn colbert-search-concepts
  "Search concepts using ColBERT late-interaction retrieval.

   This function provides ColBERT search results formatted for RRF fusion
   with graph and embedding signals. Requires the ColBERT component to be
   available and an index to be created.

   Args:
     ctx - Context map containing :event-store
     query-text - Natural language search query
     opts:
       :colbert-index-id - ColBERT index UUID (required)
       :limit - Maximum results (default 20)
       :weight - Score weight for RRF (default 1.0)

   Returns:
     Vector of {:uri :score} or nil if ColBERT not available"
  [ctx query-text & {:keys [colbert-index-id limit weight]
                      :or {limit 20 weight 1.0}}]
  (when-let [search-fn (resolve-colbert-search-fn)]
    (when colbert-index-id
      (try
        (search-fn ctx {:query query-text
                        :index-id colbert-index-id
                        :k limit
                        :normalize? true
                        :weight weight})
        (catch Exception e
          ;; Log but don't fail - ColBERT is optional
          (println "ColBERT search failed:" (.getMessage e))
          nil)))))

(defn- resolve-colbert-search-batch-fn
  "Dynamically resolve the ColBERT search-for-rrf-batch function if available."
  []
  (try
    (when-let [ns (find-ns 'ai.obney.orc.colbert.interface)]
      (ns-resolve ns 'search-for-rrf-batch))
    (catch Exception _ nil)))

(defn colbert-search-concepts-batch
  "Batched `colbert-search-concepts`: ONE index load + ONE bridge round-trip for ALL
   queries. Returns a vector aligned to `query-texts`, each a vector of {:uri :score}
   for RRF fusion — or nil when ColBERT is unavailable.

   Args:
     ctx - Context map containing :event-store
     query-texts - Vector of natural-language search queries
     opts:
       :colbert-index-id - ColBERT index UUID (required)
       :limit - Maximum results per query (default 20)
       :weight - Score weight for RRF (default 1.0)"
  [ctx query-texts & {:keys [colbert-index-id limit weight]
                      :or {limit 20 weight 1.0}}]
  (when-let [search-fn (resolve-colbert-search-batch-fn)]
    (when (and colbert-index-id (seq query-texts))
      (try
        (search-fn ctx {:queries (vec query-texts)
                        :index-id colbert-index-id
                        :k limit
                        :normalize? true
                        :weight weight})
        (catch Exception e
          ;; Log but don't fail - ColBERT is optional
          (println "ColBERT batch search failed:" (.getMessage e))
          nil)))))

;; =============================================================================
;; Hybrid Search (Graph + Embeddings + ColBERT via RRF)
;; =============================================================================

(defn- fuse-and-enrich
  "Fuse graph/embedding/colbert result-lists via RRF and enrich the top `limit` with
   concept metadata + per-signal ranks. The shared fusion core of hybrid-search (one
   query) and hybrid-search-batch (per query). Input result maps carry their native
   score keys: graph/colbert use :score, embedding uses :similarity."
  [{:keys [graph-results embedding-results colbert-results weights limit signals]
    :or {weights {:graph 1.0 :embedding 1.0 :colbert 1.0} limit 10}}]
  (let [;; Prepare batches for RRF — each batch is [{:uri :score} ...]
        graph-batch (when (seq graph-results)
                      (mapv (fn [{:keys [uri score]}]
                              {:uri uri :score (* (:graph weights 1.0) score)})
                            graph-results))
        embedding-batch (when (seq embedding-results)
                          (mapv (fn [{:keys [uri similarity]}]
                                  {:uri uri :score (* (:embedding weights 1.0) similarity)})
                                embedding-results))
        ;; ColBERT batch is already formatted with :uri :score
        colbert-batch (when (seq colbert-results) colbert-results)
        batches (filterv seq [graph-batch embedding-batch colbert-batch])
        merged (when (seq batches)
                 (graph/merge-batches batches :method "rrf"))
        graph-ranks (when graph-batch
                      (into {} (map-indexed (fn [i {:keys [uri]}] [uri (inc i)]) graph-batch)))
        embedding-ranks (when embedding-batch
                          (into {} (map-indexed (fn [i {:keys [uri]}] [uri (inc i)]) embedding-batch)))
        colbert-ranks (when colbert-batch
                        (into {} (map-indexed (fn [i {:keys [uri]}] [uri (inc i)]) colbert-batch)))
        enriched-results (->> merged
                              (take limit)
                              (mapv (fn [{:keys [uri score]}]
                                      (let [concept (static/get-concept-by-uri uri)]
                                        {:uri uri
                                         :score score
                                         :graph-rank (get graph-ranks uri)
                                         :embedding-rank (get embedding-ranks uri)
                                         :colbert-rank (get colbert-ranks uri)
                                         :label (:label concept)
                                         :description (:description concept)
                                         :scope (:scope concept)}))))]
    {:results enriched-results
     :graph-results (vec graph-results)
     :embedding-results (vec embedding-results)
     :colbert-results (vec colbert-results)
     :method "rrf"
     :signals-enabled signals
     :batches-used (cond-> []
                     (seq graph-batch) (conj :graph)
                     (seq embedding-batch) (conj :embedding)
                     (seq colbert-batch) (conj :colbert))}))

(defn hybrid-search
  "Hybrid search combining graph-based BFS, embedding similarity, and ColBERT via RRF.

   This is the primary search function that leverages up to three signals:
   1. Graph structure - BFS spreading activation from seed concepts
   2. Semantic similarity - Embedding-based similarity to query text
   3. ColBERT late-interaction - Token-level retrieval (when index provided)

   RRF (Reciprocal Rank Fusion) merges all ranked lists to produce
   results that capture structural relationships, semantic meaning,
   AND token-level precision.

   Args:
     event-store: Grain event store (also passed as ctx for ColBERT)
     opts:
       :seed-uris - Collection of starting concept URIs for graph expansion
       :query-text - Natural language query for embedding/ColBERT search
       :scope - Filter to specific ontology scope
       :ontology-id - Filter by single ontology-id
       :ontology-ids - Filter by multiple ontology-ids (returns union)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum embedding similarity (default 0.3)
       :max-depth - BFS expansion depth (default 2)
       :decay - BFS decay factor (default 0.6)
       :colbert-index-id - UUID of ColBERT index (required for ColBERT signal)
       :weights - {:graph N :embedding N :colbert N} weights for RRF
                  (default {:graph 1.0 :embedding 1.0 :colbert 1.0})
       :signals - Set of enabled signals #{:graph :embedding :colbert}
                  (default: all signals enabled)

   Returns:
     {:results [{:uri :score :graph-rank :embedding-rank :colbert-rank :label :description}]
      :graph-results [...]
      :embedding-results [...]
      :colbert-results [...]
      :method \"rrf\"
      :batches-used [:graph :embedding :colbert]}"
  [ctx {:keys [seed-uris query-text scope ontology-id ontology-ids limit min-similarity max-depth decay
                       colbert-index-id weights signals]
                :or {limit 10 min-similarity 0.3 max-depth 2 decay 0.6
                     weights {:graph 1.0 :embedding 1.0 :colbert 1.0}
                     signals #{:graph :embedding :colbert}}}]
  (let [;; Check which signals are enabled
        graph-enabled? (contains? signals :graph)
        embedding-enabled? (contains? signals :embedding)
        colbert-enabled? (contains? signals :colbert)

        ;; Graph-based BFS results (when enabled and seeds provided)
        graph-results (when (and graph-enabled? (seq seed-uris))
                        (->> (expand-concept-neighborhood seed-uris
                                                          :max-depth max-depth
                                                          :decay decay)
                             (take (* 2 limit))))  ;; Get more for fusion

        ;; Embedding-based results (when enabled and query provided)
        embedding-results (when (and embedding-enabled? query-text)
                            (semantic-search-concepts ctx query-text
                                                       :scope scope
                                                       :ontology-id ontology-id
                                                       :ontology-ids ontology-ids
                                                       :limit (* 2 limit)
                                                       :min-similarity min-similarity))

        ;; ColBERT results (when enabled, query provided, and index available)
        colbert-results (when (and colbert-enabled? query-text colbert-index-id)
                          (colbert-search-concepts ctx query-text
                                                    :colbert-index-id colbert-index-id
                                                    :limit (* 2 limit)
                                                    :weight (:colbert weights 1.0)))]

    ;; RRF-fuse the three signals + enrich (shared with hybrid-search-batch).
    (fuse-and-enrich {:graph-results graph-results
                      :embedding-results embedding-results
                      :colbert-results colbert-results
                      :weights weights
                      :limit limit
                      :signals signals})))

(defn hybrid-search-batch
  "Batched hybrid-search over MANY query-texts. The ColBERT signal is computed in ONE
   batched pass (index loaded ONCE, one bridge round-trip); embedding + graph stay
   per-query (in-JVM, no subprocess). Returns a vector of per-query result-maps, each
   shaped EXACTLY like hybrid-search's output, aligned to `:query-texts`.

   Use this instead of mapping hybrid-search per query over a whole transcript: it
   collapses N ColBERT round-trips (and N index loads) into one, with no per-search
   reload, while keeping the embedding+ColBERT RRF fusion substance-identical to the
   single-query path.

   Args:
     ctx - Context map containing :event-store
     opts - same as hybrid-search, except:
       :query-texts - Vector of natural-language queries (instead of :query-text)
       :seed-uris   - shared graph seeds applied to every query (usually nil)"
  [ctx {:keys [query-texts seed-uris scope ontology-id ontology-ids limit min-similarity
               max-depth decay colbert-index-id weights signals]
        :or {limit 10 min-similarity 0.3 max-depth 2 decay 0.6
             weights {:graph 1.0 :embedding 1.0 :colbert 1.0}
             signals #{:graph :embedding :colbert}}}]
  (let [graph-enabled? (contains? signals :graph)
        embedding-enabled? (contains? signals :embedding)
        colbert-enabled? (contains? signals :colbert)
        query-texts (vec query-texts)
        ;; ColBERT — ONE batched pass for ALL queries (index loaded once, reused).
        colbert-batches (when (and colbert-enabled? (seq query-texts) colbert-index-id)
                          (colbert-search-concepts-batch ctx query-texts
                                                         :colbert-index-id colbert-index-id
                                                         :limit (* 2 limit)
                                                         :weight (:colbert weights 1.0)))]
    (mapv (fn [i query-text]
            (let [graph-results (when (and graph-enabled? (seq seed-uris))
                                  (->> (expand-concept-neighborhood seed-uris
                                                                    :max-depth max-depth
                                                                    :decay decay)
                                       (take (* 2 limit))))
                  embedding-results (when (and embedding-enabled? query-text)
                                      (semantic-search-concepts ctx query-text
                                                                 :scope scope
                                                                 :ontology-id ontology-id
                                                                 :ontology-ids ontology-ids
                                                                 :limit (* 2 limit)
                                                                 :min-similarity min-similarity))
                  colbert-results (when colbert-batches (nth colbert-batches i nil))]
              (fuse-and-enrich {:graph-results graph-results
                                :embedding-results embedding-results
                                :colbert-results colbert-results
                                :weights weights
                                :limit limit
                                :signals signals})))
          (range)
          query-texts)))

(defn hybrid-search-failures
  "Search failure concepts using hybrid graph + embedding + ColBERT search.

   Specialized search for finding relevant failure types.

   Args:
     event-store: Grain event store
     query-text: Description of the failure to find
     opts:
       :seed-failure-uri - Optional seed failure URI for graph expansion
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)
       :limit - Maximum results (default 5)

   Returns:
     Vector of failure concepts with scores"
  [ctx query-text & {:keys [seed-failure-uri colbert-index-id signals limit]
                              :or {limit 5
                                   signals #{:graph :embedding :colbert}}}]
  (let [seed-uris (if seed-failure-uri
                    [seed-failure-uri]
                    ;; Default to expanding from root failure concept
                    ["failure:Root"])

        {:keys [results]} (hybrid-search ctx
                                          {:seed-uris seed-uris
                                           :query-text query-text
                                           :scope :failure
                                           :colbert-index-id colbert-index-id
                                           :signals signals
                                           :limit limit
                                           :min-similarity 0.3})]
    ;; Filter to only failure concepts
    (filterv #(= :failure (:scope %)) results)))

(defn hybrid-search-patterns
  "Search success patterns using hybrid graph + embedding + ColBERT search.

   Specialized search for finding relevant success patterns.

   Args:
     event-store: Grain event store
     query-text: Description of the pattern to find
     opts:
       :seed-pattern-uri - Optional seed pattern URI for graph expansion
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)
       :limit - Maximum results (default 5)

   Returns:
     Vector of success pattern concepts with scores"
  [ctx query-text & {:keys [seed-pattern-uri colbert-index-id signals limit]
                              :or {limit 5
                                   signals #{:graph :embedding :colbert}}}]
  (let [seed-uris (if seed-pattern-uri
                    [seed-pattern-uri]
                    ["success:Root"])

        {:keys [results]} (hybrid-search ctx
                                          {:seed-uris seed-uris
                                           :query-text query-text
                                           :scope :success
                                           :colbert-index-id colbert-index-id
                                           :signals signals
                                           :limit limit
                                           :min-similarity 0.3})]
    (filterv #(= :success (:scope %)) results)))

;; =============================================================================
;; Enhanced Ontology Context with Embeddings
;; =============================================================================

(defn build-ontology-context-with-embeddings
  "Build comprehensive ontology context using hybrid search.

   Enhanced version of build-ontology-context that leverages embeddings
   and ColBERT (when available) to provide more semantically relevant results.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Primary problem URI
       :problem-description - Natural language description (for embedding/ColBERT search)
       :required-patterns - Success patterns that should be used
       :user-domain - Domain context
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)

   Returns:
     Same structure as build-ontology-context but with embedding/ColBERT-enhanced results"
  [ctx {:keys [problem-type problem-description required-patterns user-domain
                       colbert-index-id signals]
                :or {signals #{:graph :embedding :colbert}}
                :as opts}]
  (let [;; Check if embeddings are available and enabled
        has-embeddings? (and (contains? signals :embedding)
                             (seq (rm/get-all-concept-embeddings ctx)))

        ;; Check if ColBERT is available and enabled
        has-colbert? (and (contains? signals :colbert)
                          colbert-index-id
                          (resolve-colbert-search-fn))

        ;; Base context from regular build
        base-context (build-ontology-context ctx opts)

        ;; If we have embeddings/ColBERT and a description, enhance with semantic search
        enhanced-patterns (when (and (or has-embeddings? has-colbert?) problem-description)
                            (hybrid-search-patterns ctx problem-description
                                                     :colbert-index-id colbert-index-id
                                                     :signals signals
                                                     :limit 5))

        enhanced-failures (when (and (or has-embeddings? has-colbert?) problem-description)
                            (hybrid-search-failures ctx
                                                     (str "failures when " problem-description)
                                                     :colbert-index-id colbert-index-id
                                                     :signals signals
                                                     :limit 5))

        ;; Hybrid search for related concepts
        hybrid-related (when (and (or has-embeddings? has-colbert?) problem-type problem-description)
                         (:results (hybrid-search ctx
                                                   {:seed-uris [problem-type]
                                                    :query-text problem-description
                                                    :colbert-index-id colbert-index-id
                                                    :signals signals
                                                    :limit 15})))]

    (cond-> base-context
      ;; Add embedding-enhanced patterns
      (seq enhanced-patterns)
      (assoc :embedding-recommended-patterns enhanced-patterns)

      ;; Add embedding-enhanced failures to avoid
      (seq enhanced-failures)
      (assoc :embedding-patterns-to-avoid enhanced-failures)

      ;; Add hybrid related concepts
      (seq hybrid-related)
      (assoc :hybrid-related-concepts hybrid-related)

      ;; Mark what was used
      has-embeddings?
      (assoc-in [:context-metadata :embeddings-used] true)

      has-colbert?
      (assoc-in [:context-metadata :colbert-used] true)

      ;; Record enabled signals
      true
      (assoc-in [:context-metadata :signals-enabled] signals))))

;; =============================================================================
;; Context Formatting for LLM Injection
;; =============================================================================

(defn format-context-for-llm
  "Format ontology context for injection into LLM prompts.

   Converts the rich ontology context into a markdown-formatted string
   suitable for inclusion in an LLM system prompt.

   Args:
     context: Map from build-ontology-context containing:
       - :recommended-patterns - Success patterns to use
       - :patterns-to-avoid - Failure patterns to avoid
       - :related-concepts - Neighborhood expansion results
       - :problem-hierarchy - Problem type with parent/child
       - :few-shot-trees - Similar successful trees
     opts: Configuration map
       :include #{:patterns :failures :related :hierarchy :examples}
       :max-items - Max items per section (default 5)

   Returns:
     Formatted markdown string for prompt injection, or nil if empty"
  [context {:keys [include max-items]
            :or {include #{:patterns :related}
                 max-items 5}}]
  (let [sections (atom [])

        ;; Problem hierarchy section
        _ (when (and (contains? include :hierarchy)
                     (:problem-hierarchy context))
            (let [h (:problem-hierarchy context)]
              (swap! sections conj
                     (str "### Problem Context\n"
                          "**Type:** " (:label h) "\n"
                          (when (:description h)
                            (str "**Description:** " (:description h) "\n"))
                          (when (seq (:broader h))
                            (str "**Category:** " (str/join ", " (map :label (:broader h)))))))))

        ;; Recommended patterns section - use rich formatting when available
        _ (when (and (contains? include :patterns)
                     (seq (:recommended-patterns context)))
            (let [patterns (:recommended-patterns context)
                  ;; Check if any patterns have rich context (conditions + actions)
                  has-rich-context? (some #(and (:context-conditions %) (:action-taken %)) patterns)
                  ;; Deduplicate patterns by creating a signature from conditions + action type
                  dedupe-key (fn [p]
                               (let [conds (:context-conditions p)
                                     action (:action-taken p)]
                                 (str (pr-str (sort-by first conds))
                                      "|" (:type action) "|" (pr-str (:target action)))))
                  ;; Group by signature and take highest confidence from each group
                  deduped-patterns (->> patterns
                                        (filter #(and (:context-conditions %) (:action-taken %)))
                                        (group-by dedupe-key)
                                        (map (fn [[_ group]]
                                               ;; Take highest confidence, sum up evidence counts
                                               (let [best (apply max-key :confidence group)]
                                                 (assoc best
                                                        :count (reduce + 0 (keep :count group))
                                                        :evidence-episodes (count group)))))
                                        (sort-by :confidence >)
                                        vec)
                  ;; If no rich patterns, use original patterns
                  final-patterns (if (seq deduped-patterns) deduped-patterns patterns)]
              (swap! sections conj
                     (if has-rich-context?
                       ;; Use actionable rule format with deduplicated patterns
                       (str "### Learned Rules from Success Patterns\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map format-rich-pattern)
                                 (str/join "\n")))
                       ;; Fallback to label/description format
                       (str "### Recommended Patterns\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map #(str "- **" (:label %) "**: " (:description %)))
                                 (str/join "\n")))))))

        ;; Patterns to avoid section
        _ (when (and (contains? include :failures)
                     (seq (:patterns-to-avoid context)))
            (swap! sections conj
                   (str "### Patterns to Avoid\n"
                        (->> (:patterns-to-avoid context)
                             (take max-items)
                             (map #(str "- **" (or (:description %) (:failure-uri %)) "**"
                                        (when (:frequency %)
                                          (str " (frequency: " (format "%.0f%%" (* 100 (:frequency %))) ")"))))
                             (str/join "\n")))))

        ;; Related concepts section
        _ (when (and (contains? include :related)
                     (seq (:related-concepts context)))
            (swap! sections conj
                   (str "### Related Concepts\n"
                        (->> (:related-concepts context)
                             (take max-items)
                             (map #(let [concept (static/get-concept-by-uri (:uri %))]
                                     (str "- **" (or (:label concept) (:uri %)) "**"
                                          (when (:description concept)
                                            (str ": " (:description concept))))))
                             (str/join "\n")))))

        ;; Few-shot examples section
        _ (when (and (contains? include :examples)
                     (seq (:few-shot-trees context)))
            (swap! sections conj
                   (str "### Similar Successful Trees\n"
                        (->> (:few-shot-trees context)
                             (take max-items)
                             (map #(str "- Tree `" (:tree-id %) "`"
                                        (when-let [pm (:problem-match %)]
                                          (str " (success rate: " (format "%.0f%%" (* 100 (:success-rate pm))) ")"))
                                        (when (seq (:matching-patterns %))
                                          (str "\n  Patterns: " (str/join ", " (:matching-patterns %))))))
                             (str/join "\n")))))]

    (when (seq @sections)
      (str "## Relevant Knowledge\n\n" (str/join "\n\n" @sections)))))

;; =============================================================================
;; Self-Learning Retrieval
;; =============================================================================

(defn find-self-patterns
  "Retrieve patterns from the current tree for self-reinforcing learning.

   Unlike find-success-patterns which aggregates across trees, this returns
   the patterns accumulated by THIS specific tree. This enables single-tree
   training scenarios where the tree learns from its own execution history.

   Args:
     ctx: System context with :event-store
     tree-id: UUID of the tree to get patterns for
     opts: {:min-confidence double} - minimum confidence threshold (default 0.2)

   Returns:
     {:strengths [{:uri :label :description :confidence :count
                   :context-conditions :action-taken :domain-type :expected-outcome} ...]
      :weaknesses [{:uri :description :frequency :severity :triggers
                    :failure-context :attempted-action :domain-type} ...]}

   Returns nil if tree has no profile or ctx/tree-id is nil."
  [ctx tree-id {:keys [min-confidence] :or {min-confidence 0.2}}]
  (when (and ctx tree-id)
    (let [profiles (rm/get-all-tree-profiles ctx)
          profile (get profiles tree-id)]
      (when profile
        {:strengths (->> (:strengths profile)
                         (filter #(>= (:confidence %) min-confidence))
                         (map (fn [s]
                                (let [concept (static/get-concept-by-uri (:pattern s))
                                      label-from-uri (when (:pattern s)
                                                       (-> (:pattern s)
                                                           (str/replace #"^success:" "")
                                                           (str/replace #"([A-Z])" " $1")
                                                           str/trim))]
                                  (cond-> {:uri (:pattern s)
                                           :label (or (:label concept) label-from-uri (:pattern s))
                                           :description (or (:description concept)
                                                            (str "Learned pattern: " (or label-from-uri (:pattern s))))
                                           :confidence (:confidence s)
                                           :avg-score (:avg-score s)
                                           :count (:evidence-count s)}
                                    ;; Include rich context for actionable formatting
                                    (:context-conditions s)
                                    (assoc :context-conditions (:context-conditions s))
                                    (:action-taken s)
                                    (assoc :action-taken (:action-taken s))
                                    (:domain-type s)
                                    (assoc :domain-type (:domain-type s))
                                    (:expected-outcome s)
                                    (assoc :expected-outcome (:expected-outcome s))))))
                         vec)
         :weaknesses (->> (:weaknesses profile)
                          (map (fn [w]
                                 (let [concept (static/get-concept-by-uri (:failure w))
                                       label-from-uri (when (:failure w)
                                                        (-> (:failure w)
                                                            (str/replace #"^failure:" "")
                                                            (str/replace #"([A-Z])" " $1")
                                                            str/trim))]
                                   (cond-> {:uri (:failure w)
                                            :description (or (:description concept)
                                                             (str "Failure: " (or label-from-uri (:failure w))))
                                            :frequency (:frequency w)
                                            :severity (:severity w)
                                            :triggers (:triggers w)}
                                     ;; Include rich context for domain-agnostic weakness tracking
                                     (:failure-context w)
                                     (assoc :failure-context (:failure-context w))
                                     (:attempted-action w)
                                     (assoc :attempted-action (:attempted-action w))
                                     (:domain-type w)
                                     (assoc :domain-type (:domain-type w))))))
                          vec)}))))

;; =============================================================================
;; Rich Pattern Formatting
;; =============================================================================

(defn- format-rich-pattern
  "Format a pattern with its conditions and actions for LLM injection.

   Produces multi-line entries when the pattern carries rich tree-design
   context (a tree-DSL snippet in :action-taken.target plus a :reason
   and an :expected-outcome at the top level). Lower-fidelity patterns
   fall back to the single-line label format.

   For RLM tree-design principles, the multi-line form surfaces:
     - the named pattern (Action),
     - the WHY behind the rule (Why, from :action-taken.reason),
     - what success looks like (Expected outcome, from :expected-outcome),
     - the tree-DSL snippet rendered as a Clojure code block.

   Works for any domain (drone, legal, sales, construction, RLM tree-design)."
  [pattern]
  (let [{:keys [label context-conditions action-taken confidence avg-score
                count evidence-episodes expected-outcome]} pattern

        ;; Format conditions as readable string
        conditions-str (when (and context-conditions (seq context-conditions))
                         (->> context-conditions
                              (map (fn [[k v]]
                                     (let [field-name (name k)
                                           ;; Format value based on type
                                           value-str (cond
                                                       (number? v) (if (float? v)
                                                                     (format "%.1f" (double v))
                                                                     (str v))
                                                       (coll? v) (str "[" (str/join " " (map #(if (float? %) (format "%.1f" %) %) v)) "]")
                                                       :else (str v))]
                                       (str field-name "=" value-str))))
                              (str/join ", ")))

        ;; Extract action subfields without lossy stringification — we want
        ;; the :target rendered separately (often a long DSL snippet best
        ;; shown as a code block) and the :reason preserved for the multi-line
        ;; output.
        {action-type :type
         action-target :target
         action-reason :reason} action-taken

        target-str (when action-target
                     (if (coll? action-target)
                       (str "[" (str/join " " (map #(if (float? %) (format "%.1f" %) %) action-target)) "]")
                       (str action-target)))

        ;; Single-line summary for the rule header line
        action-summary (when action-type
                         (str action-type (when target-str " (see code block below)")))

        ;; Calculate display score (prefer avg-score, fallback to confidence)
        score (or avg-score confidence 0.8)

        ;; Use evidence-episodes from deduplication, fallback to count
        episode-count (or evidence-episodes count)]

    (cond
      ;; Rich multi-line form: we have both conditions AND an action AND
      ;; at least one of (reason, expected-outcome, target). This is the
      ;; shape RLM tree-design principles take.
      (and conditions-str
           action-type
           (or action-reason expected-outcome target-str))
      (let [header (str "- **" (or action-type label) "** — when " conditions-str
                        (format " (%.0f%% confidence" (* 100 score))
                        (when (and episode-count (pos? episode-count))
                          (str ", " episode-count " episode" (when (> episode-count 1) "s")))
                        ")")
            target-block (when target-str
                           (str "\n  - Action:\n    ```clojure\n    " target-str "\n    ```"))
            reason-line (when action-reason
                          (str "\n  - Why: " action-reason))
            outcome-line (when expected-outcome
                           (str "\n  - Expected outcome: " expected-outcome))]
        (str header target-block reason-line outcome-line))

      ;; Original single-line format — preserved for legacy patterns whose
      ;; :action-taken has only :type/:target without :reason/:expected-outcome.
      (and conditions-str action-summary)
      (str "- When " conditions-str ": " action-type
           (when target-str (str " to " target-str))
           (format " (%.0f%% success" (* 100 score))
           (when (and episode-count (pos? episode-count))
             (str ", " episode-count " episodes"))
           ")")

      ;; Fallback to simple label format
      :else
      (str "- **" label "**"
           (format " (%.0f%% confidence" (* 100 (or confidence 0.8)))
           (when (and episode-count (pos? episode-count))
             (str ", " episode-count " episodes"))
           ")"))))

;; =============================================================================
;; Actionable Context Building
;; =============================================================================

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
       :has-patterns? - Whether any patterns were found"
  [ctx tree-id problem-type & [{:keys [max-items include-patterns include-failures]
                                  :or {max-items 5 include-patterns true include-failures true}}]]
  (let [self-patterns (find-self-patterns ctx tree-id {:min-confidence 0.2})

        sections (atom [])

        ;; Learned Strengths section - use rich formatting when available
        _ (when (and include-patterns (seq (:strengths self-patterns)))
            (let [patterns (:strengths self-patterns)
                  ;; Check if any patterns have rich context (conditions + actions)
                  has-rich-context? (some #(and (:context-conditions %) (:action-taken %)) patterns)
                  ;; Deduplicate patterns by creating a signature from conditions + action type
                  dedupe-key (fn [p]
                               (let [conds (:context-conditions p)
                                     action (:action-taken p)]
                                 (str (pr-str (sort-by first conds))
                                      "|" (:type action) "|" (pr-str (:target action)))))
                  ;; Group by signature and take highest confidence from each group
                  deduped-patterns (->> patterns
                                        (filter #(and (:context-conditions %) (:action-taken %)))
                                        (group-by dedupe-key)
                                        (map (fn [[_ group]]
                                               ;; Take highest confidence, sum up evidence counts
                                               (let [best (apply max-key :confidence group)]
                                                 (assoc best
                                                        :count (reduce + 0 (keep :count group))
                                                        :evidence-episodes (count group)))))
                                        (sort-by :confidence >)
                                        vec)
                  ;; If no rich patterns, use original patterns
                  final-patterns (if (seq deduped-patterns) deduped-patterns patterns)]
              (swap! sections conj
                     (if has-rich-context?
                       ;; Use actionable rule format with deduplicated patterns
                       (str "### Learned Rules from Success Patterns\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map format-rich-pattern)
                                 (str/join "\n")))
                       ;; Fallback to label/description format
                       (str "### What Works Well\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map #(str "- **" (:label %) "** "
                                            (format "(%.0f%% confidence)" (* 100 (:confidence %)))))
                                 (str/join "\n")))))))

        ;; Patterns to avoid section
        _ (when (and include-failures (seq (:weaknesses self-patterns)))
            (swap! sections conj
                   (str "### Patterns to Avoid\n"
                        (->> (:weaknesses self-patterns)
                             (take max-items)
                             (map #(str "- **" (:description %) "**"
                                        (when (:frequency %)
                                          (str " (frequency: " (format "%.0f%%" (* 100 (:frequency %))) ")"))))
                             (str/join "\n")))))

        formatted (when (seq @sections)
                    (str "## Relevant Knowledge\n\n" (str/join "\n\n" @sections)))]

    {:formatted-context formatted
     :self-patterns self-patterns
     :has-patterns? (boolean (or (seq (:strengths self-patterns))
                                  (seq (:weaknesses self-patterns))))
     :strength-count (count (:strengths self-patterns))
     :weakness-count (count (:weaknesses self-patterns))}))
