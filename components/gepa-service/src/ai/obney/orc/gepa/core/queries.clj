(ns ai.obney.orc.gepa.core.queries
  "Query handlers for GEPA optimization state.

   Queries read state from events via read models.
   All queries are read-only and return data or anomalies."
  (:require [ai.obney.grain.query-processor.interface :as query-processor :refer [defquery]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [cognitect.anomalies :as anom]))

(defn get-progress
  "Get progress of an optimization run.

   Returns:
   {:optimization-id uuid
    :status :running | :completed | :failed
    :progress-percentage 0-100
    :metric-calls {:used n :budget m}
    :candidates {:total n :evaluated m}
    :frontier {:size n :instances-covered m}
    :best-score double
    :best-candidate-id uuid}"
  [context optimization-id]
  (query-processor/process-query
    (assoc context
           :query {:query/id (random-uuid)
                   :query/timestamp (time/now)
                   :query/name :gepa/get-optimization-progress
                   :optimization-id optimization-id})))

(defn get-population
  "Get all candidates in an optimization.

   Returns:
   {:optimization-id uuid
    :candidates [...]
    :total int
    :evaluated int
    :total-metric-calls int}"
  [context optimization-id]
  (query-processor/process-query
    (assoc context
           :query {:query/id (random-uuid)
                   :query/timestamp (time/now)
                   :query/name :gepa/get-population
                   :optimization-id optimization-id})))

(defn get-pareto-frontier
  "Get the Pareto frontier candidates.

   Returns:
   {:optimization-id uuid
    :frontier-size int
    :members [candidate-maps]
    :max-scores {instance-idx -> score}
    :best-at {instance-idx -> #{candidate-ids}}}"
  [context optimization-id]
  (query-processor/process-query
    (assoc context
           :query {:query/id (random-uuid)
                   :query/timestamp (time/now)
                   :query/name :gepa/get-pareto-frontier
                   :optimization-id optimization-id})))

(defn list-optimizations
  "List optimization runs.

   Options:
   - :sheet-id - Filter by target workflow
   - :status - Filter by :running, :completed, or :failed
   - :limit - Max results (default 20)

   Returns:
   {:optimizations [{:optimization-id ... :status ... :best-score ...}]
    :total int}"
  [context & {:keys [sheet-id status limit]}]
  (query-processor/process-query
    (assoc context
           :query {:query/id (random-uuid)
                   :query/timestamp (time/now)
                   :query/name :gepa/list-optimizations
                   :sheet-id sheet-id
                   :status status
                   :limit limit})))

;; =============================================================================
;; Cross-Run Persistence API
;; =============================================================================

(defn get-previous-optimizations
  "Get previous optimization runs for the same sheet.

   Useful for continuing optimization across sessions ('epochs').

   Options:
   - :limit - Max number of previous runs (default 5)

   Returns sequence of optimization events, most recent first."
  [ctx sheet-id & {:keys [limit] :or {limit 5}}]
  (rm/get-previous-optimizations ctx sheet-id :limit limit))

;; =============================================================================
;; Get Optimization State
;; =============================================================================

(defquery :gepa get-optimization-state
  "Get the current state of an optimization run."
  [{{:keys [optimization-id]} :query :as ctx}]
  (let [state (rm/get-optimization-summary ctx optimization-id)]
    (if (:optimization-id state)
      {:query/result state}
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"})))

;; =============================================================================
;; Get Candidate
;; =============================================================================

(defquery :gepa get-candidate
  "Get a specific candidate by ID."
  [{{:keys [optimization-id candidate-id]} :query :as ctx}]
  (let [pop-state (rm/get-population-state ctx optimization-id)
        candidate (get-in pop-state [:candidates candidate-id])]
    (if candidate
      {:query/result candidate}
      {::anom/category ::anom/not-found
       ::anom/message "Candidate not found"})))

;; =============================================================================
;; Get Population
;; =============================================================================

(defquery :gepa get-population
  "Get all candidates in an optimization's population."
  [{{:keys [optimization-id]} :query :as ctx}]
  (let [pop-state (rm/get-population-state ctx optimization-id)]
    (if (:optimization-id pop-state)
      {:query/result {:optimization-id optimization-id
                      :candidates (vals (:candidates pop-state))
                      :total (count (:candidates pop-state))
                      :evaluated (count (:evaluated pop-state))
                      :total-metric-calls (:total-metric-calls pop-state)
                      :reserved-metric-calls (:reserved-metric-calls pop-state)
                      :available-metric-calls (:available-metric-calls pop-state)}}
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"})))

;; =============================================================================
;; Get Pareto Frontier
;; =============================================================================

(defquery :gepa get-pareto-frontier
  "Get the current Pareto frontier for an optimization."
  [{{:keys [optimization-id]} :query :as ctx}]
  (let [frontier (rm/get-pareto-frontier-state ctx optimization-id)
        pop-state (rm/get-population-state ctx optimization-id)]
    (if (:optimization-id frontier)
      (let [member-ids (:frontier-members frontier)
            members (map #(get-in pop-state [:candidates %]) member-ids)]
        {:query/result {:optimization-id optimization-id
                        :frontier-size (count member-ids)
                        :members (filter some? members)
                        :max-scores (:max-scores frontier)
                        :best-at (:best-at frontier)}})
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"})))

;; =============================================================================
;; Get Best Candidate
;; =============================================================================

(defquery :gepa get-best-candidate
  "Get the current best candidate by aggregate score."
  [{{:keys [optimization-id]} :query :as ctx}]
  (let [best (rm/get-best-candidate ctx optimization-id)]
    (if best
      {:query/result best}
      {::anom/category ::anom/not-found
       ::anom/message "No evaluated candidates found"})))

;; =============================================================================
;; List Optimizations
;; =============================================================================

(defquery :gepa list-optimizations
  "List optimization runs, optionally filtered by sheet or status."
  [{{:keys [sheet-id status limit]
     :or {limit 20}} :query :as ctx}]
  (let [all-opts (vals (rmp/project ctx :gepa/optimization-list {:tags #{[:sheet sheet-id]}}))
        filtered (cond->> all-opts
                   status (filter #(= status (:status %))))]
    {:query/result {:optimizations (vec (take limit (sort-by :started-at #(compare %2 %1) filtered)))
                    :total (count filtered)}}))

;; =============================================================================
;; Get Optimization Progress
;; =============================================================================

(defquery :gepa get-optimization-progress
  "Get detailed progress metrics for an optimization."
  [{{:keys [optimization-id]} :query :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        pop-state (rm/get-population-state ctx optimization-id)
        frontier (rm/get-pareto-frontier-state ctx optimization-id)]
    (if (:optimization-id opt-state)
      (let [config (:config opt-state)
            budget-pct (if (pos? (:max-metric-calls config))
                         (* 100.0 (/ (:total-metric-calls opt-state)
                                     (:max-metric-calls config)))
                         0.0)]
        {:query/result {:optimization-id optimization-id
                        :status (:status opt-state)
                        :progress-percentage (min 100.0 budget-pct)
                        :metric-calls {:used (:total-metric-calls opt-state)
                                       :reserved (:reserved-metric-calls opt-state)
                                       :available (:available-metric-calls opt-state)
                                       :budget (:max-metric-calls config)}
                        :reflection-tokens {:used (rm/get-reflection-tokens-used ctx optimization-id)
                                            :budget (:max-reflection-tokens config)}
                        :candidates {:total (:total-candidates opt-state)
                                     :evaluated (count (:evaluated pop-state))}
                        :frontier {:size (count (:frontier-members frontier))
                                   :instances-covered (count (:max-scores frontier))}
                        :best-score (:best-score opt-state)
                        :best-candidate-id (:best-candidate-id opt-state)}})
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"})))

;; =============================================================================
;; Get Candidate Lineage
;; =============================================================================

(defquery :gepa get-candidate-lineage
  "Get the mutation lineage for a candidate (ancestors)."
  [{{:keys [optimization-id candidate-id]} :query :as ctx}]
  (let [pop-state (rm/get-population-state ctx optimization-id)]
    (if (:optimization-id pop-state)
      (letfn [(get-ancestors [cid depth]
                (when (and cid (< depth 10))
                  (let [candidate (get-in pop-state [:candidates cid])
                        parent-ids (filter some? (:parent-ids candidate))]
                    (cons candidate
                          (mapcat #(get-ancestors % (inc depth)) parent-ids)))))]
        (let [lineage (get-ancestors candidate-id 0)]
          {:query/result {:candidate-id candidate-id
                          :lineage (vec lineage)
                          :depth (count (filter #(some some? (:parent-ids %)) lineage))}}))
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"})))

;; =============================================================================
;; Get Failing Instances
;; =============================================================================

(defquery :gepa get-failing-instances
  "Get instances where a candidate underperformed vs frontier."
  [{{:keys [optimization-id candidate-id]} :query :as ctx}]
  (let [failures (rm/get-failing-instances ctx optimization-id candidate-id)]
    (if failures
      {:query/result {:candidate-id candidate-id
                      :failing-instances failures
                      :num-failures (count failures)}}
      {::anom/category ::anom/not-found
       ::anom/message "Candidate not found or not evaluated"})))
