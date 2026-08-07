(ns ai.obney.orc.ontology.model-pinning-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.ontology.sheets.model-pinning :as model-pinning]))

(deftest pins-every-model-backed-node-without-changing-other-data
  (testing "all nested model roles receive one explicit model"
    (let [workflow {:name "fixture"
                    :children [{:type :llm :model "old-a" :instruction "a"}
                               {:type :sequence
                                :children [{:type :llm :model "old-b"}
                                           {:type :code :fn "f"}]}]}
          pinned (model-pinning/pin-model workflow "provider/model")]
      (is (= ["provider/model" "provider/model"]
             (->> (tree-seq coll? seq pinned)
                  (filter #(and (map? %) (contains? % :model)))
                  (mapv :model))))
      (is (= "f" (get-in pinned [:children 1 :children 1 :fn])))
      (is (= workflow (model-pinning/pin-model workflow nil))
          "nil preserves production defaults rather than storing nil"))))
