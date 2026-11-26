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
            [ai.obney.orc.gepa.core.logic :as logic]
            [ai.obney.orc.gepa.core.periodic-tasks]
            [ai.obney.orc.gepa.core.queries :as queries]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.todo-processors :as todo]))

(defn register-completion!
  "Register a promise for an optimization-id. Returns the promise.
   Used by optimize! with :block? true to wait for completion."
  [optimization-id]
  (logic/register-completion! optimization-id))

(defn deliver-completion!
  "Deliver a result to any waiting promise for an optimization-id.
   Called by todo processors when optimization completes or fails."
  [optimization-id result]
  (logic/deliver-completion! optimization-id result))

;; =============================================================================
;; Event Types
;; =============================================================================

(def gepa-event-types
  "All GEPA event types for event store filtering."
  logic/gepa-event-types)

;; =============================================================================
;; Python GEPA Prompt Template
;; =============================================================================

(def python-gepa-prompt-template
  "EXACT copy of Python GEPA's InstructionProposalSignature.default_prompt_template.
   Use this for custom integrations requiring the exact prompt."
  logic/python-gepa-prompt-template)

(def general-rules-prompt-template
  "Optional reflection prompt: GEPA's default template plus three sentences that
   ask for general rules instead of copied example texts. Pass it as
   `:reflection-prompt-template`; nothing selects it by default."
  logic/general-rules-prompt-template)

;; =============================================================================
;; Metric Functions
;; =============================================================================

(defn make-exact-match-metric
  "Create a metric function that checks for exact match on a key.

   Usage:
   (make-exact-match-metric \"answer\")
   ;; Returns 1.0 if output[\"answer\"] == expected[\"answer\"], else 0.0"
  [output-key]
  (logic/make-exact-match-metric output-key))

(defn make-contains-metric
  "Create a metric function that checks if output contains expected.

   Usage:
   (make-contains-metric \"answer\")
   ;; Returns 1.0 if expected is substring of output, else 0.0"
  [output-key]
  (logic/make-contains-metric output-key))

(defn make-judge-metric
  "Create a metric function from orc-service judges.

   Usage:
   (make-judge-metric {:grounding 0.35
                       :completeness 0.25
                       :instruction-following 0.25
                       :reasoning 0.15})

   Note: Judges are run on the execution trace, not direct comparison."
  [judge-weights]
  (logic/make-judge-metric judge-weights))

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
  (commands/optimize! context opts))

(defn get-best-candidate
  "Get the current best candidate by aggregate score.

   Returns the candidate map or anomaly if not found."
  [context optimization-id]
  (rm/get-best-candidate context optimization-id))

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
  (apply logic/propose-new-instruction
         llm-fn current-instruction formatted-examples opts))

(defn render-proposal-prompt
  "Render the proposal prompt with Python GEPA's exact template.

   Fills in <curr_param> and <side_info> placeholders."
  ([current-instruction formatted-examples]
   (logic/render-proposal-prompt current-instruction formatted-examples))
  ([current-instruction formatted-examples prompt-template]
   (logic/render-proposal-prompt current-instruction formatted-examples prompt-template)))

(defn extract-instruction-from-response
  "Extract instruction from ``` blocks using Python GEPA's exact logic."
  [lm-output]
  (logic/extract-instruction-from-response lm-output))

(defn make-reflective-example
  "Create a ReflectiveExample matching Python's TypedDict structure.

   Keys: \"Inputs\", \"Generated Outputs\", \"Feedback\""
  [inputs generated-outputs feedback]
  (logic/make-reflective-example inputs generated-outputs feedback))

(defn format-reflective-examples
  "Format reflective examples as markdown for the proposer.

   Matches Python's format_samples function."
  [examples]
  (logic/format-reflective-examples examples))

(defn build-orc-reflective-dataset
  "Build reflective dataset from ORC evaluation results."
  [evaluation-results components feedback-fns]
  (logic/build-orc-reflective-dataset evaluation-results components feedback-fns))

(defn remove-dominated-programs
  "Remove dominated programs from Pareto front.

   EXACT match to Python's remove_dominated_programs."
  ([pareto-front-mapping]
   (logic/remove-dominated-programs pareto-front-mapping))
  ([pareto-front-mapping scores]
   (logic/remove-dominated-programs pareto-front-mapping scores)))

(defn find-dominator-programs
  "Find non-dominated programs in Pareto front.

   EXACT match to Python's find_dominator_programs."
  [pareto-front-mapping agg-scores]
  (logic/find-dominator-programs pareto-front-mapping agg-scores))

(defn select-program-candidate-from-pareto-front
  "Select a candidate from Pareto front for mutation.

   EXACT match to Python's select_program_candidate_from_pareto_front."
  [pareto-front-mapping agg-scores rng]
  (logic/select-program-candidate-from-pareto-front pareto-front-mapping agg-scores rng))

(defn select-component-round-robin
  "Select component to mutate using round-robin.

   EXACT match to Python's RoundRobinReflectionComponentSelector."
  [candidate-idx component-names round-robin-state]
  (logic/select-component-round-robin candidate-idx component-names round-robin-state))

(def idxmax
  "Return index of maximum value. Matches Python's idxmax."
  logic/idxmax)

(defn sample-and-attempt-merge
  "Sample candidates and attempt merge via common ancestor.

   EXACT match to Python's sample_and_attempt_merge_programs_by_common_predictors."
  [agg-scores rng merge-candidates merges-performed program-candidates parent-list & opts]
  (apply logic/sample-and-attempt-merge
         agg-scores rng merge-candidates merges-performed program-candidates parent-list opts))

(defn find-common-ancestor-pair
  "Find a mergeable pair with common ancestor.

   EXACT match to Python's find_common_ancestor_pair."
  [rng parent-list program-indexes merges-performed agg-scores program-candidates & opts]
  (apply logic/find-common-ancestor-pair
         rng parent-list program-indexes merges-performed agg-scores program-candidates opts))

(defn should-accept-merge?
  "Check if merge should be accepted (>= max parent score).

   EXACT match to Python's acceptance criterion."
  [new-scores id1-scores id2-scores]
  (logic/should-accept-merge? new-scores id1-scores id2-scores))

(def empty-merge-state
  "Create empty merge tracking state."
  logic/empty-merge-state)

;; =============================================================================
;; Workflow Builders
;; =============================================================================

(defn build-proposer-workflow!
  "Build the instruction proposer ORC workflow.

   Returns the sheet-id of the built workflow.
   Call this once during application setup."
  [context]
  (logic/build-proposer-workflow! context))

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

(def recover-in-progress!
  "Request tenant-scoped recovery for abandoned GEPA tasks through Grain processors."
  commands/recover-in-progress!)

(def register-metric!
  "Restore a custom metric by its persisted tenant-scoped identity and revision."
  logic/register!)

(defn apply-winner!
  "Apply a completed optimization winner to the workflow draft and publish a
   new immutable version. `source-version` is mandatory: optimization
   provenance must never silently follow a moving latest-version pointer.

   Returns the source/target fingerprints and version identities."
  [ctx optimization-id source-version]
  (commands/apply-winner! ctx optimization-id source-version))

(defn resume!
  "Resume one incomplete GEPA transition from immutable state.

   The function deliberately advances only the first missing transition. It is
   safe to call repeatedly (or after process reconstruction): command guards
   and terminal events remain authoritative, and completed proposer calls are
   never invoked again merely to reconstruct their proposal.

   The result also reports `:reflection-tokens-used`, the tokens recorded by
   the optimization's proposer calls."
  [ctx optimization-id]
  (todo/resume! ctx optimization-id))

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
  (queries/get-progress context optimization-id))

(defn get-population
  "Get all candidates in an optimization.

   Returns:
   {:optimization-id uuid
    :candidates [...]
    :total int
    :evaluated int
    :total-metric-calls int}"
  [context optimization-id]
  (queries/get-population context optimization-id))

(defn get-pareto-frontier
  "Get the Pareto frontier candidates.

   Returns:
   {:optimization-id uuid
    :frontier-size int
    :members [candidate-maps]
    :max-scores {instance-idx -> score}
    :best-at {instance-idx -> #{candidate-ids}}}"
  [context optimization-id]
  (queries/get-pareto-frontier context optimization-id))

(defn list-optimizations
  "List optimization runs.

   Options:
   - :sheet-id - Filter by target workflow
   - :status - Filter by :running, :completed, or :failed
   - :limit - Max results (default 20)

   Returns:
   {:optimizations [{:optimization-id ... :status ... :best-score ...}]
    :total int}"
  [context & options]
  (apply queries/list-optimizations context options))

(defn get-previous-optimizations
  "Get previous optimization runs for the same sheet.

   Useful for continuing optimization across sessions ('epochs').

   Options:
   - :limit - Max number of previous runs (default 5)

   Returns sequence of optimization events, most recent first."
  [ctx sheet-id & options]
  (apply queries/get-previous-optimizations ctx sheet-id options))
