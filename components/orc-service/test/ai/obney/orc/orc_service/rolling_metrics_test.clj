(ns ai.obney.orc.orc-service.rolling-metrics-test
  "Unit tests for rolling metrics read model.

   Tests the metrics tracking functionality:
   - Event handling for node execution completed
   - Rolling window behavior (keeps last 100 executions)
   - Trend calculation (improving/declining/stable)
   - Metrics computation (success rate, avg duration)"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-execution-event
  "Create a mock node-execution-completed event."
  [sheet-id node-id status duration-ms & [timestamp]]
  {:event/type :sheet/node-execution-completed
   :event/timestamp (or timestamp (java.time.Instant/now))
   :sheet-id sheet-id
   :node-id node-id
   :status status
   :duration-ms duration-ms})

(defn make-success-event
  [sheet-id node-id duration-ms]
  (make-execution-event sheet-id node-id :success duration-ms))

(defn make-failure-event
  [sheet-id node-id duration-ms]
  (make-execution-event sheet-id node-id :failure duration-ms))

(def test-sheet-id #uuid "11111111-1111-1111-1111-111111111111")
(def test-node-id #uuid "22222222-2222-2222-2222-222222222222")


(deftest rolling-metrics-success-event-test
  (testing "handles success event correctly"
    (let [event (make-success-event test-sheet-id test-node-id 100)
          state (service-logic/rolling-metrics {} [event])
          metrics (get state [test-sheet-id test-node-id])]
      (is (= 1 (count (:executions metrics))))
      (is (= 1 (:success-count metrics)))
      (is (= 0 (:failure-count metrics)))
      (is (= 100 (:total-duration metrics))))))

(deftest rolling-metrics-failure-event-test
  (testing "handles failure event correctly"
    (let [event (make-failure-event test-sheet-id test-node-id 50)
          state (service-logic/rolling-metrics {} [event])
          metrics (get state [test-sheet-id test-node-id])]
      (is (= 1 (count (:executions metrics))))
      (is (= 0 (:success-count metrics)))
      (is (= 1 (:failure-count metrics)))
      (is (= 50 (:total-duration metrics))))))

(deftest rolling-metrics-multiple-events-test
  (testing "accumulates multiple events for same node"
    (let [events [(make-success-event test-sheet-id test-node-id 100)
                  (make-success-event test-sheet-id test-node-id 150)
                  (make-failure-event test-sheet-id test-node-id 200)]
          state (service-logic/rolling-metrics {} events)
          metrics (get state [test-sheet-id test-node-id])]
      (is (= 3 (count (:executions metrics))))
      (is (= 2 (:success-count metrics)))
      (is (= 1 (:failure-count metrics)))
      (is (= 450 (:total-duration metrics))))))

(deftest rolling-metrics-multiple-nodes-test
  (testing "tracks different nodes separately"
    (let [node-a #uuid "aaaa0000-0000-0000-0000-000000000000"
          node-b #uuid "bbbb0000-0000-0000-0000-000000000000"
          events [(make-success-event test-sheet-id node-a 100)
                  (make-failure-event test-sheet-id node-b 200)]
          state (service-logic/rolling-metrics {} events)]
      (is (= 1 (:success-count (get state [test-sheet-id node-a]))))
      (is (= 1 (:failure-count (get state [test-sheet-id node-b])))))))

(deftest rolling-metrics-window-limit-test
  (testing "respects rolling window size limit"
    (let [
          events (mapv #(make-success-event test-sheet-id test-node-id %)
                       (range 150))
          state (service-logic/rolling-metrics {} events)
          metrics (get state [test-sheet-id test-node-id])]
      (is (= 100 (count (:executions metrics))))
      (is (= 150 (:success-count metrics))))))

;; =============================================================================
;; calculate-trend Tests
;; =============================================================================

(deftest calculate-trend-insufficient-data-test
  (testing "returns nil with fewer than 10 executions"
    (let [executions (repeat 5 {:status :success})]
      (is (nil? (service-logic/calculate-trend executions))))))

(deftest calculate-trend-exactly-10-test
  (testing "returns :stable with exactly 10 executions (no older data)"
    (let [executions (repeat 10 {:status :success})]
      (is (= :stable (service-logic/calculate-trend executions))))))

(deftest calculate-trend-improving-test
  (testing "detects improving trend"
    (let [
          older (concat (repeat 5 {:status :success})
                        (repeat 5 {:status :failure}))
          recent (concat (repeat 9 {:status :success})
                         (repeat 1 {:status :failure}))
          executions (concat older recent)]
      (is (= :improving (service-logic/calculate-trend executions))))))

(deftest calculate-trend-declining-test
  (testing "detects declining trend"
    (let [
          older (concat (repeat 9 {:status :success})
                        (repeat 1 {:status :failure}))
          recent (concat (repeat 5 {:status :success})
                         (repeat 5 {:status :failure}))
          executions (concat older recent)]
      (is (= :declining (service-logic/calculate-trend executions))))))

(deftest calculate-trend-stable-test
  (testing "returns :stable for consistent performance"
    (let [
          older (concat (repeat 8 {:status :success})
                        (repeat 2 {:status :failure}))
          recent (concat (repeat 8 {:status :success})
                         (repeat 2 {:status :failure}))
          executions (concat older recent)]
      (is (= :stable (service-logic/calculate-trend executions))))))

(deftest calculate-trend-within-threshold-test
  (testing "small changes (< 10%) are :stable"
    (let [
          older (concat (repeat 7 {:status :success})
                        (repeat 3 {:status :failure}))
          recent (concat (repeat 7 {:status :success})
                         (repeat 3 {:status :failure}))
          executions (concat older recent)]
      (is (= :stable (service-logic/calculate-trend executions))))))

;; =============================================================================
;; compute-node-metrics Tests
;; =============================================================================

(defn approx=
  "Check if two numbers are approximately equal (within epsilon)."
  [a b & [epsilon]]
  (< (Math/abs (- (double a) (double b))) (or epsilon 0.0001)))

(deftest compute-node-metrics-basic-test
  (testing "computes basic metrics correctly"
    (let [metrics {:executions [{:status :success :duration-ms 100}
                                {:status :success :duration-ms 200}
                                {:status :failure :duration-ms 150}]
                   :success-count 2
                   :failure-count 1
                   :total-duration 450}
          result (service-logic/compute-node-metrics metrics)]
      (is (= 3 (:execution-count result)))
      (is (approx= (/ 2 3.0) (:success-rate result)))
      (is (approx= (/ 1 3.0) (:failure-rate result)))
      (is (= 150.0 (:avg-duration-ms result))))))

(deftest compute-node-metrics-all-success-test
  (testing "handles 100% success rate"
    (let [metrics {:executions (repeat 5 {:status :success :duration-ms 100})
                   :success-count 5
                   :failure-count 0
                   :total-duration 500}
          result (service-logic/compute-node-metrics metrics)]
      (is (= 1.0 (:success-rate result)))
      (is (= 0.0 (:failure-rate result))))))

(deftest compute-node-metrics-all-failure-test
  (testing "handles 100% failure rate"
    (let [metrics {:executions (repeat 5 {:status :failure :duration-ms 50})
                   :success-count 0
                   :failure-count 5
                   :total-duration 250}
          result (service-logic/compute-node-metrics metrics)]
      (is (= 0.0 (:success-rate result)))
      (is (= 1.0 (:failure-rate result))))))

(deftest compute-node-metrics-empty-test
  (testing "returns nil for empty executions"
    (let [metrics {:executions []
                   :success-count 0
                   :failure-count 0
                   :total-duration 0}
          result (service-logic/compute-node-metrics metrics)]
      (is (nil? result)))))

(deftest compute-node-metrics-nil-duration-test
  (testing "handles nil duration gracefully"
    (let [metrics {:executions [{:status :success :duration-ms nil}
                                {:status :success :duration-ms 100}]
                   :success-count 2
                   :failure-count 0
                   :total-duration 100}
          result (service-logic/compute-node-metrics metrics)]
      (is (= 100.0 (:avg-duration-ms result))))))

(deftest compute-node-metrics-includes-trend-test
  (testing "includes trend calculation"
    (let [
          executions (repeat 20 {:status :success :duration-ms 100})
          metrics {:executions (vec executions)
                   :success-count 20
                   :failure-count 0
                   :total-duration 2000}
          result (service-logic/compute-node-metrics metrics)]
      (is (contains? result :recent-trend))
      (is (= :stable (:recent-trend result))))))

;; =============================================================================
;; Tree Metadata Read Model Tests
;; =============================================================================

(defn make-metadata-event
  "Create a mock tree-metadata-extracted event."
  [sheet-id metadata]
  {:event/type :sheet/tree-metadata-extracted
   :sheet-id sheet-id
   :metadata metadata})

(defn make-sheet-deleted-event
  "Create a mock sheet-deleted event."
  [sheet-id]
  {:event/type :sheet/sheet-deleted
   :sheet-id sheet-id})

(deftest tree-metadata-basic-test
  (testing "reduces metadata-extracted events correctly"
    (let [sheet-1 #uuid "11111111-1111-1111-1111-111111111111"
          metadata {:name "Test Sheet"
                    :problem-types ["problem:Classification"]}
          events [(make-metadata-event sheet-1 metadata)]
          state (service-logic/tree-metadata {} events)]
      (is (= metadata (get state sheet-1))))))

(deftest tree-metadata-multiple-sheets-test
  (testing "tracks multiple sheets"
    (let [sheet-1 #uuid "11111111-1111-1111-1111-111111111111"
          sheet-2 #uuid "22222222-2222-2222-2222-222222222222"
          events [(make-metadata-event sheet-1 {:name "Sheet 1"})
                  (make-metadata-event sheet-2 {:name "Sheet 2"})]
          state (service-logic/tree-metadata {} events)]
      (is (= "Sheet 1" (:name (get state sheet-1))))
      (is (= "Sheet 2" (:name (get state sheet-2)))))))

(deftest tree-metadata-update-test
  (testing "later events update metadata"
    (let [sheet-1 #uuid "11111111-1111-1111-1111-111111111111"
          events [(make-metadata-event sheet-1 {:name "Original"})
                  (make-metadata-event sheet-1 {:name "Updated"})]
          state (service-logic/tree-metadata {} events)]
      (is (= "Updated" (:name (get state sheet-1)))))))

(deftest tree-metadata-delete-test
  (testing "sheet-deleted removes metadata"
    (let [sheet-1 #uuid "11111111-1111-1111-1111-111111111111"
          events [(make-metadata-event sheet-1 {:name "Test"})
                  (make-sheet-deleted-event sheet-1)]
          state (service-logic/tree-metadata {} events)]
      (is (nil? (get state sheet-1))))))

(deftest tree-metadata-unknown-event-test
  (testing "ignores unknown event types"
    (let [events [{:event/type :unknown/event :body {:foo "bar"}}]
          state (service-logic/tree-metadata {} events)]
      (is (empty? state)))))
