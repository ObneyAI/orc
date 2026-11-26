(ns ai.obney.orc.orc-service.researcher-repair-composition-worker
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.logic :as service-logic])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress] [java.util.concurrent TimeUnit]))
(def tree
  '[:sequence
    [:code {:reads [:seed] :writes [:prefix] :output-schemas {:prefix :int}
            :fn (fn [{:keys [inputs once-effect]}] (once-effect) {:prefix (+ 3 (:seed inputs))})}]
    [:code {:reads [:prefix] :writes [:doubled] :output-schemas {:doubled :int}
            :fn (fn [{:keys [inputs owned-gate tick-id node-id]}]
                  (owned-gate {:tick-id tick-id :node-id node-id})
                  {:doubled (* 2 (:prefix inputs))})}]
    [:llm {:model "fixture/semantic" :instruction "Return packet with count equal to doubled, enabled false, note null."
           :reads [:doubled] :writes [:reasoning :packet]
           :options {:output-mode :function-calling :max-retries 0}
           :output-schemas {:reasoning :string :packet [:map {:closed true} [:count :int] [:enabled :boolean] [:note [:maybe :string]]]}}]
    [:final {:keys [:packet]}]])
(def first-code (str "(emit-tree! (quote " (pr-str tree) "))"))
(def final-code "(final! {:answer (get-var :packet)})")
(defn read! [f] (edn/read-string {:default tagged-literal} (slurp f)))
(defn save! [dir file data] (spit (io/file dir file) (pr-str data)))
(def source-resources
  ["ai/obney/orc/orc_service/core/agent_runtime.clj"
   "ai/obney/orc/orc_service/core/block.clj"
   "ai/obney/orc/orc_service/core/commands.clj"
   "ai/obney/orc/orc_service/core/execution_budget.clj"
   "ai/obney/orc/orc_service/core/logic.clj"
   "ai/obney/orc/orc_service/core/periodic_tasks.clj"
   "ai/obney/orc/orc_service/core/queries.clj"
   "ai/obney/orc/orc_service/core/read_models.clj"
   "ai/obney/orc/orc_service/core/todo_processors.clj"
   "ai/obney/orc/orc_service/interface/agent_runtime.clj"
   "ai/obney/orc/orc_service/interface/processor_registration.clj"
   "ai/obney/orc/orc_service/interface/projections.clj"
   "ai/obney/orc/orc_service/interface/schemas.clj"
   "ai/obney/orc/orc_service/interface/stream_schemas.clj"
   "ai/obney/orc/orc_service/interface.clj"
   "ai/obney/orc/orc_service/test_helpers.clj"
   "ai/obney/orc/integrations/interface.clj"
   "ai/obney/orc/llm/core.clj"
   "ai/obney/orc/llm/interface.clj"
   "ai/obney/orc/file_store/interface/protocol.clj"
   "ai/obney/orc/file_store/interface.clj"
   "ai/obney/orc/langfuse/core.clj"
   "ai/obney/orc/langfuse/interface.clj"])
(defn source-evidence []
  (mapv (fn [resource]
          (let [url (io/resource resource)
                _ (assert url (str "Missing source resource: " resource))
                bytes (.getBytes (slurp url) "UTF-8")]
            {:resource resource :url (str url)
             :sha256 (format "%064x" (java.math.BigInteger. 1
                                      (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))}))
        source-resources))
(defn guard-sources! [dir mode]
  (let [expected (read! (io/file dir "expected-sources.edn"))
        actual (source-evidence)]
    (assert (= expected actual) "Worker sources must match the invoking test JVM")
    (save! dir (str mode "-sources.edn") actual)))
(defn process-info []
  (let [p (java.lang.ProcessHandle/current) info (.info p)]
    {:pid (.pid p) :command (.orElse (.command info) "")
     :argv (vec (.orElse (.arguments info) (make-array String 0)))
     :classpath (System/getProperty "java.class.path")}))
(defn worker [mode dir url]
  (let [mfile (io/file dir "manifest.edn") m (when (.exists mfile) (read! mfile))
        tenant (or (:tenant m) (random-uuid)) tick (or (:tick m) (random-uuid))
        profile :composition/profile ctx* (atom nil) gate (promise)]
    (llm/register-provider! profile {:provider :openrouter :model "fixture/research"
      :output-mode :function-calling :config {:api-key "CONTROLLED-LOCAL" :api-base url :timeout 5000}})
    (let [ctx (h/create-async-test-context
                {:event-store-conn {:type :sqlite :database-file (str (io/file dir "events.sqlite")) :maximum-pool-size 2}
                 :context {:tenant-id tenant :llm-provider profile :orc/execution-lease-ms 2000
                   :once-effect #(spit (io/file dir "effects.edn") "{:completed-prefix true}\n" :append true)
                   :owned-gate (fn [identity]
                     (when (= mode "prepare")
                       (save! dir "pending.edn" {:identity identity :events (h/read-all-events @ctx*)
                         :parent (orc/get-tick-execution-context @ctx* tick)
                         :child (orc/get-tick-execution-context @ctx* (:tick-id identity))})
                       @gate))}})]
      (reset! ctx* ctx)
      (guard-sources! dir mode)
      (save! dir (str mode "-process.edn") (process-info))
      (try
        (if (= mode "prepare")
          (let [sheet (orc/build-workflow! ctx
                        (orc/workflow "researcher-recovery-composition"
                          (orc/blackboard {:seed :int :answer [:map {:closed true} [:count :int] [:enabled :boolean] [:note [:maybe :string]]]})
                          (orc/repl-researcher "actual-researcher"
                            :model "fixture/research" :instruction "Emit one typed child tree then finalize its actual packet."
                            :reads [:seed] :writes [:answer] :max-iterations 2
                            :options {:max-retries 0}
                            :rlm {:recursive? true :checkpointed? true
                                  :timeouts {:provider-ms 5000 :iteration-ms 180000 :campaign-ms 240000}})))
                deadline (+ (System/currentTimeMillis) 240000)]
            (save! dir "manifest.edn" {:tenant tenant :tick tick :sheet sheet :deadline deadline
              :first-code first-code :final-code final-code :tree tree :profile profile :url url})
            (save! dir "prepare-return.edn" (orc/execute ctx sheet {:seed 4} :tick-id tick
              :timeout-ms 240000 :execution-deadline-ms deadline :llm-call-budget 3 :max-ticks 1)))
          (let [before (h/read-all-events ctx)
                pending (read! (io/file dir "pending.edn"))
                pending-node (get-in pending [:child :nodes-by-id (get-in pending [:identity :node-id])])
                callable-before? (some? (service-logic/lookup-ephemeral-fn (:fn pending-node)))
                _ (assert (false? callable-before?) "Fresh JVM must have no original callable")
                resumed (orc/resume-in-progress! ctx)
                settled (h/settle-until! #(some (fn [e] (and (= tick (:tick-id e)) (= :sheet/tree-tick-completed (:event/type e)))) (h/read-all-events ctx)) :timeout-ms 45000)
                events (h/read-all-events ctx)
                completions (filter #(and (= tick (:tick-id %)) (= :sheet/node-execution-completed (:event/type %))) events)
                writes (mapv #(orc/value-log-resolve-writes ctx tenant tick %) completions)]
            (save! dir "recovered.edn" {:settled settled :before before :resumed resumed :events events
              :parent (orc/get-tick-execution-context ctx tick) :tick (orc/get-tick ctx tick)
              :child (orc/get-tick-execution-context ctx (get-in pending [:identity :tick-id]))
              :callable-before? callable-before?
              :canonical-completions (mapv (fn [e] {:event e :writes (orc/value-log-resolve-writes ctx tenant (:tick-id e) e)})
                                           (filter #(= :sheet/node-execution-completed (:event/type %)) events))
              :writes writes :second-resume (orc/resume-in-progress! ctx)})
            (assert settled "Actual parent did not finish")))
        (finally (deliver gate true) (h/stop-async-context ctx) (shutdown-agents))))))
(defn server! [dir]
  (let [calls (atom []) prime (atom 0) pool (java.util.concurrent.Executors/newSingleThreadExecutor)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler (handle [_ x]
        (try
          (let [raw (slurp (.getRequestBody x)) body (json/parse-string raw true)
                values (if (= "fixture/research" (:model body))
                         {:reasoning "controlled real research turn" :code (if (= 1 (swap! prime inc)) first-code final-code)}
                         {:reasoning "controlled semantic packet" :count 14 :enabled false :note nil})
                response {:id (str "local-" (count @calls)) :model (:model body)
                  :choices [{:index 0 :finish_reason "tool_calls" :message {:role "assistant" :content nil
                    :tool_calls [{:id "local-submit" :type "function" :function {:name "submit_response" :arguments (json/generate-string values)}}]}}]
                  :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}
                serialized (json/generate-string response) bytes (.getBytes serialized "UTF-8")]
            (swap! calls conj {:raw-request raw :request body :raw-response serialized :response response})
            (save! dir "wire.edn" @calls)
            (.set (.getResponseHeaders x) "Content-Type" "application/json")
            (.sendResponseHeaders x 200 (alength bytes))
            (with-open [out (.getResponseBody x)] (.write out bytes)))
          (catch Throwable e (save! dir "server-error.edn" {:class (str (class e)) :message (.getMessage e)}))
          (finally (.close x))))))
    (.start server)
    {:server server :pool pool :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))
(defn launch! [mode dir url]
  (let [argv [(str (System/getProperty "java.home") "/bin/java") "-Xmx768m"
              "--add-opens=java.base/java.nio=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
              "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "ai.obney.orc.orc-service.researcher-repair-composition-worker" mode (str dir) url]
        b (ProcessBuilder. ^java.util.List argv)]
    (.redirectErrorStream b true) (.redirectOutput b (io/file dir (str mode ".log")))
    (let [p (.start b)] (save! dir (str mode "-launch.edn") {:pid (.pid p) :argv argv}) p)))
(defn kill-owned! [p]
  (when (.isAlive ^Process p) (.destroyForcibly ^Process p) (assert (.waitFor ^Process p 10 TimeUnit/SECONDS)))
  (.exitValue ^Process p))
(defn run! [dir]
  (.mkdirs (io/file dir))
  (assert (not (.exists (io/file dir "events.sqlite"))))
  (save! dir "expected-sources.edn" (source-evidence))
  (let [s (server! dir) owned (atom [])]
    (try
      (let [p (launch! "prepare" dir (:url s)) _ (swap! owned conj p)
            ready (io/file dir "pending.edn")]
        (h/settle-until! #(or (.exists ready) (not (.isAlive p))) :timeout-ms 90000)
        (assert (.exists ready) (str "Missing pending gate; see " dir))
        (save! dir "prepare-exit.edn" {:pid (.pid p) :exit (kill-owned! p)})
        (let [q (launch! "recover" dir (:url s)) _ (swap! owned conj q)
              closed (.waitFor q 90 TimeUnit/SECONDS)]
          (assert closed "Recovery worker timed out")
          (save! dir "recover-exit.edn" {:pid (.pid q) :exit (.exitValue q)})
          (assert (zero? (.exitValue q)) "Recovery worker failed")
          (let [r (read! (io/file dir "recovered.edn"))]
            (assert (= :success (get-in r [:tick :root-status])) "Actual parent must succeed")
            (assert (= {:count 14 :enabled false :note nil} (:answer (first (:writes r)))) "Exact canonical native answer required"))))
      (finally
        (doseq [p @owned] (kill-owned! p))
        (.stop (:server s) 0)
        (.shutdownNow (:pool s))
        (assert (.awaitTermination (:pool s) 5 TimeUnit/SECONDS) "Owned HTTP executor did not close")))))
(defn -main [mode dir & [url]]
  (try
    (assert (contains? #{"prepare" "recover"} mode) "Worker mode must be prepare or recover")
    (worker mode dir url) (System/exit 0)
       (catch Throwable e (.printStackTrace e) (System/exit 1))))
