(ns ai.obney.orc.demos.pi-agent-loop.repl
  "Local nREPL lifecycle and named, stateful Pi harness sessions."
  (:refer-clojure :exclude [reset!])
  (:require [ai.obney.orc.demos.pi-agent-loop.events :as events]
            [ai.obney.orc.demos.pi-agent-loop.model :as model]
            [ai.obney.orc.demos.pi-agent-loop.runtime :as runtime]
            [ai.obney.orc.demos.pi-agent-loop.scenarios :as scenarios]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [ai.obney.orc.demos.pi-agent-loop.tools :as tools]
            [ai.obney.orc.orc-service.interface :as orc]
            [nrepl.server :as nrepl]))

(defrecord Session [harness results evidence])

(defonce ^:private state
  (atom {:server nil :system nil :sessions {} :stopped (promise)}))

(defn running? [] (boolean (:server @state)))

(defn server-info []
  (let [{:keys [server sessions]} @state]
    {:running? (boolean server)
     :bind "127.0.0.1"
     :port (some-> server :port)
     :sessions (vec (keys sessions))}))

(defn context []
  (or (get-in @state [:system :context])
      (throw (ex-info "Pi demo nREPL is not running" {:kind :not-running}))))

(defn start!
  "Start a loopback-only nREPL and disposable Grain/ORC system.

   Port zero asks the operating system to choose a free port. Starting the
   server does not create a session or contact a model provider."
  ([] (start! 0))
  ([port]
   (locking state
     (when (running?)
       (throw (ex-info "Pi demo nREPL is already running"
                       {:kind :already-running :server (server-info)})))
     (let [orc-system (system/start!)]
       (try
         (let [server (nrepl/start-server :bind "127.0.0.1" :port port)
               info {:running? true :bind "127.0.0.1" :port (:port server)
                     :sessions []}]
           (clojure.core/reset! state {:server server :system orc-system
                                       :sessions {} :stopped (promise)})
           info)
         (catch Throwable error
           (system/stop! orc-system)
           (throw error)))))))

(defn- require-session [name]
  (or (get-in @state [:sessions name])
      (throw (ex-info (str "Unknown Pi harness session: " name)
                      {:kind :unknown-session :name name}))))

(defn create-session!
  "Create a named harness from explicit core-loop options.

   The disposable ORC context is supplied automatically. At minimum, options
   normally contains :model-turn and :tools."
  [name options]
  (context)
  (locking state
    (when (get-in @state [:sessions name])
      (throw (ex-info (str "Pi harness session already exists: " name)
                      {:kind :duplicate-session :name name})))
    (let [session (->Session (runtime/create (assoc options :context (context)))
                             (atom []) (or (:evidence options) (atom [])))]
      (swap! state assoc-in [:sessions name] session)
      name)))

(defn create-live-session!
  "Create a real-provider session around caller-supplied Pi/ORC tools."
  [name {:keys [tools provider model-name system-prompt request-options]
         :or {provider :openrouter
              model-name "google/gemini-3.6-flash"
              system-prompt "Use the available tools when they are relevant. Never invent tool results."
              request-options {}}}]
   (model/setup!)
   (let [evidence (atom [])
         turn (model/live-model-turn
               {:provider provider :model model-name :system-prompt system-prompt
                :tools tools :request-options request-options :evidence evidence})]
     (create-session! name {:tools tools :model-turn turn :evidence evidence})))

(defn create-demo-session!
  "Create a live conversational session with the demo's typed ORC lookup tool."
  [name]
  (let [sheet-id (orc/build-workflow!
                  (context)
                  (orc/workflow (str "pi-repl-lookup-" (clojure.core/name name))
                    (orc/blackboard {:query :string :answer :string})
                    (orc/code "lookup" :fn (str `scenarios/nonce-lookup)
                              :reads [:query] :writes [:answer])))
        correlation-id (random-uuid)
        tool (assoc
              (tools/orc-workflow-tool
               {:name "lookup_secret"
                :description "Retrieve the authoritative secret for a query."
                :context (context) :sheet-id sheet-id
                :correlation-id correlation-id
                :input-fn #(select-keys % [:query])
                :content-fn #(get-in % [:outputs :answer])})
              :parameters {:type "object"
                           :properties {:query {:type "string"}}
                           :required ["query"]
                           :additionalProperties false})]
    (create-live-session!
     name
     {:tools {"lookup_secret" tool}
      :system-prompt (str "You are a conversational Pi/ORC harness. Use lookup_secret "
                          "for requests that need its authoritative value, never invent "
                          "tool results, and otherwise converse normally.")})))

(defn sessions [] (vec (keys (:sessions @state))))

(defn prompt! [name content]
  (let [{:keys [harness results]} (require-session name)
        result (runtime/prompt! harness [{:role :user :content content}])]
    (swap! results conj result)
    result))

(defn continue! [name]
  (let [{:keys [harness results]} (require-session name)
        result (runtime/continue! harness)]
    (swap! results conj result)
    result))

(defn history [name] (runtime/messages (:harness (require-session name))))
(defn results [name] @(-> (require-session name) :results))
(defn last-result [name] (last (results name)))
(defn last-events [name] (:events (last-result name)))
(defn event-history [name] (into [] (mapcat :events) (results name)))
(defn provider-evidence [name] @(-> (require-session name) :evidence))

(defn durable-summary [name]
  (when-let [result (last-result name)]
    (events/durable-summary (context) result)))

(defn durable-history [name]
  (mapv #(events/durable-summary (context) %) (results name)))

(defn steer! [name content]
  (runtime/steer! (:harness (require-session name)) [{:role :user :content content}])
  name)

(defn follow-up! [name content]
  (runtime/follow-up! (:harness (require-session name)) [{:role :user :content content}])
  name)

(defn stop-after-turn! [name]
  (runtime/stop-after-turn! (:harness (require-session name)))
  name)

(defn abort! [name]
  (runtime/abort! (:harness (require-session name)))
  name)

(defn close-session! [name]
  (let [session (require-session name)]
    (when (runtime/running? (:harness session))
      (runtime/abort! (:harness session)))
    (swap! state update :sessions dissoc name)
    name))

(defn shutdown! []
  (locking state
    (let [{:keys [server system sessions stopped]} @state]
      (doseq [[_ session] sessions]
        (when (runtime/running? (:harness session))
          (runtime/abort! (:harness session))))
      (try
        (when server (nrepl/stop-server server))
        (finally
          (when system (system/stop! system))
          (deliver stopped true)
          (clojure.core/reset! state {:server nil :system nil :sessions {}
                                      :stopped (promise)})))))
  :stopped)

(defn -main [& [port-text]]
  (let [port (if port-text (parse-long port-text) 0)
        {:keys [bind port]} (start! port)
        stopped (:stopped @state)
        hook (Thread. ^Runnable #(shutdown!) "pi-demo-nrepl-shutdown")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (println (str "Pi/ORC nREPL listening on " bind ":" port))
    (println "Evaluate functions in ai.obney.orc.demos.pi-agent-loop.repl")
    (try
      @stopped
      (finally
        (try (.removeShutdownHook (Runtime/getRuntime) hook)
             (catch IllegalStateException _))))))
