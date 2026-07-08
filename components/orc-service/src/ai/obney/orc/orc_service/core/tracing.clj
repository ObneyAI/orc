(ns ai.obney.orc.orc-service.core.tracing
  "Langfuse tracing for behavior tree execution.

   Provides observability into workflow execution by sending:
   - Trace events for overall workflow execution
   - Span events for each node execution
   - Generation events for AI node executions

   Events are sent immediately as they occur (streaming) to avoid
   payload size limits when batching large outputs."
  (:require [ai.obney.orc.langfuse.interface :as langfuse]
            [ai.obney.grain.time.interface :as time]
            [cheshire.core :as json])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]))

;; Maximum output size in bytes before truncation (3 MB, under Langfuse's 4.5 MB limit)
(def ^:private max-output-bytes (* 3 1024 1024))

;; =============================================================================
;; Post-hoc Trace Assembly Helpers (event correlation)
;; =============================================================================
;;
;; Shared, pure helpers for correlating :sheet/node-execution-started /
;; :sheet/node-execution-completed events during post-hoc trace assembly.
;; They live here (rather than in todo-processors) so the on-demand
;; node-trace-detail query can reuse the SAME correlation logic without a
;; require cycle into the todo-processor namespace.

(def trace-context-ns
  "Namespace (as a string) of the execution-disambiguating input keys
   (::map-each-index / ::map-each-parent are interned under the
   todo-processors namespace). Referenced by fully-qualified name here so this
   ns-agnostic helper resolves the same keyword regardless of who calls it."
  "ai.obney.orc.orc-service.core.todo-processors")

(def ^:private map-each-index-key (keyword trace-context-ns "map-each-index"))
(def ^:private map-each-parent-key (keyword trace-context-ns "map-each-parent"))

(defn trace-execution-context
  "Return the execution-disambiguating context for trace correlation.
   This distinguishes repeated executions of the same node under map-each."
  [inputs]
  (select-keys (or inputs {}) [map-each-index-key map-each-parent-key]))

(defn trace-execution-key
  "Correlation key for matching started/completed events in trace assembly."
  [event]
  [(:node-id event) (trace-execution-context (:inputs event))])

(defn node-trace-inputs
  "The user-facing subset of a node-execution-started :inputs map: keyword keys
   that are NOT execution-context keys. Matches the filter the trace builder
   applied when it used to inline :inputs on each node trace."
  [inputs]
  (into {} (filter (fn [[k _]]
                     (and (keyword? k)
                          (not (= (namespace k) trace-context-ns))))
                   inputs)))

(defn node-trace-io
  "Given all events for a tick and a node-id, return the on-demand
   {:node-id :inputs :outputs} detail for the FIRST execution of that node,
   sourcing :inputs from the :sheet/node-execution-started event (context keys
   stripped) and :outputs from the correlated :sheet/node-execution-completed
   :writes. Returns nil when the node has no started event.

   This reproduces the pre-RB-2a node-trace-detail contract, now sourced from
   the granular events instead of an inlined copy on the aggregate trace."
  [events node-id]
  (let [started-events   (filter #(= :sheet/node-execution-started (:event/type %)) events)
        completed-events (filter #(= :sheet/node-execution-completed (:event/type %)) events)
        completed-by-execution (reduce (fn [acc e] (assoc acc (trace-execution-key e) e))
                                       {} completed-events)
        started (first (filter #(= node-id (:node-id %)) started-events))]
    (when started
      (let [completed      (get completed-by-execution (trace-execution-key started))
            started-inputs (:inputs started)]
        {:node-id node-id
         :inputs  (when started-inputs (node-trace-inputs started-inputs))
         :outputs (:writes completed)}))))

;; =============================================================================
;; Trace Event Builders
;; =============================================================================

(defn- timestamp-str []
  (str (time/now)))

(defn- millis->iso
  "Convert epoch milliseconds to ISO 8601 timestamp string."
  [epoch-ms]
  (-> (Instant/ofEpochMilli epoch-ms)
      (.atZone (ZoneId/systemDefault))
      (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn trace-start-event
  "Create a trace-create event for starting a workflow execution."
  [trace-id sheet-name inputs]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "trace-create"
   :body {:id (str trace-id)
          :name (or sheet-name "Workflow Execution")
          :startTime (timestamp-str)
          :input inputs
          :metadata {:type "behavior-tree"}}})

(defn- limit-output-size
  "Truncate output if it exceeds max-size bytes when serialized.
   Returns truncation summary if too large, otherwise returns original output."
  [outputs max-size]
  (try
    (let [json-str (json/generate-string outputs)
          json-size (count json-str)]
      (if (> json-size max-size)
        {:_truncated true
         :original-size json-size
         :message "Output truncated due to size. See individual node traces for details."}
        outputs))
    (catch Exception _
      ;; If serialization fails, return a safe fallback
      {:_error "Failed to serialize output"
       :message "See individual node traces for details."})))

(defn trace-end-event
  "Create a trace-create event for completing a workflow execution.
   Large outputs are truncated to stay under Langfuse's payload size limit.
   Includes trace-name to preserve it (Langfuse updates can clear missing fields)."
  [trace-id trace-name status outputs duration-ms error]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "trace-create"
   :body (cond-> {:id (str trace-id)
                  :name (or trace-name "Workflow Execution")
                  :endTime (timestamp-str)
                  :output (limit-output-size outputs max-output-bytes)
                  :metadata {:status (name status)
                             :duration-ms duration-ms}}
           error (assoc-in [:metadata :error] error))})

(defn span-event
  "Create a span-create event for a node execution.
   Uses the passed observation-id for unique identification per execution."
  [trace-id node-id node-name node-type start-time end-time inputs outputs status error
   & {:keys [observation-id parent-observation-id]}]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "span-create"
   :body (cond-> {:id observation-id
                  :traceId (str trace-id)
                  :name (or node-name (str node-type " node"))
                  :startTime (millis->iso start-time)
                  :endTime (millis->iso end-time)
                  :input inputs
                  :output outputs
                  :metadata {:node-id (str node-id)
                             :node-type (name node-type)
                             :status (name status)}}
           parent-observation-id (assoc :parentObservationId parent-observation-id)
           error (assoc-in [:metadata :error] error))})

(defn generation-event
  "Create a generation-create event for an AI node execution.
   Uses the passed observation-id for unique identification per execution.

   Langfuse usage format:
   - :promptTokens - Number of tokens in the prompt
   - :completionTokens - Number of tokens in the completion
   - :totalTokens - Total tokens used"
  [trace-id node-id node-name model start-time end-time inputs outputs status error
   & {:keys [observation-id parent-observation-id usage]}]
  (let [;; Convert usage keys to camelCase for Langfuse
        langfuse-usage (when usage
                         (let [prompt (:prompt-tokens usage)
                               completion (:completion-tokens usage)
                               total (:total-tokens usage)]
                           (cond-> {}
                             prompt (assoc :promptTokens prompt)
                             completion (assoc :completionTokens completion)
                             total (assoc :totalTokens total))))]
    {:id (random-uuid)
     :timestamp (timestamp-str)
     :type "generation-create"
     :body (cond-> {:id observation-id
                    :traceId (str trace-id)
                    :name (or node-name "AI Generation")
                    :startTime (millis->iso start-time)
                    :endTime (millis->iso end-time)
                    :input inputs
                    :output outputs
                    :model model
                    :metadata {:node-id (str node-id)
                               :status (name status)}}
             parent-observation-id (assoc :parentObservationId parent-observation-id)
             (seq langfuse-usage) (assoc :usage langfuse-usage)
             error (assoc-in [:metadata :error] error))}))

;; =============================================================================
;; Trace Context
;; =============================================================================

(defn create-trace-context
  "Create a new trace context for a workflow execution.

   Returns a map with:
   - :trace-id - UUID for the trace
   - :langfuse-client - Client for sending events (may be nil)
   - :events - Atom collecting events to send
   - :enabled? - Whether tracing is enabled
   - :trace-name-atom - Atom to store trace name for use in end event"
  [langfuse-client]
  {:trace-id (random-uuid)
   :langfuse-client langfuse-client
   :events (atom [])
   :trace-name-atom (atom nil)
   :enabled? (and langfuse-client
                  (:public-key langfuse-client)
                  (:secret-key langfuse-client))})

(defn record-event!
  "Record an event and send it immediately to Langfuse.
   Events are streamed as they occur to avoid large batch payloads."
  [trace-ctx event]
  (when (:enabled? trace-ctx)
    ;; Still collect for local traces and debugging
    (swap! (:events trace-ctx) conj event)
    ;; Send immediately to Langfuse (streaming)
    (langfuse/ingestion (:langfuse-client trace-ctx) [event])))

(defn- reorder-events-for-langfuse
  "Reorder events so parents are sent before children.
   Langfuse requires parent observations to exist before children reference them."
  [events]
  (let [;; Separate trace events from observation events
        trace-start (filter #(and (= "trace-create" (:type %))
                                  (not (get-in % [:body :endTime]))) events)
        trace-end (filter #(and (= "trace-create" (:type %))
                                (get-in % [:body :endTime])) events)
        observations (filter #(#{"span-create" "generation-create"} (:type %)) events)

        ;; Build a map of id -> event for observations
        obs-by-id (into {} (map (fn [e] [(get-in e [:body :id]) e]) observations))

        ;; Topological sort: events with no parent first, then children
        sorted-obs (loop [remaining (set (keys obs-by-id))
                          result []
                          processed #{}]
                     (if (empty? remaining)
                       result
                       ;; Find events whose parent is either nil or already processed
                       (let [ready (filter (fn [id]
                                             (let [parent (get-in obs-by-id [id :body :parentObservationId])]
                                               (or (nil? parent)
                                                   (contains? processed parent))))
                                           remaining)]
                         (if (empty? ready)
                           ;; Circular dependency or missing parent - just add remaining
                           (concat result (map obs-by-id remaining))
                           (recur (apply disj remaining ready)
                                  (concat result (map obs-by-id ready))
                                  (into processed ready))))))]
    (concat trace-start sorted-obs trace-end)))

(defn flush-events!
  "No-op for Langfuse since events are sent immediately via record-event!.
   Kept for backward compatibility and local trace collection."
  [_trace-ctx]
  ;; Events are already sent immediately in record-event!, nothing to flush
  nil)

;; =============================================================================
;; High-Level Tracing Functions
;; =============================================================================

(defn start-trace!
  "Start a new trace for a workflow execution.
   Stores the sheet-name in trace-ctx for use in end-trace!."
  [trace-ctx sheet-name inputs]
  ;; Store name in trace context for later use
  (when (:trace-name-atom trace-ctx)
    (reset! (:trace-name-atom trace-ctx) sheet-name))
  (record-event! trace-ctx
                 (trace-start-event (:trace-id trace-ctx) sheet-name inputs)))

(defn end-trace!
  "End the current trace with results."
  [trace-ctx status outputs duration-ms error]
  (let [trace-name (when-let [atom (:trace-name-atom trace-ctx)]
                     @atom)]
    (record-event! trace-ctx
                   (trace-end-event (:trace-id trace-ctx) trace-name status outputs duration-ms error))
    (flush-events! trace-ctx)))

(defn trace-node!
  "Record a node execution span."
  [trace-ctx {:keys [node-id node-name node-type executor model
                     start-time end-time inputs outputs status error
                     observation-id parent-observation-id usage]}]
  (let [event (if (= :ai executor)
                (generation-event (:trace-id trace-ctx) node-id node-name model
                                  start-time end-time inputs outputs status error
                                  :observation-id observation-id
                                  :parent-observation-id parent-observation-id
                                  :usage usage)
                (span-event (:trace-id trace-ctx) node-id node-name node-type
                            start-time end-time inputs outputs status error
                            :observation-id observation-id
                            :parent-observation-id parent-observation-id))]
    (record-event! trace-ctx event)))

;; =============================================================================
;; Local Trace Collector (for REPL / non-Langfuse use)
;; =============================================================================

(defn create-local-trace
  "Create a local trace collector that doesn't send to Langfuse.
   Useful for REPL testing and debugging."
  []
  {:trace-id (random-uuid)
   :langfuse-client nil
   :events (atom [])
   :enabled? true
   :local? true})

(defn get-trace-summary
  "Get a summary of the local trace for display."
  [trace-ctx]
  (when (:local? trace-ctx)
    (let [events @(:events trace-ctx)
          nodes (->> events
                     (filter #(#{"span-create" "generation-create"} (:type %)))
                     (map (fn [e]
                            (let [body (:body e)]
                              {:name (:name body)
                               :type (:type e)
                               :status (get-in body [:metadata :status])
                               :duration-ms (when-let [start (:startTime body)]
                                              (when-let [end (:endTime body)]
                                                ;; Approximate - would need proper time parsing
                                                nil))}))))]
      {:trace-id (:trace-id trace-ctx)
       :node-count (count nodes)
       :nodes nodes})))

;; =============================================================================
;; Internal Trace Collection (Always-On for Event Storage)
;; =============================================================================

(defn create-internal-trace
  "Create an internal trace collector for event storage.
   This always collects traces for storing in the event store,
   independent of Langfuse tracing."
  []
  {:trace-id (random-uuid)
   :node-traces (atom [])
   :started-at nil
   :completed-at nil})

(defn start-internal-trace!
  "Mark the start time for internal trace collection."
  [internal-trace]
  (assoc internal-trace :started-at (time/now)))

(defn record-node-trace!
  "Record a node trace entry for internal storage.

   Args:
     internal-trace - The internal trace context
     node-trace - Map with:
       :node-id, :node-name, :node-type
       :parent-id, :path, :child-index
       :status, :started-at, :completed-at, :duration-ms
       :inputs, :outputs, :error"
  [internal-trace node-trace]
  (swap! (:node-traces internal-trace) conj node-trace))

(defn complete-internal-trace!
  "Mark the completion time and return the finalized trace."
  [internal-trace status error]
  (assoc internal-trace
         :completed-at (time/now)
         :status status
         :error error))

(defn get-node-traces
  "Get all collected node traces."
  [internal-trace]
  @(:node-traces internal-trace))
