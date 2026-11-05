(ns ai.obney.orc.llm.transport-failure-evidence-test
  (:require [ai.obney.orc.llm.interface :as llm]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [litellm.errors :as errors]
            [litellm.router :as router]))

(def ^:private fixture-secret "FICTIONAL-TRANSPORT-SECRET")
(def ^:private contract
  {:inputs [] :outputs [{:name :answer :spec :boolean :description "The answer."}]
   :instructions "Answer the declared question."})

(defn- fail-call [data]
  (with-redefs [router/completion
                (fn [& _]
                  (throw (ex-info fixture-secret
                                  (merge {:request fixture-secret :message fixture-secret} data)
                                  (Exception. fixture-secret))))]
    (try
      (llm/predict :transport-contract contract {}
                   {:use-function-calling? false :validate? false})
      (catch Exception e e))))

(deftest ordinary-errors-copy-only-the-declared-transport-fields
  (testing "every recognized category remains evidence, not a fallback policy"
    (doseq [type errors/all-error-types]
      (let [failure (fail-call {:type type :http-status 502})]
        (is (= :transport-failure (:failure-kind (ex-data failure))))
        (is (= {:provider "transport-contract" :transport {:type type :http-status 502}}
               (:provider-evidence (ex-data failure))))
        (is (nil? (ex-cause failure)))
        (is (not (str/includes? (pr-str failure) fixture-secret))))))
  (testing "type and status are independently optional; invalid values are absent"
    (doseq [[data expected]
            [[{} nil]
             [{:type :unrecognized/secret :http-status 502} {:http-status 502}]
             [{:type :litellm/timeout :http-status "502"} {:type :litellm/timeout}]
             [{:type fixture-secret :http-status 502.0} nil]
             [{:http-status 99} nil]
             [{:http-status 600} nil]
             [{:http-status 100} {:http-status 100}]
             [{:http-status 599} {:http-status 599}]
             [{:status 502 :cause {:type :litellm/server-error :http-status 502}} nil]]]
      (let [failure (fail-call data)
            evidence (:provider-evidence (ex-data failure))]
        (is (= expected (:transport evidence)))
        (is (= (boolean expected) (contains? evidence :transport)))
        (is (nil? (ex-cause failure)))
        (is (not (str/includes? (pr-str failure) fixture-secret)))))))

(deftest unconfigured-provider-does-not-copy-available-data-or-cause
  (let [failure (fail-call {:config-name :transport-contract
                           :available [fixture-secret {:request fixture-secret}]})]
    (is (= :provider-not-configured (:failure-kind (ex-data failure))))
    (is (= {:provider "transport-contract"} (:provider-evidence (ex-data failure))))
    (is (= "LLM provider is not configured; register it, or declare a model the runtime can reach"
           (ex-message failure)))
    (is (nil? (ex-cause failure)))
    (is (not (str/includes? (pr-str failure) fixture-secret)))))
