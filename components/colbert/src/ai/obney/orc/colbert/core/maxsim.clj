(ns ai.obney.orc.colbert.core.maxsim
  "Exact MaxSim — the late-interaction score (CONTEXT.md Retrieval): each query
   token takes its best-matching candidate token, and those best matches are
   summed. Pure, total float-array math: no I/O, fully golden-testable.

   Punctuation semantics (mask_punctuation=true, P-0 verified): ZERO-NOT-DROP.
   The Python reference zeroes punctuation rows before normalize and its max
   still ranges over them, so a skiplisted doc position contributes a 0.0
   CANDIDATE to each query token's max — it is NOT excluded. Pure exclusion
   would differ whenever every real dot is negative.

   Encoder rows are already unit-normed inside the ONNX graph, so the dot IS
   the cosine; the score is bounded by the query token count (32 here)."
  (:refer-clojure :exclude []))

(defn dot
  "Dot product of two float arrays (same length)."
  ^double [^floats a ^floats b]
  (let [n (alength a)]
    (loop [i 0 acc 0.0]
      (if (< i n)
        (recur (inc i) (+ acc (* (aget a i) (aget b i))))
        acc))))

(defn max-sim
  "Exact MaxSim with zero-not-drop skiplist semantics.

     q-rows   - seqable of query per-token float arrays (ALL rows score,
                including the MASK query-expansion rows)
     d-rows   - seqable of document per-token float arrays (CLS/marker/SEP
                rows DO score)
     d-ids    - the document token ids, aligned with d-rows
     skiplist - set of token ids whose positions contribute a 0.0 candidate
                instead of their dot (punctuation masking)

   Returns the summed per-query-token maxima (double). The max is a TRUE max
   over the candidates (an all-negative doc with no skiplisted position stays
   negative — only a skiplisted position introduces a 0.0 candidate). An empty
   document contributes 0.0 per query token."
  ^double [q-rows d-rows d-ids skiplist]
  (let [d-pairs (mapv (fn [id ^floats row]
                        [(boolean (contains? skiplist id)) row])
                      d-ids d-rows)]
    (if (empty? d-pairs)
      0.0
      (reduce
       (fn [^double acc ^floats q-row]
         (+ acc
            (double
             (reduce (fn [^double best [skip? ^floats d-row]]
                       (max best (if skip? 0.0 (dot q-row d-row))))
                     Double/NEGATIVE_INFINITY
                     d-pairs))))
       0.0
       q-rows))))
