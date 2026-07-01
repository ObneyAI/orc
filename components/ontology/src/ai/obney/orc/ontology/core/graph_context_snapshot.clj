(ns ai.obney.orc.ontology.core.graph-context-snapshot
  "GM-1 — the graph-context SNAPSHOT (deterministic, read-only, non-mutating).

   ## Why this exists (the resolved insight)

   The per-source Model `:llm` node decides entity-vs-attribute-vs-observation for
   a new source. When it models a WIDE DENORMALIZED STATS source (each row qualified
   by several dimensions and carrying many numeric measure columns), it can mis-model
   the statistics as a NEW entity per row — a cross-product explosion that fragments
   the graph. The GC-16 attempt to fix this with a deterministic post-Model checker
   FAILED: a checker cannot tell a genuine per-row measure-explosion apart from a
   legitimately multi-numeric entity, and mutating the plan regressed the clean
   sources.

   The fix (GM-1): give the Model a PREVIEW OF THE GRAPH BUILT SO FAR — the existing
   entity types, how they are keyed, the predicates in use, and a small sample — so
   it can reason LOGICALLY (\"does a `program` entity already EXIST that these
   earnings attach to? are the measures qualified by MORE THAN ONE subject
   dimension?\") instead of blind. This namespace PROVIDES that context. It NEVER
   mutates the model-spec (that was GC-16's failure) — it only measures the current
   projection state and renders a structured snapshot.

   ## What the snapshot is

   A compact, schema-focused MAP (the C1 structured shape so it crosses `:delegate`
   parsed into the Model, mirroring GC-6's `:vocabulary`):

     {:entity-types [{:type <scheme>              ;; the existing entity TYPE (URI scheme)
                      :count <n>                  ;; how many concepts of this type exist
                      :uri-keying-sample [<uri>…] ;; representative existing URIs (the keying shape)
                      :attribute-fields [<field>…]} …]  ;; attribute keys seen on this type
      :predicates [{:predicate <p> :count <n>} …] ;; the edges the graph already holds
      :content-sample [{:uri :label :type} …]     ;; a small representative ABox sample
      :concept-count <n>
      :relationship-count <n>}

   An EMPTY graph (the first source) yields an empty/degraded snapshot (no crash) —
   `{:entity-types [] :predicates [] :content-sample [] :concept-count 0
     :relationship-count 0}` — so the empty-graph first-source path still runs (the
   Model simply has nothing to attach to yet and models freely).

   ## Re-orchestration, not rewrite (#8) + domain-agnostic (#12)

   The digest + sample logic mirrors the S20 orientation-card
   (`orc-service/…/orientation_card.clj`): entity TYPES by URI scheme (its
   `uri-prefix-counts`), predicate usage counts (its `predicate-summary`), and a
   bounded degree-ranked content sample (its content-sample). We re-house that shape
   here as a STRUCTURED MAP (not the card's prose string) because the Model consumes
   it as a `:delegate`d `:reads` input. No vertical knowledge — every entity type,
   key, predicate, and sample is DERIVED from the runtime projection; NO domain
   entity-type / column / format is baked in.

   ## Read-side only (Grain discipline)

   Every fn projects existing events via the ontology read-models; none emits events,
   none mutates the model-spec. Pure over the projection state."
  (:require [ai.obney.orc.ontology.core.read-models :as rm]
            [clojure.string :as str]))

;; =============================================================================
;; The graph-context OUTPUT contract (the C1 structured schema) — mirrors GC-6's
;; `vocabulary-schema` so it crosses `:delegate` PARSED into the Model (a bare
;; `:map` write/read would arrive as a JSON string; a STRUCTURED `[:map …]` over
;; CONCRETE `[:vector [:map …]]` fields is the load-bearing `:llm`-node C1 fix).
;; Leaf values are `:any` (tolerant of model/graph variance); `{:closed false}`
;; tolerates extra keys.
;; =============================================================================

(def graph-context-key
  "The GM-1 pre-Model step's OUTPUT: the graph-context snapshot threaded into the
   per-source Model so it models new data AGAINST the graph built so far (attaching
   to existing entities, reifying multi-subject-qualified measures as Observations
   rather than minting per-row entities)."
  :graph-context)

(def graph-context-schema
  "C1 — the STRUCTURED Malli schema for the `:graph-context` write (the LOCKED GM-1
   shape, modeled on GC-6's `vocabulary-schema`). A `[:map {:closed false}]` wrapper
   around CONCRETE `[:vector [:map …]]` fields (the C1 outer-structure + per-field-
   type fixes so the value crosses `:delegate` parsed, NOT a JSON string). `:maybe`
   /`:any` leaves + `{:closed false}` tolerate the empty-graph degraded snapshot and
   any extra fields."
  [:map {:closed false}
   [:entity-types {:optional true}
    [:vector [:map {:closed false}
              [:type {:optional true} :any]
              [:count {:optional true} :any]
              [:uri-keying-sample {:optional true} [:vector :any]]
              [:attribute-fields {:optional true} [:vector :any]]]]]
   [:predicates {:optional true}
    [:vector [:map {:closed false}
              [:predicate {:optional true} :any]
              [:count {:optional true} :any]]]]
   [:content-sample {:optional true}
    [:vector [:map {:closed false}
              [:uri {:optional true} :any]
              [:label {:optional true} :any]
              [:type {:optional true} :any]]]]
   [:concept-count {:optional true} :any]
   [:relationship-count {:optional true} :any]])

;; =============================================================================
;; URI scheme = the existing entity TYPE (mirror the orientation-card's
;; uri-prefix-counts / eb12's uri-kind — the scheme before the FIRST `:` or `/`).
;; =============================================================================

(defn uri-scheme
  "The entity TYPE of a concept = the URI scheme: the substring before the FIRST
   `:` OR `/` separator (whichever comes first). `program/236753` → \"program\";
   `degree_program:22:52` → \"degree_program\". A URI with no separator → the whole
   string. Pure structural string logic — names no domain (#12)."
  [uri]
  (let [u (str uri)
        ci (str/index-of u ":")
        si (str/index-of u "/")
        i (cond (and ci si) (min ci si)
                ci ci
                si si
                :else nil)]
    (if i (subs u 0 i) u)))

;; =============================================================================
;; Read the current graph state (in-scope concepts + relationships)
;; =============================================================================

(defn- concepts-in-scope
  "The concept records for the given ontology-id (the accumulating graph)."
  [ctx ontology-id]
  (rm/get-concepts ctx {:ontology-id ontology-id}))

(defn- relationships-in-scope
  "The relationships whose `:ontology-id` matches the scope (legacy edges without
   an `:ontology-id` are included — global until backfill)."
  [ctx ontology-id]
  (filter (fn [r]
            (let [oid (:ontology-id r)]
              (or (nil? oid) (= oid ontology-id))))
          (rm/get-relationships ctx)))

;; =============================================================================
;; T-Box digest — existing entity TYPES (by URI scheme) with keying-sample +
;; attribute-fields, and predicate usage.
;; =============================================================================

(defn- entity-types-digest
  "Group the in-scope concepts by URI scheme into existing entity TYPES. For each
   type: its `:count`, a bounded `:uri-keying-sample` (representative existing URIs
   — the keying SHAPE the Model attaches to), and the `:attribute-fields` (the union
   of attribute keys seen on that type, so the Model sees which numeric/measure
   fields already ride these entities). Sorted by count descending, capped."
  [concepts {:keys [max-types uri-sample-per-type]
             :or {max-types 12 uri-sample-per-type 3}}]
  (->> concepts
       (group-by (comp uri-scheme :uri))
       (map (fn [[scheme cs]]
              {:type scheme
               :count (count cs)
               ;; SORTED sample so the snapshot is DETERMINISTIC regardless of the
               ;; projection-vals order (a map's vals order is not contractual).
               :uri-keying-sample (->> cs (map :uri) (map str) sort (take uri-sample-per-type) vec)
               :attribute-fields (->> cs
                                      (mapcat (comp keys :attributes))
                                      (remove nil?)
                                      (map name)
                                      distinct
                                      sort
                                      (take 12)
                                      vec)}))
       ;; count DESC, then type ASC as a deterministic tie-break.
       (sort-by (juxt (comp - :count) :type))
       (take max-types)
       vec))

(defn- predicate-digest
  "The predicates the graph already holds, with usage counts, sorted descending,
   capped (mirror the orientation-card's predicate-summary shape)."
  [relationships {:keys [max-predicates] :or {max-predicates 15}}]
  (->> relationships
       (map :predicate)
       (remove nil?)
       frequencies
       (map (fn [[p n]] {:predicate p :count n}))
       ;; count DESC, then predicate ASC as a deterministic tie-break.
       (sort-by (juxt (comp - :count) (comp str :predicate)))
       (take max-predicates)
       vec))

;; =============================================================================
;; Content sample — a small representative ABox sample (degree-ranked, mirroring
;; the orientation-card's content-sample), so the Model sees real existing
;; concepts it can attach observations to.
;; =============================================================================

(defn- concept-degrees
  "{uri -> total-degree} over the in-scope relationships."
  [relationships]
  (reduce (fn [m {:keys [source-uri target-uri]}]
            (-> m
                (update source-uri (fnil inc 0))
                (update target-uri (fnil inc 0))))
          {} relationships))

(defn- content-sample
  "A bounded, degree-ranked sample of existing concepts (uri + label + type). When
   the graph has no edges yet, falls back to the first N concepts (still gives the
   Model something to attach to). Names no domain (#12)."
  [concepts relationships {:keys [n-sample] :or {n-sample 10}}]
  (let [degs (concept-degrees relationships)
        by-uri (into {} (map (juxt :uri identity) concepts))
        ranked (if (seq degs)
                 ;; degree DESC, then uri ASC as a deterministic tie-break.
                 (->> degs (sort-by (fn [[uri d]] [(- d) (str uri)])) (map first) (keep by-uri))
                 ;; no edges yet — fall back to uri-sorted concepts (deterministic).
                 (sort-by (comp str :uri) concepts))]
    (->> ranked
         (take n-sample)
         (map (fn [c] {:uri (:uri c)
                       :label (:label c)
                       :type (uri-scheme (:uri c))}))
         vec)))

;; =============================================================================
;; The whole snapshot (pure, read-only, structured C1 map)
;; =============================================================================

(defn snapshot-from
  "The PURE core: compute the graph-context snapshot from a concept collection + a
   relationship collection (no Grain / no ctx). Pure + total + deterministic — an
   EMPTY graph yields an empty/degraded snapshot (no crash); the SAME collections
   yield the SAME snapshot. This is the seam the pure snapshot tests drive.

   Options (all bounded so the snapshot stays compact under scale):
     :max-types           — cap on entity types listed (default 12)
     :uri-sample-per-type — representative URIs per type (default 3)
     :max-predicates      — cap on predicates listed (default 15)
     :n-sample            — content-sample concept count (default 10)

   Returns the C1 structured map (see the ns docstring)."
  ([concepts relationships] (snapshot-from concepts relationships {}))
  ([concepts relationships opts]
   (let [concepts (vec concepts)
         relationships (vec relationships)]
     {:entity-types (entity-types-digest concepts opts)
      :predicates (predicate-digest relationships opts)
      :content-sample (content-sample concepts relationships opts)
      :concept-count (count concepts)
      :relationship-count (count relationships)})))

(defn graph-context-snapshot
  "Compute the graph-context snapshot for `ontology-id` from the current projection
   state. NON-MUTATING — reads the ontology projections only; never emits events,
   never touches the model-spec. Delegates to the pure `snapshot-from` core.

   Options are forwarded to `snapshot-from` (see there)."
  ([ctx ontology-id] (graph-context-snapshot ctx ontology-id {}))
  ([ctx ontology-id opts]
   (snapshot-from (concepts-in-scope ctx ontology-id)
                  (relationships-in-scope ctx ontology-id)
                  opts)))
