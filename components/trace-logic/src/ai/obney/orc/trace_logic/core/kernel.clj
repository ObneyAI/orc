(ns ai.obney.orc.trace-logic.core.kernel
  "Markov kernel primitives.

   A Markov kernel on a finite state space {0, 1, ..., n-1} is represented
   as a vector of vectors of doubles, where row i, column j is the
   transition probability P(j | i). Rows must sum to 1; entries must be
   non-negative."
  (:require [clojure.core.matrix :as m]
            [clojure.set :as cset]))

(m/set-current-implementation :vectorz)

(def ^:private epsilon 1.0e-10)

(defn- approx=
  ([a b] (approx= a b epsilon))
  ([a b eps] (< (Math/abs (- (double a) (double b))) eps)))

(defn- row-sum [row] (reduce + 0.0 row))

(defn- non-negative? [row]
  (every? #(>= (double %) (- epsilon)) row))

(defn markov?
  "True iff P is row-stochastic with non-negative entries.

   Tolerates floating-point error to within `epsilon`."
  [P]
  (and (sequential? P)
       (every? sequential? P)
       (let [n (count P)]
         (and (pos? n)
              (every? #(= n (count %)) P)
              (every? non-negative? P)
              (every? #(approx= (row-sum %) 1.0) P)))))

(defn semi-markov?
  "True iff P, restricted to rows in `support`, is row-stochastic and
   non-negative; rows outside `support` are all zero (semi-Markovian per
   paper §3)."
  [P support]
  (and (sequential? P)
       (let [n (count P)
             support-set (set support)]
         (and (every? sequential? P)
              (every? #(= n (count %)) P)
              (every? non-negative? P)
              (every? identity
                      (map-indexed
                       (fn [i row]
                         (if (contains? support-set i)
                           (approx= (row-sum row) 1.0)
                           (approx= (row-sum row) 0.0)))
                       P))))))

(defn- to-matrix [P] (m/matrix P))

(defn- normalize-stationary [v]
  (let [s (reduce + 0.0 v)]
    (if (zero? s)
      v
      (mapv #(/ % s) v))))

(defn stationary
  "Return the stationary measure π satisfying π·P = π, Σπ = 1.

   For ergodic P, this is the unique left-eigenvector of P with
   eigenvalue 1, normalized to a probability distribution.

   For reducible P with multiple recurrent communicating classes, the
   stationary measure is non-unique; this implementation returns the
   uniform mixture over all RCCs' invariant measures (a canonical choice).

   Computed via the iterative power method on Pᵀ to avoid numerical
   issues with eigendecomposition for near-degenerate cases."
  [P]
  (let [n (count P)
        Pt (m/transpose (to-matrix P))
        ;; Start from uniform; iterate v ← Pᵀv until convergence
        max-iter 10000
        tol 1.0e-12]
    (loop [v (vec (repeat n (/ 1.0 n)))
           iter 0]
      (if (>= iter max-iter)
        (normalize-stationary v)
        (let [v' (m/mmul Pt v)
              v'-vec (vec (m/eseq v'))
              v'-norm (normalize-stationary v'-vec)
              diff (reduce + 0.0
                           (map #(Math/abs (- (double %1) (double %2)))
                                v'-norm v))]
          (if (< diff tol)
            v'-norm
            (recur v'-norm (inc iter))))))))

(defn compose
  "Markov kernel composition by matrix multiplication.

   For three kernels P, A, D representing the (P, A, D) of a CA, the
   qualia kernel is Q = D·A·P (paper eq. 5).

   Accepts arbitrary number of kernels; right-folds matrix multiplication."
  [& kernels]
  (when (seq kernels)
    (let [matrices (map to-matrix kernels)
          product (reduce m/mmul matrices)]
      (mapv vec (m/rows product)))))

(defn strategy-of
  "The strategy kernel S = A·P·D (paper §3, eq. 24).

   Same composition as `compose` but with the canonical action-kernel
   permutation: APD instead of DAP."
  [D A P]
  (compose A P D))

(defn entropy-rate
  "Entropy rate of an ergodic Markov kernel (paper §6, eq. 47):
     H(P) = −Σᵢⱼ μᵢ Pᵢⱼ log₂ Pᵢⱼ
   where μ is the stationary measure of P.

   Returns 0 for periodic kernels (rows have a single unit entry)."
  [P]
  (let [mu (stationary P)
        n (count P)]
    (- (reduce
        +
        0.0
        (for [i (range n)
              j (range n)
              :let [pij (double (get-in P [i j]))]
              :when (> pij epsilon)]
          (* (double (nth mu i))
             pij
             (/ (Math/log pij) (Math/log 2))))))))

(defn- reachable-from
  "BFS forward-reachable set from state s in P."
  [P s]
  (let [n (count P)]
    (loop [seen #{s}
           frontier #{s}]
      (if (empty? frontier)
        seen
        (let [next-frontier (reduce
                             (fn [acc i]
                               (into acc
                                     (for [j (range n)
                                           :when (and (not (seen j))
                                                      (> (double (get-in P [i j])) epsilon))]
                                       j)))
                             #{}
                             frontier)]
          (recur (into seen next-frontier)
                 next-frontier))))))

(defn- reachable-to
  "BFS backward-reachable set to state s in P."
  [P s]
  (let [n (count P)]
    (loop [seen #{s}
           frontier #{s}]
      (if (empty? frontier)
        seen
        (let [next-frontier (reduce
                             (fn [acc j]
                               (into acc
                                     (for [i (range n)
                                           :when (and (not (seen i))
                                                      (> (double (get-in P [i j])) epsilon))]
                                       i)))
                             #{}
                             frontier)]
          (recur (into seen next-frontier)
                 next-frontier))))))

(defn communicating-classes
  "Partition the state space into communicating classes.

   States i and j are in the same class iff each is reachable from the
   other under P. Returns a vector of state-sets covering all of
   {0, ..., n-1}.

   Implemented via reachable-forward ∩ reachable-backward (textbook
   Tarjan-style decomposition; sufficient for ORC-scale matrices)."
  [P]
  (let [n (count P)]
    (loop [unassigned (set (range n))
           classes []]
      (if (empty? unassigned)
        classes
        (let [s (first unassigned)
              fwd (reachable-from P s)
              bwd (reachable-to P s)
              cls (cset/intersection fwd bwd)]
          (recur (cset/difference unassigned cls)
                 (conj classes cls)))))))

(defn recurrent-classes
  "Recurrent communicating classes (RCCs): communicating classes that
   are closed under P (i.e., no transitions exit the class).

   Per paper §5 these classify particles via decorated permutations."
  [P]
  (let [n (count P)
        classes (communicating-classes P)]
    (filterv
     (fn [cls]
       (every?
        (fn [i]
          (every?
           (fn [j]
             (or (contains? cls j)
                 (approx= (double (get-in P [i j])) 0.0)))
           (range n)))
        cls))
     classes)))
