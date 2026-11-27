(ns el5-1-batch-proto
  "THROWAWAY EL-5.1 prototype: prove the BATCH design before TDD.

   Validates two things with pure data (no bridge, no DJL, no LLM):
     1. A batch scorer that gathers the DISTINCT guard set across ALL candidates,
        makes ONE rerank call, builds a content->normalized-score map, and serves
        per-candidate {:cos-avoid :cos-good} from it, yields the IDENTICAL cosines
        as the per-candidate (N-call) scorer.
     2. The batch design makes EXACTLY ONE rerank call for M candidates (a call
        counter on the stub rerank-fn).

   Run: clojure -M:dev -m el5-1-batch-proto  (no env needed — fully stubbed)"
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.logic :as ontology-logic]))

(def candidates
  [{:document-id "A"
    :avoid-when ["extract or refactor, not a pure rename"]
    :content "behavior-preserving pure rename of a symbol"
    :strengths [{:good-when "a pure rename across files"}]}
   {:document-id "B"
    :avoid-when ["a one-line config tweak"]
    :content "build/extract/refactor code structure into a helper"
    :strengths [{:good-when "extract a pure helper from a handler"}]}
   {:document-id "C"
    :avoid-when ["extract or refactor, not a pure rename"]
    :content "rename a local variable"
    :strengths [{:good-when "a pure rename across files"}]}])

(def task "refactor: extract a pure helper from the request handler")

(defn fake-score [content]
  (let [t-tokens (set (str/split (str/lower-case task) #"\W+"))
        c-tokens (str/split (str/lower-case content) #"\W+")]
    (* 5.0 (count (filter t-tokens c-tokens)))))

(defn norm-fn [score] (min 1.0 (max 0.0 (/ (double score) 40.0))))

(defn per-candidate-rerank-fn [calls]
  (fn [{:keys [documents]}]
    (swap! calls inc)
    (mapv (fn [c] {:content c :score (fake-score c)}) documents)))

(defn batch-rerank-fn [calls]
  (fn [{:keys [documents]}]
    (swap! calls inc)
    (mapv (fn [c] {:content c :score (fake-score c)}) documents)))

(defn -main [& _]
  (let [n-calls (atom 0)
        rf (per-candidate-rerank-fn n-calls)
        per-cand (into {}
                       (map (fn [c]
                              [(:document-id c)
                               (ontology-logic/colbert-rerank-scores rf norm-fn
                                                         (ontology-logic/avoid-strings c)
                                                         (ontology-logic/legacy-positive-strings c)
                                                         task)]))
                       candidates)
        b-calls (atom 0)
        brf (batch-rerank-fn b-calls)
        all-strings (distinct (mapcat (fn [c] (concat (ontology-logic/avoid-strings c)
                                                      (ontology-logic/legacy-positive-strings c)))
                                      candidates))
        all-strings (remove (fn [s] (or (nil? s) (str/blank? s))) all-strings)
        res (brf {:query task :documents (vec all-strings)})
        score-map (into {} (map (juxt :content (comp norm-fn :score))) res)
        max-from-map (fn [strings]
                       (let [vs (keep score-map strings)]
                         (if (seq vs) (apply max vs) 0.0)))
        batch-cand (into {}
                         (map (fn [c]
                                [(:document-id c)
                                 {:cos-avoid (max-from-map (ontology-logic/avoid-strings c))
                                  :cos-good  (max-from-map (ontology-logic/legacy-positive-strings c))}]))
                         candidates)]
    (println "=== EL-5.1 BATCH PROTO ===")
    (println "candidates:" (count candidates))
    (println (format "N-call (per-candidate) rerank calls: %d" @n-calls))
    (println (format "BATCH rerank calls:                  %d" @b-calls))
    (println "distinct guard strings:" (count all-strings))
    (doseq [c candidates
            :let [id (:document-id c)
                  p (get per-cand id)
                  b (get batch-cand id)]]
      (println (format "  %s  per-call: avoid=%.4f good=%.4f | batch: avoid=%.4f good=%.4f | %s"
                       id (:cos-avoid p) (:cos-good p) (:cos-avoid b) (:cos-good b)
                       (if (and (== (:cos-avoid p) (:cos-avoid b))
                                (== (:cos-good p) (:cos-good b)))
                         "IDENTICAL" "*** DIVERGED ***"))))
    (let [identical? (every? (fn [c]
                               (let [id (:document-id c)
                                     p (get per-cand id) b (get batch-cand id)]
                                 (and (== (:cos-avoid p) (:cos-avoid b))
                                      (== (:cos-good p) (:cos-good b)))))
                             candidates)]
      (println)
      (println (if (and identical? (= 1 @b-calls) (= (count candidates) @n-calls))
                 (format "PASS: batch makes 1 call (vs %d per-candidate) and cosines are IDENTICAL."
                         @n-calls)
                 "FAIL: see divergence / call count above."))
      (System/exit (if identical? 0 1)))))
