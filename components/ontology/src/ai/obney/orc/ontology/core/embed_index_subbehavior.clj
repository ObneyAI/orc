(ns ai.obney.orc.ontology.core.embed-index-subbehavior
  "EB7 — the EMBED+INDEX subbehavior as a delegatable ORC sheet.

   The SIXTH real subbehavior on the EB1 registry/delegation pattern (after EB2
   Survey + EB3 Model + EB4 Extract + EB5 Reconcile + EB6 Axiom/TBox). A
   subbehavior is a first-class composed ORC sheet, built via the DSL +
   `build-workflow!`, registered under a stable name → deterministic sheet-id,
   and invoked from a central evolver tree via `:delegate` (child tick, isolated
   blackboard, mapped `:reads`/`:writes`).

   ## What Embed+Index does (its ONE job)

   Turn EB3's `:embed-fields` signal + the EXTRACTED GRAPH (read via
   `:ontology-id`) into SEMANTIC RETRIEVABILITY — by DEFAULT, with NO caller
   wiring. It (1) RESOLVES the embed-worthy fields (the Model's `:embed-fields`
   when given, else the `detect-embeddable-fields-heuristic` schema scan over the
   graph), (2) EMBEDS the in-scope concepts on those fields (reusing
   `embedding/embed-concepts-batch!` to compute + the public
   `:ontology/embed-concept` command to LAND each `:ontology/concept-embedded`
   event), and (3) ColBERT-INDEXES the concepts via the `:colbert/create-index`
   COMMAND (so the index-created event LANDS and the index is RESOLVABLE for
   hybrid-search). Then it READS the embeddings + the index BACK from the
   projection (discipline 7).

   ## The P2 drift this closes — GUARANTEED-by-default embed + index

   The `deterministic_skeleton.clj` build pipeline's `:embed`/`:index` stages
   carried a SKIP DEFAULT in their docstring story — semantic retrieval silently
   does not happen unless a caller wires the embed/reindex fns. EB7 makes embed +
   ColBERT-index a FIRST-CLASS, GUARANTEED step driven by the Model's
   `:embed-fields`: a graph that flows through this subbehavior is semantically
   retrievable WITHOUT any caller wiring.

   ## A single `:code` node — Embed+Index is DETERMINISTIC orchestration

   EB3 already committed the `:embed-fields` signal (the reasoning is DONE), so
   there is no `:llm` step here: resolving the fields, batching the embeddings,
   landing the events, and dispatching the index are all DETERMINISTIC
   orchestration over proven capabilities. The body is ONE `:code` node, NO
   `:llm` node (F3 does not apply). This mirrors EB6's single-`:code`-node design.

   ## The ColBERT root-cause (the load-bearing reuse decision)

   `colbert-indexer/index-concepts!` calls the colbert INTERFACE fn
   `colbert/create-index!`, which forwards `operations/create-index!` DIRECTLY —
   it builds the PLAID index on disk but does NOT emit the `:colbert/index-created`
   event. So `colbert/load-index` / `search` cannot resolve that index (it reads
   the colbert index read-model via `get-index`) and a subsequent ColBERT
   hybrid-search fails with 'Index not found'. EB7 therefore dispatches the
   `:colbert/create-index` COMMAND (which emits the index-created event), then
   registers the per-ontology mapping (`:ontology/record-colbert-index` via
   `emit-colbert-indexed-event!`) with the SAME id the command landed. This makes
   the index actually RESOLVABLE — the difference between a green-by-completion
   index that no query can read and a real searchable one (discipline #4).

   ## Domain-agnostic (12) + no hardcoded vocab (7)

   The embed-worthy fields come from the Model (`:embed-fields`) or the
   `detect-embeddable-fields-heuristic` schema/value-shape scan — NEVER a baked-in
   field list. The embed command's text builder + schema understand only the four
   canonical concept fields (`:label :description :indicators :triggers`); a
   resolved field is intersected with that enum so an exotic field never blows the
   command schema, and the canonical pair is the fallback when the intersection is
   empty so a concept with only a label/description is still embedded. No vertical
   knowledge — it embeds + indexes ANY graph.

   ## C1 — what crosses `:delegate`

   The OUTPUT is the embed+index REPORT (`:embed-index-report`). It is produced by
   a `:code` node → it crosses `:delegate` PARSED (the C1 `:llm` JSON-string
   failure mode is node-type-specific to the AI executor's schema-coercion path,
   which a `:code` write does not traverse). The blackboard still declares a
   STRUCTURED schema for it (the EB2-EB6 defense-in-depth)."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.concept-stream :as concept-stream]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.colbert-indexer :as colbert-indexer]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [clojure.set :as set]
            [clojure.string :as str]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; The embed-index-report contract — the public OUTPUT
;; =============================================================================

(def embed-index-report-key
  "The Embed+Index subbehavior's public OUTPUT contract: a single embed+index
   REPORT map. It carries the resolved fields + their source, the embed count
   (READ BACK from the projection — discipline 7), the registered ColBERT
   index-id (+ its document count), and the honest-empty / honest-gap signals
   (`:index-skipped-reason` when there was nothing to index, the ColBERT
   corpus-minimum boundary, etc.). Produced by a `:code` node → crosses
   `:delegate` PARSED."
  :embed-index-report)

(def embed-index-report-schema
  "STRUCTURED Malli schema for the `:embed-index-report` write. A concrete
   `[:map …]` (NOT a bare `:map`/`:any`) documents the report shape and keeps the
   contract robust across `:delegate` (the EB2-EB6 defense-in-depth).
   `{:closed false}` + `:any` leaf values tolerate the rich per-entry shapes."
  [:map {:closed false}
   [:ontology-id {:optional true} :any]
   ;; field resolution (the EB3 :embed-fields signal vs the heuristic fallback)
   [:embed-fields-used {:optional true} :any]
   [:embed-fields-source {:optional true} :any]
   ;; embeddings (computed + landed + read back from the projection — discipline 7)
   [:embedded-count {:optional true} :any]
   [:embeddings-read-back-count {:optional true} :any]
   [:concepts-considered {:optional true} :any]
   ;; GC-12 — incremental embed skip accounting (honest, behavior-preserving)
   [:skipped-already-count {:optional true} :any]
   [:skipped-reference-count {:optional true} :any]
   ;; the registered, RESOLVABLE ColBERT index
   [:index-id {:optional true} :any]
   [:index-document-count {:optional true} :any]
   [:index-skipped-reason {:optional true} :any]])

;; =============================================================================
;; The embeddable-field enum the command understands (no hardcoded VOCAB —
;; this is the command's structural field set, not a domain vocabulary)
;; =============================================================================

(def embeddable-field-enum
  "The four concept fields the `:ontology/embed-concept` command's text builder +
   schema understand. A RESOLVED embed-field is intersected with this enum so an
   exotic field never blows the command schema; the canonical `#{:label
   :description}` pair is the fallback when the intersection is empty (so a
   concept with only a label/description is still embedded). This is the command's
   structural field set — NOT a domain vocabulary (#12)."
  #{:label :description :indicators :triggers})

(defn- ->field-kw
  "Coerce an embed-field reference (string/keyword/symbol) to a keyword."
  [x]
  (cond
    (keyword? x) x
    (or (string? x) (symbol? x)) (keyword (name x))
    (nil? x) nil
    :else (keyword (str x))))

;; =============================================================================
;; Field resolution — the EB3 :embed-fields signal (primary) → the heuristic
;; schema scan over the graph (fallback). NO LLM (EB3 already reasoned).
;; =============================================================================

(defn resolve-embed-fields
  "Resolve the embed-worthy fields for the ontology's concepts.

   PRIMARY: EB3's `:embed-fields` signal (the fields the Model committed) — a
   vector of field-name strings/keywords. Used verbatim when present.

   FALLBACK (no signal): `detect-embeddable-fields-heuristic` over a Malli map
   schema built from the concepts' OWN keys — a deterministic, domain-agnostic
   value-shape scan (NO LLM, NO vertical vocab).

   Returns `{:fields #{kw…} :source :model-signal|:heuristic|:canonical}`. The
   resolved fields are intersected with the command's `embeddable-field-enum`;
   the canonical `#{:label :description}` pair is the fallback when the
   intersection is empty so a label/description-only concept is still embedded."
  [embed-fields concepts]
  (let [signal-kws (->> (cond
                          (sequential? embed-fields) embed-fields
                          (some? embed-fields) [embed-fields]
                          :else [])
                        (keep ->field-kw)
                        (into #{}))]
    (if (seq signal-kws)
      ;; EB3 signal present — use it (intersected with the command enum).
      (let [hit (set/intersection signal-kws embeddable-field-enum)]
        {:fields (if (seq hit) hit #{:label :description})
         :source :model-signal})
      ;; No signal — heuristic schema scan over the concepts' own keys.
      (let [keyset (into #{} (mapcat keys) concepts)
            schema (into [:map {:closed false}]
                         (map (fn [k] [k {:optional true} :any]))
                         keyset)
            detection (ontology/detect-embeddable-fields-heuristic schema (vec concepts))
            detected (->> (:embeddable-fields detection) (keep ->field-kw) (into #{}))
            hit (set/intersection detected embeddable-field-enum)]
        (if (seq hit)
          {:fields hit :source :heuristic}
          {:fields #{:label :description} :source :canonical})))))

;; =============================================================================
;; The orchestrating embed+index — embed (compute + LAND), index (COMMAND),
;; READ BACK (discipline 7)
;; =============================================================================

(defn- scoped-concepts
  "The ontology's concepts from the URI-keyed projection, scoped to
   `ontology-id` (mirrors the deterministic-skeleton helper — same shape).

   STREAM Slice 2 scope note (field-projection DEFERRED — the two embedding reads
   in `embed+index!` are the load-bearing OOM win and are already streamed):
   converting THIS read to `concept-stream/reduce-concepts` + a `:project-fn`
   was deliberately deferred to keep this slice byte-preserving, for two reasons:
     (a) `resolve-embed-fields`'s no-signal branch scans ALL of each concept's
         keys/values through `detect-embeddable-fields-heuristic`; pre-projecting
         the concept to a pinned field set could change which fields the heuristic
         resolves — a real behavior-change risk the handoff flagged.
     (b) this helper reads the UNSCOPED URI-keyed projection then filters by
         `:ontology-id` (last-writer-wins across ontologies), whereas
         `reduce-concepts` folds the `[:ontology id]`-TAG-scoped event stream;
         the two diverge under cross-ontology URI collision (canonical-URI
         direction) — not byte-identical in the general case.
   When taken up, the `:project-fn` should keep the UNION the three consumers
   need — `:uri`, `:label`, `:description`, the embeddable text fields
   (`:indicators`/`:triggers`), `:scope`, and `(select-keys attributes
   [:linking-key])` for the spine skip — and DROP the heavy collect-mode attribute
   lists; `resolve-embed-fields` must run on FULL concepts (or the heuristic scan
   be proven projection-invariant) first."
  [ctx ontology-id]
  (filterv #(= ontology-id (:ontology-id %)) (rm/get-concepts ctx {})))

;; =============================================================================
;; GC-12 — incremental embed selection: skip already-embedded + structural
;; spine code-nodes. The bottleneck this repairs: EB7 ran per-source × per
;; CQ-iteration over the WHOLE accumulating graph, and the embed batch embeds
;; EVERY concept handed to it (its blank-text skip is the only skip). So the
;; graph was RE-EMBEDDED O(N × calls). GC-11's ~2,500 spine code-nodes (they
;; carry a label+description → pass the embed filter, yet are reached only via
;; `identified-by` graph traversal, NEVER embedding similarity) pushed embed-text
;; volume past the time budget. This selection makes embed-text volume
;; O(distinct NEW semantic concepts) — behavior-preserving (every semantic
;; concept still ends up embedded exactly once), just no redundant work.
;; =============================================================================

(defn spine-code-node?
  "True iff a LANDED concept is a GC-11b cross-source linking-key SPINE code-node
   — a structural join node reached via the `identified-by` edge, never via
   embedding similarity, so it must NOT be embedded.

   The robust, LANDING-SURVIVING marker is `:attributes` carrying a
   `:linking-key` — the stamp `linking-key-relationship-drafts`
   (`extract_subbehavior.clj`) puts on every code-node it mints, and which the
   `:ontology/create-concept` command forwards verbatim. (The draft's
   `:scope :reference` does NOT survive landing: it is not in the `ontology-scope`
   enum, so `compile-discovery-source!`'s `coerce-scope` maps it to `:custom` —
   filtering on `:scope :reference` ALONE would skip nothing. We still honour an
   explicit `:reference` scope for any draft-shaped concept that reaches embed
   pre-landing, but the attribute marker is the load-bearing one.)

   Domain-agnostic (#12): `:linking-key` is the spine's STRUCTURAL attribute key,
   not a domain field — this names no vocabulary."
  [concept]
  (boolean
   (or (= :reference (:scope concept))
       (let [attrs (:attributes concept)]
         (and (map? attrs)
              (contains? attrs :linking-key))))))

(defn select-concepts-to-embed
  "GC-12 — pure incremental embed selection. Given the in-scope `concepts` and
   the set of `already-embedded-uris` (read from the `:ontology/concept-embedded`
   projection), return only the concepts that should be embedded THIS call, plus
   the honest skip counts:

     {:to-embed [concept …]            ; NEW semantic concepts only
      :skipped-already-count int       ; URIs already carrying an embedding
      :skipped-reference-count int     ; structural spine code-nodes
      :considered-count int}

   Behavior-preserving (#4): the FINAL embedded set across calls is identical —
   every semantic concept is embedded exactly once; only redundant re-embeds and
   the never-embed spine code-nodes are dropped. A spine code-node is excluded
   FIRST (it is never embedded regardless of prior state); a remaining concept is
   skipped only if its URI is already embedded."
  [concepts already-embedded-uris]
  (let [already (set already-embedded-uris)
        {:keys [refs others]} (group-by (fn [c] (if (spine-code-node? c) :refs :others))
                                         concepts)
        skipped-reference-count (count refs)
        to-embed (filterv #(not (contains? already (:uri %))) others)
        skipped-already-count (- (count others) (count to-embed))]
    {:to-embed to-embed
     :skipped-already-count skipped-already-count
     :skipped-reference-count skipped-reference-count
     :considered-count (count concepts)}))

(defn- embed-concepts!
  "Compute the batch embeddings (REUSE `embedding/embed-concepts-batch!`, NO
   fork — note F1: this is the per-concept embed path; batching the EVENT is the
   open scale follow-up) over the in-scope concepts on the resolved fields, then
   LAND each `:ontology/concept-embedded` event via the public
   `:ontology/embed-concept` command (NO bare event-store append — discipline 7).

   HONEST EMPTY (#4): a concept whose resolved-field text is blank carries
   nothing to embed; it is skipped deterministically (using the SAME text builder
   the command uses) so no event is emitted — no fabricated vectors.

   GC-12 — INCREMENTAL: only concepts that do NOT already have an embedding (read
   from the projection) and are NOT structural spine code-nodes are embedded; the
   honest skip counts (`:skipped-already-count`, `:skipped-reference-count`) ride
   in the result. Behavior-preserving (#4): the final embedded set is identical —
   every semantic concept ends up embedded exactly once.

   Returns `{:embedded-count int :batch-embedded-count int :concepts-considered
   int :skipped-already-count int :skipped-reference-count int}`. A genuine
   command anomaly (model-load fault, concept vanished) is RAISED with the root
   cause attached (#5 — no silent fallback)."
  [ctx fields concepts already-embedded-uris]
  (let [{:keys [to-embed skipped-already-count skipped-reference-count]}
        (select-concepts-to-embed concepts already-embedded-uris)
        ;; REUSE the production batch-embed to compute (F1: per-concept event is
        ;; the known scale concern). We pass the resolved fields explicitly so the
        ;; batch does NOT re-detect (EB3 already decided the fields). GC-12: only
        ;; the NEW, non-spine concepts — never the whole accumulating graph.
        batch (embedding/embed-concepts-batch!
               to-embed {:embedding-fields fields :auto-detect? false :ctx ctx})
        ;; HONEST EMPTY decided HERE with the command's own text builder.
        embeddable (filterv #(seq (embedding/concept->embedding-text % fields))
                            to-embed)
        emitted
        (reduce
         (fn [n concept]
           (let [result (cp/process-command
                         (assoc ctx :command
                                {:command/name :ontology/embed-concept
                                 :command/id (random-uuid)
                                 :command/timestamp (time/now)
                                 :uri (:uri concept)
                                 :fields fields}))]
             (when (:cognitect.anomalies/category result)
               (throw (ex-info "embed-concepts!: embed-concept command returned anomaly"
                               {:anomaly result :uri (:uri concept)})))
             (cond-> n
               (pos? (or (get-in result [:command-result/data :dimensions]) 0))
               inc)))
         0
         embeddable)]
    {:embedded-count emitted
     :batch-embedded-count (:embedded-count batch)
     :concepts-considered (count concepts)
     :skipped-already-count skipped-already-count
     :skipped-reference-count skipped-reference-count}))

(defn- colbert-corpus-too-small?
  "True iff the throwable is ColBERT/FAISS's specific 'too few training points to
   train k-means centroids' error — the well-understood minimum-corpus boundary
   of the PLAID indexer (mirrors the deterministic-skeleton recognizer). NOT a
   catch-all — every other failure re-throws."
  [^Throwable t]
  (let [msg (str (.getMessage t) " " (some-> t .getCause .getMessage))]
    (boolean (or (re-find #"Number of training points" msg)
                 (re-find #"nx >= static_cast" msg)))))

(defn- index-concepts!
  "ColBERT-index the in-scope concepts on the resolved fields, by DEFAULT, so the
   index is RESOLVABLE for hybrid-search.

   REUSE not fork: `colbert-indexer/build-documents-from-concepts` builds the
   document content; the `:colbert/create-index` COMMAND builds the index AND
   emits the `:colbert/index-created` event (the load-bearing root-cause fix —
   `index-concepts!`/`colbert/create-index!` bypass that event, leaving the index
   unresolvable); `emit-colbert-indexed-event!` registers the per-ontology
   mapping with the SAME landed id so `get-colbert-index-for-ontology` resolves.

   HONEST EMPTY (#4): no embeddable documents → register NO phantom index,
   return `:index-skipped-reason :no-document-content`. ColBERT's training-points
   floor is recognized as a structured `:corpus-below-colbert-minimum`
   (non-fatal — the embeddings still landed). Every other error propagates.

   Returns `{:index-id uuid :index-document-count int}` or
   `{:index-skipped-reason kw}`."
  [ctx ontology-id fields concepts]
  (let [docs (colbert-indexer/build-documents-from-concepts
              concepts {:colbert-fields (vec fields)})
        valid-docs (filterv #(not (str/blank? (:content %))) docs)]
    (if (empty? valid-docs)
      {:index-skipped-reason :no-document-content}
      (let [index-name (str "ontology-" (subs (str ontology-id) 0 8))
            cmd-result
            (try
              (cp/process-command
               (assoc ctx :command
                      {:command/name :colbert/create-index
                       :command/id (random-uuid)
                       :command/timestamp (time/now)
                       :collection (mapv :content valid-docs)
                       :document-ids (mapv :document-id valid-docs)
                       :index-name index-name
                       :model-name "colbert-ir/colbertv2.0"
                       :split-documents? true
                       :max-document-length 256}))
              (catch Exception e
                (if (colbert-corpus-too-small? e)
                  ::corpus-too-small
                  (throw e))))]
        (cond
          (= cmd-result ::corpus-too-small)
          {:index-skipped-reason :corpus-below-colbert-minimum}

          ;; The `:colbert/create-index` command is unregistered — the colbert
          ;; component (an optional Layer-5 upgrade) is not on the classpath /
          ;; its commands were not loaded. Honest skip (#4/#5): the embeddings
          ;; still landed and remain retrievable via the embedding signal; the
          ;; ColBERT signal is simply unavailable. NOT a fault to mask.
          (and (map? cmd-result)
               (= ::anom/not-found (::anom/category cmd-result))
               (= "Unknown Command" (:cognitect.anomalies/message cmd-result)))
          {:index-skipped-reason :colbert-unavailable}

          ;; Any OTHER command-level anomaly is a GENUINE fault — surface the
          ;; root cause rather than leave the index silently unregistered (#5).
          (and (map? cmd-result) (::anom/category cmd-result))
          (throw (ex-info "index-concepts!: :colbert/create-index command returned anomaly"
                          {:anomaly cmd-result :ontology-id ontology-id}))

          :else
          (let [index-id (get-in cmd-result [:command/result :index-id])]
            (if (uuid? index-id)
              (let [emit-r (colbert-indexer/emit-colbert-indexed-event!
                            ctx {:ontology-id ontology-id
                                 :index-id index-id
                                 :index-name index-name
                                 :document-count (count valid-docs)
                                 :colbert-fields (vec fields)})]
                (when (and (map? emit-r) (::anom/category emit-r))
                  (throw (ex-info "index-concepts!: record-colbert-index command returned anomaly"
                                  {:anomaly emit-r :ontology-id ontology-id :index-id index-id})))
                {:index-id index-id
                 :index-document-count (count valid-docs)})
              {:index-skipped-reason :no-index-id-returned})))))))

(defn embed+index!
  "Orchestrate the full Embed+Index step. Given the granted `:ontology-id` +
   EB3's `:embed-fields` signal:

     1. RESOLVE the embed-worthy fields (the EB3 signal → the heuristic scan).
     2. EMBED the in-scope concepts on those fields (compute via
        `embed-concepts-batch!`, LAND via the `:ontology/embed-concept` command).
     3. ColBERT-INDEX them via the `:colbert/create-index` COMMAND so the index
        is RESOLVABLE, and register the per-ontology mapping.
     4. READ the embeddings + the index BACK from the projection (discipline 7).

   Returns the public embed+index report. Fails loudly without `:ontology-id`
   (no silent empty-graph default — #5)."
  [ctx {:keys [ontology-id embed-fields]}]
  (when-not ontology-id
    (throw (ex-info "embed+index! requires :ontology-id (the granted scope)"
                    {:ontology-id ontology-id})))
  (let [concepts (scoped-concepts ctx ontology-id)
        {:keys [fields source]} (resolve-embed-fields embed-fields concepts)
        ;; GC-12 — read the ALREADY-EMBEDDED set (discipline 7, the projection)
        ;; BEFORE embedding, so this call embeds only the NEW semantic concepts.
        ;; STREAM Slice 2: STREAM the embedded URIs off the `:ontology/concept-
        ;; embedded` event log and DISCARD every vector — byte-identical to
        ;; `(set (keys (get-all-concept-embeddings …)))` but never materializes the
        ;; whole per-uri vector map (the proven OOM win).
        already-embedded-uris (concept-stream/reduce-concept-embeddings
                               ctx ontology-id
                               (fn [acc uri _vec] (conj acc uri)) #{})
        embed-r (embed-concepts! ctx fields concepts already-embedded-uris)
        index-r (index-concepts! ctx ontology-id fields concepts)
        ;; DISCIPLINE 7 — read the embeddings + index BACK from the projection.
        ;; STREAM Slice 2: a STREAMED COUNT of the embedded events — byte-identical
        ;; to `(count (get-all-concept-embeddings …))` with NO vector materialized.
        read-back-count (concept-stream/reduce-concept-embeddings
                         ctx ontology-id
                         (fn [n _uri _vec] (inc n)) 0)
        registered-idx (ontology/get-colbert-index-for-ontology ctx ontology-id)]
    {:ontology-id ontology-id
     :embed-fields-used (vec (sort fields))
     :embed-fields-source source
     :embedded-count (:embedded-count embed-r)
     :embeddings-read-back-count read-back-count
     :concepts-considered (:concepts-considered embed-r)
     ;; GC-12 — honest incremental skip accounting
     :skipped-already-count (:skipped-already-count embed-r)
     :skipped-reference-count (:skipped-reference-count embed-r)
     :index-id (or (:colbert-index-id registered-idx) (:index-id index-r))
     :index-document-count (:index-document-count index-r)
     :index-skipped-reason (:index-skipped-reason index-r)}))

;; =============================================================================
;; The `:code` node wrapper + the delegatable sheet
;; =============================================================================

(defn embed-index-code
  "The `:code` `:fn`. The orc-service `:code` executor calls the `:fn` with
   `(assoc context :inputs <reads-map> :execution-context context)` — so the ctx
   (event-store, cache, registries, …) IS the top-level arg map, and the node's
   `:reads` arrive under `:inputs`. Runs the full `embed+index!` orchestration
   against that ctx and writes the public `:embed-index-report` (native Clojure —
   crosses `:delegate` parsed)."
  [{:keys [inputs] :as ctx}]
  (let [{:keys [ontology-id embed-fields]} inputs]
    {embed-index-report-key
     (embed+index!
      (dissoc ctx :inputs :execution-context)
      {:ontology-id ontology-id
       :embed-fields embed-fields})}))

(defn embed-index-subbehavior-name
  "Canonical registry name for the Embed+Index subbehavior. Like EB3-EB6 (and
   UNLIKE per-source Survey), it bakes in NO source path — it embeds + indexes the
   CURRENT graph for the `:ontology-id` it is handed on the `:embed-fields` it is
   handed (both `:reads` inputs), so a SINGLE Embed+Index sheet serves every
   source and graph. `\"<family>/<behavior>@v<N>\"` — version is part of identity
   (a new version is a new, separately-evolvable sheet)."
  []
  "ontology-embed-index/embed-index@v1")

(defn embed-index-sheet-id-for
  "Look up the deterministic sheet-id for the Embed+Index subbehavior (pure — no
   event-store read). The central tree points its `:delegate` `:target-sheet-id`
   here without rebuilding the subbehavior."
  []
  (dsl/sheet-id-for-name (embed-index-subbehavior-name)))

(defn embed-index-subbehavior-def
  "The Embed+Index subbehavior workflow definition.

   Body: a single `:code` node — the deterministic resolve→embed→index→read-back
   orchestration. NO `:llm` node (EB3 already committed `:embed-fields`).

   Contract (the public `:reads`/`:writes`):
     :reads  [:ontology-id :embed-fields]
     :writes [:embed-index-report]
   `:embed-fields` is the EB3 signal (a vector of field-name strings); it is
   tolerated as `[:maybe [:vector :string]]` so EB7 still runs (heuristic
   fallback) when omitted. The report is a `:code`-node output → it crosses
   `:delegate` PARSED; the blackboard declares a STRUCTURED schema for it
   (defense-in-depth)."
  [{:keys [_model]}]
  (let [nm (embed-index-subbehavior-name)]
    (dsl/workflow nm
      (dsl/blackboard {;; public :reads — the granted scope + EB3's embed-fields
                       :ontology-id :any
                       :embed-fields [:maybe [:vector :string]]
                       ;; public :write — the embed+index report
                       embed-index-report-key embed-index-report-schema})
      (dsl/sequence "embed-index-root"
        (dsl/code "embed-index"
          :fn "ai.obney.orc.ontology.core.embed-index-subbehavior/embed-index-code"
          :reads [:ontology-id :embed-fields]
          :writes [embed-index-report-key])))))

(defn register-embed-index-subbehavior!
  "REGISTER (build, idempotent) the Embed+Index subbehavior sheet and return its
   deterministic sheet-id. Re-registering an unchanged def is a no-op (same id).
   The central evolver tree resolves the name → id via `embed-index-sheet-id-for`
   and `:delegate`s to it."
  [ctx {:keys [model]}]
  (dsl/build-workflow! ctx (embed-index-subbehavior-def {:_model model})))
