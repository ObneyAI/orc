(ns ai.obney.orc.orc-dev.core
  "Minimal Integrant system for ORC development.

   No web server, no auth, no external services. Just:
   - Event store (in-memory)
   - LMDB cache
   - Control plane (pull-based todo processors)
   - Periodic task triggers
   - All ORC component registrations

   Usage:
     (def service (start))
     (def ctx (::context service))
     (stop service)"
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as ps]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.periodic-task.interface :as pt]

            ;; ORC components (side-effect registration)
            [ai.obney.orc.orc-service.interface]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.gepa.interface]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.colbert.interface]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.mcp-sheet-builder.interface]
            [ai.obney.orc.mcp-sheet-builder.interface.schemas]
            [ai.obney.orc.langfuse.interface]

            [integrant.core :as ig]
            [config.core :refer [env]]))

;; =============================================================================
;; Integrant System
;; =============================================================================

(def system-tenant-id #uuid "00000000-0000-0000-0000-000000000000")

(def system
  {::logger {}

   ::event-store {:logger (ig/ref ::logger)
                  :event-pubsub (ig/ref ::event-pubsub)
                  :conn {:type :in-memory}}

   ::event-pubsub {:type :core-async
                   :topic-fn :event/type}

   ::cache {:storage-dir "storage/cache"
            :db-name "read-model-cache"
            :map-size (* 1024 1024 512)}

   ::control-plane {:event-store (ig/ref ::event-store)
                    :cache (ig/ref ::cache)
                    :context (ig/ref ::context)}

   ::periodic-tasks {:context (ig/ref ::context)}

   ::context {:tenant-id system-tenant-id
              :system-tenant-id system-tenant-id
              :event-store (ig/ref ::event-store)
              :cache (ig/ref ::cache)
              :event-pubsub (ig/ref ::event-pubsub)
              :command-registry (cp/global-command-registry)
              :query-registry (qp/global-query-registry)
              :dscloj-provider (when-let [p (:dscloj-provider env)]
                                 (keyword p))}})

;; =============================================================================
;; Integrant Lifecycle
;; =============================================================================

(defmethod ig/init-key ::logger [_ _]
  nil)

(defmethod ig/halt-key! ::logger [_ _]
  nil)

(defmethod ig/init-key ::event-store [_ config]
  (es/start config))

(defmethod ig/halt-key! ::event-store [_ event-store]
  (es/stop event-store))

(defmethod ig/init-key ::event-pubsub [_ config]
  (ps/start config))

(defmethod ig/halt-key! ::event-pubsub [_ event-pubsub]
  (ps/stop event-pubsub))

(defmethod ig/init-key ::cache [_ {:keys [storage-dir db-name map-size]}]
  (let [dir (clojure.java.io/file storage-dir)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f))))
  (rmp/l1-clear!)
  (kv/start (lmdb/->KV-Store-LMDB (cond-> {:storage-dir storage-dir :db-name db-name}
                                     map-size (assoc :map-size map-size)))))

(defmethod ig/halt-key! ::cache [_ cache]
  (kv/stop cache))

(defmethod ig/init-key ::control-plane [_ {:keys [event-store cache context]}]
  (control-plane/start {:event-store event-store
                        :cache cache
                        :context context}))

(defmethod ig/halt-key! ::control-plane [_ cp]
  (control-plane/stop cp))

(defmethod ig/init-key ::periodic-tasks [_ {:keys [context]}]
  (let [event-store (:event-store context)]
    (pt/start-periodic-triggers!
     {:append-fn (fn [args] (es/append event-store args))
      :tenant-ids-fn (fn [] (set (keys (es/tenants event-store))))})))

(defmethod ig/halt-key! ::periodic-tasks [_ triggers]
  (pt/stop-periodic-triggers! triggers))

(defmethod ig/init-key ::context [_ context]
  context)

;; =============================================================================
;; Public API
;; =============================================================================

(defn start
  "Start the ORC development system. Returns the Integrant system map.

   Optional opts (recognized at startup so todo processors get them):
     :call-tool-fn  fn   — installed on ::context for repl-researcher MCP
                            tool dispatch (e.g. doc-skills/call-tool-fn).
                            Without this, RLM-mode workflows that declare
                            :mcp-tools have NO bindings in their SCI
                            sandbox and fail with 'Could not resolve
                            symbol' on every tool call.
   "
  ([] (start {}))
  ([{:keys [call-tool-fn] :as _opts}]
   (let [cfg (cond-> system
               call-tool-fn (assoc-in [::context :call-tool-fn] call-tool-fn))]
     (ig/init cfg))))

(defn stop
  "Stop the ORC development system."
  [system]
  (ig/halt! system))
