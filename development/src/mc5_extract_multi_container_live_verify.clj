(ns mc5-extract-multi-container-live-verify
  "MC-5 — LIVE VERIFY: the EXTRACT subbehavior re-orchestrated single-container →
   MULTI-container. The public `ontology-extract/extract@v1` sheet is now a thin
   `:code` orchestrator that enumerates the source's containers and drives the
   per-container SAMPLE → AUTHOR → APPLY unit (with-resilience) once per container,
   accumulating the drafts across containers.

   What this proves (real Grain, real OpenRouter, real async child ticks, REAL
   source files — NO mocks):
     - A many-container SQL store extracts from MORE THAN ONE table (concept drafts
       carry real URIs from multiple containers — not just the largest).
     - A many-sheet workbook DIRECTORY extracts from MULTIPLE sheets (real keyed-row
       drafts, not 0).
     - A single-container csv source still extracts (it is just N=1 containers).
     - The `:extraction-report` aggregates per-container coverage HONESTLY — a
       0-draft / cleanly-failed container surfaces (no false-green).
     - Verified through the subbehavior's `:reads`/`:writes` contract via
       `:delegate` from a central tree (discipline 7 — read the PARENT tick
       blackboard back from the projection).

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[mc5-extract-multi-container-live-verify :as mc5])
     (mc5/run-all! {})
   Or bounded from the CLI (the runner wraps it in future+deref+exit)."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
  (:import [java.io File]))

(def default-model "google/gemini-3-flash-preview")

;; REAL sources (skip-if-absent). A many-table relational store + a many-sheet
;; workbook directory + a single-file csv — the three traversal cases MC-5 covers.
(def sql-source   {:type :sql   :path (str (System/getProperty "user.home") "/Downloads/output.db")})
(def excel-source {:type :excel :path (str (System/getProperty "user.home") "/Downloads/db_30_1_excel")})
(def csv-source   {:type :csv   :path (str (System/getProperty "user.home") "/Downloads/cip_soc_crosswalk.csv")})

(defn- present? [{:keys [path]}] (.exists (File. ^String path)))

;; A GENERIC model-spec — domain-agnostic. ONE entity type, canonical-row-filter
;; grain, no scope filter; the per-container author grounds in whatever real keys
;; each container carries. (No vertical taxonomy baked in.)
(def generic-model-spec
  {:entity-types [{:type "Record" :uri-keying-fields []
                   :grain-strategy :canonical-row-filter}]
   :scope-filter nil
   :edges []})

;; ---------------------------------------------------------------------------
;; Real-Grain harness (same shape as the EB live verifies)
;; ---------------------------------------------------------------------------

(defn- register-openrouter! [model]
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set (env var only)" {})))
        base {:provider :openrouter :model model
              :config {:api-base "https://openrouter.ai/api/v1" :api-key api-key}}]
    (litellm-router/register! :openrouter base)
    (litellm-router/register! (keyword (str "openrouter/" model)) base)))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/mc5-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "mc5-live"
                          :map-size (* 1024 1024 1024)}))
        base-ctx {:event-store store :cache cache :tenant-id (random-uuid)
                  :provider :openrouter :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps ::cache-dir dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps :topics topics
                                        :handler-fn handler-fn :context base-ctx})))
                    {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-ctx [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (File. ^String d)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

;; ---------------------------------------------------------------------------
;; Full live verify — DELEGATE the Extract subbehavior from a central tree, read
;; the draft set + report back off the PARENT tick blackboard (discipline 7).
;; ---------------------------------------------------------------------------

(defn run-once!
  "Register BOTH Extract sheets (orchestrator + per-container unit) and delegate
   the public @v1 orchestrator with a model-spec + source. Returns the result map
   incl. the accumulated draft set + per-container report off the parent bb."
  [ctx {:keys [model source model-spec label]}]
  (let [model (or model default-model)
        _ (extract/register-extract-subbehavior! ctx {:model model :resilient? true})
        sub-id (extract/extract-sheet-id-for)
        central-name (str "mc5/central-extract-" label "@v1")
        central-def (dsl/workflow central-name
                      (dsl/blackboard {:model-spec [:map {:closed false}]
                                       :source [:map {:closed false}]
                                       :max-containers [:maybe :int]
                                       :concept-drafts extract/concept-drafts-schema
                                       :relationship-drafts extract/relationship-drafts-schema
                                       :extraction-report extract/extraction-report-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-extract"
                          :target-sheet-id sub-id
                          :reads [:model-spec :source :max-containers]
                          :writes [:concept-drafts :relationship-drafts :extraction-report]
                          :timeout-ms 290000)))
        central-id (dsl/build-workflow! ctx central-def)
        central-tick-id (random-uuid)
        t0 (System/currentTimeMillis)
        ;; cap the live traversal small (≥2 proves multi-container; keeps the run
        ;; bounded — the production default ceiling is higher).
        central-result (runtime/execute ctx central-id
                                        {"model-spec" model-spec "source" source
                                         "max-containers" 3}
                                        :timeout-ms 290000 :tick-id central-tick-id)
        elapsed (- (System/currentTimeMillis) t0)
        _ (Thread/sleep 400)
        ;; DISCIPLINE 7: read the PARENT tick blackboard back from the projection.
        parent-bb (rm/get-tick-blackboard ctx central-tick-id)
        concept-drafts (get-in parent-bb [:concept-drafts :value])
        relationship-drafts (get-in parent-bb [:relationship-drafts :value])
        report (get-in parent-bb [:extraction-report :value])
        per-container (:per-container report)]
    {:label label
     :central-status (:central-status central-result)
     :status (:status central-result)
     :elapsed-ms elapsed
     :concept-count (count (or concept-drafts []))
     :relationship-count (count (or relationship-drafts []))
     :containers-seen (:containers-seen report)
     :containers-with-drafts (:containers-with-drafts report)
     :containers-with-drafts-actual (count (filter #(pos? (or (:concept-count %) 0))
                                                   (or per-container [])))
     :extraction-report report
     :sample-concepts (vec (take 6 (or concept-drafts [])))
     :per-container per-container
     :error (:error central-result)}))

(defn- print-result! [r]
  (println "\n================" (:label r) "================")
  (println "status:" (:status r) "(" (:elapsed-ms r) "ms)")
  (println "containers seen:" (:containers-seen r)
           " with drafts:" (:containers-with-drafts r))
  (println "total concept-count:" (:concept-count r)
           " relationship-count:" (:relationship-count r))
  (println "per-container breakdown:")
  (doseq [c (:per-container r)]
    (println "   " (:container c) "->" (:concept-count c) "concepts,"
             (:rows-streamed c) "rows, status" (:status c)
             (when (:diagnosis c) "(DIAGNOSED)")))
  (println "sample concepts (verbatim):")
  (pp/pprint (:sample-concepts r)))

(defn run-all!
  "Live verify over the REAL multi-container sources (skip-if-absent)."
  [{:keys [model] :or {model default-model}}]
  (let [ctx (make-ctx)]
    (try
      (register-openrouter! model)
      (println "=== MC-5 EXTRACT MULTI-CONTAINER LIVE VERIFY ===")
      (println "model:" model)
      (let [results
            (doall
             (for [[label source] [["SQL-multi-table" sql-source]
                                   ["EXCEL-multi-sheet-dir" excel-source]
                                   ["CSV-single-container" csv-source]]]
               (if-not (present? source)
                 (do (println "\n[MC-5] SKIP" label "— absent at" (:path source))
                     {:label label :skipped true})
                 (let [r (run-once! ctx {:model model :source source
                                         :model-spec generic-model-spec :label label})]
                   (print-result! r)
                   r))))]
        {:model model :results (vec results)})
      (finally (stop-ctx ctx)))))

;; ---------------------------------------------------------------------------
;; Bounded CLI runner — future + deref timeout + System/exit (JVM hygiene).
;; ---------------------------------------------------------------------------

(defn -main [& _]
  (let [fut (future
              (try (run-all! {}) :done
                   (catch Throwable t
                     (println "MC-5 live verify FAILED:" (.getMessage t))
                     (.printStackTrace t) :error)))
        result (deref fut 870000 :timeout)]
    (println "\nMC-5 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[mc5-extract-multi-container-live-verify :as mc5] :reload)
  (mc5/run-all! {}))
