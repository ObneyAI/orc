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
         (cond-> {:uri (:uri event)
                  :id (:concept-id event)
                  :ontology-id (:ontology-id event)
                  :label (:label event)
                  :description (:description event)
                  :scope (:scope event)
                  :broader (set (or (:broader event) []))
                  :narrower #{}
                  :related #{}
                  :indicators (or (:indicators event) [])
                  :created-at (str (:created-at event))}
           ;; S04 — representation bundle. Only attach when present so
           ;; legacy concepts have no phantom nil entries (the test
           ;; suite explicitly checks `(nil? (:model-guidance c))` etc.
           ;; on legacy fixtures).
           (seq (:labels event))      (assoc :labels (vec (:labels event)))
           (seq (:comments event))    (assoc :comments (vec (:comments event)))
           (:comment event)           (assoc :comment (:comment event))
           (seq (:see-also event))    (assoc :see-also (vec (:see-also event)))
           (:is-defined-by event)     (assoc :is-defined-by (:is-defined-by event))
           (:model-guidance event)    (assoc :model-guidance (:model-guidance event))
           (seq (:attributes event))  (assoc :attributes (:attributes event)))))

(defmethod concepts* :ontology/concept-updated
  [state event]
  (if-let [concept (get state (some-> event :concept-id str))]
    (update state (:uri concept) merge (:changes event))
    state))

(defn- edge-metadata
  "S06 — Extract the metadata sub-map from a relationship-created event.
   Returns nil when no metadata fields are present so callers can skip
   the typed-edges-meta annotation entirely for bare edges."
  [event]
  (let [m (cond-> {}
            (:confidence-class event) (assoc :confidence-class (:confidence-class event))
            (seq (:evidence event))   (assoc :evidence (:evidence event))
            (:valid-from event)       (assoc :valid-from (:valid-from event))
            (:valid-to event)         (assoc :valid-to (:valid-to event))
            (:superseded-by event)    (assoc :superseded-by (:superseded-by event))
            (seq (:properties event)) (assoc :properties (:properties event)))]
    (when (seq m)
      (assoc m :relationship-id (:relationship-id event)))))

(defn- with-edge-meta
  "S06 — Attach edge metadata (if any) onto the source concept's
   :typed-edges-meta map. Shape:
   {:typed-edges-meta {<predicate> {<target-uri> #{<meta-map> ...}}}}
   Multiple metadata records can land on the same (predicate, target) pair
   (multi-write edges, supersession chains). Returns state unchanged when
   the event has no metadata to attach."
  [state source-uri predicate target-uri event]
  (if-let [m (edge-metadata event)]
    (update-in state [source-uri :typed-edges-meta predicate target-uri]
               (fnil conj #{}) m)
    state))

(defmethod concepts* :ontology/relationship-created
  [state event]
  (let [{:keys [source-uri target-uri predicate]} event
        ;; S06 — attach edge metadata regardless of predicate type. The
        ;; metadata is queryable in the same shape across all predicates,
        ;; so downstream consumers (review queue, TTL exporter) consult
        ;; a single :typed-edges-meta map rather than branching on which
        ;; bucket the edge landed in.
        state' (with-edge-meta state source-uri predicate target-uri event)]
    (case predicate
      "skos:broader"
      (-> state'
          (update-in [source-uri :broader] (fnil conj #{}) target-uri)
          (update-in [target-uri :narrower] (fnil conj #{}) source-uri))

      "skos:narrower"
      (-> state'
          (update-in [source-uri :narrower] (fnil conj #{}) target-uri)
          (update-in [target-uri :broader] (fnil conj #{}) source-uri))

      "skos:related"
      (-> state'
          (update-in [source-uri :related] (fnil conj #{}) target-uri)
          (update-in [target-uri :related] (fnil conj #{}) source-uri))

      ;; R05a — behavior:composes-into is the bridge from behavioral
      ;; subtrees (Layer 2) to structural shells (Layer 1). The behavior
      ;; carries an outgoing :composes-into set of shell URIs; the shell
      ;; carries an incoming :composed-by set of behavior URIs. R05b's
      ;; classify-behaviors traverses these edges when narrowing
      ;; candidates by structural-context.
      "behavior:composes-into"
      (-> state'
          (update-in [source-uri :composes-into] (fnil conj #{}) target-uri)
          (update-in [target-uri :composed-by] (fnil conj #{}) source-uri))

      ;; Other predicates (owl:causes, follows, immediately-follows,
      ;; etc.) — preserve TWO views:
      ;; 1. ALSO landed in :related on the source so the default BFS
      ;;    closure (which iterates :broader/:narrower/:related buckets)
      ;;    continues to traverse them — preserves S05's
      ;;    immediately-follows reach.
      ;; 2. ALSO landed in :typed-edges keyed by the original predicate
      ;;    string so S07's axiom-aware BFS — which filters by predicate
      ;;    name — can identify transitive-marked predicates exactly.
      ;;    Without this typed view, the predicate name is erased into
      ;;    the :related bucket and the axiom filter has no key to match.
      (-> state'
          (update-in [source-uri :related] (fnil conj #{}) target-uri)
          (update-in [source-uri :typed-edges predicate]
                     (fnil conj #{}) target-uri)))))

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
;; S02 — Section-keyed parallel concepts projection
;; =============================================================================
;;
;; The URI-keyed :ontology/concepts projection above silently overwrites
;; when two sections mint the same URI. That collapse makes per-section
;; lookup of a colliding URI impossible after-the-fact (no amount of
;; downstream filtering can recover the OTHER section's concept once
;; the URI key has been overwritten).
;;
;; This parallel projection is keyed by [ontology-id uri] — same events,
;; different key shape. Scoped accessors consult it for collision-correct
;; lookups; the URI-keyed projection above is preserved for back-compat
;; with single-tenant callers and as the canonical "URI-keyed view" the
;; merge-projection callers (graph builder, etc.) consume.
;;
;; No new event types are introduced; this is purely a read-side
;; re-projection of the same events.

(defn- assoc-by-section
  "Add a concept under its [ontology-id uri] key in the section-keyed
   state map. The state shape is {ontology-id {uri concept-map}}."
  [state ontology-id uri concept]
  (assoc-in state [ontology-id uri] concept))

(defn- update-by-section
  "Apply f to the concept at [ontology-id uri] in the section-keyed
   state map (no-op when no such concept yet exists)."
  [state ontology-id uri f]
  (if (get-in state [ontology-id uri])
    (update-in state [ontology-id uri] f)
    state))

(defmulti concepts-by-section*
  "Apply event to the section-keyed concepts read model.
   State: {ontology-id {uri -> concept-map}}.

   For per-section concept lookup that survives URI collisions across
   sections. Reduces over the same events as :ontology/concepts."
  (fn [_state event] (:event/type event)))

(defmethod concepts-by-section* :ontology/concept-created
  [state event]
  (assoc-by-section state (:ontology-id event) (:uri event)
                    (cond-> {:uri (:uri event)
                             :id (:concept-id event)
                             :ontology-id (:ontology-id event)
                             :label (:label event)
                             :description (:description event)
                             :scope (:scope event)
                             :broader (set (or (:broader event) []))
                             :narrower #{}
                             :related #{}
                             :indicators (or (:indicators event) [])
                             :created-at (str (:created-at event))}
                      ;; S04 — ADDITIVE to S02's section-keyed projection.
                      ;; Same shape as the URI-keyed projection above so
                      ;; downstream consumers see the same enriched fields
                      ;; regardless of which projection they consulted.
                      (seq (:labels event))      (assoc :labels (vec (:labels event)))
                      (seq (:comments event))    (assoc :comments (vec (:comments event)))
                      (:comment event)           (assoc :comment (:comment event))
                      (seq (:see-also event))    (assoc :see-also (vec (:see-also event)))
                      (:is-defined-by event)     (assoc :is-defined-by (:is-defined-by event))
                      (:model-guidance event)    (assoc :model-guidance (:model-guidance event))
                      (seq (:attributes event))  (assoc :attributes (:attributes event)))))

(defmethod concepts-by-section* :ontology/concept-updated
  [state event]
  ;; concept-updated doesn't carry ontology-id directly; find by concept-id
  ;; via a scan. Scanning is fine here — this projection is only consulted
  ;; for scoped accessors, not in hot retrieval paths.
  (let [cid (some-> event :concept-id str)]
    (if cid
      (reduce-kv (fn [acc ont-id by-uri]
                   (reduce-kv (fn [a uri concept]
                                (if (= cid (some-> concept :id str))
                                  (assoc-in a [ont-id uri] (merge concept (:changes event)))
                                  a))
                              acc by-uri))
                 state state)
      state)))

(defn- update-by-section-with-edge-meta
  "S06 — Attach edge metadata onto the section-keyed concept's
   :typed-edges-meta map. Same shape as the URI-keyed projection's
   helper; only the storage layout differs (state is keyed by
   [ontology-id uri] here)."
  [state ontology-id source-uri predicate target-uri event]
  (if-let [m (edge-metadata event)]
    (update-by-section state ontology-id source-uri
                       (fn [c]
                         (update-in c [:typed-edges-meta predicate target-uri]
                                    (fnil conj #{}) m)))
    state))

(defn- apply-section-edge
  "S06 — Helper that applies a single relationship-created event to ONE
   specific section. Extracted out of the legacy multi-section reduce so
   the direct (ontology-id present) path and the legacy
   (find-endpoints-fallback) path can both call it without code
   duplication."
  [state ont-id event]
  (let [{:keys [source-uri target-uri predicate]} event
        state' (update-by-section-with-edge-meta state ont-id source-uri
                                                 predicate target-uri event)]
    (case predicate
      "skos:broader"
      (-> state'
          (update-by-section ont-id source-uri
                             (fn [c] (update c :broader (fnil conj #{}) target-uri)))
          (update-by-section ont-id target-uri
                             (fn [c] (update c :narrower (fnil conj #{}) source-uri))))

      "skos:narrower"
      (-> state'
          (update-by-section ont-id source-uri
                             (fn [c] (update c :narrower (fnil conj #{}) target-uri)))
          (update-by-section ont-id target-uri
                             (fn [c] (update c :broader (fnil conj #{}) source-uri))))

      "skos:related"
      (-> state'
          (update-by-section ont-id source-uri
                             (fn [c] (update c :related (fnil conj #{}) target-uri)))
          (update-by-section ont-id target-uri
                             (fn [c] (update c :related (fnil conj #{}) source-uri))))

      "behavior:composes-into"
      (-> state'
          (update-by-section ont-id source-uri
                             (fn [c] (update c :composes-into (fnil conj #{}) target-uri)))
          (update-by-section ont-id target-uri
                             (fn [c] (update c :composed-by (fnil conj #{}) source-uri))))

      ;; Other predicates (incl. S05 sequence-convention predicates
      ;; "immediately-follows" / "follows", plus any custom predicate,
      ;; including S07 axiom-driven predicates) → land in BOTH :related
      ;; AND :typed-edges. Symmetric with the URI-keyed projection's
      ;; default branch.
      (-> state'
          (update-by-section ont-id source-uri
                             (fn [c] (update c :related (fnil conj #{}) target-uri)))
          (update-by-section ont-id source-uri
                             (fn [c] (update-in c [:typed-edges predicate]
                                                (fnil conj #{}) target-uri)))))))

(defmethod concepts-by-section* :ontology/relationship-created
  [state event]
  ;; S06 — when the event carries `:ontology-id` (the recommended new
  ;; write shape), route the edge to THAT section in O(1) — no scan.
  ;; Legacy events written before S06 (no `:ontology-id`) take the
  ;; fallback path: scan to find sections containing both endpoints and
  ;; apply the edge to each. The fallback preserves back-compat for
  ;; replays of older event streams.
  (if-let [ont-id (:ontology-id event)]
    ;; Direct path — O(1) section routing.
    (apply-section-edge state ont-id event)
    ;; Legacy fallback — find sections that contain both endpoints.
    (let [{:keys [source-uri target-uri]} event
          endpoint-sections (fn [uri]
                              (->> state
                                   (filter (fn [[_ by-uri]] (contains? by-uri uri)))
                                   (map first)
                                   set))
          src-sections (endpoint-sections source-uri)
          tgt-sections (endpoint-sections target-uri)
          shared (set/intersection src-sections tgt-sections)]
      (reduce (fn [acc ont-id] (apply-section-edge acc ont-id event))
              state shared))))

(defmethod concepts-by-section* :evolutionary/concepts-extracted
  [state event]
  (let [ontology-id (:ontology-id event)]
    (reduce (fn [acc concept]
              (let [uri (:uri concept)]
                (assoc-by-section acc ontology-id uri
                                  {:uri uri
                                   :id nil
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

(defmethod concepts-by-section* :evolutionary/relationships-extracted
  [state event]
  ;; Evolutionary relationship events ARE ontology-id-tagged at the event
  ;; level — apply edges only within that section.
  (let [ontology-id (:ontology-id event)]
    (reduce (fn [acc {:keys [subject predicate object]}]
              (case predicate
                "skos:broader"
                (-> acc
                    (update-by-section ontology-id subject
                                       (fn [c] (update c :broader (fnil conj #{}) object)))
                    (update-by-section ontology-id object
                                       (fn [c] (update c :narrower (fnil conj #{}) subject))))

                "skos:narrower"
                (-> acc
                    (update-by-section ontology-id subject
                                       (fn [c] (update c :narrower (fnil conj #{}) object)))
                    (update-by-section ontology-id object
                                       (fn [c] (update c :broader (fnil conj #{}) subject))))

                "skos:related"
                (-> acc
                    (update-by-section ontology-id subject
                                       (fn [c] (update c :related (fnil conj #{}) object)))
                    (update-by-section ontology-id object
                                       (fn [c] (update c :related (fnil conj #{}) subject))))

                (update-by-section ontology-id subject
                                   (fn [c] (update c :related (fnil conj #{}) object)))))
            state
            (:relationships event))))

(defmethod concepts-by-section* :default [state _] state)

(defn concepts-by-section
  "Build the section-keyed concepts state from events."
  [initial-state events]
  (reduce concepts-by-section* initial-state events))

(defreadmodel :ontology concepts-by-section
  {:events concept-events, :version 1}
  [state event] (concepts-by-section* state event))

;; =============================================================================
;; S06 — Relationships projection (per-edge metadata accessor)
;; =============================================================================
;;
;; Keyed by relationship-id. Each entry carries the full edge record —
;; source-uri, target-uri, predicate, plus the named metadata fields and
;; the `:properties` open bag. Distinct from the URI-keyed
;; :ontology/concepts projection (which is concept-centric) so that
;; downstream consumers can iterate edges as first-class — the HITL
;; ambiguous-edge review queue, the TTL exporter (which needs to walk
;; relationships not concepts to know which to reify), the C5 lint
;; surfaces that operate on edge-level invariants.
;;
;; Append-only — back-compat with legacy events that lack the named
;; metadata is preserved (those fields are simply omitted from the
;; projected record).

(def relationship-events
  "Events that affect the per-edge relationships read model."
  #{:ontology/relationship-created})

(defmulti relationships*
  "Apply event to relationships read model.
   State: {relationship-id -> relationship-map}"
  (fn [_state event] (:event/type event)))

(defmethod relationships* :ontology/relationship-created
  [state event]
  (let [{:keys [relationship-id source-uri target-uri predicate ontology-id
                confidence-class evidence valid-from valid-to superseded-by
                properties created-at]} event]
    (assoc state relationship-id
           (cond-> {:relationship-id relationship-id
                    :source-uri source-uri
                    :target-uri target-uri
                    :predicate predicate
                    :created-at created-at}
             ontology-id      (assoc :ontology-id ontology-id)
             confidence-class (assoc :confidence-class confidence-class)
             (seq evidence)   (assoc :evidence (vec evidence))
             valid-from       (assoc :valid-from valid-from)
             valid-to         (assoc :valid-to valid-to)
             superseded-by    (assoc :superseded-by superseded-by)
             (seq properties) (assoc :properties properties)))))

(defmethod relationships* :default [state _] state)

(defn relationships
  "Build the relationships state from events."
  [initial-state events]
  (reduce relationships* initial-state events))

(defreadmodel :ontology relationships
  {:events relationship-events, :version 1}
  [state event] (relationships* state event))

(defn get-relationship
  "Return the projected relationship-record for a given relationship-id,
   or nil when no such edge has been created. S06."
  [ctx relationship-id]
  (get (rmp/project ctx :ontology/relationships) relationship-id))

(defn get-relationships
  "Return all projected relationship records as a seq. Use to iterate the
   edge corpus (TTL export, HITL review queues, lint surfaces)."
  [ctx]
  (vals (rmp/project ctx :ontology/relationships)))

(defn get-edges-by-confidence-class
  "S06 — Return all edges with the given confidence-class
   (:extracted / :inferred / :ambiguous). C5's HITL review queue read
   path. Linear scan over the relationships projection — fine for the
   review-queue workload (small N, batched UI).

   Edges that have NO `:confidence-class` field set are NEVER returned
   (i.e. legacy edges and bare-extracted edges with the field omitted are
   excluded). Callers wanting 'all edges' should use `get-relationships`."
  [ctx confidence-class]
  (filterv (fn [rel] (= confidence-class (:confidence-class rel)))
           (vals (rmp/project ctx :ontology/relationships))))

;; =============================================================================
;; S04 — Ontology-level metadata projection
;; =============================================================================
;;
;; Per-ontology-id metadata header (title / version / license / creator)
;; that the TTL exporter emits on the `owl:Ontology` block. Each
;; :ontology/ontology-metadata-recorded event REPLACES the prior state
;; for that ontology-id (latest-wins). Only the fields the command
;; supplied land here — the projection NEVER substitutes empty defaults
;; for absent fields, so the serializer can never accidentally emit
;; `dcterms:creator ""` from a partial command.

(def ontology-metadata-events
  "Events that affect the ontology-level metadata read model."
  #{:ontology/ontology-metadata-recorded})

(defmulti ontology-metadata*
  "Apply event to ontology-metadata read model.
   State: {ontology-id -> {:title ... :version ... :license ... :creator ...}}
   Only the keys present in the event land in the projected map."
  (fn [_state event] (:event/type event)))

(defmethod ontology-metadata* :default [state _] state)

(defmethod ontology-metadata* :ontology/ontology-metadata-recorded
  [state event]
  (assoc state (:ontology-id event)
         (cond-> {:ontology-id (:ontology-id event)
                  :recorded-at (str (:recorded-at event))}
           (:title event)   (assoc :title (:title event))
           (:version event) (assoc :version (:version event))
           (:license event) (assoc :license (:license event))
           (:creator event) (assoc :creator (:creator event)))))

(defn ontology-metadata
  "Build the ontology-metadata state from a seq of events."
  [initial-state events]
  (reduce ontology-metadata* initial-state events))

(defreadmodel :ontology ontology-metadata
  {:events ontology-metadata-events, :version 1}
  [state event] (ontology-metadata* state event))

(defn get-ontology-metadata
  "Return the metadata record for a given ontology-id, or nil if no
   :ontology/ontology-metadata-recorded event has been emitted for it."
  [ctx ontology-id]
  (get (rmp/project ctx :ontology/ontology-metadata) ontology-id))

(defn get-all-ontology-metadata
  "Return the full {ontology-id -> metadata} map across all ontologies."
  [ctx]
  (rmp/project ctx :ontology/ontology-metadata))

;; =============================================================================
;; S07 — Axioms-as-data projection (per-ontology)
;; =============================================================================
;;
;; Four axiom families collapsed into ONE per-ontology projection map so
;; downstream consumers (lints, axiom-aware BFS, OWL serializer) need
;; exactly one lookup per ontology-id. Shape:
;;
;;   {<ontology-id>
;;    {:disjointness     {<class-uri> #{<disjoint-sibling-uri> ...}}    ; symmetric
;;     :characteristics  {<predicate>  #{:functional :transitive :symmetric}}
;;     :inverse-of       {<predicate>  <inverse-predicate>}              ; bidirectional
;;     :sub-property-of  {<sub-pred>   <super-pred>}
;;     :chains           {<derived-pred> [<chain-pred-1> <chain-pred-2> ...]}}}
;;
;; CRITICAL non-goal: this projection does NOT inspect concept state and
;; does NOT mutate the :ontology/concepts projection. A concept whose
;; class membership would be inconsistent under OWL DL given a recorded
;; disjointness axiom REMAINS unchanged in the concept projection. Lints
;; (S11) catch the inconsistency AT VALIDATION TIME by JOINING the two
;; projections — this slice records the DATA only.

(def axiom-events
  "Events that affect the per-ontology axiom projection. Four event
   types, all additive — appending an axiom event NEVER mutates concept
   or relationship state (no inference). Disjointness uses set semantics
   so re-assertion is idempotent; the other three are last-write-wins
   on their key shapes."
  #{:ontology/disjointness-asserted
    :ontology/property-characteristic-asserted
    :ontology/sub-property-asserted
    :ontology/chain-axiom-asserted})

(defmulti axioms*
  "Apply event to per-ontology axiom projection.
   State: {ontology-id -> {:disjointness ... :characteristics ...
                            :inverse-of ... :sub-property-of ...
                            :chains ...}}.
   Each event updates the matching submap under its ontology-id."
  (fn [_state event] (:event/type event)))

(defmethod axioms* :default [state _] state)

(defmethod axioms* :ontology/disjointness-asserted
  [state event]
  ;; Symmetric projection: every URI in the set carries the OTHERS.
  ;; Set semantics make re-assertion of the same set a no-op.
  (let [ontology-id (:ontology-id event)
        uris (:class-uris event)
        pairs (for [a uris, b uris :when (not= a b)] [a b])]
    (reduce (fn [acc [a b]]
              (update-in acc [ontology-id :disjointness a]
                         (fnil conj #{}) b))
            state pairs)))

(defmethod axioms* :ontology/property-characteristic-asserted
  [state event]
  ;; Characteristics accumulate into a per-predicate set; inverse-of is
  ;; recorded bidirectionally so consumers reading either side find the
  ;; pairing. No defensive merge — re-assertion of the same flag is a
  ;; set no-op.
  (let [ontology-id (:ontology-id event)
        predicate (:predicate event)
        flags (set (:characteristic event))
        inverse-of (:inverse-of event)]
    (cond-> state
      (seq flags)
      (update-in [ontology-id :characteristics predicate]
                 (fnil into #{}) flags)
      inverse-of
      (-> (assoc-in [ontology-id :inverse-of predicate] inverse-of)
          (assoc-in [ontology-id :inverse-of inverse-of] predicate)))))

(defmethod axioms* :ontology/sub-property-asserted
  [state event]
  ;; Each sub maps to its super (last-write-wins on conflict — multiple
  ;; supers are NOT supported in this slice's shape; the consumer can
  ;; widen if needed).
  (let [ontology-id (:ontology-id event)
        sub (:sub-predicate event)
        super (:super-predicate event)]
    (assoc-in state [ontology-id :sub-property-of sub] super)))

(defmethod axioms* :ontology/chain-axiom-asserted
  [state event]
  ;; Derived predicate keys the entry; the chain value is the ordered
  ;; vector. Last-write-wins on conflict (a derived predicate has ONE
  ;; chain definition).
  (let [ontology-id (:ontology-id event)
        chain (:chain event)
        derived (:derived-predicate event)]
    (assoc-in state [ontology-id :chains derived] (vec chain))))

(defn axioms
  "Build the per-ontology axiom projection state from a seq of events."
  [initial-state events]
  (reduce axioms* initial-state events))

(defreadmodel :ontology axioms
  {:events axiom-events, :version 1}
  [state event] (axioms* state event))

(defn get-axioms
  "Return the full axiom map (:disjointness / :characteristics /
   :inverse-of / :sub-property-of / :chains) for an ontology-id, or
   nil if no axioms have been asserted for it."
  [ctx ontology-id]
  (get (rmp/project ctx :ontology/axioms) ontology-id))

(defn transitive-predicates
  "Return the SET of predicates marked transitive for the given
   ontology-id. Used by axiom-aware BFS to restrict closure to
   transitively-marked edges. Empty set when no characteristics
   have been recorded."
  [ctx ontology-id]
  (let [chars (get-in (rmp/project ctx :ontology/axioms)
                      [ontology-id :characteristics])]
    (set (keep (fn [[pred flags]]
                 (when (contains? flags :transitive) pred))
               chars))))

;; =============================================================================
;; S14 — Ontology Specs (ORSD) Projection
;; =============================================================================
;;
;; Per-ontology-id event-sourced contract — purpose, scope, intended-uses,
;; competency-questions, natural-language-statements, non-functional. The
;; projection mirrors the Living Descriptions shape exactly: each
;; :ontology/ontology-spec-recorded event REPLACES :current with the new
;; body AND APPENDS a versioned history entry. The full revision history
;; is queryable; revisions never destroy history.
;;
;; State shape:
;;   {<ontology-id> {:current <latest-body>
;;                   :history [{:body <body> :recorded-at <ts>
;;                              :event-id <eid>} ...chronological]}}

(def ontology-spec-events
  "Events that affect the ontology-specs read model. Single event type,
   append-only — mirrors the descriptions-events idiom."
  #{:ontology/ontology-spec-recorded})

(defmulti ontology-specs*
  "Apply an event to the ORSD spec read-model state."
  (fn [_state event] (:event/type event)))

(defmethod ontology-specs* :default [state _] state)

(defmethod ontology-specs* :ontology/ontology-spec-recorded
  [state event]
  (let [ontology-id (:ontology-id event)
        body (:body event)
        recorded-at (:recorded-at event)
        history-entry {:body body
                       :recorded-at recorded-at
                       :event-id (:event/id event)}]
    (-> state
        (assoc-in [ontology-id :current] body)
        (update-in [ontology-id :history] (fnil conj []) history-entry))))

(defn ontology-specs
  "Build the ORSD spec state from a seq of events."
  [initial-state events]
  (reduce ontology-specs* initial-state events))

(defreadmodel :ontology ontology-specs
  {:events ontology-spec-events, :version 1}
  [state event] (ontology-specs* state event))

(defn get-ontology-spec
  "Return the CURRENT ORSD spec body for the given ontology-id, or
   nil if no :ontology/ontology-spec-recorded event has been emitted
   for it. The body shape is `ontology-spec-body` — every field is
   optional, so the returned map is whatever the recording caller
   provided (no defaulted fields)."
  [ctx ontology-id]
  (get-in (rmp/project ctx :ontology/ontology-specs)
          [ontology-id :current]))

(defn get-ontology-spec-history
  "Return the chronological vector of all ORSD spec revisions ever
   recorded for the given ontology-id. Each entry carries
   `{:body :recorded-at :event-id}`. Empty vector when no revisions
   recorded — never nil, so callers can safely iterate."
  [ctx ontology-id]
  (or (get-in (rmp/project ctx :ontology/ontology-specs)
              [ontology-id :history])
      []))

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
     :scope        - Filter by concept scope
     :broader-uri  - Filter by concepts with this URI in their broader set
     :ontology-id  - Filter by single ontology-id (S02 scoping)
     :ontology-ids - Filter by multiple ontology-ids (returns union, S02 scoping)

   S02: when an :ontology-id or :ontology-ids is passed, the section-keyed
   :ontology/concepts-by-section projection is consulted instead of the
   URI-keyed projection. This defeats the silent-collision failure mode
   where two sections minting the same URI would have ONE silently
   overwrite the other in the URI-keyed projection. Without scoping
   (no :ontology-id / :ontology-ids), today's URI-keyed projection
   behavior is preserved — back-compat for single-tenant consumers."
  [ctx & [{:keys [scope broader-uri ontology-id ontology-ids]}]]
  (let [ont-id-set (cond
                     ontology-ids (set ontology-ids)
                     ontology-id #{ontology-id}
                     :else nil)
        all-concepts (if ont-id-set
                       ;; Scoped: union the per-section maps' values so
                       ;; collision-bearing URIs surface both copies.
                       (mapcat vals
                               (vals (select-keys
                                      (rmp/project ctx :ontology/concepts-by-section)
                                      ont-id-set)))
                       ;; Unscoped: today's URI-keyed projection
                       (vals (rmp/project ctx :ontology/concepts)))]
    (cond->> all-concepts
      scope (filter #(= scope (:scope %)))
      broader-uri (filter #(contains? (:broader %) broader-uri)))))

(defn get-concept-by-uri
  "Get a single concept by URI.

   S02: accepts an optional `opts` map with `:ontology-id` or
   `:ontology-ids` for section-scoped lookup. When scoped, consults
   the section-keyed `:ontology/concepts-by-section` projection so
   URI collisions across sections resolve to the correct concept;
   the unscoped 2-arity preserves today's URI-keyed behavior (the
   projection's last-writer-wins shape) for back-compat with single-
   tenant callers.

   When `:ontology-ids` (a coll) is passed, returns the FIRST matching
   concept in input order (callers needing all matches should use
   `get-concepts` with the scoping coll)."
  ([ctx uri]
   (get (rmp/project ctx :ontology/concepts) uri))
  ([ctx uri {:keys [ontology-id ontology-ids]}]
   (cond
     ontology-id
     (get-in (rmp/project ctx :ontology/concepts-by-section)
             [ontology-id uri])

     (seq ontology-ids)
     (some (fn [oid]
             (get-in (rmp/project ctx :ontology/concepts-by-section)
                     [oid uri]))
           ontology-ids)

     :else
     (get (rmp/project ctx :ontology/concepts) uri))))

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
  "Get all concepts that are narrower than the given URI.

   S02: accepts an optional `opts` map with `:ontology-id` or
   `:ontology-ids`. When scoped, consults the section-keyed projection
   so URI collisions resolve correctly; the unscoped 2-arity preserves
   today's URI-keyed behavior (back-compat)."
  ([ctx uri]
   (get-in (rmp/project ctx :ontology/concepts) [uri :narrower] #{}))
  ([ctx uri {:keys [ontology-id ontology-ids]}]
   (cond
     ontology-id
     (get-in (rmp/project ctx :ontology/concepts-by-section)
             [ontology-id uri :narrower] #{})

     (seq ontology-ids)
     (reduce (fn [acc oid]
               (into acc
                     (get-in (rmp/project ctx :ontology/concepts-by-section)
                             [oid uri :narrower] #{})))
             #{} ontology-ids)

     :else
     (get-in (rmp/project ctx :ontology/concepts) [uri :narrower] #{}))))

(defn get-broader-concepts
  "Get all concepts that are broader than the given URI.

   S02: accepts an optional `opts` map with `:ontology-id` or
   `:ontology-ids`. When scoped, consults the section-keyed projection."
  ([ctx uri]
   (get-in (rmp/project ctx :ontology/concepts) [uri :broader] #{}))
  ([ctx uri {:keys [ontology-id ontology-ids]}]
   (cond
     ontology-id
     (get-in (rmp/project ctx :ontology/concepts-by-section)
             [ontology-id uri :broader] #{})

     (seq ontology-ids)
     (reduce (fn [acc oid]
               (into acc
                     (get-in (rmp/project ctx :ontology/concepts-by-section)
                             [oid uri :broader] #{})))
             #{} ontology-ids)

     :else
     (get-in (rmp/project ctx :ontology/concepts) [uri :broader] #{}))))

;; =============================================================================
;; Statistics
;; =============================================================================

(defn concept-statistics
  "Get statistics about the concept graph.

   S02: accepts an optional `opts` map with `:ontology-id` or
   `:ontology-ids`. When scoped, statistics reflect only the requested
   sections (so URI collisions across sections aren't undercounted by
   the URI-keyed projection's last-writer-wins shape); the unscoped
   1-arity preserves today's behavior."
  ([ctx]
   (let [concept-graph (rmp/project ctx :ontology/concepts)
         by-scope (group-by :scope (vals concept-graph))]
     {:total-concepts (count concept-graph)
      :by-scope (into {} (map (fn [[k v]] [k (count v)]) by-scope))
      :with-indicators (count (filter #(seq (:indicators %)) (vals concept-graph)))}))
  ([ctx {:keys [ontology-id ontology-ids]}]
   (let [by-section (rmp/project ctx :ontology/concepts-by-section)
         scope-set (cond
                     ontology-ids (set ontology-ids)
                     ontology-id #{ontology-id}
                     :else nil)
         concepts (if scope-set
                    (mapcat vals (vals (select-keys by-section scope-set)))
                    ;; No scope passed → equivalent to URI-keyed projection
                    (vals (rmp/project ctx :ontology/concepts)))
         by-scope (group-by :scope concepts)]
     {:total-concepts (count concepts)
      :by-scope (into {} (map (fn [[k v]] [k (count v)]) by-scope))
      :with-indicators (count (filter #(seq (:indicators %)) concepts))})))

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
