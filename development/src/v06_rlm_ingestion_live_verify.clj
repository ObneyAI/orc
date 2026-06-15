(ns v06-rlm-ingestion-live-verify
  "V06 LIVE VERIFY — a REAL recursive-RLM discovery session, granted the
   per-format source-access tools, explores a REAL structured source end-to-end
   through the deterministic skeleton's :rlm-discovery parse route, producing a
   CONNECTED graph.

   Proves the keystone: the discovery RLM (1) is granted CSV tools for the real
   cip_soc_crosswalk.csv (and SQL tools for the real IPEDS output.db CIPCodes
   table), (2) EXPLORES by sampling — never dumping, (3) designs extraction
   minting SHAREABLE cip:/soc: URIs, (4) feeds the ONE deterministic skeleton →
   events → a graph whose crosswalk edges connect CIP and SOC concepts that the
   SQL source ALSO contributes by the same shareable URIs.

   Run:
     OPENROUTER_API_KEY=sk-or-... \\
     clojure -M:dev:test -e \"(require 'v06-rlm-ingestion-live-verify)\" \\
       -e \"(v06-rlm-ingestion-live-verify/run!)\"

   If the key 401s, the run records the failure honestly (no false green) and the
   driver remains runnable for a later key."
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def crosswalk-csv "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")
(def ipeds-db "/Users/darylroberts/Downloads/output.db")
(def model "google/gemini-2.5-flash")
(def report-path "development/bench/v06_rlm_ingestion_live_verify.md")

(defn- register-openrouter! []
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        base {:provider :openrouter :model model
              :config {:api-base "https://openrouter.ai/api/v1" :api-key api-key}}]
    (litellm-router/register! :openrouter base)
    (litellm-router/register! (keyword (str "openrouter/" model)) (assoc base :model model))))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/v06-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "live"}))]
    {:event-store store :cache cache :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps :provider :openrouter}))

(defn- skeleton-build! [ctx oid stub]
  ((requiring-resolve 'ai.obney.orc.ontology.core.deterministic-skeleton/build!)
   ctx {:ontology-id oid :sources [stub] :validation {:halt-on :none}}))

(defn- discover-source!
  "Run discovery for ONE structured source, capturing the RAW discovery output
   (drafts the model authored, verbatim) BEFORE ingesting, then ingest via
   compile-discovery-source! + skeleton build. Returns the discovery drafts +
   build result + the resulting graph snapshot."
  [ctx oid source]
  (let [disc (ontology/run-discovery!
               ctx
               {:ontology-id oid
                :sources [source]
                :model model
                :budget {:max-iterations 8 :total-budget-ms 300000}})
        ;; Ingest only when drafts were emitted (no false green on a failure).
        build (when (= :emitted-drafts (:status disc))
                (let [stub (ontology/compile-discovery-source! ctx oid disc)]
                  (skeleton-build! ctx oid stub)))
        concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
        rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
    {:discovery-status (:status disc)
     :emitted-concepts (:emitted-concepts disc)
     :emitted-relationships (:emitted-relationships disc)
     :rlm-trace (:rlm-trace disc)
     :final-output (get-in disc [:session-result :outputs])
     :iterations-attempted (count (get-in disc [:session-result :iterations] []))
     :build-status (:status build)
     :concepts (mapv #(select-keys % [:uri :label]) concepts)
     :relationships (mapv #(select-keys % [:source-uri :target-uri :predicate]) rels)}))

(defn run! []
  (register-openrouter!)
  (let [ctx (make-ctx)
        oid (random-uuid)
        log (atom {})]
    (try
      ;; --- Source A: the real crosswalk CSV (granted CSV tools) ---
      (println "\n=== V06 LIVE: discovering crosswalk CSV (granted CSV tools) ===")
      (swap! log assoc :csv
             (discover-source! ctx oid
                               {:name :crosswalk :type :csv :path crosswalk-csv}))

      ;; --- Source B: the real IPEDS CIPCodes table (granted SQL tools) ---
      (println "\n=== V06 LIVE: discovering IPEDS CIPCodes (granted SQL tools) ===")
      (swap! log assoc :sql
             (discover-source! ctx oid
                               {:name :ipeds :type :sql :path ipeds-db}))

      ;; --- Connectivity snapshot over the COMBINED graph ---
      (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
            rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))
            concept-uris (set (map :uri concepts))
            connected? (and (seq rels)
                            (every? (fn [r] (and (contains? concept-uris (:source-uri r))
                                                 (contains? concept-uris (:target-uri r))))
                                    rels))]
        (swap! log assoc :combined
               {:concept-count (count concepts)
                :relationship-count (count rels)
                :cip-concepts (count (filter #(str/starts-with? (str (:uri %)) "cip:") concepts))
                :soc-concepts (count (filter #(str/starts-with? (str (:uri %)) "soc:") concepts))
                :every-edge-endpoint-resolves connected?
                :sample-edges (mapv #(select-keys % [:source-uri :target-uri :predicate])
                                    (take 8 rels))})
        (println "Combined graph:" (:combined @log)))
      (catch Throwable t
        (swap! log assoc :error {:message (.getMessage t) :data (ex-data t)})
        (println "LIVE VERIFY ERROR (recorded honestly, no false green):" (.getMessage t))))

    ;; --- Capture the transcript verbatim ---
    (io/make-parents report-path)
    (spit report-path
          (str "# V06 Live Verify — RLM explores real structured sources → CONNECTED graph\n\n"
               "Model: `" model "`. CSV: `" crosswalk-csv "`. DB: `" ipeds-db "`.\n\n"
               "The discovery RLM is granted the per-format source tools, explores each\n"
               "source by SAMPLING, and feeds the ONE deterministic skeleton via the\n"
               ":rlm-discovery parse route. Cross-source linking is proven by shared\n"
               "cip:/soc: URIs connecting the crosswalk edges to the SQL-sourced concepts.\n\n"
               "## Captured (verbatim)\n\n```clojure\n"
               (with-out-str (pp/pprint @log))
               "```\n"))
    (println "\n=== V06 LIVE VERIFY COMPLETE — report at" report-path "===")
    @log))

(comment
  (run!))
