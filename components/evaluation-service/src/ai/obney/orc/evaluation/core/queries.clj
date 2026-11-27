(ns ai.obney.orc.evaluation.core.queries
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.evaluation.core.logic :as logic]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.orc.orc-service.interface :as orc]
            [com.brunobonacci.mulog :as u]))

;; --- Assessments ---

(defn get-assessments
  "The assessments of the tenant, oldest request first, optionally narrowed by
   any of `:sheet-id :node-id :tick-id :judge-name :status`. Each entry is the
   assessment's current state: its identity (`:assessment-id`,
   `:subject-completion-id`, `:judge-name`, `:judge-revision-number`), its
   `:status` (:pending until it ends :scored, :failed or :ungradable), and the
   outcome's own fields (`:band :score :feedback :dimensions` when scored,
   `:reason :message` otherwise, `:model-provenance`)."
  [ctx filters]
  (let [wanted (select-keys filters [:sheet-id :node-id :tick-id :judge-name :status])]
    (->> (vals (rmp/project ctx :evaluation/assessments))
         (filter (fn [entry] (every? (fn [[k v]] (= v (get entry k))) wanted)))
         (sort-by (juxt :requested-at :assessment-id))
         vec)))

;; --- Performance ---

(defn- cells [ctx]
  (vals (:cells (rmp/project ctx :evaluation/performance))))

(defn cell
  "The cell of `cell-key` (see `cell-key`), or nil."
  [ctx cell-key]
  (get-in (rmp/project ctx :evaluation/performance) [:cells cell-key]))

(defn get-node-performance
  "The performance of a node, version by version. `{:sheet-id :node-id}` are
   required; `:node-version` and `:judge-name` narrow it. Returns
   `{:sheet-id :node-id :versions [{:node-version :first-seen :last-seen :judges [stats]}]
     :rollup [stats]}`: the versions oldest first, each with one stats entry per judge
   and judge revision, and the rollup merging each judge revision across the listed
   versions. Stats are described in `stats`; `:window` sets the trailing window."
  [ctx {:keys [sheet-id node-id window] :as query}]
  (let [window (or window logic/default-window)
        cs (logic/matching query (cells ctx))
        judge-order (juxt :judge-name :judge-revision-number)
        versions (->> cs
                      (group-by :node-version)
                      (map (fn [[v group]]
                             {:node-version v
                              :version-number (:version-number (first group))
                              :first-seen (first (sort (map :first-seen group)))
                              :last-seen (last (sort (map :last-seen group)))
                              :judges (->> (sort-by judge-order group)
                                           (mapv #(logic/stats % window)))}))
                      (sort-by (juxt :first-seen (comp str :node-version)))
                      vec)
        rollup (->> cs
                    (map logic/chronological)
                    (group-by judge-order)
                    (sort-by key)
                    (mapv (fn [[_ group]] (logic/stats (logic/merge-cells group) window))))]
    {:sheet-id sheet-id :node-id node-id :versions versions :rollup rollup}))

(defn get-low-performing
  "The node versions performing below `:below` (required): each judge revision of
   each node version whose trailing mean is under it, worst first. `:sheet-id`
   narrows the search; `:min-coverage` (default 0) leaves out cells whose coverage
   is lower, since a thin grade is not evidence of poor performance."
  [ctx {:keys [below min-coverage window] :as query}]
  (let [window (or window logic/default-window)
        min-coverage (or min-coverage 0.0)]
    (->> (logic/matching (select-keys query [:sheet-id]) (cells ctx))
         (map #(logic/stats % window))
         (filter (fn [s] (and (some? (:trailing-mean s))
                              (< (:trailing-mean s) below)
                              (>= (:coverage s) min-coverage))))
         (sort-by (juxt :trailing-mean :judge-name))
         vec)))

(defn get-performance-trend
  "The ordered outcomes of a node's judge, oldest first: the most recent `:window`
   (default `default-window`) of `{:assessment-id :status :score :band :at
   :node-version :judge-revision-number}`. `:sheet-id :node-id :judge-name` are
   required; `:node-version` narrows to one version."
  [ctx {:keys [window] :as query}]
  (let [window (or window logic/default-window)]
    (->> (logic/matching query (cells ctx))
         (mapcat (fn [c] (map #(assoc % :node-version (:node-version c)
                                      :judge-revision-number (:judge-revision-number c))
                              (:series c))))
         (sort-by (juxt :at (comp str :assessment-id)))
         (take-last window)
         vec)))

(defn get-assessment-report
  "Coverage and grades over an explicit set of assessments, named by
   `{:assessment-ids [...]}` or `{:subject-ids [...]}` (the completions assessed).
   Separated by judge and judge revision, each with its counts, coverage, band
   counts and the mean of the scored. An id naming no assessment is simply absent
   from the report."
  [ctx {:keys [assessment-ids subject-ids]}]
  (let [wanted-a (set assessment-ids)
        wanted-s (set subject-ids)
        entries (filter (fn [e] (or (contains? wanted-a (:assessment-id e))
                                    (contains? wanted-s (:subject-completion-id e))))
                        (vals (rmp/project ctx :evaluation/assessments)))]
    (->> entries
         (group-by (juxt :judge-name :judge-revision-number))
         (sort-by key)
         (mapv (fn [[[judge-name revision] group]]
                 (let [by (group-by :status group)
                       scored (get by :scored)
                       total (count group)]
                   {:judge-name judge-name
                    :judge-revision-number revision
                    :total total
                    :scored (count scored)
                    :failed (count (get by :failed))
                    :ungradable (count (get by :ungradable))
                    :pending (count (get by :pending))
                    :coverage (/ (double (count scored)) total)
                    :mean-score (logic/mean (keep :score scored))
                    :band-distribution (frequencies (keep :band scored))}))))))

;; --- Trace extraction ---

(defn tick-node-io
  "Rehydrate per-node input/output VALUES for one trace.

   :sheet/execution-traced records only the shape of each node's I/O
   (:read-keys, :write-keys, size profiles); the values live in the tick's
   :sheet/execution-value-written events. A trace-id IS the tick-id (see
   assemble-execution-trace), so one tagged read gets everything.

   Returns {[node-id exec-context] {:inputs {..} :outputs {..}}} — keyed by
   EXECUTION, not by node-id. Two rules drive that:

   - A node-id is not unique within a tick: map-each runs the same child once
     per item, so filing under a bare node-id makes N iterations overwrite
     each other.
   - A key is not unique within a tick either, so reads resolve through
     :read-sources — the write the node actually saw — rather than by key
     name, which yields the last write even if it landed after the node
     finished."
  [{:keys [event-store tenant-id] :as ctx} trace-id node-traces]
  (try
    (let [events (into [] (es/read
                           event-store
                           (cond-> {:tags #{[:tick trace-id]}}
                             tenant-id (assoc :tenant-id tenant-id))))
          completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
          by-execution (reduce (fn [acc c] (assoc acc (orc/value-log-execution-key c) c))
                               {}
                               completions)]
      (into {}
            (for [nt node-traces
                  :let [k [(:node-id nt) (or (:exec-context nt) {})]
                        c (get by-execution k)]
                  :when c]
              [k {:inputs (orc/value-log-resolve-reads ctx tenant-id trace-id c)
                  :outputs (orc/value-log-resolve-writes ctx tenant-id trace-id c)}])))
    (catch Exception _ {})))

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
  (let [query (cond-> {:types logic/trace-events :tenant-id tenant-id}
                sheet-id (assoc :tags #{[:sheet sheet-id]})
                since (assoc :after since))
        events (es/read event-store query)
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
                        :researcher-iterations (:researcher-iterations event)
                        :researcher-events (:researcher-events event)
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
  (let [
        raw-traces (get-traces-raw ctx {:sheet-id sheet-id
                                        :since since})
        node-events-data (when sheet-id
                           (es/read event-store
                                             {:types logic/node-events
                                              :tags #{[:sheet sheet-id]}
                                              :tenant-id tenant-id}))
        nodes-map (logic/build-nodes-map node-events-data)

        io-by-trace (into {}
                          (for [st raw-traces]
                            [(:trace-id st)
                             (tick-node-io ctx (:trace-id st) (:node-traces st))]))

        results (for [sheet-trace raw-traces
                      node-trace (:node-traces sheet-trace)
                      :let [filtered (logic/filter-node-traces [node-trace]
                                                         {:node-id node-id
                                                          :node-name node-name
                                                          :llm-only? true
                                                          :nodes-map nodes-map})]
                      :when (seq filtered)]
                  (logic/extract-node-trace-data
                   sheet-trace
                   (first filtered)
                   (get nodes-map (:node-id node-trace))
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
        by-node (group-by :node-id traces)
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

;; --- Judges ---

(defn living-description-enabled?
  "The optional ontology read model enables default judges. Explicitly
   attached judges also work in installations without that model."
  [ctx]
  (let [model :ontology/living-description-enabled]
    (when-not (contains? (rmp/global-read-model-registry) model)
      (try (integrations/load-provider! :ontology)
           (catch Throwable cause
             (u/log :evaluation/optional-ontology-unavailable :exception cause))))
    (boolean (and (contains? (rmp/global-read-model-registry) model)
                  (:enabled? (rmp/project ctx model))))))

(defn get-effective-judges-for-node
  "Return the effective judge list for a node — `[{:judge-name :judge-config}...]`.

   Resolution order:
   1. Node has explicit :judges field present (consumer called
      :sheet/set-node-judges, even with []): look up each judge name's
      config from the sheet's :judges read-model. Returns the union.
   2. Else if node type is :repl-researcher AND the Living Description opt-in
      flag is on (the flag gates ONLY these defaults, never an attachment):
      return the default-judges set.
   3. Else: empty vector.

   The 4-arg arity filters by Gap-7's :applies-to-completion-kinds. A
   judge config carrying e.g. `:applies-to-completion-kinds #{:terminal}`
   is excluded from the effective list when the resolver is called
   with completion-kind `:tree-iteration` — or, per CJ-5b, with a nil
   completion-kind (a :blocked completion carries no kind). Backwards-compat:
   the 3-arg arity is UNFILTERED (returns every effective judge regardless
   of kind — the 'what judges are attached' query), and judges WITHOUT
   :applies-to-completion-kinds always apply.

   CJ-5b: the 3-arg 'unfiltered' intent and a real event's nil kind used to
   collapse to the same `nil` argument, so making applies-to? exclude nil
   would have broken the 3-arg query. We disambiguate with an explicit
   ::all-kinds sentinel: the 3-arg arity passes it (skip filtering), while
   the 4-arg arity from `on-node-execution-completed` passes the event's
   real (possibly nil) kind (filter — nil excludes kind-scoped judges).

   Public — callers can query 'what judges WILL run for this node'."
  ([ctx sheet-id node-id]
   (get-effective-judges-for-node ctx sheet-id node-id :ai.obney.orc.evaluation.core.judge-runtime/all-kinds))
  ([ctx sheet-id node-id completion-kind]
   (let [node (when (and sheet-id node-id) (orc/get-node ctx sheet-id node-id))
         all-effective
         (cond
           (nil? node)
           []

           (contains? node :judges)
           (let [sheet-judges (orc/get-judges ctx sheet-id)]
             (vec
               (keep (fn [judge-name]
                       (when-let [jc (get sheet-judges judge-name)]
                         {:judge-name judge-name
                          :judge-config jc}))
                     (:judges node))))

           (and (= :repl-researcher (:type node))
                (living-description-enabled? ctx))
           logic/default-judges

           :else [])]
     (if (= :ai.obney.orc.evaluation.core.judge-runtime/all-kinds completion-kind)
       all-effective
       (filterv #(logic/applies-to? (:judge-config %) completion-kind)
                all-effective)))))

(defn get-judge-scores
  "Return the vector of judge score entries (the learning loops' record) for the
   given (sheet-id, node-id, tick-id) tuple, in emission order. Empty vector if
   no learning judge scored for that tick. Entries carry the score, feedback,
   dimensions and :model-provenance (absent for a judge that makes no model
   call). The band, revision and assessment of a score are on its
   `:judge/score-emitted` event and in `:evaluation/assessments`."
  [ctx sheet-id node-id tick-id]
  (or (get (rmp/project ctx :evaluation/judge-scores)
           [sheet-id tick-id node-id])
      []))
