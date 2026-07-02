(ns mt6-listform-live-verify
  "MT-6 /inspect-orc — LIVE, adversarial proof of list-valued aggregation through the
   REAL extract flow (real Grain child tick, real LLM AUTHOR, real O*NET). Proves the
   thing the unit tests cannot:

     1. Alternate Titles (occupation → its other job titles; MT-1 tags it :entity,
        24,999 rows) → the AUTHOR emits a LIST-collect spec, the sample-driven gate
        FIRES (SOC repeats), and it lands ~900 OCCUPATION drafts each carrying a FLAT
        LIST attribute — NOT 24,999 per-row nodes. (The OOM + acceptance fix.)
     2. Occupation Data (unique SOC, 1 row/occ) → the gate DECLINES → per-row (1016
        drafts). A near-unique key is never wrongly aggregated.
     3. Skills (:long-form, numeric value) → top-N (894) — unregressed.

   USAGE: clj -M:dev:test -m mt6-listform-live-verify"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.orc-service.interface :as dsl]
            [clojure.pprint :as pp]))

(def onet-dir h/onet-dir)
(def onet {:type :excel :path onet-dir})
(def model-spec
  {:entity-types [{:type "occupation" :uri-keying-fields ["O*NET-SOC Code"]
                   :description "An occupation identified by its O*NET-SOC code."}]})

(defn- run-container [ctx sid nm shape roles max-windows]
  (let [tick-id (random-uuid)
        selected [(merge {:name nm :path onet-dir :sheet nm :shape shape} (when roles {:roles roles}))]]
    (dsl/execute ctx sid {"source" onet "model-spec" model-spec
                          "selected-containers" selected "max-windows" max-windows}
                 :tick-id tick-id :timeout-ms 400000)
    (let [bb (dsl/get-tick-blackboard ctx tick-id)
          drafts (vec (get-in bb [:concept-drafts :value]))
          report (get-in bb [:extraction-report :value])]
      {:name nm :shape shape :drafts drafts :report report})))

(defn- list-attrs [d]
  (into {} (filter (fn [[_ v]] (and (vector? v) (seq v))) (:attributes d))))

(defn- summarize [{:keys [name shape drafts report]}]
  (let [with-list (filter #(seq (list-attrs %)) drafts)]
    (println "\n#####" name " (tag" shape ")  drafts:" (count drafts)
             " with-list-attr:" (count with-list)
             " rows-streamed:" (:rows-streamed report))
    (doseq [d (take 3 with-list)]
      (let [[k arr] (first (list-attrs d))]
        (println "    " (:uri d) " " k "(" (count arr) ")=" (vec (take 6 arr)))))
    {:name name :n (count drafts) :n-list (count with-list)}))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-6 LIST-FORM LIVE VERIFY — real extract, real LLM AUTHOR, real O*NET ===")
      (let [{:keys [extract-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? false})
            ;; Alternate Titles: MT-1 tags it :entity (near-unique title col); the
            ;; sample-driven gate must still aggregate it (SOC repeats). Pass its REAL tag.
            alt (run-container ctx extract-sheet-id "Alternate Titles" :entity
                               {:key "O*NET-SOC Code" :element "Alternate Title"} 100000)
            ;; Occupation Data: unique SOC → must STAY per-row.
            occ (run-container ctx extract-sheet-id "Occupation Data" :entity
                               {:key "O*NET-SOC Code"} 50)
            ;; Skills: long-form top-N — unregressed.
            skl (run-container ctx extract-sheet-id "Skills" :long-form
                               {:key "O*NET-SOC Code" :element "Element Name" :value "Data Value"} 100000)
            a (summarize alt) o (summarize occ) s (summarize skl)]
        (println "\n=== ADVERSARIAL VERDICT ===")
        (println "  AC1 Alternate Titles → LIST rollup (NOT ~25k per-row nodes):")
        (println "     drafts:" (:n a) " with-list-attr:" (:n-list a))
        (println "     => " (cond
                              (> (:n a) 5000) "FAIL — thousands of drafts = per-ROW (gate declined / no list spec)"
                              (pos? (:n-list a)) "PASS — bounded per-occupation drafts carrying a flat LIST attribute"
                              (zero? (:n a)) "FAIL — 0 drafts"
                              :else "SUSPECT — bounded drafts but none carry a list attribute"))
        (println "  AC2 Occupation Data → per-row UNREGRESSED (gate declines a unique key):")
        (println "     drafts:" (:n o) " with-list-attr:" (:n-list o))
        (println "     => " (if (and (> (:n o) 500) (zero? (:n-list o)))
                              "PASS — per-row (1 draft/occupation), no list rollup" "SUSPECT — inspect"))
        (println "  AC3 Skills → top-N UNREGRESSED:")
        (println "     drafts:" (:n s) " with-list-attr:" (:n-list s))
        (println "     => " (if (and (< (:n s) 5000) (pos? (:n-list s))) "PASS" "SUSPECT — inspect"))
        (println "\n=== DONE ===")
        {:alt a :occ o :skl s})
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
