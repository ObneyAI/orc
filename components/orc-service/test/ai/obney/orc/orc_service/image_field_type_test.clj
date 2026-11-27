(ns ai.obney.orc.orc-service.image-field-type-test
  "Tests for :field-type :image support via Malli schema properties."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))


(deftest build-field-image-type-test
  (testing "schema with {:field-type :image} property sets :type :image on field"
    (let [entry {:key :document-images
                 :schema [:vector {:field-type :image} :string]
                 :value nil
                 :version 0}
          field (#'service-logic/build-field :document-images entry)]
      (is (= :image (:type field)))
      (is (= :document-images (:name field)))))

  (testing "plain schema without properties has no :type"
    (let [entry {:key :instruction
                 :schema :string
                 :value nil
                 :version 0}
          field (#'service-logic/build-field :instruction entry)]
      (is (nil? (:type field)))
      (is (= :instruction (:name field)))))

  (testing "vector schema without :field-type property has no :type"
    (let [entry {:key :items
                 :schema [:vector :string]
                 :value nil
                 :version 0}
          field (#'service-logic/build-field :items entry)]
      (is (nil? (:type field)))))

  (testing "future extensibility — :field-type :audio works too"
    (let [entry {:key :audio-clip
                 :schema [:vector {:field-type :audio} :string]
                 :value nil
                 :version 0}
          field (#'service-logic/build-field :audio-clip entry)]
      (is (= :audio (:type field))))))


(deftest gather-inputs-image-serialization-test
  (testing "image-typed values are NOT JSON-serialized"
    (let [images ["data:image/png;base64,abc123" "data:image/png;base64,def456"]
          node {:reads [:document-images]}
          blackboard {:document-images {:key :document-images
                                        :schema [:vector {:field-type :image} :string]
                                        :value images
                                        :version 1}}
          result (service-logic/gather-inputs node blackboard)]
      (is (vector? (:document-images result)))
      (is (= 2 (count (:document-images result))))
      (is (= "data:image/png;base64,abc123" (first (:document-images result))))))

  (testing "non-image vectors retain native identity until request rendering"
    (let [items ["a" "b" "c"]
          node {:reads [:items]}
          blackboard {:items {:key :items
                              :schema [:vector :string]
                              :value items
                              :version 1}}
          result (service-logic/gather-inputs node blackboard)]
      (is (= items (:items result)))))

  (testing "plain string values pass through unchanged"
    (let [node {:reads [:instruction]}
          blackboard {:instruction {:key :instruction
                                    :schema :string
                                    :value "do something"
                                    :version 1}}
          result (service-logic/gather-inputs node blackboard)]
      (is (= "do something" (:instruction result))))))


(deftest build-module-image-type-test
  (testing "module input for image-typed blackboard key has :type :image"
    (let [node {:reads [:document-images :instruction]
                :writes [:result]
                :instruction "Analyze images"}
          blackboard {:document-images {:key :document-images
                                        :schema [:vector {:field-type :image} :string]
                                        :value ["data:image/png;base64,abc"]
                                        :version 1}
                      :instruction {:key :instruction
                                    :schema :string
                                    :value "test"
                                    :version 1}
                      :result {:key :result
                               :schema :map
                               :value nil
                               :version 0}}
          module (service-logic/build-module node blackboard)
          image-input (first (filter #(= :document-images (:name %)) (:inputs module)))
          text-input (first (filter #(= :instruction (:name %)) (:inputs module)))]
      (is (= :image (:type image-input)))
      (is (nil? (:type text-input))))))
