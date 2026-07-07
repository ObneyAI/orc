(ns ai.obney.orc.ontology.connect3d-canonical-uri-reconciliation-test
  "CONNECT-3d — association edges must attach to the CANONICAL entity node.

   CONNECT-3a/b/c made junction sheets emit shared element nodes + key→element
   edges (a traversable graph). CONNECT-4's live build then surfaced a URI
   RECONCILIATION gap: the association fold builds the edge SOURCE URI as
   `<:entity-type>/<key>` using the CANONICAL entity-type spelling
   (`Occupation/<SOC>`) with the RAW key value, while the rich per-row occupation
   node lands at the GC-1-canonical `occupation/<normalize-value(SOC)>` (lowercase
   type via `normalize-key-name`, value via `normalize-value`). Two URI SCHEMES for
   the SAME entity → the edge attaches to a case/scheme-variant STUB, and the rich
   occupation profile stays edge-less (BFS-dead from the canonical node).

   The fix is a GC-1 RECONCILIATION, not a case-lowercase hack: the association
   fold now also emits the KEY entity as a concept-draft (carrying the key value
   in `:attributes` under `:key-col`), so the EXISTING per-container
   `canonicalize-drafts` (GC-1) rewrites BOTH the key node AND the edge
   `source-uri` to the identical canonical URI the per-row path produces — one
   canonicalizer, no fork, no URI-string parsing (GC-1's banned shortcut).

   These tracers route the fold's output through the REAL `canonicalize-drafts`
   seam (which the CONNECT-3a tracers never did — they landed occupations RAW at
   lowercase `occupation/<key>`, masking this gap), with the canonical-cased
   `Occupation` vocabulary the apply seam actually produces (resolve-entity-type
   snaps the model's authored type to the canonical `:type` spelling).

   Domain-agnostic (#12): the spec/vocab name no O*NET column beyond the fixture's
   own runtime values; the assertions derive the canonical truth from GC-1 itself."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.container-aggregate :as ca]
            [ai.obney.orc.ontology.core.extract-subbehavior :as ext]))

;; A Skills-shaped junction fixture. Two SOC keys share `Active Listening` (the
;; dedup/bridge element). One SOC (`15-1252.0`) carries a trailing `.0` that
;; `normalize-value` STRIPS — so a mere lowercase of the source scheme would NOT
;; reconcile it; the fix must run the endpoint through GC-1's value normalization.
(def ^:private junction-rows
  [{"O*NET-SOC Code" "11-1011.00" "Element Name" "Active Listening" "DataValue" 5}
   {"O*NET-SOC Code" "11-1011.00" "Element Name" "Speaking"         "DataValue" 4}
   {"O*NET-SOC Code" "13-2011.00" "Element Name" "Active Listening" "DataValue" 3}
   {"O*NET-SOC Code" "15-1252.0"  "Element Name" "Programming"      "DataValue" 2}])

;; The spec AS IT EXISTS AT THE APPLY SEAM: `resolve-entity-type` has already
;; snapped the model's authored key type to the CANONICAL vocabulary spelling
;; `Occupation` (capital) — the exact shape that diverges from the lowercase
;; canonical URI scheme.
(def ^:private association-spec
  {:key-col "O*NET-SOC Code" :element-col "Element Name" :value-col "DataValue"
   :predicate "requires" :element-entity-type "Skill" :entity-type "Occupation"})

;; The discovered vocabulary — canonical `Occupation`/`Skill` with their keying
;; fields (what GC-1 mints URIs from).
(def ^:private model-spec
  {:entity-types [{:type "Occupation" :uri-keying-fields ["O*NET-SOC Code"]}
                  {:type "Skill"      :uri-keying-fields ["Element Name"]}]})

(defn- canonical-occupation-uri
  "The canonical occupation URI GC-1 mints for the per-row Occupation Data node —
   the TRUTH the association edge must point at. Derived from GC-1 itself (not
   hand-computed) so the test can never drift from the real minting."
  [soc]
  (-> (ext/canonicalize-drafts
       model-spec
       [{:uri "occupation/whatever-the-author-freelanced"
         :label "A Real Occupation Title" :description "A rich per-row profile."
         :entity-type "Occupation" :attributes {"O*NET-SOC Code" soc}}]
       [])
      :concept-drafts first :uri))

(deftest association-edge-source-reconciles-to-canonical-occupation-node
  (testing "routed through the REAL GC-1 canonicalize-drafts seam with the
            canonical-cased Occupation vocab, every association edge's :source-uri
            equals the canonical occupation node URI (occupation/<normalize-value
            SOC>), NOT a case/scheme-variant Occupation/<raw-SOC> stub"
    (let [assoc (ca/stream-aggregate association-spec junction-rows)
          {:keys [relationship-drafts]}
          (ext/canonicalize-drafts model-spec
                                   (:concept-drafts assoc)
                                   (:relationship-drafts assoc))
          src-uris (set (map :source-uri relationship-drafts))
          uri-11 (canonical-occupation-uri "11-1011.00")
          uri-13 (canonical-occupation-uri "13-2011.00")
          uri-15 (canonical-occupation-uri "15-1252.0")]
      ;; sanity — GC-1 mints the lowercase-type, value-normalized scheme.
      (is (= "occupation/11-1011.00" uri-11)
          "GC-1 canonical occupation URI is occupation/<normalize-value SOC>")
      (is (= "occupation/15-1252" uri-15)
          "normalize-value STRIPS the trailing .0 — a lowercase-only fix would miss this")
      ;; THE FIX — the edges attach to the canonical occupation nodes.
      (is (contains? src-uris uri-11)
          "edge source reconciles to the canonical occupation node (11-1011.00)")
      (is (contains? src-uris uri-13)
          "edge source reconciles to the canonical occupation node (13-2011.00)")
      (is (contains? src-uris uri-15)
          "edge source reconciles to the canonical node even when value-normalized")
      ;; NO case/scheme-variant stubs survive.
      (is (not-any? #(= "Occupation" (namespace (keyword %))) src-uris)
          "no edge points at the case-variant Occupation/<SOC> scheme")
      (is (= #{uri-11 uri-13 uri-15} src-uris)
          "EVERY edge source is a canonical occupation URI — none stranded"))))

(deftest canonical-occupation-node-is-emitted-and-canonicalized
  (testing "the association fold emits the KEY entity as a concept-draft so GC-1
            mints it to the SAME canonical URI as the per-row node — a bare
            identity draft that EB5 unions under the rich Occupation Data node"
    (let [assoc (ca/stream-aggregate association-spec junction-rows)
          {:keys [concept-drafts]}
          (ext/canonicalize-drafts model-spec
                                   (:concept-drafts assoc)
                                   (:relationship-drafts assoc))
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)]
      ;; the three distinct occupation keys are present as canonical concept nodes
      (is (contains? by-uri (canonical-occupation-uri "11-1011.00"))
          "occupation key 11-1011.00 is a canonical concept node")
      (is (contains? by-uri (canonical-occupation-uri "15-1252.0"))
          "occupation key 15-1252.0 is a canonical concept node")
      ;; the key node carries the SOC so GC-1 could recover identity (and EB5 can
      ;; union it under the rich node); its :entity-type is the key type.
      (is (= "Occupation"
             (:entity-type (get by-uri (canonical-occupation-uri "11-1011.00"))))
          "the emitted key node carries the canonical key entity-type"))))
