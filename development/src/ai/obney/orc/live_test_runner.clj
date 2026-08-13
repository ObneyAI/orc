(ns ai.obney.orc.live-test-runner
  "Explicit runner for every OpenRouter-backed test suite used by CI."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [clojure.test :as test]))

(def expected-live-tests
  '{ai.obney.orc.orc-service.end-to-end-integration-test 1
    ai.obney.orc.orc-service.real-llm-adaptive-loop-e2e-test 2
    ai.obney.orc.orc-service.real-llm-gepa-e2e-test 3
    ai.obney.orc.orc-service.real-llm-living-description-e2e-test 2
    ai.obney.orc.orc-service.real-llm-ontology-builder-e2e-test 2
    ai.obney.orc.orc-service.real-llm-projection-replay-e2e-test 1
    ai.obney.orc.orc-service.real-llm-recursive-rlm-e2e-test 2
    ai.obney.orc.orc-service.real-llm-tenant-isolation-e2e-test 1})

(def live-test-namespaces (vec (keys expected-live-tests)))

(defn- true-env?
  [name]
  (= "true" (some-> (System/getenv name) str/trim str/lower-case)))

(defn- require-live-configuration!
  []
  (when-not (true-env? "ORC_INTEGRATION_TESTS")
    (throw (ex-info "ORC_INTEGRATION_TESTS must be true" {})))
  (when-not (true-env? "ORC_OPENROUTER_E2E_TESTS")
    (throw (ex-info "ORC_OPENROUTER_E2E_TESTS must be true" {})))
  (when (str/blank? (System/getenv "OPENROUTER_API_KEY"))
    (throw (ex-info "OPENROUTER_API_KEY must be configured" {}))))

(defn run!
  []
  (require-live-configuration!)
  (clojure.core/run! require live-test-namespaces)
  (let [discovered (into {}
                         (map (fn [namespace]
                                [namespace
                                 (count (for [[_ var] (ns-publics namespace)
                                              :when (:test (meta var))]
                                          var))]))
                         live-test-namespaces)]
    (when-not (= expected-live-tests discovered)
      (throw (ex-info "Live test discovery did not match the per-namespace CI contract"
                      {:expected expected-live-tests
                       :actual discovered})))
    (reduce (fn [totals namespace]
              (let [result (test/run-tests namespace)]
                (when-not (test/successful? result)
                  (throw (ex-info "Live test namespace failed"
                                  (assoc (select-keys result [:test :pass :fail :error])
                                         :namespace namespace))))
                (merge-with + totals
                            (select-keys result [:test :pass :fail :error]))))
            {:test 0 :pass 0 :fail 0 :error 0}
            live-test-namespaces)))

(defn -main
  [& _]
  (try
    (let [{:keys [test pass]} (run!)]
      (println (str "Verified " test " live tests with " pass " passing assertions.")))
    (finally
      ;; Futures and async processors use Clojure's agent pools. A successful
      ;; command-line test run must release them so CI cannot hang after its
      ;; final assertion waiting for non-daemon executor threads.
      (shutdown-agents))))
