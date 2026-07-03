(ns mt7-frag-probe
  "MT-7 VERIFY-FIRST — root-cause the comprehensive-build FRAGMENTATION: occupations
   land under DIFFERENT URI schemes (occupation/X vs job-zones/occupation/X) so MT-4
   merge can't collapse them → 3971 nodes instead of ~1016, coverage diluted.

   Runs the REAL survey → model → extract (select) on O*NET with the AUTO-authored
   model-spec (NOT a hand spec — the hand spec merged fine, so the auto spec is the
   suspect), and dumps: (a) the model-spec's entity-types, (b) the DISTINCT URI schemes
   of the extracted drafts + how many drafts each, (c) per-scheme the :entity-type the
   aggregation AUTHOR chose. This pins WHERE the inconsistent entity-type comes from.

   USAGE: clj -M:dev:test -m mt7-frag-probe"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def onet {:type :excel :path h/onet-dir})

(defn- scheme [uri] (let [u (str uri) i (str/last-index-of u "/")]
                      (if i (subs u 0 i) u)))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-7 FRAG PROBE — real survey → model → extract (cap 6), AUTO model-spec ===")
      (let [{:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? false})
            sv (ce/delegate-survey! ctx {:source onet :goal "Build an ontology of occupations and the skills, knowledge, and job requirements they have." :model h/default-model})
            _ (println "survey status:" (:status sv))
            mx (ce/delegate-model-extract!
                ctx {:source onet :goal "Build an ontology of occupations and the skills, knowledge, and job requirements they have."
                     :profile (:profile sv) :pipeline-sheet-id pipeline-sheet-id
                     :model h/default-model :max-containers 12})
            _ (println "model-extract status:" (:status mx))
            ms (:model-spec mx)
            drafts (:concept-drafts mx)]
        (println "\n=== (a) AUTO-authored model-spec :entity-types ===")
        (doseq [et (:entity-types ms)]
          (println "   type=" (pr-str (:type et)) " keying=" (pr-str (:uri-keying-fields et))))
        (println "\n=== (b) draft URI schemes (distinct prefix before last /) ===")
        (let [by-scheme (frequencies (map (comp scheme :uri) drafts))]
          (doseq [[s n] (reverse (sort-by val by-scheme))]
            (println (format "   %-6d %s" n s))))
        (println "\n=== (c) sample drafts per scheme (uri + entity-type + attr-keys) ===")
        (doseq [[s ds] (take 8 (group-by (comp scheme :uri) drafts))]
          (let [d (first ds)]
            (println "   scheme" (pr-str s) "→" (:uri d) " entity-type=" (pr-str (:entity-type d))
                     " attrs=" (vec (keys (:attributes d))))))
        (println "\n=== VERDICT ===")
        (let [schemes (distinct (map (comp scheme :uri) drafts))
              occ-schemes (filter #(re-find #"(?i)occupation|soc" %) schemes)]
          (println "   distinct URI schemes:" (count schemes))
          (println "   occupation-like schemes:" (vec occ-schemes))
          (println "   =>" (if (> (count occ-schemes) 1)
                             "FRAGMENTED — the same occupation is minted under multiple schemes (the bug)"
                             "SINGLE occupation scheme (no fragmentation here)")))
        (println "\n=== DONE ==="))
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
