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
  #{;; Domain events (from commands)
    :ontology/concept-created
    :ontology/concept-updated
    :ontology/relationship-created
    ;; Evolutionary events (from builder)
    :evolutionary/concepts-extracted
    :evolutionary/relationships-extracted})

(def tree-profile-events
  "Events that affect tree profile read model"
  #{:ontology/tree-strength-recorded
    :ontology/tree-weakness-recorded
    :ontology/tree-problem-mapping-created
    :ontology/tree-problem-mapping-updated
    :ontology/domain-knowledge-added})

(def description-events
  "C-2: events that affect the Living Description read model.

   One event type per granularity (node-type, node-instance, tree-fingerprint).
   Append-only — each event is a new version of the target's description;
   the projection maintains both 'current' (latest body) and 'history'
   (chronological vector of all versions)."
  #{:ontology/node-type-description-updated
    :ontology/node-instance-description-updated
    :ontology/tree-description-updated})

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

      ;; R05a — behavior:composes-into is the bridge from behavioral
      ;; subtrees (Layer 2) to structural shells (Layer 1). The behavior
      ;; carries an outgoing :composes-into set of shell URIs; the shell
      ;; carries an incoming :composed-by set of behavior URIs. R05b's
      ;; classify-behaviors traverses these edges when narrowing
      ;; candidates by structural-context.
      "behavior:composes-into"
      (-> state
          (update-in [source-uri :composes-into] (fnil conj #{}) target-uri)
          (update-in [target-uri :composed-by] (fnil conj #{}) source-uri))

      ;; Other predicates (owl:causes, etc.) - store as related
      (-> state
          (update-in [source-uri :related] (fnil conj #{}) target-uri)))))

;; -----------------------------------------------------------------------------
;; Evolutionary Event Handlers
;; -----------------------------------------------------------------------------
;; These handlers process events from the evolutionary ontology builder,
;; allowing concepts extracted from JSON/CSV/SQL/text sources to be queryable
;; through the same read model as domain-created concepts.

(defmethod concepts* :evolutionary/concepts-extracted
  [state event]
  ;; event has :concepts vector, each with :uri :label :definition :entity-type etc
  (let [ontology-id (:ontology-id event)]
    (reduce (fn [acc concept]
              (let [uri (:uri concept)]
                (assoc acc uri
                       {:uri uri
                        :id nil  ;; No concept-id from evolutionary path
                        :ontology-id ontology-id
                        :label (:label concept)
                        :description (or (:definition concept) "")
                        :scope (keyword (or (:entity-type concept) "entity"))
                        :broader #{}
                        :narrower #{}
                        :related #{}
                        :indicators []
                        :alt-labels (or (:alt-labels concept) [])
                        :confidence (:confidence concept 1.0)
                        :source-id (:source-id concept)
                        :created-at (:extracted-at event)})))
            state
            (:concepts event))))

(defmethod concepts* :evolutionary/relationships-extracted
  [state event]
  ;; event has :relationships vector, each with :subject :predicate :object
  (reduce (fn [acc {:keys [subject predicate object]}]
            (case predicate
              "skos:broader"
              (-> acc
                  (update-in [subject :broader] (fnil conj #{}) object)
                  (update-in [object :narrower] (fnil conj #{}) subject))

              "skos:narrower"
              (-> acc
                  (update-in [subject :narrower] (fnil conj #{}) object)
                  (update-in [object :broader] (fnil conj #{}) subject))

              "skos:related"
              (-> acc
                  (update-in [subject :related] (fnil conj #{}) object)
                  (update-in [object :related] (fnil conj #{}) subject))

              ;; Other predicates - store as related by default
              (update-in acc [subject :related] (fnil conj #{}) object)))
          state
          (:relationships event)))

(defmethod concepts* :default [state _] state)

(defn concepts
  "Build concepts graph from events."
  [initial-state events]
  (reduce concepts* initial-state events))

(defreadmodel :ontology concepts
  {:events concept-events, :version 2}  ;; v2: Added evolutionary event support
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
;; C-2 Living Descriptions Projection
;; =============================================================================
;; State shape:
;;   {<granularity> {<target-id> {:current <latest-body>
;;                                :history [<event-as-map>, ...chronological]}}}
;;
;; Each *-description-updated event REPLACES :current and APPENDS to :history.
;; This is the foundation for `get-description` (latest body) and
;; `get-description-history` (full audit trail) queries.

(defmulti descriptions*
  "Apply an event to the Living Description read-model state."
  (fn [_state event] (:event/type event)))

(defmethod descriptions* :default [state _] state)

(defn- apply-description-event
  "Generic projection: the event has :target-type (granularity), :target-id
   (granularity-specific key), :body (the description body), :recorded-at.
   Replaces :current with the new body; appends a versioned entry to :history."
  [state event]
  (let [granularity (:target-type event)
        target-id (:target-id event)
        body (:body event)
        recorded-at (:recorded-at event)
        history-entry {:body body
                       :recorded-at recorded-at
                       :event-id (:event/id event)}]
    (-> state
        (assoc-in [granularity target-id :current] body)
        (update-in [granularity target-id :history]
                   (fnil conj []) history-entry))))

(defmethod descriptions* :ontology/node-type-description-updated [state event]
  (apply-description-event state event))

(defmethod descriptions* :ontology/node-instance-description-updated [state event]
  (apply-description-event state event))

(defmethod descriptions* :ontology/tree-description-updated [state event]
  (apply-description-event state event))

(defn descriptions
  "Build the Living Description state from a seq of events."
  [initial-state events]
  (reduce descriptions* initial-state events))

(defreadmodel :ontology descriptions
  {:events description-events, :version 1}
  [state event] (descriptions* state event))

(defn get-description
  "Return the CURRENT description body for the (granularity, target-id)
   target, or nil if no description exists.

   Granularity is one of :node-type, :node-instance, :tree-fingerprint.
   The target-id is whatever shape the granularity uses (keyword for
   :node-type, [sheet-id node-id] tuple for :node-instance, string for
   :tree-fingerprint)."
  [ctx granularity target-id]
  (get-in (rmp/project ctx :ontology/descriptions)
          [granularity target-id :current]))

(defn get-description-history
  "Return the chronological vector of all description versions ever
   recorded for the (granularity, target-id) target. Empty vector if
   none recorded."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :history])
      []))

;; =============================================================================
;; C-2a-3a — Consolidation threshold config read-model
;; =============================================================================
;;
;; Event-sourced per-target-type threshold configuration. The
;; threshold-tracking processor reads this to decide when to emit
;; :ontology/consolidation-requested. Unset target-types use the default
;; threshold of 10.

(def ^:private default-consolidation-threshold
  "Default delta-since-last-consolidation count that triggers a
   consolidation request when no per-target-type override has been set."
  10)

(defmulti consolidation-thresholds*
  "Apply an event to the threshold-config state map.
   State: {target-type → int}."
  (fn [_state event] (:event/type event)))

(defmethod consolidation-thresholds* :default [state _] state)

(defmethod consolidation-thresholds* :ontology/consolidation-threshold-set
  [state event]
  (assoc state (:target-type event) (:threshold event)))

(defn consolidation-thresholds
  "Build the threshold-config state from a seq of events."
  [initial-state events]
  (reduce consolidation-thresholds* initial-state events))

(defreadmodel :ontology consolidation-thresholds
  {:events #{:ontology/consolidation-threshold-set} :version 1}
  [state event] (consolidation-thresholds* state event))

(defn get-consolidation-threshold
  "Return the configured threshold for a target-type, or the default 10."
  [ctx target-type]
  (or (get (rmp/project ctx :ontology/consolidation-thresholds) target-type)
      default-consolidation-threshold))

;; =============================================================================
;; Gap-1 — Living Description opt-in flag read-model
;; =============================================================================
;;
;; System-level boolean gating the WRITING side of the Living Description
;; loop. State is a map `{:enabled? boolean}` because read-models always
;; project to a map shape; the lone field is the flag itself.
;; Default false when no event has been emitted (consumer must opt in).

(defmulti living-description-enabled*
  (fn [_state event] (:event/type event)))

(defmethod living-description-enabled* :default [state _] state)

(defmethod living-description-enabled* :ontology/living-description-enabled-set
  [state event]
  (assoc state :enabled? (boolean (:enabled? event))))

(defn living-description-enabled
  "Build the opt-in flag state from a seq of events."
  [initial-state events]
  (reduce living-description-enabled* initial-state events))

(defreadmodel :ontology living-description-enabled
  {:events #{:ontology/living-description-enabled-set} :version 1}
  [state event] (living-description-enabled* state event))

(defn get-living-description-enabled?
  "Return the current Living Description opt-in flag (default false)."
  [ctx]
  (boolean (:enabled? (rmp/project ctx :ontology/living-description-enabled))))

;; =============================================================================
;; C-2a-3c — Consolidation budget config read-model
;; =============================================================================
;;
;; Hourly consolidation budget per target-type. The consolidator gate
;; reads this to decide whether to run the LLM reflection or skip the
;; consolidation due to budget exhaustion. Unset target-types use the
;; default budget of 100/hour.

(def ^:private default-consolidation-budget
  "Default consolidations-per-hour-per-target-type allowed when no
   per-target-type override has been set."
  100)

(defmulti consolidation-budgets*
  (fn [_state event] (:event/type event)))

(defmethod consolidation-budgets* :default [state _] state)

(defmethod consolidation-budgets* :ontology/consolidation-budget-set
  [state event]
  (assoc state (:target-type event) (:budget event)))

(defn consolidation-budgets
  "Build the budget-config state from a seq of events."
  [initial-state events]
  (reduce consolidation-budgets* initial-state events))

(defreadmodel :ontology consolidation-budgets
  {:events #{:ontology/consolidation-budget-set} :version 1}
  [state event] (consolidation-budgets* state event))

(defn get-consolidation-budget
  "Return the configured hourly budget for a target-type, or the default 100."
  [ctx target-type]
  (or (get (rmp/project ctx :ontology/consolidation-budgets) target-type)
      default-consolidation-budget))

;; =============================================================================
;; C-2b-1 — Re-index config read-model
;; =============================================================================
;;
;; Global re-index configuration (not per-target-type). Drives the
;; hybrid threshold-OR-timer trigger in the re-index processor.
;; Defaults: 10 events, 5 minutes.

(def ^:private default-reindex-config
  {:reindex-threshold-events 10
   :reindex-timer-minutes 5})

(defmulti reindex-config*
  (fn [_state event] (:event/type event)))

(defmethod reindex-config* :default [state _] state)

(defmethod reindex-config* :ontology/reindex-config-set
  [_state event]
  {:reindex-threshold-events (:reindex-threshold-events event)
   :reindex-timer-minutes (:reindex-timer-minutes event)})

(defn reindex-config
  "Build the re-index config state from a seq of events."
  [initial-state events]
  (reduce reindex-config* initial-state events))

(defreadmodel :ontology reindex-config
  {:events #{:ontology/reindex-config-set} :version 1}
  [state event] (reindex-config* state event))

(defn get-reindex-config
  "Return the current re-index config (merge of defaults + any
   :ontology/reindex-config-set event)."
  [ctx]
  (merge default-reindex-config
         (rmp/project ctx :ontology/reindex-config)))

;; =============================================================================
;; C-2b-1 — Re-index state read-model
;; =============================================================================
;;
;; Tracks per-rebuild-cycle state:
;;   :events-since-last-rebuild — incremented on each :ontology/*-description-updated
;;   :last-rebuild-timestamp — ISO string, set when :colbert/index-created lands
;;   :index-built? — false until first :colbert/index-created
;;
;; The re-index processor reads this to decide threshold-or-timer trigger
;; firing. The :colbert/index-created event resets the counter and updates
;; the timestamp.

(def ^:private initial-reindex-state
  {:events-since-last-rebuild 0
   :last-rebuild-timestamp nil
   :index-built? false})

(defmulti reindex-state*
  (fn [_state event] (:event/type event)))

(defmethod reindex-state* :default [state _] state)

(defmethod reindex-state* :ontology/node-type-description-updated [state _event]
  (update state :events-since-last-rebuild (fnil inc 0)))

(defmethod reindex-state* :ontology/node-instance-description-updated [state _event]
  (update state :events-since-last-rebuild (fnil inc 0)))

(defmethod reindex-state* :ontology/tree-description-updated [state _event]
  (update state :events-since-last-rebuild (fnil inc 0)))

(defmethod reindex-state* :colbert/index-created [state event]
  ;; Only rebuilds of the ontology-descriptions index reset our state.
  ;; Other indexes (tree-profiles, concepts) don't affect us.
  (if (= "ontology-descriptions" (:index-name event))
    (assoc state
           :events-since-last-rebuild 0
           :last-rebuild-timestamp (str (or (:event/timestamp event)
                                            (java.time.Instant/now)))
           :index-built? true)
    state))

(defn reindex-state
  "Build the re-index state from a seq of events."
  [initial-state events]
  (reduce reindex-state* (or initial-state initial-reindex-state) events))

(defreadmodel :ontology reindex-state
  {:events #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated
             :colbert/index-created}
   :version 1}
  [state event] (reindex-state* state event))

(defn get-reindex-state
  "Return the current re-index state — {:events-since-last-rebuild N
   :last-rebuild-timestamp ISO-string :index-built? bool}."
  [ctx]
  (merge initial-reindex-state
         (rmp/project ctx :ontology/reindex-state)))

;; =============================================================================
;; C-2a-3c — Recent consolidations counter read-model
;; =============================================================================
;;
;; Tracks the timestamps of recent :*-description-updated events per
;; target-type. The budget gate counts entries within the last hour
;; window. Per-target-type granularity (not per-target-id) so a single
;; runaway target can't exhaust the budget for unrelated targets within
;; the same target-type — though in practice a single runaway target
;; WOULD trigger budget cap and stop until the hour rolls.

(defmulti recent-consolidations*
  (fn [_state event] (:event/type event)))

(defmethod recent-consolidations* :default [state _] state)

(defn- record-consolidation-timestamp [state event]
  (let [target-type (:target-type event)
        ts (or (some-> event :event/timestamp str) (str (java.time.Instant/now)))]
    (update state target-type (fnil conj []) ts)))

(defmethod recent-consolidations* :ontology/node-type-description-updated [state event]
  (record-consolidation-timestamp state event))
(defmethod recent-consolidations* :ontology/node-instance-description-updated [state event]
  (record-consolidation-timestamp state event))
(defmethod recent-consolidations* :ontology/tree-description-updated [state event]
  (record-consolidation-timestamp state event))

(defn recent-consolidations
  "Build the recent-consolidations state from a seq of events."
  [initial-state events]
  (reduce recent-consolidations* initial-state events))

(defreadmodel :ontology recent-consolidations
  {:events #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated}
   :version 1}
  [state event] (recent-consolidations* state event))

(defn- ts->instant [^String s]
  (try (java.time.Instant/parse s)
       (catch Exception _
         (try (.toInstant (java.time.OffsetDateTime/parse s))
              (catch Exception _ nil)))))

(defn get-recent-consolidation-count
  "Return how many :*-description-updated events have fired for the
   given target-type in the rolling last-hour window. Used by the
   consolidator's budget gate."
  [ctx target-type]
  (let [now (java.time.Instant/now)
        cutoff (.minusSeconds now 3600)
        all (get (rmp/project ctx :ontology/recent-consolidations) target-type [])]
    (->> all
         (keep ts->instant)
         (filter #(.isAfter ^java.time.Instant % cutoff))
         count)))

;; =============================================================================
;; C-2a-3a — Consolidation delta-counter read-model
;; =============================================================================
;;
;; Tracks per-(target-type, target-id):
;;   :delta — events since last :ontology/consolidation-requested (drives
;;            the threshold-tracking processor's fire decision)
;;   :total — lifetime count of source events for this target (used to
;;            derive the deterministic "crossing-number" that CAS-guards
;;            the consolidation-requested append for exactly-once
;;            semantics across concurrent processor handlers)
;;
;; Four event types project:
;;   :sheet/node-execution-completed     → increments :delta + :total for
;;                                          [:node-type kw] AND
;;                                          [:node-instance [sheet node]]
;;   :sheet/rlm-tree-execution-completed → increments :delta + :total for
;;                                          [:tree-fingerprint fp]
;;   :ontology/task-classified           → increments :delta + :total for
;;                                          [:tree-class assigned-tree-id]
;;                                          (C-Loop-1: drives the Living
;;                                          Description loop at the
;;                                          classifier's substrate)
;;   :ontology/consolidation-requested   → resets :delta to 0 (:total
;;                                          continues climbing)

(defn- bump-counter
  "Increment both :delta and :total at the given target path."
  [state path]
  (-> state
      (update-in (conj path :delta) (fnil inc 0))
      (update-in (conj path :total) (fnil inc 0))))

(defmulti consolidation-delta-counters*
  (fn [_state event] (:event/type event)))

(defmethod consolidation-delta-counters* :default [state _] state)

(defmethod consolidation-delta-counters* :sheet/node-execution-completed
  [state event]
  (let [node-type (:node-type event)
        sheet-id  (:sheet-id event)
        node-id   (:node-id event)]
    (cond-> state
      (some? node-type)
      (bump-counter [:node-type node-type])

      (and (some? sheet-id) (some? node-id))
      (bump-counter [:node-instance [sheet-id node-id]]))))

(defmethod consolidation-delta-counters* :sheet/rlm-tree-execution-completed
  [state event]
  (if-let [fp (:tree-fingerprint event)]
    (bump-counter state [:tree-fingerprint fp])
    state))

(defmethod consolidation-delta-counters* :ontology/task-classified
  [state event]
  (if-let [tree-class-id (:assigned-tree-id event)]
    (bump-counter state [:tree-class tree-class-id])
    state))

(defmethod consolidation-delta-counters* :ontology/consolidation-requested
  [state event]
  (let [target-type (:target-type event)
        target-id   (:target-id event)]
    (assoc-in state [target-type target-id :delta] 0)))

(defn consolidation-delta-counters
  "Build the delta-counter state from a seq of events."
  [initial-state events]
  (reduce consolidation-delta-counters* initial-state events))

(defreadmodel :ontology consolidation-delta-counters
  {:events #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed
             :ontology/task-classified
             :ontology/consolidation-requested}
   :version 2}
  [state event] (consolidation-delta-counters* state event))

(defn get-consolidation-delta
  "Return the current delta-counter (events-since-last-consolidation)
   for the (target-type, target-id) target. Returns 0 when no events
   have ticked the counter."
  [ctx target-type target-id]
  (or (get-in (rmp/project ctx :ontology/consolidation-delta-counters)
              [target-type target-id :delta])
      0))

(defn get-consolidation-total
  "Return the lifetime total count of source events for the
   (target-type, target-id) target. Used by the CAS guard on
   consolidation-requested emissions to derive the crossing-number
   that enforces exactly-once-per-threshold-crossing across
   concurrent processor handlers."
  [ctx target-type target-id]
  (or (get-in (rmp/project ctx :ontology/consolidation-delta-counters)
              [target-type target-id :total])
      0))

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
  "Get all concepts, optionally filtered by scope and/or ontology-id.

   Options:
     :scope       - Filter by concept scope
     :broader-uri - Filter by concepts with this URI in their broader set
     :ontology-id - Filter by single ontology-id
     :ontology-ids - Filter by multiple ontology-ids (returns union)"
  [ctx & [{:keys [scope broader-uri ontology-id ontology-ids]}]]
  (let [all-concepts (vals (rmp/project ctx :ontology/concepts))
        ont-id-set (cond
                     ontology-ids (set ontology-ids)
                     ontology-id #{ontology-id}
                     :else nil)]
    (cond->> all-concepts
      scope (filter #(= scope (:scope %)))
      broader-uri (filter #(contains? (:broader %) broader-uri))
      ont-id-set (filter #(contains? ont-id-set (:ontology-id %))))))

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
  "Get all concept embeddings, optionally filtered by scope and/or ontology-id.

   Options:
     :scope       - Filter by concept scope
     :ontology-id - Filter by single ontology-id
     :ontology-ids - Filter by multiple ontology-ids (returns union)"
  [ctx & [{:keys [scope ontology-id ontology-ids]}]]
  (let [all-embeddings (if scope
                         (rmp/project ctx :ontology/concept-embeddings {:tags #{[:scope scope]}})
                         (rmp/project ctx :ontology/concept-embeddings))
        ont-id-set (cond
                     ontology-ids (set ontology-ids)
                     ontology-id #{ontology-id}
                     :else nil)]
    (if ont-id-set
      (into {} (filter #(contains? ont-id-set (:ontology-id (val %))) all-embeddings))
      all-embeddings)))

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
   Tracks which ColBERT index is associated with each ontology.

   NB: events arrive with :event/type and their body flattened to the top level
   (v3 event store), exactly like every other read model here — NOT as a nested
   {:type :body}. Reading :type/:body meant this projection never fired."
  [state event]
  (case (:event/type event)
    :evolutionary/colbert-indexed
    (assoc state (:ontology-id event)
           {:colbert-index-id (:index-id event)
            :index-name (:index-name event)
            :colbert-fields (vec (:colbert-fields event))
            :document-count (:document-count event)
            :indexed-at (:indexed-at event)})

    :evolutionary/colbert-index-updated
    (update state (:ontology-id event) merge
            {:colbert-index-id (:index-id event)
             :updated-at (:updated-at event)})

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
;; Site Registry Projection (Generic Site Pattern Learning)
;; =============================================================================
;; Builds site registry with trust scores and learned patterns

(def site-registry-events
  "Events that affect site registry read model"
  #{:site/registered
    :site/trust-updated
    :site/pattern-learned})

(defmulti site-registry*
  "Apply event to site registry read model.
   State: {:by-domain {domain -> site}
           :by-trust [domains sorted by trust]
           :patterns {domain -> [patterns]}}"
  (fn [_state event] (:event/type event)))

(defmethod site-registry* :site/registered
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

(defmethod site-registry* :site/trust-updated
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

(defmethod site-registry* :site/pattern-learned
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

(defreadmodel :site registry
  {:events site-registry-events, :version 1}
  [state event] (site-registry* state event))

;; =============================================================================
;; Site Registry Query Helpers
;; =============================================================================

(defn get-site-by-domain
  "Get a site by its domain."
  [ctx domain]
  (get-in (rmp/project ctx :site/registry) [:by-domain domain]))

(defn get-all-sites
  "Get all registered sites."
  [ctx]
  (vals (get (rmp/project ctx :site/registry) :by-domain {})))

(defn get-trusted-sites
  "Get sites with trust score above threshold, sorted by trust.

   Args:
   - min-trust: Minimum trust score (default 0.5)
   - limit: Maximum sites to return"
  [ctx & [{:keys [min-trust limit] :or {min-trust 0.5}}]]
  (let [state (rmp/project ctx :site/registry)
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
  (let [patterns (get-in (rmp/project ctx :site/registry) [:patterns domain] [])]
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
  (let [state (rmp/project ctx :site/registry)
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
