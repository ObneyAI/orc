(ns ai.obney.orc.trace-logic.interface.schemas
  "Malli schemas for the trace-logic component.

   Defines the shapes of:
   - Markov kernels (square row-stochastic matrices)
   - Probability measures (vectors of non-negative reals summing to 1)
   - Conscious agents (the 7-tuple from the paper)
   - Observer windows (subsets of a base CA's state space)
   - Policies and meta-policies (level-indexed Markov kernels)")

(def MarkovKernel
  "An n×n row-stochastic matrix represented as a vector of vectors of doubles."
  [:vector {:min 1} [:vector {:min 1} :double]])

(def ProbabilityMeasure
  "A non-negative measure summing to 1."
  [:vector {:min 1} :double])

(def StateIndex
  "A non-negative integer indexing a state in a kernel's state space."
  [:int {:min 0}])

(def StateSet
  "A set of state indices."
  [:set StateIndex])

(def ConsciousAgent
  "Conscious agent as defined in the paper:
     C = ((X, X), (G, G), (W, W), P, D, A, N)

   Mapped onto an ORC node:
     X = read schema (qualia)
     G = write schema (actions)
     W = blackboard projection (world)
     P = world → qualia (read projection)
     D = qualia → action (LLM + instruction)
     A = action → world (write effect)
     Q = DAP (induced experience-to-experience)
     S = APD (induced action-to-action)"
  [:map
   [:ca-id :uuid]
   [:node-id {:optional true} :keyword]
   [:qualia-space :any]
   [:action-space :any]
   [:world-state :any]
   [:P-kernel MarkovKernel]
   [:D-kernel MarkovKernel]
   [:A-kernel MarkovKernel]
   [:Q-kernel MarkovKernel]
   [:S-kernel MarkovKernel]
   [:counter-N {:default 0} :int]
   [:stationary-Q {:optional true} ProbabilityMeasure]
   [:stationary-S {:optional true} ProbabilityMeasure]
   [:entropy-rate-Q {:optional true} :double]
   [:entropy-rate-S {:optional true} :double]])

(def ObserverWindow
  "A subset of a base CA's state space — the observer's current focus."
  [:map
   [:base-ca-id :uuid]
   [:state-indices StateSet]])

(def Policy
  "A level-indexed Markov kernel.
     :level 0 = kernel over observer windows of a base CA
     :level n = kernel over (level n-1) policies"
  [:map
   [:policy-id :uuid]
   [:level [:int {:min 0}]]
   [:base-chain-ref :any]
   [:kernel MarkovKernel]
   [:stationary {:optional true} ProbabilityMeasure]
   [:reward-signal-ref {:optional true} :any]
   [:lambda-2 {:optional true} :double]
   [:entropy-rate {:optional true} :double]])
