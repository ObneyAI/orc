(ns ai.obney.orc.orc-service.real-llm-model-condition-e2e-test
  "REAL-LLM proof for contract ModelBackedCondition: a real model answers a
   true and a false proposition, and an unknown model id fails as a provider
   failure rather than a semantic negative. Opt-in through the shared
   ORC_OPENROUTER_E2E_TESTS gate."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn mark-ran [_] {:ran true})

(defn- guarded-workflow [workflow-name & {:keys [model]}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:claim :string :ran :boolean})
    (sheet/sequence "guarded"
      (apply sheet/llm-condition "holds"
             (cond-> [:instruction "Is the claim factually true?" :reads [:claim]]
               model (conj :model model)))
      (sheet/code "act"
        :fn "ai.obney.orc.orc-service.real-llm-model-condition-e2e-test/mark-ran"
        :writes [:ran]))))

(defn- condition-evidence [ctx result]
  (let [trace-id (:trace-id result)
        _ (h/settle-until!
           #(some? (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                           [:query/result :trace])))
        trace (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                      [:query/result :trace])
        node-trace (first (filter #(= :llm-condition (:node-type %))
                                  (:node-traces trace)))
        completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                        (= :llm-condition (:node-type %)))
                                  (h/read-tick-events ctx trace-id)))]
    {:completion completion
     :detail (:query/result
              (h/run-query ctx {:query/name :sheet/node-trace-detail
                                :trace-id trace-id
                                :trace-instance-id (:trace-instance-id node-trace)}))}))

(defn- run! [ctx workflow claim]
  (let [sheet-id (sheet/build-workflow! ctx workflow)]
    (sheet/execute ctx sheet-id {:claim claim} :timeout-ms 120000)))

(deftest real-llm-model-condition-preserves-true-and-false
  (live/with-real-openrouter
    (live/register-openrouter!)
    (h/with-async-test-context
      [ctx {:context {:llm-provider :openrouter}}]
      (testing "a true proposition succeeds and the guarded action runs"
        (let [result (run! ctx (guarded-workflow "real-mbc-true" :model live/openrouter-model)
                           "Two plus two equals four.")
              {:keys [completion detail]} (condition-evidence ctx result)]
          (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
          (is (true? (get-in result [:outputs :ran])))
          (is (true? (:condition-answer completion)))
          (is (string? (:model completion)))
          (is (pos? (or (get-in completion [:usage :total-tokens]) 0)))
          (is (= {:claim "Two plus two equals four."} (:inputs detail)))))
      (testing "a false proposition fails as a semantic negative"
        (let [result (run! ctx (guarded-workflow "real-mbc-false" :model live/openrouter-model)
                           "Two plus two equals five.")
              {:keys [completion detail]} (condition-evidence ctx result)]
          (is (= :failure (:status result)))
          (is (nil? (get-in result [:outputs :ran])))
          (is (false? (:condition-answer completion)))
          (is (nil? (:failure-kind detail)))))
      (testing "an unknown model fails as a provider failure, not as false"
        (let [result (run! ctx (guarded-workflow "real-mbc-bad-model"
                                                 :model "openrouter/no-such-vendor/no-such-model")
                           "Two plus two equals four.")
              {:keys [completion detail]} (condition-evidence ctx result)]
          (is (= :failure (:status result)))
          (is (not (contains? completion :condition-answer)))
          (is (keyword? (:failure-kind detail))
              (pr-str (select-keys completion [:error :failure-kind]))))))))
