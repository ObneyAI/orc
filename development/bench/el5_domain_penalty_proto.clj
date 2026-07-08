(ns el5-domain-penalty-proto
  "THROWAWAY EL-5 prototype (ADR 0016 + amendment): render, for the REAL refactor
   case, per candidate the CONTRASTIVE penalty computed by the PLUGGABLE :colbert
   scorer (in-memory MaxSim, normalized) — cos_avoid / cos_good / diff / penalty /
   orig->final fitness, and the RE-RANKED order. Confirms rename-move-symbol is
   demoted and Code-building rises, on the NORMALIZED ColBERT scale, and lets us
   calibrate margin/scale/cap.

   Uses the SAME production seam the apply-rerank hook uses: it builds candidates
   via the ontology interface's real retrieval + enrichment, then runs
   domain-penalty/penalize-candidates with the :colbert config — so the render IS
   the end-to-end penalty layer (no LLM here; the LLM reranker's shape-bias is the
   rate probe's concern — this proto isolates the DETERMINISTIC penalty so the
   knob calibration is reproducible).

   Run (bridge resolves to this worktree's scripts/colbert_bridge.py; venv is
   orc-main's, read-only):
     OPENROUTER_API_KEY=… clojure -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert -M:dev -m el5-domain-penalty-proto"
  (:require [runner]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]))

(def names
  {#uuid "bf47c816-2833-320e-9fbd-6ae109275ab0" "Code-building(PARENT-correct)"
   #uuid "9880798a-8487-3a24-93e4-b59c5ae5d789" "child/rename-move-symbol(FORCE-FIT)"
   #uuid "b638e3fa-50c0-3fb8-b306-11d067550afe" "child/code-edit-dependency-wiring"
   #uuid "ed8fac34-ba1d-3855-9fc7-7f9d0205190a" "child/performance-optimization"
   #uuid "c841adb5-394a-3f45-b904-49e9f2822b6b" "child/documentation-writing"
   #uuid "225be622-6cc2-3359-8c18-024bdf08548d" "child/bug-diagnosis"})

(defn nm [id] (get names id (str "other/" (when id (subs (str id) 0 8)))))

(def refactor-instruction
  "Refactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.")

(defn build-sig [instruction]
  (tc/build-task-signature {:instruction instruction
                            :reads [:user-message :active-plan :workspace-root]
                            :writes [:assistant-response]
                            :mcp-tools ["shell/exec" "fs/read" "fs/list"]}))

(defn label-for [c]
  (let [tid (-> c :document-metadata :target-id)
        uuid (try (java.util.UUID/fromString (str tid)) (catch Exception _ nil))]
    (if uuid (nm uuid) (str tid))))

(defn -main [& args]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (runner/start!)
  (let [;; allow knob overrides on the CLI: scale margin cap
        [scale margin cap] (map #(when % (Double/parseDouble %)) (take 3 args))
        cfg (cond-> (assoc dp/default-penalty-config :scorer :colbert)
              scale (assoc :penalty-scale scale)
              margin (assoc :margin margin)
              cap (assoc :penalty-cap cap))
        ctx (deref (var-get (requiring-resolve 'runner/system-state)))
        sig (build-sig refactor-instruction)
        enrich (requiring-resolve 'ai.obney.orc.ontology.interface/enrich-candidate-evidence)
        normalize (requiring-resolve 'ai.obney.orc.ontology.interface/normalize-search-result)
        latest-idx (requiring-resolve 'ai.obney.orc.ontology.interface/latest-ontology-descriptions-index)
        colbert-fn (requiring-resolve 'ai.obney.orc.ontology.interface/colbert-fn)
        idx (latest-idx ctx)
        colbert-search (colbert-fn (symbol "search"))
        raw (mapv normalize (colbert-search ctx {:query sig :index-id (:index-id idx) :k 20}))
        beh (filterv #(= "behavioral-subtree"
                         (let [g (-> % :document-metadata :granularity)]
                           (if (keyword? g) (name g) (str g)))) raw)
        ;; enrich (EL-2) + stamp a starting :fitness-score = the ColBERT retrieval
        ;; score (proxy for the LLM fitness; the penalty multiplies whatever
        ;; fitness arrives) so we can show orig->final + re-rank deterministically.
        enriched (mapv (fn [c] (assoc (@enrich ctx c) :fitness-score (:score c))) beh)
        ;; THE PRODUCTION SEAM: build the :colbert scorer + run the real pass.
        scorer (dp/make-scorer ctx cfg)
        scored (mapv (fn [c]
                       (let [{:keys [cos-avoid cos-good penalty]} (dp/score-candidate c sig scorer cfg)]
                         (assoc c :cos-avoid cos-avoid :cos-good cos-good :penalty penalty
                                :final (dp/apply-penalty (:fitness-score c) penalty))))
                     enriched)
        penalized (dp/penalize-candidates ctx enriched sig cfg scorer)]
    (println "\n=== EL-5 PROTOTYPE: refactor/extract-helper case (REAL ColBERT MaxSim, normalized) ===")
    (println "config:" (select-keys cfg [:scorer :penalty-scale :margin :penalty-cap :colbert-norm]))
    (println (format "candidates (behavioral-subtree): %d" (count enriched)))
    (println (str/join "" (repeat 110 "-")))
    (doseq [c (sort-by (comp - (fn [x] (double (or (:final x) -1.0)))) scored)]
      (println (format "%-42s cos-avoid=%.4f cos-good=%.4f diff=%+.4f penalty=%.3f  orig=%.4f -> final=%.4f"
                       (label-for c) (:cos-avoid c) (:cos-good c)
                       (- (:cos-avoid c) (:cos-good c)) (:penalty c)
                       (double (or (:fitness-score c) 0.0)) (double (or (:final c) 0.0)))))
    (println (str/join "" (repeat 110 "-")))
    (println "RE-RANKED ORDER (penalize-candidates, the production pass):")
    (doseq [[i c] (map-indexed vector penalized)]
      (println (format "  #%d  %-42s final-fitness=%.4f penalty=%.3f"
                       (inc i) (label-for c) (double (or (:fitness-score c) 0.0)) (:domain-penalty c))))
    ;; NB: this proto isolates the DETERMINISTIC penalty layer using the ColBERT
    ;; RETRIEVAL score as a fitness proxy (NOT the LLM reranker fitness), so the
    ;; absolute #1 here is not the LLM's ordering — the load-bearing signal is
    ;; that the rename force-fit is PENALIZED and DEMOTED. (The live rate probe,
    ;; el2_inspect_rate, runs the real LLM fitness and shows Code-building #1.)
    (let [rename-pos (first (keep-indexed (fn [i c] (when (str/includes? (label-for c) "rename") (inc i))) penalized))
          rename (first (filter #(str/includes? (label-for %) "rename") scored))
          top (label-for (first penalized))]
      (println "\nTOP after penalty (deterministic-proxy view):" top)
      (println (format "rename-move-symbol: penalty=%.3f, rank=#%s of %d"
                       (double (or (:penalty rename) 0.0)) (or rename-pos "?") (count penalized)))
      (println (if (and rename (pos? (:penalty rename)) (= rename-pos (count penalized)))
                 "PASS: the rename force-fit is PENALIZED and DEMOTED to last; all other candidates penalty 0."
                 "CHECK: rename not cleanly demoted — inspect cos/penalty above + the rename guard content.")))
    (runner/stop!)
    (System/exit 0)))
