(ns ai.obney.orc.orc-service.test-helpers
  "Test utilities for behavior tree sheet service tests."
  (:require [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.queries]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.time.interface :as time])
  (:import [java.io File]))

;; =============================================================================
;; Test Context
;; =============================================================================

(defn- delete-dir-recursively
  "Delete a directory and all its contents."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-dir-recursively (.getPath child))))
      (.delete f))))

(defn create-test-context
  "Create a fresh test context with in-memory event store and LMDB cache."
  []
  (rmp/l1-clear!)
  (let [dir (str "/tmp/sheet-test-" (random-uuid))
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub nil
                               :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id #uuid "00000000-0000-0000-0000-000000000000"
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir dir}))

(defn stop-context
  "Stop and clean up test context."
  [ctx]
  (rmp/l1-clear!)
  (when-let [cache (:cache ctx)]
    (kv/stop cache))
  (when-let [event-store (:event-store ctx)]
    (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (delete-dir-recursively dir)))

(defmacro with-test-context
  "Execute body with a fresh test context, cleaning up afterward."
  [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context)]
     (try
       ~@body
       (finally
         (stop-context ~ctx-sym)))))

;; =============================================================================
;; Async Test Context (with PubSub + Todo Processors)
;; =============================================================================

(defn create-async-test-context
  "Create a test context with real pubsub and todo processors.
   Events are published and trigger todo processor handlers asynchronously.
   Returns context map with :processors key containing started processors."
  []
  (rmp/l1-clear!)
  (let [dir (str "/tmp/sheet-async-test-" (random-uuid))
        ps (pubsub/start {:type :core-async
                           :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub ps
                               :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id #uuid "00000000-0000-0000-0000-000000000000"
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  ::cache-dir dir}
        ;; Start a todo processor for each registered processor
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx
           :event-pubsub ps
           :processors processors)))

(defn stop-async-context
  "Stop and clean up async test context."
  [ctx]
  ;; Stop processors first, then pubsub, then event store, then cache
  (doseq [[_ processor] (:processors ctx)]
    (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)]
    (pubsub/stop ps))
  ;; Clear L1 cache after processors stopped to prevent stale writes
  (rmp/l1-clear!)
  (when-let [es (:event-store ctx)]
    (es/stop es))
  (when-let [cache (:cache ctx)]
    (kv/stop cache))
  (when-let [dir (::cache-dir ctx)]
    (delete-dir-recursively dir)))

(defmacro with-async-test-context
  "Execute body with an async test context (pubsub + todo processors).
   Cleans up afterward."
  [[ctx-sym] & body]
  `(let [~ctx-sym (create-async-test-context)]
     (try
       ~@body
       (finally
         (stop-async-context ~ctx-sym)))))

;; =============================================================================
;; Command/Query Execution
;; =============================================================================

(defn run-command
  "Run a command with the given context and command data.

   Uses command-processor to properly handle:
   - Command execution
   - Event storage (via event-store/append)
   - Error handling

   This is the correct pattern per Grain architecture."
  [ctx command-data]
  (cp/process-command (assoc ctx :command command-data)))

(defn apply-events!
  "DEPRECATED: No longer needed - cp/process-command handles event storage.

   Kept for backward compatibility but now just returns result unchanged."
  [_ctx result]
  result)

(defn run-and-apply!
  "Run a command and apply its events to the store.

   Note: Since run-command now uses cp/process-command,
   events are automatically stored. This function exists
   for backward compatibility with existing tests."
  [ctx command-data]
  (run-command ctx command-data))

(defn run-query
  "Run a query with the given context and query data."
  [ctx query-data]
  (let [query-name (:query/name query-data)
        handler-fn (get-in ctx [:query-registry query-name :handler-fn])]
    (if handler-fn
      (handler-fn (assoc ctx :query query-data))
      (throw (ex-info "Unknown query" {:query query-name})))))

;; =============================================================================
;; Factory Functions - Sheet Commands
;; =============================================================================

(defn make-create-sheet-command
  "Create a create-sheet command with defaults.
   If sheet-id is provided, uses that ID (for deterministic UUIDs)."
  [& {:keys [name sheet-id]
      :or {name "Test Sheet"}}]
  (cond-> {:command/name :sheet/create-sheet
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :name name}
    sheet-id (assoc :sheet-id sheet-id)))

(defn make-rename-sheet-command
  "Create a rename-sheet command."
  [sheet-id name]
  {:command/name :sheet/rename-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :name name})

(defn make-delete-sheet-command
  "Create a delete-sheet command."
  [sheet-id]
  {:command/name :sheet/delete-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id})

;; =============================================================================
;; Factory Functions - Node Commands
;; =============================================================================

(defn make-create-node-command
  "Create a create-node command."
  [sheet-id node-type & {:keys [node-id parent-id index]}]
  (cond-> {:command/name :sheet/create-node
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :type node-type}
    node-id (assoc :node-id node-id)
    parent-id (assoc :parent-id parent-id)
    index (assoc :index index)))

(defn make-move-node-command
  "Create a move-node command."
  [sheet-id node-id new-parent-id index]
  {:command/name :sheet/move-node
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :new-parent-id new-parent-id
   :index index})

(defn make-delete-node-command
  "Create a delete-node command."
  [sheet-id node-id]
  {:command/name :sheet/delete-node
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id})

(defn make-set-node-name-command
  "Create a set-node-name command."
  [sheet-id node-id name]
  {:command/name :sheet/set-node-name
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :name name})

(defn make-set-node-instruction-command
  "Create a set-node-instruction command."
  [sheet-id node-id instruction]
  {:command/name :sheet/set-node-instruction
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :instruction instruction})

(defn make-set-node-context-command
  "Create a set-node-context command for ontology context injection."
  [sheet-id node-id context]
  {:command/name :sheet/set-node-context
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :context context})

(defn make-set-node-io-command
  "Create a set-node-io command."
  [sheet-id node-id reads writes]
  {:command/name :sheet/set-node-io
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :reads reads
   :writes writes})

(defn make-set-node-executor-command
  "Create a set-node-executor command.
   executor-type: :ai, :code, or :tool
   opts: {:model \"...\", :fn \"...\", :tools [...]}"
  [sheet-id node-id executor-type & {:keys [model fn tools]}]
  (cond-> {:command/name :sheet/set-node-executor
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :executor executor-type}
    model (assoc :model model)
    fn (assoc :fn fn)
    tools (assoc :tools tools)))

(defn make-set-node-retry-command
  "Create a set-node-retry command."
  [sheet-id node-id max-attempts backoff-ms]
  {:command/name :sheet/set-node-retry
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :retry {:max-attempts max-attempts
           :backoff-ms backoff-ms}})

(defn make-set-node-check-command
  "Create a set-node-check command."
  [sheet-id node-id check]
  {:command/name :sheet/set-node-check
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :check check})

(defn make-set-parallel-config-command
  "Create a set-parallel-config command."
  [sheet-id node-id & {:keys [success-policy failure-policy]}]
  (cond-> {:command/name :sheet/set-parallel-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id}
    success-policy (assoc :success-policy success-policy)
    failure-policy (assoc :failure-policy failure-policy)))

(defn make-set-map-each-config-command
  "Create a set-map-each-config command."
  [sheet-id node-id source-key item-key output-key & {:keys [max-concurrency]}]
  (cond-> {:command/name :sheet/set-map-each-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :source-key source-key
           :item-key item-key
           :output-key output-key}
    max-concurrency (assoc :max-concurrency max-concurrency)))

(defn make-set-llm-condition-config-command
  "Create a set-llm-condition-config command."
  [sheet-id node-id instruction reads & {:keys [model]}]
  (cond-> {:command/name :sheet/set-llm-condition-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :instruction instruction
           :reads (vec reads)}
    model (assoc :model model)))

(defn make-set-repl-researcher-config-command
  "Create a set-repl-researcher-config command."
  [sheet-id node-id instruction reads writes mcp-tools & {:keys [model max-iterations browser-tools]}]
  (cond-> {:command/name :sheet/set-repl-researcher-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :instruction instruction
           :reads (vec reads)
           :writes (vec writes)
           :mcp-tools (vec (or mcp-tools []))}
    model (assoc :model model)
    max-iterations (assoc :max-iterations max-iterations)
    browser-tools (assoc :browser-tools (vec browser-tools))))

(defn make-set-delegate-config-command
  "Create a set-delegate-config command.
   Delegate nodes execute another sheet with isolated blackboard."
  [sheet-id node-id target-sheet-id & {:keys [reads writes timeout-ms inherit-ontology?]}]
  (cond-> {:command/name :sheet/set-delegate-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :target-sheet-id target-sheet-id
           :reads (vec (or reads []))
           :writes (vec (or writes []))}
    timeout-ms (assoc :timeout-ms timeout-ms)
    (some? inherit-ontology?) (assoc :inherit-ontology? inherit-ontology?)))

;; =============================================================================
;; Factory Functions - Judge Commands
;; =============================================================================

(defn make-declare-judge-command
  "Create a declare-judge command.

   Judge config specifies the judge type and custom criteria:
   {:type :completeness  ;; :grounding, :completeness, :instruction-following, :reasoning, :custom
    :criteria \"Must include X, Y, Z\"
    :weight 0.35
    :sheet-id UUID}  ;; For :custom type only"
  [sheet-id judge-name judge-config]
  {:command/name :sheet/declare-judge
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :judge-name judge-name
   :judge-config judge-config})

(defn make-set-node-judges-command
  "Create a set-node-judges command."
  [sheet-id node-id judges]
  {:command/name :sheet/set-node-judges
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :judges (vec judges)})

(defn make-set-node-context-command
  "Create a set-node-context command for self-learning injection."
  [sheet-id node-id context]
  {:command/name :sheet/set-node-context
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :context context})

;; =============================================================================
;; Factory Functions - Blackboard Commands
;; =============================================================================

(defn make-declare-key-command
  "Create a declare-key command with a Malli schema."
  [sheet-id key schema]
  {:command/name :sheet/declare-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :schema schema})

(defn make-set-key-value-command
  "Create a set-key-value command."
  [sheet-id key value]
  {:command/name :sheet/set-key-value
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :value value})

(defn make-delete-key-command
  "Create a delete-key command."
  [sheet-id key]
  {:command/name :sheet/delete-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key})

(defn make-set-content-hash-command
  "Create a set-content-hash command."
  [sheet-id content-hash]
  {:command/name :sheet/set-content-hash
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :content-hash content-hash})

;; =============================================================================
;; Factory Functions - Execution Commands
;; =============================================================================

(defn make-tick-tree-command
  "Create a tick-tree command."
  [sheet-id & {:keys [tick-id]}]
  (cond-> {:command/name :sheet/tick-tree
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id}
    tick-id (assoc :tick-id tick-id)))

(defn make-tick-node-command
  "Create a tick-node command."
  [sheet-id node-id & {:keys [tick-id overrides]}]
  (cond-> {:command/name :sheet/tick-node
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id}
    tick-id (assoc :tick-id tick-id)
    overrides (assoc :overrides overrides)))

;; =============================================================================
;; Factory Functions - Versioning Commands
;; =============================================================================

(defn make-publish-version-command
  "Create a publish-version command."
  [sheet-id & {:keys [description]}]
  (cond-> {:command/name :sheet/publish-version
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id}
    description (assoc :description description)))

(defn make-revert-to-version-command
  "Create a revert-to-version command."
  [sheet-id version-number]
  {:command/name :sheet/revert-to-version
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :version-number version-number})

(defn make-restore-stash-command
  "Create a restore-stash command."
  [sheet-id]
  {:command/name :sheet/restore-stash
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id})

(defn make-set-execution-mode-command
  "Create a set-execution-mode command."
  [sheet-id mode]
  {:command/name :sheet/set-execution-mode
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :mode mode})

;; =============================================================================
;; Factory Functions - Versioning Queries
;; =============================================================================

(defn make-version-history-query
  "Create a version-history query."
  [sheet-id]
  {:query/name :sheet/version-history
   :sheet-id sheet-id})

(defn make-get-version-query
  "Create a get-version query."
  [sheet-id version-number]
  {:query/name :sheet/get-version
   :sheet-id sheet-id
   :version-number version-number})

(defn make-get-stash-query
  "Create a get-stash query."
  [sheet-id]
  {:query/name :sheet/get-stash
   :sheet-id sheet-id})

;; =============================================================================
;; Assertion Helpers
;; =============================================================================

(defn get-event-type
  "Get the event type from a command result."
  [result]
  (-> result :command-result/events first :event/type))

(defn get-event-body
  "Get the event body from a command result."
  [result]
  (-> result :command-result/events first))

(defn is-anomaly?
  "Check if a result is an anomaly."
  [result]
  (contains? result :cognitect.anomalies/category))

(defn anomaly-category
  "Get the anomaly category from a result."
  [result]
  (:cognitect.anomalies/category result))

;; =============================================================================
;; Factory Functions - Execution Commands
;; =============================================================================

(defn make-execute-version-command
  "Create an execute-version command."
  [sheet-id version-number & {:keys [inputs]}]
  {:command/name :sheet/execute-version
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :version-number version-number
   :inputs (or inputs {})})

(defn make-batch-execute-command
  "Create a batch-execute command."
  [sheet-id inputs-list & {:keys [version-number]}]
  (cond-> {:command/name :sheet/batch-execute
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :inputs inputs-list}
    version-number (assoc :version-number version-number)))

;; =============================================================================
;; Factory Functions - Trace Queries
;; =============================================================================

(defn make-get-trace-query
  "Create a get-trace query."
  [trace-id]
  {:query/name :sheet/get-trace
   :trace-id trace-id})

(defn make-get-traces-query
  "Create a get-traces query."
  [sheet-id & {:keys [version-number status node-id since limit]}]
  (cond-> {:query/name :sheet/get-traces
           :sheet-id sheet-id}
    (some? version-number) (assoc :version-number version-number)
    status (assoc :status status)
    node-id (assoc :node-id node-id)
    since (assoc :since since)
    limit (assoc :limit limit)))

(defn make-diff-versions-query
  "Create a diff-versions query."
  [sheet-id from-version to-version]
  {:query/name :sheet/diff-versions
   :sheet-id sheet-id
   :from-version from-version
   :to-version to-version})

(defn make-node-stats-query
  "Create a node-stats query."
  [sheet-id & {:keys [version-number since node-ids]}]
  (cond-> {:query/name :sheet/node-stats
           :sheet-id sheet-id}
    (some? version-number) (assoc :version-number version-number)
    since (assoc :since since)
    (seq node-ids) (assoc :node-ids node-ids)))
