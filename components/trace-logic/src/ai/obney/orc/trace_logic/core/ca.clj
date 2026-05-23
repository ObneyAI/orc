(ns ai.obney.orc.trace-logic.core.ca
  "Conscious Agent type — the 7-tuple from *Traces of Consciousness*.

      C = ((X, X), (G, G), (W, W), P, D, A, N)

   where:
     X = qualia space (read schema in ORC)
     G = action space (write schema)
     W = world state space (blackboard projection)
     P : W → X  — world-to-qualia projection
     D : X → G  — qualia-to-action decision
     A : G → W  — action-to-world effect
     N         — counter / tick index

   The qualia kernel is Q = D·A·P (paper eq. 5); the strategy kernel
   is S = A·P·D (paper eq. 24). Two CAs are compared via both kernels:
   A ≤ B ⟺ Q_A ≤ₜ Q_B ∧ S_A ≤ₜ S_B."
  (:require [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.trace :as trace]
            [ai.obney.orc.trace-logic.core.spectral :as spec])
  (:import [java.util UUID]))

(defn ->conscious-agent
  "Construct a CA from explicit P, D, A kernels.

   Computes derived fields: Q-kernel, S-kernel, stationary measures,
   entropy rates. All kernels must be Markov and have matching
   dimensions appropriate to their domains:
     P : W × X → [0,1]  (rows = |W|, cols = |X|)
     D : X × G → [0,1]  (rows = |X|, cols = |G|)
     A : G × W → [0,1]  (rows = |G|, cols = |W|)

   For V1, all three kernels are required to be square with the same
   dimension n (qualia / action / world spaces all have size n). This
   matches the paper's worked examples and is sufficient for ORC's
   leaf-node mapping. Distinct dimensions are a future generalization."
  [{:keys [node-id qualia-space action-space world-state
           P-kernel D-kernel A-kernel counter-N]
    :or {counter-N 0}}]
  {:pre [(k/markov? P-kernel)
         (k/markov? D-kernel)
         (k/markov? A-kernel)
         (= (count P-kernel) (count D-kernel) (count A-kernel))]}
  (let [Q (k/compose D-kernel A-kernel P-kernel)
        S (k/strategy-of D-kernel A-kernel P-kernel)
        stat-Q (try (k/stationary Q) (catch Exception _ nil))
        stat-S (try (k/stationary S) (catch Exception _ nil))
        h-Q (try (k/entropy-rate Q) (catch Exception _ nil))
        h-S (try (k/entropy-rate S) (catch Exception _ nil))]
    {:ca-id (UUID/randomUUID)
     :node-id node-id
     :qualia-space qualia-space
     :action-space action-space
     :world-state world-state
     :P-kernel P-kernel
     :D-kernel D-kernel
     :A-kernel A-kernel
     :Q-kernel Q
     :S-kernel S
     :counter-N counter-N
     :stationary-Q stat-Q
     :stationary-S stat-S
     :entropy-rate-Q h-Q
     :entropy-rate-S h-S}))

(defn qualia-kernel
  "Q = D·A·P (paper eq. 5)."
  [ca]
  (:Q-kernel ca))

(defn strategy-kernel
  "S = A·P·D (paper eq. 24)."
  [ca]
  (:S-kernel ca))

(defn lambda-2
  "λ₂ of the qualia kernel — the intelligence metric for this CA."
  [ca]
  (spec/lambda-2 (qualia-kernel ca)))

(defn leq?
  "CA ordering: A ≤ B ⟺ Q_A ≤ₜ Q_B ∧ S_A ≤ₜ S_B (paper §3).

   Both qualia and strategy kernels must be in the trace order;
   either alone is insufficient."
  [ca-A ca-B]
  (and (trace/leq? (qualia-kernel ca-A) (qualia-kernel ca-B))
       (trace/leq? (strategy-kernel ca-A) (strategy-kernel ca-B))))

(defn equiv?
  "Trace-logic equivalence: A ≤ B ∧ B ≤ A.
   Distinct from value equality (= ca-A ca-B) — two CAs are
   equivalent in the trace order when neither dominates the other."
  [ca-A ca-B]
  (and (leq? ca-A ca-B) (leq? ca-B ca-A)))

(defn increment-counter
  "Advance the counter N by one (paper §5 enhanced-chain construction)."
  [ca]
  (update ca :counter-N inc))
