(ns ai.obney.orc.orc-service.core.queries
  "Behavior Tree Sheet query handlers.

   Fat Query Model: One query per screen, each returns all data needed.
   All queries return {:query/result ...} on success."
  (:require [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.tree-layout :as layout]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [clojure.set]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Auth Predicate
;; =============================================================================

(defn authenticated?
  "Authorization predicate - checks if auth-claims are present in context."
  [ctx]
  (some? (:auth-claims ctx)))

;; =============================================================================
;; Snapshot Parsing Helpers (for published mode display)
;; =============================================================================

(defn- flatten-node-tree
  "Convert nested tree structure back to flat list of nodes.
   Preserves existing IDs if present, otherwise generates new ones.
   Returns {:nodes-by-id {...} :root-id uuid}."
  ([tree-node] (flatten-node-tree tree-node nil (atom {})))
  ([tree-node parent-id nodes-atom]
   (when tree-node
     (let [node-id (or (:id tree-node) (random-uuid))
           children (:children tree-node)
           child-results (mapv #(flatten-node-tree % node-id nodes-atom) children)
           children-ids (vec (keep :node-id child-results))
           base-node {:id node-id
                      :type (:type tree-node)
                      :name (:name tree-node)
                      :status :idle
                      :parent-id parent-id
                      :children-ids children-ids}
           node (cond-> base-node
                  ;; Leaf fields
                  (:executor tree-node) (assoc :executor (:executor tree-node))
                  (:model tree-node) (assoc :model (:model tree-node))
                  (:fn tree-node) (assoc :fn (:fn tree-node))
                  (:instruction tree-node) (assoc :instruction (:instruction tree-node))
                  (seq (:reads tree-node)) (assoc :reads (:reads tree-node))
                  (seq (:writes tree-node)) (assoc :writes (:writes tree-node))
                  (:retry tree-node) (assoc :retry (:retry tree-node))
                  ;; Condition fields
                  (:check tree-node) (assoc :check (:check tree-node))
                  (:on-fail tree-node) (assoc :on-fail (:on-fail tree-node))
                  ;; Parallel fields
                  (:success-policy tree-node) (assoc :success-policy (:success-policy tree-node))
                  (:failure-policy tree-node) (assoc :failure-policy (:failure-policy tree-node))
                  ;; Map-each fields
                  (:source-key tree-node) (assoc :source-key (:source-key tree-node))
                  (:item-key tree-node) (assoc :item-key (:item-key tree-node))
                  (:output-key tree-node) (assoc :output-key (:output-key tree-node))
                  (:max-concurrency tree-node) (assoc :max-concurrency (:max-concurrency tree-node))
                  (some? (:preserve-failures? tree-node)) (assoc :preserve-failures? (:preserve-failures? tree-node)))]
       (swap! nodes-atom assoc node-id node)
       {:node-id node-id :nodes-atom nodes-atom}))))

(defn- snapshot->nodes
  "Convert a snapshot's nested tree to a flat list of nodes."
  [snapshot]
  (if-let [tree (:nodes snapshot)]
    (let [nodes-atom (atom {})
          result (flatten-node-tree tree nil nodes-atom)
          nodes-by-id @nodes-atom]
      {:nodes (vec (vals nodes-by-id))
       :nodes-by-id nodes-by-id
       :root-id (:node-id result)})
    {:nodes [] :nodes-by-id {} :root-id nil}))

(defn- snapshot->blackboard
  "Convert a snapshot's blackboard-schema to a blackboard list."
  [snapshot]
  (mapv (fn [[k schema]]
          {:key k
           :schema schema
           :value nil})  ;; Published snapshots don't store runtime values
        (:blackboard-schema snapshot)))

;; =============================================================================
;; Sheets List Screen (/sheets)
;; =============================================================================

(defquery :sheet sheets-list-screen
  {:authorized? authenticated?}
  "Fat query for sheets list screen.
   Returns all sheets with basic metadata."
  [{:keys [event-store] :as ctx}]
  (let [sheets (rm/get-sheets-all ctx)]
    {:query/result
     {:sheets (vec sheets)
      :total (count sheets)}}))

;; =============================================================================
;; Sheet View Screen (/sheet?sheet-id=...)
;; =============================================================================

(defquery :sheet sheet-view-screen
  {:authorized? authenticated?}
  "Fat query for sheet view screen.
   Returns sheet metadata, all nodes, blackboard, layout, current tick, and version info.
   When execution-mode is :published, returns the published snapshot's nodes/blackboard."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [execution-mode (or (:execution-mode sheet) :draft)
            published-version (:published-version sheet)
            ;; Determine if we should show published snapshot
            use-published? (and (= :published execution-mode) published-version)
            ;; Get the data source based on mode
            {:keys [nodes nodes-by-id blackboard root-id]}
            (if use-published?
              ;; Load from published snapshot
              (let [version (rm/get-version ctx sheet-id published-version)
                    snapshot (:snapshot version)
                    parsed (snapshot->nodes snapshot)]
                {:nodes (:nodes parsed)
                 :nodes-by-id (:nodes-by-id parsed)
                 :blackboard (snapshot->blackboard snapshot)
                 :root-id (:root-id parsed)})
              ;; Load from draft (current state)
              (let [draft-nodes (rm/get-nodes-for-sheet ctx sheet-id)
                    draft-nodes-by-id (into {} (map (juxt :id identity) draft-nodes))]
                {:nodes draft-nodes
                 :nodes-by-id draft-nodes-by-id
                 :blackboard (rm/get-blackboard-for-sheet ctx sheet-id)
                 :root-id (:root-node-id sheet)}))
            root-node (when root-id (get nodes-by-id root-id))
            tree-layout (if root-node
                          (layout/compute-layout root-node nodes-by-id)
                          [])
            current-tick (rm/get-current-tick ctx sheet-id)
            ;; Get in-progress execution context if there's an active tick
            execution-context (when current-tick
                               (rm/get-execution-context ctx (:id current-tick)))
            version-info {:published-version published-version
                          :draft-dirty? (boolean (:draft-dirty? sheet))
                          :execution-mode execution-mode
                          :has-stash? (boolean (:has-stash? sheet))}
            ;; Update sheet's root-node-id to match the data source
            sheet-for-response (if (and use-published? root-id)
                                 (assoc sheet :root-node-id root-id)
                                 sheet)
            ;; Build progress info from execution context
            progress-info (when execution-context
                           {:current-node-id (:current-node-id execution-context)
                            :nodes-in-progress (vec (:nodes-in-progress execution-context))
                            :sequence-progress (:sequence-progress execution-context)
                            :map-each-progress (:map-each-progress execution-context)})]
        {:query/result
         (cond-> {:sheet sheet-for-response
                  :nodes (vec nodes)
                  :blackboard (vec blackboard)
                  :layout tree-layout
                  :version-info version-info}
           current-tick (assoc :tick current-tick)
           progress-info (assoc :progress progress-info))}))))

;; =============================================================================
;; Export Sheet Query
;; =============================================================================

(defn- build-node-tree
  "Convert flat nodes map to nested tree structure for export.
   Preserves original node IDs for accurate structural diffing."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:id (:id node)
                 :type (:type node)
                 :name (:name node)}
          ;; Leaf-specific fields
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:fn node) (assoc :fn (:fn node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:retry node) (assoc :retry (:retry node))))
          ;; Condition-specific
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))
                   (:on-fail node) (assoc :on-fail (:on-fail node))))
          ;; LLM-condition-specific
          (= :llm-condition (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (:model node) (assoc :model (:model node))))
          ;; Parallel-specific
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          ;; Map-each-specific
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))
                   (some? (:preserve-failures? node)) (assoc :preserve-failures? (:preserve-failures? node))))
          ;; Children for composite nodes
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(build-node-tree nodes-by-id %) (:children-ids node)))))))))

(defquery :sheet export-sheet
  {:authorized? authenticated?}
  "Export a sheet as a portable EDN structure for download/backup."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [nodes (rm/get-nodes-by-id ctx sheet-id)
            blackboard (rm/get-blackboard-for-sheet ctx sheet-id)]
        {:query/result
         {:version 1
          :exported-at (str (time/now))
          :sheet {:name (:name sheet)
                  :id sheet-id}
          :blackboard-schema (into {}
                                   (map (fn [bb] [(:key bb) (:schema bb)])
                                        blackboard))
          :nodes (build-node-tree nodes (:root-node-id sheet))}}))))

;; =============================================================================
;; Versioning Queries
;; =============================================================================

(defquery :sheet version-history
  {:authorized? authenticated?}
  "Get all published versions for a sheet."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [versions (rm/get-versions-for-sheet ctx sheet-id)]
        {:query/result
         {:versions (vec versions)
          :current-published-version (:published-version sheet)
          :draft-dirty? (boolean (:draft-dirty? sheet))
          :has-stash? (boolean (:has-stash? sheet))}}))))

(defquery :sheet get-version
  {:authorized? authenticated?}
  "Get a specific published version by version number."
  [{{:keys [sheet-id version-number]} :query
    :keys [event-store] :as ctx}]
  (let [version (rm/get-version ctx sheet-id version-number)]
    (if-not version
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " version-number " not found")}
      {:query/result
       {:version version}})))

(defquery :sheet get-stash
  {:authorized? authenticated?}
  "Get the stashed draft for a sheet, if any."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [stash (rm/get-stash ctx sheet-id)]
        {:query/result
         {:stash stash}}))))

;; =============================================================================
;; Execution Traces
;; =============================================================================

(defquery :sheet get-trace
  {:authorized? authenticated?}
  "Get a single execution trace by ID."
  [{{:keys [trace-id]} :query
    :keys [event-store] :as ctx}]
  (let [trace (rm/get-trace ctx trace-id)]
    (if-not trace
      {::anom/category ::anom/not-found
       ::anom/message "Trace not found"}
      {:query/result trace})))

(defquery :sheet get-traces
  {:authorized? authenticated?}
  "Get execution traces for a sheet with optional filtering.

   Query params:
     :sheet-id - Required. The sheet to get traces for.
     :version-number - Optional. Filter by specific version (nil = draft).
     :status - Optional. Filter by status (:success, :failure, :timeout).
     :node-id - Optional. Filter to traces that executed this node.
     :since - Optional. Filter to traces after this time.
     :limit - Optional. Max traces to return (default 100)."
  [{{:keys [sheet-id version-number status node-id since limit]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [all-traces (rm/get-traces-for-sheet ctx sheet-id)
            ;; Apply filters
            filtered (cond->> all-traces
                       ;; Filter by version (nil matches nil, number matches number)
                       (some? version-number)
                       (filter #(= version-number (:version-number %)))

                       ;; Filter by status
                       status
                       (filter #(= status (:status %)))

                       ;; Filter by node involvement
                       node-id
                       (filter #(some (fn [nt] (= node-id (:node-id nt)))
                                      (:node-traces %)))

                       ;; Filter by time (since should be an Instant or compatible)
                       since
                       (filter #(pos? (compare (:started-at %) since))))
            ;; Sort by started-at descending (most recent first)
            sorted (sort-by :started-at #(compare %2 %1) filtered)
            ;; Apply limit
            limited (take (or limit 100) sorted)]
        {:query/result
         {:traces (vec limited)
          :total (count filtered)}}))))

(defn- instant->str
  "Convert an Instant or OffsetDateTime to ISO string for Transit serialization."
  [t]
  (when t (str t)))

(defn- serialize-node-trace
  "Serialize a node trace for Transit, converting timestamps."
  [nt]
  (-> nt
      (update :started-at instant->str)
      (update :completed-at instant->str)))

(defn- serialize-node-trace-summary
  "Serialize a node trace summary (without inputs/outputs) for Transit."
  [nt]
  (-> nt
      (dissoc :inputs :outputs)
      (update :started-at instant->str)
      (update :completed-at instant->str)))

(defn- serialize-trace
  "Serialize a full trace for Transit, converting all timestamps.
   Uses summary node-traces (no inputs/outputs) to keep payload small."
  [trace]
  (when trace
    (-> trace
        (update :started-at instant->str)
        (update :completed-at instant->str)
        (update :node-traces #(mapv serialize-node-trace-summary %)))))

(defquery :sheet runs-screen
  {:authorized? authenticated?}
  "Fat query for the /runs screen.
   Returns trace summaries for list + optionally full trace detail.

   Query params:
     :trace-id - Optional. If provided, includes full trace data for detail view.
     :status - Optional. Filter list by status (:success, :failure, :timeout).
     :limit - Optional. Max traces in list (default 100)."
  [{{:keys [trace-id status limit]} :query
    :keys [event-store] :as ctx}]
  (let [;; Get all traces for list (summaries only)
        all-traces (rm/get-all-traces ctx)
        sheets-map (rm/get-sheets-name-map ctx)

        ;; Filter and transform to summaries
        filtered (cond->> all-traces
                   status (filter #(= status (:status %))))
        sorted (sort-by :started-at #(compare %2 %1) filtered)
        limited (take (or limit 100) sorted)
        summaries (mapv (fn [t]
                          {:trace-id (:trace-id t)
                           :sheet-id (:sheet-id t)
                           :sheet-name (get sheets-map (:sheet-id t) "Unknown")
                           :status (:status t)
                           :started-at (instant->str (:started-at t))
                           :duration-ms (:duration-ms t)
                           :node-count (count (:node-traces t))
                           :version-number (:version-number t)})
                        limited)

        ;; Get full trace if requested
        selected-trace (when trace-id
                         (serialize-trace (rm/get-trace ctx trace-id)))]
    {:query/result
     {:traces summaries
      :total (count filtered)
      :selected-trace selected-trace}}))

(defquery :sheet node-trace-detail
  {:authorized? authenticated?}
  "Fetch inputs/outputs for a specific node trace on demand.

   Query params:
     :trace-id - The execution trace ID.
     :node-id - The node ID within the trace."
  [{{:keys [trace-id node-id]} :query
    :keys [event-store] :as ctx}]
  (let [trace (rm/get-trace ctx trace-id)
        node-trace (some #(when (= (:node-id %) node-id) %)
                         (:node-traces trace))]
    (if node-trace
      {:query/result
       {:node-id node-id
        :inputs (:inputs node-trace)
        :outputs (:outputs node-trace)}}
      {::anom/category ::anom/not-found
       ::anom/message (str "Node trace not found: " node-id)})))

(defquery :sheet run-detail-screen
  {:authorized? authenticated?}
  "Fat query for the /runs/detail screen.
   Returns full trace data for a single run.

   Query params:
     :trace-id - The execution trace ID."
  [{{:keys [trace-id]} :query
    :keys [event-store] :as ctx}]
  (let [trace (rm/get-trace ctx trace-id)
        sheets-map (rm/get-sheets-name-map ctx)]
    (if trace
      {:query/result
       {:trace (-> (serialize-trace trace)
                   (assoc :sheet-name (get sheets-map (:sheet-id trace) "Unknown")))}}
      {::anom/category ::anom/not-found
       ::anom/message (str "Trace not found: " trace-id)})))

;; =============================================================================
;; Structural Diff
;; =============================================================================

(defn- flatten-tree-with-paths
  "Flatten a nested snapshot tree to a map of {node-id {:node ... :path [...]}}."
  ([tree] (flatten-tree-with-paths tree []))
  ([tree path]
   (when tree
     (let [node-id (:id tree)
           node-name (:name tree)
           current-path (conj path node-name)
           children (or (:children tree) [])
           ;; Recursively flatten children
           child-results (mapcat #(flatten-tree-with-paths % current-path) children)]
       (cons {node-id {:node tree :path current-path}}
             child-results)))))

(defn- diff-node-fields
  "Compare two nodes and return a list of changes."
  [from-node to-node]
  (let [fields-to-compare [:type :name :executor :model :instruction :fn
                           :reads :writes :check :on-fail
                           :success-policy :failure-policy
                           :source-key :item-key :output-key :max-concurrency
                           :preserve-failures?]]
    (for [field fields-to-compare
          :let [from-val (get from-node field)
                to-val (get to-node field)]
          :when (not= from-val to-val)]
      {:field field
       :old from-val
       :new to-val})))

(defn- diff-node-trees
  "Compute structural diff between two node trees."
  [from-tree to-tree]
  (let [;; Flatten both trees
        from-flat (into {} (flatten-tree-with-paths from-tree))
        to-flat (into {} (flatten-tree-with-paths to-tree))
        from-ids (set (keys from-flat))
        to-ids (set (keys to-flat))
        ;; Compute set differences
        added-ids (clojure.set/difference to-ids from-ids)
        removed-ids (clojure.set/difference from-ids to-ids)
        common-ids (clojure.set/intersection from-ids to-ids)]
    {:added-nodes (mapv (fn [id]
                          (let [{:keys [node path]} (get to-flat id)]
                            {:id id
                             :name (:name node)
                             :type (:type node)
                             :path path}))
                        added-ids)
     :removed-nodes (mapv (fn [id]
                            (let [{:keys [node path]} (get from-flat id)]
                              {:id id
                               :name (:name node)
                               :type (:type node)
                               :path path}))
                          removed-ids)
     :modified-nodes (vec (for [id common-ids
                                :let [from-entry (get from-flat id)
                                      to-entry (get to-flat id)
                                      changes (diff-node-fields (:node from-entry) (:node to-entry))]
                                :when (seq changes)]
                            {:id id
                             :name (:name (:node to-entry))
                             :path (:path to-entry)
                             :changes (vec changes)}))}))

(defn- diff-blackboard-schemas
  "Compute diff between two blackboard schemas."
  [from-schema to-schema]
  (let [from-keys (set (keys from-schema))
        to-keys (set (keys to-schema))
        added-keys (clojure.set/difference to-keys from-keys)
        removed-keys (clojure.set/difference from-keys to-keys)
        common-keys (clojure.set/intersection from-keys to-keys)]
    {:added (vec added-keys)
     :removed (vec removed-keys)
     :modified (vec (for [k common-keys
                          :let [from-val (get from-schema k)
                                to-val (get to-schema k)]
                          :when (not= from-val to-val)]
                      {:key k
                       :old-schema from-val
                       :new-schema to-val}))}))

(defquery :sheet diff-versions
  {:authorized? authenticated?}
  "Compare two published versions and return structural differences.

   Returns:
     :node-diff - Added, removed, and modified nodes
     :blackboard-diff - Added, removed, and modified blackboard keys"
  [{{:keys [sheet-id from-version to-version]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        v1 (when sheet (rm/get-version ctx sheet-id from-version))
        v2 (when sheet (rm/get-version ctx sheet-id to-version))]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not v1)
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " from-version " not found")}

      (not v2)
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " to-version " not found")}

      :else
      (let [from-snapshot (:snapshot v1)
            to-snapshot (:snapshot v2)]
        {:query/result
         {:from-version from-version
          :to-version to-version
          :node-diff (diff-node-trees (:nodes from-snapshot) (:nodes to-snapshot))
          :blackboard-diff (diff-blackboard-schemas (:blackboard-schema from-snapshot)
                                                     (:blackboard-schema to-snapshot))}}))))

;; =============================================================================
;; Node Statistics
;; =============================================================================

(defn- percentile
  "Calculate the nth percentile from a sorted list of values."
  [sorted-values n]
  (when (seq sorted-values)
    (let [idx (int (* (/ n 100.0) (dec (count sorted-values))))]
      (nth sorted-values (min idx (dec (count sorted-values)))))))

(defn- top-errors
  "Get the top N most common errors."
  [traces n]
  (->> traces
       (keep :error)
       (frequencies)
       (sort-by val >)
       (take n)
       (mapv (fn [[msg cnt]] {:message msg :count cnt}))))

(defn- compute-node-stats
  "Compute statistics for a single node from its traces."
  [node-id traces]
  (when (seq traces)
    (let [first-trace (first traces)
          durations (sort (keep :duration-ms traces))
          statuses (frequencies (map :status traces))
          total (count traces)
          success-count (get statuses :success 0)]
      {:node-id node-id
       :node-name (:node-name first-trace)
       :node-type (:node-type first-trace)
       :execution-count total
       :success-count success-count
       :failure-count (get statuses :failure 0)
       :skip-count (get statuses :skipped 0)
       :success-rate (if (pos? total) (double (/ success-count total)) 0.0)
       :avg-duration-ms (when (seq durations)
                          (double (/ (reduce + durations) (count durations))))
       :p50-duration-ms (percentile durations 50)
       :p95-duration-ms (percentile durations 95)
       :common-errors (top-errors traces 5)})))

(defquery :sheet node-stats
  {:authorized? authenticated?}
  "Get aggregated execution statistics per node.

   Query params:
     :sheet-id - Required. The sheet to get stats for.
     :version-number - Optional. Filter to specific version.
     :since - Optional. Filter to traces after this time.
     :node-ids - Optional. Vector of specific node IDs to include (nil = all)."
  [{{:keys [sheet-id version-number since node-ids]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      (let [;; Get all traces for this sheet
            all-traces (rm/get-traces-for-sheet ctx sheet-id)
            ;; Apply filters
            filtered-traces (cond->> all-traces
                              version-number
                              (filter #(= version-number (:version-number %)))

                              since
                              (filter #(pos? (compare (:started-at %) since))))
            ;; Flatten all node traces from filtered traces
            all-node-traces (mapcat :node-traces filtered-traces)
            ;; Group by node-id
            by-node (group-by :node-id all-node-traces)
            ;; Filter to requested nodes if specified
            by-node (if (seq node-ids)
                      (select-keys by-node (set node-ids))
                      by-node)
            ;; Compute stats per node
            stats (keep (fn [[node-id traces]]
                          (compute-node-stats node-id traces))
                        by-node)]
        {:query/result
         {:stats (vec (sort-by :success-rate stats))
          :trace-count (count filtered-traces)}}))))

;; =============================================================================
;; Active Executions Dashboard
;; =============================================================================

(defquery :sheet active-executions-screen
  {:authorized? authenticated?}
  "Fat query for the /executions active executions dashboard.
   Returns all currently running executions across all sheets.

   Returns:
     :executions - List of active executions with progress info
     :total - Total count of active executions"
  [{:keys [event-store] :as ctx}]
  (let [active-execs (rm/get-all-active-executions ctx)
        sheets-map (rm/get-sheets-name-map ctx)
        ;; Enrich each execution with sheet name and node info
        enriched (mapv (fn [exec]
                         (let [sheet-id (:sheet-id exec)
                               nodes-by-id (when (:current-node-id exec)
                                            (rm/get-nodes-by-id ctx sheet-id))
                               current-node (when nodes-by-id
                                             (get nodes-by-id (:current-node-id exec)))]
                           {:tick-id (:tick-id exec)
                            :sheet-id sheet-id
                            :sheet-name (get sheets-map sheet-id "Unknown")
                            :status (:status exec)
                            :started-at (:started-at exec)
                            :current-node-id (:current-node-id exec)
                            :current-node-name (:name current-node)
                            :current-node-type (:type current-node)
                            :nodes-in-progress-count (count (:nodes-in-progress exec))
                            :sequence-progress (:sequence-progress exec)
                            :map-each-progress (:map-each-progress exec)}))
                       active-execs)]
    {:query/result
     {:executions enriched
      :total (count enriched)}}))
