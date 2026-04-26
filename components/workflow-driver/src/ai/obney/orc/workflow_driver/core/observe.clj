(ns ai.obney.orc.workflow-driver.core.observe
  "Read-only observation operations the workflow-driver agent uses to
   understand a target Sheet's current state and recent behavior.

   Every function here is a thin wrapper over orc-service read-models
   plus DSL utilities, packaged into shapes useful for an LLM agent
   that reasons about workflows. No mutations — Milestone 1 is the
   read-only surface."
  (:require [ai.obney.orc.orc-service.interface :as orc]))

;; =============================================================================
;; Sheet snapshot — current structural state
;; =============================================================================

(defn sheet-snapshot
  "Return a full structural snapshot of a Sheet — what the agent needs
   to understand 'what does this workflow look like right now.'

   Returns:
     {:sheet              <sheet record>
      :root-node          <root node>
      :nodes-by-id        {node-id -> node}
      :blackboard-schema  [{:key … :schema … :version …} …]
      :latest-version     {:version-number … :published-at …} or nil
      :tree-metadata      <metadata or nil>}

   Returns nil if the sheet does not exist."
  [ctx sheet-id]
  (when-let [sheet (orc/get-sheet ctx sheet-id)]
    {:sheet sheet
     :root-node (orc/get-root-node ctx sheet-id)
     :nodes-by-id (orc/get-nodes-by-id ctx sheet-id)
     :blackboard-schema (vec (orc/get-blackboard-for-sheet ctx sheet-id))
     :latest-version (orc/get-latest-version ctx sheet-id)
     :tree-metadata (orc/get-tree-metadata ctx sheet-id)}))

(defn sheet-as-dsl
  "Round-trip the Sheet into a DSL string (the same shape the agent
   will emit on `submit-tree!`). Useful for showing the agent the
   current tree in the same vocabulary it has to write back.

   Returns a Clojure source string, or nil if the sheet is missing or
   export fails."
  [ctx sheet-id]
  (try
    (when-let [exported (orc/export-sheet ctx sheet-id)]
      (orc/export-to-dsl exported))
    (catch Exception _ nil)))

;; =============================================================================
;; Tick listing + per-tick detail
;; =============================================================================

(defn- tick-comparator
  "Sort ticks by started-at descending (most recent first)."
  [a b]
  (compare (str (:started-at b)) (str (:started-at a))))

(defn recent-ticks
  "List recent ticks for a sheet, most recent first.

   Options:
     :limit n  — cap results (default 20)
     :status   — filter by status (:running / :completed / :cancelled)

   Returns a vector of tick summary maps:
     [{:id … :sheet-id … :status … :iteration …
       :started-at … :completed-at … :root-status …} …]"
  [ctx sheet-id & [{:keys [limit status]
                    :or {limit 20}}]]
  (let [all (orc/get-ticks-for-sheet ctx sheet-id)
        filtered (cond->> all
                   status (filter #(= status (:status %))))]
    (->> filtered
         (sort tick-comparator)
         (take limit)
         vec)))

(defn tick-snapshot
  "Return one tick's full state. Pulls the tick record plus per-node
   completion data when available.

   Returns:
     {:tick <tick record>
      :nodes [{node-id … :status … :duration-ms … :outputs …} …]
      :final-blackboard {key value …} or nil}

   Returns nil if the tick does not exist."
  [ctx tick-id]
  (when-let [tick (orc/get-tick ctx tick-id)]
    {:tick tick
     ;; Per-node tick state lives in tick-execution-context but is not
     ;; on the public interface yet. For now, surface what is exposed
     ;; (status, root-status, timing) and document the gap. The agent
     ;; can still reason about overall pass/fail at this level.
     :nodes nil
     :final-blackboard nil}))

;; =============================================================================
;; Performance — rolling metrics joined to node tree
;; =============================================================================

(defn node-summary
  "For each node in the Sheet, join its name + executor type with its
   rolling metrics. The agent uses this to spot which nodes are slow,
   failing, or trending downward.

   Returns:
     [{:node-id … :name … :executor … :node-type …
       :execution-count … :success-rate … :failure-rate …
       :avg-duration-ms … :recent-trend …} …]"
  [ctx sheet-id]
  (let [nodes-by-id (orc/get-nodes-by-id ctx sheet-id)
        rolling (orc/get-tree-rolling-metrics ctx sheet-id)
        metrics-by-id (into {}
                            (map (fn [m] [(:node-id m) m]))
                            (:nodes rolling))]
    (->> (vals nodes-by-id)
         (mapv (fn [{:keys [id name type executor]}]
                 (let [m (get metrics-by-id id)]
                   (cond-> {:node-id id
                            :name name
                            :node-type type
                            :executor executor}
                     m (merge (select-keys m [:execution-count
                                              :success-rate
                                              :failure-rate
                                              :avg-duration-ms
                                              :recent-trend])))))))))

(defn pareto
  "Cost vs quality frontier across recent ticks of a Sheet. For
   Milestone 1 this returns the raw datapoints; downstream code can
   compute the actual frontier.

   Returns a vector of {:tick-id … :duration-ms … :status …
                        :root-status … :iteration …} sorted by
   duration ascending."
  [ctx sheet-id & [{:keys [limit] :or {limit 50}}]]
  (->> (recent-ticks ctx sheet-id {:limit limit :status :completed})
       (mapv (fn [t]
               (let [started (:started-at t)
                     completed (:completed-at t)
                     duration (when (and started completed)
                                (- (.getTime (java.util.Date/from
                                              (java.time.Instant/parse completed)))
                                   (.getTime (java.util.Date/from
                                              (java.time.Instant/parse started)))))]
                 {:tick-id (:id t)
                  :duration-ms duration
                  :status (:status t)
                  :root-status (:root-status t)
                  :iteration (:iteration t)})))
       (sort-by (fn [m] (or (:duration-ms m) Long/MAX_VALUE)))
       vec))
