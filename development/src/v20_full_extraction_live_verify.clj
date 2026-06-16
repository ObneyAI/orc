(ns v20-full-extraction-live-verify
  "V20 — LIVE VERIFY: deterministic full-extraction over a REAL source, driven
   by a REAL model-authored transform.

   This is the mandatory live verification (Discipline #4). It exercises BOTH
   V20 parts end-to-end through the PUBLIC discovery path:

     Part 1 (scaffolding): the shared discovery prompt now carries the
       entity-as-node principle + the extract-to-coverage / design-a-transform
       principle. The model receives ONLY a domain goal + the shipped prompt —
       no per-row recipe, no transform handed to it.

     Part 2 (deterministic full-extraction): the model designs an extraction
       TRANSFORM on a SAMPLE inside the discovery loop and hands it back via
       :extraction-transform. run-discovery! then streams the FULL source via
       V19 stream-all and applies the transform to EVERY row, collecting the
       comprehensive draft set, which flows through compile + V18 referential
       integrity.

   The source is the REAL IPEDS SQLite DB. The goal points the model at the
   IPEDS completions data (programs/awards by institution) — a table with far
   more rows than a sample, so comprehensive coverage is the test. We capture
   VERBATIM: the model-authored transform, count-rows, rows-streamed, drafts
   produced, per-row error count, and endpoint-resolution read back from the
   projection.

   No mocks: real Grain event store, real OpenRouter LLM, real SCI eval of the
   model's transform, real stream over the real DB.

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[v20-full-extraction-live-verify :as v20])
     (def r (v20/run! {}))
     (v20/print-summary! r)
     (v20/save-capture! r)"
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def ipeds-db "/Users/darylroberts/Downloads/output.db")
(def default-model "google/gemini-3-flash-preview")

(def capture-path "docs/build-timeline/live-verify/V20-full-extraction.md")

;; A goal, NOT a recipe. Names NO table, column, key, offset, or transform — the
;; model explores the DB and designs the extraction itself. The entity-as-node +
;; extract-to-coverage scaffolding it needs is in the shipped prompt (Part 1).
(def domain-goal
  (str
   "DOMAIN GOAL — build a comprehensive ontology of the educational COMPLETIONS "
   "reported in this database: for the completions data, mint each distinct "
   "program/award an institution reports as its own node (carrying its own "
   "values), the institutions that report them, and connect each program to its "
   "institution. Cover the completions data COMPREHENSIVELY — it has far more "
   "rows than a sample, so design your extraction so EVERY row is covered, not "
   "just the first window.\n\n"
   "============================================================\n\n"))

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
        dir (str "/tmp/v20-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "v20-live"
                          :map-size (* 4 1024 1024 1024)}))
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

(defn run!
  [{:keys [model budget]
    :or {model default-model
         budget {:max-iterations 16 :total-budget-ms 1200000 :max-retries 3}}}]
  (let [ctx (make-ctx)
        oid (random-uuid)
        source {:name :ipeds :type :sql :path ipeds-db}
        prompt (str domain-goal rlm-discovery/default-discovery-prompt)]
    (try
      (register-openrouter! model)
      (println "=== V20 FULL-EXTRACTION LIVE VERIFY ===")
      (println "Ontology-id:" oid " model:" model)
      (println "Source:" (:path source) " budget:" budget)
      (let [t0 (System/currentTimeMillis)
            disc (ontology/run-discovery!
                  ctx {:ontology-id oid
                       :sources [source]
                       :discovery-prompt prompt
                       :model model
                       :budget budget})
            disc-ms (- (System/currentTimeMillis) t0)
            _ (println "\ndiscovery status:" (:status disc)
                       " emitted c/r:" (count (:emitted-concepts disc)) "/"
                       (count (:emitted-relationships disc))
                       " (" disc-ms "ms)")
            _ (when (:full-extraction disc)
                (println "FULL-EXTRACTION COVERAGE:")
                (pp/pprint (:full-extraction disc)))
            compiled (when (= :emitted-drafts (:status disc))
                       (ontology/compile-discovery-source! ctx oid disc))
            concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
            relationships (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))
            program-nodes (filter #(str/starts-with? (str (:uri %)) "program:") concepts)
            ;; The model chose its own URI schemes; bucket by scheme generically.
            by-scheme (frequencies
                       (map #(let [u (str (:uri %))
                                   i (str/index-of u ":")]
                               (if i (subs u 0 i) "other"))
                            concepts))
            ;; Re-derive endpoint resolution from a projection read-back (NOT just
            ;; the provenance flag) — the authoritative no-false-green check.
            concept-uris (set (map :uri concepts))
            dangling (filter (fn [r]
                               (or (not (contains? concept-uris (:source-uri r)))
                                   (not (contains? concept-uris (:target-uri r)))))
                             relationships)]
        (println "\n=== READ-BACK ===")
        (println "concepts:" (count concepts) " relationships:" (count relationships))
        (println "concepts by uri-scheme:" by-scheme)
        (println "program-style nodes:" (count program-nodes))
        (println "dangling edges (read-back):" (count dangling))
        {:ontology-id oid
         :model model
         :budget budget
         :discovery-status (:status disc)
         :discovery-ms disc-ms
         :extraction-transform (get-in disc [:session-result :outputs :extraction-transform])
         :full-extraction (:full-extraction disc)
         :emitted-concepts (count (:emitted-concepts disc))
         :emitted-relationships (count (:emitted-relationships disc))
         :rlm-trace (:rlm-trace disc)
         :iteration-reasonings (:iteration-reasonings disc)
         :compile-provenance (:discovery-provenance compiled)
         :concepts-in-graph (count concepts)
         :relationships-in-graph (count relationships)
         :program-nodes (count program-nodes)
         :concepts-by-scheme by-scheme
         :dangling-read-back (count dangling)
         :sample-program-nodes (vec (take 8 (map #(select-keys % [:uri :label :attributes])
                                                 program-nodes)))
         :session-error (:error disc)})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (println "\n================ V20 LIVE VERIFY SUMMARY ================")
  (println "ontology-id:" (:ontology-id r) " model:" (:model r))
  (println "discovery-status:" (:discovery-status r))
  (println "\n--- model-authored extraction transform (VERBATIM) ---")
  (println (get-in r [:extraction-transform :transform-source]))
  (println "selector:" (get-in r [:extraction-transform :selector]))
  (println "\n--- full-extraction coverage ---")
  (pp/pprint (:full-extraction r))
  (println "\n--- read-back ---")
  (println "concepts:" (:concepts-in-graph r) " program-nodes:" (:program-nodes r)
           " dangling:" (:dangling-read-back r))
  (println "by-scheme:" (:concepts-by-scheme r))
  (println "\n--- compile provenance ---")
  (pp/pprint (:compile-provenance r)))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (spit capture-path
        (str "# V20 — Deterministic full-extraction — LIVE VERIFY\n\n"
             "**Date:** 2026-06-16. **Branch:** `feature/ontology-architecture`.\n"
             "**Model:** `" (:model r) "` (real OpenRouter). **Source:** real IPEDS "
             "SQLite. **No mocks.**\n\n"
             "Ontology-id: `" (:ontology-id r) "`. Budget: `" (pr-str (:budget r)) "`.\n\n"
             "The model received ONLY a domain goal + the shipped discovery prompt (which "
             "now carries the entity-as-node + extract-to-coverage/design-a-transform "
             "scaffolding). No table, column, key, offset, or transform was handed to it.\n\n"
             "## The model-authored extraction transform (VERBATIM)\n\n"
             "Selector: `" (pr-str (get-in r [:extraction-transform :selector])) "`\n\n"
             "```clojure\n"
             (get-in r [:extraction-transform :transform-source])
             "\n```\n\n"
             "## Full-extraction coverage (deterministic apply-step over the FULL source)\n\n"
             "```clojure\n" (with-out-str (pp/pprint (:full-extraction r))) "```\n\n"
             "## Read-back from the projection\n\n"
             "- concepts in graph: **" (:concepts-in-graph r) "**\n"
             "- relationships in graph: **" (:relationships-in-graph r) "**\n"
             "- program-style nodes (entity-as-node): **" (:program-nodes r) "**\n"
             "- concepts by uri-scheme: `" (pr-str (:concepts-by-scheme r)) "`\n"
             "- dangling edges (re-derived from read-back): **" (:dangling-read-back r) "**\n\n"
             "Sample program nodes (with attributes):\n\n```clojure\n"
             (with-out-str (pp/pprint (:sample-program-nodes r))) "```\n\n"
             "## Compile provenance (V18 referential integrity)\n\n```clojure\n"
             (with-out-str (pp/pprint (:compile-provenance r))) "```\n\n"
             "## Discovery trace (model reasoning, verbatim)\n\n```clojure\n"
             (with-out-str (pp/pprint {:rlm-trace (:rlm-trace r)
                                       :iteration-reasonings (:iteration-reasonings r)}))
             "```\n"))
  (println "Capture written:" capture-path)
  capture-path)

(comment
  (require '[v20-full-extraction-live-verify :as v20] :reload)
  (def r (v20/run! {}))
  (v20/print-summary! r)
  (v20/save-capture! r))
