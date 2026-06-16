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
            ;; Live execution streaming
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            [ai.obney.orc.orc-service.interface.stream-schemas :as stream-schemas]
            ;; DSL for workflow building
            [ai.obney.orc.orc-service.core.dsl :as dsl]))

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

;; Judge functions (Gap-1: per-event evaluator runtime reads these)
(def get-judges rm/get-judges)
(def get-judge rm/get-judge)

;; Tick functions
(def get-tick rm/get-tick)

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
;; C-2a-2: cross-sheet rolling metrics for the Living Description system.
(def get-node-type-metrics rm/get-node-type-metrics)
(def get-tree-fingerprint-metrics rm/get-tree-fingerprint-metrics)

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
;; Live Streaming (ephemeral observation layer — see docs/STREAMING.md)
;; =============================================================================

(def subscribe-execution
  "Subscribe to the live event stream for a tick-id and every child tick
   spawned under it (RLM Phase 2 trees, delegate nodes). Call BEFORE
   dispatching the tick (tick-id is caller-suppliable on execute).

   (subscribe-execution context tick-id & {:keys [include-values? buffer ttl-ms]})
   => {:events-ch <chan of :orc.stream/* envelopes> :tick-id uuid :close! fn}

   Envelopes carry a strictly monotonic :seq per subscription; a gap means
   the consumer fell behind the sliding buffer and lost events (everything
   durable is recoverable from the event store by [:tick tick-id]).
   See interface.stream-schemas/stream-envelope for the full taxonomy."
  streaming/subscribe-execution)

(def execute-stream
  "Non-blocking streamed execution: subscribe + dispatch in one call.

   (execute-stream context sheet-id inputs & opts)
   => {:tick-id uuid
       :events-ch <chan of envelopes, closes after :stream-closed>
       :close!   (fn [])
       :result   <promise of the exact `execute` return map>}

   Accepts every `execute` option plus :include-values? :buffer :ttl-ms."
  streaming/execute-stream)

(def cancel!
  "Cancel a running tick and any known child ticks. Best-effort: the engine
   stops progressing but in-flight LLM HTTP calls run to completion.
   Blocking callers unblock with {:status :failure :cancelled? true}; live
   streams end with :tick-cancelled then :stream-closed {:reason :cancelled}.

   (cancel! context tick-id) => {:cancelled [tick-ids]} | anomaly map"
  streaming/cancel!)

(def stream-envelope-schema
  "Malli schema (multi-dispatch on :orc.stream/type) for every envelope a
   subscription can deliver. For consumer-side validation/codegen."
  stream-schemas/stream-envelope)

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
