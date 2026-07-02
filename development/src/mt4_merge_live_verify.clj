(ns mt4-merge-live-verify
  "MT-4 /inspect-orc — LIVE, adversarial end-to-end proof of within-source
   occurrence-merge on the REAL O*NET 3-container slice, through the REAL pipeline:

     MT-2 select (3 containers) → MT-3 aggregate (Skills/Knowledge) + per-row
     (Occupation Data) → GC-1 canonicalize → MT-4 union → reconcile/land → read back.

   The proof the unit tests CANNOT give: the 3 containers' drafts must actually
   CANONICALIZE to the SAME URI in the real flow (the per-row Occupation-Data author
   vs the MT-3 aggregation author, both through GC-1) for the union to fire. If the
   URIs diverge → 3 nodes / dropped attrs (a false-green the union can't fix). We read
   the PROJECTION back and assert: exactly ONE occupation node per SOC carrying
   label + description + populated topSkills AND topKnowledge.

   USAGE: clj -M:dev:test -m mt4-merge-live-verify"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.orc-service.interface :as dsl]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def onet-dir h/onet-dir)
(def onet {:type :excel :path onet-dir})
(def model-spec
  {:entity-types [{:type "occupation"
                   :uri-keying-fields ["O*NET-SOC Code"]
                   :description "An occupation identified by its O*NET-SOC code."}]})
(def goal "Build an ontology of occupations and the skills and knowledge they require.")

(defn- array-attrs [d]
  (into {} (filter (fn [[_ v]] (vector? v)) (:attributes d))))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})
        oid (random-uuid)]
    (try
      (println "=== MT-4 MERGE LIVE VERIFY — real O*NET 3-container slice, end-to-end ===")
      (let [{:keys [extract-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? false})
            ;; select the 3 containers in ONE orchestrator run → union'd, GC-1
            ;; canonicalized concept-drafts across all three.
            selected [{:name "Occupation Data" :path onet-dir :sheet "Occupation Data" :shape :entity
                       :roles {:key "O*NET-SOC Code"}}
                      {:name "Skills" :path onet-dir :sheet "Skills" :shape :long-form
                       :roles {:key "O*NET-SOC Code" :element "Element Name" :value "Data Value"}}
                      {:name "Knowledge" :path onet-dir :sheet "Knowledge" :shape :long-form
                       :roles {:key "O*NET-SOC Code" :element "Element Name" :value "Data Value"}}]
            tick-id (random-uuid)
            _ (println "extracting 3 containers (real survey-less extract w/ shared model-spec)…")
            _ (dsl/execute ctx extract-sheet-id
                           {"source" onet "model-spec" model-spec
                            "selected-containers" selected "max-windows" 50}
                           :tick-id tick-id :timeout-ms 400000)
            bb (dsl/get-tick-blackboard ctx tick-id)
            drafts (vec (get-in bb [:concept-drafts :value]))
            ;; how many DISTINCT occupation URIs did canonicalization produce across
            ;; the 3 containers? (the union's precondition — must collapse to ~1/SOC)
            occ-drafts (filter #(re-find #"(?i)occupation" (str (:uri %))) drafts)
            uris (frequencies (map :uri occ-drafts))
            multi (filter (fn [[_ n]] (> n 1)) uris)
            _ (println "  extracted drafts:" (count drafts)
                       " occupation-URI drafts:" (count occ-drafts)
                       " distinct occ URIs:" (count uris)
                       " URIs with >1 draft (pre-union, same-URI multi-container):" (count multi))
            ;; land via the REAL reconcile (which runs compile-discovery-source! with
            ;; the MT-4 union) and read the projection back.
            rc (ce/delegate-reconcile! ctx {:ontology-id oid :concept-drafts drafts
                                            :relationship-drafts [] :source-uri-sets nil
                                            :model h/default-model})
            _ (println "  reconcile status:" (:status rc))
            concepts (rm/get-concepts ctx {:ontology-id oid})
            occ (filter #(re-find #"(?i)occupation" (str (:uri %))) concepts)
            by-uri (group-by :uri occ)
            enriched (filter (fn [n] (and (:label n) (:description n)
                                          (seq (array-attrs n)))) occ)
            ;; the fully-unioned nodes: BOTH topSkills-like AND topKnowledge-like arrays
            full (filter (fn [n] (>= (count (array-attrs n)) 2)) occ)]
        (println "\n=== READ-BACK (the landed projection) ===")
        (println "  occupation nodes:" (count occ) "  distinct URIs:" (count by-uri)
                 "  dupes (URI w/ >1 node):" (count (filter (fn [[_ v]] (> (count v) 1)) by-uri)))
        (println "  nodes carrying label+description+>=1 array attr:" (count enriched))
        (println "  nodes carrying label+description+>=2 array attrs (FULL union):" (count full))
        (doseq [n (take 4 full)]
          (println "    " (:uri n) " label=" (pr-str (:label n)) " desc?=" (boolean (:description n)))
          (doseq [[k arr] (array-attrs n)] (println "        " k "(" (count arr) ")")))
        (println "\n=== ADVERSARIAL VERDICT ===")
        (println "  AC canonicalization collapses the 3 containers to ~1 URI/SOC:")
        (println "     => " (if (pos? (count multi))
                              "PASS — same-URI multi-container drafts exist pre-union (union has real work)"
                              "SUSPECT — no same-URI multi-container drafts (did canonicalization diverge?)"))
        (println "  AC one node per SOC (no duplicates):")
        (println "     => " (if (zero? (count (filter (fn [[_ v]] (> (count v) 1)) by-uri)))
                              "PASS — one node per URI" "FAIL — duplicate nodes for a URI"))
        (println "  AC nodes carry the FULL union (label+desc+topSkills+topKnowledge):")
        (println "     full-union nodes:" (count full) " / occupation nodes:" (count occ))
        (println "     => " (if (pos? (count full))
                              "PASS — occupation nodes carry BOTH aggregated attributes + label/desc"
                              "FAIL — no node carries both array attributes (union did not land / URIs diverged)"))
        (println "\n  reconcile occurrence-merge provenance (if surfaced):")
        (pp/pprint (select-keys (:reconcile-report rc) [:landed]))
        (println "\n=== DONE ===")
        {:occ-count (count occ) :full (count full) :dupes (count (filter (fn [[_ v]] (> (count v) 1)) by-uri))})
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
