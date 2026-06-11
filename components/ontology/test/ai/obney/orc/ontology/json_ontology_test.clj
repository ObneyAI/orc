(ns ai.obney.orc.ontology.json-ontology-test
  "TDD tests for JSON ontology extraction.

   Tests the JSON structure analysis and concept extraction logic.
   Follows the pattern of other ontology tests: unit tests for functions,
   not full sheet execution (which requires LLM mocking)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.sheets.json-ontology :as json-ont]))

;; =============================================================================
;; Sample JSON Data
;; =============================================================================

(def simple-people-json
  "Simple JSON array with clear field names."
  [{"name" "John Smith"
    "age" 30
    "role" "Engineer"}
   {"name" "Jane Doe"
    "age" 28
    "role" "Designer"}])

(def cryptic-people-json
  "JSON with badly labeled fields - LLM must infer meaning."
  [{"n" "John Smith"
    "a" 30
    "r" "Engineer"}
   {"n" "Jane Doe"
    "a" 28
    "r" "Designer"}])

(def nested-company-json
  "Nested JSON with containment relationships."
  {"company" {"name" "Acme Corp"
              "founded" 2010}
   "employees" [{"name" "John" "department" "Engineering"}
                {"name" "Jane" "department" "Design"}]
   "departments" [{"name" "Engineering" "budget" 500000}
                  {"name" "Design" "budget" 300000}]})

(def primitive-array-json
  "Array of primitives (edge case)."
  ["apple" "banana" "cherry"])

(def single-object-json
  "Single object (not array)."
  {"id" 1
   "title" "Introduction to Clojure"
   "author" "Rich Hickey"})

;; =============================================================================
;; Slice 1: JSON Structure Analysis (code-only, no LLM)
;; =============================================================================

(deftest analyze-json-structure-array-of-objects
  (testing "analyzes array of objects - the most common case"
    (let [result (json-ont/analyze-json-structure simple-people-json)]
      (is (= :array (:root-type result))
          "root type should be :array")
      (is (= :object (:element-type result))
          "element type should be :object")
      (is (= 2 (:total-count result))
          "total count should match array length")
      (is (= 3 (count (:fields result)))
          "should detect 3 fields (name, age, role)")

      ;; Verify field analysis
      (let [name-field (first (filter #(= "name" (:name %)) (:fields result)))]
        (is (some? name-field) "should have name field")
        (is (= :string (:type name-field)) "name should be string type")
        (is (vector? (:sample-values name-field)) "should have sample values")))))

(deftest analyze-json-structure-cryptic-fields
  (testing "analyzes JSON with cryptic field names"
    (let [result (json-ont/analyze-json-structure cryptic-people-json)]
      (is (= :array (:root-type result)))
      (is (= 3 (count (:fields result)))
          "should detect 3 fields (n, a, r)")

      ;; Should capture cryptic field names
      (let [field-names (set (map :name (:fields result)))]
        (is (= #{"n" "a" "r"} field-names)
            "should preserve cryptic field names"))

      ;; Sample values help LLM infer meaning later
      (let [n-field (first (filter #(= "n" (:name %)) (:fields result)))]
        (is (= :string (:type n-field)))
        (is (some #(= "John Smith" %) (:sample-values n-field))
            "should capture sample values for inference")))))

(deftest analyze-json-structure-nested-object
  (testing "analyzes nested object structure"
    (let [result (json-ont/analyze-json-structure nested-company-json)]
      (is (= :object (:root-type result))
          "root type should be :object")
      (is (true? (:has-nested-objects? result))
          "should detect nested objects")
      (is (true? (:has-arrays? result))
          "should detect arrays within object"))))

(deftest analyze-json-structure-primitive-array
  (testing "handles array of primitives"
    (let [result (json-ont/analyze-json-structure primitive-array-json)]
      (is (= :array (:root-type result)))
      (is (= :primitive (:element-type result)))
      (is (empty? (:fields result))
          "primitives have no fields"))))

(deftest analyze-json-structure-single-object
  (testing "handles single object (not array)"
    (let [result (json-ont/analyze-json-structure single-object-json)]
      (is (= :object (:root-type result)))
      (is (= 3 (count (:fields result)))))))

;; =============================================================================
;; Slice 2: Parse JSON Function (Phase 1 code executor)
;; =============================================================================

(deftest parse-json-fn-from-data
  (testing "parse-json-fn processes pre-parsed JSON data"
    (let [result (json-ont/parse-json-fn {:inputs {:json-data simple-people-json}})]
      (is (= simple-people-json (:json-data result))
          "should pass through json-data")
      (is (map? (:structure result))
          "should include structure analysis")
      (is (string? (:structure-summary result))
          "should generate LLM-readable summary"))))

(deftest parse-json-fn-from-string
  (testing "parse-json-fn parses JSON string content"
    (let [json-str "[{\"name\": \"Alice\", \"age\": 25}]"
          result (json-ont/parse-json-fn {:inputs {:content json-str}})]
      (is (vector? (:json-data result))
          "should parse JSON string to data")
      (is (= "Alice" (get-in (:json-data result) [0 :name]))
          "should correctly parse content"))))

;; =============================================================================
;; Slice 3: Concept Extraction (code executor, no LLM)
;; =============================================================================

(deftest extract-concepts-from-entity-types
  (testing "creates concepts from LLM-discovered entity types"
    (let [entity-types [{:name "Person"
                         :type "Person"
                         :source-fields ["name" "age" "role"]
                         :definition "An individual with personal information"
                         :confidence 0.9}]
          result (json-ont/extract-concepts-from-analysis-fn
                   {:inputs {:entity-types entity-types
                             :base-uri "http://test.org/"
                             :json-data simple-people-json}})]
      (is (= 1 (count (:concepts result)))
          "should create one concept")
      (let [concept (first (:concepts result))]
        (is (= "Person" (:label concept)))
        (is (= "http://test.org/Person" (:uri concept)))
        (is (= "Person" (:entity-type concept)))
        (is (= 0.9 (:confidence concept)))))))

(deftest extract-concepts-fallback-when-no-types
  (testing "creates fallback concept when LLM returns no types"
    (let [result (json-ont/extract-concepts-from-analysis-fn
                   {:inputs {:entity-types []
                             :base-uri "http://test.org/"
                             :json-data simple-people-json}})]
      (is (= 1 (count (:concepts result)))
          "should create fallback concept")
      (is (= "Entity" (:label (first (:concepts result)))))
      (is (= 0.5 (:confidence (first (:concepts result))))
          "fallback has low confidence"))))

;; =============================================================================
;; Slice 4: Deduplication
;; =============================================================================

(deftest deduplicate-concepts-removes-duplicates
  (testing "removes duplicate concepts by label (case-insensitive)"
    (let [concepts [{:uri "http://test.org/Person" :label "Person"}
                    {:uri "http://test.org/person" :label "person"}
                    {:uri "http://test.org/Employee" :label "Employee"}]
          result (json-ont/deduplicate-concepts-fn {:inputs {:concepts concepts}})]
      (is (= 2 (count (:concepts result)))
          "should keep 2 unique concepts")
      (is (= {:before 3 :after 2 :removed 1} (:dedup-stats result))))))

;; =============================================================================
;; Slice 5: T-Box Construction
;; =============================================================================

(deftest build-tbox-creates-classes
  (testing "builds T-Box classes from concepts"
    (let [concepts [{:uri "http://test.org/Person"
                     :label "Person"
                     :definition "An individual"}
                    {:uri "http://test.org/Role"
                     :label "Role"
                     :definition "A job role"}]
          result (json-ont/build-tbox-fn {:inputs {:concepts concepts
                                                   :relationships []
                                                   :base-uri "http://test.org/"}})]
      (is (= 2 (count (get-in result [:tbox :classes])))
          "should create 2 classes"))))

(deftest build-tbox-creates-object-properties
  (testing "builds object properties from relationships"
    (let [relationships [{:subject "Person"
                          :predicate "hasRole"
                          :object "Role"
                          :evidence "Person has role field"
                          :confidence 0.8}]
          result (json-ont/build-tbox-fn {:inputs {:concepts []
                                                   :relationships relationships
                                                   :base-uri "http://test.org/"}})]
      (is (= 1 (count (get-in result [:tbox :object-properties])))))))

;; =============================================================================
;; Slice 6: A-Box Construction
;; =============================================================================

(deftest build-abox-creates-instances
  (testing "creates instances from JSON array data"
    (let [result (json-ont/build-abox-fn
                   {:inputs {:json-data simple-people-json
                             :concepts [{:entity-type "Person"}]
                             :base-uri "http://test.org/"}})]
      (is (= 2 (count (:abox result)))
          "should create 2 instances (one per JSON object)")
      (let [instance (first (:abox result))]
        (is (string? (:uri instance)))
        (is (= "Person" (:type instance)))
        (is (= "John Smith" (get-in instance [:properties "name"])))))))

;; =============================================================================
;; Issue 003: A-Box Label Extraction Tests
;; =============================================================================

(deftest build-abox-includes-label-field
  (testing "individuals include :label field (Grain schema compliance)"
    (let [result (json-ont/build-abox-fn
                   {:inputs {:json-data simple-people-json
                             :concepts [{:entity-type "Person"}]
                             :base-uri "http://test.org/"}})]
      (doseq [instance (:abox result)]
        (is (contains? instance :label)
            "Each individual must have a :label field")
        (is (string? (:label instance))
            "Label must be a string")))))

(deftest build-abox-uses-label-field-from-concepts
  (testing "extracts label using LLM-specified label-field"
    (let [result (json-ont/build-abox-fn
                   {:inputs {:json-data [{"n" "John" "a" 30} {"n" "Jane" "a" 25}]
                             :concepts [{:entity-type "Person"
                                         :label-field "n"      ;; LLM told us 'n' is the name field
                                         :source-fields ["n" "a"]}]
                             :base-uri "http://test.org/"}})]
      (is (= "John" (:label (first (:abox result))))
          "Should use label-field value as label")
      (is (= "Jane" (:label (second (:abox result))))
          "Should use label-field value for all instances"))))

(deftest build-abox-falls-back-to-source-field
  (testing "falls back to first source-field when label-field is nil"
    (let [result (json-ont/build-abox-fn
                   {:inputs {:json-data [{"name" "Alice"} {"name" "Bob"}]
                             :concepts [{:entity-type "Person"
                                         :label-field nil     ;; No label-field specified
                                         :source-fields ["name"]}]  ;; But we have source-fields
                             :base-uri "http://test.org/"}})]
      (is (= "Alice" (:label (first (:abox result))))
          "Should fall back to first source-field")
      (is (= "Bob" (:label (second (:abox result))))))))

(deftest build-abox-falls-back-to-synthetic-label
  (testing "falls back to synthetic label when no field extraction works"
    (let [result (json-ont/build-abox-fn
                   {:inputs {:json-data [{"x" 1} {"x" 2}]
                             :concepts [{:entity-type "Thing"
                                         :label-field nil
                                         :source-fields []}]  ;; No source-fields either
                             :base-uri "http://test.org/"}})]
      (is (= "Thing-0" (:label (first (:abox result))))
          "Should generate synthetic label")
      (is (= "Thing-1" (:label (second (:abox result))))
          "Should include index in synthetic label"))))

;; =============================================================================
;; Slice 7: OWL Serialization
;; =============================================================================

(deftest serialize-to-owl-produces-turtle
  (testing "serializes to OWL Turtle format"
    (let [tbox {:classes [{:uri "http://test.org/Person"
                           :label "Person"
                           :definition "An individual"}]
                :object-properties []
                :datatype-properties []}
          result (json-ont/serialize-to-owl-fn
                   {:inputs {:tbox tbox
                             :abox []
                             :base-uri "http://test.org/"}})]
      (is (string? (:owl-output result)))
      (is (re-find #"@prefix" (:owl-output result))
          "should have Turtle prefixes")
      (is (re-find #"owl:Class" (:owl-output result))
          "should declare OWL classes"))))

;; =============================================================================
;; Slice 8: Format Structure for LLM
;; =============================================================================

(deftest format-structure-for-llm-readable
  (testing "formats structure in LLM-readable way"
    (let [structure (json-ont/analyze-json-structure simple-people-json)
          formatted (json-ont/format-structure-for-llm structure simple-people-json)]
      (is (string? formatted))
      (is (re-find #"Root type" formatted)
          "should describe root type")
      (is (re-find #"Field Details" formatted)
          "should list field details")
      (is (re-find #"Sample Data" formatted)
          "should include sample data"))))

;; =============================================================================
;; Slice 9: Pipeline Definition Structure
;; =============================================================================

(deftest pipeline-definition-is-valid
  (testing "json-to-ontology-pipeline is a valid workflow definition"
    (is (some? json-ont/json-to-ontology-pipeline)
        "pipeline should be defined")
    ;; The workflow macro returns a map with :workflow-name, :blackboard-schema, :root-node
    (is (map? json-ont/json-to-ontology-pipeline)
        "pipeline should be a map (workflow definition)")
    (is (= "json-to-ontology" (:workflow-name json-ont/json-to-ontology-pipeline))
        "should have correct workflow name")
    (is (map? (:blackboard-schema json-ont/json-to-ontology-pipeline))
        "should have blackboard schema")
    (is (map? (:root-node json-ont/json-to-ontology-pipeline))
        "should have root node")
    (is (= :sequence (:node-type (:root-node json-ont/json-to-ontology-pipeline)))
        "root should be a sequence node")
    ;; Verify pipeline has expected phases
    (let [children (get-in json-ont/json-to-ontology-pipeline [:root-node :children])]
      (is (= 11 (count children))
          "should have 11 pipeline phases")
      (is (= "parse-json" (:name (first children)))
          "first phase should be parse-json")
      (is (= "serialize-to-owl" (:name (last children)))
          "last phase should be serialize-to-owl"))))

(deftest api-functions-exist
  (testing "public API functions are defined"
    (is (fn? json-ont/build-json-ontology-pipeline!)
        "build-json-ontology-pipeline! should be a function")
    (is (fn? json-ont/run-json-to-ontology)
        "run-json-to-ontology should be a function")
    (is (fn? json-ont/analyze-json-structure)
        "analyze-json-structure should be a function")))

;; =============================================================================
;; Slice 10: Unified Ontology Integration
;; =============================================================================

(require '[ai.obney.orc.ontology.sheets.unified-ontology :as unified])

(deftest unified-ontology-detects-json
  (testing "detect-source-type identifies JSON from file extension"
    (is (= :json (unified/detect-source-type {:path "data.json"})))
    (is (= :json (unified/detect-source-type {:path "data.jsonl"})))
    (is (= :json (unified/detect-source-type {:path "/full/path/to/DATA.JSON"}))))

  (testing "detect-source-type identifies JSON from content"
    (is (= :json (unified/detect-source-type {:content "[{\"name\": \"test\"}]"})))
    (is (= :json (unified/detect-source-type {:content "{\"key\": \"value\"}"}))))

  (testing "explicit type override works"
    (is (= :json (unified/detect-source-type {:path "file.txt" :type :json})))))
