(ns onet-selection-repro
  "Reproduce the container SELECTION reconciliation for the killed O*NET build:
   feed the REAL survey candidates (O*NET sheet names) + the REAL coverage map
   (read from the build's store) into csel/select-containers, and observe whether
   :selected comes out in RELEVANCE order (Task Statements/Skills/Knowledge first)
   or collapses to ALPHABETICAL (the disconnection cause). Read-only."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.ontology.core.container-select :as csel]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [clojure.string :as str]))

(def db-file "/tmp/eb12-graph-b-ad57ff55-ec3c-4b0e-b969-03f5366f802c-events.db")
(def tenant #uuid "d3350b96-d190-4fdf-8db0-2fc70eb40911")
(def onet-dir "/Users/darylroberts/Downloads/db_30_1_excel")

(defn- plain-keys
  "THE CURRENT (broken) normalizer: (keyword (name k)). No-op on `::name` keys
   (name = \":name\", still colon-prefixed)."
  [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [(if (or (keyword? k) (string? k)) (keyword (name k)) k) v])) m)
    m))

(defn- fixed-keys
  "THE FIX: strip a leading colon from the key NAME before keywordizing, so the
   JSON-round-tripped `::name` (name \":name\") → `:name`."
  [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [(keyword (str/replace (name k) #"^:" "")) v])) m)
    m))

(defn -main [& _]
  ;; 1. REAL candidates from the O*NET excel-dir container contract (sorted by file name).
  (let [contract (#'dt/contract-for {:type :excel :path onet-dir})
        containers (vec ((:list-containers contract)))
        cand-names (mapv :name containers)
        ;; 2. REAL coverage map from the build's store.
        store (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})
        cov (try
              (->> (into [] (es/read store {:tenant-id tenant :types #{:sheet/execution-value-written} :limit 100000}))
                   (filter #(= :container-coverage (:key %)))
                   first :value)
              (finally (es/stop store)))
        cov-plain (mapv plain-keys cov)
        cov-fixed (mapv fixed-keys cov)
        cov-names (mapv :name cov-plain)]
    (println "\n===== CONTAINER SELECTION REPRO =====")
    (println "BROKEN plain-keys → :name of first entry:" (pr-str (:name (first cov-plain))))
    (println "FIXED  colon-strip → :name of first entry:" (pr-str (:name (first cov-fixed))))
    (println "CANDIDATES (" (count cand-names) ", survey/list order):")
    (doseq [n cand-names] (println "   " (pr-str n)))
    (println "\nCOVERAGE-MAP names (" (count cov-names) ", relevance-ranked):")
    (doseq [e cov-plain] (println (format "    %-34s serves-cqs=%s relevance=%s" (pr-str (:name e)) (:serves-cqs e) (:relevance e))))
    (println "\nNAME RECONCILIATION (exact match — the select-containers filter):")
    (let [cand-set (set cand-names)]
      (doseq [n cov-names]
        (println (format "    %-34s %s" (pr-str n) (if (cand-set n) "MATCH ✓" "NO-MATCH ✗ (ranking DROPPED)")))))
    ;; 3. Run the REAL select-containers with BROKEN vs FIXED coverage.
    (let [candidates (mapv #(assoc % :keep? true) containers)
          run (fn [cov*] (mapv :name (:selected (csel/select-containers candidates
                                                 {:goal "connect occupations to skills/tasks"
                                                  :cqs [0 1 2 3 4] :cap 25 :rank-fn (fn [_ _] cov*)}))))
          broken-order (run cov-plain)
          fixed-order (run cov-fixed)]
      (println "\n:selected first-8 with BROKEN plain-keys (what actually ran):")
      (doseq [n (take 8 broken-order)] (println "    " (pr-str n)))
      (println "\n:selected first-8 with the FIXED colon-strip:")
      (doseq [n (take 8 fixed-order)] (println "    " (pr-str n)))
      (println "\nVERDICT:")
      (println "  BROKEN first-6 == alphabetical first-6? "
               (= (take 6 broken-order) (take 6 (sort cand-names))) "(alphabetical = ranking dropped)")
      (println "  FIXED surfaces connecting sheets in top-8? "
               (boolean (some #{"Task Statements" "Skills" "Knowledge"} (take 8 fixed-order)))))))
