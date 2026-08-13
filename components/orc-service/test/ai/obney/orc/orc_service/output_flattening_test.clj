(ns ai.obney.orc.orc-service.output-flattening-test
  "Tests for output flattening behavior in the executor.

   Output flattening aligns with Python DSPy's approach:
   - Nested :map schemas are flattened into separate output fields
   - Each field gets its own [[ ## field ## ]] marker in the prompt
   - Flattened outputs are reassembled back into nested structure

   This improves LLM output reliability by having the model fill
   individual fields rather than generating a complete JSON object."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.executor :as executor]))

;; =============================================================================
;; Helper to access private functions for testing
;; =============================================================================

(def ^:private flatten-output-schema
  #'ai.obney.orc.orc-service.core.executor/flatten-output-schema)

(def ^:private reassemble-flattened-outputs
  #'ai.obney.orc.orc-service.core.executor/reassemble-flattened-outputs)

(def ^:private map-schema?
  #'ai.obney.orc.orc-service.core.executor/map-schema?)

(def ^:private map-of-schema?
  #'ai.obney.orc.orc-service.core.executor/map-of-schema?)

;; =============================================================================
;; Schema Detection Tests
;; =============================================================================

(deftest map-schema?-test
  (testing "detects [:map ...] schemas"
    (is (true? (map-schema? [:map [:score :double] [:reasoning :string]])))
    (is (true? (map-schema? [:map [:name :string]]))))

  (testing "rejects non-map schemas"
    (is (false? (map-schema? :string)))
    (is (false? (map-schema? [:vector :string])))
    (is (false? (map-schema? [:map-of :keyword :string])))))

(deftest map-of-schema?-test
  (testing "detects [:map-of ...] schemas"
    (is (true? (map-of-schema? [:map-of :keyword :string])))
    (is (true? (map-of-schema? [:map-of :string :int]))))

  (testing "rejects non-map-of schemas"
    (is (false? (map-of-schema? :string)))
    (is (false? (map-of-schema? [:map [:name :string]])))
    (is (false? (map-of-schema? [:vector :string])))))

;; =============================================================================
;; flatten-output-schema Tests
;; =============================================================================

(deftest flatten-output-schema-nested-map-test
  (testing "flattens [:map ...] schema into separate fields"
    (let [schema [:map [:score :double] [:reasoning :string] [:keyFactors [:vector :string]]]
          result (flatten-output-schema :academic-score schema)]

      ;; Should produce 3 separate fields
      (is (= 3 (count result)))

      ;; Check each flattened field
      (let [score-field (first (filter #(= :score (:name %)) result))
            reasoning-field (first (filter #(= :reasoning (:name %)) result))
            factors-field (first (filter #(= :keyFactors (:name %)) result))]

        ;; Score field
        (is (= :score (:name score-field)))
        (is (= :academic-score (:original-key score-field)))
        (is (= "score" (:nested-key score-field)))
        (is (= :double (:spec score-field)))

        ;; Reasoning field
        (is (= :reasoning (:name reasoning-field)))
        (is (= :academic-score (:original-key reasoning-field)))
        (is (= "reasoning" (:nested-key reasoning-field)))
        (is (= :string (:spec reasoning-field)))

        ;; KeyFactors field
        (is (= :keyFactors (:name factors-field)))
        (is (= :academic-score (:original-key factors-field)))
        (is (= "keyFactors" (:nested-key factors-field)))
        (is (= [:vector :string] (:spec factors-field)))))))

(deftest flatten-output-schema-simple-test
  (testing "passes through simple schemas unchanged"
    (let [result (flatten-output-schema :answer :string)]
      (is (= 1 (count result)))
      (is (= :answer (:name (first result))))
      (is (= :answer (:original-key (first result))))
      (is (nil? (:nested-key (first result))))
      (is (= :string (:spec (first result)))))))

(deftest flatten-output-schema-map-of-test
  (testing "[:map-of ...] schema is NOT flattened (no defined field names)"
    (let [result (flatten-output-schema :personalization [:map-of :keyword :string])]
      ;; Should return single field, not flattened
      (is (= 1 (count result)))
      (is (= :personalization (:name (first result))))
      (is (= :personalization (:original-key (first result))))
      (is (nil? (:nested-key (first result))))
      (is (= [:map-of :keyword :string] (:spec (first result))))
      ;; Should have JSON guidance in description
      (is (str/includes? (:description (first result)) "JSON object")))))

(deftest flatten-output-schema-optional-fields-test
  (testing "preserves required and optional presence when flattening map entries"
    (let [schema [:map
                  [:required-field :string]
                  [:optional-field {:optional true} :int]]
          result (flatten-output-schema :data schema)
          required-field (first (filter #(= :required-field (:name %)) result))
          optional-field (first (filter #(= :optional-field (:name %)) result))]
      (is (= 2 (count result)))
      (is (false? (:optional required-field)))
      (is (true? (:optional optional-field))))))

(deftest reassemble-flattened-outputs-preserves-absence-test
  (testing "an omitted optional flattened field is not reintroduced as nil"
    (let [raw-outputs {:action :reply :reply "Done."}
          output-mapping {:action {:original-key :result :nested-key "action"}
                          :reply {:original-key :result :nested-key "reply"}
                          :capability {:original-key :result :nested-key "capability"}}
          result (reassemble-flattened-outputs raw-outputs output-mapping)]
      (is (= {:result {:action :reply :reply "Done."}} result))
      (is (not (contains? (:result result) :capability))))))

(deftest reassemble-flattened-outputs-normalizes-optional-null-test
  (testing "null is absence only for optional fields whose schemas reject nil"
    (let [raw-outputs {:optional-value nil
                       :optional-nullable nil
                       :required-value nil
                       :present-value "kept"}
          output-mapping {:optional-value {:original-key :first
                                           :nested-key "optional-value"
                                           :optional true
                                           :spec :string}
                          :optional-nullable {:original-key :first
                                              :nested-key "optional-nullable"
                                              :optional true
                                              :spec [:maybe :string]}
                          :required-value {:original-key :second
                                           :nested-key "required-value"
                                           :optional false
                                           :spec :string}
                          :present-value {:original-key :second
                                          :nested-key "present-value"
                                          :optional true
                                          :spec :string}}
          result (reassemble-flattened-outputs raw-outputs output-mapping)]
      (is (= {:first {:optional-nullable nil}
              :second {:required-value nil :present-value "kept"}}
             result))
      (is (not (contains? (:first result) :optional-value))))))

;; =============================================================================
;; reassemble-flattened-outputs Tests
;; =============================================================================

(deftest reassemble-flattened-outputs-test
  (testing "reassembles flattened outputs into nested structure"
    (let [raw-outputs {:score 0.85
                       :reasoning "Strong academic match"
                       :keyFactors ["GPA" "Test scores"]}
          output-mapping {:score {:original-key :academic-score :nested-key "score"}
                          :reasoning {:original-key :academic-score :nested-key "reasoning"}
                          :keyFactors {:original-key :academic-score :nested-key "keyFactors"}}
          result (reassemble-flattened-outputs raw-outputs output-mapping)]

      ;; Should produce single key with nested map
      (is (= 1 (count result)))
      (is (contains? result :academic-score))

      ;; Check nested values
      (let [nested (get result :academic-score)]
        (is (= 0.85 (:score nested)))
        (is (= "Strong academic match" (:reasoning nested)))
        (is (= ["GPA" "Test scores"] (:keyFactors nested)))))))

(deftest reassemble-flattened-outputs-multiple-keys-test
  (testing "reassembles multiple separate output keys"
    (let [raw-outputs {:score 0.8
                       :answer "The answer is 42"}
          output-mapping {:score {:original-key :rating :nested-key nil}
                          :answer {:original-key :response :nested-key nil}}
          result (reassemble-flattened-outputs raw-outputs output-mapping)]

      ;; Should produce two separate keys (not nested)
      (is (= 2 (count result)))
      (is (= 0.8 (get result :rating)))
      (is (= "The answer is 42" (get result :response))))))

(deftest reassemble-flattened-outputs-mixed-test
  (testing "handles mix of nested and non-nested outputs"
    (let [raw-outputs {:score 0.9
                       :reasoning "Good fit"
                       :summary "Brief summary"}
          output-mapping {:score {:original-key :evaluation :nested-key "score"}
                          :reasoning {:original-key :evaluation :nested-key "reasoning"}
                          :summary {:original-key :summary :nested-key nil}}
          result (reassemble-flattened-outputs raw-outputs output-mapping)]

      ;; Should have nested evaluation and flat summary
      (is (= 2 (count result)))
      (is (map? (get result :evaluation)))
      (is (= 0.9 (get-in result [:evaluation :score])))
      (is (= "Good fit" (get-in result [:evaluation :reasoning])))
      (is (= "Brief summary" (get result :summary))))))

;; =============================================================================
;; build-module Integration Tests
;; =============================================================================

(deftest build-module-flattening-test
  (testing "build-module correctly flattens nested map outputs"
    (let [node {:name "score-node"
                :instruction "Score this item"
                :reads [:input-data]
                :writes [:score-result]}
          blackboard {:input-data {:key :input-data
                                    :schema :string
                                    :value "test input"
                                    :version 1}
                      :score-result {:key :score-result
                                      :schema [:map
                                               [:score :double]
                                               [:reasoning :string]]
                                      :value nil
                                      :version 0}}
          module (executor/build-module node blackboard)]

      ;; Check outputs are flattened
      (is (= 2 (count (:outputs module))))
      (is (some #(= :score (:name %)) (:outputs module)))
      (is (some #(= :reasoning (:name %)) (:outputs module)))

      ;; Check output-mapping exists
      (is (contains? module :output-mapping))
      (is (= :score-result (get-in module [:output-mapping :score :original-key])))
      (is (= "score" (get-in module [:output-mapping :score :nested-key])))
      (is (false? (get-in module [:output-mapping :score :optional])))
      (is (= :double (get-in module [:output-mapping :score :spec]))))))

(deftest build-module-simple-output-test
  (testing "build-module handles simple (non-nested) outputs"
    (let [node {:name "simple-node"
                :instruction "Answer the question"
                :reads [:question]
                :writes [:answer]}
          blackboard {:question {:key :question
                                  :schema :string
                                  :value "What is 2+2?"
                                  :version 1}
                      :answer {:key :answer
                                :schema :string
                                :value nil
                                :version 0}}
          module (executor/build-module node blackboard)]

      ;; Should have single output
      (is (= 1 (count (:outputs module))))
      (is (= :answer (:name (first (:outputs module)))))
      (is (nil? (get-in module [:output-mapping :answer :nested-key]))))))

(deftest build-module-requires-declared-schemas-test
  (testing "missing read and write schemas fail before an LLM request is built"
    (let [node {:name "schema-guard"
                :instruction "Produce structured output"
                :reads [:present :missing-input]
                :writes [:missing-output]}
          blackboard {:present {:key :present :schema :string :value "x"}}
          error (try
                  (executor/build-module node blackboard)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? error))
      (is (str/includes? (.getMessage error) "schema-guard"))
      (is (= [:missing-input] (:missing-reads (ex-data error))))
      (is (= [:missing-output] (:missing-writes (ex-data error))))
      (is (= [:present] (:blackboard-keys (ex-data error)))))))

;; =============================================================================
;; Warning Test for [:map-of ...] schemas
;; =============================================================================

(deftest map-of-schema-warning-test
  (testing "build-module warns when using [:map-of ...] schema"
    (let [node {:name "loose-schema-node"
                :instruction "Generate data"
                :reads [:input]
                :writes [:output]}
          blackboard {:input {:key :input
                               :schema :string
                               :value "test"
                               :version 1}
                      :output {:key :output
                                :schema [:map-of :keyword :string]
                                :value nil
                                :version 0}}
          ;; Capture stdout to check for warning
          output (with-out-str
                   (executor/build-module node blackboard))]

      ;; Should print warning about map-of schema
      (is (str/includes? output "[WARN]"))
      (is (str/includes? output "[:map-of ...]"))
      (is (str/includes? output "loose-schema-node")))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest empty-map-schema-test
  (testing "handles empty [:map] schema"
    (let [result (flatten-output-schema :empty [:map])]
      ;; Empty map should produce empty flattened fields
      (is (= 0 (count result))))))

(deftest deeply-nested-schema-test
  (testing "flattens only top-level map fields (not recursive)"
    (let [schema [:map
                  [:outer :string]
                  [:nested [:map [:inner :int]]]]
          result (flatten-output-schema :data schema)]
      ;; Should flatten to 2 fields: :outer and :nested
      ;; The nested [:map] becomes the spec for :nested, not further flattened
      (is (= 2 (count result)))
      (let [nested-field (first (filter #(= :nested (:name %)) result))]
        (is (= [:map [:inner :int]] (:spec nested-field)))))))
