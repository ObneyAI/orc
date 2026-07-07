(ns ai.obney.orc.ontology.core.concept-stream
  "STREAM Slice 1 — the streaming-read foundation for whole-graph ontology ops.
   (Slice 3 generalizes it with `reduce-relationships` / `relationships-reducible`
   — the edge analogue of `reduce-concepts`, folding the REGISTERED
   `:ontology/relationships` reducer over the same tag-scoped windowed stream.)

   The pipeline OOMs whenever a whole-graph stage does
   `(vals (rmp/project :ontology/concepts ...))` / `get-all-concept-embeddings`
   — `rmp/project` ALWAYS realizes the full concept map, and the embedding
   projection additionally RETAINS every vector (the real OOM). This namespace
   provides the two primitives that let a whole-graph op run in bounded heap by
   reducing DIRECTLY over the streaming `es/read` event log:

     - `reduce-concepts` / `concepts-reducible` — fold the REGISTERED
       `:ontology/concepts` reducer over a tag-scoped, WINDOWED `es/read` stream,
       then reduce the caller's `rf`/`init` over the concept VALUES. State is
       byte-identical to `(rmp/project :ontology/concepts {:tags #{[:ontology id]}})`
       because it uses the SAME registered reducer, the SAME event scope
       (`concept-events` ∩ the `[:ontology id]` tag), and the SAME id-order — no
       forked notion of concept state.

       FIELD-PROJECTION (the Slice-0 refinement): an optional `:project-fn` is
       applied to each concept BEFORE the caller's `rf` sees it, so a whole-graph
       pass keeps ONLY the light fields it needs (e.g. `:uri :label :type
       :broader`) and DISCARDS heavy `:attributes` lists — so uncapped attributes
       (Slice 7) never bloat a whole-graph op. Default = full concept (back-compat).

     - `reduce-concept-embeddings` — stream `:ontology/concept-embedded` events one
       at a time (each carries ONE vector); apply `(rf acc uri vector)` per embedded
       concept. The CONTRACT is that `rf` keeps only light state and the vector is
       DISCARDED after `rf` returns — heap is O(one vector) transiently, never a
       per-uri vector map. Deliberately does NOT reuse the `concept-embeddings*`
       reducer (whose whole job is to RETAIN the vector — the OOM being eliminated).

   Reduce-over-`es/read`, NOT partition-the-read-model: partitioning only shrinks
   WHICH events fold; `rmp/project` still realizes the full map. Tag-scoped
   `project` stays correct for the small single-entity reads it already serves."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Config
;; =============================================================================

(def ^:const default-window
  "Events per `es/read` page (the pager's `:limit`). Bounds the per-page SQL
   transaction / JDBC result set; the fold accumulator threads across pages."
  5000)

;; =============================================================================
;; Registry access — the REAL registered concept reducer + event types
;; (discipline #8: reuse the registered read model, do NOT fork a second notion
;;  of concept state)
;; =============================================================================

(defn- concepts-registry-entry []
  (:ontology/concepts (rmp/global-read-model-registry)))

(defn- concept-reducer-fn
  "The registered `:ontology/concepts` reducer — the SAME `concepts*` multimethod
   `rmp/project` folds. (fn [state event] -> state')."
  []
  (:reducer-fn (concepts-registry-entry)))

(defn- concept-event-types
  "The registered `:ontology/concepts` event-type set (= read-models/concept-events)."
  []
  (:events (concepts-registry-entry)))

;; =============================================================================
;; Windowed pager over es/read (copies the apply-aggregation-transform! cursor
;; loop: page by :limit, advance :after past the last event id, stop on a short
;; window). `es/read` already streams one row at a time; the windowing bounds the
;; per-page SQL transaction. Reused for both the concepts and embeddings folds.
;; =============================================================================

(defn- reduce-scope
  "Fold `event-rf` over EVERY event matching (`:types` ∩ optional `:tags`) for the
   ctx's tenant, paging `es/read` in `window`-sized pages. `:tags` is omitted from
   the query when nil (embeddings are NOT ontology-tagged in production). Returns
   {:state acc :events n}."
  [ctx {:keys [tags types window]} event-rf init]
  (let [window (or window default-window)
        store  (:event-store ctx)
        tenant (:tenant-id ctx)]
    (loop [after nil, acc init, total 0]
      (let [q (cond-> {:tenant-id tenant :types types :limit window}
                tags  (assoc :tags tags)
                after (assoc :after after))
            page (reduce (fn [m ev]
                           (-> m
                               (update :acc event-rf ev)
                               (assoc :last (:event/id ev))
                               (update :cnt inc)))
                         {:acc acc :last after :cnt 0}
                         (es/read store q))
            {acc' :acc last-id :last cnt :cnt} page]
        (if (< cnt window)
          {:state acc' :events (+ total cnt)}
          (recur last-id acc' (+ total cnt)))))))

(defn- fold-concept-state
  "Build the concept-state map {uri -> concept} for `ontology-id` by folding the
   REGISTERED concepts reducer over the tag-scoped, windowed es/read stream. Same
   reducer + same scope + same id-order as `(rmp/project :ontology/concepts
   {:tags #{[:ontology id]}})` ⇒ byte-identical state."
  [ctx ontology-id window]
  (:state (reduce-scope ctx
                        {:tags #{[:ontology ontology-id]}
                         :types (concept-event-types)
                         :window window}
                        (concept-reducer-fn)
                        {})))

;; =============================================================================
;; Public primitive — concepts
;; =============================================================================

(defn concepts-reducible
  "An `IReduceInit` view over the concept VALUES for `ontology-id`. Reducing it
   applies `(rf acc concept)` over each concept value, composing a consumer's fold
   in ONE pass (no second full copy of the value collection).

   Options:
     :project-fn — (fn [concept] -> projected) applied to each concept BEFORE `rf`
                   sees it, so the caller accumulates ONLY the light fields and the
                   heavy `:attributes` list is DISCARDED pre-accumulation. Default:
                   identity (full concept, back-compat).
     :window     — events per es/read page (default `default-window`)."
  ([ctx ontology-id] (concepts-reducible ctx ontology-id nil))
  ([ctx ontology-id {:keys [project-fn window]}]
   (let [xf (or project-fn identity)]
     (reify clojure.lang.IReduceInit
       (reduce [_ rf init]
         (let [state (fold-concept-state ctx ontology-id window)]
           (reduce (fn [acc concept] (rf acc (xf concept))) init (vals state))))))))

(defn reduce-concepts
  "Eagerly fold `rf`/`init` over the concept VALUES for `ontology-id`, streaming
   the registered `:ontology/concepts` reducer over a windowed es/read pass. The
   reduced value the caller asked for is returned (e.g. `conj []` -> a vector of
   concepts). See `concepts-reducible` for the `:project-fn` field-projection.

   `(reduce-concepts ctx ontology-id rf init)` — default = full concept.
   `(reduce-concepts ctx ontology-id rf init {:project-fn f :window n})`."
  ([ctx ontology-id rf init] (reduce-concepts ctx ontology-id rf init nil))
  ([ctx ontology-id rf init opts]
   (reduce rf init (concepts-reducible ctx ontology-id opts))))

;; =============================================================================
;; Registry access + fold — relationships (STREAM Slice 3)
;; (discipline #8: reuse the REGISTERED :ontology/relationships reducer — the
;;  SAME `relationships*` multimethod `rmp/project` folds — do NOT fork a second
;;  notion of edge state)
;; =============================================================================

(defn- relationships-registry-entry []
  (:ontology/relationships (rmp/global-read-model-registry)))

(defn- relationship-reducer-fn
  "The registered `:ontology/relationships` reducer (fn [state event] -> state')."
  []
  (:reducer-fn (relationships-registry-entry)))

(defn- relationship-event-types
  "The registered `:ontology/relationships` event-type set
   (= read-models/relationship-events)."
  []
  (:events (relationships-registry-entry)))

(defn- fold-relationship-state
  "Build the relationship-state map {relationship-id -> relationship} for
   `ontology-id` by folding the REGISTERED relationships reducer over the
   tag-scoped, windowed es/read stream. Same reducer + same scope + same order
   as `(rmp/project :ontology/relationships {:tags #{[:ontology id]}})` ⇒
   byte-identical state.

   Scope note: `:ontology/relationship-created` events ARE `[:ontology id]`-tagged
   whenever the command was given an `:ontology-id` (S06 — see
   `commands/create-relationship`: `#{[:relationship rid]}` plus
   `[:ontology id]` when supplied). So the `[:ontology id]` tag scope selects
   exactly the edges whose value carries `:ontology-id id` — no body filter
   needed (unlike embeddings, which are NOT ontology-tagged)."
  [ctx ontology-id window]
  (:state (reduce-scope ctx
                        {:tags #{[:ontology ontology-id]}
                         :types (relationship-event-types)
                         :window window}
                        (relationship-reducer-fn)
                        {})))

;; =============================================================================
;; Public primitive — relationships
;; =============================================================================

(defn relationships-reducible
  "An `IReduceInit` view over the relationship VALUES for `ontology-id`.
   Reducing it applies `(rf acc relationship)` over each edge value in ONE pass.
   Analogous to `concepts-reducible`.

   Options:
     :project-fn — (fn [relationship] -> projected) applied to each edge BEFORE
                   `rf` sees it, so a whole-graph pass keeps ONLY the endpoint
                   fields it needs (e.g. `:source-uri :target-uri :predicate`)
                   and DISCARDS heavy edge metadata (`:evidence` / `:properties`).
                   Default: identity (full edge, back-compat).
     :window     — events per es/read page (default `default-window`)."
  ([ctx ontology-id] (relationships-reducible ctx ontology-id nil))
  ([ctx ontology-id {:keys [project-fn window]}]
   (let [xf (or project-fn identity)]
     (reify clojure.lang.IReduceInit
       (reduce [_ rf init]
         (let [state (fold-relationship-state ctx ontology-id window)]
           (reduce (fn [acc rel] (rf acc (xf rel))) init (vals state))))))))

(defn reduce-relationships
  "Eagerly fold `rf`/`init` over the relationship VALUES for `ontology-id`,
   streaming the registered `:ontology/relationships` reducer over a windowed
   es/read pass. Returns the reduced value (e.g. `conj []` -> a vector of edges).
   See `relationships-reducible` for the `:project-fn` field-projection.

   `(reduce-relationships ctx ontology-id rf init)` — default = full edge.
   `(reduce-relationships ctx ontology-id rf init {:project-fn f :window n})`."
  ([ctx ontology-id rf init] (reduce-relationships ctx ontology-id rf init nil))
  ([ctx ontology-id rf init opts]
   (reduce rf init (relationships-reducible ctx ontology-id opts))))

;; =============================================================================
;; Public primitive — concept embeddings (vector-discarding stream)
;; =============================================================================

(defn reduce-concept-embeddings
  "Stream `:ontology/concept-embedded` events for `ontology-id` ONE AT A TIME
   (each event carries exactly ONE vector) and apply `(rf acc uri vector)` per
   embedded concept, returning the reduced accumulator.

   CONTRACT: `rf` keeps only LIGHT state (a uri flag/count) — the vector is
   DISCARDED after `rf` returns. NEVER accumulate a per-uri vector map; that is the
   OOM this primitive exists to eliminate. Deliberately does NOT reuse the
   `concept-embeddings*` reducer (its job is to RETAIN the vector).

   Scope note: production `:ontology/concept-embedded` events are tagged only
   `[:concept id]` (NOT `[:ontology id]`) — they carry `:ontology-id` in the body.
   So the stream is type-scoped and each event is filtered by its body's
   `:ontology-id`, exactly mirroring `get-all-concept-embeddings {:ontology-id id}`."
  ([ctx ontology-id rf init] (reduce-concept-embeddings ctx ontology-id rf init nil))
  ([ctx ontology-id rf init {:keys [window]}]
   (:state
    (reduce-scope ctx
                  {:types #{:ontology/concept-embedded} :window window}
                  (fn [acc ev]
                    (if (= ontology-id (:ontology-id ev))
                      (rf acc (:uri ev) (:embedding ev))
                      acc))
                  init))))

(defn any-concept-embedding?
  "True iff the store holds ≥1 `:ontology/concept-embedded` event (ANY ontology).
   Reads at most ONE event (`es/read :limit 1`, early-exit via `reduced`) — the
   BOUNDED, byte-invariant replacement for `(seq (get-all-concept-embeddings ctx))`,
   which folds EVERY embedding event (RETAINING every vector) just to answer the
   existence boolean. Scope-agnostic on purpose: mirrors the unscoped
   `(seq (get-all-concept-embeddings ctx))` it replaces."
  [ctx]
  (boolean
   (reduce (fn [_ _ev] (reduced true))
           false
           (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :types #{:ontology/concept-embedded}
                     :limit 1}))))
