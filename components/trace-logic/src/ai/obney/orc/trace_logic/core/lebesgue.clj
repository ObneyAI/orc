(ns ai.obney.orc.trace-logic.core.lebesgue
  "Lebesgue order on probability measures over a finite state space.

   For ν, μ ∈ probability measures (vectors of non-negative reals
   summing to 1), the Lebesgue order is:

     ν ≤_L μ  ⟺  ν is a normalized restriction of μ
              ⟺  ∃ α > 0 such that the absolutely-continuous part
                 μ_ν of μ with respect to ν equals α·ν

   Intuitively, ν has the same shape as μ on the support of ν, and
   μ may have additional mass outside that support.

   The Lebesgue logic is non-Boolean but locally Boolean: for a fixed
   μ, the set of all ν ≤_L μ forms a Boolean sublogic (paper §4).

   The Stationary Map Theorem connects this to the trace logic: the
   map (kernel → its stationary measure) is a homomorphism from the
   trace logic to the Lebesgue logic.

   Reference: Hoffman, Prakash, Chattopadhyay (2024) §4. Lebesgue
   decomposition follows the standard measure-theoretic construction
   adapted to the finite-state setting."
  (:require [clojure.set :as cset]))

(def ^:private epsilon 1.0e-9)

(defn- approx-zero? [x]
  (< (Math/abs (double x)) epsilon))

(defn- support
  "Set of indices where the measure is non-zero."
  [mu]
  (set (keep-indexed (fn [i v] (when-not (approx-zero? v) i)) mu)))

(defn- restrict-to
  "Set entries outside `indices` to zero, leaving the others unchanged."
  [mu indices]
  (mapv (fn [i v] (if (contains? indices i) v 0.0))
        (range (count mu))
        mu))

(defn- normalize
  "Normalize a non-negative measure to a probability measure.
   Returns the all-zero vector if total mass is zero."
  [mu]
  (let [total (reduce + 0.0 mu)]
    (if (approx-zero? total)
      (vec mu)
      (mapv #(/ (double %) total) mu))))

(defn decompose
  "Lebesgue decomposition of μ with respect to ν: μ = μ_ν + μ^ν, where
   μ_ν is absolutely continuous w.r.t. ν (zero outside ν's support) and
   μ^ν is singular w.r.t. ν (zero on ν's support)."
  [mu nu]
  {:pre [(= (count mu) (count nu))]}
  (let [nu-support (support nu)]
    {:ac (restrict-to mu nu-support)
     :singular (restrict-to mu (cset/difference (set (range (count mu)))
                                                nu-support))}))

(defn leq?
  "True iff ν ≤_L μ in the Lebesgue order:
   the absolutely-continuous part of μ w.r.t. ν is a positive multiple
   of ν.

   Equivalently, on ν's support, μ has the same shape as ν (up to a
   positive scalar). μ is unconstrained outside ν's support."
  [nu mu]
  (when (= (count nu) (count mu))
    (let [{:keys [ac]} (decompose mu nu)
          nu-support (support nu)
          nu-norm (normalize (vec nu))
          ac-norm (normalize ac)]
      (cond
        (empty? nu-support) true   ; null measure ≤ anything
        ;; ac is all-zero (no shape on ν's support) — only equal if ν is also null
        (every? approx-zero? ac) false
        :else
        (every? identity
                (for [i nu-support]
                  (< (Math/abs (- (double (nth ac-norm i))
                                  (double (nth nu-norm i))))
                     epsilon)))))))

(defn meet
  "Greatest lower bound ν ∧ μ in the Lebesgue logic.

   For compatible measures (paper [7] / appendix): the meet is the
   normalized restriction of either measure to the intersection of
   their supports."
  [nu mu]
  {:pre [(= (count nu) (count mu))]}
  (let [common (cset/intersection (support nu) (support mu))]
    (if (empty? common)
      :trace-logic/empty-lebesgue-meet
      (normalize (restrict-to nu common)))))

(defn join
  "Least upper bound ν ∨ μ in the Lebesgue logic.

   For compatible measures (those with the same normalized restriction
   on their common support, paper §4), the join is the normalized
   union: ν + μ scaled so that both ν and μ are normalized restrictions
   of the result. Returns `:trace-logic/no-closed-form` for
   incompatible pairs."
  [nu mu]
  {:pre [(= (count nu) (count mu))]}
  (let [common (cset/intersection (support nu) (support mu))]
    (cond
      (empty? common)
      ;; Disjoint supports — join is the (normalized) sum of supports.
      (normalize (mapv + nu mu))

      ;; Compatible: on the common support, both are positive multiples of
      ;; the same shape. Test by comparing normalized restrictions to common.
      (let [nu-on-common (normalize (restrict-to nu common))
            mu-on-common (normalize (restrict-to mu common))]
        (every? identity
                (for [i common]
                  (< (Math/abs (- (double (nth nu-on-common i))
                                  (double (nth mu-on-common i))))
                     epsilon))))
      ;; Compatible: combine. Use the union of supports with values from
      ;; whichever measure had support there; then normalize.
      (let [combined (mapv (fn [i]
                             (let [v-nu (double (nth nu i))
                                   v-mu (double (nth mu i))]
                               (cond
                                 (and (not (approx-zero? v-nu))
                                      (not (approx-zero? v-mu)))
                                 ;; Both contribute on the common support.
                                 ;; Use their average to be symmetric.
                                 (/ (+ v-nu v-mu) 2.0)
                                 (not (approx-zero? v-nu)) v-nu
                                 (not (approx-zero? v-mu)) v-mu
                                 :else 0.0)))
                           (range (count nu)))]
        (normalize combined))

      :else :trace-logic/no-closed-form)))

;; =============================================================================
;; Stationary Map Theorem support
;; =============================================================================

(defn restrict-stationary
  "Normalized restriction of stationary measure π to subset A.

   Per the Stationary Map Theorem (paper §4): if Q = trace(P, A), then
   stationary(Q) = restrict-stationary(stationary(P), A)."
  [pi A]
  (normalize (restrict-to pi (set A))))
