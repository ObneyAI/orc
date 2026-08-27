(ns ai.obney.orc.orc-service.test-helpers
  "Test utilities for behavior tree sheet service tests."
  (:require [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.queries]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store.interface.protocol :as kvp]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as rmp-core]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.fressian-util.interface :as fressian-util]
            [clojure.data.fressian :as fressian]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io File]))

;; =============================================================================
;; Counting KV store — L2 write-traffic accounting
;; =============================================================================
;;
;; The resting size of a read model's cache entry is only half the story. The
;; partitioned projection path rewrites its whole entry on every batch of
;; events it observes (read-model-processor-v2/core.clj p-partitioned-full),
;; so a read model that holds large values pays that size back on every
;; projection, not once. This decorator makes that traffic measurable: it is
;; the difference between "the entry is 2 KB" and "we wrote 200 MB to get
;; there."

(defn- record-put
  "Accumulate one put into the stats map, both in total and per cache key.
   Per-key is what names the offending read model in a failure message."
  [s ^String key-str ^long n]
  (-> s
      (update :put-count inc)
      (update :put-bytes + n)
      (update-in [:by-key key-str :n] (fnil inc 0))
      (update-in [:by-key key-str :bytes] (fnil + 0) n)))

(def ^:private empty-kv-stats
  {:put-count 0 :put-bytes 0 :get-count 0 :get-bytes 0 :by-key {}})

(defrecord CountingKV [inner stats]
  kvp/KVStore
  (start [this] (assoc this :inner (kvp/start inner)))
  (stop [_] (kvp/stop inner))
  (get! [_ args]
    (let [v (kvp/get! inner args)]
      (swap! stats (fn [s]
                     (-> s
                         (update :get-count inc)
                         (update :get-bytes + (if v (alength ^bytes v) 0)))))
      v))
  (put! [_ {:keys [k v] :as args}]
    (swap! stats record-put (String. ^bytes k) (count v))
    (kvp/put! inner args))
  (put-batch! [_ {:keys [entries] :as args}]
    (swap! stats (fn [s]
                   (reduce (fn [acc {:keys [k v]}]
                             (record-put acc (String. ^bytes k) (count v)))
                           s entries)))
    (kvp/put-batch! inner args)))

(defn counting-kv
  "Wrap a started KV store so every put/get is accounted. Returns the wrapper;
   read its counters with l2-stats."
  [inner]
  (->CountingKV inner (atom empty-kv-stats)))

(defn l2-stats
  "Current KV accounting for a context whose cache is a counting-kv.
   Returns nil when the cache is not instrumented."
  [ctx]
  (some-> (:cache ctx) :stats deref))

(defn l2-reset-stats!
  "Zero the KV counters. Call after fixture setup so a measurement covers only
   the tick under test, not the sheet-building commands that preceded it."
  [ctx]
  (some-> (:cache ctx) :stats (reset! empty-kv-stats))
  nil)

(defn l2-write-bytes
  "Bytes written to L2, optionally restricted to cache keys beginning with
   `key-prefix` (a read model's QUALIFIED name, e.g.
   :sheet/tick-execution-contexts — grain keys the cache by the full
   keyword since 5406c93).

   Cache keys are `<qualified-name>-<version>-<scope-hash>` plus a
   `:p<hash>`/`:s<n>`/`:eidx` suffix, so a name prefix selects exactly one
   read model across all its scopes, partitions and segments."
  (^long [ctx] (:put-bytes (l2-stats ctx) 0))
  (^long [ctx key-prefix]
   (->> (:by-key (l2-stats ctx))
        (filter (fn [[k _]] (str/starts-with? k (str key-prefix))))
        (map (comp :bytes val))
        (reduce + 0))))

(defn format-l2-stats
  "Render L2 accounting as a sorted table, biggest writer first."
  [stats]
  (let [rows (sort-by (comp - :bytes val) (:by-key stats))]
    (str (format "%-58s %6s %12s%n" "CACHE KEY" "PUTS" "BYTES")
         (apply str
                (for [[k {:keys [n bytes]}] rows]
                  (format "%-58s %6d %12d%n" k n bytes)))
         (format "%-58s %6d %12d%n" "TOTAL"
                 (:put-count stats) (:put-bytes stats)))))

;; =============================================================================
;; L2 cache entry inspection
;; =============================================================================
;;
;; Reads the bytes actually resident in LMDB for a given read model, so a test
;; can assert on what was STORED rather than on what an accessor returned. An
;; accessor-level assertion can be satisfied by a resolver that quietly re-adds
;; the values; this cannot.

(defn l2-cache-key
  "The LMDB key a read model's state is stored under, reconstructed the same
   way read-model-processor-v2 constructs it (core.clj format-scoped-key /
   partition-cache-key). `scope` is the map passed to rmp/project.

   Mirrors p-partitioned's key derivation: :partition-key is stripped from the
   scope hash, because it selects a partition ENTRY rather than a key space."
  ^bytes [ctx rm-name version scope partition-key]
  (let [cache-scope (not-empty (dissoc scope :partition-key))
        base (rmp-core/format-scoped-key
              rm-name version
              (if cache-scope [(:tenant-id ctx) cache-scope] (:tenant-id ctx)))]
    (if partition-key
      (rmp-core/partition-cache-key base partition-key)
      base)))

(defn l2-entry-raw
  "Raw stored bytes for a read model entry, or nil if absent."
  ^bytes [ctx rm-name version scope partition-key]
  (kv/get! (:cache ctx) {:k (l2-cache-key ctx rm-name version scope partition-key)}))

(defn l2-entry-bytes
  "Size in bytes of a read model's stored entry, 0 when absent.
   No ^long hint: Clojure allows primitive hints only up to 4 args."
  [ctx rm-name version scope partition-key]
  (if-let [b (l2-entry-raw ctx rm-name version scope partition-key)]
    (alength ^bytes b)
    0))

(defn l2-entry-state
  "Fressian-decoded state of a read model's stored entry, or nil.
   Returns the decoded wrapper — {:data ... :watermark ...} for a partition
   entry, or the manifest for a base key."
  [ctx rm-name version scope partition-key]
  (when-let [b (l2-entry-raw ctx rm-name version scope partition-key)]
    (fressian-util/decode b)))

(defn tick-context-l2-entry
  "The stored tick-execution-contexts entry for one tick.

   get-tick-execution-context projects with {:tags #{[:tick tick-id]}} as
   SCOPE (not :partition-key), so each tick mints its own key space and the
   sheet-id is the partition within it."
  [ctx sheet-id tick-id version]
  (l2-entry-state ctx :sheet/tick-execution-contexts version
                  {:tags #{[:tick tick-id]}} sheet-id))

(defn tick-context-l2-bytes
  "Size in bytes of one tick's stored execution-context entry."
  [ctx sheet-id tick-id version]
  (l2-entry-bytes ctx :sheet/tick-execution-contexts version
                  {:tags #{[:tick tick-id]}} sheet-id))

(defn contains-value-key?
  "True when `:value` appears anywhere in x, at any depth. Used to assert that
   a cached blackboard holds metadata only — a top-level check would miss a
   value nested under a per-key entry."
  [x]
  (let [found (volatile! false)]
    (walk/postwalk (fn [node]
                     (when (and (map? node) (contains? node :value))
                       (vreset! found true))
                     node)
                   x)
    @found))

(defn bytes-contain?
  "True when the byte array contains the UTF-8 encoding of `needle`.

   The strongest available proof that a payload is absent from storage: it
   makes no assumption about the stored shape, so it cannot be satisfied by
   moving a value to a different key."
  [^bytes haystack ^String needle]
  (when haystack
    (let [n (.getBytes needle "UTF-8")
          hl (alength haystack)
          nl (alength n)]
      (and (pos? nl)
           (loop [i 0]
             (cond
               (> (+ i nl) hl) false
               (loop [j 0]
                 (cond (= j nl) true
                       (= (aget haystack (+ i j)) (aget n j)) (recur (inc j))
                       :else false)) true
               :else (recur (inc i))))))))

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

(defn start-test-processors
  "Start every registered todo processor against an existing async context.
   Kept separate so recovery tests can tear down and rebuild processors while
   retaining the same strongly consistent event store, cache, and pubsub."
  [base-ctx]
  (reduce-kv
   (fn [acc proc-name {:keys [handler-fn topics]}]
     (assoc acc proc-name
            (tp/start
             (cond-> {:event-pubsub (:event-pubsub base-ctx)
                      :topics topics
                      :handler-fn handler-fn
                      :context base-ctx}
               (= "evaluation" (namespace proc-name))
               (assoc :processor-name proc-name)))))
   {}
   @tp/processor-registry*))

(defn stop-test-processors! [ctx]
  (doseq [[_ processor] (:processors ctx)]
    (tp/stop processor)))

(def ^:private background-drain-timeout-ms 30000)

(defn- acquire-background-work!
  [supervisor]
  (locking supervisor
    (when (:accepting? @supervisor)
      (let [done (promise)]
        (swap! supervisor update :work conj done)
        #(deliver done true)))))

(defn- tracked-background-submitter
  [supervisor]
  (fn [task]
    (if-let [release! (acquire-background-work! supervisor)]
      (future
        (try
          (task)
          (finally
            (release!))))
      (java.util.concurrent.CompletableFuture/completedFuture nil))))

(defn- fence-background-work!
  [ctx]
  (when-let [supervisor (:orc/background-supervisor ctx)]
    (locking supervisor
      (swap! supervisor assoc :accepting? false))))

(defn- drain-background-work!
  [ctx]
  (when-let [background-work (or (some-> ctx :orc/background-supervisor deref :work)
                                 (some-> ctx :orc/background-work deref))]
    (let [deadline (+ (System/currentTimeMillis) background-drain-timeout-ms)]
      (doseq [worker background-work]
        (let [remaining (max 1 (- deadline (System/currentTimeMillis)))
              result (deref worker remaining ::drain-timeout)]
          (when (= ::drain-timeout result)
            ;; Closing LMDB while this worker may still be committing can crash
            ;; the JVM. Fail teardown and deliberately leave the cache open.
            (throw (ex-info "Background work did not stop before context teardown"
                            {:timeout-ms background-drain-timeout-ms}))))))))

(defn create-async-test-context
  "Create a test context with real pubsub and todo processors.
   Events are published and trigger todo processor handlers asynchronously.
   Returns context map with :processors key containing started processors.

   Options:
     :count-cache? - wrap the LMDB cache so every put/get is accounted
                     (see l2-stats / l2-write-bytes). The wrapper must be in
                     place BEFORE processors start, since each processor
                     captures the context by value."
  ([] (create-async-test-context {}))
  ([{:keys [count-cache? context]}]
  (rmp/l1-clear!)
  (let [dir (str "/tmp/sheet-async-test-" (random-uuid))
        background-supervisor (atom {:accepting? true :work #{}})
        ps (pubsub/start {:type :core-async
                           :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub ps
                               :logger nil})
        cache (cond-> (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
                count-cache? counting-kv)
        base-ctx (merge {:event-store event-store
                         :cache cache
                         :tenant-id #uuid "00000000-0000-0000-0000-000000000000"
                         ;; Effect processors capture base-ctx by value. Custom
                         ;; evaluators sub-execute workflows and therefore need
                         ;; the same pubsub handle as top-level executions.
                         :event-pubsub ps
                         :command-registry (cp/global-command-registry)
                         :query-registry (qp/global-query-registry)
                         :llm-provider :openrouter
                         :orc/background-supervisor background-supervisor
                         :orc/acquire-background!
                         #(acquire-background-work! background-supervisor)
                         :orc/submit-background!
                         (tracked-background-submitter background-supervisor)
                         ::cache-dir dir}
                        context)
        ;; Start a todo processor for each registered processor
        processors (start-test-processors base-ctx)]
    (assoc base-ctx
           :event-pubsub ps
           :processors processors))))

(defn stop-async-context
  "Stop and clean up async test context."
  [ctx]
  ;; Fence admission first: Grain closes processor inputs on stop but does not
  ;; join handler threads already dispatched by its core.async executor. The
  ;; trace handler acquires ownership before touching projections, so a late
  ;; handler is rejected and every admitted handler/task can be drained while
  ;; pubsub, event store, and LMDB are still alive.
  (fence-background-work! ctx)
  (stop-test-processors! ctx)
  (drain-background-work! ctx)
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
   Cleans up afterward. An optional opts map is passed to
   create-async-test-context (e.g. {:count-cache? true})."
  [[ctx-sym & [opts]] & body]
  `(let [~ctx-sym (create-async-test-context (or ~opts {}))]
     (try
       ~@body
       (finally
         (stop-async-context ~ctx-sym)))))

(defn settle-until!
  "Block until `pred` returns truthy, or the timeout elapses. Returns true if
   the predicate was satisfied.

   Completion processors (trace assembly, result delivery) run in futures off
   the pubsub thread, so a test that accounts for storage must wait for them.
   A fixed sleep either flakes or wastes time; this waits for the actual
   condition and stops as soon as it holds."
  [pred & {:keys [timeout-ms interval-ms] :or {timeout-ms 15000 interval-ms 25}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep interval-ms) (recur))))))

(defn trace-stored?
  "True once an execution trace has been appended for `tick-id`. Trace
   assembly is the last thing a completed tick writes, so it is the settle
   signal for whole-run byte accounting.

   Reads directly rather than via read-all-events, which is defined below in
   the byte-accounting section. es/read returns a reducible, not a seq — it
   must be materialized before any seq operation."
  [ctx tick-id]
  (boolean (some #(and (= :sheet/execution-traced (:event/type %))
                       (= tick-id (:trace-id %)))
                 (into [] (es/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)})))))

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
;; Event Byte Accounting
;; =============================================================================
;;
;; Storage-cost measurement for the event log. Sizes events with the same
;; Fressian encoding the Postgres/SQLite event stores use (grain's
;; fressian-util write-handlers, which add the java.time tags), so the
;; numbers here correspond to what actually lands on disk — modulo row and
;; index overhead, and modulo Postgres TOAST compression on large values.
;;
;; Used by storage_budget_test to assert byte *invariants* (a payload is
;; stored a bounded number of times; lifecycle event size does not scale
;; with payload size) rather than fixed thresholds, so the copy-count
;; discipline cannot silently regress.

(defn event-bytes
  "Serialized size of a single event, in bytes, using grain's Fressian
   write-handlers. Sizes the whole event map (type, tags, timestamp, body)
   — not just the body — to match how the event store persists it."
  ^long [event]
  (let [baos (java.io.ByteArrayOutputStream.)
        writer (fressian/create-writer baos :handlers fressian-util/write-handlers)]
    (.writeObject writer event)
    (.close writer)
    (count (.toByteArray baos))))

(defn read-tick-events
  "All events tagged with the given tick-id, realized into a vector.
   Note this does NOT include events from child ticks (RLM Phase 2 trees,
   delegate nodes) — those carry their own tick-id. Use read-all-events
   when you need the whole run."
  [ctx tick-id]
  (into [] (es/read (:event-store ctx)
                    {:tags #{[:tick tick-id]}
                     :tenant-id (:tenant-id ctx)})))

(defn read-all-events
  "Every event in the store for this tenant, realized into a vector.
   This is the whole-run view: parent tick plus any child ticks spawned
   during it."
  [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(defn install-consumer-code-fixture!
  "Install a callable in a consumer-owned namespace for code-node boundary tests."
  []
  (let [consumer-ns (or (find-ns 'sormo.orc.consumer-code-fixture)
                        (create-ns 'sormo.orc.consumer-code-fixture))]
    (intern consumer-ns 'deterministic-decision
            (fn [{:keys [inputs]}]
              {:decision (str "act:" (:stimulus inputs))}))))

(defn byte-report
  "Per-event-type byte accounting for a collection of events.

   Returns:
     {:total-bytes  <sum over all events>
      :event-count  <n>
      :by-type      {<event-type> {:n <count> :total <bytes> :avg <bytes>}}}

   The :by-type map is what makes a regression legible — it names the event
   type that grew, not just that the total moved."
  [events]
  (let [sized (mapv (fn [e] [(:event/type e) (event-bytes e)]) events)]
    {:total-bytes (reduce + 0 (map second sized))
     :event-count (count sized)
     :by-type (reduce (fn [acc [t b]]
                        (-> acc
                            (update-in [t :n] (fnil inc 0))
                            (update-in [t :total] (fnil + 0) b)))
                      {}
                      sized)}))

(defn- finalize-averages
  [report]
  (update report :by-type
          (fn [m] (reduce-kv (fn [acc t {:keys [n total]}]
                               (assoc acc t {:n n :total total
                                             :avg (long (/ total (max n 1)))}))
                             {} m))))

(defn tick-byte-report
  "byte-report over one tick's events. See read-tick-events for the
   child-tick caveat."
  [ctx tick-id]
  (finalize-averages (byte-report (read-tick-events ctx tick-id))))

(defn run-byte-report
  "byte-report over every event in the store — the whole-run view."
  [ctx]
  (finalize-averages (byte-report (read-all-events ctx))))

(defn bytes-for-type
  "Total bytes attributed to a single event type in a report."
  ^long [report event-type]
  (get-in report [:by-type event-type :total] 0))

(defn- collect-large-strings
  "Every string of at least min-chars appearing anywhere in x, WITH
   repeats. Strings are where LLM payloads live and they do not nest, so
   collecting them avoids the double-counting a general subtree walk would
   produce. Large non-string collections are still covered indirectly —
   they decompose into the strings they contain."
  [x min-chars]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [node]
                     (when (and (string? node) (>= (count node) min-chars))
                       (vswap! acc conj node))
                     node)
                   x)
    @acc))

(defn payload-duplication-report
  "How much of the stored payload mass is the SAME content stored more than
   once, across all the given events.

   This is the measurement that decides whether content-addressed storage
   is worth building: structural de-duplication (removing redundant event
   fields) cannot touch duplication that is semantic — the same document
   re-seeded into several nested ticks.

   Options:
     :min-chars - floor for what counts as a payload (default 512). Small
                  strings are keys, names and statuses; counting them would
                  drown the signal.

   Returns:
     {:payload-bytes    <total bytes of all large-string occurrences>
      :unique-bytes     <bytes if each distinct string were stored once>
      :duplicate-bytes  <payload-bytes minus unique-bytes>
      :ratio            <duplicate-bytes / payload-bytes, 0.0 when none>
      :occurrences      <n large-string occurrences>
      :distinct         <n distinct large strings>}"
  [events & {:keys [min-chars] :or {min-chars 512}}]
  (let [occurrences (mapcat #(collect-large-strings % min-chars) events)
        utf8-len (fn ^long [^String s] (count (.getBytes s "UTF-8")))
        payload-bytes (reduce + 0 (map utf8-len occurrences))
        unique-bytes (reduce + 0 (map utf8-len (distinct occurrences)))
        duplicate-bytes (- payload-bytes unique-bytes)]
    {:payload-bytes payload-bytes
     :unique-bytes unique-bytes
     :duplicate-bytes duplicate-bytes
     :ratio (if (pos? payload-bytes)
              (double (/ duplicate-bytes payload-bytes))
              0.0)
     :occurrences (count occurrences)
     :distinct (count (distinct occurrences))}))

(defn format-byte-report
  "Render a byte-report as a sorted, human-readable table. Handy for
   recording a baseline in a test's output."
  [{:keys [total-bytes event-count by-type]}]
  (let [rows (sort-by (comp - :total val) by-type)]
    (str (format "%-42s %6s %12s %10s%n" "EVENT TYPE" "N" "TOTAL" "AVG")
         (apply str
                (for [[t {:keys [n total avg]}] rows]
                  (format "%-42s %6d %12d %10d%n" (str t) n total avg)))
         (format "%-42s %6d %12d%n" "TOTAL" event-count total-bytes))))

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
   opts: {:model \"...\", :fn \"...\", :tools [...], :options {...}}"
  [sheet-id node-id executor-type & {:keys [model fn tools options]}]
  (cond-> {:command/name :sheet/set-node-executor
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :executor executor-type}
    model (assoc :model model)
    fn (assoc :fn fn)
    tools (assoc :tools tools)
    options (assoc :options options)))

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
  [sheet-id node-id source-key item-key output-key & {:keys [max-concurrency preserve-failures?]}]
  (cond-> {:command/name :sheet/set-map-each-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :source-key source-key
           :item-key item-key
           :output-key output-key}
    max-concurrency (assoc :max-concurrency max-concurrency)
    preserve-failures? (assoc :preserve-failures? preserve-failures?)))

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
  [sheet-id node-id instruction reads writes mcp-tools & {:keys [model tool-caller-fn tool-contracts max-iterations browser-tools rlm timeout-ms options]}]
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
    tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
    tool-contracts (assoc :tool-contracts tool-contracts)
    max-iterations (assoc :max-iterations max-iterations)
    browser-tools (assoc :browser-tools (vec browser-tools))
    (some? rlm) (assoc :rlm rlm)
    timeout-ms (assoc :timeout-ms timeout-ms)
    options (assoc :options options)))

(defn make-set-delegate-config-command
  "Create a set-delegate-config command.
   Delegate nodes execute another sheet with isolated blackboard."
  [sheet-id node-id target-sheet-id & {:keys [reads writes timeout-ms max-ticks inherit-ontology?]}]
  (cond-> {:command/name :sheet/set-delegate-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :target-sheet-id target-sheet-id
           :reads (vec (or reads []))
           :writes (vec (or writes []))}
    timeout-ms (assoc :timeout-ms timeout-ms)
    max-ticks (assoc :max-ticks max-ticks)
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
