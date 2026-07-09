(ns ai.obney.orc.orc-service.interface.tracing
  "Public interface for the pure trace-snapshot helpers.

   RB-2b stopped storing the trace-level :input-snapshot/:output-snapshot on
   :sheet/execution-traced events; consumers that read those events directly
   (e.g. evaluation's get-traces-raw) hydrate the snapshots on demand from
   the tick's durable [:tick trace-id] events via these PURE helpers
   (trace-id == tick-id). Both take a seq of the tick's events — no read
   model, no ctx."
  (:require [ai.obney.orc.orc-service.core.tracing :as core]))

(defn blackboard-snapshot-from-events
  "The trace-level :input-snapshot: the ACCUMULATED tick blackboard at
   completion (seed inputs + every execution-value-written), non-nil values
   only. A pure fold that mirrors the tick-execution-contexts read model
   exactly. Takes the tick's events; returns a map."
  [tick-events]
  (core/blackboard-snapshot-from-events tick-events))

(defn final-tick-outputs
  "The trace-level :output-snapshot: the tick's FINAL
   :sheet/tree-tick-completed event's :outputs (re-tick aware — intermediate
   :running completions carry no :outputs), default {}. Takes the tick's
   events; returns a map."
  [tick-events]
  (core/final-tick-outputs tick-events))
