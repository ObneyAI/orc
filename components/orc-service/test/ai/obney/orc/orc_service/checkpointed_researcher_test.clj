(ns ai.obney.orc.orc-service.checkpointed-researcher-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.time.interface :as time]))

(defn- checkpoint-command [sheet-id tick-id node-id checkpoint]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :sheet/checkpoint-researcher-iteration
   :sheet-id sheet-id
   :tick-id tick-id
   :node-id node-id
   :checkpoint checkpoint
   :inputs {}})

(defn checkpoint-predecessor [_]
  {:input "prepared-once"})

(deftest checkpoint-command-persists-and-fences-duplicate-resume
  (testing "checkpoint and continuation start are one durable, idempotent command batch"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            checkpoint {:version 1
                        :next-iteration 1
                        :history [{:code "(store! :memo 1)" :result 1}]
                        :sandbox-vars {:memo 1}
                        :var-creation-times {:memo 1}
                        :usage {:prompt-tokens 2 :completion-tokens 1 :total-tokens 3}
                        :cumulative-tree-ms 0
                        :campaign-started-at-ms 100
                        :campaign-deadline-ms 10000}
            first-result (h/run-and-apply!
                          ctx (checkpoint-command sheet-id tick-id node-id checkpoint))
            duplicate-result (h/run-and-apply!
                              ctx (checkpoint-command sheet-id tick-id node-id checkpoint))
            projected (rm/get-researcher-checkpoint ctx sheet-id tick-id node-id)]
        (is (= [:rlm/researcher-checkpointed :sheet/node-execution-started]
               (mapv :event/type (:command-result/events first-result))))
        (is (empty? (:command-result/events duplicate-result)))
        (is (= checkpoint (:checkpoint projected)))))))

(deftest abandoned-frontier-resume-is-cas-fenced
  (let [ctx (h/create-test-context)]
    (try
      (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          original (es/->event
                    {:type :sheet/node-execution-started
                     :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                     :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                            :inputs {}}})
          _ (es/append (:event-store ctx)
                       {:tenant-id (:tenant-id ctx) :events [original]})
          original-start-event-id
          (:event/id (first (into [] (es/read (:event-store ctx)
                                              {:tenant-id (:tenant-id ctx)
                                               :tags #{[:tick tick-id]}}))))
          command (fn []
                    {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :sheet/resume-node-execution
                     :sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :original-start-event-id original-start-event-id
                     :inputs {}})
          first-result (h/run-and-apply! ctx (command))
          duplicate-result (h/run-and-apply! ctx (command))]
      (is (= 1 (count (:command-result/events first-result))))
      (is (empty? (:command-result/events duplicate-result)))
      (is (= original-start-event-id
             (-> first-result :command-result/events first :resumed-from-event-id))))
      (finally
        (h/stop-context ctx)))))

(deftest sqlite-restart-rehydrates-the-next-iteration
  (testing "a newly opened JVM store can replay the checkpoint and continue"
    (let [db-file (str "/tmp/checkpointed-researcher-" (random-uuid) ".db")
          tenant-id (random-uuid)
          support (h/create-test-context)
          sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          checkpoint {:version 1
                      :next-iteration 1
                      :history [{:code "(store! :memo \"persisted\")"}]
                      :sandbox-vars {:memo "persisted"}
                      :var-creation-times {:memo 1}
                      :usage {:prompt-tokens 2 :completion-tokens 1 :total-tokens 3}
                      :cumulative-tree-ms 0
                      :campaign-started-at-ms (System/currentTimeMillis)
                      :campaign-deadline-ms (+ (System/currentTimeMillis) 60000)}]
      (try
        (let [store (es/start {:conn {:type :sqlite :database-file db-file
                                      :maximum-pool-size 2}})
              ctx (assoc support
                         :event-store store
                         :tenant-id tenant-id
                         :auth-claims {:subject "test"}
                         :command-registry (cp/global-command-registry))
              result (cp/process-command
                      (assoc ctx :command
                             (checkpoint-command sheet-id tick-id node-id checkpoint)))
              append-result (es/append store {:tenant-id tenant-id
                                               :events (:command-result/events result)})]
          (is (= 2 (count (:command-result/events result))) (pr-str result))
          (is (nil? (:cognitect.anomalies/category append-result)) (pr-str append-result))
          (es/stop store))
        (let [reopened (es/start {:conn {:type :sqlite :database-file db-file
                                         :maximum-pool-size 2}})
              events (into [] (es/read reopened {:tenant-id tenant-id
                                                  :tags #{[:sheet sheet-id]}}))
              projected (get (reduce rm/researcher-checkpoints* {} events)
                             [tick-id node-id])
              calls (atom 0)
              node {:type :repl-researcher
                    :instruction "finish"
                    :writes [:summary]
                    :rlm {:checkpointed? true}
                    :max-iterations 3}]
          (try
            (with-redefs [llm/predict
                          (fn [& _]
                            (swap! calls inc)
                            {:outputs {:code "(final! {:summary (get-var :memo)})"}})]
              (let [result (executor/execute-repl-researcher-rlm
                            node {} :openrouter
                            {:tick-id tick-id
                             :node-id node-id
                             :researcher-checkpoint (:checkpoint projected)})]
                (is (= :success (:status result)) (pr-str result))
                (is (= "persisted" (get-in result [:outputs :summary])))
                (is (= 1 @calls))))
            (finally (es/stop reopened))))
        (finally
          (h/stop-context support)
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest public-execution-reticks-and-traces-checkpointed-iterations
  (testing "the public workflow boundary yields internally, resumes, and exposes durable iteration trace data"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "checkpointed-public-e2e"
              (sheet/blackboard {:input :string :summary :string})
              (sheet/sequence "campaign"
                (sheet/code "predecessor"
                  :fn "ai.obney.orc.orc-service.checkpointed-researcher-test/checkpoint-predecessor"
                  :writes [:input])
                (sheet/repl-researcher "researcher"
                  :instruction "work in two iterations"
                  :reads [:input]
                  :writes [:summary]
                  :max-iterations 4
                  :rlm {:checkpointed? true
                        :quantum {:max-iterations 1}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}})))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"durable\")"}
                             :reasoning "save intermediate state"
                             :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
                          2 {:outputs {:code "(final! {:summary (get-var :memo)})"}
                             :reasoning "finish from checkpoint"
                             :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                trace-id (:trace-id result)]
            (is (= :success (:status result)) (pr-str result))
            (is (= "durable" (get-in result [:outputs :summary])))
            (is (= 2 @calls))
            (is (h/settle-until! #(some? (rm/get-trace ctx trace-id))))
            (let [trace (rm/get-trace ctx trace-id)
                  predecessor-id (:id (first (filter #(= "predecessor" (:name %))
                                                     (sheet/get-nodes-for-sheet ctx sheet-id))))
                  tick-events (into [] (es/read (:event-store ctx)
                                                {:tenant-id (:tenant-id ctx)
                                                 :tags #{[:tick trace-id]}}))
                  checkpoint-events (filter #(= :rlm/researcher-checkpointed (:event/type %))
                                            tick-events)
                  action-events (filter #(= :rlm/researcher-action-completed (:event/type %))
                                        tick-events)]
              (is (= 2 (count checkpoint-events))
                  "the yielded and terminal iterations are independently durable")
              (is (= 2 (count action-events)))
              (is (= 1 (count (filter #(and (= :sheet/node-execution-completed
                                                (:event/type %))
                                             (= predecessor-id (:node-id %)))
                                       tick-events)))
                  "the completed predecessor is not rerun when the researcher resumes")
              (is (= [1 2] (mapv :iteration (:researcher-iterations trace))))
              (is (= [:action-completed :checkpoint :yield :resume
                      :action-completed :checkpoint]
                     (mapv :type (:researcher-events trace)))))))))))

(deftest later-timeout-retains-prior-iteration-in-public-trace
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [calls (atom 0)
          definition
          (sheet/workflow "checkpointed-timeout-trace"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "checkpoint then timeout"
              :writes [:summary]
              :max-iterations 4
              :rlm {:checkpointed? true
                    :timeouts {:provider-ms 15 :iteration-ms 100 :campaign-ms 5000}
                    :iteration-retry {:max-attempts 1}}))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [& _]
                      (if (= 1 (swap! calls inc))
                        {:outputs {:code "(store! :memo \"kept\")"}
                         :reasoning "completed iteration"
                         :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}}
                        (do (Thread/sleep 250)
                            {:outputs {:code "(final! {:summary \"late\"})"}})))]
        (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
              trace-id (:trace-id result)]
          (is (= :timeout (:status result)) (pr-str result))
          (is (h/settle-until! #(some? (rm/get-trace ctx trace-id))))
          (let [trace (rm/get-trace ctx trace-id)
                iteration (first (:researcher-iterations trace))]
            (is (= :timeout (:status trace)))
            (is (= [1] (mapv :iteration (:researcher-iterations trace))))
            (is (= "completed iteration" (:reasoning iteration)))
            (is (number? (:provider-latency-ms iteration)))
            (is (= 5 (get-in iteration [:provider-usage :total-tokens])))))))))
