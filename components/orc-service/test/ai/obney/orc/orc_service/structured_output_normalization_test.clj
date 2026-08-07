(ns ai.obney.orc.orc-service.structured-output-normalization-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(defn- validate [blackboard result]
  (executor/validate-leaf-outputs blackboard result true))

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
      (is (= {} (:outputs result)))
      (is (= {:concepts [{"wrong" "Queue"}]}
             (:rejected-writes result))))))

(deftest provider-json-numbers-are-decoded-through-the-declared-schema
  (testing "an integral JSON number satisfies a double contract canonically"
    (let [result (validate {:academic-context
                            {:schema [:map [:gpa :double]]}}
                           {:status :success
                            :outputs {:academic-context {"gpa" (long 3)}}})]
      (is (= :success (:status result)))
      (is (= 3.0 (get-in result [:outputs :academic-context :gpa])))
      (is (double? (get-in result [:outputs :academic-context :gpa])))))

  (testing "numeric strings remain invalid and are retained only as rejected evidence"
    (let [raw {:academic-context {"gpa" "3.0"}}
          result (validate {:academic-context
                            {:schema [:map [:gpa :double]]}}
                           {:status :success
                            :outputs raw
                            :raw-response "provider response"})]
      (is (= :failure (:status result)))
      (is (= {} (:outputs result)))
      (is (= raw (:rejected-writes result)))
      (is (= "provider response" (:raw-response result)))
      (is (re-find #"should be a double" (:error result)))))

  (testing "JSON decoding follows declared schemas through nested vectors"
    (let [result (validate {:academic-history
                            {:schema [:vector [:map [:gpa :double]]]}}
                           {:status :success
                            :outputs {:academic-history [{"gpa" (long 3)}
                                                         {"gpa" 3.5}]}})]
      (is (= [{:gpa 3.0} {:gpa 3.5}]
             (get-in result [:outputs :academic-history])))
      (is (every? double?
                  (map :gpa (get-in result [:outputs :academic-history]))))))

  (testing "non-provider executors retain strict JVM type validation"
    (let [result (executor/validate-leaf-outputs
                  {:academic-context {:schema [:map [:gpa :double]]}}
                  {:status :success
                   :outputs {:academic-context {:gpa (long 3)}}}
                  false)]
      (is (= :failure (:status result)))
      (is (= (long 3)
             (get-in result [:rejected-writes :academic-context :gpa]))))))
