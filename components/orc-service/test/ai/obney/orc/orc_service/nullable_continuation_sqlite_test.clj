(ns ai.obney.orc.orc-service.nullable-continuation-sqlite-test
  "Controlled HTTP, real authored sequence, SQLite and fresh-JVM query rebuild."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(defn accept-null [{:keys [inputs]}]
  ;; This authored action distinguishes absent from present nil in its real
  ;; runtime inputs. A nil? check alone would accept missing input incorrectly.
  {:accepted (and (contains? inputs :answer) (nil? (:answer inputs)))})

(defn- digest [file]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream file)]
      (let [buf (byte-array 8192)]
        (loop [] (let [n (.read in buf)]
                   (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" (bit-and 255 %)) (.digest md)))))

(defn- checkpoint [ctx tick-id]
  (let [events (bt/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
        writes (filter #(= :sheet/execution-value-written (:event/type %)) events)
        producer-id (:node-id (first (filter #(= :answer (:key %)) writes)))
        consumer-id (:node-id (first (filter #(= :accepted (:key %)) writes)))
        completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
        producer (first (filter #(= producer-id (:node-id %)) completions))
        consumer (first (filter #(= consumer-id (:node-id %)) completions))]
    {:root-status (:root-status (bt/get-tick ctx tick-id))
     :producer-status (:status producer) :consumer-status (:status consumer)
     :producer-writes (bt/value-log-resolve-writes ctx (:tenant-id ctx) tick-id producer)
     :consumer-reads (bt/value-log-resolve-reads ctx (:tenant-id ctx) tick-id consumer)
     :consumer-writes (bt/value-log-resolve-writes ctx (:tenant-id ctx) tick-id consumer)
     ;; Compare complete native event print forms in fresh process, avoiding
     ;; EDN tagged Java-time reader differences. No event is excluded.
     :events (mapv pr-str events)}))

(defn -main [manifest-path result-path]
  ;; Query only: no provider, poller, schedule or execution is started.
  (let [{:keys [database tenant-id tick-id]} (edn/read-string (slurp manifest-path))
        support (h/create-test-context)]
    (es/stop (:event-store support))
    (let [store (es/start {:conn {:type :sqlite :database-file database :maximum-pool-size 2}})
          ctx (assoc support :event-store store :tenant-id tenant-id)]
      (try
        (spit result-path (pr-str (checkpoint ctx tick-id)))
        (finally (h/stop-context ctx)))))
  (shutdown-agents)
  (System/exit 0))

(deftest required-null-reaches-next-action-and-survives-fresh-projection
  (let [dir (.toFile (Files/createTempDirectory "orc-null-continuation-" (make-array java.nio.file.attribute.FileAttribute 0)))
        database (str (io/file dir "canonical.db"))
        replay-db (str (io/file dir "query-copy.db"))
        manifest (str (io/file dir "manifest.edn"))
        rebuilt-path (str (io/file dir "rebuilt.edn"))
        log (io/file dir "fresh-jvm.log")
        tenant-id (random-uuid)
        requests (atom [])
        before (atom nil)]
    (println "Nullable continuation evidence:" (.getAbsolutePath dir))
    (llm/register-provider! :nullable-continuation
      {:provider :openrouter :model "controlled/model" :config {:api-key "synthetic-not-sent"}})
    (with-redefs [http/post
                  (fn [_ options]
                    (swap! requests conj (json/parse-string (:body options) true))
                    (future
                      {:status 200
                       :body {:id "controlled-null" :model "controlled/model"
                              :choices [{:index 0 :finish_reason "tool_calls"
                                         :message {:role "assistant" :content nil
                                                   :tool_calls [{:id "controlled-call" :type "function"
                                                                 :function {:name "submit_response"
                                                                            :arguments "{\"answer-reasoning\":\"Unknown answer.\",\"answer\":null}"}}]}}]
                              :usage {:prompt_tokens 12 :completion_tokens 8 :total_tokens 20}}}))]
      (let [ctx (h/create-async-test-context
                 {:event-store-conn {:type :sqlite :database-file database :maximum-pool-size 2}
                  :context {:tenant-id tenant-id :llm-provider :nullable-continuation}})]
        (try
          (let [sheet (bt/build-workflow! ctx
                        (bt/workflow "required-null-continuation"
                          (bt/blackboard {:answer-reasoning [:string {:description "Concise basis for the answer."}]
                                          :answer [:maybe {:description "Required answer; null means unknown."} :string]
                                          :accepted [:boolean {:description "The next action received an explicitly present null answer."}]})
                          (bt/sequence "continue"
                            (bt/llm "answer" :instruction "Supply the contracted answer."
                                    :reads [] :writes [:answer-reasoning :answer]
                                    :retry {:max-attempts 1 :backoff-ms []}
                                    :options {:use-function-calling? true :max-retries 0 :max-tokens 256})
                            (bt/code "accept-null"
                                     :fn "ai.obney.orc.orc-service.nullable-continuation-sqlite-test/accept-null"
                                     :reads [:answer] :writes [:accepted]))))
                result (bt/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 1)
                tick-id (:trace-id result)]
            (is (= :success (:status result)))
            (reset! before {:tick-id tick-id :observation (checkpoint ctx tick-id)}))
          (finally (h/stop-async-context ctx)))))
    ;; Read the closed canonical log after the owned processors have drained;
    ;; late bookkeeping is retained rather than silently excluded.
    (let [support (h/create-test-context)]
      (es/stop (:event-store support))
      (let [ctx (assoc support :tenant-id tenant-id
                       :event-store (es/start {:conn {:type :sqlite :database-file database :maximum-pool-size 2}}))]
        (try (swap! before assoc :observation (checkpoint ctx (:tick-id @before)))
             (finally (h/stop-context ctx)))))
    (let [original (:observation @before)
          original-digest (digest database)]
      (spit (io/file dir "original.edn") (pr-str original))
      (spit (io/file dir "requests.edn") (pr-str @requests))
      (is (= 1 (count @requests)))
      (is (= :success (:root-status original)))
      (is (= :success (:producer-status original)))
      (is (= :success (:consumer-status original)))
      (is (contains? (:producer-writes original) :answer))
      (is (= {:answer nil} (:consumer-reads original)))
      (is (= {:accepted true} (:consumer-writes original)))
      (Files/copy (.toPath (io/file database)) (.toPath (io/file replay-db))
                  (make-array StandardCopyOption 0))
      (spit manifest (pr-str {:database replay-db :tenant-id tenant-id :tick-id (:tick-id @before)}))
      (let [command [(str (System/getProperty "java.home") "/bin/java")
                     "--add-opens=java.base/java.nio=ALL-UNNAMED"
                     "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
                     "-cp" (System/getProperty "java.class.path") "clojure.main" "-m"
                     "ai.obney.orc.orc-service.nullable-continuation-sqlite-test" manifest rebuilt-path]
            process (.start (doto (ProcessBuilder. ^java.util.List command)
                              (.redirectErrorStream true) (.redirectOutput log)))
            exited? (.waitFor process 60 TimeUnit/SECONDS)]
        (when-not exited? (.destroyForcibly process) (.waitFor process 10 TimeUnit/SECONDS))
        (is exited? "fresh JVM must finish within its bound")
        (is (and exited? (zero? (.exitValue process))) (str "See " log))
        (when (and exited? (zero? (.exitValue process)))
          (let [rebuilt (edn/read-string (slurp rebuilt-path))]
            (is (= original rebuilt) "Fresh projections and complete canonical event sequence agree")
            (is (contains? (:producer-writes rebuilt) :answer))
            (is (= {:answer nil} (:consumer-reads rebuilt)))
            (is (= {:accepted true} (:consumer-writes rebuilt))))))
      (is (= original-digest (digest database)) "Closed original SQLite file is unchanged"))))
