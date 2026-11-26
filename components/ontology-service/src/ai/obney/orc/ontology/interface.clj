(ns ai.obney.orc.ontology.interface
  "Public API for the ontology component.

   Three-layer ontology system:
   1. Failure Ontology - Why things go wrong (maps to 4 evaluation judges)
   2. Success Ontology - What makes things work (structural, instruction, data flow patterns)
   3. Problem Ontology - What types of problems exist (classification, retrieval, generation)

   Main capabilities:
   - Static ontology initialization from predefined concepts
   - Event-sourced concept graph with relationships
   - Tree profile tracking (strengths, weaknesses, problem mappings)
   - Node-type pattern learning aggregation
   - Evaluation classification to failure URIs
   - TTL/SKOS/OWL serialization for graph database export
   - Embedding generation and semantic search (Phase 4)
   - Hybrid search combining graph BFS + embeddings via RRF"
  (:require [ai.obney.ontology.interface :as ontology-lib]
            [ai.obney.ontology.core.graph :as graph]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.orc.ontology.core.commands :as commands]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.todo-processors :as processors]
            [ai.obney.orc.ontology.interface.schemas :as schemas]))

(defn get-static-concepts
  "Get concepts from static ontology definitions.
   Options:
   - :scope - Filter by :failure, :success, or :problem"
  {:arglists '([] [{:keys [scope]}])}
  ([]
   (logic/get-static-concepts))
  ([options]
   (logic/get-static-concepts options)))

(defn get-static-relationships
  "Get all relationships from static ontology definitions."
  []
  (logic/get-all-static-relationships))

(defn get-static-concept-by-uri
  "Find a static concept by its URI."
  [uri]
  (logic/static-ontology-get-concept-by-uri uri))

(defn get-behavior-mint-by-logical-action
  "Return the durable winning mint event for one researcher logical action."
  [ctx logical-action-identity]
  (queries/get-behavior-mint-by-logical-action ctx logical-action-identity))

(defn behavior-mint-provenance
  "RR-24: return the researcher attempt provenance recorded on one minted
   behavior — {:iteration-index n :attempt-ordinal n :ownership-epoch n
   :first-attempt? bool} — or nil when the mint carries no researcher
   provenance (a hand-authored or harvested mint, or a pre-RR-24 event that
   predates these fields).

   :first-attempt? is true only when the recorded attempt was the campaign's
   very first attempt (attempt-ordinal 0) on its very first ownership epoch
   (ownership-epoch 1) — a late fallback (a higher attempt ordinal) and a
   first attempt made by a NEW owner after a lease handoff (a higher
   ownership epoch) both carry materially less evidentiary weight than a
   confident, uncontested first try."
  [ctx target-id]
  (queries/behavior-mint-provenance ctx target-id))

(defn get-failure-concept-for-dimension
  "Map evaluation dimension name to failure concept URI.
   E.g., 'Grounding' -> 'failure:Grounding'"
  [dimension-name]
  (logic/get-failure-concept-for-dimension dimension-name))

(defn get-ontology [ctx ontology-id]
  (ontology-lib/get-ontology ctx ontology-id))

(defn list-ontologies
  ([ctx] (ontology-lib/list-ontologies ctx))
  ([ctx _opts] (ontology-lib/list-ontologies ctx)))

(defn ontology-exists? [ctx ontology-id]
  (ontology-lib/ontology-exists? ctx ontology-id))

(def create-ontology! ontology-lib/create-ontology!)

(def create-concept! ontology-lib/create-concept!)

(def update-concept! ontology-lib/update-concept!)

(def create-relationship! ontology-lib/create-relationship!)

(defn get-concepts
  "Get concepts from the event-sourced concept graph.
   Options:
   - :scope - Filter by scope
   - :broader-uri - Filter by parent concept"
  {:arglists '([ctx & [opts]])}
  [ctx & options]
  (apply logic/interface-get-concepts ctx options))

(defn get-concept-by-uri
  "Get a concept from the event-sourced graph. Prefer the scoped
   (ctx ontology-id uri) form; (ctx uri) returns nil when ambiguous."
  ([ctx uri] (logic/get-concept-by-uri ctx uri))
  ([ctx ontology-id uri] (logic/get-concept-by-uri ctx ontology-id uri)))

(defn get-narrower-concepts
  "Get all concepts narrower than (children of) the given URI."
  [ctx uri]
  (logic/get-narrower-concepts ctx uri))

(defn get-broader-concepts
  "Get all concepts broader than (parents of) the given URI."
  [ctx uri]
  (logic/get-broader-concepts ctx uri))

(defn concept-statistics
  "Get statistics about the concept graph."
  [ctx]
  (logic/concept-statistics ctx))

(defn get-tree-profile
  "Get profile for a specific tree including strengths, weaknesses, and problem mappings."
  [ctx tree-id]
  (read-models/get-tree-profile ctx tree-id))

(defn get-description
  "Return the CURRENT Living Description body for the given target.

   Granularity is one of :node-type, :node-instance, :tree-fingerprint.
   Target-id shape depends on the granularity:
   - :node-type        → keyword (e.g. :llm, :map-each)
   - :node-instance    → [sheet-id node-id] tuple of UUIDs
   - :tree-fingerprint → string (the canonical-tree-raw hash)

   Returns nil if no description has been recorded for the target.

   CC-3 (ADR 0021) — WHERE THE BODY COMES FROM. The returned SHAPE is
   unchanged (`schemas/description-body`), and every existing consumer
   reads it unchanged. What changed is provenance: a target that has
   claims gets an ASSEMBLED body, recomputed from its claim set on every
   `:ontology/record-claim-deltas` (kind → section; strengths/weaknesses
   principle-shaped; `:confidence` DERIVED from earned support rather
   than asserted by an LLM). A target described the legacy wholesale way
   still returns exactly what was recorded. Both paths write the same
   `:current` slot, so the LAST WRITE WINS on a target that uses both —
   except that an assembly carries forward the previous body's graph
   metadata (`:parent-tree-id`, `:scope`, `:composes-into`,
   `:parent-behavior`, `:behavioral-subtree-ids`), which is not
   derivable from claims.

   ORDER IS PART OF THE CONTRACT: `:strengths`, `:weaknesses`,
   `:capabilities`, `:representative-uses` and `:avoid-when` come back
   BEST-SUPPORTED FIRST on an assembled body, so consumers that truncate
   without sorting (EL-2's `enrich-candidate-evidence` takes 3 in body
   order) keep the strongest evidence."
  [ctx granularity target-id]
  (read-models/get-description ctx granularity target-id))

(defn get-description-history
  "Return the full chronological history of every description version
   recorded for the (granularity, target-id) target. Each entry is
   {:body :recorded-at :event-id}. Empty vector if none recorded.

   CC-3: append-only across BOTH write paths — a wholesale
   `record-*-description` and a claim-set change each append one entry,
   in the same shape, and nothing ever rewrites or removes one."
  [ctx granularity target-id]
  (read-models/get-description-history ctx granularity target-id))

(defn get-claims
  "CC-1 (ADR 0021): return the CLAIM SET for the given target — the vector
   of claims accumulated from every claim-delta event recorded against it,
   in the order they were added. Empty vector when the target has none.

   Same (granularity, target-id) key and arg order as `get-description`;
   a target's claims and its assembled body are two views of one Living
   Description. Disjoint targets never share claims.

   Each claim is
   {:claim-id :kind :content :context-guard :recommendation :support
    :status :supporting-episodes :contradicting-episodes
    :legacy-provenance :created-at :updated-at}."
  [ctx granularity target-id]
  (read-models/get-claims ctx granularity target-id))

(defn get-retired-claims
  "CC-2 (ADR 0021): return the target's ClaimRetired facts, oldest first;
   empty vector when nothing has retired.

   Each entry is
   {:claim <the claim as it stood, including the contradiction that
            finished it>
    :reason :support-exhausted
    :retired-at <string>}.

   Retirement at exhausted support is the ONLY way a claim leaves the
   claim set — there is no delete operation — so this is the complete
   record of everything the target has stopped believing, and it never
   shrinks. Retired claims do NOT appear in `get-claims`."
  [ctx granularity target-id]
  (read-models/get-retired-claims ctx granularity target-id))

(defn get-enforcing-claims
  "CC-7 (ADRs 0021/0022): return only the target's VALIDATED claims —
   those that have earned the right to influence retrieval ranking —
   BEST-SUPPORTED FIRST. Empty vector when none.

   Same (granularity, target-id) key and arg order as `get-claims`, and the
   claim maps are the identical shape; this is a filtered, ranked VIEW.

   A claim is validated when its net support has reached the validation
   threshold AND at least one of its supporting occurrences was resolved by
   CC-4's evidence guard. Legacy-provenance claims carrying no resolvable
   occurrence therefore accumulate support and stay visible, but never
   appear here: pre-guard evidence has unknown starvation content by
   definition.

   Use this — never `get-claims` — anywhere a claim is allowed to ACT.
   Under ADR 0022 a negative claim suppresses retrieval, so the difference
   between the two functions is the difference between showing the model
   what a target believes and letting a belief mute a behavior.

   Ordering is deliberate and differs from `get-claims`' insertion order:
   the consumer is a ranker, and downstream truncation is unsorted, so the
   strongest claim must arrive first. Tie-break is `:claim-id`, so the order
   is stable across a rebuild from the log."
  [ctx granularity target-id]
  (read-models/get-enforcing-claims ctx granularity target-id))

(defn get-claim-set-version
  "CC-1 (ADR 0021): return the target's current claim-set version (0 when
   it has no claim-delta events yet).

   A consolidation reads this BEFORE reasoning and passes it back on
   :ontology/record-claim-deltas; the append CAS compares it, so a
   consolidation that reasoned over a stale claim set loses instead of
   overwriting a concurrent one."
  [ctx granularity target-id]
  (read-models/get-claim-set-version ctx granularity target-id))

(defn pattern-key-bindings
  "RR-22 (`OfferedPatternsAreUsable`): derive a pattern's declared key
   bindings from its EXACT source text (a `:recommended-pattern` /
   `:generated-tree-source` string) — a PURE function, no ctx, no effects.

   Returns `{:reads […] :writes […] :outputs […]}`:
   - `:reads`   — keys the pattern reads before any node inside it wrote
                  them (its external inputs);
   - `:writes`  — every key any node writes;
   - `:outputs` — the `[:final {:keys […]}]` node's keys, present only
                  when the pattern has a `:final` node.

   Returns nil — never throws — for text that is not a string, does not
   parse as Clojure data, or whose code was elided (the RR-6/G6 sanitize
   placeholder `\"<inline-fn>\"` in place of a `:code` node's `:fn`): an
   unusable pattern declares nothing rather than a binding that would
   mislead a model rebinding it."
  [source]
  (logic/pattern-key-bindings source))

(defn get-excluded-evidence
  "CC-4 (ADR 0023): return every claim delta the evidence guard REFUSED to
   record for this target, oldest first; empty vector when none.

   Each entry is
   {:reason <:no-judge-evidence | :starved-evidence | :judge-abstained
             | :no-episodes | :unverified-explanation>
    :episodes <the occurrence pairs whose evidence could not be trusted>
    :operation :kind :content <the delta as proposed>
    :settled-by <:deterministic | :verifier>
    :excluded-at <string>}.

   A silently dropped delta would destroy the ability to measure how much
   of our historical evidence was starved — the number the migration
   (CC-12) and the engine-side proposal (CC-14) both rest on. Exclusion is
   per-DELTA: a batch keeps its grounded deltas and loses only the ones
   listed here."
  [ctx granularity target-id]
  (read-models/get-excluded-evidence ctx granularity target-id))

(defn get-claim-delta-refusals
  "CC-4 (ADR 0021), the spec's `ClaimDeltasRefused`: return every
   consolidation REFUSED for this target, oldest first; empty vector when
   none.

   Each entry is
   {:reason :stale-claim-set
    :attempted-version <the version the loser had read>
    :current-version <the version that had already won>
    :delta-count <how many deltas were lost with it>
    :refused-at <string>}.

   CC-1's CAS already prevented a stale consolidation from corrupting the
   claim set, but the refusal existed only as a command return value — and
   a return value cannot be asserted on or queried later. This turns 'how
   often do concurrent consolidations collide' from a forensic into a
   query."
  [ctx granularity target-id]
  (read-models/get-claim-delta-refusals ctx granularity target-id))

(defn seed-baseline-corpus!
  "C-Baseline: emit the baseline seed corpus that ships with the ontology
   component (~45 hand-authored descriptions covering common node-types,
   structural tree-classes, and behavioral subtrees).

   Consumers using ORC via git deps call this on first start to bootstrap
   the description corpus. Without it the R-Inject classifier path has
   nothing to retrieve against and the Living Description loop has no
   starting bodies for the consolidator to refine.

   Returns a vec of command-results — one per dispatch. The 23 structural
   tree-class seeds are dual-emitted (under :tree-fingerprint AND
   :tree-class scopes) so the prepend assembler's body-fetch hits from
   bootstrap onward; node-type and behavioral-subtree seeds are emitted
   once each. Total dispatches: 68.

   Idempotent: re-running appends new :tree-description-updated events
   with the same body and the read-model projects the latest as `:current`
   while preserving `:history` for audit."
  [ctx]
  (commands/seed-baseline-corpus! ctx))

(defn baseline-seeds
  "Pure-data inspection: return the loaded baseline seed catalog as
   `{:node-types <vec> :tree-classes <vec> :behavioral-subtrees <vec>}`.

   For consumers and tests that want to walk the corpus without
   dispatching commands (e.g., asserting invariants over seed bodies,
   diffing against an app-specific extension)."
  []
  (logic/baseline-seeds))

(defn get-consolidation-threshold
  "C-2a-3a: return the configured consolidation threshold for a
   target-type keyword. Falls back to the default 10 events when no
   per-target-type override has been set via
   :ontology/set-consolidation-threshold."
  [ctx target-type]
  (read-models/get-consolidation-threshold ctx target-type))

(defn get-consolidation-delta
  "C-2a-3a: return the current delta-counter (events-since-last-
   consolidation) for the given (target-type, target-id) target.
   Returns 0 when no events have ticked the counter."
  [ctx target-type target-id]
  (read-models/get-consolidation-delta ctx target-type target-id))

(defn get-tree-class-judge-averages
  "EL-4 (ADR 0015): return {judge-name -> mean-score} across a
   :tree-class's lifetime, or nil when no judge scores exist for it.
   Standing read-model queryable at harvest time; parity with the
   consolidator's on-demand tree-class-aggregate-metrics :judge-averages."
  [ctx tree-class-id]
  (read-models/get-tree-class-judge-averages ctx tree-class-id))

(defn get-tree-class-judge-recent-averages
  "CC-24b (ADR 0029): return {judge-name -> mean over that judge's most recent
   `window` scored occurrences of the :tree-class}, or nil when the class has
   no judge scores in its retained window. The harvest gate's DIMENSION axis
   reads this; the lifetime accessor above is unchanged and still live."
  [ctx tree-class-id window]
  (read-models/get-tree-class-judge-recent-averages ctx tree-class-id window))

(defn get-tree-class-for-sheet
  "CV-2 (ADR 0017 decision 3): return the :tree-class id assigned to
   `source-sheet-id` (via the task-classified sheet->class join), or nil
   when the sheet was never classified."
  [ctx source-sheet-id]
  (read-models/get-tree-class-for-sheet ctx source-sheet-id))

(defn get-consolidation-budget
  "C-2a-3c: return the configured hourly consolidation budget for a
   target-type keyword. Falls back to default 100 when no per-target-type
   override has been set via :ontology/set-consolidation-budget."
  [ctx target-type]
  (read-models/get-consolidation-budget ctx target-type))

(defn get-evidence-token-budget
  "PR-1 (ADR 0030): return the consolidator's evidence token budget — the
   predicted-prompt-token bound the newest-first evidence window selection
   respects. Falls back to the DERIVED default
   (read-models/default-evidence-token-budget) when no override has been
   set via :ontology/set-evidence-token-budget."
  [ctx]
  (read-models/get-evidence-token-budget ctx))

(defn get-consolidation-outcome
  "PR-2 (spec invariant EveryConsolidationRequestYieldsAnOutcome): the
   durable outcome that answered the given consolidation request (the
   REQUEST EVENT's :event/id).

   Returns {:status <:deltas-recorded | :description-updated | :failed |
                     :refused | :skipped | :claim-set-unchanged | :none>
            :events [{:event-type … :at … (:reason …)} …]}.

   One command batch = one outcome: a recorded batch's ride-along exclusion
   events do not make it two, and the all-excluded ClaimSetUnchanged shape
   (exclusion events only, version unadvanced) reports as ONE
   :claim-set-unchanged outcome. :none is the 08-18 defect shape — a
   request nothing ever answered."
  [ctx request-id]
  (read-models/get-consolidation-outcome ctx request-id))

(defn get-unanswered-consolidation-requests
  "PR-2 (EveryConsolidationRequestYieldsAnOutcome): every consolidation
   request with NO durable outcome, oldest first — the store query that
   makes the 2026-08-18 vanished request (mid-flight JVM death, no event,
   indistinguishable from health) a DETECTABLE defect.

   opts: :older-than-ms (only requests at least this old — pass the orphan
   grace period so an in-flight consolidation is not reported dead),
   :target-type / :target-id scope filters.

   Each entry: {:request-id :target-type :target-id :requested-at}. The
   consolidator's sweep (sweep-orphaned-consolidation-requests!) converts
   these into durable :caller-interrupted failure records."
  [ctx opts]
  (read-models/get-unanswered-consolidation-requests ctx opts))

(defn get-reflection-evidence-exclusions
  "PR-2 (ADR 0030, spec invariant BoundedReflectionEvidence): return every
   durable record of a consolidation whose evidence window the token budget
   CUT for this target, oldest first; [] when none.

   Each entry is
   {:budget-tokens <the bound in force>
    :selected-count :excluded-count
    :predicted-prompt-tokens <what the SELECTED window predicts>
    :oldest-excluded-at :newest-excluded-at <the excluded range's bounds —
      exclusion is a contiguous OLDEST prefix, so these plus the target
      identify every excluded event still durable in the store>
    :excluded-at <string>
    :request-id <uuid, when the consolidation was request-driven>}.

   ONE entry per consolidation-with-exclusions; zero-exclusion
   consolidations record NOTHING."
  [ctx target-type target-id]
  (read-models/get-reflection-evidence-exclusions ctx target-type target-id))

(defn get-recent-consolidation-count
  "C-2a-3c: return how many consolidation attempts have fired for the
   given target-type in the rolling last-hour window — successes
   (:*-description-updated, :ontology/claim-deltas-recorded) and, per
   CC-28's FailuresConsumeBudget, terminal reflection failures
   (:ontology/description-consolidation-failed). Used by the
   consolidator's budget gate."
  [ctx target-type]
  (read-models/get-recent-consolidation-count ctx target-type))

(defn get-living-description-enabled?
  "Gap-1: return the system-level Living Description opt-in flag.
   Default false when no `:ontology/set-living-description-enabled`
   event has been emitted. When true, the writing side of the loop
   activates (consolidator, threshold tracking, per-event evaluator
   runtime). Read side (R-Inject's :auto-classify? in :rlm config)
   is independent."
  [ctx]
  (read-models/get-living-description-enabled? ctx))

(defn get-reindex-config
  "C-2b-1: return the current ColBERT re-index configuration —
   {:reindex-threshold-events N :reindex-timer-minutes T}. Falls back
   to defaults (10 events, 5 minutes) when no :ontology/set-reindex-config
   command has been emitted."
  [ctx]
  (read-models/get-reindex-config ctx))

(defn get-reindex-state
  "C-2b-1: return the current re-index state for the ColBERT description
   corpus — {:events-since-last-rebuild N :last-rebuild-timestamp
   ISO-string :index-built? bool}. The re-index processor reads this to
   decide threshold-or-timer trigger firing."
  [ctx]
  (read-models/get-reindex-state ctx))

(defn bootstrap-reindex!
  "C-2b-1: bootstrap the ColBERT ontology-descriptions index. Call this
   once during application startup, AFTER tp/start has wired up the
   re-index processor. If descriptions already exist in the event store
   AND no index has been built yet, this triggers an initial rebuild so
   the search API has something to retrieve from immediately.

   Idempotent — if the index already exists, this is a no-op (the
   threshold-or-timer trigger handles future rebuilds)."
  [ctx]
  (processors/maybe-rebuild! ctx))

(def family-label-list-bound
  "CV-A (domain-child convergence, item 1 + item 6): the bound on the
   tenant-wide family-label list — one number shared by the classifier's
   tenant-wide family landing (`existing-domain-families`) and the
   reranker's bounded `:existing-domain-labels` blackboard slot, so the two
   never drift apart."
  logic/family-label-list-bound)

(defn existing-domain-families
  "CV-A item 1 (`DomainChildIdentityIsStable`, `DomainChildrenAreAlwaysConsidered`):
   every tree-class concept minted as a domain family, TENANT-WIDE across
   every parent — provenance :agent-authored, a non-blank :label that is
   not the concept's own bare id (the generic placeholder an ordinary,
   non-domain tree-class concept carries as its label). Most-recently-
   minted first, bounded by `family-label-list-bound`.

   Returns [{:target-id :domain-label :parent-id} ...] — :parent-id is the
   family's own birth shape (its first skos:broader edge), nil when the
   concept carries none.

   A genuine read failure PROPAGATES — never caught here. The classifier's
   `:domain-families-fn` seam (and the reranker's label-list builder) decide
   how a failure is handled; a lookup that cannot be resolved must defer,
   never silently read as \"no families\" (DomainCoverageIsJudgedNotInferred)."
  [ctx]
  (logic/existing-domain-families ctx))

(defn tree-class-representatives
  "Every tree class is indexed on BOTH tree axes with the same description, so
   a reranker fed raw rows spends two of its slots on each class and sees half
   as many classes as its budget allows (a live in-domain task lost its seed
   this way, ColBERT's near-flat scores deciding which five classes made it).
   Returns `{:representatives :siblings}`: one row per tree class, in ColBERT
   order, preferring the instruction-aware :tree-class row; every other row
   passes through unchanged. `:siblings` maps a class's target id to its other
   axis rows, for `restore-axis-siblings`."
  [rows]
  (logic/tree-class-representatives rows))

(defn restore-axis-siblings
  "After the rerank, give each tree class's other axis row the SAME judgement
   as its representative (fitness, reasoning, domain verdict, enrichment) with
   its own identity fields, placed right after it. Downstream sees both axis
   rows exactly as before, now judged once."
  [reranked siblings]
  (logic/restore-axis-siblings reranked siblings))

(defn search-descriptions
  "C-2b-1+C-2b-2: parameterized retrieval over the ColBERT-indexed
   Living Description corpus. Returns top-K results, optionally
   filtered by granularity and optionally reranked by an LLM against
   the caller's intent.

   Args:
     ctx  - context map with :event-store, :tenant-id, etc.
     opts - options map:
       :query              - natural-language query string (REQUIRED)
       :granularity        - filter to one of :node-type, :node-instance,
                             :tree-fingerprint, :tree-class,
                             :behavioral-subtree; OR a SET of those
                             (membership filter, retrieve across axes);
                             or :all (no filter; default :all)
       :k                  - top-K to return (default 10)
       :rerank-with-intent - optional string. When provided, the
                             ColBERT top-(2k or capped 50) is run
                             through an LLM reranker that returns
                             a reordered list with per-result
                             :reasoning + :fitness-score.
       :model              - optional OpenRouter model id override for
                             the reranker (RR-2). Only meaningful when
                             :rerank-with-intent is also provided; nil
                             is a no-op (the reranker resolves its own
                             evidence-tested default).
       :exclude?           - CV-E (`DomainFamilyIsALeafOnTheDomainAxis`,
                             revised C5'): optional (fn [ctx candidate] ->
                             truthy) applied WITH the granularity filter —
                             before `tree-class-representatives` and any
                             take — so an excluded candidate never reaches
                             the reranker or a plain take-k result. Absent
                             (the default for every caller but
                             `classify-task`) leaves this function's
                             behaviour byte-identical to before this opt
                             existed. Invoked at most ONCE per distinct
                             candidate id (a class's :tree-class and
                             :tree-fingerprint rows share one answer) —
                             the caller is free to make it expensive (a
                             store read) without paying for it twice.
                             When present, the ColBERT fetch over-fetches
                             an EXTRA 2x (on top of the existing 3x
                             granularity-filter over-fetch) so exclusion
                             still leaves the reranker its usual candidate
                             count wherever the index holds enough
                             survivors.

   Returns a vector of result maps:
     [{:content \"...\" :score 0.87 :rank 1 :document-id \"...\"
       :document-metadata {:granularity :target-id :confidence :last-update}
       ;; Present only when :rerank-with-intent was provided:
       :reasoning \"...\" :fitness-score 0.87}]

   Cold-search semantics: if no ontology-descriptions index has been
   built yet, returns [] and logs ::search-cold-no-index. NEVER
   triggers a synchronous rebuild (would block the caller ~30-60s).

   Rerank-failure semantics: if the LLM call throws or returns nil,
   fall back to the pure-ColBERT top-K + log ::rerank-failed. The
   caller sees no exception."
  {:arglists '([ctx {:keys [query granularity k rerank-with-intent model exclude?]
        :or {granularity :all k 10}}])}
  [ctx options]
  (queries/search-descriptions ctx options))

(def classify-task
  "C-2c-1: pure classification function. Given a task signature +
   optional parent-context summary + threshold, returns a tree-class
   assignment (matched corpus entry above threshold OR fresh UUID mint).

   See `ai.obney.orc.ontology.core.task-classifier/classify-task` for
   the full docstring."
  queries/classify-task)

(def classify-behaviors
  "R05b: pure behavioral classification. Given a task signature +
   threshold (+ optional :structural-context + :top-n), returns top-N
   behavioral-subtree examples from the Layer-2 corpus with reasoning.

   The corpus is FEW-SHOT EXAMPLE MATERIAL for the recursive RLM
   researcher; the model decides reuse/adapt/mint. composes-into
   edges in the concept graph are RETRIEVAL HINTS, not gates.

   See `ai.obney.orc.ontology.core.task-classifier/classify-behaviors`
   for the full docstring."
  logic/classify-behaviors)

(defn get-all-tree-profiles
  "Get all tree profiles."
  [ctx]
  (read-models/get-all-tree-profiles ctx))

(defn find-trees-by-problem
  "Find trees that solve a specific problem type."
  [ctx problem-uri]
  (read-models/find-trees-by-problem ctx problem-uri))

(defn find-trees-with-weakness
  "Find trees that have a specific weakness/failure pattern."
  [ctx failure-uri]
  (read-models/find-trees-with-weakness ctx failure-uri))

(defn tree-profile-statistics
  "Get statistics about tree profiles."
  [ctx]
  (read-models/tree-profile-statistics ctx))

(defn get-node-type-learnings
  "Get aggregated learnings for a specific node type (e.g., :llm, :code)."
  [ctx node-type]
  (read-models/get-node-type-learnings ctx node-type))

(defn get-all-node-learnings
  "Get all node learnings aggregated by type."
  [ctx]
  (read-models/get-all-node-learnings ctx))

(defn node-learning-statistics
  "Get statistics about node-level learning."
  [ctx]
  (read-models/node-learning-statistics ctx))

(defn concepts->turtle
  "Serialize concepts to SKOS Turtle format.
   Arguments:
   - concepts: Map of URI -> concept-map
   - opts: {:base-uri \"...\" :include-scheme? true/false}"
  {:arglists '([concepts & [opts]])}
  [concepts & options]
  (apply logic/interface-concepts->turtle concepts options))

(defn tree-profile->turtle
  "Serialize a single tree profile to OWL Turtle format."
  {:arglists '([profile & [opts]])}
  [profile & options]
  (apply logic/interface-tree-profile->turtle profile options))

(defn tree-profiles->turtle
  "Serialize multiple tree profiles to OWL Turtle format."
  {:arglists '([profiles & [opts]])}
  [profiles & options]
  (apply logic/interface-tree-profiles->turtle profiles options))

(defn node-experiences->turtle
  "Serialize node learning experiences to OWL Turtle format."
  {:arglists '([experiences & [opts]])}
  [experiences & options]
  (apply logic/interface-node-experiences->turtle experiences options))

(defn export-turtle
  "Full ontology export to Turtle format.
   Options:
   - :scope - Filter concepts (:failure, :success, :problem)
   - :include-profiles? - Include tree profiles (default true)
   - :include-experiences? - Include node experiences (default true)
   - :base-uri - Base URI for the ontology"
  {:arglists '([ctx & [opts]])}
  [ctx & options]
  (apply queries/export-turtle ctx options))

(defn validate-turtle
  "Basic validation of generated Turtle syntax."
  [turtle-str]
  (logic/validate-turtle turtle-str))

(defn classify-evaluation
  "Classify evaluation results into failure ontology URIs.

   Arguments:
   - evaluation-result: Map with :score and :dimensions
     - :score - Overall score (0.0-1.0)
     - :dimensions - Vector of {:name :score :feedback}

   Options:
   - :threshold - Score below which to flag as failure (default 0.7)
   - :min-confidence - Minimum confidence to include (default 0.1)

   Returns:
   {:failures [{:uri :confidence :evidence :dimension}]
    :primary-failure-uri \"failure:Hallucination\"
    :overall-score 0.5}"
  {:arglists '([evaluation-result & [opts]])}
  [evaluation-result & options]
  (apply logic/interface-classify-evaluation evaluation-result options))

(defn classify-trace-evaluations
  "Classify multiple trace evaluations, grouping failures by type."
  {:arglists '([evaluations & [opts]])}
  [evaluations & options]
  (apply logic/interface-classify-trace-evaluations evaluations options))

(defn estimate-severity
  "Estimate severity level for a classified failure."
  [failure]
  (logic/estimate-severity failure))

(defn extract-triggers
  "Extract trigger phrases from failure evidence."
  [failures]
  (logic/extract-triggers failures))

(defn bfs-spreading-activation
  "BFS traversal with exponential decay activation scores.

   Algorithm:
   1. Initialize seeds with activation 1.0
   2. Process nodes level by level (BFS)
   3. For each node, propagate to neighbors:
      neighbor_activation = current_activation × edge_weight × decay^depth
   4. Stop when activation < min_activation or depth > max_depth
   5. Return all visited nodes sorted by activation score

   Args:
     graph: Graph map with :nodes and :edges (or concept map to convert)
     seed-uris: Collection of starting URIs
     config: {:max-depth N :decay N :min-activation N :max-results N}

   Returns:
     Vector of {:uri :score :path :depth :source-seed :edge-type}"
  {:arglists '([graph-or-concepts seed-uris & [config]])}
  [graph-or-concepts seed-uris & options]
  (apply logic/interface-bfs-spreading-activation graph-or-concepts seed-uris options))

(defn link-expansion
  "Fast 2-3 hop expansion for real-time suggestions.

   Use for: autocomplete, quick suggestions, low-latency scenarios

   Args:
     graph: Graph map with :nodes and :edges (or concept map)
     seed-uris: Collection of starting URIs
     opts: {:hops N} - Number of expansion hops (1-3)

   Returns:
     Vector of {:uri :score :path :depth}"
  {:arglists '([graph-or-concepts seed-uris & {:keys [hops] :or {hops 2}}])}
  [graph-or-concepts seed-uris & options]
  (apply logic/interface-link-expansion graph-or-concepts seed-uris options))

(defn compute-rrf-scores
  "Reciprocal Rank Fusion for merging multiple ranked result lists.

   Formula: score(d) = sum(1 / (k + rank_i))
   where k = 60 (per original RRF paper)

   Args:
     batches: Vector of result batches, each batch is vector of {:uri :score}
     opts: {:k N} - RRF constant (default 60)

   Returns:
     Vector of [uri fused-score] sorted by fused score descending"
  {:arglists '([batches & {:keys [k] :or {k 60}}])}
  [batches & options]
  (apply logic/interface-compute-rrf-scores batches options))

(defn merge-batches
  "Merge multiple search batches using RRF or weighted-sum fusion.

   Args:
     batches: Vector of result batches
     opts: {:method \"rrf\"/\"weighted-sum\" :weights [...]}

   Returns:
     Vector of {:uri :score :sources}"
  {:arglists '([batches & {:keys [method weights] :or {method "rrf"}}])}
  [batches & options]
  (apply logic/interface-merge-batches batches options))

(defn concepts->graph
  "Convert concept read model to graph representation for traversal algorithms.

   Args:
     concepts: Map of URI -> concept-map from concepts read model

   Returns:
     {:nodes #{uri...} :edges {uri [{:to uri :predicate pred :weight w}...]}}"
  [concepts]
  (graph/concepts->graph concepts))

(defn quick-search
  "Quick graph search without full ORC workflow.

   Args:
     graph: Graph map or concept map
     seed-uris: Vector of seed URIs
     opts: {:strategy \"bfs\"/\"link_expansion\" :max-depth N :decay N :hops N}

   Returns:
     Vector of {:uri :score :path :depth}"
  {:arglists '([graph seed-uris & {:keys [strategy max-depth decay hops]
                      :or {strategy "bfs" max-depth 3 decay 0.5 hops 2}}])}
  [graph seed-uris & options]
  (apply logic/interface-quick-search graph seed-uris options))

(defn expand-concept-neighborhood
  "Expand from seed concept URIs using BFS spreading activation.

   Args:
     seed-uris: Collection of starting concept URIs
     opts: {:max-depth N :decay N :graph graph}

   Returns:
     Vector of {:uri :score :path :depth} sorted by score"
  {:arglists '([seed-uris & {:keys [max-depth decay graph]
                :or {max-depth 2 decay 0.6}}])}
  [seed-uris & options]
  (apply logic/interface-expand-concept-neighborhood seed-uris options))

(defn find-similar-trees
  "Find trees similar to a given problem type and/or pattern requirements.

   Uses graph traversal to find related concepts, then filters tree profiles
   by success rate and matching patterns.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Problem URI to match (e.g., \"problem:Classification\")
       :required-patterns - Set of success pattern URIs that must be present
       :exclude-patterns - Set of success pattern URIs to exclude
       :min-success-rate - Minimum success rate (default 0.7)
       :limit - Maximum results (default 10)

   Returns:
     Vector of {:tree-id :profile :score :matching-patterns :problem-match}"
  [event-store opts]
  (queries/find-similar-trees event-store opts))

(defn find-failure-patterns
  "Find common failure patterns for a problem type or across all trees.

   Aggregates weaknesses from tree profiles and groups by failure URI.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Filter to trees solving this problem type
       :failure-uri - Filter to specific failure type
       :min-frequency - Minimum occurrence frequency (default 0.2)
       :limit - Maximum results (default 20)

   Returns:
     Vector of {:failure-uri :total-occurrences :avg-frequency
                :severity-distribution :common-triggers :tree-count}"
  [event-store opts]
  (queries/find-failure-patterns event-store opts))

(defn find-success-patterns
  "Find effective success patterns for a problem type.

   Aggregates strengths from high-performing trees.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Filter to trees solving this problem type
       :min-success-rate - Minimum tree success rate (default 0.8)
       :min-confidence - Minimum pattern confidence (default 0.7)
       :limit - Maximum results (default 10)

   Returns:
     Vector of {:pattern-uri :label :description :avg-confidence
                :avg-score :tree-count :evidence-count}"
  [event-store opts]
  (queries/find-success-patterns event-store opts))

(defn build-ontology-context
  "Build comprehensive ontology context for MCP sheet builder integration.

   This provides all the information needed to guide tree generation:
   - Few-shot examples (similar successful trees)
   - Success patterns to use
   - Failure patterns to avoid
   - Related concepts for grounding
   - Self-learning patterns (when enabled)

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Primary problem being solved
       :required-patterns - Success patterns that should be used
       :user-domain - Domain context (e.g., \"education\", \"healthcare\")
       :tree-id - Current tree ID for self-learning mode
       :self-learning? - Enable self-reinforcing learning from tree's own patterns

   Returns:
     {:few-shot-trees [...] - Similar successful trees with profiles
      :recommended-patterns [...] - Success patterns to use (includes self-patterns when enabled)
      :patterns-to-avoid [...] - Common failure patterns (includes self-weaknesses when enabled)
      :related-concepts [...] - Neighborhood expansion for context
      :problem-hierarchy {...} - Problem type with parent/child
      :self-patterns {...} - Tree's own patterns (when self-learning enabled)}

   Self-learning mode example:
   ```clojure
   (build-ontology-context ctx
     {:problem-type \"problem:PointNavigation\"
      :tree-id sheet-id
      :self-learning? true})
   ```"
  [event-store opts]
  (queries/build-ontology-context event-store opts))

(defn format-context-for-llm
  "Format ontology context for injection into LLM prompts.

   Converts the rich ontology context from build-ontology-context into
   a markdown-formatted string suitable for inclusion in an LLM system prompt.

   Args:
     context: Map from build-ontology-context
     opts: Configuration map
       :include #{:patterns :failures :related :hierarchy :examples}
       :max-items - Max items per section (default 5)

   Returns:
     Formatted markdown string for prompt injection, or nil if empty

   Example:
     (let [ctx (build-ontology-context event-store {:problem-type \"problem:Classification\"})]
       (format-context-for-llm ctx {:include #{:patterns :related} :max-items 3}))"
  [context opts]
  (logic/format-context-for-llm context opts))

(defn hybrid-retrieval
  "Hybrid retrieval combining multiple signals.

   Combines:
   1. Problem type graph expansion
   2. Pattern matching from tree profiles
   3. Failure pattern analysis

   Args:
     event-store: Grain event store
     query-concepts: Set of seed concept URIs
     opts: Retrieval configuration

   Returns:
     {:trees [...] :patterns [...] :failures [...] :merged-scores [...]}"
  [event-store query-concepts opts]
  (queries/hybrid-retrieval event-store query-concepts opts))

(defn compute-temporal-relevance
  "Compute temporal relevance score.

   Returns:
   - 1.0 for current year
   - 1.1 for future years (slight boost)
   - Decaying score for past years (min 0.1)
   - 0.5 for entities without temporal info"
  {:arglists '([entity-year reference-year & {:keys [decay-per-year] :or {decay-per-year 0.15}}])}
  [entity-year reference-year & options]
  (apply logic/interface-compute-temporal-relevance entity-year reference-year options))

(defn apply-temporal-scoring
  "Apply temporal relevance scoring to search results.

   Final score = graph_score × temporal_relevance

   Args:
     results: Vector of {:uri :score ...}
     temporal-entities: Map of uri -> effective-year
     reference-year: Current year for relevance calculation

   Returns:
     Results with adjusted scores"
  [results temporal-entities reference-year]
  (graph/apply-temporal-scoring results temporal-entities reference-year))

(defn build-tree-profiles
  "Build tree profiles from events."
  [initial-state events]
  (logic/tree-profiles initial-state events))

(defn build-node-experiences
  "Build node experiences from events."
  [initial-state events]
  (logic/node-experiences initial-state events))

(def concept-events
  "Event types that affect the concept graph."
  logic/interface-concept-events)

(def tree-profile-events
  "Event types that affect tree profiles."
  logic/interface-tree-profile-events)

(def node-learning-events
  "Event types that affect node learning."
  logic/interface-node-learning-events)

(def all-ontology-events
  "All ontology-related event types."
  logic/interface-all-ontology-events)

(def embedding-events
  "Event types that affect embedding read models."
  logic/interface-embedding-events)

(defn embed-text
  "Generate an embedding vector for text.

   Args:
     text: String to embed
     opts: {:model-id \"sentence-transformers/all-MiniLM-L6-v2\"}

   Returns:
     Vector of doubles (384 dimensions for default model), or nil on error."
  {:arglists '([text & [opts]])}
  [text & options]
  (apply logic/interface-embed-text text options))

(defn embed-texts-batch
  "Generate embeddings for multiple texts.

   Args:
     texts: Collection of strings to embed
     opts: {:model-id \"...\"}

   Returns:
     Vector of embedding vectors"
  {:arglists '([texts & [opts]])}
  [texts & options]
  (apply logic/interface-embed-texts-batch texts options))

(defn cosine-similarity
  "Compute cosine similarity between two embedding vectors.

   Returns:
     Similarity score in range [-1, 1], or 0.0 if either vector is zero."
  [a b]
  (logic/embedding-cosine-similarity a b))

(defn detect-embeddable-fields
  "Analyze a Malli schema to find fields suitable for embedding.

   Heuristics:
   - :string fields with semantic names (:description, :feedback, :label)
   - [:vector :string] fields (indicators, triggers)
   - Fields with {:description ...} metadata

   Args:
     schema: Malli schema to analyze

   Returns:
     {:embeddable-fields [:description :feedback ...]
      :reasoning {'description' 'reason...'}}

   Returns nil if schema is not a map schema."
  [schema]
  (logic/detect-embeddable-fields schema))

(defn concept->embedding-text
  "Convert a concept to text suitable for embedding.

   Combines specified fields into a single text string.

   Args:
     concept: Concept map with :label, :description, :indicators, etc.
     fields: Set of field keywords to include (default: #{:label :description})

   Returns:
     Combined text string for embedding"
  {:arglists '([concept & [fields]])}
  [concept & options]
  (apply logic/interface-concept->embedding-text concept options))

(defn tree-profile->embedding-text
  "Convert a tree profile to text suitable for embedding.

   Summarizes strengths, weaknesses, and problem types."
  [profile]
  (logic/tree-profile->embedding-text profile))

(defn get-embedding-model
  "Get an embedding model by ID, loading it if necessary.

   Thread-safe with double-checked locking pattern.

   Args:
     model-id: Optional model identifier (default: all-MiniLM-L6-v2)

   Returns:
     Loaded DJL ZooModel"
  {:arglists '([& [model-id]])}
  [& options]
  (apply logic/interface-get-embedding-model options))

(defn close-all-embedding-models!
  "Close all loaded embedding models. Useful for REPL cleanup."
  []
  (logic/close-all-embedding-models!))

(defn get-concept-embedding
  "Read a stored vector by URI. Supply the ontology ID when URIs overlap."
  ([ctx uri] (logic/get-concept-embedding ctx uri))
  ([ctx ontology-id uri] (logic/get-concept-embedding ctx ontology-id uri)))

(defn get-all-concept-embeddings
  "Get all concept embeddings, optionally filtered by scope."
  {:arglists '([ctx & [opts]])}
  [ctx & options]
  (apply logic/queries-get-all-concept-embeddings ctx options))

(defn get-tree-profile-embedding
  "Get embedding for a specific tree profile."
  [ctx tree-id]
  (read-models/get-tree-profile-embedding ctx tree-id))

(defn get-all-tree-profile-embeddings
  "Get all tree profile embeddings."
  [ctx]
  (read-models/get-all-tree-profile-embeddings ctx))

(defn get-embedding-config
  "Get embedding model configuration for a scope."
  [ctx scope]
  (read-models/get-embedding-config ctx scope))

(defn embedding-statistics
  "Get statistics about embeddings."
  [ctx]
  (read-models/embedding-statistics ctx))

(defn semantic-search-concepts
  "Compare a query embedding to stored concept vectors from the same model.
   Select graphs with :ontology-id or :ontology-ids and restrict concepts with
   :scope. Accepts :model-id, :min-similarity and :limit. Results include each
   concept's ontology ID, URI, label, description and similarity."
  [ctx query-text & opts]
  (apply logic/interface-semantic-search-concepts ctx query-text opts))

(defn semantic-search-tree-profiles
  "Search tree profiles using embedding similarity.

   Finds trees with similar characteristics based on their embedded profiles.

   Args:
     event-store: Grain event store
     query-text: Description of desired tree characteristics
     opts:
       :limit - Maximum results (default 5)
       :min-similarity - Minimum similarity threshold (default 0.4)

   Returns:
     Vector of {:tree-id :similarity :profile} sorted by similarity"
  {:arglists '([event-store query-text & {:keys [limit min-similarity]
                              :or {limit 5 min-similarity 0.4}
                              :as opts}])}
  [event-store query-text & options]
  (apply queries/interface-semantic-search-tree-profiles event-store query-text options))

(defn hybrid-search
  "Hybrid search combining graph-based BFS with embedding similarity via RRF.

   This is the primary search function that leverages both:
   1. Graph structure - BFS spreading activation from seed concepts
   2. Semantic similarity - Embedding-based similarity to query text

   RRF (Reciprocal Rank Fusion) merges both ranked lists to produce
   results that capture both structural relationships AND semantic meaning.

   Args:
     event-store: Grain event store
     opts:
       :seed-uris - Collection of starting concept URIs for graph expansion
       :query-text - Natural language query for embedding search
       :scope - Filter to specific ontology scope
       :ontology-id - Filter by single ontology-id
       :ontology-ids - Filter by multiple ontology-ids (returns union)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum embedding similarity (default 0.3)
       :max-depth - BFS expansion depth (default 2)
       :decay - BFS decay factor (default 0.6)
       :weights - {:graph N :embedding N} weights for RRF

   Returns:
     {:results [{:uri :score :graph-rank :embedding-rank :label :description}]
      :graph-results [...]
      :embedding-results [...]
      :method \"rrf\"}"
  [event-store opts]
  (logic/hybrid-search event-store opts))

(defn hybrid-search-batch
  "Batched hybrid-search over MANY query-texts. The ColBERT signal runs in ONE
   batched pass (index loaded ONCE, one bridge round-trip); embedding + graph stay
   per-query (in-JVM). Returns a vector of per-query result-maps, each shaped exactly
   like hybrid-search's output, aligned to `:query-texts`.

   Use over a whole transcript instead of mapping hybrid-search per line: collapses N
   ColBERT round-trips (and N index loads) into one, no per-search reload, while
   keeping the embedding+ColBERT RRF fusion substance-identical to the single-query
   path.

   Args:
     event-store: Grain event store
     opts: same as hybrid-search, except
       :query-texts - Vector of natural-language queries (instead of :query-text)
       :seed-uris   - shared graph seeds applied to every query (usually nil)"
  [event-store opts]
  (logic/hybrid-search-batch event-store opts))

(defn hybrid-search-failures
  "Search failure concepts using hybrid graph + embedding search.

   Specialized search for finding relevant failure types.

   Args:
     event-store: Grain event store
     query-text: Description of the failure to find
     opts:
       :seed-failure-uri - Optional seed failure URI for graph expansion
       :limit - Maximum results (default 5)

   Returns:
     Vector of failure concepts with scores"
  {:arglists '([event-store query-text & {:keys [seed-failure-uri limit]
                              :or {limit 5}}])}
  [event-store query-text & options]
  (apply logic/interface-hybrid-search-failures event-store query-text options))

(defn hybrid-search-patterns
  "Search success patterns using hybrid graph + embedding search.

   Specialized search for finding relevant success patterns.

   Args:
     event-store: Grain event store
     query-text: Description of the pattern to find
     opts:
       :seed-pattern-uri - Optional seed pattern URI for graph expansion
       :limit - Maximum results (default 5)

   Returns:
     Vector of success pattern concepts with scores"
  {:arglists '([event-store query-text & {:keys [seed-pattern-uri limit]
                              :or {limit 5}}])}
  [event-store query-text & options]
  (apply logic/interface-hybrid-search-patterns event-store query-text options))

(defn build-ontology-context-with-embeddings
  "Build comprehensive ontology context using hybrid search.

   Enhanced version of build-ontology-context that leverages embeddings
   when available to provide more semantically relevant results.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Primary problem URI
       :problem-description - Natural language description (for embedding search)
       :required-patterns - Success patterns that should be used
       :user-domain - Domain context

   Returns:
     Same structure as build-ontology-context but with embedding-enhanced results"
  [event-store opts]
  (queries/build-ontology-context-with-embeddings event-store opts))

(defn analyze-fields-for-embedding
  "Explicitly analyze schema to determine embeddable fields using LLM.

   Unlike detect-embeddable-fields (heuristic-based), this uses an LLM
   to examine actual data samples and determine which fields contain
   semantic content worth embedding for similarity search.

   This is an EXPLICIT step - call it BEFORE embedding to get recommendations.
   Results can be reviewed, cached, or modified before use.

   Workflow:
   1. Call this function with schema + sample data
   2. Review the recommended fields and reasoning
   3. Optionally modify the field list
   4. Use embed-with-fields to embed data

   Args:
     ctx: Application context with sheet service
     schema: Malli [:map ...] schema to analyze
     opts:
       :sample-data - Sample records matching schema (recommended: 3-5)
       :confidence-threshold - Min confidence to include (default: 0.7)
       :sheet-id - Pre-built workflow sheet ID (optional, for reuse)

   Returns:
     {:embeddable-fields [:field1 :field2 ...]
      :confidence-scores {:field1 0.92 :field2 0.85}
      :reasoning {:field1 \"Contains natural language...\" ...}
      :method :llm
      :trace-id #uuid \"...\"}"
  {:arglists '([ctx schema & {:keys [sample-data confidence-threshold sheet-id]
                 :or {confidence-threshold 0.7}}])}
  [ctx schema & options]
  (apply logic/analyze-fields-for-embedding ctx schema options))

(defn build-field-analyzer-workflow!
  "Build the field analyzer ORC workflow for reuse.

   Returns a sheet-id that can be passed to analyze-fields-for-embedding
   for efficient repeated analysis.

   Args:
     ctx: Application context with sheet service

   Returns:
     sheet-id (uuid)"
  [ctx]
  (logic/build-field-analyzer! ctx))

(defn detect-embeddable-fields-heuristic
  "Schema + data heuristic detection (no LLM).

   Use this when:
   - LLM analysis is not needed (simple/known schemas)
   - LLM is unavailable or too slow
   - Testing or development

   Args:
     schema: Malli schema to analyze
     sample-data: Optional sample records
     opts:
       :threshold - Min confidence (default: 0.6)

   Returns:
     {:embeddable-fields [...]
      :confidence-scores {...}
      :reasoning {...}
      :method :heuristic}"
  {:arglists '([schema & [sample-data opts]])}
  [schema & options]
  (apply logic/interface-detect-embeddable-fields-heuristic schema options))

(defn embed-with-fields
  "Embed data using specified fields.

   Combines the values of specified fields into text and generates an embedding.

   Args:
     data: Record to embed
     fields: Collection of field names to include
     opts: {:model-id \"...\"}

   Returns:
     Embedding vector (384 dimensions for default model)"
  {:arglists '([data fields & {:keys [model-id] :as opts}])}
  [data fields & options]
  (apply logic/embed-with-fields data fields options))

(defn analyze-and-embed
  "Convenience function: analyze schema and embed data in one call.

   For simple cases where you don't need to review/modify the field selection.
   Uses the hybrid approach: tries LLM analysis, falls back to heuristics.

   Args:
     ctx: Application context (or nil for heuristic-only)
     schema: Malli schema
     data: Record to embed
     opts:
       :sample-data - Sample records for analysis
       :confidence-threshold - Min confidence (default: 0.7)
       :fallback-to-heuristic - If true, use heuristics when LLM fails (default: true)

   Returns:
     {:embedding [...] (384-dim vector)
      :fields-used [...]
      :method :llm or :heuristic}"
  {:arglists '([ctx schema data & {:keys [sample-data confidence-threshold fallback-to-heuristic]
                      :or {confidence-threshold 0.7 fallback-to-heuristic true}}])}
  [ctx schema data & options]
  (apply logic/analyze-and-embed ctx schema data options))

(defn preload-ontology-cache!
  "Read the standalone graph and preload cached ORC read models.

   Call this at application startup to avoid cold-start latency.
   Grain's rmp/project provides L1/L2 caching, but the first call
   must build the projection from events.

   Preloads:
   - Concepts graph
   - Tree profiles
   - Concept embeddings
   - ColBERT index mappings

   Returns map with:
   - :preloaded - vector of preloaded read model names
   - :elapsed-ms - time taken to preload"
  [ctx]
  (queries/preload-ontology-cache! ctx))

(defn get-colbert-index-for-ontology
  "Get the ColBERT index-id and metadata associated with an ontology.

   Returns nil if no ColBERT index exists for this ontology.

   Example:
   ```clojure
   (get-colbert-index-for-ontology ctx ontology-id)
   ;; => {:colbert-index-id uuid
   ;;     :index-name \"ontology-abc123\"
   ;;     :colbert-fields [:label :description]
   ;;     :document-count 150
   ;;     :indexed-at \"2024-01-15T...\"}
   ```"
  [ctx ontology-id]
  (read-models/get-colbert-index-for-ontology ctx ontology-id))

(defn find-self-patterns
  "Retrieve patterns from the current tree for self-reinforcing learning.

   Unlike find-success-patterns which aggregates across trees, this returns
   the patterns accumulated by THIS specific tree. This enables single-tree
   training scenarios where the tree learns from its own execution history.

   This function is domain-agnostic - works for drone control, legal review,
   sales outreach, construction planning, or any other domain.

   Args:
     ctx: System context with :event-store
     tree-id: UUID of the tree to get patterns for
     opts: {:min-confidence double} - minimum confidence threshold (default 0.2)

   Returns:
     {:strengths [{:uri :label :description :confidence :count
                   :context-conditions :action-taken :domain-type :expected-outcome} ...]
      :weaknesses [{:uri :description :frequency :severity :triggers
                    :failure-context :attempted-action :domain-type} ...]}

   Returns nil if tree has no profile or ctx/tree-id is nil.

   Example usage for different domains:
   ```clojure
   ;; Drone control
   (find-self-patterns ctx drone-tree-id {:min-confidence 0.3})
   ;; => {:strengths [{:context-conditions {:velocity 0.3 :battery 60}
   ;;                  :action-taken {:type \"stabilize\" :target [0 0 1.0]}
   ;;                  :domain-type \"drone-control\"}]}

   ;; Legal review
   (find-self-patterns ctx legal-tree-id {:min-confidence 0.5})
   ;; => {:strengths [{:context-conditions {:contract-value 1500000 :risk-score 0.3}
   ;;                  :action-taken {:type \"approve\" :reviewer \"senior-partner\"}
   ;;                  :domain-type \"legal-review\"}]}
   ```"
  [ctx tree-id opts]
  (queries/find-self-patterns ctx tree-id opts))

(defn build-actionable-context
  "Build complete actionable context combining self-patterns for LLM injection.

   This is the main entry point for generating context that enables
   true learning improvement during training. Works for any domain.

   Args:
     ctx: Context with event-store
     tree-id: Current tree ID
     problem-type: Problem type URI
     opts: Options for formatting
       :max-items - Max patterns to include (default 5)
       :include-patterns - Include success patterns (default true)
       :include-failures - Include failure patterns (default true)

   Returns:
     Map with:
       :formatted-context - Markdown string for LLM injection
       :self-patterns - Raw self-pattern data
       :has-patterns? - Whether any patterns were found
       :strength-count - Number of strength patterns
       :weakness-count - Number of weakness patterns

   The formatted-context produces actionable rules like:
   ```markdown
   ### Learned Rules from Success Patterns
   - When battery=15, altitude=2.0: return-home to [0 0 0] (90% success, 5 episodes)
   - When velocity=0.3, battery=60: navigate to [2 0 1.0] (85% success, 3 episodes)

   ### Patterns to Avoid
   - **Logical reasoning defects** (frequency: 100%)
   ```"
  {:arglists '([ctx tree-id problem-type & [opts]])}
  [ctx tree-id problem-type & options]
  (apply queries/interface-build-actionable-context ctx tree-id problem-type options))

(def learned-rule-events
  "Event types that affect the learned-rules read model"
  logic/interface-learned-rule-events)

(defn get-tree-rules
  "Get learned rules for a specific tree.

   Returns vector of rules extracted from successful episodes:
   [{:rule-id uuid
     :condition {:field value ...}
     :action {:type \"action\" :target ...}
     :confidence 0.92
     :success-rate 0.95
     :evidence-episodes [uuid ...]
     :problem-type \"problem:Navigation\"
     :domain-type \"drone-control\"
     :extracted-at \"2024-01-15T...\"}]"
  [ctx tree-id]
  (read-models/get-tree-rules ctx tree-id))

(defn get-all-learned-rules
  "Get all learned rules for all trees.

   Returns map of {tree-id -> [rule ...]}"
  [ctx]
  (read-models/get-all-learned-rules ctx))

(defn find-rules-by-problem
  "Find rules that were extracted for a specific problem type.

   Returns flat vector of rules across all trees."
  [ctx problem-type]
  (read-models/find-rules-by-problem ctx problem-type))

(defn find-rules-by-condition
  "Find rules that match given condition criteria.

   conditions: Map of condition key-value pairs to match
   Returns rules where all specified conditions are present."
  [ctx conditions]
  (read-models/find-rules-by-condition ctx conditions))

(defn learned-rules-statistics
  "Get statistics about learned rules.

   Returns:
   {:total-rules N
    :trees-with-rules N
    :by-problem-type {\"problem:Nav\" 5 ...}
    :by-domain-type {\"drone-control\" 8 ...}
    :avg-confidence 0.88
    :avg-success-rate 0.92}"
  [ctx]
  (read-models/learned-rules-statistics ctx))

(defn extract-rules
  "High-level API to extract rules from a tree's successful episodes.

   Domain-agnostic: Works with any domain by accepting domain-type and domain-description.

   Args:
   - ctx: Context with event-store
   - tree-id: Tree to analyze
   - options:
     - :domain-type - Domain identifier, e.g. 'drone-control', 'legal-review' (required)
     - :domain-description - Human-readable context for LLM (required)
     - :min-episodes - Minimum episodes required (default 5)

   Returns map with:
   - :extracted - count of rules extracted
   - :analyzed-episodes - count of episodes analyzed
   - :rules - vector of rule maps (with condition-description and action-description)
   - :skipped - true if insufficient episodes
   - :domain-type - Domain that was analyzed"
  [ctx tree-id opts]
  (queries/extract-rules ctx tree-id opts))

(def site-registry-events
  "Event types that affect site registry read model."
  logic/interface-site-registry-events)

(defn get-site-by-domain
  "Get a registered site by its domain."
  [ctx domain]
  (read-models/get-site-by-domain ctx domain))

(defn get-all-sites
  "Get all registered sites."
  [ctx]
  (read-models/get-all-sites ctx))

(defn get-trusted-sites
  "Get sites with trust score above threshold, sorted by trust.
   Options:
   - :min-trust - Minimum trust score (default 0.5)
   - :limit - Maximum sites to return"
  {:arglists '([ctx & [opts]])}
  [ctx & options]
  (apply queries/get-trusted-sites ctx options))

(defn get-site-patterns
  "Get learned patterns for a site.
   Options:
   - :pattern-type - Filter by pattern type (:navigation, :extraction, etc.)"
  {:arglists '([ctx domain & [opts]])}
  [ctx domain & options]
  (apply queries/get-site-patterns ctx domain options))

(defn get-sites-requiring-headed
  "Get sites that require headed browser mode."
  [ctx]
  (read-models/get-sites-requiring-headed ctx))

(defn site-registry-statistics
  "Get statistics about the site registry.
   Returns:
   - :total-sites - Total registered sites
   - :by-category - Map of category to count
   - :by-discovered-via - Map of discovery method to count
   - :sites-requiring-headed - Count of headed-mode sites
   - :total-patterns - Total learned patterns
   - :avg-trust-score - Average trust score"
  [ctx]
  (read-models/site-registry-statistics ctx))

(defn record-action-outcome
  "Record the outcome of an action for future learning.

   This is the general-purpose recording function that can be used by
   any code executor to record outcomes. It wraps the existing ontology
   commands with convenient defaults.

   Args:
     ctx: Context with event-store
     outcome-map:
       :tree-id - UUID of the behavior tree (required)
       :domain - Domain/site where action occurred (e.g., 'rent.com')
       :action-type - Keyword like :click, :scroll, :navigate, :fill
       :action-target - The target of the action (selector, URL, etc.)
       :success - Boolean indicating if action succeeded
       :outcome-type - Keyword like :success, :failure, :bot-detection, :redirect, :timeout
       :error-message - Optional error message
       :redirected-to - Optional URL if redirected
       :browser-mode - Optional :headed or :headless
       :page-url - Optional current page URL

   Returns:
     Command result map

   Example:
     (record-action-outcome ctx
       {:tree-id sheet-id
        :domain \"rent.com\"
        :action-type :click
        :action-target \"@e123\"
        :success false
        :outcome-type :bot-detection
        :redirected-to \"ratelimited.rent.com\"})"
  {:arglists '([ctx {:keys [tree-id domain action-type action-target success
               outcome-type error-message redirected-to
               browser-mode page-url]
        :as outcome-map}])}
  [ctx outcome-map]
  (commands/record-action-outcome ctx outcome-map))

(defn query-action-outcomes
  "Query past outcomes for similar actions.

   Searches the tree's weakness and strength records for matching
   domain and action-type patterns.

   Args:
     ctx: Context with event-store
     tree-id: UUID of the behavior tree
     query-map:
       :domain - Domain to filter by (optional)
       :action-type - Action type to filter by (optional)
       :limit - Max results (default 10)

   Returns:
     Vector of outcome maps:
     [{:success false
       :domain \"rent.com\"
       :action-type :click
       :outcome-type :bot-detection
       :context {...}
       :recorded-at \"...\"}]"
  {:arglists '([ctx tree-id {:keys [domain action-type limit] :or {limit 10}}])}
  [ctx tree-id options]
  (queries/query-action-outcomes ctx tree-id options))

(defn check-action-safe
  "Check if an action should be attempted based on past outcomes.

   Queries past outcomes and calculates failure rate. Returns a
   recommendation based on historical data.

   Args:
     ctx: Context with event-store
     tree-id: UUID of the behavior tree
     domain: Domain/site (string or keyword)
     action-type: Action type (keyword like :click, :scroll)

   Returns:
     {:safe true/false
      :failure-rate 0.0-1.0
      :success-count N
      :failure-count N
      :has-prior-outcomes true/false
      :recommendation :proceed/:caution/:skip
      :failures [...] - list of past failures if any}"
  [ctx tree-id domain action-type]
  (queries/check-action-safe ctx tree-id domain action-type))

(defn get-action-recommendations
  "Get recommended strategies for actions on a domain based on learnings.

   Analyzes past outcomes to provide actionable recommendations for
   behavior tree nodes.

   Args:
     ctx: Context with event-store
     tree-id: UUID of the behavior tree
     domain: Domain/site to get recommendations for

   Returns:
     {:domain \"rent.com\"
      :recommendations
        [{:action-type :click
          :safe false
          :failure-rate 1.0
          :recommendation :skip
          :alternative \"Use scroll-only strategy\"}
         {:action-type :scroll
          :safe true
          :failure-rate 0.0
          :recommendation :proceed}]}"
  [ctx tree-id domain]
  (queries/get-action-recommendations ctx tree-id domain))

(defn build-ontology-from-sources
  "Build ontology from multiple sources using the evolutionary pipeline.

   This is the main entry point for batch ontology construction.

   Args:
     ctx: Context with :event-store
     params:
       :sources - Vector of source maps:
         [{:path \"data.csv\" :type \"csv\"}
          {:path \"database.db\" :type \"sql\"}
          {:content \"text...\" :type \"text\"}]
       :config - Configuration:
         :base-uri - Ontology namespace (default: http://ontology.local/)
         :similarity-threshold - Entity resolution threshold (default: 0.85)
         :enable-colbert? - Enable ColBERT indexing (default: true)
         :enable-embeddings? - Enable embedding generation (default: true)

   Supported source types:
     - \"csv\" - CSV files (provide :entity-column, :entity-type in config)
     - \"text\" - Text documents (provide :domain in config)
     - \"sql\"/\"sqlite\"/\"db\" - SQLite databases
     - \"json\" - JSON files/data (provide :domain in config for better extraction)
     - \"rdf\" - Deterministic N-Triples RDF imports

   Returns:
     {:ontology-id uuid
      :build-id uuid
      :total-sources int
      :total-concepts int
      :total-triples int
      :ttl-output string
      :events [...]}

   Example:
     (build-ontology-from-sources ctx
       {:sources [{:path \"/data/programs.csv\" :type \"csv\"}
                  {:path \"/data/ipeds.db\" :type \"sql\"}]
        :config {:base-uri \"http://education.ai/\"
                 :entity-column \"name\"
                 :entity-type \"Program\"}})"
  [ctx params]
  (commands/build-ontology-from-sources ctx params))

(defn evolve-ontology
  "Evolve existing ontology with new sources (incremental mode).

   Extends an existing ontology without rebuilding from scratch.

   Args:
     ctx: Context with :event-store
     params:
       :ontology-id - UUID of existing ontology
       :sources - Vector of new sources to add
       :config - Configuration options

   Returns:
     Same as build-ontology-from-sources"
  [ctx params]
  (commands/evolve-ontology ctx params))

(defn extract-from-csv
  "Extract ontology from CSV data using the CSV ORC sheet.

   Args:
     ctx: ORC context
     opts:
       :csv-data - Parsed CSV as vector of maps, or CSV string
       :entity-column - Column to use as entity label
       :entity-type - OWL class name
       :base-uri - Ontology namespace

   Returns:
     {:status :success/:failed
      :entities [...] :relationships [...] :tbox {...}
      :owl-output string}"
  [ctx opts]
  (logic/extract-from-csv ctx opts))

(defn extract-from-text
  "Extract ontology from text using the taxonomy ORC sheet.

   Args:
     ctx: ORC context
     opts:
       :source-text - Text to extract concepts from
       :domain - Domain context (e.g., \"artificial intelligence\")
       :existing-concepts - Concepts to avoid re-extracting

   Returns:
     {:status :success/:failed
      :concepts [...] :relationships [...] :top-concepts [...]
      :skos-output string}"
  [ctx opts]
  (logic/extract-from-text ctx opts))

(defn extract-from-sql
  "Extract ontology from SQLite database using the SQL ORC sheet.

   Args:
     ctx: ORC context
     opts:
       :db-path - Path to SQLite database file
       :base-uri - Ontology namespace (default: http://example.org/db#)
       :max-tables - Max tables to process (default: 50)
       :max-instances - Max instances per table for A-box (default: 10)

   Returns:
     {:status :success/:failed
      :domain - Detected domain
      :domain-description - Domain description
      :tbox {:classes [...] :object-properties [...] :datatype-properties [...]}
      :abox [...]
      :owl-output string
      :statistics {...}}"
  [ctx opts]
  (logic/extract-from-sql ctx opts))

(defn extract-unified
  "Unified ontology extraction - auto-detects source type.

   This is the simplest API - provide a source and let the system
   determine the appropriate extraction method.

   Args:
     ctx: ORC context
     source: Map with:
       :path - File path (optional if :content provided)
       :content - Inline content (optional if :path provided)
       :type - Source type override (optional, will auto-detect)
       Plus type-specific options

   Auto-detection:
     - .csv → CSV extraction
     - .db/.sqlite → SQL extraction
     - .txt/.md → Text extraction
     - .json → JSON extraction (planned)
     - RDF content with :type \"rdf\" → deterministic N-Triples import

   Returns:
     {:status :success/:failed/:not-implemented
      :source-type :csv/:text/:sql/:json/:rdf
      ...type-specific outputs...}"
  [ctx source]
  (logic/extract-unified ctx source))

(defn extract-unified-multiple
  "Extract ontologies from multiple sources with unified API.

   Returns map of source identifiers to extraction results."
  [ctx sources]
  (logic/extract-unified-multiple ctx sources))

(integrations/register-capabilities!
 :ontology
 {:build-ontology-context #'build-ontology-context
  :build-task-signature #'logic/build-task-signature
  :classify-behaviors #'classify-behaviors
  :classify-task #'classify-task
  :default-rerank-timeout-ms #'logic/default-rerank-timeout-ms
  :existing-domain-families #'existing-domain-families
  :format-context-for-llm #'format-context-for-llm
  :get-behavior-mint-by-logical-action #'get-behavior-mint-by-logical-action
  :get-claim-set-version #'get-claim-set-version
  :get-concept-by-uri #'get-concept-by-uri
  :get-description #'get-description
  :get-narrower-concepts #'get-narrower-concepts
  :hybrid-search #'hybrid-search
  :rerank-fallback-sources #'logic/rerank-fallback-sources
  :search-descriptions #'search-descriptions})

(integrations/register-code-functions!
 :ontology
 {'ai.obney.orc.ontology.core.field-analyzer/extract-field-metadata #'logic/extract-field-metadata
  'ai.obney.orc.ontology.core.field-analyzer/filter-embeddable-fields #'logic/filter-embeddable-fields
  'ai.obney.orc.ontology.core.rule-extraction/group-episodes-by-outcome #'logic/group-episodes-by-outcome
  'ai.obney.orc.ontology.core.rule-extraction/load-episodes-for-extraction #'queries/load-episodes-for-extraction
  'ai.obney.orc.ontology.sheets.csv-ontology/analyze-csv-structure-fn #'logic/analyze-csv-structure-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/build-abox-fn #'logic/csv-ontology-build-abox-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/build-csv-context-fn #'logic/build-csv-context-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/build-tbox-fn #'logic/csv-ontology-build-tbox-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/collect-entity-definitions-fn #'logic/collect-entity-definitions-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/collect-hierarchies-fn #'logic/collect-hierarchies-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/collect-mapping-issues-fn #'logic/collect-mapping-issues-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/compute-csv-statistics-fn #'logic/compute-csv-statistics-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/detect-temporal-columns-fn #'logic/detect-temporal-columns-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/extract-temporal-metadata-fn #'logic/extract-temporal-metadata-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/identify-categorical-columns-fn #'logic/identify-categorical-columns-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/load-csv-fn #'logic/load-csv-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/prepare-entity-context-fn #'logic/prepare-entity-context-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/prepare-hierarchy-context-fn #'logic/prepare-hierarchy-context-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/prepare-mapping-validation-fn #'logic/prepare-mapping-validation-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/prepare-relationship-context-fn #'logic/prepare-relationship-context-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/prepare-validation-context-fn #'logic/prepare-validation-context-fn
  'ai.obney.orc.ontology.sheets.csv-ontology/serialize-to-owl-fn #'logic/csv-ontology-serialize-to-owl-fn
  'ai.obney.orc.ontology.sheets.json-ontology/build-abox-fn #'logic/json-ontology-build-abox-fn
  'ai.obney.orc.ontology.sheets.json-ontology/build-tbox-fn #'logic/json-ontology-build-tbox-fn
  'ai.obney.orc.ontology.sheets.json-ontology/deduplicate-concepts-fn #'logic/json-ontology-deduplicate-concepts-fn
  'ai.obney.orc.ontology.sheets.json-ontology/extract-concepts-from-analysis-fn #'logic/extract-concepts-from-analysis-fn
  'ai.obney.orc.ontology.sheets.json-ontology/parse-json-fn #'logic/parse-json-fn
  'ai.obney.orc.ontology.sheets.json-ontology/serialize-to-owl-fn #'logic/json-ontology-serialize-to-owl-fn
  'ai.obney.orc.ontology.sheets.ontology-exploration/apply-auto-fixes-fn #'logic/apply-auto-fixes-fn
  'ai.obney.orc.ontology.sheets.ontology-exploration/build-taxonomy-fn #'logic/build-taxonomy-fn
  'ai.obney.orc.ontology.sheets.ontology-exploration/deduplicate-concepts-fn #'logic/ontology-exploration-deduplicate-concepts-fn
  'ai.obney.orc.ontology.sheets.ontology-exploration/extract-labels-fn #'logic/extract-labels-fn
  'ai.obney.orc.ontology.sheets.ontology-exploration/ingest-sources-fn #'logic/ingest-sources-fn
  'ai.obney.orc.ontology.sheets.ontology-exploration/quality-is-good? #'logic/quality-is-good?
  'ai.obney.orc.ontology.sheets.ontology-exploration/serialize-to-skos-fn #'logic/serialize-to-skos-fn
  'ai.obney.orc.ontology.sheets.ontology-exploration/validate-causal-relations-fn #'logic/validate-causal-relations-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/analyze-schema-fn #'logic/analyze-schema-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/build-schema-context-fn #'logic/build-schema-context-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/build-sql-tbox-fn #'logic/build-sql-tbox-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/collect-table-entities-fn #'logic/collect-table-entities-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/compute-sql-statistics-fn #'logic/compute-sql-statistics-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/connect-to-database-fn #'logic/connect-to-database-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/extract-sample-instances-fn #'logic/extract-sample-instances-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/parse-table-entities-fn #'logic/parse-table-entities-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/prepare-table-context-fn #'logic/prepare-table-context-fn
  'ai.obney.orc.ontology.sheets.sql-ontology/serialize-sql-to-owl-fn #'logic/serialize-sql-to-owl-fn})
