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
  (:require [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.graph :as graph]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.field-analyzer :as field-analyzer]
            [ai.obney.orc.ontology.core.field-analyzer-workflow :as fa-workflow]
            [ai.obney.orc.ontology.core.discovery :as discovery]
            [ai.obney.orc.ontology.core.rule-extraction :as rule-extraction]
            [ai.obney.orc.ontology.core.commands] ;; Register defcommand handlers for tree profiles
            [ai.obney.orc.ontology.core.evolutionary-commands] ;; Register defcommand handlers for evolutionary builder
            [ai.obney.orc.ontology.core.todo-processors :as todo-processors] ;; Register todo processors for auto-learning
            [ai.obney.orc.ontology.core.harvest] ;; EL-4: register the harvest processor
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.domain-penalty :as domain-penalty]
            [ai.obney.orc.ontology.core.task-classifier :as task-classifier]
            [ai.obney.orc.ontology.core.seeds :as seeds]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [com.brunobonacci.mulog :as u]))

(defn- colbert-fn
  "Lazily resolve a fn from the colbert component. Returns nil when colbert
   is not on the classpath, so the ontology builds and runs without it — the
   ColBERT-backed Living-Description search simply returns no results, while
   graph + embedding retrieval is unaffected. (ColBERT is an optional Layer-5
   upgrade; see COMPONENT-MAP.md.)"
  [fn-name]
  (try (requiring-resolve (symbol "ai.obney.orc.colbert.interface" (name fn-name)))
       (catch Throwable _ nil)))

;; =============================================================================
;; Static Ontology Access
;; =============================================================================

(defn get-static-concepts
  "Get concepts from static ontology definitions.
   Options:
   - :scope - Filter by :failure, :success, or :problem"
  ([]
   (static/get-all-static-concepts))
  ([{:keys [scope]}]
   (if scope
     (static/get-concepts-by-scope scope)
     (static/get-all-static-concepts))))

(defn get-static-relationships
  "Get all relationships from static ontology definitions."
  []
  (static/get-all-static-relationships))

(defn get-static-concept-by-uri
  "Find a static concept by its URI."
  [uri]
  (static/get-concept-by-uri uri))

(defn get-failure-concept-for-dimension
  "Map evaluation dimension name to failure concept URI.
   E.g., 'Grounding' -> 'failure:Grounding'"
  [dimension-name]
  (static/get-failure-concept-for-dimension dimension-name))

;; =============================================================================
;; Concept Graph Queries (Event-Sourced)
;; =============================================================================

(defn get-concepts
  "Get concepts from the event-sourced concept graph.
   Options:
   - :scope - Filter by scope
   - :broader-uri - Filter by parent concept"
  [ctx & [opts]]
  (rm/get-concepts ctx opts))

(defn get-concept-by-uri
  "Get a single concept by URI from the event-sourced graph."
  [ctx uri]
  (rm/get-concept-by-uri ctx uri))

(defn get-narrower-concepts
  "Get all concepts narrower than (children of) the given URI."
  [ctx uri]
  (rm/get-narrower-concepts ctx uri))

(defn get-broader-concepts
  "Get all concepts broader than (parents of) the given URI."
  [ctx uri]
  (rm/get-broader-concepts ctx uri))

(defn concept-statistics
  "Get statistics about the concept graph."
  [ctx]
  (rm/concept-statistics ctx))

;; =============================================================================
;; Tree Profile Queries
;; =============================================================================

(defn get-tree-profile
  "Get profile for a specific tree including strengths, weaknesses, and problem mappings."
  [ctx tree-id]
  (rm/get-tree-profile ctx tree-id))

(defn get-description
  "Return the CURRENT Living Description body for the given target.

   Granularity is one of :node-type, :node-instance, :tree-fingerprint.
   Target-id shape depends on the granularity:
   - :node-type        → keyword (e.g. :llm, :map-each)
   - :node-instance    → [sheet-id node-id] tuple of UUIDs
   - :tree-fingerprint → string (the canonical-tree-raw hash)

   Returns nil if no description has been recorded for the target."
  [ctx granularity target-id]
  (rm/get-description ctx granularity target-id))

(defn get-description-history
  "Return the full chronological history of every description version
   recorded for the (granularity, target-id) target. Each entry is
   {:body :recorded-at :event-id}. Empty vector if none recorded."
  [ctx granularity target-id]
  (rm/get-description-history ctx granularity target-id))

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
  (seeds/seed-baseline-corpus! ctx))

(defn baseline-seeds
  "Pure-data inspection: return the loaded baseline seed catalog as
   `{:node-types <vec> :tree-classes <vec> :behavioral-subtrees <vec>}`.

   For consumers and tests that want to walk the corpus without
   dispatching commands (e.g., asserting invariants over seed bodies,
   diffing against an app-specific extension)."
  []
  (seeds/baseline-seeds))

(defn get-consolidation-threshold
  "C-2a-3a: return the configured consolidation threshold for a
   target-type keyword. Falls back to the default 10 events when no
   per-target-type override has been set via
   :ontology/set-consolidation-threshold."
  [ctx target-type]
  (rm/get-consolidation-threshold ctx target-type))

(defn get-consolidation-delta
  "C-2a-3a: return the current delta-counter (events-since-last-
   consolidation) for the given (target-type, target-id) target.
   Returns 0 when no events have ticked the counter."
  [ctx target-type target-id]
  (rm/get-consolidation-delta ctx target-type target-id))

(defn get-tree-class-judge-averages
  "EL-4 (ADR 0015): return {judge-name -> mean-score} across a
   :tree-class's lifetime, or nil when no judge scores exist for it.
   Standing read-model queryable at harvest time; parity with the
   consolidator's on-demand tree-class-aggregate-metrics :judge-averages."
  [ctx tree-class-id]
  (rm/get-tree-class-judge-averages ctx tree-class-id))

(defn get-tree-class-for-sheet
  "CV-2 (ADR 0017 decision 3): return the :tree-class id assigned to
   `source-sheet-id` (via the task-classified sheet->class join), or nil
   when the sheet was never classified."
  [ctx source-sheet-id]
  (rm/get-tree-class-for-sheet ctx source-sheet-id))

(defn get-consolidation-budget
  "C-2a-3c: return the configured hourly consolidation budget for a
   target-type keyword. Falls back to default 100 when no per-target-type
   override has been set via :ontology/set-consolidation-budget."
  [ctx target-type]
  (rm/get-consolidation-budget ctx target-type))

(defn get-recent-consolidation-count
  "C-2a-3c: return how many :*-description-updated events have fired for
   the given target-type in the rolling last-hour window. Used by the
   consolidator's budget gate."
  [ctx target-type]
  (rm/get-recent-consolidation-count ctx target-type))

(defn get-living-description-enabled?
  "Gap-1: return the system-level Living Description opt-in flag.
   Default false when no `:ontology/set-living-description-enabled`
   event has been emitted. When true, the writing side of the loop
   activates (consolidator, threshold tracking, per-event evaluator
   runtime). Read side (R-Inject's :auto-classify? in :rlm config)
   is independent."
  [ctx]
  (rm/get-living-description-enabled? ctx))

(defn get-reindex-config
  "C-2b-1: return the current ColBERT re-index configuration —
   {:reindex-threshold-events N :reindex-timer-minutes T}. Falls back
   to defaults (10 events, 5 minutes) when no :ontology/set-reindex-config
   command has been emitted."
  [ctx]
  (rm/get-reindex-config ctx))

(defn get-reindex-state
  "C-2b-1: return the current re-index state for the ColBERT description
   corpus — {:events-since-last-rebuild N :last-rebuild-timestamp
   ISO-string :index-built? bool}. The re-index processor reads this to
   decide threshold-or-timer trigger firing."
  [ctx]
  (rm/get-reindex-state ctx))

(defn bootstrap-reindex!
  "C-2b-1: bootstrap the ColBERT ontology-descriptions index. Call this
   once during application startup, AFTER tp/start has wired up the
   re-index processor. If descriptions already exist in the event store
   AND no index has been built yet, this triggers an initial rebuild so
   the search API has something to retrieve from immediately.

   Idempotent — if the index already exists, this is a no-op (the
   threshold-or-timer trigger handles future rebuilds)."
  [ctx]
  (todo-processors/maybe-rebuild! ctx))

;; =============================================================================
;; C-2b-1 — search-descriptions: parameterized retrieval API
;; =============================================================================

(defn- latest-ontology-descriptions-index
  "Return the most-recently-created active ColBERT index with name
   'ontology-descriptions', or nil if none exists."
  [ctx]
  (let [candidates (filter #(= "ontology-descriptions" (:index-name %))
                           (if-let [list-indexes (colbert-fn 'list-indexes)]
                             (list-indexes ctx)
                             []))]
    (when (seq candidates)
      (last (sort-by :created-at candidates)))))

(defn- granularity-name
  "Granularity field round-trips through ColBERT's Python bridge as a
   JSON string, so a stored value of `:node-type` comes back as
   \"node-type\". Compare by name to bridge that gap."
  [v]
  (cond
    (keyword? v) (name v)
    (string? v)  v
    :else        (str v)))

(defn- normalize-search-result
  "The ColBERT Python bridge returns snake_case keys (`:document_id`,
   `:document_metadata`). The docstring on `colbert/search` promises
   kebab-case — that's a doc-vs-code mismatch in the colbert component
   that we work around here. Also re-keywordize known metadata fields
   so downstream consumers don't have to deal with stringified keywords
   from the JSON roundtrip."
  [r]
  (let [meta (or (:document-metadata r) (:document_metadata r))
        norm-meta (when meta
                    (cond-> meta
                      (:granularity meta) (update :granularity
                                                   #(if (keyword? %) % (keyword %)))))]
    (-> r
        (assoc :document-id (or (:document-id r) (:document_id r)))
        (assoc :document-metadata norm-meta)
        (dissoc :document_id :document_metadata))))

(def ^:private rerank-over-fetch-multiplier
  "Default over-fetch when reranking: pull 2x the caller's :k from
   ColBERT so the reranker has signal to actually re-order."
  2)

(def ^:private rerank-hard-cap
  "Hard cap on the candidate count fed to the reranker. Bounds the
   structured-output prompt size regardless of caller :k."
  50)

(defn- rerank-fetch-k
  "How many candidates to pull from ColBERT when reranking. Caller asks
   for top-:k; we pull 2x that for the reranker to choose from, hard-
   capped at rerank-hard-cap."
  [k]
  (min rerank-hard-cap (max k (* rerank-over-fetch-multiplier k))))

;; =============================================================================
;; EL-2 (ADR 0015, emergence loop): candidate evidence enrichment
;;
;; The reranker decision must READ the judge-grounded evidence the flywheel
;; writes (`:avoid-when`/strengths) — closing the link that exists-but-is-
;; ignored today. ColBERT hands the reranker only each candidate's `:content`
;; (the description SUMMARY) + score + metadata, so a strong shape/summary
;; match force-fits a wrong-domain behavior even when its `:avoid-when` says
;; "not this case". We enrich each candidate map, BEFORE rerank!, with its
;; body's `:avoid-when` (the top-level domain guards + the per-weakness guards)
;; and compact strengths/weaknesses. The reranker output contract
;; ({:document-id :reasoning :fitness-score}) is UNCHANGED — this is purely
;; input-side.
;;
;; SCOPE (the E3.5 wrong-scope trap, root-caused not assumed): minted
;; behavioral-subtree bodies AND minted tree-class bodies are recorded under
;; :target-type :tree-fingerprint (see mint-behavioral-subtree /
;; record-tree-description in commands.clj), while the candidate's
;; :document-metadata :granularity reads :behavioral-subtree / :tree-class.
;; A get-description keyed naively on the candidate's stated granularity
;; returns nil and ships a silent no-op. We therefore try the candidate's
;; stated scope FIRST, then fall back to :tree-fingerprint (the universal
;; store for minted/seeded bodies; tree-class seeds are dual-emitted there).
;; =============================================================================

(def ^:private evidence-max-entries
  "Keep the enrichment compact: cap how many strengths/weaknesses ride along
   so the structured-output prompt stays small."
  3)

(defn- coerce-evidence-target-id
  "A candidate's :document-metadata :target-id round-trips through ColBERT's
   JSON bridge as a string; coerce to UUID when it parses as one (minted
   bodies key the descriptions read-model by UUID). Non-UUID strings (e.g.
   seed fingerprints like \"seed:tree:ChunkedExtraction\") pass through."
  [tid]
  (cond
    (uuid? tid) tid
    (string? tid) (try (java.util.UUID/fromString tid) (catch Exception _ tid))
    :else tid))

(defn- fetch-evidence-body
  "Return the candidate's Living Description body, fetched from the RIGHT
   read-model scope. Tries the candidate's stated granularity, then
   :tree-fingerprint, then :tree-class (deduped). Returns nil if no body."
  [ctx granularity target-id]
  (let [tid (coerce-evidence-target-id target-id)]
    (some (fn [scope] (when scope (get-description ctx scope tid)))
          (distinct [granularity :tree-fingerprint :tree-class]))))

(defn- compact-strengths [strengths]
  (->> strengths
       (take evidence-max-entries)
       (mapv (fn [e] (cond-> {:trait (:trait e)}
                       (:good-when e) (assoc :good-when (:good-when e))
                       (:recommended-pattern e) (assoc :recommended-pattern (:recommended-pattern e)))))))

(defn- compact-weaknesses [weaknesses]
  (->> weaknesses
       (take evidence-max-entries)
       (mapv (fn [e] (cond-> {:trait (:trait e)}
                       (:avoid-when e) (assoc :avoid-when (:avoid-when e))
                       (:recommended-alternative e) (assoc :recommended-alternative (:recommended-alternative e)))))))

(defn- enrich-candidate-evidence
  "Add :avoid-when (top-level body guards + per-weakness guards) and compact
   :strengths/:weaknesses from the candidate's body to the candidate map.
   No-op (returns the candidate unchanged) when no body is found — the
   reranker simply sees the summary, as before."
  [ctx c]
  (let [{:keys [granularity target-id]} (:document-metadata c)
        body (fetch-evidence-body ctx granularity target-id)
        weaknesses (:weaknesses body)
        per-weakness-guards (into [] (keep :avoid-when) weaknesses)
        avoid-when (vec (distinct (concat (:avoid-when body) per-weakness-guards)))]
    (cond-> c
      (seq avoid-when)        (assoc :avoid-when avoid-when)
      (seq (:strengths body)) (assoc :strengths (compact-strengths (:strengths body)))
      (seq weaknesses)        (assoc :weaknesses (compact-weaknesses weaknesses)))))

(defn- apply-rerank
  "JOIN the reranker's delta output back to the original ColBERT
   candidates on :document-id, returning the top-N in the reranker's
   order. Each result carries the original ColBERT fields PLUS
   :reasoning, :fitness-score, and :rerank-source from the reranker.

   EL-2: before reranking, each candidate is enriched with its body's
   judge-grounded evidence (:avoid-when + compact strengths/weaknesses) so
   the reranker can weight DOMAIN fit, not just structural shape. The JOIN
   below still keys on the ORIGINAL candidates, so the enrichment is
   input-only — the returned result shape is unchanged.

   R01: each result is stamped with :rerank-source. On the success
   path the value is :reranker. On the fallback path (reranker threw,
   returned nil, or returned empty) the value is :colbert-fallback
   AND :fitness-score/:reasoning are explicitly nil so downstream
   `(or (:fitness-score x) 0.0)` short-circuits don't mask the
   absence.

   EL-5 (ADR 0016): AFTER the JOIN, the reranker's :fitness-score is
   passed through the deterministic CONTRASTIVE domain penalty
   (domain-penalty/penalize-candidates). The JOIN below keys back onto
   the ENRICHED candidates (not the raw ColBERT ones) so the penalty
   pass can read each candidate's :avoid-when (negative signal) +
   :content/:good-when (positive signal) — the same EL-2 evidence — and
   bite DETERMINISTICALLY where the LLM ignored the veto. The penalty
   re-sorts by the new fitness BEFORE (take k …). Output contract
   ({:document-id :reasoning :fitness-score}) is UNCHANGED (the extra
   :domain-penalty/:cos-* keys are additive observability)."
  [ctx candidates rerank-intent query k]
  (let [enriched (mapv #(enrich-candidate-evidence ctx %) candidates)
        reranked (try
                   (reranker/rerank! ctx
                     {:query query
                      :intent rerank-intent
                      :candidates enriched})
                   (catch Throwable t
                     (u/log ::rerank-failed
                            :query query
                            :error (.getMessage t))
                     nil))]
    (if (seq reranked)
      (let [by-doc-id (into {} (map (juxt :document-id identity)) enriched)
            joined (keep (fn [r]
                           (when-let [orig (get by-doc-id (:document-id r))]
                             (-> orig
                                 (assoc :reasoning (:reasoning r))
                                 (assoc :fitness-score (:fitness-score r))
                                 (assoc :rerank-source :reranker))))
                         reranked)
            ;; EL-5: contrastive domain penalty + re-sort, then take k. The
            ;; scorer is config-selected (ADR 0016 amendment): :colbert (DEFAULT)
            ;; or :embedding. An operator overrides via ctx :domain-penalty-config
            ;; (e.g. {:scorer :embedding :embedding-model "…"}); absent => the
            ;; recalibrated :colbert defaults.
            penalty-config (merge domain-penalty/default-penalty-config
                                  (:domain-penalty-config ctx))
            penalized (domain-penalty/penalize-candidates ctx joined query penalty-config)]
        (vec (take k penalized)))
      (do
        (u/log ::rerank-failed
               :query query
               :note "Reranker returned no results; falling back to pure ColBERT ordering.")
        (->> candidates
             (take k)
             (mapv (fn [c]
                     (-> c
                         (assoc :reasoning nil)
                         (assoc :fitness-score nil)
                         (assoc :rerank-source :colbert-fallback)))))))))

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
  [ctx {:keys [query granularity k rerank-with-intent]
        :or {granularity :all k 10}}]
  (if-let [index (latest-ontology-descriptions-index ctx)]
    (let [fetch-k (if rerank-with-intent
                    (rerank-fetch-k k)
                    (if (= granularity :all) k (* 3 k)))
          raw-results (mapv normalize-search-result
                            ((colbert-fn 'search) ctx
                              {:query query
                               :index-id (:index-id index)
                               :k fetch-k}))
          ;; EL-1a: granularity may be :all (no filter), a single granularity
          ;; keyword (equality, legacy), OR a SET of granularities
          ;; (membership) so a caller can retrieve across more than one axis
          ;; in one query — e.g. classify-task pulling BOTH :tree-fingerprint
          ;; and :tree-class so a previously-recorded tree-class is reachable
          ;; (it was indexed-but-unreachable when only :tree-fingerprint was
          ;; queried). Compare by name because granularity round-trips through
          ;; the ColBERT JSON bridge as a string.
          allowed-names (cond
                          (= granularity :all) nil ;; nil => no filter
                          (set? granularity)   (into #{} (map granularity-name) granularity)
                          :else                #{(granularity-name granularity)})
          filtered (if (nil? allowed-names)
                     raw-results
                     (filterv #(contains? allowed-names
                                          (granularity-name
                                            (-> % :document-metadata :granularity)))
                              raw-results))]
      (if rerank-with-intent
        (apply-rerank ctx filtered rerank-with-intent query k)
        (vec (take k filtered))))
    (do
      (u/log ::search-cold-no-index
             :query query
             :note "No ontology-descriptions index has been built yet. Returning empty results.")
      [])))

;; =============================================================================
;; C-2c-1 — classify-task: pure task → tree-class classification
;; =============================================================================

(def classify-task
  "C-2c-1: pure classification function. Given a task signature +
   optional parent-context summary + threshold, returns a tree-class
   assignment (matched corpus entry above threshold OR fresh UUID mint).

   See `ai.obney.orc.ontology.core.task-classifier/classify-task` for
   the full docstring."
  task-classifier/classify-task)

(def classify-behaviors
  "R05b: pure behavioral classification. Given a task signature +
   threshold (+ optional :structural-context + :top-n), returns top-N
   behavioral-subtree examples from the Layer-2 corpus with reasoning.

   The corpus is FEW-SHOT EXAMPLE MATERIAL for the recursive RLM
   researcher; the model decides reuse/adapt/mint. composes-into
   edges in the concept graph are RETRIEVAL HINTS, not gates.

   See `ai.obney.orc.ontology.core.task-classifier/classify-behaviors`
   for the full docstring."
  task-classifier/classify-behaviors)

(defn get-all-tree-profiles
  "Get all tree profiles."
  [ctx]
  (rm/get-all-tree-profiles ctx))

(defn find-trees-by-problem
  "Find trees that solve a specific problem type."
  [ctx problem-uri]
  (rm/find-trees-by-problem ctx problem-uri))

(defn find-trees-with-weakness
  "Find trees that have a specific weakness/failure pattern."
  [ctx failure-uri]
  (rm/find-trees-with-weakness ctx failure-uri))

(defn tree-profile-statistics
  "Get statistics about tree profiles."
  [ctx]
  (rm/tree-profile-statistics ctx))

;; =============================================================================
;; Node Learning Queries
;; =============================================================================

(defn get-node-type-learnings
  "Get aggregated learnings for a specific node type (e.g., :llm, :code)."
  [ctx node-type]
  (rm/get-node-type-learnings ctx node-type))

(defn get-all-node-learnings
  "Get all node learnings aggregated by type."
  [ctx]
  (rm/get-all-node-learnings ctx))

(defn node-learning-statistics
  "Get statistics about node-level learning."
  [ctx]
  (rm/node-learning-statistics ctx))

;; =============================================================================
;; Serialization (TTL Export)
;; =============================================================================

(defn concepts->turtle
  "Serialize concepts to SKOS Turtle format.
   Arguments:
   - concepts: Map of URI -> concept-map
   - opts: {:base-uri \"...\" :include-scheme? true/false}"
  [concepts & [opts]]
  (serialization/concepts->turtle concepts opts))

(defn tree-profile->turtle
  "Serialize a single tree profile to OWL Turtle format."
  [profile & [opts]]
  (serialization/tree-profile->turtle profile opts))

(defn tree-profiles->turtle
  "Serialize multiple tree profiles to OWL Turtle format."
  [profiles & [opts]]
  (serialization/tree-profiles->turtle profiles opts))

(defn node-experiences->turtle
  "Serialize node learning experiences to OWL Turtle format."
  [experiences & [opts]]
  (serialization/node-experiences->turtle experiences opts))

(defn export-turtle
  "Full ontology export to Turtle format.
   Options:
   - :scope - Filter concepts (:failure, :success, :problem)
   - :include-profiles? - Include tree profiles (default true)
   - :include-experiences? - Include node experiences (default true)
   - :base-uri - Base URI for the ontology"
  [ctx & [opts]]
  (serialization/full-export ctx opts))

(defn validate-turtle
  "Basic validation of generated Turtle syntax."
  [turtle-str]
  (serialization/validate-turtle turtle-str))

;; =============================================================================
;; Classification
;; =============================================================================

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
  [evaluation-result & [opts]]
  (classifier/classify-evaluation evaluation-result opts))

(defn classify-trace-evaluations
  "Classify multiple trace evaluations, grouping failures by type."
  [evaluations & [opts]]
  (classifier/classify-trace-evaluations evaluations opts))

(defn estimate-severity
  "Estimate severity level for a classified failure."
  [failure]
  (classifier/estimate-severity failure))

(defn extract-triggers
  "Extract trigger phrases from failure evidence."
  [failures]
  (classifier/extract-triggers failures))

;; =============================================================================
;; Graph Traversal (Phase 3)
;; =============================================================================

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
  [graph-or-concepts seed-uris & [config]]
  (let [g (if (contains? graph-or-concepts :edges)
            graph-or-concepts
            (graph/concepts->graph graph-or-concepts))]
    (graph/bfs-spreading-activation g seed-uris config)))

(defn link-expansion
  "Fast 2-3 hop expansion for real-time suggestions.

   Use for: autocomplete, quick suggestions, low-latency scenarios

   Args:
     graph: Graph map with :nodes and :edges (or concept map)
     seed-uris: Collection of starting URIs
     opts: {:hops N} - Number of expansion hops (1-3)

   Returns:
     Vector of {:uri :score :path :depth}"
  [graph-or-concepts seed-uris & {:keys [hops] :or {hops 2}}]
  (let [g (if (contains? graph-or-concepts :edges)
            graph-or-concepts
            (graph/concepts->graph graph-or-concepts))]
    (graph/link-expansion g seed-uris :hops hops)))

(defn compute-rrf-scores
  "Reciprocal Rank Fusion for merging multiple ranked result lists.

   Formula: score(d) = sum(1 / (k + rank_i))
   where k = 60 (per original RRF paper)

   Args:
     batches: Vector of result batches, each batch is vector of {:uri :score}
     opts: {:k N} - RRF constant (default 60)

   Returns:
     Vector of [uri fused-score] sorted by fused score descending"
  [batches & {:keys [k] :or {k 60}}]
  (graph/compute-rrf-scores batches :k k))

(defn merge-batches
  "Merge multiple search batches using RRF or weighted-sum fusion.

   Args:
     batches: Vector of result batches
     opts: {:method \"rrf\"/\"weighted-sum\" :weights [...]}

   Returns:
     Vector of {:uri :score :sources}"
  [batches & {:keys [method weights] :or {method "rrf"}}]
  (graph/merge-batches batches :method method :weights weights))

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
  [graph seed-uris & {:keys [strategy max-depth decay hops]
                      :or {strategy "bfs" max-depth 3 decay 0.5 hops 2}}]
  (graph/quick-search graph seed-uris
                      :strategy strategy :max-depth max-depth :decay decay :hops hops))

;; =============================================================================
;; Few-Shot Retrieval (Phase 3)
;; =============================================================================

(defn expand-concept-neighborhood
  "Expand from seed concept URIs using BFS spreading activation.

   Args:
     seed-uris: Collection of starting concept URIs
     opts: {:max-depth N :decay N :graph graph}

   Returns:
     Vector of {:uri :score :path :depth} sorted by score"
  [seed-uris & {:keys [max-depth decay graph]
                :or {max-depth 2 decay 0.6}}]
  (retrieval/expand-concept-neighborhood seed-uris
                                         :max-depth max-depth
                                         :decay decay
                                         :graph graph))

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
  (retrieval/find-similar-trees event-store opts))

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
  (retrieval/find-failure-patterns event-store opts))

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
  (retrieval/find-success-patterns event-store opts))

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
  (retrieval/build-ontology-context event-store opts))

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
  (retrieval/format-context-for-llm context opts))

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
  (retrieval/hybrid-retrieval event-store query-concepts opts))

;; =============================================================================
;; Temporal Relevance (Phase 3)
;; =============================================================================

(defn compute-temporal-relevance
  "Compute temporal relevance score.

   Returns:
   - 1.0 for current year
   - 1.1 for future years (slight boost)
   - Decaying score for past years (min 0.1)
   - 0.5 for entities without temporal info"
  [entity-year reference-year & {:keys [decay-per-year] :or {decay-per-year 0.15}}]
  (graph/compute-temporal-relevance entity-year reference-year
                                    :decay-per-year decay-per-year))

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

;; =============================================================================
;; Read Model Builders (for direct use in tests/REPL)
;; =============================================================================

(defn build-concepts
  "Build concept graph from events."
  [initial-state events]
  (rm/concepts initial-state events))

(defn build-tree-profiles
  "Build tree profiles from events."
  [initial-state events]
  (rm/tree-profiles initial-state events))

(defn build-node-experiences
  "Build node experiences from events."
  [initial-state events]
  (rm/node-experiences initial-state events))

;; =============================================================================
;; Event Type Sets (for external query filtering)
;; =============================================================================

(def concept-events
  "Event types that affect the concept graph."
  rm/concept-events)

(def tree-profile-events
  "Event types that affect tree profiles."
  rm/tree-profile-events)

(def node-learning-events
  "Event types that affect node learning."
  rm/node-learning-events)

(def all-ontology-events
  "All ontology-related event types."
  rm/all-ontology-events)

(def embedding-events
  "Event types that affect embedding read models."
  rm/embedding-events)

;; =============================================================================
;; Embedding Functions (Phase 4)
;; =============================================================================

(defn embed-text
  "Generate an embedding vector for text.

   Args:
     text: String to embed
     opts: {:model-id \"sentence-transformers/all-MiniLM-L6-v2\"}

   Returns:
     Vector of doubles (384 dimensions for default model), or nil on error."
  [text & [opts]]
  (embedding/embed-text text (or opts {})))

(defn embed-texts-batch
  "Generate embeddings for multiple texts.

   Args:
     texts: Collection of strings to embed
     opts: {:model-id \"...\"}

   Returns:
     Vector of embedding vectors"
  [texts & [opts]]
  (embedding/embed-texts-batch texts (or opts {})))

(defn cosine-similarity
  "Compute cosine similarity between two embedding vectors.

   Returns:
     Similarity score in range [-1, 1], or 0.0 if either vector is zero."
  [a b]
  (embedding/cosine-similarity a b))

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
  (embedding/detect-embeddable-fields schema))

(defn concept->embedding-text
  "Convert a concept to text suitable for embedding.

   Combines specified fields into a single text string.

   Args:
     concept: Concept map with :label, :description, :indicators, etc.
     fields: Set of field keywords to include (default: #{:label :description})

   Returns:
     Combined text string for embedding"
  [concept & [fields]]
  (embedding/concept->embedding-text concept (or fields #{:label :description})))

(defn tree-profile->embedding-text
  "Convert a tree profile to text suitable for embedding.

   Summarizes strengths, weaknesses, and problem types."
  [profile]
  (embedding/tree-profile->embedding-text profile))

(defn get-embedding-model
  "Get an embedding model by ID, loading it if necessary.

   Thread-safe with double-checked locking pattern.

   Args:
     model-id: Optional model identifier (default: all-MiniLM-L6-v2)

   Returns:
     Loaded DJL ZooModel"
  [& [model-id]]
  (if model-id
    (embedding/get-embedding-model model-id)
    (embedding/get-embedding-model)))

(defn close-all-embedding-models!
  "Close all loaded embedding models. Useful for REPL cleanup."
  []
  (embedding/close-all-embedding-models!))

;; =============================================================================
;; Embedding Read Model Queries (Phase 4)
;; =============================================================================

(defn get-concept-embedding
  "Get embedding for a specific concept by URI."
  [ctx uri]
  (rm/get-concept-embedding ctx uri))

(defn get-all-concept-embeddings
  "Get all concept embeddings, optionally filtered by scope."
  [ctx & [opts]]
  (rm/get-all-concept-embeddings ctx opts))

(defn get-tree-profile-embedding
  "Get embedding for a specific tree profile."
  [ctx tree-id]
  (rm/get-tree-profile-embedding ctx tree-id))

(defn get-all-tree-profile-embeddings
  "Get all tree profile embeddings."
  [ctx]
  (rm/get-all-tree-profile-embeddings ctx))

(defn get-embedding-config
  "Get embedding model configuration for a scope."
  [ctx scope]
  (rm/get-embedding-config ctx scope))

(defn embedding-statistics
  "Get statistics about embeddings."
  [ctx]
  (rm/embedding-statistics ctx))

;; =============================================================================
;; Semantic Search (Phase 4)
;; =============================================================================

(defn semantic-search-concepts
  "Search concepts using embedding similarity.

   Generates an embedding for the query text and finds similar concepts
   based on cosine similarity with stored concept embeddings.

   Args:
     event-store: Grain event store (required)
     query-text: Natural language search query
     opts:
       :scope - Filter to specific ontology scope (:failure, :success, :problem)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum cosine similarity threshold (default 0.5)
       :model-id - Embedding model to use (default: all-MiniLM-L6-v2)

   Returns:
     Vector of {:uri :similarity :label :description} sorted by similarity

   Requires concept embeddings to be generated first via ontology/embed-concepts-batch."
  [event-store query-text & {:keys [scope limit min-similarity model-id]
                              :or {limit 10 min-similarity 0.5}
                              :as opts}]
  (retrieval/semantic-search-concepts event-store query-text
                                       :scope scope
                                       :limit limit
                                       :min-similarity min-similarity
                                       :model-id model-id))

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
  [event-store query-text & {:keys [limit min-similarity]
                              :or {limit 5 min-similarity 0.4}
                              :as opts}]
  (retrieval/semantic-search-tree-profiles event-store query-text
                                            :limit limit
                                            :min-similarity min-similarity))

;; =============================================================================
;; Hybrid Search (Graph + Embeddings via RRF) (Phase 4)
;; =============================================================================

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
  (retrieval/hybrid-search event-store opts))

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
  (retrieval/hybrid-search-batch event-store opts))

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
  [event-store query-text & {:keys [seed-failure-uri limit]
                              :or {limit 5}}]
  (retrieval/hybrid-search-failures event-store query-text
                                     :seed-failure-uri seed-failure-uri
                                     :limit limit))

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
  [event-store query-text & {:keys [seed-pattern-uri limit]
                              :or {limit 5}}]
  (retrieval/hybrid-search-patterns event-store query-text
                                     :seed-pattern-uri seed-pattern-uri
                                     :limit limit))

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
  (retrieval/build-ontology-context-with-embeddings event-store opts))

;; =============================================================================
;; Dynamic Field Analysis (Phase 4B)
;; =============================================================================

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
  [ctx schema & {:keys [sample-data confidence-threshold sheet-id]
                 :or {confidence-threshold 0.7}}]
  (fa-workflow/analyze-schema ctx sheet-id schema (or sample-data [])
                               :confidence-threshold confidence-threshold))

(defn build-field-analyzer-workflow!
  "Build the field analyzer ORC workflow for reuse.

   Returns a sheet-id that can be passed to analyze-fields-for-embedding
   for efficient repeated analysis.

   Args:
     ctx: Application context with sheet service

   Returns:
     sheet-id (uuid)"
  [ctx]
  (fa-workflow/build-field-analyzer! ctx))

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
  [schema & [sample-data opts]]
  (field-analyzer/detect-embeddable-fields-heuristic
   schema (or sample-data [])
   :threshold (or (:threshold opts) 0.6)))

(defn embed-with-fields
  "Embed data using specified fields.

   Combines the values of specified fields into text and generates an embedding.

   Args:
     data: Record to embed
     fields: Collection of field names to include
     opts: {:model-id \"...\"}

   Returns:
     Embedding vector (384 dimensions for default model)"
  [data fields & {:keys [model-id] :as opts}]
  (let [text (field-analyzer/combine-fields-for-embedding data fields)]
    (embedding/embed-text text (or opts {}))))

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
  [ctx schema data & {:keys [sample-data confidence-threshold fallback-to-heuristic]
                      :or {confidence-threshold 0.7 fallback-to-heuristic true}}]
  (let [analysis (if ctx
                   (try
                     (analyze-fields-for-embedding ctx schema
                                                    :sample-data (or sample-data [data])
                                                    :confidence-threshold confidence-threshold)
                     (catch Exception e
                       (when fallback-to-heuristic
                         (detect-embeddable-fields-heuristic schema [data]))))
                   (detect-embeddable-fields-heuristic schema [data]))
        fields (:embeddable-fields analysis)
        emb (when (seq fields)
              (embed-with-fields data fields))]
    {:embedding emb
     :fields-used fields
     :method (:method analysis)}))

;; =============================================================================
;; Pattern Discovery
;; =============================================================================

(defn get-low-scoring-evaluations
  "Get evaluation events with low aggregate scores.

   Reads :evaluation/trace-evaluated events from the event store and
   filters to those below the score threshold.

   Args:
   - event-store: Grain event store
   - sheet-id: UUID of the sheet to analyze
   - options:
     - :threshold - Score threshold (default 0.6)
     - :limit - Max evaluations to return (default 100)

   Returns vector of evaluation event bodies."
  ([ctx sheet-id]
   (discovery/get-low-scoring-evaluations ctx sheet-id {}))
  ([ctx sheet-id options]
   (discovery/get-low-scoring-evaluations ctx sheet-id options)))

(defn build-discovery-workflow!
  "Build the pattern discovery workflow. Returns sheet-id.

   This is idempotent - calling multiple times with the same definition
   will return the same sheet-id."
  [ctx]
  (discovery/build-discovery-workflow! ctx))

(defn discover-patterns
  "High-level API to run pattern discovery on a sheet's evaluations.

   Analyzes low-scoring evaluation feedback from the evaluation component
   to identify recurring failure patterns not covered by the current ontology.

   Args:
   - ctx: Context with event-store
   - sheet-id: Sheet to analyze
   - options:
     - :min-traces - Minimum traces required to run (default 20)
     - :score-threshold - Analyze traces below this score (default 0.6)

   Returns map with:
   - :discovered - count of new subtypes
   - :analyzed-traces - count of traces analyzed
   - :subtypes - vector of discovered subtype maps (with :parent-uri :proposed-uri :label :description :indicators :evidence-count)
   - :skipped - true if insufficient traces (with :reason :found :required)"
  ([ctx sheet-id]
   (discovery/discover-patterns ctx sheet-id {}))
  ([ctx sheet-id options]
   (discovery/discover-patterns ctx sheet-id options)))

;; =============================================================================
;; Cache Preloading (Warm Startup)
;; =============================================================================

(defn preload-ontology-cache!
  "Preload ontology read models into L1 cache for fast first queries.

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
  (let [start (System/currentTimeMillis)]
    ;; Force projection of all read models - this populates L1 cache
    (rmp/project ctx :ontology/concepts)
    (rmp/project ctx :ontology/tree-profiles)
    (rmp/project ctx :ontology/node-experiences)
    (rmp/project ctx :ontology/concept-embeddings)
    (rmp/project ctx :ontology/tree-profile-embeddings)
    (rmp/project ctx :ontology/ontology-colbert-indexes)

    (let [elapsed (- (System/currentTimeMillis) start)]
      {:preloaded [:concepts :tree-profiles :node-experiences
                   :concept-embeddings :tree-profile-embeddings
                   :ontology-colbert-indexes]
       :elapsed-ms elapsed})))

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
  (rm/get-colbert-index-for-ontology ctx ontology-id))

;; =============================================================================
;; Self-Learning Retrieval (Domain-Agnostic)
;; =============================================================================

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
  (retrieval/find-self-patterns ctx tree-id opts))

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
  [ctx tree-id problem-type & [opts]]
  (retrieval/build-actionable-context ctx tree-id problem-type opts))

;; =============================================================================
;; Learned Rules API (Self-Learning Read Model)
;; =============================================================================

(def learned-rule-events
  "Event types that affect the learned-rules read model"
  rm/learned-rule-events)

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
  (rm/get-tree-rules ctx tree-id))

(defn get-all-learned-rules
  "Get all learned rules for all trees.

   Returns map of {tree-id -> [rule ...]}"
  [ctx]
  (rm/get-all-learned-rules ctx))

(defn find-rules-by-problem
  "Find rules that were extracted for a specific problem type.

   Returns flat vector of rules across all trees."
  [ctx problem-type]
  (rm/find-rules-by-problem ctx problem-type))

(defn find-rules-by-condition
  "Find rules that match given condition criteria.

   conditions: Map of condition key-value pairs to match
   Returns rules where all specified conditions are present."
  [ctx conditions]
  (rm/find-rules-by-condition ctx conditions))

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
  (rm/learned-rules-statistics ctx))

;; =============================================================================
;; Rule Extraction API (Self-Learning)
;; =============================================================================

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
  (rule-extraction/extract-rules ctx tree-id opts))

;; =============================================================================
;; Site Registry Access (Generic Site Pattern Learning)
;; =============================================================================

(def site-registry-events
  "Event types that affect site registry read model."
  rm/site-registry-events)

(defn get-site-by-domain
  "Get a registered site by its domain."
  [ctx domain]
  (rm/get-site-by-domain ctx domain))

(defn get-all-sites
  "Get all registered sites."
  [ctx]
  (rm/get-all-sites ctx))

(defn get-trusted-sites
  "Get sites with trust score above threshold, sorted by trust.
   Options:
   - :min-trust - Minimum trust score (default 0.5)
   - :limit - Maximum sites to return"
  [ctx & [opts]]
  (rm/get-trusted-sites ctx opts))

(defn get-site-patterns
  "Get learned patterns for a site.
   Options:
   - :pattern-type - Filter by pattern type (:navigation, :extraction, etc.)"
  [ctx domain & [opts]]
  (rm/get-site-patterns ctx domain opts))

(defn get-sites-requiring-headed
  "Get sites that require headed browser mode."
  [ctx]
  (rm/get-sites-requiring-headed ctx))

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
  (rm/site-registry-statistics ctx))

;; =============================================================================
;; Action Outcome Learning (General-Purpose)
;; =============================================================================
;;
;; These convenience functions enable behavior trees to learn from action
;; outcomes without needing domain-specific recording functions. They wrap
;; the existing ontology commands with sensible defaults for browser automation
;; and other domains.
;;
;; Usage:
;;   ;; Record a failed action
;;   (record-action-outcome ctx
;;     {:tree-id sheet-id
;;      :domain "rent.com"
;;      :action-type :click
;;      :action-target "@e123"
;;      :success false
;;      :outcome-type :bot-detection
;;      :redirected-to "ratelimited.rent.com"})
;;
;;   ;; Query past outcomes before attempting an action
;;   (query-action-outcomes ctx
;;     {:domain "rent.com"
;;      :action-type :click})
;;   ;; => [{:success false :outcome-type :bot-detection ...}]
;;
;;   ;; Check if action is safe based on past outcomes
;;   (check-action-safe ctx "rent.com" :click)
;;   ;; => {:safe false :failure-rate 1.0 :recommendation :skip}

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
  [ctx {:keys [tree-id domain action-type action-target success
               outcome-type error-message redirected-to
               browser-mode page-url]
        :as outcome-map}]
  (require '[ai.obney.grain.command-processor-v2.interface :as cp])
  (require '[ai.obney.grain.time.interface :as time])
  (let [process-fn (resolve 'ai.obney.grain.command-processor-v2.interface/process-command)
        now-fn (resolve 'ai.obney.grain.time.interface/now)
        domain-type "browser-automation"

        ;; Build context-conditions map
        context-conditions (cond-> {:domain domain
                                    :action-type (name action-type)}
                             browser-mode (assoc :browser-mode (name browser-mode))
                             page-url (assoc :page-url page-url))

        ;; Build action description
        action-desc {:type (name action-type)
                     :target action-target
                     :reason (str "executing " (name action-type) " on " domain)}]

    (if success
      ;; Record as strength
      (process-fn
       (assoc ctx :command
              {:command/id (random-uuid)
               :command/timestamp (now-fn)
               :command/name :ontology/record-tree-strength
               :tree-id tree-id
               :pattern-uri (str "success:" (name action-type) "-on-" domain)
               :confidence 0.8
               :evidence-trace-ids []
               :avg-score 1.0
               :context-conditions context-conditions
               :action-taken action-desc
               :domain-type domain-type
               :expected-outcome (str (name action-type) " completed successfully")}))

      ;; Record as weakness
      (process-fn
       (assoc ctx :command
              {:command/id (random-uuid)
               :command/timestamp (now-fn)
               :command/name :ontology/record-tree-weakness
               :tree-id tree-id
               :failure-uri (str "failure:" (name (or outcome-type :action-failed)))
               :frequency 1.0
               :severity :medium
               :triggers [(str "action:" (name action-type))]
               :evidence-trace-ids []
               :failure-context (cond-> context-conditions
                                  redirected-to (assoc :redirected-to redirected-to)
                                  error-message (assoc :error-message error-message)
                                  outcome-type (assoc :outcome-type (name outcome-type)))
               :attempted-action action-desc
               :domain-type domain-type})))))

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
  [ctx tree-id {:keys [domain action-type limit] :or {limit 10}}]
  (let [profile (get-tree-profile ctx tree-id)
        domain-str (when domain (if (keyword? domain) (name domain) domain))
        action-str (when action-type (if (keyword? action-type) (name action-type) action-type))

        ;; Extract failures that match criteria
        matching-weaknesses
        (->> (:weaknesses profile)
             (filter (fn [{:keys [failure-context]}]
                       (and (or (nil? domain-str)
                                (= domain-str (get failure-context :domain)))
                            (or (nil? action-str)
                                (= action-str (get failure-context :action-type))))))
             (take limit)
             (mapv (fn [{:keys [failure failure-context attempted-action recorded-at]}]
                     {:success false
                      :domain (get failure-context :domain)
                      :action-type (keyword (get failure-context :action-type))
                      :outcome-type (when failure
                                      (keyword (last (clojure.string/split (str failure) #":"))))
                      :context failure-context
                      :action attempted-action
                      :recorded-at recorded-at})))

        ;; Extract successes that match criteria
        matching-strengths
        (->> (:strengths profile)
             (filter (fn [{:keys [context-conditions]}]
                       (and (or (nil? domain-str)
                                (= domain-str (get context-conditions :domain)))
                            (or (nil? action-str)
                                (= action-str (get context-conditions :action-type))))))
             (take limit)
             (mapv (fn [{:keys [pattern-uri context-conditions action-taken recorded-at]}]
                     {:success true
                      :domain (get context-conditions :domain)
                      :action-type (keyword (get context-conditions :action-type))
                      :outcome-type :success
                      :context context-conditions
                      :action action-taken
                      :recorded-at recorded-at})))]

    (vec (concat matching-weaknesses matching-strengths))))

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
  (let [outcomes (query-action-outcomes ctx tree-id
                   {:domain domain
                    :action-type action-type
                    :limit 20})
        successes (filter :success outcomes)
        failures (remove :success outcomes)
        total (count outcomes)
        failure-rate (if (pos? total)
                       (/ (count failures) total)
                       0.0)
        recommendation (cond
                         (zero? total) :proceed
                         (>= failure-rate 0.8) :skip
                         (>= failure-rate 0.5) :caution
                         :else :proceed)]
    {:safe (< failure-rate 0.5)
     :failure-rate failure-rate
     :success-count (count successes)
     :failure-count (count failures)
     :has-prior-outcomes (pos? total)
     :recommendation recommendation
     :failures (mapv :context failures)}))

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
  (let [action-types [:click :scroll :navigate :fill :press :hover]
        recommendations
        (->> action-types
             (map (fn [action-type]
                    (let [check (check-action-safe ctx tree-id domain action-type)]
                      (assoc check
                             :action-type action-type
                             :alternative (case (:recommendation check)
                                            :skip (case action-type
                                                    :click "Use scroll-only strategy"
                                                    :navigate "Try alternative URL"
                                                    :fill "Check for CAPTCHA"
                                                    "Avoid this action")
                                            :caution "Proceed with extra care"
                                            nil)))))
             (filter :has-prior-outcomes)
             vec)]
    {:domain domain
     :recommendations recommendations}))

;; =============================================================================
;; Evolutionary Ontology Building
;; =============================================================================
;;
;; These functions provide ontology construction from various source types:
;; - CSV files → entities and relationships
;; - Text documents → concept extraction and taxonomy
;; - SQL databases → schema-to-ontology mapping
;; - Unified interface → auto-detection and routing
;;

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
     - \"rdf\" - RDF/OWL imports (planned)

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
  (require '[ai.obney.orc.ontology.core.evolutionary-builder :as evo-builder])
  ((resolve 'ai.obney.orc.ontology.core.evolutionary-builder/build-from-sources) ctx params))

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
  (require '[ai.obney.orc.ontology.core.evolutionary-builder :as evo-builder])
  ((resolve 'ai.obney.orc.ontology.core.evolutionary-builder/evolve) ctx params))

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
  (require '[ai.obney.orc.ontology.sheets.csv-ontology :as csv-ont])
  (let [build! (resolve 'ai.obney.orc.ontology.sheets.csv-ontology/build-csv-ontology-pipeline!)
        run! (resolve 'ai.obney.orc.ontology.sheets.csv-ontology/run-csv-to-ontology)
        sheet-id (build! ctx)]
    (run! ctx sheet-id opts)))

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
  (require '[ai.obney.orc.ontology.sheets.ontology-exploration :as text-ont])
  (let [build! (resolve 'ai.obney.orc.ontology.sheets.ontology-exploration/build-taxonomy-pipeline!)
        run! (resolve 'ai.obney.orc.ontology.sheets.ontology-exploration/run-taxonomy-pipeline)
        sheet-id (build! ctx)]
    (run! ctx sheet-id opts)))

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
  (require '[ai.obney.orc.ontology.sheets.sql-ontology :as sql-ont])
  (let [build! (resolve 'ai.obney.orc.ontology.sheets.sql-ontology/build-sql-ontology-pipeline!)
        run! (resolve 'ai.obney.orc.ontology.sheets.sql-ontology/run-sql-to-ontology)
        sheet-id (build! ctx)]
    (run! ctx sheet-id opts)))

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
     - .ttl/.rdf/.owl → RDF import (planned)

   Returns:
     {:status :success/:failed/:not-implemented
      :source-type :csv/:text/:sql/:json/:rdf
      ...type-specific outputs...}"
  [ctx source]
  (require '[ai.obney.orc.ontology.sheets.unified-ontology :as unified])
  ((resolve 'ai.obney.orc.ontology.sheets.unified-ontology/extract) ctx source))

(defn extract-unified-multiple
  "Extract ontologies from multiple sources with unified API.

   Returns map of source identifiers to extraction results."
  [ctx sources]
  (require '[ai.obney.orc.ontology.sheets.unified-ontology :as unified])
  ((resolve 'ai.obney.orc.ontology.sheets.unified-ontology/extract-multiple) ctx sources))
