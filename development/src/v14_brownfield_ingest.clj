(ns v14-brownfield-ingest
  "V14 live verify — REAL ingest-ttl! over the production BRYC TTL.

   Runs the SHIPPED (V14-fixed) `ingest-ttl!` path over the 45 MB
   production TTL, then reads the event-sourced projection back to prove
   the domain-class individuals landed as concepts + relationships in the
   graph (not just in the returned report). NO OpenRouter key, NO
   embedding — rdflib parse + local Grain commands + projection read only.

   USAGE (REPL with :dev alias):
     (require '[v14-brownfield-ingest :as v])
     (v/run!)"
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.ttl-ingest :as ttli]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.pprint :as pp]))

(def ttl-path
  "/Users/darylroberts/Desktop/Code/area_51/ontology_exploration/output/louisiana_programs_full.ttl")

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        dir (str "/tmp/v14-brownfield-" (random-uuid))
        ;; Real-sized graph: bump LMDB map-size well past the 10 MB default.
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "test"
                          :map-size (* 4 1024 1024 1024)}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defn run!
  []
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (println "=== V14 LIVE VERIFY — real ingest-ttl! over production BRYC TTL ===")
      (let [ttl (slurp ttl-path)
            _ (println "TTL bytes:" (count ttl))
            t0 (System/currentTimeMillis)
            report (ttli/ingest-ttl! ctx ttl {:ontology-id oid})
            t1 (System/currentTimeMillis)]
        (println "\n>>> ingest-ttl! REPORT (verbatim):")
        (pp/pprint (dissoc report :commands))
        (println "ingest wall-ms:" (- t1 t0))
        ;; PROJECTION READ-BACK — prove concepts/relationships landed in
        ;; the event-sourced graph, not just in the report.
        (rmp/l1-clear!)
        (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
              rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))
              ;; Concept-kind tally from the URI namespace prefix.
              by-ns (frequencies
                     (map (fn [c]
                            (let [u (str (:uri c))]
                              (cond
                                (clojure.string/includes? u "education#") :edu
                                (clojure.string/includes? u "cip#") :cip
                                (clojure.string/includes? u "onet#") :onet
                                (clojure.string/includes? u "soc#") :soc
                                :else :other)))
                          concepts))]
          (println "\n>>> PROJECTION READ-BACK:")
          (println "projected concepts:" (count concepts))
          (println "projected relationships:" (count rels))
          (println "concept URIs by namespace:" (pr-str by-ns))
          (println "sample concept:")
          (pp/pprint (select-keys (first concepts) [:uri :label :scope :ontology-id]))
          (println "sample relationship:")
          (pp/pprint (select-keys (first rels) [:source-uri :target-uri :predicate]))
          {:report (dissoc report :commands)
           :projected-concepts (count concepts)
           :projected-relationships (count rels)
           :by-ns by-ns}))
      (finally (stop-ctx ctx)))))
