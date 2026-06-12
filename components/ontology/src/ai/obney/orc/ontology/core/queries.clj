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

(defquery :ontology get-concepts
  "Get concepts from the ontology, optionally filtered by scope, broader-uri
   and (S02) ontology-id / ontology-ids."
  [{{:keys [scope broader-uri include-narrower? ontology-id ontology-ids]} :query
    :keys [event-store] :as ctx}]
  (let [scope-opts (cond-> {:scope scope :broader-uri broader-uri}
                     ontology-id (assoc :ontology-id ontology-id)
                     (seq ontology-ids) (assoc :ontology-ids ontology-ids))
        concepts (rm/get-concepts ctx scope-opts)
        narrower-opts (cond-> {}
                        ontology-id (assoc :ontology-id ontology-id)
                        (seq ontology-ids) (assoc :ontology-ids ontology-ids))
        ;; Optionally include narrower concepts for each
        result (if include-narrower?
                 (mapv (fn [c]
                         (assoc c :narrower-concepts
                                (rm/get-narrower-concepts ctx (:uri c) narrower-opts)))
                       concepts)
                 concepts)]
    {:query/result result}))

(defquery :ontology get-concept
  "Get a single concept by URI. S02: accepts optional :ontology-id /
   :ontology-ids for section-scoped lookup."
  [{{:keys [uri ontology-id ontology-ids]} :query
    :keys [event-store] :as ctx}]
  (let [opts (cond-> {}
               ontology-id (assoc :ontology-id ontology-id)
               (seq ontology-ids) (assoc :ontology-ids ontology-ids))
        concept (if (seq opts)
                  (rm/get-concept-by-uri ctx uri opts)
                  (rm/get-concept-by-uri ctx uri))]
    (if concept
      {:query/result concept}
      ;; Fall back to static ontology (unscoped — static is a single corpus)
      (if-let [static-concept (static/get-concept-by-uri uri)]
        {:query/result static-concept}
        {::anom/category ::anom/not-found
         ::anom/message (str "Concept not found: " uri)}))))

(defquery :ontology get-static-concepts
  "Get concepts from the static ontology definitions."
  [{{:keys [scope]} :query}]
  (let [concepts (if scope
                   (static/get-concepts-by-scope scope)
                   (static/get-all-static-concepts))]
    {:query/result concepts}))

(defquery :ontology concept-statistics
  "Get statistics about the concept graph. S02: accepts optional
   :ontology-id / :ontology-ids for section-scoped statistics."
  [{{:keys [ontology-id ontology-ids]} :query
    :keys [event-store] :as ctx}]
  (let [opts (cond-> {}
               ontology-id (assoc :ontology-id ontology-id)
               (seq ontology-ids) (assoc :ontology-ids ontology-ids))]
    {:query/result (if (seq opts)
                     (rm/concept-statistics ctx opts)
                     (rm/concept-statistics ctx))}))

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
