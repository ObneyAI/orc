(ns cc17-costs
  "CC-17 — the TWO KNOWN COSTS of changing maximum_query_tokens, measured
   rather than hand-waved, on REAL production data:

     1. The [MASK] pedestal. Raising the limit adds soft-expansion rows.
        Measured in BOTH regimes: SHORT queries (where the pedestal grows
        worst) and the LONG real production signatures (where it shrinks,
        because today they fill the budget exactly).

     2. The MaxSim ceiling. 32.0 IS query_maxlen, so every constant fitted to
        that scale must be re-derived or confirmed unaffected — above all
        `:margin 0.010` in ontology domain_penalty.clj.

   Real inputs only: candidates are the living-description bodies from the
   2,713-event production dump; tasks are the real consolidator inference
   signature and the real classifier task signature. Real encoder, real
   MaxSim, real domain-penalty scorer. Prints its N."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [time-literals.read-write]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]))

(def corpus-path
  "The P-E production dump (2,713 events, 2026-07-30 -> 2026-08-03). Override
   with -Dcc17.corpus when the scratchpad copy is gone; re-dump with
   scratchpad/pe/dump_corpus.clj."
  (or (System/getProperty "cc17.corpus")
      "/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc-sessions/1dcb8677-d588-4d07-b419-779f8489bfdb/scratchpad/pe/corpus-a89f9f58-9761-42c9-bc67-94acba7bd4f2.edn"))

(def candidate-sample-size 20)

(def short-queries
  "The SHORT regime — the golden-fixture queries the checkpoint was calibrated
   on, plus ontology's own short retrieval surfaces."
  ["arbitration clause"
   "termination notice period"
   "intellectual property ownership"
   "binding arbitration of contract disputes"])

(defn- pct [xs p]
  (let [v (vec (sort xs)) n (count v)]
    (nth v (min (dec n) (max 0 (int (Math/ceil (- (* (/ p 100.0) n) 1))))))))

(defn- stats [xs]
  (when (seq xs)
    {:n (count xs)
     :min (apply min xs) :p50 (pct xs 50) :p95 (pct xs 95) :max (apply max xs)
     :mean (/ (reduce + xs) (double (count xs)))}))

(defn- fmt [m]
  (if m
    (format "n=%d min %.4f | p50 %.4f | p95 %.4f | max %.4f | mean %.4f"
            (:n m) (double (:min m)) (double (:p50 m)) (double (:p95 m))
            (double (:max m)) (double (:mean m)))
    "(none)"))

(defn body->candidate
  "The enriched-candidate shape domain-penalty reads (avoid-strings /
   positive-strings), built from a real living-description body.

   CC-16 note: this bench's document pools now read `legacy-positive-strings`
   (`:content` + `:good-when`) so it keeps measuring EXACTLY the pool it measured
   when the CC-17 evidence was captured. `positive-strings` is now the ADR-0026
   `:good-when` signal alone; see development/bench/cc16_shadow_rate.clj for the
   contrast distribution under BOTH readings."
  [body]
  {:content (:summary body)
   :avoid-when (vec (:avoid-when body))
   :strengths (vec (:strengths body))
   :weaknesses (vec (:weaknesses body))
   :fitness-score 0.8})

(defn rerank-at
  "A rerank-fn pinned to a specific maximum_query_tokens."
  [limit]
  (fn [{:keys [query documents]}]
    (operations/rerank {} {:query query :documents documents
                           :maximum-query-tokens limit})))

(defn contrast-run
  "One (limit, task) measurement over the candidate pool: the batch scorer's
   cos-avoid / cos-good / contrast per candidate, plus raw MaxSim ceiling
   usage and pool spread."
  [limit task candidates]
  (let [rerank (rerank-at limit)
        batch (dp/batch-colbert-scorer {} dp/default-penalty-config rerank nil)
        lookup (batch candidates task)
        rows (mapv (fn [c] (let [{:keys [cos-avoid cos-good]} (lookup c)]
                             {:cos-avoid cos-avoid :cos-good cos-good
                              :contrast (- cos-avoid cos-good)
                              :penalty (dp/domain-penalty cos-avoid cos-good
                                                          dp/default-penalty-config)}))
                   candidates)
        ;; RAW MaxSim over the same distinct guard set (ceiling + spread).
        docs (vec (distinct (remove str/blank?
                                    (concat (mapcat dp/avoid-strings candidates)
                                            (mapcat dp/legacy-positive-strings candidates)))))
        raw (rerank {:query task :documents docs})
        scores (mapv :score raw)]
    {:limit limit
     :cos-avoid (stats (map :cos-avoid rows))
     :cos-good (stats (map :cos-good rows))
     :contrast (stats (map :contrast rows))
     :penalty-fires (count (filter #(pos? (:penalty %)) rows))
     :candidates (count rows)
     :doc-pool (count docs)
     :top-1-raw (apply max scores)
     :top-1-fraction-of-ceiling (/ (apply max scores) (double limit))
     :pool-spread-raw (- (apply max scores) (apply min scores))
     :pool-spread-relative (/ (- (apply max scores) (apply min scores))
                              (double (apply max scores)))}))

(defn print-run [label r]
  (println (format "  %s  limit=%d  candidates=%d  doc-pool=%d" label (:limit r)
                   (:candidates r) (:doc-pool r)))
  (println "    cos-avoid " (fmt (:cos-avoid r)))
  (println "    cos-good  " (fmt (:cos-good r)))
  (println "    contrast  " (fmt (:contrast r)))
  (println (format "    penalty fires (margin %.3f): %d/%d"
                   (double (:margin dp/default-penalty-config))
                   (:penalty-fires r) (:candidates r)))
  (println (format "    top-1 raw MaxSim %.3f = %.4f of ceiling %d"
                   (double (:top-1-raw r)) (double (:top-1-fraction-of-ceiling r)) (:limit r)))
  (println (format "    pool spread raw %.3f (%.4f relative)"
                   (double (:pool-spread-raw r)) (double (:pool-spread-relative r)))))

(defn saturation
  "The [MASK] pedestal, measured directly: raw MaxSim of a query against an
   UNRELATED document as a fraction of the ceiling. The higher this floor, the
   less dynamic range remains for real signal."
  [limit query related unrelated]
  (let [r (operations/rerank {} {:query query :documents [related unrelated]
                                 :maximum-query-tokens limit})
        by (into {} (map (juxt :content :score)) r)]
    {:limit limit
     :related (by related)
     :unrelated (by unrelated)
     :floor-fraction (/ (double (by unrelated)) (double limit))
     :ceiling-fraction (/ (double (by related)) (double limit))
     :headroom-fraction (/ (- (double (by related)) (double (by unrelated)))
                           (double limit))}))

(defn -main [& args]
  (time-literals.read-write/print-time-literals-clj!)
  (let [limits (if (seq args) (mapv parse-long args) [32 464])
        enc (encoder/get-encoder (model-store/resolve-model-dir))
        evs (edn/read-string {:readers time-literals.read-write/tags} (slurp corpus-path))
        build-parent-sig @#'ai.obney.orc.ontology.core.consolidator/build-parent-inference-signature
        bodies (->> evs
                    (filter #(= :ontology/tree-description-updated (:event/type %)))
                    (keep :body)
                    (filter #(seq (:avoid-when %)))
                    (filter #(seq (:strengths %))))
        _ (println "REAL description bodies with BOTH avoid + strengths:" (count bodies))
        ;; Distinct by summary so the pool is 20 distinct concepts, not 20
        ;; re-consolidations of the same one.
        distinct-bodies (vec (vals (into {} (map (juxt :summary identity)) bodies)))
        _ (println "DISTINCT by summary:" (count distinct-bodies))
        candidates (mapv body->candidate (take candidate-sample-size distinct-bodies))
        task (build-parent-sig (first distinct-bodies))
        task-tokens (count (encoder/encode-ids enc task))]

    (println)
    (println "=== N ===")
    (println "  candidate pool:" (count candidates))
    (println "  task = real consolidator inference signature," task-tokens "word-piece tokens")
    (println "  limits under test:" (pr-str limits))

    (println)
    (println "=== COST 2 — MaxSim ceiling + contrast (LONG real-production query) ===")
    (let [runs (mapv #(contrast-run % task candidates) limits)]
      (doseq [r runs] (print-run "LONG" r))
      (println)
      (println "  margin 0.010 verdict data — contrast p95/max per limit:")
      (doseq [r runs]
        (println (format "    limit %3d: contrast p95 %.5f max %.5f | fires %d/%d"
                         (:limit r) (double (:p95 (:contrast r)))
                         (double (:max (:contrast r)))
                         (:penalty-fires r) (:candidates r)))))

    (println)
    (println "=== COST 1 — the [MASK] pedestal, BOTH regimes ===")
    (let [related "Any dispute arising under this agreement shall be settled by binding arbitration."
          unrelated "Preheat the oven and bake the sourdough loaf for forty minutes until golden."]
      (println "  SHORT regime (4 short queries x related/unrelated doc pair):")
      (doseq [limit limits]
        (let [rows (mapv #(saturation limit % related unrelated) short-queries)]
          (println (format "    limit %3d: floor %.4f of ceiling | signal %.4f | HEADROOM %.4f (mean over %d queries)"
                           limit
                           (/ (reduce + (map :floor-fraction rows)) (double (count rows)))
                           (/ (reduce + (map :ceiling-fraction rows)) (double (count rows)))
                           (/ (reduce + (map :headroom-fraction rows)) (double (count rows)))
                           (count rows)))))
      (println "  LONG regime (the real production signature):")
      (doseq [limit limits]
        (let [s (saturation limit task related unrelated)]
          (println (format "    limit %3d: floor %.4f of ceiling | signal %.4f | HEADROOM %.4f"
                           limit (:floor-fraction s) (:ceiling-fraction s) (:headroom-fraction s))))))

    (println)
    (println "=== TRUNCATION RATE AFTER (do not assume zero) ===")
    (let [sigs (mapv build-parent-sig (->> evs
                                           (filter #(#{:ontology/tree-description-updated
                                                       :ontology/node-instance-description-updated
                                                       :ontology/node-type-description-updated}
                                                     (:event/type %)))
                                           (keep :body)))]
      (doseq [limit limits]
        (let [reports (mapv #(operations/query-truncation {} {:query % :maximum-query-tokens limit}) sigs)
              t (count (filter :query-truncated? reports))]
          (println (format "    limit %3d: %d/%d truncated (%.1f%%)"
                           limit t (count reports)
                           (* 100.0 (/ t (double (count reports)))))))))

    (println)
    (println "=== LATENCY (one rerank call over the guard pool, warm encoder) ===")
    (doseq [limit limits]
      (let [docs (vec (distinct (remove str/blank?
                                        (concat (mapcat dp/avoid-strings candidates)
                                                (mapcat dp/legacy-positive-strings candidates)))))
            _ (operations/rerank {} {:query task :documents (vec (take 3 docs))
                                     :maximum-query-tokens limit})
            t0 (System/nanoTime)
            _ (operations/rerank {} {:query task :documents docs :maximum-query-tokens limit})
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (println (format "    limit %3d: %.0f ms for %d documents" limit ms (count docs)))))
    (System/exit 0)))
