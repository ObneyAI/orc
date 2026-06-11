(ns ai.obney.orc.orc-service.core.rlm-fingerprint
  "C-2a-2: tree-fingerprint — deterministic stable hash derived from an
   emit-tree! S-expression, used to aggregate rolling metrics across
   structurally-equivalent trees.

   The hash is stable across the inline-fn sanitization step (R-3) so
   that the same logical tree produces the same fingerprint regardless
   of whether the live SCI fn objects are still resolvable or already
   replaced with [:fn \"<inline-fn>\"] placeholders.

   Collapsed in normalization (not part of the fingerprint):
   - inline (fn ...) bodies — implementation detail
   - :instruction strings on :llm nodes — content, not structure

   Kept in normalization (part of the fingerprint):
   - tree shape (:sequence, :parallel, :map-each composition)
   - :reads / :writes blackboard keys
   - control args (:max-concurrency, :size, :delimiter, :output-schemas)
   - node-type position in the tree (an :llm at position N is different
     from a :code at position N)"
  (:require [clojure.walk :as walk]))

(def ^:private fn-placeholder
  "Canonical placeholder for :fn values in the normalized tree. Collapses
   inline fn values, [:fn \"<inline-fn>\"] markers (sanitize-tree-for-events
   output), and qualified-symbol-string fn references to the same canonical
   form so the fingerprint is stable across all three representations."
  :fingerprint/fn)

(def ^:private instruction-placeholder
  "Canonical placeholder for :instruction string values in the normalized
   tree. Instructions are content; the fingerprint is keyed on structure."
  :fingerprint/instruction)

(defn- normalize
  "Walk a tree and replace each :fn / :instruction map-entry value with
   a canonical placeholder so content variations collapse on the same
   hash while structural choices are preserved."
  [tree]
  (walk/postwalk
    (fn [node]
      (cond
        (and (map-entry? node) (= :fn (key node)))
        [:fn fn-placeholder]

        (and (map-entry? node) (= :instruction (key node)))
        [:instruction instruction-placeholder]

        :else node))
    tree))

(defn fingerprint
  "Compute a deterministic fingerprint hash for a tree S-expression.
   Normalizes the tree (collapsing :fn representations to a canonical
   placeholder) before hashing."
  [tree]
  (str (hash (normalize tree))))
