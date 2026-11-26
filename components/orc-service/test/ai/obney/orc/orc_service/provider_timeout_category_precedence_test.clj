(ns ai.obney.orc.orc-service.provider-timeout-category-precedence-test
  "Supplementary contract: durable timeout markers obey the same explicit
   category precedence as live provider failures. No model or transport call."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [litellm.router :as router]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

(defn- classified-as [expected failure]
  (is (= expected (llm/transport-timeout? failure)) "Public LLM classifier")
  (is (= expected (service-logic/transport-timeout? failure)) "Executor uses the same classifier"))

(deftest durable-provider-markers-and-normalized-timeouts-need-no-cause
  (doseq [data [{:timeout-kind :provider}
                {:timeout-kind :provider :failure-kind :transport-failure}
                {:timeout-kind :provider :type :litellm/timeout}
                {:failure-kind :transport-failure
                 :provider-evidence {:transport {:type :litellm/timeout}}}
                {:timeout-kind :provider :failure-kind :transport-failure
                 :provider-evidence {:transport {:type :litellm/timeout}}}]]
    (let [failure (ex-info "Controlled durable outcome" data)]
      (is (nil? (ex-cause failure)))
      (classified-as true failure))))

(deftest explicit-non-timeout-categories-defeat-stale-provider-marker
  (doseq [data [{:failure-kind :schema-validation-failed}
                {:type :litellm/invalid-request}
                {:type :future/provider-error}
                {:failure-kind :transport-failure
                 :provider-evidence {:transport {:type :litellm/invalid-request}}}
                {:provider-evidence {:transport {:type :litellm/invalid-request}}}]]
    (testing (pr-str data)
      (classified-as false (ex-info "Controlled conflict" (assoc data :timeout-kind :provider)
                                    (java.net.http.HttpTimeoutException. "controlled"))))))

(deftest raw-timeouts-and-cycle-guard-survive-canonical-classification
  (doseq [failure [(java.net.http.HttpTimeoutException. "controlled")
                   (java.net.SocketTimeoutException. "controlled")
                   (java.util.concurrent.TimeoutException. "controlled")
                   (ex-info "Controlled wrapper" {} (java.net.http.HttpTimeoutException. "controlled"))]]
    (classified-as true failure))
  (let [cycle (proxy [Exception] ["controlled"] (getCause [] this))]
    (classified-as false cycle)))

(deftest deadline-prose-http-status-and-other-markers-are-not-provider-timeouts
  (doseq [failure [nil
                   (Exception. "request timed out")
                   (ex-info "request timed out" {:http-status 504 :status 408})
                   (ex-info "Controlled iteration deadline" {:timeout-kind :iteration})
                   (ex-info "Controlled campaign deadline" {:timeout-kind :campaign})]]
    (classified-as false failure)))

(deftest nil-category-does-not-hide-observed-or-durable-deadline
  (let [deadline #(java.net.http.HttpTimeoutException. "owned")]
    (doseq [failure [(ex-info "owned" {:type nil} (deadline))
                     (ex-info "owned" {:type nil :provider-evidence {:transport {:type :litellm/timeout}}})
                     (ex-info "owned" {:type nil :timeout-kind :provider})
                     (ex-info "owned" {:type nil :failure-kind nil :provider-evidence {:transport {:type nil}}} (deadline))]]
      (classified-as true failure))
    (doseq [failure [(ex-info "owned" {:type nil :http-status 504})
                     (ex-info "owned" {:type nil :provider-evidence {:transport {:type :litellm/invalid-request}}} (deadline))
                     (ex-info "owned" {:type nil :failure-kind :schema-validation-failed :timeout-kind :provider} (deadline))]]
      (classified-as false failure))))

(deftest nil-category-wrapped-deadline-survives-public-conversational-normalization
  (let [calls (atom 0) secret "OWNED-NIL-TYPE-CANARY"
        contract {:inputs [] :outputs [{:name :answer :spec :boolean :description "Owned boolean answer."}]
                  :instructions "Answer the declared question."}
        provider (keyword (str "nil-type-deadline-" (random-uuid)))]
    (llm/register-provider! provider {:provider :openrouter :model "fixture/nil-type"
                                    :config {:api-key "LOCAL-FIXTURE-NOT-A-CREDENTIAL"}})
    (with-redefs [router/completion
                  (fn [& _] (swap! calls inc)
                    (throw (ex-info secret {:type nil} (java.net.http.HttpTimeoutException. secret))))]
      (let [failure (try (llm/predict provider contract {} {:use-function-calling? false :validate? false :max-retries 0}) nil
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (= 1 @calls))
        (is (= :transport-failure (:failure-kind (ex-data failure))))
        (is (= {:type :litellm/timeout} (get-in (ex-data failure) [:provider-evidence :transport])))
        (is (nil? (ex-cause failure)))
        (is (not (.contains (str (ex-message failure) (pr-str (ex-data failure))) secret)))
        (classified-as true failure)))))
