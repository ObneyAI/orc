(ns ai.obney.orc.orc-service.core.streaming
  "Live execution streaming hub.

   An ephemeral observation layer over tick execution. Consumers subscribe
   by tick-id (before dispatching the tick) and receive one core.async
   channel of normalized stream envelopes covering node lifecycle, progress,
   incremental node results, RLM phase activity, completion, errors, and
   cancellation. The durable event-sourced model is unchanged — the hub
   only *reads* from the Grain pubsub and is fed ephemeral events via
   `emit!` from executors. Nothing here is ever appended to the event store.

   Two invariants:
   1. The engine's shared pubsub can never be stalled or polluted: the tap's
      sub-chans are sliding-buffered (an unbuffered sub-chan would park
      async/pub distribution and drop engine events), and ephemeral events
      bypass the pubsub entirely.
   2. Loss is detectable: each subscription has a single router that assigns
      a strictly monotonic :seq BEFORE the consumer-facing sliding buffer,
      so a slow consumer that loses events sees a :seq gap and can
      reconcile from the event store.

   Envelope shape (flat; see interface/stream_schemas.clj):
     {:orc.stream/type :node-completed
      :seq 42                       ;; monotonic per subscription
      :ts #inst ...
      :tick-id #uuid ...            ;; tick the event belongs to
      :root-tick-id #uuid ...       ;; the subscribed root
      :sheet-id #uuid ...
      :node-id #uuid ...            ;; when node-scoped
      :map-each {:parent uuid :index int}  ;; when inside a map-each item
      ...type-specific keys}"
  (:require [clojure.core.async :as async]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private default-buffer 4096)
(def ^:private default-ttl-ms (* 60 60 1000))
(def ^:private value-size-limit 16384)

;; Durable event types forwarded from the Grain pubsub. Progress and
;; lifecycle only — :sheet/execution-value-written is omitted (duplicates
;; :node-completed :writes) and the RLM bookend events are covered by the
;; ephemeral phase events emitted at their source.
(def ^:private tapped-event-types
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/sequence-progress-updated
    :sheet/map-each-progress-updated
    :sheet/tree-tick-completed
    :sheet/tick-cancelled
    :rlm/tree-generated})

;; The map-each execution-context keys stamped on event :inputs by
;; todo_processors. Referenced by fully-qualified name so the consumer
;; envelope never leaks internal namespaced keywords.
(def ^:private map-each-index-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-index)
(def ^:private map-each-parent-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-parent)

;; =============================================================================
;; Registries
;; =============================================================================

;; sub-id -> {:root-tick-id uuid
;;            :tick-ids #{uuid ...}     ;; root + linked descendants
;;            :tenant-id any
;;            :src-chan chan            ;; internal merge FIFO (multi-producer)
;;            :events-ch chan           ;; consumer-facing, sliding buffer
;;            :seq-counter atom
;;            :node-cache atom          ;; tick-id -> nodes-by-id (for names)
;;            :context map
;;            :opts map}
(defonce ^:private subscriptions (atom {}))

;; child-tick-id -> parent-tick-id. In-memory only; matches the process-local
;; scope of streaming itself (multi-node deployments stream only locally).
(defonce ^:private lineage (atom {}))

;; Tap state: {:pubsub <handle> :chans [..]} — one tap per pubsub instance.
(defonce ^:private taps (atom {}))

;; =============================================================================
;; Lineage
;; =============================================================================

(defn descendants-of
  "All known descendant tick-ids of tick-id (transitive), from the
   in-memory lineage registry."
  [tick-id]
  (let [children-by-parent (reduce-kv (fn [acc c p] (update acc p (fnil conj #{}) c))
                                      {} @lineage)]
    (loop [frontier [tick-id]
           acc #{}]
      (if-let [t (first frontier)]
        (let [children (remove acc (get children-by-parent t))]
          (recur (into (rest frontier) children) (into acc children)))
        acc))))

(defn- subs-covering
  "Subscriptions whose :tick-ids include tick-id."
  [tick-id]
  (vals (into {} (filter (fn [[_ sub]] (contains? (:tick-ids sub) tick-id)) @subscriptions))))

(defn wanted?
  "Cheap check used by executors before building ephemeral events:
   is anyone streaming this tick?"
  [tick-id]
  (boolean (some (fn [sub] (contains? (:tick-ids sub) tick-id))
                 (vals @subscriptions))))

(defn delta-config
  "When some subscription covering tick-id opted into LLM token deltas
   (:llm-deltas? true), returns {:fields? true :raw? bool}; nil otherwise.
   Drives the Stage 2 streaming branch in execute-ai."
  [tick-id]
  (let [wanting (filter #(and (contains? (:tick-ids %) tick-id)
                              (get-in % [:opts :llm-deltas?]))
                        (vals @subscriptions))]
    (when (seq wanting)
      {:fields? true
       :raw? (boolean (some #(get-in % [:opts :raw-deltas?]) wanting))})))

(defn link-child!
  "Record that child-tick-id was spawned under parent-tick-id. Expands any
   subscription covering the parent to also cover the child and informs it
   with a :child-tick-linked envelope. Call BEFORE dispatching the child
   tick so no child events are missed."
  [parent-tick-id child-tick-id]
  (swap! lineage assoc child-tick-id parent-tick-id)
  (doseq [[sub-id sub] @subscriptions
          :when (contains? (:tick-ids sub) parent-tick-id)]
    ;; Guarded: a concurrent finalize may have removed the entry between the
    ;; snapshot deref and this swap. An unguarded update-in would RECREATE
    ;; it as a malformed zombie ({:tick-ids (list child)}) that poisons
    ;; every registry scan in the process.
    (swap! subscriptions
           (fn [m]
             (if (contains? m sub-id)
               (update-in m [sub-id :tick-ids] conj child-tick-id)
               m)))
    ;; put! on the snapshot's (possibly closed) chan is a harmless no-op
    (async/put! (:src-chan sub)
                {:orc.stream/type :child-tick-linked
                 :tick-id parent-tick-id
                 :parent-tick-id parent-tick-id
                 :child-tick-id child-tick-id})))

(defn unlink-tick!
  "Drop lineage entries rooted at tick-id. Called on subscription close for
   the root; safe to call redundantly."
  [tick-id]
  (let [ds (descendants-of tick-id)]
    (swap! lineage (fn [l] (apply dissoc l ds)))))

;; =============================================================================
;; Ephemeral emission (executor side-channel)
;; =============================================================================

(defn emit!
  "Push an ephemeral stream event for tick-id into every covering
   subscription. The map must carry :orc.stream/type; the router adds
   :seq/:ts/:root-tick-id. No-op when nobody is subscribed. Never blocks:
   src-chans are sliding-buffered."
  [tick-id m]
  (doseq [sub (subs-covering tick-id)]
    (async/put! (:src-chan sub) (assoc m :tick-id tick-id))))

;; =============================================================================
;; Value truncation
;; =============================================================================

(defn truncate-value
  "Cap a single value at value-size-limit when printed. Oversized values are
   replaced with {:orc.stream/truncated true :preview <string> :full-size n};
   the raw value is always recoverable from the event store. Public so
   executors can cap ephemeral payloads at the emission site."
  [v]
  (let [s (if (string? v) v (pr-str v))]
    (if (> (count s) value-size-limit)
      {:orc.stream/truncated true
       :preview (subs s 0 value-size-limit)
       :full-size (count s)}
      v)))

(defn- truncate-values [m]
  (when m
    (reduce-kv (fn [acc k v] (assoc acc k (truncate-value v))) {} m)))

;; =============================================================================
;; Envelope normalization
;; =============================================================================

(defn- map-each-context
  "Extract map-each correlation from event :inputs without leaking the
   internal namespaced keys."
  [inputs]
  (let [idx (get inputs map-each-index-key)
        parent (get inputs map-each-parent-key)]
    (when (and (some? idx) (some? parent))
      {:parent parent :index idx})))

(defn- node-info
  "Best-effort node name/type lookup from the tick execution snapshot,
   memoized per tick on the subscription. Returns {} when unresolvable."
  [sub tick-id node-id]
  (try
    (let [cache (:node-cache sub)
          nodes (or (get @cache tick-id)
                    (let [n (some-> (rm/get-tick-execution-context (:context sub) tick-id)
                                    :nodes-by-id)]
                      (swap! cache assoc tick-id (or n {}))
                      n))]
      (if-let [node (get nodes node-id)]
        (cond-> {}
          (:name node) (assoc :node-name (:name node))
          (:type node) (assoc :node-type (if (= :leaf (:type node))
                                           (or (:executor node) :ai)
                                           (:type node))))
        {}))
    (catch Exception _ {})))

(defn- normalize-durable
  "Durable Grain event -> envelope (sans :seq/:ts/:root-tick-id), or nil to
   skip. include-values? gates :writes payloads."
  [sub event include-values?]
  (let [t (:event/type event)
        tick-id (:tick-id event)
        base {:tick-id tick-id
              :sheet-id (:sheet-id event)}]
    (case t
      :sheet/tree-tick-started
      (merge base
             {:orc.stream/type :tick-started}
             (when-let [i (:iteration event)] {:iteration i})
             (when-let [p (:parent-tick-id event)] {:parent-tick-id p})
             (when-let [ks (keys (:inputs event))] {:input-keys (vec ks)}))

      :sheet/node-execution-started
      (merge base
             {:orc.stream/type :node-started
              :node-id (:node-id event)}
             (node-info sub tick-id (:node-id event))
             (when-let [me (map-each-context (:inputs event))] {:map-each me}))

      :sheet/node-execution-completed
      (merge base
             {:orc.stream/type :node-completed
              :node-id (:node-id event)
              :status (:status event)}
             (node-info sub tick-id (:node-id event))
             (when include-values?
               (when-let [w (:writes event)] {:writes (truncate-values w)}))
             (when-let [u (:usage event)] {:usage u})
             (when-let [d (:duration-ms event)] {:duration-ms d})
             (when-let [e (:error event)] {:error e})
             (when-let [ck (:completion-kind event)] {:completion-kind ck})
             (when-let [me (map-each-context (:inputs event))] {:map-each me}))

      :sheet/sequence-progress-updated
      (merge base {:orc.stream/type :progress
                   :node-id (:node-id event)
                   :kind :sequence
                   :index (:child-index event)
                   :total (:total-children event)})

      :sheet/map-each-progress-updated
      (merge base {:orc.stream/type :progress
                   :node-id (:node-id event)
                   :kind :map-each
                   :index (:item-index event)
                   :total (:total-items event)})

      :sheet/tree-tick-completed
      ;; :running completions are re-tick signals, not results (mirrors
      ;; deliver-execution-result). Skip them.
      (when (not= :running (:root-status event))
        (merge base
               {:orc.stream/type :tick-completed
                :status (:root-status event)}
               (when include-values?
                 (when-let [o (:outputs event)] {:outputs (truncate-values o)}))
               (when-let [e (:error event)] {:error e})))

      :sheet/tick-cancelled
      (merge base {:orc.stream/type :tick-cancelled})

      :rlm/tree-generated
      {:orc.stream/type :rlm-tree-generated
       :tick-id (:execution-id event)
       :sheet-id (:sheet-id event)
       :node-id (:node-id event)
       :raw-dsl (truncate-value (:raw-dsl event))}

      nil)))

;; =============================================================================
;; Subscription lifecycle
;; =============================================================================

(defn- close-subscription!
  "Idempotent: emit the terminal :stream-closed through the router (via the
   ::close sentinel) exactly once."
  [sub-id reason]
  (when-let [sub (get @subscriptions sub-id)]
    (async/put! (:src-chan sub) {::close reason})))

(defn- finalize-subscription!
  "Called by the router after it has emitted :stream-closed. Tears down
   channels and registry entries."
  [sub-id]
  (when-let [sub (get @subscriptions sub-id)]
    (swap! subscriptions dissoc sub-id)
    (unlink-tick! (:root-tick-id sub))
    (async/close! (:src-chan sub))
    (async/close! (:events-ch sub))))

(defn- ephemeral-for-sub
  "Per-subscription filtering of ephemeral envelopes: token-delta events are
   delivered only to subscriptions that opted in (delta-config is a per-tick
   union that ACTIVATES streaming; delivery still honors each subscriber)."
  [sub m]
  (case (:orc.stream/type m)
    :llm-fields (when (get-in sub [:opts :llm-deltas?]) m)
    :llm-raw-delta (when (get-in sub [:opts :raw-deltas?]) m)
    m))

(defn- start-router!
  "One router per subscription: single consumer of src-chan, single :seq
   assigner. Ordering holds because every producer (tap loops, emit!,
   link-child!) targets the same FIFO.

   Runs on a dedicated thread (async/thread), NOT a go block: envelope
   normalization does read-model lookups (node-info) and pr-str of
   potentially large values (truncate-values) — blocking work that would
   starve the fixed core.async dispatch pool and could stall the shared
   engine pubsub's distribution loops (P1)."
  [sub-id]
  (async/thread
    (loop []
      (let [sub (get @subscriptions sub-id)
            m (when sub (async/<!! (:src-chan sub)))]
        (cond
          (or (nil? sub) (nil? m))
          nil ;; closed underneath us — finalize already ran

          (::close m)
          (let [n (swap! (:seq-counter sub) inc)]
            (async/put! (:events-ch sub)
                        {:orc.stream/type :stream-closed
                         :seq n
                         :ts (time/now)
                         :tick-id (:root-tick-id sub)
                         :root-tick-id (:root-tick-id sub)
                         :reason (::close m)})
            (finalize-subscription! sub-id))

          :else
          (let [include-values? (get-in sub [:opts :include-values?] true)
                envelope (try
                           (if (:orc.stream/type m)
                             (ephemeral-for-sub sub m)
                             (normalize-durable sub m include-values?))
                           (catch Exception _ nil))]
            (when envelope
              (let [n (swap! (:seq-counter sub) inc)
                    terminal? (and (contains? #{:tick-completed :tick-cancelled}
                                              (:orc.stream/type envelope))
                                   (= (:tick-id envelope) (:root-tick-id sub)))]
                (async/put! (:events-ch sub)
                            (assoc envelope
                                   :seq n
                                   :ts (time/now)
                                   :root-tick-id (:root-tick-id sub)))
                (when terminal?
                  (close-subscription! sub-id
                                       (if (= :tick-cancelled (:orc.stream/type envelope))
                                         :cancelled
                                         :completed)))))
            (recur)))))))

;; =============================================================================
;; Pubsub tap
;; =============================================================================

(defn- ensure-tap!
  "Start (once per pubsub instance) the always-draining tap loops that
   forward matching durable events into covering subscriptions. Sub-chans
   are sliding-buffered so a burst can never park async/pub distribution."
  [ps]
  (when (and ps (not (contains? @taps ps)))
    (locking taps
      (when-not (contains? @taps ps)
        (let [chans (doall
                     (for [event-type tapped-event-types]
                       (let [ch (async/chan (async/sliding-buffer 1024))]
                         (pubsub/sub ps {:topic event-type :sub-chan ch})
                         (async/go-loop []
                           (when-let [event (async/<! ch)]
                             ;; A throw here would kill this tap loop for the
                             ;; whole process — never let one bad registry
                             ;; entry or event take streaming down.
                             (try
                               (doseq [sub (subs-covering
                                            (if (= :rlm/tree-generated (:event/type event))
                                              (:execution-id event)
                                              (:tick-id event)))
                                       :when (or (nil? (:tenant-id sub))
                                                 (nil? (:grain/tenant-id event))
                                                 (= (:tenant-id sub) (:grain/tenant-id event)))]
                                 (async/put! (:src-chan sub) event))
                               (catch Exception _ nil))
                             (recur)))
                         ch)))]
          (swap! taps assoc ps chans))))))

(defn shutdown-taps!
  "Close all tap channels. Test/REPL hygiene only — taps are otherwise
   process-lifetime."
  []
  (doseq [[_ chans] @taps, ch chans]
    (async/close! ch))
  (reset! taps {}))

(defn reset-all!
  "Test/REPL hygiene: synchronously tear down every subscription (without
   emitting :stream-closed), clear lineage, and shut down taps."
  []
  (doseq [sub-id (keys @subscriptions)]
    (finalize-subscription! sub-id))
  (reset! lineage {})
  (shutdown-taps!))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- resolve-pubsub [context]
  (or (:event-pubsub context)
      (get-in (:event-store context) [:config :event-pubsub])))

(defn subscribe-execution
  "Subscribe to the live event stream for tick-id and every child tick
   spawned under it (RLM Phase 2 trees, delegate nodes). Call BEFORE
   dispatching :sheet/tick-tree — tick-id is caller-suppliable on
   execute/tick-tree — so no events are missed.

   Options:
     :include-values? - include :writes/:outputs payloads (default true,
                        values capped at ~16KB with a :orc.stream/truncated
                        marker; raw values are in the event store)
     :llm-deltas?     - opt into live LLM output (:llm-fields progressive
                        field snapshots) from :ai leaves (default false;
                        requires a DSCloj with predict-stream-v2, otherwise
                        leaves run blocking as always)
     :raw-deltas?     - additionally receive :llm-raw-delta raw text chunks
                        (debug-grade; default false)
     :buffer          - consumer channel sliding-buffer size (default 4096).
                        A slow consumer loses OLDEST events; detect via :seq
                        gaps and reconcile from the event store.
     :ttl-ms          - backstop close for ticks that never complete
                        (default 1h)

   Returns {:events-ch <chan of envelopes, closes after :stream-closed>
            :tick-id tick-id
            :close! (fn [] ...)}   ;; idempotent
   or an anomaly map when no pubsub is reachable from the context."
  [context tick-id & {:keys [include-values? llm-deltas? raw-deltas? buffer ttl-ms]
                      :or {include-values? true
                           buffer default-buffer
                           ttl-ms default-ttl-ms}}]
  (let [ps (resolve-pubsub context)]
    (if-not ps
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message
       "No event pubsub reachable: expected :event-pubsub in context or [:config :event-pubsub] on :event-store"}
      (do
        (ensure-tap! ps)
        (let [sub-id (random-uuid)
              sub {:root-tick-id tick-id
                   :tick-ids #{tick-id}
                   :tenant-id (:tenant-id context)
                   :src-chan (async/chan (async/sliding-buffer default-buffer))
                   :events-ch (async/chan (async/sliding-buffer buffer))
                   :seq-counter (atom 0)
                   :node-cache (atom {})
                   :context context
                   :opts {:include-values? include-values?
                          :llm-deltas? (boolean llm-deltas?)
                          :raw-deltas? (boolean raw-deltas?)}}]
          (swap! subscriptions assoc sub-id sub)
          (start-router! sub-id)
          (async/go
            (async/<! (async/timeout ttl-ms))
            (close-subscription! sub-id :ttl))
          {:events-ch (:events-ch sub)
           :tick-id tick-id
           :sub-id sub-id ;; internal — lets execute-stream close with a precise reason
           :close! (fn [] (close-subscription! sub-id :closed-by-consumer))})))))

(defn execute-stream
  "Non-blocking streamed execution. Subscribes, then dispatches the tick the
   same way runtime/execute does, and returns immediately.

   Accepts every runtime/execute option (:timeout-ms :use-version
   :force-draft :trace? :langfuse-client :store-trace? :max-ticks
   :llm-call-budget :tick-id) plus subscription opts (:include-values?
   :buffer :ttl-ms).

   Returns {:tick-id uuid
            :events-ch <chan of envelopes>
            :close! (fn [])
            :result <promise of the exact runtime/execute return map>}
   or an anomaly map when subscription fails."
  [context sheet-id inputs & {:keys [timeout-ms use-version force-draft
                                     trace? langfuse-client store-trace?
                                     max-ticks llm-call-budget tick-id
                                     include-values? llm-deltas? raw-deltas? buffer ttl-ms]
                              :or {timeout-ms 300000 store-trace? true
                                   include-values? true
                                   buffer default-buffer}}]
  (let [tick-id (or tick-id (random-uuid))
        subscription (subscribe-execution context tick-id
                                          :include-values? include-values?
                                          :llm-deltas? llm-deltas?
                                          :raw-deltas? raw-deltas?
                                          :buffer buffer
                                          ;; stream at least as long as the execution
                                          :ttl-ms (or ttl-ms (+ timeout-ms 60000)))]
    (if (:cognitect.anomalies/category subscription)
      subscription
      (let [result-promise (promise)
            p (runtime/register-completion! tick-id)
            start-time (System/currentTimeMillis)
            cmd-result (cp/process-command
                        (assoc context :command
                               (cond-> {:command/id (random-uuid)
                                        :command/timestamp (time/now)
                                        :command/name :sheet/tick-tree
                                        :sheet-id sheet-id
                                        :tick-id tick-id
                                        :inputs (or inputs {})
                                        :options (cond-> {:timeout-ms timeout-ms
                                                          :store-trace? store-trace?}
                                                   trace? (assoc :trace? true)
                                                   langfuse-client (assoc :langfuse-client langfuse-client)
                                                   max-ticks (assoc :max-ticks max-ticks)
                                                   llm-call-budget (assoc :llm-call-budget llm-call-budget))}
                                 use-version (assoc :use-version use-version)
                                 force-draft (assoc :force-draft force-draft)
                                 ;; CE-5b FIX A (ADR 0018): carry the OPAQUE
                                 ;; :tool-context off the execute-stream context
                                 ;; onto the root :sheet/tick-tree command so it
                                 ;; survives the async command -> event ->
                                 ;; tick-execution-context read model boundary
                                 ;; and can be read back at node/leaf depth.
                                 ;; Mirrors rlm_tree_executor.clj's child-tick
                                 ;; threading. Absent -> not carried
                                 ;; (backward-compatible; non-coding turns see
                                 ;; no change).
                                 (:tool-context context)
                                 (assoc :tool-context (:tool-context context)))))]
        (if (:cognitect.anomalies/category cmd-result)
          (do (runtime/deregister-completion! tick-id)
              (emit! tick-id {:orc.stream/type :error
                              :error (:cognitect.anomalies/message cmd-result)
                              :source :command-dispatch})
              ((:close! subscription))
              (deliver result-promise
                       {:status :failure
                        :error (:cognitect.anomalies/message cmd-result)
                        :duration-ms (- (System/currentTimeMillis) start-time)}))
          (future
            (let [result (deref p timeout-ms ::timeout)
                  duration-ms (- (System/currentTimeMillis) start-time)]
              (runtime/deregister-completion! tick-id)
              (if (= result ::timeout)
                (do (close-subscription! (:sub-id subscription) :timeout)
                    (deliver result-promise {:status :timeout
                                             :error "Execution timed out"
                                             :duration-ms duration-ms}))
                ;; terminal tick event closes the stream; just deliver
                (deliver result-promise (assoc result :duration-ms duration-ms))))))
        {:tick-id tick-id
         :events-ch (:events-ch subscription)
         :close! (:close! subscription)
         :result result-promise}))))

(defn cancel!
  "Cancel a running tick (and any known child ticks spawned under it).

   Best-effort semantics: the engine stops progressing (no new nodes start;
   the re-tick loop halts) but in-flight LLM HTTP calls run to completion.
   Blocking callers unblock with {:status :failure :cancelled? true}; live
   streams receive :tick-cancelled then :stream-closed {:reason :cancelled}.

   Returns {:cancelled [tick-ids...]} or an anomaly map when the tick is
   unknown or already terminal."
  [context tick-id]
  (let [tick (rm/get-tick context tick-id)]
    (cond
      (nil? tick)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Unknown tick: " tick-id)}

      (contains? #{:completed :cancelled} (:status tick))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Tick already " (name (:status tick)))}

      :else
      (let [targets (cons tick-id (descendants-of tick-id))
            cancelled (vec
                       (for [t targets
                             :let [trec (if (= t tick-id) tick (rm/get-tick context t))
                                   sheet-id (:sheet-id trec)]
                             :when (and sheet-id
                                        (not (contains? #{:completed :cancelled}
                                                        (:status trec))))]
                         (do (cp/process-command
                              (assoc context :command
                                     {:command/id (random-uuid)
                                      :command/timestamp (time/now)
                                      :command/name :sheet/cancel-tick
                                      :sheet-id sheet-id
                                      :tick-id t}))
                             t)))]
        {:cancelled cancelled}))))
