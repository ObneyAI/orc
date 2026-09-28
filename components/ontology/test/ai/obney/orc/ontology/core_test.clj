(ns ai.obney.orc.ontology.core-test
  "Unit tests for ontology component.

   Tests the four main capabilities:
   1. Static ontology access
   2. Read model projections (concepts, tree-profiles, node-experiences)
   3. TTL/SKOS serialization
   4. Evaluation classification"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.ontology.interface :as graph]
            [ai.obney.orc.ontology.graph-test-support :as g]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]))

;; =============================================================================
;; Static Ontology Tests
;; =============================================================================

(deftest test-static-ontology-structure
  (testing "Failure ontology has expected root concepts"
    (let [failure-concepts (static/get-concepts-by-scope :failure)]
      (is (some #(= "failure:Root" (:uri %)) failure-concepts))
      (is (some #(= "failure:Grounding" (:uri %)) failure-concepts))
      (is (some #(= "failure:InstructionFollowing" (:uri %)) failure-concepts))
      (is (some #(= "failure:Reasoning" (:uri %)) failure-concepts))
      (is (some #(= "failure:Completeness" (:uri %)) failure-concepts))))

  (testing "Success ontology has expected pattern categories"
    (let [success-concepts (static/get-concepts-by-scope :success)]
      (is (some #(= "success:Root" (:uri %)) success-concepts))
      (is (some #(= "success:StructuralPattern" (:uri %)) success-concepts))
      (is (some #(= "success:InstructionPattern" (:uri %)) success-concepts))
      (is (some #(= "success:DataFlowPattern" (:uri %)) success-concepts))))

  (testing "Problem ontology has expected categories"
    (let [problem-concepts (static/get-concepts-by-scope :problem)]
      (is (some #(= "problem:Root" (:uri %)) problem-concepts))
      (is (some #(= "problem:InformationRetrieval" (:uri %)) problem-concepts))
      (is (some #(= "problem:ContentGeneration" (:uri %)) problem-concepts))
      (is (some #(= "problem:Analysis" (:uri %)) problem-concepts)))))

(deftest test-static-concept-lookup
  (testing "Can find concept by URI"
    (let [hallucination (static/get-concept-by-uri "failure:Hallucination")]
      (is (= "Hallucination" (:label hallucination)))
      (is (= :failure (:scope hallucination)))
      (is (some #(= "failure:Grounding" %) (:broader hallucination)))))

  (testing "Can get narrower concepts"
    (let [narrower (static/get-narrower-concepts "failure:Grounding")]
      (is (some #(= "failure:Hallucination" (:uri %)) narrower))
      (is (some #(= "failure:Contradiction" (:uri %)) narrower)))))

(deftest test-dimension-to-failure-mapping
  (testing "Maps evaluation dimensions to failure URIs"
    (is (= "failure:Grounding" (static/get-failure-concept-for-dimension "Grounding")))
    (is (= "failure:InstructionFollowing" (static/get-failure-concept-for-dimension "Instruction Following")))
    (is (= "failure:Reasoning" (static/get-failure-concept-for-dimension "Reasoning")))
    (is (= "failure:Completeness" (static/get-failure-concept-for-dimension "Completeness")))
    (is (nil? (static/get-failure-concept-for-dimension "Unknown")))))

;; =============================================================================
;; Read Model Projection Tests
;; =============================================================================

(deftest test-concepts-projection
  (g/with-context [ctx]
    (let [oid (g/ontology! ctx "Projection")]
      (g/concept! ctx oid "test:Concept1" "Test Concept"
                  {:indicators ["test" "example"]})
      (let [concept (graph/get-concept-by-uri ctx oid "test:Concept1")]
        (is (some? concept))
        (is (= "Test Concept" (:label concept)))
        (is (= ["test" "example"] (:indicators concept))))
      (g/concept! ctx oid "test:Parent" "Parent")
      (g/concept! ctx oid "test:Child" "Child")
      (g/successful! (graph/create-relationship! ctx
                      {:source-ontology-id oid :target-ontology-id oid
                       :source-uri "test:Child" :target-uri "test:Parent"
                       :predicate "skos:broader"}))
      (is (contains? (:broader (graph/get-concept-by-uri ctx oid "test:Child")) "test:Parent"))
      (is (contains? (:narrower (graph/get-concept-by-uri ctx oid "test:Parent")) "test:Child")))))

(deftest test-tree-profiles-projection
  (testing "Records tree strengths"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-strength-recorded
                   :tree-id tree-id
                   :pattern-uri "success:ValidationLoop"
                   :confidence 0.85
                   :evidence-trace-ids [(random-uuid) (random-uuid)]
                   :avg-score 0.9
                   :recorded-at "2024-01-01T00:00:00Z"}]
          result (rm/tree-profiles {} events)
          profile (get result tree-id)]
      (is (some? profile))
      (is (= 1 (count (:strengths profile))))
      (is (= "success:ValidationLoop" (-> profile :strengths first :pattern)))))

  (testing "Records tree weaknesses"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-weakness-recorded
                   :tree-id tree-id
                   :failure-uri "failure:Hallucination"
                   :subtype-uri "failure:FactHallucination"
                   :frequency 0.3
                   :severity :high
                   :triggers ["missing context" "ambiguous input"]
                   :evidence-trace-ids [(random-uuid)]
                   :recorded-at "2024-01-01T00:00:00Z"}]
          result (rm/tree-profiles {} events)
          profile (get result tree-id)]
      (is (some? profile))
      (is (= 1 (count (:weaknesses profile))))
      (is (= "failure:Hallucination" (-> profile :weaknesses first :failure)))
      (is (= :high (-> profile :weaknesses first :severity)))))

  (testing "Records problem mappings"
    (let [tree-id (random-uuid)
          events [{:event/type :ontology/tree-problem-mapping-created
                   :tree-id tree-id
                   :problem-uri "problem:Classification"
                   :success-rate 0.85
                   :execution-count 100
                   :recorded-at "2024-01-01T00:00:00Z"}]
          result (rm/tree-profiles {} events)
          profile (get result tree-id)]
      (is (= 1 (count (:solves profile))))
      (is (= "problem:Classification" (-> profile :solves first :problem-uri)))
      (is (= 0.85 (-> profile :solves first :success-rate))))))

(deftest test-node-experiences-projection
  (testing "Aggregates patterns by node type"
    (let [events [{:event/type :ontology/node-pattern-learned
                   :node-id (random-uuid)
                   :sheet-id (random-uuid)
                   :node-type :llm
                   :pattern-type :instruction
                   :effective? true
                   :pattern-description "Use explicit output schemas"
                   :metrics {:success-rate 0.9 :avg-score 0.85}
                   :evidence-trace-ids [(random-uuid)]
                   :learned-at "2024-01-01T00:00:00Z"}
                  {:event/type :ontology/node-pattern-learned
                   :node-id (random-uuid)
                   :sheet-id (random-uuid)
                   :node-type :llm
                   :pattern-type :instruction
                   :effective? false
                   :pattern-description "Vague instructions without examples"
                   :metrics {:success-rate 0.3 :failure-rate 0.7}
                   :evidence-trace-ids [(random-uuid)]
                   :learned-at "2024-01-01T00:00:00Z"}]
          result (rm/node-experiences {} events)]
      (is (contains? result :llm))
      (is (contains? (get result :llm) :instruction))
      (is (= 1 (count (get-in result [:llm :instruction :effective]))))
      (is (= 1 (count (get-in result [:llm :instruction :ineffective])))))))

;; =============================================================================
;; Serialization Tests
;; =============================================================================

(deftest test-concepts-to-turtle
  (testing "Generates valid SKOS Turtle"
    (let [concepts {"test:Concept1" {:uri "test:Concept1"
                                     :id (random-uuid)
                                     :label "Test Concept"
                                     :description "A test concept"
                                     :scope :custom
                                     :broader #{}
                                     :narrower #{}
                                     :related #{}
                                     :indicators []
                                     :created-at "2024-01-01"}}
          result (serialization/concepts->turtle concepts)]
      (is (str/includes? result "@prefix skos:"))
      (is (str/includes? result "@prefix owl:"))
      (is (str/includes? result "skos:Concept"))
      (is (str/includes? result "Test Concept"))))

  (testing "Includes broader relationships"
    (let [concepts {"test:Child" {:uri "test:Child"
                                  :id (random-uuid)
                                  :label "Child Concept"
                                  :description "A child concept"
                                  :scope :custom
                                  :broader #{"test:Parent"}
                                  :narrower #{}
                                  :related #{}
                                  :indicators []
                                  :created-at "2024-01-01"}}
          result (serialization/concepts->turtle concepts)]
      (is (str/includes? result "skos:broader")))))

(deftest test-tree-profile-to-turtle
  (testing "Generates OWL individuals for tree profile"
    (let [profile {:tree-id (random-uuid)
                   :strengths [{:pattern "success:ValidationLoop"
                               :confidence 0.85
                               :evidence-count 5
                               :avg-score 0.9}]
                   :weaknesses [{:failure "failure:Hallucination"
                                :frequency 0.2
                                :severity :medium
                                :triggers ["missing context"]}]
                   :solves [{:problem-uri "problem:Classification"
                            :success-rate 0.85
                            :execution-count 100}]}
          result (serialization/tree-profile->turtle profile)]
      (is (str/includes? result "tree:TreeProfile"))
      (is (str/includes? result "tree:Strength"))
      (is (str/includes? result "tree:confidence"))
      (is (str/includes? result "tree:Weakness"))
      (is (str/includes? result "tree:ProblemMapping")))))

(deftest test-node-experiences-to-turtle
  (testing "Generates OWL individuals for node experiences"
    (let [experiences {:llm {:instruction {:effective [{:pattern "Use explicit schemas"
                                                        :metrics {:success-rate 0.9}
                                                        :evidence-count 10}]
                                           :ineffective [{:pattern "Vague instructions"
                                                         :metrics {:success-rate 0.3}
                                                         :evidence-count 5}]}}}
          result (serialization/node-experiences->turtle experiences)]
      (is (str/includes? result "pattern:LearnedPattern"))
      (is (str/includes? result "pattern:nodeType"))
      (is (str/includes? result "pattern:effective")))))

(deftest test-turtle-validation
  (testing "Validates balanced quotes"
    (let [valid-ttl "@prefix ex: <http://example.org/> .\nex:Test a ex:Concept ."
          invalid-ttl "@prefix ex: <http://example.org/> .\nex:Test a ex:Concept ; rdfs:label \"unbalanced ."]
      (is (:valid? (serialization/validate-turtle valid-ttl)))
      (is (not (:valid? (serialization/validate-turtle invalid-ttl)))))))

;; =============================================================================
;; Interface Integration Tests
;; =============================================================================

(deftest test-interface-static-access
  (testing "Interface provides access to static concepts"
    (let [all-concepts (ontology/get-static-concepts)
          failure-concepts (ontology/get-static-concepts {:scope :failure})]
      (is (> (count all-concepts) 0))
      (is (> (count failure-concepts) 0))
      (is (< (count failure-concepts) (count all-concepts)))))

  (testing "Interface provides dimension mapping"
    (is (= "failure:Grounding" (ontology/get-failure-concept-for-dimension "Grounding")))))

;; =============================================================================
;; Classification Tests
;; =============================================================================

(deftest test-classify-evaluation-basic
  (testing "Classifies low-scoring dimensions as failures"
    (let [evaluation {:score 0.5
                      :dimensions [{:name "Grounding" :score 0.3 :feedback "The output contained hallucinated claims"}
                                   {:name "Instruction Following" :score 0.9 :feedback "Good"}
                                   {:name "Reasoning" :score 0.4 :feedback "Circular reasoning detected"}
                                   {:name "Completeness" :score 0.8 :feedback "Good"}]}
          result (classifier/classify-evaluation evaluation)]
      (is (= 2 (count (:failures result))))
      (is (some? (:primary-failure-uri result)))
      (is (= 0.5 (:overall-score result)))))

  (testing "Uses custom threshold"
    (let [evaluation {:score 0.5
                      :dimensions [{:name "Grounding" :score 0.5 :feedback "Some issues"}]}
          result-default (classifier/classify-evaluation evaluation)
          result-strict (classifier/classify-evaluation evaluation {:threshold 0.8})]
      ;; With default threshold 0.7, score 0.5 is a failure
      (is (= 1 (count (:failures result-default))))
      ;; With threshold 0.8, score 0.5 is still a failure
      (is (= 1 (count (:failures result-strict)))))))

(deftest test-classify-evaluation-canonical-evaluator-dimensions
  (testing "Maps the evaluation component's canonical dimension names"
    (let [evaluation {:score 0.4
                      :dimensions [{:name "Source Grounding"
                                    :score 0.2
                                    :feedback "The response contains an unsupported claim"}
                                   {:name "Reasoning Quality"
                                    :score 0.3
                                    :feedback "The routing conclusion is unexplained"}]}
          failures (:failures (classifier/classify-evaluation evaluation))]
      (is (= ["failure:Grounding" "failure:Reasoning"]
             (mapv :base-uri failures)))
      (is (= ["Source Grounding" "Reasoning Quality"]
             (mapv :dimension failures)))
      (is (every? #(str/starts-with? (:uri %) "failure:") failures)))))

(deftest test-classify-evaluation-subtype-detection
  (testing "Detects hallucination subtype from feedback"
    (let [evaluation {:score 0.3
                      :dimensions [{:name "Grounding" :score 0.2
                                    :feedback "The output contained hallucinated facts that were made up"}]}
          result (classifier/classify-evaluation evaluation)
          failure (first (:failures result))]
      (is (some? failure))
      (is (= "failure:Grounding" (:base-uri failure)))
      ;; Should detect Hallucination subtype
      (is (= "failure:Hallucination" (:uri failure)))))

  (testing "Detects format violation from feedback"
    (let [evaluation {:score 0.4
                      :dimensions [{:name "Instruction Following" :score 0.3
                                    :feedback "Output was in wrong format - expected JSON but got plain text"}]}
          result (classifier/classify-evaluation evaluation)
          failure (first (:failures result))]
      (is (some? failure))
      (is (= "failure:InstructionFollowing" (:base-uri failure)))
      ;; Should detect FormatViolation subtype
      (is (= "failure:FormatViolation" (:uri failure)))))

  (testing "Falls back to base failure when no subtype matches"
    (let [evaluation {:score 0.4
                      :dimensions [{:name "Grounding" :score 0.4
                                    :feedback "Generic grounding issue with no specific indicators"}]}
          result (classifier/classify-evaluation evaluation)
          failure (first (:failures result))]
      ;; Should fall back to base failure URI
      (is (= "failure:Grounding" (:uri failure))))))

(deftest test-classify-trace-evaluations-batch
  (testing "Aggregates failures across multiple traces"
    (let [evaluations [{:trace-id (random-uuid)
                        :evaluation-result {:score 0.3
                                            :dimensions [{:name "Grounding" :score 0.2
                                                          :feedback "Hallucinated content"}]}}
                       {:trace-id (random-uuid)
                        :evaluation-result {:score 0.4
                                            :dimensions [{:name "Grounding" :score 0.3
                                                          :feedback "Made up facts"}]}}
                       {:trace-id (random-uuid)
                        :evaluation-result {:score 0.9
                                            :dimensions [{:name "Grounding" :score 0.9
                                                          :feedback "Good"}]}}]
          result (classifier/classify-trace-evaluations evaluations)]
      (is (= 3 (-> result :summary :total-traces)))
      (is (= 2 (-> result :summary :failed-traces)))
      ;; Check aggregation by failure
      (is (contains? (:by-failure result) "failure:Hallucination")))))

(deftest test-severity-estimation
  (testing "Estimates severity based on failure type and confidence"
    (is (= :critical (classifier/estimate-severity {:uri "failure:Hallucination"
                                                     :confidence 0.9
                                                     :dimension-score 0.1})))
    (is (= :high (classifier/estimate-severity {:uri "failure:Grounding"
                                                 :confidence 0.7
                                                 :dimension-score 0.3})))
    (is (= :medium (classifier/estimate-severity {:uri "failure:Completeness"
                                                   :confidence 0.5
                                                   :dimension-score 0.5})))
    (is (= :low (classifier/estimate-severity {:uri "failure:TruncatedOutput"
                                                :confidence 0.2
                                                :dimension-score 0.6})))))

(deftest test-trigger-extraction
  (testing "Extracts trigger phrases from evidence"
    (let [failures [{:evidence "The model did not include required fields. Missing customer ID and timestamp."}
                    {:evidence "Format was incorrect."}]
          triggers (classifier/extract-triggers failures)]
      (is (vector? triggers))
      (is (every? string? triggers))))

  (testing "Handles empty evidence"
    (let [failures [{:evidence nil} {:evidence ""}]
          triggers (classifier/extract-triggers failures)]
      (is (empty? triggers)))))

(deftest test-interface-classification
  (testing "Interface exposes classification functions"
    (let [evaluation {:score 0.4
                      :dimensions [{:name "Grounding" :score 0.3 :feedback "Hallucinated"}]}
          result (ontology/classify-evaluation evaluation)]
      (is (map? result))
      (is (contains? result :failures))
      (is (contains? result :primary-failure-uri)))))

;; =============================================================================
;; Integration Tests (event-sourced round-trip)
;; =============================================================================

(deftest full-export-integration-test
  (testing "full-export combines standalone graph data with ORC-owned profiles"
    (h/with-test-context [ctx]
      (let [oid (g/ontology! ctx "Export")]
        (g/concept! ctx oid "failure:TestHallucination" "Test Hallucination"
                    {:scope :failure :indicators ["hallucinated" "made up"]})
        (h/setup-test-tree! ctx)
        (let [node-id (random-uuid) sheet-id (random-uuid)]
          (h/run-and-apply! ctx
            (fn [c] (cmd/ontology-record-node-pattern
                      (assoc c :command (h/make-node-pattern-data node-id sheet-id))))))
        (let [ttl (serialization/full-export ctx)]
          (is (string? ttl))
          (is (str/includes? ttl "skos:Concept"))
          (is (str/includes? ttl "Test Hallucination"))
          (is (str/includes? ttl "tree:TreeProfile"))
          (is (str/includes? ttl "pattern:LearnedPattern"))))))
  (testing "scope filter excludes concepts with other scopes"
    (h/with-test-context [ctx]
      (let [oid (g/ontology! ctx "Scoped export")]
        (g/concept! ctx oid "failure:ScopedFailure" "Scoped Failure" {:scope :failure})
        (g/concept! ctx oid "success:ScopedSuccess" "Scoped Success" {:scope :success})
        (let [ttl (serialization/full-export ctx {:scope :failure})]
          (is (str/includes? ttl "Scoped Failure"))
          (is (not (str/includes? ttl "Scoped Success"))))))))

(deftest build-concept-graph-integration-test
  (testing "ORC graph retrieval reads the standalone event-backed graph"
    (g/with-context [ctx]
      (let [oid (g/ontology! ctx "Graph")]
        (g/concept! ctx oid "test:Parent" "Parent")
        (g/concept! ctx oid "test:Child" "Child" {:broader ["test:Parent"]})
        (let [result (retrieval/build-concept-graph ctx)]
          (is (some? result))
          (is (map? result))))))
  (testing "ORC static vocabulary remains usable without an event store"
    (let [result (retrieval/build-concept-graph {})]
      (is (some? result))
      (is (map? result)))))
