(ns ai.obney.orc.ontology.core.read-models
  "Ontology read models - projections built from events.

   Following the crm-service pattern:
   - Event type sets for efficient querying
   - Multimethod projections for each entity type
   - Helper functions for common queries (via rmp/project)

   Three main projections:
   1. concepts - Ontology concept graph with relationships
   2. tree-profiles - Per-tree strengths, weaknesses, problem mappings
   3. node-experiences - Aggregated patterns by node type"
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [clojure.set :as set]))

;; =============================================================================
;; Event Type Sets
;; =============================================================================

(def ontology-events
  "Events that affect the ontology lifecycle"
  #{:ontology/ontology-created})

(def concept-events
  "Events that affect the concept graph read model"
  #{:ontology/concept-created
    :ontology/concept-updated
    :ontology/relationship-created})

(def tree-profile-events
  "Events that affect tree profile read model"
  #{:ontology/tree-strength-recorded
    :ontology/tree-weakness-recorded
    :ontology/tree-problem-mapping-created
    :ontology/tree-problem-mapping-updated
    :ontology/domain-knowledge-added})

(def node-learning-events
  "Events that affect node-level learning read model"
  #{:ontology/node-pattern-learned})

(def discovery-events
  "Events for ontology discovery/extension"
  #{:ontology/failure-subtype-discovered})

(def all-ontology-events
  "All events for full ontology reconstruction"
  (set/union ontology-events
             concept-events
             tree-profile-events
             node-learning-events
             discovery-events))

;; =============================================================================
;; Concepts Projection
;; =============================================================================
;; Builds the concept graph with broader/narrower/related relationships

(defmulti concepts*
  "Apply event to concepts read model.
   State: {uri -> concept-map}"
  (fn [_state event] (:event/type event)))

(defmethod concepts* :ontology/concept-created
  [state event]
  (assoc state (:uri event)
         {:uri (:uri event)
          :id (:concept-id event)
          :ontology-id (:ontology-id event)
          :label (:label event)
          :description (:description event)
          :scope (:scope event)
          :broader (set (or (:broader event) []))
          :narrower #{}
          :related #{}
          :indicators (or (:indicators event) [])
          :created-at (str (:created-at event))}))

(defmethod concepts* :ontology/concept-updated
  [state event]
  (if-let [concept (get state (some-> event :concept-id str))]
    (update state (:uri concept) merge (:changes event))
    state))

(defmethod concepts* :ontology/relationship-created
  [state event]
  (let [{:keys [source-uri target-uri predicate]} event]
    (case predicate
      "skos:broader"
      (-> state
          (update-in [source-uri :broader] (fnil conj #{}) target-uri)
          (update-in [target-uri :narrower] (fnil conj #{}) source-uri))

      "skos:narrower"
      (-> state
          (update-in [source-uri :narrower] (fnil conj #{}) target-uri)
          (update-in [target-uri :broader] (fnil conj #{}) source-uri))

      "skos:related"
      (-> state
          (update-in [source-uri :related] (fnil conj #{}) target-uri)
          (update-in [target-uri :related] (fnil conj #{}) source-uri))

      ;; Other predicates (owl:causes, etc.) - store as related
      (-> state
          (update-in [source-uri :related] (fnil conj #{}) target-uri)))))

(defmethod concepts* :default [state _] state)

(defn concepts
  "Build concepts graph from events."
  [initial-state events]
  (reduce concepts* initial-state events))

(defreadmodel :ontology concepts
  {:events concept-events, :version 1}
  [state event] (concepts* state event))

;; =============================================================================
;; Tree Profiles Projection
;; =============================================================================
;; Builds per-tree profiles with strengths, weaknesses, problem mappings

(defmulti tree-profiles*
  "Apply event to tree profiles read model.
   State: {tree-id -> profile-map}"
  (fn [_state event] (:event/type event)))

(defmethod tree-profiles* :ontology/tree-strength-recorded
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :strengths]
                   (fnil conj [])
                   ;; Include rich context fields for actionable rule formatting
                   (cond-> {:pattern (:pattern-uri event)
                            :confidence (:confidence event)
                            :evidence-count (count (:evidence-trace-ids event))
                            :avg-score (:avg-score event)
                            :recorded-at (str (:recorded-at event))}
                     ;; Add context conditions (state at success)
                     (:context-conditions event)
                     (assoc :context-conditions (:context-conditions event))
                     ;; Fallback to state-conditions for backward compat
                     (and (nil? (:context-conditions event)) (:state-conditions event))
                     (assoc :context-conditions (:state-conditions event))
                     ;; Add action taken
                     (:action-taken event)
                     (assoc :action-taken (:action-taken event))
                     ;; Add domain type
                     (:domain-type event)
                     (assoc :domain-type (:domain-type event))
                     ;; Add expected outcome
                     (:expected-outcome event)
                     (assoc :expected-outcome (:expected-outcome event)))))))

(defmethod tree-profiles* :ontology/tree-weakness-recorded
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :weaknesses]
                   (fnil conj [])
                   ;; Include rich context fields for domain-agnostic weakness tracking
                   (cond-> {:failure (:failure-uri event)
                            :subtype (:subtype-uri event)
                            :frequency (:frequency event)
                            :severity (:severity event)
                            :triggers (:triggers event)
                            :recorded-at (str (:recorded-at event))}
                     ;; Add failure context conditions
                     (:failure-context event)
                     (assoc :failure-context (:failure-context event))
                     ;; Fallback to failure-conditions for backward compat
                     (and (nil? (:failure-context event)) (:failure-conditions event))
                     (assoc :failure-context (:failure-conditions event))
                     ;; Add attempted action
                     (:attempted-action event)
                     (assoc :attempted-action (:attempted-action event))
                     ;; Add domain type
                     (:domain-type event)
                     (assoc :domain-type (:domain-type event)))))))

(defmethod tree-profiles* :ontology/tree-problem-mapping-created
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :solves]
                   (fnil conj [])
                   {:problem-uri (:problem-uri event)
                    :success-rate (:success-rate event)
                    :execution-count (:execution-count event)
                    :recorded-at (str (:recorded-at event))}))))

(defmethod tree-profiles* :ontology/tree-problem-mapping-updated
  [state event]
  (let [tree-id (:tree-id event)
        problem-uri (:problem-uri event)]
    ;; Update the existing mapping for this problem type
    (update-in state [tree-id :solves]
               (fn [solves]
                 (mapv (fn [s]
                         (if (= problem-uri (:problem-uri s))
                           {:problem-uri problem-uri
                            :success-rate (:success-rate event)
                            :execution-count (:execution-count event)
                            :updated-at (str (:updated-at event))}
                           s))
                       (or solves []))))))

(defmethod tree-profiles* :ontology/domain-knowledge-added
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :domain-knowledge]
                   (fnil conj [])
                   {:id (:knowledge-id event)
                    :description (:description event)
                    :node-id (:node-id event)
                    :impact-score (:impact-score event)
                    :added-at (str (:added-at event))}))))

(defmethod tree-profiles* :default [state _] state)

(defn tree-profiles
  "Build tree profiles from events."
  [initial-state events]
  (reduce tree-profiles* initial-state events))

(defreadmodel :ontology tree-profiles
  {:events tree-profile-events, :version 1}
  [state event] (tree-profiles* state event))

;; =============================================================================
;; Node Experiences Projection
;; =============================================================================
;; Aggregates patterns by node type across all nodes

(defmulti node-experiences*
  "Apply event to node experiences read model.
   State: {node-type -> {pattern-type -> {:effective [...] :ineffective [...]}}}
   Aggregates across all nodes of the same type."
  (fn [_state event] (:event/type event)))

(defmethod node-experiences* :ontology/node-pattern-learned
  [state event]
  (let [node-type (:node-type event)
        pattern-type (:pattern-type event)
        category (if (:effective? event) :effective :ineffective)]
    (update-in state [node-type pattern-type category]
               (fnil conj [])
               {:pattern (:pattern-description event)
                :metrics (:metrics event)
                :evidence-count (count (:evidence-trace-ids event))
                :node-id (:node-id event)
                :sheet-id (:sheet-id event)
                :learned-at (str (:learned-at event))})))

(defmethod node-experiences* :default [state _] state)

(defn node-experiences
  "Build node experiences from events (aggregated by node-type)."
  [initial-state events]
  (reduce node-experiences* initial-state events))

(defreadmodel :ontology node-experiences
  {:events node-learning-events, :version 1}
  [state event] (node-experiences* state event))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn get-concepts
  "Get all concepts, optionally filtered by scope."
  [ctx & [{:keys [scope broader-uri]}]]
  (let [all-concepts (vals (rmp/project ctx :ontology/concepts))]
    (cond->> all-concepts
      scope (filter #(= scope (:scope %)))
      broader-uri (filter #(contains? (:broader %) broader-uri)))))

(defn get-concept-by-uri
  "Get a single concept by URI."
  [ctx uri]
  (get (rmp/project ctx :ontology/concepts) uri))

(defn get-tree-profile
  "Get profile for a specific tree."
  [ctx tree-id]
  (get (rmp/project ctx :ontology/tree-profiles {:tags #{[:tree tree-id]}}) tree-id))

(defn get-all-tree-profiles
  "Get all tree profiles."
  [ctx]
  (rmp/project ctx :ontology/tree-profiles))

(defn get-node-type-learnings
  "Get aggregated learnings for a specific node type."
  [ctx node-type]
  (get (rmp/project ctx :ontology/node-experiences {:tags #{[:node-type node-type]}}) node-type))

(defn get-all-node-learnings
  "Get all node learnings aggregated by type."
  [ctx]
  (rmp/project ctx :ontology/node-experiences))

(defn find-trees-by-problem
  "Find trees that solve a specific problem type."
  [ctx problem-uri]
  (let [profiles (get-all-tree-profiles ctx)]
    (->> profiles
         vals
         (filter (fn [p]
                   (some #(= problem-uri (:problem-uri %)) (:solves p)))))))

(defn find-trees-with-weakness
  "Find trees that have a specific weakness."
  [ctx failure-uri]
  (let [profiles (get-all-tree-profiles ctx)]
    (->> profiles
         vals
         (filter (fn [p]
                   (some #(= failure-uri (:failure %)) (:weaknesses p)))))))

(defn get-narrower-concepts
  "Get all concepts that are narrower than the given URI."
  [ctx uri]
  (get-in (rmp/project ctx :ontology/concepts) [uri :narrower] #{}))

(defn get-broader-concepts
  "Get all concepts that are broader than the given URI."
  [ctx uri]
  (get-in (rmp/project ctx :ontology/concepts) [uri :broader] #{}))

;; =============================================================================
;; Statistics
;; =============================================================================

(defn concept-statistics
  "Get statistics about the concept graph."
  [ctx]
  (let [concept-graph (rmp/project ctx :ontology/concepts)
        by-scope (group-by :scope (vals concept-graph))]
    {:total-concepts (count concept-graph)
     :by-scope (into {} (map (fn [[k v]] [k (count v)]) by-scope))
     :with-indicators (count (filter #(seq (:indicators %)) (vals concept-graph)))}))

(defn tree-profile-statistics
  "Get statistics about tree profiles."
  [ctx]
  (let [profiles (get-all-tree-profiles ctx)]
    {:total-profiles (count profiles)
     :with-strengths (count (filter #(seq (:strengths %)) (vals profiles)))
     :with-weaknesses (count (filter #(seq (:weaknesses %)) (vals profiles)))
     :with-problem-mappings (count (filter #(seq (:solves %)) (vals profiles)))}))

(defn node-learning-statistics
  "Get statistics about node learning."
  [ctx]
  (let [learnings (get-all-node-learnings ctx)]
    {:node-types-with-learnings (count learnings)
     :by-node-type (into {}
                         (for [[node-type patterns] learnings]
                           [node-type
                            {:pattern-types (count patterns)
                             :total-patterns (reduce + 0
                                                     (for [[_ {:keys [effective ineffective]}] patterns]
                                                       (+ (count effective) (count ineffective))))}]))}))

;; =============================================================================
;; Embedding Projections (Phase 4)
;; =============================================================================

(def embedding-events
  "Events that affect embedding read models"
  #{:ontology/concept-embedded
    :ontology/tree-profile-embedded
    :ontology/evaluation-embedded
    :ontology/embedding-model-configured})

(defmulti concept-embeddings*
  "Apply event to concept embeddings read model.
   State: {uri -> {:embedding [...] :text-embedded ... :model-id ...}}"
  (fn [_state event] (:event/type event)))

(defmethod concept-embeddings* :ontology/concept-embedded
  [state event]
  ;; v3 event store flattens body fields to top level
  (let [{:keys [uri concept-id embedding text-embedded field-source model-id embedded-at ontology-id]} event]
    (assoc state uri
           {:uri uri
            :concept-id concept-id
            :ontology-id ontology-id
            :embedding embedding
            :text-embedded text-embedded
            :field-source field-source
            :model-id model-id
            :embedded-at (str embedded-at)})))

(defmethod concept-embeddings* :default [state _] state)

(defn concept-embeddings
  "Build concept embeddings from events."
  [initial-state events]
  (reduce concept-embeddings* initial-state events))

(defreadmodel :ontology concept-embeddings
  {:events #{:ontology/concept-embedded}, :version 1}
  [state event] (concept-embeddings* state event))

(defmulti tree-profile-embeddings*
  "Apply event to tree profile embeddings read model.
   State: {tree-id -> {:embedding [...] :text-embedded ...}}"
  (fn [_state event] (:event/type event)))

(defmethod tree-profile-embeddings* :ontology/tree-profile-embedded
  [state event]
  (assoc state (:tree-id event)
         {:tree-id (:tree-id event)
          :embedding (:embedding event)
          :text-embedded (:text-embedded event)
          :model-id (:model-id event)
          :embedded-at (str (:embedded-at event))}))

(defmethod tree-profile-embeddings* :default [state _] state)

(defn tree-profile-embeddings
  "Build tree profile embeddings from events."
  [initial-state events]
  (reduce tree-profile-embeddings* initial-state events))

(defreadmodel :ontology tree-profile-embeddings
  {:events #{:ontology/tree-profile-embedded}, :version 1}
  [state event] (tree-profile-embeddings* state event))

(defmulti embedding-config*
  "Apply event to embedding config read model.
   State: {scope -> {:model-id ... :dimensions ...}}"
  (fn [_state event] (:event/type event)))

(defmethod embedding-config* :ontology/embedding-model-configured
  [state event]
  (assoc state (:scope event)
         {:model-id (:model-id event)
          :dimensions (:dimensions event)
          :configured-at (str (:configured-at event))}))

(defmethod embedding-config* :default [state _] state)

(defn embedding-config
  "Build embedding configuration from events."
  [initial-state events]
  (reduce embedding-config* initial-state events))

(defreadmodel :ontology embedding-config
  {:events #{:ontology/embedding-model-configured}, :version 1}
  [state event] (embedding-config* state event))

;; =============================================================================
;; Embedding Query Helpers
;; =============================================================================

(defn get-concept-embedding
  "Get embedding for a specific concept by URI."
  [ctx uri]
  (get (rmp/project ctx :ontology/concept-embeddings {:tags #{[:uri uri]}}) uri))

(defn get-all-concept-embeddings
  "Get all concept embeddings, optionally filtered by scope."
  [ctx & [{:keys [scope]}]]
  (if scope
    (rmp/project ctx :ontology/concept-embeddings {:tags #{[:scope scope]}})
    (rmp/project ctx :ontology/concept-embeddings)))

(defn get-tree-profile-embedding
  "Get embedding for a specific tree profile."
  [ctx tree-id]
  (get (rmp/project ctx :ontology/tree-profile-embeddings {:tags #{[:tree tree-id]}}) tree-id))

(defn get-all-tree-profile-embeddings
  "Get all tree profile embeddings."
  [ctx]
  (rmp/project ctx :ontology/tree-profile-embeddings))

(defn get-embedding-config
  "Get embedding model configuration for a scope."
  [ctx scope]
  (get (rmp/project ctx :ontology/embedding-config {:tags #{[:scope scope]}}) scope))

(defn embedding-statistics
  "Get statistics about embeddings."
  [ctx]
  (let [embeddings (rmp/project ctx :ontology/concept-embeddings)
        profile-embs (rmp/project ctx :ontology/tree-profile-embeddings)
        configs (rmp/project ctx :ontology/embedding-config)]
    {:concept-embeddings-count (count embeddings)
     :tree-profile-embeddings-count (count profile-embs)
     :configured-scopes (keys configs)
     :by-model (frequencies (map :model-id (vals embeddings)))}))

;; =============================================================================
;; Ontology-ColBERT Index Mapping (Phase 7 Evolutionary Integration)
;; =============================================================================

(def ontology-colbert-events
  "Events that track ontology-to-ColBERT index mappings."
  #{:evolutionary/colbert-indexed
    :evolutionary/colbert-index-updated})

(defn- ontology-colbert-indexes*
  "Reducer for ontology-colbert-indexes read model.
   Tracks which ColBERT index is associated with each ontology."
  [state {:keys [type body]}]
  (case type
    :evolutionary/colbert-indexed
    (assoc state (:ontology-id body)
           {:colbert-index-id (:index-id body)
            :index-name (:index-name body)
            :colbert-fields (vec (:colbert-fields body))
            :document-count (:document-count body)
            :indexed-at (:indexed-at body)})

    :evolutionary/colbert-index-updated
    (update state (:ontology-id body) merge
            {:colbert-index-id (:index-id body)
             :updated-at (:updated-at body)})

    ;; Pass through unchanged
    state))

(defreadmodel :ontology ontology-colbert-indexes
  {:events ontology-colbert-events :version 1}
  [state event] (ontology-colbert-indexes* state event))

;; =============================================================================
;; Ontology-ColBERT Query Helpers
;; =============================================================================

(defn get-colbert-index-for-ontology
  "Get the ColBERT index-id and metadata associated with an ontology.

   Returns nil if no ColBERT index exists for this ontology."
  [ctx ontology-id]
  (get (rmp/project ctx :ontology/ontology-colbert-indexes
                    {:tags #{[:ontology ontology-id]}})
       ontology-id))

(defn list-ontology-colbert-indexes
  "List all ontology-to-ColBERT index mappings."
  [ctx]
  (rmp/project ctx :ontology/ontology-colbert-indexes))

;; =============================================================================
;; Ontology-Embedding State (Phase 8 Evolutionary Integration - RRF Support)
;; =============================================================================

(def ontology-embedding-events
  "Events that track ontology embedding state."
  #{:evolutionary/concepts-embedded
    :evolutionary/concepts-embedding-updated})

(defn- ontology-embedding-state*
  "Reducer for ontology-embedding-state read model.
   Tracks which concepts have been embedded for each ontology."
  [state {:keys [type body]}]
  (case type
    :evolutionary/concepts-embedded
    (assoc state (:ontology-id body)
           {:embedded? true
            :build-id (:build-id body)
            :embedded-count (:embedded-count body)
            :embedding-fields (vec (:embedding-fields body))
            :model-id (:model-id body)
            :embedded-at (:embedded-at body)})

    :evolutionary/concepts-embedding-updated
    (update state (:ontology-id body)
            (fn [existing]
              (merge existing
                     {:embedded? true
                      :embedded-count (:total-embedded-count body)
                      :embedding-fields (vec (:embedding-fields body))
                      :updated-at (:updated-at body)})))

    ;; Pass through unchanged
    state))

(defreadmodel :ontology ontology-embedding-state
  {:events ontology-embedding-events :version 1}
  [state event] (ontology-embedding-state* state event))

;; =============================================================================
;; Ontology-Embedding Query Helpers
;; =============================================================================

(defn get-embedding-state-for-ontology
  "Get the embedding state for an ontology.

   Returns nil if no embeddings exist for this ontology.
   Returns map with:
     {:embedded? true
      :embedded-count N
      :embedding-fields [:label :description ...]
      :model-id \"...\"
      :embedded-at \"...\"}"
  [ctx ontology-id]
  (get (rmp/project ctx :ontology/ontology-embedding-state
                    {:tags #{[:ontology ontology-id]}})
       ontology-id))

(defn ontology-has-embeddings?
  "Check if an ontology has been embedded (ready for RRF search)."
  [ctx ontology-id]
  (boolean (:embedded? (get-embedding-state-for-ontology ctx ontology-id))))

(defn list-ontology-embedding-states
  "List all ontology embedding states."
  [ctx]
  (rmp/project ctx :ontology/ontology-embedding-state))

(defn get-embedded-ontologies
  "Get all ontology-ids that have embeddings."
  [ctx]
  (->> (list-ontology-embedding-states ctx)
       (filter (fn [[_id state]] (:embedded? state)))
       (map first)
       set))

;; =============================================================================
;; Learned Rules Projection (Self-Learning)
;; =============================================================================
;; Builds per-tree extracted rules from successful episodes

(def learned-rule-events
  "Events that affect the learned-rules read model"
  #{:ontology/learned-rule-extracted})

(defmulti learned-rules*
  "Apply event to learned rules read model.
   State: {tree-id -> [rule ...]}"
  (fn [_state event] (:event/type event)))

(defmethod learned-rules* :ontology/learned-rule-extracted
  [state event]
  (let [tree-id (:tree-id event)
        rule {:rule-id (:rule-id event)
              :condition (get-in event [:rule :condition])
              :action (get-in event [:rule :action])
              :confidence (get-in event [:rule :confidence])
              :success-rate (get-in event [:rule :success-rate])
              :evidence-episodes (vec (get-in event [:rule :evidence-episodes] []))
              :problem-type (:problem-type event)
              :domain-type (:domain-type event)
              :extracted-at (str (:extracted-at event))}]
    (update state tree-id (fnil conj []) rule)))

(defmethod learned-rules* :default [state _] state)

(defn learned-rules
  "Build learned rules from events."
  [initial-state events]
  (reduce learned-rules* initial-state events))

(defreadmodel :ontology learned-rules
  {:events learned-rule-events, :version 1}
  [state event] (learned-rules* state event))

;; =============================================================================
;; Learned Rules Query Helpers
;; =============================================================================

(defn get-tree-rules
  "Get learned rules for a specific tree."
  [ctx tree-id]
  (get (rmp/project ctx :ontology/learned-rules {:tags #{[:tree tree-id]}}) tree-id))

(defn get-all-learned-rules
  "Get all learned rules for all trees."
  [ctx]
  (rmp/project ctx :ontology/learned-rules))

(defn find-rules-by-problem
  "Find rules that were extracted for a specific problem type."
  [ctx problem-type]
  (let [all-rules (get-all-learned-rules ctx)]
    (->> all-rules
         vals
         (apply concat)
         (filter #(= problem-type (:problem-type %)))
         vec)))

(defn find-rules-by-condition
  "Find rules that match given condition criteria.

   conditions: Map of condition key-value pairs to match
   Returns rules where all specified conditions are present in the rule's condition map."
  [ctx conditions]
  (let [all-rules (get-all-learned-rules ctx)]
    (->> all-rules
         vals
         (apply concat)
         (filter (fn [rule]
                   (let [rule-conditions (:condition rule)]
                     (every? (fn [[k v]]
                               (= v (get rule-conditions k)))
                             conditions))))
         vec)))

(defn learned-rules-statistics
  "Get statistics about learned rules."
  [ctx]
  (let [all-rules (get-all-learned-rules ctx)
        all-rules-flat (apply concat (vals all-rules))]
    {:total-rules (count all-rules-flat)
     :trees-with-rules (count all-rules)
     :by-problem-type (frequencies (map :problem-type all-rules-flat))
     :by-domain-type (frequencies (keep :domain-type all-rules-flat))
     :avg-confidence (when (seq all-rules-flat)
                       (/ (reduce + 0 (map :confidence all-rules-flat))
                          (count all-rules-flat)))
     :avg-success-rate (when (seq all-rules-flat)
                         (/ (reduce + 0 (map :success-rate all-rules-flat))
                            (count all-rules-flat)))}))

;; =============================================================================
;; Apartment Listings Projection
;; =============================================================================
;; Builds indexes for apartment listings by ID, URL, city, and site

(def apartment-listing-events
  "Events that affect apartment listings read model"
  #{:apartment/listing-recorded
    :apartment/listing-updated})

(defmulti apartment-listings*
  "Apply event to apartment listings read model.
   State: {:by-id {uuid -> listing}
           :by-url {url -> listing-id}
           :by-city {city -> #{listing-ids}}
           :by-site {site -> #{listing-ids}}
           :price-history {listing-id -> [{:price :date}]}}"
  (fn [_state event] (:event/type event)))

(defmethod apartment-listings* :apartment/listing-recorded
  [state event]
  (let [{:keys [listing-id source-url source-site city]} event
        listing {:listing-id listing-id
                 :source-site source-site
                 :source-url source-url
                 :extracted-at (str (:extracted-at event))
                 :title (:title event)
                 :price (:price event)
                 :price-numeric (:price-numeric event)
                 :bedrooms (:bedrooms event)
                 :bathrooms (:bathrooms event)
                 :sqft (:sqft event)
                 :address (:address event)
                 :neighborhood (:neighborhood event)
                 :city city
                 :state (:state event)
                 :amenities (vec (or (:amenities event) []))
                 :lat (:lat event)
                 :lng (:lng event)}]
    (-> state
        (assoc-in [:by-id listing-id] listing)
        (assoc-in [:by-url source-url] listing-id)
        (update-in [:by-city city] (fnil conj #{}) listing-id)
        (update-in [:by-site source-site] (fnil conj #{}) listing-id)
        ;; Initialize price history
        (assoc-in [:price-history listing-id]
                  [{:price (:price event)
                    :price-numeric (:price-numeric event)
                    :date (str (:extracted-at event))}]))))

(defmethod apartment-listings* :apartment/listing-updated
  [state event]
  (let [{:keys [listing-id price price-numeric updated-at]} event]
    (-> state
        ;; Update listing price
        (update-in [:by-id listing-id] merge
                   {:price price
                    :price-numeric price-numeric})
        ;; Add to price history
        (update-in [:price-history listing-id]
                   (fnil conj [])
                   {:price price
                    :price-numeric price-numeric
                    :date (str updated-at)}))))

(defmethod apartment-listings* :default [state _] state)

(defn apartment-listings
  "Build apartment listings from events."
  [initial-state events]
  (reduce apartment-listings* initial-state events))

(defreadmodel :apartment listings
  {:events apartment-listing-events, :version 1}
  [state event] (apartment-listings* state event))

;; =============================================================================
;; Apartment Searches Projection
;; =============================================================================

(def apartment-search-events
  "Events that affect apartment searches read model"
  #{:apartment/search-recorded})

(defmulti apartment-searches*
  "Apply event to apartment searches read model.
   State: {:by-id {uuid -> search}
           :by-location {location -> [search-ids]}}"
  (fn [_state event] (:event/type event)))

(defmethod apartment-searches* :apartment/search-recorded
  [state event]
  (let [{:keys [search-id location]} event
        search {:search-id search-id
                :location location
                :max-rent (:max-rent event)
                :filters (:filters event)
                :listing-count (:listing-count event)
                :source-sites (vec (:source-sites event))
                :listing-ids (vec (:listing-ids event))
                :searched-at (str (:searched-at event))}]
    (-> state
        (assoc-in [:by-id search-id] search)
        (update-in [:by-location location] (fnil conj []) search-id))))

(defmethod apartment-searches* :default [state _] state)

(defn apartment-searches
  "Build apartment searches from events."
  [initial-state events]
  (reduce apartment-searches* initial-state events))

(defreadmodel :apartment searches
  {:events apartment-search-events, :version 1}
  [state event] (apartment-searches* state event))

;; =============================================================================
;; Apartment Query Helpers
;; =============================================================================

(defn get-listing
  "Get an apartment listing by ID."
  [ctx listing-id]
  (get-in (rmp/project ctx :apartment/listings {:tags #{[:listing listing-id]}})
          [:by-id listing-id]))

(defn get-listing-by-url
  "Get an apartment listing by source URL."
  [ctx source-url]
  (let [state (rmp/project ctx :apartment/listings)]
    (when-let [listing-id (get-in state [:by-url source-url])]
      (get-in state [:by-id listing-id]))))

(defn get-listings-by-city
  "Get all apartment listings for a city."
  [ctx city & [{:keys [limit]}]]
  (let [state (rmp/project ctx :apartment/listings)
        listing-ids (get-in state [:by-city city] #{})
        listings (keep #(get-in state [:by-id %]) listing-ids)]
    (if limit
      (take limit listings)
      listings)))

(defn get-listings-by-site
  "Get all apartment listings from a specific site."
  [ctx source-site & [{:keys [limit]}]]
  (let [state (rmp/project ctx :apartment/listings)
        listing-ids (get-in state [:by-site source-site] #{})
        listings (keep #(get-in state [:by-id %]) listing-ids)]
    (if limit
      (take limit listings)
      listings)))

(defn get-listings-by-price-range
  "Get apartment listings within a price range."
  [ctx min-price max-price & [{:keys [city limit]}]]
  (let [state (rmp/project ctx :apartment/listings)
        all-listings (vals (:by-id state))
        filtered (filter (fn [l]
                           (when-let [p (:price-numeric l)]
                             (and (>= p min-price)
                                  (<= p max-price)
                                  (or (nil? city) (= city (:city l))))))
                         all-listings)]
    (if limit
      (take limit filtered)
      filtered)))

(defn get-price-history
  "Get price history for a listing."
  [ctx listing-id]
  (get-in (rmp/project ctx :apartment/listings {:tags #{[:listing listing-id]}})
          [:price-history listing-id]))

(defn get-recent-searches
  "Get recent searches, optionally filtered by location."
  [ctx & [{:keys [location limit] :or {limit 20}}]]
  (let [state (rmp/project ctx :apartment/searches)
        searches (if location
                   (->> (get-in state [:by-location location] [])
                        (keep #(get-in state [:by-id %])))
                   (vals (:by-id state)))
        sorted (->> searches
                    (sort-by :searched-at)
                    reverse)]
    (take limit sorted)))

;; =============================================================================
;; Apartment Statistics
;; =============================================================================

(defn apartment-statistics
  "Get statistics about stored apartment listings."
  [ctx]
  (let [listing-state (rmp/project ctx :apartment/listings)
        search-state (rmp/project ctx :apartment/searches)
        listings (vals (:by-id listing-state))]
    {:total-listings (count listings)
     :listings-by-city (into {}
                             (map (fn [[city ids]] [city (count ids)])
                                  (:by-city listing-state)))
     :listings-by-site (into {}
                             (map (fn [[site ids]] [site (count ids)])
                                  (:by-site listing-state)))
     :total-searches (count (:by-id search-state))
     :price-range (when (seq listings)
                    (let [prices (keep :price-numeric listings)]
                      (when (seq prices)
                        {:min (apply min prices)
                         :max (apply max prices)
                         :avg (/ (reduce + prices) (count prices))})))}))

;; =============================================================================
;; Site Registry Projection (Multi-Site Discovery)
;; =============================================================================
;; Builds site registry with trust scores and learned patterns

(def site-registry-events
  "Events that affect site registry read model"
  #{:apartment/site-registered
    :apartment/site-trust-updated
    :apartment/site-pattern-learned})

(defmulti site-registry*
  "Apply event to site registry read model.
   State: {:by-domain {domain -> site}
           :by-trust [domains sorted by trust]
           :patterns {domain -> [patterns]}}"
  (fn [_state event] (:event/type event)))

(defmethod site-registry* :apartment/site-registered
  [state event]
  (let [{:keys [site-id domain display-name category discovered-via
                url-pattern requires-headed known-challenges notes registered-at]} event
        site {:site-id site-id
              :domain domain
              :display-name display-name
              :category category
              :discovered-via discovered-via
              :url-pattern url-pattern
              :requires-headed (boolean requires-headed)
              :known-challenges (vec (or known-challenges []))
              :notes notes
              :trust-score 0.5  ;; Initial trust score
              :extraction-count 0
              :registered-at (str registered-at)}]
    (-> state
        (assoc-in [:by-domain domain] site)
        (update :by-trust (fn [domains]
                            (->> (conj (or domains []) domain)
                                 (sort-by (fn [d]
                                            (- (get-in state [:by-domain d :trust-score] 0.5))))
                                 vec))))))

(defmethod site-registry* :apartment/site-trust-updated
  [state event]
  (let [{:keys [domain trust-score extraction-count
                last-success-at last-failure-at updated-at]} event]
    (-> state
        (update-in [:by-domain domain] merge
                   {:trust-score trust-score
                    :extraction-count extraction-count
                    :last-success-at last-success-at
                    :last-failure-at last-failure-at})
        ;; Re-sort by-trust list
        (update :by-trust (fn [domains]
                            (->> (or domains [])
                                 (sort-by (fn [d]
                                            (- (get-in state [:by-domain d :trust-score] 0.5))))
                                 vec))))))

(defmethod site-registry* :apartment/site-pattern-learned
  [state event]
  (let [{:keys [domain pattern-type pattern-data confidence learned-at]} event
        pattern {:pattern-type pattern-type
                 :pattern-data pattern-data
                 :confidence confidence
                 :learned-at (str learned-at)}]
    (update-in state [:patterns domain] (fnil conj []) pattern)))

(defmethod site-registry* :default [state _] state)

(defn site-registry
  "Build site registry from events."
  [initial-state events]
  (reduce site-registry* initial-state events))

(defreadmodel :apartment site-registry
  {:events site-registry-events, :version 1}
  [state event] (site-registry* state event))

;; =============================================================================
;; Site Registry Query Helpers
;; =============================================================================

(defn get-site-by-domain
  "Get a site by its domain."
  [ctx domain]
  (get-in (rmp/project ctx :apartment/site-registry) [:by-domain domain]))

(defn get-all-sites
  "Get all registered sites."
  [ctx]
  (vals (get (rmp/project ctx :apartment/site-registry) :by-domain {})))

(defn get-trusted-sites
  "Get sites with trust score above threshold, sorted by trust.

   Args:
   - min-trust: Minimum trust score (default 0.5)
   - limit: Maximum sites to return"
  [ctx & [{:keys [min-trust limit] :or {min-trust 0.5}}]]
  (let [state (rmp/project ctx :apartment/site-registry)
        all-sites (vals (:by-domain state))
        trusted (->> all-sites
                     (filter #(>= (:trust-score % 0) min-trust))
                     (sort-by :trust-score >))]
    (if limit
      (take limit trusted)
      trusted)))

(defn get-site-patterns
  "Get learned patterns for a site.

   Args:
   - domain: Site domain
   - pattern-type: Optional filter by pattern type"
  [ctx domain & [{:keys [pattern-type]}]]
  (let [patterns (get-in (rmp/project ctx :apartment/site-registry) [:patterns domain] [])]
    (if pattern-type
      (filter #(= pattern-type (:pattern-type %)) patterns)
      patterns)))

(defn get-sites-requiring-headed
  "Get sites that require headed browser mode."
  [ctx]
  (->> (get-all-sites ctx)
       (filter :requires-headed)))

(defn site-registry-statistics
  "Get statistics about the site registry."
  [ctx]
  (let [state (rmp/project ctx :apartment/site-registry)
        sites (vals (:by-domain state))
        patterns (get state :patterns {})]
    {:total-sites (count sites)
     :by-category (frequencies (map :category sites))
     :by-discovered-via (frequencies (map :discovered-via sites))
     :sites-requiring-headed (count (filter :requires-headed sites))
     :total-patterns (reduce + (map count (vals patterns)))
     :avg-trust-score (when (seq sites)
                        (/ (reduce + (map :trust-score sites))
                           (count sites)))
     :sites-with-extractions (count (filter #(> (:extraction-count % 0) 0) sites))}))
