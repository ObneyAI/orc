(ns ai.obney.orc.ontology.evolutionary-builder-test
  "Tests for evolutionary builder Grain schema compliance.

   Issue 001: Verify ontology-id normalization produces valid UUID tags.
   Issue 004: Integration test for full Grain compliance."
  (:require [ai.obney.orc.ontology.core.commands :as commands]
            [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as ontology]))


(def normalize-ontology-id #'logic/normalize-ontology-id)

(deftest normalize-ontology-id-converts-strings-to-uuids
  (testing "string ontology-id is converted to UUID"
    (let [result (normalize-ontology-id "my-test-ontology")]
      (is (uuid? result)
          "String should be converted to UUID")))

  (testing "conversion is deterministic - same string produces same UUID"
    (let [result1 (normalize-ontology-id "transcript-corrections")
          result2 (normalize-ontology-id "transcript-corrections")]
      (is (= result1 result2)
          "Same string should always produce same UUID")))

  (testing "different strings produce different UUIDs"
    (let [result1 (normalize-ontology-id "ontology-a")
          result2 (normalize-ontology-id "ontology-b")]
      (is (not= result1 result2)
          "Different strings should produce different UUIDs"))))

(deftest normalize-ontology-id-passes-through-uuids
  (testing "UUID ontology-id passes through unchanged"
    (let [original-uuid (random-uuid)
          result (normalize-ontology-id original-uuid)]
      (is (= original-uuid result)
          "UUID should pass through unchanged"))))

(deftest duplicate-content-is-deduplicated-within-one-build
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx {:event-store store :tenant-id (random-uuid)}
        calls (atom 0)
        source {:type "text" :content "same immutable source"}]
    (try
      (with-redefs [logic/extract-from-source
                    (fn [_ source-id _ _ _]
                      (swap! calls inc)
                      {:concepts [{:uri "urn:a" :label "A" :definition "Definition"
                                   :source-id source-id}] :relationships []})]
        (let [result (commands/evolutionary-builder-build-from-sources ctx
                       {:sources [source source]
                        :config {:ontology-id "my-test-ontology"
                                 :enable-colbert? false :enable-embeddings? false}})
              oid (:ontology-id result)]
          (is (uuid? oid))
          (is (= oid (:ontology-id (commands/evolutionary-builder-build-from-sources ctx
                                     {:sources [source] :config {:ontology-id "my-test-ontology"
                                                               :enable-colbert? false :enable-embeddings? false}}))))
          (is (not= (normalize-ontology-id "my-test-ontology") oid))
          (is (= 1 @calls) "A second input sees the first committed contribution immediately")
          (is (= 1 (count (ontology/get-sources ctx oid))))
          (is (= 1 (:new-sources-processed result)))
          (is (= 1 (count (ontology/get-contributions ctx oid))))))
      (finally (es/stop store)))))
