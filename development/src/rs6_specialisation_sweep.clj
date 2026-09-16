(ns rs6-specialisation-sweep
  "RS-6 — the two-pass specialisation sweep over the 21-task OOD corpus.

   Unlike c2d-ood-stress-live (which calls classify-task directly and so
   decides in memory only), every instruction here goes through the LIVE
   auto-classify wedge with a synthetic researcher node and a fresh
   sheet/tick, so a domain-child mint is DURABLE (concept, skos:broader
   edge, birth claim, classified event) and the R-Inject prepend is rendered
   from the real payload. Pass 1 mints; pass 2, on the same store, must land
   on IDENTICAL child identities (DomainChildIdentityIsStable through
   LandOnDomainChild). Everything recorded is structured data the runtime
   emitted — the classified/deferred event bodies, the payload's :domain
   map, the rendered block as an opaque string — never a parse of prose.

   Run from the launcher (see the comment block at the bottom)."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def corpus-dir "development/bench/ood-corpus")
(def results-root "development/bench/ood-stress-results")

(defn- now-stamp []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "uuuu-MM-dd_HHmmss")))

(defn- tick-events [ctx tick-id]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :types #{:ontology/task-classified
                              :ontology/task-classification-deferred
                              :ontology/domain-child-minted}
                     :tags #{[:tick tick-id]}})))

(defn classify-one!
  "Drive ONE instruction through the live wedge and the R-Inject render.
   Returns the per-instruction record."
  [ctx pass {:keys [slug instruction]}]
  (let [sheet-id (random-uuid)
        tick-id (random-uuid)
        node {:id (random-uuid)
              :name slug
              :type :repl-researcher
              :instruction instruction
              :reads [] :writes []
              :rlm {:auto-classify? true}}
        wedge-ctx (assoc ctx :sheet-id sheet-id :tick-id tick-id)
        start (System/currentTimeMillis)
        classified-node (tp/maybe-auto-classify-and-set-context node wedge-ctx)
        rendered-node (tp/apply-r05-classifier-context classified-node wedge-ctx)
        elapsed (- (System/currentTimeMillis) start)
        events (tick-events ctx tick-id)
        by-type (group-by :event/type events)
        classified (first (:ontology/task-classified by-type))
        deferred (:ontology/task-classification-deferred by-type)
        ;; The mint event is tagged by the CHILD (description-target), not the
        ;; tick; read it by the assigned child and keep the one this tick minted.
        minted (when-let [child (:assigned-tree-id classified)]
                 (->> (es/read (:event-store ctx)
                               {:tenant-id (:tenant-id ctx)
                                :types #{:ontology/domain-child-minted}
                                :tags #{[:description-target child]}})
                      (into [])
                      (filterv #(= tick-id (:source-tick-id %)))))
        structural (get-in classified-node [:context :r05-classifier :structural])]
    {:slug slug
     :pass pass
     :node-id (:id node)
     :sheet-id sheet-id
     :tick-id tick-id
     :elapsed-ms elapsed
     ;; The three-state outcome + provenance + verdict, from the EVENT.
     :outcome (:outcome classified)
     :assigned-via (:assigned-via classified)
     :assigned-tree-id (:assigned-tree-id classified)
     :parent-tree-id (:parent-tree-id classified)
     :domain-label (:domain-label classified)
     :domain-verdict (:domain-verdict classified)
     :domain-children-considered (:domain-children-considered classified)
     :domain-deferral (:domain-deferral classified)
     :was-fresh-mint? (:was-fresh-mint? classified)
     :confidence (:confidence classified)
     :top-1 (let [c (first (:top-candidates structural))]
              {:target-id (get-in c [:document-metadata :target-id])
               :fitness-score (:fitness-score c)
               :rerank-source (:rerank-source c)
               :domain-coverage (:domain-coverage c)
               :domain-label (:domain-label c)})
     :deferred-events (mapv #(select-keys % [:fallback-source :reasoning]) deferred)
     :minted-events (mapv #(select-keys % [:parent-tree-id :child-tree-id :domain-label]) minted)
     ;; The June-era flags, for the side-by-side the issue asks for.
     :june-flags (when structural (ood/classify-outcome structural))
     ;; The render: the payload's :domain map (structured) and the block.
     :payload-domain (:domain structural)
     :rendered-instruction (:instruction rendered-node)
     :classified-event classified}))

(defn- freq-of [k results] (frequencies (map k results)))

(defn aggregate [results]
  (let [n (count results)]
    {:total n
     :by-outcome (freq-of :outcome results)
     :by-assigned-via (freq-of :assigned-via results)
     :by-coverage (frequencies (map #(get-in % [:domain-verdict :domain-coverage]) results))
     :by-deferral-reason (frequencies (keep #(get-in % [:domain-deferral :reason]) results))
     :mints (vec (for [r results
                       :when (contains? #{:mint-domain-child :mint-sibling-domain-child}
                                        (:assigned-via r))]
                   {:slug (:slug r) :parent (:parent-tree-id r) :label (:domain-label r)
                    :child (:assigned-tree-id r) :via (:assigned-via r)
                    :mint-event-seen? (boolean (seq (:minted-events r)))}))
     :june-direct-match (count (filter (comp :direct-matched? :june-flags) results))
     :june-fresh-mint (count (filter (comp :was-fresh-mint? :june-flags) results))
     :june-rerank-fallback (count (filter (comp :rerank-fallback? :june-flags) results))
     :latency-mean-ms (if (pos? n) (double (/ (reduce + (map :elapsed-ms results)) n)) 0.0)}))

(defn compare-passes
  "Per slug: did pass 2 land on the identity pass 1 minted (or matched)?"
  [pass-1 pass-2]
  (let [p1 (into {} (map (juxt :slug identity)) pass-1)]
    (vec (for [r2 pass-2
               :let [r1 (get p1 (:slug r2))]]
           {:slug (:slug r2)
            :pass-1-via (:assigned-via r1)
            :pass-2-via (:assigned-via r2)
            :pass-1-id (:assigned-tree-id r1)
            :pass-2-id (:assigned-tree-id r2)
            :identity-stable? (= (:assigned-tree-id r1) (:assigned-tree-id r2))
            :pass-1-label (:domain-label r1)
            :pass-2-label (:domain-label r2)}))))

(defn- md-table [headers rows]
  (str "| " (str/join " | " headers) " |\n"
       "|" (str/join "|" (repeat (count headers) "---")) "|\n"
       (str/join "\n" (for [row rows] (str "| " (str/join " | " (map #(str/replace (str %) "|" "\\|") row)) " |")))
       "\n"))

(defn summary-md [{:keys [pass-1 pass-2 comparison agg-1 agg-2]}]
  (str "# RS-6 specialisation sweep — two passes through the live wedge\n\n"
       "## Aggregate\n\n"
       (md-table ["metric" "pass 1" "pass 2"]
                 [["total" (:total agg-1) (:total agg-2)]
                  ["by outcome" (:by-outcome agg-1) (:by-outcome agg-2)]
                  ["by assigned-via" (:by-assigned-via agg-1) (:by-assigned-via agg-2)]
                  ["by coverage" (:by-coverage agg-1) (:by-coverage agg-2)]
                  ["by deferral reason" (:by-deferral-reason agg-1) (:by-deferral-reason agg-2)]
                  ["June direct-match" (:june-direct-match agg-1) (:june-direct-match agg-2)]
                  ["June fresh-mint flag" (:june-fresh-mint agg-1) (:june-fresh-mint agg-2)]
                  ["June rerank-fallback" (:june-rerank-fallback agg-1) (:june-rerank-fallback agg-2)]
                  ["mean latency ms" (long (:latency-mean-ms agg-1)) (long (:latency-mean-ms agg-2))]])
       "\n## Per instruction — pass 1\n\n"
       (md-table ["slug" "outcome" "via" "top-1 fitness" "coverage" "label" "assigned" "parent" "deferral"]
                 (for [r pass-1]
                   [(:slug r) (:outcome r) (:assigned-via r)
                    (some-> (get-in r [:top-1 :fitness-score]) double (as-> f (format "%.2f" f)))
                    (get-in r [:domain-verdict :domain-coverage]) (:domain-label r)
                    (:assigned-tree-id r) (:parent-tree-id r) (get-in r [:domain-deferral :reason])]))
       "\n## Pass 2 vs pass 1 — identity stability\n\n"
       (md-table ["slug" "pass-1 via" "pass-2 via" "identity stable?" "pass-1 label" "pass-2 label"]
                 (for [c comparison]
                   [(:slug c) (:pass-1-via c) (:pass-2-via c) (:identity-stable? c) (:pass-1-label c) (:pass-2-label c)]))
       "\n## Mints (pass 1)\n\n"
       (md-table ["slug" "parent" "label" "child" "via"]
                 (for [m (:mints agg-1)] [(:slug m) (:parent m) (:label m) (:child m) (:via m)]))))

(defn- persist-pass! [dir pass results]
  (let [d (io/file dir (str "pass-" pass))]
    (.mkdirs d)
    (doseq [r results]
      (spit (io/file d (str "ood-result-" (:slug r) ".edn")) (with-out-str (pp/pprint r))))))

(defn run-two-pass!
  ([ctx] (run-two-pass! ctx {}))
  ([ctx {:keys [corpus-path dir-suffix] :or {corpus-path corpus-dir dir-suffix "-rs6-specialisation-sweep"}}]
   (let [corpus (ood/load-corpus corpus-path)
         dir (str results-root "/" (now-stamp) dir-suffix)
         _ (.mkdirs (io/file dir))
         run-pass (fn [pass]
                    (println (format "=== pass %d: %d instructions" pass (count corpus)))
                    (vec (for [[i entry] (map-indexed vector corpus)]
                           (do (println (format "  [%d/%d] %s" (inc i) (count corpus) (:slug entry)))
                               (let [r (classify-one! ctx pass entry)]
                                 (println (format "    outcome=%s via=%s coverage=%s label=%s assigned=%s elapsed=%dms"
                                                  (:outcome r) (:assigned-via r)
                                                  (get-in r [:domain-verdict :domain-coverage])
                                                  (:domain-label r) (:assigned-tree-id r) (:elapsed-ms r)))
                                 r)))))
         pass-1 (run-pass 1)
         _ (persist-pass! dir 1 pass-1)
         pass-2 (run-pass 2)
         _ (persist-pass! dir 2 pass-2)
         comparison (compare-passes pass-1 pass-2)
         agg-1 (aggregate pass-1)
         agg-2 (aggregate pass-2)
         summary {:pass-1 pass-1 :pass-2 pass-2 :comparison comparison :agg-1 agg-1 :agg-2 agg-2}]
     (spit (str dir "/combined.edn")
           (with-out-str (pp/pprint (-> summary
                                        (update :pass-1 #(mapv (fn [r] (dissoc r :rendered-instruction)) %))
                                        (update :pass-2 #(mapv (fn [r] (dissoc r :rendered-instruction)) %))))))
     (spit (str dir "/SUMMARY.md") (summary-md summary))
     (println "\n=== pass 1 aggregate ===") (pp/pprint agg-1)
     (println "\n=== pass 2 aggregate ===") (pp/pprint agg-2)
     (println "\n=== identity stability ===")
     (pp/pprint (frequencies (map :identity-stable? comparison)))
     (println "\nResults saved to" dir)
     (assoc summary :dir dir))))

;; =============================================================================
;; The bounded full-bench observation: three off-domain tasks through the
;; REAL bench runner (Phase 1 + Phase 2, auto-classify on), recorded as
;; observation — the classified event, the mint, the render's candidates and
;; block, the verdict occurrence and the claims that followed.
;; =============================================================================

(def full-bench-slugs
  ["domain-001-game-balance" "domain-002-recipe-scaling" "domain-003-marathon-training"])

(defn- sheet-events [ctx sheet-id]
  (let [all (into [] (es/read (:event-store ctx)
                              {:tenant-id (:tenant-id ctx)
                               :types #{:ontology/task-classified
                                        :ontology/task-classification-deferred
                                        :ontology/domain-child-minted
                                        :ontology/tree-class-occurrence-recorded
                                        :ontology/claim-deltas-recorded}}))]
    (filterv #(or (= sheet-id (:source-sheet-id %))
                  (contains? (:event/tags %) [:sheet sheet-id]))
             all)))

(defn run-full-bench-observation!
  "Run `slugs` (default the three off-domain tasks) through runner/run! with
   auto-classify on; persist one observation EDN per task under `dir`."
  ([ctx] (run-full-bench-observation! ctx {}))
  ([ctx {:keys [slugs dir-suffix] :or {slugs full-bench-slugs dir-suffix "-rs6-full-bench-observation"}}]
   (let [corpus (ood/load-corpus corpus-dir)
         by-slug (into {} (map (juxt :slug identity)) corpus)
         dir (str results-root "/" (now-stamp) dir-suffix)
         _ (.mkdirs (io/file dir))
         run! (requiring-resolve 'runner/run!)
         results
         (vec (for [slug slugs
                    :let [entry (get by-slug slug)]]
                (do (println "\n=== full bench:" slug)
                    (let [record (run! {:name slug
                                        :slug slug
                                        :pattern "ood-observation"
                                        :documents []
                                        :instruction (:instruction entry)
                                        :writes [:result]
                                        :rlm {:auto-classify? true}})
                          sheet-id (some-> record :r-inject-trace :injection-records first :sheet-id)
                          events (when sheet-id (sheet-events ctx sheet-id))
                          by-type (group-by :event/type events)
                          classified (first (:ontology/task-classified by-type))
                          obs {:slug slug
                               :sheet-id sheet-id
                               :status (:status record)
                               :duration-ms (:duration-ms record)
                               :usage (:usage record)
                               :classified (select-keys classified
                                                        [:outcome :assigned-via :assigned-tree-id :parent-tree-id
                                                         :domain-label :domain-verdict :domain-children-considered
                                                         :domain-deferral :was-fresh-mint? :confidence])
                               :deferred (mapv #(select-keys % [:fallback-source :reasoning])
                                               (:ontology/task-classification-deferred by-type))
                               :minted (mapv #(select-keys % [:parent-tree-id :child-tree-id :domain-label])
                                             (:ontology/domain-child-minted by-type))
                               :occurrences (mapv #(select-keys % [:assigned-tree-id :verdict])
                                                  (:ontology/tree-class-occurrence-recorded by-type))
                               :claims (mapv #(select-keys % [:granularity :target-identifier :evidence-event-count])
                                             (:ontology/claim-deltas-recorded by-type))
                               :render {:arm (-> record :r-inject-trace :arm)
                                        :candidates (-> record :r-inject-trace :candidates)
                                        :prepend-chars (-> record :r-inject-trace :prepend-chars)
                                        :prepend (-> record :r-inject-trace :prepend)}
                               :generated-tree-raw (:generated-tree-raw record)
                               :outputs (:outputs record)
                               :error (:error record)}]
                      (spit (str dir "/observation-" slug ".edn") (with-out-str (pp/pprint obs)))
                      (println "    status=" (:status record) "via=" (get-in obs [:classified :assigned-via])
                               "label=" (get-in obs [:classified :domain-label])
                               "occurrences=" (count (:occurrences obs)))
                      obs))))]
     (spit (str dir "/observations.edn") (with-out-str (pp/pprint (mapv #(dissoc % :render :generated-tree-raw :outputs) results))))
     (println "\nObservations saved to" dir)
     {:dir dir :results results})))

(comment
  ;; Launcher (from the worktree root, OPENROUTER_API_KEY in the environment):
  ;; clojure -J-Djava.awt.headless=true -J-Xmx4g -M:dev:test -e "(require 'runner) (runner/start!) (Thread/sleep 45000) (require 'rs6-specialisation-sweep) (let [ctx (deref @(requiring-resolve 'runner/system-state))] (rs6-specialisation-sweep/run-two-pass! ctx)) (runner/stop!) (shutdown-agents) (System/exit 0)"
  )
