(ns ai.obney.orc.ontology.core.container-aggregate
  "MT-3 — the deterministic AGGREGATING transform for a `:long-form` container.

   A `:long-form` container (MT-1 shape tag) carries MANY rows per entity — one row
   per entity×element measurement (O*NET `Skills`: one row per occupation×skill×scale).
   The right extraction is NOT one concept per raw row (that fragments the entity into
   ~91 measurement rows); it is ONE concept per ENTITY KEY carrying its TOP-N elements
   (ranked by a value column) as a FLAT array attribute (`topSkills: [...]`).

   The mechanism (Option B, /prototype-proven on the real 62,580-row O*NET `Skills`):

     1. The `:llm` AUTHOR emits a declarative ROLLUP SPEC (a small DATA map) — WHICH
        column is the entity key / the element label / the rank VALUE, N, the flat
        attribute name, the entity-type, AND (critically) the SCALE FILTER. It is
        DATA, not a fn-string, so it needs no SCI eval.

     2. `stream-aggregate` (PURE + TOTAL, here) folds the spec over the lazy row
        stream keeping ONLY per-key top-N — bounded memory = distinct-keys × N. It
        never materializes the whole container.

   The SCALE FILTER (`:filter-col`/`:filter-val`) is REQUIRED, not optional: an O*NET
   measurement long-form carries the SAME entity×element TWICE — once per scale (`Scale
   ID` = `IM` Importance vs `LV` Level) with different values. A top-N WITHOUT the
   filter MIXES scales → garbage. The AUTHOR discovers the filter column + value from
   the sample (role hints are ADVISORY — the AUTHOR verifies the value column against
   the real rows, which is how MT-3 avoids MT-1's Task-ID-as-measure bug).

   Domain/format-agnostic (#12): this ns names NO O*NET/CIP/SOC column or scale value —
   every column and the filter come from the AUTHOR's runtime spec."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Numeric coercion — the rank VALUE column arrives as STRINGS in the source
;; ("4.12"). number? passthrough / numeric-string parse / else nil (skip).
;; ---------------------------------------------------------------------------

(defn coerce-num
  "Coerce a rank-value cell to a number: a real number passes through; a numeric
   STRING (`\"4.12\"`, `\"-3\"`, with surrounding whitespace) parses; anything else
   (a label, a blank, nil, an id like `\"19-1011\"`) yields nil — the caller SKIPS
   and COUNTS it (honest, #4/#5 — never fabricated). Pure + total."
  [v]
  (cond
    (number? v) v
    (and (string? v) (re-matches #"\s*-?\d+(?:\.\d+)?\s*" v)) (Double/parseDouble (str/trim v))
    :else nil))

;; ---------------------------------------------------------------------------
;; MT-6 — COLLECT (list) mode. A repeating-key ATTRIBUTE table (O*NET `Alternate
;; Titles`: occupation → its other job titles, `Task Statements`: occupation → its
;; tasks) carries a REPEATING entity key + a DESCRIPTIVE element column but NO
;; numeric measure to rank by. There is nothing to top-N — the right rollup is ONE
;; record per key carrying its element values as a FLAT LIST. Collect mode fires
;; when the spec has NO `:value-col` (blank/absent). The list is ALWAYS DISTINCT
;; (dedup is lossless); it is UNBOUNDED by default (keep ALL distinct values — the
;; STREAM Slice 7 payoff: the projections field-project the heavy lists away, so the
;; accumulator is the irreducible "keep all distinct values" working set the user
;; wants retained) and only capped when a caller OPTS IN via `:max-list-size`.
;; ---------------------------------------------------------------------------

(defn list-cap
  "STREAM Slice 7 — the OPT-IN collect-mode (list) cap. Returns a POSITIVE INT when
   the `spec` carries a positive-int (or positive numeric-string) `:max-list-size`,
   else NIL = UNBOUNDED (keep ALL distinct element values per key, deduped). The old
   hard 25-cap (which silently DROPPED the 26th+ distinct value — the drop the user
   flagged) is now this opt-in field; the default keeps everything. A cap that is set
   and FIRES is surfaced via `:list-truncated?` (never a silent drop). Pure + total."
  [spec]
  (let [c (:max-list-size spec)]
    (cond
      (and (integer? c) (pos? c)) (long c)
      (and (string? c) (re-matches #"\s*\d+\s*" (str c)) (pos? (Long/parseLong (str/trim c))))
      (Long/parseLong (str/trim c))
      :else nil)))

(defn collect-mode?
  "True iff `spec` is a COLLECT (list) spec — it has NO `:value-col` (blank/absent),
   so there is no numeric column to RANK by. Collect mode gathers the element values
   into a per-key flat list (DISTINCT; UNBOUNDED by default, capped only via an opt-in
   `:max-list-size`) instead of a top-N-by-value. A spec WITH a non-blank `:value-col`
   keeps the top-N path (also UNBOUNDED by default, capped only via an opt-in `:n`).
   Pure + total."
  [spec]
  (not (seq (str/trim (str (:value-col spec))))))

;; ---------------------------------------------------------------------------
;; The deterministic executor — bounded streaming group-by-key → top-N-by-value.
;; Exposed as init / step / finalize so the APPLY path can drive the fold CHUNK-BY-
;; CHUNK over a paged stream (never materializing a huge container — the bound is the
;; ACCUMULATOR `distinct-keys × N`, not the row count). `stream-aggregate` is the
;; whole-seq convenience wrapper over the same three.
;; ---------------------------------------------------------------------------

(defn aggregate-init
  "The empty fold state (accumulator + honest counters). `:list-truncated?` /
   `:topn-truncated?` start false and flip true only when an OPT-IN cap actually
   DROPS a value (STREAM Slice 7 — a fired cap is surfaced, never silent)."
  []
  {:acc {} :rows-seen 0 :rows-kept 0 :rows-errored 0 :rows-filtered 0
   :list-truncated? false :topn-truncated? false})

(defn aggregate-step
  "Fold ONE row into the aggregate `state` under `spec`. Off-scale rows (the scale
   filter) increment `:rows-filtered`. Then, gated on the spec's MODE:

   TOP-N mode (spec HAS a `:value-col`): in-scope rows with key+element+numeric value
   contribute to the per-key ranked pairs. By default (NO `:n`) ALL ranked pairs are
   KEPT (STREAM Slice 7 — nothing dropped; finalize sorts by value desc). An OPT-IN
   `:n` caps to the per-key top-N (pruned on every insert, bounded at N) and sets
   `:topn-truncated?` when it actually drops a pair. In-scope rows missing key/element
   or with a non-numeric value increment `:rows-errored` (honest skip, never fabricated).

   COLLECT mode (MT-6, spec has NO `:value-col`): in-scope rows with key+element
   collect the ELEMENT value into the per-key flat list — ALWAYS DISTINCT (dedup is
   lossless), UNBOUNDED by default (keep ALL distinct values — the Slice 7 payoff). An
   OPT-IN `:max-list-size` caps the list and sets `:list-truncated?` when a new distinct
   value is DROPPED because the cap is reached (never silent). No numeric value is
   required. Rows missing key/element increment `:rows-errored`. A duplicate (or an
   over-an-opt-in-cap) element is still a valid in-scope row (`:rows-kept`), just
   deduped/dropped — never an error.

   Pure + total."
  [spec state row]
  (let [{:keys [key-col element-col n filter-col filter-val]} spec
        collect? (collect-mode? spec)
        cap (list-cap spec)                    ; OPT-IN collect cap (nil = UNBOUNDED)
        {:keys [acc rows-seen rows-kept rows-errored rows-filtered
                list-truncated? topn-truncated?]} state
        rows-seen (inc rows-seen)
        scale-ok? (or (nil? filter-col)
                      (= (str (get row filter-col)) (str filter-val)))]
    (if-not scale-ok?
      (assoc state :rows-seen rows-seen :rows-filtered (inc rows-filtered))
      (let [k (get row key-col)
            e (get row element-col)]
        (if collect?
          ;; MT-6 COLLECT (list) mode — DISTINCT always; UNBOUNDED unless :max-list-size.
          (if (and (some? k) (some? e))
            (let [cur (or (get acc k) [])
                  present? (boolean (some #(= % e) cur))
                  at-cap? (boolean (and cap (>= (count cur) cap)))
                  ;; a NEW distinct value blocked by the opt-in cap = a real truncation
                  dropped? (and at-cap? (not present?))]
              (assoc state
                     :acc (if (or present? at-cap?) acc (assoc acc k (conj cur e)))
                     :rows-seen rows-seen
                     :rows-kept (inc rows-kept)
                     :list-truncated? (or list-truncated? dropped?)))
            (assoc state :rows-seen rows-seen :rows-errored (inc rows-errored)))
          ;; TOP-N-by-value mode — keep ALL ranked pairs by default; cap only via :n.
          (let [v (coerce-num (get row (:value-col spec)))]
            (if (and (some? k) (some? e) (some? v))
              (let [cur (or (get acc k) [])
                    ;; with an opt-in :n at capacity, adding one pair evicts one = truncation
                    dropped? (boolean (and n (>= (count cur) n)))
                    pairs (conj cur {:element e :value v})
                    ;; :n present → prune to sorted top-N per insert (bounded); else keep all
                    pairs (if n (->> pairs (sort-by :value >) (take n) vec) pairs)]
                (assoc state
                       :acc (assoc acc k pairs)
                       :rows-seen rows-seen
                       :rows-kept (inc rows-kept)
                       :topn-truncated? (or topn-truncated? dropped?)))
              (assoc state :rows-seen rows-seen :rows-errored (inc rows-errored)))))))))

;; ---------------------------------------------------------------------------
;; CONNECT-3a — the ASSOCIATION mode. A junction/associative table (SOC + Element
;; + rating: O*NET `Skills`/`Knowledge`/`Abilities`) is NOT an entity to enrich with
;; an attribute LIST — it is a many-to-many RELATION. Aggregating it onto the key as
;; an attribute list yields 0 edges / 0 element nodes → a BFS-DEAD graph (the
;; observation build's finding). The right extraction MINTS the element as a SHARED
;; canonical node (deduped — one skill node across all occupations, embeddable) + a
;; key→element EDGE per (key,element) pair carrying the rating.
;;
;; It REUSES the SAME group-by fold (`aggregate-step`) — no fork. The ONLY difference
;; is `aggregate-finalize`: instead of ONE attribute draft per key, `associate-finalize`
;; emits DISTINCT element concept-drafts + one relationship-draft per (key,element).
;; Signalled by the spec carrying `:predicate` + `:element-entity-type` (in place of
;; `:attr-name`). Absent → today's collect/top-N attribute behavior is byte-identical
;; (association is strictly opt-in). Domain-agnostic (#12): the predicate + both
;; entity-types come from the runtime spec, names no O*NET/SOC column.
;; ---------------------------------------------------------------------------

(defn association-mode?
  "True iff `spec` is an ASSOCIATION spec — it carries a non-blank `:predicate` AND
   `:element-entity-type` (in place of `:attr-name`). CONNECT-3a: the finalize then
   emits SHARED element concept-drafts (deduped, canonical) + one key→element
   relationship-draft per (key,element) pair (the value-col rating rides the edge)
   INSTEAD of one per-key attribute draft. Absent → today's collect/top-N attribute
   behavior is byte-identical (association is opt-in). Pure + total."
  [spec]
  (boolean (and (map? spec)
                (seq (str/trim (str (:predicate spec))))
                (seq (str/trim (str (:element-entity-type spec)))))))

(defn- entry->element+value
  "Normalize ONE accumulator entry to `[element value]`. Top-N-mode entries are
   `{:element :value}` maps (the value-col rating preserved); collect-mode entries are
   the bare element value (no rating). So association works over EITHER fold mode —
   with a value-col the rating rides the edge, without one the edge carries no rating."
  [entry]
  (if (map? entry)
    [(:element entry) (:value entry)]
    [entry nil]))

(defn associate-finalize
  "CONNECT-3a — the ASSOCIATION finalize (sibling of the attribute finalize; driven by
   the SAME `aggregate-step` accumulator `{key -> [entries]}`). Emits, from the folded
   `state`:

   1. `:concept-drafts` — DISTINCT element concept-drafts across ALL keys (canonical
      URI `<element-entity-type>/<element>` keyed on the element VALUE, so the SAME
      element from many keys is ONE node — dedup is the anti-over-mint invariant). Each
      carries the element value in `:attributes` under the element-col name so GC-1
      canonicalize / MC-6 can recover its identity. Order = first-appearance (#10).
   2. `:relationship-drafts` — ONE edge per (key,element) pair:
      `{:source-uri <key-entity>/<key> :target-uri <element-entity>/<element>
        :predicate <predicate> :properties {<:value-col-as-keyword> <value>}}` — the
      rating rides the edge in KEYWORD-KEYED `:properties` (CONNECT-3b: this is the
      field `relationship-draft->command` forwards + the relationship-created
      `:properties [:map-of :keyword :any]` schema accepts, so the rating SURVIVES
      into the landed edge — a string-keyed `:attributes` map was dropped by the
      compile path, CONNECT-3a's noted gap). Omitted when the fold carried no value.

   Plus the SAME honest counts + boundedness witnesses the attribute finalize returns.
   Pure + total."
  [spec state]
  (let [{:keys [element-entity-type key-entity-type value-col element-col]} spec
        predicate (str (:predicate spec))
        elem-et (str/trim (str element-entity-type))
        ;; CONNECT-3c — the SOURCE (key) node scheme. Prefer an explicit
        ;; `:key-entity-type`, but FALL BACK to the model's `:entity-type` (the
        ;; field the AUTHOR actually emits for the key entity — it authors
        ;; `:entity-type "occupation"`, NOT `:key-entity-type`). Without this the
        ;; edges keyed off the "entity" default → `entity/<key>` STUBS, fragmented
        ;; from the canonical `occupation/<key>` profiles (0 traversal from the rich
        ;; occupation node). Falling back to `:entity-type` attaches the edges to the
        ;; canonical occupation nodes (same `<entity-type>/<key>` scheme the attribute
        ;; finalize + per-row mint use). "entity" only as the last-resort default.
        key-et (let [k (str/trim (str key-entity-type))
                     k (if (seq k) k (str/trim (str (:entity-type spec))))]
                 (if (seq k) k "entity"))
        vcol (when (seq (str/trim (str value-col))) value-col)
        ecol (when (seq (str/trim (str element-col))) element-col)
        acc (:acc state)
        elem-uri (fn [e] (str elem-et "/" e))
        key-uri (fn [k] (str key-et "/" k))
        ;; DISTINCT element values across ALL keys (first-appearance order, #10) → one
        ;; SHARED concept-draft each (canonical dedup — Active Listening = ONE node).
        distinct-elems (->> acc
                            vals
                            (mapcat identity)
                            (map (comp first entry->element+value))
                            (remove nil?)
                            distinct
                            vec)
        concept-drafts (mapv (fn [e]
                               {:uri (elem-uri e)
                                :label (str e)
                                :entity-type elem-et
                                :attributes (if ecol {ecol e} {})})
                             distinct-elems)
        ;; ONE key→element edge per (key,element) entry — the rating rides the edge.
        relationship-drafts (vec
                             (for [[k entries] acc
                                   entry entries
                                   :let [[e v] (entry->element+value entry)]
                                   :when (and (some? k) (some? e))]
                               (cond-> {:source-uri (key-uri k)
                                        :target-uri (elem-uri e)
                                        :predicate predicate}
                                 ;; CONNECT-3b — the rating rides KEYWORD-KEYED
                                 ;; :properties (the value-col NAME becomes the
                                 ;; property key) so relationship-draft->command
                                 ;; forwards it into the landed edge (a string-keyed
                                 ;; :attributes map is dropped by the compile path).
                                 (and (some? v) vcol) (assoc :properties {(keyword vcol) v}))))]
    {:concept-drafts concept-drafts
     :relationship-drafts relationship-drafts
     :distinct-keys (count acc)
     :distinct-elements (count distinct-elems)
     :peak-acc-entries (reduce + 0 (map (comp count val) acc))
     :list-truncated? (boolean (:list-truncated? state))
     :topn-truncated? (boolean (:topn-truncated? state))
     :rows-seen (:rows-seen state)
     :rows-kept (:rows-kept state)
     :rows-errored (:rows-errored state)
     :rows-filtered (:rows-filtered state)}))

(defn aggregate-finalize
  "Produce the per-key concept-drafts + honest counts from a folded `state`. ONE
   draft per key: `{:uri :label :entity-type :attributes {attr-name [labels] key-col
   <key value>}}`. TOP-N entries are sorted by value DESC HERE (so the default no-`:n`
   path keeps ALL ranked pairs in deterministic order, #10; the opt-in `:n` path was
   already pruned+sorted per insert — the re-sort is idempotent). `:peak-acc-entries`
   is the honest boundedness witness — the REAL accumulator size (unbounded by default,
   possibly >25), NOT a capped figure. `:list-truncated?` / `:topn-truncated?` surface
   whether an OPT-IN cap actually FIRED (STREAM Slice 7 — a fired cap is never silent).

   CONNECT-3a: when `spec` is an ASSOCIATION spec (`association-mode?` — it carries a
   `:predicate` + `:element-entity-type`), this DELEGATES to `associate-finalize`,
   which emits SHARED element concept-drafts + key→element relationship-drafts (a
   traversable graph) INSTEAD of one per-key attribute draft. The SAME `aggregate-step`
   accumulator feeds both — no fork. A non-association spec is byte-identical."
  [spec state]
  (if (association-mode? spec)
    (associate-finalize spec state)
    (let [{:keys [attr-name entity-type key-col]} spec
        attr-name (or attr-name :top)
        et (or entity-type "entity")
        collect? (collect-mode? spec)
        acc (:acc state)
        drafts (mapv (fn [[k entries]]
                       {:uri (str et "/" k)
                        :label (str k)
                        :entity-type et
                        ;; COLLECT mode entries are the element values themselves (a
                        ;; flat list, dedup-preserving insertion order); TOP-N entries
                        ;; are {:element :value} pairs sorted by value DESC here.
                        :attributes (merge {attr-name (if collect?
                                                        (vec entries)
                                                        (mapv :element (sort-by :value > entries)))}
                                           (when key-col {key-col k}))})
                     acc)]
    {:concept-drafts drafts
     :distinct-keys (count acc)
     :peak-acc-entries (reduce + 0 (map (comp count val) acc))
     :list-truncated? (boolean (:list-truncated? state))
     :topn-truncated? (boolean (:topn-truncated? state))
     :rows-seen (:rows-seen state)
     :rows-kept (:rows-kept state)
     :rows-errored (:rows-errored state)
     :rows-filtered (:rows-filtered state)})))

(defn stream-aggregate
  "Bounded streaming group-by-key → top-N-by-value rollup. `spec` is the model-
   authored ROLLUP SPEC:

     {:key-col     <col>  ; the ENTITY key to group by
      :element-col <col>  ; the element LABEL to collect
      :value-col   <col>  ; the numeric column to RANK by (top-N mode; omit → collect)
      :n           <int>  ; OPT-IN top-N cap; absent → keep ALL ranked pairs (Slice 7)
      :max-list-size <int>; OPT-IN collect-list cap; absent → keep ALL distinct values
      :attr-name   <k>    ; the flat array attribute name (e.g. :topSkills)
      :entity-type <str>  ; the concept's :entity-type (for URI + GC-1 canonicalize)
      :filter-col  <col>  ; OPTIONAL — the SCALE column to filter on
      :filter-val  <val>} ; OPTIONAL — the scale value to KEEP (rows off-scale skipped)

   Folds `rows` (a lazy seq of keyed row-maps — dual-keyed by the caller so the
   spec's column names resolve whatever the source's key TYPE) maintaining, per key,
   only the TOP-N {:element :value} pairs. Bounded memory = distinct-keys × N — a
   62k-row container never materializes whole. Emits ONE concept-draft per key:

     {:uri (str entity-type \"/\" key) :label (str key) :entity-type <et>
      :attributes {attr-name [<element> …top-N by value…] key-col <key value>}}

   The key value is carried in :attributes under the key column name so GC-1 canonical
   URI minting + GC-11 linking can recover the entity's identity value.

   Honest counts (#4/#5):
     :rows-seen     every row folded
     :rows-filtered rows excluded by the scale filter (off-scale — legitimately not
                    this measurement; NOT an error)
     :rows-kept     in-scope rows with a key + element + numeric value (contributed)
     :rows-errored  in-scope rows MISSING key/element or with a non-numeric value —
                    SKIPPED + COUNTED, never fabricated
     :distinct-keys entities that produced a draft
     :peak-acc-entries  the FINAL accumulator size — the HONEST witness of the real
                        per-key list sizes (UNBOUNDED by default, possibly >25; only
                        ≤ keys × N / keys × max-list-size when a cap is opt-in-applied)
     :list-truncated?   true iff an OPT-IN :max-list-size actually DROPPED a distinct value
     :topn-truncated?   true iff an OPT-IN :n actually DROPPED a ranked pair

   Pure + total — never throws (a non-numeric value coerces to nil + counts, not an
   exception).

   Implemented as `aggregate-finalize` over a `reduce` of `aggregate-step` — the SAME
   init/step/finalize the STREAMING apply drives CHUNK-BY-CHUNK, so a caller that pages
   a huge container never holds more than one chunk in heap (the bound is the ACCUMULATOR
   `keys × N`, not the row count)."
  [spec rows]
  (aggregate-finalize spec (reduce (fn [st row] (aggregate-step spec st row))
                                   (aggregate-init) rows)))

;; ---------------------------------------------------------------------------
;; The APPLY routing gate + predicates. MT-6: the aggregating path is SAMPLE-DRIVEN
;; — it fires when the AUTHOR emitted a valid model-authored spec AND the spec's key
;; genuinely REPEATS in the sample (`key-repeats?`), which SUBSUMES the old
;; `:long-form` shape tag (a repeating key still aggregates) and ADDS the list-collect
;; case, while DECLINING a near-unique key (1 row per entity) even with a spec. When
;; no sample is available it FALLS BACK to the `:long-form` tag (behavior-preserving).
;; Every other outcome keeps the EXISTING per-row `:transform-source` path (#8). The
;; gate is DETERMINISTIC (in the `:code` APPLY node), so a mis-emitted spec on a
;; genuinely per-entity table can never divert the per-row path.
;;
;; WHY sample-driven, not a structural shape rule: the MT-6 /prototype proved a
;; structural "repeating-key → aggregate" MT-1 rule OVER-FIRES (a list-valued
;; attribute table and a reference dictionary are STRUCTURALLY IDENTICAL — both a
;; repeating key + a near-unique element). Only the domain-aware AUTHOR can tell which
;; repeating key is a real entity to enrich; `key-repeats?` only GUARDS that whatever
;; the AUTHOR chose is not applied to a 1-row-per-entity table.
;; ---------------------------------------------------------------------------

(defn long-form-container?
  "True iff the container's MT-1 `:shape` tag is `:long-form`. Tolerant of the tag
   arriving as a keyword OR its string form (the child-tick blackboard / C1 map-write
   fragility can stringify a keyword)."
  [container]
  (let [s (:shape container)]
    (boolean (or (= :long-form s)
                 (= "long-form" (str s))
                 (= ":long-form" (str s))))))

(defn valid-aggregation-spec?
  "True iff `spec` is an executable rollup spec — a MAP carrying a non-blank KEY +
   ELEMENT column (the two the fold ALWAYS needs). MT-6: the `:value-col` is
   OPTIONAL — WITH it the fold ranks a top-N, WITHOUT it it COLLECTS the element
   values into a bounded flat list. A spec missing the key or the element is NOT
   executable (the caller keeps the per-row path)."
  [spec]
  (boolean (and (map? spec)
                (seq (str (:key-col spec)))
                (seq (str (:element-col spec))))))

(def unique-key-threshold
  "MT-6 — the near-unique cutoff for the sample-driven gate, mirroring MT-1's
   `container-shape/unique-key-threshold` (0.9). A sampled key column whose
   distinct-ratio is AT/ABOVE this is a near-unique identifier (≈1 row per entity —
   do NOT aggregate → per-row); BELOW it the key REPEATS (many rows per entity —
   aggregate). The entity/non-entity gap is wide, so 0.9 is a safe separator."
  0.9)

(defn key-repeats?
  "MT-6 — the sample-driven predicate at the heart of the APPLY gate: TRUE iff
   `key-col` genuinely REPEATS in `sample` — its distinct-ratio (distinct-nonnil /
   sample-size) is BELOW `unique-key-threshold` (many rows share a key value, i.e.
   many rows per entity). A near-unique key (≈1 row per entity, e.g. an Occupation
   Data SOC) returns false → do NOT aggregate (per-row). An empty sample or a
   key-col absent from every row returns false (repetition can't be established —
   the caller falls back to the shape tag). Pure + total.

   This is the SEMANTIC-agnostic distinction the /prototype showed a structural MT-1
   rule can't make on its own: a list-valued attribute table and a reference
   dictionary are structurally identical (both repeating-key), so the AUTHORING of a
   valid spec (the LLM's domain judgment) is what selects aggregation; `key-repeats?`
   only guards that a spec is NOT applied to a genuinely 1-row-per-entity table."
  [sample key-col]
  (boolean
   (when (and (seq sample) (seq (str key-col)))
     (let [vals (remove nil? (map #(get % key-col) sample))
           n (count sample)]
       (and (pos? (count vals))
            (< (/ (count (distinct vals)) (double n)) unique-key-threshold))))))

(defn aggregating-apply?
  "The DETERMINISTIC APPLY routing gate. Fire the aggregating path ONLY when the
   AUTHOR produced a valid rollup spec AND the spec's `:key-col` genuinely REPEATS in
   the SAMPLE (`key-repeats?` — many rows per entity). This is SAMPLE-DRIVEN, not
   shape-tag-driven: it SUBSUMES the old `:long-form` gate (a repeating key still
   aggregates) and ADDS the MT-6 list case (a repeating-key attribute table), while a
   near-unique key (1 row per entity) keeps the per-row path even if a spec was
   emitted. When NO sample is available it FALLS BACK to the old
   `long-form-container?` tag (behavior-preserving — the 2-arity contract). Otherwise
   the caller runs the EXISTING per-row `apply-extraction-transform!` (#8)."
  ([container spec] (aggregating-apply? container spec nil))
  ([container spec sample]
   (boolean
    (and (valid-aggregation-spec? spec)
         (if (seq sample)
           (key-repeats? sample (:key-col spec))
           (long-form-container? container))))))

(defn normalize-spec-keys
  "CONNECT-3b — normalize the AUTHOR's key-spelling variance to the canonical
   HYPHEN field names. The structured-output round-trip lets the model emit the
   nested aggregation-spec's keys with UNDERSCORES (`:key_col`, `:attr_name`,
   `:element_entity_type`) or a near-synonym (`:field` for the element column) —
   the live CONNECT-3b prototype observed both. This RENAMES every key's
   underscores to hyphens (so `:element_entity_type` → `:element-entity-type`,
   the association-mode trigger, and `:key_col` → `:key-col`, the validity trigger,
   BOTH resolve) and folds the `:field` → `:element-col` synonym when no
   `:element-col` is present. Deterministic field-synonym normalization (#10) — the
   VALUES are untouched (a predicate value `\"requires_skill\"` keeps its
   underscore); only KEYS are canonicalized. Pure + total."
  [m]
  (when (map? m)
    (let [hyphenated (reduce-kv (fn [acc k v]
                                  (let [k' (if (keyword? k)
                                             (keyword (str/replace (name k) "_" "-"))
                                             k)]
                                    (assoc acc k' v)))
                                {} m)]
      (cond-> hyphenated
        (and (not (contains? hyphenated :element-col)) (contains? hyphenated :field))
        (-> (assoc :element-col (:field hyphenated)) (dissoc :field))))))

(defn parse-aggregation-spec
  "Coerce the AUTHOR's `:aggregation-spec` write into a clean spec map. The `:llm`
   node may deliver a structured map OR — intermittently (the C1 map-write
   fragility, cf. GC-10 Fix A's `:entity-types`) — an un-parsed EDN STRING; this
   `edn/read-string`s a string back to a map (garbage → nil, never a fabricated
   spec — #5), NORMALIZES the model's key-spelling variance (underscore→hyphen +
   the `:field` synonym via `normalize-spec-keys`, CONNECT-3b), coerces `:n` from a
   string, and nils a blank `:filter-col`. The association fields `:predicate` +
   `:element-entity-type` (CONNECT-3b) pass through untouched once normalized — so a
   model-authored `{:key_col … :predicate … :element_entity_type …}` becomes a valid
   association spec. Pure + total — never throws."
  [raw]
  (let [m (cond
            (map? raw) (normalize-spec-keys raw)
            (and (string? raw) (seq (str/trim raw)))
            (try (let [p (edn/read-string raw)] (when (map? p) (normalize-spec-keys p)))
                 (catch Throwable _ nil))
            :else nil)]
    (when m
      (-> m
          (update :n (fn [n] (cond (number? n) (long n)
                                   (and (string? n) (re-matches #"\s*\d+\s*" n)) (Long/parseLong (str/trim n))
                                   :else nil)))
          ;; STREAM Slice 7 — the OPT-IN collect cap coerces like :n (string→long,
          ;; else nil = UNBOUNDED default). Absent → nil (keep everything).
          (update :max-list-size (fn [n] (cond (number? n) (long n)
                                               (and (string? n) (re-matches #"\s*\d+\s*" n)) (Long/parseLong (str/trim n))
                                               :else nil)))
          (update :filter-col (fn [c] (when (seq (str/trim (str c))) c)))))))

;; ---------------------------------------------------------------------------
;; The AUTHOR contract EXTENSION (gated on :long-form). Appended to the DT4 per-row
;; author prompt: on a :long-form container the AUTHOR emits an :aggregation-spec
;; (a DATA map — no SCI eval) INSTEAD of a per-row :transform-source. Domain-
;; agnostic (#12): the AUTHOR picks EVERY column + the scale filter from the sample.
;; ---------------------------------------------------------------------------

(def aggregation-spec-write-key
  "The AUTHOR's aggregating-transform output write (the rollup SPEC, one DATA map)."
  :aggregation-spec)

(def full-coverage-max-windows
  "MT-3 — the window ceiling the AGGREGATING apply streams to. The per-row extract
   caps windows (`default-max-extract-windows` = 50) because it accumulates EVERY
   row's drafts in heap (draft VOLUME → OOM at scale — the GC-7/GC-8 bound). The
   aggregating fold is BOUNDED-MEMORY (distinct-keys × N regardless of row count), so
   that heap-volume cap does NOT apply — and inheriting it would TRUNCATE the stream
   (excel windows hard-cap at 500 rows → 50 windows = 25k rows; O*NET `Skills` = 62.5k
   rows → the later occupations would get NO draft, a SILENT coverage loss / wrong
   top-N — #4/#5). So the aggregating path streams to CONTAINER EXHAUSTION: this
   matches `do-stream-all`'s own ceiling (it stops at a short/empty window), so a
   real container completes fully while the bound still guards a pathological source."
  100000)

(defn aggregation-author-guidance
  "The prompt EXTENSION the AUTHOR reads on every container. MT-6: the decision is
   SAMPLE-DRIVEN, not shape-tag-gated. It tells the model to INSPECT the sample: if
   the ENTITY key appears in MANY rows (a tall table — many rows per entity), ROLL IT
   UP via an `:aggregation-spec`. CONNECT-3b: there are now THREE cases the model must
   distinguish —
     (A) ATTRIBUTE — the element value is an ENTITY-SPECIFIC label/measure (unique to
         one key: an alternate name, a one-off string) → roll it onto the entity as an
         attribute (ranked TOP-N with a numeric `:value-col`, or a flat LIST without);
     (C) ASSOCIATION — the element value is itself a GENERAL, REFERENCEABLE ENTITY that
         MANY different keys would share (a capability, category, activity, standard) →
         a many-to-many RELATION; author an association-spec (the same map PLUS
         `:predicate` + `:element-entity-type` in place of `:attr-name`) so the element
         MINTS as a SHARED node + a key→element EDGE (the value-col rating rides the
         edge). Burying a shareable entity in a per-key list yields 0 shared nodes / 0
         edges — a BFS-dead graph;
     (B) one row per entity (near-unique key) → author the per-row `transform-source`.
   Reasoning FIRST (#13). Domain-agnostic (#12): the model picks every column + the
   mode + the predicate + the element-entity-type from the SAMPLE; this text names NO
   domain column/type/predicate. The A↔C distinction is the element's NATURE (a
   shareable, referenceable KIND OF THING vs a one-entity label) — the structural
   'recurs across many keys' signal is often NOT visible in a small contiguous sample
   (which shows one key's rows), so the model judges by the element's KIND."
  []
  (str
   "\n\n*** REPEATING-KEY TABLES — AUTHOR AN AGGREGATION-SPEC INSTEAD (read carefully) ***\n"
   "INSPECT the provided sample-rows. Ask: does ONE entity key value appear in MANY "
   "rows? If so, emitting one concept per raw row would SHATTER each entity into "
   "dozens of fragments — roll it up with an `aggregation-spec` (a DATA MAP, not "
   "code) instead of a per-row `transform-source`. There are THREE cases — decide "
   "which the element column is:\n\n"

   "(A) MANY ROWS PER ENTITY, and each row's ELEMENT value is an ENTITY-SPECIFIC "
   "LABEL/MEASURE — a value that BELONGS TO this one entity and would NOT be shared "
   "verbatim by many other entities (an alternate name/title, a one-off descriptive "
   "string, a code specific to this row). These have no independent identity worth a "
   "node of their own. ROLL THEM UP onto the entity as an ATTRIBUTE with:\n"
   "  {:key-col      <the ENTITY key column to group by (the one that REPEATS)>\n"
   "   :element-col  <the column whose values are the labels to collect>\n"
   "   :value-col    <OPTIONAL — the NUMERIC column to RANK by. Include ONLY when the "
   "rows carry a REAL measure/score you VERIFIED in the sample (NOT an id/code) → a "
   "ranked TOP-N. If the elements are descriptive labels with no ranking measure, "
   "OMIT it → a bounded flat LIST of the label values.>\n"
   "   :n            <how many top elements to keep per entity for the top-N (ignored "
   "for a list-collect)>\n"
   "   :attr-name    <the flat array attribute name to hold them, e.g. topElements>\n"
   "   :entity-type  <the model-spec entity-type this key names>\n"
   "   :filter-col   <OPTIONAL — a scale/subset column; see the scale-filter note below>\n"
   "   :filter-val   <OPTIONAL — the single scale/subset VALUE to KEEP>}\n\n"

   "(C) MANY ROWS PER ENTITY, but each row's ELEMENT value is itself a GENERAL, "
   "REFERENCEABLE ENTITY — a reusable 'thing' with its OWN identity that MANY "
   "DIFFERENT entities would share (a capability, category, topic, standard, "
   "activity, component, requirement — a value you would expect to see attached to "
   "lots of other keys too, and that you'd want to REACH AS A NODE and compare "
   "ACROSS entities). This is a MANY-TO-MANY RELATION, not an attribute: burying it "
   "in a per-entity list yields ZERO shared nodes and ZERO edges (a graph you cannot "
   "traverse from one entity to another through the shared thing). Instead MINT the "
   "element as a SHARED node + a key->element EDGE by authoring an ASSOCIATION-spec — "
   "the SAME map as (A) but with TWO extra fields IN PLACE OF `:attr-name`:\n"
   "   :predicate            <the relationship VERB from the key entity to the element "
   "entity — how the key RELATES to the element (author it yourself from the meaning; "
   "a verb like uses / performs / belongs-to / depends-on)>\n"
   "   :element-entity-type  <the element's OWN entity-type — the type of thing each "
   "element value IS (propose a new type here if the model-spec has none for it)>\n"
   " Keep `:key-col` `:element-col` and (when the rows carry a real measure you "
   "verified) `:value-col` + the scale `:filter-col`/`:filter-val` — the value-col "
   "rating RIDES THE EDGE. Do NOT set `:attr-name` for an association.\n"
   " HOW TO TELL (A) FROM (C): ask 'would this exact element value plausibly appear "
   "for MANY OTHER entity keys, and is it a general thing I'd want as its own node to "
   "traverse to?' — YES → (C) association; or is it a label/measure specific to THIS "
   "entity → (A) attribute. NOTE: a small contiguous sample often shows only ONE key's "
   "rows, so you may NOT literally SEE the element repeat across keys — judge by the "
   "element's NATURE (a shareable, referenceable kind of thing vs a one-entity label).\n\n"

   "(B) ONE ROW PER ENTITY (each row is a DISTINCT entity — the key is near-unique). "
   "NOT an aggregation: leave `aggregation-spec` empty/absent and author the per-row "
   "`transform-source` exactly as described above.\n\n"

   "The SCALE FILTER is critical whenever you rank a top-N: if the table repeats each "
   "entity×element under multiple scales/subsets, a top-N WITHOUT the filter MIXES "
   "scales and is garbage — inspect the sample for a scale/subset column and set "
   "`filter-col`/`filter-val` to the one scale you rank by. Pick a value that occurs "
   "in the real sampled rows.\n\n"

   "Write your `reasoning` FIRST (#13): state whether the key repeats (many rows per "
   "entity vs one); if it repeats, state whether the element is an ENTITY-SPECIFIC "
   "label/measure (→ A, attribute) or a GENERAL REFERENCEABLE entity many keys would "
   "share (→ C, association) and name the predicate + element-entity-type; then name "
   "the key/element columns and any value column + scale filter, and why."))
