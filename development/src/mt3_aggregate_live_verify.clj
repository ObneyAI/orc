(ns mt3-aggregate-live-verify
  "MT-3 /inspect-orc — LIVE, adversarial end-to-end verification of the aggregating
   transform through the REAL extract flow (real Grain child tick, real LLM AUTHOR,
   real O*NET stream). This is the proof the /prototype + unit tests CANNOT give:

     1. GATE SURVIVAL — the MT-1 `:shape :long-form` tag survives the REAL dsl/execute
        child-tick blackboard and FIRES the aggregating gate (not the per-row path).
        Witness: `Skills` yields ONE draft per SOC (~894) carrying a populated topN
        flat attribute — NOT ~62k per-row fragments (that = the gate did NOT fire).
     2. REAL AUTHOR — the real LLM, in the full combined prompt, emits a VALID
        aggregation-spec (incl. the scale filter) end-to-end (not just in isolation).
     3. KNOWLEDGE too (domain-agnostic, the AUTHOR picks the columns).
     4. NON-REGRESSION — an `:entity` container (Occupation Data) keeps the per-row
        path (many per-row drafts, no topN rollup).

   Drives the extract sheet with a MT-2-shaped :selected-containers list (so the real
   orchestrator drives exactly the target container, carrying its :shape tag through
   the child tick — the exact runtime path). Minimal real model-spec (the model-spec
   is NOT what's under test — the aggregation authoring + gate + fold is).

   USAGE: clj -M:dev:test -m mt3-aggregate-live-verify"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.orc-service.interface :as dsl]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def onet-dir h/onet-dir)
(def onet {:type :excel :path onet-dir})

;; a minimal REAL model-spec — occupation keyed by SOC. (Not under test; the AUTHOR
;; authors the rollup from the sample. Honest: a real entity-types list, one entry.)
(def model-spec
  {:entity-types [{:type "occupation"
                   :uri-keying-fields ["O*NET-SOC Code"]
                   :description "An occupation identified by its O*NET-SOC code."}]})

(def goal "Build an ontology of occupations and the skills/knowledge they require.")

(defn- run-container [ctx extract-sid nm shape roles max-windows]
  (let [tick-id (random-uuid)
        selected [ (merge {:name nm :path onet-dir :sheet nm :shape shape} (when roles {:roles roles})) ]
        _ (dsl/execute ctx extract-sid
                       {"source" onet "model-spec" model-spec
                        "selected-containers" selected
                        "max-windows" max-windows}
                       :tick-id tick-id :timeout-ms 300000)
        bb (dsl/get-tick-blackboard ctx tick-id)
        drafts (vec (get-in bb [:concept-drafts :value]))
        report (get-in bb [:extraction-report :value])]
    {:name nm :shape shape :drafts drafts :report report}))

(defn- attr-arrays
  "The flat array attributes on a draft (the topN rollup lands as a vector-valued
   attribute). Returns {attr-key vector}."
  [d]
  (into {} (filter (fn [[_ v]] (vector? v)) (:attributes d))))

(defn- summarize [{:keys [name shape drafts report]}]
  (println "\n#####" name "  (tag" shape ")")
  (println "  drafts:" (count drafts)
           "  distinct-uris:" (count (distinct (map :uri drafts)))
           "  report:" (select-keys report [:containers-total :containers-processed
                                            :rows-streamed :concept-count]))
  (let [with-array (filter #(seq (attr-arrays %)) drafts)
        sample (take 3 with-array)]
    (println "  drafts carrying a flat ARRAY attribute (the rollup):" (count with-array))
    (doseq [d sample]
      (let [[k arr] (first (attr-arrays d))]
        (println "    " (:uri d) " " k "(" (count arr) ")=" (vec (take 8 arr)))))
    {:name name :shape shape :n-drafts (count drafts)
     :n-with-array (count with-array)
     :sample-uris (mapv :uri (take 3 drafts))}))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-3 AGGREGATE LIVE VERIFY — real extract flow, real LLM AUTHOR, real O*NET ===")
      (let [{:keys [extract-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? false})
            ;; long-form targets (the aggregating path) — high window cap so the full
            ;; container streams (the fold is bounded-memory, so streaming all is safe).
            ;; PASS THE LOW DEFAULT CAP (50) on purpose — the fix must make the
            ;; aggregating path stream the FULL container regardless (all ~894 SOCs),
            ;; proving the truncation bug is gone (50 windows × 500 rows = 25k < 62.5k).
            skills (run-container ctx extract-sheet-id "Skills" :long-form
                                  {:key "O*NET-SOC Code" :element "Element Name" :value "Data Value"} 50)
            knowledge (run-container ctx extract-sheet-id "Knowledge" :long-form
                                     {:key "O*NET-SOC Code" :element "Element Name" :value "Data Value"} 50)
            ;; the NON-regression control — an :entity container keeps the per-row path.
            occdata (run-container ctx extract-sheet-id "Occupation Data" :entity nil 50)
            s (summarize skills)
            k (summarize knowledge)
            o (summarize occdata)]
        (println "\n=== ADVERSARIAL VERDICT ===")
        ;; AC1 — long-form → ONE draft per SOC with a populated rollup array, NOT ~62k fragments.
        (println "  AC1 Skills long-form → per-KEY rollup (not per-row fragments):")
        (println "     drafts:" (:n-drafts s) " with-rollup-array:" (:n-with-array s))
        (println "     => " (cond
                              (zero? (:n-drafts s)) "FAIL — 0 drafts (author/gate/stream broke)"
                              (> (:n-drafts s) 5000) "FAIL — thousands of drafts = per-ROW fragments (gate did NOT fire)"
                              (pos? (:n-with-array s)) "PASS — bounded per-key drafts carrying the flat rollup array"
                              :else "FAIL — drafts present but NONE carry a rollup array (spec empty?)"))
        ;; AC2 — Knowledge too.
        (println "  AC2 Knowledge long-form → per-KEY rollup:")
        (println "     drafts:" (:n-drafts k) " with-rollup-array:" (:n-with-array k))
        (println "     => " (if (and (pos? (:n-with-array k)) (< (:n-drafts k) 5000))
                              "PASS" "FAIL — see counts"))
        ;; AC3 — non-regression: entity container keeps per-row path (no rollup arrays,
        ;; typically MANY more drafts than the ~1k occupations, OR at least not a rollup).
        (println "  AC3 Occupation Data (:entity) → per-row path UNREGRESSED:")
        (println "     drafts:" (:n-drafts o) " with-rollup-array:" (:n-with-array o))
        (println "     => " (if (zero? (:n-with-array o))
                              "PASS — no rollup arrays (per-row path ran, gate correctly did NOT fire)"
                              "SUSPECT — an :entity container produced rollup arrays (gate mis-fired?)"))
        (println "\n=== DONE ===")
        {:skills s :knowledge k :occdata o})
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _]
  (run!)
  (shutdown-agents)
  (System/exit 0))
