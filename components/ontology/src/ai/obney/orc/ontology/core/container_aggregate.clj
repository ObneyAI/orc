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
;; The deterministic executor — bounded streaming group-by-key → top-N-by-value.
;; ---------------------------------------------------------------------------

(defn stream-aggregate
  "Bounded streaming group-by-key → top-N-by-value rollup. `spec` is the model-
   authored ROLLUP SPEC:

     {:key-col     <col>  ; the ENTITY key to group by
      :element-col <col>  ; the element LABEL to collect
      :value-col   <col>  ; the numeric column to RANK by
      :n           <int>  ; how many top elements to keep (default 10)
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
     :peak-acc-entries  the FINAL (== peak, since the top-N is pruned on every insert)
                        accumulator size — the boundedness witness (≤ keys × N)

   Pure + total — never throws (a non-numeric value coerces to nil + counts, not an
   exception)."
  [spec rows]
  (let [{:keys [key-col element-col value-col n attr-name filter-col filter-val entity-type]} spec
        n (or n 10)
        attr-name (or attr-name :top)
        et (or entity-type "entity")
        ;; bounded: on each insert keep only the top-N pairs by value (desc).
        keep-topn (fn [pairs] (->> pairs (sort-by :value >) (take n) vec))
        result
        (reduce
         (fn [{:keys [acc rows-seen rows-kept rows-errored rows-filtered] :as st} row]
           (let [rows-seen (inc rows-seen)
                 scale-ok? (or (nil? filter-col)
                               (= (str (get row filter-col)) (str filter-val)))]
             (if-not scale-ok?
               ;; off-scale — legitimately not this measurement (not an error).
               (assoc st :rows-seen rows-seen :rows-filtered (inc rows-filtered))
               (let [k (get row key-col)
                     e (get row element-col)
                     v (coerce-num (get row value-col))]
                 (if (and (some? k) (some? e) (some? v))
                   (assoc st
                          :acc (update acc k (fn [cur] (keep-topn (conj (or cur []) {:element e :value v}))))
                          :rows-seen rows-seen
                          :rows-kept (inc rows-kept))
                   ;; in-scope but missing key/element or non-numeric value → honest skip.
                   (assoc st :rows-seen rows-seen :rows-errored (inc rows-errored)))))))
         {:acc {} :rows-seen 0 :rows-kept 0 :rows-errored 0 :rows-filtered 0}
         rows)
        acc (:acc result)
        drafts (mapv (fn [[k pairs]]
                       {:uri (str et "/" k)
                        :label (str k)
                        :entity-type et
                        :attributes (merge {attr-name (mapv :element pairs)}
                                           (when key-col {key-col k}))})
                     acc)]
    {:concept-drafts drafts
     :distinct-keys (count acc)
     :peak-acc-entries (reduce + 0 (map (comp count val) acc))
     :rows-seen (:rows-seen result)
     :rows-kept (:rows-kept result)
     :rows-errored (:rows-errored result)
     :rows-filtered (:rows-filtered result)}))

;; ---------------------------------------------------------------------------
;; The shape gate + the APPLY routing predicates. The aggregating path fires ONLY
;; for a `:long-form`-tagged container (MT-1) with a valid model-authored spec —
;; every other shape keeps the EXISTING per-row `:transform-source` path (behavior-
;; preserving, #8). The gate is DETERMINISTIC (in the `:code` APPLY node), so a
;; mis-emitted spec on a non-long-form container can never divert the per-row path.
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
  "True iff `spec` is an executable rollup spec — a MAP carrying a non-blank key +
   element + value column (the three the fold needs). An incomplete/absent spec is
   NOT executable (the caller keeps the per-row path)."
  [spec]
  (boolean (and (map? spec)
                (seq (str (:key-col spec)))
                (seq (str (:element-col spec)))
                (seq (str (:value-col spec))))))

(defn aggregating-apply?
  "The DETERMINISTIC APPLY routing gate: fire the aggregating path ONLY when the
   container is `:long-form` AND the AUTHOR produced a valid rollup spec. Otherwise
   (any other shape, or no/incomplete spec) the caller runs the EXISTING per-row
   `apply-extraction-transform!` — behavior-preserving (#8)."
  [container spec]
  (boolean (and (long-form-container? container)
                (valid-aggregation-spec? spec))))

(defn parse-aggregation-spec
  "Coerce the AUTHOR's `:aggregation-spec` write into a clean spec map. The `:llm`
   node may deliver a structured map OR — intermittently (the C1 map-write
   fragility, cf. GC-10 Fix A's `:entity-types`) — an un-parsed EDN STRING; this
   `edn/read-string`s a string back to a map (garbage → nil, never a fabricated
   spec — #5), coerces `:n` from a string, and nils a blank `:filter-col` (so a
   spec with no scale dimension carries no filter). Pure + total — never throws."
  [raw]
  (let [m (cond
            (map? raw) raw
            (and (string? raw) (seq (str/trim raw)))
            (try (let [p (edn/read-string raw)] (when (map? p) p))
                 (catch Throwable _ nil))
            :else nil)]
    (when m
      (-> m
          (update :n (fn [n] (cond (number? n) (long n)
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
  "The prompt EXTENSION the AUTHOR reads when its container may be `:long-form`. It
   tells the model: check the container's `:shape` tag; if it is `:long-form` (many
   rows per entity — one row per entity×element measurement), do NOT author a per-row
   transform — instead author an `:aggregation-spec` DATA map that rolls the group up
   into ONE record per entity key carrying its TOP-N elements as a FLAT array
   attribute. Reasoning FIRST (#13). Domain-agnostic (#12): the model picks every
   column + the scale filter from the SAMPLE (role hints are ADVISORY — it VERIFIES
   the value column is a real MEASURE against the sample, never trusting a hint that
   points at an id column)."
  []
  (str
   "\n\n*** LONG-FORM CONTAINERS — AUTHOR AN AGGREGATION-SPEC INSTEAD (read carefully) ***\n"
   "The container you are extracting carries a structural SHAPE tag (see the `:shape` "
   "of the `container` input). If that shape is `:long-form`, this table is NOT one "
   "entity per row — it is MANY rows per entity, one row per entity×element "
   "measurement (a tall, narrow table: an entity-key column, an element-label column, "
   "and a numeric value column, often repeated across a measurement SCALE). Emitting "
   "one concept per raw row would SHATTER each entity into dozens of measurement "
   "fragments. Instead, ROLL IT UP.\n\n"
   "For a `:long-form` container, do NOT author a `transform-source`. Author an "
   "`aggregation-spec` — a DATA MAP (not code) — with these fields, choosing every "
   "column by INSPECTING the provided sample-rows:\n"
   "  {:key-col      <the ENTITY key column to group by>\n"
   "   :element-col  <the column whose values are the element LABELS to collect>\n"
   "   :value-col    <the NUMERIC column to RANK elements by — VERIFY against the "
   "sample that this column holds real measures/scores, NOT an id/code>\n"
   "   :n            <how many top elements to keep per entity, e.g. 10>\n"
   "   :attr-name    <the flat array attribute name to hold them, e.g. topElements>\n"
   "   :entity-type  <the model-spec entity-type this key names>\n"
   "   :filter-col   <OPTIONAL — if the SAME entity×element appears more than once "
   "under different measurement SCALES/subsets (a scale/subset column), name that "
   "column here so only ONE scale is ranked; otherwise omit it>\n"
   "   :filter-val   <OPTIONAL — the single scale/subset VALUE to KEEP>}\n\n"
   "The SCALE FILTER is critical: if the table repeats each entity×element under "
   "multiple scales (e.g. two different measurements of the same pair), a top-N "
   "WITHOUT the filter MIXES scales and is garbage — inspect the sample for a "
   "repeated-pair/scale column and set `filter-col`/`filter-val` to the one scale you "
   "are ranking by. Pick the value that occurs in the real sampled rows.\n\n"
   "If the container's shape is anything OTHER than `:long-form`, IGNORE this section "
   "entirely: leave `aggregation-spec` empty/absent and author the per-row "
   "`transform-source` exactly as described above.\n\n"
   "Write your `reasoning` FIRST (#13): name the shape, and for a long-form table "
   "name the key/element/value columns you verified in the sample and whether a scale "
   "filter is needed and why."))
