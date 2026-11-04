(ns ai.obney.orc.orc-service.gepa-named-startup-recovery-test
  "ResumeFromRecordedBoundary before a task manifest exists. The registered,
   named Grain processor catches up its persisted start after SQLite reopen."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.gepa.core.metric-runtime :as metric]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [java.nio.file Files]))

(defn answer [{:keys [inputs]}]
  {:answer (str "answer:" (:question inputs))})

(defn- sheet-context [database tenant-id metric-calls]
  (let [initial (h/create-async-test-context
                  {:event-store-conn {:type :sqlite :database-file database :maximum-pool-size 2}
                   :context {:tenant-id tenant-id
                             :gepa/workflow-timeout-ms 15000
                             ;; Count the actual built-in metric invocation;
                             ;; the thunk still computes the real grade.
                             :gepa/metric-runner (fn [thunk _]
                                                   (swap! metric-calls inc)
                                                   (thunk))}})
        _ (h/stop-test-processors! initial)
        base (dissoc initial :processors)
        without (into #{} (remove #(= "sheet" (namespace %)))
                      (keys @tp/processor-registry*))]
    ;; Sheet effects run normally. GEPA starts only through the named processor
    ;; below, so fixture commands cannot advance the boundary before shutdown.
    (assoc base :processors (h/start-test-processors base without))))

(defn- named-candidate-processor [ctx]
  (let [processor-name :gepa/on-candidate-evaluation-started
        {:keys [handler-fn topics]} (get @tp/processor-registry* processor-name)]
    (when-not (and handler-fn (seq topics))
      (throw (ex-info "The registered GEPA evaluation processor is required" {})))
    ;; Unlike the generic async test helper, the production name enables Grain's
    ;; checkpoint lookup and startup catch-up before pubsub subscription.
    (tp/start {:processor-name processor-name
               :event-pubsub (:event-pubsub ctx)
               :handler-fn handler-fn :topics topics
               :context (dissoc ctx :processors)})))

(defn- events-of [ctx type]
  (filterv #(= type (:event/type %)) (h/read-all-events ctx)))

(defn- execution-evidence [ctx]
  ;; Trace-rendering work can finish asynchronously. Freeze the durable task,
  ;; execution and aggregate evidence whose duplicate effects this test guards.
  (filterv #(or (= "gepa" (namespace (:event/type %)))
                (#{:sheet/tree-tick-started :sheet/node-execution-started
                   :sheet/node-execution-completed :sheet/tree-tick-completed}
                 (:event/type %)))
           (h/read-all-events ctx)))

(defn- prepare! [ctx]
  (let [sheet-id (sheet/build-workflow! ctx
                   (sheet/workflow "gepa-named-startup"
                     (sheet/blackboard {:question :string :expected :string :answer :string})
                     (sheet/code "answer"
                       :fn "ai.obney.orc.orc-service.gepa-named-startup-recovery-test/answer"
                       :reads [:question] :writes [:answer])))
        input {"question" "q" "expected" "answer:q"}
        optimization-id (random-uuid)
        started (tu/process-command! ctx
                  {:command/name :gepa/start-optimization :optimization-id optimization-id
                   :sheet-id sheet-id :trainset [input] :valset [input]
                   :metric-descriptor (metric/prepare! ctx optimization-id {})
                   :config {:max-metric-calls 1}})
        candidate-id (get-in (tu/process-command! ctx
                              {:command/name :gepa/create-candidate :optimization-id optimization-id
                               :instructions {"answer" "Answer the supplied question"}
                               :parent-ids [] :mutation-reason "startup recovery fixture"})
                            [:command/result :candidate-id])
        evaluated (tu/process-command! ctx
                    {:command/name :gepa/evaluate-candidate
                     :optimization-id optimization-id :candidate-id candidate-id})
        starts (events-of ctx :gepa/candidate-evaluation-started)]
    (is (nil? (:cognitect.anomalies/category started)))
    (is (uuid? candidate-id))
    (is (nil? (:cognitect.anomalies/category evaluated)))
    (is (= 1 (count starts)))
    (is (empty? (:boundaries (ledger/get-state ctx optimization-id))))
    (is (empty? (events-of ctx :sheet/tree-tick-started)))
    {:optimization-id optimization-id :candidate-id candidate-id
     :evaluation-event-id (:event/id (first starts)) :input input}))

(deftest named-startup-recovers-an-evaluation-before-its-task-manifest
  (let [directory (.toFile (Files/createTempDirectory "orc-gepa-named-startup-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        database (str (io/file directory "events.db"))
        tenant-id (random-uuid)
        metric-calls (atom 0)]
    (try
      (let [initial (sheet-context database tenant-id metric-calls)
            {:keys [optimization-id candidate-id evaluation-event-id input]}
            (try (prepare! initial)
                 (finally (h/stop-async-context initial)))
            recovered (sheet-context database tenant-id metric-calls)]
        (try
          (is (= 0 @metric-calls))
          (is (empty? (:boundaries (ledger/get-state recovered optimization-id))))
          ;; No resume! or recovery scan: only real named processor startup.
          (let [processor (named-candidate-processor recovered)]
            (try
              (let [tasks (vals (:tasks (ledger/get-state recovered optimization-id)))
                    task (first tasks)
                    trace-id (:trace-id task)
                    aggregates (events-of recovered :gepa/candidate-evaluated)
                    expected {:question "q" :expected "answer:q" :answer "answer:q"}]
                (is (= 1 (count tasks)))
                (is (= :completed (:status task)))
                (is (= input (:input task)))
                (is (= 1 @metric-calls))
                (is (= 1 (count (events-of recovered :sheet/tree-tick-started))))
                (is (= trace-id (:tick-id (first (events-of recovered :sheet/tree-tick-started)))))
                (is (= 1 (count (events-of recovered :sheet/node-execution-completed))))
                (is (= expected (get-in task [:result :output])))
                (is (= expected (:outputs (sheet/get-execution-result recovered trace-id))))
                (is (= 1 (count aggregates)))
                (is (= candidate-id (:candidate-id (first aggregates))))
                (is (= [trace-id] (:trace-ids (first aggregates))))
                (is (= [1.0] (:scores (first aggregates))))
                (is (= 1 (:total-metric-calls (gepa/get-optimization-summary recovered optimization-id))))
                (is (some #(= evaluation-event-id (:triggered-by %))
                          (events-of recovered :grain/todo-processor-checkpoint))))
              (finally (tp/stop processor))))
          ;; A second startup reads its durable checkpoint; the same evaluation
          ;; cannot create another task, workflow, metric call or aggregate.
          (let [before (execution-evidence recovered)
                processor (named-candidate-processor recovered)]
            (try
              (is (= before (execution-evidence recovered)))
              (is (= 1 @metric-calls))
              (is (= [evaluation-event-id]
                     (mapv :event/id (events-of recovered :gepa/candidate-evaluation-started))))
              (finally (tp/stop processor))))
          (finally (h/stop-async-context recovered))))
      (finally
        (doseq [file (reverse (file-seq directory))]
          (io/delete-file file true))))))
