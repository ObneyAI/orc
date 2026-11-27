(ns ai.obney.orc.gepa.core.read-models
  "Read models for GEPA - EXACT 1:1 Python GEPA Parity.

   Projects events into queryable state matching Python's GEPAState:
   - program_candidates: List of instruction maps
   - program_full_scores_val_set: Aggregate scores per candidate
   - prog_candidate_val_subscores: Per-candidate per-instance scores
   - parent_program_for_candidate: Lineage tracking
   - named_predictor_id_to_update_next_for_program_candidate: Round-robin state
   - evaluation_cache: Cached evaluation results
   - frontier: Pareto front mapping

   Uses defreadmodel + rmp/project for L1/L2 cached projections."
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.orc.gepa.core.logic :as logic]
            [ai.obney.orc.gepa.interface.schemas]
            [cognitect.anomalies :as anom]))

(defn get-state [context optimization-id]
  (if (uuid? (:tenant-id context))
    (rmp/project context :gepa/task-ledger {:tags #{[:optimization optimization-id]}})
    {::anom/category ::anom/incorrect ::anom/message "A tenant UUID is required"}))

(defn get-accounting [context optimization-id]
  (let [state (get-state context optimization-id)]
    (if (::anom/category state) state (logic/accounting state))))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn- with-accounting
  "Compose completed-evaluation statistics with authoritative attempt spend.
   The internal domain reducers retain historical aggregate counters; callers
   must use these getters for budget accounting, including unfinished work."
  [ctx optimization-id state]
  (if (:optimization-id state)
    (let [{:keys [spent reserved available]} (get-accounting ctx optimization-id)]
      (assoc state :total-metric-calls spent
                   :reserved-metric-calls reserved
                   :available-metric-calls available))
    state))

(defn get-optimization-summary
  "Get the overall optimization state summary."
  [ctx optimization-id]
  (with-accounting ctx optimization-id
    (rmp/project ctx :gepa/optimization-state {:tags #{[:optimization optimization-id]}})))

(defn get-population-state
  "Get the current population state for an optimization."
  [ctx optimization-id]
  (with-accounting ctx optimization-id
    (rmp/project ctx :gepa/population {:tags #{[:optimization optimization-id]}})))

(defn get-candidate
  "Get a specific candidate from the population."
  [ctx optimization-id candidate-id]
  (let [pop-state (get-population-state ctx optimization-id)]
    (get-in pop-state [:candidates candidate-id])))

(defn get-best-candidate
  "Get the current best candidate (highest aggregate score)."
  [ctx optimization-id]
  (let [opt-state (get-optimization-summary ctx optimization-id)
        candidate-id (:best-candidate-id opt-state)]
    (when candidate-id
      (get-candidate ctx optimization-id candidate-id))))

(defn get-datasets
  "Get the trainset and valset for an optimization.
   Returns {:trainset [...] :valset [...]} or nil if not found."
  [ctx optimization-id]
  (let [state (rmp/project ctx :gepa/datasets {:tags #{[:optimization optimization-id]}})]
    (when (:trainset state)
      {:trainset (:trainset state)
       :valset (:valset state)})))

(defn get-valset
  "Get the valset for an optimization."
  [ctx optimization-id]
  (:valset (get-datasets ctx optimization-id)))

(defn get-task [context optimization-id call-id]
  (let [state (get-state context optimization-id)]
    (if (::anom/category state) state (get-in state [:tasks call-id]))))

(defn get-trainset
  "Get the trainset for an optimization."
  [ctx optimization-id]
  (:trainset (get-datasets ctx optimization-id)))

(defn get-reflection-tokens-used
  "Tokens spent by the optimization's reflection-model calls: the sum of
   `:total-tokens` over its durable proposer-call evidence. A completed call
   that is reused on resume appends no evidence, so it is counted once."
  [ctx optimization-id]
  (transduce (map #(get-in % [:usage :total-tokens] 0)) + 0
             (into [] (event-store/read (:event-store ctx)
                               {:tenant-id (:tenant-id ctx)
                                :types #{:gepa/proposer-call-completed}
                                :tags #{[:optimization optimization-id]}}))))

(defn get-gepa-state
  "Get the full GEPA state for an optimization (Python-compatible)."
  [ctx optimization-id]
  (with-accounting ctx optimization-id
    (rmp/project ctx :gepa/gepa-state {:tags #{[:optimization optimization-id]}})))

(defn get-pareto-frontier-state
  "Get the current Pareto frontier for an optimization."
  [ctx optimization-id]
  (rmp/project ctx :gepa/pareto-frontier {:tags #{[:optimization optimization-id]}}))

(defn budget-exhausted?
  "Check if the metric call budget has been exhausted."
  [ctx optimization-id]
  (let [opt-state (get-optimization-summary ctx optimization-id)
        max-calls (get-in opt-state [:config :max-metric-calls] 50)
        current-calls (:total-metric-calls opt-state 0)]
    (>= current-calls max-calls)))

(defn get-previous-optimizations
  "Get previous optimization runs for the same sheet.
   Returns most recent first, only completed optimizations."
  [ctx sheet-id & {:keys [limit] :or {limit 5}}]
  (->> (vals (rmp/project ctx :gepa/optimization-list {:tags #{[:sheet sheet-id]}}))
       (filter #(= :completed (:status %)))
       (sort-by :completed-at #(compare %2 %1))
       (take limit)))

(defn get-pareto-candidates-from-optimization
  "Get all Pareto-optimal candidates from a completed optimization.
   Returns sequence with per-instance scores for true single-run parity:
   {:instructions, :aggregate-score, :val-subscores, :candidate-details}"
  [ctx optimization-id]
  (let [state (get-gepa-state ctx optimization-id)
        pareto-mapping (:pareto-front-mapping state)
        pareto-indices (->> (vals pareto-mapping)
                            (mapcat identity)
                            set)]
    (->> pareto-indices
         (map (fn [idx]
                {:instructions (nth (:program-candidates state) idx)
                 :aggregate-score (nth (:agg-scores state) idx)
                 :val-subscores (get (:val-subscores state) idx {})
                 :candidate-details (get (:candidate-details state) idx)}))
         (sort-by :aggregate-score >))))

(defn get-all-unique-candidates
  "Get all unique candidates across ALL previous runs for cumulative inheritance.

   Deduplicates by instruction hash - if the same instruction appears in
   multiple runs, keeps the one with the highest aggregate score AND merges
   per-instance scores (keeping best score per instance). This ensures:
   - 5 epochs × 20 runs = 100 epochs × 1 run in terms of knowledge retained
   - Per-instance diversity is preserved across runs
   - True single-run parity with Python GEPA

   Returns sequence of {:instructions :aggregate-score :val-subscores :source-optimization-id}"
  [ctx sheet-id & {:keys [limit] :or {limit 20}}]
  (let [
        all-opts (->> (vals (rmp/project ctx :gepa/optimization-list {:tags #{[:sheet sheet-id]}}))
                      (filter #(= :completed (:status %)))
                      (sort-by :completed-at #(compare %2 %1)))

        all-candidates (->> all-opts
                            (mapcat (fn [opt-event]
                                      (let [opt-id (:optimization-id opt-event)]
                                        (map #(assoc % :source-optimization-id opt-id)
                                             (get-pareto-candidates-from-optimization ctx opt-id))))))

        unique-by-hash (reduce (fn [acc cand]
                                 (let [inst-hash (hash (:instructions cand))
                                       existing (get acc inst-hash)]
                                   (if (nil? existing)
                                     (assoc acc inst-hash cand)
                                     (let [merged-subscores (merge-with max
                                                              (or (:val-subscores existing) {})
                                                              (or (:val-subscores cand) {}))
                                           best-cand (if (> (or (:aggregate-score cand) 0)
                                                            (or (:aggregate-score existing) 0))
                                                       cand
                                                       existing)]
                                       (assoc acc inst-hash
                                              (assoc best-cand :val-subscores merged-subscores))))))
                               {}
                               all-candidates)]

    (->> (vals unique-by-hash)
         (sort-by :aggregate-score >)
         (take limit))))

(defn inherit-from-previous-runs
  "Get candidates to inherit from ALL previous optimization runs.

   Uses cumulative deduplication - same instruction across runs only counted once.
   This ensures 5 epochs × 20 runs = 100 epochs × 1 run."
  [ctx sheet-id & {:keys [limit] :or {limit 10}}]
  (get-all-unique-candidates ctx sheet-id :limit limit))

(defn get-failing-instances
  "Get instances where a candidate scored below threshold.
   Returns sequence of {:instance-id :score}."
  [ctx optimization-id candidate-id]
  (let [pop-state (get-population-state ctx optimization-id)
        candidate (get-in pop-state [:candidates candidate-id])
        scores (:scores candidate)]
    (when scores
      (->> scores
           (map-indexed (fn [idx score]
                          {:instance-id idx :score score}))
           (filter #(< (:score %) 0.7))
           vec))))

(defreadmodel :gepa gepa-state
  {:events logic/gepa-event-types :version 1}
  [state event] (logic/gepa-state state [event]))

(defreadmodel :gepa population
  {:events logic/population-event-types :version 1}
  [state event] (logic/population state [event]))

(defreadmodel :gepa pareto-frontier
  {:events logic/pareto-frontier-event-types :version 1}
  [state event] (logic/pareto-frontier state [event]))

(defreadmodel :gepa optimization-state
  {:events logic/optimization-state-event-types :version 1}
  [state event] (logic/optimization-state state [event]))

(defreadmodel :gepa datasets
  {:events logic/datasets-event-types :version 1}
  [state event] (logic/datasets state [event]))

(defreadmodel :gepa optimization-list
  {:events logic/optimization-list-event-types :version 1}
  [state event] (logic/optimization-list-reducer state event))

(defn get-pareto-members
  "Get all candidates currently in the Pareto frontier."
  [ctx optimization-id]
  (let [pop-state (get-population-state ctx optimization-id)
        frontier (get-pareto-frontier-state ctx optimization-id)
        member-ids (:frontier-members frontier)]
    (select-keys (:candidates pop-state) member-ids)))

(defn get-iteration-count
  "Get the current iteration count for an optimization.
   Used for epoch-shuffled batch sampling to determine which
   minibatch to use for reflective mutation.
   Counts evaluated candidates + subsample evaluations from read models."
  [ctx optimization-id]
  (let [pop-state (get-population-state ctx optimization-id)
        gepa-st (get-gepa-state ctx optimization-id)]
    (+ (count (:evaluated pop-state))
       (count (:subsample-decisions gepa-st)))))

(defn get-candidate-scores
  "Get the per-instance scores for a candidate.
   Returns map of {instance-id -> score} for use in skip-perfect-score check."
  [ctx optimization-id candidate-id]
  (let [pop-state (get-population-state ctx optimization-id)
        candidate (get-in pop-state [:candidates candidate-id])
        scores (:scores candidate)]
    (when scores
      (into {} (map-indexed (fn [idx score] [idx score]) scores)))))

(defn sample-pareto-frontier
  "Sample a candidate from the Pareto frontier weighted by instance coverage.
   Uses Python GEPA's exact selection algorithm."
  [ctx optimization-id]
  (let [state (get-gepa-state ctx optimization-id)
        pareto-mapping (:pareto-front-mapping state)
        agg-scores (vec (:agg-scores state))]
    (when (and (seq pareto-mapping) (seq (filter some? agg-scores)))
      (let [candidate-counts (->> (vals pareto-mapping)
                                  (mapcat identity)
                                  frequencies)
            candidates-with-weights (vec candidate-counts)
            total-weight (reduce + (map second candidates-with-weights))]
        (when (pos? total-weight)
          (let [r (rand total-weight)]
            (loop [remaining r
                   [[cand-idx weight] & rest-cands] candidates-with-weights]
              (if (or (nil? cand-idx) (< remaining weight))
                (let [idx->id (into {} (map (fn [[id idx]] [idx id])
                                            (:candidate-id->idx state)))]
                  (get idx->id cand-idx))
                (recur (- remaining weight) rest-cands)))))))))

(defn get-candidate-feedbacks
  "Get the per-instance RICH judge feedback recorded for a candidate.
   Returns map of {instance-idx -> feedback-string}, or {} if none (e.g. the
   candidate was scored by a structural/user metric with no feedback)."
  [ctx optimization-id candidate-id]
  (let [pop-state (get-population-state ctx optimization-id)
        candidate (get-in pop-state [:candidates candidate-id])]
    (or (:feedbacks candidate) {})))

(defn get-candidate-outputs
  "Get the per-instance generated OUTPUT recorded for a candidate.
   Returns map of {instance-idx -> output-map}, or {} if none (e.g. the
   workflow produced no output, or this candidate predates output threading)."
  [ctx optimization-id candidate-id]
  (let [pop-state (get-population-state ctx optimization-id)
        candidate (get-in pop-state [:candidates candidate-id])]
    (or (:outputs candidate) {})))

(defn get-cumulative-pareto-frontier
  "Build a Pareto frontier spanning ALL optimization runs for a sheet.
   This simulates what Python GEPA would have after a single continuous run.

   Returns:
   {:max-scores {instance-id -> max-score}  ;; Best score ever per instance
    :frontier-members [candidates...]        ;; Candidates on the frontier
    :frontier-count int}                     ;; Number of frontier members"
  [ctx sheet-id]
  (let [
        all-candidates (get-all-unique-candidates ctx sheet-id :limit 1000)

        max-scores (reduce (fn [acc cand]
                            (reduce-kv (fn [acc2 inst-id score]
                                         (update acc2 inst-id (fnil max 0.0) score))
                                       acc
                                       (or (:val-subscores cand) {})))
                          {}
                          all-candidates)

        frontier-members (filter (fn [cand]
                                  (some (fn [[inst-id score]]
                                          (= score (get max-scores inst-id)))
                                        (or (:val-subscores cand) {})))
                                all-candidates)]

    {:max-scores max-scores
     :frontier-members (vec frontier-members)
     :frontier-count (count frontier-members)}))

(defn records [context optimization-id]
  (into [] (event-store/read (:event-store context)
                   {:tenant-id (:tenant-id context) :types logic/event-types
                    :tags #{[:optimization optimization-id]}})))

(defreadmodel :gepa task-ledger
  {:events logic/event-types :version 1}
  [state event] (logic/reduce-event state event))
