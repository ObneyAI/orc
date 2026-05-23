(ns ai.obney.orc.trace-logic.core.policy
  "Policies as Markov kernels over observer windows.

   A Policy_n is a Markov kernel typed by its `:level`:

     :level 0  — kernel over observer windows of a base CA's Q-kernel.
                 State space = subsets of the base CA's state space.
     :level n  — kernel over Policy_{n-1} kernels (meta-policy).

   Sampling from a Policy_0 selects 'which window to look through next'
   given the current window. In ORC this drives adaptive child selection
   in :fallback / :sequence / :parallel composite nodes.

   This phase implements material from the May 2026 lecture not yet in
   the published paper. All operations reduce to Phase 1 primitives
   applied to higher-level kernels."
  (:require [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.spectral :as spec]
            [ai.obney.orc.trace-logic.core.trace :as trace])
  (:import [java.util UUID Random]))

(defn ->policy
  "Construct a Policy at the given level from a Markov kernel.

   Required:
     :kernel — n×n Markov kernel
     :level  — non-negative integer

   Optional:
     :base-chain-ref — reference to the base chain whose trace logic
                       this kernel lives over (for level 0: a CA's
                       Q-kernel; for level n: a level-(n-1) policy)
     :reward-signal-ref — fn or ref to a reward function
     :state-space    — vector of identifiers for the kernel's states
                       (e.g., observer windows for level 0)"
  [{:keys [kernel level base-chain-ref reward-signal-ref state-space]
    :or {level 0}}]
  {:pre [(k/markov? kernel) (>= level 0)]}
  {:policy-id (UUID/randomUUID)
   :level level
   :base-chain-ref base-chain-ref
   :kernel kernel
   :state-space (or state-space (vec (range (count kernel))))
   :stationary (try (k/stationary kernel) (catch Exception _ nil))
   :lambda-2 (try (spec/lambda-2 kernel) (catch Exception _ nil))
   :entropy-rate (try (k/entropy-rate kernel) (catch Exception _ nil))
   :reward-signal-ref reward-signal-ref})

(defn sample-next
  "Sample the next state from the policy's kernel given the current
   state index. Returns a state index in [0, n)."
  ([policy current-state-idx]
   (sample-next policy current-state-idx (Random.)))
  ([policy current-state-idx ^Random rng]
   (let [kernel (:kernel policy)
         row (nth kernel current-state-idx)
         u (.nextDouble rng)
         n (count row)]
     (loop [j 0 cumsum 0.0]
       (if (>= j n)
         (dec n)
         (let [next-cumsum (+ cumsum (double (nth row j)))]
           (if (<= u next-cumsum)
             j
             (recur (inc j) next-cumsum))))))))

(defn sample-from-stationary
  "Sample a state from the policy's stationary measure (used to choose
   an initial focus when no current state is set)."
  ([policy] (sample-from-stationary policy (Random.)))
  ([policy ^Random rng]
   (let [pi (:stationary policy)
         u (.nextDouble rng)
         n (count pi)]
     (loop [j 0 cumsum 0.0]
       (if (>= j n)
         (dec n)
         (let [next-cumsum (+ cumsum (double (nth pi j)))]
           (if (<= u next-cumsum)
             j
             (recur (inc j) next-cumsum))))))))

;; =============================================================================
;; Trace-logic operations on policies (polymorphic over :level)
;; =============================================================================

(defn policy-leq?
  "Trace order on policies: P ≤ₜ Q on their kernels.

   Type-uniform across :level — works on Policy_0, Policy_1, etc."
  [policy-A policy-B]
  (trace/leq? (:kernel policy-A) (:kernel policy-B)))

(defn policy-trace
  "Compute the trace of the policy's kernel onto a subset A of states.
   Returns a new policy at the same level."
  [policy A]
  (->policy
   {:kernel (trace/projection (:kernel policy) A)
    :level (:level policy)
    :base-chain-ref (:base-chain-ref policy)}))

;; =============================================================================
;; Identity / uniform policies (useful as baselines and starting points)
;; =============================================================================

(defn uniform-policy
  "A Policy whose kernel has uniform rows: every state is equally likely
   to follow any state. Convergence rate is 0 (instant). Useful as a
   maximally-exploratory baseline."
  [n & {:keys [level] :or {level 0}}]
  (->policy
   {:kernel (vec (repeat n (vec (repeat n (/ 1.0 n)))))
    :level level
    :state-space (vec (range n))}))

(defn deterministic-policy
  "A Policy that always returns the same next state (a deterministic
   permutation kernel). Useful as a fully-exploitative baseline."
  [next-state-fn n & {:keys [level] :or {level 0}}]
  (let [kernel (mapv (fn [i]
                       (let [j (next-state-fn i)]
                         (mapv #(if (= % j) 1.0 0.0) (range n))))
                     (range n))]
    (->policy
     {:kernel kernel
      :level level
      :state-space (vec (range n))})))
