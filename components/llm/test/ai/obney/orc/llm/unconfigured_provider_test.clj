(ns ai.obney.orc.llm.unconfigured-provider-test
  "A call to a provider that was never configured is classified as that - not as
   a transport failure - so a caller can tell the user to configure one, instead
   of retrying a network that was never involved."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]))

(def ^:private qa
  {:inputs [{:name :question :spec :string :description "The question"}]
   :outputs [{:name :answer :spec :string :description "The answer"}]
   :instructions "Answer concisely."})

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(deftest a-provider-that-was-never-configured-fails-as-not-configured
  (let [e (thrown #(llm/predict :never-registered-provider qa {:question "q"} {:validate? false}))]
    (is (some? e))
    (is (= :provider-not-configured (:failure-kind (ex-data e))) (pr-str (ex-data e)))
    (is (= "never-registered-provider" (get-in (ex-data e) [:provider-evidence :provider])))
    (is (str/includes? (ex-message e) "never-registered-provider") (ex-message e))))

(deftest a-network-error-from-a-configured-provider-stays-a-transport-failure
  (with-redefs [router/completion (fn [& _] (throw (java.io.IOException. "connection reset")))]
    (let [e (thrown #(llm/predict :some-provider qa {:question "q"} {:validate? false}))]
      (is (= :transport-failure (:failure-kind (ex-data e))) (pr-str (ex-data e)))
      (is (str/includes? (ex-message e) "connection reset")))))
