(ns ai.obney.orc.ontology.interface.schemas
  "Ontology component schemas - defines commands, events, and queries for
   the event-sourced ontology system.

   Three-layer ontology system:
   - Failure Ontology: Why things go wrong (Hallucination, InstructionViolation, etc.)
   - Success Ontology: What makes things work (StructuralPatterns, InstructionPatterns)
   - Problem Domain: What types of problems exist (Classification, InformationRetrieval)"
  (:require [ai.obney.ontology.interface]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Shared Domain Schemas
;; =============================================================================

(def evidence-value
  "Domain data recorded with observations, extracted rules and source metadata."
  [:schema {:registry
            {::evidence-value
             [:or :nil :boolean :string :keyword :symbol :uuid number? inst? char?
              [:sequential [:ref ::evidence-value]]
              [:set [:ref ::evidence-value]]
              [:map-of [:ref ::evidence-value] [:ref ::evidence-value]]]}}
   [:ref ::evidence-value]])

(def observed-action
  "A recorded action can target domain-specific data such as coordinates or a tree DSL form."
  [:map
   [:type {:optional true} :string]
   [:target {:optional true} evidence-value]
   [:reason {:optional true} :string]])

(def extracted-rule-conditions
  "The rule-extraction workflow emits scalar conditions and flat comparison vectors."
  (let [literal [:or :string :int :double :boolean :keyword]]
    [:map-of :keyword [:or literal [:vector literal]]]))

(def extracted-rule-action
  "The action produced by the rule-extraction workflow."
  [:map
   [:type {:optional true} [:or :string :keyword]]
   [:target {:optional true} [:or :string :keyword]]
   [:reason {:optional true} :string]])

(def persisted-rule-action
  [:and extracted-rule-action [:map-of :keyword evidence-value]])

(def model-usage
  "Provider usage retained in learning provenance. Token counts stay integral;
   cost may be fractional or explicitly unavailable. This open map preserves
   additive provider accounting details instead of mistaking them for tokens."
  [:map
   [:prompt-tokens {:optional true} :int]
   [:completion-tokens {:optional true} :int]
   [:total-tokens {:optional true} :int]
   [:cost {:optional true} [:maybe number?]]
   [:cost-details {:optional true} [:maybe :map]]
   [:prompt-tokens-details {:optional true} [:maybe :map]]
   [:completion-tokens-details {:optional true} [:maybe :map]]
   [:is-byok {:optional true} [:maybe :boolean]]])

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
   [:good-when               {:optional true} :string]
   [:avoid-when              {:optional true} :string]
   [:recommended-pattern     {:optional true} :string]
   [:recommended-alternative {:optional true} :string]
   [:confidence              :double]
   [:evidence-count          :int]
   [:first-observed-at       {:optional true} :string]
   [:last-reinforced-at      {:optional true} :string]
   [:verdict-corroborations  {:optional true} :int]
   [:pattern-reads           {:optional true} [:vector :keyword]]
   [:pattern-writes          {:optional true} [:vector :keyword]]
   [:pattern-outputs         {:optional true} [:vector :keyword]]])

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
   [:scope                     {:optional true} ontology-scope]
   [:composes-into             {:optional true} [:vector [:or :uuid :string]]]
   [:parent-behavior           {:optional true} [:or :uuid :string]]
   [:behavioral-subtree-ids    {:optional true} [:vector [:or :uuid :string]]]
   [:domain-label              {:optional true} :string]])

(def node-instance-target
  "Identity tuple for a node-instance description target —
   [sheet-id node-id]."
  [:tuple :uuid :uuid])

(def description-granularity
  "The granularities a Living Description (and therefore its claim set)
   can be keyed at. Mirrors the spec's DescriptionGranularity enum."
  [:enum :node-type :node-instance :tree-fingerprint :tree-class
   :behavioral-subtree])

(def claim-kind
  "The section of an assembled description body a claim belongs to. A
   claim's kind is MUTABLE (CC-2's `edit` op), so an insight that migrates
   between sections keeps its accumulated support."
  [:enum :capability :strength :weakness :guard :representative-use])

(def consolidation-failure-reason
  "CC-28 (contract ClaimConsolidation, FailureIsVisible) — the CLOSED
   reason-class set for a reflection call that never answered. The class
   is the contract; a message string may ride along on `:error` but is
   never the thing a consumer matches on.

     :provider-rejected — the provider refused the call outright (the
       motivating incident: a 1,571,414-token prompt vs a 1,048,576 cap)
     :timeout           — the execution deadline died before an answer
     :retries-exhausted — every provider attempt errored until the retry
       budget ran out
     :unparseable       — the provider answered but no value could be
       extracted for the declared writes
     :caller-interrupted — the CALLER killed the call before the provider
       could answer: the terminal carries neither an error message nor a
       structured failure kind, so nothing says a provider attempt was ever
       consumed (SIO-4b's measured shape: stopping the harness JVM makes
       grain's stop-tenant-poller shutdownNow the pool, and the interrupt
       surfaces as an InterruptedException whose .getMessage is nil).
       Distinct from :retries-exhausted by the spec's AttemptCountIsEvidenced
       — 'was interrupted before it could answer' is its own fact, not
       something to be implied by an attempt count of 1"
  [:enum :provider-rejected :timeout :retries-exhausted :unparseable
   :caller-interrupted])

(def consolidation-target-identifier
  "The FULL identity range `consolidate!` accepts, one shape per
   granularity: a keyword for :node-type (:llm), a [sheet-id node-id]
   tuple for :node-instance, a SHA string for :tree-fingerprint, a UUID
   for :tree-class. Wider than the claim events' [:or :string :uuid]
   because BOTH reflection paths — claim and legacy body — must be able
   to record their deaths."
  [:or :keyword node-instance-target :string :uuid])

(def claim-status
  "`:candidate` — recorded and visible, but NOT enforcing. `:validated` —
   earned enough post-guard evidence to influence retrieval ranking.
   Every newly-added claim starts `:candidate`."
  [:enum :candidate :validated])

(def evidence-basis
  "CC-6 — WHAT A DELTA SAYS ITS CONTENT RESTS ON.

   CC-4's guard resolves `:episodes` against real judge evidence and refuses
   what it cannot resolve. That is right for a consolidation, which reasons
   over turns a judge already scored, and it is fatal for a DETERMINISTIC
   writer that runs before any judge exists: CV-1's provisional capture fires
   at classify time, so under a resolve-or-refuse guard it could never write at
   all — a designed path blocked by construction, which the orchestrator
   recorded as a forward conflict when CC-4 landed.

   `:from-legacy-corpus` was the first member of this idea rather than a
   special case of nothing. Naming the idea is what stops `legacy` from
   quietly acquiring a second meaning — CC-7's `:legacy-provenance` reads it
   as *converted from a pre-claim body*, and a CV-1 signature is not that.

     :judged-occurrences        the delta rests on the turns it names, and the
                                guard must RESOLVE them. Every LLM-authored
                                consolidation delta. The default when nothing
                                is declared.
     :legacy-corpus             a prior whole-body description (CC-5's
                                just-in-time backfill; CC-12's migration).
     :classification-signature  the deterministic task signature the
                                classifier itself keyed on (CV-1). Asserts an
                                INPUT, not a quality judgement.
     :emitted-artifact          a verbatim artifact the engine produced and
                                recorded — CV-2's emitted worked-DSL. Asserts
                                that the tree was emitted, not that it was good.
     :emitted-artifact-outcome  RR-20: a verbatim artifact the engine produced
                                AND recorded AGAINST the engine's own
                                deterministic execution-outcome (the Phase-2
                                bookend's `:status`) — the shape a campaign
                                SUCCEEDED or FAILED with. `:emitted-artifact`
                                alone is honest only for 'this was emitted';
                                once a writer conditions on outcome to decide
                                WHICH section of the claim set a shape lands
                                in (`:strength` = proven worked pattern,
                                `:weakness` = failed shape), the declaration
                                must name that stronger, still-mechanical fact.
                                It is still not a judge's qualitative verdict —
                                :status is the engine's own deterministic
                                report, not an LLM judgement — so it earns
                                admission exactly like the other mechanical
                                bases and nothing more.
     :campaign-verdict          RR-20: the delta rests on RR-19's DURABLE
                                campaign verdict (`:ontology/tree-class-
                                occurrence-recorded` `:verdict :success`) —
                                distinct from `:emitted-artifact-outcome`
                                (the Phase-2 bookend's OWN status, asserted
                                by the writer that CREATES a shape claim).
                                `:campaign-verdict` is asserted only by the
                                REINFORCEMENT this writer's occurrence-
                                reactive corroboration issues — a SEPARATE,
                                LATER, still-mechanical fact (the whole
                                campaign, not just this one Phase-2
                                execution, reached :success) — and is what
                                `:verdict-corroborations` counts. Still no
                                occurrence named on the delta itself (the
                                guard's `:episodes` stay empty), so it buys
                                admission and nothing else, exactly like
                                every other mechanical basis.
     :authored                  designer-written corpus knowledge (CC-9d).
                                Asserts AUTHORSHIP — a true, auditable statement
                                about provenance.

   WHAT A DECLARATION BUYS: admission past the guard, and — for `:authored`
   alone — enforcement. The MECHANICAL bases buy admission and nothing
   else: such a delta names no occurrence, so it contributes no post-guard
   episode, so CC-7 cannot validate the claim and CC-9's gate cannot let it
   enforce, at any level of accumulated support. Mechanical knowledge is visible
   and never authoritative; it earns enforcement the only way anything does, by
   being reinforced from occurrences a judge actually scored.

   CC-9d — WHY `:authored` IS THE EXCEPTION, and why it is not a hole. A
   designer-written corpus guard has no occurrences by construction (nothing
   judged it; nothing could have), so under the mechanical rule it would seed at
   `initial_claim_support` = 2, below the validation threshold of 5, and the
   curated regression corpus would be NON-ENFORCING from the day it was seeded.
   The two obvious repairs were both rejected in grill GR-2 Q4: seeding at the
   threshold buys only ~0.6 of full guard strength, and seeding above it
   FABRICATES episode counts, which breaks `ClaimsCarryResolvableProvenance` at
   the corpus root and poisons every later calibration. So authorship is carried
   as what it actually is — a basis, not a number. Authorship grants enforcement
   from creation and exemption from support-driven demotion; it does NOT grant
   immortality, because contradiction still decrements support and the ordinary
   retirement path still removes the claim when that support is exhausted. And
   the reflection LLM cannot reach it: the consolidator STAMPS
   `:judged-occurrences` on every model-proposed operation in code."
  [:enum :judged-occurrences :legacy-corpus :classification-signature
   :emitted-artifact :emitted-artifact-outcome :campaign-verdict :authored])

(def claim-operation
  "The complete set of operations a consolidation may express over a claim
   set. Deletion is deliberately absent: a claim leaves only when its
   support decays to zero (CC-2's retire path).

   CC-1 implements `:add` only. The other three are declared here so a
   consolidation that proposes them is still a schema-valid, recordable
   event — the projection fold treats them as no-ops until CC-2 lands,
   rather than throwing on an event that is already permanent."
  [:enum :add :support :contradict :edit])

(def episode-ref
  "One episode — the HP-2 occurrence pair [source-sheet-id source-tick-id]
   that identifies a single turn. Occurrence identity, NOT event identity:
   a static task-shape's sheet-id is shared across every turn, so the tick
   is what makes the reference resolvable (the SJ-1 join lesson)."
  [:tuple :uuid :uuid])

(def claim-delta
  "One proposed change to a claim set. A consolidation emits a vector of
   these instead of a replacement body; the merge that applies them is
   deterministic.

   `:target-claim` names the claim an op applies to and is absent (or nil)
   for `:add`, which creates one.

   CC-6: `:evidence-basis` is what the writer DECLARES its content rests on
   (see `evidence-basis`). Optional, and derived when absent — a delta that
   says nothing is read as `:legacy-corpus` when `:from-legacy-corpus` is set
   and `:judged-occurrences` otherwise, so every delta written before CC-6
   keeps exactly the meaning it had. `:from-legacy-corpus` therefore remains
   the boolean spelling of one basis rather than a second, competing axis."
  [:map
   [:operation          claim-operation]
   [:target-claim       {:optional true} [:maybe :string]]
   [:kind               claim-kind]
   [:content            :string]
   [:context-guard      {:optional true} [:maybe :string]]
   [:recommendation     {:optional true} [:maybe :string]]
   [:episodes           [:vector episode-ref]]
   [:from-legacy-corpus :boolean]
   [:evidence-basis     {:optional true} [:maybe evidence-basis]]])

(def claim
  "One accumulated claim — the shape `get-claims` returns.

   `:claim-id` is derived deterministically from the recording event's id
   plus the delta's index, so a projection rebuild from the log reproduces
   identical claim identities. `:support` is EARNED (seeded at
   `initial_support`, raised by support/edit, lowered by contradict) and is
   always positive: a claim that reaches exhausted support retires out of
   the claim set rather than lingering at zero.

   CC-9d: `:evidence-basis` is the spec's `Claim.evidence_basis` — SET AT
   CREATION ONLY, from the creating delta's declaration, and `nil` when the
   delta declared nothing. It is deliberately durable ON THE CLAIM rather than
   only on the recording event, because two spec rules compare against it
   (`ValidateAuthoredClaimAtCreation`, `DemoteUnderSupportedClaim`) and the
   invariant `OnlyValidatedClaimsEnforce` reads it. An `:edit` operation
   PRESERVES it: that is the anti-laundering rule, without which a reworded
   authored guard could be reclassified into weaker earned-evidence accounting
   by whoever wrote last."
  [:map
   [:claim-id               :string]
   [:kind                   claim-kind]
   [:content                :string]
   [:context-guard          [:maybe :string]]
   [:recommendation         [:maybe :string]]
   [:support                :int]
   [:status                 claim-status]
   [:supporting-episodes    [:vector episode-ref]]
   [:contradicting-episodes [:vector episode-ref]]
   [:legacy-provenance      :boolean]
   [:evidence-basis         {:optional true} [:maybe evidence-basis]]
   [:verdict-corroborations {:optional true} :int]
   [:created-at             :string]
   [:updated-at             :string]])

(def claim-retirement
  "CC-2: the spec's `ClaimRetired` fact — the shape `get-retired-claims`
   returns. Retirement at exhausted support is the ONLY path out of a
   claim set (there is no delete operation), so `:reason` has exactly one
   member and the fact log never shrinks."
  [:map
   [:claim      claim]
   [:reason     [:enum :support-exhausted]]
   [:retired-at :string]])

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
   or `0`) round-trip correctly through the LLM's structured output.

   RS-1: `:domain-coverage`/`:domain-label`/`:domain-reasoning` realise
   `specs/ontology.allium`'s `DomainVerdict`/`DomainCoverage` — a discrete
   verdict the reranker gives BESIDE fitness (fitness stays shape-and-intent
   fit; domain coverage is judged separately, never read off the fitness
   number). OPTIONAL so pre-RS-1 payloads (and the three-key shipped
   contract) still validate unchanged."
  [:map
   [:document-id   :string]
   [:reasoning     :string]
   [:fitness-score [:and number? [:>= 0.0] [:<= 1.0]]]
   [:domain-coverage  {:optional true} [:enum :covered :partial :uncovered :unknown]]
   [:domain-label     {:optional true} [:maybe :string]]
   [:domain-reasoning {:optional true} [:maybe :string]]])

(def reranked-results
  "Vector of reranked-result entries, descending by :fitness-score
   per the reranker's instruction."
  [:vector reranked-result])

(def domain-verdict
  "The RAW {:domain-coverage :domain-label :domain-reasoning} verdict
   `assign-domain-child` carries, unnormalised. Every key is optional and
   every value may be nil — a missing/unknown/malformed verdict is carried
   as-seen (the CC-31 omit-not-nil idiom), never fabricated.

   `:domain-coverage` allows `nil` alongside the four judged values: the
   `apply-rerank` JOIN always assocs the key onto a reranked entry (never
   leaves it simply absent) — `parse-reranked-json`'s real JSON parse path
   defaults an omitted/malformed value to `:unknown`, but a caller that
   stubs the reranker seam directly (bypassing that parse, as classifier-
   level tests do) can legitimately produce `nil` here. RS-2's
   `assign-domain-child` already treats that the same as `:unknown` (defer,
   DomainCoverageIsJudgedNotInferred) — the schema must not reject what the
   join can genuinely produce and the domain logic already handles."
  [:map
   [:domain-coverage  {:optional true} [:maybe [:enum :covered :partial :uncovered :unknown]]]
   [:domain-label     {:optional true} [:maybe :string]]
   [:domain-reasoning {:optional true} [:maybe :string]]])

(def domain-deferral
  "RS-2's `:domain-deferral` marker: the domain axis could not be resolved.
   `:reason` is a CLOSED set (the CC-28 idiom) — a new reason must be added
   here and to `task-classifier/domain-deferral` deliberately.

   CV-A adds `:families-lookup-failed` (the tenant-wide family lookup or the
   family-is-leaf parent lookup threw — never read as \"no families\") and
   `:merge-unresolved` (the merge judge answered `:unknown`, or something
   other than `:same`/`:new` — CV-C's seam; this bundle's default merge-fn
   never produces it, but the reason must exist for the seam contract).

   CV-D adds `:label-taken` (`MintDomainFamily`'s identity-collision guard,
   decision C3'): the merge judge answered `:new`, but the mint's derived
   identity — `stable_domain_child_identity` of the shape and the
   canonical label — is an EXISTING family's identity. Minting again would
   silently duplicate/overwrite it, so the domain axis defers instead."
  [:map
   [:axis   [:enum :domain]]
   [:reason [:enum :unknown-coverage :children-lookup-failed
             :families-lookup-failed :merge-unresolved :label-taken]]])

(def domain-selection
  "CV-A item 2 (`CoveredSeedProtection`): recorded on the classified event
   when a covered, childless tree-class candidate in the ranking wins the
   assignment over a top-1 that would otherwise mint — the passed-over
   shape stays visible for audit."
  [:map
   [:preferred [:or :uuid :string]]
   [:over      [:or :uuid :string]]
   [:reason    [:enum :covered-leaf-neighbour]]])

(def merge-verdict
  "CV-A item 7 — the `:domain-merge-fn` seam's raw answer at a would-be
   mint. `:kind :new` mints (this bundle's default, always); `:kind :same`
   names the existing family it lands on (CV-C); anything else is
   `:merge-unresolved`. Carried unnormalised, exactly as the seam returned
   it — never re-derived from `:kind` alone."
  [:map
   [:kind      [:enum :same :new :unknown]]
   [:family    {:optional true} [:maybe [:or :uuid :string]]]
   [:reasoning {:optional true} [:maybe :string]]])

(def ranked-candidate
  "CC-23, the spec's DecidedRankingIsRecorded: ONE entry of the PRE-GATE
   ranking snapshot a classification decision ran on, REDUCED to identity,
   axis, scores, and rerank source.

   PAYLOAD BOUND (load-bearing, enforced by {:closed true}): NO `:content`,
   NO `:summary`, no description text of any kind — measured, candidate
   content was 59.4% of the tree-class evidence payload (the CC-21 lesson).

     :target-id     — candidate identity (UUID or the ColBERT JSON bridge's
                      stringified form; seeds may carry seed-string ids)
     :granularity   — the retrieval axis (:tree-class / :tree-fingerprint /
                      :behavioral-subtree / ...)
     :rerank-source — how this entry was ranked (:reranker on success;
                      :colbert-fallback / :timeout-fallback when the
                      reranker did not rank). Omitted in the degenerate
                      case of an unstamped candidate (omit-not-nil).
     :fitness-score — the reranker's absolute fitness. OMITTED (not nil)
                      on the fallback path where the reranker never scored.
     :score         — the raw ColBERT retrieval score, when present."
  [:map {:closed true}
   [:target-id     [:or :uuid :string]]
   [:granularity   :keyword]
   [:rerank-source {:optional true} :keyword]
   [:fitness-score {:optional true} number?]
   [:score         {:optional true} number?]])

(def ranked-candidates
  "The bounded pre-gate ranking snapshot: at most the retrieval top-k
   entries (k = 5, `task-classifier/classify-retrieval-k` — the classifier's
   retrieval :k). The {:max 5} bound plus the entry's closed map IS the
   spec's payload bound: length <= k, no description content."
  [:vector {:max 5} ranked-candidate])

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {

   ;; -------------------------------------------------------------------------
   ;; Tree Profile Events
   ;; -------------------------------------------------------------------------

   :ontology/tree-strength-recorded
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    [:recorded-at :string]
    [:context-conditions {:optional true} [:map-of :keyword evidence-value]]
    [:state-conditions {:optional true} [:map-of :keyword evidence-value]]
    [:action-taken {:optional true} observed-action]
    [:domain-type {:optional true} :string]
    [:expected-outcome {:optional true} :string]]

   :ontology/tree-weakness-recorded
   [:map
    [:tree-id :uuid]
    [:failure-uri :string]
    [:subtype-uri {:optional true} :string]
    [:frequency :double]
    [:severity severity-level]
    [:triggers [:vector :string]]
    [:evidence-trace-ids [:vector :uuid]]
    [:recorded-at :string]
    [:failure-context {:optional true} [:map-of :keyword evidence-value]]
    [:failure-conditions {:optional true} [:map-of :keyword evidence-value]]
    [:attempted-action {:optional true} observed-action]
    [:domain-type {:optional true} :string]]

   :ontology/tree-problem-mapping-created
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]
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

   :ontology/learned-rule-extracted
   [:map
    [:rule-id :uuid]
    [:tree-id :uuid]
    [:rule [:map
            [:condition extracted-rule-conditions]
            [:action persisted-rule-action]
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

   :ontology/node-type-description-updated
   [:map
    [:target-type [:= :node-type]]
    [:target-id :keyword]
    [:body description-body]
    [:model-provenance {:optional true} [:maybe [:map
                                                 [:trace-id :uuid]
                                                 [:model :string]
                                                 [:usage model-usage]]]]
    [:request-id {:optional true} :uuid]
    [:recorded-at :string]]

   :ontology/node-instance-description-updated
   [:map
    [:target-type [:= :node-instance]]
    [:target-id node-instance-target]
    [:body description-body]
    [:model-provenance {:optional true} [:maybe [:map
                                                 [:trace-id :uuid]
                                                 [:model :string]
                                                 [:usage model-usage]]]]
    [:request-id {:optional true} :uuid]
    [:recorded-at :string]]

   :ontology/tree-description-updated
   [:map
    [:target-type [:enum :tree-fingerprint :tree-class]]
    [:target-id [:or :string :uuid]]
    [:body description-body]
    [:model-provenance {:optional true} [:maybe [:map
                                                 [:trace-id :uuid]
                                                 [:model :string]
                                                 [:usage model-usage]]]]
    [:request-id {:optional true} :uuid]
    [:recorded-at :string]]

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
    [:researcher-ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:classification-context {:optional true} :map]
    [:parent-tree-id    {:optional true} [:or :uuid :string]]
    [:rerank-failed?    {:optional true} :boolean]
    [:behavioral-subtrees {:optional true} [:vector :map]]
    [:ranked-candidates {:optional true} ranked-candidates]
    [:outcome {:optional true} [:enum :matched :novel :uncertain]]
    [:assigned-via {:optional true}
     [:enum :match :bundle :walk-down :mint
      :mint-domain-child :land-on-domain-child :mint-sibling-domain-child]]
    [:domain-verdict {:optional true} domain-verdict]
    [:domain-label {:optional true} [:maybe :string]]
    [:domain-children-considered {:optional true} [:vector [:maybe :string]]]
    [:domain-deferral {:optional true} domain-deferral]
    [:domain-selection {:optional true} domain-selection]
    [:merge-verdict {:optional true} merge-verdict]]

   :ontology/tree-class-occurrence-recorded
   [:map
    [:source-sheet-id :uuid]
    [:source-tick-id :uuid]
    [:source-node-id :uuid]
    [:source-completion-event-id :uuid]
    [:assigned-tree-id :uuid]
    [:verdict [:enum :success :failure :timeout]]
    [:recorded-at :string]]

   :ontology/shape-coherence-reported
   [:map
    [:tree-class :uuid]
    [:source-sheet-id :uuid]
    [:source-tick-id :uuid]
    [:source-occurrence-event-id :uuid]
    [:verdict-occurrences :int]
    [:successful-campaigns :int]
    [:successful-shape-observations :int]
    [:distinct-successful-shapes :int]
    [:ratio [:maybe number?]]
    [:status [:enum :qualified :rejected :not-measurable]]
    [:maximum-shape-ratio number?]
    [:recorded-at :string]]

   :ontology/task-classification-deferred
   [:map
    [:source-sheet-id   :uuid]
    [:source-tick-id    :uuid]
    [:source-node-id    :uuid]
    [:fallback-source   [:enum :colbert-fallback :timeout-fallback :domain-coverage]]
    [:ranked-candidates ranked-candidates]
    [:reasoning         :string]
    [:deferred-at       :string]
    [:researcher-ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:classification-context {:optional true} :map]]

   :ontology/domain-child-minted
   [:map
    [:parent-tree-id [:or :uuid :string]]
    [:child-tree-id  :uuid]
    [:domain-label   :string]
    [:minted-at      :string]
    [:source-sheet-id {:optional true} :uuid]
    [:source-tick-id  {:optional true} :uuid]
    [:source-node-id  {:optional true} :uuid]]

   :ontology/behavioral-subtree-minted
   [:map
    [:target-id          :uuid]
    [:name               :string]
    [:parent-behavior    {:optional true} [:or :uuid :string]]
    [:provenance         [:enum :agent-minted :human-authored :harvested]]
    [:minted-by-sheet-id {:optional true} :uuid]
    [:minted-by-tick-id  {:optional true} :uuid]
    [:logical-action-identity {:optional true} :string]
    [:attempt-identity {:optional true} :string]
    [:researcher-iteration {:optional true} [:int {:min 0}]]
    [:attempt-ordinal {:optional true} [:int {:min 0}]]
    [:ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:harvested-from-tree-class {:optional true} [:or :uuid :string]]
    [:minted-at          :string]]

   :ontology/mint-reindex-forced
   [:map
    [:minted-event-id :uuid]
    [:forced-at        :string]]

   :ontology/claim-deltas-recorded
   [:map
    [:granularity          description-granularity]
    [:target-identifier    [:or :string :uuid]]
    [:deltas               [:vector claim-delta]]
    [:evidence-event-count :int]
    [:claim-set-version    :int]
    [:model-provenance     {:optional true} [:maybe [:map
                                                     [:trace-id :uuid]
                                                     [:model :string]
                                                     [:usage model-usage]]]]
    [:request-id           {:optional true} :uuid]
    [:recorded-at          :string]]

   :ontology/promotion-evidence-excluded
   [:map
    [:granularity       description-granularity]
    [:target-identifier [:or :string :uuid]]
    [:reason            [:enum :no-judge-evidence :starved-evidence
                         :judge-abstained :no-episodes
                         :unverified-explanation]]
    [:episodes          [:vector episode-ref]]
    [:operation         claim-operation]
    [:kind              claim-kind]
    [:content           :string]
    [:settled-by        [:enum :deterministic :verifier]]
    [:request-id        {:optional true} :uuid]
    [:excluded-at       :string]]

   :ontology/claim-deltas-refused
   [:map
    [:granularity       description-granularity]
    [:target-identifier [:or :string :uuid]]
    [:reason            [:enum :stale-claim-set]]
    [:attempted-version :int]
    [:current-version   :int]
    [:delta-count       :int]
    [:request-id        {:optional true} :uuid]
    [:refused-at        :string]]

   :ontology/description-consolidation-failed
   [:map
    [:granularity        description-granularity]
    [:target-identifier  consolidation-target-identifier]
    [:reason             consolidation-failure-reason]
    [:error              {:optional true} [:maybe :string]]
    [:attempts           :int]
    [:request-id         {:optional true} :uuid]
    [:failed-at          :string]]

   :ontology/anti-recency-rejection
   [:map
    [:target-type     [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id       consolidation-target-identifier]
    [:bucket          [:enum :strengths :weaknesses]]
    [:entry-trait     :string]
    [:prior-confidence number?]
    [:prior-evidence-count :int]
    [:reason          :keyword]
    [:rejected-body   :map]
    [:model-provenance {:optional true} [:maybe [:map
                                                 [:trace-id :uuid]
                                                 [:model :string]
                                                 [:usage model-usage]]]]
    [:detected-at     :string]]

   :ontology/anti-recency-clamp-applied
   [:map
    [:target-type     [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id       consolidation-target-identifier]
    [:bucket          [:enum :strengths :weaknesses]]
    [:entry-trait     :string]
    [:prior-confidence number?]
    [:llm-confidence  number?]
    [:clamped-confidence number?]
    [:reason          :keyword]
    [:detected-at     :string]]

   :ontology/consolidation-requested
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    [:on-demand? :boolean]
    [:requested-at :string]]

   :ontology/consolidation-skipped
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    [:reason [:enum :budget-exhausted :no-operations-proposed]]
    [:budget {:optional true} :int]
    [:recent-count {:optional true} :int]
    [:request-id {:optional true} :uuid]
    [:skipped-at :string]]

   :ontology/consolidation-threshold-set
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:threshold :int]
    [:set-at :string]]

   :ontology/living-description-enabled-set
   [:map
    [:enabled? :boolean]
    [:set-at :string]]

   :ontology/consolidation-budget-set
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:budget :int]
    [:set-at :string]]

   :ontology/evidence-token-budget-set
   [:map
    [:budget-tokens :int]
    [:set-at :string]]

   :ontology/reflection-evidence-excluded
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    [:budget-tokens :int]
    [:selected-count :int]
    [:excluded-count :int]
    [:predicted-prompt-tokens :int]
    [:oldest-excluded-at [:maybe :string]]
    [:newest-excluded-at [:maybe :string]]
    [:request-id {:optional true} :uuid]
    [:excluded-at :string]]

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
    [:parent-uri :string]
    [:proposed-uri :string]
    [:label :string]
    [:description :string]
    [:evidence-count :int]
    [:discovered-at :string]
    [:status [:enum :proposed :approved :rejected]]]

   :ontology/embedding-model-configured
   [:map
    [:ontology-id :uuid]
    [:scope [:enum :failure :success :problem :node-type :custom :all]]
    [:model-id :string]
    [:dimensions :int]
    [:configured-at :string]]

   :ontology/concept-embedded
   [:map
    [:uri :string]
    [:ontology-id {:optional true} :uuid]
    [:concept-id {:optional true} :uuid]
    [:text-embedded :string]
    [:field-source :string]
    [:embedding [:vector :double]]
    [:model-id :string]
    [:embedded-at :string]]

   :ontology/tree-profile-embedded
   [:map
    [:tree-id :uuid]
    [:text-embedded :string]
    [:embedding [:vector :double]]
    [:model-id :string]
    [:embedded-at :string]]

   :ontology/evaluation-embedded
   [:map
    [:trace-id :uuid]
    [:dimension :string]
    [:feedback :string]
    [:embedding [:vector :double]]
    [:failure-uri {:optional true} :string]
    [:model-id :string]
    [:embedded-at :string]]

   :site/registered
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
    [:registered-at :string]]

   :site/trust-updated
   [:map
    [:site-id :uuid]
    [:domain :string]
    [:trust-score :double]
    [:extraction-count :int]
    [:last-success-at {:optional true} :string]
    [:last-failure-at {:optional true} :string]
    [:updated-at :string]]

   :site/pattern-learned
   [:map
    [:site-id :uuid]
    [:domain :string]
    [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
    [:pattern-data [:map-of :keyword evidence-value]]
    [:confidence :double]
    [:learned-at :string]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {:ontology/record-build-state
   [:map
    [:event-type [:enum :evolutionary/build-started :evolutionary/build-completed :evolutionary/build-failed]]
    [:details [:map [:ontology-id :uuid] [:build-id :uuid]]]]

   :ontology/initialize-static-ontology
   [:map
    [:scope {:optional true} ontology-scope]]

   :ontology/record-tree-strength
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    [:context-conditions {:optional true} [:map-of :keyword evidence-value]]
    [:state-conditions {:optional true} [:map-of :keyword evidence-value]]
    [:action-taken {:optional true} observed-action]
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
    [:failure-context {:optional true} [:map-of :keyword evidence-value]]
    [:failure-conditions {:optional true} [:map-of :keyword evidence-value]]
    [:attempted-action {:optional true} observed-action]
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

   :ontology/record-node-type-description
   [:map
    [:target-id :keyword]
    [:body description-body]
    [:model-provenance {:optional true} [:maybe :map]]
    [:request-id {:optional true} :uuid]]

   :ontology/record-node-instance-description
   [:map
    [:target-id node-instance-target]
    [:body description-body]
    [:model-provenance {:optional true} [:maybe :map]]
    [:request-id {:optional true} :uuid]]

   :ontology/record-tree-description
   [:map
    [:target-id [:or :string :uuid]]
    [:body description-body]
    [:model-provenance {:optional true} [:maybe :map]]
    [:request-id {:optional true} :uuid]]

   :ontology/record-tree-class-description
   [:map
    [:target-id [:or :string :uuid]]
    [:body description-body]
    [:model-provenance {:optional true} [:maybe :map]]
    [:request-id {:optional true} :uuid]]

   :ontology/record-claim-deltas
   [:map
    [:granularity          description-granularity]
    [:target-identifier    [:or :string :uuid]]
    [:deltas               [:vector claim-delta]]
    [:evidence-event-count {:optional true} :int]
    [:claim-set-version    :int]
    [:model-provenance     {:optional true} [:maybe :map]]
    [:request-id           {:optional true} :uuid]]

   :ontology/record-consolidation-failure
   [:map
    [:granularity       description-granularity]
    [:target-identifier consolidation-target-identifier]
    [:reason            consolidation-failure-reason]
    [:error             {:optional true} [:maybe :string]]
    [:attempts          :int]
    [:request-id        {:optional true} :uuid]]

   :ontology/record-consolidation-skip
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    [:reason [:enum :budget-exhausted :no-operations-proposed]]
    [:budget {:optional true} :int]
    [:recent-count {:optional true} :int]
    [:request-id {:optional true} :uuid]]

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
    [:researcher-ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:classification-context {:optional true} :map]
    [:parent-tree-id    {:optional true} [:or :uuid :string]]
    [:rerank-failed?    {:optional true} :boolean]
    [:behavioral-subtrees {:optional true} [:vector :map]]
    [:ranked-candidates {:optional true} ranked-candidates]
    [:outcome {:optional true} [:enum :matched :novel :uncertain]]
    [:assigned-via {:optional true}
     [:enum :match :bundle :walk-down :mint
      :mint-domain-child :land-on-domain-child :mint-sibling-domain-child]]
    [:domain-verdict {:optional true} domain-verdict]
    [:domain-label {:optional true} [:maybe :string]]
    [:domain-children-considered {:optional true} [:vector [:maybe :string]]]
    [:domain-deferral {:optional true} domain-deferral]
    [:domain-selection {:optional true} domain-selection]
    [:merge-verdict {:optional true} merge-verdict]]

   :ontology/record-tree-class-occurrence
   [:map
    [:source-sheet-id :uuid]
    [:source-tick-id :uuid]
    [:source-node-id :uuid]
    [:source-completion-event-id :uuid]
    [:assigned-tree-id :uuid]
    [:verdict [:enum :success :failure :timeout]]]

   :ontology/report-shape-coherence
   [:map
    [:tree-class :uuid]
    [:source-sheet-id :uuid]
    [:source-tick-id :uuid]
    [:source-occurrence-event-id :uuid]]

   :ontology/record-task-classification-deferral
   [:map
    [:source-sheet-id   :uuid]
    [:source-tick-id    :uuid]
    [:source-node-id    :uuid]
    [:fallback-source   [:enum :colbert-fallback :timeout-fallback :domain-coverage]]
    [:ranked-candidates ranked-candidates]
    [:reasoning         :string]
    [:researcher-ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:classification-context {:optional true} :map]]

   :ontology/mint-domain-child
   [:map
    [:parent-tree-id [:or :uuid :string]]
    [:child-tree-id  :uuid]
    [:domain-label   :string]
    [:source-sheet-id {:optional true} :uuid]
    [:source-tick-id  {:optional true} :uuid]
    [:source-node-id  {:optional true} :uuid]
    [:birth-description {:optional true} [:maybe :string]]]

   :ontology/mint-behavioral-subtree
   [:map
    [:name               :string]
    [:body               description-body]
    [:parent-behavior    {:optional true} [:or :uuid :string]]
    [:provenance         [:enum :agent-minted :human-authored :harvested]]
    [:minted-by-sheet-id {:optional true} :uuid]
    [:minted-by-tick-id  {:optional true} :uuid]
    [:logical-action-identity {:optional true} :string]
    [:attempt-identity {:optional true} :string]
    [:researcher-iteration {:optional true} [:int {:min 0}]]
    [:attempt-ordinal {:optional true} [:int {:min 0}]]
    [:ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:harvested-from-tree-class {:optional true} [:or :uuid :string]]]

   :ontology/mark-mint-reindex-forced
   [:map
    [:minted-event-id :uuid]]

   :ontology/request-consolidation
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    [:on-demand? {:optional true} :boolean]]

   :ontology/set-consolidation-threshold
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:threshold :int]]

   :ontology/set-consolidation-budget
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:budget :int]]

   :ontology/set-evidence-token-budget
   [:map
    [:budget-tokens :int]]

   :ontology/record-reflection-evidence-exclusion
   [:map
    [:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]]
    [:target-id [:or :keyword [:tuple :uuid :uuid] :string :uuid]]
    [:budget-tokens :int]
    [:selected-count :int]
    [:excluded-count :int]
    [:predicted-prompt-tokens :int]
    [:oldest-excluded-at [:maybe :string]]
    [:newest-excluded-at [:maybe :string]]
    [:request-id {:optional true} :uuid]]

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

   :ontology/configure-embedding-model
   [:map
    [:scope [:enum :failure :success :problem :node-type :custom :all]]
    [:model-id :string]
    [:dimensions {:optional true} :int]]

   :ontology/embed-concept
   [:map
    [:ontology-id {:optional true} :uuid]
    [:uri :string]
    [:fields {:optional true} [:set [:enum :label :description :indicators :triggers]]]]

   :ontology/embed-concepts-batch
   [:map
    [:ontology-id {:optional true} :uuid]
    [:ontology-ids {:optional true} [:or [:sequential :uuid] [:set :uuid]]]
    [:scope {:optional true} ontology-scope]
    [:uris {:optional true} [:vector :string]]
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
    [:success? :boolean]
    [:listings-extracted {:optional true} :int]]

   :site/record-site-pattern
   [:map
    [:domain :string]
    [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
    [:pattern-data [:map-of :keyword evidence-value]]
    [:confidence {:optional true} :double]]})

;; =============================================================================
;; Query Schemas
;; =============================================================================

(defschemas queries
  {:ontology/get-ontology
   [:map [:ontology-id :uuid]]

   :ontology/list-ontologies
   [:map]

   :ontology/get-concepts
   [:map
    [:ontology-ids {:optional true} [:or [:sequential :uuid] [:set :uuid]]]
    [:ontology-id {:optional true} [:or :uuid :string]]
    [:scope {:optional true} ontology-scope]
    [:broader-uri {:optional true} :string]
    [:include-narrower? {:optional true} :boolean]]

   :ontology/get-concept
   [:map
    [:ontology-id {:optional true} [:or :uuid :string]]
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
    [:ontology-id {:optional true} :uuid]
    [:uri :string]]

   :site/get-site
   [:map
    [:domain :string]]

   :site/get-trusted-sites
   [:map
    [:min-trust {:optional true} :double]
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

(def build-event-config
  "Settings persisted by batch and incremental builds. Source-specific options
   retain their nested domain data. Unset index names and field selections are nil."
  [:and
   [:map
    [:ontology-id {:optional true} :uuid]
    [:base-uri {:optional true} :string]
    [:similarity-threshold {:optional true} number?]
    [:emit-owl-sameAs? {:optional true} :boolean]
    [:use-type-blocking? {:optional true} :boolean]
    [:include-metadata? {:optional true} :boolean]
    [:prefer-existing-uris? {:optional true} :boolean]
    [:enable-colbert? {:optional true} :boolean]
    [:colbert-index-name {:optional true} [:maybe :string]]
    [:colbert-fields {:optional true} [:maybe [:sequential :keyword]]]
    [:auto-detect-colbert-fields? {:optional true} :boolean]
    [:enable-embeddings? {:optional true} :boolean]
    [:embedding-fields {:optional true} [:maybe [:sequential :keyword]]]
    [:auto-detect-embedding-fields? {:optional true} :boolean]]
   [:map-of :keyword evidence-value]])

(defschemas evolutionary-events
  {

   ;; -------------------------------------------------------------------------
   ;; Concept Extraction Events
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
                            [:properties {:optional true} [:map-of [:or :keyword :string] evidence-value]]]]]
    [:extracted-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Build Orchestration Events
   ;; -------------------------------------------------------------------------

   :evolutionary/build-started
   [:map
    [:build-id :uuid]
    [:ontology-id :uuid]
    [:mode resolution-mode]
    [:source-count :int]
    [:config build-event-config]
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
  {

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
    [:existing-labels [:vector :string]]
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
  {:evolutionary/get-build-history
   [:map
    [:ontology-id :uuid]
    [:limit {:optional true} :int]]})

;; =============================================================================
;; Read Model Schemas
;; =============================================================================

(defschemas read-models
  {

   ;; -------------------------------------------------------------------------
   ;; Original ORC Optimization Read Models
   ;; -------------------------------------------------------------------------

   :ontology/tree-profile
   [:map
    [:tree-id :uuid]
    [:strengths [:vector [:map
                          [:pattern :string]
                          [:confidence :double]
                          [:evidence-count :int]
                          [:avg-score :double]
                          [:recorded-at {:optional true} :string]
                          [:context-conditions {:optional true} [:map-of :keyword evidence-value]]
                          [:action-taken {:optional true} observed-action]
                          [:domain-type {:optional true} :string]
                          [:expected-outcome {:optional true} :string]]]]
    [:weaknesses [:vector [:map
                           [:failure :string]
                           [:subtype {:optional true} :string]
                           [:frequency :double]
                           [:severity severity-level]
                           [:triggers [:vector :string]]
                           [:recorded-at {:optional true} :string]
                           [:failure-context {:optional true} [:map-of :keyword evidence-value]]
                           [:attempted-action {:optional true} observed-action]
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
   [:map-of :uuid
    [:vector [:map
              [:rule-id :uuid]
              [:condition extracted-rule-conditions]
              [:action persisted-rule-action]
              [:confidence :double]
              [:success-rate :double]
              [:evidence-episodes [:vector :uuid]]
              [:problem-type :string]
              [:domain-type {:optional true} :string]
              [:extracted-at :string]]]]

   :ontology/node-experiences
   [:map-of node-type
    [:map-of pattern-type
     [:map
      [:effective [:vector [:map
                            [:pattern :string]
                            [:metrics [:map-of :keyword :double]]
                            [:evidence-count :int]]]]
      [:ineffective [:vector [:map
                              [:pattern :string]
                              [:metrics [:map-of :keyword :double]]
                              [:evidence-count :int]]]]]]]

   :ontology/claim claim

   :ontology/claim-retirement claim-retirement

   :ontology/embedding-config
   [:map-of ontology-scope
    [:map
     [:model-id :string]
     [:dimensions :int]
     [:configured-at :string]]]

   :site/registry
   [:map
    [:by-domain [:map-of :string
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
    [:by-trust [:vector :string]]
    [:patterns [:map-of :string
                [:vector [:map
                          [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
                          [:pattern-data [:map-of :keyword evidence-value]]
                          [:confidence :double]
                          [:learned-at :string]]]]]]})
