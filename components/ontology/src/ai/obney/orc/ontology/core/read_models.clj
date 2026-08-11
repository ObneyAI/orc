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
            [clojure.set :as set]
            [clojure.string :as str]))

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

(def claim-events
  "CC-1 (ADR 0021): events that affect the CLAIM side of the Living
   Description read model.

   Claims live in the same versioned read model as descriptions because
   they hang off the same (granularity, target-identifier) key — the
   spec's `LivingDescription.claims` relationship. Keeping them in one
   projection means one rebuild, and means a target's claim set and its
   assembled body can never drift into separate cache generations."
  #{:ontology/claim-deltas-recorded})

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
;; Ontology Lifecycle Projection
;; =============================================================================

(defmulti ontologies* (fn [_state event] (:event/type event)))

(defmethod ontologies* :ontology/ontology-created
  [state event]
  (assoc state (:ontology-id event)
         (cond-> {:ontology-id (:ontology-id event)
                  :name (:name event)
                  :scope (:scope event)
                  :created-at (str (:created-at event))}
           (contains? event :description) (assoc :description (:description event))
           (contains? event :base-uri) (assoc :base-uri (:base-uri event)))))

(defmethod ontologies* :default [state _] state)

(defn ontologies [initial-state events]
  (reduce ontologies* initial-state events))

(defreadmodel :ontology ontologies
  {:events ontology-events, :version 1}
  [state event] (ontologies* state event))

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
  (let [uri (:uri event)
        existing (get state uri)
        state (if (and existing (not= (:ontology-id existing) (:ontology-id event)))
                (-> state
                    (dissoc uri)
                    (assoc [(:ontology-id existing) uri] existing))
                state)]
    (assoc state uri
         {:uri uri
          :id (:concept-id event)
          :ontology-id (:ontology-id event)
          :label (:label event)
          :description (:description event)
          :scope (:scope event)
          :broader (set (or (:broader event) []))
          :narrower #{}
          :related #{}
          :indicators (or (:indicators event) [])
          :provenance (or (:provenance event) {:kind :system-static})
          :created-at (str (:created-at event))
          ::projection-order (count state)})))

(defmethod concepts* :ontology/concept-updated
  [state event]
  (if-let [[concept-key _] (some #(when (= (:concept-id event) (:id (val %))) %)
                                  state)]
    (update state concept-key merge
            (cond-> (:changes event)
              (contains? (:changes event) :broader)
              (update :broader set)
              true
              (assoc :updated-at (str (:updated-at event)))))
    state))

(defn- concept-keys-for-uri [state uri ontology-id]
  (->> state
       (keep (fn [[k concept]]
               (when (and (= uri (:uri concept))
                          (or (nil? ontology-id) (= ontology-id (:ontology-id concept))))
                 k)))))

(defn- update-concepts-by-uri [state uri ontology-id field target-uri]
  (reduce #(update-in %1 [%2 field] (fnil conj #{}) target-uri)
          state
          (concept-keys-for-uri state uri ontology-id)))

(defmethod concepts* :ontology/relationship-created
  [state event]
  (let [{:keys [source-uri target-uri predicate source-ontology-id target-ontology-id]} event]
    (case predicate
      "skos:broader"
      (-> state
          (update-concepts-by-uri source-uri source-ontology-id :broader target-uri)
          (update-concepts-by-uri target-uri target-ontology-id :narrower source-uri))

      "skos:narrower"
      (-> state
          (update-concepts-by-uri source-uri source-ontology-id :narrower target-uri)
          (update-concepts-by-uri target-uri target-ontology-id :broader source-uri))

      "skos:related"
      (-> state
          (update-concepts-by-uri source-uri source-ontology-id :related target-uri)
          (update-concepts-by-uri target-uri target-ontology-id :related source-uri))

      ;; R05a — behavior:composes-into is the bridge from behavioral
      ;; subtrees (Layer 2) to structural shells (Layer 1). The behavior
      ;; carries an outgoing :composes-into set of shell URIs; the shell
      ;; carries an incoming :composed-by set of behavior URIs. R05b's
      ;; classify-behaviors traverses these edges when narrowing
      ;; candidates by structural-context.
      "behavior:composes-into"
      (-> state
          (update-concepts-by-uri source-uri source-ontology-id :composes-into target-uri)
          (update-concepts-by-uri target-uri target-ontology-id :composed-by source-uri))

      ;; Other predicates (owl:causes, etc.) - store as related
      (update-concepts-by-uri state source-uri source-ontology-id :related target-uri))))

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
                (let [existing-key (first (concept-keys-for-uri acc uri ontology-id))
                      existing (get acc existing-key)
                      uri-existing (get acc uri)
                      acc (if (and uri-existing
                                   (not= (:ontology-id uri-existing) ontology-id))
                            (-> acc
                                (dissoc uri)
                                (assoc [(:ontology-id uri-existing) uri] uri-existing))
                            acc)]
                  (if existing
                    acc
                    (assoc acc uri
                           {:uri uri
                            ;; Extraction has no separate create command. Use
                            ;; ontology-scoped URI identity so replay is stable.
                            :id (java.util.UUID/nameUUIDFromBytes
                                 (.getBytes (str ontology-id "|" uri) "UTF-8"))
                            :ontology-id ontology-id
                            :label (:label concept)
                            :description (or (:definition concept) "")
                            :scope (keyword (or (:entity-type concept) "entity"))
                            :broader #{}
                            :narrower #{}
                            :related #{}
                            :indicators []
                            :provenance {:kind :source-extracted
                                         :source-reference (some-> (:source-id concept) str)}
                            :alt-labels (or (:alt-labels concept) [])
                            :confidence (:confidence concept 1.0)
                            :source-id (:source-id concept)
                            :created-at (:extracted-at event)
                            ::projection-order (count acc)})))))
            state
            (:concepts event))))

(defmethod concepts* :evolutionary/relationships-extracted
  [state event]
  ;; event has :relationships vector, each with :subject :predicate :object
  (let [ontology-id (:ontology-id event)]
    (reduce (fn [acc {:keys [subject predicate object]}]
            (case predicate
              "skos:broader"
              (-> acc
                  (update-concepts-by-uri subject ontology-id :broader object)
                  (update-concepts-by-uri object ontology-id :narrower subject))

              "skos:narrower"
              (-> acc
                  (update-concepts-by-uri subject ontology-id :narrower object)
                  (update-concepts-by-uri object ontology-id :broader subject))

              "skos:related"
              (-> acc
                  (update-concepts-by-uri subject ontology-id :related object)
                  (update-concepts-by-uri object ontology-id :related subject))

              ;; Other predicates - store as related by default
              (update-concepts-by-uri acc subject ontology-id :related object)))
          state
          (:relationships event))))

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

;; -----------------------------------------------------------------------------
;; CC-1 (ADR 0021) — the CLAIM fold, in the same projection
;; -----------------------------------------------------------------------------
;; A target's node in this projection grows three more keys alongside
;; :current / :history:
;;
;;   :claims            [<claim-map>, ...]  insertion-ordered, LIVE claims only
;;   :retired-claims    [<retirement>, ...] CC-2: the ClaimRetired facts
;;   :claim-set-version <int>               = how many :claim-deltas-recorded
;;                                            events this target has seen
;;
;; The version is what the command handler CAS-compares on append, so it
;; MUST equal the event count exactly — hence it advances on every recorded
;; event, including one whose deltas all turn out to be no-ops.
;;
;; TOLERANCE IS THE POINT. Events are permanent. A fold that throws on some
;; historical delta makes the whole read model unrebuildable, which is the
;; one failure this design cannot survive — so every branch that cannot be
;; applied returns the claim set UNCHANGED rather than raising. That covers
;; a delta naming a claim that does not exist, and the support/contradict/
;; edit operations that CC-2 has not implemented yet.

;; -----------------------------------------------------------------------------
;; The two calibrated numbers (CC-3's confidence curve, CC-7's threshold)
;; -----------------------------------------------------------------------------
;; They are stated together, and one is DERIVED from the other, because together
;; they decide what the model SEES (confidence governs R-Inject's top-two rank
;; per candidate) and what the ranker OBEYS (after CC-9, only a validated claim
;; may suppress a behavior's retrieval). Two independently-plucked literals
;; would drift apart silently the first time either was tuned.

(def ^:private confidence-support-scale
  "`k` in the derived-confidence curve `c(s) = s / (s + k)`. See
   `derive-confidence` for why the curve, and why k = 3."
  3)

(def ^:private enforcement-confidence-floor
  "The derived confidence a claim must reach before it is allowed to ENFORCE.

   0.6 is not a new number invented here: it is the waypoint `derive-confidence`
   is already calibrated around and the one CC-9's guard strength is specified
   against. Naming it makes the coupling explicit instead of leaving two
   literals to agree by coincidence."
  6/10)

(def ^:private validation-support-threshold
  "The spec's `validation_support_threshold` — the net support at which a claim
   that has post-guard evidence earns `:validated`. **It is 5.**

   DERIVED, NOT PLUCKED. It is the least integer support whose derived
   confidence clears `enforcement-confidence-floor`:

       c(s) >= f   <=>   s/(s+k) >= f   <=>   s >= f*k/(1-f)
       f = 0.6, k = 3  ->  s >= 4.5  ->  s = 5,  and c(5) = 0.625

   so `:status = :validated` implies `confidence >= 0.6` BY CONSTRUCTION, for
   whatever k the curve is ever re-calibrated to. The alternative — a literal 5
   sitting next to a literal 3 — makes that implication true today and silently
   false after the first tuning.

   WHY 0.6 IS THE RIGHT PLACE TO PUT THE LINE, which is where the judgement
   actually lives. Support is NET (corroborations minus contradictions, CC-2),
   so s = 5 means 'five more episodes agreed than disagreed', not 'five
   episodes were seen'.

   - LOWER (s = 3, c = 0.50) is precisely even odds — the state in which a
     claim must NOT be acting on retrieval. The forensic behind ADR 0021 found
     low-episode hypotheses to be the noisiest signal in the corpus; a
     threshold of 3 sits inside that noise, and under ADR 0022 a negative claim
     is a suppression lever, so the cost of a false positive is a muted
     behavior rather than a wrong sentence in a prompt.
   - HIGHER (s = 7, c = 0.70) means nothing enforces until a target has been
     consolidated seven net times. Consolidation is low-frequency by design, so
     that is the loop learning and never being permitted to act — the
     protection-induced starvation ADR 0021 exists to end, arriving as a
     threshold instead of as a whole-body validator.
   - AT 5 a thinly-corroborated claim stays fully VISIBLE to the model (it is
     still in the assembled body, still ranked by support, still readable via
     `get-claims`) and cannot mute anything. Visibility and enforcement are
     separated, which is the whole point of the status field.

   WHERE THAT SEPARATION IS ACTUALLY ENFORCED (CC-9a). Until CC-9a it was not:
   this docstring asserted it while `assemble-body` filtered on `:kind` alone,
   so an unproven guard reached EL-5's penalty through the body like any other.
   The threshold is now honoured at ONE seam — EL-2's `enrich-candidate-evidence`
   (ontology `interface`), which stamps the candidate with the
   `get-enforcing-claims` subset for `domain-penalty/avoid-strings` to read
   while `:avoid-when`/`:weaknesses` keep carrying everything for the render.
   A number that nothing reads is not a threshold; if that stamp is ever
   removed, this paragraph is false again.

   NOT A DEADBAND. Promotion and demotion share this one number, because the
   spec declares one threshold and a symmetric pair of transition edges. A
   claim oscillating across exactly 5 therefore flips status. Accepted rather
   than papered over: support moves only on consolidation events, which are
   rare, and a hysteresis band would be a second calibrated number the spec
   does not have and nothing has yet measured a need for."
  (long (Math/ceil (double (/ (* enforcement-confidence-floor confidence-support-scale)
                              (- 1 enforcement-confidence-floor))))))

(def ^:private initial-claim-support
  "The spec's `config.initial_claim_support`. A newly-added claim is seeded
   ABOVE the retirement floor. Support is EARNED from here: incremented by
   support/edit, decremented by contradict, and a claim retires only when a
   contradiction lands on support <= 1 (all CC-2).

   WHY 2 AND NOT 1. At a seed of 1 the very first `:contradict` retires a
   brand-new claim outright, which contradicts ADR 0021's own stated property
   that *no single consolidation and no single judgement can erase accumulated
   knowledge*. That is not theoretical: the CC-5 prototype (P-A) replayed the
   real reflection contract over 20 real consolidations and watched ONE
   consolidation retire SIX of a target's twelve claims in a single fold —
   because that evidence window happened to be all successes, so the model
   contradicted every negative claim at once. Two of those six had already been
   adjudicated as genuinely present. Anti-recency had re-entered through
   `:contradict`, wearing a different hat from the whole-body validator ADR
   0021 retired.

   At 2, one contradiction takes a fresh claim to 1 — visible, weakened, no
   longer enforcing — and a SECOND retires it. That is what \"accumulated
   contradiction\" means, and it is ExpeL's measured design (insights start at
   importance 2; removal requires accumulation).

   Consequence accepted deliberately: under CC-3's `c(s) = s/(s+k)` with k=3, a
   fresh claim now displays at 0.40 confidence rather than 0.25 and needs three
   further supports to validate rather than four. CC-7's threshold is DERIVED
   from the confidence floor, not from this seed, so it is unaffected."
  2)

(defn- claim-id-for
  "Deterministic identity for the claim created by the delta at `idx` of
   `event`. Derived from the recording event's id, which is permanent, so
   a rebuild from the log reproduces byte-identical claim ids — and so a
   later delta can name this claim by string as the spec's
   `ClaimDelta.target_claim` requires."
  [event idx]
  (str (:event/id event) "#" idx))

(def ^:private authored-basis
  "The spec's `EvidenceBasis.authored` — designer-written corpus knowledge.

   Named once because THREE spec constructs compare against this exact value
   (`ValidateAuthoredClaimAtCreation`, the `evidence_basis != authored`
   precondition of `DemoteUnderSupportedClaim`, and the widened
   `OnlyValidatedClaimsEnforce`). Three literal keywords agreeing by
   coincidence is how the exemption would half-disappear under a rename."
  :authored)

(defn- authored?
  "CC-9d: does this claim rest on AUTHORSHIP rather than on accrual?

   Reads the claim's own durable `:evidence-basis`, never a delta's — a claim's
   basis is set at creation and an edit preserves it, so a later delta cannot
   argue its way into (or out of) the exemption."
  [claim]
  (= authored-basis (:evidence-basis claim)))

(defn- add-claim
  "Apply an `:add` delta: create a claim carrying the episodes that produced it.
   `recorded-at` comes off the event (the command handler stamped it), never
   from a clock read inside the fold — a fold that reads the wall clock does not
   rebuild to the same state.

   CC-9d: `:evidence-basis` is recorded here and ONLY here — the spec's 'set at
   CREATION only'. It is stored verbatim from the delta (`nil` when the delta
   declared nothing) rather than normalised through the guard's
   `declared-basis`, so `nil` keeps meaning 'declared nothing' on the claim,
   which is exactly the optionality the spec's `evidence_basis: EvidenceBasis?`
   carries.

   STATUS AT CREATION is `:candidate` — EXCEPT for an authored basis, which is
   BORN `:validated` (spec `ValidateAuthoredClaimAtCreation`). A curated guard
   must enforce at full strength from day one; seeding it as a candidate would
   silently disarm the proven regression corpus at the moment of seeding.

   This is deliberately NOT routed through `with-earned-status`. The two paths
   stay distinct for the reason that function's docstring gives — so that
   raising `initial-claim-support` could never quietly mint pre-validated claims
   — and CC-9d does not weaken that: the ONLY thing that mints a validated claim
   here is an explicit, auditable declaration of authorship, which the
   consolidator forbids the reflection LLM from making."
  [claims delta claim-id recorded-at]
  (let [basis (:evidence-basis delta)]
    (conj (or claims [])
          {:claim-id               claim-id
           :kind                   (:kind delta)
           :content                (:content delta)
           :context-guard          (:context-guard delta)
           :recommendation         (:recommendation delta)
           :support                initial-claim-support
           :status                 (if (= authored-basis basis) :validated :candidate)
           :supporting-episodes    (vec (:episodes delta))
           :contradicting-episodes []
           :legacy-provenance      (boolean (:from-legacy-corpus delta))
           :evidence-basis         basis
           :created-at             recorded-at
           :updated-at             recorded-at})))

;; -----------------------------------------------------------------------------
;; CC-7 (ADRs 0021/0022) — status: earning, and keeping, the right to enforce
;; -----------------------------------------------------------------------------

(defn- post-guard-episode-count
  "The spec's `post_guard_episode_count(claim)` — how many of this claim's
   supporting occurrences were RESOLVED against real judge evidence by CC-4's
   evidence guard.

   Every delta that reaches this fold has already passed the guard, so the
   question is not whether an episode was checked but whether the claim rests
   on any checked episode AT ALL. CC-4's `:from-legacy-corpus` arm is the
   discriminator: a legacy delta naming no occurrence is admitted by DECLARED
   PROVENANCE rather than by resolved judge evidence, contributes no episode
   here, and therefore contributes nothing that can validate.

   That is the second property this slice exists for, and it is not
   bureaucracy. Pre-guard evidence has unknown starvation content BY
   DEFINITION — CC-4 measured three starved judgements in a small historical
   corpus, one of which scored 1.0 while reasoning 'assuming the document
   exists…' against an empty input context. A backfilled claim may be visible,
   well-supported and sorted high, and still must not be able to suppress a
   behavior on the strength of evidence nobody can re-examine."
  [claim]
  (count (remove nil? (:supporting-episodes claim))))

(defn- earned-status
  "The spec's `ValidateWellSupportedClaim` rule AND its `validated ->
   candidate` transition edge, expressed as ONE total function of the claim's
   own state.

   Written as a recomputation rather than as two mutating branches, for three
   reasons:

   1. BOTH EDGES ARE DECLARED, so enforcement is continuously earned rather
      than granted once. A claim whose support decays back below the threshold
      returns to candidate — it does not keep a privilege it no longer merits.
   2. IT IS THE ONLY SHAPE IN WHICH NO THIRD STATUS IS REACHABLE. The range of
      this function is literally `#{:candidate :validated}`, so a future
      operation added to the fold cannot invent a status by forgetting a
      check.
   3. A REBUILD FROM THE LOG MUST REPRODUCE IT. Status derived from the
      claim's own support and episodes — both of which are themselves folded
      from permanent events — cannot drift from the events that produced them.

   The apparent asymmetry (promotion needs post-guard evidence, demotion is
   governed by support alone) is not one: `:supporting-episodes` never shrinks,
   because a contradiction files onto `:contradicting-episodes` instead, so the
   post-guard condition is monotone once satisfied.

   CC-9d ADDS ONE ARM: an AUTHORED claim is validated at every support level.
   That is the spec's `evidence_basis != authored` precondition on
   `DemoteUnderSupportedClaim`, expressed in the same total-function shape
   rather than as a branch bolted onto the demotion path — the range is still
   literally `#{:candidate :validated}`, and the exemption is still a pure
   function of the claim's own durable state.

   IT IS NOT IMMORTALITY, and the distinction matters. This function decides
   STATUS, not existence. Contradiction still decrements the claim's support on
   every disagreement, and `apply-claim-delta`'s retirement arm still removes it
   outright when that support is exhausted — the spec's own stated erosion path
   for authored knowledge. What authorship buys is that a curated guard is not
   quietly disarmed by sitting at a support number that was never a threshold
   count for it in the first place."
  [claim]
  (if (or (authored? claim)
          (and (>= (or (:support claim) 0) validation-support-threshold)
               (pos? (post-guard-episode-count claim))))
    :validated
    :candidate))

(defn- with-earned-status
  "Re-derive `:status` after an operation that changed a claim's support —
   the spec rule's `when: ClaimSupportChanged` trigger.

   `:add` deliberately does NOT route through here. A brand-new claim is
   `:candidate` by construction (CC-1), creation is not a support change, and
   keeping the two paths distinct means raising `initial-claim-support` could
   never quietly mint pre-validated claims."
  [claim]
  (assoc claim :status (earned-status claim)))

(defn- reinforce-claim
  "CC-2, spec `ReinforceClaim`: a `:support` (or `:edit`) delta earns the
   claim one more unit of support and files the episode that earned it.
   The episodes are what make the support RESOLVABLE — a support count
   with no episodes behind it is an unfalsifiable number.

   CC-7: the earned support may cross the validation threshold, so status is
   re-derived here — after the episodes are filed, because the post-guard
   condition reads them."
  [claim delta recorded-at]
  (-> claim
      (update :support inc)
      (update :supporting-episodes (fnil into []) (:episodes delta))
      (assoc :updated-at recorded-at)
      (with-earned-status)))

(defn- edit-claim
  "CC-2, spec `ReinforceClaim` for `delta.operation = edit`: an edit
   reinforces AND rewords.

   `:kind` is MUTABLE and that is the point. The forensic behind ADR 0021
   found that rephrasings systematically migrate between description
   sections (weakness→guard, strength→capability); treating that as a new
   claim is how accumulated knowledge got lost. Here the claim keeps its
   identity, its provenance and every unit of support it earned, and only
   its wording moves.

   `:context-guard` / `:recommendation` are optional on the delta, so they
   are refreshed only when the delta actually SUPPLIES one — an edit that
   is silent about them must not erase what an earlier delta established.

   CC-9d — `:evidence-basis` IS ABSENT FROM THIS FUNCTION ON PURPOSE, and the
   omission is load-bearing rather than an oversight. The spec says the basis is
   set at CREATION only and that an edit PRESERVES it. That is the
   anti-laundering rule: `:edit` is precisely the operation the reflection LLM
   uses to reword a claim while it keeps its identity and its earned support, so
   an edit that also re-set the basis would let an ordinary rewording of a
   curated guard silently drop it out of enforcement into weaker
   earned-evidence accounting — at a moment nobody is watching. It cuts both
   ways: it also stops a delta asserting its way INTO the curated corpus after
   the fact.

   DO NOT ADD `:evidence-basis` TO THE `assoc` BELOW. If you do, the failure is
   not even loud: `reinforce-claim` re-derives `:status` BEFORE this `assoc`
   runs, so the claim would be left with a status derived from the OLD basis and
   a NEW basis recorded beside it — an internally inconsistent claim that
   violates `OnlyValidatedClaimsEnforce`. Guarded by
   `cc9d-authored-evidence-basis-test`, both directions."
  [claim delta recorded-at]
  (cond-> (-> (reinforce-claim claim delta recorded-at)
              (assoc :content (:content delta)
                     :kind    (:kind delta)))
    (some? (:context-guard delta))  (assoc :context-guard (:context-guard delta))
    (some? (:recommendation delta)) (assoc :recommendation (:recommendation delta))))

(defn- contradict-claim
  "CC-2, spec `ContradictClaim`: disagreement is RECORDED, not resolved by
   whoever wrote last. One unit of support is withdrawn and the episode
   that disagreed is filed on `:contradicting-episodes` — deliberately NOT
   on `:supporting-episodes`, so the two ledgers stay readable against each
   other.

   Only ever called when `support > 1`; at `<= 1` the claim retires
   instead (spec `RetireExhaustedClaim`), which is the complementary arm.

   CC-7: this is the DEMOTION edge. Withdrawn support can carry a claim back
   under the validation threshold, and when it does the claim stops enforcing
   immediately — while remaining present and visible, because demotion is not
   deletion."
  [claim delta recorded-at]
  (-> claim
      (update :support dec)
      (update :contradicting-episodes (fnil into []) (:episodes delta))
      (assoc :updated-at recorded-at)
      (with-earned-status)))

(defn- retire-claim
  "CC-2, spec `RetireExhaustedClaim` — the ONLY path by which a claim
   leaves the projection, and never a single consolidation's decision: it
   fires when accumulated contradiction has exhausted the support a claim
   earned over many episodes.

   The claim VANISHES from `:claims` (so nothing downstream has to filter
   tombstones out of an assembled body) and the retirement FACT is filed on
   `:retired-claims`, carrying the claim exactly as it stood — including
   the contradiction that finished it. Nothing is erased: the deltas that
   built the claim and the delta that retired it are both permanent in the
   log, and a rebuild reproduces this same retirement."
  [claim delta recorded-at]
  {:claim      (-> claim
                   (update :contradicting-episodes (fnil into []) (:episodes delta))
                   (assoc :updated-at recorded-at))
   :reason     :support-exhausted
   :retired-at recorded-at})

(defn- resolve-claim-index
  "Position of the claim named by `:target-claim`, or nil when this target
   has no such claim — the fold tolerance CC-1 established, which CC-2
   makes load-bearing rather than merely defensive: now that claims can
   retire, a delta naming a claim that has since left is a NORMAL
   occurrence, not an error."
  [claims delta]
  (let [target-id (:target-claim delta)]
    (first (keep-indexed (fn [i c] (when (= target-id (:claim-id c)) i)) claims))))

(defn- apply-claim-delta
  "Fold ONE delta into a target's claim NODE — a map of `:claims` and
   `:retired-claims`. Returns the node unchanged for anything it cannot
   apply; never throws.

   Deletion is absent by construction: no branch here removes a claim
   except `retire-claim`, and that one is reachable only through
   `:contradict` against an already-exhausted claim. A delta whose
   `:target-claim` names a claim this target does not have STAYS a no-op —
   resolving it must not become a throw, because the event it came from
   can never be unwritten."
  [node [idx delta] event recorded-at]
  (let [{:keys [claims]} node
        op  (:operation delta)
        pos (when (not= :add op) (resolve-claim-index claims delta))]
    (cond
      (= :add op)
      (update node :claims add-claim delta (claim-id-for event idx) recorded-at)

      ;; Fold tolerance: an op naming a claim that is not here (never was,
      ;; or has already retired), or an operation outside the declared set.
      (or (nil? pos) (not (contains? #{:support :edit :contradict} op)))
      node

      (= :support op)
      (update-in node [:claims pos] reinforce-claim delta recorded-at)

      (= :edit op)
      (update-in node [:claims pos] edit-claim delta recorded-at)

      ;; The two contradict arms are complementary on support: >1 decays,
      ;; <=1 exhausts and retires.
      (> (:support (nth claims pos)) 1)
      (update-in node [:claims pos] contradict-claim delta recorded-at)

      :else
      (let [claim (nth claims pos)]
        (-> node
            (assoc :claims (into (vec (subvec claims 0 pos)) (subvec claims (inc pos))))
            (update :retired-claims (fnil conj [])
                    (retire-claim claim delta recorded-at)))))))

;; -----------------------------------------------------------------------------
;; CC-3 (ADR 0021) — the BODY is ASSEMBLED from the claim set
;; -----------------------------------------------------------------------------
;; Sixteen-odd consumers read `:current` (R-Inject's principle render, EL-2's
;; candidate enrichment, EL-5's domain penalty, harvest's pattern pick, the
;; reindex metadata, the walk-down classifier, the RLM sandbox lookup). None of
;; them change. What changes is where the body comes FROM: it stops being
;; whatever an LLM last wrote wholesale and becomes a pure function of the
;; target's accumulated claims, recomputed on every claim-delta event.
;;
;; Kind → section, the mapping consumers already read:
;;   :capability         → :capabilities         (vector of strings)
;;   :representative-use → :representative-uses  (vector of strings)
;;   :guard              → :avoid-when           (body-level, EL-5 reads it)
;;   :strength           → :strengths            (principle entries)
;;   :weakness           → :weaknesses           (principle entries)

(defn- derive-confidence
  "Confidence DERIVED from earned support, never asserted by an LLM. This is
   the property ADR 0021 keeps the field for: the number now means something
   because it is a function of how many episodes corroborated the claim minus
   how many contradicted it (`:support` already nets those — CC-2).

   The curve is `c(s) = s / (s + k)` with k = 3:

       s=1 → 0.25   s=2 → 0.40   s=3 → 0.50   s=5 → 0.63
       s=7 → 0.70   s=12 → 0.80  s=27 → 0.90  s→∞ → 1.0 (never reached)

   Why this shape, given it silently governs which traits R-Inject injects
   (`format-seed-body` sorts by :confidence and keeps only the top two per
   candidate) and, after CC-9, how hard a guard suppresses retrieval:

   1. BOUNDED AND MONOTONE BY CONSTRUCTION, not by clamping. s/(s+k) is in
      (0,1) and strictly increasing for every s > 0, so
      `DescriptionConfidenceIsBounded` cannot be violated by any support value
      the fold can produce, and a `max`/`min` clamp — which would silently
      flatten a mis-scaled curve into a plateau and hide the bug — is not
      needed. (A defensive clamp is still applied, but it is dead code for
      every reachable support; see below.)

   2. CONCAVE — the second episode is worth far more than the twentieth. Ten
      repetitions of the same episode is not ten times the knowledge one gave;
      an unbounded or linear curve would let a chatty target out-shout a
      well-corroborated quiet one purely on volume.

   3. NEVER 1.0. No finite pile of episodes proves a claim about a
      probabilistic system. A curve that saturates at exactly 1 invites
      downstream code to treat a claim as a fact.

   4. THE CALIBRATION (k = 3) is where the judgement is, so state it plainly:
      k is the support at which confidence is 0.5 — 'three independent
      corroborations is even odds'. It puts a single-episode claim at 0.25,
      comfortably below anything that reads as advice, and crosses 0.6 at
      s = 5. A smaller k (k=1: one episode → 0.5) would let the
      single-episode hypotheses ADR 0021's forensic found to be the noisiest
      signal in the corpus dominate the R-Inject top-two. A larger k (k=10)
      would leave a genuinely well-established claim below 0.5 after a month
      of episodes and effectively mute the loop.

   NOTE ON THE 0.6 NUMBER: the brief describes 0.6 as R-Inject's 'display
   floor'. Read against the code, R-Inject's 0.6 (`min-seed-score` in
   orc-service `todo_processors.clj`) is a RERANK-SCORE floor on candidates,
   not a confidence floor — confidence there governs RANK (top-two per
   candidate), not admission. The curve is nonetheless calibrated so 0.6 is a
   meaningful waypoint (s=5), because CC-9's guard strength is specified
   against it."
  [support]
  (let [s (double (max 0 (or support 0)))]
    ;; The min/max is defence in depth only: s/(s+k) is already in [0,1) for
    ;; every s >= 0. If a future change to how support is earned ever breaks
    ;; that, the invariant still holds at the read model boundary.
    (-> (/ s (+ s (double confidence-support-scale)))
        (max 0.0)
        (min 1.0))))

(defn- by-earned-support
  "Rank claims strongest-first, with `:claim-id` as a deterministic tie-break.

   The ORDER IS LOAD-BEARING and is not cosmetic: EL-2's
   `enrich-candidate-evidence` truncates with `(take 3)` in body order — it
   does NOT sort — so whatever lands first is what the reranker sees. Ranking
   here means the best-supported claims survive every downstream truncation,
   including the ones that do not sort for themselves. Tie-breaking on
   `:claim-id` (deterministic from the recording event) keeps a rebuild from
   the log byte-identical."
  [claims]
  (sort-by (juxt (comp - :support) :claim-id) claims))

(defn- claim-contents
  "The de-duplicated contents of every claim of `kind`, best-supported first —
   the shape the string-vector body sections take."
  [ranked-claims kind]
  (into [] (comp (filter #(= kind (:kind %))) (map :content) (distinct))
        ranked-claims))

(defn- principle-entry
  "One `:strengths` / `:weaknesses` entry, principle-shaped per the shipped
   `schemas/principle-entry`: an actionable trait, its context guard, its
   advice, plus the derived weight signal.

   `guard-key` / `advice-key` differ by section — strengths carry
   `:good-when` + `:recommended-pattern`, weaknesses carry `:avoid-when` +
   `:recommended-alternative` — which is exactly the branch R-Inject's
   `format-principle-entry` and EL-5's `avoid-strings` take. Both are
   `{:optional true} :string` (not `:maybe`) on the shipped schema, so a nil
   guard must be ABSENT, not present-and-nil."
  [claim guard-key advice-key]
  (cond-> {:trait          (:content claim)
           :confidence     (derive-confidence (:support claim))
           :evidence-count (count (:supporting-episodes claim))}
    (:context-guard claim)  (assoc guard-key (:context-guard claim))
    (:recommendation claim) (assoc advice-key (:recommendation claim))
    (:created-at claim)     (assoc :first-observed-at (:created-at claim))
    (:updated-at claim)     (assoc :last-reinforced-at (:updated-at claim))))

(def ^:private empty-body-summary
  "What `:summary` says for a target whose claim set is empty — reachable
   when every delta in an event was a no-op, or when the last surviving claim
   retired. Honest rather than blank: `:summary` is what ColBERT indexes
   today, and an empty string would index as a match for everything."
  "No claims have been recorded for this target yet.")

(defn- assemble-summary
  "Derive `:summary` from the claims themselves.

   `:summary` is THE ColBERT retrieval key — permanently, not provisionally.
   CC-8 would have replaced it with a synthesised `retrieval_description`;
   CC-8 is CLOSED, not built (ADR 0026: its stated goal measured false and its
   premise was never in the literature). Nothing is scheduled to demolish this,
   so 'don't invest in the phrasing, it's going away' is no longer a reason for
   anything.

   Consumers read it directly (R-Inject's `derive-seed-name` parses it, EL-2's
   terse candidates carry it as `:content`), so it cannot be blank. What it
   must be is FAITHFUL: every sentence is claim content, nothing is invented,
   and the ordering is the support ordering, so the best-corroborated content
   leads. That faithfulness constraint — not an impending replacement — is what
   keeps the assembly mechanical."
  [{:keys [capabilities representative-uses strengths weaknesses avoid-when]}]
  (let [section (fn [label items]
                  (when (seq items) (str label ": " (str/join "; " items) ".")))
        parts (remove nil?
                      [(section "Capabilities" capabilities)
                       (section "Representative uses" representative-uses)
                       (section "Strengths" (map :trait strengths))
                       (section "Known weaknesses" (map :trait weaknesses))
                       (section "Avoid when" avoid-when)])]
    (if (seq parts) (str/join " " parts) empty-body-summary)))

(def ^:private carried-body-keys
  "Body fields that are NOT derivable from claims and must survive an
   assembly, because they are graph/identity metadata rather than knowledge:
   the SKOS hierarchy axes (`:parent-tree-id`, `:parent-behavior`), the
   retrieval dimension (`:scope`), the behavior→shell edges
   (`:composes-into`) and the consolidator's inferred behavioral ids.

   Dropping these would silently unparent a tree-class the first time a claim
   landed on it — the assembled body replaces the KNOWLEDGE, not the target's
   place in the graph."
  [:parent-tree-id :scope :composes-into :parent-behavior :behavioral-subtree-ids])

(defn- assemble-body
  "The spec's `assemble_body(description.claims)` — a pure function of the
   claim set (plus the carried-forward graph metadata and the previous
   version number). Called from the fold, so it must never read a clock or
   any state outside its arguments: a rebuild from the log has to reproduce
   this body exactly."
  [claims prev-body evidence-event-count]
  (let [ranked     (by-earned-support claims)
        of-kind    (fn [k] (filter #(= k (:kind %)) ranked))
        sections   {:capabilities        (claim-contents ranked :capability)
                    :representative-uses (claim-contents ranked :representative-use)
                    ;; Guard claims are the body-level :avoid-when EL-5's
                    ;; `avoid-strings` reads. Per-weakness guards are NOT
                    ;; duplicated here — EL-2's enrichment and EL-5 both
                    ;; already union the two sources.
                    :avoid-when          (claim-contents ranked :guard)
                    :strengths           (mapv #(principle-entry % :good-when :recommended-pattern)
                                               (of-kind :strength))
                    :weaknesses          (mapv #(principle-entry % :avoid-when :recommended-alternative)
                                               (of-kind :weakness))}
        prev-version (:version prev-body)]
    (merge (select-keys (or prev-body {}) carried-body-keys)
           sections
           {:summary (assemble-summary sections)
            ;; The spec's `description.version = description.version + 1`.
            ;; Reads the PREVIOUS body's version, so a target that was
            ;; described the legacy way and then grows claims keeps counting
            ;; up rather than restarting at 1.
            :version (inc (if (int? prev-version) prev-version 0))
            :consolidated-from-event-count (or evidence-event-count 0)})))

(defmethod descriptions* :ontology/claim-deltas-recorded
  [state event]
  (let [granularity (:granularity event)
        target-id   (:target-identifier event)
        recorded-at (:recorded-at event)
        path        [granularity target-id]
        node        (reduce (fn [acc indexed-delta]
                              (apply-claim-delta acc indexed-delta event recorded-at))
                            {:claims         (or (get-in state (conj path :claims)) [])
                             :retired-claims (or (get-in state (conj path :retired-claims)) [])}
                            (map-indexed vector (:deltas event)))
        ;; CC-3: the claim set changed, so the body is re-derived. Retired
        ;; claims are already gone from (:claims node) — CC-2 removes rather
        ;; than tombstones — so nothing here has to filter them out.
        assembled   (assemble-body (:claims node)
                                   (get-in state (conj path :current))
                                   (:evidence-event-count event))]
    (-> state
        (assoc-in (conj path :claims) (:claims node))
        (assoc-in (conj path :retired-claims) (:retired-claims node))
        (assoc-in (conj path :current) assembled)
        ;; `description.updated_at = now` — `now` being the handler's stamp on
        ;; the event, never a clock read inside the fold.
        (assoc-in (conj path :updated-at) recorded-at)
        ;; DescriptionHistoryIsAppendOnly: same entry shape the legacy
        ;; description fold appends, so `get-description-history` stays one
        ;; homogeneous audit trail across both paths, and `:recorded-at` here
        ;; equals the `:updated-at` stamped above (the invariant's `<=`).
        (update-in (conj path :history) (fnil conj [])
                   {:body assembled :recorded-at recorded-at :event-id (:event/id event)})
        ;; Advances per RECORDED EVENT, not per applied delta — this is the
        ;; value the append CAS compares against.
        (update-in (conj path :claim-set-version) (fnil inc 0)))))

(defn descriptions
  "Build the Living Description state from a seq of events."
  [initial-state events]
  (reduce descriptions* initial-state events))

(defreadmodel :ontology descriptions
  ;; v2 (CC-1): claim-delta events now project into the same read model.
  {:events (set/union description-events claim-events), :version 2}
  [state event] (descriptions* state event))

(defn get-description
  "Return the CURRENT description body for the (granularity, target-id)
   target, or nil if no description exists.

   Granularity is one of :node-type, :node-instance, :tree-fingerprint.
   The target-id is whatever shape the granularity uses (keyword for
   :node-type, [sheet-id node-id] tuple for :node-instance, string for
   :tree-fingerprint).

   CC-3: the body is either wholesale-recorded (legacy) or ASSEMBLED
   from the target's claims — see `assemble-body`. Same slot, same
   shape, same consumers."
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

(defn get-claims
  "CC-1 (ADR 0021): return the (granularity, target-id) target's current
   CLAIM SET — the vector of claims accumulated from every claim-delta
   event recorded against it, in the order they were added. Empty vector
   when the target has no claims.

   Mirrors `get-description`'s arg order; claims and the description body
   hang off the same target key, which is the spec's
   `LivingDescription.claims` relationship. Disjoint targets never share
   claims."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :claims])
      []))

(defn get-retired-claims
  "CC-2 (ADR 0021): return the target's `ClaimRetired` facts, oldest first.
   Empty vector when nothing has retired.

   Each entry is `{:claim <the claim as it stood> :reason :support-exhausted
   :retired-at <recorded-at>}`. Retirement is the ONLY removal path, so
   this vector is the complete record of everything this target has ever
   stopped believing — and it never shrinks. Retired claims are absent
   from `get-claims` deliberately: an assembled body must not have to
   filter tombstones."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :retired-claims])
      []))

(defn get-enforcing-claims
  "CC-7 (ADRs 0021/0022): return only the target's VALIDATED claims — the
   ones that have earned the right to influence retrieval ranking — ordered
   BEST-SUPPORTED FIRST. Empty vector when none.

   This is a SELECTION SURFACE, not a second projection: each element is the
   same claim map `get-claims` returns. What it removes is every claim that
   has not earned enforcement, which under ADR 0022 is the whole safety
   property — a negative claim is a retrieval-SUPPRESSION lever, so an
   unproven assertion reachable by a ranker would be the exact failure this
   arc exists to prevent, wearing new clothes. Candidates stay fully visible
   to the model through `get-claims` and the assembled body; they cannot mute
   anything.

   THE PRODUCTION CONSUMER IS CC-9a, and it is the only one: EL-2's
   `enrich-candidate-evidence` stamps this function's result onto a
   claim-backed candidate as `::domain-penalty/enforcing-avoid-when` — a
   `:guard` claim contributes its `:content`, a `:weakness` claim its
   `:context-guard`, which are precisely the two shapes `assemble-body` writes
   into the body and `avoid-strings` unions. Between CC-7 and CC-9a this
   function had ZERO consumers, so the safety property above was documented
   rather than true: the assembled body carried every claim regardless of
   status straight into the penalty. Guarded now by
   `cc9a-enforcement-gate-test`.

   THE ORDER IS PART OF THE CONTRACT and deliberately differs from
   `get-claims`, which preserves insertion order. The consumer here is a
   RANKER (CC-9 wires this into EL-5's domain penalty), and CC-3 established
   that consumers downstream truncate in the order they are handed — EL-2's
   `enrich-candidate-evidence` takes 3 in body order without sorting. Ranking
   at the source means the strongest claim survives every such truncation.
   The tie-break is `:claim-id`, deterministic from the recording event, so a
   rebuild from the log reproduces a byte-identical order."
  [ctx granularity target-id]
  (into []
        (by-earned-support
          (filter #(= :validated (:status %))
                  (get-claims ctx granularity target-id)))))

(defn get-claim-set-version
  "CC-1 (ADR 0021): return the target's current claim-set version — the
   count of claim-delta events recorded against it. 0 when none.

   This is the value a consolidation must carry back when it records its
   deltas; the append CAS compares it, so a consolidation that reasoned
   over a stale claim set loses instead of silently overwriting."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/descriptions)
              [granularity target-id :claim-set-version])
      0))

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

(defmethod reindex-state* :ontology/claim-deltas-recorded [state _event]
  ;; CC-6. This counter's job is 'how much INDEXABLE CONTENT has changed since
  ;; the last rebuild', and under ADR 0021 that content changes through claim
  ;; deltas: CC-3 re-derives `:current` — including `:summary`, the field the
  ;; index is built from — on every one of these events. Counting only
  ;; `*-description-updated` was correct while a body could only arrive by
  ;; being written; after CC-5 it silently under-counts, and after CC-6 it
  ;; would under-count to ZERO for the classify-time capture whose entire
  ;; purpose is to make a freshly-minted class retrievable.
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
  ;; v2 (CC-6): claim-delta events now count toward the rebuild trigger, because
  ;; they are now how an indexable body changes. VERSION BUMPED, not amended in
  ;; place: a v1 cache generation was built from a stream that excluded claim
  ;; events, so serving it after this change would report a counter that can
  ;; never reach the threshold and no rebuild would ever fire — the same silent
  ;; staleness CC-2/CC-7 flagged for :ontology/descriptions.
  {:events #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated
             :ontology/claim-deltas-recorded
             :colbert/index-created}
   :version 2}
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

;; CC-5: a claim-path consolidation costs exactly the same LLM call as a
;; body-path one and must count against the same hourly budget. It emits
;; :ontology/claim-deltas-recorded instead of a *-description-updated event, and
;; names its target-type `:granularity` rather than `:target-type` — so without
;; this method the budget gate would silently stop bounding the one granularity
;; that consolidates most often.
(defmethod recent-consolidations* :ontology/claim-deltas-recorded [state event]
  (record-consolidation-timestamp state (assoc event :target-type (:granularity event))))

;; CC-28, the spec's FailuresConsumeBudget: a failed attempt-set cost the
;; same LLM attempts a success did and must count against the same hourly
;; budget — a target whose every attempt dies must not retry unthrottled,
;; and the budget read cannot report a dying target as idle. The event
;; names its target-type `:granularity` (the claim-event idiom), so it is
;; re-keyed exactly as :ontology/claim-deltas-recorded is. The fold is a
;; named-key conj into the same per-target-type timestamp vector — safe on
;; empty state, so a failure event replayed BEFORE any success (or on a
;; store holding nothing else) folds identically.
(defmethod recent-consolidations* :ontology/description-consolidation-failed [state event]
  (record-consolidation-timestamp state (assoc event :target-type (:granularity event))))

(defn recent-consolidations
  "Build the recent-consolidations state from a seq of events."
  [initial-state events]
  (reduce recent-consolidations* initial-state events))

(defreadmodel :ontology recent-consolidations
  ;; v2 (CC-5): claim-delta events now count against the consolidation budget.
  ;; The version bump is required, not cosmetic — a v1 cache generation was
  ;; built by code that did not fold this event type, so it would serve a
  ;; budget count that permanently under-reports claim-path consolidations.
  ;; v3 (CC-28): failure events now count too, same reasoning — a v2 cache
  ;; generation was built by code that could not see death, so it would
  ;; serve a budget count that reports a dying target as idle and lets it
  ;; retry unthrottled forever.
  {:events #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated
             :ontology/claim-deltas-recorded
             :ontology/description-consolidation-failed}
   :version 3}
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
;; EL-4 (ADR 0015) — tree-class judge-averages STANDING read-model
;; =============================================================================
;;
;; The consolidator computes per-tree-class judge-averages on demand by
;; scanning the event store (consolidator.clj tree-class-aggregate-metrics),
;; but that private scan is NOT queryable at harvest time. This standing
;; read-model projects the same signal so the harvest gate can read a
;; per-[:tree-class id] per-judge running mean cheaply.
;;
;; It does the sheet -> tree-class JOIN in the reducer by accumulating two
;; order-independent maps:
;;   :sheet->class  {source-sheet-id -> assigned-tree-id}   (task-classified)
;;   :sheet-judge   {sheet-id -> {judge-name -> {:sum :count}}}  (score-emitted)
;; The per-class projection is computed by the accessor, joining the two.
;; This is order-independent: a score seen before its classification (or
;; after) both land correctly, exactly as the aggregate's post-hoc scan does.
;;
;; PARITY ORACLE (el4-harvest-test): the projected per-class mean EQUALS
;; consolidator's tree-class-aggregate-metrics :judge-averages for the same
;; event stream.

(defmulti tree-class-judge-averages*
  (fn [_state event] (:event/type event)))

(defmethod tree-class-judge-averages* :default [state _] state)

(defmethod tree-class-judge-averages* :ontology/task-classified
  [state event]
  (if-let [class-id (:assigned-tree-id event)]
    (-> state
        ;; :sheet->class stays keyed on the bare (possibly shared/static)
        ;; sheet-id — get-tree-class-for-sheet (CV-2's post-emit enrichment
        ;; consumer) relies on this "most recent classification for this
        ;; sheet" semantics and has no per-occurrence tick-id to join on.
        (assoc-in [:sheet->class (:source-sheet-id event)] class-id)
        ;; SJ-1: :occurrence->class is keyed on [sheet-id tick-id] — a
        ;; per-OCCURRENCE identity, unlike :sheet->class above. A static
        ;; task-shape's sheet-id is shared across every turn, so a bare
        ;; sheet-id key silently overwrites earlier occurrences' class
        ;; assignment whenever the same sheet-id is later reclassified to a
        ;; sibling class — corrupting score attribution for every occurrence,
        ;; not just the most recent one. tick-id is the per-turn identity
        ;; already used this way by the consolidator (gather-recent-tree-
        ;; class-events joins judge-scores by (sheet-id, tick-id)).
        (assoc-in [:occurrence->class [(:source-sheet-id event) (:source-tick-id event)]] class-id))
    state))

(defmethod tree-class-judge-averages* :judge/score-emitted
  [state event]
  (let [{:keys [sheet-id tick-id judge-name score]} event]
    (if (and sheet-id tick-id judge-name (number? score))
      (-> state
          (update-in [:sheet-judge [sheet-id tick-id] judge-name :sum] (fnil + 0.0) score)
          (update-in [:sheet-judge [sheet-id tick-id] judge-name :count] (fnil inc 0)))
      state)))

(defn tree-class-judge-averages-projection
  "Join the two accumulated maps into {class-id -> {judge-name -> {:sum :count}}}.
   SJ-1: joined by [sheet-id tick-id] OCCURRENCE identity (via :occurrence->class),
   not bare sheet-id — a static task-shape's sheet-id is shared across every
   turn, so a bare-sheet-id join would misattribute every occurrence's score
   onto whichever class the sheet was MOST RECENTLY (re)classified to. A score
   whose (sheet-id, tick-id) has no matching classification yet is excluded."
  [state]
  (let [{:keys [occurrence->class sheet-judge]} state]
    (reduce-kv
      (fn [acc occurrence-key judges]
        (if-let [class-id (get occurrence->class occurrence-key)]
          (reduce-kv
            (fn [a judge-name {:keys [sum count]}]
              (-> a
                  (update-in [class-id judge-name :sum] (fnil + 0.0) (or sum 0.0))
                  (update-in [class-id judge-name :count] (fnil + 0) (or count 0))))
            acc judges)
          acc))
      {} sheet-judge)))

(defreadmodel :ontology tree-class-judge-averages
  {:events #{:judge/score-emitted :ontology/task-classified}
   :version 1}
  [state event] (tree-class-judge-averages* state event))

(defn get-tree-class-for-sheet
  "CV-2 (ADR 0017 decision 3): return the :tree-class id assigned to
   `source-sheet-id`, or nil when the sheet was never classified.

   Reuses the tree-class-judge-averages read-model's :sheet->class map,
   which the :ontology/task-classified reducer populates UNCONDITIONALLY
   (independent of any judge scores) — exactly the sheet->class join the
   EL-4 read-model already performs. The post-emit enrichment processor
   uses this to route an emitted tree's worked-DSL onto the class the task
   was assigned to."
  [ctx source-sheet-id]
  (get-in (rmp/project ctx :ontology/tree-class-judge-averages)
          [:sheet->class source-sheet-id]))

(defn get-tree-class-judge-averages
  "EL-4: return {judge-name -> mean-score} across this tree-class's lifetime,
   or nil when no scores exist for the class. Parity target: the
   consolidator's tree-class-aggregate-metrics :judge-averages."
  [ctx tree-class-id]
  (let [state (rmp/project ctx :ontology/tree-class-judge-averages)
        by-class (tree-class-judge-averages-projection state)]
    (when-let [judges (get by-class tree-class-id)]
      (not-empty
        (into {}
              (map (fn [[judge-name {:keys [sum count]}]]
                     [judge-name (/ sum (double count))]))
              judges)))))

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
  (let [normalize-id #(if (string? %)
                        (java.util.UUID/nameUUIDFromBytes (.getBytes ^String % "UTF-8"))
                        %)
        all-concepts (map #(dissoc % ::projection-order)
                          (vals (rmp/project ctx :ontology/concepts)))
        ont-id-set (cond
                     ontology-ids (set (map normalize-id ontology-ids))
                     ontology-id #{(normalize-id ontology-id)}
                     :else nil)]
    (cond->> all-concepts
      scope (filter #(= scope (:scope %)))
      broader-uri (filter #(contains? (:broader %) broader-uri))
      ont-id-set (filter #(contains? ont-id-set (:ontology-id %))))))

(defn get-ontology [ctx ontology-id]
  (get (rmp/project ctx :ontology/ontologies) ontology-id))

(defn list-ontologies [ctx]
  (->> (rmp/project ctx :ontology/ontologies)
       vals
       (sort-by (juxt :created-at :ontology-id))
       vec))

(defn ontology-exists? [ctx ontology-id]
  (boolean (get-ontology ctx ontology-id)))

(defn get-concept-by-uri
  "Get a concept by ontology identity and URI. The legacy two-argument form
   returns a value only when the URI is globally unambiguous."
  ([ctx uri]
   (let [matches (filter #(= uri (:uri %))
                         (vals (rmp/project ctx :ontology/concepts)))]
     (when (= 1 (count matches))
       (dissoc (first matches) ::projection-order))))
  ([ctx ontology-id uri]
   (let [normalized-id (if (string? ontology-id)
                         (java.util.UUID/nameUUIDFromBytes (.getBytes ^String ontology-id "UTF-8"))
                         ontology-id)]
     (some->> (vals (rmp/project ctx :ontology/concepts))
              (filter #(and (= uri (:uri %))
                            (= normalized-id (:ontology-id %))))
              first
              (#(dissoc % ::projection-order))))))

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
