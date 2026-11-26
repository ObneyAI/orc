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

(deftest observed-and-unobserved-union-items-have-distinct-contracts
  (llm/register-provider! :union-input-guide {:provider :openrouter :model "test/model"})
  (let [item [:or [:nil {:description "No measurement supplied"}]
                  [:enum {:description "Empty measurement label"} ""]]
        spec {:inputs [{:name :records :spec [:vector [:map [:value item]]]}]
              :outputs [{:name :out :spec :string}]}
        sent (atom nil)]
    (with-redefs [router/completion
                  (fn [_ request]
                    (reset! sent (get-in request [:messages 0 :content]))
                    {:choices [{:message {:content "[[ ## out ## ]]\nok"}}]})]
      (doseq [[records null-meaning? empty-meaning?]
              [[[] true true]
               [[{:value nil}] true false]
               [[{:value ""}] false true]
               [[{:value nil} {:value ""}] true true]]]
        (is (= {:out "ok"} (llm/predict :union-input-guide spec {:records records}
                                        {:use-function-calling? false :max-retries 0})))
        (is (= null-meaning? (str/includes? @sent "No measurement supplied")) (pr-str records))
        (is (= empty-meaning? (str/includes? @sent "Empty measurement label")) (pr-str records))))))
