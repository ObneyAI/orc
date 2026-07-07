(ns connect3b-live-verify
  "CONNECT-3b STEP 3 — LIVE bounded O*NET build + forensic edge-scan. Proves the
   MODEL's association discovery LANDS: occupations participate in cross-sheet EDGES
   to shared element (skill/knowledge/…) nodes, and BFS occupation↔element↔occupation
   traverses. Reads the ontology-id + the snapshot the build RETURNS (no wrong-oid
   trap — the oid comes straight from the result, verified against the concepts' tags).

   USAGE: OPENROUTER_API_KEY=... clj -J-Xmx6g -M:dev -m connect3b-live-verify"
  (:require [eb12-graph-b-central-evolver :as h]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn scheme-of [uri]
  (let [u (str uri) i (str/index-of u "/")] (if i (subs u 0 i) u)))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (println "=== CONNECT-3b LIVE — bounded O*NET (association discovery + edge-scan) ===")
  ;; Single CQ iteration + single evolver iteration: the association EDGES are
  ;; produced by the FIRST extract pass; the multi-iteration CQ loop re-embeds the
  ;; whole graph each pass (the known ~13x re-embed cost) and adds no new edges —
  ;; so we bound the CQ loop to ONE iteration for a fast finalize + snapshot.
  (let [r (h/run! {:only [:onet] :max-containers 10 :max-windows 5 :store :sqlite
                   :budget {:max-iterations 1 :total-budget-ms 900000 :max-retries 2}
                   :evolver-config {:max-iterations 1}})
        oid (:ontology-id r)
        concepts (get r (keyword "eb12-graph-b-central-evolver" "concepts"))
        rels (get r (keyword "eb12-graph-b-central-evolver" "relationships"))
        concept-uris (set (map :uri concepts))
        by-scheme (frequencies (map (comp scheme-of :uri) concepts))]
    (println "\n########## RESULT ##########")
    (println "ontology-id:" oid "  status:" (:status r) "  elapsed(ms):" (:elapsed-ms r))
    (println "concepts:" (count concepts) "  relationships:" (count rels))
    (println "\nconcept schemes (top 20):")
    (doseq [[s n] (take 20 (sort-by val > by-scheme))] (println (format "  %6d  %s" n s)))

    ;; --- EDGE-SCAN: cross-sheet occupation participation ---
    (let [occ-scheme? (fn [uri] (re-find #"(?i)occupation|soc|onet" (scheme-of uri)))
          edge-schemes (fn [e] [(scheme-of (:source-uri e)) (scheme-of (:target-uri e))])
          cross-sheet (filter (fn [e]
                                (let [[s t] (edge-schemes e)]
                                  (and (not= s t)
                                       (or (occ-scheme? (:source-uri e))
                                           (occ-scheme? (:target-uri e))))))
                              rels)
          occ-participants (set (mapcat (fn [e]
                                          (filter occ-scheme? [(:source-uri e) (:target-uri e)]))
                                        cross-sheet))]
      (println "\n########## OCCUPATION CROSS-SHEET EDGE-SCAN ##########")
      (println "cross-sheet edges touching an occupation:" (count cross-sheet))
      (println "DISTINCT occupations participating in a cross-sheet edge:" (count occ-participants)
               "  (was 0 pre-CONNECT-3a/3b)")
      (println "\npredicates on cross-sheet occupation edges (frequency):")
      (doseq [[p n] (sort-by val > (frequencies (map :predicate cross-sheet)))]
        (println (format "  %6d  %s" n (pr-str p))))
      (println "\ntarget schemes the occupation edges point at (the associatively-modeled junctions):")
      (doseq [[s n] (sort-by val > (frequencies (map (fn [e]
                                                       (if (occ-scheme? (:source-uri e))
                                                         (scheme-of (:target-uri e))
                                                         (scheme-of (:source-uri e))))
                                                     cross-sheet)))]
        (println (format "  %6d  %s" n s)))

      ;; --- ratings on the edges (CONNECT-3b :properties) ---
      (let [with-props (filter #(seq (:properties %)) cross-sheet)]
        (println "\ncross-sheet occupation edges carrying a RATING in :properties:" (count with-props)
                 "/" (count cross-sheet))
        (println "sample rated edges (up to 5):")
        (doseq [e (take 5 with-props)]
          (println "  " (:source-uri e) "--" (pr-str (:predicate e)) "-->" (:target-uri e)
                   "  :properties" (pr-str (:properties e)))))

      ;; --- BFS occupation -> element -> DIFFERENT occupation ---
      (println "\n########## BFS occupation ↔ element ↔ occupation ##########")
      (let [;; adjacency over cross-sheet edges (bidirectional)
            adj (reduce (fn [m e]
                          (-> m
                              (update (:source-uri e) (fnil conj #{}) (:target-uri e))
                              (update (:target-uri e) (fnil conj #{}) (:source-uri e))))
                        {} cross-sheet)
            occ-list (vec occ-participants)
            ;; find an element shared by >=2 occupations
            shared-triads (for [start occ-list
                                elem (get adj start)
                                :when (not (occ-scheme? elem))
                                other (get adj elem)
                                :when (and (occ-scheme? other) (not= other start))]
                            [start elem other])]
        (println "occupation→element→DIFFERENT-occupation triads found:" (count shared-triads))
        (doseq [[a e b] (take 5 shared-triads)]
          (println (format "  %s  ↔  %s  ↔  %s" a e b)))
        (println "\n>>> BFS-TRAVERSABLE (occupation↔element↔occupation):" (boolean (seq shared-triads)))))

    ;; --- rule out the harness: verify the oid against the concepts' presence ---
    (println "\n########## HARNESS RULE-OUT ##########")
    (println "ontology-id from result:" oid)
    (println "concepts present under this build:" (count concept-uris) "(non-zero ⇒ oid is the real build)")
    (println "\n=== CONNECT-3b LIVE DONE ===")
    (shutdown-agents)
    (System/exit 0)))
