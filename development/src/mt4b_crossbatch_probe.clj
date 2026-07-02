(ns mt4b-crossbatch-probe
  "MT-4b VERIFY-FIRST (and later the red→green integration check) — the CROSS-BATCH
   occurrence-merge case: a draft whose URI matches a concept ALREADY LANDED by a
   PRIOR reconcile call (a later source enriching an entity an earlier source created).

   Batch 1 lands occupation/X with label 'Chief Executives' + description + topSkills.
   Batch 2 lands the SAME URI with a bare-code label + topKnowledge (a DIFFERENT source).
   Read the projection back: does the node carry the UNION (label + description +
   topSkills + topKnowledge), or did batch 2 REPLACE batch 1 (attributes + the good
   label/description lost)?

   Run BEFORE the fix → expect REPLACE (the known gap). Run AFTER → expect UNION.

   USAGE: clj -M:dev:test -m mt4b-crossbatch-probe"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [clojure.pprint :as pp]))

(def soc "11-1011.00")
(def uri (str "occupation/" soc))

(def batch1 ;; source A (Occupation Data-like): the entity-defining draft
  [{:uri uri :label "Chief Executives"
    :description "Determine and formulate policies and provide overall direction."
    :scope :custom
    :attributes {"O*NET-SOC Code" soc
                 :topSkills ["Critical Thinking" "Active Listening" "Speaking"]}}])

(def batch2 ;; source B (a LATER source): same entity, a new attribute, a bare label
  [{:uri uri :label soc :scope :custom
    :attributes {"O*NET-SOC Code" soc
                 :topKnowledge ["Administration and Management" "English Language"]}}])

(defn- land! [ctx oid drafts]
  (ce/delegate-reconcile! ctx {:ontology-id oid :concept-drafts drafts
                               :relationship-drafts [] :source-uri-sets nil
                               :model h/default-model}))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})
        oid (random-uuid)]
    (try
      (println "=== MT-4b CROSS-BATCH PROBE — two separate reconcile calls, same URI ===")
      (println "batch 1 (source A: label+desc+topSkills)…") (land! ctx oid batch1)
      (let [after1 (first (filter #(= uri (:uri %)) (rm/get-concepts ctx {:ontology-id oid})))]
        (println "  after batch 1 → label:" (pr-str (:label after1))
                 " desc?:" (boolean (:description after1))
                 " attrs:" (vec (keys (:attributes after1)))))
      (println "batch 2 (source B: bare label + topKnowledge)…") (land! ctx oid batch2)
      (let [nodes (filter #(= uri (:uri %)) (rm/get-concepts ctx {:ontology-id oid}))
            n (first nodes)
            a (:attributes n)]
        (println "\n=== READ-BACK ===")
        (println "  nodes for" uri ":" (count nodes))
        (println "  label:" (pr-str (:label n)) " desc?:" (boolean (:description n)))
        (println "  attribute keys:" (vec (keys a)))
        (println "  topSkills:" (:topSkills a) "  topKnowledge:" (:topKnowledge a))
        (println "\n=== VERDICT ===")
        (let [has-label? (= "Chief Executives" (:label n))
              has-desc? (boolean (:description n))
              has-skills? (boolean (:topSkills a))
              has-know? (boolean (:topKnowledge a))
              unioned? (and has-label? has-desc? has-skills? has-know?)]
          (println "  label kept (entity-defining 'Chief Executives')?" has-label?)
          (println "  description kept?" has-desc? "  topSkills kept?" has-skills? "  topKnowledge added?" has-know?)
          (println "  =>" (if unioned?
                            "UNION — cross-batch merge WORKS (fix landed)"
                            "REPLACE — batch 2 clobbered batch 1 (the gap; topSkills/label/desc lost)"))))
      (println "\n=== DONE ===")
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
