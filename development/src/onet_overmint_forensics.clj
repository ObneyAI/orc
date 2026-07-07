(ns onet-overmint-forensics
  "Forensic read of the killed default-cap O*NET build's store (ontology
   c8579c73). Answers, with PROOF (no assumptions):
   Q1 — how many concepts, broken down by URI-scheme/kind (is 3852 an over-mint
        vs A2's ~1016, and WHERE do they concentrate?).
   Q2 — does cs/reduce-concept-embeddings (Slice 2) correctly return the
        already-embedded URI set? (If it returns < the 3852 concept-embedded
        events, GC-12's skip breaks → re-embed every iteration → the ~13x
        49,216-DJL-call redundancy.)
   Read-only; starts a store on the EXISTING db-file. No writes."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface] ;; registers the :sqlite store multimethod (side-effect require)
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.read-models] ;; registers :ontology/concepts + concept-embeddings read models (defreadmodel side-effect)
            [clojure.string :as str]))

(def db-file "/tmp/eb12-graph-b-e6cb8887-2e83-45da-901e-12232b27c0c4-events.db")
(def tenant #uuid "47a376ec-272a-4f02-8c99-ad1b71053a24")
(def oid    #uuid "398a54e1-12a9-4260-afc4-25efb8badb81")

(defn- uri-scheme
  "The leading scheme/kind token of a URI: the substring before the first
   `:` or `/` (e.g. \"occupation/25-1011\" -> \"occupation\", \"onet:soc/...\"
   -> \"onet\"). Domain-agnostic — just the structural prefix."
  [uri]
  (let [s (str uri)]
    (-> s (str/split #"[:/]" 2) first (or "∅"))))

(defn -main [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file
                                :maximum-pool-size 2}
                         :logger nil})
        ctx {:event-store store :tenant-id tenant}]
    (try
      (let [concepts (cs/reduce-concepts ctx oid conj [])
            embedded (cs/reduce-concept-embeddings ctx oid (fn [acc uri _] (conj acc uri)) #{})
            uris (map :uri concepts)
            concept-uri-set (set uris)]
        (println "\n========== O*NET OVER-MINT FORENSICS (ontology" oid ") ==========")
        (println "Q1 concepts (via reduce-concepts):" (count concepts))
        (println "    distinct URIs:" (count concept-uri-set)
                 (if (= (count concepts) (count concept-uri-set)) "(all distinct)"
                     (str "(!! " (- (count concepts) (count concept-uri-set)) " DUPLICATE URIs)")))
        (println "\nQ1 breakdown by URI-scheme (kind) — top 25:")
        (doseq [[scheme n] (->> uris (map uri-scheme) frequencies (sort-by val >) (take 25))]
          (println (format "    %6d  %s" n scheme)))
        (println "\nQ1 label-cardinality per scheme (are these entities or occurrence-rows?):")
        (doseq [[scheme cs*] (->> concepts (group-by (comp uri-scheme :uri)) (sort-by (comp count val) >) (take 8))]
          (let [labels (map :label cs*)]
            (println (format "    %-22s concepts=%-6d distinct-labels=%-6d  sample-labels=%s"
                             scheme (count cs*) (count (distinct labels))
                             (pr-str (vec (take 3 (distinct labels))))))))
        (println "\nQ2 EMBED-READ CORRECTNESS (the ~13x re-embed suspect):")
        (println "    concept-embedded events in store (SQL): 3852")
        (println "    reduce-concept-embeddings returned URIs:" (count embedded))
        (println "    embedded ⊆ concepts?:" (every? concept-uri-set embedded))
        (println "    concepts WITHOUT an embedding (would be re-embedded each pass):"
                 (count (remove embedded concept-uri-set)))
        (println "    VERDICT:"
                 (cond
                   (zero? (count embedded)) "reduce-concept-embeddings returns 0 → GC-12 skip BROKEN → re-embeds all each pass (REGRESSION)"
                   (< (count embedded) 3800) (str "reduce-concept-embeddings returns " (count embedded) " << 3852 → skip PARTIALLY broken")
                   :else "reduce-concept-embeddings returns ~3852 → embed-read OK → the 13x is NOT this primitive"))

        ;; ---- Q3: the grain choice — tasks as NODES vs occupation ATTRIBUTES ----
        (let [by-scheme (group-by (comp uri-scheme :uri) concepts)
              occ  (get by-scheme "occupation")
              task (get by-scheme "jobtask")
              rels (cs/reduce-relationships ctx oid conj [])
              task-uris (set (map :uri task))
              occ-uris  (set (map :uri occ))]
          (println "\nQ3 GRAIN CHOICE — are tasks NODES, occupation ATTRIBUTES, or BOTH?")
          (println "  -- sample OCCUPATION concept (does it carry a task LIST attribute? = collect-mode/attr path) --")
          (let [o (first (sort-by :uri occ))]
            (println "    uri:" (:uri o) " label:" (:label o))
            (println "    attribute keys:" (keys (:attributes o)))
            (doseq [[k v] (:attributes o)]
              (println (format "      %s -> %s%s" k
                               (if (coll? v) (str "[" (count v) " values] ") "")
                               (pr-str (if (coll? v) (vec (take 2 v)) v))))))
          (println "  -- sample JOBTASK concept (a first-class node? = breakdown-as-entity path) --")
          (let [t (first (sort-by :uri task))]
            (println "    uri:" (:uri t) " label:" (pr-str (:label t)))
            (println "    attribute keys:" (keys (:attributes t))))
          (println "\n  -- relationships:" (count rels) "total; predicate breakdown --")
          (doseq [[p n] (->> rels (map :predicate) frequencies (sort-by val >) (take 12))]
            (println (format "      %6d  %s" n p)))
          (let [occ->task (filter #(and (occ-uris (:source-uri %)) (task-uris (:target-uri %))) rels)
                task->occ (filter #(and (task-uris (:source-uri %)) (occ-uris (:target-uri %))) rels)]
            (println "  -- occupation<->jobtask edges: occ->task=" (count occ->task) " task->occ=" (count task->occ))
            (println "     sample occ<->task edge:" (pr-str (select-keys (first (concat occ->task task->occ)) [:source-uri :predicate :target-uri]))))
          (let [occ-with-attrs (count (filter (comp seq :attributes) occ))
                occ-with-list  (count (filter (fn [o] (some coll? (vals (:attributes o)))) occ))]
            (println "\n  VERDICT on double-modeling:")
            (println "    occupations with ANY attributes:" occ-with-attrs "/" (count occ))
            (println "    occupations carrying a LIST-valued attribute (collect-mode tasks?):" occ-with-list "/" (count occ))
            (println "    →" (cond
                               (and (pos? (count task)) (zero? occ-with-list))
                               "tasks are ONLY nodes (breakdown-as-entity) — occupations have NO task-list attr"
                               (and (pos? (count task)) (pos? occ-with-list))
                               "DOUBLE-MODELED: tasks as nodes AND as occupation list-attrs"
                               :else "tasks not node-minted here"))))
        (println "==================================================================\n"))
      (finally (es/stop store)))))

(defn spec-scan
  "Read the sheet execution-value events and surface any carrying a model-spec /
   entity-type / grain-strategy that produced the jobtask nodes — the 'why'."
  [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})]
    (try
      (let [evs (into [] (es/read store {:tenant-id tenant
                                         :types [:sheet/execution-value-written]}))]
        (println "\n=== SHEET execution-value events:" (count evs) "===")
        (doseq [e evs]
          (let [v (pr-str (dissoc e :event/id :id :time :tenant-id :type :event/type))]
            (when (re-find #"(?i)jobtask|grain|entity-type|entity_type|breakdown|Task ID|DWA|selector|element-col|key-col|value-col" v)
              (println "----")
              (println (subs v 0 (min 1400 (count v)))))))
        (println "=== end ==="))
      (finally (es/stop store)))))

(defn edge-scan
  "Edges by (source-scheme -> target-scheme): proves whether occupations are
   connected to anything, or sit as isolated attribute-only nodes."
  [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})
        ctx {:event-store store :tenant-id tenant}]
    (try
      (let [rels (cs/reduce-relationships ctx oid conj [])
            concepts (cs/reduce-concepts ctx oid conj [])
            scheme (into {} (map (fn [c] [(:uri c) (uri-scheme (:uri c))]) concepts))]
        (println "\n=== EDGE endpoints by kind (src-scheme -> tgt-scheme : predicate) ===")
        (doseq [[k n] (->> rels
                           (map (fn [r] [(get scheme (:source-uri r) "∅") (get scheme (:target-uri r) "∅") (:predicate r)]))
                           frequencies (sort-by val >) (take 15))]
          (println (format "    %6d  %-16s -> %-20s  %s" n (nth k 0) (nth k 1) (nth k 2))))
        (let [occ-uris (set (map :uri (filter #(= "occupation" (uri-scheme (:uri %))) concepts)))
              occ-in-edge (count (filter #(or (occ-uris (:source-uri %)) (occ-uris (:target-uri %))) rels))]
          (println "\n    occupations (1016) participating in ANY edge:" occ-in-edge)
          (println "    → " (if (zero? occ-in-edge)
                              "OCCUPATIONS ARE EDGE-LESS: 1016 isolated attribute-only nodes; the DWA/content-model taxonomy is a SEPARATE disconnected component"
                              "occupations have some edges"))))
      (finally (es/stop store)))))

(defn occ-attr-scan
  "Across ALL 1016 occupations: which attribute keys landed (= which junction
   sheets contributed, and as attributes not edges). Reveals which SOC->X sheets
   were extracted onto occupations vs absent entirely."
  [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})
        ctx {:event-store store :tenant-id tenant}]
    (try
      (let [concepts (cs/reduce-concepts ctx oid conj [])
            occ (filter #(= "occupation" (uri-scheme (:uri %))) concepts)
            key-freq (->> occ (mapcat (comp keys :attributes)) frequencies (sort-by val >))]
        (println "\n=== OCCUPATION attribute-key distribution across" (count occ) "occupations ===")
        (doseq [[k n] key-freq]
          (println (format "    %4d/%d occ carry  %s" n (count occ) (pr-str k))))
        (println "\n  (each key = a junction/property sheet that landed on occupations AS AN ATTRIBUTE, not an edge)")
        (println "  distinct kinds in the whole graph (any skill/knowledge/task NODES?):"
                 (pr-str (->> concepts (map (comp uri-scheme :uri)) distinct sort vec))))
      (finally (es/stop store)))))

(defn probe-sheet-events
  "Understand the :sheet/* event shape (across ALL tenants) + surface container
   selection / per-container extraction outcomes. Robust to event shape."
  [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})]
    (try
      (doseq [t [:sheet/execution-value-written :sheet/key-declared]]
        (let [evs (into [] (es/read store {:types [t]}))]
          (println "\n=== type" t " count=" (count evs) "===")
          (when-let [e (first evs)]
            (println "  shape:" (type e) " keys:" (pr-str (when (map? e) (keys e)))))
          ;; dump compact string of first few, guarded
          (doseq [e (take 8 evs)]
            (let [s (try (pr-str e) (catch Throwable _ "<unprintable>"))]
              (println "  •" (subs s 0 (min 260 (count s))))))))
      (finally (es/stop store)))))

(defn selection-scan
  "Read the discovery :sheet/execution-value-written events (WITH tenant) and
   surface: the container SURVEY (sheets found), the SELECTION (chosen), and
   per-container extraction outcomes — esp. junction sheets that yielded 0."
  [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})]
    (try
      (let [evs (into [] (es/read store {:tenant-id tenant :types [:sheet/execution-value-written]}))]
        (println "\n=== :sheet/execution-value-written events:" (count evs) "(tenant-scoped) ===")
        (when-let [e (first evs)] (println "  event keys:" (pr-str (keys e))))
        (doseq [e evs]
          (let [v (get e :value (get e :sheet/value e))
                s (try (pr-str v) (catch Throwable _ ""))]
            (when (re-find #"(?i)container|select|survey|coverage|Skills|Knowledge|Work Activities|Task Statements|entity-type|grain|drafts|concept" s)
              (let [nm (or (:node-name e) (:sheet/node-name e) (:name e) "?")]
                (println "\n---- node:" nm " (val" (count s) "chars) ----")
                (println (subs s 0 (min 900 (count s))))))))
        (println "=== end selection-scan ==="))
      (finally (es/stop store)))))

(defn selection-scan2
  "Correct es/read args (with :limit). Surface discovery outputs re container
   selection + per-container extraction outcomes (esp junction sheets → 0)."
  [& _]
  (let [store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})]
    (try
      (let [evs (into [] (es/read store {:tenant-id tenant
                                         :types [:sheet/execution-value-written]
                                         :limit 100000}))]
        (println "\n=== execution-value events:" (count evs) "===")
        (when-let [e (first evs)] (println "  keys:" (pr-str (keys e))))
        (doseq [e evs]
          (let [v (:value e)
                s (try (pr-str v) (catch Throwable _ ""))]
            (when (re-find #"(?i)Skills|Knowledge|Work Activities|Task Statements|selected|container|coverage|entity-type|grain-strategy|:drafts|concept-drafts|rows-ok|0 concepts|truncated" s)
              (println "\n---- node:" (pr-str (:node-name e)) " key:" (pr-str (:key e)) " (" (count s) "chars) ----")
              (println (subs s 0 (min 1000 (count s)))))))
        (println "\n=== ALL execution-value node-names + value-sizes (the full picture) ===")
        (doseq [e evs]
          (println (format "  %-28s key=%-22s val=%s chars"
                           (pr-str (:node-name e)) (pr-str (:key e))
                           (count (try (pr-str (:value e)) (catch Throwable _ "")))))))
      (finally (es/stop store)))))
