(ns ai.obney.orc.llm.canonical-provider-value-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.llm.interface :as llm]
            [malli.core :as m]))

(deftest valid-native-dictionaries-are-not-reinterpreted-as-provider-json
  (doseq [[schema value]
          [[[:map-of [:enum 0 "0" :audit/count] [:maybe :int]] {0 0 "0" nil :audit/count 1}]
           [[:map {:closed true} [:rows [:vector [:map-of [:enum :app/ready "app/pending"] :boolean]]]]
            {:rows [{:app/ready false "app/pending" true}]}]
           [[:map {:closed true} [:same :string] ["same" :int]] {:same "keyword" "same" 0}]]]
    (is (m/validate schema value))
    (is (identical? value (llm/decode-provider-value schema value)))
    (is (m/validate schema (llm/decode-provider-value schema value)))))

(deftest incomplete-raw-json-still-uses-declared-conversion
  (let [schema [:map [:state [:enum :app/ready]] [:score :double]]
        raw {"state" "app/ready" "score" 1}
        decoded (llm/decode-provider-value schema raw)]
    (is (= {:state :app/ready :score 1.0} decoded))
    (is (m/validate schema decoded))))

(deftest raw-sibling-decoding-preserves-valid-nested-dictionary
  (let [schema [:map [:score :double] [:metrics [:map-of [:enum 0 "0" :audit/count] :int]]]
        value {:score 1 :metrics {0 0 "0" 0 :audit/count 0}}
        decoded (llm/decode-provider-value schema value)]
    (is (= {:score 1.0 :metrics {0 0 "0" 0 :audit/count 0}} decoded))
    (is (m/validate schema decoded))))
