(ns ai.obney.orc.evaluation.core.trace-extraction
  "Utilities for extracting LLM node traces for evaluation.

   This namespace provides functions to query historical sheet executions
   and extract the relevant data needed for LLM-as-judge evaluation.

   Key functions:
   - get-llm-traces: Query traces for specific LLM nodes
   - extract-trace-data: Transform raw trace into evaluation format"
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.orc.orc-service.core.value-log :as value-log]))

;; =============================================================================
;; Event Types (from orc-service)
;; =============================================================================

(def trace-events
  "Events that contain execution traces"
  #{:sheet/execution-traced})

(def node-events
  "Events that contain node definitions"
  #{:sheet/node-created
    :sheet/node-executor-set
    :sheet/node-instruction-set
    :sheet/node-io-set})

;; =============================================================================
;; Node Metadata Extraction
;; =============================================================================

(defn- build-nodes-map
  "Build a map of node-id -> node metadata from events"
  [events]
  (reduce
   (fn [state event]
     (case (:event/type event)
       :sheet/node-created
       (assoc state (:node-id event)
              {:node-id (:node-id event)
               :sheet-id (:sheet-id event)
               :type (:type event)
               :name nil
               :instruction nil
               :executor nil
               :model nil
               :reads []
               :writes []})

       :sheet/node-executor-set
       (-> state
           (assoc-in [(:node-id event) :executor] (:executor event))
           (assoc-in [(:node-id event) :model] (:model event)))

       :sheet/node-instruction-set
       (assoc-in state [(:node-id event) :instruction] (:instruction event))

       :sheet/node-io-set
       (-> state
           (assoc-in [(:node-id event) :reads] (:reads event))
           (assoc-in [(:node-id event) :writes] (:writes event)))

       state))
   {}
   events))

;; =============================================================================
;; Trace Extraction
;; =============================================================================

(def ^:private llm-node-types
  "Node TYPES that call an LLM by definition, whatever their executor."
  #{:llm-condition :repl-researcher})

(defn- is-llm-node?
  "Does this node trace represent an LLM call?

   Two independent axes decide it, and conflating them is why this returned
   false for every node in the log:

     :node-type — what kind of node (:leaf, :map-each, :repl-researcher …).
                  :llm-condition and :repl-researcher call an LLM by
                  definition.
     :executor  — how a LEAF runs (:ai, :code, :tool). A leaf is :leaf
                  whether it calls a model or runs Clojure; only :executor
                  distinguishes them.

   The previous version tested `:executor` against node-TYPE values
   (:llm, :llm-condition, :repl-researcher). :llm is not a member of either
   enum, and the executor enum is :ai/:code/:tool — so the set could never
   match, and `llm-only?` emptied every result.

   `node-metadata` is the entry from build-nodes-map, used as a fallback for
   traces written before :executor was carried on the node trace."
  ([node-trace] (is-llm-node? node-trace nil))
  ([node-trace node-metadata]
   (let [executor (or (:executor node-trace) (:executor node-metadata))
         node-type (or (:node-type node-trace) (:type node-metadata))]
     (boolean (or (= :ai executor)
                  (contains? llm-node-types node-type))))))

(defn- extract-node-trace-data
  "Transform a raw node trace into evaluation format.

   Returns:
     {:trace-id UUID
      :sheet-id UUID
      :node-id UUID
      :node-name string
      :inputs map - the inputs provided to the node
      :outputs map - the outputs produced by the node
      :instruction string - the instruction/prompt used
      :model string - the model used
      :duration-ms int
      :status keyword}"
  [sheet-trace node-trace node-metadata io]
  {:trace-id (:trace-id sheet-trace)
   :sheet-id (:sheet-id sheet-trace)
   :node-id (:node-id node-trace)
   :node-name (or (:node-name node-trace) (:name node-metadata) "unknown")
   ;; Values are rehydrated from the tick's events (see tick-node-io): the
   ;; trace stores only the shape of each node's I/O. Judges need the real
   ;; values for grounding, so an empty map here would silently degrade
   ;; every grounding score rather than fail loudly.
   :inputs (or (:inputs io) {})
   :outputs (or (:outputs io) {})
   :instruction (or (:instruction node-trace) (:instruction node-metadata) "")
   :model (or (:model node-trace) (:model node-metadata))
   :duration-ms (:duration-ms node-trace)
   :status (:status node-trace)
   :executed-at (:started-at sheet-trace)})

(defn tick-node-io
  "Rehydrate per-node input/output VALUES for one trace.

   :sheet/execution-traced records only the shape of each node's I/O
   (:read-keys, :write-keys, size profiles). The values live in the tick's
   :sheet/execution-value-written events — the canonical record of every
   write — and storing them in the trace as well made it the largest event
   type in the log.

   A trace-id IS the tick-id (see assemble-execution-trace), so one tagged
   read gets everything.

   Returns {[node-id exec-context] {:inputs {..} :outputs {..}}} — keyed by
   EXECUTION, not by node-id. Two correctness rules drive that:

   - A node-id is not unique within a tick. map-each runs the same child once
     per item, so filing results under a bare node-id makes N iterations
     overwrite each other and every one of them is served the last one's I/O.
   - A key is not unique within a tick either. Reads therefore resolve through
     :read-sources — the id of the write event the node actually saw — rather
     than by key name, which would yield the last write to that key even if it
     happened after this node finished."
  [{:keys [event-store tenant-id]} trace-id node-traces]
  (try
    (let [events (into [] (event-store/read
                           event-store
                           (cond-> {:tags #{[:tick trace-id]}}
                             tenant-id (assoc :tenant-id tenant-id))))
          writes (filter #(= :sheet/execution-value-written (:event/type %)) events)
          ;; Exact resolution: write event id -> value.
          by-event-id (reduce (fn [acc e] (assoc acc (:event/id e) (:value e))) {} writes)
          ;; Values seeded into the tick have no write event; they arrive on
          ;; tree-tick-started :inputs. Fallback source for reads of keys the
          ;; tick was given rather than produced.
          ;; Keys are normalized here as well as at the emission site: a node
          ;; declares its :reads as simple keywords, so a seeded key stored as
          ;; the string "student-analysis" would never match :student-analysis
          ;; and the read would be dropped. Normalizing on both sides keeps
          ;; events written before that fix resolvable.
          seeded (reduce-kv (fn [acc k v] (assoc acc (if (string? k) (keyword k) k) v))
                            {}
                            (or (some #(when (= :sheet/tree-tick-started (:event/type %))
                                         (:inputs %))
                                      events)
                                {}))
          ;; Last-write-wins, used ONLY as a final fallback for events that
          ;; predate :read-sources.
          latest (value-log/latest-values events)
          completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
          ;; Keyed by execution, so map-each iterations stay distinct.
          by-execution (reduce (fn [acc c] (assoc acc (value-log/execution-key c) c))
                               {}
                               completions)
          ;; Values seeded FOR a specific node execution (map-each items).
          ;; Kept separate from that execution's own outputs — a child that
          ;; reads and writes the same key would otherwise resolve its input
          ;; to its own result.
          input-seeds (value-log/input-seeds-by-iteration events)
          resolve-reads
          (fn [c]
            (let [ek (value-log/execution-key c)
                  ;; The ITERATION this execution belongs to, if any. Shared by
                  ;; every node inside it regardless of node-id — which is why
                  ;; the item lookup keys on this and not on the node.
                  iter (value-log/exec-context (:inputs c))
                  sources (:read-sources c)
                  ;; Ordered candidates, most specific first. Each step is a
                  ;; FALLTHROUGH, not a terminal branch: a miss moves on rather
                  ;; than dropping the key. Treating any of these as terminal
                  ;; silently loses reads — which is how 172 of them vanished.
                  resolve-one
                  (fn [k]
                    (or ;; 1. A value seeded FOR this execution — exact for
                        ;;    map-each items, where every iteration shares the
                        ;;    key and the blackboard slot holds only the last.
                        (get-in input-seeds [iter k])
                        ;; 2. The specific write this node recorded reading.
                        (some->> (get sources k) (get by-event-id))
                        ;; 3. Seeded into the tick (no write event exists).
                        (get seeded k)
                        ;; 4. Last write to the key. Ambiguous when a key is
                        ;;    written more than once, but better than nothing
                        ;;    and the only option for events predating (2).
                        (get latest k)))]
              (into {}
                    (for [k (:read-keys c)
                          :let [v (resolve-one k)]
                          :when (some? v)]
                      [k v]))))]
      (into {}
            (for [nt node-traces
                  :let [k [(:node-id nt) (or (:exec-context nt) {})]
                        c (get by-execution k)]
                  :when c]
              [k {:inputs (resolve-reads c)
                  :outputs (value-log/writes-for events c)}])))
    (catch Exception _ {})))

(defn- filter-node-traces
  "Filter node traces from a sheet trace based on criteria.

   Options:
     :node-id - Filter to specific node ID
     :node-name - Filter by node name (substring match)
     :executor - Filter by executor type (:ai, :code, :tool)
     :llm-only? - Only include LLM nodes
     :nodes-map - Optional {node-id -> node metadata}, used to resolve
                  :executor for traces that predate it being carried on the
                  node trace itself"
  [node-traces {:keys [node-id node-name executor llm-only? nodes-map]}]
  (cond->> node-traces
    llm-only? (filter #(is-llm-node? % (get nodes-map (:node-id %))))
    node-id (filter #(= node-id (:node-id %)))
    node-name (filter #(and (:node-name %)
                            (.contains (str (:node-name %)) node-name)))
    executor (filter #(= executor (:executor %)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn get-traces-raw
  "Get raw sheet execution traces.

   Args:
     event-store: The grain event store
     options: Map with optional keys:
       :sheet-id - Filter to specific sheet
       :since - Only traces after this timestamp (ISO string)
       :limit - Maximum number of traces to return

   Returns:
     Vector of raw trace maps from :sheet/execution-traced events"
  [{:keys [event-store tenant-id] :as ctx} {:keys [sheet-id since limit]}]
  (let [query (cond-> {:types trace-events :tenant-id tenant-id}
                sheet-id (assoc :tags #{[:sheet sheet-id]})
                since (assoc :after since))
        events (event-store/read event-store query)
        traces (mapv (fn [event]
                       {:trace-id (:trace-id event)
                        :sheet-id (:sheet-id event)
                        :version-number (:version-number event)
                        :started-at (:started-at event)
                        :completed-at (:completed-at event)
                        :duration-ms (:duration-ms event)
                        :status (:status event)
                        :input-snapshot (:input-snapshot event)
                        :output-snapshot (:output-snapshot event)
                        :node-traces (:node-traces event)
                        :error (:error event)})
                     events)]
    (if limit
      (vec (take limit (sort-by :started-at #(compare %2 %1) traces)))
      traces)))

(defn get-llm-traces
  "Extract LLM node traces for evaluation.

   This is the main entry point for getting evaluation data.

   Args:
     event-store: The grain event store
     options: Map with keys:
       :sheet-id - (required) The sheet to query traces from
       :node-id - (optional) Filter to specific node
       :node-name - (optional) Filter by node name (substring match)
       :since - (optional) Only traces after this timestamp
       :limit - (optional) Maximum number of traces to return

   Returns:
     Vector of maps, each containing:
       :trace-id - Unique trace identifier
       :sheet-id - Sheet that was executed
       :node-id - The LLM node identifier
       :node-name - Human-readable node name
       :inputs - Map of inputs provided to the node
       :outputs - Map of outputs produced
       :instruction - The prompt/instruction used
       :model - The LLM model used
       :duration-ms - Execution time
       :status - :success or :failure
       :executed-at - Timestamp of execution

   Example:
     (get-llm-traces event-store
       {:sheet-id my-sheet-id
        :node-name \"analyze-lead\"
        :limit 50})"
  [{:keys [event-store tenant-id] :as ctx} {:keys [sheet-id node-id node-name since limit] :as options}]
  (let [;; Get raw traces
        raw-traces (get-traces-raw ctx {:sheet-id sheet-id
                                        :since since})
        ;; Get node metadata for enrichment
        node-events-data (when sheet-id
                           (event-store/read event-store
                                             {:types node-events
                                              :tags #{[:sheet sheet-id]}
                                              :tenant-id tenant-id}))
        nodes-map (build-nodes-map node-events-data)

        ;; Rehydrate I/O values once per trace, not once per node.
        io-by-trace (into {}
                          (for [st raw-traces]
                            [(:trace-id st)
                             (tick-node-io ctx (:trace-id st) (:node-traces st))]))

        ;; Extract and filter node traces
        results (for [sheet-trace raw-traces
                      node-trace (:node-traces sheet-trace)
                      :let [filtered (filter-node-traces [node-trace]
                                                         {:node-id node-id
                                                          :node-name node-name
                                                          :llm-only? true
                                                          :nodes-map nodes-map})]
                      :when (seq filtered)]
                  (extract-node-trace-data
                   sheet-trace
                   (first filtered)
                   (get nodes-map (:node-id node-trace))
                   ;; Keyed by EXECUTION — a bare node-id would hand every
                   ;; map-each iteration the LAST iteration's I/O.
                   (get-in io-by-trace [(:trace-id sheet-trace)
                                        [(:node-id node-trace)
                                         (or (:exec-context node-trace) {})]])))]
    (if limit
      (vec (take limit results))
      (vec results))))

(defn get-node-stats
  "Get basic statistics for LLM node executions.

   Args:
     event-store: The grain event store
     options: Map with keys:
       :sheet-id - (required) The sheet to analyze
       :node-ids - (optional) Vector of specific node IDs to analyze

   Returns:
     Vector of maps, one per node:
       :node-id - Node identifier
       :node-name - Human-readable name
       :execution-count - Total executions
       :success-count - Successful executions
       :failure-count - Failed executions
       :success-rate - Ratio of successes
       :avg-duration-ms - Average execution time"
  [ctx {:keys [sheet-id node-ids]}]
  (let [traces (get-llm-traces ctx {:sheet-id sheet-id})
        ;; Group by node
        by-node (group-by :node-id traces)
        ;; Filter to requested nodes if specified
        by-node (if node-ids
                  (select-keys by-node node-ids)
                  by-node)]
    (mapv (fn [[node-id traces]]
            (let [total (count traces)
                  successes (count (filter #(= :success (:status %)) traces))
                  failures (- total successes)
                  durations (keep :duration-ms traces)
                  avg-duration (when (seq durations)
                                 (/ (reduce + durations) (count durations)))]
              {:node-id node-id
               :node-name (:node-name (first traces))
               :execution-count total
               :success-count successes
               :failure-count failures
               :success-rate (if (pos? total) (/ successes total) 0.0)
               :avg-duration-ms (or avg-duration 0)}))
          by-node)))

(defn format-trace-for-evaluation
  "Format a trace for input to evaluation judges.

   Transforms extracted trace data into the format expected by judges:
   - Converts inputs/outputs to strings if needed
   - Extracts response text from common output patterns
   - Ensures instruction is present

   Args:
     trace: A trace map from get-llm-traces

   Returns:
     Map with:
       :inputs - JSON string or map of inputs
       :response - The LLM response text
       :instruction - The instruction/prompt used"
  [{:keys [inputs outputs instruction]}]
  (let [;; Try to extract a primary response from outputs
        ;; Common patterns: {:response "..."}, {:answer "..."}, {:output "..."}
        response (or (:response outputs)
                     (:answer outputs)
                     (:output outputs)
                     ;; If no specific response field, use full outputs
                     outputs)]
    {:inputs inputs
     :response response
     :instruction (or instruction "No instruction provided")}))
