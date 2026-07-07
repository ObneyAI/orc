(ns connect3b-edge-scan
  "CONNECT-3b STEP 3 — READ-ONLY forensic edge-scan against the persisted store of
   the killed live O*NET build. Proves the MODEL's association discovery LANDED:
   occupations participate in cross-sheet EDGES to shared element (skill/knowledge/…)
   nodes, and BFS occupation↔element↔occupation traverses. Streams the concepts +
   relationships (no full projection). oid + tenant verified from the db tags."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface] ;; register :sqlite store
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.read-models] ;; register read models (side-effect)
            [ai.obney.orc.ontology.interface.schemas] ;; event schemas
            [clojure.string :as str]))

(def db-file "/tmp/eb12-graph-b-c96754af-bc17-4a8d-aabb-57a51ff97d6c-events.db")
(def tenant  #uuid "0385e06d-1d09-4261-b9d4-f7804aa293ca")
(def oid     #uuid "3b2a8431-d369-4367-9eb5-f8c0c9837144")

(defn scheme-of [uri]
  (let [u (str uri) i (str/index-of u "/")] (if i (subs u 0 i) u)))

;; The occupation KEY nodes land under scheme "entity" (the association-spec's
;; :key-entity-type defaulted), plus "occupation"/"Occupation" for canonical + related
;; nodes. All are SOC-coded occupations. A genuine ELEMENT scheme is skill/ability/etc.
(def element-scheme-re #"(?i)^skill|^abilit|^knowledge|^workactiv|^task")
(defn element-scheme? [uri] (boolean (re-find element-scheme-re (scheme-of uri))))
(defn occ-scheme? [uri]
  (boolean (re-find #"(?i)occupation|^soc|^onet|^entity" (scheme-of uri))))

(defn -main [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2}
                         :logger nil})
        ctx {:event-store store :tenant-id tenant}]
    (try
      (println "========== CONNECT-3b EDGE-SCAN — oid" oid "==========")
      (let [concepts (cs/reduce-concepts ctx oid conj [])
            edges    (cs/reduce-relationships ctx oid conj [])
            by-scheme (frequencies (map (comp scheme-of :uri) concepts))]
        (println "concepts:" (count concepts) "  relationships:" (count edges))
        (println "\nconcept schemes (top 15):")
        (doseq [[s n] (take 15 (sort-by val > by-scheme))] (println (format "  %7d  %s" n s)))

        ;; ALL edge types by (source-scheme -> target-scheme) pair — shows exactly which
        ;; associations were formed (occupation->skill? occupation->occupation? etc).
        (println "\n########## EDGE TYPES by (source-scheme -> target-scheme) — top 25 ##########")
        (let [pairs (frequencies (map (fn [e] [(scheme-of (:source-uri e)) (scheme-of (:target-uri e))
                                               (:predicate e)]) edges))]
          (doseq [[[s t p] n] (take 25 (sort-by val > pairs))]
            (println (format "  %7d  %-14s -> %-16s  %s" n s t (pr-str p))))
          ;; edges reaching genuine ELEMENT nodes (skill/ability/knowledge/workactivity/task)
          (println "\n  edges into genuine ELEMENT schemes (skill/ability/knowledgearea/workactivity/task):")
          (doseq [[[s t p] n] (sort-by val >
                                       (filter (fn [[[_ t _] _]]
                                                 (re-find #"(?i)skill|abilit|knowledge|workactiv|^task" t))
                                               pairs))]
            (println (format "  %7d  %-14s -> %-16s  %s" n s t (pr-str p)))))

        ;; occupation -> genuine-ELEMENT edges (occupation key on one side, a shared
        ;; skill/task/ability/knowledge/workactivity element on the other) — the
        ;; headline association discovery. Related-Occupations (occ->occ) is excluded
        ;; here so the BFS bridges through a genuine shared ELEMENT node.
        (let [cross (filterv (fn [e]
                               (or (and (occ-scheme? (:source-uri e)) (element-scheme? (:target-uri e)))
                                   (and (occ-scheme? (:target-uri e)) (element-scheme? (:source-uri e)))))
                             edges)
              occ-parts (set (mapcat (fn [e] (filter occ-scheme? [(:source-uri e) (:target-uri e)])) cross))]
          (println "\n########## OCCUPATION CROSS-SHEET EDGE-SCAN ##########")
          (println "cross-sheet edges touching an occupation:" (count cross))
          (println "DISTINCT occupations participating in a cross-sheet edge:" (count occ-parts)
                   "  (was 0 pre-CONNECT-3a/3b)")
          (println "\npredicates on those edges (freq, top 15):")
          (doseq [[p n] (take 15 (sort-by val > (frequencies (map :predicate cross))))]
            (println (format "  %7d  %s" n (pr-str p))))
          (println "\nelement schemes the occupation edges reach (the associatively-modeled junctions):")
          (doseq [[s n] (sort-by val > (frequencies
                                        (map (fn [e] (if (occ-scheme? (:source-uri e))
                                                       (scheme-of (:target-uri e))
                                                       (scheme-of (:source-uri e)))) cross)))]
            (println (format "  %7d  %s" n s)))
          ;; ratings on edges (CONNECT-3b :properties)
          (let [rated (filter #(seq (:properties %)) cross)]
            (println "\ncross-sheet occupation edges carrying a RATING in :properties:"
                     (count rated) "/" (count cross))
            (println "sample rated edges (up to 6):")
            (doseq [e (take 6 rated)]
              (println "  " (:source-uri e) "--" (pr-str (:predicate e)) "->" (:target-uri e)
                       "  :properties" (pr-str (:properties e)))))

          ;; BFS occupation -> element -> DIFFERENT occupation over the cross-sheet edges
          (println "\n########## BFS occupation ↔ element ↔ occupation ##########")
          (let [adj (reduce (fn [m e]
                              (-> m (update (:source-uri e) (fnil conj #{}) (:target-uri e))
                                    (update (:target-uri e) (fnil conj #{}) (:source-uri e))))
                            {} cross)
                triads (for [start (take 3000 occ-parts)
                             elem (get adj start)
                             :when (element-scheme? elem)
                             other (get adj elem)
                             :when (and (occ-scheme? other) (not= other start))]
                         [start elem other])]
            (println "occupation→element→DIFFERENT-occupation triads found:" (count (take 20000 triads)))
            (doseq [[a e b] (take 6 triads)]
              (println (format "  %s  ↔  %s  ↔  %s" a e b)))
            (println "\n>>> BFS-TRAVERSABLE (occupation↔element↔occupation):" (boolean (seq triads))))

          ;; harness rule-out
          (println "\n########## HARNESS RULE-OUT ##########")
          (println "ontology-id (verified from db tags):" oid)
          (println "tenant (from db):" tenant)
          (println "concepts under this oid:" (count concepts) "(non-zero ⇒ correct oid/tenant)")))
      (finally
        (es/stop store)
        (shutdown-agents)))))
