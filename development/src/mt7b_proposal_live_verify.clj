(ns mt7b-proposal-live-verify
  "MT-7b /inspect-orc — LIVE proof of the vocabulary-proposal path on real O*NET,
   through the REAL extract flow (real Grain child ticks, real LLM AUTHOR, resilient).

   Scenario (the unsampled-container case the ADR admits): the model-spec's vocabulary
   DELIBERATELY declares only one type — Occupation [O*NET-SOC Code] — and we extract:
     - 'Content Model Reference' (Element ID + Element Name + Description — clearly
       NOT occupations): the author must PROPOSE a new type (per the new guidance),
       NOT freelance and NOT fail; its drafts land under the proposed type with
       GC-1-canonical URIs.
     - 'Occupation Data' (control): binds normally to Occupation, no proposal needed.

   Asserts: the aggregate :vocabulary-proposals ledger is surfaced (proposed/admitted);
   proposed-type drafts LANDED (not excluded); zero freelanced landed drafts overall
   (every draft's type ∈ vocabulary ∪ admitted); the control container unaffected.

   USAGE: clj -M:dev:test -m mt7b-proposal-live-verify"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]
            [ai.obney.orc.orc-service.interface :as dsl]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def onet-dir h/onet-dir)
(def onet {:type :excel :path onet-dir})
(def narrow-model-spec
  {:entity-types [{:type "Occupation" :uri-keying-fields ["O*NET-SOC Code"]
                   :description "An occupation identified by its O*NET-SOC code."}]})

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-7b PROPOSAL LIVE VERIFY — narrow vocab (Occupation only), real O*NET ===")
      (let [{:keys [extract-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? true})
            selected [{:name "Content Model Reference" :path onet-dir :sheet "Content Model Reference" :shape :entity}
                      {:name "Occupation Data" :path onet-dir :sheet "Occupation Data" :shape :entity}]
            tick-id (random-uuid)
            _ (dsl/execute ctx extract-sheet-id
                           {"source" onet "model-spec" narrow-model-spec
                            "selected-containers" selected "max-windows" 3}
                           :tick-id tick-id :timeout-ms 500000)
            bb (dsl/get-tick-blackboard ctx tick-id)
            drafts (vec (get-in bb [:concept-drafts :value]))
            report (get-in bb [:extraction-report :value])
            ledger (:vocabulary-proposals report)
            per-container (:per-container report)
            by-type (frequencies (map :entity-type drafts))
            ;; the FULL effective vocabulary = declared + admitted
            admitted (vec (:admitted ledger))
            effective-vocab (into (vb/canonical-types narrow-model-spec) admitted)
            unresolved (remove #(vb/resolve-entity-type effective-vocab (:entity-type %)) drafts)]
        (println "\nper-container:")
        (doseq [pc per-container]
          (println (format "   %-28s status=%-9s drafts=%-5s proposal=%s"
                           (:container pc) (str (:status pc)) (str (:concept-count pc))
                           (pr-str (select-keys (or (:entity-type-proposal pc) {})
                                                [:outcome :proposed :reason])))))
        (println "\nledger (:vocabulary-proposals):") (pp/pprint ledger)
        (println "\nlanded drafts:" (count drafts) " by entity-type:" by-type)
        (println "\n=== ADVERSARIAL VERDICT ===")
        (println "  AC1 the mismatched container landed via a PROPOSAL (not freelance/fail):")
        (let [proposed-types (set (map :type admitted))
              proposed-drafts (filter #(contains? proposed-types (:entity-type %)) drafts)]
          (println "     admitted types:" (mapv :type admitted)
                   " drafts under them:" (count proposed-drafts))
          (println "     => " (cond
                                (and (seq admitted) (pos? (count proposed-drafts))) "PASS"
                                (seq admitted) "SUSPECT — admitted but 0 drafts landed under it"
                                :else "FAIL — no proposal admitted (freelanced? failed? inspect per-container)")))
        (println "  AC2 zero freelanced landed drafts (types ⊆ declared ∪ admitted):")
        (println "     unresolved:" (count unresolved) "of" (count drafts)
                 "=>" (if (zero? (count unresolved)) "PASS"
                          (str "FAIL — " (pr-str (distinct (map :entity-type unresolved))))))
        (println "  AC3 the control (Occupation Data) bound normally:")
        (let [occ (get by-type "Occupation" 0)]
          (println "     Occupation drafts:" occ "=>" (if (pos? occ) "PASS" "FAIL")))
        (println "\n=== DONE ==="))
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
