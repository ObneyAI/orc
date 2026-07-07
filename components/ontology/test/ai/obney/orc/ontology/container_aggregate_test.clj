(ns ai.obney.orc.ontology.container-aggregate-test
  "MT-3 — the deterministic AGGREGATING transform (long-form → top-N flat attribute).

   These tests exercise the PURE executor `stream-aggregate` and the APPLY routing
   through the PUBLIC `container-aggregate` surface. NO LLM, NO real source here (the
   real O*NET Skills/Knowledge live proof is the /inspect-orc). Domain-agnostic
   synthetic fixtures — abstract entity/element/scale, no O*NET/CIP/SOC baked in."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.core.container-aggregate :as ca]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]))

;; ===========================================================================
;; Tracer 1 — group-by-key + top-N-by-value + the SCALE filter (the heart).
;; ===========================================================================
;; A long-form fixture: 2 entity keys, several elements each, with a SCALE column
;; carrying an in-scope value (IM) and an off-scale value (LV). The off-scale rows
;; deliberately carry a HIGHER value than the in-scope rows — so if the filter were
;; NOT applied, they would rank #1 and corrupt the top-N. The filter must exclude them.

(def ^:private long-form-rows
  [;; occ1 — in-scope (IM) measurements
   {"occ" "occ1" "elem" "A" "val" 5 "scale" "IM"}
   {"occ" "occ1" "elem" "B" "val" 4 "scale" "IM"}
   {"occ" "occ1" "elem" "C" "val" 3 "scale" "IM"}
   ;; occ1 — OFF-scale (LV): E carries the highest value in the whole group
   {"occ" "occ1" "elem" "E" "val" 9 "scale" "LV"}
   ;; occ2 — in-scope (IM)
   {"occ" "occ2" "elem" "X" "val" 7 "scale" "IM"}
   {"occ" "occ2" "elem" "Y" "val" 6 "scale" "IM"}
   ;; occ2 — OFF-scale (LV): Z carries the highest value
   {"occ" "occ2" "elem" "Z" "val" 8 "scale" "LV"}])

(def ^:private top2-spec
  {:key-col "occ" :element-col "elem" :value-col "val"
   :filter-col "scale" :filter-val "IM"
   :n 2 :attr-name :topElems :entity-type "occupation"})

(deftest stream-aggregate-groups-ranks-and-scale-filters
  (testing "ONE draft per entity KEY, the flat attr = the top-N element labels by
            value, and the OFF-scale rows are EXCLUDED (the scale filter is load-bearing)"
    (let [{:keys [concept-drafts distinct-keys]} (ca/stream-aggregate top2-spec long-form-rows)
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)]
      ;; one draft per key — NOT one per raw row (7 rows → 2 drafts)
      (is (= 2 (count concept-drafts))
          "one concept-draft per distinct entity key (not per raw measurement row)")
      (is (= 2 distinct-keys))
      ;; occ1 top-2 by IMPORTANCE = [A B] (5, 4) — NOT E (9, but off-scale)
      (is (= ["A" "B"] (get-in by-uri ["occupation/occ1" :attributes :topElems]))
          "occ1's top-2 elements are ranked by the in-scope value column")
      ;; occ2 top-2 = [X Y] (7, 6) — NOT Z (8, but off-scale)
      (is (= ["X" "Y"] (get-in by-uri ["occupation/occ2" :attributes :topElems]))
          "occ2's top-2 elements are ranked by the in-scope value column")
      ;; the off-scale elements never appear in ANY draft — the filter excluded them
      (let [all-elems (mapcat #(get-in % [:attributes :topElems]) concept-drafts)]
        (is (not (some #{"E" "Z"} all-elems))
            "OFF-scale (LV) rows are excluded — a top-N without the filter mixes scales"))
      ;; the draft is a real keyed entity: uri, label, entity-type, and the key value
      ;; carried in :attributes so GC-1 canonicalize / GC-11 linking can recover identity
      (let [d (get by-uri "occupation/occ1")]
        (is (= "occupation" (:entity-type d)) "the draft carries its entity-type")
        (is (= "occ1" (:label d)) "the draft label is the entity key")
        (is (= "occ1" (get-in d [:attributes "occ"]))
            "the key value is carried in :attributes under the key column name")))))

;; ===========================================================================
;; Tracer 2 — boundedness + string coercion + honest counts + empty input.
;; ===========================================================================

(def ^:private counts-spec
  {:key-col "k" :element-col "el" :value-col "v"
   :filter-col "s" :filter-val "IM"
   :n 10 :attr-name :top :entity-type "e"})

(def ^:private counts-rows
  [{"k" "e1" "el" "a" "v" "3.5" "s" "IM"}       ; kept — string value COERCED
   {"k" "e1" "el" "b" "v" "2.0" "s" "IM"}       ; kept — string value coerced
   {"k" "e1" "el" "c" "v" "not-a-number" "s" "IM"} ; in-scope, NON-numeric → errored
   {"k" "e1" "el" "d" "v" "9.0" "s" "LV"}        ; OFF-scale → filtered (not error)
   {"el" "x" "v" "9" "s" "IM"}                   ; MISSING key → errored
   {"k" "e2" "v" "5" "s" "IM"}])                 ; MISSING element → errored

(deftest stream-aggregate-coerces-strings-and-counts-honestly
  (testing "string values are numeric-coerced; a row missing key/element or with a
            non-numeric value is SKIPPED + COUNTED (never fabricated); off-scale rows
            are counted as FILTERED not errored (#4/#5)"
    (let [{:keys [concept-drafts distinct-keys rows-seen rows-kept
                  rows-errored rows-filtered]} (ca/stream-aggregate counts-spec counts-rows)]
      (is (= 6 rows-seen) "every row folded")
      (is (= 1 rows-filtered) "the one off-scale (LV) row is filtered, not errored")
      (is (= 2 rows-kept) "only the two in-scope numeric rows contributed")
      (is (= 3 rows-errored)
          "the non-numeric value + missing-key + missing-element rows are counted, not silent")
      (is (= 1 distinct-keys) "only e1 produced a draft (e2's only row was errored)")
      ;; the STRING values were coerced + ranked numerically: 3.5 > 2.0 → [a b]
      (is (= ["a" "b"] (get-in (first concept-drafts) [:attributes :top]))
          "string values '3.5'/'2.0' coerced + ranked numerically (not lexically)"))))

(deftest stream-aggregate-is-bounded-by-keys-times-n
  (testing "peak accumulator entries = distinct-keys × N regardless of row count —
            the top-N is pruned on every insert, never materializing all rows"
    (let [n 10
          spec {:key-col "k" :element-col "el" :value-col "v" :n n
                :attr-name :top :entity-type "e"}
          ;; 2 keys × 50 distinct elements each = 100 rows, far more than N per key
          rows (for [k ["e1" "e2"]
                     i (range 50)]
                 {"k" k "el" (str k "-" i) "v" i})
          {:keys [distinct-keys peak-acc-entries concept-drafts]} (ca/stream-aggregate spec rows)]
      (is (= 2 distinct-keys))
      (is (= (* distinct-keys n) peak-acc-entries)
          "the accumulator is bounded to keys × N (100 rows never all held)")
      (is (<= peak-acc-entries (* distinct-keys n))
          "peak accumulator never exceeds the keys × N bound")
      (is (every? #(= n (count (get-in % [:attributes :top]))) concept-drafts)
          "each draft keeps exactly the top-N elements"))))

(deftest chunked-folding-equals-whole-seq-aggregate
  (testing "MT-5 — folding rows CHUNK-BY-CHUNK via aggregate-init/step/finalize
            (the way the streaming apply pages a huge container) yields the IDENTICAL
            result as stream-aggregate over the whole seq — so the streaming fix that
            never materializes the container is correct, not just bounded."
    (let [spec {:key-col "k" :element-col "el" :value-col "v" :n 5
                :filter-col "scale" :filter-val "IM" :attr-name :top :entity-type "occ"}
          rows (for [k ["a" "b" "c"]
                     i (range 40)
                     sc ["IM" "LV"]]                 ; two scales — the filter must hold across chunks
                 {"k" k "el" (str k "-" (mod i 12)) "v" (+ i (if (= sc "LV") 100 0)) "scale" sc})
          whole (ca/stream-aggregate spec rows)
          ;; page the SAME rows in 3 arbitrary chunks, threading the accumulator
          chunks (partition-all 37 rows)              ; uneven chunk boundaries
          paged (ca/aggregate-finalize
                 spec
                 (reduce (fn [st chunk] (reduce (fn [s r] (ca/aggregate-step spec s r)) st chunk))
                         (ca/aggregate-init) chunks))]
      (is (= (:rows-seen whole) (:rows-seen paged)) "rows-seen identical")
      (is (= (:rows-kept whole) (:rows-kept paged)) "rows-kept identical")
      (is (= (:rows-filtered whole) (:rows-filtered paged)) "off-scale filtered identical")
      (is (= (:distinct-keys whole) (:distinct-keys paged)))
      (is (= (into #{} (map (juxt :uri #(get-in % [:attributes :top]))) (:concept-drafts whole))
             (into #{} (map (juxt :uri #(get-in % [:attributes :top]))) (:concept-drafts paged)))
          "the per-key top-N drafts are identical whether folded whole or chunk-by-chunk"))))

(deftest stream-aggregate-empty-input-is-honest-zero
  (testing "empty input → honest zero counts, no crash, no fabricated drafts"
    (let [r (ca/stream-aggregate counts-spec [])]
      (is (= [] (:concept-drafts r)))
      (is (= 0 (:distinct-keys r)))
      (is (= 0 (:rows-seen r)))
      (is (= 0 (:rows-kept r)))
      (is (= 0 (:rows-errored r)))
      (is (= 0 (:rows-filtered r)))
      (is (= 0 (:peak-acc-entries r))))))

;; ===========================================================================
;; MT-6 Tracer 1 — COLLECT (list) mode: a spec with NO :value-col collects the
;; element values into a per-key FLAT LIST (no ranking), DISTINCT + BOUNDED at
;; max-list-size — the fix for repeating-key ATTRIBUTE tables (alternate titles,
;; task statements) that carry a descriptive element but NO numeric measure. The
;; existing top-N path (a spec WITH :value-col) is unchanged.
;; ===========================================================================

(deftest collect-mode-keeps-all-distinct-values-by-default-uncapped
  (testing "STREAM Slice 7 (THE PAYOFF) — a collect spec with NO :max-list-size keeps
            ALL distinct element values for a key (the >25 the old hard 25-cap DROPPED
            — the exact drop the user flagged), still DISTINCT-deduped (lossless); the
            accumulator witness reflects the real (>25) per-key size, honestly"
    (let [n-distinct 60                                  ; far more than the old 25 cap
          collect-spec {:key-col "k" :element-col "el"   ; NO :value-col, NO :max-list-size
                        :attr-name :altTitles :entity-type "occupation"}
          rows (concat
                ;; occ1 — 60 DISTINCT titles + a DUPLICATE (must collapse to distinct)
                (for [i (range n-distinct)] {"k" "occ1" "el" (str "title-" i)})
                [{"k" "occ1" "el" "title-0"}])          ; duplicate — deduped, not counted
          {:keys [concept-drafts distinct-keys peak-acc-entries list-truncated?]}
          (ca/stream-aggregate collect-spec rows)
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)
          lst (get-in by-uri ["occupation/occ1" :attributes :altTitles])]
      (is (= 1 distinct-keys) "one draft per entity KEY (not one per raw attribute row)")
      ;; THE PAYOFF — ALL 60 distinct values are kept by default (not capped at 25)
      (is (= n-distinct (count lst))
          "collect keeps ALL >25 distinct values by default — NOTHING dropped (the payoff)")
      ;; DISTINCT dedup is preserved (lossless — the duplicate collapsed)
      (is (= (count lst) (count (distinct lst)))
          "the list is DISTINCT — the duplicate collapsed (dedup is lossless, not a drop)")
      ;; the key value is still carried in :attributes (GC-1 identity recovery)
      (is (= "occ1" (get-in by-uri ["occupation/occ1" :attributes "k"]))
          "the key value is carried under the key column name for identity recovery")
      ;; witness honesty — the accumulator reflects the real (>25) size, not a capped 25
      (is (= n-distinct peak-acc-entries)
          "the accumulator witness reflects the real (>25) per-key size, honestly")
      (is (not list-truncated?)
          "no opt-in cap set → :list-truncated? is false (nothing was truncated)"))))

(deftest collect-mode-opt-in-max-list-size-caps-and-surfaces-truncation
  (testing "STREAM Slice 7 — an OPT-IN :max-list-size caps the per-key list at N AND
            surfaces :list-truncated? true (a cap that FIRES is never silent, #5)"
    (let [collect-spec {:key-col "k" :element-col "el" :max-list-size 25
                        :attr-name :altTitles :entity-type "occupation"}
          rows (for [i (range 60)] {"k" "occ1" "el" (str "title-" i)})
          {:keys [concept-drafts list-truncated? peak-acc-entries distinct-keys]}
          (ca/stream-aggregate collect-spec rows)
          lst (get-in (first concept-drafts) [:attributes :altTitles])]
      (is (= 25 (count lst)) "the opt-in :max-list-size 25 caps the list at 25")
      (is (true? list-truncated?)
          "the fired opt-in cap is SURFACED via :list-truncated? — never a silent drop")
      (is (<= peak-acc-entries (* distinct-keys 25))
          "the accumulator is bounded by the opt-in cap when it is set")
      ;; a cap NOT exceeded must NOT falsely flag truncation (honest)
      (let [{:keys [list-truncated?]}
            (ca/stream-aggregate (assoc collect-spec :max-list-size 100) rows)]
        (is (not list-truncated?)
            "a cap larger than the distinct count does NOT flag truncation (honest)")))))

(deftest top-n-keeps-all-ranked-pairs-by-default-no-n
  (testing "STREAM Slice 7 — a top-N spec (has :value-col) with NO :n keeps ALL ranked
            pairs, sorted by value DESC (order preserved, nothing dropped); the old
            default-of-10 no longer silently drops the 11th+ ranked element"
    (let [spec {:key-col "k" :element-col "el" :value-col "v"   ; NO :n
                :attr-name :top :entity-type "e"}
          ;; 30 distinct elements, distinct values → an unambiguous descending order
          rows (for [i (range 30)] {"k" "e1" "el" (str "el-" i) "v" i})
          {:keys [concept-drafts topn-truncated? peak-acc-entries]}
          (ca/stream-aggregate spec rows)
          top (get-in (first concept-drafts) [:attributes :top])]
      (is (= 30 (count top)) "ALL 30 ranked elements kept by default (not just top-10)")
      (is (= (mapv #(str "el-" %) (reverse (range 30))) top)
          "the list is sorted by value DESC — deterministic order preserved (#10)")
      (is (= 30 peak-acc-entries) "the accumulator witness reflects the real size")
      (is (not topn-truncated?) "no :n → :topn-truncated? false (nothing dropped)"))))

(deftest top-n-opt-in-n-caps-and-surfaces-truncation
  (testing "STREAM Slice 7 — an OPT-IN :n caps to the top-N (the existing behavior, now
            explicit) AND surfaces :topn-truncated? true when it fires"
    (let [spec {:key-col "k" :element-col "el" :value-col "v" :n 10
                :attr-name :top :entity-type "e"}
          rows (for [i (range 30)] {"k" "e1" "el" (str "el-" i) "v" i})
          {:keys [concept-drafts topn-truncated?]} (ca/stream-aggregate spec rows)
          top (get-in (first concept-drafts) [:attributes :top])]
      (is (= 10 (count top)) ":n 10 caps to the top-10 ranked elements")
      (is (= (mapv #(str "el-" %) (reverse (range 20 30))) top)
          "the top-10 are the 10 HIGHEST-valued elements, sorted desc")
      (is (true? topn-truncated?)
          "the fired :n cap is SURFACED via :topn-truncated? — never silent")
      ;; :n larger than the element count must NOT falsely flag truncation
      (let [{:keys [topn-truncated?]} (ca/stream-aggregate (assoc spec :n 100) rows)]
        (is (not topn-truncated?)
            "an :n above the element count does not flag truncation (honest)")))))

(deftest collect-mode-and-top-n-mode-coexist-on-value-col-presence
  (testing "the SAME public stream-aggregate keeps the existing TOP-N behavior for a
            spec WITH a :value-col (Skills-style) — collect mode fires ONLY when the
            :value-col is absent/blank (behavior-preserving for MT-3)"
    ;; WITH a value-col → top-N by value (the existing Tracer-1 fixture, unchanged)
    (let [{:keys [concept-drafts]} (ca/stream-aggregate top2-spec long-form-rows)
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)]
      (is (= ["A" "B"] (get-in by-uri ["occupation/occ1" :attributes :topElems]))
          "a spec WITH a value-col still ranks a top-N (unregressed)"))
    ;; a blank value-col string is treated as COLLECT mode (no ranking dimension)
    (let [blank-spec {:key-col "occ" :element-col "elem" :value-col "   "
                      :attr-name :topElems :entity-type "occupation"}
          {:keys [concept-drafts]} (ca/stream-aggregate blank-spec long-form-rows)
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)]
      (is (some? (get-in by-uri ["occupation/occ1" :attributes :topElems]))
          "a BLANK value-col falls to collect mode (a flat list, not a top-N)"))))

;; ===========================================================================
;; Tracer 3 — the shape-gated AUTHOR contract: aggregate for :long-form, per-row
;; otherwise. The gate + the author guidance are DETERMINISTIC (no LLM here).
;; ===========================================================================

(deftest long-form-gate-decides-aggregate-vs-per-row
  (testing "the aggregating path fires ONLY for a :long-form container with a valid
            spec — a non-long-form container keeps the per-row path (behavior-
            preserving) even if a spec is somehow present"
    (let [spec {:key-col "k" :element-col "el" :value-col "v"}]
      (is (true? (ca/aggregating-apply? {:shape :long-form} spec))
          ":long-form + valid spec → aggregate")
      (is (false? (ca/aggregating-apply? {:shape :entity} spec))
          "an :entity container keeps the per-row path (behavior-preserving)")
      (is (false? (ca/aggregating-apply? {:shape :wide-stats} spec))
          "a :wide-stats container keeps the per-row path")
      (is (false? (ca/aggregating-apply? {:shape :long-form} nil))
          ":long-form but NO spec → per-row (the author authored a transform instead)")
      (is (false? (ca/aggregating-apply? {:shape :long-form} {:key-col "k"}))
          ":long-form but an INCOMPLETE spec → per-row (not executable)"))))

(deftest long-form-shape-tag-is-tolerant-of-string-form
  (testing "the shape tag is recognized whether it arrives as a keyword OR its string
            form (child-tick blackboard / C1 fragility)"
    (is (true? (ca/long-form-container? {:shape :long-form})))
    (is (true? (ca/long-form-container? {:shape "long-form"})))
    (is (true? (ca/long-form-container? {:shape ":long-form"})))
    (is (false? (ca/long-form-container? {:shape :entity})))
    (is (false? (ca/long-form-container? {})))))

(deftest valid-aggregation-spec-requires-key-and-element-value-optional
  (testing "MT-6 — a spec needs a key + element column to be executable; the value
            column is OPTIONAL (present → top-N rank, absent → collect a flat list)"
    ;; top-N spec (with a value-col) is valid
    (is (true? (ca/valid-aggregation-spec? {:key-col "k" :element-col "el" :value-col "v"})))
    ;; MT-6 — a COLLECT spec (NO value-col) is now ALSO valid (key + element)
    (is (true? (ca/valid-aggregation-spec? {:key-col "k" :element-col "el"}))
        "a spec with key + element but NO value-col is a valid COLLECT spec")
    ;; still invalid: no element, no key, non-map
    (is (false? (ca/valid-aggregation-spec? {:key-col "k"}))
        "key alone (no element) is not executable")
    (is (false? (ca/valid-aggregation-spec? {:element-col "el"}))
        "element alone (no key) is not executable")
    (is (false? (ca/valid-aggregation-spec? {})))
    (is (false? (ca/valid-aggregation-spec? nil)))
    (is (false? (ca/valid-aggregation-spec? "a string")))))

;; ===========================================================================
;; MT-6 Tracer 2 — key-repeats? + the SAMPLE-DRIVEN gate. The aggregating path no
;; longer fires on the MT-1 :shape TAG (which the /prototype proved over-fires on
;; reference dictionaries); it fires when the AUTHOR emitted a valid spec AND the
;; spec's :key-col genuinely REPEATS in the sample. A near-unique key (1 row per
;; entity, e.g. Occupation Data) → do NOT aggregate → per-row. No sample → fall
;; back to the old long-form-container? tag (behavior-preserving).
;; ===========================================================================

(deftest key-repeats-detects-repeating-vs-near-unique-key
  (testing "key-repeats? is TRUE when the key column's sampled distinct-ratio is
            below the near-unique threshold (many rows per key), FALSE for a
            near-unique key (≈1 row per entity); empty/absent → false"
    ;; a repeating key: 9 rows, 3 distinct values → ratio 0.33 → repeats
    (let [repeating (for [k ["e1" "e2" "e3"] _ (range 3)] {"k" k "el" (str (rand))})]
      (is (true? (ca/key-repeats? repeating "k"))
          "a low distinct-ratio key REPEATS (many rows per entity)"))
    ;; a near-unique key: 9 rows, 9 distinct values → ratio 1.0 → NOT repeating
    (let [unique (for [i (range 9)] {"k" (str "e" i) "el" (str (rand))})]
      (is (false? (ca/key-repeats? unique "k"))
          "a near-unique key does NOT repeat (≈1 row per entity → per-row)"))
    ;; empty sample / missing key-col → false (can't establish repetition)
    (is (false? (ca/key-repeats? [] "k")))
    (is (false? (ca/key-repeats? [{"other" 1}] "k"))
        "a key column absent from every row → false")))

(deftest sample-driven-gate-aggregates-repeating-key-declines-near-unique
  (testing "with a sample present, aggregating-apply? fires on a REPEATING key +
            valid spec REGARDLESS of the MT-1 shape tag (subsuming :long-form), and
            DECLINES a near-unique key even for a :long-form-tagged container"
    (let [spec {:key-col "k" :element-col "el"}                ; a valid COLLECT spec
          repeating (for [k ["e1" "e2" "e3"] _ (range 3)] {"k" k "el" "x"})
          unique (for [i (range 9)] {"k" (str "e" i) "el" "x"})]
      ;; repeating key + valid spec → aggregate, even though shape is :entity (the
      ;; sample subsumes the shape tag)
      (is (true? (ca/aggregating-apply? {:shape :entity} spec repeating))
          "a repeating key aggregates even for a non-long-form shape (sample wins)")
      ;; near-unique key → per-row EVEN for a :long-form tag (Occupation Data case)
      (is (false? (ca/aggregating-apply? {:shape :long-form} spec unique))
          "a near-unique key declines to aggregate even for a :long-form container")
      ;; an invalid spec never aggregates, sample or not
      (is (false? (ca/aggregating-apply? {:shape :entity} {:key-col "k"} repeating))
          "an incomplete spec is not executable (no aggregation)"))))

(deftest sample-driven-gate-falls-back-to-long-form-tag-without-sample
  (testing "when NO sample is available, the gate FALLS BACK to the old
            long-form-container? tag (behavior-preserving — the 2-arity contract)"
    (let [spec {:key-col "k" :element-col "el" :value-col "v"}]
      ;; 2-arity (no sample) → falls back to the :shape tag
      (is (true? (ca/aggregating-apply? {:shape :long-form} spec))
          "no sample + :long-form tag → aggregate (fallback)")
      (is (false? (ca/aggregating-apply? {:shape :entity} spec))
          "no sample + non-long-form tag → per-row (fallback)")
      ;; an explicitly EMPTY sample also falls back to the tag (can't gate on it)
      (is (true? (ca/aggregating-apply? {:shape :long-form} spec []))
          "an empty sample falls back to the :shape tag"))))

(deftest parse-aggregation-spec-coerces-string-form-and-n
  (testing "an aggregation-spec arriving as an EDN STRING (C1) is parsed; :n coerces
            from a string; a blank filter-col becomes nil (no filter); garbage → nil"
    (let [p (ca/parse-aggregation-spec
             "{:key-col \"k\" :element-col \"el\" :value-col \"v\" :n \"5\" :filter-col \"\"}")]
      (is (= "k" (:key-col p)))
      (is (= 5 (:n p)) ":n string coerced to a number")
      (is (nil? (:filter-col p)) "a blank filter-col → nil (no scale filter)"))
    (let [p (ca/parse-aggregation-spec {:key-col "k" :element-col "el" :value-col "v" :n "7"})]
      (is (= 7 (:n p)) "a map passes through with :n coerced"))
    ;; STREAM Slice 7 — an opt-in :max-list-size string coerces like :n; absent → nil (unbounded)
    (let [p (ca/parse-aggregation-spec {:key-col "k" :element-col "el" :max-list-size "25"})]
      (is (= 25 (:max-list-size p)) "an opt-in :max-list-size string coerces to a number"))
    (let [p (ca/parse-aggregation-spec {:key-col "k" :element-col "el"})]
      (is (nil? (:max-list-size p)) "no :max-list-size → nil (UNBOUNDED default, keep everything)"))
    (is (nil? (ca/parse-aggregation-spec "not edn {{{"))
        "unparseable garbage → nil (never a fabricated spec, #5)")
    (is (nil? (ca/parse-aggregation-spec nil)))))

(deftest aggregation-author-guidance-is-sample-driven-and-domain-agnostic
  (testing "MT-6 — the AUTHOR guidance instructs SAMPLE INSPECTION (many rows per
            entity key → aggregate; one row per key → per-row), names BOTH the top-N
            (value-col) and the LIST-collect (no value-col) modes + the rollup-spec
            fields, insists on reasoning FIRST (#13), and bakes in NO domain
            column/entity term (#12)"
    (let [g (ca/aggregation-author-guidance)
          lg (str/lower-case g)]
      (is (str/includes? g "aggregation-spec") "names the spec write")
      (is (str/includes? g "key-col") "names the rollup key column field")
      (is (str/includes? g "element-col") "names the element label field")
      (is (str/includes? g "value-col") "names the OPTIONAL rank value column field")
      (is (str/includes? g "filter-col") "names the SCALE filter field")
      ;; MT-6 — the guidance is SAMPLE-DRIVEN (inspect the sample), not shape-tag-gated
      (is (str/includes? lg "sample") "instructs the AUTHOR to inspect the SAMPLE")
      ;; MT-6 — the LIST-collect mode (no value-col) must be described
      (is (str/includes? lg "list") "describes the LIST-collect mode (no value-col)")
      ;; #13 — reasoning first
      (is (str/includes? lg "reasoning") "insists on reasoning FIRST (#13)")
      ;; CONNECT-3b — match the domain TOKEN on WORD BOUNDARIES, not as a bare
      ;; substring: the association-mechanism term "association" legitimately contains
      ;; "soc" (as-soc-iation), which is NOT the O*NET SOC domain leak this guards.
      (doseq [leak ["o*net" "onet" "scale id" "occupation" "topskills" "importance" "cip" "soc"]]
        (is (not (re-find (re-pattern (str "\\b" (java.util.regex.Pattern/quote leak) "\\b")) lg))
            (str "the aggregation guidance must not bake in the vertical term: " leak))))))

;; ===========================================================================
;; Tracer 4 — the APPLY node routes on spec-vs-transform over a REAL csv stream.
;; A :long-form container + an aggregation-spec → per-KEY drafts (stream-aggregate);
;; a non-long-form container + a transform-source → per-ROW drafts (existing path
;; unchanged). REAL csv, REAL V19 stream-all — no mocked source.
;; ===========================================================================

(defn- write-long-form-csv!
  "Write a temp long-form CSV: entity,element,value,scale. The OFF-scale (LV) rows
   carry a HIGHER value than the in-scope (IM) rows so a mixed-scale top-N would be
   visibly wrong. Returns the absolute path."
  []
  (let [f (java.io.File/createTempFile "mt3-longform" ".csv")]
    (.deleteOnExit f)
    (with-open [w (io/writer f)]
      (.write w "entity,element,value,scale\n")
      (.write w "occ1,A,5,IM\n")
      (.write w "occ1,B,4,IM\n")
      (.write w "occ1,C,3,IM\n")
      (.write w "occ1,E,9,LV\n")   ; off-scale — highest value, must be excluded
      (.write w "occ2,X,7,IM\n")
      (.write w "occ2,Y,6,IM\n")
      (.write w "occ2,Z,8,LV\n"))  ; off-scale — must be excluded
    (.getAbsolutePath f)))

(def ^:private per-row-transform
  ;; one concept-draft per RAW row (the existing per-row path) — csv rows are
  ;; string-keyed; dual-key lets keyword reads resolve too.
  "(fn [row]
     {:concept-drafts
      [{:uri (str \"row:\" (get row \"entity\") \"-\" (get row \"element\"))
        :label (str (get row \"element\"))
        :scope :custom}]
      :relationship-drafts []})")

(deftest apply-routes-to-aggregate-for-long-form-spec
  (testing "a :long-form container + a valid aggregation-spec → the APPLY node
            produces ONE per-KEY draft (top-N by the in-scope value), NOT per-row"
    (let [path (write-long-form-csv!)
          spec {:key-col "entity" :element-col "element" :value-col "value"
                :filter-col "scale" :filter-val "IM"
                :n 10 :attr-name :topElements :entity-type "occupation"}
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:shape :long-form}
                         :aggregation-spec spec}})
          drafts (:concept-drafts out)
          by-uri (into {} (map (juxt :uri identity)) drafts)]
      ;; 7 raw rows → 2 per-KEY drafts (NOT 7 per-row fragments)
      (is (= 2 (count drafts))
          "one draft per entity KEY (aggregated), not one per raw measurement row")
      (is (= ["A" "B" "C"] (get-in by-uri ["occupation/occ1" :attributes :topElements]))
          "occ1 top elements ranked by in-scope value")
      (is (= ["X" "Y"] (get-in by-uri ["occupation/occ2" :attributes :topElements]))
          "occ2 top elements ranked by in-scope value")
      (let [all-elems (mapcat #(get-in % [:attributes :topElements]) drafts)]
        (is (not (some #{"E" "Z"} all-elems))
            "the off-scale (LV) rows are excluded from the real-stream aggregation"))
      ;; the extraction report reflects the aggregating apply honestly
      (is (map? (:extraction-report out)))
      (is (= 2 (:concept-count out)) "the flat concept-count (resilience gate key) is per-key"))))

(deftest aggregating-apply-streams-full-coverage-not-the-per-row-window-cap
  (testing "the aggregating path is BOUNDED-MEMORY, so it must stream the WHOLE
            container — it does NOT inherit the low per-row :max-windows cap (which
            bounds heap DRAFT-VOLUME and would TRUNCATE a large long-form → missing
            keys / wrong top-N); an explicit HIGHER caller override still wins"
    (let [spec {:key-col "entity" :element-col "element" :value-col "value"
                :n 5 :attr-name :topElements :entity-type "occupation"}
          agg-windows (atom nil)
          perrow-windows (atom nil)]
      ;; :long-form + valid spec → aggregating branch; capture the max-windows it got.
      (with-redefs [rlm/apply-aggregation-transform!
                    (fn [{:keys [max-windows]}]
                      (reset! agg-windows max-windows)
                      {:selector "s" :rows-streamed 0 :rows-ok 0 :rows-errored 0
                       :windows 0 :concept-drafts [] :relationship-drafts []})]
        (extract/apply-transform-for-container-code
         {:inputs {:source {:type :csv :path "/x"} :container {:shape :long-form}
                   :aggregation-spec spec :max-windows 3}}))          ; a LOW per-row cap
      (is (= ca/full-coverage-max-windows @agg-windows)
          "a low per-row cap (3) is IGNORED — the aggregating path streams to full coverage")
      ;; an explicit HIGHER override wins (max with the full-coverage floor).
      (with-redefs [rlm/apply-aggregation-transform!
                    (fn [{:keys [max-windows]}] (reset! agg-windows max-windows)
                      {:selector "s" :rows-streamed 0 :rows-ok 0 :rows-errored 0
                       :windows 0 :concept-drafts [] :relationship-drafts []})]
        (extract/apply-transform-for-container-code
         {:inputs {:source {:type :csv :path "/x"} :container {:shape :long-form}
                   :aggregation-spec spec :max-windows (* 10 ca/full-coverage-max-windows)}}))
      (is (= (* 10 ca/full-coverage-max-windows) @agg-windows)
          "an explicit higher caller cap still wins (max, not a hard override)")
      ;; the PER-ROW branch is unaffected — it still honors the caller's cap verbatim.
      (with-redefs [rlm/apply-extraction-transform!
                    (fn [{:keys [max-windows]}] (reset! perrow-windows max-windows)
                      {:selector "s" :rows-streamed 0 :rows-ok 0 :rows-errored 0
                       :windows 0 :concept-drafts [] :relationship-drafts []})]
        (extract/apply-transform-for-container-code
         {:inputs {:source {:type :csv :path "/x"} :container {:shape :entity}
                   :transform-source per-row-transform :max-windows 3}}))
      (is (= 3 @perrow-windows)
          "the per-row path still honors the caller's window cap verbatim (unregressed)"))))

(deftest apply-routes-to-per-row-for-non-long-form-transform
  (testing "a non-long-form container + a transform-source → the APPLY node produces
            per-ROW drafts (the existing path is UNCHANGED / behavior-preserving)"
    (let [path (write-long-form-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:shape :entity}
                         :transform-source per-row-transform}})
          drafts (:concept-drafts out)]
      ;; 7 raw rows → 7 per-row drafts (per-row path, no aggregation)
      (is (= 7 (count drafts))
          "one draft per RAW row (the existing per-row path is unchanged)")
      (is (every? #(str/starts-with? (str (:uri %)) "row:") drafts)
          "the drafts are the per-row transform's output (not aggregated)"))))

;; ===========================================================================
;; MT-6 Tracer 3 — the SAMPLE-DRIVEN APPLY seam over a REAL csv stream. A
;; repeating-key sample + a NO-VALUE (collect) spec → per-KEY LIST drafts (bounded,
;; distinct); a near-unique-key sample + a spec → the gate DECLINES → per-ROW drafts
;; (even for a :long-form tag — the sample subsumes the tag); a repeating-key sample
;; + a value-spec → top-N (unregressed). REAL csv, REAL V19 stream-all.
;; ===========================================================================

(defn- write-attribute-csv!
  "Write a temp repeating-key ATTRIBUTE CSV: entity,attr. The entity key REPEATS
   (two occupations, several descriptive attribute values each) with NO numeric
   value column — the MT-6 alternate-titles/task-statements shape. Returns the path."
  []
  (let [f (java.io.File/createTempFile "mt6-attribute" ".csv")]
    (.deleteOnExit f)
    (with-open [w (io/writer f)]
      (.write w "entity,attr\n")
      (.write w "occ1,Analyst\n")
      (.write w "occ1,Consultant\n")
      (.write w "occ1,Analyst\n")     ; duplicate — must collapse in the list
      (.write w "occ1,Advisor\n")
      (.write w "occ2,Teacher\n")
      (.write w "occ2,Instructor\n"))
    (.getAbsolutePath f)))

(deftest apply-collects-per-key-list-for-repeating-key-no-value-spec
  (testing "a repeating-key sample + a NO-VALUE (collect) spec → the APPLY node
            produces ONE per-KEY draft carrying a DISTINCT flat list of the element
            values (NOT one node per raw attribute row — the MT-6 OOM fix)"
    (let [path (write-attribute-csv!)
          ;; a valid COLLECT spec — NO :value-col
          spec {:key-col "entity" :element-col "attr"
                :attr-name :altTitles :entity-type "occupation"}
          ;; a sample where the key REPEATS (2 distinct keys across 6 rows)
          sample [{"entity" "occ1" "attr" "Analyst"}
                  {"entity" "occ1" "attr" "Consultant"}
                  {"entity" "occ2" "attr" "Teacher"}
                  {"entity" "occ2" "attr" "Instructor"}]
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:shape :entity}     ; NOT long-form — the sample decides
                         :aggregation-spec spec
                         :sample-rows sample}})
          drafts (:concept-drafts out)
          by-uri (into {} (map (juxt :uri identity)) drafts)]
      ;; 6 raw rows → 2 per-KEY drafts (NOT 6 per-row fragments)
      (is (= 2 (count drafts))
          "one draft per entity KEY (list-collected), not one per raw attribute row")
      ;; occ1's list is DISTINCT (the duplicate 'Analyst' collapsed)
      (is (= ["Analyst" "Consultant" "Advisor"]
             (get-in by-uri ["occupation/occ1" :attributes :altTitles]))
          "occ1's flat list is the distinct element values (no ranking)")
      (is (= ["Teacher" "Instructor"]
             (get-in by-uri ["occupation/occ2" :attributes :altTitles]))
          "occ2's flat list is its distinct element values")
      (is (= 2 (:concept-count out))
          "the flat concept-count is per-key (the aggregating apply fired)"))))

(deftest apply-declines-aggregate-for-near-unique-key-even-when-long-form-tagged
  (testing "a NEAR-UNIQUE key sample + a valid spec → the gate DECLINES to aggregate
            (per-row) EVEN when the container is :long-form-tagged — the sample
            subsumes the (sometimes-wrong) MT-1 shape tag (the Occupation Data case)"
    (let [path (write-long-form-csv!)   ; the per-row transform emits 7 row drafts
          spec {:key-col "entity" :element-col "element" :value-col "value"
                :filter-col "scale" :filter-val "IM"
                :n 10 :attr-name :topElements :entity-type "occupation"}
          ;; a sample where the key is NEAR-UNIQUE (1 row per key value)
          unique-sample (for [i (range 8)] {"entity" (str "e" i) "element" "x" "value" "1"})
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:shape :long-form}       ; a long-form TAG …
                         :aggregation-spec spec               ; … and a valid spec …
                         :transform-source per-row-transform  ; … but a near-unique sample
                         :sample-rows unique-sample}})
          drafts (:concept-drafts out)]
      ;; the gate declined → the PER-ROW path ran → 7 per-row drafts, not 2 aggregated
      (is (= 7 (count drafts))
          "a near-unique key keeps the per-row path even with a spec + long-form tag")
      (is (every? #(str/starts-with? (str (:uri %)) "row:") drafts)
          "the drafts are the per-row transform's output (the gate declined to aggregate)"))))

(deftest apply-top-n-unregressed-for-repeating-key-value-spec
  (testing "a repeating-key sample + a VALUE spec → the existing top-N aggregation
            still fires (Skills-style) — sample-driven gating does not regress MT-3"
    (let [path (write-long-form-csv!)
          spec {:key-col "entity" :element-col "element" :value-col "value"
                :filter-col "scale" :filter-val "IM"
                :n 10 :attr-name :topElements :entity-type "occupation"}
          ;; a repeating-key sample (occ1/occ2 across several rows)
          rep-sample [{"entity" "occ1" "element" "A" "value" "5" "scale" "IM"}
                      {"entity" "occ1" "element" "B" "value" "4" "scale" "IM"}
                      {"entity" "occ2" "element" "X" "value" "7" "scale" "IM"}
                      {"entity" "occ2" "element" "Y" "value" "6" "scale" "IM"}]
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:shape :entity}   ; not long-form — the sample decides
                         :aggregation-spec spec
                         :sample-rows rep-sample}})
          drafts (:concept-drafts out)
          by-uri (into {} (map (juxt :uri identity)) drafts)]
      (is (= 2 (count drafts)) "top-N still yields one per-key draft")
      (is (= ["A" "B" "C"] (get-in by-uri ["occupation/occ1" :attributes :topElements]))
          "occ1 top elements ranked by the in-scope value (top-N unregressed)")
      (let [all-elems (mapcat #(get-in % [:attributes :topElements]) drafts)]
        (is (not (some #{"E" "Z"} all-elems))
            "the off-scale rows are still excluded (the scale filter is unregressed)")))))

(deftest apply-per-row-unchanged-when-long-form-but-no-spec
  (testing "a :long-form container but NO aggregation-spec (the author authored a
            transform instead) → the per-row path still runs (the deterministic gate
            needs BOTH shape AND a valid spec)"
    (let [path (write-long-form-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:shape :long-form}
                         :transform-source per-row-transform}})]
      (is (= 7 (count (:concept-drafts out)))
          "no spec → per-row path (a long-form tag alone does not divert)"))))
