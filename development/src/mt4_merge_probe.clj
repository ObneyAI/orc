(ns mt4-merge-probe
  "MT-4 VERIFY-FIRST probe — OBSERVE what the REAL reconcile + landing path does with
   MULTIPLE drafts of the SAME entity URI arriving from different containers (the real
   O*NET case: Occupation Data + Skills + Knowledge are sheets of ONE source, so the
   orchestrator UNIONS their drafts into ONE reconcile batch). Question the issue poses:
   ONE node with UNIONED attributes (label + description + topSkills + topKnowledge),
   or duplicates / DROPPED attributes?

   No assumptions (#1): drive real drafts through delegate-reconcile! then read the
   concepts projection back. Grounding hypothesis (to CONFIRM or REFUTE): the concepts
   read-model does `(assoc state uri …)` on :concept-created (a full REPLACE keyed by
   URI), so unless reconcile emits a merge/update for a same-URI draft, later drafts
   REPLACE earlier ones → attributes LOST.

   USAGE: clj -M:dev:test -m mt4-merge-probe"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [clojure.pprint :as pp]))

(def soc1 "11-1011.00")
(def soc2 "11-2021.00")

;; 3 drafts per occupation, SAME canonical URI (as GC-1 would produce post-canonicalize),
;; each carrying DIFFERENT attributes — mimicking the 3 containers.
(defn drafts-for [soc title]
  [{:uri (str "occupation/" soc) :label title
    :description (str "Occupation " title " (from Occupation Data).")
    :scope :custom
    :attributes {"O*NET-SOC Code" soc}}
   {:uri (str "occupation/" soc) :label soc :scope :custom
    :attributes {"O*NET-SOC Code" soc
                 :topSkills ["Critical Thinking" "Active Listening" "Speaking"]}}
   {:uri (str "occupation/" soc) :label soc :scope :custom
    :attributes {"O*NET-SOC Code" soc
                 :topKnowledge ["Administration and Management" "English Language"]}}])

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})
        oid (random-uuid)]
    (try
      (println "=== MT-4 MERGE PROBE — real reconcile + landing, same-URI multi-container drafts ===")
      (let [drafts (vec (concat (drafts-for soc1 "Chief Executives")
                                (drafts-for soc2 "General Managers")))
            _ (println "landing" (count drafts) "drafts for" 2 "occupations (3 same-URI each)…")
            rc (ce/delegate-reconcile! ctx {:ontology-id oid
                                            :concept-drafts drafts
                                            :relationship-drafts []
                                            :source-uri-sets nil
                                            :model h/default-model})
            _ (println "reconcile status:" (:status rc))
            concepts (rm/get-concepts ctx {:ontology-id oid})
            occ (filter #(re-find #"^occupation/" (str (:uri %))) concepts)
            by-uri (group-by :uri occ)]
        (println "\n=== READ-BACK (the projection — the real landed graph) ===")
        (println "occupation nodes total:" (count occ)
                 " distinct URIs:" (count by-uri))
        (doseq [[uri nodes] by-uri]
          (println "\n" uri "  (" (count nodes) "node(s) for this URI)")
          (doseq [n nodes]
            (let [attrs (:attributes n)]
              (println "    label:" (pr-str (:label n))
                       " desc?:" (boolean (:description n)))
              (println "    attribute keys:" (vec (keys attrs)))
              (println "    topSkills:" (get attrs :topSkills)
                       " topKnowledge:" (get attrs :topKnowledge)))))
        (println "\n=== VERDICT ===")
        (let [n1 (first (get by-uri (str "occupation/" soc1)))
              has-label? (boolean (:label n1))
              has-desc? (boolean (:description n1))
              has-skills? (boolean (get-in n1 [:attributes :topSkills]))
              has-know? (boolean (get-in n1 [:attributes :topKnowledge]))
              dupes? (some #(> (count (val %)) 1) by-uri)
              unioned? (and has-label? has-desc? has-skills? has-know?)]
          (println " one-node-per-URI? " (not dupes?))
          (println " node carries label+description+topSkills+topKnowledge (UNION)? " unioned?)
          (println "   label?" has-label? " desc?" has-desc? " topSkills?" has-skills? " topKnowledge?" has-know?)
          (println " =>" (cond
                           unioned? "UNION ALREADY HAPPENS — MT-4 = add a guard test, no build"
                           dupes? "DUPLICATES — reconcile mints per draft (need merge)"
                           :else "ATTRIBUTES DROPPED — same-URI drafts REPLACE (need union at reconcile/landing)"))
          (println "\n reconcile-report (attribute-reconcile summary):")
          (pp/pprint (select-keys (get rc :reconcile-report) [:entity-reconcile :attribute-reconcile :probe]))))
      (println "\n=== DONE ===")
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
