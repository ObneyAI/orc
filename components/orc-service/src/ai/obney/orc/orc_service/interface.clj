(ns ai.obney.orc.orc-service.interface
  "Behavior Tree Sheet Service public interface.

   This namespace loads all core modules for side-effect registration
   and re-exports the public API."
  (:require ;; Load core namespaces for side-effect registration of commands/queries
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.queries]
            [ai.obney.orc.orc-service.core.todo-processors]
            ;; Load schemas for registration
            [ai.obney.orc.orc-service.interface.schemas]
            ;; Re-export from interface sub-namespaces
            [ai.obney.orc.orc-service.interface.read-models :as rm]
            ;; Runtime for synchronous execution
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            ;; DSL for workflow building
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            ;; Executor helpers exposed for downstream LLM callers
            [ai.obney.orc.orc-service.core.executor :as executor]
            ;; Versioning commands (publish/revert) are issued through grain's
            ;; command processor — exposed below as small wrappers.
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Read Models
;; =============================================================================

;; Sheet functions
(def get-sheet rm/get-sheet)
(def get-sheets-all rm/get-sheets-all)
(def get-sheet-by-name rm/get-sheet-by-name)

;; Node functions
(def get-node rm/get-node)
(def get-nodes-for-sheet rm/get-nodes-for-sheet)
(def get-nodes-by-id rm/get-nodes-by-id)
(def get-root-node rm/get-root-node)
(def get-children rm/get-children)
(def get-descendants rm/get-descendants)

;; Blackboard functions
(def get-blackboard-for-sheet rm/get-blackboard-for-sheet)
(def get-blackboard-by-key rm/get-blackboard-by-key)

;; Tick functions
(def get-tick rm/get-tick)
(def get-ticks-for-sheet rm/get-ticks-for-sheet)
(def get-current-tick rm/get-current-tick)

;; Trace functions
(def get-trace rm/get-trace)
(def get-traces-for-sheet rm/get-traces-for-sheet)

;; Version functions
(def get-versions-for-sheet rm/get-versions-for-sheet)
(def get-version rm/get-version)
(def get-latest-version rm/get-latest-version)
(def get-stash rm/get-stash)

;; Tree metadata functions
(def get-tree-metadata rm/get-tree-metadata)
(def get-all-tree-metadata rm/get-all-tree-metadata)
(def find-trees-by-problem-type rm/find-trees-by-problem-type)

;; Rolling metrics functions
(def get-node-rolling-metrics rm/get-node-rolling-metrics)
(def get-tree-rolling-metrics rm/get-tree-rolling-metrics)

;; =============================================================================
;; Synchronous Execution
;; =============================================================================

(def execute
  "Execute a sheet (behavior tree) with inputs and return outputs.

   This is a synchronous, blocking call that:
   1. Creates an isolated execution context (doesn't mutate sheet's blackboard)
   2. Runs the tree to completion
   3. Returns output values
   4. Supports execution mode (draft/published)

   Args:
     context - Map with :event-store and optional :dscloj-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)
     :use-version - Specific version number to execute (overrides execution-mode)
     :force-draft - Force draft execution even if execution-mode is :published

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :error string?
      :executed-version int?}  ;; Present if published version was used

   Example:
     (sheet/execute ctx sheet-id {\"student-id\" student-id} :timeout-ms 60000)
     (sheet/execute ctx sheet-id inputs :use-version 2)  ;; Execute specific version"
  runtime/execute)

;; =============================================================================
;; Workflow DSL
;; =============================================================================

;; Node builders
(def llm dsl/llm)
(def code dsl/code)
(def condition dsl/condition)
(def llm-condition dsl/llm-condition)
(def sequence dsl/sequence)
(def fallback dsl/fallback)
(def parallel dsl/parallel)
(def map-each dsl/map-each)
(def repl-researcher dsl/repl-researcher)
(def delegate dsl/delegate)

;; Schema builder
(def blackboard dsl/blackboard)

;; Judges builder
(def judges dsl/judges)

;; Workflow definition
(def workflow dsl/workflow)

;; Build functions
(def build-workflow! dsl/build-workflow!)
(def build-workflow!! dsl/build-workflow!!)

;; Utilities
(def print-tree dsl/print-tree)
(def describe-workflow dsl/describe-workflow)

;; Export/Import
(def export-sheet dsl/export-sheet)
(def export-to-dsl dsl/export-to-dsl)
(def save-sheet-as-dsl! dsl/save-sheet-as-dsl!)
(def import-sheet dsl/import-sheet)
(def save-sheet! dsl/save-sheet!)
(def load-sheet! dsl/load-sheet!)
(def save-all-sheets! dsl/save-all-sheets!)
(def load-all-sheets! dsl/load-all-sheets!)

;; =============================================================================
;; GEPA Integration (Native Clojure - no Python required)
;; =============================================================================

;; =============================================================================
;; Versioning Commands
;; =============================================================================

(defn publish-version!
  "Snapshot the current draft state of a Sheet as a new immutable
   published version. Returns the command result map. On success the
   first event in `:command-result/events` carries `:version-number`
   and `:snapshot-id`. On failure returns a cognitect anomaly map."
  [ctx sheet-id & {:keys [description]}]
  (cp/process-command
    (assoc ctx :command
           (cond-> {:command/name :sheet/publish-version
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id}
             description (assoc :description description)))))

(defn revert-to-version!
  "Discard the current draft state and restore the Sheet from a
   previously-published version. The dirty draft (if any) is stashed
   first so it can be restored. Returns the command result map or a
   cognitect anomaly map."
  [ctx sheet-id version-number]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/revert-to-version
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :version-number version-number})))

(defn restore-stash!
  "Restore a stashed draft after a revert. Returns the command result
   map or a cognitect anomaly map."
  [ctx sheet-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/restore-stash
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id})))

;; =============================================================================
;; LLM Provider Helpers
;; =============================================================================

(def setup-providers!
  "Register DSCloj providers from environment variables (e.g.
   OPENROUTER_API_KEY). Call once at application startup."
  executor/setup-providers!)

(def list-available-providers
  "List all registered DSCloj providers."
  executor/list-available-providers)

(def get-provider-with-model
  "Resolve a provider keyword + model override into a provider keyword
   that actually applies the model. litellm-clj's router ignores
   :model in request options for registered providers; this helper
   dynamically registers a model-specific provider keyword on demand
   and returns it. Pass nil for `model-override` to get the original
   provider back unchanged.

   Use this in any code that calls dscloj/predict directly with an
   override — it's the same shim every executor path uses."
  executor/get-provider-with-model)

;; GEPA prompt optimization is available via the native Clojure implementation:
;;
;;   (require '[ai.obney.orc.gepa.interface :as gepa])
;;   (gepa/optimize! ctx
;;     {:sheet-id sheet-id
;;      :trainset trainset
;;      :valset valset
;;      :metric-fn (gepa/make-exact-match-metric "answer")
;;      :config {:max-metric-calls 50}
;;      :block? true})
;;
;; See: components/gepa/
;; See: development/src/gepa_training_demo.clj for full examples
