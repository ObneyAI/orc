(ns ai.obney.orc.demos.pi-agent-loop.system
  "Disposable real Grain/ORC system for the runnable demo."
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as todo]
            [ai.obney.orc.orc-service.interface]))

(defn start! []
  (let [event-pubsub (pubsub/start {:type :core-async :topic-fn :event/type})
        store (event-store/start {:conn {:type :in-memory} :event-pubsub event-pubsub :logger nil})
        directory (.toFile (java.nio.file.Files/createTempDirectory
                            "orc-pi-loop-" (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (.getAbsolutePath directory)
                                               :db-name "projections"}))
        tenant-id (random-uuid)
        context {:tenant-id tenant-id :event-store store :event-pubsub event-pubsub
                 :cache cache :llm-provider :openrouter}
        poller (todo/start-tenant-poller {:event-store store :tenant-ids #{tenant-id}
                                          :context context :poll-interval-ms 10})]
    {:context context :poller poller :cache cache :event-store store
     :event-pubsub event-pubsub :directory directory}))

(defn stop! [{:keys [poller cache event-store event-pubsub directory]}]
  (when poller (todo/stop-tenant-poller poller))
  (when cache (kv/stop cache))
  (when event-store (event-store/stop event-store))
  (when event-pubsub (pubsub/stop event-pubsub))
  (when (and directory (.exists directory))
    (doseq [file (reverse (file-seq directory))] (.delete file))))
