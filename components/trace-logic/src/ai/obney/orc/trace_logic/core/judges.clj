(ns ai.obney.orc.trace-logic.core.judges
  "Analysis judges over conscious-agent kernels.

   Each judge is a standalone function that takes a CA (or pair of CAs)
   and returns a numeric score in [0, 1] (or boolean for ordering
   judges). They match GEPA's metric-fn signature so consumers can use
   them as drop-in optimization targets.

   The judges:
     λ-2-judge              — Hoffman intelligence metric, mapped to [0,1]
                              by convergence half-life
     stationary-proximity   — TV-distance to a target outcome distribution
     strategy-coherence     — entropy-based; lower S-entropy ⇒ more directed
     trace-comparison       — boolean: is CA A ≤ CA B in the trace order
     rcc-report             — structured: communicating-class decomposition

   Ground truth is the Stationary Map Theorem: for any (P, A),
   `stationary(trace(P, A)) = lebesgue/restrict(stationary(P), A)`.
   Stationary-proximity is well-defined under this homomorphism."
  (:require [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.spectral :as spec]
            [ai.obney.orc.trace-logic.core.trace :as trace]
            [ai.obney.orc.trace-logic.core.community :as comm]
            [ai.obney.orc.trace-logic.core.ca :as ca]))

;; =============================================================================
;; λ-2 judge: convergence rate as intelligence metric
;; =============================================================================

(defn lambda-2-judge
  "Score a CA's qualia kernel by convergence rate.

   Score is mapped from λ₂ to [0, 1] via:
     score = 1 - λ₂

   Smaller λ₂ ⇒ faster convergence ⇒ higher score (more 'intelligent'
   in Hoffman's sense). Bounded in [0, 1] for any Markov kernel."
  [ca-instance]
  (let [l2 (ca/lambda-2 ca-instance)]
    (max 0.0 (min 1.0 (- 1.0 l2)))))

;; =============================================================================
;; Stationary proximity: TV-distance to target outcome distribution
;; =============================================================================

(defn- total-variation [pi-1 pi-2]
  (* 0.5
     (reduce + 0.0
             (map (fn [a b] (Math/abs (- (double a) (double b))))
                  pi-1 pi-2))))

(defn stationary-proximity-judge
  "Score CA against a target stationary measure π*.

   Score is `1 - TV(π_CA, π*)` ∈ [0, 1]. Higher = closer to target.

   Both π and π* must have the same dimension. Well-definedness under
   the trace-logic homomorphism follows from the Stationary Map Theorem
   (paper §4)."
  [ca-instance target-pi]
  (let [pi (:stationary-Q ca-instance)]
    (cond
      (or (nil? pi) (nil? target-pi)) 0.0
      (not= (count pi) (count target-pi)) 0.0
      :else (- 1.0 (total-variation pi target-pi)))))

;; =============================================================================
;; Strategy coherence: how directed is the agent's strategy?
;; =============================================================================

(defn strategy-coherence-judge
  "Score CA by inverse normalized strategy-kernel entropy:
     score = 1 - H(S) / log₂(|G|)

   Where H(S) is the entropy rate of the strategy kernel. Score ∈ [0,1];
   higher = more directed strategy (less thrashing)."
  [ca-instance]
  (let [S (ca/strategy-kernel ca-instance)
        n (count S)]
    (if (or (zero? n) (= n 1))
      1.0
      (let [h (or (:entropy-rate-S ca-instance) (k/entropy-rate S))
            upper (/ (Math/log n) (Math/log 2))]
        (max 0.0 (min 1.0 (- 1.0 (/ h upper))))))))

;; =============================================================================
;; Trace comparison: is one CA ≤ another?
;; =============================================================================

(defn trace-comparison-judge
  "Boolean: does CA A precede CA B in the trace order?

   A ≤ B iff Q_A ≤ₜ Q_B AND S_A ≤ₜ S_B (paper §3). Useful as a
   monotone-improvement check across GEPA generations."
  [ca-A ca-B]
  (ca/leq? ca-A ca-B))

;; =============================================================================
;; RCC report: communicating-class structure of empirical Q-kernel
;; =============================================================================

(defn rcc-report
  "Structured analytics of CA's qualia-kernel communicating-class
   structure.

   Returns:
     {:communicating-classes [#{state-set} ...]
      :recurrent-classes      [#{state-set} ...]
      :community-partition    [#{state-set} ...]
      :bipartite              [#{A} #{B}] | :not-bipartite | :disconnected
      :num-rccs               int
      :is-confined?           bool   (single bipartite RCC)
      :is-free?               bool   (multiple disjoint RCCs)}

   Used to surface structural workflow features: tightly-coupled
   subgoals (RCCs), nested-goal hierarchies (communities), and
   alternation patterns (bipartite)."
  [ca-instance]
  (let [Q (ca/qualia-kernel ca-instance)
        cc (k/communicating-classes Q)
        rcc (k/recurrent-classes Q)
        community-part (try (comm/detect Q) (catch Exception _ []))
        bp (comm/bipartite-decomposition Q)]
    {:communicating-classes cc
     :recurrent-classes rcc
     :community-partition community-part
     :bipartite bp
     :num-rccs (count rcc)
     :is-confined? (and (= 1 (count rcc)) (vector? bp))
     :is-free? (>= (count rcc) 2)}))
