(ns ai.obney.orc.trace-logic.core.empirical
  "Construct conscious agents from ORC event logs via sampled trace chains.

   The orc-service event stream contains all the data needed:

     :sheet/node-execution-started   — source state, inputs (qualia)
     :sheet/node-execution-completed — terminal state, status, writes (actions)

   Discretization is configurable:

     :coarse — state = (node-id, status). Finite, derived from the tree.
     :medium — state = (node-id, status, hash-of-key-blackboard-fields).
              Discretizes blackboard variation.
     :fine   — state = full feature-vector quantization.

   Window-size selection follows paper §3: small windows give artifact-
   dominated matrices; the right window stabilizes the empirical kernel
   to within ε of the asymptotic limit."
  (:require [ai.obney.orc.trace-logic.core.ca :as ca]
            [ai.obney.orc.trace-logic.core.kernel :as k])
  (:import [java.util UUID]))

(def ^:dynamic *minimum-samples*
  "Minimum number of observed transitions required before the empirical
   kernel is considered well-defined. Below this, ca-from-events
   returns ::insufficient-samples."
  16)

;; =============================================================================
;; State discretization
;; =============================================================================

(defmulti state-of
  "Compute the discrete state for an event under the given discretization."
  (fn [discretization _event] discretization))

(defmethod state-of :coarse
  [_ event]
  (let [node-id (:node-id event)
        status (or (:status event) :running)]
    [node-id status]))

(defmethod state-of :medium
  [_ event]
  (let [node-id (:node-id event)
        status (or (:status event) :running)
        ;; Hash the input/write maps to a coarse bucket.
        relevant (or (:writes event) (:inputs event) {})
        hash-bucket (mod (hash relevant) 16)]
    [node-id status hash-bucket]))

(defmethod state-of :fine
  [_ event]
  ;; Full feature-vector quantization: include all blackboard keys.
  ;; For V1 this is identical to medium with a wider hash range.
  (let [node-id (:node-id event)
        status (or (:status event) :running)
        relevant (or (:writes event) (:inputs event) {})
        hash-bucket (mod (hash relevant) 256)]
    [node-id status hash-bucket]))

(defmethod state-of :default
  [d _]
  (throw (ex-info (str "Unknown discretization: " d)
                  {:discretization d
                   :supported [:coarse :medium :fine]})))

;; =============================================================================
;; Event filtering and ordering
;; =============================================================================

(defn- order-events
  "Sort events by (tick-id, sequence-within-tick). For V1 we trust the
   stream's natural order if no explicit ordering field is present."
  [events]
  (sort-by (juxt :tick-id (fn [e] (or (:event-seq e) 0))) events))

(defn- node-events
  "Extract events for a given node-id, restricted to the relevant types
   (started + completed)."
  [events node-id]
  (->> events
       (filter (fn [e]
                 (and (= node-id (:node-id e))
                      (#{:sheet/node-execution-started
                         :sheet/node-execution-completed}
                       (:event-type e)))))
       order-events))

(defn events->state-sequence
  "Project a stream of events onto a sequence of discrete states for the
   given node-id and discretization.

   Returns a vector of state values; consecutive entries form transitions."
  [events {:keys [node-id discretization] :or {discretization :coarse}}]
  (let [filtered (if node-id
                   (node-events events node-id)
                   (->> events
                        (filter #(#{:sheet/node-execution-started
                                    :sheet/node-execution-completed}
                                  (:event-type %)))
                        order-events))]
    (mapv #(state-of discretization %) filtered)))

;; =============================================================================
;; Empirical kernel fitting
;; =============================================================================

(defn- fit-kernel
  "Given a state sequence, build the empirical Markov kernel by counting
   transitions. Returns:
     {:state-space sorted-vector-of-distinct-states
      :kernel      n×n row-stochastic matrix
      :sample-count number-of-transitions-observed}

   Rows for states with no observed outgoing transitions default to a
   uniform distribution."
  [state-sequence]
  (let [states (vec (sort (distinct state-sequence)))
        n (count states)
        idx (zipmap states (range))
        transitions (mapv vector state-sequence (rest state-sequence))
        sample-count (count transitions)
        ;; Tally transitions
        counts (java.util.HashMap.)]
    (doseq [[from to] transitions]
      (let [key [(idx from) (idx to)]]
        (.put counts key (inc (or (.get counts key) 0)))))
    (let [kernel (mapv
                  (fn [from-i]
                    (let [row-counts (mapv (fn [to-i]
                                             (or (.get counts [from-i to-i]) 0))
                                           (range n))
                          total (reduce + 0 row-counts)]
                      (if (zero? total)
                        (vec (repeat n (/ 1.0 n)))
                        (mapv #(double (/ % total)) row-counts))))
                  (range n))]
      {:state-space states
       :kernel kernel
       :sample-count sample-count})))

(defn- input-state-sequence
  "Sequence of (input) states from started events only — used to fit Q."
  [events {:keys [node-id discretization] :or {discretization :coarse}}]
  (->> (node-events events node-id)
       (filter #(= :sheet/node-execution-started (:event-type %)))
       order-events
       (mapv #(state-of discretization %))))

(defn- output-state-sequence
  "Sequence of (output) states from completed events only — used to fit S."
  [events {:keys [node-id discretization] :or {discretization :coarse}}]
  (->> (node-events events node-id)
       (filter #(= :sheet/node-execution-completed (:event-type %)))
       order-events
       (mapv #(state-of discretization %))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn ca-from-events
  "Construct an empirical Conscious Agent for `node-id` from the event
   stream.

   Options:
     :node-id        — the leaf node whose CA we're fitting (required)
     :discretization — :coarse | :medium | :fine (default :coarse)
     :since          — filter events to those after this timestamp (optional)
     :min-samples    — override minimum-samples threshold (default 16)

   Returns a Conscious Agent map (see ca/->conscious-agent), or
   ::insufficient-samples if not enough transitions are observed.

   For V1, the empirical Q and S kernels are fitted directly from the
   input and output state sequences. The P, D, A decomposition is
   exposed as derived projections: P is identity (input is qualia),
   A is identity (output is action), D = Q (since P and A are
   identity, Q = DAP reduces to Q = D)."
  [events {:keys [node-id discretization since min-samples]
           :or {discretization :coarse
                min-samples *minimum-samples*}
           :as opts}]
  {:pre [node-id]}
  (let [events (cond->> events
                 since (filter #(>= (or (:timestamp %) 0) since)))
        Q-data (fit-kernel (input-state-sequence events opts))
        S-data (fit-kernel (output-state-sequence events opts))]
    (cond
      (< (:sample-count Q-data) min-samples)
      ::insufficient-samples

      (empty? (:state-space Q-data))
      ::insufficient-samples

      :else
      (let [Q (:kernel Q-data)
            S (or (:kernel S-data)
                  ;; If output state space is degenerate, fall back to Q
                  Q)
            ;; For empirical CAs, P and A are identity (input ≡ qualia,
            ;; output ≡ action). D = Q under that simplification.
            n (count Q)
            I (mapv (fn [i] (mapv (fn [j] (if (= i j) 1.0 0.0)) (range n))) (range n))]
        {:ca-id (UUID/randomUUID)
         :node-id node-id
         :qualia-space (:state-space Q-data)
         :action-space (:state-space S-data)
         :world-state (:state-space Q-data)
         :P-kernel I
         :D-kernel Q
         :A-kernel I
         :Q-kernel Q
         :S-kernel (let [s-n (count S)]
                     (if (= s-n n)
                       S
                       Q))
         :counter-N (:sample-count Q-data)
         :stationary-Q (try (k/stationary Q) (catch Exception _ nil))
         :stationary-S (try (k/stationary (let [s-n (count S)]
                                            (if (= s-n n) S Q)))
                            (catch Exception _ nil))
         :entropy-rate-Q (try (k/entropy-rate Q) (catch Exception _ nil))
         :entropy-rate-S (try (k/entropy-rate (let [s-n (count S)]
                                                (if (= s-n n) S Q)))
                              (catch Exception _ nil))
         :empirical? true
         :sample-count (:sample-count Q-data)}))))

(defn network-from-events
  "Construct empirical CAs for every node observed in the event stream.

   Returns a map of node-id → ConsciousAgent (or ::insufficient-samples)."
  [events {:keys [discretization sheet-id since min-samples]
           :or {discretization :coarse}}]
  (let [events (cond->> events
                 sheet-id (filter #(= sheet-id (:sheet-id %)))
                 since (filter #(>= (or (:timestamp %) 0) since)))
        node-ids (->> events
                      (keep :node-id)
                      distinct
                      vec)]
    (into {}
          (for [node-id node-ids]
            [node-id
             (ca-from-events events
                             (cond-> {:node-id node-id
                                      :discretization discretization}
                               min-samples (assoc :min-samples min-samples)))]))))

(defn- fit-kernel-on-state-space
  "Fit a Markov kernel from a state sequence, using a fixed (pre-computed)
   global state space rather than the distinct values in the sequence.

   States in the state-space that aren't observed get uniform rows."
  [state-sequence state-space]
  (let [n (count state-space)
        idx (zipmap state-space (range))
        transitions (mapv vector state-sequence (rest state-sequence))
        counts (java.util.HashMap.)]
    (doseq [[from to] transitions
            :let [from-i (get idx from)
                  to-i (get idx to)]
            :when (and from-i to-i)]
      (let [key [from-i to-i]]
        (.put counts key (inc (or (.get counts key) 0)))))
    (mapv
     (fn [from-i]
       (let [row-counts (mapv (fn [to-i]
                                (or (.get counts [from-i to-i]) 0))
                              (range n))
             total (reduce + 0 row-counts)]
         (if (zero? total)
           (vec (repeat n (/ 1.0 n)))
           (mapv #(double (/ % total)) row-counts))))
     (range n))))

(defn optimal-window
  "Empirically determine the smallest window size k such that the
   window-matrix(k) stabilizes within tolerance ε of the asymptotic
   trace.

   Algorithm: compute window matrices (averaged) for k ∈ {4, 8, 16, 32,
   64, 128, 256, 512} on the global state space, find the smallest k
   where consecutive estimates differ by less than ε in Frobenius norm.

   Returns a numeric window size."
  [state-sequence & {:keys [tolerance] :or {tolerance 0.05}}]
  (let [global-state-space (vec (sort (distinct state-sequence)))
        n (count global-state-space)
        k-candidates [4 8 16 32 64 128 256 512]
        compute-at-k
        (fn [k]
          (let [groups (partition k k nil state-sequence)
                fitted (->> groups
                            (filter #(>= (count %) 2))
                            (mapv #(fit-kernel-on-state-space % global-state-space)))]
            (when (seq fitted)
              (let [num (count fitted)]
                (mapv (fn [i]
                        (mapv (fn [j]
                                (/ (reduce + 0.0
                                           (map #(double (get-in % [i j])) fitted))
                                   num))
                              (range n)))
                      (range n))))))
        diff-frobenius
        (fn [m1 m2]
          (when (and m1 m2 (= (count m1) (count m2)))
            (Math/sqrt
             (reduce + 0.0
                     (for [i (range (count m1))
                           j (range (count (first m1)))]
                       (let [d (- (double (get-in m1 [i j]))
                                  (double (get-in m2 [i j])))]
                         (* d d)))))))
        results (mapv compute-at-k k-candidates)]
    (loop [pairs (mapv vector k-candidates results (rest results))]
      (if-let [[k m1 m2] (first pairs)]
        (let [d (diff-frobenius m1 m2)]
          (if (and d (< d tolerance))
            k
            (recur (rest pairs))))
        (last k-candidates)))))
