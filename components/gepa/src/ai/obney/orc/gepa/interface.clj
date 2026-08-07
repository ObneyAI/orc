(ns ai.obney.orc.gepa.interface
  "Public API for GEPA - EXACT 1:1 Python GEPA Parity.

   Native ORC/Grain implementation providing EXACT feature parity
   with the Python GEPA library.

   ## Python GEPA Parity Features

   - EXACT same LLM prompt template for reflective mutation
   - Dominator-based Pareto selection (remove_dominated_programs)
   - Common ancestor merge logic (find_common_ancestor_pair)
   - Round-robin component selection per candidate
   - Strict acceptance criterion (new_sum > old_sum)
   - Cross-run persistence for training data mining

   ## Quick Start

   ```clojure
   (require '[ai.obney.orc.gepa.interface :as gepa])

   ;; Blocking optimization (REPL use)
   (gepa/optimize! ctx
     {:sheet-id my-sheet-id
      :trainset [{\"question\" \"What is 2+2?\" \"answer\" \"4\"}]
      :valset [{\"question\" \"What is 3*3?\" \"answer\" \"9\"}]
      :metric-fn (gepa/make-exact-match-metric \"answer\")
      :config {:max-metric-calls 50}
      :block? true})
   ;; => {:status :completed :best-candidate {...} :best-score 0.85}

   ;; Async optimization (background jobs)
   (def opt-id (:optimization-id
     (gepa/optimize! ctx {:sheet-id ... :valset ...})))
   (gepa/get-progress ctx opt-id)
   (gepa/get-best-candidate ctx opt-id)
   ```

   ## Configuration Options

   | Option | Default | Description |
   |--------|---------|-------------|
   | :max-metric-calls | 50 | Budget for workflow executions |
   | :reflection-minibatch-size | 3 | Failures to sample for mutation |
   | :reflection-lm | claude-sonnet-4 | Model for instruction generation |
   | :use-merge | true | Enable crossover operations |
   | :val-overlap-floor | 5 | Min overlap for merge candidates |
   | :skip-perfect-score | true | Skip mutation if all scores perfect |
   | :inherit-from-previous | true | Auto-inherit from previous runs |"
  (:require [ai.obney.orc.gepa.core.commands :as commands]
            [ai.obney.orc.gepa.core.queries]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.pareto :as pareto]
            [ai.obney.orc.gepa.core.proposer :as proposer]
            [ai.obney.orc.gepa.core.merge :as merge]
            [ai.obney.orc.gepa.core.reflection :as reflection]
            [ai.obney.orc.gepa.core.todo-processors :as todo]
            [ai.obney.orc.gepa.core.metrics :as metrics]
            [ai.obney.orc.gepa.core.optimization :as optimization]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.query-processor.interface :as query-processor]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.time.interface :as time]))

(defn- sha256 [value]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value)
                                  java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn- command!
  [ctx command]
  (command-processor/process-command
    (assoc ctx :command (merge {:command/id (random-uuid)
                                :command/timestamp (time/now)}
                               command))))

(defn- snapshot-instructions
  [version]
  (letfn [(walk [node]
            (cons (select-keys node [:name :instruction])
                  (mapcat walk (:children node))))]
    (->> (walk (get-in version [:snapshot :nodes]))
         (keep (fn [{:keys [name instruction]}]
                 (when (and name instruction) [name instruction])))
         (into (sorted-map)))))

;; =============================================================================
;; Completion Registry (delegated to core.optimization)
;; =============================================================================

(defn register-completion!
  "Register a promise for an optimization-id. Returns the promise.
   Used by optimize! with :block? true to wait for completion."
  [optimization-id]
  (optimization/register-completion! optimization-id))

(defn deliver-completion!
  "Deliver a result to any waiting promise for an optimization-id.
   Called by todo processors when optimization completes or fails."
  [optimization-id result]
  (optimization/deliver-completion! optimization-id result))

;; =============================================================================
;; Event Types
;; =============================================================================

(def gepa-event-types
  "All GEPA event types for event store filtering."
  rm/gepa-event-types)

;; =============================================================================
;; Python GEPA Prompt Template
;; =============================================================================

(def python-gepa-prompt-template
  "EXACT copy of Python GEPA's InstructionProposalSignature.default_prompt_template.
   Use this for custom integrations requiring the exact prompt."
  proposer/python-gepa-prompt-template)

;; =============================================================================
;; Metric Functions
;; =============================================================================

(defn make-exact-match-metric
  "Create a metric function that checks for exact match on a key.

   Usage:
   (make-exact-match-metric \"answer\")
   ;; Returns 1.0 if output[\"answer\"] == expected[\"answer\"], else 0.0"
  [output-key]
  (metrics/make-exact-match-metric output-key))

(defn make-contains-metric
  "Create a metric function that checks if output contains expected.

   Usage:
   (make-contains-metric \"answer\")
   ;; Returns 1.0 if expected is substring of output, else 0.0"
  [output-key]
  (metrics/make-contains-metric output-key))

(defn make-judge-metric
  "Create a metric function from orc-service judges.

   Usage:
   (make-judge-metric {:grounding 0.35
                       :completeness 0.25
                       :instruction-following 0.25
                       :reasoning 0.15})

   Note: Judges are run on the execution trace, not direct comparison."
  [judge-weights]
  (metrics/make-judge-metric judge-weights))

;; =============================================================================
;; High-Level API
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
                   :metric-fn (make-exact-match-metric \"answer\")
                   :block? true})
   ```"
  [context opts]
  (optimization/optimize! context opts))

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
            source-fingerprint (sha256 (snapshot-instructions source))
            target-fingerprint (sha256 (snapshot-instructions target))]
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

(defn resume!
  "Resume one incomplete GEPA transition from immutable state.

   The function deliberately advances only the first missing transition. It is
   safe to call repeatedly (or after process reconstruction): command guards
   and terminal events remain authoritative, and completed proposer calls are
   never invoked again merely to reconstruct their proposal."
  [ctx optimization-id]
  (let [summary (rm/get-optimization-summary ctx optimization-id)
        remaining-budget (- (get-in summary [:config :max-metric-calls] 0)
                            (or (:total-metric-calls summary) 0))
        events (into []
                     (filter #(= optimization-id (:optimization-id %)))
                     (event-store/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)}))
        by-type (group-by :event/type events)
        terminal (last (concat (get by-type :gepa/optimization-completed)
                               (get by-type :gepa/optimization-failed)))
        evaluated-ids (set (map :candidate-id
                                (get by-type :gepa/candidate-evaluated)))
        evaluation-started-ids (set (map :candidate-id
                                         (get by-type :gepa/candidate-evaluation-started)))
        subsample-results (set (map (juxt :parent-id :iteration)
                                    (get by-type :gepa/subsample-evaluated)))
        subsample-starts (set (map (juxt :parent-id :iteration)
                                   (get by-type :gepa/subsample-evaluation-started)))
        pending-proposal (last
                           (remove #(contains? subsample-starts
                                               [(:parent-id %) (:iteration %)])
                                   (get by-type :gepa/proposal-ready)))
        pending-subsample (last
                            (remove #(contains? subsample-results
                                                [(:parent-id %) (:iteration %)])
                                    (get by-type :gepa/subsample-evaluation-started)))
        pending-evaluation (last
                             (remove #(contains? evaluated-ids (:candidate-id %))
                                     (get by-type :gepa/candidate-evaluation-started)))
        pending-candidate (last
                            (remove #(or (contains? evaluated-ids (:candidate-id %))
                                         (contains? evaluation-started-ids (:candidate-id %)))
                                    (get by-type :gepa/candidate-created)))]
    (cond
      terminal
      {:optimization-id optimization-id :status :already-terminal
       :terminal-event (:event/type terminal)}

      pending-subsample
      (do (todo/on-subsample-evaluation-started (assoc ctx :event pending-subsample))
          {:optimization-id optimization-id :status :resumed
           :boundary :subsample-evaluation
           :parent-id (:parent-id pending-subsample)})

      pending-proposal
      (do (command! ctx {:command/name :gepa/mark-optimization-resumed
                         :optimization-id optimization-id})
          (let [started (command! ctx
                                  {:command/name :gepa/evaluate-on-subsample
                                   :optimization-id optimization-id
                                   :parent-id (:parent-id pending-proposal)
                                   :proposed-instructions
                                   (:proposed-instructions pending-proposal)
                                   :component-updated
                                   (:component-updated pending-proposal)
                                   :subsample-indices
                                   (:subsample-indices pending-proposal)
                                   :iteration (:iteration pending-proposal)})
                start-event (some #(when (= :gepa/subsample-evaluation-started
                                                (:event/type %)) %)
                                  (:command-result/events started))]
            ;; Recovery cannot depend on a just-restarted subscriber having
            ;; completed registration before this live event was published.
            ;; Advance the durable start synchronously; stable task-call IDs
            ;; make this safe if a subscriber observed it concurrently.
            (when start-event
              (todo/on-subsample-evaluation-started
                (assoc ctx :event start-event))))
          {:optimization-id optimization-id :status :resumed
           :boundary :proposal-ready
           :remaining-budget remaining-budget
           :parent-id (:parent-id pending-proposal)})

      pending-evaluation
      (do (todo/on-candidate-evaluation-started (assoc ctx :event pending-evaluation))
          {:optimization-id optimization-id :status :resumed
           :boundary :candidate-evaluation
           :candidate-id (:candidate-id pending-evaluation)})

      pending-candidate
      (do (todo/on-candidate-created (assoc ctx :event pending-candidate))
          {:optimization-id optimization-id :status :resumed
           :boundary :candidate-created
           :candidate-id (:candidate-id pending-candidate)})

      :else
      {:optimization-id optimization-id :status :no-recoverable-boundary})))

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
           :query {:query/name :gepa/get-optimization-progress
                   :optimization-id optimization-id})))

(defn get-best-candidate
  "Get the current best candidate by aggregate score.

   Returns the candidate map or anomaly if not found."
  [context optimization-id]
  (rm/get-best-candidate context optimization-id))

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
           :query {:query/name :gepa/get-population
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
           :query {:query/name :gepa/get-pareto-frontier
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
           :query {:query/name :gepa/list-optimizations
                   :sheet-id sheet-id
                   :status status
                   :limit limit})))

;; =============================================================================
;; Python-Compatible State Access
;; =============================================================================

(defn get-gepa-state
  "Get full GEPA state matching Python's GEPAState structure.

   Returns state with Python-compatible field names:
   - :program-candidates (Python: program_candidates)
   - :agg-scores (Python: program_full_scores_val_set)
   - :val-subscores (Python: prog_candidate_val_subscores)
   - :parent-list (Python: parent_program_for_candidate)
   - :round-robin-state (Python: named_predictor_id_to_update_next...)
   - :pareto-front-mapping (Python: pareto_front_mapping)"
  [ctx optimization-id]
  (rm/get-gepa-state ctx optimization-id))

(defn get-population-state
  "Get raw population state from events."
  [ctx optimization-id]
  (rm/get-population-state ctx optimization-id))

(defn get-pareto-frontier-state
  "Get raw Pareto frontier state from events."
  [ctx optimization-id]
  (rm/get-pareto-frontier-state ctx optimization-id))

(defn get-optimization-summary
  "Get optimization state summary from events."
  [ctx optimization-id]
  (rm/get-optimization-summary ctx optimization-id))

(defn budget-exhausted?
  "Check if the metric call budget is exhausted."
  [ctx optimization-id]
  (rm/budget-exhausted? ctx optimization-id))

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

(defn get-pareto-candidates-from-run
  "Get all Pareto-optimal candidates from a completed optimization.

   Use this to mine best instructions from previous runs.

   Returns sequence of {:instructions, :aggregate-score, :candidate-details}."
  [ctx optimization-id]
  (rm/get-pareto-candidates-from-optimization ctx optimization-id))

(defn inherit-from-previous-runs
  "Get candidates to inherit from previous optimization runs.

   Returns top candidates from recent runs, suitable for seeding
   a new optimization.

   Options:
   - :limit - Max candidates to return (default 10)
   - :num-runs - Number of previous runs to check (default 5)"
  [ctx sheet-id & opts]
  (apply rm/inherit-from-previous-runs ctx sheet-id opts))

;; =============================================================================
;; Proposer API (Python GEPA InstructionProposalSignature)
;; =============================================================================

(defn propose-new-instruction
  "Propose a new instruction using the EXACT Python GEPA method.

   Arguments:
   - llm-fn: Function (prompt) -> response
   - current-instruction: The instruction to improve
   - formatted-examples: Pre-formatted reflective examples string

   Options:
   - :prompt-template - Custom template (uses Python default if nil)

   Returns:
   {:proposed-instruction \"extracted instruction\"
    :raw-response \"full LLM response\"}"
  [llm-fn current-instruction formatted-examples & opts]
  (apply proposer/propose-new-instruction
         llm-fn current-instruction formatted-examples opts))

(defn render-proposal-prompt
  "Render the proposal prompt with Python GEPA's exact template.

   Fills in <curr_param> and <side_info> placeholders."
  ([current-instruction formatted-examples]
   (proposer/render-proposal-prompt current-instruction formatted-examples))
  ([current-instruction formatted-examples prompt-template]
   (proposer/render-proposal-prompt current-instruction formatted-examples prompt-template)))

(defn extract-instruction-from-response
  "Extract instruction from ``` blocks using Python GEPA's exact logic."
  [lm-output]
  (proposer/extract-instruction-from-response lm-output))

;; =============================================================================
;; Reflection API (Python GEPA ReflectiveExample)
;; =============================================================================

(defn make-reflective-example
  "Create a ReflectiveExample matching Python's TypedDict structure.

   Keys: \"Inputs\", \"Generated Outputs\", \"Feedback\""
  [inputs generated-outputs feedback]
  (reflection/make-reflective-example inputs generated-outputs feedback))

(defn format-reflective-examples
  "Format reflective examples as markdown for the proposer.

   Matches Python's format_samples function."
  [examples]
  (reflection/format-reflective-examples examples))

(defn build-orc-reflective-dataset
  "Build reflective dataset from ORC evaluation results."
  [evaluation-results components feedback-fns]
  (reflection/build-orc-reflective-dataset evaluation-results components feedback-fns))

;; =============================================================================
;; Pareto Selection API (Python GEPA gepa_utils)
;; =============================================================================

(defn remove-dominated-programs
  "Remove dominated programs from Pareto front.

   EXACT match to Python's remove_dominated_programs."
  ([pareto-front-mapping]
   (pareto/remove-dominated-programs pareto-front-mapping))
  ([pareto-front-mapping scores]
   (pareto/remove-dominated-programs pareto-front-mapping scores)))

(defn find-dominator-programs
  "Find non-dominated programs in Pareto front.

   EXACT match to Python's find_dominator_programs."
  [pareto-front-mapping agg-scores]
  (pareto/find-dominator-programs pareto-front-mapping agg-scores))

(defn select-program-candidate-from-pareto-front
  "Select a candidate from Pareto front for mutation.

   EXACT match to Python's select_program_candidate_from_pareto_front."
  [pareto-front-mapping agg-scores rng]
  (pareto/select-program-candidate-from-pareto-front pareto-front-mapping agg-scores rng))

(defn select-component-round-robin
  "Select component to mutate using round-robin.

   EXACT match to Python's RoundRobinReflectionComponentSelector."
  [candidate-idx component-names round-robin-state]
  (pareto/select-component-round-robin candidate-idx component-names round-robin-state))

(def idxmax
  "Return index of maximum value. Matches Python's idxmax."
  pareto/idxmax)

;; =============================================================================
;; Merge API (Python GEPA proposer/merge.py)
;; =============================================================================

(defn sample-and-attempt-merge
  "Sample candidates and attempt merge via common ancestor.

   EXACT match to Python's sample_and_attempt_merge_programs_by_common_predictors."
  [agg-scores rng merge-candidates merges-performed program-candidates parent-list & opts]
  (apply merge/sample-and-attempt-merge
         agg-scores rng merge-candidates merges-performed program-candidates parent-list opts))

(defn find-common-ancestor-pair
  "Find a mergeable pair with common ancestor.

   EXACT match to Python's find_common_ancestor_pair."
  [rng parent-list program-indexes merges-performed agg-scores program-candidates & opts]
  (apply merge/find-common-ancestor-pair
         rng parent-list program-indexes merges-performed agg-scores program-candidates opts))

(defn should-accept-merge?
  "Check if merge should be accepted (>= max parent score).

   EXACT match to Python's acceptance criterion."
  [new-scores id1-scores id2-scores]
  (merge/should-accept-merge? new-scores id1-scores id2-scores))

(def empty-merge-state
  "Create empty merge tracking state."
  merge/empty-merge-state)

;; =============================================================================
;; Workflow Builders
;; =============================================================================

(defn build-proposer-workflow!
  "Build the instruction proposer ORC workflow.

   Returns the sheet-id of the built workflow.
   Call this once during application setup."
  [context]
  (proposer/build-proposer-workflow! context))

;; =============================================================================
;; Manual Control (for testing and debugging)
;; =============================================================================

(defn create-candidate!
  "Manually create a candidate (for testing)."
  [context optimization-id instructions parent-ids mutation-reason]
  (commands/create-candidate! context optimization-id instructions parent-ids mutation-reason))

(defn record-evaluation!
  "Manually record evaluation results (for testing)."
  [context optimization-id candidate-id scores trace-ids metric-calls]
  (commands/record-evaluation! context optimization-id candidate-id scores trace-ids metric-calls))

(defn complete-optimization!
  "Manually complete an optimization (for testing)."
  [context optimization-id]
  (commands/complete-optimization! context optimization-id))
