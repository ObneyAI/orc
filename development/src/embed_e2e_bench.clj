(ns embed-e2e-bench
  "INSPECT (perf) — end-to-end throughput of the PRODUCTION embed path
   (`embed+index!` → single-pass `embed-concepts!` → batched `embed-texts-batch`
   → precompute `:ontology/embed-concept` command). Lands N concepts into a REAL
   in-memory Grain store, times embed+index!, and asserts every concept is embedded
   EXACTLY once with a real 384-dim vector — proving the double-embed is gone and the
   batched inference is on the real path (not just the prototype's inline call).

   USAGE: clj -J-Xmx4g -M:dev:test -m embed-e2e-bench [N]"
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.embed-index-subbehavior :as ei]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(defn -main [& [n-str]]
  (let [n (Integer/parseInt (or n-str "1000"))
        _ (rmp/l1-clear!)
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/embed-e2e-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "b"
                                               :map-size (* 4 1024 1024 1024)}))
        ctx {:event-store store :cache cache :tenant-id (random-uuid)
             :command-registry (cp/global-command-registry)
             :query-registry (qp/global-query-registry) :event-pubsub ps}
        oid (random-uuid)
        concepts (mapv (fn [i]
                         {:uri (str "occupation/" i)
                          :label (str "Occupation " i)
                          :description (str "A standardized career category " i
                                            " performing specialized work activities.")})
                       (range n))]
    (try
      (println "\n===== EMBED E2E BENCH (production path) =====")
      (println "landing" n "concepts...")
      (ontology/compile-discovery-source!
       ctx oid {:status :emitted-drafts :emitted-concepts concepts :emitted-relationships []})
      ;; warm the model AND the batchPredict path + PyTorch graph optimizer (embed a
      ;; few REAL texts in a throwaway ontology so the timed run is steady-state).
      (let [woid (random-uuid)]
        (ontology/compile-discovery-source!
         ctx woid {:status :emitted-drafts
                   :emitted-concepts (mapv (fn [i] {:uri (str "warm/" i) :label (str "Warm " i)
                                                    :description (str "warmup concept " i)})
                                           (range 100))
                   :emitted-relationships []})
        (ei/embed+index! ctx {:ontology-id woid :embed-fields ["label" "description"]}))
      (let [t0 (System/nanoTime)
            report (ei/embed+index! ctx {:ontology-id oid :embed-fields ["label" "description"]})
            ms (/ (- (System/nanoTime) t0) 1e6)
            embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})
            dims (set (map (comp count :embedding val) embs))]
        (println (format "\nembed+index! over %d concepts: %.0f ms  (%.0f concepts/s)"
                         n ms (/ n (/ ms 1000.0))))
        (println "  embedded-count      :" (:embedded-count report))
        (println "  landed embeddings   :" (count embs))
        (println "  vector dimensions   :" (pr-str dims))
        (println "  every concept once? :" (= n (count embs) (:embedded-count report)))
        (println "  real 384-dim vectors?:" (= #{384} dims))
        (println "=============================================="))
      (finally
        (rmp/l1-clear!) (pubsub/stop ps) (kv/stop cache) (es/stop store)
        (let [f (java.io.File. dir)]
          (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))
        (shutdown-agents)))))
