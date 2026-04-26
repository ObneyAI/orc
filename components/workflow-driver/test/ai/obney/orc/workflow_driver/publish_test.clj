(ns ai.obney.orc.workflow-driver.publish-test
  "Milestone 3 validation: eval-set runs ticks, publish! refuses
   regressions, revert! restores a known-good version.

   Uses an async test context so real ticks can complete via the
   pubsub + todo-processor pipeline."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.interface :as driver]))

;; =============================================================================
;; Test code-fns the Sheets reference
;; =============================================================================

(defn echo-fn
  "Always succeeds — passes the input back as the analysis output."
  [{:keys [inputs]}]
  {:analysis {:status :ok :echo (:doc inputs)}})

(defn boom-fn
  "Always fails — used to simulate a regression."
  [{:keys [inputs]}]
  (throw (ex-info "boom-fn deliberately fails" {:inputs inputs})))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- build-sheet-with-fn!
  "Build (or rebuild) the test sheet using the given code-fn FQN."
  [ctx fn-fqn]
  (orc/build-workflow! ctx
    (orc/workflow "publish-test-sheet"
      (orc/blackboard
        {:doc :string
         :analysis :map})
      (orc/sequence "main"
        (orc/code "process"
          :fn fn-fqn
          :reads [:doc]
          :writes [:analysis])))))

(def ^:private smoke-eval-set
  [{:name "doc-1" :inputs {:doc "alpha"}}
   {:name "doc-2" :inputs {:doc "beta"}}
   {:name "doc-3" :inputs {:doc "gamma"}}])

;; =============================================================================
;; Tests
;; =============================================================================

(deftest run-eval-set-counts-pass-fail
  (testing "all-pass eval set reports 100% pass-rate"
    (h/with-async-test-context [ctx]
      (let [sheet-id (build-sheet-with-fn! ctx
                       "ai.obney.orc.workflow-driver.publish-test/echo-fn")
            report (driver/run-eval-set! ctx sheet-id smoke-eval-set
                     {:timeout-ms 10000})]
        (is (= 3 (:total report)))
        (is (= 3 (:pass-count report)))
        (is (= 0 (:fail-count report)))
        (is (= 1.0 (:pass-rate report)))
        (is (every? #(= :success (:status %)) (:results report))))))

  (testing "all-fail eval set reports 0% pass-rate"
    (h/with-async-test-context [ctx]
      (let [sheet-id (build-sheet-with-fn! ctx
                       "ai.obney.orc.workflow-driver.publish-test/boom-fn")
            report (driver/run-eval-set! ctx sheet-id smoke-eval-set
                     {:timeout-ms 10000})]
        (is (= 3 (:total report)))
        (is (= 0 (:pass-count report)))
        (is (= 3 (:fail-count report)))
        (is (= 0.0 (:pass-rate report)))))))

(deftest publish-promotes-when-eval-passes
  (testing "publish! returns :ok with a version-number when eval passes"
    (h/with-async-test-context [ctx]
      (let [sheet-id (build-sheet-with-fn! ctx
                       "ai.obney.orc.workflow-driver.publish-test/echo-fn")
            result (driver/publish! ctx sheet-id smoke-eval-set
                     {:min-pass-rate 1.0
                      :description "all-pass baseline"})]
        (is (= :ok (:status result)))
        (is (pos-int? (:version-number result)))
        (is (uuid? (:snapshot-id result)))
        (is (= 1.0 (get-in result [:eval :pass-rate])))))))

(deftest publish-refuses-on-regression
  (testing "publish! refuses when pass-rate falls below threshold"
    (h/with-async-test-context [ctx]
      ;; Bootstrap with a passing fn and publish v1 first
      (let [sheet-id (build-sheet-with-fn! ctx
                       "ai.obney.orc.workflow-driver.publish-test/echo-fn")
            v1 (driver/publish! ctx sheet-id smoke-eval-set
                 {:min-pass-rate 1.0 :description "v1"})]
        (is (= :ok (:status v1)))
        ;; Mutate the draft to use the failing fn
        (build-sheet-with-fn! ctx
          "ai.obney.orc.workflow-driver.publish-test/boom-fn")
        ;; Try to publish — should refuse
        (let [result (driver/publish! ctx sheet-id smoke-eval-set
                       {:min-pass-rate 1.0})]
          (is (= :refused (:status result)))
          (is (= :pass-rate-below-threshold (:reason result)))
          (is (= 0.0 (:pass-rate result)))
          (is (= 3 (get-in result [:eval :fail-count]))))))))

(deftest revert-restores-prior-version
  (testing "revert! after a regression restores a passing draft"
    (h/with-async-test-context [ctx]
      (let [sheet-id (build-sheet-with-fn! ctx
                       "ai.obney.orc.workflow-driver.publish-test/echo-fn")
            v1 (driver/publish! ctx sheet-id smoke-eval-set
                 {:min-pass-rate 1.0 :description "v1-baseline"})]
        (is (= :ok (:status v1)))
        ;; Break the draft
        (build-sheet-with-fn! ctx
          "ai.obney.orc.workflow-driver.publish-test/boom-fn")
        (let [post-break (driver/run-eval-set! ctx sheet-id smoke-eval-set
                           {:timeout-ms 10000})]
          (is (= 0 (:pass-count post-break))))
        ;; Revert and confirm eval passes again
        (let [revert-result (driver/revert! ctx sheet-id (:version-number v1))]
          (is (= :ok (:status revert-result)))
          (is (= (:version-number v1) (:reverted-to revert-result))))
        (let [post-revert (driver/run-eval-set! ctx sheet-id smoke-eval-set
                            {:timeout-ms 10000})]
          (is (= 3 (:pass-count post-revert))))))))
