(ns ai.obney.orc.ontology.core.lints.read-models
  "S10 — Read-model projections for the lint registry + validation report.

   Three projections defined here (each via Grain's defreadmodel so they
   participate in the watermark-incremental cache):

   1. shape-registry — `{ontology-id -> {shape-id -> shape-body}}`
      Built from `:ontology/shape-registered` events. The interpreter
      loads its shape set from here.

   2. validation-report — `{ontology-id -> {:violations [...] :skips [...]
                                            :run-id :detected-at}}`
      The LATEST run per ontology-id. Newer run-ids supersede older ones
      so consumers see the current health snapshot.

   3. violation-history — `{ontology-id -> [{<violation-event> ...} ...]}`
      Every violation event ever emitted, in append order. Queryable by
      ontology-id (always) + shape-id (optional) + time window (optional)."
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]))

;; =============================================================================
;; Event-type sets
;; =============================================================================

(def shape-registry-events
  "Events that affect the shape-registry projection."
  #{:ontology/shape-registered})

(def validation-events
  "Events that affect the validation-report and violation-history
   projections — every per-run event the interpreter emits."
  #{:ontology/lint-violation
    :ontology/lint-shape-skipped})

(def all-lint-events
  (clojure.set/union shape-registry-events validation-events))

;; =============================================================================
;; shape-registry projection
;; =============================================================================
;; State: {ontology-id {shape-id {:shape-body :code-symbol :registered-at}}}
;;
;; A re-register of the same shape-id REPLACES the prior body (latest-wins).
;; This mirrors the M4 design point that the registry is mutable — consumers
;; can ship a fix to a shape without retiring its identity.

(defmulti shape-registry*
  "Apply event to the shape-registry projection."
  (fn [_state event] (:event/type event)))

(defmethod shape-registry* :ontology/shape-registered
  [state event]
  (let [{:keys [ontology-id shape-id shape-body code-symbol registered-at]} event]
    (assoc-in state [ontology-id shape-id]
              (cond-> {:shape-body shape-body
                       :registered-at registered-at}
                code-symbol (assoc :code-symbol code-symbol)))))

(defmethod shape-registry* :default [state _] state)

(defreadmodel :ontology shape-registry
  {:events shape-registry-events, :version 1}
  [state event] (shape-registry* state event))

;; =============================================================================
;; validation-report projection (CURRENT — latest run per ontology-id)
;; =============================================================================
;; State: {ontology-id {:run-id :detected-at :violations [...] :skips [...]}}
;;
;; Per-run events arrive in append order; we collect them per ontology-id
;; under the LATEST run-id. When a newer run-id appears for the same
;; ontology-id, we reset that ontology-id's bucket (newer runs replace).

(defn- new-run? [state ontology-id run-id]
  (let [cur (get-in state [ontology-id :run-id])]
    (and run-id (not= cur run-id))))

(defn- reset-bucket [state ontology-id event]
  (assoc state ontology-id
         {:run-id      (:run-id event)
          :detected-at (:detected-at event)
          :violations  []
          :skips       []}))

(defmulti validation-report*
  (fn [_state event] (:event/type event)))

(defmethod validation-report* :ontology/lint-violation
  [state event]
  (let [{:keys [ontology-id run-id]} event
        state' (if (new-run? state ontology-id run-id)
                 (reset-bucket state ontology-id event)
                 (-> state
                     (update-in [ontology-id :run-id] #(or % run-id))
                     (update-in [ontology-id :detected-at] #(or % (:detected-at event)))
                     (update-in [ontology-id :violations] #(or % []))
                     (update-in [ontology-id :skips] #(or % []))))]
    (update-in state' [ontology-id :violations] conj
               (select-keys event [:violation-id :shape-id :severity :message
                                   :offending-uri :reason :detail :detected-at]))))

(defmethod validation-report* :ontology/lint-shape-skipped
  [state event]
  (let [{:keys [ontology-id run-id]} event
        state' (if (new-run? state ontology-id run-id)
                 (reset-bucket state ontology-id event)
                 (-> state
                     (update-in [ontology-id :run-id] #(or % run-id))
                     (update-in [ontology-id :detected-at] #(or % (:detected-at event)))
                     (update-in [ontology-id :violations] #(or % []))
                     (update-in [ontology-id :skips] #(or % []))))]
    (update-in state' [ontology-id :skips] conj
               (select-keys event [:skip-id :shape-id :reason :detected-at]))))

(defmethod validation-report* :default [state _] state)

(defreadmodel :ontology validation-report
  {:events validation-events, :version 1}
  [state event] (validation-report* state event))

;; =============================================================================
;; violation-history projection (queryable history)
;; =============================================================================
;; State: {ontology-id [violation-event-body ...]}
;;
;; Append-only — every lint-violation event ever emitted. Time-range +
;; shape-id filtering happens at the query layer; here we just keep the
;; chronologically-ordered append log per ontology-id.

(defmulti violation-history*
  (fn [_state event] (:event/type event)))

(defmethod violation-history* :ontology/lint-violation
  [state event]
  (update state (:ontology-id event) (fnil conj [])
          (select-keys event [:violation-id :shape-id :severity :message
                              :offending-uri :reason :detail :run-id :detected-at])))

(defmethod violation-history* :default [state _] state)

(defreadmodel :ontology violation-history
  {:events #{:ontology/lint-violation}, :version 1}
  [state event] (violation-history* state event))
