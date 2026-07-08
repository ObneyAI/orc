(ns ai.obney.orc.ontology.skeleton-auto-embed-perf-test
  "PERF — the SKELETON's `auto-embed!` (deterministic_skeleton) is a SECOND embed
   path, distinct from `embed+index!`. The full O*NET build exercises it and it hit
   the SAME walls embed+index! had: single-item DJL inference + the O(n²)
   `get-concept-by-uri` projection (once per concept). This locks the fix — auto-embed!
   computes vectors ONCE (batched) and lands each via the command with the concept's
   identity metadata (:concept-id/:ontology-id/:scope) + precomputed :embedding, so the
   command neither re-embeds NOR projects per concept. DJL-FREE via an injected batch
   capability (runs on the fast gate)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as ds]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/auto-embed-perf-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "t"}))]
    {:event-store store :cache cache :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps ::dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)] (try ~@body (finally (stop-ctx ~sym)))))

(def ^:private concepts
  [{:uri "occupation/1" :label "Occupation One" :description "Does the first kind of work."}
   {:uri "occupation/2" :label "Occupation Two" :description "Does the second kind of work."}
   {:uri "occupation/3" :label "Occupation Three" :description "Does the third kind of work."}])

(deftest auto-embed-single-pass-with-metadata-test
  (testing "auto-embed! computes vectors ONCE via the injected batch and lands them
            through the command with identity metadata; landed vectors ARE the batch's
            output (no re-embed, no per-concept projection)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (ontology/compile-discovery-source!
               ctx oid {:status :emitted-drafts
                        :emitted-concepts concepts :emitted-relationships []})
            projected (vec (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {})))
            sentinels (into {} (map (fn [c] [(:uri c) (vec (repeatedly 384 #(double (rand))))]))
                            projected)
            calls (atom 0)
            fake-batch (fn [embeddable _opts]
                         (swap! calls inc)
                         {:embedded-count (count embeddable)
                          :embeddings (mapv (fn [c] {:uri (:uri c)
                                                     :embedding (get sentinels (:uri c))
                                                     :text-embedded (str "t:" (:uri c))})
                                            embeddable)})
            r (#'ds/auto-embed! ctx oid projected :heuristic fake-batch)
            embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})]
        (is (= 3 (count projected)) "precondition: three concepts landed with :id")
        (is (every? :id projected) "projected concepts carry :id (the metadata path)")
        (is (= 1 @calls) "the batched compute ran EXACTLY ONCE (single pass)")
        (is (= 3 (:embedded-count r)) "all three embedded")
        (doseq [c projected]
          (is (= (get sentinels (:uri c)) (:embedding (get embs (:uri c))))
              (str "landed vector for " (:uri c)
                   " is the batch's precomputed vector — no re-embed")))))))
