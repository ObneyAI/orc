(ns v15-live-verify
  "V15 live verify — hybrid-search label enrichment over a REAL event-sourced graph.

   Builds a small graph via the PUBLIC :ontology/create-concept command path
   against a real in-memory Grain event store + LMDB cache (mirrors the ctx
   setup in development/src/v02_mode_a.clj), embeds every concept with REAL
   local DJL all-MiniLM-L6-v2 embeddings (NO API key needed), then runs
   hybrid-search and prints the verbatim result maps. Pre-V15 these came back
   :label nil for graph/embedding-reached hits; this proves they now carry
   the projected label/description.

   USAGE (REPL with :dev alias, NO OPENROUTER key required):
     (require '[v15-live-verify :as v]) (v/run!)"
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [clojure.pprint :as pp]))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/v15-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "test"
                          :map-size (* 256 1024 1024)}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defn- create-concept! [ctx ontology-id uri label description]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/create-concept
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :uri uri
           :label label
           :description description
           :scope :custom
           :broader []
           :indicators []})))

(defn- create-rel! [ctx ontology-id src pred tgt]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/create-relationship
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :source-uri src
           :target-uri tgt
           :predicate pred
           :confidence-class :extracted
           :properties {}})))

(defn- embed! [ctx uri]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/embed-concept
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :uri uri
           :fields #{:label :description}})))

(defn run! []
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (println "=== V15 LIVE VERIFY — label enrichment over a REAL event-sourced graph ===")
      (println "ontology-id:" oid)
      ;; A small REAL event-sourced graph via PUBLIC commands.
      (let [concepts [["concept:dir/ava"   "Ava DuVernay"        "An acclaimed American film director and producer"]
                      ["concept:dir/spike" "Spike Lee"           "A pioneering American film director"]
                      ["concept:film/selma" "Selma"              "A 2014 historical drama film about the voting-rights marches"]
                      ["concept:film/do-the-right-thing" "Do the Right Thing" "A 1989 comedy-drama film set in Brooklyn"]
                      ["concept:role/director" "Film Director"    "The person who directs the making of a film"]]]
        (doseq [[uri label desc] concepts]
          (create-concept! ctx oid uri label desc))
        (create-rel! ctx oid "concept:dir/ava" "directed" "concept:film/selma")
        (create-rel! ctx oid "concept:dir/spike" "directed" "concept:film/do-the-right-thing")
        (Thread/sleep 300)
        (println "\nconcepts created (projected):"
                 (count (rm/get-concepts ctx {:ontology-id oid})))
        ;; Real local embeddings on every concept (no API key).
        (doseq [[uri _ _] concepts] (embed! ctx uri))
        (Thread/sleep 300)
        (println "concepts embedded (real DJL all-MiniLM-L6-v2):"
                 (count (rm/get-all-concept-embeddings ctx {:ontology-id oid})))
        ;; Probes: each crafted so the TOP hits include graph/embedding-reached
        ;; concepts (NOT just lexical matches) — those are the ones that used
        ;; to come back :label nil.
        (println "\n=== VERBATIM hybrid-search result maps ===")
        (doseq [q ["acclaimed film director" "voting rights drama" "Brooklyn comedy"]]
          (println "\n--- query:" (pr-str q) "---")
          (let [r (ontology/hybrid-search ctx {:query-text q
                                               :ontology-ids [oid]
                                               :limit 4})]
            (doseq [hit (:results r)]
              (pp/pprint (select-keys hit [:uri :label :description :scope
                                           :graph-rank :embedding-rank
                                           :lexical-rank :score])))))
        (println "\n=== nil-label audit across all probes ==="))
      (let [all-hits (mapcat (fn [q]
                               (:results (ontology/hybrid-search ctx {:query-text q
                                                                      :ontology-ids [oid]
                                                                      :limit 4})))
                             ["acclaimed film director" "voting rights drama" "Brooklyn comedy"])
            nil-labels (filter (comp nil? :label) all-hits)]
        (println "total hits across probes:" (count all-hits))
        (println "hits with :label nil:" (count nil-labels))
        (println (if (zero? (count nil-labels))
                   ">>> PASS: every hit carries a non-nil label"
                   (str ">>> FAIL: " (count nil-labels) " hit(s) still :label nil"))))
      (finally (stop-ctx ctx)))))
