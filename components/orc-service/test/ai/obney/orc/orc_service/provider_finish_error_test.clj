(ns ai.obney.orc.orc-service.provider-finish-error-test
  "J21 — a provider that finished with an error fails the node with that
   classification and evidence, and prediction metadata is never written to the
   blackboard as though it were a declared output."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [litellm.router :as router]))

(defn- workflow [name options]
  (sheet/workflow name
    (sheet/blackboard {:answer :string})
    (sheet/llm "answer"
      :instruction "Return an answer."
      :writes [:answer]
      :options (merge {:use-function-calling? true
                       :max-retries 0
                       :retry-delay-ms 1}
                      options))))

(defn- trace-for [ctx result]
  (is (h/settle-until!
       #(some? (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
                       [:query/result :trace]))))
  (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
          [:query/result :trace]))

(defn- failed-leaf-error [ctx result]
  (some #(when (= :leaf (:node-type %)) (:error %)) (:node-traces (trace-for ctx result))))

(defn- failed-leaf-detail [ctx result]
  (let [leaf (some #(when (= :leaf (:node-type %)) %) (:node-traces (trace-for ctx result)))]
    (get-in (h/run-query ctx {:query/name :sheet/node-trace-detail
                              :trace-id (:trace-id result)
                              :trace-instance-id (:trace-instance-id leaf)})
            [:query/result])))

(defn- value-writes [ctx result]
  (filterv #(= :sheet/execution-value-written (:event/type %))
           (h/read-tick-events ctx (:trace-id result))))

(def ^:private metadata-keys
  #{:outputs :usage :model :raw-response :provider-evidence :decisions})

(deftest a-metadata-envelope-with-nil-outputs-fails-on-the-declared-writes
  (h/with-async-test-context [ctx]
    (with-redefs [llm/predict (fn [& _]
                                {:outputs nil
                                 :usage {:prompt_tokens 3 :completion_tokens 0 :total_tokens 3}
                                 :model "deterministic-model"
                                 :raw-response nil
                                 :provider-evidence {:provider "deterministic-provider"}})]
      (let [sheet-id (sheet/build-workflow! ctx (workflow "j21-nil-envelope" {}))
            result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                  sheet-id {})
            detail (failed-leaf-detail ctx result)]
        (is (= :failure (:status result)))
        (is (re-find #"\[:answer\]" (str (failed-leaf-error ctx result))))
        (is (not (re-find #":outputs|:raw-response|:usage" (str (failed-leaf-error ctx result)))))
        (is (empty? (value-writes ctx result)))
        (is (empty? (filter metadata-keys (keys (:outputs result)))))))))

(deftest a-direct-output-return-without-metadata-still-succeeds
  (h/with-async-test-context [ctx]
    (with-redefs [llm/predict (fn [& _] {:answer "direct"})]
      (let [sheet-id (sheet/build-workflow! ctx (workflow "j21-direct" {}))
            result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                  sheet-id {})]
        (is (= :success (:status result)))
        (is (= "direct" (get-in result [:outputs :answer])))))))

(deftest a-provider-finish-error-fails-the-node-with-kind-and-evidence
  (h/with-async-test-context [ctx]
    (with-redefs [router/completion
                  (fn [& _]
                    {:id "gen-finish-error"
                     :model "deterministic-model"
                     :usage {:prompt_tokens 7 :completion_tokens 0 :total_tokens 7}
                     :choices [{:finish-reason :error
                                :native-finish-reason "MALFORMED_FUNCTION_CALL"
                                :message {:content nil}}]})]
      (let [sheet-id (sheet/build-workflow! ctx (workflow "j21-finish-error" {}))
            result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                  sheet-id {})
            detail (failed-leaf-detail ctx result)]
        (is (= :failure (:status result)))
        (is (= :provider-finish-error (:failure-kind detail)))
        (is (= "MALFORMED_FUNCTION_CALL"
               (get-in detail [:provider-evidence :native-finish-reason])))
        (is (= "error" (get-in detail [:provider-evidence :finish-reason])))
        (is (= "gen-finish-error" (get-in detail [:provider-evidence :response-id])))
        (is (empty? (value-writes ctx result)))
        (is (empty? (filter metadata-keys (keys (:outputs result)))))))))
