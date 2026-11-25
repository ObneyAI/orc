(ns ai.obney.orc.orc-service.recoverable-generated-function-test
  "Focused source validation through the code executor. The separate actual
   SQLite/process-death proof remains the acceptance test for recovery."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree]))

(defn binding-for [source]
  {:version 1 :context-version 1 :bindings {} :source source
   :sha256 (format "%064x" (java.math.BigInteger. 1
                           (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                    (.getBytes ^String source "UTF-8"))))})

(defn execute [key binding]
  (executor/execute-code {:fn key :recoverable-fn binding :reads [:n] :writes [:result]}
                         {:n {:value 4}} {}))

(deftest source-must-contain-exactly-one-function
  (let [key (str "ephemeral-fn-" (random-uuid))
        binding (binding-for "(fn [{:keys [inputs]}] {:result (inc (:n inputs))}) (fn [_] {:result 999})")]
    (try
      (let [result (execute key binding)]
        (is (= :failure (:status result)))
        (is (re-find #"exactly one EDN function form" (:error result)))
        (is (nil? (tree/lookup-ephemeral-fn key))))
      (finally (tree/clear-ephemeral-fn! key)))))

(deftest original-function-identity-cannot-change-source
  (let [key (str "ephemeral-fn-" (random-uuid))
        original (binding-for "(fn [{:keys [inputs]}] {:result (inc (:n inputs))})")
        different (binding-for "(fn [{:keys [inputs]}] {:result (dec (:n inputs))})")]
    (try
      (is (= :success (:status (execute key original))))
      (let [result (execute key different)]
        (is (= :failure (:status result)))
        (is (re-find #"different source binding" (:error result))))
      (is (= {:result 5} (:outputs (execute key original))))
      (finally (tree/clear-ephemeral-fn! key)))))

(deftest invalid-or-captured-bindings-fail-before-body-execution
  (doseq [binding [(assoc (binding-for "(fn [_] {:result 5})") :version 2)
                   (assoc (binding-for "(fn [_] {:result 5})") :context-version 2)
                   (assoc (binding-for "(fn [_] {:result 5})") :sha256 (apply str (repeat 64 "0")))
                   (binding-for "(fn [_] {:result captured-value})")
                   (assoc (binding-for "(fn [_] {:result 5})") :bindings {'captured-value 5})]]
    (let [key (str "ephemeral-fn-" (random-uuid))]
      (try
        (is (= :failure (:status (execute key binding))))
        (is (nil? (tree/lookup-ephemeral-fn key)))
        (finally (tree/clear-ephemeral-fn! key))))))

(deftest legacy-ephemeral-only-code-keeps-precise-missing-function-failure
  (let [key (str "ephemeral-fn-" (random-uuid))
        result (executor/execute-code {:fn key :reads [] :writes [:result]} {} {})]
    (is (= :failure (:status result)))
    (is (re-find #"[Ee]phemeral function.*not found" (:error result)))
    (is (nil? (tree/lookup-ephemeral-fn key)))))
