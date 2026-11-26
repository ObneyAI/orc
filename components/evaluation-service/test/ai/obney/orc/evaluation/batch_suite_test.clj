(ns ai.obney.orc.evaluation.batch-suite-test
  "J14 - the shipped batch evaluation suite executes, and each item's result is
   its own. Two opposing traces run through the parallel map-each; the good one
   must score high and the bad one low, in input order, never swapped or
   blended. Only the provider seam (`llm/predict`) is injected."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- judge-answer
  "A model answer for any tier-1 judge module: the band is 5 for the good
   response and 1 for the bad one, every other field a neutral value of its
   declared shape."
  [module inputs]
  (let [band (if (str/includes? (str (:response inputs)) "GOOD") 5 1)]
    (into {}
          (map (fn [{:keys [name spec]}]
                 [name (cond
                         (= :level name) band
                         (= :feedback name) (str "feedback for band " band)
                         (and (vector? spec) (= :vector (first spec))) []
                         :else "reasoning")]))
          (:outputs module))))

(def ^:private good
  {:inputs {"source" "The invoice total is 40 dollars."}
   :response "GOOD: the invoice total is 40 dollars."
   :instruction "State the invoice total."})

(def ^:private bad
  {:inputs {"source" "The invoice total is 40 dollars."}
   :response "BAD: the invoice total is 9000 dollars."
   :instruction "State the invoice total."})

(deftest the-batch-suite-executes-and-attributes-each-result-to-its-own-item
  (h/with-async-test-context [ctx]
    (with-redefs [llm/predict (fn [_provider module inputs _options]
                                {:outputs (judge-answer module inputs)
                                 :usage {:total-tokens 1}})]
      (let [sheet-id (sheet/build-workflow! ctx (evaluation/batch-evaluation-suite))
            result (sheet/execute ctx sheet-id {:traces [good bad]} :timeout-ms 60000)
            results (get-in result [:outputs :results])]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= 2 (count results)) (pr-str results))
        (is (= [1.0 0.0] (mapv #(get-in % [:current-aggregate :aggregate-score]) results))
            (str "in input order, each item's own aggregate: " (pr-str results)))
        (is (= [(:response good) (:response bad)] (mapv :response results))
            "each result still carries the item it was computed from")))))

(deftest the-single-trace-suites-execute
  (h/with-async-test-context [ctx]
    (with-redefs [llm/predict (fn [_provider module inputs _options]
                                {:outputs (judge-answer module inputs)
                                 :usage {:total-tokens 1}})]
      (testing "the full evaluation suite"
        (let [sheet-id (sheet/build-workflow! ctx (evaluation/evaluation-suite))
              result (sheet/execute ctx sheet-id {:trace-data good} :timeout-ms 60000)]
          (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
          (is (= 1.0 (get-in result [:outputs :aggregate-result :aggregate-score]))
              (pr-str (:outputs result)))))
      (testing "a selective suite aggregates only the selected judges"
        (let [sheet-id (sheet/build-workflow! ctx (evaluation/selective-judge-suite [:grounding :reasoning]))
              result (sheet/execute ctx sheet-id {:trace-data bad} :timeout-ms 60000)
              aggregate (get-in result [:outputs :aggregate-result])]
          (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
          (is (= 2 (count (:dimensions aggregate))) (pr-str aggregate))
          (is (= 0.0 (:aggregate-score aggregate)) (pr-str aggregate)))))))
