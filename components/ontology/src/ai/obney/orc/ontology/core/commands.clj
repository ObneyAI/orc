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
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.orc.ontology.core.evidence :as evidence]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
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

        ;; Create relationship events. S06 — supply `:ontology-id` in the
        ;; event body so the section-keyed projection routes in O(1) (it
        ;; was already on the tag for retrieval-by-tag, but the projection
        ;; reads from body).
        relationship-events (mapv (fn [rel]
                                    (->event
                                     {:type :ontology/relationship-created
                                      :tags #{[:ontology ontology-id]}
                                      :body {:relationship-id (generate-uuid)
                                             :ontology-id ontology-id
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
  "Create a new concept in the ontology.

   S04 — the optional representation bundle fields (labels, comments,
   comment, see-also, is-defined-by, model-guidance, attributes) are
   ADDITIVE and forwarded verbatim. The classic :label / :description
   single-value path stays the back-compat default; the structured
   forms enrich the concept when present.

   S05 — `:attributes` values may carry the new quantity+unit shape
   `{:value v :unit \"u\"}` (with optional `:datatype dt` riding
   alongside). The presence of `:unit` on an attribute value selects
   the QUDT-style serialization path on export. Bare scalars and
   S04 `{:value :datatype}` typed values continue to flow through
   unchanged."
  [{{:keys [ontology-id uri label description scope broader indicators
            ;; S04 representation bundle additions
            labels comments comment see-also is-defined-by model-guidance
            attributes]} :command
    :keys [event-store]}]
  (let [concept-id (generate-uuid)
        now (now-str)
        scope-kw (if (keyword? scope) scope (keyword scope))]
    {:command-result/events
     [(->event
       {:type :ontology/concept-created
        :tags #{[:ontology ontology-id]
                [:concept concept-id]}  ;; Only UUID-based tags allowed
        :body (cond-> {:ontology-id ontology-id
                       :concept-id concept-id
                       :uri uri
                       :label label
                       :description description
                       :scope scope-kw
                       :broader (vec (or broader []))
                       :indicators (vec (or indicators []))
                       :created-at now}
                ;; Only attach the S04 fields when supplied — preserves
                ;; today's emitted event-body shape for callers that
                ;; don't use the bundle.
                (seq labels)        (assoc :labels (vec labels))
                (seq comments)      (assoc :comments (vec comments))
                comment             (assoc :comment comment)
                (seq see-also)      (assoc :see-also (vec see-also))
                is-defined-by       (assoc :is-defined-by is-defined-by)
                model-guidance      (assoc :model-guidance model-guidance)
                (seq attributes)    (assoc :attributes attributes))})]}))

;; S14 — record an ORSD spec revision for an ontology-id. Append-only,
;; mirroring the descriptions-event pattern:
;;   command (with body)
;;     → :ontology/ontology-spec-recorded event tagged [:ontology id]
;;     → ontology-specs projection holds :current + :history per id.
;; The :body schema (`ontology-spec-body`) is closed against unknown
;; top-level keys; the Grain command-processor's pre-handler Malli gate
;; rejects garbage shapes with ::anom/incorrect before this handler
;; ever runs (no defensive parsing here).
(defcommand :ontology record-ontology-spec
  "S14: record an ORSD spec revision for the given ontology-id. The
   spec is the ontology's requirements contract — purpose, scope,
   intended-uses, competency-questions, natural-language-statements,
   non-functional. All fields are optional (a spec can start as just
   CQs and grow); unknown extra keys in `:body` are rejected by the
   command schema's closed-map. Every invocation APPENDS — the
   ontology-specs projection retains the latest body as :current and
   the full chronological revision history as :history."
  [{{:keys [ontology-id body]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/ontology-spec-recorded
      :tags #{[:ontology ontology-id]}
      :body {:ontology-id ontology-id
             :body body
             :recorded-at (now-str)}})]})

;; S04 — Ontology-level metadata for the export header.
;; Per-ontology-id, NOT per-concept. Every annotation field is
;; OPTIONAL; only the fields the caller supplied land on the event
;; body so the projection (and downstream serializer) carry only what
;; was recorded — no defaulted-empty-string artefacts.
(defcommand :ontology record-ontology-metadata
  "S04: record (or replace) the ontology-level metadata that appears on
   the exported TTL's `owl:Ontology` header — title, version, license,
   creator. Per-ontology-id; the projection retains the latest values
   per ontology-id. Any field omitted from the command is omitted from
   the event body."
  [{{:keys [ontology-id title version license creator]} :command
    :keys [event-store]}]
  {:command-result/events
   [(->event
     {:type :ontology/ontology-metadata-recorded
      :tags #{[:ontology ontology-id]}
      :body (cond-> {:ontology-id ontology-id
                     :recorded-at (now-str)}
              title   (assoc :title title)
              version (assoc :version version)
              license (assoc :license license)
              creator (assoc :creator creator))})]})

(defcommand :ontology create-relationship
  "Create a relationship between two concepts.

   The `:predicate` is an open string — standard SKOS predicates
   (`\"skos:broader\"` / `\"skos:narrower\"` / `\"skos:related\"`)
   take the typed-edge paths in the projections; behavioral predicates
   (`\"behavior:composes-into\"`) take the bridge path; ANY OTHER
   predicate is stored as a `:related`-shaped edge on the source side
   and bidirectionally followed by the graph builder at expansion
   time.

   S05 — ordered-sequence convention. Order-dependent knowledge
   (recipe steps, procedure stages, narrative arcs) is encoded by
   emitting one `:ontology/create-relationship` per consecutive pair
   with predicate `\"immediately-follows\"`. Multi-hop \"X follows Y\"
   answers via the standard BFS expansion: an `immediately-follows`
   chain of length N is traversable from the head at BFS depth N.
   The transitive `\"follows\"` predicate is the planned extension
   (S07 will add the transitive-marker auto-closure mechanism); S05
   establishes only the convention so callers can start writing
   sequences today."
  [{{:keys [ontology-id source-uri target-uri predicate properties
            confidence-class evidence valid-from valid-to superseded-by]} :command
    :keys [event-store]}]
  (let [relationship-id (generate-uuid)
        now (now-str)]
    {:command-result/events
     [(->event
       {:type :ontology/relationship-created
        ;; S06 — when an `:ontology-id` is supplied, ALSO tag the event
        ;; by it so per-ontology event queries can locate relationships
        ;; without scanning. When not supplied (legacy callers), only the
        ;; [:relationship id] tag is emitted — the projection still works
        ;; via the find-where-endpoints-live fallback.
        :tags (cond-> #{[:relationship relationship-id]}
                ontology-id (conj [:ontology ontology-id]))
        :body (cond-> {:relationship-id relationship-id
                       :source-uri source-uri
                       :target-uri target-uri
                       :predicate predicate
                       :created-at now}
                ;; S06 — only include each metadata field when supplied.
                ;; Absent fields stay absent — the projection can
                ;; distinguish "no metadata" from "metadata was empty",
                ;; and the serializer skips reified emission entirely
                ;; for bare edges (reify-on-demand).
                ontology-id      (assoc :ontology-id ontology-id)
                confidence-class (assoc :confidence-class confidence-class)
                (seq evidence)   (assoc :evidence (vec evidence))
                valid-from       (assoc :valid-from valid-from)
                valid-to         (assoc :valid-to valid-to)
                superseded-by    (assoc :superseded-by superseded-by)
                (seq properties) (assoc :properties properties))})]}))

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
   - fields: Optional set of fields to include (:label :description :indicators :triggers)
   - embedding: OPTIONAL precomputed embedding vector — when supplied, the command
     LANDS it verbatim and SKIPS the DJL embed-text recompute (the caller already
     computed it, e.g. via a single batched pass). Kills the double-embed (2× DJL).
   - text-embedded: OPTIONAL text that produced the precomputed embedding (recorded
     verbatim on the event); defaults to the fields text when absent.
   - concept-id / ontology-id / scope: OPTIONAL concept metadata. When BOTH a
     precomputed :embedding AND :concept-id + :ontology-id are supplied, the command
     SKIPS the `get-concept-by-uri` projection entirely — the caller (which already
     holds the concept) provides identity, so landing is O(1) per concept instead of
     projecting the whole concepts read-model each call (the O(n²) embed-landing the
     inspect pass surfaced). Absent → today's lookup path (back-compat)."
  [{{:keys [uri fields embedding text-embedded concept-id ontology-id scope]} :command
    :keys [event-store] :as ctx}]
  (let [;; PERF: skip the per-concept full-projection lookup when the caller supplied
        ;; the identity metadata alongside a precomputed embedding (the single-pass
        ;; landing path). Otherwise fall back to the projection lookup (back-compat).
        have-meta? (and (seq embedding) concept-id ontology-id)
        concept (when-not have-meta?
                  (or (static/get-concept-by-uri uri)
                      (rm/get-concept-by-uri ctx uri)))
        _ (when (and (not have-meta?) (not concept))
            (throw (ex-info "Concept not found" {:uri uri
                                                 ::anom/category ::anom/not-found})))

        ;; Prepare text for embedding — the precomputed text when supplied.
        fields-set (or (when fields (set fields)) #{:label :description})
        text (or text-embedded
                 (when concept (embedding/concept->embedding-text concept fields-set)))

        ;; Generate embedding — REUSE a precomputed vector when the caller supplied
        ;; one (the single-pass path, no double-embed); else compute via DJL.
        embedding-vec (or embedding (embedding/embed-text text))
        _ (when-not (seq embedding-vec)
            (throw (ex-info "Failed to generate embedding" {:uri uri
                                                            ::anom/category ::anom/fault})))

        ;; identity from the supplied metadata (O(1) path) or the looked-up concept.
        concept-id (or concept-id (:id concept) (generate-uuid))
        final-ontology-id (or ontology-id (:ontology-id concept))
        final-scope (or scope (:scope concept))
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
               :ontology-id final-ontology-id
               :scope final-scope
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
            minted-by-sheet-id minted-by-tick-id]} :command}]
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
                       parent-behavior (assoc :parent-behavior parent-behavior))]
    {:command-result/events
     [(->event
        {:type :ontology/behavioral-subtree-minted
         :tags #{[:behavioral-subtree-minted target-id]}
         :body (cond-> {:target-id target-id
                        :name name
                        :provenance provenance
                        :minted-at minted-at}
                 parent-behavior   (assoc :parent-behavior parent-behavior)
                 minted-by-sheet-id (assoc :minted-by-sheet-id minted-by-sheet-id)
                 minted-by-tick-id  (assoc :minted-by-tick-id minted-by-tick-id))})
      (->event
        {:type :ontology/tree-description-updated
         :tags #{[:description-target target-id]}
         :body {:target-type :tree-fingerprint
                :target-id target-id
                :body stamped-body
                :recorded-at minted-at}})]}))

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

;; =============================================================================
;; S07 — Axiom-as-data commands
;; =============================================================================
;;
;; Four axiom families, each emitted as a distinct event type, projected
;; per-ontology-id into :ontology/axioms, exported as proper OWL.
;;
;; CRITICAL non-goal: these commands do NOT trigger inference. Asserting
;; that two classes are disjoint NEVER causes the projection to remove a
;; concept's class membership. Asserting a predicate functional NEVER
;; causes the projection to dedup existing values. Lint slices (S11) catch
;; violations AT VALIDATION TIME from the projected axiom data.

(defcommand :ontology assert-disjointness
  "Record that the given set of class URIs is mutually disjoint
   (`owl:disjointWith`). The Malli schema rejects singleton sets at the
   pre-handler gate.

   The projection treats this SYMMETRICALLY: each URI in the set maps to
   the OTHERS as its disjoint siblings. Re-asserting on the same set is
   idempotent at the projection layer (set semantics)."
  [{{:keys [ontology-id class-uris]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/disjointness-asserted
      :tags #{[:ontology ontology-id]}
      :body {:ontology-id ontology-id
             :class-uris (vec class-uris)
             :asserted-at (now-str)}})]})

(defcommand :ontology assert-property-characteristic
  "Record one or more OWL property characteristics (`owl:FunctionalProperty`,
   `owl:TransitiveProperty`, `owl:SymmetricProperty`) and/or an `owl:inverseOf`
   pairing for a predicate.

   `:characteristic` may be empty when the only declaration is an inverse-of
   pairing. The Malli schema's enum bounds the allowed flags; unknown
   values are rejected at the pre-handler gate.

   When a transitive characteristic is asserted, the predicate becomes
   followable by axiom-aware BFS (`retrieval/expand-concept-neighborhood`
   with `:transitive-only? true`) — closure mechanism unchanged; the
   marker simply lands in the predicate filter."
  [{{:keys [ontology-id predicate characteristic inverse-of]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/property-characteristic-asserted
      :tags #{[:ontology ontology-id]}
      :body (cond-> {:ontology-id ontology-id
                     :predicate predicate
                     :characteristic (vec (or characteristic []))
                     :asserted-at (now-str)}
              inverse-of (assoc :inverse-of inverse-of))})]})

(defcommand :ontology assert-sub-property
  "Record an `rdfs:subPropertyOf` relationship between two predicates.
   The sub-predicate is the more specific predicate; the super-predicate
   is the broader predicate (e.g. hasMother sub-property-of hasParent).
   Stored as a per-ontology map; lint slices consult it for property
   hierarchy reasoning IF the consumer chooses; this slice ONLY records."
  [{{:keys [ontology-id sub-predicate super-predicate]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/sub-property-asserted
      :tags #{[:ontology ontology-id]}
      :body {:ontology-id ontology-id
             :sub-predicate sub-predicate
             :super-predicate super-predicate
             :asserted-at (now-str)}})]})

(defcommand :ontology assert-sub-class
  "EB6 MINT — record an `rdfs:subClassOf` relationship between two CLASS
   URIs. The sub-class is the more specific class; the super-class is the
   broader class (e.g. Dog subClassOf Animal). Mirrors `assert-sub-property`
   exactly: stored as a per-ontology sub→super map; lint/export consult it.

   CLOSES the EB3 `:sub-class` candidate's previously-pathless silent drop:
   before this command, class subsumption could only be set on the
   concept-creation `:broader` SKOS field, which cannot be asserted over
   ALREADY-LANDED concepts at axiom time — so a `:sub-class` candidate had
   NO emission target and was dropped. This is a DATA assertion only — NO
   inference (the formality ceiling is unchanged: it never reclassifies a
   concept's class membership)."
  [{{:keys [ontology-id sub-class super-class]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/sub-class-asserted
      :tags #{[:ontology ontology-id]}
      :body {:ontology-id ontology-id
             :sub-class sub-class
             :super-class super-class
             :asserted-at (now-str)}})]})

(defcommand :ontology assert-chain-axiom
  "Record a chain definition P∘Q→R (`owl:propertyChainAxiom`).

   DEFINITIONS only — query-time synthesis arrives in a later NEXT-tail
   slice. The chain is a vector of predicates (must have at least 2
   elements per the Malli schema); the derived predicate is the rule
   head."
  [{{:keys [ontology-id chain derived-predicate]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/chain-axiom-asserted
      :tags #{[:ontology ontology-id]}
      :body {:ontology-id ontology-id
             :chain (vec chain)
             :derived-predicate derived-predicate
             :asserted-at (now-str)}})]})

;; =============================================================================
;; S03 — Alignment-section registry commands
;; =============================================================================
;;
;; Two commands record the registry: register-alignment-section adds a
;; (primary-id, alignment-id) pair, deregister-alignment-section removes
;; it. The events are tagged BOTH on the primary AND the alignment
;; ontology — the primary is the lookup key the auto-widen path
;; consults, but tagging both keeps audit queries against either side
;; cheap. Registration is cycle-tolerant (the prototype's verdict): the
;; per-pair record carries no global invariant, so a registered cycle
;; P->A1, A1->P is accepted and the single-hop widening naturally
;; dedupes via set semantics.

(defcommand :ontology register-alignment-section
  "S03: register an alignment-section relationship — primary-ontology-id
   gains alignment-ontology-id as a registered alignment. Scoped queries
   against the primary section auto-widen to include the alignment via
   the existing S02 multi-section widening mechanism (the registry
   expands the caller's :ontology-id into a wider :ontology-ids list
   that is then passed down to all three retrieval signals).

   Cycle-tolerant: registering P->A1 and A1->P is accepted. The widen
   path is single-hop and dedupes via set semantics so neither widening
   loops nor returns a surprising fanout.

   Tagging: BOTH the primary and the alignment carry the event's
   :ontology tags so audit queries scoped to either side find it."
  [{{:keys [primary-ontology-id alignment-ontology-id]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/alignment-section-registered
      :tags #{[:ontology primary-ontology-id]
              [:ontology alignment-ontology-id]}
      :body {:primary-ontology-id primary-ontology-id
             :alignment-ontology-id alignment-ontology-id
             :registered-at (now-str)}})]})

(defcommand :ontology deregister-alignment-section
  "S03: deregister an alignment-section relationship — primary-ontology-
   id NO LONGER has alignment-ontology-id as a registered alignment.

   Idempotent on the CURRENT projection view (deregistering a pair that
   was never registered just leaves :current empty), but the event IS
   emitted regardless so the audit history records the action. The
   acceptance test asserts the very-next scoped query against the
   primary does NOT surface concepts from the now-deregistered
   alignment — no read-side stale-cache leak."
  [{{:keys [primary-ontology-id alignment-ontology-id]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/alignment-section-deregistered
      :tags #{[:ontology primary-ontology-id]
              [:ontology alignment-ontology-id]}
      :body {:primary-ontology-id primary-ontology-id
             :alignment-ontology-id alignment-ontology-id
             :deregistered-at (now-str)}})]})

;; =============================================================================
;; S08 — Equivalence-event command
;; =============================================================================
;;
;; A dedicated equivalence event with a REQUIRED `:kind` discriminator —
;; `:same-as` / `:equivalent-class` / `:equivalent-property`. The kind
;; is load-bearing: owl:sameAs ≠ owl:equivalentClass ≠ owl:equivalentProperty
;; under OWL DL semantics, and conflating them (most dangerously, sameAs
;; on classes) merges property assertions across the equivalence — the
;; inheritance-merge hazard documented in the round-2 grill. The pre-
;; handler Malli enum gate rejects unknown / missing kinds with
;; ::anom/incorrect; no silent kind defaulting.
;;
;; Tagging: the event tags to the ALIGNMENT section's :ontology-id
;; (NOT either endpoint's primary section). The prototype's Path B
;; verdict drove this: tagging to a primary would corrupt the URI-keyed
;; concept projection via last-write-wins collapse when the alignment
;; mints "proxy" concepts at the same URI. Path B keeps real concepts
;; intact and surfaces the equivalence via a parallel per-section
;; :ontology/equivalences projection.

(defcommand :ontology record-equivalence
  "S08: record an equivalence assertion between two URIs in an
   alignment section, with the kind discriminator carrying the OWL
   semantics. `:kind` is REQUIRED (no default — forcing the assertion
   to be deliberate per the slice's acceptance criteria):

     :same-as             → owl:sameAs            (individuals only)
     :equivalent-class    → owl:equivalentClass   (classes only)
     :equivalent-property → owl:equivalentProperty (properties only)

   Tagging: the event tags ONLY to the ALIGNMENT section's :ontology-id.
   Primary sections' event streams stay clean — no equivalence events
   leak into either primary's stream under its [:ontology <primary-id>]
   tag. The S08 prototype's Path B verdict.

   Idempotency: re-recording the same (kind, URI-pair) is benign — the
   projection canonicalizes endpoints as a sorted-pair set so re-
   assertion is a set no-op."
  [{{:keys [ontology-id source-uri target-uri kind evidence]} :command}]
  (let [equivalence-id (generate-uuid)
        now (now-str)]
    {:command-result/events
     [(->event
       {:type :ontology/equivalence-recorded
        ;; Tag the event with the alignment-section's :ontology-id AND
        ;; an equivalence-id (UUID tag for direct lookup if ever needed).
        ;; NOT tagged with either endpoint's primary section — that's
        ;; the slice's load-bearing cleanliness property.
        :tags #{[:ontology ontology-id]
                [:equivalence equivalence-id]}
        :body (cond-> {:equivalence-id equivalence-id
                       :ontology-id ontology-id
                       :source-uri source-uri
                       :target-uri target-uri
                       :kind kind
                       :recorded-at now}
                (seq evidence) (assoc :evidence (vec evidence)))})]}))

;; =============================================================================
;; S12 — Dedup-cascade command
;; =============================================================================
;;
;; Runs the tiered cheapest-first cascade for a candidate pair and emits the
;; events the verdict requires:
;;
;;   :verdict :merge              → :ontology/equivalence-recorded
;;                                  (+ :ontology/concept-pair-co-occurrence *)
;;   :verdict :distinct           → (:ontology/dedup-distinct-recorded *)
;;                                  (+ :ontology/concept-pair-co-occurrence *)
;;   :verdict :skip               → (:ontology/concept-pair-co-occurrence * ONLY)
;;   :verdict :requires-review    → (:ontology/concept-pair-co-occurrence * ONLY)
;;
;; Every verdict ALSO emits the S13 :ontology/concept-evidence-aggregated
;; events (one per side) — see below.
;;
;; ME-2 — the events marked (*) are the write-only pair LEDGERS
;; (`concept-pair-co-occurrence` + `dedup-distinct-recorded`). VERIFIED: no
;; read-model / query / reconcile / dedup consumer reads either — they are
;; aspirational incremental-dedup ledgers (~723k events in the real O*NET
;; build) of pure write-only bloat. They are now gated behind the OPT-IN
;; `:persist-pair-ledger?` command flag (default FALSE): a normal build does
;; NOT persist them; a future incremental-dedup caller opts in. The dedup
;; VERDICT (on `:command-result/data`) and the CONSUMED events (equivalence on
;; :merge, the S13 evidence events) are byte-identical in both modes.
;;
;; The T1 disjointness guard reads the S07 :ontology/axioms projection;
;; the merge case emits an equivalence event tagged to the alignment-
;; ontology-id (S08); the co-occurrence event is the C1 trail.
;;
;; LLM tier: unconditional functionality (NOT R-Inject-gated), but
;; :llm-budget knobbed. When the budget is exhausted, T9 surfaces
;; :requires-review — NEVER silently :merge, NEVER silently :skip.

(defcommand :ontology run-dedup-cascade
  "S12: run the tiered dedup cascade for a candidate URI pair.

   Emits the events the verdict requires (see ns doc). The verdict map
   itself rides on `:command-result/data` under :verdict for the caller's
   immediate inspection (the events carry the persistent record).

   When `:verdict :merge`, an `:alignment-ontology-id` MUST be supplied —
   the equivalence event tags to it. The Malli :and gate rejects the
   command up-front if the merge case is reached without one (handled
   by the handler: when merge verdict + no alignment, the handler returns
   a `::anom/incorrect` anomaly rather than silently downgrading the
   verdict)."
  [{{:keys [ontology-id alignment-ontology-id a b
            llm-budget string-merge-threshold string-ambiguity-lo lsh-jaccard-min
            a-source-ref b-source-ref
            disjointness existing-evidence
            persist-pair-ledger?]} :command
    :as ctx}]
  (let [;; DTscale-1 — project-once: when the caller (dedup-stage) threads the
        ;; per-section disjointness map (projected ONCE for the whole stage),
        ;; use it directly. Otherwise project `:ontology/axioms` here so the
        ;; command stays self-sufficient for direct callers / tests.
        disjointness (or disjointness
                         (get-in (rmp/project ctx :ontology/axioms)
                                 [ontology-id :disjointness])
                         {})
        ;; The guard's fn closes over the broader vectors on both candidates
        ;; AND the per-section disjointness map. The cascade tests this
        ;; FIRST — zero LLM calls if it fires.
        disjoint-pair-fn (fn [_a-uri _b-uri]
                           (dedup/disjoint-under-axioms?
                            disjointness
                            (:broader a [])
                            (:broader b [])))
        opts (cond-> {:a a :b b :disjoint-pair-fn disjoint-pair-fn}
               string-merge-threshold (assoc :string-merge-threshold string-merge-threshold)
               string-ambiguity-lo    (assoc :string-ambiguity-lo string-ambiguity-lo)
               lsh-jaccard-min        (assoc :lsh-jaccard-min lsh-jaccard-min)
               llm-budget             (assoc :llm-budget llm-budget
                                             :llm-counter (atom 0))
               (:llm-fn ctx)          (assoc :llm-fn (:llm-fn ctx)))
        verdict (dedup/run-cascade opts)
        now (now-str)
        a-uri (:uri a) b-uri (:uri b)
        co-occurrence-event
        (->event
         {:type :ontology/concept-pair-co-occurrence
          :tags #{[:ontology ontology-id]}
          :body {:ontology-id ontology-id
                 :source-uri a-uri
                 :target-uri b-uri
                 :context-source (:tier verdict)
                 :verdict (:verdict verdict)
                 :recorded-at now}})
        ;; --------------------------------------------------------
        ;; S13 — always-on evidence aggregation for BOTH sides.
        ;; --------------------------------------------------------
        ;; Pull the existing evidence projection ONCE and derive each
        ;; side's aggregate via the pure `evidence/aggregate-from-
        ;; cascade` helper. The same helper produces both events so
        ;; the math lives in ONE place (binding: any weight tweak
        ;; happens in `evidence.clj`, never inline here).
        ;;
        ;; Not R-Inject-gated — mechanism-level functionality. Every
        ;; cascade invocation emits one of these per side.
        ;; DTscale-1 — project-once: reuse the stage-supplied evidence map when
        ;; present; otherwise project here (self-sufficient for direct callers).
        existing-evidence (or existing-evidence
                              (rmp/project ctx :ontology/concept-evidence))
        a-agg (evidence/aggregate-from-cascade
               {:existing     (get existing-evidence a-uri {})
                :verdict      verdict
                :source-ref   a-source-ref
                :computed-at  now
                :alignment-id (when (= :merge (:verdict verdict))
                                alignment-ontology-id)})
        b-agg (evidence/aggregate-from-cascade
               {:existing     (get existing-evidence b-uri {})
                :verdict      verdict
                :source-ref   b-source-ref
                :computed-at  now
                :alignment-id (when (= :merge (:verdict verdict))
                                alignment-ontology-id)})
        evidence-event
        (fn [uri agg]
          (->event
           {:type :ontology/concept-evidence-aggregated
            :tags #{[:ontology ontology-id]}
            :body (cond->
                    {:ontology-id           ontology-id
                     :concept-uri           uri
                     :tier                  (or (:tier verdict) :unknown-tier)
                     :verdict               (:verdict verdict)
                     :tier-contributions    (:tier-contributions agg)
                     :sources-count         (:sources-count agg)
                     :dedup-decisions-count (:dedup-decisions-count agg)
                     :evidence-score        (:evidence-score agg)
                     :computed-at           (:computed-at agg)}
                    (seq (:source-refs agg))
                    (assoc :source-refs (vec (:source-refs agg)))
                    (seq (:equivalence-history agg))
                    (assoc :equivalence-history (vec (:equivalence-history agg))))}))
        a-evidence-event (evidence-event a-uri a-agg)
        b-evidence-event (evidence-event b-uri b-agg)
        evidence-events [a-evidence-event b-evidence-event]]
    (case (:verdict verdict)
      :merge
      (if-not alignment-ontology-id
        ;; Merge requires an alignment section. No silent downgrade — refuse.
        ;; Evidence aggregation is also DEFERRED here: emitting evidence
        ;; events alongside an anomaly would commit half-state. The
        ;; caller fixes the input and retries.
        {::anom/category ::anom/incorrect
         ::anom/message "run-dedup-cascade: :merge verdict requires :alignment-ontology-id"
         :command-result/data {:verdict verdict}}
        (let [equivalence-id (generate-uuid)
              evidence-vec (vec (remove nil?
                                        [(when-let [d (:detail verdict)] d)
                                         (when-let [r (:reason verdict)] (str r))
                                         (str (:tier verdict))]))]
          {:command-result/data {:verdict verdict}
           :command-result/events
           ;; The equivalence event is CONSUMED (S08/reconcile) — always emitted.
           ;; The co-occurrence LEDGER is write-only — ME-2 gates it behind the
           ;; opt-in flag (order preserved when opted in).
           (-> [(->event
                 {:type :ontology/equivalence-recorded
                  :tags #{[:ontology alignment-ontology-id]
                          [:equivalence equivalence-id]}
                  :body {:equivalence-id equivalence-id
                         :ontology-id alignment-ontology-id
                         :source-uri a-uri
                         :target-uri b-uri
                         :kind (:kind verdict)
                         :evidence evidence-vec
                         :recorded-at now}})]
               (cond-> persist-pair-ledger? (conj co-occurrence-event))
               (into evidence-events))}))

      :distinct
      {:command-result/data {:verdict verdict}
       :command-result/events
       ;; ME-2 — both the dedup-distinct LEDGER and the co-occurrence LEDGER are
       ;; write-only (no reader). Emitted ONLY when opted in; order preserved.
       ;; The S13 evidence events (CONSUMED) always ride.
       (-> []
           (cond-> persist-pair-ledger?
             (conj (->event
                    {:type :ontology/dedup-distinct-recorded
                     :tags #{[:ontology ontology-id]}
                     :body (cond-> {:ontology-id ontology-id
                                    :source-uri a-uri
                                    :target-uri b-uri
                                    :tier (:tier verdict)
                                    :reason (:reason verdict)
                                    :recorded-at now}
                             (:detail verdict) (assoc :evidence (:detail verdict)))})
                   co-occurrence-event))
           (into evidence-events))}

      ;; :skip and :requires-review emit the evidence-aggregated events (every
      ;; cascade run counts as evidence — even an undecided one tells us
      ;; something about the concept's neighborhood). Neither carries an
      ;; equivalence event (no merge claim) nor a dedup-distinct event (the
      ;; cascade declined to decide). The co-occurrence LEDGER (write-only)
      ;; rides ONLY when opted in (ME-2).
      (:skip :requires-review)
      {:command-result/data {:verdict verdict}
       :command-result/events
       (-> []
           (cond-> persist-pair-ledger? (conj co-occurrence-event))
           (into evidence-events))})))

;; =============================================================================
;; S13 — Concept contradiction recording
;; =============================================================================
;;
;; The builder calls this when it detects a field-value conflict between
;; an EXISTING concept's stored attribute and an INCOMING value from a
;; new source. The slice's load-bearing rule: the existing value is
;; NEVER silently overwritten — a contradiction marker is recorded and
;; queryable through the review surface. The decision to merge / refine
;; / refuse the conflict is the LATER Tier-2 LLM step's job; Tier-1's
;; job is to MAKE THE CONFLICT VISIBLE.
;;
;; This command emits ONLY the contradiction marker event. It does NOT
;; emit :ontology/concept-updated — silent overwrite is the adversarial
;; failure mode the slice test guards against.

(defcommand :ontology record-concept-contradiction
  "S13: record a field-value conflict between an EXISTING and an
   INCOMING source. The contradiction is MARKED and queryable; the
   stored field value is NEVER silently replaced. Tier-2 LLM
   consolidation (a separate slice) will eventually choose how to
   resolve them.

   Inputs:
   - :ontology-id     — the section the concept lives in
   - :concept-uri     — the concept whose field conflicts
   - :field           — :label / :description / etc.
   - :existing-value  — the value currently stored
   - :incoming-value  — the conflicting value from the new source
   - :existing-source — string ref identifying the existing value's source
   - :incoming-source — string ref identifying the incoming value's source"
  [{{:keys [ontology-id concept-uri field
            existing-value incoming-value
            existing-source incoming-source]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/concept-contradiction-recorded
      :tags #{[:ontology ontology-id]}
      :body {:ontology-id     ontology-id
             :concept-uri     concept-uri
             :field           field
             :existing-value  existing-value
             :incoming-value  incoming-value
             :existing-source existing-source
             :incoming-source incoming-source
             :recorded-at     (now-str)}})]})

;; =============================================================================
;; S15 — Record CQ evaluation result (per-CQ per-run)
;; =============================================================================
;;
;; The CQ runner dispatches ONE :ontology/record-cq-evaluation command
;; per CQ per evaluation run. Each command emits a single
;; :ontology/cq-evaluated event tagged by ontology-id. The
;; cq-evaluations projection accumulates the events; the graph-health
;; derivation walks the latest-run-per-CQ to compute pass-rate,
;; unknown-rate, and fail-rate.
;;
;; The :verdict field is ONE of :pass / :fail / :unknown. The :unknown
;; verdict is a FIRST-CLASS outcome (round-3 three-layer negation
;; posture) — never a fallback. The projection surfaces unknown-rate as
;; its own metric, NOT folded into fail-rate.
(defcommand :ontology record-cq-evaluation
  "S15: record a single CQ verdict from the runner against an
   ontology's stored ORSD spec. One command per CQ per run; the
   cq-evaluations projection aggregates per ontology-id and the
   graph-health derivation computes pass / unknown / fail rates."
  [{{:keys [ontology-id cq-index cq-text verdict reasoning
            evidence-uris judged-by? layer gaps]} :command}]
  {:command-result/events
   [(->event
     {:type :ontology/cq-evaluated
      :tags #{[:ontology ontology-id]}
      :body (cond-> {:ontology-id   ontology-id
                     :cq-index      cq-index
                     :cq-text       cq-text
                     :verdict       verdict
                     :reasoning     reasoning
                     :evidence-uris (vec evidence-uris)
                     :judged-by?    judged-by?
                     :layer         layer
                     :evaluated-at  (now-str)}
              (seq gaps) (assoc :gaps (vec gaps)))})]})
