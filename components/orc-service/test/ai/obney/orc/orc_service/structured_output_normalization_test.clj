(ns ai.obney.orc.orc-service.structured-output-normalization-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]))

(defn- validate [blackboard result]
  (#'processors/validate-leaf-outputs blackboard result))

(deftest json-object-keys-are-normalized-by-the-declared-schema
  (testing "nested model JSON maps satisfy keyword-keyed blackboard schemas"
    (let [schema [:vector [:map
                           [:label :string]
                           [:metadata [:map-of :string :string]]]]
          result (validate {:concepts {:schema schema}}
                           {:status :success
                            :outputs {:concepts [{"label" "Queue"
                                                  "metadata" {"source" "live"}}]}})]
      (is (= :success (:status result)))
      (is (= [{:label "Queue" :metadata {"source" "live"}}]
             (get-in result [:outputs :concepts])))
      (is (string? (-> result :outputs :concepts first :metadata keys first))
          ":map-of keys retain their declared string type")))

  (testing "normalization does not make genuinely invalid values pass"
    (let [result (validate {:concepts {:schema [:vector [:map [:label :string]]]} }
                           {:status :success
                            :outputs {:concepts [{"wrong" "Queue"}]}})]
      (is (= :failure (:status result)))
      (is (re-find #"missing required key" (:error result)))
      (is (= {} (:outputs result))))))
