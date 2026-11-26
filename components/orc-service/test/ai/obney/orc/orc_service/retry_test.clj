(ns ai.obney.orc.orc-service.retry-test
  "Tests for retry with backoff functionality."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

;; =============================================================================
;; Test State
;; =============================================================================

(def attempt-counter (atom 0))

(defn reset-attempts! []
  (reset! attempt-counter 0))

;; =============================================================================
;; Test Functions
;; =============================================================================

(defn flaky-fn [{:keys [inputs]}]
  (let [n (swap! attempt-counter inc)]
    (if (< n 3)
      (throw (ex-info "Transient failure" {:attempt n}))
      {:result (str "Success on attempt " n)})))

(defn always-fails [{:keys [inputs]}]
  (swap! attempt-counter inc)
  (throw (ex-info "Always fails" {})))

(defn succeeds-first-time [{:keys [inputs]}]
  (swap! attempt-counter inc)
  {:result "immediate success"})

;; =============================================================================
;; get-backoff Tests
;; =============================================================================

(deftest get-backoff-test
  (testing "returns correct backoff for each attempt"
    (let [config {:backoff-ms [100 500 2000]}]
      (is (= 100 (service-logic/get-backoff config 0)))
      (is (= 500 (service-logic/get-backoff config 1)))
      (is (= 2000 (service-logic/get-backoff config 2)))))

  (testing "returns last backoff for attempts beyond array"
    (let [config {:backoff-ms [100 500]}]
      (is (= 500 (service-logic/get-backoff config 5)))
      (is (= 500 (service-logic/get-backoff config 10))))))

;; =============================================================================
;; execute-with-retry Tests
;; =============================================================================

(deftest execute-with-retry-success-test
  (testing "returns result on immediate success without retry"
    (reset-attempts!)
    (let [result (service-logic/execute-with-retry
                   (fn [] {:status :success :outputs {:x 1}})
                   {:max-attempts 3})]
      (is (= :success (:status result)))
      (is (= 1 (get-in result [:outputs :x]))))))

(deftest execute-with-retry-eventual-success-test
  (testing "retries until success"
    (reset-attempts!)
    (let [result (service-logic/execute-with-retry
                   (fn []
                     (let [n (swap! attempt-counter inc)]
                       (if (< n 3)
                         {:status :failure :error "retry"}
                         {:status :success :outputs {:n n}})))
                   {:max-attempts 5 :backoff-ms [10 20]})]
      (is (= :success (:status result)))
      (is (= 3 (get-in result [:outputs :n])))
      (is (= 3 @attempt-counter)))))

(deftest execute-with-retry-max-attempts-test
  (testing "stops after max-attempts and returns failure"
    (reset-attempts!)
    (let [result (service-logic/execute-with-retry
                   (fn []
                     (swap! attempt-counter inc)
                     {:status :failure :error "always fails"})
                   {:max-attempts 3 :backoff-ms [10]})]
      (is (= :failure (:status result)))
      (is (= 3 @attempt-counter)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest retry-integration-flaky-test
  (testing "flaky function succeeds after retries"
    (h/with-async-test-context [ctx]
      (reset-attempts!)

      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Retry Flaky"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :result :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.retry-test/flaky-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] [:result]))
          (h/run-and-apply! ctx (h/make-set-node-retry-command sheet-id leaf-id 5 [50 100]))

          (let [result (sheet/execute ctx sheet-id {})]
            (is (= :success (:status result)))
            (is (= "Success on attempt 3" (get-in result [:outputs :result])))
            (is (= 3 @attempt-counter))))))))

(deftest retry-integration-always-fails-test
  (testing "always-failing function respects max attempts"
    (h/with-async-test-context [ctx]
      (reset-attempts!)

      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Retry Always Fails"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :result :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.retry-test/always-fails"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] [:result]))
          (h/run-and-apply! ctx (h/make-set-node-retry-command sheet-id leaf-id 3 [10 20]))

          (let [result (sheet/execute ctx sheet-id {})]
            (is (= :failure (:status result)))
            (is (= 3 @attempt-counter))))))))

(deftest retry-integration-no-retry-needed-test
  (testing "successful function doesn't retry"
    (h/with-async-test-context [ctx]
      (reset-attempts!)

      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Retry Not Needed"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :result :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.retry-test/succeeds-first-time"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] [:result]))
          (h/run-and-apply! ctx (h/make-set-node-retry-command sheet-id leaf-id 5 [100 200]))

          (let [result (sheet/execute ctx sheet-id {})]
            (is (= :success (:status result)))
            (is (= 1 @attempt-counter))))))))

(deftest retry-backoff-timing-test
  (testing "backoff delays are applied between retries"
    (h/with-async-test-context [ctx]
      (reset-attempts!)

      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Retry Backoff Timing"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :result :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.retry-test/flaky-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] [:result]))
          (h/run-and-apply! ctx (h/make-set-node-retry-command sheet-id leaf-id 5 [100 200]))

          (let [start-time (System/currentTimeMillis)
                result (sheet/execute ctx sheet-id {})
                duration (- (System/currentTimeMillis) start-time)]
            (is (= :success (:status result)))
            (is (>= duration 250))))))))
