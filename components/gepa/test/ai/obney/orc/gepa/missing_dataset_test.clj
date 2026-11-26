(ns ai.obney.orc.gepa.missing-dataset-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.gepa.core.todo-processors :as todo]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.grain.event-store-v3.interface :as es]))

(deftest a-missing-training-set-cannot-produce-a-scored-comparison
  (tu/with-test-context [ctx]
    (let [opt (get-in (tu/process-command! ctx
                       {:command/name :gepa/start-optimization
                        :sheet-id (random-uuid) :trainset [] :valset [{"question" "q"}]
                        :config {:max-metric-calls 10}})
                     [:command/result :optimization-id])
          candidate (get-in (tu/process-command! ctx
                             {:command/name :gepa/create-candidate
                              :optimization-id opt :instructions {"answer" "original"}
                              :parent-ids [] :mutation-reason "fixture"})
                           [:command/result :candidate-id])]
      (todo/on-subsample-evaluation-started
        (assoc ctx :event {:optimization-id opt :parent-id candidate
                          :proposed-instructions {"answer" "proposal"}
                          :component-updated "answer" :subsample-indices [0] :iteration 0}))
      (let [facts (into [] (es/read (:event-store ctx)
                            {:tenant-id (:tenant-id ctx) :tags #{[:optimization opt]}}))]
        (is (not-any? #(= :gepa/subsample-evaluated (:event/type %)) facts)
            "Missing observations cannot be replaced with random scores")
        (is (some #(= :gepa/optimization-failed (:event/type %)) facts)
            "Unavailable required data produces an explicit durable failure")))))
