(ns connect3d-node-origin
  "CONNECT-3d follow-up — identify the ORIGIN of the residual case-variant
   `Occupation/*` (capital) concept nodes that coexist with the canonical
   `occupation/*` nodes after the edge reconciliation. Dumps, for a few variant
   nodes and their canonical SOC-twin, the :label/:description/:attributes so we
   can tell a BARE identity draft (my association key-draft, degraded) from a RICH
   per-row occupation draft (a different sheet's un-canonicalized mint).

   USAGE: clj -M:dev:test -m connect3d-node-origin <db-file> <tenant-uuid> <oid-uuid>"
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.read-models]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn- scheme [uri] (-> (str uri) (str/split #"[:/]" 2) first))
(defn- tail   [uri] (let [s (str uri) i (str/index-of s "/")] (if i (subs s (inc i)) s)))

(defn -main [& [db-file tenant-str oid-str]]
  (let [tenant (java.util.UUID/fromString tenant-str)
        oid    (java.util.UUID/fromString oid-str)
        store  (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})
        cache-dir (str "/tmp/c3d-origin-" (random-uuid))
        cache  (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "c3d"
                                                :map-size (* 256 1024 1024)}))
        ctx {:event-store store :tenant-id tenant :cache cache}]
    (rmp/l1-clear!)
    (try
      (let [concepts (cs/reduce-concepts ctx oid conj [])
            by-uri  (into {} (map (juxt :uri identity)) concepts)
            variant (filter #(= "Occupation" (scheme (:uri %))) concepts)
            canon   (filter #(= "occupation" (scheme (:uri %))) concepts)
            summarize (fn [c]
                        {:uri (:uri c)
                         :label (:label c)
                         :desc? (boolean (seq (str (:description c))))
                         :desc-sample (some-> (:description c) str (subs 0 (min 60 (count (str (:description c))))))
                         :attr-keys (vec (keys (:attributes c)))})]
        (println "\n===== CONNECT-3d NODE ORIGIN =====")
        (println "oid:" oid " (verify vs event_tags upstream)")
        (println "canonical occupation/* nodes:" (count canon)
                 "  variant Occupation/* nodes:" (count variant))
        (println "\n--- variant Occupation/* : has-description? distribution ---")
        (pp/pprint (frequencies (map #(boolean (seq (str (:description %)))) variant)))
        (println "\n--- variant Occupation/* : attribute-key-set distribution (top 8) ---")
        (doseq [[ks n] (->> variant (map #(vec (sort (map str (keys (:attributes %)))))) frequencies (sort-by val >) (take 8))]
          (println (format "  %4d  %s" n (pr-str ks))))
        (println "\n--- 5 sample variant Occupation/* nodes ---")
        (doseq [c (take 5 variant)] (pp/pprint (summarize c)))
        (println "\n--- their canonical occupation/<same-SOC> TWINS ---")
        (doseq [c (take 5 variant)]
          (let [twin (get by-uri (str "occupation/" (tail (:uri c))))]
            (pp/pprint (if twin (summarize twin) {:twin-missing (str "occupation/" (tail (:uri c)))})))))
      (finally (es/stop store) (kv/stop cache)
               (let [d (java.io.File. cache-dir)]
                 (when (.exists d) (doseq [f (.listFiles d)] (.delete f)) (.delete d)))))))
