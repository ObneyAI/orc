(ns ai.obney.orc.mcp-sheet-builder.schema-converter-test
  "Tests for JSON Schema to Malli conversion."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.mcp-sheet-builder.interface :as msb]))

;; ============================================================================
;; Primitive Type Tests
;; ============================================================================

(deftest json-schema-to-malli-string-test
  (testing "converts string type"
    (is (= :string (msb/json-schema->malli {"type" "string"})))))

(deftest json-schema-to-malli-integer-test
  (testing "converts integer type"
    (is (= :int (msb/json-schema->malli {"type" "integer"})))))

(deftest json-schema-to-malli-number-test
  (testing "converts number type"
    (is (= :double (msb/json-schema->malli {"type" "number"})))))

(deftest json-schema-to-malli-boolean-test
  (testing "converts boolean type"
    (is (= :boolean (msb/json-schema->malli {"type" "boolean"})))))

;; ============================================================================
;; Description Preservation Tests
;; ============================================================================

(deftest json-schema-preserves-description-test
  (testing "preserves field descriptions"
    (let [schema {"type" "string" "description" "User name"}
          result (msb/json-schema->malli schema)]
      (is (vector? result))
      (is (= :string (first result)))
      (is (= "User name" (get-in result [1 :description]))))))

(deftest json-schema-no-description-test
  (testing "works without description"
    (let [result (msb/json-schema->malli {"type" "string"})]
      (is (= :string result)))))

;; ============================================================================
;; Object Type Tests
;; ============================================================================

(deftest json-schema-to-malli-object-test
  (testing "converts object with properties"
    (let [schema {"type" "object"
                  "properties" {"name" {"type" "string"}
                                "age" {"type" "integer"}}
                  "required" ["name"]}
          result (msb/json-schema->malli schema)]
      (is (vector? result))
      (is (= :map (first result))))))

(deftest json-schema-object-required-fields-test
  (testing "marks required fields correctly"
    (let [schema {"type" "object"
                  "properties" {"query" {"type" "string"}}
                  "required" ["query"]}
          result (msb/json-schema->malli schema)]
      (is (= :map (first result)))
      (let [[_ & props] result
            query-prop (first (filter #(= :query (first %)) props))]
        (is (some? query-prop))
        (is (= 2 (count query-prop)))))))

(deftest json-schema-object-optional-fields-test
  (testing "marks optional fields correctly"
    (let [schema {"type" "object"
                  "properties" {"limit" {"type" "integer"}}
                  "required" []}
          result (msb/json-schema->malli schema)]
      (is (= :map (first result)))
      (let [[_ & props] result
            limit-prop (first (filter #(= :limit (first %)) props))]
        (is (some? limit-prop))
        (is (= 3 (count limit-prop)))
        (is (= true (:optional (second limit-prop))))))))

;; ============================================================================
;; Array Type Tests
;; ============================================================================

(deftest json-schema-to-malli-array-test
  (testing "converts array with items"
    (let [schema {"type" "array"
                  "items" {"type" "string"}}
          result (msb/json-schema->malli schema)]
      (is (vector? result))
      (is (= :vector (first result)))
      (is (= :string (second result))))))

(deftest json-schema-array-with-description-test
  (testing "preserves array description"
    (let [schema {"type" "array"
                  "items" {"type" "string"}
                  "description" "List of keywords"}
          result (msb/json-schema->malli schema)]
      (is (= :vector (first result)))
      (is (= "List of keywords" (get-in result [1 :description]))))))

;; ============================================================================
;; Enum Tests
;; ============================================================================

(deftest json-schema-to-malli-enum-test
  (testing "converts enum values"
    (let [schema {"type" "string"
                  "enum" ["asc" "desc"]}
          result (msb/json-schema->malli schema)]
      (is (vector? result))
      (is (= :enum (first result)))
      (is (= ["asc" "desc"] (second result))))))

;; ============================================================================
;; Complex Nested Schema Tests
;; ============================================================================

(deftest json-schema-nested-object-test
  (testing "converts nested objects"
    (let [schema {"type" "object"
                  "properties" {"search" {"type" "object"
                                          "properties" {"query" {"type" "string"}}}}}
          result (msb/json-schema->malli schema)]
      (is (= :map (first result))))))

(deftest json-schema-map-of-test
  (testing "converts additionalProperties to map-of"
    (let [schema {"type" "object"
                  "additionalProperties" {"type" "string"}}
          result (msb/json-schema->malli schema)]
      (is (vector? result))
      (is (= :map-of (first result))))))
