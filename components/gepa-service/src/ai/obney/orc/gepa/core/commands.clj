(ns ai.obney.orc.gepa.core.commands
  "Command handlers for GEPA optimization.

   Commands trigger state changes by emitting events.
   All state is derived from events, not stored directly.

   Command flow:
   1. Validate input
   2. Query current state via read models
   3. Apply business rules
   4. Emit events or return anomaly"
  (:require [ai.obney.grain.command-processor-v2.interface :as command-processor :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as event-store :refer [->event]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.gepa.core.logic :as logic]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.orc-service.interface :as sheet]
            [cognitect.anomalies :as anom]
            [malli.core :as m])
  (:import [java.time Instant]))

(defn command!
  [ctx command]
  (command-processor/process-command
    (assoc ctx :command (merge {:command/id (random-uuid)
                                :command/timestamp (time/now)}
                               command))))

;; =============================================================================
;; Command Processing Helper
;; =============================================================================

(defn process-command!
  "Process a command and extract the result.
   The command processor stores events automatically.
   Returns :command/result on success, or anomaly on failure."
  [context]
  (let [result (command-processor/process-command context)]
    (if (::anom/category result)
      result
      (:command/result result))))

;; =============================================================================
;; Main Optimization Entry Point
;; =============================================================================

(defn optimize!
  "Start a GEPA optimization run.

   Arguments:
   - context: Grain context with :event-store, :command-processor, etc.
   - opts: Optimization options
     - :sheet-id - UUID of the workflow to optimize
     - :trainset - Vector of training examples (for reflection)
     - :valset - Vector of validation examples (for scoring)
     - :metric-fn - Scoring function (fn [expected actual] -> 0.0-1.0)
     - :judges - Alternative: use orc-service judges {:grounding 0.35 ...}
     - :config - Optional configuration overrides
     - :inherit-from-previous - Auto-inherit Pareto candidates (default true)
     - :inherit-from - Specific optimization ID to inherit from
     - :block? - If true, block until complete (default false)
     - :timeout-ms - Max wait time when blocking (default 300000 = 5 min)

   Returns (async, :block? false):
   {:optimization-id uuid
    :status :running
    :config merged-config}

   Returns (sync, :block? true):
   {:optimization-id uuid
    :status :completed | :failed | :timeout
    :best-candidate {:instructions {...} :score double}
    :best-score double
    :duration-ms int}

   Example:
   ```clojure
   ;; Async (returns immediately)
   (optimize! ctx {:sheet-id id :valset data})

   ;; Sync (blocks until done)
   (optimize! ctx {:sheet-id id
                   :valset data
                   :metric-fn (metrics/make-exact-match-metric \"answer\")
                   :block? true})
   ```"
  [context {:keys [sheet-id trainset valset metric-fn judges config
                   inherit-from-previous inherit-from block? timeout-ms]
            :or {inherit-from-previous true block? false timeout-ms 300000}
            :as opts}]
  (let [optimization-id (random-uuid)
        start-time (System/currentTimeMillis)
        metric-descriptor (logic/prepare! context optimization-id opts)

        effective-metric-fn (cond
                              metric-fn metric-fn
                              judges (logic/make-judge-metric judges)
                              :else nil)

        ctx-with-metric (if effective-metric-fn
                          (assoc context :gepa/metric-fn effective-metric-fn)
                          context)

        _ (when effective-metric-fn
            (logic/register-metric-fn! optimization-id effective-metric-fn))

        completion-promise (when block?
                             (logic/register-completion! optimization-id))

        cmd-result (process-command!
                     (assoc ctx-with-metric
                            :command {:command/id (random-uuid)
                                      :command/timestamp (time/now)
                                      :command/name :gepa/start-optimization
                                      :optimization-id optimization-id
                                      :metric-descriptor metric-descriptor
                                      :sheet-id sheet-id
                                      :trainset trainset
                                      :valset valset
                                      :config (or config {})
                                      :inherit-from-previous inherit-from-previous
                                      :inherit-from inherit-from}))]

    (if (::anom/category cmd-result)
      (do
        (logic/unregister-metric-fn! optimization-id)
        (when block?
          (logic/unregister-completion! optimization-id))
        cmd-result)

      (if block?
        (let [result (deref completion-promise timeout-ms :ai.obney.orc.gepa.core.optimization/timeout)
              duration-ms (- (System/currentTimeMillis) start-time)]
          (logic/unregister-completion! optimization-id)
          (logic/unregister-metric-fn! optimization-id)
          (if (= result :ai.obney.orc.gepa.core.optimization/timeout)
            {:optimization-id optimization-id
             :status :timeout
             :error "Optimization timed out"
             :duration-ms duration-ms}
            (assoc result :duration-ms duration-ms)))

        {:optimization-id optimization-id
         :status :running
         :config (merge {:max-metric-calls 50} config)}))))

(defn apply-winner!
  "Apply a completed optimization winner to the workflow draft and publish a
   new immutable version. `source-version` is mandatory: optimization
   provenance must never silently follow a moving latest-version pointer.

   Returns the source/target fingerprints and version identities."
  [ctx optimization-id source-version]
  (let [summary (rm/get-optimization-summary ctx optimization-id)
        candidate (rm/get-best-candidate ctx optimization-id)
        sheet-id (:sheet-id summary)
        source (sheet/get-version ctx sheet-id source-version)]
    (when-not (= :completed (:status summary))
      (throw (ex-info "Only a completed optimization can be applied"
                      {:optimization-id optimization-id :status (:status summary)})))
    (when-not source
      (throw (ex-info "Optimization source workflow version does not exist"
                      {:sheet-id sheet-id :source-version source-version})))
    (when-not candidate
      (throw (ex-info "Completed optimization has no selected candidate"
                      {:optimization-id optimization-id})))
    (let [nodes-by-name (->> (sheet/get-nodes-for-sheet ctx sheet-id)
                             (map (juxt :name identity))
                             (into {}))
          instructions (:instructions candidate)
          missing (remove #(contains? nodes-by-name %) (keys instructions))]
      (when (seq missing)
        (throw (ex-info "Winner references workflow components that do not exist"
                        {:missing-components (vec missing)})))
      (doseq [[component instruction] instructions
              :let [node (get nodes-by-name component)]]
        (command! ctx {:command/name :sheet/set-node-instruction
                       :sheet-id sheet-id
                       :node-id (:id node)
                       :instruction instruction}))
      (command! ctx {:command/name :sheet/publish-version
                     :sheet-id sheet-id
                     :description (str "GEPA winner " (:candidate-id candidate)
                                       " from workflow v" source-version)})
      (let [target (sheet/get-latest-version ctx sheet-id)
            target-version (:version-number target)
            source-fingerprint (logic/sha256 (logic/snapshot-instructions source))
            target-fingerprint (logic/sha256 (logic/snapshot-instructions target))]
        (command! ctx {:command/name :gepa/record-winner-application
                       :optimization-id optimization-id
                       :candidate-id (:candidate-id candidate)
                       :sheet-id sheet-id
                       :source-version source-version
                       :target-version target-version
                       :source-fingerprint source-fingerprint
                       :target-fingerprint target-fingerprint})
        {:optimization-id optimization-id
         :candidate-id (:candidate-id candidate)
         :sheet-id sheet-id
         :source-version source-version
         :target-version target-version
         :source-fingerprint source-fingerprint
         :target-fingerprint target-fingerprint}))))

(defn- task-ledger-command! [context operation data]
  (loop [remaining 32]
    (let [result (command-processor/process-command
                   (assoc context :command
                          (merge {:command/id (random-uuid) :command/timestamp (time/now)
                                  :command/name :gepa/manage-task :operation operation} data)))]
      (if (and (pos? remaining) (= ::anom/conflict (::anom/category result)) (:cas result))
        (recur (dec remaining))
        (or (:command/result result) result)))))

(defn open-boundary! [context data] (task-ledger-command! context :open-boundary data))

(defn attention! [context data] (task-ledger-command! context :attention data))

(defn renew! [context data] (task-ledger-command! context :renew data))

(defn dispatch! [context data] (task-ledger-command! context :dispatch data))

(defn begin-metric! [context data] (task-ledger-command! context :metric-started data))

(defn finish! [context data] (task-ledger-command! context :finish data))

(defn claim! [context data] (task-ledger-command! context :claim data))

(defn record-workflow! [context data] (task-ledger-command! context :workflow-completed data))

(defn create-candidate!
  "Create a candidate via command dispatch."
  [context optimization-id instructions parent-ids mutation-reason]
  (process-command!
    (assoc context
           :command {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/create-candidate
                     :optimization-id optimization-id
                     :instructions instructions
                     :parent-ids parent-ids
                     :mutation-reason mutation-reason})))

(defn record-evaluation!
  "Record evaluation results via command dispatch."
  [context optimization-id candidate-id scores trace-ids metric-calls]
  (process-command!
    (assoc context
           :command {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/record-evaluation-result
                     :optimization-id optimization-id
                     :candidate-id candidate-id
                     :scores scores
                     :trace-ids trace-ids
                     :metric-calls metric-calls})))

(defn complete-optimization!
  "Complete an optimization via command dispatch."
  [context optimization-id]
  (process-command!
    (assoc context
           :command {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/complete-optimization
                     :optimization-id optimization-id})))

(defn recover-in-progress!
  "Discover abandoned task boundaries and request processor-owned recovery.
   Healthy task ownership and completed aggregates are left untouched."
  [ctx]
  (when-not (uuid? (:tenant-id ctx))
    (throw (ex-info "GEPA recovery requires a tenant UUID" {})))
  (let [now ((or (:gepa/clock-fn ctx) #(Instant/now)))
        ids (into #{} (map :optimization-id)
                  (event-store/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx) :types #{:gepa/optimization-started}}))]
    (into []
      (comp
        (filter #(logic/due? (rm/get-state ctx %) now))
        (map (fn [id]
               (command-processor/process-command
                 (assoc ctx :command {:command/id (random-uuid) :command/timestamp (time/now)
                                      :command/name :gepa/request-recovery :optimization-id id})))))
      ids)))

(defcommand :gepa start-optimization
  "Start a new GEPA optimization run.

   Creates the optimization context and prepares for
   seed candidate creation and evaluation loop.

   Emits two events:
   1. :gepa/optimization-started - Metadata about the optimization
   2. :gepa/datasets-stored - Full trainset and valset for evaluation"
  [{{:keys [sheet-id trainset valset config optimization-id metric-descriptor]} :command
    :keys [event-store] :as ctx}]
  (let [optimization-id (or optimization-id (random-uuid))
        merged-config (logic/merge-config (or config {}))
        template-error (logic/invalid-reflection-template (:reflection-prompt-template merged-config))
        metric-descriptor (or metric-descriptor
                              (when-not (:gepa/metric-fn ctx)
                                {:kind :default :metric-id "orc/default" :revision "1"
                                 :configuration {} :retry-safe? true
                                 :max-attempts 3 :timeout-ms 300000}))
        now (time/now)
        normalize-keys (fn [m] (into {} (map (fn [[k v]] [(name k) v]) m)))
        normalized-trainset (mapv normalize-keys trainset)
        normalized-valset (mapv normalize-keys valset)]
    (if template-error
      {::anom/category ::anom/incorrect ::anom/message template-error}
    {:command-result/events
     [
      (->event {:type :gepa/optimization-started
                :tags #{[:optimization optimization-id]
                        [:sheet sheet-id]}
                :body (cond-> {:optimization-id optimization-id
                       :sheet-id sheet-id
                       :config merged-config
                       :trainset-size (count trainset)
                       :valset-size (count valset)
                       :started-at now}
                        metric-descriptor (assoc :metric-descriptor metric-descriptor))})
      (->event {:type :gepa/datasets-stored
                :tags #{[:optimization optimization-id]}
                :body {:optimization-id optimization-id
                       :trainset normalized-trainset
                       :valset normalized-valset
                       :stored-at now}})]
     :command/result {:optimization-id optimization-id
                      :config merged-config}})))

(defcommand :gepa select-metric
  {:authorized? (fn [ctx] (uuid? (:tenant-id ctx)))}
  "Adopt an explicitly restored legacy metric once, before evaluation effects."
  [{{:keys [optimization-id metric-descriptor]} :command :as ctx}]
  (let [events (into [] (event-store/read (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :types #{:gepa/optimization-started :gepa/metric-selected}
                          :tags #{[:optimization optimization-id]}}))
        existing (some :metric-descriptor events)]
    (cond
      (empty? events) {::anom/category ::anom/not-found ::anom/message "Optimization not found"}
      existing
      (if (= existing metric-descriptor)
        {:command-result/events [] :command/result {:status :selected :metric-descriptor existing}}
        {::anom/category ::anom/conflict ::anom/message "Optimization metric identity is immutable"})
      :else
      {:command-result/events
       [(->event {:type :gepa/metric-selected :tags #{[:optimization optimization-id]}
                  :body {:optimization-id optimization-id :metric-descriptor metric-descriptor}})]
       :command-result/cas
       {:types #{:gepa/optimization-started :gepa/metric-selected}
        :tags #{[:optimization optimization-id]}
        :predicate-fn (fn [current] (not-any? :metric-descriptor (into [] current)))}
       :command/result {:status :selected :metric-descriptor metric-descriptor}})))

;; =============================================================================
;; Create Candidate
;; =============================================================================

(defcommand :gepa create-candidate
  "Create a new candidate in the population.

   Candidates are instruction sets to be evaluated.
   Parent IDs track lineage for mutation history.
   Source-optimization-id tracks cross-run inheritance.
   Historical-val-subscores enables skipping re-evaluation for inherited candidates."
  [{{:keys [optimization-id instructions parent-ids mutation-reason
            source-optimization-id historical-val-subscores]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)]
    (cond
      (nil? (:optimization-id opt-state))
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"}

      (= :completed (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization already completed"}

      :else
      (let [candidate-id (random-uuid)
            pop-state (rm/get-population-state ctx optimization-id)
            generation (if (some some? parent-ids)
                         (let [parent-gens (->> parent-ids
                                                (filter some?)
                                                (map #(get-in pop-state [:candidates % :generation] 0)))]
                           (if (seq parent-gens)
                             (inc (apply max parent-gens))
                             0))
                         0)]
        {:command-result/events
         [(->event {:type :gepa/candidate-created
                    :tags #{[:optimization optimization-id]
                            [:candidate candidate-id]}
                    :body {:optimization-id optimization-id
                           :candidate-id candidate-id
                           :instructions instructions
                           :parent-ids parent-ids
                           :generation generation
                           :mutation-reason mutation-reason
                           :source-optimization-id source-optimization-id
                           :historical-val-subscores historical-val-subscores
                           :created-at (time/now)}})]
         :command/result {:candidate-id candidate-id
                          :generation generation}}))))

;; =============================================================================
;; Evaluate Candidate
;; =============================================================================

(defcommand :gepa evaluate-candidate
  "Evaluate a candidate on the validation set.

   This command triggers the evaluation process.
   The actual evaluation happens in the todo processor
   which executes the workflow and records traces."
  [{{:keys [optimization-id candidate-id]} :command
    :keys [event-store] :as ctx}]
  (let [candidate (rm/get-candidate ctx optimization-id candidate-id)]
    (cond
      (nil? candidate)
      {::anom/category ::anom/not-found
       ::anom/message "Candidate not found"}

      (= :evaluated (:status candidate))
      {::anom/category ::anom/conflict
       ::anom/message "Candidate already evaluated"}

      :else
      {:command-result/events
       [(->event {:type :gepa/candidate-evaluation-started
                  :tags #{[:optimization optimization-id]
                          [:candidate candidate-id]}
                  :body {:optimization-id optimization-id
                         :candidate-id candidate-id
                         :started-at (time/now)}})]
       :command/result {:status :evaluation-started}})))

;; =============================================================================
;; Record Evaluation Result
;; =============================================================================

(defn- boundary-identities [ctx optimization-id boundary]
  (into #{} (keep (fn [[id entry]] (when (= boundary (:boundary entry)) id)))
        (:boundaries (rm/get-state ctx optimization-id))))

(defn- managed-boundary? [ctx optimization-id boundary]
  (boolean (some #(= boundary (:boundary %))
                 (vals (:boundaries (rm/get-state ctx optimization-id))))))

(defn- managed-tasks
  "Resolve complete, unique membership from the original frozen manifests."
  [ctx optimization-id boundary call-ids]
  (let [state (rm/get-state ctx optimization-id)
        manifest (vec (mapcat (fn [[id entry]]
                               (when (= boundary (:boundary entry))
                                 (map #(assoc % :boundary-id id) (:tasks entry))))
                             (:boundaries state)))
        expected-ids (mapv :call-id manifest)
        tasks (mapv #(get-in state [:tasks (:call-id %)]) manifest)]
    (when (and (seq manifest)
               (= (count call-ids) (count (set call-ids)) (count expected-ids))
               (= (set expected-ids) (set call-ids))
               (every? true?
                 (map (fn [entry task]
                        (and (= :completed (:status task))
                             (= (select-keys entry [:boundary-id :role :example-index])
                                (select-keys task [:boundary-id :role :example-index]))))
                      manifest tasks)))
      tasks)))

(defn- managed-candidate-evidence [ctx command]
  (let [{:keys [optimization-id candidate-id task-call-ids]} command
        tasks (managed-tasks ctx optimization-id
                            {:kind :candidate :candidate-id candidate-id} task-call-ids)]
    {:outputs (into {} (map (fn [task] [(:example-index task) (get-in task [:result :output])])) tasks)
     :feedbacks (into {} (keep (fn [task]
                                (when-some [feedback (get-in task [:result :feedback])]
                                  [(:example-index task) feedback]))) tasks)}))

(defn- valid-managed-candidate? [ctx command]
  (let [{:keys [optimization-id candidate-id task-call-ids scores trace-ids metric-calls]} command
        tasks (sort-by :example-index
                (managed-tasks ctx optimization-id
                  {:kind :candidate :candidate-id candidate-id} task-call-ids))]
    (and (seq tasks)
         (every? (fn [[k v]] (or (not (contains? command k)) (= v (get command k))))
                 (managed-candidate-evidence ctx command))
         (every? #(= :candidate (:role %)) tasks)
         (= (vec (range (count tasks))) (mapv :example-index tasks))
         (= scores (mapv #(get-in % [:result :score]) tasks))
         (= trace-ids (mapv :trace-id tasks))
         (= metric-calls (reduce + (map :attempt-count tasks))))))

(defcommand :gepa record-evaluation-result
  "Record the results of a candidate evaluation.

   Called after workflow execution and trace collection.
   Updates the population with scores and triggers frontier update."
  [{{:keys [optimization-id candidate-id scores trace-ids feedbacks outputs metric-calls accounting-mode task-call-ids]} :command
    :keys [event-store] :as ctx}]
  (let [candidate (rm/get-candidate ctx optimization-id candidate-id)
        boundary {:kind :candidate :candidate-id candidate-id}
        admitted-boundaries (boundary-identities ctx optimization-id boundary)]
    (cond
      (#{:completed :failed} (:status (rm/get-optimization-summary ctx optimization-id)))
      {::anom/category ::anom/conflict ::anom/message "Optimization is terminal"}

      (nil? candidate)
      {::anom/category ::anom/not-found
       ::anom/message "Candidate not found"}

      (:scores candidate)
      {::anom/category ::anom/conflict
       ::anom/message "Candidate already has evaluation results"}

      (and (or (= :tasks accounting-mode)
               (managed-boundary? ctx optimization-id
                                  {:kind :candidate :candidate-id candidate-id}))
           (or (not= :tasks accounting-mode)
               (not (valid-managed-candidate? ctx (:command ctx)))))
      {::anom/category ::anom/conflict
       ::anom/message "Managed evaluation requires completed task evidence"}

      :else
      (let [evidence (when (= :tasks accounting-mode)
                       (managed-candidate-evidence ctx (:command ctx)))
            feedbacks (if evidence (:feedbacks evidence) feedbacks)
            outputs (if evidence (:outputs evidence) outputs)
            aggregate-score (if (seq scores)
                              (/ (reduce + scores) (count scores))
                              0.0)]
        {:command-result/events
         [(->event {:type :gepa/candidate-evaluated
                    :tags #{[:optimization optimization-id]
                            [:candidate candidate-id]}
                    :body (cond-> {:optimization-id optimization-id
                           :candidate-id candidate-id
                           :scores (vec scores)
                           :aggregate-score aggregate-score
                           :trace-ids (vec trace-ids)
                           :feedbacks (or feedbacks {})
                           :outputs (or outputs {})
                           :metric-calls metric-calls
                           :evaluated-at (time/now)}
                            accounting-mode (assoc :accounting-mode accounting-mode :task-call-ids task-call-ids))})]
         :command-result/cas
         {:types #{:gepa/candidate-evaluated :gepa/task-boundary-opened
                   :gepa/optimization-completed :gepa/optimization-failed}
          :tags #{[:optimization optimization-id]}
          :predicate-fn
          (fn [existing]
            (let [facts (into [] existing)]
              (and (not-any? #(#{:gepa/optimization-completed :gepa/optimization-failed} (:event/type %)) facts)
                   (logic/same-admitted-boundaries? facts boundary admitted-boundaries)
                   (not-any? #(and (= :gepa/candidate-evaluated (:event/type %))
                                   (= candidate-id (:candidate-id %))) facts))))}
         :command/result {:aggregate-score aggregate-score
                          :num-scores (count scores)}}))))

;; =============================================================================
;; Update Frontier
;; =============================================================================

(defcommand :gepa update-frontier
  "Update the Pareto frontier with a newly evaluated candidate.

   Determines which instances (if any) this candidate is best at
   and emits a frontier update event if it's Pareto-optimal."
  [{{:keys [optimization-id candidate-id scores]} :command
    :keys [event-store tenant-id] :as ctx}]
  (let [already-updated? (some #(when (and (= optimization-id (:optimization-id %))
                                           (= candidate-id (:candidate-id %))) %)
                               (into [] (event-store/read event-store
                                                {:tenant-id tenant-id
                                                 :types #{:gepa/frontier-updated}})))
        frontier (rm/get-pareto-frontier-state ctx optimization-id)
        instances-best-at
        (reduce-kv
          (fn [best-at idx score]
            (let [current-best (get-in frontier [:max-scores idx] Double/NEGATIVE_INFINITY)]
              (if (>= score current-best)
                (conj best-at idx)
                best-at)))
          []
          (zipmap (range) scores))]

    (cond
      already-updated?
      {:command-result/events []
       :command/result {:pareto-optimal? true
                        :instances-best-at (:instances-best-at already-updated?)
                        :reused? true}}

      (seq instances-best-at)
      {:command-result/events
       [(->event {:type :gepa/frontier-updated
                  :tags #{[:optimization optimization-id]
                          [:candidate candidate-id]}
                  :body {:optimization-id optimization-id
                         :candidate-id candidate-id
                         :instances-best-at instances-best-at
                         :updated-at (time/now)}})]
       :command/result {:pareto-optimal? true
                        :instances-best-at instances-best-at}}

      :else
      {:command/result {:pareto-optimal? false
                        :instances-best-at []}})))

(defcommand :gepa evaluate-on-subsample
  "Trigger subsample evaluation for proposed instructions.

   Python GEPA parity: Before committing to full valset evaluation,
   first evaluate BOTH parent and proposed instructions on a small
   minibatch (3 examples). Only proceed to full eval if the proposed
   instructions show strict improvement (proposed_sum > parent_sum).

   This is the key efficiency optimization:
   - Rejected mutations cost only ~6 metric calls (3 parent + 3 proposed)
   - Accepted mutations cost ~6 + 72 = ~78 calls (subsample + full valset)
   - Net savings: ~60% if 50% rejection rate"
  [{{:keys [optimization-id parent-id proposed-instructions
            component-updated subsample-indices iteration]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        parent (rm/get-candidate ctx optimization-id parent-id)]
    (cond
      (nil? (:optimization-id opt-state))
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"}

      (nil? parent)
      {::anom/category ::anom/not-found
       ::anom/message "Parent candidate not found"}

      (rm/budget-exhausted? ctx optimization-id)
      {::anom/category ::anom/conflict
       ::anom/message "Metric budget exhausted"}

      :else
      {:command-result/events
       [(->event {:type :gepa/subsample-evaluation-started
                  :tags #{[:optimization optimization-id]
                          [:parent parent-id]}
                  :body {:optimization-id optimization-id
                         :parent-id parent-id
                         :proposed-instructions proposed-instructions
                         :component-updated component-updated
                         :subsample-indices (vec subsample-indices)
                         :iteration iteration
                         :started-at (time/now)}})]
       :command/result {:status :subsample-evaluation-started
                        :iteration iteration
                        :subsample-size (count subsample-indices)}})))

(defn- valid-managed-subsample? [ctx command]
  (let [{:keys [optimization-id parent-id iteration task-call-ids subsample-indices
                parent-scores proposed-scores proposed-instructions accepted?
                parent-perfect-skip? metric-calls]} command
        tasks (managed-tasks ctx optimization-id
                {:kind :subsample :parent-id parent-id :iteration iteration} task-call-ids)
        roles (group-by :role tasks)
        parents (:subsample-parent roles)
        proposals (:subsample-proposed roles)
        indices-match? (fn [entries]
                         (and (= (count subsample-indices) (count entries))
                              (= (set subsample-indices) (set (map :example-index entries)))))
        ordered-scores (fn [entries]
                         (let [by-index (into {} (map (juxt :example-index identity) entries))]
                           (mapv #(get-in by-index [% :result :score]) subsample-indices)))
        config (:config (rm/get-optimization-summary ctx optimization-id))]
    (and (seq tasks) (seq subsample-indices)
         (= (count subsample-indices) (count (set subsample-indices)))
         (every? #{:subsample-parent :subsample-proposed} (keys roles))
         (indices-match? parents)
         (= parent-scores (ordered-scores parents))
         (= metric-calls (reduce + (map :attempt-count tasks)))
         (if parent-perfect-skip?
           (and (get config :skip-perfect-score true)
                (empty? proposals)
                (every? #(= 1.0 %) parent-scores)
                (= parent-scores proposed-scores)
                (false? accepted?))
           (and (indices-match? proposals)
                (every? #(= proposed-instructions (:instructions %)) proposals)
                (= proposed-scores (ordered-scores proposals))
                (= accepted? (> (reduce + proposed-scores) (reduce + parent-scores))))))))

(defcommand :gepa record-subsample-result
  "Record the result of subsample evaluation.

   Emits subsample-evaluated event which triggers either:
   - Candidate creation + full eval (if accepted)
   - Another mutation attempt (if rejected)

   The acceptance criterion matches Python GEPA:
   proposed_sum > parent_sum (strict improvement, not >=)"
  [{{:keys [optimization-id proposal-id parent-id proposed-instructions component-updated
            subsample-indices iteration parent-scores proposed-scores accepted? metric-calls accounting-mode task-call-ids parent-perfect-skip?]} :command
    :keys [event-store tenant-id] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        boundary {:kind :subsample :parent-id parent-id :iteration iteration}
        admitted-boundaries (boundary-identities ctx optimization-id boundary)
        parent-sum (reduce + parent-scores)
        proposed-sum (reduce + proposed-scores)
        already-recorded?
        (some #(and (= optimization-id (:optimization-id %))
                    (= parent-id (:parent-id %))
                    (= iteration (:iteration %)))
              (into [] (event-store/read event-store
                                {:tenant-id tenant-id
                                 :types #{:gepa/subsample-evaluated}})))]
    (cond
      (#{:completed :failed} (:status opt-state))
      {::anom/category ::anom/conflict ::anom/message "Optimization is terminal"}

      (nil? (:optimization-id opt-state))
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"}

      (and (or (= :tasks accounting-mode)
               (managed-boundary? ctx optimization-id
                                  {:kind :subsample :parent-id parent-id :iteration iteration}))
           (or (not= :tasks accounting-mode)
               (not (valid-managed-subsample? ctx (:command ctx)))))
      {::anom/category ::anom/conflict
       ::anom/message "Managed subsample requires complete original comparison evidence"}

      already-recorded?
      {:command-result/events []
       :command/result {:status :already-evaluated
                        :parent-id parent-id
                        :iteration iteration}}

      :else
      {:command-result/events
       [(->event {:type :gepa/subsample-evaluated
                  :tags #{[:optimization optimization-id]
                          [:parent parent-id]}
                  :body (cond-> {:optimization-id optimization-id
                         :parent-id parent-id
                         :proposed-instructions proposed-instructions
                         :component-updated component-updated
                         :subsample-indices (vec subsample-indices)
                         :iteration iteration
                         :parent-scores (vec parent-scores)
                         :proposed-scores (vec proposed-scores)
                         :parent-sum parent-sum
                         :proposed-sum proposed-sum
                         :accepted? accepted?
                         :metric-calls metric-calls
                         :evaluated-at (time/now)}
                          accounting-mode (assoc :accounting-mode accounting-mode :task-call-ids task-call-ids
                                                 :parent-perfect-skip? (boolean parent-perfect-skip?)))})]
       :command-result/cas
       {:types #{:gepa/subsample-evaluated :gepa/task-boundary-opened
                 :gepa/optimization-completed :gepa/optimization-failed}
        :tags #{[:optimization optimization-id]}
        :predicate-fn (fn [existing]
                        (let [facts (into [] existing)]
                          (and (not-any? #(#{:gepa/optimization-completed :gepa/optimization-failed} (:event/type %)) facts)
                   (logic/same-admitted-boundaries? facts boundary admitted-boundaries)
                               (not-any? #(and (= :gepa/subsample-evaluated (:event/type %))
                                               (= parent-id (:parent-id %))
                                               (= iteration (:iteration %))) facts))))}
       :command/result {:accepted? accepted?
                        :parent-sum parent-sum
                        :proposed-sum proposed-sum
                        :metric-calls metric-calls}})))

;; =============================================================================
;; Propose Mutation
;; =============================================================================

(defcommand :gepa propose-mutation
  "Propose a mutation for a parent candidate.

   Uses reflective examples (failures with feedback) to guide
   the instruction improvement. The actual mutation happens
   via an ORC workflow in the todo processor."
  [{{:keys [optimization-id parent-candidate-id component-to-update
            reflective-examples]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        parent (rm/get-candidate ctx optimization-id parent-candidate-id)]
    (cond
      (nil? parent)
      {::anom/category ::anom/not-found
       ::anom/message "Parent candidate not found"}

      (rm/budget-exhausted? ctx optimization-id)
      {::anom/category ::anom/conflict
       ::anom/message "Metric budget exhausted"}

      :else
      {:command/result {:status :mutation-queued
                        :parent-candidate-id parent-candidate-id
                        :component-to-update component-to-update
                        :num-examples (count reflective-examples)}})))

;; =============================================================================
;; Record Mutation Result
;; =============================================================================

(defcommand :gepa record-mutation-result
  "Record the result of a mutation proposal.

   Called after the instruction proposer workflow completes.
   Creates the new mutated candidate."
  [{{:keys [optimization-id parent-candidate-id component-updated
            new-instruction mutation-reason]} :command
    :keys [event-store] :as ctx}]
  (let [parent (rm/get-candidate ctx optimization-id parent-candidate-id)
        new-candidate-id (random-uuid)
        new-instructions (assoc (:instructions parent) component-updated new-instruction)]
    {:command-result/events
     [(->event {:type :gepa/mutation-proposed
                :tags #{[:optimization optimization-id]
                        [:candidate new-candidate-id]
                        [:parent parent-candidate-id]}
                :body {:optimization-id optimization-id
                       :parent-candidate-id parent-candidate-id
                       :new-candidate-id new-candidate-id
                       :component-updated component-updated
                       :new-instruction new-instruction
                       :mutation-reason mutation-reason
                       :proposed-at (time/now)}})]
     :command/result {:new-candidate-id new-candidate-id
                      :new-instructions new-instructions}}))

(defcommand :gepa record-proposer-call
  "Persist an actual reflection-model call independently of whether its
   proposal is later accepted. Rejected proposals still consume budget and
   must remain attributable during replay and auditing."
  [{{:keys [optimization-id parent-candidate-id component model provider usage
            prompt response prompt-sha256 response-sha256 call-id]} :command
    :keys [event-store tenant-id]}]
  (if (some #(= call-id (:call-id %))
            (into [] (event-store/read event-store {:tenant-id tenant-id
                                           :types #{:gepa/proposer-call-completed}})))
    {:command-result/events [] :command/result {:call-id call-id :reused? true}}
    {:command-result/events
     [(->event {:type :gepa/proposer-call-completed
              :tags #{[:optimization optimization-id]
                      [:parent parent-candidate-id]}
              :body {:optimization-id optimization-id
                     :parent-candidate-id parent-candidate-id
                     :component component
                     :call-id call-id
                     :provider provider
                     :model model
                     :usage usage
                     :prompt prompt
                     :response response
                     :prompt-sha256 prompt-sha256
                     :response-sha256 response-sha256
                     :completed-at (time/now)}})]
     :command/result {:call-id call-id}}))

(defcommand :gepa mark-optimization-resumed
  "Durably release a pause-after-proposal optimization. The marker is
   idempotent and makes the pause a one-shot crash boundary rather than a
   permanent stop after every subsequent proposal."
  [{{:keys [optimization-id]} :command :keys [event-store tenant-id]}]
  (if (seq (into [] (event-store/read event-store
                             {:tenant-id tenant-id
                              :types #{:gepa/optimization-resumed}
                              :tags #{[:optimization optimization-id]}})))
    {:command-result/events [] :command/result {:status :already-resumed}}
    {:command-result/events
     [(->event {:type :gepa/optimization-resumed
                :tags #{[:optimization optimization-id]}
                :body {:optimization-id optimization-id
                       :resumed-at (time/now)}})]
     :command/result {:status :resumed}}))

(defcommand :gepa record-task-call
  "Persist one completed GEPA workflow/metric evaluation under a stable call
   identity so restart can reuse it without another model call or charge.
   Exact replay is a no-op; different evidence for the same call conflicts."
  [{{:keys [optimization-id call-id role example-index trace-id output score
            feedback model usage]} :command
    :keys [event-store tenant-id]}]
  (let [body {:optimization-id optimization-id
              :call-id call-id
              :role role
              :example-index example-index
              :trace-id trace-id
              :output output
              :score score
              :feedback feedback
              :model model
              :usage usage}
        existing (some #(when (= call-id (:call-id %)) %)
                       (into [] (event-store/read event-store {:tenant-id tenant-id
                                                      :types #{:gepa/task-call-completed}
                                                      :tags #{[:call call-id]}})))]
    (cond
      (logic/managed-call? (into [] (event-store/read event-store
                               {:tenant-id tenant-id :types #{:gepa/task-boundary-opened}})) call-id)
      {::anom/category ::anom/conflict
       ::anom/message "Managed task completion must use the owned ledger attempt"
       :call-id call-id}

      existing
      (if (every? (fn [[k v]] (= v (get existing k))) body)
        {:command-result/events [] :command/result {:call-id call-id :reused? true}}
        {::anom/category ::anom/conflict
         ::anom/message "Task call already has different evaluation results"
         :call-id call-id})
      :else
    {:command-result/events
     [(->event {:type :gepa/task-call-completed
                :tags #{[:optimization optimization-id]
                        [:call call-id]}
                :body (assoc body :completed-at (time/now))})]
     :command-result/cas
     {:types #{:gepa/task-call-completed :gepa/task-boundary-opened}
      :predicate-fn (fn [existing]
                      (let [facts (into [] existing)]
                        (and (not (logic/managed-call? facts call-id))
                             (not-any? #(= call-id (:call-id %)) facts))))}
     :command/result {:call-id call-id}})))

(defcommand :gepa request-parent-minibatch-evaluation
  "Record that a mutation round was opened: the parent, the component and the
   training minibatch it will be evaluated on. The record is the durable
   boundary `resume!` re-runs. A round is identified by its optimization and
   iteration: every round ends in a recorded decision that advances the
   iteration, so a repeat, whatever parent it names, is only a redelivery and
   is a no-op."
  [{{:keys [optimization-id parent-id component iteration subsample-indices]} :command
    :keys [event-store tenant-id]}]
  (let [request-id (java.util.UUID/nameUUIDFromBytes
                     (.getBytes (pr-str [:gepa/parent-minibatch-evaluation optimization-id
                                         iteration])
                                java.nio.charset.StandardCharsets/UTF_8))]
    (if (some #(= request-id (:request-id %))
              (into [] (event-store/read event-store
                         {:tenant-id tenant-id
                          :types #{:gepa/parent-minibatch-evaluation-requested}
                          :tags #{[:optimization optimization-id]}})))
      {:command-result/events [] :command/result {:status :already-requested}}
      {:command-result/events
       [(->event {:type :gepa/parent-minibatch-evaluation-requested
                  :tags #{[:optimization optimization-id]
                          [:request request-id]
                          [:parent parent-id]}
                  :body {:optimization-id optimization-id
                         :request-id request-id
                         :parent-id parent-id
                         :component component
                         :iteration iteration
                         :subsample-indices (vec subsample-indices)
                         :requested-at (time/now)}})]
       :command/result {:status :requested :request-id request-id}})))

(defcommand :gepa record-proposal-ready
  "Persist the complete proposal before its first evaluation. This is the
   explicit crash/resume boundary between a billable proposer call and task
   model evaluation."
  [{{:keys [optimization-id proposal-id parent-id proposed-instructions component-updated
            subsample-indices iteration]} :command
    :keys [event-store tenant-id]}]
  (if (some #(= proposal-id (:proposal-id %))
            (into [] (event-store/read event-store {:tenant-id tenant-id
                                           :types #{:gepa/proposal-ready}})))
    {:command-result/events [] :command/result {:status :already-ready}}
    {:command-result/events
     [(->event {:type :gepa/proposal-ready
                :tags #{[:optimization optimization-id]
                        [:proposal proposal-id]
                        [:parent parent-id]}
                :body {:optimization-id optimization-id
                       :proposal-id proposal-id
                       :parent-id parent-id
                       :proposed-instructions proposed-instructions
                       :component-updated component-updated
                       :subsample-indices subsample-indices
                       :iteration iteration
                       :ready-at (time/now)}})]
     :command/result {:status :ready}}))

(defcommand :gepa record-winner-application
  "Link an immutable optimization winner to the new workflow version created
   from it. The source version remains explicit so provenance cannot drift to
   whatever version happens to be latest later."
  [{{:keys [optimization-id candidate-id sheet-id source-version target-version
            source-fingerprint target-fingerprint]} :command}]
  {:command-result/events
   [(->event {:type :gepa/winner-applied
              :tags #{[:optimization optimization-id]
                      [:candidate candidate-id]
                      [:sheet sheet-id]}
              :body {:optimization-id optimization-id
                     :candidate-id candidate-id
                     :sheet-id sheet-id
                     :source-version source-version
                     :target-version target-version
                     :source-fingerprint source-fingerprint
                     :target-fingerprint target-fingerprint
                     :applied-at (time/now)}})]})

;; =============================================================================
;; Perform Crossover
;; =============================================================================

(defcommand :gepa perform-crossover
  "Perform crossover between two parent candidates.

   Merges instructions from two Pareto-optimal parents.
   Uses the merge engine ORC workflow."
  [{{:keys [optimization-id parent1-id parent2-id]} :command
    :keys [event-store] :as ctx}]
  (let [parent1 (rm/get-candidate ctx optimization-id parent1-id)
        parent2 (rm/get-candidate ctx optimization-id parent2-id)]
    (cond
      (nil? parent1)
      {::anom/category ::anom/not-found
       ::anom/message "Parent 1 not found"}

      (nil? parent2)
      {::anom/category ::anom/not-found
       ::anom/message "Parent 2 not found"}

      :else
      {:command/result {:status :crossover-queued
                        :parent1-id parent1-id
                        :parent2-id parent2-id}})))

;; =============================================================================
;; Record Crossover Result
;; =============================================================================

(defcommand :gepa record-crossover-result
  "Record the result of a crossover operation."
  [{{:keys [optimization-id parent1-id parent2-id merged-instructions]} :command
    :keys [event-store] :as ctx}]
  (let [child-id (random-uuid)]
    {:command-result/events
     [(->event {:type :gepa/crossover-performed
                :tags #{[:optimization optimization-id]
                        [:candidate child-id]
                        [:parent parent1-id]
                        [:parent parent2-id]}
                :body {:optimization-id optimization-id
                       :parent1-id parent1-id
                       :parent2-id parent2-id
                       :child-candidate-id child-id
                       :merged-instructions merged-instructions
                       :performed-at (time/now)}})]
     :command/result {:child-candidate-id child-id}}))

(defcommand :gepa complete-optimization
  "Mark an optimization run as completed.

   Calculates final statistics and identifies the best candidate."
  [{{:keys [optimization-id]} :command
    :keys [event-store tenant-id] :as ctx}]
  (let [managed-events (into [] (event-store/read event-store
                                       {:tenant-id tenant-id :types logic/event-types
                                        :tags #{[:optimization optimization-id]}}))
        managed-state (reduce logic/reduce-event nil managed-events)
        opt-state (rm/get-optimization-summary ctx optimization-id)
        pop-state (rm/get-population-state ctx optimization-id)
        evaluated-candidates (->> (vals (:candidates pop-state))
                                  (filter :aggregate-score))
        best-candidate (when (seq evaluated-candidates)
                         (apply max-key :aggregate-score evaluated-candidates))
        first-candidate (first (sort-by :created-at (vals (:candidates pop-state))))
        initial-score (or (:aggregate-score first-candidate) 0.0)
        final-score (or (:aggregate-score best-candidate) 0.0)
        improvement (- final-score initial-score)]
    (cond
      (#{:completed :failed} (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization is already terminal"}

      (some #(not= :completed (:status %)) (vals (:tasks managed-state)))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization has admitted unfinished managed tasks"}

      (logic/ready-unpublished-comparison? managed-state (:config opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization has completed task evidence awaiting comparison publication"}

      (nil? best-candidate)
      {::anom/category ::anom/incorrect
       ::anom/message "No candidates evaluated"}

      :else
      {:command-result/events
       [(->event {:type :gepa/optimization-completed
                  :tags #{[:optimization optimization-id]
                          [:sheet (:sheet-id opt-state)]}
                  :body {:optimization-id optimization-id
                         :sheet-id (:sheet-id opt-state)
                         :best-candidate-id (:candidate-id best-candidate)
                         :final-score final-score
                         :total-metric-calls (:total-metric-calls pop-state)
                         :num-candidates (count (:candidates pop-state))
                         :improvement improvement
                         :completed-at (time/now)}})]
       :command-result/cas
       {:types logic/event-types :tags #{[:optimization optimization-id]}
        :predicate-fn (fn [current]
                        (let [facts (into [] current)]
                          (and (not-any? #(#{:gepa/optimization-completed :gepa/optimization-failed}
                                           (:event/type %)) facts)
                               (= (mapv :event/id managed-events) (mapv :event/id facts)))))}
       :command/result {:best-candidate-id (:candidate-id best-candidate)
                        :final-score final-score
                        :improvement improvement}})))

;; =============================================================================
;; Fail Optimization
;; =============================================================================

(defcommand :gepa fail-optimization
  "Mark an optimization run as failed."
  [{{:keys [optimization-id error-message]} :command
    :keys [event-store tenant-id] :as ctx}]
  (let [managed-events (into [] (event-store/read event-store
                                       {:tenant-id tenant-id :types logic/event-types
                                        :tags #{[:optimization optimization-id]}}))
        opt-state (rm/get-optimization-summary ctx optimization-id)]
    (cond
      (= :completed (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot fail a completed optimization"}

      (= :failed (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization already failed"}

      :else
      {:command-result/events
       [(->event {:type :gepa/optimization-failed
                  :tags #{[:optimization optimization-id]}
                  :body {:optimization-id optimization-id
                         :error-message error-message
                         :failed-at (time/now)}})]
       :command-result/cas
       {:types logic/event-types :tags #{[:optimization optimization-id]}
        :predicate-fn (fn [current]
                        (let [facts (into [] current)]
                          (and (not-any? #(#{:gepa/optimization-completed :gepa/optimization-failed}
                                           (:event/type %)) facts)
                               (= (mapv :event/id managed-events) (mapv :event/id facts)))))}
       :command/result {:status :failed}})))

(defcommand :gepa request-recovery
  {:authorized? (fn [ctx] (uuid? (:tenant-id ctx)))}
  "Request recovery through the ordinary processor pool, not an unmanaged future."
  [{{:keys [optimization-id]} :command :as ctx}]
  (let [state (rm/get-state ctx optimization-id)]
    (if (and (:optimization-id state) (not (:terminal? state)))
      {:command-result/events
       [(->event {:type :gepa/optimization-recovery-requested
                  :tags #{[:optimization optimization-id]}
                  :body {:optimization-id optimization-id}})]
       :command/result {:status :requested :optimization-id optimization-id}}
      {:command-result/events [] :command/result {:status :not-active}})))

(defcommand :gepa manage-task
  {:authorized? (fn [context] (some? (:auth-claims context)))}
  "Manage durable evaluation work under optimization-scoped atomic admission."
  [{:keys [command] :as context}]
  (let [{:keys [optimization-id boundary-id operation boundary tasks call-id owner-id]} command
        previous (when (uuid? (:tenant-id context)) (rm/records context optimization-id))
        state (reduce logic/reduce-event nil previous)
        instant (logic/now context)]
    (cond
      (not (uuid? (:tenant-id context)))
      {::anom/category ::anom/incorrect ::anom/message "A tenant UUID is required"}
      (nil? state) {::anom/category ::anom/not-found ::anom/message "Optimization not found"}
      (:terminal? state) (logic/reply :optimization-terminal)
      :else
      (case operation
        :open-boundary
        (let [ambiguity (logic/ambiguous-legacy-task previous tasks)]
        (cond
          (contains? (:completed-boundaries state) boundary)
          (logic/reply :boundary-completed {:boundary boundary})
          ambiguity
          (let [issue (assoc ambiguity :boundary-id boundary-id)
                result (assoc issue :status :recovery-required)]
            (if (= issue (get-in state [:adoption-issues boundary-id]))
              (logic/reply :recovery-required issue)
              (logic/append-result optimization-id previous
                             [(logic/event :gepa/task-adoption-blocked optimization-id issue)] result)))
          (some (fn [[id manifest]]
                  (and (not= id boundary-id)
                       (some (set (map :call-id tasks)) (map :call-id (:tasks manifest)))))
                (:boundaries state))
          {::anom/category ::anom/conflict ::anom/message "Task identity already belongs to another boundary"}
          :else
        (if-let [existing (get-in state [:boundaries boundary-id])]
          (if (= existing {:boundary boundary :tasks tasks})
            (logic/reply :opened {:boundary-id boundary-id})
            {::anom/category ::anom/conflict ::anom/message "Evaluation manifest changed"})
          (if (and boundary (seq tasks) (= (count tasks) (count (set (map :call-id tasks)))))
            (let [adoptions
                  (keep (fn [entry]
                          (let [matches (filter #(and (= :gepa/task-call-completed (:event/type %))
                                                      (= (:call-id entry) (:call-id %))) previous)
                                canonical (first matches)
                                traces (vec (distinct (map :trace-id matches)))]
                            (when canonical
                              (logic/event :gepa/task-adopted optimization-id
                                     {:boundary-id boundary-id :call-id (:call-id entry)
                                      :trace-id (:trace-id canonical)
                                      :result (select-keys canonical [:output :score :feedback :model :usage])
                                      :source-event-ids (mapv :event/id matches)
                                      :source-trace-ids traces :observed-attempts (count traces)})))) tasks)]
              (logic/append-result optimization-id previous
                             (into [(logic/event :gepa/task-boundary-opened optimization-id
                                           {:boundary-id boundary-id :boundary boundary :tasks tasks})] adoptions)
                             {:status :opened :boundary-id boundary-id}))
            {::anom/category ::anom/incorrect ::anom/message "A unique nonempty task manifest is required"}))))
        :claim
        (let [entry (some #(when (= call-id (:call-id %)) %)
                          (get-in state [:boundaries boundary-id :tasks]))
              existing (get-in state [:tasks call-id])]
          (cond
            (or (nil? entry) (nil? owner-id))
            {::anom/category ::anom/incorrect ::anom/message "Claim requires a manifest task and owner"}
            (= :completed (:status existing)) (logic/reply :reused {:task existing})
            (and existing (logic/healthy? existing instant)) (logic/reply :pending {:task existing})
            (and (nil? existing) (not (pos? (:available (logic/accounting state))))) (logic/reply :budget-exhausted)
            :else
            (let [body {:boundary-id boundary-id :call-id call-id :trace-id (:trace-id entry)
                        :owner-id owner-id :epoch (inc (:epoch existing 0))
                        :lease-expires-at (str (.plusMillis instant (long (or (:gepa/task-lease-ms context) 90000))))}]
              (logic/append-result optimization-id previous [(logic/event :gepa/task-claimed optimization-id body)]
                             {:status :owned :task (merge entry {:status :claimed :attempt-count 0} existing body)}))))
        :renew
        (let [task (get-in state [:tasks call-id])]
          (if (logic/owner? task command instant)
            (let [body (assoc (select-keys command [:boundary-id :call-id :owner-id :epoch])
                              :lease-expires-at
                              (str (.plusMillis instant (long (or (:gepa/task-lease-ms context) 90000)))))]
              (logic/append-result optimization-id previous [(logic/event :gepa/task-lease-renewed optimization-id body)]
                             {:status :renewed}))
            (logic/reply :stale-owner)))
        :dispatch
        (let [task (get-in state [:tasks call-id]) retry? (= :metric (:attempt-kind command))]
          (cond
            (= :completed (:status task)) (logic/reply :reused {:task task})
            (not (logic/owner? task command instant)) (logic/reply :stale-owner)
            (or (and (not retry?) (pos? (:attempt-count task 0)))
                (some #(= (:attempt-id command) (:attempt-id %)) (:attempts task)))
            (logic/reply :already-dispatched {:task task})
            (and retry? (not (:metric-retry-safe? task))) (logic/reply :recovery-required {:reason :metric-not-retry-safe})
            (and retry? (>= (:attempt-count task 0) (:max-metric-attempts task 3))) (logic/reply :attempt-limit-exceeded)
            (and retry? (not (pos? (:available (logic/accounting state))))) (logic/reply :budget-exhausted)
            (and retry? (nil? (:workflow-result task)))
            {::anom/category ::anom/incorrect ::anom/message "A metric retry requires original workflow outputs"}
            (or (nil? (:attempt-id command)) (not (#{:evaluation :metric} (:attempt-kind command))))
            {::anom/category ::anom/incorrect ::anom/message "Initial dispatch requires an evaluation attempt identity"}
            :else
            (let [body (assoc (select-keys command [:boundary-id :call-id :owner-id :epoch :attempt-id :attempt-kind])
                              :attempt-number (inc (:attempt-count task 0)))]
              (logic/append-result optimization-id previous
                             [(logic/event :gepa/task-attempt-dispatched optimization-id body)]
                             {:status :dispatched :attempt-number (:attempt-number body) :attempt-id (:attempt-id command)}))))
        :workflow-completed
        (let [task (get-in state [:tasks call-id]) result (:result command)]
          (cond
            (not (logic/owner? task command instant)) (logic/reply :stale-owner)
            (:workflow-result task)
            (if (= result (:workflow-result task)) (logic/reply :workflow-recorded {:reused? true})
                {::anom/category ::anom/conflict ::anom/message "Workflow result is immutable"})
            (or (zero? (:attempt-count task 0)) (not= (:trace-id task) (:trace-id result)))
            {::anom/category ::anom/incorrect ::anom/message "Workflow result must reference the dispatched task trace"}
            :else
            (logic/append-result optimization-id previous
                           [(logic/event :gepa/task-workflow-completed optimization-id
                                   (select-keys command [:boundary-id :call-id :owner-id :epoch :result]))]
                           {:status :workflow-recorded})))
        :metric-started
        (let [task (get-in state [:tasks call-id])]
          (cond
            (= :completed (:status task)) (logic/reply :reused {:task task})
            (not (logic/owner? task command instant)) (logic/reply :stale-owner)
            (not= (:attempt-id task) (:attempt-id command)) (logic/reply :stale-attempt)
            (:metric-started? task) (logic/reply :already-started)
            (nil? (:workflow-result task))
            {::anom/category ::anom/incorrect ::anom/message "Metric requires the original durable workflow result"}
            :else
            (logic/append-result optimization-id previous
                           [(logic/event :gepa/task-metric-started optimization-id
                                   (select-keys command [:boundary-id :call-id :owner-id :epoch :attempt-id]))]
                           {:status :metric-started})))
        :finish
        (let [task (get-in state [:tasks call-id]) result (:result command)]
          (cond
            (= :completed (:status task))
            (if (= result (:result task)) (logic/reply :reused {:task task})
                {::anom/category ::anom/conflict ::anom/message "Task result is immutable"})
            (not (logic/owner? task command instant)) (logic/reply :stale-owner)
            (not= (:attempt-id task) (:attempt-id command)) (logic/reply :stale-attempt)
            (some (fn [key] (and (contains? result key) (not= (get result key) (get task key))))
                  [:call-id :trace-id :role :example-index])
            {::anom/category ::anom/conflict ::anom/message "Metric result cannot replace task identity"}
            (not= (:output result) (get-in task [:workflow-result :outputs]))
            {::anom/category ::anom/conflict ::anom/message "Metric output must match the durable workflow result"}
            (or (not (:metric-started? task)) (not (m/validate :gepa/task-result result)))
            {::anom/category ::anom/incorrect ::anom/message "Completion requires a started metric and a valid scored result"}
            :else
            (logic/append-result optimization-id previous
                           [(logic/event :gepa/task-finished optimization-id
                                   (assoc (select-keys command [:boundary-id :call-id :owner-id :epoch :result])
                                          :attempt-id (:attempt-id task)))
                            (logic/event :gepa/task-call-completed optimization-id
                                   (merge (select-keys task [:call-id :role :example-index :trace-id])
                                          (select-keys result [:output :score :feedback :model :usage])
                                          {:accounting-mode :tasks :completed-at (str instant)}))]
                           {:status :completed :result result})))
        :attention
        (let [task (get-in state [:tasks call-id])]
          (cond
            (not (logic/owner? task command instant)) (logic/reply :stale-owner)
            (= :completed (:status task)) (logic/reply :reused {:task task})
            (nil? (:reason command)) {::anom/category ::anom/incorrect ::anom/message "Recovery reason is required"}
            (= (:reason command) (:recovery-reason task)) (logic/reply :recovery-required)
            :else
            (logic/append-result optimization-id previous
                           [(logic/event :gepa/task-recovery-required optimization-id
                                   (select-keys command [:boundary-id :call-id :owner-id :epoch :reason]))]
                           {:status :recovery-required})))))))
