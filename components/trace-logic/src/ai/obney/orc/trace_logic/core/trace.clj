(ns ai.obney.orc.trace-logic.core.trace
  "Trace order on Markov kernels.

   The trace formula gives the induced kernel on a subset A of P's
   state space (paper eq. 12):

     trace_A(P) = a + b · (I − c)⁻¹ · d

   where the block decomposition of P with respect to A is:

       P = [ a  b ]   ← rows in A; columns: [A | A']
           [ d  c ]   ← rows in A'

   Equivalently (paper eq. 10):

     trace_A(P) = I_A · P · Σ_{k≥0} (I_{A'} P)^k · I_A

   This is the unique Markovian kernel on A whose dynamics match P
   when states outside A are unobserved.

   The trace order is the partial order on Markov kernels:

     P ≤ₜ Q  ⟺  P is a trace of Q

   Reference: Hoffman, Prakash, Chattopadhyay (2024). Trace Chain
   Theorem (eq. 10–12); Trace Order Theorem (paper §3)."
  (:require [clojure.core.matrix :as m]
            [clojure.set :as cset]))

(def ^:private epsilon 1.0e-9)

(defn- approx-equal-mat?
  "Element-wise approximate equality of two matrices within tolerance."
  ([M N] (approx-equal-mat? M N epsilon))
  ([M N tol]
   (and (= (count M) (count N))
        (every? identity
                (for [i (range (count M))
                      :let [row-m (get M i)
                            row-n (get N i)]]
                  (and (= (count row-m) (count row-n))
                       (every? identity
                               (map #(< (Math/abs (- (double %1) (double %2))) tol)
                                    row-m row-n))))))))

(defn- block-decompose
  "Reorder P so that states in A come first, then partition into
   submatrices a (A→A), b (A→A'), d (A'→A), c (A'→A').

   A is a set of state indices. Result rows/cols are in sorted order
   of A and A' respectively."
  [P A]
  (let [n (count P)
        A-set (set A)
        A-vec (vec (sort A-set))
        A'-vec (vec (sort (cset/difference (set (range n)) A-set)))
        sub-mat (fn [rows cols]
                  (mapv (fn [r] (mapv #(double (get-in P [r %])) cols)) rows))]
    {:a (sub-mat A-vec A-vec)
     :b (sub-mat A-vec A'-vec)
     :d (sub-mat A'-vec A-vec)
     :c (sub-mat A'-vec A'-vec)
     :A-vec A-vec
     :A'-vec A'-vec}))

(defn projection
  "Compute the trace of P on the subset A.

   A is a set (or seq) of state indices. Returns a |A|×|A| Markov kernel
   whose row/column ordering follows the sorted order of A.

   Implements the closed form a + b·(I−c)⁻¹·d via a linear solve."
  [P A]
  (let [{:keys [a b c d A-vec A'-vec]} (block-decompose P A)]
    (cond
      (empty? A-vec)
      (throw (ex-info "Cannot trace P onto empty subset A"
                      {:A A :n (count P)}))

      (empty? A'-vec)
      ;; No hidden states; trace is just a (= P restricted to A).
      a

      :else
      (let [n' (count A'-vec)
            m-c (m/matrix c)
            I' (m/identity-matrix n')
            ;; Solve (I − c)·X = d  ⇔  X = (I − c)⁻¹·d
            ;; core.matrix's mset!-style inverse, then multiply.
            I-minus-c (m/sub I' m-c)
            inv (m/inverse I-minus-c)
            b-mat (m/matrix b)
            d-mat (m/matrix d)
            correction (m/mmul b-mat inv d-mat)
            result (m/add (m/matrix a) correction)]
        (mapv (fn [row] (mapv double (m/eseq row)))
              (m/rows result))))))

(defn of
  "Alias for `projection`: returns the trace of P on subset A."
  [P A]
  (projection P A))

(defn- subsets-of-size
  "All subsets of (range n) with the given size, returned as sorted vectors."
  [n size]
  (cond
    (zero? size) [[]]
    (> size n) []
    :else
    (loop [acc [[]] remaining n]
      (if (zero? remaining)
        (filter #(= size (count %)) acc)
        (let [next-elem (- n remaining)]
          (recur (concat acc
                         (for [s acc :when (< (count s) size)]
                           (conj s next-elem)))
                 (dec remaining)))))))

(defn all-traces
  "Enumerate all traces of P. Returns a seq of [subset, trace-kernel]
   pairs covering every non-empty subset of the state space.

   For an n-state P this is 2ⁿ − 1 entries. Tractable only for small n
   (n ≤ ~20). Useful for ground-truth tests of the trace logic."
  [P]
  (let [n (count P)]
    (for [size (range 1 (inc n))
          subset (subsets-of-size n size)
          :let [A (set subset)]]
      [A (projection P A)])))

(defn leq?
  "True iff M is a trace of N — i.e., there exists a subset A of N's
   state space such that projection(N, A) = M (within tolerance).

   Implementation: enumerate subsets of N of size |M| and test each.
   O(C(n, k)) trace computations; tractable for ORC-scale matrices."
  [M N]
  (let [m-size (count M)
        n-size (count N)]
    (cond
      (zero? m-size) true
      (> m-size n-size) false
      (= M N) true
      :else
      (let [subsets (subsets-of-size n-size m-size)]
        (boolean
         (some (fn [subset]
                 (try
                   (let [A (set subset)
                         traced (projection N A)]
                     (approx-equal-mat? traced M))
                   (catch Exception _ false)))
               subsets))))))

(defn meet
  "Greatest common trace of M and N (M ∧ N).

   For arbitrary M, N this requires knowing a common ancestor kernel P
   such that both are traces of P. If P is given via metadata or a
   third argument, returns trace(P, A_M ∩ A_N) where A_M, A_N are the
   defining subsets.

   For arbitrary M, N without such context, returns
   `:trace-logic/meet-requires-common-ancestor`. Closed-form general
   meet from M, N alone is an open problem (paper §3)."
  ([M N]
   (cond
     (= M N) M
     (leq? M N) M
     (leq? N M) N
     :else :trace-logic/meet-requires-common-ancestor))
  ([P A B]
   ;; Common ancestor P known; meet is the trace on A ∩ B.
   (let [common (cset/intersection (set A) (set B))]
     (if (empty? common)
       :trace-logic/empty-meet
       (projection P common)))))

(defn join
  "Least upper bound of M and N (M ∨ N) in the trace logic.

   Per paper §3, the trace logic is non-Boolean and the join does not
   admit a general closed form — only special cases are known (Chayan;
   paper appendix). This implementation handles the trivial cases
   (M ≤ N or N ≤ M) and otherwise returns
   `:trace-logic/no-closed-form`.

   With a known common ancestor P:
     join(P, A, B) = trace(P, A ∪ B)
   when A ∪ B's induced kernel is well-defined as the join."
  ([M N]
   (cond
     (= M N) M
     (leq? M N) N
     (leq? N M) M
     :else :trace-logic/no-closed-form))
  ([P A B]
   ;; Common ancestor P known; join is the trace on A ∪ B.
   (let [union (cset/union (set A) (set B))]
     (if (empty? union)
       :trace-logic/empty-join
       (projection P union)))))

(defn order-axioms-hold?
  "Verify the three partial-order axioms for ≤ₜ on a triple of
   matrices. Used by property-based tests; returns a map of
   {axiom → bool} so failures can be diagnosed.

   Reflexivity: ∀P. P ≤ₜ P
   Antisymmetry: P ≤ₜ Q ∧ Q ≤ₜ P ⇒ P = Q
   Transitivity: P ≤ₜ Q ∧ Q ≤ₜ R ⇒ P ≤ₜ R"
  [P Q R]
  (let [pq (leq? P Q)
        qp (leq? Q P)
        qr (leq? Q R)
        pr (leq? P R)]
    {:reflexive (leq? P P)
     :antisymmetric (if (and pq qp) (= P Q) true)
     :transitive (if (and pq qr) pr true)}))
