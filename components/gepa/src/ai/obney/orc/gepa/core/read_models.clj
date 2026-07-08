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
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [clojure.set :as set]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Event Types
;; =============================================================================

(def gepa-event-types
  #{:gepa/optimization-started
    :gepa/datasets-stored             ;; Trainset/valset persistence
    :gepa/candidate-created
    :gepa/candidate-evaluation-started
    :gepa/candidate-evaluated
    :gepa/example-evaluated           ;; Per-example results
    :gepa/frontier-updated
    :gepa/reflective-dataset-built    ;; Track reflection data
    :gepa/mutation-proposed
    :gepa/candidate-acceptance-decision ;; Track acceptance
    :gepa/subsample-evaluation-started  ;; Subsample eval flow (Python GEPA parity)
    :gepa/subsample-evaluated           ;; Subsample result
    :gepa/merge-attempted             ;; Track merge attempts
    :gepa/crossover-performed
    :gepa/optimization-completed
    :gepa/optimization-failed})

(def population-event-types
  "Events that affect the population read model."
  #{:gepa/optimization-started
    :gepa/candidate-created
    :gepa/candidate-evaluation-started
    :gepa/candidate-evaluated})

(def pareto-frontier-event-types
  "Events that affect the Pareto frontier read model."
  #{:gepa/optimization-started
    :gepa/candidate-evaluated
    :gepa/frontier-updated})

(def optimization-state-event-types
  "Events that affect the optimization state read model."
  #{:gepa/optimization-started
    :gepa/candidate-created
    :gepa/candidate-evaluated
    :gepa/subsample-evaluated
    :gepa/optimization-completed
    :gepa/optimization-failed})

(def datasets-event-types
  "Events that affect the datasets read model."
  #{:gepa/datasets-stored})

(def optimization-list-event-types
  "Events that affect the optimization list read model."
  #{:gepa/optimization-started
    :gepa/optimization-completed
    :gepa/optimization-failed})

;; =============================================================================
;; Full GEPA State Read Model (Matches Python GEPAState)
;; =============================================================================

(defn gepa-state
  "Projects events into full GEPA state matching Python's GEPAState structure.

   Python GEPAState fields mapped:
   - program_candidates -> :program-candidates (vec of instruction maps)
   - program_full_scores_val_set -> :agg-scores (vec of doubles)
   - prog_candidate_val_subscores -> :val-subscores ({cand-id -> {instance-id -> score}})
   - parent_program_for_candidate -> :parent-list (vec of [parent-ids])
   - named_predictor_id_to_update_next_for_program_candidate -> :round-robin-state
   - list_of_named_predictors -> :predictor-names (vec of strings)

   Usage:
   (let [events (into [] (es/read event-store {:tags #{[:optimization opt-id]}}))
         state (gepa-state {} events)]
     (:program-candidates state))"
  [state events]
  (reduce
    (fn [s event]
      ;; Note: grain's ->event flattens body into top-level, so we access fields directly
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          (-> s
              (assoc :optimization-id (:optimization-id event))
              (assoc :sheet-id (:sheet-id event))
              (assoc :config (:config event))
              (assoc :trainset-size (:trainset-size event))
              (assoc :valset-size (:valset-size event))
              (assoc :status :running)
              (assoc :started-at (str (:started-at event)))
              ;; Python-matching state
              (assoc :program-candidates [])       ;; vec of instruction maps
              (assoc :agg-scores [])               ;; vec of aggregate scores
              (assoc :val-subscores {})            ;; {cand-idx -> {instance-id -> score}}
              (assoc :parent-list [])              ;; vec of [parent-idx ...] per candidate
              (assoc :round-robin-state {})        ;; {cand-idx -> next-predictor-idx}
              (assoc :predictor-names nil)         ;; set on first candidate
              (assoc :candidate-id->idx {})        ;; uuid -> idx mapping
              ;; Pareto front matching Python structure
              (assoc :pareto-front-mapping {})     ;; {instance-id -> #{cand-idx}}
              (assoc :max-scores {})               ;; {instance-id -> max-score}
              ;; Tracking
              (assoc :total-metric-calls 0)
              (assoc :iteration 0)
              (assoc :evaluation-cache {})         ;; {[cand-hash example-id] -> result}
              (assoc :merge-state {:ancestor-logs #{} :merge-descs #{}}))

          :gepa/datasets-stored
          (-> s
              (assoc :trainset (:trainset event))
              (assoc :valset (:valset event)))

          :gepa/candidate-created
          (let [{:keys [candidate-id instructions parent-ids generation]} event
              idx (count (:program-candidates s))
              ;; Extract predictor names from first candidate
              pred-names (or (:predictor-names s) (vec (keys instructions)))
              ;; Map parent UUIDs to indices
              parent-indices (mapv (fn [pid]
                                     (get (:candidate-id->idx s) pid))
                                   parent-ids)]
          (-> s
              (update :program-candidates conj instructions)
              (update :agg-scores conj nil)  ;; Will be set on evaluation
              (update :parent-list conj parent-indices)
              (update :round-robin-state assoc idx 0)  ;; Initialize round-robin
              (assoc :predictor-names pred-names)
              (assoc-in [:candidate-id->idx candidate-id] idx)
              (assoc-in [:candidate-details idx]
                        {:candidate-id candidate-id
                         :generation generation
                         :mutation-reason (:mutation-reason event)
                         :source-optimization-id (:source-optimization-id event)})))

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id scores aggregate-score metric-calls]} event
              idx (get (:candidate-id->idx s) candidate-id)]
          (when idx
            (-> s
                (assoc-in [:agg-scores idx] aggregate-score)
                (update :total-metric-calls + (or metric-calls 0))
                ;; Store per-instance scores in val-subscores for cross-run inheritance
                ;; This ensures get-pareto-candidates-from-optimization returns subscores
                (assoc-in [:val-subscores idx]
                          (into {} (map-indexed (fn [i score] [i score]) scores)))
                ;; Update Pareto front mapping
                (as-> s'
                      (reduce-kv
                        (fn [acc instance-idx score]
                          (let [current-max (get-in acc [:max-scores instance-idx]
                                                    Double/NEGATIVE_INFINITY)]
                            (cond
                              (> score current-max)
                              (-> acc
                                  (assoc-in [:max-scores instance-idx] score)
                                  (assoc-in [:pareto-front-mapping instance-idx] #{idx}))

                              (== score current-max)
                              (update-in acc [:pareto-front-mapping instance-idx]
                                         (fnil conj #{}) idx)

                              :else acc)))
                        s'
                        (zipmap (range) scores))))))

        :gepa/example-evaluated
          (let [{:keys [candidate-id example-id score]} event
                idx (get (:candidate-id->idx s) candidate-id)]
            (when idx
              (assoc-in s [:val-subscores idx example-id] score)))

          :gepa/reflective-dataset-built
          (let [{:keys [candidate-id components dataset]} event
                idx (get (:candidate-id->idx s) candidate-id)]
            (when idx
              (assoc-in s [:reflective-datasets idx] {:components components
                                                      :dataset dataset})))

          :gepa/candidate-acceptance-decision
          (let [{:keys [candidate-id accepted? subsample-scores-before subsample-scores-after]} event
                idx (get (:candidate-id->idx s) candidate-id)]
            (when idx
              (assoc-in s [:acceptance-decisions idx]
                        {:accepted? accepted?
                         :before subsample-scores-before
                         :after subsample-scores-after})))

          ;; Subsample evaluation events (Python GEPA parity)
          :gepa/subsample-evaluation-started
          (update s :iteration inc)  ;; Count as iteration for epoch-shuffled sampling

          :gepa/subsample-evaluated
          (let [{:keys [parent-id proposed-instructions accepted? metric-calls]} event]
            (-> s
                ;; Track total metric calls from subsample evals
                (update :total-metric-calls + (or metric-calls 0))
                ;; Track subsample decisions for debugging/analysis
                (update :subsample-decisions (fnil conj [])
                        {:parent-id parent-id
                         :accepted? accepted?
                         :proposed-instructions proposed-instructions})))

          :gepa/merge-attempted
          (let [{:keys [id1 id2 ancestor merge-desc]} event]
            (-> s
                (update-in [:merge-state :ancestor-logs] conj [id1 id2 ancestor])
                (cond-> merge-desc
                  (update-in [:merge-state :merge-descs] conj [id1 id2 merge-desc]))))

          :gepa/optimization-completed
          (assoc s
                 :status :completed
                 :completed-at (str (:completed-at event))
                 :best-candidate-id (:best-candidate-id event)
                 :final-score (:final-score event)
                 :improvement (:improvement event))

          :gepa/optimization-failed
          (assoc s
                 :status :failed
                 :completed-at (str (:failed-at event))
                 :error-message (:error-message event))

          ;; Default: return unchanged
          s)))
    state
    events))

(defreadmodel :gepa gepa-state
  {:events gepa-event-types :version 1}
  [state event] (gepa-state state [event]))

;; =============================================================================
;; Population Read Model (simpler view)
;; =============================================================================

(defn population
  "Projects candidate events into population state.
   This is a simpler view focused on candidate data."
  [state events]
  (reduce
    (fn [pop event]
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          (-> pop
              (assoc :optimization-id (:optimization-id event))
              (assoc :sheet-id (:sheet-id event))
              (assoc :config (:config event))
              (assoc :candidates {})
              (assoc :evaluated #{})
              (assoc :total-metric-calls 0))

          :gepa/candidate-created
          (assoc-in pop [:candidates (:candidate-id event)]
                    {:candidate-id (:candidate-id event)
                     :instructions (:instructions event)
                     :parent-ids (:parent-ids event)
                     :generation (:generation event)
                     :mutation-reason (:mutation-reason event)
                     :source-optimization-id (:source-optimization-id event)
                     :historical-val-subscores (:historical-val-subscores event)  ;; For skip-eval
                     :created-at (str (:created-at event))
                     :status :pending})

          :gepa/candidate-evaluation-started
          (update-in pop [:candidates (:candidate-id event)]
                     assoc :status :evaluating)

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id scores aggregate-score feedbacks outputs metric-calls]} event]
            (-> pop
                (update-in [:candidates candidate-id] merge
                           {:scores scores
                            :aggregate-score aggregate-score
                            ;; Per-instance RICH judge feedback (instance-idx ->
                            ;; feedback string); used by build-reflective-dataset.
                            :feedbacks (or feedbacks {})
                            ;; Per-instance generated OUTPUT (instance-idx ->
                            ;; output map); used by build-reflective-dataset.
                            :outputs (or outputs {})
                            :status :evaluated})
                (update :evaluated (fnil conj #{}) candidate-id)
                (update :total-metric-calls + (or metric-calls 0))))

          ;; Default: return unchanged
          pop)))
    state
    events))

(defreadmodel :gepa population
  {:events population-event-types :version 1}
  [state event] (population state [event]))

;; =============================================================================
;; Pareto Frontier Read Model
;; =============================================================================

(defn pareto-frontier
  "Projects evaluated candidates into Pareto frontier state.
   Returns structure matching Python's pareto_front_mapping."
  [state events]
  (reduce
    (fn [frontier event]
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          (-> frontier
              (assoc :optimization-id (:optimization-id event))
              (assoc :max-scores {})
              (assoc :best-at {})
              (assoc :frontier-members #{}))

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id scores]} event]
            (reduce-kv
              (fn [f idx score]
                (let [current-best (get-in f [:max-scores idx] Double/NEGATIVE_INFINITY)]
                  (cond
                    (> score current-best)
                    (-> f
                        (assoc-in [:max-scores idx] score)
                        (assoc-in [:best-at idx] #{candidate-id})
                        (update :frontier-members conj candidate-id))

                    (== score current-best)
                    (-> f
                        (update-in [:best-at idx] (fnil conj #{}) candidate-id)
                        (update :frontier-members conj candidate-id))

                    :else f)))
              frontier
              (zipmap (range) scores)))

          :gepa/frontier-updated
          (let [{:keys [candidate-id instances-best-at]} event]
            (-> frontier
                (update :frontier-members conj candidate-id)
                (as-> f
                      (reduce (fn [acc idx]
                                (update-in acc [:best-at idx] (fnil conj #{}) candidate-id))
                              f
                              instances-best-at))))

          ;; Default: return unchanged
          frontier)))
    state
    events))

(defreadmodel :gepa pareto-frontier
  {:events pareto-frontier-event-types :version 1}
  [state event] (pareto-frontier state [event]))

;; =============================================================================
;; Optimization State Read Model
;; =============================================================================

(defn optimization-state
  "Projects events into overall optimization state summary."
  [state events]
  (reduce
    (fn [opt event]
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          {:optimization-id (:optimization-id event)
           :sheet-id (:sheet-id event)
           :config (:config event)
           :status :running
           :started-at (str (:started-at event))
           :completed-at nil
           :best-score Double/NEGATIVE_INFINITY
           :best-candidate-id nil
           :total-candidates 0
           :total-metric-calls 0}

          :gepa/candidate-created
          (update opt :total-candidates inc)

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id aggregate-score metric-calls]} event
                new-best? (> aggregate-score (:best-score opt Double/NEGATIVE_INFINITY))]
            (-> opt
                (update :total-metric-calls + (or metric-calls 0))
                (cond->
                  new-best?
                  (assoc :best-score aggregate-score
                         :best-candidate-id candidate-id))))

          :gepa/subsample-evaluated
          (update opt :total-metric-calls (fnil + 0) (or (:metric-calls event) 0))

          :gepa/optimization-completed
          (assoc opt
                 :status :completed
                 :completed-at (str (:completed-at event))
                 :best-candidate-id (:best-candidate-id event)
                 :best-score (:final-score event))

          :gepa/optimization-failed
          (assoc opt
                 :status :failed
                 :completed-at (str (:failed-at event))
                 :error-message (:error-message event))

          ;; Default: return unchanged
          opt)))
    state
    events))

(defreadmodel :gepa optimization-state
  {:events optimization-state-event-types :version 1}
  [state event] (optimization-state state [event]))

;; =============================================================================
;; Datasets Read Model
;; =============================================================================

(defn datasets
  "Projects datasets-stored events into dataset state."
  [state events]
  (reduce
    (fn [s event]
      (let [type (:event/type event)]
        (case type
          :gepa/datasets-stored
          (assoc s
                 :trainset (:trainset event)
                 :valset (:valset event))
          ;; Default: return unchanged
          s)))
    state
    events))

(defreadmodel :gepa datasets
  {:events datasets-event-types :version 1}
  [state event] (datasets state [event]))

;; =============================================================================
;; Optimization List Read Model
;; =============================================================================

(defn optimization-list-reducer
  "Reduces optimization lifecycle events into a map of
   {optimization-id -> {:optimization-id :sheet-id :status :started-at ...}}."
  [state event]
  (case (:event/type event)
    :gepa/optimization-started
    (assoc state (:optimization-id event)
           {:optimization-id (:optimization-id event)
            :sheet-id (:sheet-id event)
            :status :running
            :started-at (str (:started-at event))})

    :gepa/optimization-completed
    (-> state
        (assoc-in [(:optimization-id event) :status] :completed)
        (assoc-in [(:optimization-id event) :completed-at] (str (:completed-at event))))

    :gepa/optimization-failed
    (-> state
        (assoc-in [(:optimization-id event) :status] :failed)
        (assoc-in [(:optimization-id event) :failed-at] (str (:failed-at event))))

    state))

(defreadmodel :gepa optimization-list
  {:events optimization-list-event-types :version 1}
  [state event] (optimization-list-reducer state event))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn get-gepa-state
  "Get the full GEPA state for an optimization (Python-compatible)."
  [ctx optimization-id]
  (rmp/project ctx :gepa/gepa-state {:tags #{[:optimization optimization-id]}}))

(defn get-population-state
  "Get the current population state for an optimization."
  [ctx optimization-id]
  (rmp/project ctx :gepa/population {:tags #{[:optimization optimization-id]}}))

(defn get-pareto-frontier-state
  "Get the current Pareto frontier for an optimization."
  [ctx optimization-id]
  (rmp/project ctx :gepa/pareto-frontier {:tags #{[:optimization optimization-id]}}))

(defn get-optimization-summary
  "Get the overall optimization state summary."
  [ctx optimization-id]
  (rmp/project ctx :gepa/optimization-state {:tags #{[:optimization optimization-id]}}))

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

(defn get-pareto-members
  "Get all candidates currently in the Pareto frontier."
  [ctx optimization-id]
  (let [pop-state (get-population-state ctx optimization-id)
        frontier (get-pareto-frontier-state ctx optimization-id)
        member-ids (:frontier-members frontier)]
    (select-keys (:candidates pop-state) member-ids)))

(defn budget-exhausted?
  "Check if the metric call budget has been exhausted."
  [ctx optimization-id]
  (let [opt-state (get-optimization-summary ctx optimization-id)
        max-calls (get-in opt-state [:config :max-metric-calls] 50)
        current-calls (:total-metric-calls opt-state 0)]
    (>= current-calls max-calls)))

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

(defn get-datasets
  "Get the trainset and valset for an optimization.
   Returns {:trainset [...] :valset [...]} or nil if not found."
  [ctx optimization-id]
  (let [state (rmp/project ctx :gepa/datasets {:tags #{[:optimization optimization-id]}})]
    (when (:trainset state)
      {:trainset (:trainset state)
       :valset (:valset state)})))

(defn get-trainset
  "Get the trainset for an optimization."
  [ctx optimization-id]
  (:trainset (get-datasets ctx optimization-id)))

(defn get-valset
  "Get the valset for an optimization."
  [ctx optimization-id]
  (:valset (get-datasets ctx optimization-id)))

(defn sample-pareto-frontier
  "Sample a candidate from the Pareto frontier weighted by instance coverage.
   Uses Python GEPA's exact selection algorithm."
  [ctx optimization-id]
  (let [state (get-gepa-state ctx optimization-id)
        pareto-mapping (:pareto-front-mapping state)
        agg-scores (vec (:agg-scores state))]
    (when (and (seq pareto-mapping) (seq (filter some? agg-scores)))
      ;; Get candidate indices that appear in any instance
      (let [candidate-counts (->> (vals pareto-mapping)
                                  (mapcat identity)
                                  frequencies)
            ;; Weight by number of instances each candidate is best at
            candidates-with-weights (vec candidate-counts)
            total-weight (reduce + (map second candidates-with-weights))]
        (when (pos? total-weight)
          ;; Weighted random selection
          (let [r (rand total-weight)]
            (loop [remaining r
                   [[cand-idx weight] & rest-cands] candidates-with-weights]
              (if (or (nil? cand-idx) (< remaining weight))
                ;; Return the candidate-id for this index
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
           (filter #(< (:score %) 0.7))  ;; Below 70% threshold
           vec))))

;; =============================================================================
;; Cross-Optimization Queries (for auto-inheritance)
;; =============================================================================

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
                 :val-subscores (get (:val-subscores state) idx {})  ;; Per-instance scores
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
  (let [;; Get ALL previous optimizations (no limit)
        all-opts (->> (vals (rmp/project ctx :gepa/optimization-list {:tags #{[:sheet sheet-id]}}))
                      (filter #(= :completed (:status %)))
                      (sort-by :completed-at #(compare %2 %1)))

        ;; Extract all Pareto candidates with source tracking
        all-candidates (->> all-opts
                            (mapcat (fn [opt-event]
                                      (let [opt-id (:optimization-id opt-event)]
                                        (map #(assoc % :source-optimization-id opt-id)
                                             (get-pareto-candidates-from-optimization ctx opt-id))))))

        ;; Deduplicate by instruction hash, keeping best aggregate score
        ;; AND merging per-instance scores (keeping best per instance)
        unique-by-hash (reduce (fn [acc cand]
                                 (let [inst-hash (hash (:instructions cand))
                                       existing (get acc inst-hash)]
                                   (if (nil? existing)
                                     ;; First time seeing this instruction
                                     (assoc acc inst-hash cand)
                                     ;; Merge: keep best aggregate, merge subscores
                                     (let [merged-subscores (merge-with max
                                                              (or (:val-subscores existing) {})
                                                              (or (:val-subscores cand) {}))
                                           ;; Keep the candidate with better aggregate score
                                           ;; but use merged subscores
                                           best-cand (if (> (or (:aggregate-score cand) 0)
                                                            (or (:aggregate-score existing) 0))
                                                       cand
                                                       existing)]
                                       (assoc acc inst-hash
                                              (assoc best-cand :val-subscores merged-subscores))))))
                               {}
                               all-candidates)]

    ;; Return sorted by score, limited
    (->> (vals unique-by-hash)
         (sort-by :aggregate-score >)
         (take limit))))

(defn inherit-from-previous-runs
  "Get candidates to inherit from ALL previous optimization runs.

   Uses cumulative deduplication - same instruction across runs only counted once.
   This ensures 5 epochs × 20 runs = 100 epochs × 1 run."
  [ctx sheet-id & {:keys [limit] :or {limit 10}}]
  (get-all-unique-candidates ctx sheet-id :limit limit))

(defn get-cumulative-pareto-frontier
  "Build a Pareto frontier spanning ALL optimization runs for a sheet.
   This simulates what Python GEPA would have after a single continuous run.

   Returns:
   {:max-scores {instance-id -> max-score}  ;; Best score ever per instance
    :frontier-members [candidates...]        ;; Candidates on the frontier
    :frontier-count int}                     ;; Number of frontier members"
  [ctx sheet-id]
  (let [;; Get all candidates with subscores from all runs
        all-candidates (get-all-unique-candidates ctx sheet-id :limit 1000)

        ;; Build max-scores per instance across all candidates
        max-scores (reduce (fn [acc cand]
                            (reduce-kv (fn [acc2 inst-id score]
                                         (update acc2 inst-id (fnil max 0.0) score))
                                       acc
                                       (or (:val-subscores cand) {})))
                          {}
                          all-candidates)

        ;; Find which candidates are at the frontier for each instance
        ;; A candidate is on the frontier if it achieves the max score on at least one instance
        frontier-members (filter (fn [cand]
                                  (some (fn [[inst-id score]]
                                          (= score (get max-scores inst-id)))
                                        (or (:val-subscores cand) {})))
                                all-candidates)]

    {:max-scores max-scores
     :frontier-members (vec frontier-members)
     :frontier-count (count frontier-members)}))

;; =============================================================================
;; Round-Robin State Helpers
;; =============================================================================

(defn get-next-component-to-update
  "Get the next component to update for a candidate using round-robin."
  [state candidate-idx]
  (let [predictor-names (:predictor-names state)
        round-robin-idx (get (:round-robin-state state) candidate-idx 0)]
    (when (seq predictor-names)
      (nth predictor-names round-robin-idx))))

(defn advance-round-robin
  "Advance the round-robin state for a candidate after mutation."
  [state candidate-idx]
  (let [num-predictors (count (:predictor-names state))
        current (get (:round-robin-state state) candidate-idx 0)
        next-idx (mod (inc current) num-predictors)]
    (assoc-in state [:round-robin-state candidate-idx] next-idx)))

;; =============================================================================
;; Evaluation Cache Helpers
;; =============================================================================

(defn cache-key
  "Generate cache key for evaluation result."
  [candidate-hash example-id]
  [candidate-hash example-id])

(defn get-cached-evaluation
  "Get cached evaluation result if available."
  [state candidate example-id]
  (let [cand-hash (hash candidate)]
    (get-in state [:evaluation-cache (cache-key cand-hash example-id)])))

(defn cache-evaluation
  "Cache an evaluation result."
  [state candidate example-id result]
  (let [cand-hash (hash candidate)]
    (assoc-in state [:evaluation-cache (cache-key cand-hash example-id)] result)))

;; =============================================================================
;; Combined Event Application
;; =============================================================================

(defn apply-events
  "Apply a sequence of events to build combined read model state."
  [events]
  {:gepa-state (gepa-state {} events)
   :population (population {} events)
   :frontier (pareto-frontier {} events)
   :optimization (optimization-state {} events)})
