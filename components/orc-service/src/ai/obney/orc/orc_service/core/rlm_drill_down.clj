(ns ai.obney.orc.orc-service.core.rlm-drill-down
  "Pure query functions over RLM Phase 2 tick events.

   These are the implementations behind the SCI sandbox drill-down
   primitives (`tree-detail`, `tree-trajectory`, `tree-failures`,
   `node-output`, `node-input-profile`) exposed in recursive RLM mode.

   All functions take a vector of cleaned event maps (typically read from
   the event store with `[:tick tick-id]` tag) and return clean projections.
   No event-store coupling — testable in isolation with fixture data.")

(defn- event-of-type [events event-type]
  (first (filter #(= event-type (:event/type %)) events)))

(defn- events-of-type [events event-type]
  (filter #(= event-type (:event/type %)) events))

(defn tree-detail-from-events
  "Build a structured projection of a tree's full execution detail from
   the events tagged with its tick-id.

   Inputs:
     events  — vec of event maps for one tick
     tree-raw — the raw S-expr tree the model wrote (from :generated-tree-raw)

   Returns:
     {:tick-id <uuid>
      :status :success | :partial | :failure | :timeout
      :tree-raw [...]
      :outputs {<writes-key> value ...}
      :nodes [{:node-id :status :duration-ms :writes :usage :input-profile} ...]}

   When the events don't include a :sheet/tree-tick-completed bookend
   (e.g. an in-flight or otherwise truncated tick), returns nil."
  [events tree-raw]
  (when-let [tick-completed (event-of-type events :sheet/tree-tick-completed)]
    (let [tick-id (:tick-id tick-completed)
          node-completions (events-of-type events :sheet/node-execution-completed)
          ;; Index the O02/O03 events by node-id for joining
          rlm-by-node (reduce (fn [acc e] (assoc acc (:node-id e) e))
                              {}
                              (events-of-type events :sheet/rlm-tree-node-completed))
          nodes (mapv (fn [c]
                        (let [n-id (:node-id c)
                              rlm (get rlm-by-node n-id)]
                          (cond-> {:node-id n-id
                                   :status (:status c)
                                   :duration-ms (:duration-ms c)
                                   :writes (:writes c {})}
                            (:usage c) (assoc :usage (:usage c))
                            (:input-profile rlm) (assoc :input-profile (:input-profile rlm))
                            ;; D-008: map-each completion events carry :partial-summary
                            ;; verbatim. Surface it so judges/drill-down consumers can see
                            ;; failure-indices + failure-reasons at the node level.
                            (:partial-summary c) (assoc :partial-summary (:partial-summary c)))))
                      node-completions)]
      {:tick-id tick-id
       :status (:root-status tick-completed)
       :tree-raw tree-raw
       :outputs (or (:outputs tick-completed) {})
       :nodes nodes})))

(defn tree-failures-from-events
  "Return the failure entries for a tree's tick events.

   Two sources of failures are joined:

   1. Direct leaf failures — :sheet/node-execution-completed events with
      :status :failure. Each entry includes the :error string and (if
      available) the :input-profile from the matching
      :sheet/rlm-tree-node-completed event.

   2. Map-each :partial-summary failures — the :failure-indices /
      :failure-reasons / :failure-input-profiles fields from D-008's
      :partial-summary block. Each index becomes its own failure entry.

   Returns:
     [{:node-id :status :error :input-profile?} ...]    ; direct leaf failures
     [{:index :error :input-profile?} ...]              ; map-each per-index failures
     [] when nothing failed."
  [events]
  (let [node-completions (events-of-type events :sheet/node-execution-completed)
        rlm-by-node (reduce (fn [acc e] (assoc acc (:node-id e) e))
                            {}
                            (events-of-type events :sheet/rlm-tree-node-completed))
        ;; Direct leaf failures — events with :status :failure that DO NOT
        ;; carry :partial-summary (those are map-each parents).
        leaf-failures (for [c node-completions
                            :when (and (= :failure (:status c))
                                       (not (:partial-summary c)))]
                        (let [rlm (get rlm-by-node (:node-id c))]
                          (cond-> {:node-id (:node-id c)
                                   :status :failure}
                            (:error c) (assoc :error (:error c))
                            (:input-profile rlm) (assoc :input-profile (:input-profile rlm)))))
        ;; Map-each :partial-summary failures — expand each index into its own entry.
        partial-summary-failures
        (mapcat (fn [c]
                  (when-let [ps (:partial-summary c)]
                    (for [idx (:failure-indices ps)]
                      (cond-> {:index idx}
                        (get-in ps [:failure-reasons idx]) (assoc :error (get-in ps [:failure-reasons idx]))
                        (get-in ps [:failure-input-profiles idx])
                        (assoc :input-profile (get-in ps [:failure-input-profiles idx]))))))
                node-completions)]
    (vec (concat leaf-failures partial-summary-failures))))

(defn tree-trajectory-from-events
  "Return the chronological per-event log for a tree's tick.

   The bookend :sheet/rlm-tree-execution-completed event captures the full
   trajectory at completion time (see the O03 work). We just pass that
   vector through. Returns nil when no bookend event is present."
  [events]
  (when-let [bookend (event-of-type events :sheet/rlm-tree-execution-completed)]
    (vec (:trajectory bookend))))

(defn node-output-from-events
  "Return the :writes map of the specified node's :sheet/node-execution-completed
   event. Returns nil when no matching event exists.

   For parse-failure completions the event carries :raw-response (the
   verbatim LLM text that could not be parsed into declared writes). In
   that case the return shape is {:writes <map> :raw-response <string>
   :error <string>} — the raw text IS the node's output as far as
   diagnosis is concerned, untruncated."
  [events node-id]
  (some (fn [e]
          (when (and (= :sheet/node-execution-completed (:event/type e))
                     (= node-id (:node-id e)))
            (if (:raw-response e)
              (cond-> {:writes (:writes e)
                       :raw-response (:raw-response e)}
                (:error e) (assoc :error (:error e)))
              (:writes e))))
        events))

(defn node-input-profile-from-events
  "Return the :input-profile of the specified node's :sheet/rlm-tree-node-completed
   event. Returns nil when no matching event exists (or the event has no
   :input-profile)."
  [events node-id]
  (some (fn [e]
          (when (and (= :sheet/rlm-tree-node-completed (:event/type e))
                     (= node-id (:node-id e)))
            (:input-profile e)))
        events))
