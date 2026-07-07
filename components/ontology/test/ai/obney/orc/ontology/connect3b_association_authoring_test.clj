(ns ai.obney.orc.ontology.connect3b-association-authoring-test
  "CONNECT-3b — the MODEL-authored association + the rating ON THE EDGE.

   CONNECT-3a landed the deterministic associative FOLD (a spec with :predicate +
   :element-entity-type → shared element nodes + key→element edges) and rode the
   value-col rating on the draft :attributes. But :attributes does NOT survive the
   create-relationship compile path (the relationship-created :properties schema is
   [:map-of :keyword :any]; a string-keyed :attributes map is dropped). CONNECT-3b:

     1. the MODEL discovers when to author the association (proven by the STEP-1
        prototype + the STEP-3 live build — NOT hardcoded here; #9 test-the-builder);
     2. parse-aggregation-spec tolerates the model's key-spelling variance
        (underscore_forms → hyphen-forms) so BOTH the association fields
        (:predicate/:element-entity-type) and the attribute fields round-trip
        (the live prototype showed the model emits :key_col / :attr_name / :field);
     3. the value-col rating rides the edge in keyword-keyed :properties so it
        SURVIVES relationship-draft->command into the landed edge.

   These are the DETERMINISTIC guards. The DISCOVERY itself (does the model author
   the association for a junction sheet + an attribute for a labels sheet) is proven
   by the prototype hit-rate + the live edge-scan, never by hardcoding the decision."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.container-aggregate :as ca]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [clojure.string :as str]))

;; ===========================================================================
;; A Skills-shaped junction fixture with the REAL O*NET value-col NAME ("Data
;; Value" — a space) so the keyword-keyed :properties path is exercised on a
;; realistic column name, not a tidy one.
;; ===========================================================================

(def ^:private junction-rows
  [{"SOC" "occA" "Element" "shared" "Data Value" 5}
   {"SOC" "occA" "Element" "onlyA"  "Data Value" 4}
   {"SOC" "occB" "Element" "shared" "Data Value" 3}
   {"SOC" "occC" "Element" "shared" "Data Value" 1}])

(def ^:private association-spec
  {:key-col "SOC" :element-col "Element" :value-col "Data Value"
   :predicate "requires" :element-entity-type "skill" :key-entity-type "occupation"})

;; ===========================================================================
;; Tracer 1 — parse-aggregation-spec round-trips the association fields AND
;; normalizes the model's underscore key-spelling (the live-prototype variance)
;; so a spec authored as {:key_col … :element_col … :predicate … :element_entity_type …}
;; becomes a valid association spec. Entity-specific label specs stay attributes.
;; ===========================================================================

(deftest parse-round-trips-association-fields-and-normalizes-underscores
  (testing "a hyphen-keyed association spec round-trips :predicate + :element-entity-type"
    (let [p (ca/parse-aggregation-spec association-spec)]
      (is (ca/valid-aggregation-spec? p))
      (is (ca/association-mode? p))
      (is (= "requires" (:predicate p)))
      (is (= "skill" (:element-entity-type p)))))

  (testing "the model's UNDERSCORE key-spelling normalizes to the canonical hyphen
            fields — an association spec authored as :key_col/:element_col/
            :element_entity_type is still recognized (parse tolerates it, #10)"
    (let [under {:key_col "SOC" :element_col "Element" :value_col "Data Value"
                 :predicate "requires" :element_entity_type "skill"}
          p (ca/parse-aggregation-spec under)]
      (is (ca/valid-aggregation-spec? p)
          "underscore key/element cols normalize so the spec is executable")
      (is (ca/association-mode? p)
          "underscore element_entity_type normalizes → association recognized")
      (is (= "skill" (:element-entity-type p)))))

  (testing "an entity-specific-label spec (underscore attr_name, NO predicate) stays
            an ATTRIBUTE — normalized so it is a valid rollup, never an association"
    (let [attr {:key_col "SOC" :element_col "Element" :attr_name "labels"
                :entity_type "occupation"}
          p (ca/parse-aggregation-spec attr)]
      (is (ca/valid-aggregation-spec? p)
          "underscore attr spec normalizes to valid :key-col/:element-col")
      (is (not (ca/association-mode? p))
          "no :predicate/:element-entity-type → NOT an association (attribute)")
      (is (= "labels" (:attr-name p)))
      (is (= "occupation" (:entity-type p)))))

  (testing "the C1 string form (an un-parsed EDN string) still round-trips"
    (let [p (ca/parse-aggregation-spec
             "{:key-col \"SOC\" :element-col \"Element\" :predicate \"requires\" :element-entity-type \"skill\"}")]
      (is (ca/association-mode? p)))))

;; ===========================================================================
;; Tracer 2 — the guidance carries the association (case C) mechanism, and the
;; A/B attribute cases stay intact. This asserts the MECHANISM vocabulary
;; (association / predicate / element-entity-type / shared-referenceable), NOT any
;; DOMAIN column/type (#7/#12 — no "skill"/"requires" hardcoded).
;; ===========================================================================

(deftest guidance-carries-association-case-and-keeps-attribute-cases
  (let [g (ca/aggregation-author-guidance)
        lower (str/lower-case g)]
    (testing "case C — the shared-referenceable-entity → association mechanism"
      (is (str/includes? lower "association"))
      (is (str/includes? g ":predicate"))
      (is (str/includes? g ":element-entity-type"))
      (is (or (str/includes? lower "referenceable") (str/includes? lower "shared")))
      (is (str/includes? lower "many-to-many")))
    (testing "the attribute cases (A ranked/list + B one-row-per-entity) stay present"
      (is (str/includes? g ":attr-name"))
      (is (str/includes? g ":value-col"))
      (is (or (str/includes? lower "top-n") (str/includes? lower "flat list")))
      (is (str/includes? lower "one row per entity")))
    (testing "domain-agnostic — NO O*NET/SOC/Skills/requires literal in the guidance"
      (is (not (re-find #"(?i)\bskill\b|\bsoc\b|o\*net|\brequires\b|occupation" g))))))

;; ===========================================================================
;; Tracer 3a — the rating rides the edge in keyword-keyed :PROPERTIES (not the
;; string-keyed :attributes that the compile path drops). associate-finalize is
;; the seam; the value-col NAME becomes the property key (a keyword).
;; ===========================================================================

(deftest edge-carries-rating-in-keyword-keyed-properties
  (testing "each occupation→element edge draft carries the value-col rating under
            keyword-keyed :properties (survives create-relationship), NOT :attributes"
    (let [{:keys [relationship-drafts]} (ca/stream-aggregate association-spec junction-rows)
          edge (fn [src tgt] (first (filter #(and (= src (:source-uri %)) (= tgt (:target-uri %)))
                                            relationship-drafts)))
          e (edge "occupation/occA" "skill/shared")]
      (is (= {(keyword "Data Value") 5} (:properties e))
          "the rating rides keyword-keyed :properties under the value-col name")
      (is (nil? (:attributes e))
          "the draft no longer uses :attributes for the rating (that is dropped by compile)")
      (is (= {(keyword "Data Value") 3} (:properties (edge "occupation/occB" "skill/shared"))))
      (is (= {(keyword "Data Value") 1} (:properties (edge "occupation/occC" "skill/shared"))))))

  (testing "a collect-mode association (NO value-col) emits edges with NO :properties"
    (let [{:keys [relationship-drafts]}
          (ca/stream-aggregate (dissoc association-spec :value-col) junction-rows)]
      (is (seq relationship-drafts))
      (is (every? #(nil? (:properties %)) relationship-drafts)
          "no value-col → no rating on the edge (never a fabricated property)"))))

;; ===========================================================================
;; Tracer 3b — THE LANDING PROOF. The rating in keyword-keyed :properties SURVIVES
;; through the REAL create-relationship command into the projected edge (this is
;; exactly what CONNECT-3a's :attributes could NOT do).
;; ===========================================================================

(def ^:private ontology-id #uuid "c3b00000-0000-0000-0000-00000000c3b0")

(defn- land-concept! [ctx uri label]
  (h/run-and-apply! ctx (fn [c]
                          (cmd/ontology-create-concept
                           (assoc c :command (h/make-concept-data
                                              :ontology-id ontology-id :uri uri
                                              :label label :description label :scope :custom))))))

(defn- land-relationship-draft! [ctx draft]
  ;; MIRROR the real relationship-draft->command path: :properties is forwarded
  ;; keyword-keyed (rlm-discovery/relationship-draft->command uses
  ;; `:properties (or (:properties draft) {})`).
  (h/run-and-apply! ctx (fn [c]
                          (cmd/ontology-create-relationship
                           (assoc c :command {:ontology-id ontology-id
                                              :source-uri (:source-uri draft)
                                              :target-uri (:target-uri draft)
                                              :predicate (:predicate draft)
                                              :properties (or (:properties draft) {})})))))

(deftest rating-survives-landing-into-the-edge-properties
  (testing "after landing the associative fold's drafts through the REAL
            create-relationship command, the occupation→skill edge's :properties
            carries the rating (keyword-keyed) — the CONNECT-3b fix that :attributes
            could not achieve"
    (h/with-test-context [ctx]
      (let [{:keys [concept-drafts relationship-drafts]}
            (ca/stream-aggregate association-spec junction-rows)]
        (land-concept! ctx "occupation/occA" "Occupation A")
        (land-concept! ctx "occupation/occB" "Occupation B")
        (land-concept! ctx "occupation/occC" "Occupation C")
        (doseq [d concept-drafts] (land-concept! ctx (:uri d) (:label d)))
        (doseq [r relationship-drafts] (land-relationship-draft! ctx r))
        (let [edges (rm/get-relationships ctx)
              occA->shared (first (filter #(and (= "occupation/occA" (:source-uri %))
                                                (= "skill/shared" (:target-uri %)))
                                          edges))]
          (is (some? occA->shared) "the occA→shared-skill edge landed")
          (is (= 5 (get (:properties occA->shared) (keyword "Data Value")))
              "the rating (5) SURVIVED into the landed edge's keyword-keyed :properties")
          ;; the shared skill has three occupation edges, each with its OWN rating
          (let [ratings (->> edges
                             (filter #(= "skill/shared" (:target-uri %)))
                             (map #(get (:properties %) (keyword "Data Value")))
                             set)]
            (is (= #{5 3 1} ratings)
                "all three occupation→shared-skill edges landed their own rating in :properties")))))))

;; ===========================================================================
;; Tracer 4 — the collect/top-N ATTRIBUTE modes are byte-identical for the
;; canonical hyphen-keyed specs (the underscore normalization must not disturb an
;; already-hyphenated spec). Association is strictly opt-in.
;; ===========================================================================

(deftest attribute-modes-byte-identical-for-hyphen-specs
  (testing "a hyphen-keyed top-N attribute spec is unchanged by the normalization —
            one draft per key, the flat ranked list, ZERO relationship-drafts"
    (let [attr-spec {:key-col "SOC" :element-col "Element" :value-col "Data Value"
                     :attr-name :topElements :entity-type "occupation"}
          {:keys [concept-drafts relationship-drafts distinct-keys]}
          (ca/stream-aggregate attr-spec junction-rows)
          by-uri (into {} (map (juxt :uri identity)) concept-drafts)]
      (is (= 3 (count concept-drafts)))
      (is (= 3 distinct-keys))
      (is (= ["shared" "onlyA"] (get-in by-uri ["occupation/occA" :attributes :topElements]))
          "occA's ranked flat list is unchanged")
      (is (empty? relationship-drafts)
          "a hyphen attribute spec (no :predicate) emits ZERO edges (byte-identical)"))))
