(ns embed-perf-prototype
  "PROTOTYPE (perf) — quantify the two confirmed embedding costs and prove the fix
   direction BEFORE any production change:

     A) BASELINE (current `embed-texts-batch`): `(mapv embed-text texts)` — a NEW
        DJL Predictor per text (`.newPredictor` + single `.predict`), NO reuse/batch.
     B) REUSE-PREDICTOR: ONE predictor, single `.predict` per text (isolates the
        predictor-creation-per-text cost).
     C) BATCH-PREDICT: ONE predictor, `.batchPredict` over the whole list (isolates
        the batched-inference win on top of reuse).

   The production pipeline ALSO embeds every concept TWICE (embed-concepts! computes
   the batch then the embed-concept command re-embeds), so the end-to-end current cost
   is ~2×A. We report that factor too.

   CORRECTNESS: B and C must produce vectors byte-identical (within fp tolerance) to A
   — a speedup that changes the vectors is NOT acceptable (#4 behavior-preserving).

   USAGE: clj -J-Xmx4g -M:dev:test -m embed-perf-prototype [N]"
  (:require [ai.obney.orc.ontology.core.embedding :as embedding])
  (:import [java.util ArrayList]))

(defn- sample-texts
  "N realistic concept-embedding texts (label + description shape, varied length)."
  [n]
  (mapv (fn [i]
          (str "Occupation " i ": "
               "A standardized career or job category identified by an O*NET-SOC "
               "code and title. Performs specialized work activities requiring "
               "domain knowledge, skills, and abilities relevant to role " i "."))
        (range n)))

(defn- ms [f]
  (let [t0 (System/nanoTime) v (f) t1 (System/nanoTime)]
    {:ms (/ (- t1 t0) 1e6) :v v}))

(defn- max-abs-diff [a b]
  (reduce max 0.0 (map (fn [x y] (Math/abs (double (- x y)))) a b)))

(defn -main [& [n-str]]
  (let [n (Integer/parseInt (or n-str "1000"))
        texts (sample-texts n)
        model (embedding/get-embedding-model)]
    (println "\n===== EMBED PERF PROTOTYPE =====")
    (println "N texts:" n "  model:" embedding/default-model-id)
    ;; warmup (model graph optimizer + JIT) — a few embeds, not timed.
    (dotimes [_ 3] (embedding/embed-text (first texts)))

    ;; A — BASELINE: new predictor per text (the current embed-texts-batch path).
    (let [{a-ms :ms a-v :v} (ms #(mapv (fn [t] (embedding/embed-text t)) texts))

          ;; B — reuse ONE predictor, single .predict per text.
          {b-ms :ms b-v :v}
          (ms #(with-open [p (.newPredictor model)]
                 (mapv (fn [t] (mapv double (.predict p t))) texts)))

          ;; C — reuse ONE predictor, .batchPredict over the whole list.
          {c-ms :ms c-v :v}
          (ms #(with-open [p (.newPredictor model)]
                 (let [out (.batchPredict p (ArrayList. ^java.util.List (vec texts)))]
                   (mapv (fn [fa] (mapv double fa)) out))))

          ;; correctness — B and C vs A (max abs elementwise diff across all vectors)
          bd (reduce max 0.0 (map max-abs-diff a-v b-v))
          cd (reduce max 0.0 (map max-abs-diff a-v c-v))]

      (println "\n--- throughput (embed each of" n "texts ONCE) ---")
      (printf  "  A baseline (new predictor / text) : %8.1f ms  (%6.1f texts/s)\n" a-ms (/ n (/ a-ms 1000.0)))
      (printf  "  B reuse predictor (single predict): %8.1f ms  (%6.1f texts/s)  speedup x%.1f\n" b-ms (/ n (/ b-ms 1000.0)) (/ a-ms b-ms))
      (printf  "  C reuse + batchPredict            : %8.1f ms  (%6.1f texts/s)  speedup x%.1f\n" c-ms (/ n (/ c-ms 1000.0)) (/ a-ms c-ms))
      (println "\n--- correctness (max abs elementwise diff vs baseline A) ---")
      (printf  "  B vs A: %.2e   C vs A: %.2e   (≈0 ⇒ byte-invariant)\n" bd cd)
      (println "\n--- double-embed factor (pipeline embeds every concept TWICE today) ---")
      (printf  "  current end-to-end ≈ 2 × A = %8.1f ms;  fixed (single-pass, path C) = %8.1f ms;  overall x%.1f\n"
               (* 2 a-ms) c-ms (/ (* 2 a-ms) c-ms))
      (println "================================"))
    (embedding/close-all-embedding-models!)
    (shutdown-agents)))
