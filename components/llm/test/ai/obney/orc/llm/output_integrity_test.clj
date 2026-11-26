(ns ai.obney.orc.llm.output-integrity-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]))

(defn- predict-arguments [arguments]
  (llm/register-provider! :output-integrity
    {:provider :openrouter :model "controlled/model"
     :config {:api-key "synthetic-not-sent"}})
  (with-redefs [http/post
                (fn [& _]
                  (future
                    {:status 200
                     :body {:id "integrity-response" :model "controlled/model"
                            :choices [{:index 0 :finish_reason "tool_calls"
                                       :message {:role "assistant" :content nil
                                                 :tool_calls [{:id "integrity-call" :type "function"
                                                               :function {:name "submit_response"
                                                                          :arguments (json/generate-string arguments)}}]}}]
                            :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}}))]
    (llm/predict :output-integrity
      {:inputs [] :outputs [{:name :answer :spec [:maybe :string]
                             :description "Required answer, null when unknown."}]}
      {} {:use-function-calling? true :validate? true :max-retries 0})))

(deftest required-nullability-does-not-permit-absence
  (is (= {:answer nil} (predict-arguments {:answer nil})))
  (let [failure (try (predict-arguments {})
                     (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo failure))
    (is (= :schema-validation-failed (:failure-kind (ex-data failure))))))

(defn- predict-marker [spec content options]
  (llm/register-provider! :marker-integrity
    {:provider :openrouter :model "controlled/model"
     :config {:api-key "synthetic-not-sent"}})
  (with-redefs [http/post
                (fn [& _]
                  (future {:status 200
                           :body {:id "marker-response" :model "controlled/model"
                                  :choices [{:index 0 :finish_reason "stop"
                                             :message {:role "assistant" :content content}}]
                                  :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}}))]
    (llm/predict :marker-integrity spec {}
                 (merge {:use-function-calling? false :validate? true :max-retries 0} options))))

(deftest marker-parser-decodes-optional-string-against-the-sent-wire-schema
  (let [spec {:inputs [] :outputs [{:name :reference :spec :string :optional true
                                    :description "Exact optional reference."}]}]
    (doseq [value ["REF-24" "quoted \"reference\"" "\"literal outer quotes\""]]
      (is (= {:reference value}
             (predict-marker spec (str "[[ ## reference ## ]]\n" (json/generate-string value)) {}))))))

(defn- stream-marker [spec content options]
  (llm/register-provider! :stream-integrity
    {:provider :openrouter :model "controlled/model"
     :config {:api-key "synthetic-not-sent"}})
  (let [sse (str "data: " (json/generate-string
                           {:id "stream-response" :model "controlled/model"
                            :choices [{:index 0 :finish_reason "stop"
                                       :delta {:role "assistant" :content content}}]})
                 "\n\ndata: [DONE]\n\n")]
    (with-redefs [http/post (fn [& _]
                             {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes sse "UTF-8"))})]
      (let [ch (llm/predict-stream-v2 :stream-integrity spec {}
                                    (merge {:validate? true :debounce-ms 0} options))]
        (loop [events []]
          (if-let [event (async/<!! ch)]
            (recur (conj events event))
            events))))))

(deftest final-stream-distinguishes-absence-from-null
  (let [spec {:inputs [] :outputs [{:name :answer :spec [:maybe :string]}]}]
    (is (= {} (:outputs (last (stream-marker spec "[[ ## completed ## ]]" {:validate? false}))))))
  (let [spec {:inputs [] :outputs [{:name :reference :spec :string :optional true}]}
        events (stream-marker spec "[[ ## reference ## ]]\n\"REF-24\"" {})]
    (is (= {:reference "REF-24"} (:outputs (last events))))
    (is (= {:reference "REF-24"} (:fields (first (filter #(= :fields (:orc/event %)) events)))))))

(deftest completed-marker-empty-value-and-unmarked-prose-remain-distinct
  (let [spec {:inputs [] :outputs [{:name :answer :spec :string}]}]
    (doseq [[content expected] [["[[ ## completed ## ]]" {}]
                                ["[[ ## answer ## ]]\n\n[[ ## completed ## ]]" {:answer ""}]
                                ["Plain unmarked answer" {:answer "Plain unmarked answer"}]]]
      (is (= expected (predict-marker spec content {:validate? false})))
      (is (= expected (:outputs (last (stream-marker spec content {:validate? false}))))))
    (let [failure (try (predict-marker spec "[[ ## completed ## ]]" {})
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (= :schema-validation-failed (:failure-kind (ex-data failure)))))
    (is (= :error (:orc/event (last (stream-marker spec "[[ ## completed ## ]]" {}))))))
  (let [spec {:inputs [] :outputs [{:name :answer :spec [:maybe :string]}]}]
    (is (= {:answer nil} (predict-marker spec "[[ ## answer ## ]]\nnull" {})))
    (is (= {:answer nil} (:outputs (last (stream-marker spec "[[ ## answer ## ]]\nnull" {})))))))
