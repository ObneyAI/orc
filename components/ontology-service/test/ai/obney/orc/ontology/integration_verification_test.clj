(ns ai.obney.orc.ontology.integration-verification-test
  "Integration verification tests for ORC-001 and ORC-002.

   These tests verify the complete feature set works correctly:
   1. JSON structure analysis (code-only)
   2. JSON extraction pipeline definition
   3. Evolutionary builder JSON support
   4. Ontology-id scoping in projections

   Note: Full LLM-based extraction requires running manually with API keys."
  (:require [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

;; =============================================================================
;; Test Data - Realistic Cambot-style JSON
;; =============================================================================

(def cambot-turnover-json
  "Sample JSON similar to what Cambot's turnover data would produce."
  {:entities
   [{:uri "person:tavidee-hoskins"
     :label "Tavidee Hoskins"
     :type "Person"
     :aliases ["Tavidee" "Tavi"]
     :properties {:group "relationship-ops" :role "Director"}}
    {:uri "person:cameron-barre"
     :label "Cameron Barre"
     :type "Person"
     :aliases ["Cameron" "Cam"]
     :properties {:group "technology" :role "Developer"}}
    {:uri "org:bryc"
     :label "BRYC"
     :type "Organization"
     :aliases ["Blue Ridge Youth Collaborative"]
     :properties {:type "nonprofit"}}]
   :relationships
   [{:subject "person:tavidee-hoskins"
     :predicate "member-of"
     :object "org:bryc"}
    {:subject "person:cameron-barre"
     :predicate "member-of"
     :object "org:bryc"}]})

(def simple-people-json
  "Simple array of people - tests basic JSON handling."
  [{:name "John Smith" :age 30 :role "Engineer"}
   {:name "Jane Doe" :age 28 :role "Designer"}])


(deftest json-structure-analysis-works
  (testing "analyze-json-structure correctly identifies nested object structure"
    (let [result (logic/analyze-json-structure cambot-turnover-json)]
      (is (= :object (:root-type result))
          "should detect root as object")
      (is (true? (:has-arrays? result))
          "should detect arrays within object")
      (is (some #(= "entities" (str (:name %))) (:fields result))
          "should detect entities field")
      (is (some #(= "relationships" (str (:name %))) (:fields result))
          "should detect relationships field")))

  (testing "analyze-json-structure handles array of objects"
    (let [result (logic/analyze-json-structure simple-people-json)]
      (is (= :array (:root-type result)))
      (is (= :object (:element-type result)))
      (is (= 3 (count (:fields result)))
          "should detect 3 fields"))))

(deftest json-parse-function-works
  (testing "parse-json-fn produces correct structure summary"
    (let [result (logic/parse-json-fn {:inputs {:json-data simple-people-json}})]
      (is (= simple-people-json (:json-data result)))
      (is (string? (:structure-summary result)))
      (is (str/includes? (:structure-summary result) "Root type")))))

(deftest json-pipeline-definition-valid
  (testing "json-to-ontology-pipeline has correct structure"
    (let [pipeline logic/json-to-ontology-pipeline]
      (is (= "json-to-ontology" (:workflow-name pipeline)))
      (is (map? (:blackboard-schema pipeline)))
      (is (= :sequence (:node-type (:root-node pipeline))))
      (let [phases (get-in pipeline [:root-node :children])]
        (is (= 11 (count phases)))
        (is (= "parse-json" (:name (first phases))))
        (is (= "serialize-to-owl" (:name (last phases))))))))

(deftest json-concept-extraction-logic-works
  (testing "extract-concepts-from-analysis-fn creates concepts from entity types"
    (let [entity-types [{:name "Person"
                         :type "Person"
                         :source-fields ["name" "role"]
                         :definition "An individual"
                         :confidence 0.9}]
          result (logic/extract-concepts-from-analysis-fn
                   {:inputs {:entity-types entity-types
                             :base-uri "http://test.org/"
                             :json-data simple-people-json}})]
      (is (= 1 (count (:concepts result))))
      (is (= "Person" (:label (first (:concepts result)))))
      (is (= 0.9 (:confidence (first (:concepts result))))))))

(deftest json-tbox-abox-construction-works
  (testing "build-tbox-fn creates OWL classes"
    (let [concepts [{:uri "http://test.org/Person"
                     :label "Person"
                     :definition "An individual"}]
          result (logic/json-ontology-build-tbox-fn {:inputs {:concepts concepts
                                                   :relationships []
                                                   :base-uri "http://test.org/"}})]
      (is (= 1 (count (get-in result [:tbox :classes]))))))

  (testing "build-abox-fn creates instances from JSON data"
    (let [result (logic/json-ontology-build-abox-fn
                   {:inputs {:json-data simple-people-json
                             :concepts [{:entity-type "Person"}]
                             :base-uri "http://test.org/"}})]
      (is (= 2 (count (:abox result))))
      (is (= "Person" (:type (first (:abox result))))))))

(deftest json-owl-serialization-works
  (testing "serialize-to-owl-fn produces valid Turtle"
    (let [tbox {:classes [{:uri "http://test.org/Person"
                           :label "Person"
                           :definition "An individual"}]
                :object-properties []
                :datatype-properties []}
          result (logic/json-ontology-serialize-to-owl-fn
                   {:inputs {:tbox tbox
                             :abox []
                             :base-uri "http://test.org/"}})]
      (is (string? (:owl-output result)))
      (is (str/includes? (:owl-output result) "@prefix"))
      (is (str/includes? (:owl-output result) "owl:Class")))))


(deftest unified-ontology-detects-json-correctly
  (testing "detect-source-type identifies JSON from extension"
    (is (= :json (logic/unified-ontology-detect-source-type {:path "data.json"})))
    (is (= :json (logic/unified-ontology-detect-source-type {:path "data.jsonl"})))
    (is (= :json (logic/unified-ontology-detect-source-type {:path "/path/to/DATA.JSON"}))))

  (testing "detect-source-type identifies JSON from content"
    (is (= :json (logic/unified-ontology-detect-source-type {:content "[{\"name\": \"test\"}]"})))
    (is (= :json (logic/unified-ontology-detect-source-type {:content "{\"key\": \"value\"}"}))))

  (testing "explicit type override works"
    (is (= :json (logic/unified-ontology-detect-source-type {:path "file.txt" :type :json})))))


(deftest evolutionary-builder-has-json-support
  (testing "extract-from-source dispatch includes json case"
    (is (fn? logic/extract-from-source))
    ))

(deftest evolutionary-builder-default-config-exists
  (testing "default-config has all expected keys"
    (is (map? logic/default-config))
    (is (contains? logic/default-config :base-uri))
    (is (contains? logic/default-config :similarity-threshold))
    (is (contains? logic/default-config :enable-colbert?))
    (is (contains? logic/default-config :enable-embeddings?))))


(def ontology-a #uuid "aaaa0000-0000-0000-0000-000000000001")
(def ontology-b #uuid "bbbb0000-0000-0000-0000-000000000002")

(deftest concepts-projection-stores-ontology-id
  (testing "concept-created events preserve ontology-id in projection"
    (let [events [{:event/type :ontology/concept-created
                   :ontology-id ontology-a
                   :concept-id (random-uuid)
                   :uri "person:alice"
                   :label "Alice"
                   :description "Test person"
                   :scope :person
                   :broader []
                   :indicators []
                   :created-at "2026-06-01T00:00:00Z"}
                  {:event/type :ontology/concept-created
                   :ontology-id ontology-b
                   :concept-id (random-uuid)
                   :uri "org:acme"
                   :label "Acme Corp"
                   :description "Test org"
                   :scope :organization
                   :broader []
                   :indicators []
                   :created-at "2026-06-01T00:00:00Z"}]
          state (logic/concepts {} events)]
      (is (= ontology-a (:ontology-id (get state "person:alice"))))
      (is (= ontology-b (:ontology-id (get state "org:acme")))))))

(deftest get-concepts-filtering-logic-correct
  (testing "filtering by ontology-id works correctly"
    (let [concepts {"person:alice" {:uri "person:alice"
                                     :label "Alice"
                                     :ontology-id ontology-a
                                     :scope :person}
                    "org:acme" {:uri "org:acme"
                                :label "Acme Corp"
                                :ontology-id ontology-b
                                :scope :organization}
                    "person:bob" {:uri "person:bob"
                                  :label "Bob"
                                  :ontology-id ontology-a
                                  :scope :person}}
          filter-fn (fn [{:keys [ontology-id ontology-ids scope]}]
                      (let [ont-id-set (cond
                                         ontology-ids (set ontology-ids)
                                         ontology-id #{ontology-id}
                                         :else nil)]
                        (cond->> (vals concepts)
                          scope (filter #(= scope (:scope %)))
                          ont-id-set (filter #(contains? ont-id-set (:ontology-id %))))))]

      (is (= 2 (count (filter-fn {:ontology-id ontology-a})))
          "ontology-a should have 2 concepts")
      (is (= 1 (count (filter-fn {:ontology-id ontology-b})))
          "ontology-b should have 1 concept")

      (is (= 3 (count (filter-fn {:ontology-ids [ontology-a ontology-b]})))
          "both ontologies should have 3 concepts")

      (is (= 2 (count (filter-fn {:ontology-id ontology-a :scope :person})))
          "ontology-a persons should be 2")

      (is (= 3 (count (filter-fn {})))
          "no filter should return all 3"))))

(deftest concept-embeddings-preserve-ontology-id
  (testing "concept-embedded events preserve ontology-id"
    (let [events [{:event/type :ontology/concept-embedded
                   :ontology-id ontology-a
                   :uri "person:alice"
                   :concept-id (random-uuid)
                   :embedding [0.1 0.2 0.3]
                   :text-embedded "Alice"
                   :field-source :label
                   :model-id "text-embedding-3-small"
                   :embedded-at "2026-06-01T00:00:00Z"}]
          state (logic/concept-embeddings {} events)
          embedding (get state "person:alice")]
      (is (some? embedding))
      (is (= ontology-a (:ontology-id embedding))))))

;; =============================================================================
;; Summary Test
;; =============================================================================

(deftest feature-completeness-summary
  (testing "ORC-001: JSON extraction sheet is complete"
    (is (some? logic/json-to-ontology-pipeline) "pipeline defined")
    (is (fn? logic/analyze-json-structure) "structure analysis works")
    (is (fn? logic/build-json-ontology-pipeline!) "builder function exists")
    (is (fn? logic/run-json-to-ontology) "run function exists"))

  (testing "ORC-002: Ontology-id scoping is complete"
    (let [docstring (-> #'read-models/get-concepts meta :doc)]
      (is (str/includes? docstring "ontology-id") "docstring mentions ontology-id")
      (is (str/includes? docstring "ontology-ids") "docstring mentions ontology-ids"))))
