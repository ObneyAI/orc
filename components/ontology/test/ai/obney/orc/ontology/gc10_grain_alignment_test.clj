(ns ai.obney.orc.ontology.gc10-grain-alignment-test
  "GC-10 — cross-source key-grain alignment.

   FIX A — degrade coercion. `entity-type-keying-fields` reduces over
   `(:entity-types model-spec)`. When the `:llm` Model node's `:entity-types`
   intermittently arrives as an un-parsed EDN STRING (the same C1 fragility GC-6's
   `normalize-vocabulary` defends), the reduce runs over the STRING's CHARACTERS →
   an empty type-index → 100% of that source's drafts degrade to free-form URIs.
   `normalize-model-spec` (mirroring GC-6's `normalize-vocabulary`) coerces a
   string `:entity-types` back to a vector of maps before the reduce. Already-parsed
   vectors are unchanged (behavior-preserving).

   FIX B2 — family↔detail SKOS hierarchy. For two SAME-canonical-type concepts
   whose normalized key tails are in a strict PREFIX-CONTAINMENT relation at a
   code-system SEPARATOR boundary (e.g. `x/01` is a prefix of `x/01.0407` at `.`),
   emit `skos:broader` (family→detail wrong dir) / `skos:narrower` so the family
   node bridges to the detail node. Domain-agnostic — purely structural
   prefix-at-separator over the normalized key tails (NO baked CIP/SOC). Bounded
   (cap per-prefix fan-out + honest report, like GC-2).

   These cycles exercise the PURE functions through their public interfaces — NO
   Grain, NO LLM. Synthetic, NO real-world field names (Discipline 12). The
   connectivity traversal lives in the eb12 driver and is tested in
   `gc10_hierarchy_traversal_test.clj`."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]))

;; =============================================================================
;; FIX A — model-spec degrade coercion.
;;
;; The PARSED-vector form of the spec (the happy path), and the STRING form (the
;; C1 parse failure the Model node intermittently emits). The string is the EXACT
;; EDN print of the vector — what an un-parsed `:llm` write looks like.
;; =============================================================================

(def ^:private parsed-entity-types
  [{:type "Alpha Thing" :uri-keying-fields ["ALPHA_K"]
    :grain-strategy :canonical-row-filter}])

(def ^:private string-form-spec
  ;; :entity-types arrives as an un-parsed EDN STRING (the C1 failure).
  {:entity-types (pr-str parsed-entity-types)})

(def ^:private parsed-form-spec
  {:entity-types parsed-entity-types})

;; A draft of "Alpha Thing" keyed by ALPHA_K=42 — canonicalizes IFF the type-index
;; built from :entity-types is non-empty.
(def ^:private one-draft
  [{:uri "alpha-thing:42" :label "Alpha 42"
    :entity-type "Alpha Thing"
    :attributes {"ALPHA_K" "42"}}])

(deftest fix-a-string-form-entity-types-canonicalizes-zero-degraded
  (testing "a STRING-form :entity-types (C1 parse failure) is coerced back to data
            so entity-type-keying-fields builds a NON-EMPTY index → the draft
            canonicalizes (0 degraded), NOT degraded to its free-form URI"
    (let [result (extract/canonicalize-drafts string-form-spec one-draft [])
          out-uri (:uri (first (:concept-drafts result)))]
      (is (= 0 (count (:degraded result)))
          "with the string coerced, the draft canonicalizes — 0 degraded")
      (is (= "alphathing/42" out-uri)
          "the draft mints its canonical URI (not the free-form alpha-thing:42)")
      (is (not= "alpha-thing:42" out-uri)
          "the free-form :uri must NOT survive as identity"))))

(deftest fix-a-parsed-form-entity-types-unchanged-behavior-preserving
  (testing "an ALREADY-PARSED vector :entity-types canonicalizes EXACTLY as before
            the coercion (behavior-preserving)"
    (let [result (extract/canonicalize-drafts parsed-form-spec one-draft [])
          out-uri (:uri (first (:concept-drafts result)))]
      (is (= 0 (count (:degraded result)))
          "the parsed form still canonicalizes — 0 degraded")
      (is (= "alphathing/42" out-uri)
          "the parsed form mints the SAME canonical URI"))))

(deftest fix-a-normalize-model-spec-coerces-string-to-data
  (testing "normalize-model-spec parses a STRING :entity-types into a vector of
            maps; an already-parsed vector is returned unchanged; garbage → []"
    (is (= parsed-entity-types
           (:entity-types (extract/normalize-model-spec string-form-spec)))
        "a string :entity-types is parsed back to the vector of maps")
    (is (= parsed-entity-types
           (:entity-types (extract/normalize-model-spec parsed-form-spec)))
        "an already-parsed vector is unchanged")
    (is (= [] (:entity-types (extract/normalize-model-spec {:entity-types "{not edn"})))
        "an unparseable string degrades honestly to []")
    (is (= [] (:entity-types (extract/normalize-model-spec {:entity-types 42})))
        "a non-string non-vector degrades honestly to []")
    (is (= [{:type "Alpha Thing" :uri-keying-fields ["ALPHA_K"]}]
           (:entity-types
            (extract/normalize-model-spec
             {:entity-types (pr-str [{:type "Alpha Thing" :uri-keying-fields ["ALPHA_K"]}
                                     "malformed-non-map-entry"])})))
        "malformed non-map entries are dropped honestly, well-formed kept")))

;; =============================================================================
;; FIX B2 — family↔detail SKOS hierarchy (structural prefix-at-separator).
;;
;; Domain-agnostic synthetic keys (NO CIP/SOC): a "family" key `x/01` is a strict
;; PREFIX of a "detail" key `x/01.0407` at the `.` separator boundary → the family
;; bridges to the detail via skos:narrower (family narrower detail; detail broader
;; family). Distinct keys with NO prefix relation → NO edge.
;; =============================================================================

(defn- by-predicate [drafts pred]
  (filter #(= pred (:predicate %)) drafts))

(deftest fix-b2-prefix-at-separator-emits-skos-narrower-family-to-detail
  (testing "two SAME-type concepts whose normalized key tails are in strict
            PREFIX-CONTAINMENT at a separator boundary (`x/01` ⊂ `x/01.0407`) emit a
            skos:narrower edge family→detail (so the read-model sets family:narrower
            + detail:broader reciprocally)"
    (let [concepts [{:uri "fieldofstudy/01"      :label "family 01"  :entity-type "Field"}
                    {:uri "fieldofstudy/01.0407" :label "detail"     :entity-type "Field"}]
          result (extract/hierarchy-relationship-drafts concepts)
          edges (:relationship-drafts result)
          narrower (by-predicate edges "skos:narrower")]
      (is (= 1 (count narrower))
          "exactly ONE skos:narrower edge for the single family→detail prefix pair")
      (is (= {:source-uri "fieldofstudy/01" :target-uri "fieldofstudy/01.0407"
              :predicate "skos:narrower"}
             (select-keys (first narrower) [:source-uri :target-uri :predicate]))
          "the family (shorter prefix) is the SOURCE of skos:narrower → the detail
           (longer key) is its narrower target"))))

(deftest fix-b2-distinct-non-prefix-keys-emit-no-edge
  (testing "two SAME-type concepts whose key tails are DISTINCT with NO prefix
            relation → NO hierarchy edge (no spurious bridging)"
    (let [concepts [{:uri "fieldofstudy/01.0407" :entity-type "Field"}
                    {:uri "fieldofstudy/09.0101" :entity-type "Field"}]
          result (extract/hierarchy-relationship-drafts concepts)]
      (is (empty? (:relationship-drafts result))
          "distinct non-prefix keys yield no hierarchy edges"))))

(deftest fix-b2-prefix-must-be-at-separator-boundary-not-arbitrary-substring
  (testing "a key that is a leading SUBSTRING but NOT at a separator boundary is NOT
            a prefix-containment (e.g. `x/010` is NOT broader of `x/0104` — `010`
            is not a separator-delimited prefix of `0104`)"
    (let [concepts [{:uri "fieldofstudy/010"  :entity-type "Field"}
                    {:uri "fieldofstudy/0104" :entity-type "Field"}]
          result (extract/hierarchy-relationship-drafts concepts)]
      (is (empty? (:relationship-drafts result))
          "a non-separator leading substring is NOT a hierarchy edge"))))

(deftest fix-b2-different-types-do-not-relate
  (testing "two concepts whose key tails ARE in prefix containment but whose TYPES
            differ are NOT related (hierarchy is within a single canonical type)"
    (let [concepts [{:uri "fieldofstudy/01"   :entity-type "Field"}
                    {:uri "occupation/01.0407" :entity-type "Occupation"}]
          result (extract/hierarchy-relationship-drafts concepts)]
      (is (empty? (:relationship-drafts result))
          "cross-type prefix keys do NOT relate — hierarchy is within one type"))))

(deftest fix-b2-longest-prefix-at-separator-parent-bridges-family-to-detail
  (testing "for keys `01` (family), `01.04` and `01.0407` (details): each detail's
            PARENT is the LONGEST same-type concept that is its prefix-AT-SEPARATOR.
            `01` is a prefix-at-separator of BOTH `01.04` and `01.0407` (the `.`
            boundary); `01.04` is NOT a separator-boundary prefix of `01.0407` (the
            next char `0` is alphanumeric). So the family `01` bridges DIRECTLY to
            BOTH details — the program→field(family)→field(detail)→occupation chain
            connects. The child does NOT also get a redundant grandparent edge."
    (let [concepts [{:uri "fieldofstudy/01"      :entity-type "Field"}
                    {:uri "fieldofstudy/01.04"   :entity-type "Field"}
                    {:uri "fieldofstudy/01.0407" :entity-type "Field"}]
          result (extract/hierarchy-relationship-drafts concepts)
          edges (set (map #(select-keys % [:source-uri :target-uri :predicate])
                          (:relationship-drafts result)))]
      (is (contains? edges {:source-uri "fieldofstudy/01" :target-uri "fieldofstudy/01.04"
                            :predicate "skos:narrower"})
          "the family 01 bridges to its detail 01.04")
      (is (contains? edges {:source-uri "fieldofstudy/01" :target-uri "fieldofstudy/01.0407"
                            :predicate "skos:narrower"})
          "the family 01 bridges to its detail 01.0407 (the family→detail hop the
           GC-5 chain needs)")
      ;; exactly TWO edges (the family to each of its two details) — no
      ;; double-counting, no spurious 01.04→01.0407 (not a separator-boundary prefix).
      (is (= 2 (count edges))
          "exactly the two family→detail edges; 01.04 is NOT a parent of 01.0407
           (its next char is alphanumeric, not a separator boundary)"))))

(deftest fix-b2-bounded-fan-out-honest-report
  (testing "a family with MANY detail children fans out BOUNDED — past the cap the
            excess edges are DROPPED and surfaced in :truncated-relations honestly
            (never a silent top-N), like GC-2"
    (let [family {:uri "fieldofstudy/01" :entity-type "Field"}
          children (for [i (range 1 31)]  ; 30 immediate children
                     {:uri (format "fieldofstudy/01.%02d" i) :entity-type "Field"})
          concepts (cons family children)
          cap 10
          result (extract/hierarchy-relationship-drafts concepts cap)
          narrower-from-family (->> (:relationship-drafts result)
                                    (filter #(and (= "skos:narrower" (:predicate %))
                                                  (= "fieldofstudy/01" (:source-uri %)))))]
      (is (= cap (count narrower-from-family))
          "the family's narrower fan-out is capped at the per-prefix cap")
      (is (seq (:truncated-relations result))
          "the dropped excess is surfaced in :truncated-relations (honest, not silent)")
      (is (= (- 30 cap)
             (reduce + 0 (map :dropped-edges (:truncated-relations result))))
          "the truncation report accounts for EVERY dropped edge (20 of 30)"))))
