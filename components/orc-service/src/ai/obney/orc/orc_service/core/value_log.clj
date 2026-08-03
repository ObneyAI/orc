(ns ai.obney.orc.orc-service.core.value-log
  "Resolving blackboard VALUES from the canonical write log.

   :sheet/execution-value-written is the canonical record of every blackboard
   write; lifecycle and trace events record only shape (:write-keys,
   :read-keys, profiles). This namespace is the one place that turns those
   events back into values.

   ATTRIBUTION. A key alone cannot answer \"what did THIS node write\": a
   later node may overwrite the same key, and under map-each the same child
   node-id executes once per item against a shared item key. Writes are
   therefore indexed by (node-id, exec-context) — one node EXECUTION, the
   same identity todo-processors/trace-execution-key uses."
  (:require [ai.obney.grain.event-store-v3.interface :as es]))

(def ^:private map-each-index-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-index)

(def ^:private map-each-parent-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-parent)

(defn exec-context
  "The execution-disambiguating context from a map of inputs. Mirrors
   todo-processors/trace-execution-context; defined here to avoid a
   dependency cycle between commands, processors and this namespace."
  [inputs]
  (select-keys (or inputs {}) [map-each-index-key map-each-parent-key]))

(defn execution-key
  "Correlation key for one node execution: [node-id exec-context].
   Works for :sheet/execution-value-written events (which carry
   :exec-context) and for node lifecycle events (which carry the same keys
   inside :inputs)."
  [event]
  [(:node-id event)
   (if (contains? event :exec-context)
     (exec-context (:exec-context event))
     (exec-context (:inputs event)))])

(defn input-seed?
  "True when a write PROVIDES a value to the execution it names rather than
   being that execution's output. map-each item writes are the case: the
   parent emits them before starting each child, and a child commonly reads
   and writes the same item key, so direction cannot be inferred from
   attribution alone."
  [event]
  (true? (:input-seed? event)))

(defn writes-by-execution
  "Index a tick's OUTPUT writes as {[node-id exec-context] {key value}}.

   Later writes win within one execution, matching blackboard semantics.
   Input seeds are excluded: they are inputs TO the named execution, not
   outputs OF it, so including them would let a map-each item masquerade as
   the child's own result for the same key."
  [events]
  (reduce (fn [acc e]
            (if (and (= :sheet/execution-value-written (:event/type e))
                     (not (input-seed? e)))
              (assoc-in acc [(execution-key e) (:key e)] (:value e))
              acc))
          {}
          events))

(defn input-seeds-by-iteration
  "Input seeds indexed by ITERATION: {exec-context {key value}}.

   Keyed on exec-context alone, NOT on [node-id exec-context]. The iteration
   identity is (map-each parent, index) — every node executing inside that
   iteration shares it, whatever its own node-id. A map-each child is often a
   composite, so the node that actually reads the item is a descendant of the
   child the write was stamped with; keying on node-id would miss it and fall
   back to the shared blackboard slot, which concurrent iterations clobber.

   This is how a map-each iteration's OWN item is recovered."
  [events]
  (reduce (fn [acc e]
            (if (and (= :sheet/execution-value-written (:event/type e))
                     (input-seed? e))
              (assoc-in acc [(exec-context (:exec-context e)) (:key e)] (:value e))
              acc))
          {}
          events))

(defn writes-for
  "The {key value} map a specific node execution wrote.

   `completion` is that node's :sheet/node-execution-completed event; its
   :node-id and :inputs supply the correlation key.

   Prefers the canonical write log, falling back to an inlined :writes map on
   the completion event when the log has nothing for this execution — which
   is the case for non-tick-scoped completions. Returns {} when neither has
   anything."
  [events completion]
  (let [from-log (get (writes-by-execution events) (execution-key completion))]
    (or (not-empty from-log)
        (:writes completion)
        {})))

(defn latest-values
  "The final {key value} map for a tick, ignoring attribution — last write
   per key wins. Use when you want the blackboard's end state rather than
   one node's contribution."
  [events]
  (reduce (fn [acc e]
            (if (= :sheet/execution-value-written (:event/type e))
              (assoc acc (:key e) (:value e))
              acc))
          {}
          events))

(defn read-tick-events
  "All events for a tick. Returns [] on failure rather than throwing —
   every caller here is enriching observability, not gating execution."
  [event-store tenant-id tick-id]
  (try
    (if event-store
      (into [] (es/read event-store (cond-> {:tags #{[:tick tick-id]}}
                                      tenant-id (assoc :tenant-id tenant-id))))
      [])
    (catch Exception _ [])))

(defn resolve-writes
  "Convenience: read a tick's events and return what one node execution
   wrote. Prefer the `events`-taking fns when you already hold the events."
  [event-store tenant-id tick-id completion]
  (writes-for (read-tick-events event-store tenant-id tick-id) completion))
