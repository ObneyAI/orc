(ns ai.obney.orc.trace-logic.interface
  "Recursive trace logic for ORC — a faithful, opt-in port of the
   Hoffman/Prakash/Chattopadhyay framework for conscious agents.

   References:
   - Traces of Consciousness (Hoffman, Prakash, Chattopadhyay; 2024).
     preprints.org 10.20944/preprints202410.1305.v1
   - Multiscale logic of collective intelligence and the recursive
     trace logic (Hoffman lecture; 2026). The recursive extension
     used in Phase 5+ is sourced from the lecture and is not yet
     in the published paper.

   The substrate is Markov kernels with a partial-order structure
   (the trace order). Every primitive in this component reduces to
   operations on Markov kernels; nothing here depends on a specific
   model, prompt, or workflow shape."
  (:require [ai.obney.orc.trace-logic.core.kernel :as kernel]
            [ai.obney.orc.trace-logic.core.trace :as trace]
            [ai.obney.orc.trace-logic.core.lebesgue :as lebesgue]
            [ai.obney.orc.trace-logic.core.spectral :as spectral]
            [ai.obney.orc.trace-logic.core.community :as community]
            [ai.obney.orc.trace-logic.core.sampled :as sampled]
            [ai.obney.orc.trace-logic.core.ca :as ca]
            [ai.obney.orc.trace-logic.core.network :as network]
            [ai.obney.orc.trace-logic.core.empirical :as empirical]
            [ai.obney.orc.trace-logic.core.judges :as judges]
            [ai.obney.orc.trace-logic.core.policy :as policy]
            [ai.obney.orc.trace-logic.core.learning :as learning]
            [ai.obney.orc.trace-logic.core.meta-policy :as meta-policy]
            ;; Loading strategy registers :adaptive and :policy with orc-service.
            [ai.obney.orc.trace-logic.core.strategy :as strategy]
            [ai.obney.orc.trace-logic.core.blanket :as blanket]))

;; =============================================================================
;; Markov kernel primitives
;; =============================================================================

(def markov? kernel/markov?)
(def semi-markov? kernel/semi-markov?)
(def stationary kernel/stationary)
(def compose kernel/compose)
(def strategy-of kernel/strategy-of)
(def entropy-rate kernel/entropy-rate)
(def communicating-classes kernel/communicating-classes)
(def recurrent-classes kernel/recurrent-classes)

;; =============================================================================
;; Trace order
;; =============================================================================

(def trace-of trace/of)
(def trace-projection trace/projection)
(def trace-leq? trace/leq?)
(def all-traces trace/all-traces)
(def trace-meet trace/meet)
(def trace-join trace/join)

;; =============================================================================
;; Lebesgue order
;; =============================================================================

(def lebesgue-leq? lebesgue/leq?)
(def lebesgue-decompose lebesgue/decompose)
(def lebesgue-meet lebesgue/meet)
(def lebesgue-join lebesgue/join)

;; =============================================================================
;; Spectral and community structure
;; =============================================================================

(def eigen-decompose spectral/eigen-decompose)
(def lambda-2 spectral/lambda-2)
(def detect-communities community/detect)
(def bipartite-decomposition community/bipartite-decomposition)

;; =============================================================================
;; Sampled trace chains
;; =============================================================================

(def sample-run sampled/run-from)
(def restrict-run sampled/restrict-run)
(def window-matrix sampled/window-matrix)
(def asymptotic-trace sampled/asymptotic-trace)

;; =============================================================================
;; Conscious agents
;; =============================================================================

(def ->conscious-agent ca/->conscious-agent)
(def qualia-kernel ca/qualia-kernel)
(def strategy-kernel ca/strategy-kernel)
(def ca-lambda-2 ca/lambda-2)
(def ca-leq? ca/leq?)
(def ca-equiv? ca/equiv?)

;; =============================================================================
;; CA networks
;; =============================================================================

(def ->network network/->network)
(def get-ca network/get-ca)
(def all-cas network/all-cas)
(def aggregate-Q network/aggregate-Q)
(def aggregate-S network/aggregate-S)
(def network-leq? network/leq?)

;; =============================================================================
;; Empirical fitting from event log
;; =============================================================================

(def ca-from-events empirical/ca-from-events)
(def network-from-events empirical/network-from-events)
(def optimal-window empirical/optimal-window)

;; =============================================================================
;; Analysis judges (GEPA metric-fn compatible)
;; =============================================================================

(def lambda-2-judge judges/lambda-2-judge)
(def stationary-proximity-judge judges/stationary-proximity-judge)
(def strategy-coherence-judge judges/strategy-coherence-judge)
(def trace-comparison-judge judges/trace-comparison-judge)
(def rcc-report judges/rcc-report)

;; =============================================================================
;; Policies (Policy_n) and meta-policies (Phase 5)
;; =============================================================================

(def ->policy policy/->policy)
(def policy-leq? policy/policy-leq?)
(def sample-next-state policy/sample-next)
(def uniform-policy policy/uniform-policy)
(def deterministic-policy policy/deterministic-policy)

(def ->meta-policy meta-policy/->meta-policy)
(def select-sub-policy meta-policy/select-sub-policy)
(def lift-to-meta meta-policy/lift-to-meta)
(def policy-level meta-policy/level)

(def ->dirichlet-state learning/->dirichlet-state)
(def observe learning/observe)
(def observe-batch learning/observe-batch)
(def update-policy learning/update-policy)

(def register-policy! strategy/register!)
(def unregister-policy! strategy/unregister!)
(def clear-tick-state! strategy/clear-tick-state!)

;; =============================================================================
;; Trace blanket — self/world boundary (Phase 6)
;; =============================================================================

(def trace-blanket blanket/ca-trace-blanket)
(def state-entropies blanket/state-entropies)
