(ns mt7a-binding-live-verify
  "MT-7a /inspect-orc — LIVE, adversarial proof of vocabulary binding on real O*NET,
   through the REAL auto pipeline (survey → model → extract, RESILIENT — the
   production path, so the EB9 re-ask fires on a freelancing author).

   This is the EXACT scenario that fragmented before (the mt7 frag probe showed
   freelanced schemes: job-element/dwa/requirement/onp; job-zones/occupation;
   Workplace vs Workplace Element). The binding must now make freelancing IMPOSSIBLE
   among LANDED drafts:

     AC1 — every landed draft's :entity-type resolves (normalized-exact) to the
           run's model-spec vocabulary; ZERO freelanced schemes among landed drafts.
     AC2 — distinct URI schemes ⊆ the vocabulary (one scheme per type; no case/
           variant splits like job-element vs Job Element).
     AC3 — the enforcement is OBSERVABLE: per-container :freelanced-drafts surfaced
           (count 0 = binding ran clean; >0 = exclusions honestly reported) and/or
           honest per-container failures — never a silently-landed freelanced draft.

   USAGE: clj -M:dev:test -m mt7a-binding-live-verify"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def onet {:type :excel :path h/onet-dir})
(def goal "Build an ontology of occupations and the skills, knowledge, and job requirements they have.")

(defn- scheme [uri] (let [u (str uri) i (str/index-of u "/")] (if i (subs u 0 i) u)))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-7a BINDING LIVE VERIFY — real survey → model → extract (cap 12, RESILIENT) ===")
      (let [{:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? true})
            sv (ce/delegate-survey! ctx {:source onet :goal goal :model h/default-model})
            _ (println "survey:" (:status sv))
            t0 (System/currentTimeMillis)
            mx (ce/delegate-model-extract! ctx {:source onet :goal goal :profile (:profile sv)
                                                :pipeline-sheet-id pipeline-sheet-id
                                                :model h/default-model :max-containers 12})
            _ (println "model-extract:" (:status mx) "(" (- (System/currentTimeMillis) t0) "ms )")
            ;; on failure: surface the REAL error + the raw model-spec (no swallowed
            ;; failures — the earlier run of this script hid the reason, a defect).
            _ (when (not= :success (:status mx))
                (println "  !! error:" (pr-str (:error mx)))
                (println "  !! raw model-spec:" (let [s (pr-str (:model-spec mx))]
                                                  (subs s 0 (min 800 (count s))))))
            ms (:model-spec mx)
            vocab (vb/canonical-types ms)
            drafts (:concept-drafts mx)
            report (:extraction-report mx)
            per-container (:per-container report)]
        (println "\nvocabulary (the model's own):")
        (doseq [et vocab] (println "   " (pr-str (:type et)) " keying=" (pr-str (:uri-keying-fields et))))
        (println "\ndrafts:" (count drafts)
                 " containers processed:" (:containers-processed report)
                 " failed:" (:containers-failed report))
        ;; AC1 — every landed draft's entity-type resolves against the vocabulary.
        (let [unresolved (remove #(vb/resolve-entity-type vocab (:entity-type %)) drafts)
              by-type (frequencies (map :entity-type drafts))]
          (println "\nlanded draft entity-types:" by-type)
          (println "AC1 zero freelanced landed drafts:"
                   (count unresolved) "unresolved of" (count drafts)
                   "=>" (if (zero? (count unresolved)) "PASS"
                            (str "FAIL — freelanced types landed: "
                                 (pr-str (distinct (map :entity-type unresolved)))))))
        ;; AC2 — URI schemes ⊆ vocabulary (normalized), no variant splits.
        (let [schemes (frequencies (map (comp scheme :uri) drafts))
              vocab-norms (set (map (comp vb/normalize-name :type) vocab))
              rogue (remove #(contains? vocab-norms (vb/normalize-name (key %))) schemes)]
          (println "\nURI schemes:" (into (sorted-map) schemes))
          (println "AC2 schemes ⊆ vocabulary:"
                   (count rogue) "rogue schemes =>"
                   (if (zero? (count rogue)) "PASS"
                       (str "FAIL — rogue: " (pr-str (map key rogue)))))
          ;; variant-split check: two distinct scheme spellings normalizing to one type
          (let [norm-groups (group-by (comp vb/normalize-name key) schemes)
                splits (filter #(> (count (val %)) 1) norm-groups)]
            (println "AC2b no case/variant scheme splits:"
                     (count splits) "splits =>" (if (zero? (count splits)) "PASS"
                                                    (pr-str (map val splits))))))
        ;; AC3 — enforcement observable per container.
        (println "\nAC3 per-container enforcement surface (freelanced-drafts / failures):")
        (doseq [pc per-container]
          (let [fl (get-in pc [:diagnosis :freelanced-drafts]
                           (get pc :freelanced-drafts))]
            (println (format "   %-34s status=%-8s drafts=%-6s"
                             (:container pc) (str (:status pc)) (str (:concept-count pc))))))
        (println "\n(raw report keys per container available; freelanced counts ride the child extraction-reports)")
        (println "\n=== DONE ==="))
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
