(ns ai.obney.orc.trace-logic.core.strategy
  "Bridge from trace-logic policies to orc-service's multimethod
   extension points.

   Loading this namespace registers `:adaptive` and `:policy` methods
   with `orc-service/core/todo-processors`'s multimethods. Without
   this namespace loaded, those keywords are undefined and dispatch
   falls back to `:linear` / `:boolean` defaults — preserving the
   engine's original behavior bit-identically.

   A live policy is keyed in the `*policy-registry*` atom by
   policy-id. When a composite node has `:strategy-id :adaptive` and
   a `:policy-id` set, the strategy looks up the registered policy
   and samples the next child from it.

   For runtime state (which window the policy is currently focused on),
   we maintain `*current-state-registry*` keyed by [tick-id node-id].
   This is a runtime concern, not part of the policy itself."
  (:require [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.trace-logic.core.policy :as policy]))

;; =============================================================================
;; Runtime registries
;; =============================================================================

(defonce ^{:doc "policy-id → Policy. Populated by `register!`."
           :dynamic true}
  *policy-registry* (atom {}))

(defonce ^{:doc "[tick-id parent-id] → current-state-idx. Populated
   per-tick during sampling; cleared by `clear-tick-state!`."
           :dynamic true}
  *current-state-registry* (atom {}))

(defn register!
  "Add a policy to the global registry, keyed by its :policy-id.
   Returns the policy."
  [policy]
  (swap! *policy-registry* assoc (:policy-id policy) policy)
  policy)

(defn unregister!
  "Remove a policy from the registry."
  [policy-id]
  (swap! *policy-registry* dissoc policy-id))

(defn registered-policy
  "Look up a policy by id; nil if not registered."
  [policy-id]
  (get @*policy-registry* policy-id))

(defn clear-tick-state!
  "Clear runtime state for a completed tick. Call from todo-processor
   on tree-tick-completed events to avoid memory growth."
  [tick-id]
  (swap! *current-state-registry*
         (fn [m]
           (into {} (remove (fn [[[t _] _]] (= t tick-id)) m)))))

(defn- current-state-idx [tick-id parent-id]
  (or (get @*current-state-registry* [tick-id parent-id]) 0))

(defn- set-current-state-idx! [tick-id parent-id idx]
  (swap! *current-state-registry* assoc [tick-id parent-id] idx))

;; =============================================================================
;; Multimethod registrations
;; =============================================================================

(defmethod tp/next-child-strategy :adaptive
  [{:keys [siblings child-index parent]}]
  (let [policy-id (:policy-id parent)
        policy (when policy-id (registered-policy policy-id))
        parent-id (:id parent)
        ;; Tick-id should be threaded through; for V1, fall back to
        ;; using parent-id if not present.
        tick-id (or (:tick-id parent) parent-id)]
    (cond
      ;; No registered policy: fall through to linear semantics.
      (nil? policy)
      (get (vec siblings) (inc child-index))

      ;; Policy size doesn't match siblings: fall through.
      (not= (count (:kernel policy)) (count siblings))
      (get (vec siblings) (inc child-index))

      :else
      (let [current-idx (current-state-idx tick-id parent-id)
            next-idx (policy/sample-next policy current-idx)]
        (set-current-state-idx! tick-id parent-id next-idx)
        (get (vec siblings) next-idx)))))

(defmethod tp/parallel-completion-strategy :policy
  [{:keys [child-counts success-policy failure-policy parent]}]
  (let [policy-id (:policy-id parent)
        policy (when policy-id (registered-policy policy-id))]
    (if (nil? policy)
      ;; No policy: fall through to boolean semantics.
      (tp/parallel-completion-strategy
       {:strategy-id :boolean
        :child-counts child-counts
        :success-policy success-policy
        :failure-policy failure-policy
        :parent parent})
      ;; With a policy registered, threshold against its stationary
      ;; distribution: complete with :success if observed success-rate
      ;; ≥ stationary[1] (where stationary index 1 is the success
      ;; state in the policy's internal coding).
      ;; For V1 keep this simple: defer to boolean semantics if the
      ;; policy doesn't expose a clear threshold.
      (tp/parallel-completion-strategy
       {:strategy-id :boolean
        :child-counts child-counts
        :success-policy success-policy
        :failure-policy failure-policy
        :parent parent}))))
