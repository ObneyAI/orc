(ns ai.obney.orc.orc-service.cancel-tick-reason-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- start-running-tick! [ctx sheet-id tick-id]
  (cp/process-command
   (assoc ctx :command
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :sheet/emit-tick-started
           :sheet-id sheet-id
           :tick-id tick-id}))
  (is (h/settle-until! #(= :running (:status (rm/get-tick ctx tick-id))))))

(defn- cancellation-event [ctx tick-id]
  (first (filter #(= :sheet/tick-cancelled (:event/type %))
                 (h/read-tick-events ctx tick-id))))

(defn- save-yielded-campaign! [ctx sheet-id tick-id node-id]
  (cp/process-command
   (assoc ctx :command
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :sheet/checkpoint-researcher-iteration
           :sheet-id sheet-id
           :tick-id tick-id
           :node-id node-id
           :resume-state {:version 2
                          :revision 1
                          :ownership-epoch 1
                          :next-iteration 1
                          :sandbox-vars {:memo "kept"}
                          :var-creation-times {:memo 0}
                          :usage {:prompt-tokens 2
                                  :completion-tokens 1
                                  :total-tokens 3}
                          :cumulative-tree-ms 0
                          :iteration-attempts {}
                          :campaign-started-at-ms 100
                          :campaign-deadline-ms 10000}
           :iteration-record {:iteration-index 0
                              :attempt-ordinal 0
                              :status :success
                              :started-at "2026-01-01T00:00:00Z"
                              :completed-at "2026-01-01T00:00:00.010Z"
                              :duration-ms 10
                              :generated-code-recorded? true
                              :emitted-tree-recorded? false
                              :code "(store! :memo \"kept\")"}
           :inputs {}}))
  (is (h/settle-until!
       #(= :yielded (:status (sheet/get-researcher-campaign
                              ctx tick-id node-id))))))

(deftest absent-cancellation-reason-normalizes-at-command-boundary
  (testing "an omitted optional cause becomes the stable infrastructure cause"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)]
        (start-running-tick! ctx sheet-id tick-id)
        (cp/process-command
         (assoc ctx :command
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :sheet/cancel-tick
                 :sheet-id sheet-id
                 :tick-id tick-id}))
        (is (h/settle-until! #(some? (cancellation-event ctx tick-id))))
        (is (= "tick cancelled" (:reason (cancellation-event ctx tick-id))))))))

(deftest supplied-nonblank-cancellation-reason-is-byte-preserved
  (testing "a caller's nonblank cause is not trimmed or rewritten"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            reason "  operator-request: maintenance window  "]
        (start-running-tick! ctx sheet-id tick-id)
        (cp/process-command
         (assoc ctx :command
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :sheet/cancel-tick
                 :sheet-id sheet-id
                 :tick-id tick-id
                 :reason reason}))
        (is (h/settle-until! #(some? (cancellation-event ctx tick-id))))
        (is (= reason (:reason (cancellation-event ctx tick-id))))))))

(deftest yielded-campaign-cancels-terminally-with-retained-evidence
  (testing "public cancellation reaches a yielded campaign and recovery cannot reopen it"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)]
        (start-running-tick! ctx sheet-id tick-id)
        (save-yielded-campaign! ctx sheet-id tick-id node-id)
        (cp/process-command
         (assoc ctx :command
                {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id (random-uuid)
                 :confidence 0.9
                 :top-candidates []
                 :reasoning "RR-19 public cancellation exclusion"
                 :was-fresh-mint? false}))
        (is (= {:cancelled [tick-id]} (sheet/cancel! ctx tick-id)))
        (is (h/settle-until!
             #(= :cancelled (:status (sheet/get-researcher-campaign
                                      ctx tick-id node-id)))))
        (let [campaign (sheet/get-researcher-campaign ctx tick-id node-id)
              records (rm/get-researcher-iteration-records
                       ctx sheet-id tick-id node-id)]
          (is (= :cancelled (:status campaign)) (pr-str campaign))
          (is (some? (:completed-at campaign)) (pr-str campaign))
          (is (= "cancelled by operator" (:terminal-reason campaign))
              (pr-str campaign))
          (is (= [[0 :success]]
                 (mapv (juxt :iteration-index :status) records)))
          (is (= 1 (count (filter #(= :ontology/task-classified
                                      (:event/type %))
                                  (h/read-tick-events ctx tick-id))))
              "the cancelled campaign remains durably classified")
          (is (not-any? #(= :ontology/tree-class-occurrence-recorded
                             (:event/type %))
                        (h/read-tick-events ctx tick-id))
              "public cancellation contributes no verdict occurrence")
          (is (empty? (filter #(= tick-id (:tick-id %))
                              (runtime/resume-in-progress! ctx)))))))))

(deftest cancellation-and-completion-share-one-terminal-winner
  (testing "the first append-boundary terminal is immutable under later campaign events"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            ready (java.util.concurrent.CountDownLatch. 2)
            release-contenders (promise)]
        (start-running-tick! ctx sheet-id tick-id)
        (save-yielded-campaign! ctx sheet-id tick-id node-id)
        (let [cancel-attempt
              (future
                (.countDown ready)
                @release-contenders
                (sheet/cancel! ctx tick-id))
              completion-attempt
              (future
                (.countDown ready)
                @release-contenders
                (cp/process-command
                 (assoc ctx :command
                        {:command/id (random-uuid)
                         :command/timestamp (time/now)
                         :command/name :sheet/emit-tick-completed
                         :sheet-id sheet-id
                         :tick-id tick-id
                         :root-status :success})))]
          (is (.await ready 2 java.util.concurrent.TimeUnit/SECONDS))
          (deliver release-contenders true)
          (is (not= ::contender-timeout
                    (deref cancel-attempt 2000 ::contender-timeout)))
          (is (not= ::contender-timeout
                    (deref completion-attempt 2000 ::contender-timeout))))
        (is (h/settle-until!
             #(= 1
                 (count
                  (filter
                   (fn [event]
                     (or (= :sheet/tick-cancelled (:event/type event))
                         (and (= :sheet/tree-tick-completed (:event/type event))
                              (not= :running (:root-status event)))))
                   (h/read-tick-events ctx tick-id))))))
        (let [terminal-event
              (first
               (filter
                (fn [event]
                  (or (= :sheet/tick-cancelled (:event/type event))
                      (and (= :sheet/tree-tick-completed (:event/type event))
                           (not= :running (:root-status event)))))
                (h/read-tick-events ctx tick-id)))
              campaign-before
              (sheet/get-researcher-campaign ctx tick-id node-id)
              expected-status
              (if (= :sheet/tick-cancelled (:event/type terminal-event))
                :cancelled
                :abandoned)]
          (is (= expected-status (:status campaign-before))
              (pr-str {:terminal terminal-event :campaign campaign-before}))
          (when (= :cancelled expected-status)
            (is (and (string? (:terminal-reason campaign-before))
                     (not (clojure.string/blank?
                           (:terminal-reason campaign-before))))
                (pr-str campaign-before)))
          (cp/process-command
           (assoc ctx :command
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :sheet/claim-researcher-frontier
                   :sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :ownership-epoch 2
                   :claimed-at "2026-01-01T00:00:01Z"}))
          (is (= expected-status
                 (:status (sheet/get-researcher-campaign
                           ctx tick-id node-id)))
              "a terminal campaign rejects an outbound transition"))))))

(deftest completion-first-rejects-later-public-cancellation
  (testing "a completed parent leaves its yielded campaign abandoned and immutable"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)]
        (start-running-tick! ctx sheet-id tick-id)
        (save-yielded-campaign! ctx sheet-id tick-id node-id)
        (cp/process-command
         (assoc ctx :command
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :sheet/emit-tick-completed
                 :sheet-id sheet-id
                 :tick-id tick-id
                 :root-status :success}))
        (is (h/settle-until!
             #(= :completed (:status (rm/get-tick ctx tick-id)))))
        (is (= :cognitect.anomalies/incorrect
               (:cognitect.anomalies/category
                (sheet/cancel! ctx tick-id))))
        (let [events (h/read-tick-events ctx tick-id)
              campaign (sheet/get-researcher-campaign ctx tick-id node-id)]
          (is (= 1 (count (filter #(and (= :sheet/tree-tick-completed
                                           (:event/type %))
                                        (not= :running (:root-status %)))
                                  events))))
          (is (not-any? #(= :sheet/tick-cancelled (:event/type %)) events))
          (is (= :abandoned (:status campaign)) (pr-str campaign))
          (is (nil? (:completed-at campaign)) (pr-str campaign)))))))
