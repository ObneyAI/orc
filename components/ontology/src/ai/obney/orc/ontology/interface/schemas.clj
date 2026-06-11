(ns ai.obney.orc.ontology.interface.schemas
  "Ontology component schemas - defines commands, events, and queries for
   the event-sourced ontology system.

   Three-layer ontology system:
   - Failure Ontology: Why things go wrong (Hallucination, InstructionViolation, etc.)
   - Success Ontology: What makes things work (StructuralPatterns, InstructionPatterns)
   - Problem Domain: What types of problems exist (Classification, InformationRetrieval)"
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Shared Domain Schemas
;; =============================================================================

(def ontology-scope
  "Scope levels for ontology concepts. `:tree-class` is added (C-2d-1)
   for the hierarchical tree-class taxonomy — projects tree-fingerprint
   descriptions into the concepts graph via SKOS broader/narrower.
   `:behavioral-subtree` is added (R05a) for the behavioral retrieval
   dimension — reusable competencies (analysis / validation / research /
   etc.) that compose into structural tree-class shells via
   `behavior:composes-into` edges."
  [:enum :failure :success :problem :node-type :custom :tree-class
   :behavioral-subtree])

(def severity-level
  "Severity levels for failures"
  [:enum :low :medium :high :critical])

(def pattern-type
  "Types of patterns that can be learned"
  [:enum :search :instruction :execution :structural])

(def node-type
  "Node types that can learn patterns"
  [:enum :llm :repl-researcher :code :map-each :condition :llm-condition])

;; =============================================================================
;; Living Descriptions — shared shapes for C-2 description events
;; =============================================================================

(def principle-entry
  "An entry inside :strengths or :weaknesses on a description body.

   Principle-shaped at every confidence level: the entry carries an
   actionable :trait + a context guard (:good-when for strengths,
   :avoid-when for weaknesses) + actionable advice (:recommended-pattern
   for strengths, :recommended-alternative for weaknesses).

   Low-confidence entries remain principle-shaped — never status-shaped
   (no 'investigate' / 'observed' / 'unclear' as the entry's substance).
   Confidence carries the weight signal; content carries actionability."
  [:map
   [:trait :string]
   ;; Strengths carry :good-when + :recommended-pattern.
   ;; Weaknesses carry :avoid-when + :recommended-alternative.
   ;; Either pair may be present; downstream consumers branch on which.
   [:good-when               {:optional true} :string]
   [:avoid-when              {:optional true} :string]
   [:recommended-pattern     {:optional true} :string]
   [:recommended-alternative {:optional true} :string]
   [:confidence              :double]
   [:evidence-count          :int]
   [:first-observed-at       {:optional true} :string]
   [:last-reinforced-at      {:optional true} :string]])

(def description-body
  "The shared body shape across all three description-updated event types.

   The :summary field is the canonical free-text representation that
   downstream ColBERT indexing embeds for semantic retrieval. The
   structured fields (:capabilities, :strengths, :weaknesses, etc.)
   power principle-shaped rendering for direct prompt injection.

   The OPTIONAL :parent-tree-id (added in C-2d-1) carries the
   tree-class parent in the SKOS broader/narrower hierarchy. The raw
   target-id form (UUID or string fingerprint) is stored; the reactive
   processor `on-tree-description-updated-project-concept` translates
   to the `tree-class:<id>` URI when projecting into the concepts
   graph. Only meaningful for :tree-fingerprint descriptions; other
   granularities ignore it."
  [:map
   [:capabilities              [:vector :string]]
   [:strengths                 [:vector principle-entry]]
   [:weaknesses                [:vector principle-entry]]
   [:representative-uses       [:vector :string]]
   [:avoid-when                [:vector :string]]
   [:summary                   :string]
   [:version                   :int]
   [:consolidated-from-event-count :int]
   [:parent-tree-id            {:optional true} [:or :uuid :string]]
   ;; R05a additions — behavioral subtree layer (C-2e foundation):
   ;; `:scope` declares which retrieval dimension this description
   ;; lives in. Absent or `:tree-class` keeps today's structural
   ;; behavior. `:behavioral-subtree` routes the event to the new
   ;; R05a reactive processor that projects under behavioral-subtree:<id>.
   ;; `:composes-into` (only meaningful for behavioral-subtree scope)
   ;; declares the structural tree-class shells the behavior commonly
   ;; composes into; the processor emits behavior:composes-into edges.
   ;; `:parent-behavior` is the SKOS broader axis WITHIN Layer 2 (nil
   ;; for top-level behaviors). Raw target-id form is stored; the
   ;; processor translates to the `behavioral-subtree:<id>` URI.
   [:scope                     {:optional true} ontology-scope]
   [:composes-into             {:optional true} [:vector [:or :uuid :string]]]
   [:parent-behavior           {:optional true} [:or :uuid :string]]
   ;; R05d — consolidator-inferred behavioral subtree IDs.
   ;; When the consolidator processes a first-time tree-fingerprint
   ;; description, it calls classify-behaviors (with the tree-class id
   ;; as :structural-context) and stamps the top-N above-threshold
   ;; behavior IDs here. Sticky on subsequent consolidations. Absent
   ;; for non-tree-fingerprint granularities and on the orphan path
   ;; (classify-behaviors returned the fresh-mint marker).
   [:behavioral-subtree-ids    {:optional true} [:vector [:or :uuid :string]]]])

(def node-instance-target
  "Identity tuple for a node-instance description target —
   [sheet-id node-id]."
  [:tuple :uuid :uuid])

;; =============================================================================
;; C-2b-2 — Reranker output shape
;; =============================================================================

(def reranked-result
  "One entry in the C-2b-2 reranker's :reranked-results output vector.

   The reranker is delta-only: it returns just the (document-id,
   reasoning, fitness-score) triple. The full candidate (content,
   ColBERT score, document-metadata) is JOINED back in search-descriptions
   via :document-id, keeping the structured-output prompt small.

   :fitness-score is an absolute [0.0, 1.0] interpretation per the
   reranker instruction (1.0 = perfect fit for the caller's intent,
   0.0 = irrelevant). Kept separate from ColBERT's raw similarity
   :score so downstream consumers can see both signals.

   `:number` (not `:double`) so that JSON-parsed integers (e.g. `1`
   or `0`) round-trip correctly through the LLM's structured output."
  [:map
   [:document-id   :string]
   [:reasoning     :string]
   [:fitness-score [:and number? [:>= 0.0] [:<= 1.0]]]])

(def reranked-results
  "Vector of reranked-result entries, descending by :fitness-score
   per the reranker's instruction."
  [:vector reranked-result])

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {;; -------------------------------------------------------------------------
   ;; Ontology Lifecycle Events
   ;; -------------------------------------------------------------------------

   :ontology/ontology-created
   [:map
    [:ontology-id :uuid]
    [:name :string]
    [:scope ontology-scope]
    [:description {:optional true} :string]
    [:base-uri {:optional true} :string]
    [:created-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Concept Events
   ;; -------------------------------------------------------------------------

   :ontology/concept-created
   [:map
    [:ontology-id :uuid]
    [:concept-id :uuid]
    [:uri :string]                        ;; e.g., "failure:Hallucination"
    [:label :string]
    [:description :string]
    [:scope ontology-scope]
    [:broader {:optional true} [:vector :string]]  ;; Parent URIs
    [:indicators {:optional true} [:vector :string]]  ;; Text patterns
    [:created-at :string]]

   :ontology/concept-updated
   [:map
    [:concept-id :uuid]
    [:changes [:map-of :keyword :any]]
    [:updated-at :string]]

   :ontology/relationship-created
   [:map
    [:relationship-id :uuid]
    [:source-uri :string]
    [:target-uri :string]
    [:predicate :string]                  ;; "skos:broader", "skos:related", "owl:causes"
    [:properties {:optional true} [:map-of :keyword :any]]
    [:created-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Tree Profile Events
   ;; -------------------------------------------------------------------------

   :ontology/tree-strength-recorded
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]                ;; e.g., "success:MultiSourceGathering"
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    [:recorded-at :string]
    ;; Domain-agnostic rich context fields for self-learning
    [:context-conditions {:optional true} [:map-of :keyword :any]]
    [:state-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:action-taken {:optional true} [:map
                                     [:type {:optional true} :string]
                                     [:target {:optional true} :any]
                                     [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]
    [:expected-outcome {:optional true} :string]]

   :ontology/tree-weakness-recorded
   [:map
    [:tree-id :uuid]
    [:failure-uri :string]                ;; e.g., "failure:Hallucination"
    [:subtype-uri {:optional true} :string]
    [:frequency :double]
    [:severity severity-level]
    [:triggers [:vector :string]]
    [:evidence-trace-ids [:vector :uuid]]
    [:recorded-at :string]
    ;; Domain-agnostic rich context fields for self-learning
    [:failure-context {:optional true} [:map-of :keyword :any]]
    [:failure-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:attempted-action {:optional true} [:map
                                         [:type {:optional true} :string]
                                         [:target {:optional true} :any]
                                         [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]]

   :ontology/tree-problem-mapping-created
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]                ;; e.g., "problem:Classification"
    [:success-rate :double]
    [:execution-count :int]
    [:recorded-at :string]]

   :ontology/tree-problem-mapping-updated
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]
    [:success-rate :double]
    [:execution-count :int]
    [:updated-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Learned Rules Events (Self-Learning)
   ;; -------------------------------------------------------------------------

   :ontology/learned-rule-extracted
   [:map
    [:rule-id :uuid]
    [:tree-id :uuid]
    [:rule [:map
            [:condition [:map-of :keyword :any]]
            [:action [:map-of :keyword :any]]
            [:confidence :double]
            [:success-rate :double]
            [:evidence-episodes [:vector :uuid]]]]
    [:problem-type :string]
    [:domain-type {:optional true} :string]
    [:extracted-at :string]]

   :ontology/domain-knowledge-added
   [:map
    [:knowledge-id :uuid]
    [:tree-id :uuid]
    [:node-id {:optional true} :uuid]
    [:description :string]
    [:based-on-failure-traces [:vector :uuid]]
    [:impact-score {:optional true} :double]
    [:added-at :string]]

   ;; -------------------------------------------------------------------------
   ;; C-2 Living Description Events
   ;; -------------------------------------------------------------------------
   ;; Three event types — one per granularity. Each carries the same
   ;; description-body (capabilities, strengths, weaknesses, etc.) but uses
   ;; a granularity-specific target-id type (keyword for node-type,
   ;; [sheet-id node-id] tuple for node-instance, string-hash for tree-fingerprint).
   ;; Append-only; the read-model maintains "current" + "history" per target.

   :ontology/node-type-description-updated
   [:map
    [:target-type [:= :node-type]]
    [:target-id :keyword]                  ;; e.g. :llm, :map-each
    [:body description-body]
    [:recorded-at :string]]

   :ontology/node-instance-description-updated
   [:map
    [:target-type [:= :node-instance]]
    [:target-id node-instance-target]      ;; [sheet-id node-id]
    [:body description-body]
    [:recorded-at :string]]

   :ontology/tree-description-updated
   [:map
    ;; C-Loop-1: `:tree-class` joins `:tree-fingerprint` as a valid
    ;; target-type. Tree-class descriptions are the substrate R-Inject's
    ;; classifier reads — one per assigned tree-class — and are the
    ;; consolidator's update target on the Living Description loop.
    ;; Tree-fingerprint descriptions stay for per-shape cross-sheet
    ;; metrics; they no longer drive R-Inject's prepend.
    [:target-type [:enum :tree-fingerprint :tree-class]]
    ;; Either a SHA hash of canonical tree-raw (production fingerprinting)
    ;; OR a task-class UUID (matches the seed_principles.clj task-class
    ;; identity that C-1 already uses). Both are stable abstract keys the
    ;; model retrieves descriptions against.
    [:target-id [:or :string :uuid]]
    [:body description-body]
    [:recorded-at :string]]

   ;; -------------------------------------------------------------------------
   ;; C-2c-1 — Auto-classifier event
   ;; -------------------------------------------------------------------------
   ;;
   ;; When the C-2c auto-classifier assigns a tree-class to a repl-researcher
   ;; node at first-tick (and dispatches :ontology/assign-task-class), the
   ;; defcommand handler emits this event. The body carries the full
   ;; decision audit trail so downstream consumers can:
   ;;   - replay classifications when the classifier improves
   ;;   - dashboard low-confidence routes
   ;;   - retroactively re-classify when the corpus grows
   ;;
   ;; :was-fresh-mint? distinguishes a confident-match (false; :assigned-tree-id
   ;; matched a corpus entry above threshold) from a novel-task mint (true;
   ;; :assigned-tree-id is a fresh UUID because no candidate passed threshold).

   :ontology/task-classified
   [:map
    [:source-sheet-id   :uuid]
    [:source-tick-id    :uuid]
    [:source-node-id    :uuid]
    [:assigned-tree-id  :uuid]
    [:confidence        [:and number? [:>= 0.0] [:<= 1.0]]]
    [:top-candidates    [:vector :map]]
    [:reasoning         :string]
    [:classified-at     :string]
    [:was-fresh-mint?   :boolean]
    ;; C-2d-2: when the walk-down classifier descends from an abstract
    ;; parent OR fresh-mints under a matched ancestor, the parent's
    ;; tree-id is carried here so the concept-graph projector can wire
    ;; the new tree-class as a child of the parent.
    [:parent-tree-id    {:optional true} [:or :uuid :string]]
    ;; R01: true when the LLM reranker failed (workflow error, JSON
    ;; parse error, all entries dropped, or thrown exception) and the
    ;; classifier saw the pure-ColBERT fallback ordering with no
    ;; :fitness-score / :reasoning. The :assigned-tree-id in this case
    ;; is fresh-mint at root via the not-matched path — but driven by
    ;; reranker failure, not legitimate low confidence. Operators
    ;; monitoring this field can distinguish the two cases.
    [:rerank-failed?    {:optional true} :boolean]
    ;; R05a: optional behavioral-subtree classification result. Each
    ;; entry carries a behavior-id, confidence, optional was-fresh-mint?,
    ;; reasoning, and rerank-source. Populated by R05b's classify-behaviors
    ;; via the wedge; absent on legacy events and on first-tick classify
    ;; calls that opt out of behavioral classification.
    [:behavioral-subtrees {:optional true} [:vector :map]]]

   ;; -------------------------------------------------------------------------
   ;; R05c — Behavioral subtree minting (audit-trail event)
   ;; -------------------------------------------------------------------------
   ;;
   ;; Emitted alongside :ontology/tree-description-updated by the
   ;; :ontology/mint-behavioral-subtree defcommand. Carries provenance
   ;; (agent-minted vs human-authored) so a future C-3 review queue can
   ;; filter on agent-authored content.
   ;;
   ;; :provenance is MANDATORY (no default) — load-bearing for the audit
   ;; trail; mixing the two provenance classes makes the review queue
   ;; meaningless.

   :ontology/behavioral-subtree-minted
   [:map
    [:target-id          :uuid]
    [:name               :string]
    [:parent-behavior    {:optional true} [:or :uuid :string]]
    [:provenance         [:enum :agent-minted :human-authored]]
    [:minted-by-sheet-id {:optional true} :uuid]
    [:minted-by-tick-id  {:optional true} :uuid]
    [:minted-at          :string]]

   ;; -------------------------------------------------------------------------
   ;; Gap-6 — Anti-recency runtime audit events
   ;; -------------------------------------------------------------------------
   ;;
   ;; Emitted by the consolidator's post-LLM validator when a protected
   ;; entry (prior :confidence >= threshold AND :evidence-count >=
   ;; threshold) is at risk in the LLM's output. :anti-recency-rejection
   ;; fires when the entry is missing entirely from the LLM body —
   ;; emission of the new description is blocked. :anti-recency-clamp-
   ;; applied fires when the LLM dropped the entry's confidence by more
   ;; than the configured max — the new body still emits but with the
   ;; clamped confidence value.
   ;;
   ;; Operators can audit these events to see whether the validator is
   ;; intervening (LLM regressions caught) or quiet (LLM compliance).

   :ontology/anti-recency-rejection
   [:map
    [:target-type     [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id       :any]
    [:bucket          [:enum :strengths :weaknesses]]
    [:entry-trait     :string]
    [:prior-confidence number?]
    [:prior-evidence-count :int]
    [:reason          :keyword]
    [:rejected-body   :map]
    [:detected-at     :string]]

   :ontology/anti-recency-clamp-applied
   [:map
    [:target-type     [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id       :any]
    [:bucket          [:enum :strengths :weaknesses]]
    [:entry-trait     :string]
    [:prior-confidence number?]
    [:llm-confidence  number?]
    [:clamped-confidence number?]
    [:reason          :keyword]
    [:detected-at     :string]]

   ;; -------------------------------------------------------------------------
   ;; C-2a-3a — Consolidation trigger events
   ;; -------------------------------------------------------------------------
   ;;
   ;; The Living Description loop fires an :ontology/consolidation-requested
   ;; event when a target has accumulated enough new evidence (default 10
   ;; events) since its last consolidation, OR on-demand via the manual
   ;; REPL command path. The consolidator processor (C-2a-3b) subscribes to
   ;; this event and runs the LLM reflection step.

   :ontology/consolidation-requested
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    ;; Granularity-specific target-id shape (mirrors the description events):
    ;; - :node-type → keyword (e.g. :llm)
    ;; - :node-instance → [sheet-id node-id] tuple of UUIDs
    ;; - :tree-fingerprint → string OR UUID (production hash or task-class UUID)
    ;; - :tree-class → UUID of an assigned tree-class (the substrate
    ;;   R-Inject's classifier reads via get-description)
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    [:on-demand? :boolean]
    [:requested-at :string]]

   :ontology/consolidation-threshold-set
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:threshold :int]
    [:set-at :string]]

   ;; Gap-1: system-level opt-in to the Living Description loop. Gates the
   ;; WRITING side — consolidator activity, threshold-tracking event
   ;; emission, per-event evaluator runtime (judges). Default off when no
   ;; event has been emitted. Forward-compatible for C-3 judge-feedback
   ;; integration and C-Loop-2 minting affordance.
   :ontology/living-description-enabled-set
   [:map
    [:enabled? :boolean]
    [:set-at :string]]

   :ontology/consolidation-budget-set
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:budget :int]
    [:set-at :string]]

   ;; -------------------------------------------------------------------------
   ;; C-2b-1 — Re-index config event
   ;; -------------------------------------------------------------------------
   ;;
   ;; Global re-index configuration for the ColBERT description corpus
   ;; (NOT per-target-type — re-indexing operates over the whole corpus).
   ;; The hybrid threshold-OR-timer trigger from the C-2b sub-grill's
   ;; Decision 2: fire on EITHER N events accumulated OR T minutes
   ;; elapsed since last rebuild, whichever first.

   :ontology/reindex-config-set
   [:map
    [:reindex-threshold-events :int]
    [:reindex-timer-minutes :int]
    [:set-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Node-Level Learning Events
   ;; -------------------------------------------------------------------------

   :ontology/node-pattern-learned
   [:map
    [:node-id :uuid]
    [:sheet-id :uuid]
    [:node-type node-type]
    [:pattern-type pattern-type]
    [:effective? :boolean]
    [:pattern-description :string]
    [:metrics [:map
               [:success-rate {:optional true} :double]
               [:avg-score {:optional true} :double]
               [:failure-rate {:optional true} :double]]]
    [:evidence-trace-ids [:vector :uuid]]
    [:learned-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Discovery Events
   ;; -------------------------------------------------------------------------

   :ontology/failure-subtype-discovered
   [:map
    [:discovery-id :uuid]
    [:parent-uri :string]                 ;; Existing broader concept
    [:proposed-uri :string]
    [:label :string]
    [:description :string]
    [:evidence-count :int]
    [:discovered-at :string]
    [:status [:enum :proposed :approved :rejected]]]

   ;; -------------------------------------------------------------------------
   ;; Embedding Events (Phase 4)
   ;; -------------------------------------------------------------------------

   :ontology/embedding-model-configured
   [:map
    [:ontology-id :uuid]
    [:scope [:enum :failure :success :problem :node-type :custom :all]]
    [:model-id :string]                   ;; "sentence-transformers/all-MiniLM-L6-v2"
    [:dimensions :int]                    ;; 384
    [:configured-at :string]]

   :ontology/concept-embedded
   [:map
    [:uri :string]                        ;; "failure:Hallucination" or "onet:11-1011.00"
    [:ontology-id {:optional true} :uuid] ;; Links to evolutionary ontology
    [:concept-id {:optional true} :uuid]  ;; Legacy individual concept ID
    [:text-embedded :string]              ;; Source text that was embedded
    [:field-source :string]               ;; "label+description" or "triggers"
    [:embedding [:vector :double]]        ;; 384-dim vector (MUST be :double, not Float)
    [:model-id :string]
    [:embedded-at :string]]

   :ontology/tree-profile-embedded
   [:map
    [:tree-id :uuid]
    [:text-embedded :string]              ;; Serialized profile summary
    [:embedding [:vector :double]]
    [:model-id :string]
    [:embedded-at :string]]

   :ontology/evaluation-embedded
   [:map
    [:trace-id :uuid]
    [:dimension :string]                  ;; "Grounding", "Reasoning", etc.
    [:feedback :string]                   ;; Original feedback text
    [:embedding [:vector :double]]
    [:failure-uri {:optional true} :string]  ;; Classified failure
    [:model-id :string]
    [:embedded-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Site Registry Events (Generic Site Pattern Learning)
   ;; -------------------------------------------------------------------------

   :site/registered
   [:map
    [:site-id :uuid]
    [:domain :string]                     ;; "redfin.com"
    [:display-name :string]               ;; "Redfin"
    [:category [:enum :corporate :peer-to-peer :aggregator :local]]
    [:discovered-via [:enum :manual :web-search :referral]]
    [:url-pattern {:optional true} :string]  ;; "https://www.{domain}/{location}/rentals/"
    [:requires-headed {:optional true} :boolean]
    [:known-challenges {:optional true} [:vector :string]]  ;; ["press-hold", "popup"]
    [:notes {:optional true} :string]
    [:registered-at :string]]

   :site/trust-updated
   [:map
    [:site-id :uuid]
    [:domain :string]
    [:trust-score :double]                ;; 0.0-1.0
    [:extraction-count :int]              ;; How many successful extractions
    [:last-success-at {:optional true} :string]
    [:last-failure-at {:optional true} :string]
    [:updated-at :string]]

   :site/pattern-learned
   [:map
    [:site-id :uuid]
    [:domain :string]
    [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
    [:pattern-data [:map-of :keyword :any]]  ;; Site-specific tactics
    [:confidence :double]
    [:learned-at :string]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {:ontology/create-ontology
   [:map
    [:name :string]
    [:scope ontology-scope]
    [:description {:optional true} :string]
    [:base-uri {:optional true} :string]]

   :ontology/create-concept
   [:map
    [:ontology-id :uuid]
    [:uri :string]
    [:label :string]
    [:description :string]
    [:scope ontology-scope]
    [:broader {:optional true} [:vector :string]]
    [:indicators {:optional true} [:vector :string]]]

   :ontology/create-relationship
   [:map
    [:source-uri :string]
    [:target-uri :string]
    [:predicate :string]]

   :ontology/initialize-static-ontology
   [:map
    [:scope {:optional true} ontology-scope]]  ;; Optional: only initialize specific scope

   :ontology/record-tree-strength
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    ;; Domain-agnostic rich context fields
    [:context-conditions {:optional true} [:map-of :keyword :any]]
    [:state-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:action-taken {:optional true} [:map
                                     [:type {:optional true} :string]
                                     [:target {:optional true} :any]
                                     [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]
    [:expected-outcome {:optional true} :string]]

   :ontology/record-tree-weakness
   [:map
    [:tree-id :uuid]
    [:failure-uri :string]
    [:subtype-uri {:optional true} :string]
    [:frequency :double]
    [:severity severity-level]
    [:triggers [:vector :string]]
    [:evidence-trace-ids [:vector :uuid]]
    ;; Domain-agnostic rich context fields
    [:failure-context {:optional true} [:map-of :keyword :any]]
    [:failure-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:attempted-action {:optional true} [:map
                                         [:type {:optional true} :string]
                                         [:target {:optional true} :any]
                                         [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]]

   :ontology/record-problem-mapping
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]
    [:success-rate :double]
    [:execution-count :int]]

   :ontology/record-node-pattern
   [:map
    [:node-id :uuid]
    [:sheet-id :uuid]
    [:node-type node-type]
    [:pattern-type pattern-type]
    [:effective? :boolean]
    [:pattern-description :string]
    [:metrics [:map-of :keyword :double]]
    [:evidence-trace-ids [:vector :uuid]]]

   :ontology/add-domain-knowledge
   [:map
    [:tree-id :uuid]
    [:node-id {:optional true} :uuid]
    [:description :string]
    [:based-on-failure-traces [:vector :uuid]]
    [:impact-score {:optional true} :double]]

   ;; -------------------------------------------------------------------------
   ;; C-2 Living Description Commands
   ;; -------------------------------------------------------------------------
   ;; One command per granularity, matching the
   ;; record-tree-strength/record-tree-weakness idiom. Each emits the
   ;; corresponding *-description-updated event.

   :ontology/record-node-type-description
   [:map
    [:target-id :keyword]
    [:body description-body]]

   :ontology/record-node-instance-description
   [:map
    [:target-id node-instance-target]   ;; [sheet-id node-id]
    [:body description-body]]

   :ontology/record-tree-description
   [:map
    ;; Either a SHA hash or a task-class UUID — see the event schema
    ;; for the rationale.
    [:target-id [:or :string :uuid]]
    [:body description-body]]

   :ontology/record-tree-class-description
   [:map
    ;; C-Loop-1: tree-class id (stable seed UUID or fresh-mint root UUID
    ;; the classifier assigned). Distinct from :tree-fingerprint, which
    ;; keys on observed-tree SHA strings.
    [:target-id [:or :string :uuid]]
    [:body description-body]]

   ;; Gap-6: audit-trail commands for the anti-recency validator.
   ;; Dispatched by the consolidator processor when the validator
   ;; intervenes; emit :ontology/anti-recency-rejection or
   ;; :ontology/anti-recency-clamp-applied respectively. These exist
   ;; instead of direct es/append calls so the consolidator follows
   ;; the standard Grain pattern: events flow through command handlers,
   ;; never bypassing them.

   :ontology/record-anti-recency-rejection
   [:map
    [:target-type     [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id       :any]
    [:bucket          [:enum :strengths :weaknesses]]
    [:entry-trait     :string]
    [:prior-confidence number?]
    [:prior-evidence-count :int]
    [:reason          :keyword]
    [:rejected-body   :map]]

   :ontology/record-anti-recency-clamp
   [:map
    [:target-type     [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id       :any]
    [:bucket          [:enum :strengths :weaknesses]]
    [:entry-trait     :string]
    [:prior-confidence number?]
    [:llm-confidence  number?]
    [:clamped-confidence number?]
    [:reason          :keyword]]

   ;; -------------------------------------------------------------------------
   ;; C-2c-1 — Auto-classifier command
   ;; -------------------------------------------------------------------------
   ;;
   ;; Dispatched by the executor wedge at first-tick when a repl-researcher
   ;; with :auto-classify? true has no :context set. The handler (C-2c-2)
   ;; emits :ontology/task-classified.
   ;;
   ;; Mirrors the event body minus :classified-at (the handler stamps that).

   :ontology/assign-task-class
   [:map
    [:source-sheet-id   :uuid]
    [:source-tick-id    :uuid]
    [:source-node-id    :uuid]
    [:assigned-tree-id  :uuid]
    [:confidence        [:and number? [:>= 0.0] [:<= 1.0]]]
    [:top-candidates    [:vector :map]]
    [:reasoning         :string]
    [:was-fresh-mint?   :boolean]
    ;; C-2d-2: optional parent ancestor (UUID or fingerprint string)
    ;; surfaced by walk-down. The defcommand forwards this to the
    ;; emitted task-classified event body.
    [:parent-tree-id    {:optional true} [:or :uuid :string]]
    ;; R01: reranker failure flag forwarded by the executor wedge.
    ;; The defcommand carries this through to the emitted event body
    ;; when present.
    [:rerank-failed?    {:optional true} :boolean]
    ;; R05a: behavioral-subtree classification result forwarded by the
    ;; wedge (set by R05b's classify-behaviors call). The defcommand
    ;; carries this through to the emitted task-classified event body.
    [:behavioral-subtrees {:optional true} [:vector :map]]]

   ;; -------------------------------------------------------------------------
   ;; R05c — Mint a new behavioral-subtree concept
   ;; -------------------------------------------------------------------------
   ;;
   ;; The recursive RLM researcher's affordance for contributing new
   ;; behavioral abstractions to the corpus when no candidate from
   ;; classify-behaviors fits. Hand-authored mints go through the same
   ;; defcommand with :provenance :human-authored.
   ;;
   ;; The handler generates a fresh UUID for the new behavior's target-id
   ;; and emits TWO events:
   ;;   1. :ontology/behavioral-subtree-minted — the provenance-tagged
   ;;      audit-trail event
   ;;   2. :ontology/tree-description-updated — so the R05a reactive
   ;;      processor projects the concept (and any :composes-into edges
   ;;      / :parent-behavior skos:broader link) into :ontology/concepts.
   ;;
   ;; :scope :behavioral-subtree is stamped by the handler. Callers may
   ;; omit it from :body; if they explicitly pass a non-:behavioral-subtree
   ;; scope the defcommand rejects to surface the intent mismatch.

   :ontology/mint-behavioral-subtree
   [:map
    [:name               :string]
    [:body               description-body]
    [:parent-behavior    {:optional true} [:or :uuid :string]]
    ;; :provenance is MANDATORY — never default. Mixing the two
    ;; provenance classes would break the audit trail for future review.
    [:provenance         [:enum :agent-minted :human-authored]]
    ;; Sandbox-only fields; absent on hand-authored mints. The sandbox
    ;; primitive (mint-behavior! ...) populates these from the
    ;; build-rlm-context's :sheet-id / :tick-id opts.
    [:minted-by-sheet-id {:optional true} :uuid]
    [:minted-by-tick-id  {:optional true} :uuid]]

   ;; -------------------------------------------------------------------------
   ;; C-2a-3a — Consolidation trigger commands
   ;; -------------------------------------------------------------------------

   :ontology/request-consolidation
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    ;; Defaults to true when invoked through the REPL helper; the
    ;; threshold-tracking processor emits with :on-demand? false.
    [:on-demand? {:optional true} :boolean]]

   :ontology/set-consolidation-threshold
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:threshold :int]]

   :ontology/set-consolidation-budget
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:budget :int]]

   ;; Gap-1: opt-in flag command (see :ontology/living-description-enabled-set
   ;; event for rationale).
   :ontology/set-living-description-enabled
   [:map
    [:enabled? :boolean]]

   :ontology/set-reindex-config
   [:map
    [:reindex-threshold-events :int]
    [:reindex-timer-minutes :int]]

   :ontology/extract-learned-rules
   [:map
    [:tree-id :uuid]
    [:problem-type :string]
    [:min-episodes {:optional true} :int]
    [:domain-type {:optional true} :string]
    [:domain-description {:optional true} :string]]

   :ontology/classify-evaluation
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:evaluation-result [:map
                          [:score :double]
                          [:dimensions [:vector [:map
                                                 [:name :string]
                                                 [:score :double]
                                                 [:feedback :string]]]]]]
    [:auto-record? {:optional true} :boolean]]

   ;; Embedding Commands (Phase 4)

   :ontology/configure-embedding-model
   [:map
    [:scope [:enum :failure :success :problem :node-type :custom :all]]
    [:model-id :string]                   ;; "sentence-transformers/all-MiniLM-L6-v2"
    [:dimensions {:optional true} :int]]  ;; Auto-detected if not provided

   :ontology/embed-concept
   [:map
    [:uri :string]
    [:fields {:optional true} [:set [:enum :label :description :indicators :triggers]]]]

   :ontology/embed-concepts-batch
   [:map
    [:scope {:optional true} ontology-scope]
    [:uris {:optional true} [:vector :string]]  ;; Specific URIs, or all in scope
    [:fields {:optional true} [:set [:enum :label :description :indicators :triggers]]]]

   :ontology/embed-tree-profile
   [:map
    [:tree-id :uuid]]

   :ontology/embed-evaluation-feedback
   [:map
    [:trace-id :uuid]
    [:dimension :string]
    [:feedback :string]
    [:failure-uri {:optional true} :string]]

   ;; Discovery Commands

   :ontology/run-pattern-discovery
   [:map
    [:sheet-id :uuid]
    [:min-traces {:optional true} :int]
    [:score-threshold {:optional true} :double]]

   ;; -------------------------------------------------------------------------
   ;; Evolutionary Builder Commands (CQRS wrappers)
   ;; -------------------------------------------------------------------------

   :ontology/build-from-sources
   [:map
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type [:enum "csv" "sql" "text" "rdf" "json"]]]]]
    [:config {:optional true} [:map
                               [:base-uri {:optional true} :string]
                               [:similarity-threshold {:optional true} :double]
                               [:emit-owl-sameAs? {:optional true} :boolean]]]]

   :ontology/evolve
   [:map
    [:ontology-id :uuid]
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type [:enum "csv" "sql" "text" "rdf" "json"]]]]]
    [:config {:optional true} [:map
                               [:prefer-existing-uris? {:optional true} :boolean]
                               [:similarity-threshold {:optional true} :double]]]]

   :ontology/record-colbert-index
   [:map
    [:ontology-id :uuid]
    [:index-id :uuid]
    [:index-name :string]
    [:document-count :int]
    [:colbert-fields [:vector :keyword]]]

   ;; -------------------------------------------------------------------------
   ;; Apartment Search Commands
   ;; -------------------------------------------------------------------------

   ;; -------------------------------------------------------------------------
   ;; Site Registry Commands (Generic Site Pattern Learning)
   ;; -------------------------------------------------------------------------

   :site/register-site
   [:map
    [:domain :string]
    [:display-name :string]
    [:category [:enum :corporate :peer-to-peer :aggregator :local]]
    [:discovered-via [:enum :manual :web-search :referral]]
    [:url-pattern {:optional true} :string]
    [:requires-headed {:optional true} :boolean]
    [:known-challenges {:optional true} [:vector :string]]
    [:notes {:optional true} :string]]

   :site/update-site-trust
   [:map
    [:domain :string]
    [:success? :boolean]                  ;; true = successful extraction, false = failure
    [:listings-extracted {:optional true} :int]]

   :site/record-site-pattern
   [:map
    [:domain :string]
    [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
    [:pattern-data [:map-of :keyword :any]]
    [:confidence {:optional true} :double]]})

;; =============================================================================
;; Query Schemas
;; =============================================================================

(defschemas queries
  {:ontology/get-concepts
   [:map
    [:scope {:optional true} ontology-scope]
    [:broader-uri {:optional true} :string]
    [:include-narrower? {:optional true} :boolean]]

   :ontology/get-concept
   [:map
    [:uri :string]]

   :ontology/get-tree-profile
   [:map
    [:tree-id :uuid]]

   :ontology/find-similar-trees
   [:map
    [:problem-type :string]
    [:required-patterns {:optional true} [:set :string]]
    [:min-success-rate {:optional true} :double]
    [:limit {:optional true} :int]]

   :ontology/find-failure-patterns
   [:map
    [:problem-type :string]
    [:min-frequency {:optional true} :double]]

   :ontology/get-node-type-learnings
   [:map
    [:node-type node-type]]

   :ontology/export-ttl
   [:map
    [:scope {:optional true} ontology-scope]
    [:include-instances? {:optional true} :boolean]
    [:base-uri {:optional true} :string]]

   :ontology/build-context
   [:map
    [:problem-type :string]
    [:required-patterns {:optional true} [:set :string]]]

   ;; Embedding Queries (Phase 4)

   :ontology/semantic-search
   [:map
    [:query :string]
    [:scope {:optional true} ontology-scope]
    [:limit {:optional true} :int]
    [:min-similarity {:optional true} :double]]

   :ontology/hybrid-search
   [:map
    [:query :string]
    [:seed-uris {:optional true} [:vector :string]]
    [:scope {:optional true} ontology-scope]
    [:limit {:optional true} :int]]

   :ontology/get-concept-embedding
   [:map
    [:uri :string]]

   ;; -------------------------------------------------------------------------
   ;; Site Registry Queries (Generic Site Pattern Learning)
   ;; -------------------------------------------------------------------------

   :site/get-site
   [:map
    [:domain :string]]

   :site/get-trusted-sites
   [:map
    [:min-trust {:optional true} :double]  ;; Default 0.5
    [:limit {:optional true} :int]]

   :site/get-site-patterns
   [:map
    [:domain :string]
    [:pattern-type {:optional true} [:enum :navigation :search :extraction :bot-bypass :pagination]]]})

;; =============================================================================
;; Evolutionary Ontology Builder - Shared Domain Schemas
;; =============================================================================

(def source-type
  "Types of data sources for evolutionary ontology building"
  [:enum "csv" "sql" "text" "rdf" "json"])

(def match-type
  "Types of entity matches during resolution"
  [:enum "exact" "semantic"])

(def resolution-mode
  "Modes for entity resolution"
  [:enum "batch" "incremental"])

(def schema-element-type
  "Types of schema elements that can be extended"
  [:enum "class" "object-property" "datatype-property"])

(def ttl-format
  "Serialization formats for ontology snapshots"
  [:enum "turtle" "rdf-xml" "json-ld"])

;; =============================================================================
;; Evolutionary Ontology Builder - Event Schemas
;; =============================================================================

(defschemas evolutionary-events
  {;; -------------------------------------------------------------------------
   ;; Source Registry Events
   ;; -------------------------------------------------------------------------

   :evolutionary/source-registered
   [:map
    [:source-id :uuid]
    [:source-uri :string]
    [:source-type source-type]
    [:content-hash :string]                  ;; SHA-256
    [:file-size :int]
    [:namespace :string]
    [:metadata {:optional true} [:map-of :keyword :any]]
    [:registered-at :string]]

   :evolutionary/source-stats-updated
   [:map
    [:source-id :uuid]
    [:concepts-extracted :int]
    [:triples-generated :int]
    [:entities-resolved :int]
    [:updated-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Concept Extraction Events
   ;; -------------------------------------------------------------------------

   :evolutionary/concepts-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:concepts [:vector [:map
                         [:uri :string]
                         [:label :string]
                         [:definition {:optional true} :string]
                         [:entity-type :string]
                         [:alt-labels {:optional true} [:vector :string]]
                         [:source-id {:optional true} :uuid]
                         [:confidence {:optional true} :double]]]]
    [:extracted-at :string]]

   :evolutionary/relationships-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:relationships [:vector [:map
                              [:subject :string]
                              [:predicate :string]
                              [:object :string]
                              [:confidence {:optional true} :double]]]]
    [:extracted-at :string]]

   :evolutionary/schema-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:classes [:vector [:map
                        [:uri :string]
                        [:label :string]]]]
    [:object-properties [:vector [:map
                                  [:uri :string]
                                  [:domain :string]
                                  [:range :string]]]]
    [:datatype-properties [:vector [:map
                                    [:uri :string]
                                    [:domain :string]
                                    [:datatype :string]]]]
    [:extracted-at :string]]

   ;; -------------------------------------------------------------------------
   ;; T-box/A-box Events (OWL Schema + Individuals)
   ;; -------------------------------------------------------------------------

   :evolutionary/tbox-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:classes [:vector [:map
                        [:uri :string]
                        [:label {:optional true} :string]
                        [:description {:optional true} :string]]]]
    [:object-properties [:vector [:map
                                  [:uri :string]
                                  [:label {:optional true} :string]
                                  [:domain {:optional true} :string]
                                  [:range {:optional true} :string]]]]
    [:datatype-properties [:vector [:map
                                    [:uri :string]
                                    [:label {:optional true} :string]
                                    [:domain {:optional true} :string]
                                    [:datatype {:optional true} :string]]]]
    [:extracted-at :string]]

   :evolutionary/abox-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:individuals [:vector [:map
                            [:uri :string]
                            [:type :string]
                            [:label :string]
                            [:properties {:optional true} [:map-of [:or :keyword :string] :any]]]]]
    [:extracted-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Entity Resolution Events
   ;; -------------------------------------------------------------------------

   :evolutionary/entities-resolved
   [:map
    [:ontology-id :uuid]
    [:resolution-mode resolution-mode]
    [:matches [:vector [:map
                        [:source1-uri :string]
                        [:source2-uri :string]
                        [:similarity-score :double]
                        [:match-type match-type]]]]
    [:canonical-map [:map-of :string :string]]
    [:alignment-triples [:vector [:tuple :string :string :string]]]
    [:exact-matches :int]
    [:semantic-matches :int]
    [:resolved-at :string]]

   :evolutionary/canonical-uri-assigned
   [:map
    [:original-uri :string]
    [:canonical-uri :string]
    [:reason :string]
    [:assigned-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Graph Evolution Events
   ;; -------------------------------------------------------------------------

   :evolutionary/graph-merged
   [:map
    [:ontology-id :uuid]
    [:source-ids [:vector :uuid]]
    [:triples-before :int]
    [:triples-after :int]
    [:concepts-added :int]
    [:concepts-merged :int]
    [:merged-at :string]]

   :evolutionary/schema-extended
   [:map
    [:ontology-id :uuid]
    [:extensions [:vector [:map
                           [:uri :string]
                           [:element-type schema-element-type]
                           [:label :string]
                           [:source-id :uuid]]]]
    [:extended-at :string]]

   :evolutionary/ttl-snapshot-created
   [:map
    [:ontology-id :uuid]
    [:snapshot-id :uuid]
    [:format ttl-format]
    [:triple-count :int]
    [:checksum :string]
    [:created-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Build Orchestration Events
   ;; -------------------------------------------------------------------------

   :evolutionary/build-started
   [:map
    [:build-id :uuid]
    [:ontology-id :uuid]
    [:mode resolution-mode]
    [:source-count :int]
    [:config [:map-of :keyword :any]]
    [:started-at :string]]

   :evolutionary/build-completed
   [:map
    [:build-id :uuid]
    [:ontology-id :uuid]
    [:total-sources :int]
    [:total-concepts :int]
    [:total-triples :int]
    [:entities-resolved :int]
    [:duration-ms :int]
    [:completed-at :string]]

   :evolutionary/build-failed
   [:map
    [:build-id :uuid]
    [:ontology-id :uuid]
    [:error :string]
    [:failed-at-stage :string]
    [:failed-at :string]]

   ;; -------------------------------------------------------------------------
   ;; ColBERT Integration Events
   ;; -------------------------------------------------------------------------

   :evolutionary/colbert-indexed
   [:map
    [:ontology-id :uuid]
    [:index-id :uuid]
    [:index-name :string]
    [:colbert-fields [:vector :keyword]]
    [:document-count :int]
    [:indexed-at :string]]

   :evolutionary/colbert-index-updated
   [:map
    [:ontology-id :uuid]
    [:index-id :uuid]
    [:added-document-count {:optional true} :int]
    [:updated-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Embedding Integration Events (for RRF hybrid search)
   ;; -------------------------------------------------------------------------

   :evolutionary/concepts-embedded
   [:map
    [:ontology-id :uuid]
    [:build-id :uuid]
    [:embedded-count :int]
    [:embedding-fields [:vector :keyword]]
    [:model-id :string]
    [:embedded-at :string]]

   :evolutionary/concepts-embedding-updated
   [:map
    [:ontology-id :uuid]
    [:new-embedded-count :int]
    [:total-embedded-count :int]
    [:embedding-fields [:vector :keyword]]
    [:updated-at :string]]})

;; =============================================================================
;; Evolutionary Ontology Builder - Command Schemas
;; =============================================================================

(defschemas evolutionary-commands
  {;; -------------------------------------------------------------------------
   ;; Source Management Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/register-source
   [:map
    [:source-uri :string]
    [:source-type source-type]
    [:content {:optional true} :string]       ;; For text/json sources
    [:namespace {:optional true} :string]
    [:metadata {:optional true} [:map-of :keyword :any]]]

   :evolutionary/check-source-processed
   [:map
    [:source-uri {:optional true} :string]
    [:content {:optional true} :string]]      ;; Compute hash from content

   ;; -------------------------------------------------------------------------
   ;; Extraction Pipeline Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/extract-from-csv
   [:map
    [:source-id :string]
    [:csv-path :string]
    [:config {:optional true} [:map
                               [:entity-column {:optional true} :string]
                               [:entity-type {:optional true} :string]
                               [:description-columns {:optional true} [:vector :string]]
                               [:relationship-columns {:optional true} [:vector :string]]]]]

   :evolutionary/extract-from-text
   [:map
    [:source-id :string]
    [:text :string]
    [:config {:optional true} [:map
                               [:domain {:optional true} :string]
                               [:extract-causal? {:optional true} :boolean]
                               [:max-depth {:optional true} :int]]]]

   :evolutionary/extract-from-sql
   [:map
    [:source-id :string]
    [:db-config [:map
                 [:host :string]
                 [:port :int]
                 [:database :string]
                 [:user {:optional true} :string]
                 [:password {:optional true} :string]]]
    [:config {:optional true} [:map
                               [:include-tables {:optional true} [:vector :string]]
                               [:exclude-tables {:optional true} [:vector :string]]
                               [:include-fks? {:optional true} :boolean]]]]

   ;; -------------------------------------------------------------------------
   ;; Entity Resolution Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/resolve-entities-batch
   [:map
    [:ontology-id :uuid]
    [:source-ids [:vector :string]]
    [:config {:optional true} [:map
                               [:similarity-threshold {:optional true} :double]
                               [:emit-owl-sameAs? {:optional true} :boolean]
                               [:use-type-blocking? {:optional true} :boolean]]]]

   :evolutionary/resolve-entities-incremental
   [:map
    [:ontology-id :uuid]
    [:new-source-ids [:vector :string]]
    [:existing-labels [:vector :string]]       ;; Labels from existing ontology
    [:config {:optional true} [:map
                               [:similarity-threshold {:optional true} :double]
                               [:prefer-existing-uris? {:optional true} :boolean]]]]

   ;; -------------------------------------------------------------------------
   ;; Graph Evolution Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/merge-sources
   [:map
    [:ontology-id :uuid]
    [:source-ids [:vector :string]]
    [:resolution-result [:map
                         [:canonical-map [:map-of :string :string]]
                         [:alignment-triples {:optional true} [:vector [:tuple :string :string :string]]]]]]

   :evolutionary/generate-ttl-snapshot
   [:map
    [:ontology-id :uuid]
    [:format {:optional true} ttl-format]
    [:include-metadata? {:optional true} :boolean]]

   ;; -------------------------------------------------------------------------
   ;; Orchestration Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/build-from-sources
   [:map
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type source-type]]]]
    [:config {:optional true} [:map
                               [:base-uri {:optional true} :string]
                               [:similarity-threshold {:optional true} :double]
                               [:emit-owl-sameAs? {:optional true} :boolean]]]]

   :evolutionary/evolve
   [:map
    [:ontology-id :uuid]
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type source-type]]]]
    [:config {:optional true} [:map
                               [:prefer-existing-uris? {:optional true} :boolean]
                               [:similarity-threshold {:optional true} :double]]]]})

;; =============================================================================
;; Evolutionary Ontology Builder - Query Schemas
;; =============================================================================

(defschemas evolutionary-queries
  {:evolutionary/get-source
   [:map
    [:source-id :string]]

   :evolutionary/get-source-by-hash
   [:map
    [:content-hash :string]]

   :evolutionary/was-processed?
   [:map
    [:source-uri {:optional true} :string]
    [:content {:optional true} :string]]

   :evolutionary/all-sources
   [:map
    [:source-type {:optional true} source-type]]

   :evolutionary/get-all-concepts
   [:map
    [:ontology-id :uuid]]

   :evolutionary/get-concepts-by-source
   [:map
    [:source-id :string]]

   :evolutionary/get-concepts-by-type
   [:map
    [:entity-type :string]]

   :evolutionary/get-canonical-uri
   [:map
    [:uri :string]]

   :evolutionary/get-all-canonical-mappings
   [:map
    [:ontology-id {:optional true} :uuid]]

   :evolutionary/get-evolution-state
   [:map
    [:ontology-id :uuid]]

   :evolutionary/get-build-history
   [:map
    [:ontology-id :uuid]
    [:limit {:optional true} :int]]})

;; =============================================================================
;; Read Model Schemas
;; =============================================================================

(defschemas read-models
  {;; -------------------------------------------------------------------------
   ;; Evolutionary Ontology Builder Read Models
   ;; -------------------------------------------------------------------------

   :evolutionary/source-registry
   [:map-of :string                            ;; source-id -> source-entry
    [:map
     [:source-id :string]
     [:source-uri :string]
     [:source-type source-type]
     [:content-hash :string]
     [:file-size :int]
     [:namespace :string]
     [:metadata {:optional true} [:map-of :keyword :any]]
     [:concepts-extracted {:optional true} :int]
     [:triples-generated {:optional true} :int]
     [:entities-resolved {:optional true} :int]
     [:registered-at :string]]]

   :evolutionary/content-hash-index
   [:map-of :string :string]                   ;; content-hash -> source-id

   :evolutionary/concept-graph
   [:map-of :string                            ;; uri -> concept-with-relationships
    [:map
     [:uri :string]
     [:label :string]
     [:definition {:optional true} :string]
     [:entity-type :string]
     [:source-id :string]
     [:alt-labels {:optional true} [:vector :string]]
     [:relationships [:vector [:map
                               [:predicate :string]
                               [:object :string]
                               [:confidence {:optional true} :double]]]]]]

   :evolutionary/canonical-uri-map
   [:map-of :string :string]                   ;; original-uri -> canonical-uri

   :evolutionary/evolution-state
   [:map
    [:ontology-id :uuid]
    [:total-sources :int]
    [:total-concepts :int]
    [:total-triples :int]
    [:schema-extensions [:vector [:map
                                  [:uri :string]
                                  [:element-type schema-element-type]
                                  [:source-id :string]]]]
    [:last-evolved-at {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Original ORC Optimization Read Models
   ;; -------------------------------------------------------------------------

   :ontology/concepts
   [:map-of :string                       ;; URI -> Concept
    [:map
     [:uri :string]
     [:id :uuid]
     [:label :string]
     [:description :string]
     [:scope ontology-scope]
     [:broader [:set :string]]
     [:narrower [:set :string]]
     [:related [:set :string]]
     [:indicators [:vector :string]]
     [:created-at :string]]]

   :ontology/tree-profile
   [:map
    [:tree-id :uuid]
    [:strengths [:vector [:map
                          [:pattern :string]
                          [:confidence :double]
                          [:evidence-count :int]
                          [:avg-score :double]
                          [:recorded-at {:optional true} :string]
                          ;; Domain-agnostic fields
                          [:context-conditions {:optional true} [:map-of :keyword :any]]
                          [:action-taken {:optional true} [:map
                                                           [:type {:optional true} :string]
                                                           [:target {:optional true} :any]
                                                           [:reason {:optional true} :string]]]
                          [:domain-type {:optional true} :string]
                          [:expected-outcome {:optional true} :string]]]]
    [:weaknesses [:vector [:map
                           [:failure :string]
                           [:subtype {:optional true} :string]
                           [:frequency :double]
                           [:severity severity-level]
                           [:triggers [:vector :string]]
                           [:recorded-at {:optional true} :string]
                           ;; Domain-agnostic fields
                           [:failure-context {:optional true} [:map-of :keyword :any]]
                           [:attempted-action {:optional true} [:map
                                                                [:type {:optional true} :string]
                                                                [:target {:optional true} :any]
                                                                [:reason {:optional true} :string]]]
                           [:domain-type {:optional true} :string]]]]
    [:solves [:vector [:map
                       [:problem-uri :string]
                       [:success-rate :double]
                       [:execution-count :int]]]]
    [:domain-knowledge [:vector [:map
                                  [:id :uuid]
                                  [:description :string]
                                  [:impact-score {:optional true} :double]]]]]

   :ontology/learned-rules
   [:map-of :uuid                          ;; tree-id -> rules vector
    [:vector [:map
              [:rule-id :uuid]
              [:condition [:map-of :keyword :any]]
              [:action [:map-of :keyword :any]]
              [:confidence :double]
              [:success-rate :double]
              [:evidence-episodes [:vector :uuid]]
              [:problem-type :string]
              [:domain-type {:optional true} :string]
              [:extracted-at :string]]]]

   :ontology/node-experiences
   [:map-of node-type                     ;; node-type -> experiences
    [:map-of pattern-type                 ;; pattern-type -> by-effectiveness
     [:map
      [:effective [:vector [:map
                            [:pattern :string]
                            [:metrics [:map-of :keyword :double]]
                            [:evidence-count :int]]]]
      [:ineffective [:vector [:map
                              [:pattern :string]
                              [:metrics [:map-of :keyword :double]]
                              [:evidence-count :int]]]]]]]

   ;; Embedding Read Models (Phase 4)

   :ontology/concept-embeddings
   [:map-of :string                       ;; URI -> embedding data
    [:map
     [:uri :string]
     [:embedding [:vector :double]]
     [:text-embedded :string]
     [:model-id :string]
     [:embedded-at :string]]]

   :ontology/embedding-config
   [:map-of ontology-scope                ;; scope -> model config
    [:map
     [:model-id :string]
     [:dimensions :int]
     [:configured-at :string]]]

   ;; -------------------------------------------------------------------------
   ;; Site Registry Read Models (Generic Site Pattern Learning)
   ;; -------------------------------------------------------------------------

   :site/registry
   [:map
    [:by-domain [:map-of :string          ;; domain -> site
                 [:map
                  [:site-id :uuid]
                  [:domain :string]
                  [:display-name :string]
                  [:category [:enum :corporate :peer-to-peer :aggregator :local]]
                  [:discovered-via [:enum :manual :web-search :referral]]
                  [:url-pattern {:optional true} :string]
                  [:requires-headed {:optional true} :boolean]
                  [:known-challenges {:optional true} [:vector :string]]
                  [:notes {:optional true} :string]
                  [:trust-score :double]
                  [:extraction-count :int]
                  [:last-success-at {:optional true} :string]
                  [:last-failure-at {:optional true} :string]
                  [:registered-at :string]]]]
    [:by-trust [:vector :string]]         ;; domains sorted by trust score
    [:patterns [:map-of :string           ;; domain -> patterns
                [:vector [:map
                          [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
                          [:pattern-data [:map-of :keyword :any]]
                          [:confidence :double]
                          [:learned-at :string]]]]]]})
