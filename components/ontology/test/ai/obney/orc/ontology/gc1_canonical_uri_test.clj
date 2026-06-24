(ns ai.obney.orc.ontology.gc1-canonical-uri-test
  "GC-1 — canonical URI minting (the keystone).

   The per-container AUTHOR writes its OWN free-form `:uri` for each concept-draft,
   so two containers can mint DIFFERENT URIs for the SAME real entity (e.g. one
   keys `entity-alpha:42` and another keys `alpha/42` for the same alpha #42).
   EB5 reconcile merges by canonical `:uri`, so those never collapse → the graph
   fragments and cross-container edges strand.

   GC-1 stops trusting the AUTHOR's free `:uri` for IDENTITY: each concept-draft
   carries an explicit `:entity-type` (the model-spec `:type` it is), and a
   DETERMINISTIC post-step looks up that type's `:uri-keying-fields`, recovers the
   field VALUES from the draft's `:attributes` (REUSING MC-6's
   `normalize-key-name` / `normalize-value` — NOT parsing the old URI), and mints a
   canonical URI `<normalized-entity-type>/<normalized-key-values>`. It builds an
   old→canonical rewrite map from the concept-drafts and applies it to
   concept-draft `:uri` AND relationship-draft `:source-uri`/`:target-uri` together
   (no dangling edges).

   These cycles exercise the PURE canonicalizer (`canonicalize-drafts`) through its
   public interface — NO Grain, NO LLM. Synthetic, NO real-world field names
   (Discipline 12). The real-Grain + real-LLM integration read-back lives in the
   live-verify driver (`development/src/gc1_canonical_uri_live_verify.clj`)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]))

;; A synthetic model-spec — entity-type "Alpha Thing" keyed by ONE field "ALPHA_K".
;; NO real-world field names (Discipline 12).
(def ^:private single-key-spec
  {:entity-types
   [{:type "Alpha Thing"
     :uri-keying-fields ["ALPHA_K"]
     :grain-strategy :canonical-row-filter}]})

;; =============================================================================
;; Cycle 1 — same entity (same entity-type + same key value, different free-form
;; :uri) → IDENTICAL canonical URI.
;; =============================================================================

(deftest cycle1-same-entity-different-free-uri-collapses-to-identical-canonical-uri
  (testing "two drafts of the SAME entity-type with the SAME key VALUE in
            :attributes but DIFFERENT free-form :uri → BOTH rewritten to the
            IDENTICAL canonical URI (so EB5 reconcile collapses them)"
    (let [;; two "containers" mint DIFFERENT free-form URIs for the SAME alpha #42
          drafts [{:uri "alpha-thing:42" :label "Alpha 42 (container A)"
                   :entity-type "Alpha Thing"
                   :attributes {"ALPHA_K" "42"}}
                  {:uri "a/0042" :label "Alpha 42 (container B)"
                   :entity-type "Alpha Thing"
                   :attributes {"ALPHA_K" "42"}}]
          result (extract/canonicalize-drafts single-key-spec drafts [])
          out-uris (map :uri (:concept-drafts result))]
      (is (= 1 (count (distinct out-uris)))
          "both drafts must mint the SAME canonical URI (one distinct value)")
      (is (apply = out-uris)
          "the two canonical URIs must be byte-identical")
      (is (every? #(not= "alpha-thing:42" %) out-uris)
          "the free-form :uri must NOT survive as identity")
      (is (every? #(not= "a/0042" %) out-uris)
          "the other free-form :uri must NOT survive as identity"))))

;; =============================================================================
;; Cycle 2 — relationship-draft endpoints rewritten to the concepts' canonical
;; URIs (no dangling edges).
;; =============================================================================

(def ^:private two-type-spec
  {:entity-types
   [{:type "Alpha Thing"
     :uri-keying-fields ["ALPHA_K"]
     :grain-strategy :canonical-row-filter}
    {:type "Beta Thing"
     :uri-keying-fields ["BETA_K"]
     :grain-strategy :canonical-row-filter}]})

(deftest cycle2-relationship-endpoints-rewritten-to-canonical-uris-no-dangling
  (testing "a relationship-draft whose :source-uri/:target-uri are the OLD free-form
            URIs is rewritten so its endpoints equal the concepts' canonical URIs
            (no dangling edge)"
    (let [concept-drafts [{:uri "alpha-thing:7" :label "Alpha 7"
                           :entity-type "Alpha Thing"
                           :attributes {"ALPHA_K" "7"}}
                          {:uri "beta-thing:99" :label "Beta 99"
                           :entity-type "Beta Thing"
                           :attributes {"BETA_K" "99"}}]
          rel-drafts [{:source-uri "alpha-thing:7"
                       :target-uri "beta-thing:99"
                       :predicate "relates-to"}]
          result (extract/canonicalize-drafts two-type-spec concept-drafts rel-drafts)
          canon-uris (set (map :uri (:concept-drafts result)))
          edge (first (:relationship-drafts result))]
      (is (contains? canon-uris (:source-uri edge))
          "the edge :source-uri must resolve to a concept's canonical URI")
      (is (contains? canon-uris (:target-uri edge))
          "the edge :target-uri must resolve to a concept's canonical URI")
      (is (not= "alpha-thing:7" (:source-uri edge))
          "the edge :source-uri must NOT keep the old free-form URI")
      (is (not= "beta-thing:99" (:target-uri edge))
          "the edge :target-uri must NOT keep the old free-form URI")
      (is (= "relates-to" (:predicate edge))
          "the predicate is preserved unchanged"))))

;; =============================================================================
;; Cycle 3 — multi-field key → canonical URI composes BOTH fields deterministically
;; (stable order/separator); two drafts agreeing on both collapse to one URI.
;; =============================================================================

(def ^:private multi-key-spec
  {:entity-types
   [{:type "Composite Thing"
     ;; TWO keying fields — the canonical URI must compose BOTH, in spec order.
     :uri-keying-fields ["K_ONE" "K_TWO"]
     :grain-strategy :canonical-row-filter}]})

(deftest cycle3-multi-field-key-composes-both-deterministically-and-collapses
  (testing "an entity keyed by TWO fields → the canonical URI composes BOTH in a
            stable order/separator; two drafts agreeing on both collapse to ONE URI"
    (let [drafts [{:uri "comp:freeA" :label "Composite (container A)"
                   :entity-type "Composite Thing"
                   :attributes {"K_ONE" "abc" "K_TWO" "xyz"}}
                  {:uri "different/freeB" :label "Composite (container B)"
                   :entity-type "Composite Thing"
                   :attributes {"K_ONE" "abc" "K_TWO" "xyz"}}]
          result (extract/canonicalize-drafts multi-key-spec drafts [])
          out-uris (map :uri (:concept-drafts result))
          canon (first out-uris)]
      (is (= 1 (count (distinct out-uris)))
          "two drafts agreeing on BOTH key fields collapse to ONE canonical URI")
      (is (re-find #"abc" canon)
          "the canonical URI must contain the FIRST key field's value")
      (is (re-find #"xyz" canon)
          "the canonical URI must contain the SECOND key field's value")
      (is (< (.indexOf ^String canon "abc") (.indexOf ^String canon "xyz"))
          "the fields compose in the spec's declared ORDER (K_ONE before K_TWO)"))))

(deftest cycle3-multi-field-key-distinguishes-when-second-field-differs
  (testing "two drafts sharing the FIRST key but differing on the SECOND do NOT
            collapse — the multi-field key must use BOTH fields, not just one"
    (let [drafts [{:uri "comp:a" :label "A"
                   :entity-type "Composite Thing"
                   :attributes {"K_ONE" "abc" "K_TWO" "one"}}
                  {:uri "comp:b" :label "B"
                   :entity-type "Composite Thing"
                   :attributes {"K_ONE" "abc" "K_TWO" "two"}}]
          result (extract/canonicalize-drafts multi-key-spec drafts [])
          out-uris (map :uri (:concept-drafts result))]
      (is (= 2 (count (distinct out-uris)))
          "differing on the SECOND key field must yield DISTINCT canonical URIs"))))

;; =============================================================================
;; Cycle 4 — distinct entities stay distinct: different key VALUES → different
;; canonical URIs (no over-merge).
;; =============================================================================

(deftest cycle4-distinct-key-values-stay-distinct-no-over-merge
  (testing "two drafts of the SAME entity-type with DIFFERENT key VALUES → DISTINCT
            canonical URIs (the canonicalizer keys on the VALUE, never collapsing
            unrelated entities)"
    (let [drafts [{:uri "alpha-thing:1" :label "Alpha 1"
                   :entity-type "Alpha Thing"
                   :attributes {"ALPHA_K" "1"}}
                  {:uri "alpha-thing:2" :label "Alpha 2"
                   :entity-type "Alpha Thing"
                   :attributes {"ALPHA_K" "2"}}]
          result (extract/canonicalize-drafts single-key-spec drafts [])
          out-uris (map :uri (:concept-drafts result))]
      (is (= 2 (count (distinct out-uris)))
          "two DISTINCT key values must yield two DISTINCT canonical URIs")
      (is (apply not= out-uris)
          "the two canonical URIs must differ"))))

;; =============================================================================
;; Cycle 5 — honest degrade: a draft with NO :entity-type (or an :entity-type not
;; in the model-spec, or unrecoverable keying values) keeps its ORIGINAL :uri,
;; is surfaced, and is NEVER given a fabricated canonical URI.
;; =============================================================================

(deftest cycle5-missing-or-unknown-entity-type-degrades-honestly
  (testing "a draft with NO :entity-type, an UNKNOWN :entity-type, or UNRECOVERABLE
            keying values keeps its original :uri, is surfaced in :degraded, and is
            never fabricated; a well-formed sibling still canonicalizes"
    (let [drafts [;; (a) no :entity-type at all
                  {:uri "orphan:no-type" :label "No type"
                   :attributes {"ALPHA_K" "5"}}
                  ;; (b) :entity-type not in the model-spec
                  {:uri "orphan:unknown-type" :label "Unknown type"
                   :entity-type "Gamma Thing"
                   :attributes {"ALPHA_K" "5"}}
                  ;; (c) known type but the keying field's value is absent
                  {:uri "orphan:no-value" :label "No keying value"
                   :entity-type "Alpha Thing"
                   :attributes {"SOME_OTHER_FIELD" "x"}}
                  ;; (d) a well-formed sibling that DOES canonicalize
                  {:uri "alpha-thing:5" :label "Alpha 5"
                   :entity-type "Alpha Thing"
                   :attributes {"ALPHA_K" "5"}}]
          result (extract/canonicalize-drafts single-key-spec drafts [])
          by-label (into {} (map (juxt :label identity)) (:concept-drafts result))
          degraded-uris (set (map :uri (:degraded result)))
          degraded-reasons (into {} (map (juxt :uri :reason)) (:degraded result))]
      ;; the three degraded drafts keep their ORIGINAL URIs
      (is (= "orphan:no-type" (:uri (by-label "No type")))
          "a draft with no :entity-type keeps its original :uri")
      (is (= "orphan:unknown-type" (:uri (by-label "Unknown type")))
          "a draft with an unknown :entity-type keeps its original :uri")
      (is (= "orphan:no-value" (:uri (by-label "No keying value")))
          "a draft with unrecoverable keying values keeps its original :uri")
      ;; all three are SURFACED in :degraded with an honest reason
      (is (= #{"orphan:no-type" "orphan:unknown-type" "orphan:no-value"}
             degraded-uris)
          "every degraded draft is surfaced in :degraded (not silently dropped)")
      (is (= :no-entity-type (degraded-reasons "orphan:no-type")))
      (is (= :unknown-entity-type (degraded-reasons "orphan:unknown-type")))
      (is (= :unrecoverable-keying-values (degraded-reasons "orphan:no-value")))
      ;; exactly ONE draft (the well-formed sibling) minted the canonical URI —
      ;; no degraded draft fabricated one (which would over-count it)
      (is (= 1 (count (filter #(= "alphathing/5" %)
                              (map :uri (:concept-drafts result)))))
          "exactly one draft carries the canonical alphathing/5 — degraded drafts
           did NOT fabricate it")
      ;; the well-formed sibling DID canonicalize
      (is (= "alphathing/5" (:uri (by-label "Alpha 5")))
          "the well-formed sibling still mints its canonical URI")
      (is (not (contains? degraded-uris "alpha-thing:5"))
          "the well-formed sibling is NOT in :degraded"))))
