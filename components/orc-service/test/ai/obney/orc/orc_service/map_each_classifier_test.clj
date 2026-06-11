(ns ai.obney.orc.orc-service.map-each-classifier-test
  "Unit tests for the pure classify-map-each-outcome function (D-008).

   Classifier signature:
     (classify-map-each-outcome {:results [...] :item-count N})
     ;; => [status partial-summary-or-nil output-vector]

   Where:
     :results    = vector of raw values (success) or {:__status :failure :__error ...} markers
     :item-count = total items started
     status      = :success | :partial | :failure
     summary     = nil OR {:total :succeeded :failed :failure-indices :failure-reasons}
     output      = successes-only vector (per Q3 — no inline failure markers)"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]))

(deftest empty-input-classifies-as-success
  (testing "empty results + zero items → :success, no summary, empty output"
    (let [[status summary output]
          (tp/classify-map-each-outcome {:results [] :item-count 0})]
      (is (= :success status))
      (is (nil? summary))
      (is (= [] output)))))

(deftest all-success-classifies-as-success-with-full-output
  (testing "3 successful results → :success, no summary, output is full vector"
    (let [[status summary output]
          (tp/classify-map-each-outcome
            {:results ["alpha" "beta" "gamma"]
             :item-count 3})]
      (is (= :success status))
      (is (nil? summary))
      (is (= ["alpha" "beta" "gamma"] output)))))

(deftest mixed-success-and-failure-classifies-as-partial
  (testing "5 items, 2 failures at indices 1 and 3 → :partial, summary with indices+reasons, output is successes-only"
    (let [results [;; index 0 — success
                   "first-result"
                   ;; index 1 — failure
                   {:__status :failure :__error "LLM timeout" :__original "chunk-1"}
                   ;; index 2 — success
                   "third-result"
                   ;; index 3 — failure
                   {:__status :failure :__error "Rate limit" :__original "chunk-3"}
                   ;; index 4 — success
                   "fifth-result"]
          [status summary output]
          (tp/classify-map-each-outcome
            {:results results :item-count 5})]
      (is (= :partial status))
      (is (some? summary))
      (is (= 5 (:total summary)))
      (is (= 3 (:succeeded summary)))
      (is (= 2 (:failed summary)))
      (is (= [1 3] (:failure-indices summary)))
      (is (= {1 "LLM timeout" 3 "Rate limit"} (:failure-reasons summary)))
      (is (= ["first-result" "third-result" "fifth-result"] output)
          "output contains only successful items, in original order, no failure markers"))))

(deftest all-failure-classifies-as-failure-with-empty-output
  (testing "3 items all failed → :failure, summary :succeeded 0, output []"
    (let [results [{:__status :failure :__error "Err A" :__original "x"}
                   {:__status :failure :__error "Err B" :__original "y"}
                   {:__status :failure :__error "Err C" :__original "z"}]
          [status summary output]
          (tp/classify-map-each-outcome
            {:results results :item-count 3})]
      (is (= :failure status))
      (is (some? summary))
      (is (= 3 (:total summary)))
      (is (= 0 (:succeeded summary)))
      (is (= 3 (:failed summary)))
      (is (= [0 1 2] (:failure-indices summary)))
      (is (= {0 "Err A" 1 "Err B" 2 "Err C"} (:failure-reasons summary)))
      (is (= [] output) "output is empty when all items failed"))))
