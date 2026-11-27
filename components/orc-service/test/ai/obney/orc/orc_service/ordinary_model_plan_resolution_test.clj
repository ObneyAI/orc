(ns ai.obney.orc.orc-service.ordinary-model-plan-resolution-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

(def module {:instructions "Assess the supplied source."
             :inputs [{:name :source :spec :string :description "Complete source."}]
             :outputs [{:name :answer :spec :boolean :description "The assessed finite answer."}]})

(deftest native-primary-keeps-native-protocol-with-a-paired-conversational-backup
  (let [calls (atom 0) native (keyword (str "native-plan-" (random-uuid)))
        chat (keyword (str "chat-plan-" (random-uuid)))
        chat-json (keyword (str "chat-json-plan-" (random-uuid)))]
    (llm/register-provider! native {:provider :openrouter :protocol :decision :model "fixture/native"
                                    :config {:api-key "SYNTHETIC-NOT-SENT"
                                             :decision-transport (fn [_] (swap! calls inc)
                                                                   (throw (Exception. "Preflight dispatched")))}})
    (llm/register-provider! chat {:provider :openrouter :model "fixture/chat" :output-mode :function-calling
                                 :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (llm/register-provider! chat-json {:provider :openrouter :model "fixture/chat-json" :output-mode :json-schema
                                      :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (let [result (try (service-logic/resolve-plan {:provider native :fallback-models [{:provider chat :model "fixture/chat"}
                                                                         {:provider chat-json :model "fixture/chat-json"}]}
                                        native module {:max-retries 0 :use-function-calling? true})
                      (catch clojure.lang.ExceptionInfo error {:failure-kind (:failure-kind (ex-data error))}))
          candidates (:candidates result)]
      (is (nil? (:failure-kind result)))
      (is (= [:decision :chat :chat] (mapv :protocol candidates)))
      (is (= [nil :function-calling :function-calling] (mapv #(get-in % [:options :output-mode]) candidates)))
      (is (= ["native-decision-v1" "paired-function-v1-strict" "paired-function-v1-strict"] (mapv #(get-in % [:profile :version]) candidates)))
      (is (= [false true true] (mapv #(get-in % [:profile :strict-arguments?]) candidates)))
      (is (zero? @calls)))))

(deftest equivalent-effective-paired-profiles-have-one-identity-and-other-profiles-differ
  (let [primary (keyword (str "profile-primary-" (random-uuid)))
        backup (keyword (str "profile-backup-" (random-uuid)))
        node {:provider primary :fallback-models [{:provider backup :model "fixture/backup"}]}
        opts {:max-retries 0 :max-tokens 64 :extra-body {:provider {:data_collection "deny"}}}]
    (llm/register-provider! primary {:provider :openrouter :model "fixture/primary" :output-mode :function-calling
                                    :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (llm/register-provider! backup {:provider :openrouter :model "fixture/backup"
                                   :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (let [inherited (service-logic/resolve-plan node primary module opts)
          explicit (service-logic/resolve-plan node primary module (assoc opts :output-mode :function-calling))
          json (service-logic/resolve-plan node primary module (assoc opts :output-mode :json-schema))
          forced (service-logic/resolve-plan node primary module (assoc opts :output-mode :function-calling :force-tool-choice? true))]
      (is (= inherited explicit))
      (is (= (service-logic/retained-configuration-id inherited) (service-logic/retained-configuration-id explicit)))
      (is (not= (service-logic/retained-configuration-id inherited) (service-logic/retained-configuration-id json)))
      (is (not= (service-logic/retained-configuration-id inherited) (service-logic/retained-configuration-id forced)))
      (is (= [:json-schema :json-schema] (mapv #(get-in % [:options :output-mode]) (:candidates json))))
      (is (every? #(= "deny" (get-in % [:options :extra-body :provider :data_collection])) (:candidates inherited))))))

(deftest unrepresentable-legacy-backup-profile-is-refused-without-changing-mode
  (let [primary (keyword (str "legacy-plan-primary-" (random-uuid)))
        paired (keyword (str "paired-plan-backup-" (random-uuid)))
        node {:provider primary :fallback-models [{:provider paired :model "fixture/backup"}]}]
    (llm/register-provider! primary {:provider :openrouter :model "fixture/primary"
                                    :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (llm/register-provider! paired {:provider :openrouter :model "fixture/backup" :output-mode :json-schema
                                   :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (let [failure (try (service-logic/resolve-plan node primary module {:max-retries 0}) nil
                       (catch clojure.lang.ExceptionInfo error (ex-data error)))
          legacy (service-logic/resolve-plan (assoc node :fallback-models [{:model "fixture/backup"}])
                                    primary module {:max-retries 0})
          explicit (service-logic/resolve-plan node primary module {:max-retries 0 :output-mode :function-calling})]
      (is (= :model-plan-incompatible (:failure-kind failure)))
      (is (= :effective-profile-cannot-be-preserved (:reason failure)))
      (is (= ["legacy-marker-v1" "legacy-marker-v1"] (mapv #(get-in % [:profile :version]) (:candidates legacy))))
      (is (= [:function-calling :function-calling] (mapv #(get-in % [:options :output-mode]) (:candidates explicit)))))))
