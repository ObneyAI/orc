(ns ai.obney.orc.orc-service.interface
  "Behavior Tree Sheet Service public interface.

   This namespace loads all core modules for side-effect registration
   and re-exports the public API."
  (:refer-clojure :exclude [sequence])
  (:require [ai.obney.orc.orc-service.core.commands :as commands]
            [ai.obney.orc.orc-service.core.logic :as logic]
            [ai.obney.orc.orc-service.core.periodic-tasks :as periodic]
            [ai.obney.orc.orc-service.core.queries :as queries]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.interface.projections :as projections]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.interface.stream-schemas :as stream-schemas]))

(def max-alert-window
  "The largest trailing window a judge alert may watch (the outcomes a node
   version's performance keeps in order)."
  logic/max-alert-window)

;; =============================================================================
;; Read Models
;; =============================================================================

(def block! logic/block!)

(def blocking-condition? logic/blocking-condition?)

(def block-payload logic/block-payload)

(def start-telemetry-exporter! logic/start!)

(def telemetry-exporter-stats logic/stats)

(def stop-telemetry-exporter! logic/stop!)

(def profile-value logic/profile-value)

(def profile-values logic/profile-values)

(def get-sheet projections/get-sheet)

(def get-sheets-all projections/get-sheets-all)

(def get-sheet-by-name projections/get-sheet-by-name)

(def get-node projections/get-node)

(def get-nodes-for-sheet projections/get-nodes-for-sheet)

(def get-nodes-by-id projections/get-nodes-by-id)

(def get-root-node projections/get-root-node)

(def get-children projections/get-children)

(def get-descendants projections/get-descendants)

(def get-blackboard-for-sheet projections/get-blackboard-for-sheet)

(def get-blackboard-by-key projections/get-blackboard-by-key)

(def get-judges projections/get-judges)

(def get-judge projections/get-judge)

(def get-family-usage rm/get-family-usage)

(def get-execution-family rm/get-execution-family)

(def get-tick projections/get-tick)

(def assessment-origin
  "The durable assessment origin ({:assessment-id uuid}) of a tick, or nil when
   the tick is not work done for an assessment. Inherited by every child run."
  projections/assessment-origin)

(def get-tick-execution-context
  "The execution context a snapshot-based tick runs under ({:nodes-by-id
   :version-number :instruction-overrides ...}), or nil for a snapshot-less tick."
  projections/get-tick-execution-context)

(def get-researcher-campaign projections/get-researcher-campaign)

(def get-researcher-iteration-records projections/get-researcher-iteration-records)

(def get-researcher-effect-claims projections/get-researcher-effect-claims)

(def get-provider-call-reservations projections/get-provider-call-reservations)

(def get-versions-for-sheet projections/get-versions-for-sheet)

(def get-version projections/get-version)

(def get-latest-version projections/get-latest-version)

(def get-stash projections/get-stash)

(def get-tree-metadata projections/get-tree-metadata)

(def get-all-tree-metadata projections/get-all-tree-metadata)

(def find-trees-by-problem-type projections/find-trees-by-problem-type)

(def get-node-rolling-metrics projections/get-node-rolling-metrics)

(def get-tree-rolling-metrics projections/get-tree-rolling-metrics)

(def get-node-type-metrics projections/get-node-type-metrics)

(def get-tree-fingerprint-metrics projections/get-tree-fingerprint-metrics)

(def value-log-exec-context logic/exec-context)

(def value-log-execution-key logic/execution-key)

(def value-log-engine-key? logic/engine-key?)

(def value-log-tick-started-event rm/tick-started-event)

(def value-log-tick-seeds rm/tick-seeds)

(def value-log-writes-for logic/writes-for)

(def value-log-latest-values logic/latest-values)

(def value-log-input-seeds-by-iteration logic/input-seeds-by-iteration)

(def value-log-read-tick-events rm/read-tick-events)

(def value-log-resolve-reads rm/resolve-reads)

(def value-log-resolve-writes rm/resolve-writes)

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
     context - Map with :event-store and optional :llm-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)
     :execution-deadline-ms - Optional absolute cap for provider admission.
                              A shorter local timeout wins; a spent cap stays
                              spent. It does not shorten the blocking wait or
                              interrupt arbitrary code (timeout-ms is unchanged).
     :use-version - Specific version number to execute (overrides execution-mode)
     :force-draft - Force draft execution even if execution-mode is :published
     :correlation-id - Optional UUID grouping independent roots and all their
                       descendants into one caller-defined operation. Overrides
                       :orc/correlation-id in context.

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :error string?
      :executed-version int?}  ;; Present if published version was used

   Example:
     (sheet/execute ctx sheet-id {\"student-id\" student-id} :timeout-ms 60000)
     (sheet/execute ctx sheet-id inputs :use-version 2)  ;; Execute specific version"
  commands/execute)

(def get-execution-result
  "Read a terminal execution result from durable tenant-scoped events.

   Returns nil when the tick has no terminal result. Otherwise returns its
   original status, trace ID, canonical outputs and output sources, and the
   executed version when recorded. This operation does not start execution,
   register a completion waiter, resume work, or append events."
  commands/durable-terminal-result)

(def resume-in-progress!
  "Resume abandoned leaf, delegate, and researcher frontiers after processors
   restart against the same durable event store. Repeated calls are idempotent."
  processors/resume-in-progress!)

(def register-recovery-scan-context-fn!
  "Let the periodic recovery scan decide before it writes. `f` maps a tenant id
   to that tenant's ORC context (the one passed to `resume-in-progress!`), or
   nil. With it, a scan records its trigger only when a start is abandoned (or
   the state cannot be read); without it the scan records every beat as before.
   Returns a no-arg function that removes the registration."
  logic/register-context-fn!)

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
  processors/subscribe-execution)

(def execute-stream
  "Non-blocking streamed execution: subscribe + dispatch in one call.

   (execute-stream context sheet-id inputs & opts)
   => {:tick-id uuid
       :events-ch <chan of envelopes, closes after :stream-closed>
       :close!   (fn [])
       :result   <promise of the exact `execute` return map>}

   Accepts every `execute` option plus :include-values? :buffer :ttl-ms."
  processors/execute-stream)

(def cancel!
  "Cancel a running tick and any known child ticks. Best-effort: the engine
   stops progressing and registered checkpointed researcher work is
   interrupted. Legacy non-checkpointed work remains outside that registry,
   and interruption does not guarantee that a provider transport aborts.
   Blocking callers unblock with {:status :failure :cancelled? true}; live
   streams end with :tick-cancelled then :stream-closed {:reason :cancelled}.

   (cancel! context tick-id) => {:cancelled [tick-ids]} | anomaly map"
  processors/cancel!)

(def stream-envelope-schema
  "Malli schema (multi-dispatch on :orc.stream/type) for every envelope a
   subscription can deliver. For consumer-side validation/codegen."
  stream-schemas/stream-envelope)

;; =============================================================================
;; Workflow DSL
;; =============================================================================

(def llm logic/llm)

(def code logic/code)

(def tool logic/tool)

(def condition logic/condition)

(def llm-condition logic/llm-condition)

(def llm-decision logic/llm-decision)

(def sequence logic/sequence)

(def fallback logic/fallback)

(def parallel logic/parallel)

(def map-each logic/map-each)

(def repl-researcher logic/repl-researcher)

(def delegate logic/delegate)

(def blackboard logic/dsl-blackboard)

(def judges logic/dsl-judges)

(def workflow logic/workflow)

(def build-workflow! commands/build-workflow!)

(def build-workflow!! commands/build-workflow!!)

(def print-tree logic/print-tree)

(def describe-workflow logic/describe-workflow)

(def export-sheet commands/export-sheet)

(def export-to-dsl logic/export-to-dsl)

(def save-sheet-as-dsl! commands/save-sheet-as-dsl!)

(def import-sheet commands/import-sheet)

(def save-sheet! commands/save-sheet!)

(def load-sheet! commands/load-sheet!)

(def save-all-sheets! commands/save-all-sheets!)

(def load-all-sheets! commands/load-all-sheets!)

(def start-provider-attempt! processors/start-provider-attempt!)

(def complete-provider-attempt! processors/complete-provider-attempt!)

(def provider-attempts commands/provider-attempts)
