(ns ai.obney.orc.orc-service.core.queries
  "Behavior Tree Sheet query handlers.

   Fat Query Model: One query per screen, each returns all data needed.
   All queries return {:query/result ...} on success."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.core.commands :as commands]
            [ai.obney.orc.orc-service.core.logic :as logic]
            [ai.obney.orc.orc-service.core.read-models :as rm]))

(defquery :sheet model-candidate-history {:authorized? logic/model-call-journal-authorized?} [ctx]
  {:query/result (vec (:entries (rmp/project ctx :sheet/model-candidate-ledger
                                            {:tags #{[:tick (get-in ctx [:query :tick-id])]}})))})

(defquery :sheet provider-attempts
  {:authorized? logic/provider-observations-authorized?}
  [ctx]
  (if-not (some? (:tenant-id ctx))
    (logic/provider-observations-rejected :cognitect.anomalies/forbidden "Provider observation tenant is required")
    (let [{:keys [tick-id node-start-id invocation-id]} (:query ctx)]
      (if-not (commands/canonical-start ctx tick-id node-start-id)
        (logic/provider-observations-rejected :cognitect.anomalies/not-found "Canonical node start is not present for this tenant and tick")
        (let [{:keys [order by-id]} (rmp/project ctx :sheet/provider-attempt-ledger
                                               {:tags #{[:provider-node-start node-start-id]}})]
          {:query/result (into [] (comp (map by-id)
                                        (filter #(and (= tick-id (:tick-id %))
                                                      (or (nil? invocation-id)
                                                          (= invocation-id (:invocation-id %)))))) order)})))))

(defquery :sheet sheets-list-screen
  {:authorized? logic/queries-authenticated?}
  "Fat query for sheets list screen.
   Returns all sheets with basic metadata."
  [{:keys [event-store] :as ctx}]
  (let [sheets (rm/get-sheets-all ctx)]
    {:query/result
     {:sheets (vec sheets)
      :total (count sheets)}}))

(defquery :sheet sheet-view-screen
  {:authorized? logic/queries-authenticated?}
  "Fat query for sheet view screen.
   Returns sheet metadata, all nodes, blackboard, layout, current tick, and version info.
   When execution-mode is :published, returns the published snapshot's nodes/blackboard."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
      (let [execution-mode (or (:execution-mode sheet) :draft)
            published-version (:published-version sheet)
            use-published? (and (= :published execution-mode) published-version)
            {:keys [nodes nodes-by-id blackboard root-id]}
            (if use-published?
              (let [version (rm/get-version ctx sheet-id published-version)
                    snapshot (:snapshot version)
                    parsed (logic/snapshot->nodes snapshot)]
                {:nodes (:nodes parsed)
                 :nodes-by-id (:nodes-by-id parsed)
                 :blackboard (logic/snapshot->blackboard snapshot)
                 :root-id (:root-id parsed)})
              (let [draft-nodes (rm/get-nodes-for-sheet ctx sheet-id)
                    draft-nodes-by-id (into {} (map (juxt :id identity) draft-nodes))]
                {:nodes draft-nodes
                 :nodes-by-id draft-nodes-by-id
                 :blackboard (rm/get-blackboard-for-sheet ctx sheet-id)
                 :root-id (:root-node-id sheet)}))
            root-node (when root-id (get nodes-by-id root-id))
            tree-layout (if root-node
                          (logic/compute-layout root-node nodes-by-id)
                          [])
            current-tick (rm/get-current-tick ctx sheet-id)
            execution-context (when current-tick
                               (rm/get-execution-context ctx (:id current-tick)))
            version-info {:published-version published-version
                          :draft-dirty? (boolean (:draft-dirty? sheet))
                          :execution-mode execution-mode
                          :has-stash? (boolean (:has-stash? sheet))}
            sheet-for-response (if (and use-published? root-id)
                                 (assoc sheet :root-node-id root-id)
                                 sheet)
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

(defquery :sheet export-sheet
  {:authorized? logic/queries-authenticated?}
  "Export a sheet as a portable EDN structure for download/backup."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
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
          :nodes (logic/queries-build-node-tree nodes (:root-node-id sheet))}}))))

;; =============================================================================
;; Versioning Queries
;; =============================================================================

(defquery :sheet version-history
  {:authorized? logic/queries-authenticated?}
  "Get all published versions for a sheet."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
      (let [versions (rm/get-versions-for-sheet ctx sheet-id)]
        {:query/result
         {:versions (vec versions)
          :current-published-version (:published-version sheet)
          :draft-dirty? (boolean (:draft-dirty? sheet))
          :has-stash? (boolean (:has-stash? sheet))}}))))

(defquery :sheet get-version
  {:authorized? logic/queries-authenticated?}
  "Get a specific published version by version number."
  [{{:keys [sheet-id version-number]} :query
    :keys [event-store] :as ctx}]
  (let [version (rm/get-version ctx sheet-id version-number)]
    (if-not version
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Version " version-number " not found")}
      {:query/result
       {:version version}})))

(defquery :sheet get-stash
  {:authorized? logic/queries-authenticated?}
  "Get the stashed draft for a sheet, if any."
  [{{:keys [sheet-id]} :query
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
      (let [stash (rm/get-stash ctx sheet-id)]
        {:query/result
         {:stash stash}}))))

(defquery :sheet get-trace
  {:authorized? logic/queries-authenticated?}
  "Get a single execution trace by ID.

   Node traces carry the SHAPE of each node's I/O — :read-keys, :write-keys
   and size profiles — not the values, and :input-snapshot / :output-snapshot
   are key -> profile maps. For a node's actual inputs/outputs use
   :sheet/node-trace-detail, which rehydrates them on demand."
  [{{:keys [trace-id]} :query
    :keys [event-store] :as ctx}]
  (let [trace (rm/get-trace ctx trace-id)]
    (if-not trace
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Trace not found"}
      {:query/result {:trace trace}})))

(defquery :sheet get-trace-family
  {:authorized? logic/queries-authenticated?}
  "Get the stored traces belonging to the same root execution as trace-id."
  [{{:keys [trace-id]} :query :as ctx}]
  (if-let [trace (rm/get-trace ctx trace-id)]
    (let [root-trace-id (:root-trace-id trace)
          traces (->> (rm/get-all-traces ctx)
                      (filter #(= root-trace-id (:root-trace-id %)))
                      (sort logic/compare-traces-ascending)
                      (mapv logic/trace-family-summary))]
      {:query/result {:root-trace-id root-trace-id
                      :traces traces}})
    {:cognitect.anomalies/category :cognitect.anomalies/not-found
     :cognitect.anomalies/message "Trace not found"}))

(defquery :sheet get-correlated-traces
  {:authorized? logic/queries-authenticated?}
  "Get every stored trace belonging to one caller-defined operation.

   Correlation spans independent root executions; :families preserves the
   structural root/delegate grouping used by get-trace-family."
  [{{:keys [correlation-id]} :query :as ctx}]
  (let [traces (->> (rm/get-traces-for-correlation ctx correlation-id)
                    (sort logic/compare-traces-ascending)
                    (mapv logic/trace-family-summary))
        families (->> traces
                      (group-by :root-trace-id)
                      (map (fn [[root-trace-id family-traces]]
                             {:root-trace-id root-trace-id
                              :traces (vec family-traces)}))
                      (sort-by (juxt #(get-in % [:traces 0 :started-at])
                                     (comp str :root-trace-id)))
                      vec)]
    {:query/result {:correlation-id correlation-id
                    :traces traces
                    :families families}}))

(defquery :sheet get-traces
  {:authorized? logic/queries-authenticated?}
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
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
      (let [all-traces (rm/get-traces-for-sheet ctx sheet-id)
            filtered (cond->> all-traces
                       (some? version-number)
                       (filter #(= version-number (:version-number %)))

                       status
                       (filter #(= status (:status %)))

                       node-id
                       (filter #(some (fn [nt] (= node-id (:node-id nt)))
                                      (:node-traces %)))

                       since
                       (filter #(pos? (logic/compare-timestamps
                                      (:started-at %) since))))
            sorted (sort logic/compare-traces-descending filtered)
            limited (take (or limit 100) sorted)]
        {:query/result
         {:traces (vec limited)
          :total (count filtered)}}))))

(defquery :sheet runs-screen
  {:authorized? logic/queries-authenticated?}
  "Fat query for the /runs screen.
   Returns trace summaries for list + optionally full trace detail.

   Query params:
     :trace-id - Optional. If provided, includes full trace data for detail view.
     :status - Optional. Filter list by status (:success, :failure, :timeout).
     :limit - Optional. Max traces in list (default 100)."
  [{{:keys [trace-id status limit]} :query
    :keys [event-store] :as ctx}]
  (let [
        all-traces (rm/get-all-traces ctx)
        sheets-map (rm/get-sheets-name-map ctx)

        filtered (cond->> all-traces
                   status (filter #(= status (:status %))))
        sorted (sort logic/compare-traces-descending filtered)
        limited (take (or limit 100) sorted)
        summaries (mapv (fn [t]
                          (cond-> {:trace-id (:trace-id t)
                                   :sheet-id (:sheet-id t)
                                   :root-trace-id (:root-trace-id t)
                                   :child-trace-ids (:child-trace-ids t)
                                   :sheet-name (get sheets-map (:sheet-id t) "Unknown")
                                   :status (:status t)
                                   :started-at (logic/instant->str (:started-at t))
                                   :duration-ms (:duration-ms t)
                                   :node-count (count (:node-traces t))}
                            (:parent-trace-id t) (assoc :parent-trace-id (:parent-trace-id t))
                            (:version-number t) (assoc :version-number (:version-number t))))
                        limited)

        selected-trace (when trace-id
                         (logic/serialize-trace (rm/get-trace ctx trace-id)))]
    {:query/result
     {:traces summaries
      :total (count filtered)
      :selected-trace selected-trace}}))

(defquery :sheet node-trace-detail
  {:authorized? logic/queries-authenticated?}
  "Fetch inputs/outputs for a specific node trace on demand.

   The trace stores only the SHAPE of each node's I/O (:read-keys,
   :write-keys and size profiles); the values are durable in the tick's
   :sheet/execution-value-written events. This query is the supported way to
   get them and rehydrates them here.

   The trace-id IS the tick-id (see assemble-execution-trace), so the tick's
   events are reachable by tag.

   Query params:
     :trace-id - The execution trace ID.
     :trace-instance-id - The unique node-start event ID within the trace."
  [{{:keys [trace-id trace-instance-id]} :query
    :keys [event-store] :as ctx}]
  (let [trace (rm/get-trace ctx trace-id)
        node-trace (some #(when (= (:trace-instance-id %) trace-instance-id) %)
                         (:node-traces trace))]
    (if node-trace
      (let [tick-events (logic/hydrate-events
                         ctx
                         (into [] (es/read event-store
                                           {:tags #{[:tick trace-id]}
                                            :tenant-id (:tenant-id ctx)})))
            node-id (:node-id node-trace)
            execution-key [node-id (logic/exec-context (:exec-context node-trace))]
            completed (some #(when (and (= :sheet/node-execution-completed (:event/type %))
                                        (= execution-key (logic/execution-key %)))
                               %)
                            tick-events)]
        {:query/result
         (merge
         {:node-id node-id
          :trace-instance-id trace-instance-id
          :exec-context (:exec-context node-trace)
          :inputs (when completed
                    (not-empty (rm/resolve-reads ctx
                                                        (:tenant-id ctx)
                                                        trace-id completed)))
          :outputs (or (:writes completed)
                       (when completed
                         (not-empty (rm/resolve-writes ctx
                                                              (:tenant-id ctx)
                                                              trace-id completed))))
          :rejected-outputs (when completed
                              (not-empty
                               (logic/rejected-writes-for tick-events completed)))
          :failure-kind (:failure-kind completed)
          :condition-answer (:condition-answer completed)
          :decision (:decision completed)
          :provider-evidence (:provider-evidence completed)}
         (select-keys completed [:model :requested-model :resolved-model]))})
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Node trace not found: " trace-instance-id)})))

(defquery :sheet run-detail-screen
  {:authorized? logic/queries-authenticated?}
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
       {:trace (-> (logic/serialize-trace trace)
                   (assoc :sheet-name (get sheets-map (:sheet-id trace) "Unknown")))}}
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Trace not found: " trace-id)})))

(defquery :sheet diff-versions
  {:authorized? logic/queries-authenticated?}
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
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (not v1)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Version " from-version " not found")}

      (not v2)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Version " to-version " not found")}

      :else
      (let [from-snapshot (:snapshot v1)
            to-snapshot (:snapshot v2)]
        {:query/result
         {:from-version from-version
          :to-version to-version
          :node-diff (logic/diff-node-trees (:nodes from-snapshot) (:nodes to-snapshot))
          :blackboard-diff (logic/diff-blackboard-schemas (:blackboard-schema from-snapshot)
                                                     (:blackboard-schema to-snapshot))}}))))

(defquery :sheet node-stats
  {:authorized? logic/queries-authenticated?}
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
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
      (let [
            all-traces (rm/get-traces-for-sheet ctx sheet-id)
            filtered-traces (cond->> all-traces
                              version-number
                              (filter #(= version-number (:version-number %)))

                              since
                              (filter #(pos? (logic/compare-timestamps
                                             (:started-at %) since))))
            all-node-traces (mapcat :node-traces filtered-traces)
            by-node (group-by :node-id all-node-traces)
            by-node (if (seq node-ids)
                      (select-keys by-node (set node-ids))
                      by-node)
            stats (keep (fn [[node-id traces]]
                          (logic/compute-node-stats node-id traces))
                        by-node)]
        {:query/result
         {:stats (vec (sort-by :success-rate stats))
          :trace-count (count filtered-traces)}}))))

;; =============================================================================
;; Active Executions Dashboard
;; =============================================================================

(defquery :sheet active-executions-screen
  {:authorized? logic/queries-authenticated?}
  "Fat query for the /executions active executions dashboard.
   Returns all currently running executions across all sheets.

   Returns:
     :executions - List of active executions with progress info
     :total - Total count of active executions"
  [{:keys [event-store] :as ctx}]
  (let [active-execs (rm/get-all-active-executions ctx)
        sheets-map (rm/get-sheets-name-map ctx)
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
