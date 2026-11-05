(ns ai.obney.orc.orc-service.durable-execution-result-test
  "Public, read-only reconstruction of completed execution results."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [java.nio.file Files]))

(defn echo-answer [{:keys [inputs]}]
  {:answer (str "answer:" (:question inputs))})

(defn- workflow []
  (sheet/workflow "durable-result-lookup"
    (sheet/blackboard {:question :string :answer :string})
    (sheet/code "answer"
      :fn "ai.obney.orc.orc-service.durable-execution-result-test/echo-answer"
      :reads [:question] :writes [:answer])))

(deftest completed-result-is-available-through-the-public-interface
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (workflow))
          tick-id (random-uuid)
          result (sheet/execute ctx sheet-id {:question "q"}
                                :tick-id tick-id :timeout-ms 30000)
          lookup (ns-resolve 'ai.obney.orc.orc-service.interface
                             'get-execution-result)]
      (is (= :success (:status result)))
      (is (ifn? lookup) "Durable result lookup is a public ORC operation")
      (when lookup
        (let [reconstructed (lookup ctx tick-id)]
          (is (= :success (:status reconstructed)))
          (is (= tick-id (:trace-id reconstructed)))
          (is (= {:question "q" :answer "answer:q"} (:outputs reconstructed)))
          (is (uuid? (get-in reconstructed [:output-sources :answer :event-id]))))))))

(defn- read-context [database tenant-id]
  (let [support (h/create-test-context)]
    (es/stop (:event-store support))
    (assoc support :tenant-id tenant-id
           :event-store (es/start {:conn {:type :sqlite :database-file database
                                          :maximum-pool-size 2}}))))

(defn- event-records [ctx]
  (mapv pr-str (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))))

(deftest an-existing-incomplete-tick-has-no-terminal-result
  (h/with-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (workflow))
          tick-id (random-uuid)
          started (h/run-and-apply! ctx
                    (assoc (h/make-tick-tree-command sheet-id :tick-id tick-id)
                           :inputs {:question "pending"}))
          before (event-records ctx)]
      (is (not (:cognitect.anomalies/category started)))
      (is (= 1 (count (filter #(= :sheet/tree-tick-started (:event/type %))
                              (h/read-all-events ctx)))))
      (is (nil? (sheet/get-execution-result ctx tick-id)))
      (is (= before (event-records ctx)) "Reading pending work neither dispatches nor finalizes it"))))

(defn- verify-reconstructed-result [{:keys [database tenant-id tick-id expected]}]
  ;; No processors, schedules, provider, or completion waiter are started.
  (let [ctx (read-context database tenant-id)]
    (try
      (let [before (event-records ctx)
            actual (sheet/get-execution-result ctx tick-id)]
        (is (= expected actual))
        (is (= :success (:status actual)))
        (is (= tick-id (:trace-id actual)))
        (is (= 1 (:executed-version actual)))
        (is (= {:question "durable" :answer "answer:durable"} (:outputs actual)))
        (is (nil? (sheet/get-execution-result ctx (random-uuid))))
        (is (nil? (sheet/get-execution-result (assoc ctx :tenant-id (random-uuid)) tick-id)))
        (is (= before (event-records ctx)) "Lookup does not append events or retick"))
      (finally (h/stop-context ctx)))))

(deftest sqlite-result-reopens-without-running-the-work-again
  (let [dir (.toFile (Files/createTempDirectory "orc-durable-result-"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        database (str (io/file dir "events.db"))
        tenant-id (random-uuid)
        tick-id (random-uuid)
        ctx (h/create-async-test-context
              {:event-store-conn {:type :sqlite :database-file database :maximum-pool-size 2}
               :context {:tenant-id tenant-id}})
        expected (try
                   (let [sheet-id (sheet/build-workflow! ctx (workflow))
                         published (h/run-and-apply! ctx
                                     (h/make-publish-version-command sheet-id))
                         result (sheet/execute ctx sheet-id {:question "durable"}
                                               :tick-id tick-id :use-version 1 :timeout-ms 30000)]
                     (is (not (:cognitect.anomalies/category published)))
                     (is (= :success (:status result)))
                     (sheet/get-execution-result ctx tick-id))
                   (finally (h/stop-async-context ctx)))
        manifest {:database database :tenant-id tenant-id :tick-id tick-id :expected expected}]
    (try
      (verify-reconstructed-result manifest)
      ;; A separate JVM can verify this same durable store after this JVM exits.
      ;; The test never starts an overlapping child JVM merely to prove recovery.
      (when-let [path (System/getenv "ORC_DURABLE_RESULT_MANIFEST")]
        (spit path (pr-str manifest)))
      (finally
        (when-not (System/getenv "ORC_DURABLE_RESULT_MANIFEST")
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str database suffix) true))
          (io/delete-file dir true))))))

(defn -main [manifest-path]
  (let [counts (binding [clojure.test/*report-counters*
                        (ref clojure.test/*initial-report-counters*)]
                 (verify-reconstructed-result (edn/read-string (slurp manifest-path)))
                 @clojure.test/*report-counters*)]
    (prn {:cold-jvm counts})
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail counts) (:error counts))) 0 1))))
