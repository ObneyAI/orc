(ns ai.obney.orc.live-test-runner
  "Explicit runner for every OpenRouter-backed test suite used by CI."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [litellm.router :as router]))

(def live-test-namespaces
  '[ai.obney.orc.orc-service.end-to-end-integration-test
    ai.obney.orc.orc-service.real-llm-adaptive-loop-e2e-test
    ai.obney.orc.orc-service.real-llm-gepa-e2e-test
    ai.obney.orc.orc-service.real-llm-living-description-e2e-test
    ai.obney.orc.orc-service.real-llm-ontology-builder-e2e-test
    ai.obney.orc.orc-service.real-llm-projection-replay-e2e-test
    ai.obney.orc.orc-service.real-llm-recursive-rlm-e2e-test
    ai.obney.orc.orc-service.real-llm-tenant-isolation-e2e-test])

(def expected-live-test-count 14)
(def live-max-tokens 2048)

(defn cap-live-request
  "Cap the output reservation used by CI's live calls while preserving a
   smaller limit selected by the production call site."
  [request]
  (update request :max-tokens #(min (or % live-max-tokens) live-max-tokens)))

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
  (let [discovered (count (for [namespace live-test-namespaces
                                [_ var] (ns-publics namespace)
                                :when (:test (meta var))]
                            var))
        real-completion @#'router/completion]
    (when-not (= expected-live-test-count discovered)
      (throw (ex-info "Live test discovery count did not match the CI contract"
                      {:expected expected-live-test-count
                       :actual discovered})))
    (with-redefs [router/completion
                  (fn [provider request]
                    (real-completion provider (cap-live-request request)))]
      (reduce (fn [totals namespace]
                (let [result (test/run-tests namespace)]
                  (when-not (test/successful? result)
                    (throw (ex-info "Live test namespace failed"
                                    (assoc (select-keys result [:test :pass :fail :error])
                                           :namespace namespace))))
                  (merge-with + totals
                              (select-keys result [:test :pass :fail :error]))))
              {:test 0 :pass 0 :fail 0 :error 0}
              live-test-namespaces))))

(defn -main
  [& _]
  (let [{:keys [test pass]} (run!)]
    (println (str "Verified " test " live tests with " pass " passing assertions."))))
