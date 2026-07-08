(ns el3-outcome-grid
  "EL-3 /prototype (THROWAWAY): unit-derive the three-state classification
   :outcome across the (top-score, threshold, rerank-fallback?) grid.

   The de-conflation rule (ADR 0015, EL-3):
     :uncertain  — rerank-fallback? true  (reranker fell back to raw ColBERT;
                   we DO NOT KNOW the fit → defer; create/capture NOTHING).
     :matched    — NOT fallback AND top-score >= threshold (confident fit).
     :novel      — NOT fallback AND top-score <  threshold (confident no-match
                   → novelty marker; EL-1b later turns this into capture).

   Precedence: :uncertain is checked FIRST. Under a real reranker fallback the
   per-result :fitness-score is nil → top-score coerces to 0.0 → it would
   otherwise fall in the < threshold (novel) bucket and fresh-mint exactly like
   a confident novelty — the conflation EL-3 fixes. So fallback MUST short-
   circuit to :uncertain before the matched/novel split.

   Run: clojure -M:dev -m el3-outcome-grid"
  (:gen-class))

(defn derive-outcome
  "Pure three-state derivation. Mirror of the logic that will live in
   task_classifier.clj for both classify-task and classify-behaviors."
  [{:keys [top-score threshold rerank-fallback?]}]
  (cond
    rerank-fallback?            :uncertain
    (>= top-score threshold)    :matched
    :else                       :novel))

(defn -main [& _]
  (println "=== EL-3 OUTCOME GRID (deterministic) ===")
  (let [threshold 0.7
        ;; (top-score, fallback?) -> expected
        grid [;; --- reranker SUCCEEDED (fallback? false) ---
              {:top-score 0.95 :rerank-fallback? false :expect :matched}
              {:top-score 0.70 :rerank-fallback? false :expect :matched}  ; boundary: == threshold
              {:top-score 0.699 :rerank-fallback? false :expect :novel}
              {:top-score 0.30 :rerank-fallback? false :expect :novel}
              {:top-score 0.0  :rerank-fallback? false :expect :novel}    ; confident no-match (empty corpus)
              ;; --- reranker FELL BACK (fallback? true) — fitness nil -> 0.0 ---
              {:top-score 0.0  :rerank-fallback? true  :expect :uncertain} ; THE conflation case
              {:top-score 0.95 :rerank-fallback? true  :expect :uncertain} ; even a "high" raw colbert score: still uncertain
              {:top-score 0.50 :rerank-fallback? true  :expect :uncertain}]
        results (for [{:keys [top-score rerank-fallback? expect]} grid]
                  (let [got (derive-outcome {:top-score top-score
                                             :threshold threshold
                                             :rerank-fallback? rerank-fallback?})]
                    {:top-score top-score :fallback? rerank-fallback?
                     :expect expect :got got :ok? (= expect got)}))]
    (printf "%-10s %-10s %-12s %-12s %s%n" "top-score" "fallback?" "expect" "got" "ok?")
    (doseq [r results]
      (printf "%-10s %-10s %-12s %-12s %s%n"
              (:top-score r) (:fallback? r) (:expect r) (:got r) (if (:ok? r) "OK" "**FAIL**")))
    (let [all-ok? (every? :ok? results)]
      (println (format "%nGRID: %d/%d correct  ALL-OK? = %s"
                       (count (filter :ok? results)) (count results) all-ok?))
      (println "Key invariant: every fallback?=true row -> :uncertain (NEVER :novel/:matched)")
      (System/exit (if all-ok? 0 1)))))
