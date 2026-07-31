(ns rr3-richness-shape-probe
  "RR-3 (ADR 0020, decision 4) acceptance probe — the
   PROTOTYPE_candidate_truncation_risk replay, re-run against the IMPLEMENTED
   structural richness cap, on the REAL seeded corpus with REAL ColBERT
   pre-rerank scores.

   Three things it measures, all against the same real corpus:

   1. THE PROTOTYPE REPLAY. `search-descriptions` WITHOUT :rerank-with-intent
      = the pure pre-rerank ColBERT ranking (no LLM, no EL-5 domain penalty)
      — exactly the signal the FALSIFIED score-based design would have cut on.
      Prints the full ranking so the force-fit's rank and Code-building's rank
      are visible, not assumed.

   2. WHO ACTUALLY GETS FULL RICHNESS under the implemented structural cap.
      The reranker is stubbed here ONLY to capture the candidate payload it is
      handed — this step is about prompt SHAPE, which is deterministic. The
      live LLM check is `el2_inspect_rate.clj` (the EL-5 refactor force-fit
      rate), run separately.

   3. THE ACTUAL PROMPT-SIZE REDUCTION, measured by running the same real
      candidate set with the cap disabled (shape-candidate-richness redefined
      to identity) and diffing the serialized payload.

   Run (needs OPENROUTER_API_KEY — runner/start! registers the provider even
   though steps 1-3 make no LLM call):
     clj -M:dev -m rr3-richness-shape-probe

   MEASURED (RR-3 slice, seeded baseline corpus = 12 abstract parents +
   5 durable coding children):
     1. pre-rerank ranking reproduces the /prototype exactly —
        child/rename-move-symbol (force-fit) #1, Code-building (correct) #9
        of 16 behavioral candidates.
     2. under the structural cap at the EL-5 retrieval breadth (k=10 -> 10
        behavioral candidates, 4 of them children): Code-building FULL,
        force-fit present, 1 of 10 terse (the 4th-ranked child).
     3. candidate-payload JSON 35153 -> 32730 chars = 6.9% smaller at the
        SAME candidate count. The ratio is small today only because the
        corpus has 5 children; it grows with the child side, which is the
        only side harvest grows."
  (:require [runner]
            [clojure.data.json :as json]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.task-classifier :as tc]))

(def names
  {#uuid "bf47c816-2833-320e-9fbd-6ae109275ab0" "Code-building (PARENT — CORRECT ANSWER)"
   #uuid "9880798a-8487-3a24-93e4-b59c5ae5d789" "child/rename-move-symbol (FORCE-FIT)"
   #uuid "b638e3fa-50c0-3fb8-b306-11d067550afe" "child/code-edit-dependency-wiring"
   #uuid "ed8fac34-ba1d-3855-9fc7-7f9d0205190a" "child/performance-optimization"
   #uuid "c841adb5-394a-3f45-b904-49e9f2822b6b" "child/documentation-writing"
   #uuid "225be622-6cc2-3359-8c18-024bdf08548d" "child/bug-diagnosis"})

(def code-building-id #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0")
(def rename-move-symbol-id #uuid "9880798a-8487-3a24-93e4-b59c5ae5d789")

;; Verbatim from development/bench/el2_inspect_rate.clj — the SAME established
;; EL-5 refactor-force-fit regression case, not a new one.
(def refactor-instruction
  "Refactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.")

(defn- ->uuid [s] (when s (try (java.util.UUID/fromString (str s)) (catch Throwable _ nil))))
(defn- target-id [c] (->uuid (-> c :document-metadata :target-id)))
(defn- nm [id] (get names id (str "other/" (when id (subs (str id) 0 8)))))

(defn- full-richness? [c]
  (boolean (and (seq (:avoid-when c)) (seq (:strengths c)) (seq (:weaknesses c)))))

;; classify-behaviors' :k is (* 2 :top-n). el2_inspect_rate — the established
;; EL-5 refactor rate probe — runs with :top-n 5, so the candidate set the EL-5
;; case is actually reranked over is k = 10. Mirror that exactly.
(def el5-k 10)

(def ^:private marker-key :ai.obney.orc.ontology.interface/parent-behavior)

(defn- capture-payload!
  "Run the rerank path against the real corpus and return the candidate vector
   the reranker was handed. `shape?` false disables the RR-3 cap so the same
   real candidates can be measured un-capped. The un-capped baseline still
   strips RR-3's internal structural marker, so the size diff below measures
   the RICHNESS cap only, not the marker."
  [ctx sig shape?]
  (let [capture (atom nil)
        stub (fn [_ {:keys [candidates]}]
               (reset! capture candidates)
               (mapv (fn [c] {:document-id (:document-id c)
                              :reasoning "probe-stub"
                              :fitness-score 0.5})
                     candidates))
        shaper (if shape?
                 @(requiring-resolve 'ai.obney.orc.ontology.interface/shape-candidate-richness)
                 (fn [cs] (mapv #(dissoc % marker-key) cs)))]
    (with-redefs-fn {(requiring-resolve 'ai.obney.orc.ontology.interface/shape-candidate-richness) shaper
                     #'reranker/rerank! stub}
      (fn []
        (ont/search-descriptions ctx {:query sig
                                      :granularity :behavioral-subtree
                                      :rerank-with-intent tc/behavioral-classifier-intent
                                      :k el5-k})))
    @capture))

(defn -main [& _]
  (runner/start!)
  (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))
        sig (tc/build-task-signature {:instruction refactor-instruction
                                      :reads [:user-message :active-plan :workspace-root]
                                      :writes [:assistant-response]
                                      :mcp-tools ["shell/exec" "fs/read" "fs/list"]})]
    (println "\n=========================================================")
    (println " RR-3 probe (1/3) — PROTOTYPE replay: pure pre-rerank rank")
    (println "=========================================================")
    (let [raw (ont/search-descriptions ctx {:query sig
                                            :granularity :behavioral-subtree
                                            :k 20})
          ranked (sort-by :score > raw)]
      (println "total candidates:" (count ranked))
      (doseq [[i c] (map-indexed vector ranked)]
        (println (format "  #%2d  score=%.4f  %s" (inc i) (double (:score c)) (nm (target-id c)))))
      (let [rank-of (fn [id] (first (keep-indexed (fn [i c] (when (= (target-id c) id) (inc i))) ranked)))]
        (println "  Code-building (correct) pre-rerank rank:" (rank-of code-building-id))
        (println "  rename-move-symbol (force-fit) pre-rerank rank:" (rank-of rename-move-symbol-id))))

    (println "\n=========================================================")
    (println " RR-3 probe (2/3) — who gets FULL richness under the cap")
    (println "=========================================================")
    (let [capped (capture-payload! ctx sig true)
          by-id (into {} (map (juxt #(target-id %) identity)) capped)]
      (println "candidates handed to the reranker:" (count capped))
      (doseq [[i c] (map-indexed vector capped)]
        (println (format "  #%2d  score=%.4f  %-42s  %s"
                         (inc i) (double (or (:score c) 0.0)) (nm (target-id c))
                         (if (full-richness? c) "FULL" "terse"))))
      (println "\n  VERDICT: Code-building present in candidate set? "
               (some? (get by-id code-building-id)))
      (println "  VERDICT: Code-building full richness? "
               (full-richness? (get by-id code-building-id)))
      (println "  force-fit child present?               "
               (some? (get by-id rename-move-symbol-id)))
      (println "  terse count:" (count (remove full-richness? capped))
               "of" (count capped))

      (println "\n=========================================================")
      (println " RR-3 probe (3/3) — prompt-size reduction (real corpus)")
      (println "=========================================================")
      (let [uncapped (capture-payload! ctx sig false)
            size (fn [v] (count (json/write-str v)))
            before (size uncapped)
            after (size capped)]
        (println "  candidate payload JSON chars, cap OFF:" before)
        (println "  candidate payload JSON chars, cap ON :" after)
        (println (format "  reduction: %d chars (%.1f%%)"
                         (- before after)
                         (* 100.0 (/ (double (- before after)) (max 1 before)))))
        (println "  candidate COUNT unchanged?" (= (count uncapped) (count capped)))))
    (runner/stop!)
    (shutdown-agents)
    (System/exit 0)))
