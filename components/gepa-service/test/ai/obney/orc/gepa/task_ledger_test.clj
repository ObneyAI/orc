(ns ai.obney.orc.gepa.task-ledger-test
  (:require [ai.obney.orc.gepa.core.logic :as logic]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [clojure.test :refer [deftest is]]
            [ai.obney.orc.gepa.core.commands :as commands]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.event-store-v3.interface :as es])
  (:import [java.time Instant]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defschemas legacy-fixture
  {:gepa/import-legacy-task-fixture
   [:map [:optimization-id :uuid] [:call-id :uuid] [:trace-id :uuid]]})

(defcommand :gepa import-legacy-task-fixture
  [{{:keys [optimization-id call-id trace-id]} :command}]
  {:command-result/events
   (mapv (fn [score]
           (es/->event {:type :gepa/task-call-completed
                       :tags #{[:optimization optimization-id] [:call call-id]}
                       :body {:optimization-id optimization-id :call-id call-id
                              :trace-id trace-id :role :candidate :example-index 0
                              :output {:answer "historical"} :score score
                              :completed-at "2026-01-01T00:00:00Z"}}))
         [0.2 0.8])})

(defn start! [ctx budget]
  (get-in (tu/process-command! ctx
            {:command/name :gepa/start-optimization :sheet-id (random-uuid)
             :trainset [{"question" "q"}] :valset [{"question" "q"}]
             :config {:max-metric-calls budget}})
          [:command/result :optimization-id]))

(defn task []
  {:call-id (random-uuid) :trace-id (random-uuid) :role :candidate
   :example-index 0 :input {"question" "q"} :instructions {"answer" "answer"}
   :metric-retry-safe? true :max-metric-attempts 3})

(defn boundary [opt tasks]
  {:optimization-id opt :boundary-id (random-uuid)
   :boundary {:kind :candidate :candidate-id (random-uuid)} :tasks tasks})

(defn events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(deftest concurrent-delivery-admits-one-owner-and-one-reservation
  (tu/with-test-context [ctx]
    (let [armed (atom false) arrivals (CountDownLatch. 2)
          ctx (assoc ctx :gepa/clock-fn
                     (fn []
                       (when @armed
                         (.countDown arrivals)
                         (assert (.await arrivals 5 TimeUnit/SECONDS)))
                       (Instant/parse "2026-01-01T00:00:00Z")))
          opt (start! ctx 1) t (task) b (boundary opt [t])
          opened (commands/open-boundary! ctx b)
          gate (promise)
          claim (fn [] (future @gate (commands/claim! ctx
                                    {:optimization-id opt :boundary-id (:boundary-id b)
                                     :call-id (:call-id t) :owner-id (random-uuid)})))
          a (claim) z (claim)]
      (is (= :opened (:status opened)))
      (reset! armed true)
      (deliver gate true)
      (let [results [(deref a 5000 ::timeout) (deref z 5000 ::timeout)]
            admitted (filter #(= :gepa/task-claimed (:event/type %)) (events ctx))]
        (is (= #{:owned :pending} (set (map :status results))) (pr-str results))
        (is (= 1 (count admitted)))
        (is (= (:trace-id t) (:trace-id (first admitted))))))))

(deftest lease-renewal-and-takeover-preserve-the-original-reservation
  (tu/with-test-context [base]
    (let [clock (atom (Instant/parse "2026-01-01T00:00:00Z"))
          ctx (assoc base :gepa/clock-fn #(deref clock))
          opt (start! ctx 1) t (task) b (boundary opt [t])
          _ (commands/open-boundary! ctx b)
          identity {:optimization-id opt :boundary-id (:boundary-id b) :call-id (:call-id t)}
          owner-a (assoc identity :owner-id (random-uuid))
          owner-b (assoc identity :owner-id (random-uuid))
          a (commands/claim! ctx owner-a)]
      (is (= :owned (:status a)))
      (swap! clock #(.plusSeconds % 30))
      (is (= :renewed (:status (commands/renew! ctx (assoc owner-a :epoch 1)))))
      (swap! clock #(.plusSeconds % 61))
      (is (= :pending (:status (commands/claim! ctx owner-b))) "Renewed ownership remains healthy")
      (swap! clock #(.plusSeconds % 30))
      (let [b (commands/claim! ctx owner-b)]
        (is (= :owned (:status b)))
        (is (= 2 (get-in b [:task :epoch])))
        (is (= (:trace-id t) (get-in b [:task :trace-id])))
        (is (= :stale-owner (:status (commands/renew! ctx (assoc owner-a :epoch 1)))))
        (is (= {:spent 0 :reserved 1 :budget 1 :available 0}
               (rm/get-accounting ctx opt)))))))

(deftest dispatch-converts-one-reservation-to-one-durable-charge
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 2) t (task) b (boundary opt [t])
          _ (commands/open-boundary! ctx b)
          owner {:optimization-id opt :boundary-id (:boundary-id b)
                 :call-id (:call-id t) :owner-id (random-uuid) :epoch 1}
          _ (commands/claim! ctx owner)
          attempt (assoc owner :attempt-id (random-uuid) :attempt-kind :evaluation)]
      (is (= {:spent 0 :reserved 1 :budget 2 :available 1} (rm/get-accounting ctx opt)))
      (is (= :dispatched (:status (commands/dispatch! ctx attempt))))
      (is (= :already-dispatched (:status (commands/dispatch! ctx attempt))))
      (is (= {:spent 1 :reserved 0 :budget 2 :available 1} (rm/get-accounting ctx opt)))
      (is (= 1 (:attempt-count (rm/get-task ctx opt (:call-id t)))))
      (is (= 1 (count (filter #(= :gepa/task-attempt-dispatched (:event/type %)) (events ctx))))))))

(deftest completed-work-and-model-evidence-are-published-together-and-reused
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 3) t (task) b (boundary opt [t])
          _ (commands/open-boundary! ctx b)
          owner {:optimization-id opt :boundary-id (:boundary-id b)
                 :call-id (:call-id t) :owner-id (random-uuid) :epoch 1}
          _ (commands/claim! ctx owner)
          attempt-id (random-uuid)
          result {:output {:answer "done"} :score 0.75 :feedback "Exact feedback.\nSecond line."
                  :model "test/code" :usage {:completion-tokens 0}}]
      (is (= :dispatched (:status (commands/dispatch! ctx (assoc owner :attempt-id attempt-id :attempt-kind :evaluation)))))
      (is (= :workflow-recorded (:status (commands/record-workflow! ctx
                         (assoc owner :result {:status :success :outputs (:output result) :trace-id (:trace-id t)})))))
      (is (= :metric-started (:status (commands/begin-metric! ctx (assoc owner :attempt-id attempt-id)))))
      (is (= :stale-owner (:status (commands/finish! ctx (assoc owner :attempt-id attempt-id :owner-id (random-uuid) :result result)))))
      (is (= :completed (:status (commands/finish! ctx (assoc owner :attempt-id attempt-id :result result)))))
      (is (= :reused (:status (commands/finish! ctx (assoc owner :attempt-id attempt-id :result result)))))
      (is (= :reused (:status (commands/claim! ctx (assoc owner :owner-id (random-uuid))))))
      (is (= :completed (:status (rm/get-task ctx opt (:call-id t)))))
      (is (= result (:result (rm/get-task ctx opt (:call-id t)))))
      (is (= {:spent 1 :reserved 0 :budget 3 :available 2} (rm/get-accounting ctx opt)))
      (let [facts (events ctx)
            records (filter #(= :gepa/task-call-completed (:event/type %)) facts)]
        (is (= 1 (count (filter #(= :gepa/task-finished (:event/type %)) facts))))
        (is (= 1 (count records)))
        (is (= (:trace-id t) (:trace-id (first records))))
        (is (= result (select-keys (first records) (keys result))))))))

(deftest unknown-metric-outcome-retains-its-charge-and-only-safe-retries-proceed
  (doseq [safe? [false true]]
    (tu/with-test-context [base]
      (let [clock (atom (Instant/parse "2026-01-01T00:00:00Z"))
            ctx (assoc base :gepa/clock-fn #(deref clock))
            opt (start! ctx 3) t (assoc (task) :metric-retry-safe? safe? :max-metric-attempts 2)
            b (boundary opt [t]) _ (commands/open-boundary! ctx b)
            owner {:optimization-id opt :boundary-id (:boundary-id b) :call-id (:call-id t)
                   :owner-id (random-uuid) :epoch 1}
            _ (commands/claim! ctx owner) attempt-id (random-uuid)
            _ (commands/dispatch! ctx (assoc owner :attempt-id attempt-id :attempt-kind :evaluation))
            _ (commands/record-workflow! ctx (assoc owner :result {:status :success :trace-id (:trace-id t) :outputs {:answer "original"}}))
            _ (commands/begin-metric! ctx (assoc owner :attempt-id attempt-id))]
        (is (= :recovery-required (:status (commands/attention! ctx
                      (assoc owner :reason {:code :metric-outcome-unknown :message "worker interrupted"})))))
        (swap! clock #(.plusSeconds % 91))
        (let [next-owner (assoc owner :owner-id (random-uuid) :epoch 2)
              claimed (commands/claim! ctx next-owner)
              retry (assoc next-owner :attempt-id (random-uuid) :attempt-kind :metric)
              result (commands/dispatch! ctx retry)]
          (is (= :owned (:status claimed)))
          (is (= (:trace-id t) (get-in claimed [:task :trace-id])))
          (is (= (if safe? :dispatched :recovery-required) (:status result)))
          (is (= (if safe? 2 1) (:spent (rm/get-accounting ctx opt))))
          (is (= {:answer "original"} (get-in (rm/get-task ctx opt (:call-id t)) [:workflow-result :outputs])))
          (when safe?
            (is (= :already-dispatched (:status (commands/dispatch! ctx retry))))
            (is (= :attempt-limit-exceeded (:status (commands/dispatch! ctx (assoc retry :attempt-id (random-uuid))))))))))))

(deftest unfinished-legacy-boundary-adopts-recorded-work-without-executing-it
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 3) t (task) b (boundary opt [t]) old-trace (random-uuid)
          recorded (tu/process-command! ctx
                     {:command/name :gepa/record-task-call :optimization-id opt
                      :call-id (:call-id t) :role (:role t) :example-index 0
                      :trace-id old-trace :output {:answer "historical"}
                      :score 0.7 :feedback "historical feedback"})
          source (first (filter #(= :gepa/task-call-completed (:event/type %)) (events ctx)))
          opened (commands/open-boundary! ctx b)
          adopted (rm/get-task ctx opt (:call-id t))]
      (is (nil? (:cognitect.anomalies/category recorded)))
      (is (= :opened (:status opened)))
      (is (= :completed (:status adopted)))
      (is (= old-trace (:trace-id adopted)))
      (is (= [(:event/id source)] (:source-event-ids adopted)))
      (is (= {:answer "historical"} (get-in adopted [:result :output])))
      (is (= :reused (:status (commands/claim! ctx {:optimization-id opt :boundary-id (:boundary-id b)
                                               :call-id (:call-id t) :owner-id (random-uuid)}))))
      (is (= {:spent 1 :reserved 0 :budget 3 :available 2} (rm/get-accounting ctx opt)))
      (is (= :opened (:status (commands/open-boundary! ctx b))))
      (is (= 1 (count (filter #(= :gepa/task-adopted (:event/type %)) (events ctx))))))))

(deftest completion-binds-the-current-attempt-and-immutable-workflow-evidence
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 5) t (task) b (boundary opt [t])
          _ (commands/open-boundary! ctx b)
          owner {:optimization-id opt :boundary-id (:boundary-id b) :call-id (:call-id t)
                 :owner-id (random-uuid) :epoch 1}
          _ (commands/claim! ctx owner)
          first-id (random-uuid) next-id (random-uuid)
          result {:output {:answer "original"} :score 0.8 :feedback "checked"}
          _ (commands/dispatch! ctx (assoc owner :attempt-id first-id :attempt-kind :evaluation))
          _ (commands/record-workflow! ctx (assoc owner :result {:status :success :trace-id (:trace-id t) :outputs (:output result)}))
          _ (commands/begin-metric! ctx (assoc owner :attempt-id first-id))
          _ (commands/attention! ctx (assoc owner :reason {:code :metric-outcome-unknown :message "interrupted"}))
          _ (commands/dispatch! ctx (assoc owner :attempt-id next-id :attempt-kind :metric))
          _ (commands/begin-metric! ctx (assoc owner :attempt-id next-id))]
      (is (= :stale-attempt (:status (commands/finish! ctx (assoc owner :attempt-id first-id :result result)))))
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category
              (commands/finish! ctx (assoc owner :attempt-id next-id :result (assoc result :output {:answer "invented"}))))))
      (is (= :completed (:status (commands/finish! ctx (assoc owner :attempt-id next-id :result result)))))
      (is (= :reused (:status (commands/finish! ctx (assoc owner :attempt-id next-id :result result)))))
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category
              (commands/finish! ctx (assoc owner :attempt-id next-id :result (assoc result :score 0.1))))))
      (is (= :reused (:status (commands/dispatch! ctx (assoc owner :attempt-id (random-uuid) :attempt-kind :metric)))))
      (is (= :reused (:status (commands/begin-metric! ctx (assoc owner :attempt-id next-id)))))
      (is (= 2 (:spent (rm/get-accounting ctx opt))))
      (is (= 1 (count (into [] (es/read (:event-store ctx)
                             {:tenant-id (:tenant-id ctx) :types #{:gepa/task-call-completed}
                              :tags #{[:call (:call-id t)]}}))))))))

(deftest completed-legacy-boundaries-keep-their-charge-without-adopting-covered-work
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 5) t (task)
          candidate-id (get-in (tu/process-command! ctx
                                 {:command/name :gepa/create-candidate :optimization-id opt
                                  :instructions (:instructions t) :parent-ids [] :mutation-reason "fixture"})
                               [:command/result :candidate-id])
          _ (tu/process-command! ctx {:command/name :gepa/record-task-call :optimization-id opt
                                      :call-id (:call-id t) :trace-id (:trace-id t) :role :candidate
                                      :example-index 0 :output {:answer "historical"} :score 0.7})
          _ (tu/process-command! ctx {:command/name :gepa/record-evaluation-result :optimization-id opt
                                      :candidate-id candidate-id :scores [0.7] :trace-ids [(:trace-id t)]
                                      :metric-calls 1})
          b (assoc (boundary opt [t]) :boundary {:kind :candidate :candidate-id candidate-id})
          opened (commands/open-boundary! ctx b)
          fact (first (filter #(= :gepa/candidate-evaluated (:event/type %)) (events ctx)))
          projected (rm/get-state ctx opt)]
      (is (= :boundary-completed (:status opened)))
      (is (empty? (:tasks projected)))
      (is (= {:spent 1 :reserved 0 :budget 5 :available 4} (rm/get-accounting ctx opt)))
      (is (not-any? #(= :gepa/task-adopted (:event/type %)) (events ctx)))
      (is (= (:spent projected)
             (:spent (logic/reduce-event projected (assoc fact :accounting-mode :tasks))))
          "Task-accounted aggregates retain descriptive metric-calls without charging twice"))))

(deftest task-admission-respects-boundary-identity-and-terminal-optimization
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 4) t (task) b (boundary opt [t])
          _ (commands/open-boundary! ctx b)
          conflicting (assoc (boundary opt [t]) :boundary (:boundary b))
          owner {:optimization-id opt :boundary-id (:boundary-id b) :call-id (:call-id t)
                 :owner-id (random-uuid) :epoch 1}]
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category (commands/open-boundary! ctx conflicting))))
      (is (= :owned (:status (commands/claim! ctx owner))))
      (tu/process-command! ctx {:command/name :gepa/fail-optimization :optimization-id opt
                                :error-message "stopped deliberately"})
      (is (= :optimization-terminal (:status (commands/dispatch! ctx (assoc owner :attempt-id (random-uuid) :attempt-kind :evaluation)))))
      (is (= :optimization-terminal (:status (commands/open-boundary! ctx (boundary opt [(task)])))))))
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 1) b (boundary opt [(task)])]
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category (commands/open-boundary! (dissoc ctx :tenant-id) b)))))))

(deftest distinct-tasks-compete-for-the-same-last-budget-slot
  (tu/with-test-context [base]
    (let [armed (atom false) arrivals (CountDownLatch. 2)
          ctx (assoc base :gepa/clock-fn
                     (fn [] (when @armed (.countDown arrivals)
                              (assert (.await arrivals 5 TimeUnit/SECONDS)))
                       (Instant/parse "2026-01-01T00:00:00Z")))
          opt (start! ctx 1) tasks [(task) (task)] b (boundary opt tasks)
          _ (commands/open-boundary! ctx b) gate (promise)
          claims (mapv (fn [t] (future @gate (commands/claim! ctx
                        {:optimization-id opt :boundary-id (:boundary-id b)
                         :call-id (:call-id t) :owner-id (random-uuid)}))) tasks)]
      (reset! armed true) (deliver gate true)
      (is (= #{:owned :budget-exhausted}
             (set (map #(-> (deref % 5000 ::timeout) :status) claims))))
      (is (= {:spent 0 :reserved 1 :budget 1 :available 0} (rm/get-accounting ctx opt))))))

(deftest metric-payload-cannot-overwrite-durable-task-identity
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 2) t (task) b (boundary opt [t])
          _ (commands/open-boundary! ctx b)
          owner {:optimization-id opt :boundary-id (:boundary-id b) :call-id (:call-id t)
                 :owner-id (random-uuid) :epoch 1 :attempt-id (random-uuid)}
          _ (commands/claim! ctx owner)
          _ (commands/dispatch! ctx (assoc owner :attempt-kind :evaluation))
          _ (commands/record-workflow! ctx (assoc owner :result {:trace-id (:trace-id t) :status :success :outputs {}}))
          _ (commands/begin-metric! ctx owner)
          result {:output {} :score 0.5 :trace-id (random-uuid)}]
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category (commands/finish! ctx (assoc owner :result result)))))
      (is (= :metric-running (:status (rm/get-task ctx opt (:call-id t))))))))

(deftest conflicting-legacy-evidence-requires-durable-attention-instead-of-guessing
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 5) t (task) b (boundary opt [t])
          imported (tu/process-command! ctx {:command/name :gepa/import-legacy-task-fixture
                                             :optimization-id opt :call-id (:call-id t) :trace-id (:trace-id t)})
          source-ids (mapv :event/id (filter #(= :gepa/task-call-completed (:event/type %)) (events ctx)))
          opened (commands/open-boundary! ctx b)]
      (is (nil? (:cognitect.anomalies/category imported)))
      (is (= :recovery-required (:status opened)))
      (is (= :ambiguous-legacy-task (:reason opened)))
      (is (= source-ids (:source-event-ids opened)))
      (is (empty? (:tasks (rm/get-state ctx opt))))
      (is (= {:spent 0 :reserved 0 :budget 5 :available 5} (rm/get-accounting ctx opt)))
      (is (= source-ids (get-in (rm/get-state ctx opt) [:adoption-issues (:boundary-id b) :source-event-ids])))
      (is (= opened (commands/open-boundary! ctx b)))
      (is (= 1 (count (filter #(= :gepa/task-adoption-blocked (:event/type %)) (events ctx))))))))

(deftest ledger-observation-requires-an-explicit-tenant
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 1) unscoped (dissoc ctx :tenant-id)]
      (doseq [result [(rm/get-state unscoped opt)
                      (rm/get-task unscoped opt (random-uuid))
                      (rm/get-accounting unscoped opt)]]
        (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result)))))))

(deftest ownership-cannot-be-used-under-a-different-boundary
  (tu/with-test-context [ctx]
    (let [opt (start! ctx 1) t (task) b (boundary opt [t])
          _ (commands/open-boundary! ctx b)
          owner {:optimization-id opt :boundary-id (:boundary-id b) :call-id (:call-id t)
                 :owner-id (random-uuid) :epoch 1}
          _ (commands/claim! ctx owner)
          wrong-boundary (random-uuid)]
      (is (= :stale-owner (:status (commands/renew! ctx (assoc owner :boundary-id wrong-boundary)))))
      (is (not-any? #(and (= :gepa/task-lease-renewed (:event/type %))
                         (= wrong-boundary (:boundary-id %))) (events ctx)))
      (is (= :renewed (:status (commands/renew! ctx owner)))))))
