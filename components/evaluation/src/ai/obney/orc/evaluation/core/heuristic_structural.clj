(ns ai.obney.orc.evaluation.core.heuristic-structural
  "Gap-2: heuristic structural evaluator. Extracted from the retiring
   RLM Rolling Judge processor in orc-service.

   Pure function — given a raw tree-DSL S-expression, returns a
   ScoreWithFeedback-shaped map describing the tree's structural
   quality. Zero LLM calls; pattern-matches on the DSL form.

   Scoring rules (preserved verbatim from the original at
   `orc_service/core/todo_processors.clj:2742`):

   - `:sequence` root + `:final` leaf → structure-score 1.0
   - `:sequence` root alone → structure-score 0.7
   - otherwise → structure-score 0.3

   - Presence of `:map-each` → decomposition-score 1.0
   - Presence of any `:llm` node → decomposition-score 0.6
   - otherwise → decomposition-score 0.2

   Overall = 0.5 × (0.6 × structure-score + 0.4 × decomposition-score).
   Note the leading 0.5 — preserved from the original; the original
   intent appears to be 'leave headroom for richer judges'."
  (:require [clojure.walk :as walk]))

;; =============================================================================
;; Structural pattern checks
;; =============================================================================

(defn- contains-node?
  "Walk the tree and check whether any node has `tag` as its first element."
  [tree tag]
  (boolean
    (some (fn [node]
            (and (vector? node) (= tag (first node))))
          (tree-seq #(and (sequential? %) (not (map? %))) seq tree))))

(defn- has-sequence-root? [tree]
  (and (vector? tree) (= :sequence (first tree))))

(defn- has-llm? [tree] (contains-node? tree :llm))
(defn- has-final? [tree] (contains-node? tree :final))
(defn- has-map-each? [tree] (contains-node? tree :map-each))

;; =============================================================================
;; Score + feedback assembly
;; =============================================================================

(defn evaluate-tree-structure
  "Evaluate the structural shape of an RLM-generated behavior tree DSL.

   `raw-dsl` is the verbatim S-expression form (vector-of-vectors) the
   model produced via emit-tree!. Returns {:score :feedback :dimensions}
   shaped consistent with the unified judge protocol.

   Pure — no LLM call, no event store access, no global state."
  [raw-dsl]
  (let [tree raw-dsl
        seq? (has-sequence-root? tree)
        llm? (has-llm? tree)
        final? (has-final? tree)
        map-each? (has-map-each? tree)
        structure-score (cond
                          (and seq? final?) 1.0
                          seq? 0.7
                          :else 0.3)
        decomposition-score (cond
                              map-each? 1.0
                              llm? 0.6
                              :else 0.2)
        overall-score (* 0.5 (+ (* 0.6 structure-score)
                                (* 0.4 decomposition-score)))
        feedback (cond
                   (and seq? map-each? final?)
                   "Excellent tree structure with proper decomposition pattern."
                   (and seq? llm? final?)
                   "Good tree structure but could use map-each for better decomposition."
                   seq?
                   "Basic tree structure. Consider adding sub-LLM calls for analysis."
                   :else
                   "Tree structure needs improvement. Use :sequence as root.")]
    {:score overall-score
     :feedback feedback
     :dimensions [{:name "Structure"
                   :weight 0.6
                   :score structure-score
                   :feedback (if seq? "Valid sequence root" "Missing sequence root")}
                  {:name "Decomposition"
                   :weight 0.4
                   :score decomposition-score
                   :feedback (if map-each?
                               "Uses map-each for parallelism"
                               "Could benefit from map-each")}]}))
