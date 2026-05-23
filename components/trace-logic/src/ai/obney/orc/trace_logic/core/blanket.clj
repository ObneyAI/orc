(ns ai.obney.orc.trace-logic.core.blanket
  "Trace blanket — the self/world boundary derived from a CA's
   policy-conditional control statistics.

   Hoffman's notion of self/world is *not* a Markov blanket on a DAG.
   It is a partition of the base CA's state space inferred from how
   tightly the agent controls each state under a given policy:

     :self states  — states with low entropy in the policy-conditional
                     outgoing distribution H(P(·|s, π)). The agent has
                     high control here.

     :world states — states with high entropy in the same conditional.
                     The agent is reactive here, not directive.

   The boundary is per-policy. Land Phase 6 last; treat as exploratory."
  (:require [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.ca :as ca]))

(def ^:private ln2 (Math/log 2))

(defn- row-entropy
  "Shannon entropy of a probability row, in bits."
  [row]
  (- (reduce + 0.0
             (for [p row
                   :let [pp (double p)]
                   :when (> pp 1.0e-12)]
               (* pp (/ (Math/log pp) ln2))))))

(defn state-entropies
  "Vector of per-state outgoing-distribution entropies of a kernel."
  [kernel]
  (mapv row-entropy kernel))

(defn partition-by-entropy
  "Partition the state space by per-state outgoing entropy.

   With explicit threshold τ ∈ [0, log₂ n]: states with H(P(·|s)) < τ
   are :self; the rest are :world.

   With :auto, τ is the median entropy across states (so the partition
   is balanced). Returns:
     {:self    #{state-indices}
      :world   #{state-indices}
      :threshold τ
      :entropies vector-of-per-state-entropies}"
  ([kernel] (partition-by-entropy kernel :auto))
  ([kernel threshold]
   (let [entropies (state-entropies kernel)
         n (count entropies)
         tau (cond
               (= threshold :auto)
               ;; Use median; balanced partition by construction
               (let [sorted (vec (sort entropies))]
                 (if (zero? (mod n 2))
                   (/ (+ (nth sorted (/ n 2))
                         (nth sorted (dec (/ n 2))))
                      2.0)
                   (nth sorted (quot n 2))))
               (number? threshold) (double threshold)
               :else
               (throw (ex-info "Threshold must be :auto or a number"
                               {:threshold threshold})))]
     {:self (set (keep-indexed (fn [i e] (when (< (double e) tau) i)) entropies))
      :world (set (keep-indexed (fn [i e] (when (>= (double e) tau) i)) entropies))
      :threshold tau
      :entropies entropies})))

(defn ca-trace-blanket
  "Compute the trace blanket of a CA under its qualia kernel.

   The blanket partitions the CA's state space into :self and :world
   based on the qualia-kernel's per-state outgoing entropy.

   For a CA driven by an explicit policy, pass the policy's effective
   kernel via the optional `:policy-kernel` argument (e.g., the
   policy-conditional Q kernel after Bayesian updates)."
  [ca-instance & {:keys [threshold policy-kernel]
                  :or {threshold :auto}}]
  (let [kernel (or policy-kernel (ca/qualia-kernel ca-instance))]
    (assoc (partition-by-entropy kernel threshold)
           :ca-id (:ca-id ca-instance)
           :node-id (:node-id ca-instance))))

(defn relabel-invariant?
  "Check that the partition is invariant under arbitrary relabeling of
   states. Used as a property-test invariant.

   Constructs a relabeled kernel by permuting rows and columns
   identically; the partition (under the same permutation) must
   match."
  [kernel permutation]
  (let [n (count kernel)
        permuted-kernel
        (mapv (fn [pi]
                (mapv (fn [pj]
                        (double (get-in kernel [pi pj])))
                      permutation))
              permutation)
        original (partition-by-entropy kernel)
        permuted (partition-by-entropy permuted-kernel)
        ;; Apply permutation to original's :self indices
        original-self-permuted
        (set (map (fn [i] (.indexOf ^java.util.List permutation i))
                  (:self original)))]
    (= original-self-permuted (:self permuted))))
