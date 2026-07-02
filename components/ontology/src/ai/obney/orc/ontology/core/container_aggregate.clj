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
;; when the spec has NO `:value-col` (blank/absent). The list is DISTINCT + BOUNDED
;; at `max-list-size` so a 25k-row attribute table can NEVER blow the accumulator
;; up (the whole reason MT-6 exists — those per-row nodes shouldn't exist).
;; ---------------------------------------------------------------------------

(def max-list-size
  "Collect-mode (list) cap: at most this many DISTINCT element values kept per key.
   Boundedness is load-bearing (#2/#5) — once a key's list reaches this cap, further
   distinct elements are IGNORED, so the accumulator stays keys × max-list-size no
   matter how many rows a repeating-key attribute table carries (never a 25k blowup).
   Mirrors the top-N mode's per-key bound; a modest cap keeps a node's list readable."
  25)

(defn collect-mode?
  "True iff `spec` is a COLLECT (list) spec — it has NO `:value-col` (blank/absent),
   so there is no numeric column to RANK by. Collect mode gathers the element values
   into a per-key flat list (bounded at `max-list-size`) instead of a top-N-by-value.
   A spec WITH a non-blank `:value-col` keeps the existing top-N path. Pure + total."
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
  "The empty fold state (accumulator + honest counters)."
  []
  {:acc {} :rows-seen 0 :rows-kept 0 :rows-errored 0 :rows-filtered 0})

(defn aggregate-step
  "Fold ONE row into the aggregate `state` under `spec`. Off-scale rows (the scale
   filter) increment `:rows-filtered`. Then, gated on the spec's MODE:

   TOP-N mode (spec HAS a `:value-col`): in-scope rows with key+element+numeric value
   contribute to the per-key TOP-N (pruned on every insert → bounded at N per key);
   in-scope rows missing key/element or with a non-numeric value increment
   `:rows-errored` (honest skip, never fabricated).

   COLLECT mode (MT-6, spec has NO `:value-col`): in-scope rows with key+element
   collect the ELEMENT value into the per-key flat list — DISTINCT and BOUNDED at
   `max-list-size` (once at the cap, further distinct elements are ignored so the
   accumulator can never blow up); no numeric value is required. Rows missing
   key/element increment `:rows-errored`. A duplicate/over-cap element is still a
   valid in-scope row (`:rows-kept`), just deduped/dropped — never an error.

   Pure + total."
  [spec state row]
  (let [{:keys [key-col element-col n filter-col filter-val]} spec
        n (or n 10)
        collect? (collect-mode? spec)
        keep-topn (fn [pairs] (->> pairs (sort-by :value >) (take n) vec))
        add-distinct-bounded (fn [cur e]
                               ;; keep at most max-list-size DISTINCT elements per key
                               (let [cur (or cur [])]
                                 (if (or (>= (count cur) max-list-size)
                                         (some #(= % e) cur))
                                   cur
                                   (conj cur e))))
        {:keys [acc rows-seen rows-kept rows-errored rows-filtered]} state
        rows-seen (inc rows-seen)
        scale-ok? (or (nil? filter-col)
                      (= (str (get row filter-col)) (str filter-val)))]
    (if-not scale-ok?
      (assoc state :rows-seen rows-seen :rows-filtered (inc rows-filtered))
      (let [k (get row key-col)
            e (get row element-col)]
        (if collect?
          ;; MT-6 COLLECT (list) mode — no numeric value needed.
          (if (and (some? k) (some? e))
            (assoc state
                   :acc (update acc k add-distinct-bounded e)
                   :rows-seen rows-seen
                   :rows-kept (inc rows-kept))
            (assoc state :rows-seen rows-seen :rows-errored (inc rows-errored)))
          ;; TOP-N-by-value mode (existing behavior — a numeric value is required).
          (let [v (coerce-num (get row (:value-col spec)))]
            (if (and (some? k) (some? e) (some? v))
              (assoc state
                     :acc (update acc k (fn [cur] (keep-topn (conj (or cur []) {:element e :value v}))))
                     :rows-seen rows-seen
                     :rows-kept (inc rows-kept))
              (assoc state :rows-seen rows-seen :rows-errored (inc rows-errored)))))))))

(defn aggregate-finalize
  "Produce the per-key concept-drafts + honest counts from a folded `state`. ONE
   draft per key: `{:uri :label :entity-type :attributes {attr-name [top-N labels]
   key-col <key value>}}`. `:peak-acc-entries` (== final, since top-N is pruned on
   insert) is the boundedness witness (≤ keys × N)."
  [spec state]
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
                        ;; flat list); TOP-N entries are {:element :value} pairs.
                        :attributes (merge {attr-name (if collect?
                                                        (vec entries)
                                                        (mapv :element entries))}
                                           (when key-col {key-col k}))})
                     acc)]
    {:concept-drafts drafts
     :distinct-keys (count acc)
     :peak-acc-entries (reduce + 0 (map (comp count val) acc))
     :rows-seen (:rows-seen state)
     :rows-kept (:rows-kept state)
     :rows-errored (:rows-errored state)
     :rows-filtered (:rows-filtered state)}))

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
  "The prompt EXTENSION the AUTHOR reads on every container. MT-6: the decision is
   SAMPLE-DRIVEN, not shape-tag-gated. It tells the model to INSPECT the sample: if
   the ENTITY key appears in MANY rows (a tall table — many rows per entity, one row
   per entity×element), ROLL IT UP into ONE record per key via an `:aggregation-spec`
   — with a numeric `:value-col` for a ranked TOP-N when the element rows carry a real
   measure, OR NO `:value-col` for a LIST-collect (a flat list of the descriptive
   element labels) when they don't. If each row is a DISTINCT entity (one row per
   key), author the per-row `transform-source` instead. Reasoning FIRST (#13).
   Domain-agnostic (#12): the model picks every column + the mode + the scale filter
   from the SAMPLE (role hints are ADVISORY — it VERIFIES a value column is a real
   MEASURE against the sample, never trusting a hint that points at an id column)."
  []
  (str
   "\n\n*** REPEATING-KEY TABLES — AUTHOR AN AGGREGATION-SPEC INSTEAD (read carefully) ***\n"
   "INSPECT the provided sample-rows. Ask: does ONE entity key value appear in MANY "
   "rows? There are two cases:\n\n"
   "(A) MANY ROWS PER ENTITY (a tall table — the key repeats; each row is one "
   "entity×element pair, e.g. an entity plus one of its measurements, or an entity "
   "plus one of its descriptive labels). Emitting one concept per raw row would "
   "SHATTER each entity into dozens of fragments. Instead ROLL IT UP: do NOT author a "
   "`transform-source`; author an `aggregation-spec` — a DATA MAP (not code) — with:\n"
   "  {:key-col      <the ENTITY key column to group by (the one that REPEATS)>\n"
   "   :element-col  <the column whose values are the element LABELS to collect>\n"
   "   :value-col    <OPTIONAL — the NUMERIC column to RANK elements by. Include it "
   "ONLY when the element rows carry a REAL measure/score you VERIFIED in the sample "
   "(NOT an id/code) → you get a ranked TOP-N. If the elements are DESCRIPTIVE labels "
   "with no ranking measure (e.g. alternate names, tasks, related items), OMIT "
   "`value-col` entirely → you get a bounded flat LIST of the label values (no rank).>\n"
   "   :n            <how many top elements to keep per entity for the top-N, e.g. 10 "
   "(ignored for a list-collect)>\n"
   "   :attr-name    <the flat array attribute name to hold them, e.g. topElements>\n"
   "   :entity-type  <the model-spec entity-type this key names>\n"
   "   :filter-col   <OPTIONAL — if the SAME entity×element appears more than once "
   "under different measurement SCALES/subsets (a scale/subset column), name that "
   "column here so only ONE scale is ranked; otherwise omit it>\n"
   "   :filter-val   <OPTIONAL — the single scale/subset VALUE to KEEP>}\n\n"
   "The SCALE FILTER is critical for a top-N: if the table repeats each "
   "entity×element under multiple scales (e.g. two different measurements of the same "
   "pair), a top-N WITHOUT the filter MIXES scales and is garbage — inspect the sample "
   "for a repeated-pair/scale column and set `filter-col`/`filter-val` to the one "
   "scale you are ranking by. Pick a value that occurs in the real sampled rows.\n\n"
   "(B) ONE ROW PER ENTITY (each row is a DISTINCT entity — the key is near-unique). "
   "This is NOT an aggregation: leave `aggregation-spec` empty/absent and author the "
   "per-row `transform-source` exactly as described above.\n\n"
   "Write your `reasoning` FIRST (#13): state whether the key repeats in the sample "
   "(many rows per entity vs one), and for a roll-up name the key/element columns and "
   "whether you are using a ranked top-N (with the value column you verified is a real "
   "measure, and any scale filter) or a flat LIST (no value column), and why."))
