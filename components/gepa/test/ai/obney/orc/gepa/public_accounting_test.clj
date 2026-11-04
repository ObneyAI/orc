(ns ai.obney.orc.gepa.public-accounting-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.grain-test-utils.interface :as tu]))

(defn- start! [ctx budget]
  (get-in (tu/process-command! ctx
            {:command/name :gepa/start-optimization :sheet-id (random-uuid)
             :trainset [{"question" "q"}] :valset [{"question" "q"}]
             :config {:max-metric-calls budget}})
          [:command/result :optimization-id]))

(defn- admit! [ctx opt]
  (let [task {:call-id (random-uuid) :trace-id (random-uuid) :role :candidate
              :example-index 0 :input {"question" "q"}
              :instructions {"answer" "Respond"}
              :metric-retry-safe? true :max-metric-attempts 3}
        boundary-id (random-uuid)
        owner {:optimization-id opt :boundary-id boundary-id
               :call-id (:call-id task) :owner-id (random-uuid) :epoch 1}]
    (is (= :opened (:status (ledger/open-boundary! ctx
                              {:optimization-id opt :boundary-id boundary-id
                               :boundary {:kind :candidate :candidate-id (random-uuid)}
                               :tasks [task]}))))
    (is (= :owned (:status (ledger/claim! ctx owner))))
    owner))

(defn- public-views [ctx opt]
  {:state (gepa/get-gepa-state ctx opt)
   :population-state (gepa/get-population-state ctx opt)
   :summary (gepa/get-optimization-summary ctx opt)
   :population (:query/result (gepa/get-population ctx opt))
   :progress (:query/result (gepa/get-progress ctx opt))})

(deftest dispatched-work-is-visible-before-any-aggregate
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 10)
          owner (admit! ctx opt)]
      (is (= :dispatched (:status (ledger/dispatch! ctx
                                   (assoc owner :attempt-id (random-uuid)
                                                :attempt-kind :evaluation)))))
      (let [views (public-views ctx opt)]
        (doseq [view [:state :population-state :summary :population]]
          (is (= 1 (get-in views [view :total-metric-calls])) (name view)))
        (is (= 1 (get-in views [:progress :metric-calls :used])))
        (is (= 0 (get-in views [:population :evaluated])))
        (is (= 0 (get-in views [:progress :candidates :evaluated])))
        (is (empty? (get-in views [:population-state :evaluated])))))))

(deftest legacy-spend-and-reservations-stay-distinct
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 2)
          candidate (get-in (tu/process-command! ctx
                              {:command/name :gepa/create-candidate :optimization-id opt
                               :instructions {"answer" "Respond"} :parent-ids []
                               :mutation-reason "legacy fixture"})
                            [:command/result :candidate-id])
          result (tu/process-command! ctx
                   {:command/name :gepa/record-evaluation-result :optimization-id opt
                    :candidate-id candidate :scores [0.7] :trace-ids [(random-uuid)]
                    :metric-calls 1})
          owner (admit! ctx opt)]
      (is (seq (:command-result/events result)))
      (let [views (public-views ctx opt)]
        (doseq [view [:state :population-state :summary :population]]
          (is (= {:total-metric-calls 1 :reserved-metric-calls 1 :available-metric-calls 0}
                 (select-keys (get views view)
                              [:total-metric-calls :reserved-metric-calls :available-metric-calls]))
              (name view)))
        (is (= {:used 1 :reserved 1 :available 0 :budget 2}
               (get-in views [:progress :metric-calls])))
        (is (false? (gepa/budget-exhausted? ctx opt))
            "Reserved capacity does not declare the budget spent or finalize active work"))
      (is (= :dispatched (:status (ledger/dispatch! ctx
                                   (assoc owner :attempt-id (random-uuid)
                                                :attempt-kind :evaluation)))))
      (let [views (public-views ctx opt)]
        (doseq [view [:state :population-state :summary :population]]
          (is (= {:total-metric-calls 2 :reserved-metric-calls 0 :available-metric-calls 0}
                 (select-keys (get views view)
                              [:total-metric-calls :reserved-metric-calls :available-metric-calls]))
              (name view)))
        (is (= {:used 2 :reserved 0 :available 0 :budget 2}
               (get-in views [:progress :metric-calls])))
        (is (= 1 (get-in views [:population :evaluated])))
        (is (true? (gepa/budget-exhausted? ctx opt)))))))

(deftest explicitly-adopted-legacy-metric-is-visible-in-summary
  (tu/with-test-context [ctx]
    (let [legacy-context (assoc ctx :gepa/metric-fn (fn [_ _] {:score 0.5}))
          opt (start! legacy-context 2)
          descriptor {:kind :custom :metric-id "domain/legacy" :revision "2"
                      :configuration {} :retry-safe? true
                      :max-attempts 3 :timeout-ms 300000}]
      (is (nil? (:metric-descriptor (gepa/get-optimization-summary ctx opt))))
      (is (= :selected
             (get-in (tu/process-command! ctx
                       {:command/name :gepa/select-metric :optimization-id opt
                        :metric-descriptor descriptor})
                     [:command/result :status])))
      (is (= descriptor (:metric-descriptor (gepa/get-optimization-summary ctx opt)))))))

(deftest completed-task-aggregate-does-not-charge-public-views-twice
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 3)
          candidate (get-in (tu/process-command! ctx
                              {:command/name :gepa/create-candidate :optimization-id opt
                               :instructions {"answer" "Respond"} :parent-ids []
                               :mutation-reason "managed fixture"})
                            [:command/result :candidate-id])
          task {:call-id (random-uuid) :trace-id (random-uuid) :role :candidate
                :example-index 0 :input {"question" "q"} :instructions {"answer" "Respond"}
                :metric-retry-safe? true :max-metric-attempts 3}
          boundary-id (random-uuid)
          owner {:optimization-id opt :boundary-id boundary-id :call-id (:call-id task)
                 :owner-id (random-uuid) :epoch 1 :attempt-id (random-uuid)}]
      (is (= :opened (:status (ledger/open-boundary! ctx
                               {:optimization-id opt :boundary-id boundary-id
                                :boundary {:kind :candidate :candidate-id candidate}
                                :tasks [task]}))))
      (is (= :owned (:status (ledger/claim! ctx owner))))
      (is (= :dispatched (:status (ledger/dispatch! ctx (assoc owner :attempt-kind :evaluation)))))
      (is (= :workflow-recorded (:status (ledger/record-workflow! ctx
                                          (assoc owner :result {:status :success
                                                               :trace-id (:trace-id task)
                                                               :outputs {:answer "done"}})))))
      (is (= :metric-started (:status (ledger/begin-metric! ctx owner))))
      (is (= :completed (:status (ledger/finish! ctx
                                  (assoc owner :result {:output {:answer "done"} :score 0.8})))))
      (let [before (public-views ctx opt)
            published (tu/process-command! ctx
                        {:command/name :gepa/record-evaluation-result :optimization-id opt
                         :candidate-id candidate :scores [0.8] :trace-ids [(:trace-id task)]
                         :metric-calls 1 :accounting-mode :tasks
                         :task-call-ids [(:call-id task)]})
            after (public-views ctx opt)]
        (is (seq (:command-result/events published)))
        (doseq [view [:state :population-state :summary :population]]
          (is (= 1 (get-in before [view :total-metric-calls])
                   (get-in after [view :total-metric-calls])) (name view)))
        (is (= {:used 1 :reserved 0 :available 2 :budget 3}
               (get-in before [:progress :metric-calls])
               (get-in after [:progress :metric-calls])))
        (is (= 0 (get-in before [:population :evaluated])))
        (is (= 1 (get-in after [:population :evaluated])))
        (is (= 1 (get-in after [:progress :candidates :evaluated])))))))
