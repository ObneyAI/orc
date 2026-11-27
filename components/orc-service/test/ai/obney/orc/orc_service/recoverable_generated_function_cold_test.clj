(ns ai.obney.orc.orc-service.recoverable-generated-function-cold-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [java.util.concurrent TimeUnit]))
(defn- read-edn [file] (edn/read-string {:default tagged-literal} (slurp file)))
(defn- launch! [mode dir]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        command [java "--add-opens=java.base/java.nio=ALL-UNNAMED"
                 "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" "-Xmx768m"
                 "-cp" (h/fresh-jvm-classpath) "clojure.main" "-m"
                 "ai.obney.orc.orc-service.recoverable-function-worker" mode (str dir)]
        builder (ProcessBuilder. ^java.util.List command)]
    (.redirectErrorStream builder true)
    (.redirectOutput builder (io/file dir (str mode "-console.txt")))
    (.start builder)))
(defn- end-owned! [^Process p]
  (when (and p (.isAlive p))
    (.destroyForcibly p)
    (assert (.waitFor p 10 TimeUnit/SECONDS) "Owned proof JVM did not close")))
(deftest new-jvm-reconstructs-original-pending-callable-without-prefix-replay
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "orc-callable-cold-" (make-array java.nio.file.attribute.FileAttribute 0)))
        owned (atom [])]
    (println :COLD-PROOF-DIRECTORY (str dir))
    (try
      (let [first-worker (launch! "prepare" dir)
            _ (swap! owned conj first-worker)
            ready (io/file dir "pending.edn")]
        (h/settle-until! #(or (.exists ready) (not (.isAlive first-worker))) :timeout-ms 90000)
        (is (.exists ready)
            (h/worker-diagnostic "Missing pending gate" first-worker (io/file dir "prepare-console.txt")))
        (when (.exists ready)
          (let [manifest (read-edn (io/file dir "manifest.edn"))
                pending (read-edn ready)
                original-events (:events pending)
                original-starts (filter #(and (= :sheet/node-execution-started (:event/type %)) (= (:node manifest) (:node-id %))) original-events)
                original-start (last original-starts)
                prefix-completions (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= :success (:status %))) original-events)]
            (is (= (.pid first-worker) (:pid manifest)))
            (is (= (:tick manifest) (get-in pending [:identity :tick-id])))
            (is (= (:node manifest) (get-in pending [:identity :node-id])))
            (is (= 1 (count original-starts)))
            (is (= 1 (count prefix-completions)))
            (is (not-any? #(= :sheet/tree-tick-completed (:event/type %)) original-events) "Cold proof must cross pending work, not a completed-child reuse")
            (is (= (:deadline manifest) (get-in pending [:snapshot :options :execution-deadline-ms])))
            (end-owned! first-worker)
            (let [second-worker (launch! "recover" dir)
                  _ (swap! owned conj second-worker)
                  exited? (.waitFor second-worker 90 TimeUnit/SECONDS)
                  console (io/file dir "recover-console.txt")]
              (is exited? (h/worker-diagnostic "Recovery worker timed out" second-worker console))
              (when exited?
                (is (zero? (.exitValue second-worker))
                    (h/worker-diagnostic "Recovery worker failed" second-worker console)))
              (is (.exists (io/file dir "recovered.edn")))
              (when (.exists (io/file dir "recovered.edn"))
                (let [recovered (read-edn (io/file dir "recovered.edn"))
                      all (:events recovered)
                      resumed (filter #(and (= :sheet/node-execution-started (:event/type %)) (= (:node manifest) (:node-id %)) (:resumed-from-event-id %)) all)
                      node (get-in recovered [:snapshot :nodes-by-id (:node manifest)])
                      effect-lines (with-open [reader (io/reader (io/file dir "effects.edn"))] (doall (line-seq reader)))
                      completions (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= :success (:status %))) all)]
                  (is (not= (:pid manifest) (:pid recovered)))
                  (is (= (:source-origins manifest) (:source-origins recovered)))
                  (is (= #{"core/agent_runtime.clj"
                           "core/block.clj"
                           "core/commands.clj"
                           "core/execution_budget.clj"
                           "core/logic.clj"
                           "core/periodic_tasks.clj"
                           "core/queries.clj"
                           "core/read_models.clj"
                           "core/todo_processors.clj"
                           "interface/agent_runtime.clj"
                           "interface/processor_registration.clj"
                           "interface/projections.clj"
                           "interface/schemas.clj"
                           "interface/stream_schemas.clj"
                           "interface.clj"}
                         (set (keys (:source-origins recovered)))))
                  (is (= 1 (count resumed)))
                  (is (= (:event/id original-start) (:resumed-from-event-id (first resumed))))
                  (is (= (:tick manifest) (:tick-id (first resumed))))
                  (is (= (:key manifest) (:fn node)))
                  (is (= (:binding manifest) (:recoverable-fn node)))
                  (is (= (:deadline manifest) (get-in recovered [:snapshot :options :execution-deadline-ms])))
                  (is (= 1 (count effect-lines)) "Completed prefix effect is not replayed")
                  (is (= 2 (count completions)))
                  (is (= :success (get-in recovered [:result :status])))
                  (is (= 14 (get-in recovered [:result :outputs :result])))
                  (is (empty? (:second-resume recovered)))
                  (is (false? (:callable-after-terminal? recovered)))
                  (is (= (map :event/id original-events) (map :event/id (take (count original-events) all))))))))))
      (finally (doseq [p @owned] (end-owned! p))))))
