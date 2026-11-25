(ns ai.obney.orc.llm.native-timeout-evidence-test
  "Public native-decision transport normalization; controlled transport capability,
   no outbound HTTP or model quality claim."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ai.obney.orc.llm.interface :as llm]))
(def spec {:inputs [{:name :request :spec :string :description "Owned request."}]
           :outputs [{:name :answer :spec :boolean :description "Bounded decision."}]
           :instructions "Return the bounded decision."})
(def canary "NATIVE-TIMEOUT-OWNED-PRIVATE-CANARY")
(defn run-failure [failure]
  (let [provider (keyword (str "native-timeout-" (random-uuid))) calls (atom [])]
    (llm/register-provider! provider
      {:provider :openrouter :protocol :decision :model "fixture/native-timeout"
       :config {:api-key "LOCAL-FIXTURE-NOT-A-CREDENTIAL"
                :decision-transport (fn [request] (swap! calls conj request) (throw failure))}})
    (let [result (try (llm/predict provider spec {:request "owned"}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (some? result))
      (is (= 1 (count @calls)) "Classification does not retry transport")
      (is (= :transport-failure (:failure-kind (ex-data result))))
      (is (nil? (ex-cause result)))
      (is (not (str/includes? (str (ex-message result) (pr-str (ex-data result))) canary)))
      (is (not (contains? (:provider-evidence (ex-data result)) :usage)))
      (is (not (contains? (:provider-evidence (ex-data result)) :model)))
      result)))
(deftest raw-and-wrapped-native-deadlines-retain-typed-safe-evidence
  (doseq [failure [(java.net.http.HttpTimeoutException. canary)
                   (java.net.SocketTimeoutException. canary)
                   (java.util.concurrent.TimeoutException. canary)
                   (ex-info canary {:unrelated canary} (java.net.http.HttpTimeoutException. canary))
                   (ex-info canary {:type nil} (java.net.http.HttpTimeoutException. canary))]]
    (let [result (run-failure failure)]
      (is (= {:type :litellm/timeout} (get-in (ex-data result) [:provider-evidence :transport])))
      (is (true? (llm/transport-timeout? result))))))
(deftest native-prose-and-status-remain-unclassified-as-deadline
  (let [result (run-failure (ex-info (str canary " request timed out") {:http-status 504}))]
    (is (= {:http-status 504} (get-in (ex-data result) [:provider-evidence :transport])))
    (is (false? (llm/transport-timeout? result)))))
(deftest explicit-native-non-timeout-category-beats-nested-deadline
  (let [result (run-failure (ex-info canary {:type :litellm/invalid-request}
                                   (java.net.http.HttpTimeoutException. canary)))]
    (is (= {:type :litellm/invalid-request} (get-in (ex-data result) [:provider-evidence :transport])))
    (is (false? (llm/transport-timeout? result)))))
