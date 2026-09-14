(ns ai.obney.orc.orc-service.rr26-two-workers-one-frontier-test
  "RR-26 Seam-7 — two workers receive ONE recovery start for one yielded campaign
   and race the frontier claim under a deterministic barrier.

   The scan-level race (two scanners converging on one start) is covered by
   `concurrent-and-repeated-recovery-converge-on-one-start`. This seam is one
   level down: the single start is delivered to two execution workers (the
   real at-least-once shape of a duplicated delivery, or of two subscribers on
   one stream), both compute the same next ownership epoch, and both attempt
   `:sheet/claim-researcher-frontier`. Exactly one epoch is claimed; the losing
   worker dispatches nothing — no provider call, no classification, no
   checkpoint, no completion — and a late write carrying the epoch it failed to
   claim is fenced by the durable frontier."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defn- iteration-commit-command
  [sheet-id tick-id node-id resume-state iteration-record]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :sheet/checkpoint-researcher-iteration
   :sheet-id sheet-id
   :tick-id tick-id
   :node-id node-id
   :resume-state resume-state
   :iteration-record iteration-record
   :inputs {}})

(defn- start-second-execution-worker
  "A second subscriber to the node-execution topic against the SAME store,
   cache and pubsub — the registered leaf-execution handler, started exactly
   as the test harness starts the first one."
  [base-ctx]
  (let [{:keys [handler-fn topics]} (get @tp/processor-registry* :sheet/execute-leaf-node)]
    (tp/start {:event-pubsub (:event-pubsub base-ctx)
               :topics topics
               :handler-fn handler-fn
               :context base-ctx})))

(deftest det-e2e-288-two-workers-race-one-frontier-one-epoch-wins
  (testing "one recovery start delivered to two workers yields one claimed epoch, one provider call and a fenced loser"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            claim-attempts (atom 0)
            definition
            (sheet/workflow "rr26-two-workers-one-frontier"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish once despite a duplicated start"
                :writes [:summary]
                :max-iterations 3
                :rlm {:checkpointed? true
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            tick-id (random-uuid)
            now-ms (System/currentTimeMillis)
            resume-state {:version 2
                          :revision 1
                          :ownership-epoch 0
                          :next-iteration 1
                          :sandbox-vars {:memo "one-winner"}
                          :var-creation-times {:memo 0}
                          :usage {:prompt-tokens 2
                                  :completion-tokens 1
                                  :total-tokens 3}
                          :cumulative-tree-ms 0
                          :iteration-attempts {}
                          :campaign-started-at-ms now-ms
                          :campaign-deadline-ms (+ now-ms 15000)}
            iteration-record {:iteration-index 0
                              :attempt-ordinal 0
                              :status :success
                              :started-at "2026-01-01T00:00:00Z"
                              :completed-at "2026-01-01T00:00:00.010Z"
                              :duration-ms 10
                              :code "(store! :memo \"one-winner\")"
                              :generated-code-recorded? true
                              :emitted-tree-recorded? false}
            worker-a (atom nil)
            worker-b (atom nil)]
        (try
          (h/stop-test-processors! ctx)
          (h/run-and-apply!
           ctx
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :sheet/tick-tree
            :sheet-id sheet-id
            :tick-id tick-id
            :inputs {}
            :options {:timeout-ms 15000}})
          (h/run-and-apply!
           ctx
           (iteration-commit-command sheet-id tick-id researcher-id
                                     resume-state iteration-record))
          (let [process-command cp/process-command
                ready (java.util.concurrent.CountDownLatch. 2)
                release (promise)]
            (with-redefs [llm/predict
                          (fn [& _]
                            (swap! calls inc)
                            {:outputs {:code "(final! {:summary (get-var :memo)})"}
                             :reasoning "the one worker that owns the frontier finishes"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}})
                          cp/process-command
                          (fn [command-ctx]
                            (if (= :sheet/claim-researcher-frontier
                                   (get-in command-ctx [:command :command/name]))
                              (do
                                (swap! claim-attempts inc)
                                (.countDown ready)
                                ;; Deterministic barrier: neither worker appends
                                ;; its claim until both have decided to claim.
                                ;; Bounded so a missing partner cannot hang the run.
                                (deref release 5000 nil)
                                (process-command command-ctx))
                              (process-command command-ctx)))]
              (let [base-ctx (dissoc ctx :processors)
                    a (h/start-test-processors base-ctx)
                    _ (reset! worker-a a)
                    b (start-second-execution-worker base-ctx)
                    _ (reset! worker-b b)
                    scan (sheet/resume-in-progress! ctx)]
                (is (= 1 (count (filter :resumed? scan))) (pr-str scan))
                (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS)
                    "both workers receive the one start and reach the frontier claim")
                (deliver release true)
                (is (h/settle-until!
                     #(some? (runtime/durable-terminal-result ctx tick-id))
                     :timeout-ms 7000)
                    "the winner finishes the campaign")
                (let [events (h/read-tick-events ctx tick-id)
                      for-node (fn [type] (filterv #(and (= type (:event/type %))
                                                         (= researcher-id (:node-id %)))
                                                   events))
                      frontiers (for-node :rlm/researcher-frontier-claimed)
                      starts (filterv :resumed-from-event-id
                                      (for-node :sheet/node-execution-started))
                      completions (for-node :sheet/node-execution-completed)
                      records (rm/get-researcher-iteration-records
                               ctx sheet-id tick-id researcher-id)
                      result (runtime/durable-terminal-result ctx tick-id)
                      ;; The loser's late write: a checkpoint carrying the epoch
                      ;; it tried to claim, after the winner's terminal fact.
                      late-write
                      (h/run-and-apply!
                       ctx
                       (iteration-commit-command
                        sheet-id tick-id researcher-id
                        (assoc resume-state :revision 2 :ownership-epoch 1 :next-iteration 3)
                        (assoc iteration-record
                               :iteration-index 2
                               :code "(final! {:summary \"stale-loser\"})")))]
                  (is (= 2 @claim-attempts) "the race was real: both workers attempted the claim")
                  (is (= 1 (count starts)) (pr-str (mapv :event/id starts)))
                  (is (= [1] (mapv :ownership-epoch frontiers))
                      "exactly one frontier claim for the epoch")
                  (is (= 1 @calls) "the losing worker dispatches no provider call")
                  (is (= 1 (count completions)) "the losing worker completes nothing")
                  (is (= [0 1] (mapv :iteration-index records))
                      "the losing worker checkpoints nothing")
                  (is (= :success (:status result)) (pr-str result))
                  (is (= "one-winner" (get-in result [:outputs :summary])) (pr-str result))
                  (is (= ::anom/conflict (::anom/category late-write))
                      "a late write on the unclaimed epoch is fenced")
                  (is (= [0 1] (mapv :iteration-index
                                     (rm/get-researcher-iteration-records
                                      ctx sheet-id tick-id researcher-id)))
                      "the fenced write left no record")))))
          (finally
            (when @worker-b (tp/stop @worker-b))
            (when @worker-a
              (h/stop-test-processors! (assoc ctx :processors @worker-a)))))))))
