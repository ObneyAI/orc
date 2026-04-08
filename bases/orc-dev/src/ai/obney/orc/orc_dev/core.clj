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

            [dscloj.core :as dscloj]
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

   ;; Note: command-registry and query-registry are added at init-key time
   ;; to ensure they pick up any commands registered after system def
   ::context {:tenant-id system-tenant-id
              :system-tenant-id system-tenant-id
              :event-store (ig/ref ::event-store)
              :cache (ig/ref ::cache)
              :event-pubsub (ig/ref ::event-pubsub)
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
      :tenant-ids-fn (fn [] (es/tenant-ids event-store))})))

(defmethod ig/halt-key! ::periodic-tasks [_ triggers]
  (pt/stop-periodic-triggers! triggers))

;; Dynamic MCP registry for runtime MCP connection management
(defonce ^{:doc "Atom holding the current MCP call-tool-fn.
                 Set via (set-mcp-call-tool-fn! fn) after connecting to MCP servers."}
  mcp-call-tool-fn*
  (atom nil))

(defn set-mcp-call-tool-fn!
  "Set the MCP call-tool-fn for repl-researcher nodes.

   Usage:
     (require '[ai.obney.orc.mcp-sheet-builder.interface :as mcp])
     (def pw-conn (mcp/connect {:type :http :url \"http://localhost:3001\"}))
     (set-mcp-call-tool-fn! (partial mcp/call-tool pw-conn))"
  [call-tool-fn]
  (reset! mcp-call-tool-fn* call-tool-fn))

(defn dynamic-call-tool-fn
  "A call-tool-fn that dynamically resolves from the mcp-call-tool-fn* atom.
   This allows MCP to be configured after the system starts."
  [tool-name args]
  (if-let [f @mcp-call-tool-fn*]
    (f tool-name args)
    (throw (ex-info "No MCP connection configured. Call (set-mcp-call-tool-fn! ...) first."
                    {:tool-name tool-name :args args}))))

(defmethod ig/init-key ::context [_ context]
  ;; Add registries at init time to pick up any commands/queries
  ;; registered after the system def was evaluated.
  ;; Include a dynamic call-tool-fn that resolves at call time from the atom.
  (assoc context
    :command-registry (cp/global-command-registry)
    :query-registry (qp/global-query-registry)
    :call-tool-fn dynamic-call-tool-fn))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start
  "Start the ORC development system. Returns the Integrant system map."
  []
  ;; Initialize DSCloj providers from environment variables
  ;; (OPENROUTER_API_KEY, ANTHROPIC_API_KEY, OPENAI_API_KEY)
  (dscloj/quick-setup!)
  (ig/init system))

(defn stop
  "Stop the ORC development system."
  [system]
  (ig/halt! system))
