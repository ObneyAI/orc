(ns ai.obney.orc.llm.provider-observations-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]))

(defn- captured-response [n]
  (json/decode (slurp (io/resource (str "ai/obney/orc/llm/fixtures/provider-response-" n ".json"))) true))

(defn- predict-response [body spec options]
  (llm/register-provider! :observation-test
                         {:provider :openrouter :model "controlled/model"
                          :config {:api-key "synthetic-never-sent"}})
  (with-redefs [http/post (fn [& _] (future {:status 200 :body body}))]
    (llm/predict :observation-test spec {}
                 (merge {:use-function-calling? true :validate? true
                         :with-metadata? true} options))))

(def verdict-spec {:inputs [] :outputs [{:name :verdict :spec :string}]})

(deftest returned-provider-observations-accompany-successful-prediction
  (doseq [n [39 41]]
    (let [body (captured-response n)
          choice (first (:choices body))
          message (:message choice)
          result (predict-response body verdict-spec {})
          observations (:provider-observations result)]
      (is (= "observation-test" (:provider observations)))
      (is (= (:id body) (:response-id observations)))
      (is (= (:model body) (:model observations)))
      (is (= (:reasoning message) (get-in observations [:choices 0 :reasoning-content])))
      (is (= (:reasoning_details message) (get-in observations [:choices 0 :reasoning-details])))
      (is (= (:index choice) (get-in observations [:choices 0 :index])))
      (is (= (keyword (:finish_reason choice)) (get-in observations [:choices 0 :finish-reason])))
      (is (= (:completion_tokens_details (:usage body)) (get-in observations [:usage :completion-tokens-details])))
      (is (= (:cost (:usage body)) (get-in observations [:usage :cost])))
      (is (= (:content message) (:raw-response result)))
      (is (not (contains? (:outputs result) :provider-observations))))))

(deftest post-response-failures-retain-the-same-observation-envelope
  (let [body (captured-response 39)
        expected (:provider-observations (predict-response body verdict-spec {}))]
    (doseq [[invalid-body spec expected-kind]
            [[(assoc-in body [:choices 0 :message :tool_calls 0 :function :arguments] "{")
              verdict-spec :tool-call-parsing-failed]
             [body {:inputs [] :outputs [{:name :verdict :spec :int}]}
              :schema-validation-failed]]]
      (let [failure (try (predict-response invalid-body spec {})
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (= expected-kind (:failure-kind (ex-data failure))))
        (is (= expected (:provider-observations (ex-data failure))))
        (is (= (:id body) (get-in (ex-data failure) [:provider-evidence :response-id])))))))

(deftest observation-presence-is-not-inferred-and-task-results-stay-separate
  (doseq [observations [{} {:reasoning nil :reasoning_details nil :refusal nil}
                         {:reasoning "" :reasoning_details [] :refusal "Declined"}]]
    (let [body (-> (captured-response 39)
                   (update-in [:choices 0 :message] #(merge (dissoc % :reasoning :reasoning_details :refusal) observations))
                   (update :usage dissoc :cost :cost_details :prompt_tokens_details :completion_tokens_details))
          result (predict-response body verdict-spec {})
          choice (get-in result [:provider-observations :choices 0])]
      (doseq [[wire normalized] [[:reasoning :reasoning-content]
                                [:reasoning_details :reasoning-details]
                                [:refusal :refusal]]]
        (is (= (contains? observations wire) (contains? choice normalized)))
        (is (= (get observations wire) (get choice normalized))))
      (is (not (contains? (get-in result [:provider-observations :usage]) :completion-tokens-details)))
      (is (not (contains? (get-in result [:provider-observations :usage]) :cost)))
      (is (= (:outputs result) (predict-response body verdict-spec {:with-metadata? false})))
      (is (= #{:provider :response-id :model :usage :choices}
             (set (keys (:provider-observations result))))))))

(deftest transport-failure-does-not-invent-returned-observations
  (llm/register-provider! :observation-test
                         {:provider :openrouter :model "controlled/model"
                          :config {:api-key "synthetic-never-sent"}})
  (let [failure (with-redefs [http/post (fn [& _] (throw (java.net.ConnectException. "Controlled unavailable transport")))]
                  (try (llm/predict :observation-test verdict-spec {} {:with-metadata? true})
                       (catch clojure.lang.ExceptionInfo e e)))]
    (is (= :transport-failure (:failure-kind (ex-data failure))))
    (is (not (contains? (ex-data failure) :provider-observations)))))
