(ns ai.obney.orc.orc-service.gepa-cold-recovery-test
  "Two separate JVMs prove recovery using SQLite, real trees and processors."
  (:require [clojure.test :as test :refer [is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.gepa.core.metric-runtime :as metric]
            [ai.obney.orc.grain-test-utils.interface :as tu])
  (:import [java.time Instant]
           [java.util UUID]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn answer [{:keys [inputs]}]
  {:answer (str "answer:" (:question inputs))})

(defn- stable-id [& parts]
  (UUID/nameUUIDFromBytes (.getBytes (pr-str (vec parts)) StandardCharsets/UTF_8)))

(defn- context
  ([database tenant-id recover?] (context database tenant-id recover? nil))
  ([database tenant-id recover? clock-fn]
  (let [initial (h/create-async-test-context
                 {:event-store-conn {:type :sqlite :database-file database :maximum-pool-size 2}
                  :context {:tenant-id tenant-id
                            :orc/clock-fn clock-fn
                            :gepa/clock-fn (or clock-fn #(Instant/parse (if recover?
                                                          "2026-01-01T00:02:00Z"
                                                          "2026-01-01T00:00:00Z")))}})
        _ (h/stop-test-processors! initial)
        base (dissoc initial :processors)
        without (into #{} (remove #(or (= "sheet" (namespace %))
                                      (and recover? (= :gepa/on-optimization-recovery-requested %))))
                      (keys @tp/processor-registry*))]
    ;; Run ordinary sheet effects and, in the second JVM, normal recovery.
    ;; Frontier/proposer processors are outside this crash-boundary proof.
    (assoc base :processors (h/start-test-processors base without)))))

(defn- facts [ctx] (h/read-all-events ctx))
(defn- of-type [events type] (filter #(= type (:event/type %)) events))
(defn- opt-facts [ctx opt] (filter #(= opt (:optimization-id %)) (facts ctx)))

(defn- prepare-case!
  ([ctx sheet-id stage] (prepare-case! ctx sheet-id stage {}))
  ([ctx sheet-id stage metric-options]
  (let [input {"question" (name stage) "expected" (str "answer:" (name stage))}
        opt (random-uuid)
        descriptor (metric/prepare! ctx opt metric-options)
        started (tu/process-command! ctx
                  {:command/name :gepa/start-optimization :optimization-id opt
                   :sheet-id sheet-id :trainset [input] :valset [input]
                   :metric-descriptor descriptor :config {:max-metric-calls 3}})
        instructions {"answer" "Answer the supplied question"}
        candidate (get-in (tu/process-command! ctx
                            {:command/name :gepa/create-candidate :optimization-id opt
                             :instructions instructions :parent-ids [] :mutation-reason "cold fixture"})
                          [:command/result :candidate-id])
        _ (tu/process-command! ctx {:command/name :gepa/evaluate-candidate
                                    :optimization-id opt :candidate-id candidate})
        call-id (stable-id :gepa/task opt candidate :candidate 0)
        trace-id (stable-id :gepa/trace call-id)
        boundary-id (stable-id :gepa/boundary opt :candidate candidate)
        task {:call-id call-id :trace-id trace-id :role :candidate :example-index 0
              :input input :instructions instructions :sheet-id sheet-id
              :metric-retry-safe? true :max-metric-attempts 3}
        owner {:optimization-id opt :boundary-id boundary-id :call-id call-id
               :owner-id (random-uuid) :epoch 1 :attempt-id (random-uuid)}]
    (is (nil? (:cognitect.anomalies/category started)))
    (is (= :opened (:status (ledger/open-boundary! ctx
                              {:optimization-id opt :boundary-id boundary-id
                               :boundary {:kind :candidate :candidate-id candidate} :tasks [task]}))))
    (is (= :owned (:status (ledger/claim! ctx owner))))
    (when-not (= stage :before-dispatch)
      (is (= :dispatched (:status (ledger/dispatch! ctx (assoc owner :attempt-kind :evaluation)))))
      (is (= :success (:status (sheet/execute ctx sheet-id input :tick-id trace-id :timeout-ms 30000)))))
    (let [durable (sheet/get-execution-result ctx trace-id)
          scored (when (= stage :before-aggregate)
                   (is (= :workflow-recorded (:status (ledger/record-workflow! ctx (assoc owner :result durable)))))
                   (is (= :metric-started (:status (ledger/begin-metric! ctx owner))))
                   (let [actual (metric/invoke-once! ctx descriptor input (:outputs durable))]
                     (is (= :completed (:status actual)))
                     (is (= :completed (:status (ledger/finish! ctx
                                                   (assoc owner :result (assoc (:result actual) :output (:outputs durable)))))))
                     (:result actual)))
          current (ledger/get-task ctx opt call-id)]
      (is (= (case stage :before-dispatch :claimed :after-tree :running :before-aggregate :completed)
             (:status current)))
      (is (empty? (of-type (opt-facts ctx opt) :gepa/candidate-evaluated)))
      {:stage stage :optimization-id opt :candidate-id candidate :boundary-id boundary-id
       :call-id call-id :trace-id trace-id :input input :instructions instructions
       :prepared-score (:score scored)
       :expected-output {:question (name stage) :expected (str "answer:" (name stage))
                         :answer (str "answer:" (name stage))}}))))

(defn prepare! [manifest-path]
  (when (.exists (io/file manifest-path))
    (throw (ex-info "Refusing to replace an existing recovery manifest" {:path manifest-path})))
  (let [dir (.toFile (Files/createTempDirectory "orc-gepa-cold-"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        database (str (io/file dir "events.db")) tenant-id (random-uuid)
        ctx (context database tenant-id false)]
    (try
      (let [sheet-id (sheet/build-workflow! ctx
                       (sheet/workflow "gepa-cold-recovery"
                         (sheet/blackboard {:question :string :expected :string :answer :string})
                         (sheet/code "answer"
                           :fn "ai.obney.orc.orc-service.gepa-cold-recovery-test/answer"
                           :reads [:question] :writes [:answer])))
            cases (mapv #(prepare-case! ctx sheet-id %)
                        [:before-dispatch :after-tree :before-aggregate])]
        (is (= 2 (count (of-type (facts ctx) :sheet/tree-tick-started))))
        (is (= 1 (count (of-type (facts ctx) :gepa/task-metric-started))))
        (spit manifest-path (pr-str {:database database :tenant-id tenant-id
                                    :prepare-pid (.pid (java.lang.ProcessHandle/current))
                                    :sheet-id sheet-id :cases cases}))
        (prn {:manifest manifest-path :database database :prepared-stages (mapv :stage cases)}))
      (finally (h/stop-async-context ctx)))))

(defn- verify-case! [ctx {:keys [optimization-id candidate-id call-id trace-id input instructions
                                expected-output stage prepared-score]}]
  (let [task (ledger/get-task ctx optimization-id call-id)
        events (opt-facts ctx optimization-id)
        aggregates (of-type events :gepa/candidate-evaluated)
        starts (filter #(= trace-id (:tick-id %)) (of-type (facts ctx) :sheet/tree-tick-started))
        metric-starts (filter #(= call-id (:call-id %)) (of-type events :gepa/task-metric-started))
        views [(gepa/get-gepa-state ctx optimization-id)
               (gepa/get-population-state ctx optimization-id)
               (gepa/get-optimization-summary ctx optimization-id)
               (:query/result (gepa/get-population ctx optimization-id))]
        progress (:query/result (gepa/get-progress ctx optimization-id))]
    (is (= :completed (:status task)))
    (is (= trace-id (:trace-id task)))
    (is (= input (:input task)))
    (is (= instructions (:instructions task)))
    (is (= expected-output (get-in task [:result :output])))
    (is (= expected-output (:outputs (sheet/get-execution-result ctx trace-id))))
    (is (= 1 (count starts)))
    (is (= 1 (count metric-starts)))
    (is (= 1 (count aggregates)))
    (is (= candidate-id (:candidate-id (first aggregates))))
    (is (= [trace-id] (:trace-ids (first aggregates))))
    (is (= [(get-in task [:result :score])] (:scores (first aggregates))))
    (when (= stage :before-aggregate)
      (is (= prepared-score (get-in task [:result :score]))))
    (doseq [view views]
      (is (= {:total-metric-calls 1 :reserved-metric-calls 0 :available-metric-calls 2}
             (select-keys view [:total-metric-calls :reserved-metric-calls :available-metric-calls]))))
    (is (= {:used 1 :reserved 0 :available 2 :budget 3} (:metric-calls progress)))
    (is (= {:spent 1 :reserved 0 :available 2 :budget 3} (ledger/get-accounting ctx optimization-id)))))

(defn recover! [manifest-path]
  (let [{:keys [database tenant-id cases prepare-pid]} (edn/read-string (slurp manifest-path))
        ctx (context database tenant-id true)]
    (try
      (is (not= prepare-pid (.pid (java.lang.ProcessHandle/current))) "Recovery must run in a separate JVM")
      (is (= 3 (count (gepa/recover-in-progress! ctx))))
      (is (h/settle-until!
            #(every? (fn [{:keys [optimization-id]}]
                       (= 1 (count (of-type (opt-facts ctx optimization-id) :gepa/candidate-evaluated))))
                     cases)
            :timeout-ms 45000))
      (doseq [fixture cases] (verify-case! ctx fixture))
      (let [counts (frequencies (map :event/type (facts ctx)))]
        (is (= 3 (get counts :sheet/tree-tick-started)))
        (is (= 3 (get counts :sheet/node-execution-completed)))
        (is (= 3 (get counts :gepa/task-metric-started)))
        (is (= 3 (get counts :gepa/task-finished)))
        (is (= 3 (get counts :gepa/candidate-evaluated)))
        (is (empty? (gepa/recover-in-progress! ctx)))
        (is (empty? (gepa/recover-in-progress! ctx)))
        (is (= counts (frequencies (map :event/type (facts ctx))))
            "Repeated recovery neither dispatches work nor republishes aggregates"))
      (finally (h/stop-async-context ctx)))))

(def custom-feedback "Original custom assessment.\nPreserve this feedback verbatim.")

(defn prepare-custom! [manifest-path]
  (when (.exists (io/file manifest-path))
    (throw (ex-info "Refusing to replace an existing recovery manifest" {:path manifest-path})))
  (let [dir (.toFile (Files/createTempDirectory "orc-gepa-custom-cold-"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        database (str (io/file dir "events.db")) tenant-id (random-uuid)
        ctx (context database tenant-id false)]
    (try
      (let [sheet-id (sheet/build-workflow! ctx
                       (sheet/workflow "gepa-custom-cold-recovery"
                         (sheet/blackboard {:question :string :expected :string :answer :string})
                         (sheet/code "answer"
                           :fn "ai.obney.orc.orc-service.gepa-cold-recovery-test/answer"
                           :reads [:question] :writes [:answer])))
            fixture (prepare-case! ctx sheet-id :after-tree
                      {:metric-id "test/cold-custom" :metric-revision "2"
                       :metric-retry-safe? true
                       :metric-fn (fn [_ _] (throw (ex-info "Preparation must not grade" {})))})
            descriptor (:metric-descriptor
                         (gepa/get-optimization-summary ctx (:optimization-id fixture)))]
        (is (= :custom (:kind descriptor)))
        (is (= 1 (count (of-type (facts ctx) :sheet/tree-tick-started))))
        (is (empty? (of-type (facts ctx) :gepa/task-metric-started)))
        (spit manifest-path (pr-str {:database database :tenant-id tenant-id
                                    :prepare-pid (.pid (java.lang.ProcessHandle/current))
                                    :fixture fixture :descriptor descriptor})))
      (finally (h/stop-async-context ctx)))))

(defn recover-custom! [manifest-path]
  (let [{:keys [database tenant-id prepare-pid fixture descriptor]}
        (edn/read-string (slurp manifest-path))
        {:keys [optimization-id call-id trace-id input expected-output]} fixture
        clock (atom (Instant/parse "2026-01-01T00:02:00Z"))
        ctx (context database tenant-id true #(deref clock))
        calls (atom [])]
    (try
      (is (not= prepare-pid (.pid (java.lang.ProcessHandle/current))))
      (is (= descriptor (:metric-descriptor (gepa/get-optimization-summary ctx optimization-id))))
      (is (= :metric-registration-missing (:reason (metric/resolve-metric ctx descriptor))))
      (is (= 1 (count (gepa/recover-in-progress! ctx))))
      (is (h/settle-until!
            #(= :recovery-required (:status (ledger/get-task ctx optimization-id call-id)))
            :timeout-ms 10000))
      (let [task (ledger/get-task ctx optimization-id call-id)
            events (facts ctx)]
        (is (= :metric-registration-missing (get-in task [:recovery-reason :code])))
        (is (= 1 (count (of-type events :gepa/task-recovery-required))))
        (is (= 1 (count (of-type events :sheet/tree-tick-started))))
        (is (= 1 (count (of-type events :sheet/node-execution-completed))))
        (is (empty? (of-type events :gepa/task-metric-started)))
        (is (empty? (of-type events :gepa/candidate-evaluated)))
        (is (= 1 (:spent (ledger/get-accounting ctx optimization-id))))
        (is (= expected-output (:outputs (sheet/get-execution-result ctx trace-id)))))
      (gepa/register-metric! ctx descriptor
        (fn [expected actual]
          (swap! calls conj [expected actual])
          {:score 0.75 :feedback custom-feedback}))
      ;; Restoring a capability does not steal a healthy lease. Advance the
      ;; injected clock beyond the attention owner's lease, then public recovery.
      (swap! clock #(.plusSeconds % 91))
      (is (= 1 (count (gepa/recover-in-progress! ctx))))
      (is (h/settle-until!
            #(= 1 (count (of-type (opt-facts ctx optimization-id) :gepa/candidate-evaluated)))
            :timeout-ms 10000))
      (verify-case! ctx fixture)
      (is (= [[input expected-output]] @calls))
      (is (= 0.75 (get-in (ledger/get-task ctx optimization-id call-id) [:result :score])))
      (let [aggregate (first (of-type (opt-facts ctx optimization-id) :gepa/candidate-evaluated))]
        (is (= {0 custom-feedback} (:feedbacks aggregate)))
        (is (= {0 expected-output} (:outputs aggregate))))
      (let [before (facts ctx)]
        (is (empty? (gepa/recover-in-progress! ctx)))
        (is (= before (facts ctx)))
        (is (= 1 (count @calls))))
      (finally (h/stop-async-context ctx)))))

(def retained-invocations (atom 0))
(def finish-invocations (atom 0))
(def running-entered (promise))
(def running-gate (promise))

(defn retain-input [{:keys [inputs]}]
  (swap! retained-invocations inc)
  {:retained (str "retained:" (:question inputs))})

(defn finish-retained [{:keys [inputs]}]
  (swap! finish-invocations inc)
  (deliver running-entered inputs)
  @running-gate
  {:answer (str (:retained inputs) ":finished")})

(defn prepare-running! [manifest-path]
  (when (.exists (io/file manifest-path))
    (throw (ex-info "Refusing to replace an existing recovery manifest" {:path manifest-path})))
  (let [dir (.toFile (Files/createTempDirectory "orc-gepa-running-cold-"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        database (str (io/file dir "events.db")) tenant-id (random-uuid)
        ctx (context database tenant-id false #(Instant/parse "2026-01-01T00:00:00Z"))
        sheet-id (sheet/build-workflow! ctx
                   (sheet/workflow "gepa-running-cold-recovery"
                     (sheet/blackboard {:question :string :expected :string :retained :string :answer :string})
                     (sheet/sequence "work"
                       (sheet/code "retain" :fn "ai.obney.orc.orc-service.gepa-cold-recovery-test/retain-input"
                         :reads [:question] :writes [:retained])
                       (sheet/code "finish" :fn "ai.obney.orc.orc-service.gepa-cold-recovery-test/finish-retained"
                         :reads [:retained] :writes [:answer]))))
        input {"question" "running" "expected" "retained:running:finished"}
        opt (random-uuid) descriptor (metric/prepare! ctx opt {})
        _ (tu/process-command! ctx {:command/name :gepa/start-optimization :optimization-id opt
                                   :sheet-id sheet-id :trainset [input] :valset [input]
                                   :metric-descriptor descriptor :config {:max-metric-calls 3}})
        instructions {"retain" "Retain input" "finish" "Finish retained input"}
        candidate (get-in (tu/process-command! ctx {:command/name :gepa/create-candidate
                                                    :optimization-id opt :instructions instructions
                                                    :parent-ids [] :mutation-reason "running crash fixture"})
                          [:command/result :candidate-id])
        _ (tu/process-command! ctx {:command/name :gepa/evaluate-candidate :optimization-id opt
                                   :candidate-id candidate})
        call-id (stable-id :gepa/task opt candidate :candidate 0)
        trace-id (stable-id :gepa/trace call-id)
        boundary-id (stable-id :gepa/boundary opt :candidate candidate)
        task {:call-id call-id :trace-id trace-id :role :candidate :example-index 0
              :input input :instructions instructions :sheet-id sheet-id
              :metric-retry-safe? true :max-metric-attempts 3}
        owner {:optimization-id opt :boundary-id boundary-id :call-id call-id
               :owner-id (random-uuid) :epoch 1 :attempt-id (random-uuid)}]
    (is (= :opened (:status (ledger/open-boundary! ctx {:optimization-id opt :boundary-id boundary-id
                                                       :boundary {:kind :candidate :candidate-id candidate}
                                                       :tasks [task]}))))
    (is (= :owned (:status (ledger/claim! ctx owner))))
    (is (= :dispatched (:status (ledger/dispatch! ctx (assoc owner :attempt-kind :evaluation)))))
    (is (nil? (:cognitect.anomalies/category
                (tu/process-command! (assoc ctx :gepa/patched-instructions instructions)
                  {:command/name :sheet/tick-tree :sheet-id sheet-id :tick-id trace-id :inputs input}))))
    (is (= {:retained "retained:running"} (deref running-entered 10000 ::timeout))
        "The second real code function entered, rather than merely being queued")
    (let [nodes (into {} (map (juxt :name :id)) (sheet/get-nodes-for-sheet ctx sheet-id))
          events (facts ctx)
          first-completions (filter #(= (:node-id %) (get nodes "retain"))
                                   (of-type events :sheet/node-execution-completed))
          second-starts (filter #(= (:node-id %) (get nodes "finish"))
                               (of-type events :sheet/node-execution-started))]
      (is (= 1 @retained-invocations))
      (is (= 1 @finish-invocations))
      (is (= 1 (count first-completions)))
      (is (= 1 (count second-starts)))
      (is (empty? (filter #(= (:node-id %) (get nodes "finish"))
                         (of-type events :sheet/node-execution-completed))))
      (is (nil? (sheet/get-execution-result ctx trace-id)))
      (is (empty? (of-type events :gepa/task-metric-started)))
      (spit manifest-path
        (pr-str {:database database :tenant-id tenant-id
                 :prepare-pid (.pid (java.lang.ProcessHandle/current))
                 :nodes nodes
                 ;; Event timestamps print as tagged time literals. Keep the full
                 ;; event verbatim as text so the outer EDN manifest needs only
                 ;; built-in readers, while preserving the exact evidence check.
                 :first-completion-edn (pr-str (first first-completions))
                 :second-start-id (:event/id (first second-starts))
                 :fixture {:optimization-id opt :candidate-id candidate :call-id call-id
                           :trace-id trace-id :input input :instructions instructions :stage :running
                           :expected-output {:question "running" :expected "retained:running:finished"
                                             :retained "retained:running" :answer "retained:running:finished"}}})))
    ;; Intentionally leave the pure leaf blocked. The CLI halts this dedicated
    ;; process after flushing results, without graceful fixture teardown that
    ;; might turn interruption into a durable failure. SQLite appends committed.
    nil))

(defn recover-running! [manifest-path]
  (let [{:keys [database tenant-id prepare-pid fixture nodes first-completion-edn second-start-id]}
        (edn/read-string (slurp manifest-path))
        _ (deliver running-gate :go)
        ctx (context database tenant-id true #(Instant/parse "2026-01-01T00:02:00Z"))]
    (try
      (is (not= prepare-pid (.pid (java.lang.ProcessHandle/current))))
      (is (= 0 @retained-invocations))
      (is (= 1 (count (gepa/recover-in-progress! ctx))))
      (is (h/settle-until!
            #(= 1 (count (of-type (opt-facts ctx (:optimization-id fixture)) :gepa/candidate-evaluated)))
            :timeout-ms 30000))
      (verify-case! ctx fixture)
      (let [events (facts ctx)
            retained-completions (filter #(= (:node-id %) (get nodes "retain"))
                                        (of-type events :sheet/node-execution-completed))
            finish-starts (filter #(= (:node-id %) (get nodes "finish"))
                                 (of-type events :sheet/node-execution-started))]
        (is (= 0 @retained-invocations) "Completed first leaf was not invoked in the recovery JVM")
        (is (= 1 @finish-invocations) "Only the interrupted pure leaf ran again")
        (is (= [first-completion-edn] (mapv pr-str retained-completions)))
        (is (= 2 (count finish-starts)))
        (is (= second-start-id (:resumed-from-event-id (last finish-starts))))
        (is (= 1.0 (get-in (ledger/get-task ctx (:optimization-id fixture) (:call-id fixture))
                           [:result :score])))
        (is (empty? (gepa/recover-in-progress! ctx)))
        (is (= events (facts ctx))))
      (finally (h/stop-async-context ctx)))))

(defn -main [mode manifest-path]
  (let [watchdog (doto (Thread. (fn [] (Thread/sleep 150000) (System/exit 2)))
                  (.setDaemon true) (.start))
        counts (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                 (try
                   (case mode "prepare" (prepare! manifest-path) "recover" (recover! manifest-path)
                         "prepare-custom" (prepare-custom! manifest-path)
                         "recover-custom" (recover-custom! manifest-path)
                         "prepare-running" (prepare-running! manifest-path)
                         "recover-running" (recover-running! manifest-path)
                         (throw (ex-info "Expected prepare/recover, prepare-custom/recover-custom, or prepare-running/recover-running" {:mode mode})))
                   (catch Throwable e
                     (test/do-report {:type :error :message "Cold recovery CLI failed" :expected :success :actual e})))
                 @test/*report-counters*)]
    (prn {:mode mode :assertions (+ (:pass counts) (:fail counts) (:error counts)) :counts counts})
    (flush)
    (if (= "prepare-running" mode)
      (.halt (Runtime/getRuntime) (if (zero? (+ (:fail counts) (:error counts))) 0 1))
      (do (shutdown-agents)
          (System/exit (if (zero? (+ (:fail counts) (:error counts))) 0 1))))))
