(ns ai.obney.orc.trace-logic.core.sampled
  "Sampled trace chains: finite-window approximations of trace operations
   (paper §3, eq. 13–22).

   The trace formula is an ideal: it sums infinite returns through the
   hidden states. In real observation we have finite samples. Sampled
   trace chains construct approximate trace kernels from sequences of
   observations.

   Operations:
   - `run-from`: simulate a sample run of length n from a Markov kernel.
   - `restrict-run`: project a run onto a subset A by deleting all states
     outside A.
   - `window-matrix`: partition a restricted run into k-step groups and
     fit one Markov matrix per group (paper eq. 18, 21, 22).
   - `asymptotic-trace`: limiting case k → length(run); converges to the
     true trace as the run grows.
   - `convergence-test`: empirically measure how fast window-matrix(k)
     approaches the true trace as k grows.

   Reference: Hoffman, Prakash, Chattopadhyay (2024) §3."
  (:require [ai.obney.orc.trace-logic.core.trace :as trace]))

(defn run-from
  "Simulate a length-n random run of P starting from state x₀.

   Returns a vector of state indices of length n + 1 (including x₀)."
  [P x0 n]
  (let [num-states (count P)
        ;; Pre-build cumulative distribution functions per row for fast sampling.
        ;; Row [.3 .2 .5] becomes CDF [.3 .5 1.0]; sample u ∈ [0,1) and find
        ;; smallest j such that u ≤ CDF[j].
        cdfs (mapv (fn [row] (vec (reductions + row))) P)
        ^java.util.Random rng (java.util.Random.)]
    (loop [run [x0]
           current x0
           remaining n]
      (if (zero? remaining)
        run
        (let [u (.nextDouble rng)
              next-state
              (loop [j 0]
                (if (>= j num-states)
                  (dec num-states)
                  (if (<= u (double (get-in cdfs [current j])))
                    j
                    (recur (inc j)))))]
          (recur (conj run next-state) next-state (dec remaining)))))))

(defn restrict-run
  "Filter a run to keep only states in subset A (paper §3, eq. 16)."
  [run A]
  (let [A-set (set A)]
    (vec (filter A-set run))))

(defn- fit-markov-from-transitions
  "Given a sequence of state transitions and the state space size n,
   fit a Markov matrix by maximum likelihood (count-based).

   Rows with no observed outgoing transitions default to a uniform
   distribution to maintain the Markov property."
  [transitions n state-space]
  (let [;; Build a state → row-index lookup. state-space is sorted vector.
        idx (zipmap state-space (range))
        counts (java.util.HashMap.)]
    ;; Tally transitions
    (doseq [[from to] transitions]
      (let [k [from to]]
        (.put counts k (inc (or (.get counts k) 0)))))
    ;; Build the row-stochastic matrix
    (mapv
     (fn [from-state]
       (let [row-counts (mapv (fn [to-state]
                                (or (.get counts [from-state to-state]) 0))
                              state-space)
             total (reduce + 0 row-counts)]
         (if (zero? total)
           ;; No outgoing observations; uniform distribution
           (vec (repeat n (/ 1.0 n)))
           (mapv #(double (/ % total)) row-counts))))
     state-space)))

(defn window-matrix
  "Partition the restricted run into length-k groups and fit a Markov
   matrix from the transitions within each group.

   Returns a vector of fitted matrices, one per group (paper eq. 18,
   21, 22). Groups with insufficient data fall back to uniform rows."
  [run A k]
  (let [restricted (restrict-run run A)
        state-space (vec (sort A))
        n (count state-space)
        groups (partition k k nil restricted)]
    (vec
     (for [group groups
           :let [transitions (map vector group (rest group))]
           :when (seq transitions)]
       (fit-markov-from-transitions transitions n state-space)))))

(defn asymptotic-trace
  "Single sampled-trace matrix from the entire restricted run (k = run length)."
  [run A]
  (let [restricted (restrict-run run A)
        state-space (vec (sort A))
        n (count state-space)
        transitions (map vector restricted (rest restricted))]
    (if (seq transitions)
      (fit-markov-from-transitions transitions n state-space)
      ;; Empty run; degenerate case
      (vec (repeat n (vec (repeat n (/ 1.0 n))))))))

(defn- frobenius-distance
  "Sqrt of sum of squared element-wise differences between two matrices."
  [M N]
  (Math/sqrt
   (reduce
    + 0.0
    (for [i (range (count M))
          j (range (count (first M)))]
      (let [d (- (double (get-in M [i j]))
                 (double (get-in N [i j])))]
        (* d d))))))

(defn convergence-test
  "Verify that window-matrix(k) converges to trace(P, A) as k → ∞.

   Generates a single long run, then for each k in `k-seq` builds a
   window-matrix and measures Frobenius distance to the true trace.

   Returns a seq of [k, distance] pairs. Distance should generally
   decrease as k grows, modulo finite-sample noise."
  [P A k-seq run-length]
  (let [run (run-from P 0 run-length)
        true-trace (trace/projection P A)]
    (vec
     (for [k k-seq
           :let [windows (window-matrix run A k)
                 ;; Average across windows for a single estimate
                 avg (when (seq windows)
                       (let [n (count (first windows))
                             num-windows (count windows)]
                         (mapv
                          (fn [i]
                            (mapv
                             (fn [j]
                               (/ (reduce + 0.0
                                          (map #(double (get-in % [i j])) windows))
                                  num-windows))
                             (range n)))
                          (range n))))]
           :when avg]
       [k (frobenius-distance avg true-trace)]))))

(defn optimal-window
  "Find the smallest window size k such that window-matrix(k) is within
   tolerance ε of the asymptotic trace.

   Currently a stub — fully implemented in Phase 3 once empirical CA
   construction is in place. For now returns a reasonable default."
  ([_events _node-id] 32)
  ([_events _node-id _tolerance] 32))
