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

(defn writes-by-iteration
  "Non-seed writes indexed by ITERATION: {exec-context {key value}} — the
   output mirror of input-seeds-by-iteration.

   execute-composite-node forwards its :inputs (which carry ::map-each-index /
   ::map-each-parent) to each child, and complete-node-execution copies that
   namespaced subset onto both the completion event and every write event's
   :exec-context. So every node running inside one map-each iteration shares
   that iteration's exec-context whatever its own node-id or depth — which is
   what [node-id exec-context] attribution cannot express when the map-each
   child is a composite and the real writers are its descendants."
  [events]
  (reduce (fn [acc e]
            (if (and (= :sheet/execution-value-written (:event/type e))
                     (not (input-seed? e)))
              (let [ctx (exec-context (:exec-context e))]
                (if (seq ctx)
                  (assoc-in acc [ctx (:key e)] (:value e))
                  acc))
              acc))
          {}
          events))

;; =============================================================================
;; Resolving values behind a METADATA blackboard
;; =============================================================================
;;
;; The :sheet/tick-execution-contexts read model caches a tick's blackboard as
;; METADATA — key, schema, version, :source-event-id, size profile — and no
;; values. Values live where they were always canonical: the write log.
;;
;; Every key in that cached blackboard got there by exactly one of two routes,
;; and they are exhaustive:
;;
;;   WRITTEN  a :sheet/execution-value-written was applied, which ALWAYS
;;            records :source-event-id. That pointer names the very write whose
;;            value the cache used to hold, so resolving through it reproduces
;;            the old :value exactly — no last-write-wins guessing, and no
;;            special case for map-each item seeds (the reducer applies those
;;            without regard to :input-seed?, and so does the pointer).
;;
;;   SEEDED   the key came from the tick-started event's :execution-snapshot /
;;            :inputs and has no write the projection has applied. It has NO
;;            :source-event-id.
;;
;; "the projection has applied" is the load-bearing qualifier in the second
;; case. A write can be durable in the log while the cache has not caught up
;; yet, and then a pointer-based resolve reports the seed — a stale value, not
;; a wrong one, and self-correcting. That is fine for a node reading its own
;; declared inputs mid-tick, which is what these functions serve. It is NOT
;; fine for reporting a tick's final result, which is why final-values below
;; ignores the cache entirely.

(defonce ^:private tick-seeds*
  (atom {}))

(defn forget-tick!
  "Drop a tick's memoized seed values. Call once the tick can no longer be
   read from — after its result has been delivered or its cancellation
   handled."
  [tick-id]
  (swap! tick-seeds* dissoc tick-id)
  nil)

(defn- read-tick-writes
  "A tick's :sheet/execution-value-written events, oldest first."
  [event-store tenant-id tick-id]
  (try
    (if event-store
      (into [] (es/read event-store (cond-> {:types #{:sheet/execution-value-written}
                                             :tags #{[:tick tick-id]}}
                                      tenant-id (assoc :tenant-id tenant-id))))
      [])
    (catch Exception _ [])))

(defn tick-started-event
  "The tick's ORIGINATING :sheet/tree-tick-started — the one carrying
   :execution-snapshot / :inputs.

   complete-tree-tick emits further events of that type for the SAME tick-id on
   each re-tick, carrying neither, so this returns the first that carries
   either. nil for a legacy (snapshot-less) tick."
  [event-store tenant-id tick-id]
  (try
    (when event-store
      (->> (es/read event-store (cond-> {:types #{:sheet/tree-tick-started}
                                         :tags #{[:tick tick-id]}}
                                  tenant-id (assoc :tenant-id tenant-id)))
           (into [])
           (some #(when (or (:execution-snapshot %) (seq (:inputs %))) %))))
    (catch Exception _ nil)))

(defn seeded-values
  "The {key value} map a tick was SEEDED with — values that existed before it
   ran and so have no write event.

   Two sources, merged in the same precedence the read model uses: the
   execution snapshot's :blackboard-entries (the sheet's stored values at
   dispatch) and the command's :inputs, which override them. String keys are
   keywordized on both sides, matching the reducer. nil values are dropped —
   a key with no value has nothing to resolve, and hydrate-blackboard keeps
   the entry either way."
  [started-event]
  (let [base (reduce-kv (fn [acc k entry]
                          (if (some? (:value entry))
                            (assoc acc (if (string? k) (keyword k) k) (:value entry))
                            acc))
                        {}
                        (or (:blackboard-entries (:execution-snapshot started-event)) {}))]
    (reduce (fn [acc [k v]]
              (if (some? v)
                (assoc acc (if (string? k) (keyword k) k) v)
                acc))
            base
            (or (:inputs started-event) {}))))

(defn tick-seeds
  "Memoized seeded-values for a tick.

   Safe to memoize without an invalidation rule: the originating
   tree-tick-started is appended by the tick-tree command before any processor
   can run, and events are immutable. Worth memoizing because it is the
   largest event in the tick — it carries the whole execution snapshot and the
   caller's inputs verbatim — and a per-leaf re-read would be a regression."
  [event-store tenant-id tick-id]
  (if-let [cached (get @tick-seeds* tick-id)]
    cached
    (let [v (seeded-values (tick-started-event event-store tenant-id tick-id))]
      (swap! tick-seeds* assoc tick-id v)
      v)))

(defn values-by-event-id
  "{event-id value} for specific write events of a tick.

   Resolves by scanning the tick's write events and indexing them by
   :event/id. Deliberately NOT by a per-pointer
   {:as-of id :reverse? true :limit 1} seek, which looks like the obvious
   optimization and is unsound:

     :as-of is an inclusive upper BOUND, not an equality filter, so such a
     query returns \"the last matching event at or before this id\" — and
     'last' means last in the store's iteration order, which is APPEND order,
     not id order. Concurrent appends commit out of id order routinely (a
     map-each with max-concurrency > 1 does it on every run), and when they
     do the seek returns a neighbouring write instead of the one asked for.
     Measured on the composite map-each fixture, that missed roughly one
     resolution in three.

   The scan has no ordering assumption, so it is correct on any backend and
   under any interleaving. An event whose id was not requested is ignored, so
   a mismatched or foreign pointer resolves to nothing rather than to a
   plausible wrong value."
  [event-store tenant-id tick-id ids]
  (let [ids (set (remove nil? ids))]
    (if (or (empty? ids) (nil? event-store))
      {}
      (reduce (fn [acc e]
                (if (contains? ids (:event/id e))
                  (assoc acc (:event/id e) (:value e))
                  acc))
              {}
              (read-tick-writes event-store tenant-id tick-id)))))

(defn resolve-values
  "The values behind a METADATA blackboard, for `ks` (nil = every key).

   `metadata-bb` is the :blackboard of a tick execution context:
   {k {:key :schema :version :source-event-id :profile}} with no :value.
   Keys that resolve to nothing are omitted."
  [event-store tenant-id tick-id metadata-bb ks]
  (let [ks (if (nil? ks) (keys metadata-bb) ks)
        entries (into {} (for [k ks :when (contains? metadata-bb k)]
                           [k (get metadata-bb k)]))
        pointers (into {} (for [[k e] entries
                                :let [src (:source-event-id e)]
                                :when src]
                            [k src]))
        by-id (values-by-event-id event-store tenant-id tick-id (vals pointers))
        ;; Seeded keys only — a written key never falls back to the seed, or a
        ;; node that overwrote its own input would read the input back.
        seeds (when (some (fn [[_ e]] (nil? (:source-event-id e))) entries)
                (tick-seeds event-store tenant-id tick-id))]
    (reduce (fn [acc [k e]]
              (let [v (if-let [src (:source-event-id e)]
                        (get by-id src)
                        (get seeds k))]
                (if (some? v) (assoc acc k v) acc)))
            {}
            entries)))

(defn final-values
  "The tick's END STATE as {key value}, read straight from the log.

   Unlike resolve-values this does not consult the read model at all, so it
   cannot be affected by how far the projection has caught up. That matters
   for the one caller that owes an answer rather than a view — result
   delivery. A tick's writes are appended atomically with the node completion
   that produced them, so by the time tree-tick-completed is observed the log
   is authoritative and complete, while the cache may still be catching up
   under concurrent projections.

   Seeds first, then every write in order, so the last write to a key wins —
   the same end state the cached blackboard converges to."
  [event-store tenant-id tick-id]
  (merge (tick-seeds event-store tenant-id tick-id)
         (latest-values (read-tick-writes event-store tenant-id tick-id))))

(defn hydrate-blackboard
  "`metadata-bb` with :value filled in for `ks` (nil = every key).

   THE ENTRY SET IS PRESERVED EXACTLY. A declared-but-unresolvable key keeps
   its metadata and simply gets no :value — which is what the read model
   produced for a declared-but-unwritten key. executor/gather-inputs
   distinguishes 'entry absent' (skip the key) from 'entry present, value nil'
   (serialize nil, i.e. an empty string), so dropping entries here would
   silently change LLM prompts."
  ([event-store tenant-id tick-id metadata-bb]
   (hydrate-blackboard event-store tenant-id tick-id metadata-bb nil))
  ([event-store tenant-id tick-id metadata-bb ks]
   (let [vals* (resolve-values event-store tenant-id tick-id metadata-bb ks)]
     (reduce-kv (fn [bb k v] (assoc-in bb [k :value] v))
                (or metadata-bb {})
                vals*))))
