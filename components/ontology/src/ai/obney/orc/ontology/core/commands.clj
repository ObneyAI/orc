(ns ai.obney.orc.ontology.core.commands
  "Ontology command handlers.

   Commands for:
   - Recording tree strengths and weaknesses
   - Recording problem mappings
   - Recording node pattern learnings
   - Initializing static ontology concepts
   - Classifying evaluations and auto-recording failures
   - Embedding concepts and profiles (Phase 4)

   All commands emit events that are processed by read models."
  (:require [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.discovery :as discovery]
            [ai.obney.orc.ontology.core.rule-extraction :as rule-extraction]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- now-str []
  (str (time/now)))

(defn- generate-uuid []
  (random-uuid))

;; =============================================================================
;; Static Ontology Initialization
;; =============================================================================

(defcommand :ontology initialize-static-ontology
  "Initialize the static ontology by emitting concept-created events.
   Optional :scope to only initialize specific ontology layer."
  [{{:keys [scope]} :command
    :keys [event-store]}]
  (let [ontology-id (generate-uuid)
        concepts (if scope
                   (static/get-concepts-by-scope scope)
                   (static/get-all-static-concepts))
        relationships (static/get-all-static-relationships)
        now (now-str)

        ;; Create ontology lifecycle event
        ontology-event (->event
                        {:type :ontology/ontology-created
                         :tags #{[:ontology ontology-id]}
                         :body {:ontology-id ontology-id
                                :name (case scope
                                        :failure "Failure Ontology"
                                        :success "Success Ontology"
                                        :problem "Problem Domain Ontology"
                                        "ObneyAI Workshop Ontology")
                                :scope (or scope :all)
                                :description "Three-layer ontology system: Failure, Success, Problem Domain"
                                :base-uri "http://obney.ai/workshop/ontology/"
                                :created-at now}})

        ;; Create concept events
        concept-events (mapv (fn [concept]
                               (let [concept-id (generate-uuid)]
                                 (->event
                                  {:type :ontology/concept-created
                                   :tags #{[:ontology ontology-id]
                                           [:concept concept-id]}  ;; Only UUID-based tags allowed
                                   :body {:ontology-id ontology-id
                                          :concept-id concept-id
                                          :uri (:uri concept)
                                          :label (:label concept)
                                          :description (:description concept)
                                          :scope (:scope concept)
                                          :broader (:broader concept)
                                          :indicators (:indicators concept)
                                          :created-at now}})))
                             concepts)

        ;; Create relationship events
        relationship-events (mapv (fn [rel]
                                    (->event
                                     {:type :ontology/relationship-created
                                      :tags #{[:ontology ontology-id]}
                                      :body {:relationship-id (generate-uuid)
                                             :source-uri (:source rel)
                                             :target-uri (:target rel)
                                             :predicate (:predicate rel)
                                             :created-at now}}))
                                  relationships)]

    {:command-result/events (vec (concat [ontology-event]
                                         concept-events
                                         relationship-events))}))

;; =============================================================================
;; Tree Profile Commands
;; =============================================================================

(defcommand :ontology record-tree-strength
  "Record that a tree demonstrates a particular success pattern.

   Domain-agnostic fields:
   - context-conditions: Map of any conditions at decision time (replaces state-conditions)
   - action-taken: Map describing the action that led to success
   - domain-type: String identifier like \"drone-control\", \"legal-review\", \"sales-outreach\"
   - expected-outcome: String describing what success looks like"
  [{{:keys [tree-id pattern-uri confidence evidence-trace-ids avg-score
            ;; Domain-agnostic fields
            context-conditions state-conditions  ;; state-conditions for backward compat
            action-taken domain-type expected-outcome]} :command
    :keys [event-store]}]
  (let [now (now-str)
        ;; Merge old and new field names for compatibility
        conditions (or context-conditions state-conditions)]
    {:command-result/events
     [(->event
       {:type :ontology/tree-strength-recorded
        :tags #{[:tree tree-id]}  ;; Only UUID-based tags allowed
        :body (cond-> {:tree-id tree-id
                       :pattern-uri pattern-uri
                       :confidence confidence
                       :evidence-trace-ids (vec evidence-trace-ids)
                       :avg-score avg-score
                       :recorded-at now}
                ;; Add domain-agnostic fields when present
                conditions (assoc :context-conditions conditions
                                  :state-conditions conditions)  ;; both for compat
                action-taken (assoc :action-taken action-taken)
                domain-type (assoc :domain-type domain-type)
                expected-outcome (assoc :expected-outcome expected-outcome))})]}))

(defcommand :ontology record-tree-weakness
  "Record that a tree exhibits a particular failure pattern.

   Domain-agnostic fields:
   - failure-context: Map of conditions when failure occurred (replaces failure-conditions)
   - attempted-action: Map describing the action that was attempted
   - domain-type: String identifier like \"drone-control\", \"legal-review\", \"sales-outreach\""
  [{{:keys [tree-id failure-uri subtype-uri frequency severity triggers evidence-trace-ids
            ;; Domain-agnostic fields
            failure-context failure-conditions  ;; failure-conditions for backward compat
            attempted-action domain-type]} :command
    :keys [event-store]}]
  (let [now (now-str)
        severity-kw (if (keyword? severity) severity (keyword severity))
        ;; Merge old and new field names for compatibility
        context (or failure-context failure-conditions)]
    {:command-result/events
     [(->event
       {:type :ontology/tree-weakness-recorded
        :tags #{[:tree tree-id]}  ;; Only UUID-based tags allowed
        :body (cond-> {:tree-id tree-id
                       :failure-uri failure-uri
                       :frequency frequency
                       :severity severity-kw
                       :triggers (vec triggers)
                       :evidence-trace-ids (vec evidence-trace-ids)
                       :recorded-at now}
                ;; Only include subtype-uri when provided (schema expects :string, not nil)
                subtype-uri (assoc :subtype-uri subtype-uri)
                ;; Add domain-agnostic fields when present
                context (assoc :failure-context context
                               :failure-conditions context)  ;; both for compat
                attempted-action (assoc :attempted-action attempted-action)
                domain-type (assoc :domain-type domain-type))})]}))

(defcommand :ontology record-problem-mapping
  "Record that a tree solves a particular problem type."
  [{{:keys [tree-id problem-uri success-rate execution-count]} :command
    :keys [event-store] :as ctx}]
  (let [now (now-str)
        ;; Check if mapping already exists
        existing-profile (rm/get-tree-profile ctx tree-id)
        existing-mapping (some #(when (= problem-uri (:problem-uri %)) %)
                               (:solves existing-profile))]
    (if existing-mapping
      ;; Update existing mapping
      {:command-result/events
       [(->event
         {:type :ontology/tree-problem-mapping-updated
          :tags #{[:tree tree-id]}  ;; Only UUID-based tags allowed
          :body {:tree-id tree-id
                 :problem-uri problem-uri
                 :success-rate success-rate
                 :execution-count execution-count
                 :updated-at now}})]}
      ;; Create new mapping
      {:command-result/events
       [(->event
         {:type :ontology/tree-problem-mapping-created
          :tags #{[:tree tree-id]}  ;; Only UUID-based tags allowed
          :body {:tree-id tree-id
                 :problem-uri problem-uri
                 :success-rate success-rate
                 :execution-count execution-count
                 :recorded-at now}})]})))

(defcommand :ontology add-domain-knowledge
  "Add domain knowledge learned from tree execution."
  [{{:keys [tree-id node-id description based-on-failure-traces impact-score]} :command
    :keys [event-store]}]
  (let [now (now-str)
        knowledge-id (generate-uuid)]
    {:command-result/events
     [(->event
       {:type :ontology/domain-knowledge-added
        :tags #{[:tree tree-id]
                (when node-id [:node node-id])}
        :body {:knowledge-id knowledge-id
               :tree-id tree-id
               :node-id node-id
               :description description
               :based-on-failure-traces (vec based-on-failure-traces)
               :impact-score impact-score
               :added-at now}})]}))

;; =============================================================================
;; Node Learning Commands
;; =============================================================================

(defcommand :ontology record-node-pattern
  "Record a pattern learned from node execution."
  [{{:keys [node-id sheet-id node-type pattern-type effective? pattern-description
            metrics evidence-trace-ids]} :command
    :keys [event-store]}]
  (let [now (now-str)
        node-type-kw (if (keyword? node-type) node-type (keyword node-type))
        pattern-type-kw (if (keyword? pattern-type) pattern-type (keyword pattern-type))]
    {:command-result/events
     [(->event
       {:type :ontology/node-pattern-learned
        :tags #{[:node node-id]
                [:sheet sheet-id]}  ;; Only UUID-based tags allowed
        :body {:node-id node-id
               :sheet-id sheet-id
               :node-type node-type-kw
               :pattern-type pattern-type-kw
               :effective? effective?
               :pattern-description pattern-description
               :metrics metrics
               :evidence-trace-ids (vec evidence-trace-ids)
               :learned-at now}})]}))

;; =============================================================================
;; Classification Command
;; =============================================================================

(defcommand :ontology classify-evaluation
  "Classify an evaluation result and optionally auto-record tree weaknesses.

   Takes evaluation results from the evaluation component and:
   1. Classifies failures using the failure ontology
   2. Optionally records weaknesses on the tree profile

   Args:
   - trace-id: The trace that was evaluated
   - sheet-id: The sheet (tree) that was executed
   - node-id: The specific node that was evaluated
   - evaluation-result: {:score :dimensions [{:name :score :feedback}]}
   - auto-record?: If true, emit tree-weakness-recorded events"
  [{{:keys [trace-id sheet-id node-id evaluation-result auto-record?]} :command
    :keys [event-store]}]
  (let [classification (classifier/classify-evaluation evaluation-result)
        now (now-str)

        ;; If auto-record is enabled, emit weakness events for each failure
        weakness-events (when (and auto-record? (seq (:failures classification)))
                          (mapv (fn [{:keys [uri base-uri subtype-uri confidence evidence dimension]}]
                                  (->event
                                   {:type :ontology/tree-weakness-recorded
                                    :tags #{[:tree sheet-id]
                                            [:trace trace-id]}  ;; Only UUID-based tags allowed
                                    :body {:tree-id sheet-id
                                           :failure-uri base-uri
                                           :subtype-uri subtype-uri
                                           :frequency confidence  ; Use confidence as initial frequency
                                           :severity (classifier/estimate-severity
                                                      {:uri uri
                                                       :confidence confidence
                                                       :dimension-score (some #(when (= dimension (:name %))
                                                                                  (:score %))
                                                                              (:dimensions evaluation-result))})
                                           :triggers (classifier/extract-triggers [{:evidence evidence}])
                                           :evidence-trace-ids [trace-id]
                                           :recorded-at now}}))
                                (:failures classification)))]

    {:command-result/data {:classification classification
                           :auto-recorded? (boolean auto-record?)
                           :recorded-weaknesses (count (or weakness-events []))}
     :command-result/events (or weakness-events [])}))

;; =============================================================================
;; Concept Extension Commands
;; =============================================================================

(defcommand :ontology create-concept
  "Create a new concept in the ontology."
  [{{:keys [ontology-id uri label description scope broader indicators]} :command
    :keys [event-store]}]
  (let [concept-id (generate-uuid)
        now (now-str)
        scope-kw (if (keyword? scope) scope (keyword scope))]
    {:command-result/events
     [(->event
       {:type :ontology/concept-created
        :tags #{[:ontology ontology-id]
                [:concept concept-id]}  ;; Only UUID-based tags allowed
        :body {:ontology-id ontology-id
               :concept-id concept-id
               :uri uri
               :label label
               :description description
               :scope scope-kw
               :broader (vec (or broader []))
               :indicators (vec (or indicators []))
               :created-at now}})]}))

(defcommand :ontology create-relationship
  "Create a relationship between two concepts."
  [{{:keys [source-uri target-uri predicate properties]} :command
    :keys [event-store]}]
  (let [relationship-id (generate-uuid)
        now (now-str)]
    {:command-result/events
     [(->event
       {:type :ontology/relationship-created
        :tags #{[:relationship relationship-id]}  ;; Only UUID-based tags allowed
        :body {:relationship-id relationship-id
               :source-uri source-uri
               :target-uri target-uri
               :predicate predicate
               :properties properties
               :created-at now}})]}))

;; =============================================================================
;; Discovery Commands
;; =============================================================================

(defcommand :ontology propose-failure-subtype
  "Propose a new failure subtype discovered from analysis."
  [{{:keys [parent-uri proposed-uri label description evidence-count]} :command
    :keys [event-store]}]
  (let [discovery-id (generate-uuid)
        now (now-str)]
    {:command-result/events
     [(->event
       {:type :ontology/failure-subtype-discovered
        :tags #{[:discovery discovery-id]}  ;; Only UUID-based tags allowed
        :body {:discovery-id discovery-id
               :parent-uri parent-uri
               :proposed-uri proposed-uri
               :label label
               :description description
               :evidence-count evidence-count
               :discovered-at now
               :status :proposed}})]}))

;; =============================================================================
;; Embedding Commands (Phase 4)
;; =============================================================================

(defcommand :ontology configure-embedding-model
  "Configure an embedding model for a specific ontology scope.

   This allows different scopes (failure, success, problem) to use
   different embedding models optimized for their content."
  [{{:keys [scope model-id dimensions]} :command
    :keys [event-store]}]
  (let [ontology-id (generate-uuid)
        now (now-str)
        scope-kw (if (keyword? scope) scope (keyword scope))
        dims (or dimensions embedding/default-dimensions)]
    {:command-result/events
     [(->event
       {:type :ontology/embedding-model-configured
        :tags #{[:ontology ontology-id]}  ;; Only UUID-based tags allowed
        :body {:ontology-id ontology-id
               :scope scope-kw
               :model-id model-id
               :dimensions dims
               :configured-at now}})]}))

(defcommand :ontology embed-concept
  "Generate and store embedding for a concept.

   Args:
   - uri: The concept URI to embed
   - fields: Optional set of fields to include (:label :description :indicators :triggers)"
  [{{:keys [uri fields]} :command
    :keys [event-store] :as ctx}]
  (let [;; Get the concept from static ontology or event store
        concept (or (static/get-concept-by-uri uri)
                    (rm/get-concept-by-uri ctx uri))
        _ (when-not concept
            (throw (ex-info "Concept not found" {:uri uri
                                                 ::anom/category ::anom/not-found})))

        ;; Prepare text for embedding
        fields-set (or (when fields (set fields)) #{:label :description})
        text (embedding/concept->embedding-text concept fields-set)

        ;; Generate embedding
        embedding-vec (embedding/embed-text text)
        _ (when-not embedding-vec
            (throw (ex-info "Failed to generate embedding" {:uri uri
                                                            ::anom/category ::anom/fault})))

        concept-id (or (:id concept) (generate-uuid))
        now (now-str)]
    {:command-result/data {:uri uri
                            :dimensions (count embedding-vec)
                            :text-embedded text}
     :command-result/events
     [(->event
       {:type :ontology/concept-embedded
        :tags #{[:concept concept-id]}  ;; Only UUID-based tags allowed
        :body {:concept-id concept-id
               :uri uri
               :ontology-id (:ontology-id concept)
               :scope (:scope concept)
               :text-embedded text
               :field-source (name (first fields-set))
               :embedding embedding-vec
               :model-id embedding/default-model-id
               :embedded-at now}})]}))

(defcommand :ontology embed-concepts-batch
  "Embed multiple concepts in batch.

   Args:
   - scope: Optional scope to filter concepts (:failure :success :problem)
   - uris: Optional specific URIs to embed (otherwise all in scope)
   - fields: Optional set of fields to include"
  [{{:keys [scope uris fields]} :command
    :keys [event-store] :as ctx}]
  (let [;; Get concepts to embed
        concepts (cond
                   (seq uris)
                   (keep #(or (static/get-concept-by-uri %)
                              (rm/get-concept-by-uri ctx %))
                         uris)

                   scope
                   (static/get-concepts-by-scope scope)

                   :else
                   (static/get-all-static-concepts))

        fields-set (or (when fields (set fields)) #{:label :description})
        now (now-str)

        ;; Generate events for each concept
        events (reduce
                 (fn [acc concept]
                   (let [text (embedding/concept->embedding-text concept fields-set)
                         embedding-vec (embedding/embed-text text)
                         concept-id (or (:id concept) (generate-uuid))]
                     (if embedding-vec
                       (conj acc
                             (->event
                              {:type :ontology/concept-embedded
                               :tags #{[:concept concept-id]}  ;; Only UUID-based tags allowed
                               :body {:concept-id concept-id
                                      :uri (:uri concept)
                                      :ontology-id (:ontology-id concept)
                                      :scope (:scope concept)
                                      :text-embedded text
                                      :field-source (name (first fields-set))
                                      :embedding embedding-vec
                                      :model-id embedding/default-model-id
                                      :embedded-at now}}))
                       acc)))
                 []
                 concepts)]

    {:command-result/data {:embedded-count (count events)
                            :total-concepts (count concepts)}
     :command-result/events events}))

(defcommand :ontology embed-tree-profile
  "Generate and store embedding for a tree profile summary."
  [{{:keys [tree-id]} :command
    :keys [event-store] :as ctx}]
  (let [profile (rm/get-tree-profile ctx tree-id)
        _ (when-not profile
            (throw (ex-info "Tree profile not found" {:tree-id tree-id
                                                      ::anom/category ::anom/not-found})))

        text (embedding/tree-profile->embedding-text profile)
        embedding-vec (embedding/embed-text text)
        _ (when-not embedding-vec
            (throw (ex-info "Failed to generate embedding" {:tree-id tree-id
                                                            ::anom/category ::anom/fault})))
        now (now-str)]

    {:command-result/data {:tree-id tree-id
                            :dimensions (count embedding-vec)
                            :text-embedded text}
     :command-result/events
     [(->event
       {:type :ontology/tree-profile-embedded
        :tags #{[:tree tree-id]}
        :body {:tree-id tree-id
               :text-embedded text
               :embedding embedding-vec
               :model-id embedding/default-model-id
               :embedded-at now}})]}))

(defcommand :ontology embed-evaluation-feedback
  "Generate and store embedding for evaluation feedback.

   Useful for finding similar evaluation feedback across traces."
  [{{:keys [trace-id dimension feedback failure-uri]} :command
    :keys [event-store]}]
  (let [text (embedding/evaluation-feedback->embedding-text feedback dimension)
        embedding-vec (embedding/embed-text text)
        _ (when-not embedding-vec
            (throw (ex-info "Failed to generate embedding" {:trace-id trace-id
                                                            ::anom/category ::anom/fault})))
        now (now-str)]

    {:command-result/data {:trace-id trace-id
                            :dimension dimension
                            :dimensions (count embedding-vec)}
     :command-result/events
     [(->event
       {:type :ontology/evaluation-embedded
        :tags #{[:trace trace-id]}  ;; Only UUID-based tags allowed
        :body {:trace-id trace-id
               :dimension dimension
               :feedback feedback
               :embedding embedding-vec
               :failure-uri failure-uri
               :model-id embedding/default-model-id
               :embedded-at now}})]}))

;; =============================================================================
;; Pattern Discovery Commands
;; =============================================================================

(defcommand :ontology run-pattern-discovery
  "Analyze low-scoring evaluation feedback to discover new failure subtypes.

   Reads :evaluation/trace-evaluated events for the specified sheet,
   filters to those below the score threshold, and uses an LLM to identify
   recurring failure patterns not covered by the current ontology.

   Args:
   - sheet-id: The sheet to analyze evaluations for
   - min-traces: Minimum traces required to run (default 20)
   - score-threshold: Only analyze traces below this score (default 0.6)

   Returns:
   - If insufficient traces: {:skipped true :reason ... :found N :required M}
   - Otherwise: {:discovered N :analyzed-traces M :subtypes [...]}

   Emits :ontology/failure-subtype-discovered events for each new pattern."
  [{{:keys [sheet-id min-traces score-threshold]} :command
    :keys [event-store] :as ctx}]
  (let [result (discovery/discover-patterns ctx sheet-id
                 {:min-traces (or min-traces 20)
                  :score-threshold (or score-threshold 0.6)})]

    (if (:skipped result)
      ;; Not enough traces - return data only, no events
      {:command-result/data result}

      ;; Emit events for each discovered subtype
      (let [now (now-str)
            events (mapv (fn [subtype]
                           (->event
                             {:type :ontology/failure-subtype-discovered
                              :tags #{[:discovery (generate-uuid)]
                                      [:sheet sheet-id]}
                              :body (merge subtype
                                      {:discovery-id (generate-uuid)
                                       :status :proposed
                                       :discovered-at now})}))
                         (:subtypes result))]

        {:command-result/data (dissoc result :subtypes)
         :command-result/events events}))))

;; =============================================================================
;; Rule Extraction Commands (Self-Learning)
;; =============================================================================

(defcommand :ontology extract-learned-rules
  "Extract condition-action rules from successful episodes.

   Domain-agnostic: Works with any domain by accepting domain-type and domain-description.

   Analyzes tree-strength-recorded events with rich context to extract
   reusable condition-action rules that can be injected into future LLM prompts.

   Args:
   - tree-id: UUID of the tree to analyze
   - problem-type: Problem type URI being solved
   - min-episodes: Minimum episodes required (default 5)
   - domain-type: Domain identifier, e.g. 'drone-control', 'legal-review'
   - domain-description: Human-readable context for LLM"
  [{{:keys [tree-id problem-type min-episodes domain-type domain-description]} :command
    :keys [event-store] :as ctx}]
  (let [result (rule-extraction/extract-rules ctx tree-id
                 {:domain-type (or domain-type "unknown")
                  :domain-description (or domain-description "General task execution")
                  :min-episodes (or min-episodes 5)})]

    (if (:skipped result)
      ;; Not enough episodes - return data only, no events
      {:command-result/data result}

      ;; Emit learned-rule-extracted events for each rule
      (let [now (now-str)
            events (mapv (fn [rule]
                           (->event
                             {:type :ontology/learned-rule-extracted
                              :tags #{[:tree tree-id]}
                              :body {:rule-id (generate-uuid)
                                     :tree-id tree-id
                                     :rule {:condition (or (:conditions rule) {})
                                            :action (or (:action rule) {})
                                            :confidence (or (:confidence rule) 0.8)
                                            :success-rate (or (:success-rate rule) 0.9)
                                            :evidence-episodes []}
                                     :problem-type problem-type
                                     :domain-type (:domain-type result)
                                     :extracted-at now}}))
                         (:rules result))]

        {:command-result/data {:extracted (count events)
                                :analyzed-episodes (:analyzed-episodes result)
                                :domain-type (:domain-type result)
                                :tree-id tree-id}
         :command-result/events events}))))

;; =============================================================================
;; C-2 Living Description Commands
;; =============================================================================
;; One command per granularity. Each emits the corresponding
;; *-description-updated event. Append-only — the read-model maintains
;; "current" + "history" per (granularity, target-id).
;;
;; Tag values must be UUIDs per Grain's event-store-v3 contract. For
;; non-UUID target-ids (keywords for node-types, strings for tree-
;; fingerprints) we derive a deterministic UUID via nameUUIDFromBytes
;; (same idiom used in orc-service/core/runtime.clj for deterministic
;; node IDs). The natural-form target-id is still carried inside the
;; event body so consumers retrieve it directly.

(defn- stable-uuid-from
  "Derive a deterministic UUID from a stringifiable value. Used for
   tag values where the target-id is not natively a UUID (node-type
   keyword, tree-fingerprint hash)."
  [v]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str v) "UTF-8")))

(defcommand :ontology record-node-type-description
  "Record (or update) the description for a node-type — a cross-sheet
   aggregation across every node of this :type. Emits the
   :ontology/node-type-description-updated event."
  [{{:keys [target-id body]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/node-type-description-updated
      :tags #{[:description-target (stable-uuid-from (str "node-type:" target-id))]}
      :body {:target-type :node-type
             :target-id target-id
             :body body
             :recorded-at (now-str)}})]})

(defcommand :ontology record-node-instance-description
  "Record (or update) the description for a specific node instance —
   keyed by [sheet-id node-id]. Emits the
   :ontology/node-instance-description-updated event."
  [{{:keys [target-id body]} :command}]
  (let [[sheet-id node-id] target-id]
    {:command-result/events
     [(->event
       {:type :ontology/node-instance-description-updated
        :tags #{[:sheet sheet-id]
                [:node node-id]
                [:description-target (stable-uuid-from
                                       (str "node-instance:" sheet-id ":" node-id))]}
        :body {:target-type :node-instance
               :target-id target-id
               :body body
               :recorded-at (now-str)}})]}))

(defcommand :ontology record-tree-description
  "Record (or update) the description for a tree-fingerprint —
   identifying all trees with the same canonical structure. Emits the
   :ontology/tree-description-updated event."
  [{{:keys [target-id body]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/tree-description-updated
      :tags #{[:description-target (stable-uuid-from
                                     (str "tree-fingerprint:" target-id))]}
      :body {:target-type :tree-fingerprint
             :target-id target-id
             :body body
             :recorded-at (now-str)}})]})

(defcommand :ontology record-tree-class-description
  "C-Loop-1: record (or update) the description for a tree-class —
   the substrate R-Inject's classifier reads via get-description.
   Distinct from :tree-fingerprint, which keys on canonical-S-expr
   SHAs of observed trees; :tree-class keys on the stable seed UUID
   (or fresh-mint root UUID) the classifier assigns. Emits the same
   :ontology/tree-description-updated event with :target-type :tree-class."
  [{{:keys [target-id body]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/tree-description-updated
      :tags #{[:description-target (stable-uuid-from
                                     (str "tree-class:" target-id))]}
      :body {:target-type :tree-class
             :target-id target-id
             :body body
             :recorded-at (now-str)}})]})

(defcommand :ontology record-anti-recency-rejection
  "Gap-6: record an audit event when the anti-recency validator
   REJECTED an emission because the LLM-produced body dropped a
   protected entry (high confidence + high evidence-count) from the
   prior body. Emits :ontology/anti-recency-rejection. Audit trail
   only — does not affect the description read-model."
  [{{:keys [target-type target-id bucket entry-trait prior-confidence
            prior-evidence-count reason rejected-body]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/anti-recency-rejection
      :tags #{[:description-target (stable-uuid-from
                                     (str target-type ":" target-id))]}
      :body {:target-type target-type
             :target-id target-id
             :bucket bucket
             :entry-trait entry-trait
             :prior-confidence prior-confidence
             :prior-evidence-count prior-evidence-count
             :reason reason
             :rejected-body rejected-body
             :detected-at (now-str)}})]})

(defcommand :ontology record-anti-recency-clamp
  "Gap-6: record an audit event when the anti-recency validator
   CLAMPED a protected entry's confidence because the LLM dropped it
   by more than max-confidence-decrease-per-cycle. Emits :ontology/
   anti-recency-clamp-applied. Audit trail only — the clamped body
   is still emitted normally via record-description."
  [{{:keys [target-type target-id bucket entry-trait prior-confidence
            llm-confidence clamped-confidence reason]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/anti-recency-clamp-applied
      :tags #{[:description-target (stable-uuid-from
                                     (str target-type ":" target-id))]}
      :body {:target-type target-type
             :target-id target-id
             :bucket bucket
             :entry-trait entry-trait
             :prior-confidence prior-confidence
             :llm-confidence llm-confidence
             :clamped-confidence clamped-confidence
             :reason reason
             :detected-at (now-str)}})]})

;; =============================================================================
;; C-2a-3a — Consolidation trigger commands
;; =============================================================================
;;
;; Two commands:
;; 1. :ontology/request-consolidation — emits :ontology/consolidation-requested.
;;    Manual REPL helper sets :on-demand? true; the threshold-tracking
;;    processor (todo_processors.clj) emits with :on-demand? false.
;; 2. :ontology/set-consolidation-threshold — event-sourced threshold config.
;;    Emits :ontology/consolidation-threshold-set. The threshold-config
;;    read-model projects this for per-target-type lookup with a default
;;    of 10 events.

(defcommand :ontology request-consolidation
  "Emit an :ontology/consolidation-requested event for the given target.

   Two paths:

   1. On-demand (:on-demand? true) — always emits. Used by REPL helpers
      and tests that want a consolidation regardless of counter state.

   2. Threshold-driven (:on-demand? false / unset) — uses Grain CAS to
      enforce exactly-once-per-threshold-crossing semantics:

         * Reads the target's lifetime :total source-event count and
           configured threshold from read-models.
         * Derives crossing-number = (quot total threshold) — stable
           across all concurrent handlers within the same threshold
           window of source events.
         * Tags the event with [:crossing <stable-uuid-from-crossing>]
           and CAS-guards on that tag being absent. The first concurrent
           handler's append succeeds; subsequent handlers' appends get
           ::anom/conflict and emit no event.

      This survives burst-event races where N processor handlers all
      observe delta >= threshold before any prior reset has propagated
      to projections — only the first handler's CAS predicate evaluates
      true at append time inside the event store."
  [{{:keys [target-id target-type on-demand?]} :command :as ctx}]
  (let [target-uuid (stable-uuid-from
                      (str (name target-type) ":" target-id))
        threshold-driven? (not on-demand?)
        ;; Crossing-number stays constant across a window of `threshold`
        ;; source events. All concurrent handlers within the window
        ;; compute the same crossing-uuid; CAS lets only one win.
        crossing-uuid (when threshold-driven?
                        (let [total (rm/get-consolidation-total
                                      ctx target-type target-id)
                              threshold (rm/get-consolidation-threshold
                                          ctx target-type)
                              crossing-num (quot total threshold)]
                          (stable-uuid-from
                            (str (name target-type) ":" target-id
                                 ":crossing-" crossing-num))))
        event (->event
                (cond-> {:type :ontology/consolidation-requested
                         :tags (cond-> #{[:description-target target-uuid]}
                                 crossing-uuid (conj [:crossing crossing-uuid]))
                         :body {:target-type target-type
                                :target-id target-id
                                :on-demand? (boolean on-demand?)
                                :requested-at (now-str)}}))]
    (cond-> {:command-result/events [event]}
      threshold-driven?
      (assoc :command-result/cas
             {:types #{:ontology/consolidation-requested}
              :tags #{[:crossing crossing-uuid]}
              :predicate-fn (fn [existing] (empty? (into [] existing)))}))))

(defcommand :ontology set-consolidation-threshold
  "Set the consolidation threshold for a target-type. Emits
   :ontology/consolidation-threshold-set; the threshold-config read-model
   projects this for runtime threshold lookup."
  [{{:keys [target-type threshold]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/consolidation-threshold-set
      :tags #{[:description-target (stable-uuid-from
                                     (str "threshold:" (name target-type)))]}
      :body {:target-type target-type
             :threshold threshold
             :set-at (now-str)}})]})

(defcommand :ontology set-living-description-enabled
  "Gap-1: flip the system-level opt-in for the Living Description loop.
   When set to true, the writing side of the loop activates — consolidator
   handles requests, threshold processor's consolidation-requested emissions
   are honored, the per-event evaluator runtime auto-executes attached
   judges, and (future C-3) judge feedback feeds into consolidator inputs.
   Default false (consumer must opt in)."
  [{{:keys [enabled?]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/living-description-enabled-set
      :tags #{[:description-target (stable-uuid-from "living-description-enabled-config")]}
      :body {:enabled? enabled?
             :set-at (now-str)}})]})

(defcommand :ontology set-consolidation-budget
  "C-2a-3c: set the hourly consolidation budget for a target-type.
   Emits :ontology/consolidation-budget-set; the budget-config read-model
   projects this for runtime budget lookup. Default 100/hour applies
   when no override has been set."
  [{{:keys [target-type budget]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/consolidation-budget-set
      :tags #{[:description-target (stable-uuid-from
                                     (str "budget:" (name target-type)))]}
      :body {:target-type target-type
             :budget budget
             :set-at (now-str)}})]})

(defcommand :ontology set-reindex-config
  "C-2b-1: set the global ColBERT re-index config (event-count threshold
   + timer-minutes). Emits :ontology/reindex-config-set; the reindex-config
   read-model projects this for runtime lookup. Defaults (10 events, 5
   minutes) apply when no override has been set."
  [{{:keys [reindex-threshold-events reindex-timer-minutes]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/reindex-config-set
      :tags #{[:description-target (stable-uuid-from "reindex-config")]}
      :body {:reindex-threshold-events reindex-threshold-events
             :reindex-timer-minutes reindex-timer-minutes
             :set-at (now-str)}})]})

;; =============================================================================
;; C-2c-2 — Auto-classifier command
;; =============================================================================
;;
;; Dispatched by the executor's auto-classify wedge after `classify-task`
;; computes a tree-class assignment. Stamps :classified-at and emits
;; :ontology/task-classified with the full body. The [:tick tick-id] tag
;; lets the runtime cheaply query "what was this tick's classification?"
;; when constructing the run-result envelope.

(defcommand :ontology assign-task-class
  "C-2c-2 + C-2d-2: record an auto-classification decision. Takes the
   result of `ontology/classify-task` plus the (source-sheet-id,
   source-tick-id, source-node-id) provenance triple and emits
   :ontology/task-classified. Stateless beyond the event itself; the
   classification machinery (classify-task) is pure and runs upstream
   in the executor wedge.

   C-2d-2 — optional :parent-tree-id forwarded from the walk-down
   classifier when the result is a deep match or fresh-mint under a
   matched ancestor. Omitted on top-level matches and when walk-down
   is disabled."
  [{{:keys [source-sheet-id source-tick-id source-node-id
            assigned-tree-id confidence top-candidates reasoning
            was-fresh-mint? parent-tree-id rerank-failed?
            behavioral-subtrees]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/task-classified
      :tags #{[:tick source-tick-id]
              [:description-target assigned-tree-id]}
      :body (cond-> {:source-sheet-id source-sheet-id
                     :source-tick-id source-tick-id
                     :source-node-id source-node-id
                     :assigned-tree-id assigned-tree-id
                     :confidence confidence
                     :top-candidates top-candidates
                     :reasoning reasoning
                     :classified-at (now-str)
                     :was-fresh-mint? was-fresh-mint?}
              parent-tree-id (assoc :parent-tree-id parent-tree-id)
              ;; R01: forward reranker-failure flag when present.
              (some? rerank-failed?) (assoc :rerank-failed? rerank-failed?)
              ;; R05b: forward behavioral-subtree classification when
              ;; the wedge called classify-behaviors after classify-task.
              ;; Omit when absent so legacy events stay unchanged.
              (some? behavioral-subtrees)
              (assoc :behavioral-subtrees behavioral-subtrees))})]})

;; =============================================================================
;; R05c — Mint a new behavioral-subtree concept
;; =============================================================================
;; Closes the self-evolution loop on the agent side: the recursive RLM
;; researcher's sandbox primitive (mint-behavior! ...) (orc-service)
;; dispatches this command with :provenance :agent-minted, populating
;; :minted-by-sheet-id / :minted-by-tick-id from the sandbox context.
;; Hand-authored mints dispatch directly with :provenance :human-authored.
;;
;; The handler:
;;   - Generates a fresh UUID for the new target-id (single owner of
;;     identity; callers don't pass :target-id)
;;   - Emits TWO events: the audit-trail :ontology/behavioral-subtree-minted
;;     AND the standard :ontology/tree-description-updated so the R05a
;;     reactive processor projects the concept + composes-into edges +
;;     parent-behavior skos:broader link.
;;   - Stamps :scope :behavioral-subtree on the description body so the
;;     R05a processor's filter fires. Rejects bodies that explicitly
;;     declare a different scope to surface intent mismatch.
;;   - Stamps :minted-at ISO timestamp.

(defcommand :ontology mint-behavioral-subtree
  "R05c: mint a new behavioral-subtree concept. Returns two events:
   the provenance-tagged audit-trail event and the standard
   tree-description-updated event the R05a processor projects into the
   concept graph. :provenance is MANDATORY — never default; mixing
   agent-minted and human-authored entries breaks the audit trail for
   future C-3 review queues."
  [{{:keys [name body parent-behavior provenance
            minted-by-sheet-id minted-by-tick-id
            harvested-from-tree-class]} :command}]
  (let [body-scope (:scope body)]
    (when (and (some? body-scope) (not= body-scope :behavioral-subtree))
      (throw (ex-info
               (str "mint-behavioral-subtree rejects body with :scope " body-scope
                    "; mint affordance only routes to :behavioral-subtree.")
               {::anom/category ::anom/incorrect
                :body-scope body-scope}))))
  ;; C-Loop-2 D4: derive the target-id from (name, parent-behavior) so the
  ;; same logical mint always lands at the same identity. If the agent
  ;; accidentally calls (mint-behavior! "name" ...) twice, both calls
  ;; resolve to the same concept rather than polluting the graph with
  ;; duplicates. Audit events still emit per call — provenance trail is
  ;; preserved.
  (let [identity-bytes (.getBytes (str "mint:" name ":" parent-behavior) "UTF-8")
        target-id (java.util.UUID/nameUUIDFromBytes identity-bytes)
        minted-at (now-str)
        stamped-body (cond-> (assoc body :scope :behavioral-subtree)
                       parent-behavior (assoc :parent-behavior parent-behavior))
        ;; EL-4 (ADR 0015): the harvested path fires from a per-event
        ;; processor, so N concurrent handlers within one gate-crossing
        ;; window can all reach the mint before any prior append is visible
        ;; to their read of the guard. Enforce exactly-once-per-tree-class
        ;; via Grain CAS on a stable [:harvested-tree-class <uuid>] tag —
        ;; the first append wins; the rest get ::anom/conflict and emit
        ;; nothing. Only on the :harvested path; hand-authored + agent mints
        ;; keep their append-always semantics (their idempotency is the
        ;; stable derived target-id + latest-wins description projection).
        harvest-crossing (when harvested-from-tree-class
                           (stable-uuid-from
                             (str "harvested-tree-class:" harvested-from-tree-class)))]
    (cond->
      {:command-result/events
       [(->event
          {:type :ontology/behavioral-subtree-minted
           :tags (cond-> #{[:behavioral-subtree-minted target-id]}
                   harvest-crossing (conj [:harvested-tree-class harvest-crossing]))
           :body (cond-> {:target-id target-id
                          :name name
                          :provenance provenance
                          :minted-at minted-at}
                   parent-behavior   (assoc :parent-behavior parent-behavior)
                   minted-by-sheet-id (assoc :minted-by-sheet-id minted-by-sheet-id)
                   minted-by-tick-id  (assoc :minted-by-tick-id minted-by-tick-id)
                   harvested-from-tree-class (assoc :harvested-from-tree-class
                                                    harvested-from-tree-class))})
        (->event
          {:type :ontology/tree-description-updated
           :tags #{[:description-target target-id]}
           :body {:target-type :tree-fingerprint
                  :target-id target-id
                  :body stamped-body
                  :recorded-at minted-at}})]}
      harvest-crossing
      (assoc :command-result/cas
             {:types #{:ontology/behavioral-subtree-minted}
              :tags #{[:harvested-tree-class harvest-crossing]}
              :predicate-fn (fn [existing] (empty? (into [] existing)))}))))

;; =============================================================================
;; Site Registry Commands (Generic Site Pattern Learning)
;; =============================================================================

(defcommand :site register-site
  "Register a new apartment listing site.

   Checks if site already exists by domain.
   If exists, returns existing site-id without emitting event.
   If new, emits site-registered event with initial trust score."
  [{{:keys [domain display-name category discovered-via
            url-pattern requires-headed known-challenges notes]} :command
    :keys [event-store] :as ctx}]
  (let [existing (rm/get-site-by-domain ctx domain)]
    (if existing
      ;; Site already registered
      {:command-result/message "Site already registered"
       :command-result/data {:site-id (:site-id existing)
                             :domain domain}}
      ;; Register new site
      (let [site-id (generate-uuid)
            now (now-str)]
        {:command-result/events
         [(->event
           {:type :site/registered
            :tags #{[:site site-id]}
            :body (cond-> {:site-id site-id
                           :domain domain
                           :display-name display-name
                           :category category
                           :discovered-via discovered-via
                           :registered-at now}
                    url-pattern (assoc :url-pattern url-pattern)
                    (some? requires-headed) (assoc :requires-headed requires-headed)
                    (seq known-challenges) (assoc :known-challenges (vec known-challenges))
                    notes (assoc :notes notes))})]
         :command-result/data {:site-id site-id
                               :domain domain}}))))

(defcommand :site update-site-trust
  "Update trust score for a site based on extraction success/failure.

   Trust score calculation:
   - Success: trust = (current * count + 1) / (count + 1)
   - Failure: trust = (current * count + 0) / (count + 1)

   This is a moving average that weighs recent results appropriately."
  [{{:keys [domain success? listings-extracted]} :command
    :keys [event-store] :as ctx}]
  (let [existing (rm/get-site-by-domain ctx domain)]
    (if-not existing
      {::anom/category ::anom/not-found
       ::anom/message (str "Site not found: " domain)}

      (let [now (now-str)
            current-trust (or (:trust-score existing) 0.5)
            current-count (or (:extraction-count existing) 0)
            ;; Calculate new trust score as moving average
            new-count (inc current-count)
            new-trust (/ (+ (* current-trust current-count)
                            (if success? 1.0 0.0))
                         new-count)]
        {:command-result/events
         [(->event
           {:type :site/trust-updated
            :tags #{[:site (:site-id existing)]}
            :body (cond-> {:site-id (:site-id existing)
                           :domain domain
                           :trust-score new-trust
                           :extraction-count new-count
                           :updated-at now}
                    success? (assoc :last-success-at now)
                    (not success?) (assoc :last-failure-at now))})]
         :command-result/data {:domain domain
                               :trust-score new-trust
                               :extraction-count new-count}}))))

(defcommand :site record-site-pattern
  "Record a learned navigation/extraction pattern for a site.

   Patterns are site-specific tactics learned over time:
   - navigation: How to navigate to listings page
   - search: How to use search filters
   - extraction: Selectors and strategies for extracting listings
   - bot-bypass: Techniques for avoiding bot detection
   - pagination: How to navigate between pages"
  [{{:keys [domain pattern-type pattern-data confidence]} :command
    :keys [event-store] :as ctx}]
  (let [existing (rm/get-site-by-domain ctx domain)]
    (if-not existing
      {::anom/category ::anom/not-found
       ::anom/message (str "Site not found: " domain)}

      (let [now (now-str)
            pattern-type-kw (if (keyword? pattern-type)
                              pattern-type
                              (keyword pattern-type))]
        {:command-result/events
         [(->event
           {:type :site/pattern-learned
            :tags #{[:site (:site-id existing)]}
            :body {:site-id (:site-id existing)
                   :domain domain
                   :pattern-type pattern-type-kw
                   :pattern-data pattern-data
                   :confidence (or confidence 0.8)
                   :learned-at now}})]
         :command-result/data {:domain domain
                               :pattern-type pattern-type-kw}}))))
