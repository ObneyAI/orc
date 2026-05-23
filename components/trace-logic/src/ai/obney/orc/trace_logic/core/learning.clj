(ns ai.obney.orc.trace-logic.core.learning
  "Bayesian inference of policy kernels from observed (state,
   transition, reward) triples.

   The posterior over each kernel row is Dirichlet, conjugate-updated
   on observed transitions weighted by reward. Closed-form; preserves
   the Markov-kernel type exactly. No gradient methods, no
   parameterizations beyond the kernel itself.

   For ORC: rewards are Lebesgue measures on outcomes derived from
   evaluation judges (paper §4 + Stationary Map Theorem). Higher reward
   ⇒ stronger Dirichlet update toward that transition."
  (:require [ai.obney.orc.trace-logic.core.policy :as p]))

(defn- new-dirichlet-counts
  "Initialize a Dirichlet count matrix. Each row starts with the
   prior alpha (smoothing) plus the policy's current kernel weighted
   by `prior-strength` — combining prior knowledge from an existing
   policy with new observations."
  [n alpha prior-strength current-kernel]
  (mapv
   (fn [i]
     (mapv
      (fn [j]
        (+ alpha
           (if current-kernel
             (* prior-strength (double (get-in current-kernel [i j])))
             0.0)))
      (range n)))
   (range n)))

(defn- counts->kernel
  "Normalize a count matrix into a row-stochastic Markov kernel."
  [counts]
  (mapv
   (fn [row]
     (let [total (reduce + 0.0 row)]
       (if (zero? total)
         (vec (repeat (count row) (/ 1.0 (count row))))
         (mapv #(/ (double %) total) row))))
   counts))

(defn ->dirichlet-state
  "Initialize learning state for a policy.

   Options:
     :alpha           — prior smoothing per cell (default 0.1)
     :prior-strength  — weight of the policy's existing kernel as prior
                        (default 1.0)"
  [policy & {:keys [alpha prior-strength]
             :or {alpha 0.1 prior-strength 1.0}}]
  (let [n (count (:kernel policy))]
    {:policy-id (:policy-id policy)
     :n n
     :counts (new-dirichlet-counts n alpha prior-strength (:kernel policy))
     :alpha alpha
     :update-count 0}))

(defn observe
  "Update the Dirichlet posterior with a single (from, to, reward)
   triple.

   Reward acts as a multiplicative weight on the count: a reward of 0
   contributes no information; a reward of 1 contributes a unit count;
   a reward > 1 amplifies the observation. Negative rewards are clipped
   to 0 (no negative-information updates in this formulation).

   Returns updated learning state."
  [learning-state from-idx to-idx reward]
  (let [w (max 0.0 (double reward))
        updated-row (update-in (:counts learning-state)
                               [from-idx to-idx]
                               + w)]
    (-> learning-state
        (assoc :counts updated-row)
        (update :update-count inc))))

(defn observe-batch
  "Update with a batch of triples. Equivalent to repeated `observe` calls
   but more efficient for many samples."
  [learning-state triples]
  (reduce (fn [state [from to reward]]
            (observe state from to reward))
          learning-state
          triples))

(defn current-posterior-kernel
  "Return the current Markov kernel from the Dirichlet posterior:
   the maximum-likelihood (mode) estimate."
  [learning-state]
  (counts->kernel (:counts learning-state)))

(defn update-policy
  "Apply the current posterior kernel back to the policy. Returns a
   new policy with the updated kernel; metadata (level, refs) preserved."
  [policy learning-state]
  (let [new-kernel (current-posterior-kernel learning-state)]
    (p/->policy
     (-> policy
         (assoc :kernel new-kernel)
         (select-keys [:level :base-chain-ref :reward-signal-ref :state-space :kernel])))))

(defn expected-counts
  "Total observation count per row. Useful for diagnosing under-sampled
   states (rows with low counts have noisy posteriors)."
  [learning-state]
  (mapv (fn [row] (reduce + 0.0 row)) (:counts learning-state)))
