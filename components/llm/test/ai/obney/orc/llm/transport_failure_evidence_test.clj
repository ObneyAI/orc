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

(deftest ordinary-raw-timeout-retains-only-structural-deadline-evidence
  (with-redefs [router/completion
                (fn [& _] (throw (java.net.http.HttpTimeoutException. fixture-secret)))]
    (let [failure (try (llm/predict :transport-contract contract {}
                                  {:use-function-calling? false :validate? false})
                       (catch Exception e e))]
      (is (= {:failure-kind :transport-failure
              :provider-evidence {:provider "transport-contract"
                                  :transport {:type :litellm/timeout}}}
             (ex-data failure)))
      (is (nil? (ex-cause failure)))
      (is (not (str/includes? (pr-str failure) fixture-secret))))))

(deftest timeout-classification-honors-explicit-categories-and-guards-cycles
  (let [deadline #(java.net.http.HttpTimeoutException. fixture-secret)
        cycle (proxy [Exception] ["not a timeout"]
                (getCause [] this))]
    (doseq [e [(deadline)
               (java.net.SocketTimeoutException. fixture-secret)
               (java.util.concurrent.TimeoutException. fixture-secret)
               (ex-info fixture-secret {} (deadline))
               (ex-info "safe" {:failure-kind :transport-failure
                                :provider-evidence {:transport {:type :litellm/timeout}}})]]
      (is (true? (llm/transport-timeout? e))))
    (doseq [e [nil cycle
               (Exception. "request timed out")
               (ex-info "request timed out" {:status 408 :http-status 504})
               (ex-info "typed invalid request" {:type :litellm/invalid-request} (deadline))
               (ex-info "typed unknown" {:type :future/provider-error} (deadline))
               (ex-info "schema failure" {:failure-kind :schema-validation-failed} (deadline))
               (ex-info "normalized invalid request"
                        {:failure-kind :transport-failure
                         :provider-evidence {:transport {:type :litellm/invalid-request}}}
                        (deadline))]]
      (is (false? (llm/transport-timeout? e)))))
  (doseq [type [:litellm/invalid-request :litellm/server-error]]
    (with-redefs [router/completion
                  (fn [& _] (throw (ex-info fixture-secret {:type type}
                                           (java.net.http.HttpTimeoutException. fixture-secret))))]
      (let [failure (try (llm/predict :transport-contract contract {}
                                    {:use-function-calling? false :validate? false})
                         (catch Exception e e))]
        (is (= {:provider "transport-contract" :transport {:type type}}
               (:provider-evidence (ex-data failure))))
        (is (nil? (ex-cause failure)))
        (is (not (str/includes? (pr-str failure) fixture-secret)))))))

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
