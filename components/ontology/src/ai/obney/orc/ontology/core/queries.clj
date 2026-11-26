(ns ai.obney.orc.ontology.core.queries
  "Ontology query handlers.

   Queries for:
   - Concept graph access
   - Tree profile retrieval
   - Node learning aggregation
   - Similar tree discovery
   - TTL export"
  (:require [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Concept Queries
;; =============================================================================

(defn tenant-scoped? [ctx]
  (some? (:tenant-id ctx)))

(defquery :ontology get-ontology
  "Get an ontology lifecycle record by stable identity."
  {:authorized? tenant-scoped?}
  [{{:keys [ontology-id]} :query :as ctx}]
  (if-let [ontology (rm/get-ontology ctx ontology-id)]
    {:query/result ontology}
    {::anom/category ::anom/not-found
     ::anom/message (str "Ontology not found: " ontology-id)}))

(defquery :ontology list-ontologies
  "List ontology lifecycle records visible to the caller's tenant."
  {:authorized? tenant-scoped?}
  [{:keys [] :as ctx}]
  {:query/result (rm/list-ontologies ctx)})

(defquery :ontology get-concepts
  "Get concepts from the ontology, optionally filtered by scope or broader URI."
  {:authorized? tenant-scoped?}
  [{query :query :as ctx}]
  (let [concepts (rm/get-concepts ctx (select-keys query [:ontology-id :ontology-ids :scope :broader-uri]))]
    (if (::anom/category concepts) concepts
      {:query/result (if (:include-narrower? query)
                       (mapv #(assoc % :narrower-concepts (:narrower %)) concepts)
                       concepts)})))

(defquery :ontology get-concept
  "Get a stored concept by URI. Unqualified requests also consult the built-in vocabulary."
  {:authorized? tenant-scoped?}
  [{{:keys [ontology-id uri]} :query :as ctx}]
  (let [concept (if ontology-id
                  (rm/get-concept-by-uri ctx ontology-id uri)
                  (let [stored (rm/get-concepts ctx)]
                    (if (::anom/category stored) stored
                      (let [matches (take 2 (filter #(= uri (:uri %)) stored))]
                        (case (count matches)
                          0 (static/get-concept-by-uri uri)
                          1 (first matches)
                          nil)))))]
    (cond
      (::anom/category concept) concept
      concept {:query/result concept}
      :else {::anom/category ::anom/not-found ::anom/message (str "Concept not found: " uri)})))

(defquery :ontology get-static-concepts
  "Get concepts from the static ontology definitions."
  [{{:keys [scope]} :query}]
  (let [concepts (if scope
                   (static/get-concepts-by-scope scope)
                   (static/get-all-static-concepts))]
    {:query/result concepts}))

(defquery :ontology concept-statistics
  "Get statistics about the concept graph."
  {:authorized? tenant-scoped?}
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (rm/concept-statistics ctx)})

;; =============================================================================
;; Tree Profile Queries
;; =============================================================================

(defquery :ontology get-tree-profile
  "Get the profile for a specific tree, including strengths, weaknesses, and capabilities."
  [{{:keys [tree-id]} :query
    :keys [event-store] :as ctx}]
  (if-let [profile (rm/get-tree-profile ctx tree-id)]
    {:query/result profile}
    {::anom/category ::anom/not-found
     ::anom/message (str "Tree profile not found: " tree-id)}))

(defquery :ontology list-tree-profiles
  "List all tree profiles."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (rm/get-all-tree-profiles ctx)})

(defquery :ontology tree-profile-statistics
  "Get statistics about tree profiles."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (rm/tree-profile-statistics ctx)})

;; =============================================================================
;; Similar Tree Discovery
;; =============================================================================

(defquery :ontology find-similar-trees
  "Find trees that solve a similar problem type with optional filtering.

   Args:
   - problem-type: The problem URI to search for
   - required-patterns: Optional set of success patterns the tree must have
   - min-success-rate: Minimum success rate threshold
   - limit: Maximum number of results"
  [{{:keys [problem-type required-patterns min-success-rate limit]} :query
    :keys [event-store] :as ctx}]
  (let [trees (rm/find-trees-by-problem ctx problem-type)
        ;; Apply additional filters
        filtered (cond->> trees
                   min-success-rate
                   (filter (fn [p]
                             (some #(and (= problem-type (:problem-uri %))
                                         (>= (:success-rate %) min-success-rate))
                                   (:solves p))))

                   required-patterns
                   (filter (fn [p]
                             (let [tree-patterns (set (map :pattern (:strengths p)))]
                               (every? tree-patterns required-patterns))))

                   limit
                   (take limit))]
    {:query/result (vec filtered)}))

(defquery :ontology find-failure-patterns
  "Find common failure patterns for a given problem type.

   Args:
   - problem-type: The problem URI to search for
   - min-frequency: Minimum frequency to include"
  [{{:keys [problem-type min-frequency]} :query
    :keys [event-store] :as ctx}]
  (let [;; Get all trees that solve this problem
        trees (rm/find-trees-by-problem ctx problem-type)
        ;; Collect all weaknesses from these trees
        all-weaknesses (mapcat :weaknesses trees)
        ;; Group by failure URI
        by-failure (group-by :failure all-weaknesses)
        ;; Calculate aggregate stats
        patterns (->> by-failure
                      (map (fn [[failure-uri weaknesses]]
                             {:failure-uri failure-uri
                              :occurrence-count (count weaknesses)
                              :avg-frequency (/ (reduce + (map :frequency weaknesses))
                                                (count weaknesses))
                              :severities (frequencies (map :severity weaknesses))
                              :common-triggers (->> weaknesses
                                                    (mapcat :triggers)
                                                    frequencies
                                                    (sort-by val >)
                                                    (take 5)
                                                    (map first)
                                                    vec)}))
                      (filter #(or (nil? min-frequency)
                                   (>= (:avg-frequency %) min-frequency)))
                      (sort-by :occurrence-count >)
                      vec)]
    {:query/result patterns}))

;; =============================================================================
;; Node Learning Queries
;; =============================================================================

(defquery :ontology get-node-type-learnings
  "Get aggregated learnings for a specific node type."
  [{{:keys [node-type]} :query
    :keys [event-store] :as ctx}]
  (let [node-type-kw (if (keyword? node-type) node-type (keyword node-type))]
    (if-let [learnings (rm/get-node-type-learnings ctx node-type-kw)]
      {:query/result learnings}
      {:query/result {}})))

(defquery :ontology list-node-learnings
  "List all node learnings aggregated by type."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (rm/get-all-node-learnings ctx)})

(defquery :ontology node-learning-statistics
  "Get statistics about node-level learning."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (rm/node-learning-statistics ctx)})

;; =============================================================================
;; TTL Export Query
;; =============================================================================

(defquery :ontology export-ttl
  "Export ontology to SKOS/OWL Turtle format.

   Args:
   - scope: Optional filter (:failure, :success, :problem)
   - include-instances?: Include tree profiles and node experiences
   - include-profiles?: Include tree profiles specifically
   - include-experiences?: Include node experiences specifically
   - base-uri: Base URI for the ontology"
  [{{:keys [scope include-instances? include-profiles? include-experiences? base-uri]} :query
    :keys [event-store] :as ctx}]
  (let [;; Determine what to include
        include-profiles (if (some? include-instances?)
                           include-instances?
                           (if (some? include-profiles?)
                             include-profiles?
                             true))
        include-experiences (if (some? include-instances?)
                              include-instances?
                              (if (some? include-experiences?)
                                include-experiences?
                                true))
        ttl (serialization/full-export ctx
                                       {:scope scope
                                        :include-profiles? include-profiles
                                        :include-experiences? include-experiences
                                        :base-uri (or base-uri "http://obney.ai/workshop/ontology/")})]
    {:query/result {:format :turtle
                    :content ttl
                    :validation (serialization/validate-turtle ttl)}}))

;; =============================================================================
;; Context Building Query (for Tree Builder Integration)
;; =============================================================================

(defquery :ontology build-context
  "Build ontology context for tree builder integration.

   Returns:
   - few-shot-examples: Similar successful trees
   - patterns-to-avoid: Common failure patterns
   - recommended-patterns: Success patterns to consider

   Args:
   - problem-type: The problem type being solved
   - required-patterns: Optional patterns that must be present"
  [{{:keys [problem-type required-patterns]} :query
    :keys [event-store] :as ctx}]
  (let [;; Find similar successful trees
        similar-trees (vec (take 5 (rm/find-trees-by-problem ctx problem-type)))

        ;; Get their profiles for more detail
        tree-profiles (into {}
                            (for [tree similar-trees
                                  :let [id (:tree-id tree)]
                                  :when id]
                              [id (rm/get-tree-profile ctx id)]))

        ;; Extract success patterns from similar trees
        success-patterns (->> tree-profiles
                              vals
                              (mapcat :strengths)
                              (group-by :pattern)
                              (map (fn [[pattern instances]]
                                     {:pattern pattern
                                      :avg-confidence (/ (reduce + (map :confidence instances))
                                                         (count instances))
                                      :tree-count (count instances)}))
                              (sort-by :avg-confidence >)
                              vec)

        ;; Collect failure patterns to avoid
        failure-patterns (->> tree-profiles
                              vals
                              (mapcat :weaknesses)
                              (group-by :failure)
                              (map (fn [[failure instances]]
                                     {:failure-uri failure
                                      :frequency (/ (reduce + (map :frequency instances))
                                                    (count instances))
                                      :common-triggers (->> instances
                                                            (mapcat :triggers)
                                                            frequencies
                                                            (sort-by val >)
                                                            (take 3)
                                                            (map first)
                                                            vec)}))
                              (sort-by :frequency >)
                              (take 5)
                              vec)

        ;; Get node-type specific learnings
        llm-learnings (rm/get-node-type-learnings ctx :llm)]

    {:query/result {:problem-type problem-type
                    :similar-tree-count (count similar-trees)
                    :few-shot-examples (mapv (fn [[id profile]]
                                               {:tree-id id
                                                :strengths (:strengths profile)
                                                :solves (filter #(= problem-type (:problem-uri %))
                                                                (:solves profile))})
                                             tree-profiles)
                    :recommended-patterns success-patterns
                    :patterns-to-avoid failure-patterns
                    :llm-learnings llm-learnings}}))
