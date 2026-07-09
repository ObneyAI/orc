(ns el2-inspect-rate
  "INSPECT-ORC probe: measure the refactor force-fit RATE.
   classify-behaviors goes through search-descriptions -> apply-rerank, which now
   (EL-5, ADR 0016) applies the deterministic CONTRASTIVE domain penalty via the
   :colbert scorer (DEFAULT) on TOP of EL-2's :avoid-when enrichment. So N runs on
   the refactor signature measure how reliably the penalty down-ranks the rename
   child (Code-building correct) vs lets the force-fit through. The reranker is an
   LLM (non-deterministic) — a single run can't settle the verdict; the penalty
   itself is deterministic given the scorer, so the RATE measures the LLM x
   penalty composite. This is case (1) of the EL-5 four-case set. THROWAWAY."
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]))

(def names
  {#uuid "bf47c816-2833-320e-9fbd-6ae109275ab0" "Code-building(PARENT-correct)"
   #uuid "9880798a-8487-3a24-93e4-b59c5ae5d789" "child/rename-move-symbol(FORCE-FIT)"
   #uuid "b638e3fa-50c0-3fb8-b306-11d067550afe" "child/code-edit-dependency-wiring"
   #uuid "ed8fac34-ba1d-3855-9fc7-7f9d0205190a" "child/performance-optimization"
   #uuid "c841adb5-394a-3f45-b904-49e9f2822b6b" "child/documentation-writing"
   #uuid "225be622-6cc2-3359-8c18-024bdf08548d" "child/bug-diagnosis"})

(defn nm [id] (get names id (str "other/" (when id (subs (str id) 0 8)))))

(defn build-sig [instruction]
  (tc/build-task-signature {:instruction instruction
                            :reads [:user-message :active-plan :workspace-root]
                            :writes [:assistant-response]
                            :mcp-tools ["shell/exec" "fs/read" "fs/list"]}))

(def refactor-instruction
  "Refactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.")

(defn -main [& args]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (runner/start!)
  (let [;; optional CLI args: <scorer-keyword> <N>. Default :colbert (the EL-5
        ;; default backend), N=10. Pass "embedding 10" to prove :embedding is
        ;; live-selectable through the SAME apply-rerank path.
        scorer (some-> (first args) keyword)
        n-runs (or (some-> (second args) Integer/parseInt) 10)
        base-ctx (deref (var-get (requiring-resolve 'runner/system-state)))
        ctx (if scorer
              (assoc base-ctx :domain-penalty-config {:scorer scorer})
              base-ctx)
        sig (build-sig refactor-instruction)
        runs (atom [])]
    (println (format "=== EL-5 refactor force-fit RATE (N=%d, real reranker + %s penalty) ==="
                     n-runs (or scorer :colbert-default)))
    (println "domain-penalty-config:" (:domain-penalty-config ctx :defaults))
    (dotimes [n n-runs]
      (let [r (ont/classify-behaviors ctx {:task-signature sig :threshold 0.6 :top-n 5})
            top (first (:behaviors r))
            id (:behavior-id top)]
        (swap! runs conj id)
        (println (format "run %2d: top=%-40s conf=%.3f outcome=%s fallback=%s"
                         n (nm id) (double (or (:confidence top) 0.0))
                         (:outcome r) (:rerank-fallback? r)))))
    (let [freq (frequencies (map nm @runs))]
      (println "\n=== TALLY ===")
      (doseq [[k v] (sort-by (comp - val) freq)] (println (format "  %-44s %d/%d" k v n-runs)))
      (println (format "\nVERDICT INPUT: force-fit (rename-child #1) = %d/%d ; correct (Code-building #1) = %d/%d"
                       (get freq "child/rename-move-symbol(FORCE-FIT)" 0) n-runs
                       (get freq "Code-building(PARENT-correct)" 0) n-runs)))
    (runner/stop!)
    (System/exit 0)))
