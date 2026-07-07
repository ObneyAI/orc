(ns ai.obney.orc.ontology.connect3e-reference-element-merge-test
  "CONNECT-3e — an association whose ELEMENT is an existing vocabulary entity-type
   (a foreign-key REFERENCE, e.g. O*NET `Related Occupations`: occupation→related
   occupation) must MERGE the element into the referenced entity's canonical node,
   not mint a variant ORPHAN.

   Root cause (proven live, oid e8b9ec69): the model correctly authored an
   association-spec {:key-col \"O*NET-SOC Code\" :element-col \"Related O*NET-SOC Code\"
   :element-entity-type \"Occupation\" …}, but the fold stored the element's identity
   VALUE under the source column `Related O*NET-SOC Code`, while GC-1 recovers
   Occupation identity via the DECLARED keying field `O*NET-SOC Code` → mismatch →
   honest-degrade → 920 orphan `Occupation/<SOC>` nodes duplicating the canonical
   `occupation/<SOC>` set.

   The fix (symmetric to CONNECT-3d's source key-drafts, applied to the TARGET):
   store the element identity under the element-entity-type's DECLARED keying field
   (`association-element-key-field` resolves it from the vocab), so GC-1 canonicalizes
   the element to `occupation/<SOC>` and it MERGES. Domain-agnostic — driven by the
   vocab's keying fields, names no domain column. It also makes Skills/Knowledge
   correct BY DESIGN (they work today only because element-col == the keying field)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.container-aggregate :as ca]
            [ai.obney.orc.ontology.core.extract-subbehavior :as ext]))

;; The vocabulary shape `canonical-types` yields (incl. the locally-admitted
;; Occupation proposal). Occupation is keyed by a SINGLE field: `O*NET-SOC Code`.
(def ^:private vocab
  [{:type "Occupation" :uri-keying-fields ["O*NET-SOC Code"]}
   {:type "Skill"      :uri-keying-fields ["Element Name"]}])

(def ^:private model-spec {:entity-types vocab})

;; The EXACT spec the model authored for Related Occupations (from the store).
(def ^:private related-occupations-spec
  {:key-col "O*NET-SOC Code" :element-col "Related O*NET-SOC Code"
   :value-col "Index" :predicate "is related to"
   :entity-type "Occupation" :element-entity-type "Occupation"})

(def ^:private rows
  [{"O*NET-SOC Code" "11-1011.00" "Related O*NET-SOC Code" "11-1021.00" "Index" "1"}
   {"O*NET-SOC Code" "11-1011.00" "Related O*NET-SOC Code" "13-1111.00" "Index" "2"}
   {"O*NET-SOC Code" "13-1111.00" "Related O*NET-SOC Code" "11-1011.00" "Index" "1"}])

;; Test A — the resolver returns the element type's SINGLE declared keying field.
(deftest resolver-returns-element-entity-type-keying-field
  (testing "association-element-key-field resolves the element-entity-type
            (Occupation) to its declared keying field (O*NET-SOC Code), NOT the raw
            :element-col — so the element identity can be recovered by GC-1"
    (is (= "O*NET-SOC Code" (ext/association-element-key-field vocab related-occupations-spec))
        "element-entity-type Occupation → its single keying field O*NET-SOC Code")
    ;; a Skills association: element-col already equals the keying field (works today)
    (is (= "Element Name"
           (ext/association-element-key-field
            vocab {:key-col "O*NET-SOC Code" :element-col "Element Name"
                   :predicate "requires" :element-entity-type "Skill"}))
        "Skill → Element Name (its keying field, == element-col here)")
    ;; not an association → nil (no :predicate/:element-entity-type)
    (is (nil? (ext/association-element-key-field
               vocab {:key-col "SOC" :element-col "X" :attr-name :top :entity-type "Occupation"}))
        "an attribute spec (not an association) resolves no element key field")
    ;; unknown element type → nil (honest degrade)
    (is (nil? (ext/association-element-key-field
               vocab {:key-col "SOC" :element-col "X" :predicate "p" :element-entity-type "Nonexistent"}))
        "an element type absent from the vocab resolves nil")))

;; Test B — THE red→green driver: with the resolved key field, the reference
;; element MERGES into the canonical occupation node (no variant orphan).
(deftest reference-element-canonicalizes-and-merges
  (testing "routed through the resolver + fold + REAL GC-1 canonicalize-drafts, the
            related-occupation ELEMENT nodes land at canonical occupation/<SOC>
            (merging with the referenced occupation), NOT orphan Occupation/<SOC>"
    (let [ekf   (ext/association-element-key-field vocab related-occupations-spec)
          spec* (cond-> related-occupations-spec ekf (assoc :element-key-field ekf))
          assoc (ca/stream-aggregate spec* rows)
          {:keys [concept-drafts relationship-drafts]}
          (ext/canonicalize-drafts model-spec
                                   (:concept-drafts assoc)
                                   (:relationship-drafts assoc))
          node-uris (set (map :uri concept-drafts))
          tgt-uris  (set (map :target-uri relationship-drafts))]
      ;; EVERY occupation node — source keys AND related-occupation elements — is
      ;; canonical lowercase; ZERO case-variant Occupation/* orphans survive.
      (is (not-any? #(= "Occupation" (namespace (keyword %))) node-uris)
          "no variant Occupation/<SOC> orphan nodes — the related occupations merged")
      (is (contains? node-uris "occupation/11-1021.00")
          "the related occupation 11-1021.00 is a CANONICAL occupation node (merged)")
      (is (contains? node-uris "occupation/13-1111.00")
          "13-1111.00 appears once as a canonical node (it is both a source and a related occ)")
      ;; the edges' TARGETS reconcile to the canonical related-occupation nodes.
      (is (= #{"occupation/11-1021.00" "occupation/13-1111.00" "occupation/11-1011.00"} tgt-uris)
          "every related-to edge points at a canonical occupation node")
      ;; and the SOURCES too (CONNECT-3d, still holding).
      (is (not-any? #(= "Occupation" (namespace (keyword (:source-uri %)))) relationship-drafts)
          "edge sources remain canonical (CONNECT-3d)"))))
