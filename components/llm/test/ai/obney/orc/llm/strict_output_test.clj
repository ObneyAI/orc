(ns ai.obney.orc.llm.strict-output-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]))

(def spec {:inputs [] :outputs [{:name :answer :spec :string}]})

(deftest conflicting-or-unsupported-strict-controls-fail-before-dispatch
  (doseq [options [{:tools []}
                   {:tool-choice "auto"}
                   {:force-tool-choice? true}
                   {:response-format {:type "json_object"}}
                   {:extra-body {:response_format {:type "json_object"}}}
                   {:extra-body {"tools" []}}
                   {:extra-body {:provider {:require_parameters false}}}
                   {:extra-body {"provider" {"require_parameters" false}}}
                   {:extra-body {:stream true}}
                   {:output-mode :unknown}]]
    (llm/register-provider! :strict-preflight
      {:provider :openrouter :model "controlled/model" :output-mode :json-schema
       :config {:api-key "synthetic-not-sent"}})
    (let [calls (atom 0)
          failure (with-redefs [http/post
                                (fn [& _]
                                  (swap! calls inc)
                                  (future {:status 200 :body {:id "controlled"
                                                            :choices [{:message {:role "assistant" :content "{\"answer\":\"ok\"}"}
                                                                       :finish_reason "stop"}]}}))]
                    (try (llm/predict :strict-preflight spec {} options)
                         (catch clojure.lang.ExceptionInfo e e)))]
      (is (= :schema-validation-failed (:failure-kind (ex-data failure))) (pr-str options))
      (is (zero? @calls) (pr-str options)))))

(deftest unsupported-strict-streaming-fails-without-marker-dispatch
  (llm/register-provider! :strict-stream-preflight
    {:provider :openrouter :model "controlled/model" :output-mode :json-schema
     :config {:api-key "synthetic-not-sent"}})
  (let [calls (atom 0)
        event (with-redefs [http/post (fn [& _]
                                       (swap! calls inc)
                                       (throw (java.io.IOException. "Controlled unexpected dispatch")))]
                (let [ch (llm/predict-stream-v2 :strict-stream-preflight spec {})
                      [event _] (async/alts!! [ch (async/timeout 3000)])]
                  event))]
    (is (= :error (:orc/event event)))
    (is (zero? @calls))))

(deftest strict-profile-rejects-unimplemented-provider-adapter
  (llm/register-provider! :unsupported-strict-provider
    {:provider :openai :model "controlled/model" :output-mode :json-schema
     :config {:api-key "synthetic-not-sent"}})
  (let [calls (atom 0)
        failure (with-redefs [http/post (fn [& _]
                                         (swap! calls inc)
                                         (throw (java.io.IOException. "Controlled unexpected dispatch")))]
                  (try (llm/predict :unsupported-strict-provider spec {})
                       (catch clojure.lang.ExceptionInfo e e)))]
    (is (= :schema-validation-failed (:failure-kind (ex-data failure))))
    (is (zero? @calls))))
