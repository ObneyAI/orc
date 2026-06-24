(ns gc1-canonical-uri-live-verify
  "GC-1 — LIVE VERIFY: canonical URI minting (the keystone).

   What this proves with REAL Grain + REAL LLM (the AUTHOR) + REAL source files —
   the integration read-back the durable pure-canonicalizer cycles cannot:

     - The real Extract AUTHOR now emits `:entity-type` on every concept-draft, and
       the deterministic GC-1 post-step (`canonicalize-drafts`, wired into the MC-5
       orchestrator BEFORE MC-6 relating) re-derives each concept's canonical URI
       from (model-spec :entity-type + its :uri-keying-fields VALUES in :attributes)
       in ONE byte-identical format.

     - TWO real sources contribute the SAME real entity under DIFFERENT free-form
       URIs (the crosswalk CSV mints a Program of Study per CIP_Code; the IPEDS
       C2022_A SQL table mints a Program of Study per CIPCODE — same code system,
       different AUTHOR-chosen free-form URI). After GC-1 + EB5 reconcile, a CIP
       that appears in BOTH sources COLLAPSES to ONE concept (read back from the
       PROJECTION), and the crosswalk's `prepares_for` edge connects that program's
       canonical URI to an Occupation's canonical URI — so a program→occupation hop
       READS BACK off the graph.

   Reverting the GC-1 canonicalization step re-fragments this (the two same-CIP
   programs stay two disconnected nodes), so this verify goes RED with the fix
   reverted.

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[gc1-canonical-uri-live-verify :as gc1])
     (def r (gc1/run! {}))
     (gc1/print-summary! r)

   JVM hygiene: the CLI entry wraps the run in future + deref-timeout + System/exit
   so it can NEVER hang."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.evolutionary-commands]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as recon]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.set]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def default-model "google/gemini-3-flash-preview")

;; The two REAL sources. Both carry CIP codes in the SAME code system / format
;; (e.g. "01.0101"), but each AUTHOR mints its OWN free-form :uri for a program.
(def csv-source {:type :csv :path "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv"})
(def sql-source {:type :sql :path "/Users/darylroberts/Downloads/output.db"})

;; Model-spec for the CROSSWALK: a Program of Study keyed by CIP_Code, an Occupation
;; keyed by SOC_Code, a prepares_for edge. Scope to CIP family 01 (fast).
(def csv-model-spec
  {:entity-types
   [{:type "Program of Study"
     :uri-keying-fields ["CIP_Code"]
     :grain-strategy :canonical-row-filter}
    {:type "Occupation"
     :uri-keying-fields ["SOC_Code"]
     :grain-strategy :canonical-row-filter}]
   :scope-filter {:field "CIP_Code" :values ["01"]}
   :edges [{:source-type "Program of Study"
            :target-type "Occupation"
            :predicate "prepares_for"}]
   :embed-fields ["CIP_Title" "SOC_Title"]})

;; Model-spec for the IPEDS CIPCodes reference table: a Program of Study keyed by
;; CIPCode (the SAME entity-type NAME the crosswalk uses, but a DIFFERENT field NAME
;; — "CIPCode" here vs "CIP_Code" in the crosswalk — and a DIFFERENT free-form :uri
;; the SQL author will mint). GC-1 must re-derive BOTH to the SAME canonical URI
;; `programofstudy/<cip>` from the entity-type + its keying-field VALUE, so the same
;; CIP collapses across the two sources. CIPCodes is one ROW per detail CIP code
;; (PRIMARY KEY CIPCode), so there is no institution multiplicity — its detail CIP
;; codes (01.0101 …) are the SAME codes the crosswalk carries. Scope to CIP family 01.
(def sql-model-spec
  {:entity-types
   [{:type "Program of Study"
     :uri-keying-fields ["CIPCode"]
     :grain-strategy :canonical-row-filter}]
   :scope-filter {:field "CIPFamily" :values ["01"]}
   :edges []
   :embed-fields []})

;; The IPEDS reference table that holds one row per CIP detail code (selector).
(def sql-selector "CIPCodes")

;; ---------------------------------------------------------------------------
;; Real-Grain harness with real todo processors (embed/index run async)
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
        dir (str "/tmp/gc1-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "gc1-live"
                          :map-size (* 1024 1024 1024)}))
        base-ctx {:event-store store
                  :cache cache
                  :tenant-id (random-uuid)
                  :provider :openrouter
                  :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps
                  ::cache-dir dir}
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
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

;; ---------------------------------------------------------------------------
;; Run ONE source's real Extract (via the public sheet) → the drafts (read back
;; off the projection, discipline 7).
;; ---------------------------------------------------------------------------

(defn- extract-source! [ctx {:keys [model source model-spec]}]
  (let [model (or model default-model)
        sub-id (extract/register-extract-subbehavior! ctx {:model model})
        tick-id (random-uuid)
        result (runtime/execute ctx sub-id
                                {"model-spec" model-spec
                                 "source" source}
                                :timeout-ms 300000
                                :tick-id tick-id)
        _ (Thread/sleep 300)
        bb (orm/get-tick-blackboard ctx tick-id)
        concept-drafts (vec (get-in bb [:concept-drafts :value]))
        relationship-drafts (vec (get-in bb [:relationship-drafts :value]))
        report (get-in bb [:extraction-report :value])]
    {:status (:status result)
     :concept-drafts concept-drafts
     :relationship-drafts relationship-drafts
     :extraction-report report}))

;; ---------------------------------------------------------------------------
;; The full GC-1 read-back: extract BOTH sources, reconcile BOTH into ONE graph,
;; then read the PROJECTION for (a) a CIP merged across the two sources to ONE
;; concept and (b) the prepares_for program→occupation hop.
;; ---------------------------------------------------------------------------

(defn run! [{:keys [model]}]
  (let [model (or model default-model)
        _ (register-openrouter! model)
        ctx (make-ctx)]
    (try
      (let [oid (random-uuid)
            ;; 1. Real Extract on the CROSSWALK CSV (Program + Occupation + edge).
            csv-ex (extract-source! ctx {:model model :source csv-source
                                         :model-spec csv-model-spec})
            ;; 2. Real Extract on the IPEDS C2022_A SQL program table.
            sql-ex (extract-source! ctx {:model model
                                         :source (assoc sql-source :selector sql-selector)
                                         :model-spec sql-model-spec})
            ;; 3. Reconcile BOTH draft sets into the SAME ontology-id. Crosswalk
            ;;    first (it carries the edges), then the IPEDS programs — the
            ;;    same-CIP program must COLLAPSE onto the crosswalk's program
            ;;    (reconcile-not-duplicate, merge by canonical URI).
            _ (recon/reconcile-drafts!
               ctx {:ontology-id oid
                    :concept-drafts (:concept-drafts csv-ex)
                    :relationship-drafts (:relationship-drafts csv-ex)})
            _ (recon/reconcile-drafts!
               ctx {:ontology-id oid
                    :concept-drafts (:concept-drafts sql-ex)
                    :relationship-drafts (:relationship-drafts sql-ex)})
            _ (Thread/sleep 500)
            ;; 4. READ BACK off the projection.
            concepts (vec (rm/get-concepts ctx {:ontology-id oid}))
            rels (vec (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx)))
            ;; canonical program URIs both sources should agree on
            program-uris (->> concepts
                              (map :uri)
                              (filter #(str/starts-with? (str %) "programofstudy/"))
                              set)
            occupation-uris (->> concepts
                                 (map :uri)
                                 (filter #(str/starts-with? (str %) "occupation/"))
                                 set)
            ;; the prepares_for edges (program → occupation hop)
            prep-edges (filter #(= "prepares_for" (:predicate %)) rels)
            ;; a prepares_for edge whose source is a canonical program AND target is
            ;; a canonical occupation = the program→occupation hop reads back.
            program->occupation-hops
            (filter (fn [e]
                      (and (contains? program-uris (:source-uri e))
                           (contains? occupation-uris (:target-uri e))))
                    prep-edges)
            ;; Which CIP canonical URIs were contributed by BOTH sources? Recompute
            ;; the canonical URI each source's drafts WOULD mint, intersect.
            csv-prog-uris (->> (extract/canonicalize-drafts
                                csv-model-spec (:concept-drafts csv-ex) [])
                               :concept-drafts
                               (map :uri)
                               (filter #(str/starts-with? (str %) "programofstudy/"))
                               set)
            sql-prog-uris (->> (extract/canonicalize-drafts
                                sql-model-spec (:concept-drafts sql-ex) [])
                               :concept-drafts
                               (map :uri)
                               (filter #(str/starts-with? (str %) "programofstudy/"))
                               set)
            shared-cips (clojure.set/intersection csv-prog-uris sql-prog-uris)
            ;; each shared CIP must be exactly ONE concept in the projection
            merged-shared
            (for [u shared-cips]
              {:uri u
               :concept-count (count (filter #(= u (:uri %)) concepts))})]
        {:ontology-id oid
         :csv-extract {:status (:status csv-ex)
                       :concepts (count (:concept-drafts csv-ex))
                       :rels (count (:relationship-drafts csv-ex))
                       :sample-entity-types (->> (:concept-drafts csv-ex)
                                                 (map :entity-type) distinct (take 5) vec)
                       :degraded (get-in (:extraction-report csv-ex)
                                         [:canonicalization :degraded-count])
                       :sample-concepts (vec (take 3 (:concept-drafts csv-ex)))}
         :sql-extract {:status (:status sql-ex)
                       :concepts (count (:concept-drafts sql-ex))
                       :sample-entity-types (->> (:concept-drafts sql-ex)
                                                 (map :entity-type) distinct (take 5) vec)
                       :degraded (get-in (:extraction-report sql-ex)
                                         [:canonicalization :degraded-count])
                       :sample-concepts (vec (take 3 (:concept-drafts sql-ex)))}
         :projection {:total-concepts (count concepts)
                      :program-concepts (count program-uris)
                      :occupation-concepts (count occupation-uris)
                      :prepares-for-edges (count prep-edges)}
         :shared-cip-uris (vec shared-cips)
         :merged-shared merged-shared
         ;; the load-bearing assertions
         :all-shared-merged-to-one? (and (seq merged-shared)
                                         (every? #(= 1 (:concept-count %)) merged-shared))
         :program->occupation-hops (vec (take 5 program->occupation-hops))
         :program->occupation-hop-count (count program->occupation-hops)
         :program->occupation-reads-back? (pos? (count program->occupation-hops))})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (println "\n===== GC-1 canonical URI live verify =====")
  (println "csv extract:" (select-keys (:csv-extract r) [:status :concepts :rels :sample-entity-types :degraded]))
  (println "sql extract:" (select-keys (:sql-extract r) [:status :concepts :sample-entity-types :degraded]))
  (println "projection :" (:projection r))
  (println "shared CIP canonical URIs (both sources):" (:shared-cip-uris r))
  (println "merged-shared (each must be concept-count 1):")
  (pp/pprint (:merged-shared r))
  (println "ALL shared CIPs merged to ONE concept?:" (:all-shared-merged-to-one? r))
  (println "program->occupation hops (sample):")
  (pp/pprint (:program->occupation-hops r))
  (println "program->occupation hop count:" (:program->occupation-hop-count r))
  (println "program->occupation READS BACK?:" (:program->occupation-reads-back? r))
  (println "==========================================\n"))

;; ---------------------------------------------------------------------------
;; CLI entry — bounded run (future + deref timeout + System/exit; can't hang).
;; ---------------------------------------------------------------------------

(defn -main [& _]
  (let [fut (future
              (try
                (let [r (run! {})]
                  (print-summary! r)
                  (if (and (:all-shared-merged-to-one? r)
                           (:program->occupation-reads-back? r))
                    (do (println "GC-1 LIVE VERIFY: PASS") 0)
                    (do (println "GC-1 LIVE VERIFY: FAIL") 1)))
                (catch Throwable t
                  (println "GC-1 LIVE VERIFY: ERROR" (.getMessage t))
                  (.printStackTrace t)
                  2)))
        code (deref fut (* 12 60 1000) :timeout)]
    (when (= code :timeout)
      (future-cancel fut)
      (println "GC-1 LIVE VERIFY: TIMEOUT"))
    (shutdown-agents)
    (System/exit (if (integer? code) code 3))))
