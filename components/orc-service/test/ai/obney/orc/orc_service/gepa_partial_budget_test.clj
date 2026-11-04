(ns ai.obney.orc.orc-service.gepa-partial-budget-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))

(defn answer [_] {:answer "fixed"})

(defn- setup! [ctx budget]
  (let [sheet-id (sheet/build-workflow! ctx
                   (sheet/workflow "partial-budget"
                     (sheet/blackboard {:question :string :expected :string :answer :string})
                     (sheet/code "answer"
                       :fn "ai.obney.orc.orc-service.gepa-partial-budget-test/answer"
                       :reads [:question] :writes [:answer])))
        examples [{"question" "first" "expected" "fixed"}
                  {"question" "second" "expected" "fixed"}]]
    (get-in (tu/process-command! ctx
              {:command/name :gepa/start-optimization :sheet-id sheet-id
               :trainset examples :valset examples
               :config {:max-metric-calls budget}})
            [:command/result :optimization-id])))

(defn- candidate! [ctx opt]
  (get-in (tu/process-command! ctx
            {:command/name :gepa/create-candidate :optimization-id opt
             :instructions {"answer" "Answer the supplied question"}
             :parent-ids [] :mutation-reason "budget boundary fixture"})
          [:command/result :candidate-id]))

(defn- start-evaluation-processors [ctx]
  (let [without (into #{} (remove #(or (= "sheet" (namespace %))
                                      (= :gepa/on-candidate-evaluation-started %)))
                      (keys @tp/processor-registry*))]
    (assoc ctx :processors (h/start-test-processors ctx without))))

(deftest incomplete-first-candidate-fails-durably-at-budget-exhaustion
  (let [initial (h/create-async-test-context) owned (atom initial)]
    (try
      (h/stop-test-processors! initial)
      (let [ctx (dissoc initial :processors)
            opt (setup! ctx 1) candidate (candidate! ctx opt)
            running (start-evaluation-processors ctx)]
        (reset! owned running)
        (is (nil? (:cognitect.anomalies/category
                    (tu/process-command! running
                      {:command/name :gepa/evaluate-candidate
                       :optimization-id opt :candidate-id candidate}))))
        (is (h/settle-until! #(= :failed (:status (gepa/get-optimization-summary running opt)))
                            :timeout-ms 10000))
        (let [events (h/read-all-events running)
              facts (fn [type] (filter #(= type (:event/type %)) events))
              summary (gepa/get-optimization-summary running opt)]
          (is (= :failed (:status summary)))
          (is (re-find #"(?i)budget" (or (:error-message summary) "")))
          (is (= 1 (count (facts :sheet/tree-tick-started))))
          (is (= 1 (count (facts :gepa/task-metric-started))))
          (is (= 1 (count (facts :gepa/task-call-completed))))
          (is (empty? (facts :gepa/candidate-evaluated)))
          (is (= 1 (:total-metric-calls summary) (:spent (ledger/get-accounting running opt))))))
      (finally (h/stop-async-context @owned)))))

(deftest partial-successor-preserves-the-fully-evaluated-winner
  (let [initial (h/create-async-test-context) owned (atom initial)]
    (try
      (h/stop-test-processors! initial)
      (let [ctx (dissoc initial :processors)
            opt (setup! ctx 3) first-candidate (candidate! ctx opt)
            running (start-evaluation-processors ctx)]
        (reset! owned running)
        (tu/process-command! running {:command/name :gepa/evaluate-candidate
                                      :optimization-id opt :candidate-id first-candidate})
        (is (h/settle-until! #(= first-candidate
                                (:best-candidate-id (gepa/get-optimization-summary running opt)))
                            :timeout-ms 10000))
        (let [successor (candidate! running opt)]
          (is (nil? (:cognitect.anomalies/category
                      (tu/process-command! running {:command/name :gepa/evaluate-candidate
                                                    :optimization-id opt :candidate-id successor}))))
          (is (h/settle-until! #(#{:completed :failed}
                                 (:status (gepa/get-optimization-summary running opt)))
                              :timeout-ms 10000))
          (let [events (h/read-all-events running)
                facts (fn [type] (filter #(= type (:event/type %)) events))
                summary (gepa/get-optimization-summary running opt)]
            (is (= :completed (:status summary)))
            (is (= first-candidate (:best-candidate-id summary)))
            (is (= 3 (count (facts :sheet/tree-tick-started))))
            (is (= 3 (count (facts :gepa/task-metric-started))))
            (is (= 3 (count (facts :gepa/task-call-completed))))
            (is (= [first-candidate] (mapv :candidate-id (facts :gepa/candidate-evaluated))))
            (is (= 1 (count (facts :gepa/optimization-completed))))
            (is (empty? (facts :gepa/optimization-failed)))
            (doseq [view [(gepa/get-gepa-state running opt)
                         (gepa/get-population-state running opt)
                         summary
                         (:query/result (gepa/get-population running opt))]]
              (is (= 3 (:total-metric-calls view))))
            (is (= 3 (get-in (gepa/get-progress running opt) [:query/result :metric-calls :used])))
            (is (= {:spent 3 :reserved 0 :budget 3 :available 0}
                   (ledger/get-accounting running opt))))))
      (finally (h/stop-async-context @owned)))))
