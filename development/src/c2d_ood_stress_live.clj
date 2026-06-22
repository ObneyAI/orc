(ns c2d-ood-stress-live
  "C-Loop-3 R04 — live OOD stress sweep.

   Loads the 21-task corpus under `development/bench/ood-corpus/`, calls
   the classifier wedge for each instruction, captures the resulting
   envelope, classifies each outcome, aggregates, and persists results
   to `development/bench/ood-stress-results/<timestamp>/`.

   Two run modes:

     (run-classify-only! ctx)
       Runs ONLY the classify-task + classify-behaviors wedge for each
       instruction. Captures envelope shape but does NOT execute Phase 2
       trees. Cheap (~2-5 min OpenRouter; minimal tokens). Sufficient
       for the structural-outcome aggregate (direct-match / walk-down /
       fresh-mint / rerank-fail rates).

     (run-full-bench! ctx)
       Runs each instruction through the FULL bench/run! path including
       recursive RLM Phase 2 execution. Expensive (~20-40 min;
       meaningful token spend). Needed to verify mint-behavior! actually
       fires when the classifier returns fresh-mint markers, and to
       capture the minted behavior's body for HITL audit.

   Both modes share the same persistence layer (per-instruction EDN +
   combined EDN + markdown summary)."
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.task-classifier :as classifier]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]
            [runner]))

(def corpus-dir "development/bench/ood-corpus")
(def results-root "development/bench/ood-stress-results")

(defn- now-stamp []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "uuuu-MM-dd_HHmmss")))

(defn- run-classify-one!
  "Call classify-task directly on one instruction's text. Returns a
   per-instruction result with the envelope, the outcome classification,
   the elapsed wall-clock, and an empty parent-chain (R02 walk-down
   isn't separately captured by classify-task today; would be derivable
   from event-store walk for HITL drill-down)."
  [ctx {:keys [slug instruction]}]
  (let [start (System/currentTimeMillis)
        envelope (classifier/classify-task ctx
                   {:task-signature instruction
                    :threshold 0.6})
        elapsed (- (System/currentTimeMillis) start)
        outcome (ood/classify-outcome envelope)]
    {:slug slug
     :envelope envelope
     :outcome outcome
     :elapsed-ms elapsed
     :parent-chain []}))

(defn run-classify-only!
  "Run the classify-only sweep over the 21-task corpus. Persists results
   to `development/bench/ood-stress-results/<ts><suffix>/`. Returns
   `{:dir :corpus :results :metrics :paths}`."
  ([ctx]
   (run-classify-only! ctx {}))
  ([ctx {:keys [corpus-path dir-suffix]
         :or {corpus-path corpus-dir
              dir-suffix "-classify-only"}}]
   (let [corpus (ood/load-corpus corpus-path)
         ts (now-stamp)
         dir (str results-root "/" ts dir-suffix)
         _ (.mkdirs (io/file dir))
         _ (println "OOD sweep:" (count corpus) "instructions from" corpus-path)
         results (vec
                   (for [[i entry] (map-indexed vector corpus)]
                     (do
                       (println (format "  [%d/%d] %s ..."
                                        (inc i) (count corpus) (:slug entry)))
                       (let [r (run-classify-one! ctx entry)]
                         (println (format "    outcome=%s confidence=%.2f elapsed=%dms"
                                          (cond
                                            (-> r :outcome :rerank-fallback?) "rerank-fallback"
                                            (-> r :outcome :was-fresh-mint?) "fresh-mint"
                                            (-> r :outcome :walk-down-fired?) "walk-down"
                                            (-> r :outcome :direct-matched?) "direct-match"
                                            :else "moderate")
                                          (double (or (-> r :envelope :confidence) 0.0))
                                          (:elapsed-ms r)))
                         r))))
         metrics (ood/aggregate-metrics results)
         paths (ood/persist-run! {:dir dir
                                  :corpus corpus
                                  :results results
                                  :metrics metrics})]
     (println "\n=== Aggregate ===")
     (println "  total:" (:total-count metrics))
     (println "  direct-match:" (:direct-match-count metrics)
              (str "(" (format "%.1f%%" (* 100.0 (:direct-match-rate metrics))) ")"))
     (println "  walk-down:" (:walk-down-fired-count metrics)
              (str "(" (format "%.1f%%" (* 100.0 (:walk-down-fired-rate metrics))) ")"))
     (println "  fresh-mint:" (:fresh-mint-count metrics)
              (str "(" (format "%.1f%%" (* 100.0 (:fresh-mint-rate metrics))) ")"))
     (println "  rerank-fail:" (:rerank-failure-count metrics)
              (str "(" (format "%.1f%%" (* 100.0 (:rerank-failure-rate metrics))) ")"))
     (println "\nResults saved to" dir)
     (println "  combined EDN:" (:combined paths))
     (println "  markdown:" (:markdown paths))
     {:dir dir :corpus corpus :results results :metrics metrics :paths paths})))

(comment
  ;; From REPL with runner started:
  (require '[runner])
  (runner/start!)
  (require '[c2d-ood-stress-live :as live] :reload)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (live/run-classify-only! ctx))
  (runner/stop!))
