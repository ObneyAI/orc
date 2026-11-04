(ns ai.obney.orc.orc-service.gepa-periodic-recovery-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.event-store-v3.interface :as es])
  (:import [java.time Instant]
           [java.util UUID]
           [java.nio.charset StandardCharsets]))

(defn answer [_] {:answer "fixed"})
(defn- identity-for [& parts]
  (UUID/nameUUIDFromBytes (.getBytes (pr-str (vec parts)) StandardCharsets/UTF_8)))

(deftest scheduled-recovery-grades-the-original-completed-tree
  (let [clock (atom (Instant/parse "2026-01-01T00:00:00Z"))
        initial (h/create-async-test-context {:context {:gepa/clock-fn #(deref clock)}})
        owned (atom initial)]
    (try
      (h/stop-test-processors! initial)
      (let [ctx (dissoc initial :processors)
            sheet-id (sheet/build-workflow! ctx
                       (sheet/workflow "gepa-post-tree-recovery"
                         (sheet/blackboard {:question :string :expected :string :answer :string})
                         (sheet/code "answer"
                           :fn "ai.obney.orc.orc-service.gepa-periodic-recovery-test/answer"
                           :reads [:question] :writes [:answer])))
            input {"question" "q" "expected" "fixed"}
            opt (get-in (tu/process-command! ctx
                          {:command/name :gepa/start-optimization :sheet-id sheet-id
                           :trainset [input] :valset [input] :config {:max-metric-calls 3}})
                        [:command/result :optimization-id])
            instructions {"answer" "Answer the supplied question"}
            candidate (get-in (tu/process-command! ctx
                                {:command/name :gepa/create-candidate :optimization-id opt
                                 :instructions instructions :parent-ids [] :mutation-reason "fixture"})
                              [:command/result :candidate-id])
            _ (tu/process-command! ctx {:command/name :gepa/evaluate-candidate
                                       :optimization-id opt :candidate-id candidate})
            call-id (identity-for :gepa/task opt candidate :candidate 0)
            trace-id (identity-for :gepa/trace call-id)
            boundary-id (identity-for :gepa/boundary opt :candidate candidate)
            task {:call-id call-id :trace-id trace-id :role :candidate :example-index 0
                  :input input :instructions instructions :sheet-id sheet-id
                  :metric-retry-safe? true :max-metric-attempts 3}
            owner {:optimization-id opt :boundary-id boundary-id :call-id call-id
                   :owner-id (random-uuid) :epoch 1}
            without (into #{} (remove #(or (= "sheet" (namespace %))
                                           (#{:gepa/on-recovery-scan-triggered :gepa/on-optimization-recovery-requested} %)))
                          (keys @tp/processor-registry*))
            running (assoc ctx :processors (h/start-test-processors ctx without))]
        (reset! owned running)
        (is (= :opened (:status (ledger/open-boundary! running
                                  {:optimization-id opt :boundary-id boundary-id
                                   :boundary {:kind :candidate :candidate-id candidate} :tasks [task]}))))
        (is (= :owned (:status (ledger/claim! running owner))))
        (is (= :dispatched (:status (ledger/dispatch! running
                                      (assoc owner :attempt-id (random-uuid) :attempt-kind :evaluation)))))
        ;; Actual workflow completes; the old process did not record its result
        ;; in GEPA or invoke a metric. No mock tree or fabricated result is used.
        (is (= :success (:status (sheet/execute running sheet-id input
                                                :tick-id trace-id :timeout-ms 30000))))
        ;; The same discovery path must leave an unexpired owner alone.
        (is (= [] (gepa/recover-in-progress! running)))
        (is (empty? (filter #(= :gepa/optimization-recovery-requested (:event/type %))
                            (h/read-all-events running))))
        (is (= (:owner-id owner) (:owner-id (ledger/get-task running opt call-id))))
        (swap! clock #(.plusSeconds % 91))
        (let [triggers (periodic/start-periodic-triggers!
                         {:append-fn #(es/append (:event-store running) %)
                          :tenant-ids-fn #(set (keys (es/tenants (:event-store running))))})]
          (try
            (is (h/settle-until!
                  #(some (fn [e] (= :gepa/candidate-evaluated (:event/type e)))
                         (h/read-all-events running)) :timeout-ms 10000))
            (let [events (h/read-all-events running)
                  completed (ledger/get-task running opt call-id)]
              (is (some #(= :gepa/recovery-scan-triggered (:event/type %)) events))
              (is (= 1 (count (filter #(= :sheet/tree-tick-started (:event/type %)) events))))
              (is (= 1 (count (filter #(= :gepa/task-metric-started (:event/type %)) events))))
              (is (= :completed (:status completed)))
              (is (= trace-id (:trace-id completed)))
              (is (= {:question "q" :expected "fixed" :answer "fixed"}
                     (get-in completed [:result :output])))
              (is (= 1 (:spent (ledger/get-accounting running opt)))))
            (finally (periodic/stop-periodic-triggers! triggers)))))
      (finally (h/stop-async-context @owned)))))
