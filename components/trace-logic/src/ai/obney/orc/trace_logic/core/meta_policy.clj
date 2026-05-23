(ns ai.obney.orc.trace-logic.core.meta-policy
  "Recursive trace logic: meta-policies as Markov kernels over
   lower-level policies.

   A Policy_n (n ≥ 1) lives in the trace logic of Policy_{n-1} kernels.
   Sampling from a meta-policy returns the index of a Policy_{n-1} from
   a registered set; that selected policy then drives ORC's child
   selection at runtime.

   This module is type-uniform with `core/policy.clj` — same operations
   apply at every level. The value-add here is *registration*: a
   meta-policy carries a vector of Policy_{n-1} instances and a
   sampling protocol over them.

   Implementation note: this implements material from the May 2026
   lecture not yet in the published paper."
  (:require [ai.obney.orc.trace-logic.core.policy :as p]
            [ai.obney.orc.trace-logic.core.kernel :as k])
  (:import [java.util Random]))

(defn ->meta-policy
  "Construct a Policy_n from a kernel and a vector of Policy_{n-1}
   instances. The kernel's state space corresponds to indices into the
   policies vector — kernel[i, j] is the probability that, after using
   policies[i], the meta-policy switches to policies[j].

   Required:
     :kernel    — n×n Markov kernel where n = (count sub-policies)
     :sub-policies — vector of n Policy_{level-1} instances

   The level is automatically (max-of-sub-policies-level) + 1."
  [{:keys [kernel sub-policies base-chain-ref reward-signal-ref]}]
  {:pre [(seq sub-policies)
         (= (count kernel) (count sub-policies))
         (every? :level sub-policies)]}
  (let [sub-level (apply max (map :level sub-policies))
        next-level (inc sub-level)]
    (assoc (p/->policy
            {:kernel kernel
             :level next-level
             :base-chain-ref base-chain-ref
             :reward-signal-ref reward-signal-ref
             :state-space (mapv :policy-id sub-policies)})
           :sub-policies sub-policies)))

(defn select-sub-policy
  "Given the meta-policy's current state index (= which sub-policy is
   currently active), sample a transition and return the next
   sub-policy.

   Returns a Policy_{n-1}."
  ([meta-policy current-idx]
   (select-sub-policy meta-policy current-idx (Random.)))
  ([meta-policy current-idx ^Random rng]
   (let [next-idx (p/sample-next meta-policy current-idx rng)]
     (nth (:sub-policies meta-policy) next-idx))))

(defn level
  "The level of any policy (Policy_n's :level field, defaults to 0)."
  [policy]
  (or (:level policy) 0))

(defn lift-to-meta
  "Construct the trivial meta-policy over a single sub-policy.

   The resulting Policy_{n+1} has a 1×1 kernel [[1.0]] — it always
   selects that sole sub-policy. Useful as a baseline / identity in
   recursive constructions."
  [policy]
  (->meta-policy
   {:kernel [[1.0]]
    :sub-policies [policy]}))

(defn flatten-policy-stack
  "Apply a stack of nested meta-policies down to a single Policy_0,
   sampling at each level.

   Returns a Policy_0 instance — the leaf of the recursion."
  ([policy]
   (flatten-policy-stack policy 0 (Random.)))
  ([policy current-idx]
   (flatten-policy-stack policy current-idx (Random.)))
  ([policy current-idx ^Random rng]
   (cond
     (zero? (level policy)) policy
     :else (recur (select-sub-policy policy current-idx rng) 0 rng))))
