(ns ai.obney.orc.ontology.ontology-id-scoping-test
  "Ontology scoping through the standalone public graph boundary."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.ontology.interface :as graph]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.graph-test-support :as g]))

(deftest concepts-preserve-qualified-identity
  (g/with-context [ctx]
    (let [a (g/ontology! ctx "A") b (g/ontology! ctx "B")]
      (g/concept! ctx a "person:alice" "Alice")
      (g/concept! ctx b "person:alice" "Different Alice")
      (is (= "Alice" (:label (graph/get-concept-by-uri ctx a "person:alice"))))
      (is (= "Different Alice" (:label (graph/get-concept-by-uri ctx b "person:alice"))))
      (is (= a (:ontology-id (graph/get-concept-by-uri ctx a "person:alice"))))
      (is (= b (:ontology-id (graph/get-concept-by-uri ctx b "person:alice"))))
      (is (= 2 (count (graph/get-concepts ctx)))))))

(deftest ontology-filters-and-scope-compose
  (g/with-context [ctx]
    (let [a (g/ontology! ctx "A") b (g/ontology! ctx "B") c (g/ontology! ctx "C")]
      (g/concept! ctx a "person:alice" "Alice" {:scope :person})
      (g/concept! ctx a "failure:timeout" "Timeout" {:scope :failure})
      (g/concept! ctx b "failure:crash" "Crash" {:scope :failure})
      (g/concept! ctx c "product:widget" "Widget" {:scope :product})
      (is (= #{"Alice" "Timeout"} (set (map :label (rm/get-concepts ctx {:ontology-id a})))))
      (is (= #{"Crash"} (set (map :label (rm/get-concepts ctx {:ontology-id b})))))
      (is (= #{"Alice" "Timeout" "Crash"}
             (set (map :label (rm/get-concepts ctx {:ontology-ids [a b]})))))
      (is (= ["Timeout"] (mapv :label (rm/get-concepts ctx {:ontology-id a :scope :failure}))))
      (is (= 4 (count (rm/get-concepts ctx)))))))

(deftest concept-embedding-identity-and-filtering
  (g/with-context [ctx]
    (let [a (g/ontology! ctx "A") b (g/ontology! ctx "B") c (g/ontology! ctx "C")
          fields {:embedding [0.1 0.2 0.3] :embedding-model "fixture-model"}]
      (g/concept! ctx a "person:alice" "Alice" fields)
      (g/concept! ctx a "person:bob" "Bob" fields)
      (g/concept! ctx b "org:acme" "Acme" fields)
      (g/concept! ctx c "product:widget" "Widget" fields)
      (is (= [0.1 0.2 0.3] (:embedding (graph/get-concept-by-uri ctx a "person:alice"))))
      (is (= a (:ontology-id (graph/get-concept-by-uri ctx a "person:alice"))))
      (is (= 2 (count (rm/get-all-concept-embeddings ctx {:ontology-id a}))))
      (is (= 1 (count (rm/get-all-concept-embeddings ctx {:ontology-id b}))))
      (is (= 3 (count (rm/get-all-concept-embeddings ctx {:ontology-ids [a b]}))))
      (is (= 4 (count (rm/get-all-concept-embeddings ctx)))))))

(deftest extracted-contribution-preserves-descriptions-types-and-aliases
  (g/with-context [ctx]
    (let [a (g/ontology! ctx "Extracted")]
      (g/contribution! ctx a
        [{:uri "person:tavidee" :label "Tavidee" :scope :Person
          :description "A person in the organization"}
         {:uri "org:bryc" :label "BRYC" :scope :Organization
          :description "Blue Ridge Youth Collective" :alt-labels ["Blue Ridge Youth Collective"]}])
      (is (= 2 (count (graph/get-concepts ctx {:ontology-id a}))))
      (let [person (graph/get-concept-by-uri ctx a "person:tavidee")
            org (graph/get-concept-by-uri ctx a "org:bryc")]
        (is (= "Tavidee" (:label person)))
        (is (= :Person (:scope person)))
        (is (= "A person in the organization" (:description person)))
        (is (= "BRYC" (:label org)))
        (is (= :Organization (:scope org)))
        (is (= ["Blue Ridge Youth Collective"] (:alt-labels org)))
        (is (= a (:ontology-id person)))))))

(deftest extracted-relationships-create-hierarchy-and-symmetric-views
  (g/with-context [ctx]
    (let [a (g/ontology! ctx "Relationships")]
      (g/contribution! ctx a
        [{:uri "failure:grounding" :label "Grounding" :description "Grounding"}
         {:uri "failure:hallucination" :label "Hallucination" :description "Hallucination"}]
        [{:subject "failure:hallucination" :predicate "skos:broader" :object "failure:grounding"}
         {:subject "failure:hallucination" :predicate "skos:related" :object "failure:grounding"}])
      (let [parent (graph/get-concept-by-uri ctx a "failure:grounding")
            child (graph/get-concept-by-uri ctx a "failure:hallucination")]
        (is (contains? (:broader child) "failure:grounding"))
        (is (contains? (:narrower parent) "failure:hallucination"))
        (is (contains? (:related child) "failure:grounding"))
        (is (contains? (:related parent) "failure:hallucination"))))))

(deftest manual-and-imported-concepts-coexist-across-ontologies
  (g/with-context [ctx]
    (let [a (g/ontology! ctx "Manual") b (g/ontology! ctx "Extracted")]
      (g/concept! ctx a "failure:timeout" "Timeout" {:scope :failure})
      (g/contribution! ctx b [{:uri "person:bob" :label "Bob" :scope :Person :description "Bob"}])
      (is (= 2 (count (graph/get-concepts ctx))))
      (is (= a (:ontology-id (graph/get-concept-by-uri ctx a "failure:timeout"))))
      (is (= b (:ontology-id (graph/get-concept-by-uri ctx b "person:bob"))))
      (is (= ["Bob"] (mapv :label (graph/get-concepts ctx {:ontology-id b})))))))
