(ns ai.obney.orc.llm.input-contract-test
  "INPUT-SHAPE1..2: public requests with injected provider transport, not hosted proof."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]))

(def contract
  {:inputs [{:name :audit :spec [:maybe [:map
             [:annotation {:optional true :description "Interpretation, not additional evidence"} :string]
             [:unconfirmed-ids [:vector [:string {:description "Identifier awaiting confirmation"}]]]]]}
            {:name :absent :spec :string :description "TOP LEVEL ABSENT"}]
   :outputs [{:name :out :spec :string}]
   :instructions "Assess the supplied audit."})

(deftest all-chat-request-builders-supplement-supplied-shapes
  (llm/register-provider! :input-contract-test {:provider :openrouter :model "test/model" :config {:api-key "dummy"}})
  (doseq [options [{:use-function-calling? false}
                   {:use-function-calling? true}
                   {:output-mode :json-schema}
                   {:output-mode :function-calling}]]
    (let [captured (atom nil)]
      (with-redefs [router/supports-function-calling? (constantly true)
                    router/completion
                    (fn [_ request]
                      (reset! captured request)
                      {:choices [{:message (cond
                                            (:tools request) {:tool-calls [{:type "function" :function {:name "submit_response" :arguments "{\"out\":\"ok\"}"}}]}
                                            (:response-format request) {:content "{\"out\":\"ok\"}"}
                                            :else {:content "[[ ## out ## ]]\nok"})}]})]
        (is (= {:out "ok"} (llm/predict :input-contract-test contract {:audit {:unconfirmed-ids []}}
                                       (assoc options :validate? false)))))
      (let [sent (pr-str @captured)]
        (is (str/includes? sent "DECLARED CONTRACT"))
        (is (str/includes? sent "not supplied evidence"))
        (is (str/includes? sent "Interpretation, not additional evidence"))
        (is (str/includes? sent "Identifier awaiting confirmation"))
        (is (not (str/includes? sent "TOP LEVEL ABSENT")))))))

(deftest native-decision-keeps-contract-out-of-state
  (let [captured (atom nil)
        spec (assoc contract :outputs [{:name :out :spec :boolean}])]
    (llm/register-provider! :input-contract-decision
      {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
       :config {:api-key "dummy" :decision-transport
                (fn [request]
                  (reset! captured request)
                  {:answers {:out {:type "noul" :noul 0.8}}})}})
    (is (= {:out true} (llm/predict :input-contract-decision spec {:audit {:unconfirmed-ids []}})))
    (let [sent (pr-str @captured)]
      (is (str/includes? sent "DECLARED CONTRACT"))
      (is (str/includes? sent "Identifier awaiting confirmation"))
      (is (str/includes? sent "Interpretation, not additional evidence"))
      (is (= #{:audit} (set (keys (:state @captured)))))
      (is (= {"unconfirmed-ids" []} (get-in @captured [:state :audit])))
      (is (not (str/includes? (pr-str (:state @captured)) "DECLARED CONTRACT")))
      (is (not (str/includes? sent "TOP LEVEL ABSENT"))))))
