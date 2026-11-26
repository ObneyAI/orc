(ns ai.obney.orc.orc-service.gepa-metric-recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.todo-processors :as todo]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.grain.todo-processor-v2.interface :as tp])
  (:import [java.time Instant]
           [java.util.concurrent TimeoutException]))

(defn answer [_] {:answer "fixed"})

(deftest unknown-metric-outcomes-preserve-original-output-and-obey-admission
  (doseq [{:keys [safe? budget attempts always-unknown? expected-calls complete?] :as scenario}
          [{:safe? true :budget 3 :attempts 3 :expected-calls 2 :complete? true}
           {:safe? false :budget 3 :attempts 3 :expected-calls 1 :complete? false}
           {:safe? true :budget 1 :attempts 3 :expected-calls 1 :complete? false}
           {:safe? true :budget 4 :attempts 2 :always-unknown? true :expected-calls 2 :complete? false}]]
    (testing (pr-str scenario)
      (let [clock (atom (Instant/parse "2026-01-01T00:00:00Z"))
            invocations (atom [])
            initial (h/create-async-test-context
                      {:context {:gepa/clock-fn #(deref clock)
                                 :gepa/metric-runner
                                 (fn [thunk _timeout]
                                   (let [actual (thunk)]
                                     (if (or always-unknown? (= 1 (count @invocations)))
                                       (throw (TimeoutException. "Response lost after metric invocation"))
                                       actual)))}})
            owned (atom initial)]
        (try
          (h/stop-test-processors! initial)
          (let [ctx (dissoc initial :processors)
                without (into #{} (remove #(= "sheet" (namespace %)))
                              (keys @tp/processor-registry*))
                running (assoc ctx :processors (h/start-test-processors ctx without))
                _ (reset! owned running)
                sheet-id (sheet/build-workflow! running
                           (sheet/workflow "gepa-metric-recovery"
                             (sheet/blackboard {:question :string :answer :string})
                             (sheet/code "answer"
                               :fn "ai.obney.orc.orc-service.gepa-metric-recovery-test/answer"
                               :reads [:question] :writes [:answer])))
                opt (:optimization-id
                      (gepa/optimize! running
                        {:sheet-id sheet-id :trainset [{"question" "q"}] :valset [{"question" "q"}]
                         :metric-fn (fn [input output]
                                      (swap! invocations conj {:input input :output output})
                                      {:score 0.8 :feedback "Original evidence is intact."})
                         :metric-retry-safe? safe?
                         :config {:max-metric-calls budget :metric-max-attempts attempts}}))
                candidate (get-in (tu/process-command! running
                                    {:command/name :gepa/create-candidate :optimization-id opt
                                     :instructions {"answer" "Respond"} :parent-ids [] :mutation-reason "fixture"})
                                  [:command/result :candidate-id])
                started (tu/process-command! running
                          {:command/name :gepa/evaluate-candidate :optimization-id opt :candidate-id candidate})
                event (first (:command-result/events started))]
            (is (uuid? opt))
            (todo/on-candidate-evaluation-started (assoc running :event event))
            (let [events (h/read-all-events running)
                  task (first (vals (:tasks (ledger/get-state running opt))))]
              (is (= expected-calls (count @invocations)))
              (is (= expected-calls (:spent (ledger/get-accounting running opt))))
              (is (<= (:spent (ledger/get-accounting running opt)) budget))
              (is (= expected-calls (count (filter #(= :gepa/task-attempt-dispatched (:event/type %)) events))))
              (is (= 1 (count (filter #(= :sheet/tree-tick-started (:event/type %)) events))))
              (is (every? #(= {:input {"question" "q"} :output {:question "q" :answer "fixed"}} %)
                          @invocations))
              (is (= (if complete? :completed :recovery-required) (:status task)))
              (is (= (if complete? 1 0) (count (filter #(= :gepa/candidate-evaluated (:event/type %)) events))))
              (when complete?
                (is (= "Original evidence is intact." (get-in task [:result :feedback]))))
              ;; An explicit resumed delivery must not rerun completed or unsafe
              ;; work, nor bypass exhausted metric attempts/capacity.
              (swap! clock #(.plusSeconds % 91))
              (gepa/resume! running opt)
              (is (= expected-calls (count @invocations)))
              (is (= 1 (count (filter #(= :sheet/tree-tick-started (:event/type %))
                                      (h/read-all-events running)))))))
          (finally (h/stop-async-context @owned)))))))
