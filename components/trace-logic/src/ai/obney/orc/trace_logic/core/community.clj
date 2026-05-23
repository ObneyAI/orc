(ns ai.obney.orc.trace-logic.core.community
  "Community / partite-set decomposition of Markov kernels.

   Communities are clusters of states that are tightly coupled internally
   but weakly coupled across the cluster boundary. They correspond to
   subgoal structure in agency dynamics (paper §6, §7; lecture).

   Two operations:

   - `detect`: spectral clustering on slow eigenvectors (those with
     eigenvalue magnitude near 1). The corresponding eigenmodes are the
     slowest-mixing components of the dynamics; clustering on their
     sign patterns yields the community partition.

   - `bipartite-decomposition`: returns the partite sets when the
     kernel's diagram is bipartite (e.g., the 'confined' kernel in
     paper §6, fig. 10–11). Equivalent to detecting period-2
     communicating-class structure."
  (:require [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.spectral :as spec]
            [clojure.set :as cset]))

(def ^:private epsilon 1.0e-9)

(defn- nonzero-edges
  "Set of [from to] pairs with non-zero transition probability."
  [P]
  (let [n (count P)]
    (set
     (for [i (range n)
           j (range n)
           :when (> (Math/abs (double (get-in P [i j]))) epsilon)]
       [i j]))))

(defn detect
  "Spectral community detection on Markov kernel P.

   Algorithm:
   1. Eigen-decompose P.
   2. Take the top-k eigenvectors by eigenvalue magnitude (excluding the
      principal eigenvector with eigenvalue 1, which contains no community
      information — it's the stationary mode).
   3. Cluster states by sign pattern across those eigenvectors.

   Returns a vector of state-sets covering all of {0, ..., n-1}.

   `k` is the number of slow modes to use; corresponds to the number of
   communities up to 2^k. Defaults to ⌈log₂(n)⌉ + 1."
  ([P] (detect P nil))
  ([P k]
   (let [n (count P)
         k (or k (max 1 (int (Math/ceil (/ (Math/log n) (Math/log 2))))))
         {:keys [magnitudes vectors]} (spec/eigen-decompose P)
         ;; Sort eigenvector indices by eigenvalue magnitude descending.
         sorted-indices (->> (range n)
                             (sort-by #(- (double (nth magnitudes %))))
                             vec)
         ;; Skip the principal mode (index 0 in sorted order) and take next k.
         slow-mode-indices (->> sorted-indices
                                (drop 1)
                                (take k)
                                vec)
         ;; For each state, build a sign-pattern string from the slow modes.
         sign-pattern (fn [state-idx]
                        (mapv (fn [mode-idx]
                                ;; vectors is a row-of-rows; column mode-idx
                                ;; gives the eigenvector. State-idx is the row.
                                (let [v (double (get-in vectors [state-idx mode-idx]))]
                                  (cond
                                    (> v epsilon) :pos
                                    (< v (- epsilon)) :neg
                                    :else :zero)))
                              slow-mode-indices))
         ;; Group states by sign pattern.
         grouped (group-by sign-pattern (range n))]
     (vec (map (comp set val) grouped)))))

(defn bipartite-decomposition
  "Detect bipartite structure in the kernel's transition diagram.

   A kernel is bipartite if its state space partitions into A ∪ B such
   that every transition goes from A to B or B to A (no within-set
   transitions; self-loops permitted and ignored). The 'confined'
   particle pattern in paper §6 is the canonical example.

   Returns:
   - `[A B]` (a vector of two state-sets) if the diagram is bipartite.
   - `:not-bipartite` if no such partition exists.
   - `:disconnected` if the diagram has multiple connected components."
  [P]
  (let [n (count P)
        edges (set (filter (fn [[i j]] (not= i j)) (nonzero-edges P)))]
    (cond
      (zero? n) :disconnected

      :else
      (loop [color {0 :A}
             queue (list 0)]
        (cond
          (empty? queue)
          (if (= (count color) n)
            (let [a-set (set (keep (fn [[k v]] (when (= v :A) k)) color))
                  b-set (set (keep (fn [[k v]] (when (= v :B) k)) color))]
              [a-set b-set])
            :disconnected)

          :else
          (let [v (first queue)
                rest-q (rest queue)
                v-color (color v)
                neighbor-color (if (= v-color :A) :B :A)
                neighbors (set
                           (concat
                            (keep (fn [[i j]] (when (= i v) j)) edges)
                            (keep (fn [[i j]] (when (= j v) i)) edges)))
                conflict? (some (fn [u]
                                  (and (contains? color u)
                                       (= (color u) v-color)))
                                neighbors)]
            (if conflict?
              :not-bipartite
              (let [new-neighbors (remove #(contains? color %) neighbors)
                    new-color (reduce (fn [c u] (assoc c u neighbor-color))
                                      color
                                      new-neighbors)]
                (recur new-color
                       (concat rest-q new-neighbors))))))))))
