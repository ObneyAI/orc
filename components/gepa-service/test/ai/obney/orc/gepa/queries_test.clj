(ns ai.obney.orc.gepa.queries-test
  "Integration tests for GEPA query handlers.

   Seeds events via commands and verifies queries return correct data
   through read models (no direct event-store access)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.gepa.core.commands :as cmd]
            [ai.obney.orc.gepa.core.queries :as qry]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.interface.schemas]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- start-optimization!
  "Start an optimization and apply its events. Returns the optimization-id."
  [ctx sheet-id]
  (let [result (tu/process-command! ctx
                 {:command/name :gepa/start-optimization
                  :sheet-id sheet-id
                  :trainset [{"question" "What is 2+2?" "answer" "4"}]
                  :valset [{"question" "What is 3*3?" "answer" "9"}
                           {"question" "What is 5+5?" "answer" "10"}]
                  :config {:max-metric-calls 50
                           :reflection-minibatch-size 3
                           :reflection-lm "anthropic/claude-sonnet-4"
                           :use-merge true
                           :crossover-rate 0.3}})]
    (tu/apply-events! ctx result)
    (get-in result [:command/result :optimization-id])))

(defn- create-candidate!
  "Create a candidate and apply its events. Returns the candidate-id."
  [ctx optimization-id instructions parent-ids]
  (let [result (tu/process-command! ctx
                 {:command/name :gepa/create-candidate
                  :optimization-id optimization-id
                  :instructions instructions
                  :parent-ids parent-ids
                  :mutation-reason nil})]
    (tu/apply-events! ctx result)
    (get-in result [:command/result :candidate-id])))

(defn- record-evaluation!
  "Record evaluation results and apply events."
  [ctx optimization-id candidate-id scores metric-calls]
  (let [result (tu/process-command! ctx
                 {:command/name :gepa/record-evaluation-result
                  :optimization-id optimization-id
                  :candidate-id candidate-id
                  :scores (vec scores)
                  :trace-ids [(random-uuid)]
                  :metric-calls metric-calls})]
    (tu/apply-events! ctx result)
    result))

(defn- complete-optimization!
  "Complete an optimization and apply events."
  [ctx optimization-id]
  (let [result (tu/process-command! ctx
                 {:command/name :gepa/complete-optimization
                  :optimization-id optimization-id})]
    (tu/apply-events! ctx result)
    result))

;; =============================================================================
;; get-candidate Tests
;; =============================================================================

(deftest get-candidate-test
  (testing "returns candidate from read model"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            cand-id (create-candidate! ctx opt-id
                      {"instruction" "Be helpful"} [nil])
            result (qry/gepa-get-candidate
                     (assoc ctx :query {:optimization-id opt-id
                                        :candidate-id cand-id}))]
        (is (contains? result :query/result))
        (is (= cand-id (get-in result [:query/result :candidate-id])))
        (is (= {"instruction" "Be helpful"}
               (get-in result [:query/result :instructions]))))))

  (testing "returns not-found for unknown candidate"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            result (qry/gepa-get-candidate
                     (assoc ctx :query {:optimization-id opt-id
                                        :candidate-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result))))))

  (testing "returns evaluated candidate with scores"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            cand-id (create-candidate! ctx opt-id
                      {"instruction" "Be helpful"} [nil])
            _ (record-evaluation! ctx opt-id cand-id [0.8 0.9] 2)
            result (qry/gepa-get-candidate
                     (assoc ctx :query {:optimization-id opt-id
                                        :candidate-id cand-id}))]
        (is (= :evaluated (get-in result [:query/result :status])))
        (is (= [0.8 0.9] (get-in result [:query/result :scores])))))))

;; =============================================================================
;; list-optimizations Tests
;; =============================================================================

(deftest list-optimizations-test
  (testing "lists all optimizations for a sheet"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id-1 (start-optimization! ctx sheet-id)
            opt-id-2 (start-optimization! ctx sheet-id)
            result (qry/gepa-list-optimizations
                     (assoc ctx :query {:sheet-id sheet-id}))]
        (is (contains? result :query/result))
        (is (= 2 (get-in result [:query/result :total])))
        (is (= 2 (count (get-in result [:query/result :optimizations])))))))

  (testing "filters by status"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id-1 (start-optimization! ctx sheet-id)
            opt-id-2 (start-optimization! ctx sheet-id)
            cand-id (create-candidate! ctx opt-id-1
                      {"instruction" "Be helpful"} [nil])
            _ (record-evaluation! ctx opt-id-1 cand-id [0.8 0.9] 2)
            _ (complete-optimization! ctx opt-id-1)
            result (qry/gepa-list-optimizations
                     (assoc ctx :query {:sheet-id sheet-id
                                        :status :completed}))]
        (is (= 1 (get-in result [:query/result :total])))
        (is (= :completed
               (get-in result [:query/result :optimizations 0 :status]))))))

  (testing "respects limit"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            _ (start-optimization! ctx sheet-id)
            _ (start-optimization! ctx sheet-id)
            _ (start-optimization! ctx sheet-id)
            result (qry/gepa-list-optimizations
                     (assoc ctx :query {:sheet-id sheet-id
                                        :limit 2}))]
        (is (= 2 (count (get-in result [:query/result :optimizations]))))
        (is (= 3 (get-in result [:query/result :total])))))))

;; =============================================================================
;; get-optimization-state Tests
;; =============================================================================

(deftest get-optimization-state-test
  (testing "returns optimization state"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            result (qry/gepa-get-optimization-state
                     (assoc ctx :query {:optimization-id opt-id}))]
        (is (contains? result :query/result))
        (is (= opt-id (get-in result [:query/result :optimization-id])))
        (is (= :running (get-in result [:query/result :status]))))))

  (testing "returns not-found for unknown optimization"
    (tu/with-test-context [ctx]
      (let [result (qry/gepa-get-optimization-state
                     (assoc ctx :query {:optimization-id (random-uuid)}))]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; get-population Tests
;; =============================================================================

(deftest get-population-test
  (testing "returns population with candidates"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            _ (create-candidate! ctx opt-id {"instruction" "A"} [nil])
            _ (create-candidate! ctx opt-id {"instruction" "B"} [nil])
            result (qry/gepa-get-population
                     (assoc ctx :query {:optimization-id opt-id}))]
        (is (contains? result :query/result))
        (is (= 2 (get-in result [:query/result :total])))
        (is (= 0 (get-in result [:query/result :evaluated])))))))

;; =============================================================================
;; get-best-candidate Tests
;; =============================================================================

(deftest get-best-candidate-test
  (testing "returns best candidate after evaluation"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            cand-1 (create-candidate! ctx opt-id {"instruction" "A"} [nil])
            cand-2 (create-candidate! ctx opt-id {"instruction" "B"} [nil])
            _ (record-evaluation! ctx opt-id cand-1 [0.5 0.6] 2)
            _ (record-evaluation! ctx opt-id cand-2 [0.8 0.9] 2)
            result (qry/gepa-get-best-candidate
                     (assoc ctx :query {:optimization-id opt-id}))]
        (is (contains? result :query/result))
        (is (= cand-2 (get-in result [:query/result :candidate-id]))))))

  (testing "returns not-found when no candidates evaluated"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            result (qry/gepa-get-best-candidate
                     (assoc ctx :query {:optimization-id opt-id}))]
        (is (= ::anom/not-found (::anom/category result)))))))


(deftest get-iteration-count-test
  (testing "counts evaluated candidates as iterations"
    (tu/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            opt-id (start-optimization! ctx sheet-id)
            cand-1 (create-candidate! ctx opt-id {"instruction" "A"} [nil])
            cand-2 (create-candidate! ctx opt-id {"instruction" "B"} [nil])]
        (is (= 0 (rm/get-iteration-count ctx opt-id)))
        (record-evaluation! ctx opt-id cand-1 [0.5 0.6] 2)
        (is (= 1 (rm/get-iteration-count ctx opt-id)))
        (record-evaluation! ctx opt-id cand-2 [0.8 0.9] 2)
        (is (= 2 (rm/get-iteration-count ctx opt-id)))))))
