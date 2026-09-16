(ns rs6-usefulness-bench
  "RS-6 usefulness benchmark — does the domain-child waterfall actually inform
   the model's trees? Mirrors development/bench/r_inject_reports: the same
   bench runner, the same models, three arms per task on ONE store:

     :baseline   — auto-classify OFF (no classifier, no prepend)
     :r-inject-1 — auto-classify ON, first occurrence (the domain child is
                   minted; the prepend renders the parent shape + child line)
     :r-inject-2 — auto-classify ON, second occurrence of the same task on the
                   same store (lands on the child; whatever the child has
                   accrued from the first campaign is visible or not)

   Tasks: three off-domain corpus tasks (no documents) and the in-domain
   legal-issue-detection control (must still match its seed at 1.00, covered,
   and render exactly as the June suite did). Everything recorded is the
   runner's own result record plus the store's facts; the report is written
   by hand from the saved EDNs."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def corpus-dir "development/bench/ood-corpus")
(def results-root "development/bench/r_inject_reports/specialisation-results")

(def off-domain-slugs
  ["domain-003-marathon-training" "domain-002-recipe-scaling" "domain-001-game-balance"])

(defn- now-stamp []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "uuuu-MM-dd_HHmmss")))

(defn- classified-for-sheet [ctx sheet-id]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx)
                 :types #{:ontology/task-classified :ontology/task-classification-deferred}})
       (into [])
       (filterv #(= sheet-id (:source-sheet-id %)))))

(defn- child-state [ctx child-id]
  (when child-id
    (let [body (ontology/get-description ctx :tree-class child-id)
          claims (ontology/get-claims ctx :tree-class child-id)]
      {:summary (:summary body)
       :version (:version body)
       :consolidated-from-event-count (:consolidated-from-event-count body)
       :strength-count (count (:strengths body))
       :weakness-count (count (:weaknesses body))
       :representative-uses (:representative-uses body)
       :claim-count (count claims)
       :claim-kinds (frequencies (map :kind claims))
       :concept-label (:label (ontology/get-concept-by-uri ctx (str "tree-class:" child-id)))})))

(defn- tree-node-count [tree]
  (if (sequential? tree)
    (reduce + 1 (map tree-node-count (rest tree)))
    0))

(defn run-arm! [ctx run! task arm]
  (let [t (assoc task :rlm (merge (:rlm task {}) {:auto-classify? (not= arm :baseline)}))
        record (run! t)
        sheet-id (some-> record :r-inject-trace :injection-records first :sheet-id)
        events (when sheet-id (classified-for-sheet ctx sheet-id))
        classified (first (filter #(= :ontology/task-classified (:event/type %)) events))
        deferred (filterv #(= :ontology/task-classification-deferred (:event/type %)) events)
        child-id (when (contains? #{:mint-domain-child :land-on-domain-child :mint-sibling-domain-child}
                                  (:assigned-via classified))
                   (:assigned-tree-id classified))
        reasonings (vec (or (:iteration-reasonings record) []))
        label (:domain-label classified)]
    {:arm arm
     :slug (:slug task)
     :status (:status record)
     :error (:error record)
     :duration-ms (:duration-ms record)
     :usage (:usage record)
     :node-trace-usage-total (:node-trace-usage-total record)
     :iteration-count (count (:iterations record))
     :iteration-reasonings reasonings
     :generated-tree-raw (:generated-tree-raw record)
     :tree-node-count (tree-node-count (:generated-tree-raw record))
     :outputs (:outputs record)
     :render {:arm (-> record :r-inject-trace :arm)
              :prepend-chars (-> record :r-inject-trace :prepend-chars)
              :candidates (-> record :r-inject-trace :candidates)
              :prepend (-> record :r-inject-trace :prepend)}
     :classified (select-keys classified [:outcome :assigned-via :assigned-tree-id :parent-tree-id
                                          :domain-label :domain-verdict :domain-children-considered
                                          :domain-deferral :confidence :was-fresh-mint?])
     :deferred (mapv #(select-keys % [:fallback-source :reasoning]) deferred)
     :child-after-run (child-state ctx child-id)
     :reasoning-names-label? (boolean (and label (some #(str/includes? % label) reasonings)))
     :reasoning-mentions-corpus? (boolean (some #(str/includes? (str/lower-case %) "corpus") reasonings))}))

(defn- md-row [cells] (str "| " (str/join " | " (map #(str/replace (str %) "|" "\\|") cells)) " |\n"))

(defn summary-md [runs]
  (str "# RS-6 usefulness benchmark — baseline vs first vs second occurrence\n\n"
       "| task | arm | status | wall s | tokens (usage) | tokens (node-trace) | iterations | tree nodes | via | label | prepend chars | reasoning names label | reasoning mentions corpus | child claims | child consolidated count |\n"
       "|---|---|---|---:|---:|---:|---:|---:|---|---|---:|---|---|---:|---:|\n"
       (apply str
              (for [r runs]
                (md-row [(:slug r) (:arm r) (:status r)
                         (format "%.1f" (/ (double (or (:duration-ms r) 0)) 1000.0))
                         (get-in r [:usage :total-tokens])
                         (get-in r [:node-trace-usage-total :total-tokens])
                         (:iteration-count r) (:tree-node-count r)
                         (get-in r [:classified :assigned-via]) (get-in r [:classified :domain-label])
                         (get-in r [:render :prepend-chars])
                         (:reasoning-names-label? r) (:reasoning-mentions-corpus? r)
                         (get-in r [:child-after-run :claim-count])
                         (get-in r [:child-after-run :consolidated-from-event-count])])))))

(defn run-bench!
  ([ctx] (run-bench! ctx {}))
  ([ctx {:keys [slugs include-legal?] :or {slugs off-domain-slugs include-legal? true}}]
   (let [run! (requiring-resolve 'runner/run!)
         corpus (into {} (map (juxt :slug identity)) (ood/load-corpus corpus-dir))
         legal (when include-legal? @(requiring-resolve 'legal-issue-detection/task))
         tasks (cond-> (vec (for [s slugs :let [e (get corpus s)]]
                              {:slug s :name s :pattern "off-domain" :documents []
                               :instruction (:instruction e) :writes [:result]}))
                 legal (conj legal))
         dir (str results-root "/" (now-stamp))
         _ (.mkdirs (io/file dir))
         runs (vec (for [task tasks
                         arm [:baseline :r-inject-1 :r-inject-2]]
                     (do (println (format "\n##### %s / %s" (:slug task) arm))
                         (let [r (run-arm! ctx run! task arm)]
                           (spit (str dir "/" (:slug task) "-" (name arm) ".edn")
                                 (with-out-str (pp/pprint r)))
                           (println (format "    -> status=%s tokens=%s via=%s label=%s prepend=%s"
                                            (:status r) (get-in r [:usage :total-tokens])
                                            (get-in r [:classified :assigned-via])
                                            (get-in r [:classified :domain-label])
                                            (get-in r [:render :prepend-chars])))
                           r))))]
     (spit (str dir "/summary.edn")
           (with-out-str (pp/pprint (mapv #(dissoc % :render :generated-tree-raw :outputs :iteration-reasonings) runs))))
     (spit (str dir "/SUMMARY.md") (summary-md runs))
     (println "\nSaved to" dir)
     {:dir dir :runs runs})))
