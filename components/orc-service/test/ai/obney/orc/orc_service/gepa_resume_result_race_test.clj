(ns ai.obney.orc.orc-service.gepa-resume-result-race-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.todo-processors :as todo]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.event-store-v3.interface :as es])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn answer [_] {:answer "fixed"})

(deftest resume-and-subscriber-publish-one-subsample-result
  (let [metric-calls (atom [])
        initial (h/create-async-test-context
                  {:context {:gepa/metric-fn
                             (fn [input output]
                               (swap! metric-calls conj {:input input :output output})
                               0.0)}})
        owned (atom initial)]
    (try
      (h/stop-test-processors! initial)
      (let [ctx (dissoc initial :processors)
            sheet-id (sheet/build-workflow! ctx
                       (sheet/workflow "resume-result-race"
                         (sheet/blackboard {:question :string :answer :string})
                         (sheet/code "answer" :fn "ai.obney.orc.orc-service.gepa-resume-result-race-test/answer"
                                     :reads [:question] :writes [:answer])))
            start (tu/process-command! ctx {:command/name :gepa/start-optimization
                                      :sheet-id sheet-id :trainset [{"question" "q"}]
                                      :valset [{"question" "q"}]
                                      :config {:max-metric-calls 10 :skip-perfect-score false
                                               :pause-after-proposal? true}})
            opt-id (get-in start [:command/result :optimization-id])
            candidate (tu/process-command! ctx {:command/name :gepa/create-candidate
                                          :optimization-id opt-id :instructions {"answer" "old"}
                                          :parent-ids [] :mutation-reason "fixture"})
            parent-id (get-in candidate [:command/result :candidate-id])
            proposal (tu/process-command! ctx {:command/name :gepa/record-proposal-ready
                                         :optimization-id opt-id :proposal-id (random-uuid)
                                         :parent-id parent-id :iteration 0
                                         :proposed-instructions {"answer" "new"}
                                         :component-updated "answer" :subsample-indices [0]})
            ;; Exercise actual resume plus actual registered pubsub delivery.
            ;; Other GEPA stages are deliberately outside this one-boundary test.
            without (into #{} (remove #(or (= "sheet" (namespace %))
                                           (= :gepa/on-subsample-evaluation-started %)))
                          (keys @tp/processor-registry*))
            running (assoc ctx :processors (h/start-test-processors ctx without))
            original-command cp/process-command
            original-handler todo/on-subsample-evaluation-started
            deliveries (atom [])
            delivery-barrier (CountDownLatch. 2)
            commands (atom [])]
        (reset! owned running)
        (is (uuid? opt-id))
        (is (uuid? parent-id))
        (is (= :ready (get-in proposal [:command/result :status])))
        (with-redefs [todo/on-subsample-evaluation-started
                      (fn [context]
                        ;; Command-return events precede store-assigned IDs;
                        ;; correlate their durable logical boundary instead.
                        (swap! deliveries conj
                               (select-keys (:event context)
                                            [:optimization-id :parent-id :iteration]))
                        (.countDown delivery-barrier)
                        (when-not (.await delivery-barrier 15 TimeUnit/SECONDS)
                          (throw (ex-info "Both actual delivery paths must enter the handler" {})))
                        (original-handler context))
                      cp/process-command
                      (fn [context]
                        (let [result (original-command context)]
                          (when (= :gepa/record-subsample-result (get-in context [:command :command/name]))
                            (swap! commands conj {:command (:command context) :result result
                                                  :thread (.getName (Thread/currentThread))}))
                          result))]
          (is (= :proposal-ready (:boundary (gepa/resume! running opt-id))))
          (is (h/settle-until!
                #(seq (filter (fn [e] (= :gepa/subsample-evaluated (:event/type e)))
                              (h/read-all-events running))))))
        (let [events (h/read-all-events running)
              results (filter #(= :gepa/subsample-evaluated (:event/type %)) events)
              starts (filter #(= :gepa/subsample-evaluation-started (:event/type %)) events)]
          (is (= 1 (count starts)))
          (is (= 2 (count @deliveries)) "Both resume and the registered subscriber delivered work")
          (is (= 1 (count (set @deliveries))) "Both deliveries refer to the same durable start")
          (is (= 1 (count results)) "Resume and subscriber share one terminal result")
          (is (= 2 (count (filter #(= :sheet/tree-tick-started (:event/type %)) events)))
              "Parent and proposal each execute one tree, not one tree per delivery")
          (is (= 2 (count (filter #(= :sheet/node-execution-completed (:event/type %)) events)))
              "Actual code leaves execute only once per intended evaluation")
          (is (= 2 (count @metric-calls)) "A duplicate delivery cannot invoke the metric again")
          (is (= (count @metric-calls)
                 (:total-metric-calls (gepa/get-optimization-summary running opt-id)))
              "Projected expenditure agrees with actual metric invocations")
          (is (every? #(= {:input {"question" "q"}
                          :output {:question "q" :answer "fixed"}} %) @metric-calls)
              "Both real metric invocations receive the original input and complete output")
          (let [tasks (filter #(= :gepa/task-call-completed (:event/type %)) events)]
            (is (= 2 (count tasks)) "Exactly one durable completion per intended task")
            (is (= 2 (count (set (map :call-id tasks))))))
          (is (not-any? #(= :cognitect.anomalies/fault
                              (get-in % [:result :cognitect.anomalies/category])) @commands)
              "A result count cannot pass because a worker faulted")
          (when-let [path (System/getenv "GEPA_RACE_EVIDENCE")]
            (spit path (pr-str {:events events :commands @commands})))))
      (finally (h/stop-async-context @owned)))))
