(ns ai.obney.orc.ontology.core.queries
  "Ontology queries and the services that read projected state."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.set :as set]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [malli.core :as m]
            [malli.error :as me]))

;; --- Interface ---

(defn get-behavior-mint-by-logical-action
  "Return the durable winning mint event for one researcher logical action."
  [ctx logical-action-identity]
  (reduce
   (fn [found event]
     (if (= logical-action-identity (:logical-action-identity event))
       event
       found))
   nil
   (es/read
    (:event-store ctx)
    {:tenant-id (:tenant-id ctx)
     :types #{:ontology/behavioral-subtree-minted}
     :tags #{(logic/researcher-logical-action-tag
              logical-action-identity)}})))

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
  (when-let [{:keys [researcher-iteration attempt-ordinal ownership-epoch]}
             (reduce
              (fn [found event]
                (if (= target-id (:target-id event)) event found))
              nil
              (es/read
               (:event-store ctx)
               {:tenant-id (:tenant-id ctx)
                :types #{:ontology/behavioral-subtree-minted}
                :tags #{[:behavioral-subtree-minted target-id]}}))]
    (when (and (some? attempt-ordinal) (some? ownership-epoch))
      {:iteration-index researcher-iteration
       :attempt-ordinal attempt-ordinal
       :ownership-epoch ownership-epoch
       :first-attempt? (and (= 0 attempt-ordinal) (= 1 ownership-epoch))})))

(defn interface-get-concepts
  "Get concepts from the event-sourced concept graph.
   Options:
   - :scope - Filter by scope
   - :broader-uri - Filter by parent concept"
  [ctx & [opts]]
  (read-models/get-concepts ctx opts))

(defn- resolve-evidence-source
  "Return `{:body :granularity :target-id}` for the candidate's Living
   Description, fetched from the RIGHT read-model scope. Tries the candidate's
   stated granularity, then :tree-fingerprint, then :tree-class (deduped).
   Returns nil if no scope carries a body.

   CC-9a: this returns the RESOLVED SCOPE, not just the body, because the
   enforcement gate below must read the claim set of the very target the body
   came from. Resolving a body from one scope and its claims from another
   would silently mismatch them — and the failure mode would be an EMPTY
   enforcing set, i.e. a target that quietly stops enforcing."
  [ctx granularity target-id]
  (let [tid (logic/coerce-evidence-target-id target-id)]
    (some (fn [scope]
            (when scope
              (when-let [body (read-models/get-description ctx scope tid)]
                {:body body :granularity scope :target-id tid})))
          (distinct [granularity :tree-fingerprint :tree-class]))))

(defn- enforcing-avoid-strings
  "CC-9a (ADR 0022, `invariant.OnlyValidatedClaimsEnforce`) — THE ENFORCEMENT
   GATE: the subset of a target's negative signals that has EARNED the right
   to suppress retrieval.

   Returns nil for a target with NO claims — the legacy corpus, which is every
   live target until CC-12 migrates it. nil means 'no claim set to gate on',
   and EL-5's `avoid-strings` then reads the body exactly as it did before, so
   a wholesale-recorded body keeps enforcing its guards unchanged. A CLAIM-BACKED
   target returns a vector, possibly EMPTY — and empty means 'nothing here has
   earned enforcement', which is a different statement from nil.

   Both doors, from one source. `assemble-body` puts a `:guard` claim's
   `:content` into the body-level `:avoid-when` and a `:weakness` claim's
   `:context-guard` into that weakness's `:avoid-when`; EL-5's `avoid-strings`
   unions the two. So the gate has to reproduce that union over
   `get-enforcing-claims` — narrowing only the body-level list would leave the
   per-weakness guards as an open second door for exactly the same unproven
   claim."
  [ctx granularity target-id]
  (when (seq (read-models/get-claims ctx granularity target-id))
    (into []
          (comp (keep (fn [{:keys [kind content context-guard]}]
                        (case kind
                          :guard    content
                          :weakness context-guard
                          nil)))
                (distinct))
          (read-models/get-enforcing-claims ctx granularity target-id))))

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
  (let [concepts (read-models/get-concepts ctx {:scope :tree-class})]
    (->> concepts
         (keep (fn [c]
                 (let [label (:label c)
                       uri (:uri c)
                       bare (if (and (string? uri) (str/starts-with? uri "tree-class:"))
                              (subs uri (count "tree-class:"))
                              uri)]
                   (when (and (string? label)
                              (not (str/blank? label))
                              (not= label bare))
                     (let [broader-uri (first (:broader c))]
                       {:target-id (logic/tree-class-uri->target-id uri)
                        :domain-label label
                        :parent-id (when broader-uri (logic/tree-class-uri->target-id broader-uri))
                        :ai.obney.orc.ontology.interface/created-at (:created-at c)})))))
         (sort-by :ai.obney.orc.ontology.interface/created-at #(compare %2 %1))
         (take logic/family-label-list-bound)
         (mapv #(dissoc % :ai.obney.orc.ontology.interface/created-at)))))

(defn- existing-domain-child-labels
  "R-Inject specialisation (weed 1.1, DomainChildIdentityIsStable): the labels
   of a tree-class candidate's existing domain children — its narrower
   concepts whose :label is a judged domain label (non-blank and not the
   concept's own identifier; every other tree-class concept carries its
   identifier as its label). Shown to the reranker so a repeat of a known
   domain REUSES the label instead of coining a variant that would mint a
   sibling. Best-effort: a failed read yields nil (the reranker simply is not
   shown children; RS-2's own lookup still defers on failure)."
  [ctx target-id]
  (try
    (let [parent-uri (str "tree-class:" target-id)
          child-uris (or (read-models/get-narrower-concepts ctx parent-uri) #{})]
      (->> child-uris
           (keep (fn [uri]
                   (let [label (:label (read-models/get-concept-by-uri ctx uri))
                         bare (subs uri (min (count uri) (count "tree-class:")))]
                     (when (and (string? label)
                                (not (str/blank? label))
                                (not= label bare))
                       label))))
           sort
           vec
           not-empty))
    (catch Exception _ nil)))

(defn- enrich-candidate-evidence
  "Add :avoid-when (top-level body guards + per-weakness guards) and compact
   :strengths/:weaknesses from the candidate's body to the candidate map.
   No-op (returns the candidate unchanged) when no body is found — the
   reranker simply sees the summary, as before.

   CC-9a: this is where VISIBLE and ENFORCING split. `:avoid-when` and
   `:weaknesses` keep carrying everything the body carries — the reranker
   still reads every caution the target has recorded, proven or not. The
   penalty's negative signal is stamped SEPARATELY, under
   `::domain-penalty/enforcing-avoid-when`, from `get-enforcing-claims`. This
   seam is the one place both facts are in scope: the body (what to show) and
   the claim set behind it (what has earned the right to act)."
  [ctx c]
  (let [{:keys [granularity target-id]} (:document-metadata c)
        source (resolve-evidence-source ctx granularity target-id)
        body (:body source)
        weaknesses (:weaknesses body)
        per-weakness-guards (into [] (keep :avoid-when) weaknesses)
        avoid-when (vec (distinct (concat (:avoid-when body) per-weakness-guards)))
        enforcing (when source
                    (enforcing-avoid-strings ctx (:granularity source) (:target-id source)))
        existing-children (when (= "tree-class" (logic/granularity-name granularity))
                            (existing-domain-child-labels ctx target-id))]
    (cond-> c
      (seq existing-children) (assoc :existing-domain-children existing-children)
      (some? enforcing) (assoc :ai.obney.orc.ontology.core.domain-penalty/enforcing-avoid-when enforcing)
      (seq avoid-when)        (assoc :avoid-when avoid-when)
      (seq (:strengths body)) (assoc :strengths (logic/compact-strengths (:strengths body)))
      (seq weaknesses)        (assoc :weaknesses (logic/compact-weaknesses weaknesses))
      (:parent-behavior body) (assoc :ai.obney.orc.ontology.interface/parent-behavior (:parent-behavior body)))))

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

   RR-1 (ADR 0020): the fallback path now distinguishes WHY it fired.
   Reached because the rerank call timed out AND its one retry also timed
   out (`reranker/timed-out?`) → :timeout-fallback. Reached because the
   reranker threw / returned nil / returned empty → :colbert-fallback, as
   before. The downstream ACTION is identical for both (ADR 0015's
   detect-and-defer :outcome :uncertain) — this only sharpens what the
   measurement code can observe: 'infra was slow' vs 'the reranker could
   not rank'.

   EL-5 (ADR 0016): AFTER the JOIN, the reranker's :fitness-score is
   passed through the deterministic CONTRASTIVE domain penalty
   (domain-penalty/penalize-candidates). The JOIN below keys back onto
   the ENRICHED candidates (not the raw ColBERT ones) so the penalty
   pass can read each candidate's :avoid-when (negative signal) +
   :good-when (positive signal) — the same EL-2 evidence — and
   bite DETERMINISTICALLY where the LLM ignored the veto. The penalty
   re-sorts by the new fitness BEFORE (take k …). Output contract
   ({:document-id :reasoning :fitness-score}) is UNCHANGED (the extra
   :domain-penalty/:cos-* keys are additive observability).

   CC-16 (ADR 0026 + ADR 0027): the positive signal is now the `:good-when`
   strings ALONE — `:content` (the whole indexed description) was competing
   with its own guard for the shared batch-relative normalizer. BOTH readings
   are computed on every pass at zero extra cost and stamped
   (`:cos-*-with-content` / `:cos-*-sans-content` / the two penalties), and
   `:positive-signal` in `:domain-penalty-config` selects which one is APPLIED.
   The shipped default APPLIES the pre-ADR-0026 reading (Stage 1: no behaviour
   change) and shadows the other; the pass emits `::domain-penalty-pass` with
   the firing rate and contrast distribution, so a silent gate is
   distinguishable from an absent one.

   CC-9a (ADR 0022): on a CLAIM-BACKED candidate the penalty's negative
   signal is no longer the body's `:avoid-when` but the enrichment's
   `::domain-penalty/enforcing-avoid-when` stamp — the VALIDATED subset. The
   reranker prompt is unaffected: it still receives every guard the body
   carries. Visible and enforcing are now different sets, which is exactly
   `invariant.OnlyValidatedClaimsEnforce`.

   RR-2: `model` is an OPTIONAL override for the reranker's :model,
   threaded straight through to `reranker/rerank!`'s own :model opt —
   nil here is a no-op (reranker/rerank! resolves its own default).

   RR-3 (ADR 0020 #4): between enrichment and the LLM call, the candidate
   payload is passed through `shape-candidate-richness` — a STRUCTURAL cap
   that keeps full richness for every parent behavior (and every
   non-behavioral candidate) but cuts all but the top-K CHILDREN by
   pre-rerank score down to content + score + document-id. INPUT-only: the
   count, the order, and the ranking task are unchanged, and the JOIN + the
   EL-5 penalty below key onto the fully-enriched set, not the shaped one."
  [ctx candidates rerank-intent query k model]
  (let [annotated (mapv #(enrich-candidate-evidence ctx %) candidates)
        enriched (mapv #(dissoc % :ai.obney.orc.ontology.interface/parent-behavior) annotated)
        prompt-candidates (logic/shape-candidate-richness annotated)
        existing-domain-labels (try
                                  (->> (existing-domain-families ctx)
                                       (mapv :domain-label)
                                       distinct
                                       vec)
                                  (catch Exception _ []))
        reranked (try
                   (logic/reranker-rerank! ctx
                     {:query query
                      :intent rerank-intent
                      :candidates prompt-candidates
                      :existing-domain-labels existing-domain-labels
                      :model model})
                   (catch Throwable t
                     (u/log :ai.obney.orc.ontology.interface/rerank-failed
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
                                 (assoc :rerank-source :reranker)
                                 (assoc :domain-coverage (:domain-coverage r))
                                 (assoc :domain-label (:domain-label r))
                                 (assoc :domain-reasoning (:domain-reasoning r)))))
                         reranked)
            penalty-config (merge logic/default-penalty-config
                                  (:domain-penalty-config ctx))
            penalized (logic/penalize-candidates ctx joined query penalty-config)]
        (mapv #(dissoc % :ai.obney.orc.ontology.core.domain-penalty/enforcing-avoid-when) (take k penalized)))
      (let [
            timeout? (logic/timed-out? reranked)
            source (if timeout? :timeout-fallback :colbert-fallback)]
        (u/log :ai.obney.orc.ontology.interface/rerank-failed
               :query query
               :rerank-source source
               :note (if timeout?
                       "Reranker timed out (including its retry); falling back to pure ColBERT ordering."
                       "Reranker returned no results; falling back to pure ColBERT ordering."))
        (->> candidates
             (take k)
             (mapv (fn [c]
                     (-> c
                         (assoc :reasoning nil)
                         (assoc :fitness-score nil)
                         (assoc :rerank-source source)))))))))

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
  [ctx {:keys [query granularity k rerank-with-intent model exclude?]
        :or {granularity :all k 10}}]
  (if-let [index (logic/latest-ontology-descriptions-index ctx)]
    (let [filtered? (not= granularity :all)
          fetch-k (if rerank-with-intent
                    (cond-> (logic/rerank-fetch-k k) filtered? (* 3) exclude? (* 2))
                    (cond-> k filtered? (* 3) exclude? (* 2)))
          raw-results (mapv logic/normalize-search-result
                            ((logic/colbert-fn 'search) ctx
                              {:query query
                               :index-id (:index-id index)
                               :k fetch-k}))
          allowed-names (cond
                          (= granularity :all) nil
                          (set? granularity)   (into #{} (map logic/granularity-name) granularity)
                          :else                #{(logic/granularity-name granularity)})
          granularity-filtered (if (nil? allowed-names)
                                  raw-results
                                  (filterv #(contains? allowed-names
                                                       (logic/granularity-name
                                                         (-> % :document-metadata :granularity)))
                                           raw-results))
          filtered (if exclude?
                     (let [seen (atom {})
                           excluded? (fn [c]
                                       (let [id (-> c :document-metadata :target-id)]
                                         (if (contains? @seen id)
                                           (get @seen id)
                                           (let [v (boolean (exclude? ctx c))]
                                             (swap! seen assoc id v)
                                             v))))]
                       (filterv (complement excluded?) granularity-filtered))
                     granularity-filtered)]
      (if rerank-with-intent
        (let [{:keys [representatives siblings]} (logic/tree-class-representatives filtered)
              reranked (apply-rerank ctx (vec (take (logic/rerank-fetch-k k) representatives))
                                     rerank-with-intent query k model)]
          (vec (take k (logic/restore-axis-siblings reranked siblings))))
        (vec (take k filtered))))
    (do
      (u/log :ai.obney.orc.ontology.interface/search-cold-no-index
             :query query
             :note "No ontology-descriptions index has been built yet. Returning empty results.")
      [])))

;; --- Serialization ---

(defn full-export
  "Export the complete ontology including concepts, tree profiles, and node experiences.

   Arguments:
   - ctx: Context map with :event-store, :tenant-id, :cache
   - opts: Options map with:
     - :scope - Filter concepts by scope (:failure, :success, :problem)
     - :include-profiles? - Include tree profiles (default true)
     - :include-experiences? - Include node experiences (default true)
     - :base-uri - Base URI for the ontology"
  [ctx & [{:keys [scope include-profiles? include-experiences? base-uri]
           :or {include-profiles? true
                include-experiences? true
                base-uri "http://obney.ai/workshop/ontology/"}}]]
  (let [concept-graph (rmp/project ctx :ontology/concepts)
        filtered-concepts (if scope
                            (into {} (filter (fn [[_ c]] (= scope (:scope c))) concept-graph))
                            concept-graph)
        concepts-ttl (logic/serialization-concepts->turtle filtered-concepts {:base-uri base-uri})

        profiles-ttl (when include-profiles?
                       (let [profiles (rmp/project ctx :ontology/tree-profiles)]
                         (when (seq profiles)
                           (str "\n\n# === TREE PROFILES ===\n\n"
                                (logic/serialization-tree-profiles->turtle profiles)))))

        experiences-ttl (when include-experiences?
                          (let [experiences (rmp/project ctx :ontology/node-experiences)]
                            (when (seq experiences)
                              (str "\n\n# === NODE EXPERIENCES ===\n\n"
                                   (logic/serialization-node-experiences->turtle experiences)))))]
    (str concepts-ttl
         (or profiles-ttl "")
         (or experiences-ttl ""))))

;; --- Interface ---

(defn export-turtle
  "Full ontology export to Turtle format.
   Options:
   - :scope - Filter concepts (:failure, :success, :problem)
   - :include-profiles? - Include tree profiles (default true)
   - :include-experiences? - Include node experiences (default true)
   - :base-uri - Base URI for the ontology"
  [ctx & [opts]]
  (full-export ctx opts))

(defn get-all-concept-embeddings
  "Get all concept embeddings, optionally filtered by scope."
  [ctx & [opts]]
  (read-models/get-all-concept-embeddings ctx opts))

;; --- Retrieval ---

(defn retrieval-semantic-search-concepts
  "Search concepts using embedding similarity.

   Generates an embedding for the query text and finds similar concepts
   based on cosine similarity with stored concept embeddings.

   Args:
     event-store: Grain event store (required)
     query-text: Natural language search query
     opts:
       :scope - Filter to specific ontology scope (:failure, :success, :problem)
       :ontology-id - Filter by single ontology-id
       :ontology-ids - Filter by multiple ontology-ids (returns union)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum cosine similarity threshold (default 0.5)
       :model-id - Embedding model to use (default: all-MiniLM-L6-v2)

   Returns:
     Vector of {:uri :similarity :label :description} sorted by similarity

   Requires concept embeddings to be generated first via ontology/embed-concepts-batch."
  [ctx query-text & {:keys [scope ontology-id ontology-ids limit min-similarity model-id]
                      :or {limit 10 min-similarity 0.5}}]
  (when (and ctx query-text)
    (let [
          query-embedding (logic/embedding-embed-text query-text
                                                 (when model-id {:model-id model-id}))

          filter-opts (cond-> {}
                        scope (assoc :scope scope)
                        ontology-id (assoc :ontology-id ontology-id)
                        ontology-ids (assoc :ontology-ids ontology-ids))
          concept-embeddings (read-models/get-all-concept-embeddings ctx
                                                             (when (seq filter-opts) filter-opts))]

      (when (and query-embedding (seq concept-embeddings))
        (->> (logic/search-concepts-by-embedding
               query-embedding
               concept-embeddings
               :limit limit
               :min-similarity min-similarity)

             (map (fn [{:keys [uri similarity]}]
                    (let [concept (logic/static-ontology-get-concept-by-uri uri)]
                      {:uri uri
                       :similarity similarity
                       :label (:label concept)
                       :description (:description concept)
                       :scope (:scope concept)})))

             vec)))))

;; --- Interface ---

(defn interface-semantic-search-concepts
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
  (retrieval-semantic-search-concepts event-store query-text
                                       :scope scope
                                       :limit limit
                                       :min-similarity min-similarity
                                       :model-id model-id))

;; --- Retrieval ---

(defn retrieval-semantic-search-tree-profiles
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
  [ctx query-text & {:keys [limit min-similarity]
                      :or {limit 5 min-similarity 0.4}}]
  (when (and ctx query-text)
    (let [query-embedding (logic/embedding-embed-text query-text)
          tree-embeddings (read-models/get-all-tree-profile-embeddings ctx)]

      (when (and query-embedding (seq tree-embeddings))
        (->> tree-embeddings
             (map (fn [[tree-id data]]
                    (when (:embedding data)
                      {:tree-id tree-id
                       :similarity (logic/embedding-cosine-similarity query-embedding
                                                                 (:embedding data))})))
             (remove nil?)
             (filter #(>= (:similarity %) min-similarity))
             (sort-by :similarity >)
             (take limit)
             (map (fn [{:keys [tree-id] :as result}]
                    (assoc result :profile (read-models/get-tree-profile ctx tree-id))))
             vec)))))

;; --- Interface ---

(defn interface-semantic-search-tree-profiles
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
  (retrieval-semantic-search-tree-profiles event-store query-text
                                            :limit limit
                                            :min-similarity min-similarity))

;; --- Retrieval ---

(defn hybrid-search
  "Hybrid search combining graph-based BFS, embedding similarity, and ColBERT via RRF.

   This is the primary search function that leverages up to three signals:
   1. Graph structure - BFS spreading activation from seed concepts
   2. Semantic similarity - Embedding-based similarity to query text
   3. ColBERT late-interaction - Token-level retrieval (when index provided)

   RRF (Reciprocal Rank Fusion) merges all ranked lists to produce
   results that capture structural relationships, semantic meaning,
   AND token-level precision.

   Args:
     event-store: Grain event store (also passed as ctx for ColBERT)
     opts:
       :seed-uris - Collection of starting concept URIs for graph expansion
       :query-text - Natural language query for embedding/ColBERT search
       :scope - Filter to specific ontology scope
       :ontology-id - Filter by single ontology-id
       :ontology-ids - Filter by multiple ontology-ids (returns union)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum embedding similarity (default 0.3)
       :max-depth - BFS expansion depth (default 2)
       :decay - BFS decay factor (default 0.6)
       :colbert-index-id - UUID of ColBERT index (required for ColBERT signal)
       :weights - {:graph N :embedding N :colbert N} weights for RRF
                  (default {:graph 1.0 :embedding 1.0 :colbert 1.0})
       :signals - Set of enabled signals #{:graph :embedding :colbert}
                  (default: all signals enabled)

   Returns:
     {:results [{:uri :score :graph-rank :embedding-rank :colbert-rank :label :description}]
      :graph-results [...]
      :embedding-results [...]
      :colbert-results [...]
      :method \"rrf\"
      :batches-used [:graph :embedding :colbert]}"
  [ctx {:keys [seed-uris query-text scope ontology-id ontology-ids limit min-similarity max-depth decay
                       colbert-index-id weights signals]
                :or {limit 10 min-similarity 0.3 max-depth 2 decay 0.6
                     weights {:graph 1.0 :embedding 1.0 :colbert 1.0}
                     signals #{:graph :embedding :colbert}}}]
  (let [
        graph-enabled? (contains? signals :graph)
        embedding-enabled? (contains? signals :embedding)
        colbert-enabled? (contains? signals :colbert)

        graph-results (when (and graph-enabled? (seq seed-uris))
                        (->> (logic/retrieval-expand-concept-neighborhood seed-uris
                                                          :max-depth max-depth
                                                          :decay decay)
                             (take (* 2 limit))))

        embedding-results (when (and embedding-enabled? query-text)
                            (retrieval-semantic-search-concepts ctx query-text
                                                       :scope scope
                                                       :ontology-id ontology-id
                                                       :ontology-ids ontology-ids
                                                       :limit (* 2 limit)
                                                       :min-similarity min-similarity))

        colbert-results (when (and colbert-enabled? query-text colbert-index-id)
                          (logic/colbert-search-concepts ctx query-text
                                                    :colbert-index-id colbert-index-id
                                                    :limit (* 2 limit)
                                                    :weight (:colbert weights 1.0)))]

    (logic/fuse-and-enrich {:graph-results graph-results
                      :embedding-results embedding-results
                      :colbert-results colbert-results
                      :weights weights
                      :limit limit
                      :signals signals})))

(defn retrieval-hybrid-search-failures
  "Search failure concepts using hybrid graph + embedding + ColBERT search.

   Specialized search for finding relevant failure types.

   Args:
     event-store: Grain event store
     query-text: Description of the failure to find
     opts:
       :seed-failure-uri - Optional seed failure URI for graph expansion
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)
       :limit - Maximum results (default 5)

   Returns:
     Vector of failure concepts with scores"
  [ctx query-text & {:keys [seed-failure-uri colbert-index-id signals limit]
                              :or {limit 5
                                   signals #{:graph :embedding :colbert}}}]
  (let [seed-uris (if seed-failure-uri
                    [seed-failure-uri]
                    ["failure:Root"])

        {:keys [results]} (hybrid-search ctx
                                          {:seed-uris seed-uris
                                           :query-text query-text
                                           :scope :failure
                                           :colbert-index-id colbert-index-id
                                           :signals signals
                                           :limit limit
                                           :min-similarity 0.3})]
    (filterv #(= :failure (:scope %)) results)))

;; --- Interface ---

(defn interface-hybrid-search-failures
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
  (retrieval-hybrid-search-failures event-store query-text
                                     :seed-failure-uri seed-failure-uri
                                     :limit limit))

;; --- Retrieval ---

(defn retrieval-hybrid-search-patterns
  "Search success patterns using hybrid graph + embedding + ColBERT search.

   Specialized search for finding relevant success patterns.

   Args:
     event-store: Grain event store
     query-text: Description of the pattern to find
     opts:
       :seed-pattern-uri - Optional seed pattern URI for graph expansion
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)
       :limit - Maximum results (default 5)

   Returns:
     Vector of success pattern concepts with scores"
  [ctx query-text & {:keys [seed-pattern-uri colbert-index-id signals limit]
                              :or {limit 5
                                   signals #{:graph :embedding :colbert}}}]
  (let [seed-uris (if seed-pattern-uri
                    [seed-pattern-uri]
                    ["success:Root"])

        {:keys [results]} (hybrid-search ctx
                                          {:seed-uris seed-uris
                                           :query-text query-text
                                           :scope :success
                                           :colbert-index-id colbert-index-id
                                           :signals signals
                                           :limit limit
                                           :min-similarity 0.3})]
    (filterv #(= :success (:scope %)) results)))

;; --- Interface ---

(defn interface-hybrid-search-patterns
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
  (retrieval-hybrid-search-patterns event-store query-text
                                     :seed-pattern-uri seed-pattern-uri
                                     :limit limit))

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

;; --- Retrieval ---

(defn find-self-patterns
  "Retrieve patterns from the current tree for self-reinforcing learning.

   Unlike find-success-patterns which aggregates across trees, this returns
   the patterns accumulated by THIS specific tree. This enables single-tree
   training scenarios where the tree learns from its own execution history.

   Args:
     ctx: System context with :event-store
     tree-id: UUID of the tree to get patterns for
     opts: {:min-confidence double} - minimum confidence threshold (default 0.2)

   Returns:
     {:strengths [{:uri :label :description :confidence :count
                   :context-conditions :action-taken :domain-type :expected-outcome} ...]
      :weaknesses [{:uri :description :frequency :severity :triggers
                    :failure-context :attempted-action :domain-type} ...]}

   Returns nil if tree has no profile or ctx/tree-id is nil."
  [ctx tree-id {:keys [min-confidence] :or {min-confidence 0.2}}]
  (when (and ctx tree-id)
    (let [profiles (read-models/get-all-tree-profiles ctx)
          profile (get profiles tree-id)]
      (when profile
        {:strengths (->> (:strengths profile)
                         (filter #(>= (:confidence %) min-confidence))
                         (map (fn [s]
                                (let [concept (logic/static-ontology-get-concept-by-uri (:pattern s))
                                      label-from-uri (when (:pattern s)
                                                       (-> (:pattern s)
                                                           (str/replace #"^success:" "")
                                                           (str/replace #"([A-Z])" " $1")
                                                           str/trim))]
                                  (cond-> {:uri (:pattern s)
                                           :label (or (:label concept) label-from-uri (:pattern s))
                                           :description (or (:description concept)
                                                            (str "Learned pattern: " (or label-from-uri (:pattern s))))
                                           :confidence (:confidence s)
                                           :avg-score (:avg-score s)
                                           :count (:evidence-count s)}
                                    (:context-conditions s)
                                    (assoc :context-conditions (:context-conditions s))
                                    (:action-taken s)
                                    (assoc :action-taken (:action-taken s))
                                    (:domain-type s)
                                    (assoc :domain-type (:domain-type s))
                                    (:expected-outcome s)
                                    (assoc :expected-outcome (:expected-outcome s))))))
                         vec)
         :weaknesses (->> (:weaknesses profile)
                          (map (fn [w]
                                 (let [concept (logic/static-ontology-get-concept-by-uri (:failure w))
                                       label-from-uri (when (:failure w)
                                                        (-> (:failure w)
                                                            (str/replace #"^failure:" "")
                                                            (str/replace #"([A-Z])" " $1")
                                                            str/trim))]
                                   (cond-> {:uri (:failure w)
                                            :description (or (:description concept)
                                                             (str "Failure: " (or label-from-uri (:failure w))))
                                            :frequency (:frequency w)
                                            :severity (:severity w)
                                            :triggers (:triggers w)}
                                     (:failure-context w)
                                     (assoc :failure-context (:failure-context w))
                                     (:attempted-action w)
                                     (assoc :attempted-action (:attempted-action w))
                                     (:domain-type w)
                                     (assoc :domain-type (:domain-type w))))))
                          vec)}))))

(defn retrieval-build-actionable-context
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
       :has-patterns? - Whether any patterns were found"
  [ctx tree-id problem-type & [{:keys [max-items include-patterns include-failures]
                                  :or {max-items 5 include-patterns true include-failures true}}]]
  (let [self-patterns (find-self-patterns ctx tree-id {:min-confidence 0.2})

        sections (atom [])

        _ (when (and include-patterns (seq (:strengths self-patterns)))
            (let [patterns (:strengths self-patterns)
                  has-rich-context? (some #(and (:context-conditions %) (:action-taken %)) patterns)
                  dedupe-key (fn [p]
                               (let [conds (:context-conditions p)
                                     action (:action-taken p)]
                                 (str (pr-str (sort-by first conds))
                                      "|" (:type action) "|" (pr-str (:target action)))))
                  deduped-patterns (->> patterns
                                        (filter #(and (:context-conditions %) (:action-taken %)))
                                        (group-by dedupe-key)
                                        (map (fn [[_ group]]
                                               (let [best (apply max-key :confidence group)]
                                                 (assoc best
                                                        :count (reduce + 0 (keep :count group))
                                                        :evidence-episodes (count group)))))
                                        (sort-by :confidence >)
                                        vec)
                  final-patterns (if (seq deduped-patterns) deduped-patterns patterns)]
              (swap! sections conj
                     (if has-rich-context?
                       (str "### Learned Rules from Success Patterns\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map logic/format-rich-pattern)
                                 (str/join "\n")))
                       (str "### What Works Well\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map #(str "- **" (:label %) "** "
                                            (format "(%.0f%% confidence)" (* 100 (:confidence %)))))
                                 (str/join "\n")))))))

        _ (when (and include-failures (seq (:weaknesses self-patterns)))
            (swap! sections conj
                   (str "### Patterns to Avoid\n"
                        (->> (:weaknesses self-patterns)
                             (take max-items)
                             (map #(str "- **" (:description %) "**"
                                        (when (:frequency %)
                                          (str " (frequency: " (format "%.0f%%" (* 100 (:frequency %))) ")"))))
                             (str/join "\n")))))

        formatted (when (seq @sections)
                    (str "## Relevant Knowledge\n\n" (str/join "\n\n" @sections)))]

    {:formatted-context formatted
     :self-patterns self-patterns
     :has-patterns? (boolean (or (seq (:strengths self-patterns))
                                  (seq (:weaknesses self-patterns))))
     :strength-count (count (:strengths self-patterns))
     :weakness-count (count (:weaknesses self-patterns))}))

;; --- Interface ---

(defn interface-build-actionable-context
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
  (retrieval-build-actionable-context ctx tree-id problem-type opts))

(defn get-trusted-sites
  "Get sites with trust score above threshold, sorted by trust.
   Options:
   - :min-trust - Minimum trust score (default 0.5)
   - :limit - Maximum sites to return"
  [ctx & [opts]]
  (read-models/get-trusted-sites ctx opts))

(defn get-site-patterns
  "Get learned patterns for a site.
   Options:
   - :pattern-type - Filter by pattern type (:navigation, :extraction, etc.)"
  [ctx domain & [opts]]
  (read-models/get-site-patterns ctx domain opts))

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
  (let [profile (read-models/get-tree-profile ctx tree-id)
        domain-str (when domain (if (keyword? domain) (name domain) domain))
        action-str (when action-type (if (keyword? action-type) (name action-type) action-type))

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
                                      (keyword (last (str/split (str failure) #":"))))
                      :context failure-context
                      :action attempted-action
                      :recorded-at recorded-at})))

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

;; --- Evidence guard ---

(defn judge-evidence-for-occurrence
  "Every judge-score event recorded for the `[sheet-id tick-id]`
   occurrence. Queried by the `:tick` tag — occurrence identity, not event
   identity: a static task-shape's sheet-id is shared across every turn, so
   the tick is what makes the reference resolvable (the SJ-1 join lesson) —
   then confirmed against the sheet so a tick reused under a different
   sheet cannot be mistaken for this occurrence's evidence."
  [{:keys [event-store tenant-id]} [sheet-id tick-id]]
  (when (and event-store tenant-id tick-id)
    (->> (es/read event-store {:types #{logic/judge-score-event-type}
                               :tags #{[:tick tick-id]}
                               :tenant-id tenant-id})
         (into [])
         (filterv #(= sheet-id (:sheet-id %))))))

(defn classify-occurrence
  "The DETERMINISTIC verdict for one occurrence. Never calls a model.

     :no-judge-evidence — nothing judged this occurrence. It is NOT a zero.
                          Conflating 'not judged' with 'judged badly' is
                          precisely what ADR 0023 exists to end.
     :judge-abstained   — judge evidence exists but carries no score (the
                          engine-side `:no-evidence` outcome, CC-14, or any
                          future abstention). Also not a zero.
     :starved-evidence  — a judge reported it had no input to judge. The
                          score, whatever it is, is an artifact of the
                          harness.
     :grounded          — a scored judgement with substantive feedback that
                          says nothing about missing input.
     :unsettled         — scored, but the feedback is too thin for the cheap
                          check to read. The verifier takes over HERE and
                          only here.

   Starvation is judged across ALL of the occurrence's judges, not the
   worst-scoring one: emptiness is a property of the shared input context,
   so one judge naming it condemns the occurrence's evidence even when a
   sibling judge scored 1.0 off the same void — the exact shape of the
   incident."
  [ctx occurrence]
  (let [evidence (judge-evidence-for-occurrence ctx occurrence)]
    (cond
      (empty? evidence)
      {:verdict :excluded :reason :no-judge-evidence :evidence-count 0}

      (some #(logic/starved-feedback? (:feedback %)) evidence)
      {:verdict :excluded :reason :starved-evidence
       :evidence-count (count evidence)
       :judge-names (mapv :judge-name evidence)}

      (every? #(nil? (:score %)) evidence)
      {:verdict :excluded :reason :judge-abstained
       :evidence-count (count evidence)}

      (some (fn [e]
              (and (some? (:score e))
                   (string? (:feedback e))
                   (>= (count (str/trim (:feedback e)))
                       logic/min-substantive-feedback-chars)))
            evidence)
      {:verdict :grounded :evidence-count (count evidence)}

      :else
      {:verdict :unsettled :reason :feedback-too-thin
       :evidence-count (count evidence)
       :evidence evidence})))

(defn evidence-verdict
  "The guard's verdict for ONE delta.

   Returns `{:grounded? bool :reason <keyword or nil>
             :settled-by :deterministic|:verifier|:declared-provenance
             :episodes <the delta's episodes>}`.

   A delta stands when AT LEAST ONE of its occurrences is grounded — a
   consolidation that cites three turns, one of which was starved, still
   learned something real from the other two, and rejecting it would be the
   protection-induced starvation ADR 0021 removes. It is excluded only when
   NONE of its occurrences can be established, and the reported reason is
   the first occurrence's, which is what a diagnosis needs.

   A delta that names no occurrence and DECLARES a basis in
   `declared-bases-admitted` is grounded by DECLARED PROVENANCE: it asserts a
   prior corpus, a classifier input or an emitted artifact rather than a judge
   event, so there is no judge evidence to resolve and no starved score to
   catch. A delta that DOES name occurrences is checked normally whatever it
   declares — CC-6's rule adds a category of writer, never an exemption from
   evidence that exists. `:basis` is reported on the verdict so an exclusion
   record says WHICH declaration was (or was not) honoured."
  [ctx {:keys [episodes] :as delta}]
  (let [episodes (vec episodes)
        basis (logic/declared-basis delta)]
    (cond
      (and (empty? episodes) (contains? logic/declared-bases-admitted basis))
      {:grounded? true :settled-by :declared-provenance :basis basis
       :episodes episodes}

      (empty? episodes)
      {:grounded? false :reason :no-episodes :settled-by :deterministic
       :episodes episodes}

      :else
      (let [verdicts (mapv #(classify-occurrence ctx %) episodes)]
        (cond
          (some #(= :grounded (:verdict %)) verdicts)
          {:grounded? true :settled-by :deterministic :episodes episodes}

          (some #(= :unsettled (:verdict %)) verdicts)
          (let [unsettled (filterv #(= :unsettled (:verdict %)) verdicts)
                explanations (logic/occurrence-explanations unsettled)
                verified? (boolean
                            (some (fn [explanation]
                                    ((logic/verifier-fn ctx) ctx {:explanation explanation}))
                                  explanations))]
            (if verified?
              {:grounded? true :settled-by :verifier :episodes episodes}
              {:grounded? false :reason :unverified-explanation
               :settled-by :verifier :episodes episodes}))

          :else
          {:grounded? false
           :reason (:reason (first (filterv #(= :excluded (:verdict %)) verdicts)))
           :settled-by :deterministic
           :episodes episodes})))))

;; --- Queries ---

(ai.obney.grain.query-processor.interface/defquery :ontology get-ontology
  "Get an ontology lifecycle record by stable identity."
  {:authorized? logic/queries-tenant-scoped?}
  [{{:keys [ontology-id]} :query :as ctx}]
  (if-let [ontology (read-models/get-ontology ctx ontology-id)]
    {:query/result ontology}
    {:cognitect.anomalies/category :cognitect.anomalies/not-found
     :cognitect.anomalies/message (str "Ontology not found: " ontology-id)}))

(ai.obney.grain.query-processor.interface/defquery :ontology list-ontologies
  "List ontology lifecycle records visible to the caller's tenant."
  {:authorized? logic/queries-tenant-scoped?}
  [{:keys [] :as ctx}]
  {:query/result (read-models/list-ontologies ctx)})

(ai.obney.grain.query-processor.interface/defquery :ontology get-concepts
  "Get concepts from the ontology, optionally filtered by scope or broader URI."
  {:authorized? logic/queries-tenant-scoped?}
  [{{:keys [scope broader-uri include-narrower?]} :query
    :keys [event-store] :as ctx}]
  (let [concepts (read-models/get-concepts ctx {:scope scope :broader-uri broader-uri})
        result (if include-narrower?
                 (mapv (fn [c]
                         (assoc c :narrower-concepts
                                (read-models/get-narrower-concepts ctx (:uri c))))
                       concepts)
                 concepts)]
    {:query/result result}))

(ai.obney.grain.query-processor.interface/defquery :ontology get-concept
  "Get a single concept by URI."
  {:authorized? logic/queries-tenant-scoped?}
  [{{:keys [ontology-id uri]} :query
    :keys [event-store] :as ctx}]
  (if-let [concept (if ontology-id
                     (read-models/get-concept-by-uri ctx ontology-id uri)
                     (read-models/get-concept-by-uri ctx uri))]
    {:query/result concept}
    (if-let [static-concept (logic/static-ontology-get-concept-by-uri uri)]
      {:query/result static-concept}
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Concept not found: " uri)})))

(ai.obney.grain.query-processor.interface/defquery :ontology get-static-concepts
  "Get concepts from the static ontology definitions."
  [{{:keys [scope]} :query}]
  (let [concepts (if scope
                   (logic/get-concepts-by-scope scope)
                   (logic/get-all-static-concepts))]
    {:query/result concepts}))

(ai.obney.grain.query-processor.interface/defquery :ontology concept-statistics
  "Get statistics about the concept graph."
  {:authorized? logic/queries-tenant-scoped?}
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (read-models/concept-statistics ctx)})

(ai.obney.grain.query-processor.interface/defquery :ontology get-tree-profile
  "Get the profile for a specific tree, including strengths, weaknesses, and capabilities."
  [{{:keys [tree-id]} :query
    :keys [event-store] :as ctx}]
  (if-let [profile (read-models/get-tree-profile ctx tree-id)]
    {:query/result profile}
    {:cognitect.anomalies/category :cognitect.anomalies/not-found
     :cognitect.anomalies/message (str "Tree profile not found: " tree-id)}))

(ai.obney.grain.query-processor.interface/defquery :ontology list-tree-profiles
  "List all tree profiles."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (read-models/get-all-tree-profiles ctx)})

(ai.obney.grain.query-processor.interface/defquery :ontology tree-profile-statistics
  "Get statistics about tree profiles."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (read-models/tree-profile-statistics ctx)})

(ai.obney.grain.query-processor.interface/defquery :ontology find-similar-trees
  "Find trees that solve a similar problem type with optional filtering.

   Args:
   - problem-type: The problem URI to search for
   - required-patterns: Optional set of success patterns the tree must have
   - min-success-rate: Minimum success rate threshold
   - limit: Maximum number of results"
  [{{:keys [problem-type required-patterns min-success-rate limit]} :query
    :keys [event-store] :as ctx}]
  (let [trees (read-models/find-trees-by-problem ctx problem-type)
        filtered (cond->> trees
                   min-success-rate
                   (filter (fn [p]
                             (some #(and (= problem-type (:problem-uri %))
                                         (>= (:success-rate %) min-success-rate))
                                   (:solves p))))

                   required-patterns
                   (filter (fn [p]
                             (let [tree-patterns (set (map :pattern (:strengths p)))]
                               (every? tree-patterns required-patterns))))

                   limit
                   (take limit))]
    {:query/result (vec filtered)}))

(ai.obney.grain.query-processor.interface/defquery :ontology find-failure-patterns
  "Find common failure patterns for a given problem type.

   Args:
   - problem-type: The problem URI to search for
   - min-frequency: Minimum frequency to include"
  [{{:keys [problem-type min-frequency]} :query
    :keys [event-store] :as ctx}]
  (let [
        trees (read-models/find-trees-by-problem ctx problem-type)
        all-weaknesses (mapcat :weaknesses trees)
        by-failure (group-by :failure all-weaknesses)
        patterns (->> by-failure
                      (map (fn [[failure-uri weaknesses]]
                             {:failure-uri failure-uri
                              :occurrence-count (count weaknesses)
                              :avg-frequency (/ (reduce + (map :frequency weaknesses))
                                                (count weaknesses))
                              :severities (frequencies (map :severity weaknesses))
                              :common-triggers (->> weaknesses
                                                    (mapcat :triggers)
                                                    frequencies
                                                    (sort-by val >)
                                                    (take 5)
                                                    (map first)
                                                    vec)}))
                      (filter #(or (nil? min-frequency)
                                   (>= (:avg-frequency %) min-frequency)))
                      (sort-by :occurrence-count >)
                      vec)]
    {:query/result patterns}))

(ai.obney.grain.query-processor.interface/defquery :ontology get-node-type-learnings
  "Get aggregated learnings for a specific node type."
  [{{:keys [node-type]} :query
    :keys [event-store] :as ctx}]
  (let [node-type-kw (if (keyword? node-type) node-type (keyword node-type))]
    (if-let [learnings (read-models/get-node-type-learnings ctx node-type-kw)]
      {:query/result learnings}
      {:query/result {}})))

(ai.obney.grain.query-processor.interface/defquery :ontology list-node-learnings
  "List all node learnings aggregated by type."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (read-models/get-all-node-learnings ctx)})

(ai.obney.grain.query-processor.interface/defquery :ontology node-learning-statistics
  "Get statistics about node-level learning."
  [{{:keys []} :query
    :keys [event-store] :as ctx}]
  {:query/result (read-models/node-learning-statistics ctx)})

(ai.obney.grain.query-processor.interface/defquery :ontology export-ttl
  "Export ontology to SKOS/OWL Turtle format.

   Args:
   - scope: Optional filter (:failure, :success, :problem)
   - include-instances?: Include tree profiles and node experiences
   - include-profiles?: Include tree profiles specifically
   - include-experiences?: Include node experiences specifically
   - base-uri: Base URI for the ontology"
  [{{:keys [scope include-instances? include-profiles? include-experiences? base-uri]} :query
    :keys [event-store] :as ctx}]
  (let [
        include-profiles (if (some? include-instances?)
                           include-instances?
                           (if (some? include-profiles?)
                             include-profiles?
                             true))
        include-experiences (if (some? include-instances?)
                              include-instances?
                              (if (some? include-experiences?)
                                include-experiences?
                                true))
        ttl (full-export ctx
                                       {:scope scope
                                        :include-profiles? include-profiles
                                        :include-experiences? include-experiences
                                        :base-uri (or base-uri "http://obney.ai/workshop/ontology/")})]
    {:query/result {:format :turtle
                    :content ttl
                    :validation (logic/validate-turtle ttl)}}))

(ai.obney.grain.query-processor.interface/defquery :ontology build-context
  "Build ontology context for tree builder integration.

   Returns:
   - few-shot-examples: Similar successful trees
   - patterns-to-avoid: Common failure patterns
   - recommended-patterns: Success patterns to consider

   Args:
   - problem-type: The problem type being solved
   - required-patterns: Optional patterns that must be present"
  [{{:keys [problem-type required-patterns]} :query
    :keys [event-store] :as ctx}]
  (let [
        similar-trees (vec (take 5 (read-models/find-trees-by-problem ctx problem-type)))

        tree-profiles (into {}
                            (for [tree similar-trees
                                  :let [id (:tree-id tree)]
                                  :when id]
                              [id (read-models/get-tree-profile ctx id)]))

        success-patterns (->> tree-profiles
                              vals
                              (mapcat :strengths)
                              (group-by :pattern)
                              (map (fn [[pattern instances]]
                                     {:pattern pattern
                                      :avg-confidence (/ (reduce + (map :confidence instances))
                                                         (count instances))
                                      :tree-count (count instances)}))
                              (sort-by :avg-confidence >)
                              vec)

        failure-patterns (->> tree-profiles
                              vals
                              (mapcat :weaknesses)
                              (group-by :failure)
                              (map (fn [[failure instances]]
                                     {:failure-uri failure
                                      :frequency (/ (reduce + (map :frequency instances))
                                                    (count instances))
                                      :common-triggers (->> instances
                                                            (mapcat :triggers)
                                                            frequencies
                                                            (sort-by val >)
                                                            (take 3)
                                                            (map first)
                                                            vec)}))
                              (sort-by :frequency >)
                              (take 5)
                              vec)

        llm-learnings (read-models/get-node-type-learnings ctx :llm)]

    {:query/result {:problem-type problem-type
                    :similar-tree-count (count similar-trees)
                    :few-shot-examples (mapv (fn [[id profile]]
                                               {:tree-id id
                                                :strengths (:strengths profile)
                                                :solves (filter #(= problem-type (:problem-uri %))
                                                                (:solves profile))})
                                             tree-profiles)
                    :recommended-patterns success-patterns
                    :patterns-to-avoid failure-patterns
                    :llm-learnings llm-learnings}}))

;; --- Retrieval ---

(defn build-concept-graph
  "Build a concept graph from event store or static ontology.

   Args:
     ctx: Context with :event-store and :tenant-id (optional, uses static if nil)

   Returns:
     Graph map with :nodes and :edges suitable for graph algorithms"
  [{:keys [event-store] :as ctx}]
  (let [
        concepts (if event-store
                   (rmp/project ctx :ontology/concepts)
                   (reduce (fn [acc concept]
                             (assoc acc (:uri concept)
                                    {:uri (:uri concept)
                                     :label (:label concept)
                                     :scope (:scope concept)
                                     :broader (set (or (:broader concept) []))
                                     :narrower #{}
                                     :related #{}}))
                           {}
                           (logic/get-all-static-concepts)))]
    (logic/concepts->graph concepts)))

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
  [ctx {:keys [problem-type required-patterns exclude-patterns
                       min-success-rate limit]
                :or {min-success-rate 0.7 limit 10}}]
  (let [
        profiles (read-models/get-all-tree-profiles ctx)

        graph (logic/build-static-concept-graph)

        related-problems (when problem-type
                           (->> (logic/graph-bfs-spreading-activation
                                 graph [problem-type]
                                 {:max-depth 2 :decay 0.5})
                                (map :uri)
                                (set)))

        expanded-patterns (when (seq required-patterns)
                            (->> required-patterns
                                 (mapcat #(map :uri
                                               (logic/graph-bfs-spreading-activation
                                                graph [%] {:max-depth 1 :decay 0.8})))
                                 (set)))]

    (->> profiles
         vals
         (filter (fn [p]
                   (or (nil? problem-type)
                       (some (fn [solve]
                               (and (>= (:success-rate solve) min-success-rate)
                                    (or (= problem-type (:problem-uri solve))
                                        (contains? related-problems (:problem-uri solve)))))
                             (:solves p)))))

         (filter (fn [p]
                   (or (nil? required-patterns)
                       (empty? required-patterns)
                       (let [tree-patterns (set (map :pattern (:strengths p)))]
                         (some #(or (contains? tree-patterns %)
                                    (contains? (set expanded-patterns) %))
                               required-patterns)))))

         (filter (fn [p]
                   (or (nil? exclude-patterns)
                       (empty? exclude-patterns)
                       (let [tree-patterns (set (map :pattern (:strengths p)))]
                         (not (some #(contains? tree-patterns %) exclude-patterns))))))

         (map (fn [p]
                (let [
                      problem-match (when problem-type
                                      (some (fn [solve]
                                              (cond
                                                (= problem-type (:problem-uri solve))
                                                {:uri (:problem-uri solve)
                                                 :score 1.0
                                                 :success-rate (:success-rate solve)}

                                                (contains? related-problems (:problem-uri solve))
                                                {:uri (:problem-uri solve)
                                                 :score 0.8
                                                 :success-rate (:success-rate solve)}))
                                            (:solves p)))

                      matching-patterns (when (seq required-patterns)
                                          (let [tree-patterns (set (map :pattern (:strengths p)))]
                                            (filter #(or (contains? tree-patterns %)
                                                         (contains? (set expanded-patterns) %))
                                                    required-patterns)))

                      strength-score (if (seq (:strengths p))
                                       (/ (reduce + 0 (map :confidence (:strengths p)))
                                          (count (:strengths p)))
                                       0.0)

                      weakness-penalty (if (seq (:weaknesses p))
                                         (* 0.2 (/ (reduce + 0 (map :frequency (:weaknesses p)))
                                                   (count (:weaknesses p))))
                                         0.0)

                      score (+ (* 0.4 (or (:score problem-match) 0.5))
                               (* 0.3 strength-score)
                               (* 0.2 (/ (count matching-patterns)
                                         (max 1 (count required-patterns))))
                               (- (* 0.1 weakness-penalty)))]

                  {:tree-id (:tree-id p)
                   :profile p
                   :score score
                   :matching-patterns (vec matching-patterns)
                   :problem-match problem-match})))

         (sort-by :score >)

         (take limit)

         vec)))

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
     Vector of {:failure-uri :subtype-uri :total-occurrences :avg-frequency
                :severity-distribution :common-triggers :tree-count}"
  [ctx {:keys [problem-type failure-uri min-frequency limit]
                :or {min-frequency 0.2 limit 20}}]
  (let [
        profiles (read-models/get-all-tree-profiles ctx)

        relevant-profiles (if problem-type
                            (filter (fn [[_ p]]
                                      (some #(= problem-type (:problem-uri %))
                                            (:solves p)))
                                    profiles)
                            profiles)

        all-weaknesses (->> relevant-profiles
                            vals
                            (mapcat :weaknesses)
                            (filter #(>= (:frequency %) min-frequency))
                            (filter #(or (nil? failure-uri)
                                         (= failure-uri (:failure %))
                                         (= failure-uri (:subtype %)))))

        by-failure (group-by :failure all-weaknesses)]

    (->> by-failure
         (map (fn [[uri weaknesses]]
                (let [subtypes (frequencies (keep :subtype weaknesses))
                      severities (frequencies (map :severity weaknesses))
                      triggers (->> weaknesses
                                    (mapcat :triggers)
                                    frequencies
                                    (sort-by val >)
                                    (take 5)
                                    (map first))]
                  {:failure-uri uri
                   :subtype-distribution subtypes
                   :total-occurrences (count weaknesses)
                   :avg-frequency (/ (reduce + 0 (map :frequency weaknesses))
                                     (count weaknesses))
                   :severity-distribution severities
                   :common-triggers (vec triggers)
                   :tree-count (count (distinct (keep :tree-id weaknesses)))})))

         (sort-by :total-occurrences >)

         (take limit)

         vec)))

(defn find-patterns-to-avoid
  "Find failure patterns that should be avoided for a problem type.

   Identifies high-frequency, high-severity failures with actionable triggers."
  [ctx {:keys [problem-type] :as opts}]
  (->> (find-failure-patterns ctx opts)
       (filter #(or (> (:avg-frequency %) 0.3)
                    (get-in % [:severity-distribution :critical])
                    (get-in % [:severity-distribution :high])))
       (map (fn [fp]
              {:failure-uri (:failure-uri fp)
               :description (:description (logic/static-ontology-get-concept-by-uri (:failure-uri fp)))
               :frequency (:avg-frequency fp)
               :severity (first (keys (sort-by val > (:severity-distribution fp))))
               :triggers (:common-triggers fp)}))
       vec))

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
  [ctx {:keys [problem-type min-success-rate min-confidence limit]
                :or {min-success-rate 0.8 min-confidence 0.7 limit 10}}]
  (let [
        profiles (read-models/get-all-tree-profiles ctx)

        high-performers (if problem-type
                          (->> profiles
                               vals
                               (filter (fn [p]
                                         (some (fn [solve]
                                                 (and (= problem-type (:problem-uri solve))
                                                      (>= (:success-rate solve) min-success-rate)))
                                               (:solves p)))))
                          (->> profiles
                               vals
                               (filter (fn [p]
                                         (when-let [solves (seq (:solves p))]
                                           (>= (/ (reduce + 0 (map :success-rate solves))
                                                  (count solves))
                                               min-success-rate))))))

        all-strengths (->> high-performers
                           (mapcat :strengths)
                           (filter #(>= (:confidence %) min-confidence)))

        by-pattern (group-by :pattern all-strengths)]

    (->> by-pattern
         (map (fn [[uri strengths]]
                (let [concept (logic/static-ontology-get-concept-by-uri uri)]
                  {:pattern-uri uri
                   :label (:label concept)
                   :description (:description concept)
                   :avg-confidence (/ (reduce + 0 (map :confidence strengths))
                                      (count strengths))
                   :avg-score (/ (reduce + 0 (map :avg-score strengths))
                                 (count strengths))
                   :tree-count (count strengths)
                   :evidence-count (reduce + 0 (map :evidence-count strengths))})))

         (sort-by (juxt :tree-count :avg-confidence) >)

         (take limit)

         vec)))

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
      :recommended-patterns [...] - Success patterns to use
      :patterns-to-avoid [...] - Common failure patterns
      :related-concepts [...] - Neighborhood expansion for context
      :problem-hierarchy {...} - Problem type with parent/child
      :self-patterns {...} - Tree's own patterns (when self-learning enabled)}"
  [ctx {:keys [problem-type required-patterns user-domain tree-id self-learning?]
        :as opts}]
  (let [
        self-patterns (when (and self-learning? tree-id)
                        (find-self-patterns ctx tree-id {:min-confidence 0.2}))

        few-shot-trees (when ctx
                         (find-similar-trees ctx
                                             {:problem-type problem-type
                                              :required-patterns required-patterns
                                              :min-success-rate 0.0
                                              :limit 5}))

        cross-tree-patterns (if ctx
                              (find-success-patterns ctx
                                                     {:problem-type problem-type
                                                      :min-success-rate 0.0
                                                      :min-confidence 0.1
                                                      :limit 8})
                              (->> (logic/get-concepts-by-scope :success)
                                   (filter #(not= "success:Root" (:uri %)))
                                   (filter #(not (contains? #{"success:StructuralPattern"
                                                              "success:InstructionPattern"
                                                              "success:DataFlowPattern"}
                                                            (:uri %))))
                                   (map (fn [c]
                                          {:pattern-uri (:uri c)
                                           :label (:label c)
                                           :description (:description c)
                                           :avg-confidence 0.5}))))

        recommended-patterns (vec (concat (:strengths self-patterns)
                                          cross-tree-patterns))

        cross-tree-avoid (when ctx
                           (find-patterns-to-avoid ctx
                                                   {:problem-type problem-type}))

        patterns-to-avoid (vec (concat (:weaknesses self-patterns)
                                       cross-tree-avoid))

        related-concepts (when problem-type
                           (->> (logic/retrieval-expand-concept-neighborhood [problem-type] :max-depth 2)
                                (remove #(= problem-type (:uri %)))
                                (take 10)))

        problem-hierarchy (when problem-type
                            (let [concept (logic/static-ontology-get-concept-by-uri problem-type)]
                              {:uri problem-type
                               :label (:label concept)
                               :description (:description concept)
                               :broader (mapv (fn [uri]
                                                {:uri uri
                                                 :label (:label (logic/static-ontology-get-concept-by-uri uri))})
                                              (or (:broader concept) []))
                               :narrower (->> (logic/static-ontology-get-narrower-concepts problem-type)
                                              (mapv (fn [c]
                                                      {:uri (:uri c)
                                                       :label (:label c)})))}))]

    {:few-shot-trees (vec few-shot-trees)
     :recommended-patterns recommended-patterns
     :patterns-to-avoid patterns-to-avoid
     :related-concepts (vec related-concepts)
     :problem-hierarchy problem-hierarchy
     :self-patterns self-patterns
     :context-metadata {:problem-type problem-type
                        :required-patterns required-patterns
                        :user-domain user-domain
                        :tree-id tree-id
                        :self-learning? self-learning?
                        :generated-at (str (java.time.Instant/now))}}))

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
  [ctx query-concepts {:keys [max-depth] :or {max-depth 2} :as opts}]
  (let [
        graph-batch (->> (logic/retrieval-expand-concept-neighborhood (vec query-concepts)
                                                      :max-depth max-depth)
                         (take 20))

        tree-batch (->> (find-similar-trees ctx
                                            {:problem-type (first query-concepts)
                                             :limit 10})
                        (map (fn [t] {:uri (str (:tree-id t))
                                      :score (:score t)})))

        pattern-batch (->> (find-success-patterns ctx opts)
                           (map (fn [p] {:uri (:pattern-uri p)
                                         :score (:avg-confidence p)})))

        merged (logic/retrieve-with-rrf [graph-batch tree-batch pattern-batch])]

    {:graph-expansion graph-batch
     :similar-trees tree-batch
     :success-patterns pattern-batch
     :merged-scores merged}))

(defn hybrid-search-batch
  "Batched hybrid-search over MANY query-texts. The ColBERT signal is computed in ONE
   batched pass (index loaded ONCE, one bridge round-trip); embedding + graph stay
   per-query (in-JVM, no subprocess). Returns a vector of per-query result-maps, each
   shaped EXACTLY like hybrid-search's output, aligned to `:query-texts`.

   Use this instead of mapping hybrid-search per query over a whole transcript: it
   collapses N ColBERT round-trips (and N index loads) into one, with no per-search
   reload, while keeping the embedding+ColBERT RRF fusion substance-identical to the
   single-query path.

   Args:
     ctx - Context map containing :event-store
     opts - same as hybrid-search, except:
       :query-texts - Vector of natural-language queries (instead of :query-text)
       :seed-uris   - shared graph seeds applied to every query (usually nil)"
  [ctx {:keys [query-texts seed-uris scope ontology-id ontology-ids limit min-similarity
               max-depth decay colbert-index-id weights signals]
        :or {limit 10 min-similarity 0.3 max-depth 2 decay 0.6
             weights {:graph 1.0 :embedding 1.0 :colbert 1.0}
             signals #{:graph :embedding :colbert}}}]
  (let [graph-enabled? (contains? signals :graph)
        embedding-enabled? (contains? signals :embedding)
        colbert-enabled? (contains? signals :colbert)
        query-texts (vec query-texts)
        colbert-batches (when (and colbert-enabled? (seq query-texts) colbert-index-id)
                          (logic/colbert-search-concepts-batch ctx query-texts
                                                         :colbert-index-id colbert-index-id
                                                         :limit (* 2 limit)
                                                         :weight (:colbert weights 1.0)))]
    (mapv (fn [i query-text]
            (let [graph-results (when (and graph-enabled? (seq seed-uris))
                                  (->> (logic/retrieval-expand-concept-neighborhood seed-uris
                                                                    :max-depth max-depth
                                                                    :decay decay)
                                       (take (* 2 limit))))
                  embedding-results (when (and embedding-enabled? query-text)
                                      (retrieval-semantic-search-concepts ctx query-text
                                                                 :scope scope
                                                                 :ontology-id ontology-id
                                                                 :ontology-ids ontology-ids
                                                                 :limit (* 2 limit)
                                                                 :min-similarity min-similarity))
                  colbert-results (when colbert-batches (nth colbert-batches i nil))]
              (logic/fuse-and-enrich {:graph-results graph-results
                                :embedding-results embedding-results
                                :colbert-results colbert-results
                                :weights weights
                                :limit limit
                                :signals signals})))
          (range)
          query-texts)))

(defn build-ontology-context-with-embeddings
  "Build comprehensive ontology context using hybrid search.

   Enhanced version of build-ontology-context that leverages embeddings
   and ColBERT (when available) to provide more semantically relevant results.

   Args:
     event-store: Grain event store
     opts:
       :problem-type - Primary problem URI
       :problem-description - Natural language description (for embedding/ColBERT search)
       :required-patterns - Success patterns that should be used
       :user-domain - Domain context
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)

   Returns:
     Same structure as build-ontology-context but with embedding/ColBERT-enhanced results"
  [ctx {:keys [problem-type problem-description required-patterns user-domain
                       colbert-index-id signals]
                :or {signals #{:graph :embedding :colbert}}
                :as opts}]
  (let [
        has-embeddings? (and (contains? signals :embedding)
                             (seq (read-models/get-all-concept-embeddings ctx)))

        has-colbert? (and (contains? signals :colbert)
                          colbert-index-id
                          (logic/resolve-colbert-search-fn))

        base-context (build-ontology-context ctx opts)

        enhanced-patterns (when (and (or has-embeddings? has-colbert?) problem-description)
                            (retrieval-hybrid-search-patterns ctx problem-description
                                                     :colbert-index-id colbert-index-id
                                                     :signals signals
                                                     :limit 5))

        enhanced-failures (when (and (or has-embeddings? has-colbert?) problem-description)
                            (retrieval-hybrid-search-failures ctx
                                                     (str "failures when " problem-description)
                                                     :colbert-index-id colbert-index-id
                                                     :signals signals
                                                     :limit 5))

        hybrid-related (when (and (or has-embeddings? has-colbert?) problem-type problem-description)
                         (:results (hybrid-search ctx
                                                   {:seed-uris [problem-type]
                                                    :query-text problem-description
                                                    :colbert-index-id colbert-index-id
                                                    :signals signals
                                                    :limit 15})))]

    (cond-> base-context
      (seq enhanced-patterns)
      (assoc :embedding-recommended-patterns enhanced-patterns)

      (seq enhanced-failures)
      (assoc :embedding-patterns-to-avoid enhanced-failures)

      (seq hybrid-related)
      (assoc :hybrid-related-concepts hybrid-related)

      has-embeddings?
      (assoc-in [:context-metadata :embeddings-used] true)

      has-colbert?
      (assoc-in [:context-metadata :colbert-used] true)

      true
      (assoc-in [:context-metadata :signals-enabled] signals))))

;; --- Harvest ---

(defn class-occurrences-through
  "RR-21 (finding, inspection); RR-23 (tag-scoped): this class's
   `:ontology/tree-class-occurrence-recorded` events, in durable order,
   bounded to (and including) the one whose `:event/id` is
   `upper-bound-event-id` — nil bound means unbounded (every occurrence
   recorded so far). PUBLIC: the durable-order 'as of THIS occurrence'
   snapshot both `:verdict-occurrences` and `winning-shape-coherence` are
   computed over, so the report command derives both numbers from the
   identical bounded view — never the store's current state at PROCESSING
   time, which is a race a backlogged/reordered delivery can and does
   expose (reproduced: three verdicts landed before the handler ran on any
   of them; every report read back the FINAL count instead of its own
   occurrence's position).

   RR-23: scoped by the `[:description-target class-id]` tag every
   `:ontology/tree-class-occurrence-recorded` event already carries
   (commands.clj) instead of a type-wide tenant scan — cost no longer
   grows with occurrences belonging to other classes. The `:assigned-
   tree-id` filter stays as a defensive check (tag narrows, filter
   decides); no legacy concern, this tag predates RR-23."
  [ctx class-id upper-bound-event-id]
  (let [this-classes-occurrences
        (->> (es/read (:event-store ctx)
                      {:types #{:ontology/tree-class-occurrence-recorded}
                       :tenant-id (:tenant-id ctx)
                       :tags #{[:description-target class-id]}})
             (into [])
             (filter #(= class-id (:assigned-tree-id %))))]
    (logic/durable-through this-classes-occurrences upper-bound-event-id)))

(defn- successful-verdict-pairs
  "RR-21: the [source-sheet-id source-tick-id] pairs of this class's
   `:success`-verdict tree-class occurrences ONLY, bounded to
   `upper-bound-event-id` (see `class-occurrences-through`) — the population
   the winning-shape ratio is computed over. Failed, timed-out, cancelled and
   abandoned campaigns never enter this set (cancelled/abandoned campaigns
   have no verdict occurrence at all; failed/timed-out ones are excluded by
   the `:verdict` filter), so they cannot make a failure-heavy class look
   more coherent. HP-2: the bare source-sheet-id is the STATIC
   workflow-definition sheet shared by every turn of a task-shape, so a
   sheet-only set either matches nothing (a bookend's :sheet-id is the
   EPHEMERAL Phase-2 sheet, a disjoint domain) or over-matches across classes
   sharing the host. The pair is what uniquely names one occurrence across
   its classification attribution, explicit verdict fact, and bookend
   (:source-sheet-id/:source-tick-id)."
  [ctx class-id upper-bound-event-id]
  (->> (class-occurrences-through ctx class-id upper-bound-event-id)
       (filter #(= :success (:verdict %)))
       (map (juxt :source-sheet-id :source-tick-id))
       (into #{})))

(defn- winning-shape-bookends
  "RR-23: this `pair`'s (`[source-sheet-id source-tick-id]`) Phase-2 bookends,
   scoped by the `[:source-tick source-tick-id]` tag `record-rlm-tree-
   execution-completion` now writes at its emit site — a targeted read
   instead of a type-wide tenant scan. `:source-sheet-id` stays a defensive
   filter (tag narrows, filter decides).

   Legacy replay: a bookend written BEFORE this slice landed carries no
   `:source-tick` tag and is invisible to the scoped read above. Correctness
   for such pre-existing stores is opt-in only (`legacy-replay?`), never the
   default — falling back to a full type scan on every call would restore
   exactly the O(store) cost this slice removes. When opted in and the
   scoped read finds nothing, fall back once to the old unscoped scan
   filtered by `pair`."
  [ctx pair legacy-replay?]
  (let [[sheet tick] pair
        scoped (->> (es/read (:event-store ctx)
                             {:types #{:sheet/rlm-tree-execution-completed}
                              :tenant-id (:tenant-id ctx)
                              :tags #{[:source-tick tick]}})
                    (into [])
                    (filter #(= sheet (:source-sheet-id %))))]
    (if (and legacy-replay? (empty? scoped))
      (->> (es/read (:event-store ctx)
                    {:types #{:sheet/rlm-tree-execution-completed}
                     :tenant-id (:tenant-id ctx)})
           (into [])
           (filter #(and (= sheet (:source-sheet-id %)) (= tick (:source-tick-id %)))))
      scoped)))

(defn- winning-shape
  "RR-21 + RR-23: the fingerprint of the LAST `:status :success` Phase-2
   bookend, in durable event order, among `pair`'s bookends (see
   `winning-shape-bookends`) — the winning shape that carried that
   successful campaign to success. Shapes the campaign abandoned earlier
   (failed bookends, or earlier success bookends superseded by a later one)
   are process evidence, never winning shapes. nil when the campaign has no
   success bookend at all — it solved by direct tool call, or the (non-nil)
   fingerprint field is simply absent — which is exactly the 'successful
   campaign with no recorded shape' case: it contributes to
   `:successful-campaigns` but not to `:successful-shape-observations`."
  [ctx pair legacy-replay?]
  (->> (winning-shape-bookends ctx pair legacy-replay?)
       (filter #(and (= pair [(:source-sheet-id %) (:source-tick-id %)])
                     (= :success (:status %))))
       last
       :tree-fingerprint))

(defn winning-shape-coherence
  "RR-21 (rule ReportSuccessfulShapeCoherence, dossier G11): the ratified
   coherence measure — distinct successful terminal shapes divided by
   successful campaigns, ONE winning shape per successful campaign (the
   terminal `:success` bookend in durable order; see `winning-shape`).
   Replaces the never-witnessed all-trees `distinct-tree-shapes` measure
   (retired from harvest; the consolidator keeps its own independent
   descriptive aggregate for reflection).

   Measurable iff `successful-campaigns > 0` AND
   `successful-shape-observations > 0` — a class with successful campaigns
   but no recorded shape (every campaign solved by direct tool call) is
   `:not-measurable`, distinct from a real `:qualified`/`:rejected` verdict:
   'harvesting nothing looks identical to nothing qualifying' (ADR 0029),
   so the absence of evidence is never reported as if it were evidence.
   Computed by a targeted scan, like `occurrence-scores`.

   4-arity `upper-bound-event-id` (see `class-occurrences-through`) computes
   the measure strictly AS OF that occurrence's durable position — the fix
   for the scheduling-dependent race a backlogged/reordered delivery
   exposed. The 2- and 3-arities stay unbounded ('everything so far'), used
   by `maybe-harvest!`'s live (non-durable) gate check, which has no single
   occurrence identity to be 'as of'.

   RR-23: bookends are read PER PAIR, scoped by `[:source-tick source-tick-
   id]` (see `winning-shape-bookends`), instead of one type-wide tenant
   scan — cost no longer grows with other classes' bookends. `config`'s
   optional `:legacy-replay?` (default false/absent — never on by default)
   opts every pair's read into a one-time unscoped fallback when its
   scoped read is empty, for stores holding bookends written before this
   slice; `default-harvest-config` does not set it, so the live gate stays
   tag-scoped-only unless a caller explicitly asks for legacy replay."
  ([ctx class-id] (winning-shape-coherence ctx class-id logic/default-harvest-config nil))
  ([ctx class-id config] (winning-shape-coherence ctx class-id config nil))
  ([ctx class-id {:keys [max-shapes-ratio legacy-replay?]} upper-bound-event-id]
   (let [pairs (successful-verdict-pairs ctx class-id upper-bound-event-id)
         successful-campaigns (count pairs)
         winning-shapes (into [] (keep #(winning-shape ctx % legacy-replay?)) pairs)
         successful-shape-observations (count winning-shapes)
         distinct-successful-shapes (count (distinct winning-shapes))
         measurable? (and (pos? successful-campaigns) (pos? successful-shape-observations))
         ratio (when measurable? (/ distinct-successful-shapes successful-campaigns))]
     {:successful-campaigns successful-campaigns
      :successful-shape-observations successful-shape-observations
      :distinct-successful-shapes distinct-successful-shapes
      :ratio ratio
      :status (cond
                (not measurable?) :not-measurable
                (<= ratio max-shapes-ratio) :qualified
                :else :rejected)})))

(defn occurrence-scores
  "CC-26 — the CONSISTENCY axis: this class's per-OCCURRENCE aggregate judge
   score, in temporal order (most recent LAST). Occurrences with no judge
   score are omitted (no signal is not a zero).

   Why this is an event scan and not the standing judge-averages read-model:
   that read-model is an ORDER-FREE aggregate BY CONSTRUCTION (its whole
   design note is that a score seen before or after its classification both
   land correctly), so it cannot answer 'the last N'. Recency has to come from
   event-store order — the same idiom the consolidator's
   gather-recent-tree-class-events uses (a newest-last es/read; since PR-1
   bounded there by the evidence token budget rather than an event count).
   Like distinct-tree-shapes this is reached only past the cheap
   occurrence pre-gate, so it runs rarely.

   The per-occurrence aggregate is the mean of that occurrence's judge scores
   — the marginal along the DIMENSION axis. That is not a re-collapse of the
   two floors: the dimension floor is the OTHER marginal (per judge, over the
   class's lifetime), and the two together constrain both axes of the
   (judge x occurrence) matrix that the old single scalar flattened.

   RR-23: the occurrence scan is scoped by `[:description-target class-id]`
   (the tag `:ontology/tree-class-occurrence-recorded` already carries).
   Judge scores are then read ONCE PER OCCURRENCE, scoped by
   `[:tick tick-id]` (the tag `:judge/score-emitted` already carries), in
   place of one type-wide scan over the tenant's fastest-growing event type
   grouped in memory — cost is now proportional to this class's occurrence
   count, not the store's total score count. `:sheet-id` stays a defensive
   filter (tick alone already names one occurrence; tag narrows, filter
   decides)."
  [ctx class-id]
  (let [ordered-occurrences (->> (es/read (:event-store ctx)
                                          {:types #{:ontology/tree-class-occurrence-recorded}
                                           :tenant-id (:tenant-id ctx)
                                           :tags #{[:description-target class-id]}})
                                 (into [])
                                 (filter #(= class-id (:assigned-tree-id %)))
                                 (map (juxt :source-sheet-id :source-tick-id))
                                 (distinct))]
    (into []
          (keep (fn [[sheet tick]]
                  (let [scores (->> (es/read (:event-store ctx)
                                             {:types #{:judge/score-emitted}
                                              :tenant-id (:tenant-id ctx)
                                              :tags #{[:tick tick]}})
                                    (into [])
                                    (filter #(and (number? (:score %)) (= sheet (:sheet-id %)))))]
                    (when (seq scores)
                      (/ (reduce + 0.0 (map :score scores))
                         (double (count scores)))))))
          ordered-occurrences)))

(defn- latest-classified-behavior-id
  "The top behavior-id from the most-recent task-classified event for this
   class that carries a non-empty :behavioral-subtrees — the live signal of
   which behavior this class composes into. nil when the class has never been
   behaviorally classified.

   RR-23: scoped by the `[:description-target class-id]` tag
   `:ontology/task-classified` already carries."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:types #{:ontology/task-classified} :tenant-id (:tenant-id ctx)
                 :tags #{[:description-target class-id]}})
       (into [])
       (filter #(and (= class-id (:assigned-tree-id %))
                     (seq (:behavioral-subtrees %))))
       last
       :behavioral-subtrees
       first
       :behavior-id))

(defn nearest-abstract-behavior
  "Walk skos:broader UP from the class's classified behavior to the top
   abstract behavior (auto-waterfall). Returns the abstract behavior's id
   (the parent-behavior for the mint), or nil when the class has no
   behavioral signal to anchor under — in which case harvest is skipped
   rather than creating an orphan."
  [ctx class-id]
  (when-let [behavior-id (latest-classified-behavior-id ctx class-id)]
    (let [concepts (rmp/project ctx :ontology/concepts)]
      (loop [uri (logic/harvest-behavioral-subtree-uri behavior-id)
             id  behavior-id
             seen #{}]
        (let [parent-uri (->> (get-in concepts [uri :broader])
                              (filter #(and (string? %)
                                            (str/starts-with? % "behavioral-subtree:")))
                              first)]
          (if (and parent-uri (not (contains? seen parent-uri)))
            (recur parent-uri
                   (subs parent-uri (count "behavioral-subtree:"))
                   (conj seen uri))
            id))))))

(defn already-harvested?
  "Fire-once guard, keyed on the STABLE class-id (independent of any name or
   parent drift): true when a :harvested behavioral-subtree already records
   this class as its source.

   RR-23: scoped by the `[:harvested-tree-class ...]` tag the mint command
   already writes on this path (commands.clj's `mint-behavioral-subtree`,
   via the shared `harvested-tree-class-tag` derivation) instead of a
   type-wide scan of every minted behavioral-subtree for the tenant — a
   non-harvested mint carries no such tag at all, so the scoped read never
   sees it. `:provenance`/`:harvested-from-tree-class` stay a defensive
   filter (tag narrows, filter decides). No legacy concern: this tag
   predates RR-23."
  [ctx class-id]
  (boolean
    (some #(and (= :harvested (:provenance %))
                (= class-id (:harvested-from-tree-class %)))
          (into [] (es/read (:event-store ctx)
                            {:types #{:ontology/behavioral-subtree-minted}
                             :tenant-id (:tenant-id ctx)
                             :tags #{(logic/harvested-tree-class-tag class-id)}})))))

(defn- domain-label-for
  "RS-5 (gap B): the domain label lives on the class's tree-class CONCEPT —
   RS-3's `mint-domain-child` stamps the judged domain label directly onto
   the child's concept at birth (RS-P2: the command path, never a
   description body write — CC-6) — never in the assembled description
   `harvest-body` transplants. Returns nil (no ctx/class-id, no concept, a
   blank label, or the generic placeholder `ensure-tree-class-concept!`
   lazy-creates for an ordinary non-domain tree-class, whose :label equals
   its own id) so an ordinary tree-class's harvest stays byte-shaped."
  [ctx class-id]
  (when (and ctx class-id)
    (let [concept (read-models/get-concept-by-uri ctx (str "tree-class:" class-id))
          label (:label concept)]
      (when (and (string? label)
                 (not (str/blank? label))
                 (not= label (str class-id)))
        label))))

(defn harvest-body
  "Assemble the harvested behavior's body by REUSING the consolidator's
   already-synthesized tree-class description (no second synthesis LLM):
   transplant :capabilities/:strengths/:weaknesses/:representative-uses/
   :avoid-when/:summary, add the worked DSL as :recommended-pattern, and
   stamp :version + :consolidated-from-event-count so anti-recency engages.
   Returns nil when the class has no consolidated description yet.

   RS-5 (gap B): the 4-arg arity additionally stamps :domain-label when the
   class is a domain child (`domain-label-for`) — the label lives on the
   CONCEPT, so the description alone can't carry it forward. The 2-arg
   arity (desc + occurrences, the propagated RR-20 contract) is UNCHANGED —
   every existing caller's body stays byte-identical."
  ([desc occurrences] (harvest-body nil desc occurrences nil))
  ([ctx desc occurrences class-id]
   (when desc
     (cond-> {:capabilities         (vec (:capabilities desc))
              :strengths            (vec (:strengths desc))
              :weaknesses           (vec (:weaknesses desc))
              :representative-uses  (vec (:representative-uses desc))
              :avoid-when           (vec (:avoid-when desc))
              :summary              (or (:summary desc) "")
              :version              1
              :consolidated-from-event-count occurrences}
       (logic/best-recommended-pattern desc)
       (assoc :recommended-pattern (logic/best-recommended-pattern desc))
       (domain-label-for ctx class-id)
       (assoc :domain-label (domain-label-for ctx class-id))))))

;; --- Rule extraction ---

(defn get-successful-episodes
  "Get successful episode events with rich context for rule extraction.

   Reads :ontology/tree-strength-recorded events and filters to those
   with context (either new context-conditions or legacy state-conditions).

   Args:
   - ctx: Context with event-store
   - tree-id: UUID of the tree to analyze
   - options:
     - :min-confidence - Minimum confidence threshold (default 0.8)
     - :limit - Max episodes to return (default 50)

   Returns vector of maps with episode context for rule extraction."
  [{:keys [event-store tenant-id] :as ctx} tree-id {:keys [min-confidence limit]
                                                    :or {min-confidence 0.8 limit 50}}]
  (let [
        strength-events (into [] (es/read event-store
                                          {:types #{:ontology/tree-strength-recorded}
                                           :tags #{[:tree tree-id]}
                                           :tenant-id tenant-id}))

        with-context (->> strength-events
                          (filter #(and (or (:scenario-context %)
                                            (:domain-type %))
                                        (or (:context-conditions %)
                                            (:state-conditions %))
                                        (>= (:confidence %) min-confidence)))
                          (take limit)
                          vec)]
    with-context))

(defn load-episodes-for-extraction
  "Code node executor: Load and format successful episodes."
  [{:keys [ctx inputs]}]
  (let [tree-id (get inputs "tree-id")
        episodes (get-successful-episodes ctx tree-id {:min-confidence 0.8 :limit 50})
        formatted (mapv logic/format-episode-for-extraction episodes)]
    {"episode-count" (count formatted)
     "episodes" formatted}))

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
  [ctx tree-id {:keys [domain-type domain-description min-episodes]
                :or {min-episodes 5
                     domain-type "unknown"
                     domain-description "General task execution"}}]
  (let [
        episodes (get-successful-episodes ctx tree-id {:min-confidence 0.8})
        episode-count (count episodes)]

    (if (< episode-count min-episodes)
      {:skipped true
       :reason "insufficient-episodes"
       :found episode-count
       :required min-episodes
       :domain-type domain-type}

      (let [
            extraction-sheet-id (logic/build-extraction-workflow! ctx)

            result (orc/execute ctx extraction-sheet-id
                                  {"tree-id" tree-id
                                   "domain-type" domain-type
                                   "domain-description" domain-description})

            rules (get-in result [:outputs "extracted-rules"] [])]

        {:extracted (count rules)
         :analyzed-episodes episode-count
         :rules rules
         :domain-type domain-type
         :tree-id tree-id}))))

(defn extract-rules-from-profiles
  "Extract rules from tree profiles projection (no direct event access needed).

   This is useful when you have the aggregated profile data but not direct
   event store access.

   Args:
   - ctx: Context for read model access
   - tree-id: Tree to analyze

   Returns rules extracted from the tree profile's strength data."
  [ctx tree-id]
  (let [profiles (rmp/project ctx :ontology/tree-profiles)
        profile (get profiles tree-id)]
    (when profile
      {:strengths (:strengths profile)
       :has-rich-context? (some #(or (contains? % :context-conditions)
                                     (contains? % :scenario-context))
                                (:strengths profile))})))

;; --- Task classifier ---

(defn get-consolidation-total*
  [ctx target-type target-id]
  (#'read-models/get-consolidation-total
   ctx target-type target-id))

(defn- gate-candidates
  "EL-1b Part 2 — EVIDENCE GATE. Filter the candidate set so a RUNTIME-EMERGENT
   :tree-class is only a retrievable candidate once it has RECURRED past the
   retrieval gate.

   The discriminator is the lifetime consolidation total on the
   instruction-aware :tree-class axis (the SAME counter the consolidator reads,
   ticked on every verdict occurrence — :ontology/tree-class-occurrence-recorded,
   RR-19 — not on classification; recurrence is counted at outcome, never at
   intent). The filter band is the half-open interval (0, retrieval-gate):

     total = 0            → NOT a runtime-accrued class. Either a curated
                            baseline SEED (recorded description, never runtime-
                            classified) or a brand-new mint about to be assigned
                            this tick. Curated baseline is trusted → PASS. (A
                            count-only >= gate filter would wrongly demote every
                            seed, which has total 0 — verified breaking baseline
                            matching in dev; this band keeps seeds reachable.)
                            CV-A item 4 (`NewbornFamilyIsMatchableNotSurfaced`)
                            narrows this: a total-0 class that IS a newborn
                            domain family (the `:newborn?-fn` seam, default:
                            agent-authored provenance) is excluded from this
                            SURFACED view too — matching stays ungated (the
                            gate runs only here, AFTER match/bundle/walk-down
                            already ran on the raw candidates), a curated seed
                            still passes at 0.
     0 < total < gate     → a runtime fresh-mint / bundle that has occurred but
                            NOT yet recurred enough — a possible one-off junk
                            class → FILTER from candidacy. It STILL accrues (the
                            counter keeps ticking); it just doesn't surface as
                            noise. PROVEN: it surfaces once it recurs to >= gate.
     total >= gate        → recurring, established → PASS.

   :tree-fingerprint (and any non-tree-class) candidates pass through untouched
   — the gate rides only the instruction-aware axis the emergence loop accrues
   on."
  [ctx candidates retrieval-gate]
  (let [newborn?-fn (or (:newborn?-fn ctx) logic/default-newborn?-fn)
        newborn? (fn [id]
                   (try (boolean (newborn?-fn ctx id))
                        (catch Throwable _ false)))]
    (filterv (fn [c]
               (if (logic/tree-class-candidate? c)
                 (if-let [id (logic/candidate-class-id c)]
                   (let [total (get-consolidation-total* ctx :tree-class id)]
                     (cond
                       (and (zero? total) (newborn? id)) false
                       (zero? total) true
                       :else (>= total retrieval-gate)))
                   false)
                 true))
             candidates)))

(defn classify-task
  "Pure classification function: given a task signature + optional
   parent-context summary + threshold, returns a tree-class
   assignment (matched corpus entry or fresh UUID mint).

   Args:
     ctx  — context with :event-store / :tenant-id / etc. (passed
            through to search-descriptions)
     opts — {:task-signature :parent-summary :threshold}
       :task-signature  — required string. Caller-built static-config
                          text (instruction + reads + writes + tools).
       :parent-summary  — optional string. Caller-resolved parent-sheet
                          consolidated :summary, or nil/absent when
                          unavailable.
       :threshold       — required double in [0.0, 1.0]. Top-1 below
                          threshold → mint fresh UUID instead of
                          matching.

   Returns:
     {:assigned-tree-id <uuid>    ; matched target or fresh mint
      :confidence       <0.0-1.0> ; top-1 fitness-score (or 0.0 when empty)
      :top-candidates   [{...}]   ; the GATED surfaced view (EL-1b Part 2)
      :ranked-candidates [{...}]  ; CC-23: the PRE-GATE ranking the decision
                                  ; ran on, <= classify-retrieval-k entries,
                                  ; each reduced to identity/axis/scores/
                                  ; rerank-source (never description content)
      :assigned-via     <kw>      ; CC-23: :match | :bundle | :walk-down |
                                  ; :mint (absent on :outcome :uncertain —
                                  ; nothing was assigned)
      :reasoning        <string>  ; top-1's reasoning, or fresh-mint note
      :was-fresh-mint?  <bool>}   ; false on match; true on mint

   Throws ex-info on invalid opts (e.g. threshold out of range)."
  [ctx opts]
  (when-let [err (m/explain logic/classify-opts-schema opts)]
    (throw (ex-info "Invalid classify-task opts"
                    {:explain err
                     :opts opts})))
  (let [{:keys [task-signature parent-summary threshold
                walk-down? specificity-threshold
                bundle-threshold retrieval-gate model]
         :or {walk-down? logic/default-walk-down?
              specificity-threshold logic/default-specificity-threshold
              bundle-threshold logic/default-bundle-threshold
              retrieval-gate logic/default-retrieval-gate}} opts
        signature (logic/build-effective-signature task-signature parent-summary)
        raw-candidates (logic/search-descriptions ctx
                         {:query signature
                          :granularity #{:tree-fingerprint :tree-class}
                          :rerank-with-intent logic/classifier-intent
                          :k logic/classify-retrieval-k
                          :model model
                          :exclude? logic/family-candidate?})
        rerank-fallback?-raw (logic/rerank-fallback?* (first raw-candidates))
        ranked-candidates (logic/reduce-ranked-candidates raw-candidates)
        candidates raw-candidates
        surfaced-candidates (gate-candidates ctx raw-candidates retrieval-gate)
        top-1 (first candidates)
        top-score (or (:fitness-score top-1) 0.0)
        matched? (and top-1 (>= top-score threshold))
        rerank-fallback? (or rerank-fallback?-raw
                             (logic/rerank-fallback?* top-1))]
    (logic/log-confidence-gate!
     (logic/maybe-assign-domain-child
      ctx
      (cond
      rerank-fallback?
      {:assigned-tree-id nil
       :confidence       top-score
       :top-candidates   (vec surfaced-candidates)
       :ranked-candidates ranked-candidates
       :reasoning        (or (:reasoning top-1)
                             "Reranker fell back to raw ColBERT; classification deferred (uncertain).")
       :outcome          :uncertain
       :parent-tree-id   nil
       :rerank-fallback? true}

      (not matched?)
      (if-let [bundle-id (logic/bundle-decision (logic/shape-candidates-only ctx candidates) bundle-threshold threshold)]
        {:assigned-tree-id bundle-id
         :confidence       top-score
         :top-candidates   (vec surfaced-candidates)
         :ranked-candidates ranked-candidates
         :assigned-via     :bundle
         :reasoning        (or (:reasoning top-1)
                               "Below the match threshold but a strong near-miss to an existing tree-class; bundling onto it (convergence) rather than scattering a fresh class.")
         :was-fresh-mint?  false
         :bundled?         true
         :outcome          :novel
         :parent-tree-id   nil
         :rerank-fallback? rerank-fallback?}
        {:assigned-tree-id (random-uuid)
         :confidence       top-score
         :top-candidates   (vec surfaced-candidates)
         :ranked-candidates ranked-candidates
         :assigned-via     :mint
         :reasoning        (or (:reasoning top-1)
                               (if (seq candidates)
                                 "Top candidate did not pass confidence threshold; minting fresh task class."
                                 "No candidates returned; minting fresh task class."))
         :was-fresh-mint?  true
         :outcome          :novel
         :parent-tree-id   nil
         :rerank-fallback? rerank-fallback?})

      (not walk-down?)
      {:assigned-tree-id (logic/coerce-to-uuid
                           (-> top-1 :document-metadata :target-id))
       :confidence       top-score
       :top-candidates   (vec surfaced-candidates)
       :ranked-candidates ranked-candidates
       :assigned-via     :match
       :reasoning        (or (:reasoning top-1) "")
       :was-fresh-mint?  false
       :outcome          :matched
       :parent-tree-id   nil
       :rerank-fallback? rerank-fallback?}

      :else
      (let [top-1-id (logic/coerce-to-uuid (-> top-1 :document-metadata :target-id))
            top-1-confident? (>= top-score specificity-threshold)]
        (if top-1-confident?
          {:assigned-tree-id top-1-id
           :confidence       top-score
           :top-candidates   (vec surfaced-candidates)
           :ranked-candidates ranked-candidates
           :assigned-via     :match
           :reasoning        (or (:reasoning top-1) "")
           :was-fresh-mint?  false
           :outcome          :matched
           :parent-tree-id   nil
           :rerank-fallback? rerank-fallback?}
          (let [walk-result (logic/walk-down-from ctx logic/classifier-intent
                                            {:target-id top-1-id
                                             :fitness-score top-score
                                             :reasoning (:reasoning top-1)}
                                            0
                                            threshold
                                            nil
                                            model)]
            (-> walk-result
                (assoc :top-candidates (vec surfaced-candidates))
                (assoc :ranked-candidates ranked-candidates)
                (assoc :assigned-via
                       (cond
                         (:was-fresh-mint? walk-result) :mint
                         (= (:assigned-tree-id walk-result) top-1-id) :match
                         :else :walk-down))
                (assoc :outcome :matched)
                (assoc :rerank-fallback? rerank-fallback?))))))
      top-1 candidates threshold signature)
     threshold)))

(defn classify-behaviors
  "R05b: pure behavioral classification. Given a task signature +
   threshold (+ optional :structural-context + :top-n), returns the
   top-N behavioral-subtree examples from the R05a Layer-2 corpus.

   The corpus is FEW-SHOT EXAMPLE MATERIAL for the recursive RLM
   researcher, NOT a routing taxonomy. classify-behaviors returns
   top-N candidates with reasoning + recommended-pattern snippets;
   the model reasons over them and decides reuse/adapt/mint.

   Args:
     ctx  — context with :event-store / :tenant-id / etc. (passed
            through to search-descriptions)
     opts — {:task-signature :threshold :structural-context :top-n}
       :task-signature      — required string. Caller-built static-config
                              text (same shape classify-task receives).
       :threshold           — required double in [0.0, 1.0]. Candidates
                              below this drop out of the result.
       :structural-context  — optional UUID. The structural tree-class
                              already assigned to this task (typically
                              the :assigned-tree-id from a prior
                              classify-task call). When provided, the
                              candidate set is narrowed via the
                              behavior:composes-into graph to behaviors
                              that compose into this shell. RETRIEVAL
                              HINT — when the narrowed set is empty
                              (no Layer-2 behaviors compose into this
                              shell yet), the result FALLS BACK to the
                              unfiltered candidate set so the model
                              still sees relevant examples.
       :top-n               — optional int. Maximum number of behavioral
                              candidates to return above threshold.
                              Defaults to 3.

   Returns:
     {:behaviors        [{:behavior-id <uuid>
                          :confidence <0.0-1.0>
                          :was-fresh-mint? <bool>
                          :reasoning <string>
                          :rerank-source <:reranker | :colbert-fallback
                                          | :timeout-fallback>}
                         ...]
      :rerank-fallback? <bool>}

   When NO candidate passes :threshold, :behaviors carries exactly one
   fresh-mint marker with :was-fresh-mint? true + a fresh UUID +
   reasoning string. Per R01 (+ RR-1): :rerank-fallback? is derived from
   top-1's :rerank-source — true for any member of
   `rerank-fallback-sources` (:colbert-fallback OR :timeout-fallback)."
  [ctx opts]
  (when-let [err (m/explain logic/classify-behaviors-opts-schema opts)]
    (throw (ex-info "Invalid classify-behaviors opts"
                    {:explain err
                     :opts opts})))
  (let [{:keys [task-signature threshold top-n structural-context model]
         :or {top-n logic/default-classify-behaviors-top-n}} opts
        candidates (logic/search-descriptions ctx
                     {:query task-signature
                      :granularity :behavioral-subtree
                      :rerank-with-intent logic/behavioral-classifier-intent
                      :k (* 2 top-n)
                      :model model})
        filtered (if structural-context
                   (let [concepts (rmp/project ctx :ontology/concepts)
                         shell-uri (str "tree-class:" structural-context)
                         composer-uris (get-in concepts [shell-uri :composed-by] #{})
                         composer? (fn [c]
                                     (contains? composer-uris
                                                (str "behavioral-subtree:"
                                                     (-> c :document-metadata :target-id))))
                         composers (filterv composer? candidates)
                         non-composers (filterv (complement composer?) candidates)]
                     (vec (distinct (concat composers non-composers))))
                   candidates)
        top-1 (first filtered)
        query-tokens (->> (str/lower-case task-signature)
                          (re-seq #"[a-z0-9][a-z0-9-]+")
                          (filter #(>= (count %) 3))
                          set)
        exact-token-candidate
        (when (>= (count query-tokens) 3)
          (some (fn [candidate]
                  (let [content-tokens (->> (or (:content candidate) "")
                                            str/lower-case
                                            (re-seq #"[a-z0-9][a-z0-9-]+")
                                            set)]
                    (when (set/subset? query-tokens content-tokens)
                      candidate)))
                filtered))
        rerank-fallback? (and (some? top-1)
                              (logic/rerank-fallback?* top-1))
        above-threshold (filter #(let [s (or (:fitness-score %) 0.0)]
                                   (>= s threshold))
                                filtered)
        kept (take top-n above-threshold)]
    (cond
      (and rerank-fallback? exact-token-candidate)
      {:behaviors
       [{:behavior-id (logic/coerce-to-uuid
                       (-> exact-token-candidate :document-metadata :target-id))
         :confidence 1.0
         :was-fresh-mint? false
         :reasoning "Exact query-token coverage in the canonical behavior summary"
         :rerank-source :exact-token-fallback}]
       :outcome :matched
       :rerank-fallback? true}

      rerank-fallback?
      {:behaviors
       (mapv (fn [c]
               {:behavior-id (logic/coerce-to-uuid
                               (-> c :document-metadata :target-id))
                :confidence (or (:fitness-score c) 0.0)
                :was-fresh-mint? false
                :reasoning (or (:reasoning c) "")
                :rerank-source (:rerank-source c)})
             (take top-n filtered))
       :outcome :uncertain
       :rerank-fallback? true}

      (seq kept)
      {:behaviors
       (mapv (fn [c]
               {:behavior-id (logic/coerce-to-uuid
                               (-> c :document-metadata :target-id))
                :confidence (or (:fitness-score c) 0.0)
                :was-fresh-mint? false
                :reasoning (or (:reasoning c) "")
                :rerank-source (:rerank-source c)})
             kept)
       :outcome :matched
       :rerank-fallback? rerank-fallback?}

      :else
      {:behaviors
       [{:behavior-id (random-uuid)
         :confidence 0.0
         :was-fresh-mint? true
         :reasoning (if (seq candidates)
                      "No candidate above threshold; minting fresh"
                      "No behavioral candidates returned; minting fresh")
         :rerank-source (:rerank-source top-1)}]
       :outcome :novel
       :rerank-fallback? rerank-fallback?})))

;; --- Evolutionary builder ---

(defn get-build-result
  "Get the result of a completed build by build-id."
  [event-store build-id]
  (let [events (into [] (es/read event-store
                                          {:types #{:evolutionary/build-completed}
                                           :tags #{[:build build-id]}}))]
    (some-> events first :body)))

(defn get-ontology-ttl
  "Get the latest TTL snapshot for an ontology."
  [event-store ontology-id]
  (let [events (into [] (es/read event-store
                                          {:types #{:evolutionary/ttl-snapshot-created}
                                           :tags #{[:ontology ontology-id]}}))]
    (some-> events last :body)))

;; --- Evolutionary ---

(defn check-source-processed
  "Check if a source has already been processed.

   Uses SHA-256 hash comparison to detect duplicates.

   Args:
     ctx - Context with :event-store
     source-uri - Path/URI to check (optional)
     content - Content to check (optional)

   Returns:
     {:processed? boolean
      :source-id string (if processed)
      :computed-hash string}

   Example:
     (check-source-processed ctx \"data.csv\" nil)
     (check-source-processed ctx nil \"inline content\")"
  [{:keys [event-store]} source-uri content]
  (let [events (es/read event-store
                                 {:types #{:evolutionary/source-registered}})
        read-models (logic/build-read-models events)]
    (logic/check-source-processed read-models source-uri content)))

(defn was-processed?
  "Simple check if content was already processed.

   Args:
     ctx - Context with :event-store
     source-uri-or-content - Path/URI or content string

   Returns: boolean"
  [{:keys [event-store]} source-uri-or-content]
  (let [events (es/read event-store
                                 {:types #{:evolutionary/source-registered}})
        read-models (logic/build-read-models events)]
    (logic/was-processed? read-models
                                    (when (and source-uri-or-content
                                               (.exists (java.io.File. source-uri-or-content)))
                                      source-uri-or-content)
                                    (when (and source-uri-or-content
                                               (not (.exists (java.io.File. source-uri-or-content))))
                                      source-uri-or-content))))

(defn- read-events
  "Read immutable evolutionary evidence within the caller's tenant. Public
  evolutionary queries must never silently widen a tenant-scoped context to
  the whole shared event store."
  ([{:keys [event-store tenant-id]} types]
   (into [] (es/read event-store
                              (cond-> {:types types}
                                tenant-id (assoc :tenant-id tenant-id)))))
  ([{:keys [event-store tenant-id]} types tags]
   (into [] (es/read event-store
                              (cond-> {:types types :tags tags}
                                tenant-id (assoc :tenant-id tenant-id))))))

(defn get-source
  "Get source entry by ID."
  [ctx source-id]
  (-> (read-events ctx #{:evolutionary/source-registered})
      logic/build-read-models
      :source-registry
      (logic/get-source source-id)))

(defn get-all-sources
  "Get all registered sources.

   Args:
     ctx - Context with :event-store
     opts:
       :source-type - Filter by type (optional)

   Returns: Vector of source entries"
  [ctx & [opts]]
  (let [registry (-> (read-events ctx #{:evolutionary/source-registered})
                     logic/build-read-models
                     :source-registry)]
    (vec (logic/all-sources registry
                                     :source-type (:source-type opts)))))

(defn evolutionary-get-concepts
  "Get concepts extracted for an ontology.

   Args:
     ctx - Context with :event-store
     ontology-id - UUID of the ontology

   Returns: Vector of concept maps"
  [ctx ontology-id]
  (->> (read-events ctx #{:evolutionary/concepts-extracted}
                    #{[:ontology ontology-id]})
       (mapcat (comp :concepts logic/body))
       vec))

(defn get-concepts-by-source
  "Get concepts extracted from a specific source."
  [ctx source-id]
  (->> (read-events ctx #{:evolutionary/concepts-extracted}
                    #{[:source source-id]})
       (mapcat (comp :concepts logic/body))
       vec))

(defn get-concepts-by-type
  "Get concepts of a specific entity type."
  [ctx entity-type]
  (->> (read-events ctx #{:evolutionary/concepts-extracted})
       (mapcat (comp :concepts logic/body))
       (filterv #(= entity-type (:entity-type %)))))

(defn get-canonical-uri
  "Get canonical URI for a given URI."
  [ctx uri]
  (get (reduce logic/canonical-uri-map-projection {}
               (read-events ctx #{:evolutionary/entities-resolved
                                  :evolutionary/canonical-uri-assigned}))
       uri))

(defn get-all-canonical-mappings
  "Get all canonical URI mappings."
  [ctx]
  (reduce logic/canonical-uri-map-projection {}
          (read-events ctx #{:evolutionary/entities-resolved
                             :evolutionary/canonical-uri-assigned})))

(defn get-evolution-state
  "Get current evolution state for an ontology."
  [ctx ontology-id]
  (some-> (read-events ctx #{:evolutionary/graph-merged}
                       #{[:ontology ontology-id]})
          last logic/body))

(defn get-build-history
  "Get build history for an ontology.

   Args:
     ctx - Context with :event-store
     ontology-id - UUID of the ontology
     opts:
       :limit - Max results (default: 10)

   Returns: Vector of build completion records"
  [ctx ontology-id & [opts]]
  (let [limit (or (:limit opts) 10)]
    (->> (read-events ctx #{:evolutionary/build-completed}
                      #{[:ontology ontology-id]})
         (take-last limit)
         (mapv logic/body))))

(defn get-statistics
  "Get comprehensive statistics about the evolutionary ontology."
  [ctx ontology-id]
  (let [history (get-build-history ctx ontology-id)
        concepts (evolutionary-get-concepts ctx ontology-id)]
    {:ontology-id ontology-id
     :build-count (count history)
     :concept-count (count concepts)
     :latest-build (last history)}))
