(ns ai.obney.orc.llm.fallback-preflight-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.llm.interface :as llm]))
(def spec {:inputs [] :outputs [{:name :answer :spec :boolean}] :instructions "Decide"})
(deftest candidate-contract-preflight
  (llm/register-provider! :fallback-chat {:provider :openrouter :model "fixture/chat" :config {:api-key "not-sent"}})
  (llm/register-provider! :fallback-native {:provider :openrouter :protocol :decision :model "fixture/native" :config {:api-key "not-sent" :decision-transport (fn [_] (throw (Exception. "Preflight dispatched")))}})
  (llm/register-provider! :fallback-unsupported-strict {:provider :anthropic :model "fixture/chat" :config {:api-key "not-sent"}})
  (is (thrown? clojure.lang.ExceptionInfo (llm/preflight-provider! :fallback-chat spec {:model "fallback-native" :orc/explicit-provider? true})))
  (is (thrown? clojure.lang.ExceptionInfo (llm/preflight-provider! :fallback-unsupported-strict spec {:output-mode :json-schema})))
  (is (= :chat (:protocol (llm/preflight-provider! :fallback-chat spec {}))))
  (is (= "fixture/override" (:model (llm/preflight-provider! :fallback-chat spec {:model "fixture/override"}))))
  (is (= :fallback-native (:provider (llm/preflight-provider! :fallback-chat spec {:model "fallback-native"}))))
  (is (= :decision (:protocol (llm/preflight-provider! :fallback-native spec {}))))
  (is (thrown? clojure.lang.ExceptionInfo (llm/preflight-provider! :fallback-native (assoc spec :outputs [{:name :text :spec :string}]) {})))
  (is (thrown? clojure.lang.ExceptionInfo (llm/preflight-provider! :missing-fallback-config spec {})))
  (is (thrown? clojure.lang.ExceptionInfo (llm/preflight-provider! :fallback-chat spec {:output-mode :unknown}))))
