(ns ai.obney.orc.orc-service.structured-output-normalization-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [dscloj.core :as dscloj]))

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

(deftest provider-keyword-enums-use-canonical-json-spellings
  (testing "provider-facing keyword enum choices omit EDN's leading colon"
    (is (= "one of: changed, unchanged"
           (executor/malli-schema->description
            [:enum :changed :unchanged])))
    (is (= "one of: decision/changed, decision/unchanged"
           (executor/malli-schema->description
            [:enum :decision/changed :decision/unchanged])))
    (is (= "one of: changed, unchanged"
           (executor/malli-schema->description
            [:enum "changed" "unchanged"])))
    (is (= "changed"
           (get-in (validate {:outcome
                              {:schema [:enum "changed" "unchanged"]}}
                             {:status :success
                              :outputs {:outcome "changed"}})
                   [:outputs :outcome]))))

  (testing "function-calling tool schemas use the same canonical spellings"
    (let [tool-definition
          (dscloj/outputs->tool-definition
           {:outputs [{:name :outcome
                       :spec [:enum :changed :unchanged]
                       :description "Learning outcome"}]})]
      (is (= ["changed" "unchanged"]
             (get-in tool-definition
                     [:function :parameters :properties "outcome" :enum])))))

  (testing "function-calling keeps root unions of map variants structured"
    (let [decision-schema
          [:or
           [:map
            [:action [:= :invoke]]
            [:capability :string]
            [:world-changing? :boolean]]
           [:map
            [:action [:= :respond]]
            [:message :string]]]
          tool-schema
          (get-in
           (dscloj/outputs->tool-definition
            {:outputs [{:name :decision :spec decision-schema}]})
           [:function :parameters :properties "decision"])]
      (is (nil? (:type tool-schema))
          "a structured union is not advertised as a JSON string")
      (is (= ["object" "object"]
             (mapv :type (:oneOf tool-schema))))
      (is (= {:const "invoke"}
             (get-in tool-schema [:oneOf 0 :properties "action"])))
      (is (= {:const "respond"}
             (get-in tool-schema [:oneOf 1 :properties "action"])))
      (let [result
            (validate
             {:decision {:schema decision-schema}}
             {:status :success
              :outputs
              {:decision
               {:action "invoke"
                :capability "filesystem.read-text"
                :world-changing? false}}})]
        (is (= :success (:status result)))
        (is (= {:action :invoke
                :capability "filesystem.read-text"
                :world-changing? false}
               (get-in result [:outputs :decision]))))))

  (testing "canonical JSON strings decode recursively to declared keywords"
    (let [schema [:map
                  [:outcome [:enum :changed :unchanged]]
                  [:relations [:vector [:enum :supports :contradicts]]]]
          result (validate {:decision {:schema schema}}
                           {:status :success
                            :outputs
                            {:decision {"outcome" "changed"
                                        "relations" ["supports"]}}})]
      (is (= :success (:status result)))
      (is (= {:outcome :changed :relations [:supports]}
             (get-in result [:outputs :decision])))))

  (testing "namespaced keyword spellings retain their namespace"
    (let [result (validate {:outcome
                            {:schema [:enum
                                      :decision/changed
                                      :decision/unchanged]}}
                           {:status :success
                            :outputs {:outcome "decision/changed"}})]
      (is (= :decision/changed (get-in result [:outputs :outcome])))))

  (testing "colon-prefixed EDN spellings remain invalid JSON enum values"
    (let [result (validate {:outcome
                            {:schema [:enum :changed :unchanged]}}
                           {:status :success
                            :outputs {:outcome ":changed"}})]
      (is (= :failure (:status result)))
      (is (= {:outcome ":changed"} (:rejected-writes result))))))
