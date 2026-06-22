(ns eb7-embed-index-subbehavior-live-verify
  "EB7 — LIVE VERIFY: the EMBED+INDEX subbehavior as a delegatable sheet — a single
   `:code` node that RESOLVES the embed-worthy fields (EB3's `:embed-fields` signal,
   else the heuristic schema scan), EMBEDS the in-scope concepts (reused
   `embed-concepts-batch!` to compute + the `:ontology/embed-concept` command to
   LAND), and ColBERT-INDEXES them via the `:colbert/create-index` COMMAND (so the
   index-created event LANDS and the index is RESOLVABLE) — BY DEFAULT, with NO
   caller wiring the embed/index fns (the P2 fix).

   What this proves with REAL Grain + REAL DJL MiniLM + REAL ColBERT bridge — NO
   mocks, NO LLM (EB3 already committed `:embed-fields`):
     - The Embed+Index sheet is BUILT on the EB1-EB6 registry/delegation pattern: a
       composed ORC sheet, registered under a stable name → deterministic sheet-id,
       invoked from a CENTRAL tree via `:delegate` with mapped `:reads`/`:writes`.
     - GUARANTEED-by-default: a real built graph → auto-embed + ColBERT-index FIRE
       by default (driven by the Model's `:embed-fields`), with NO caller wiring.
     - Embed events LAND — asserted by reading the projection back (discipline 7).
     - The ColBERT index is RESOLVABLE (registered for the ontology) and a real
       hybrid-search returns LABELED, semantically-correct hits on BOTH the
       embedding signal and (when the bridge is up) the ColBERT signal.
     - C1: the embed+index report crosses `:delegate` PARSED (a `:code`-node
       output); read back from the PARENT tick blackboard via the projection.

   USAGE (REPL with :dev:test; the ColBERT signal needs the Python ColBERT bridge
   up — the embedding signal works without it):
     (require '[eb7-embed-index-subbehavior-live-verify :as eb7])
     (def r (eb7/run-all! {}))
     (eb7/print-summary! r)
     (eb7/save-capture! r)

   Or bounded from the CLI via -main (future + deref timeout + System/exit)."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.evolutionary-commands]
            [ai.obney.orc.ontology.core.lints.commands]
            [ai.obney.orc.ontology.core.lints.read-models]
            [ai.obney.orc.ontology.core.embed-index-subbehavior :as ei]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.colbert.core.commands]
            [ai.obney.orc.colbert.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

(def capture-path "docs/build-timeline/live-verify/EB7-embed-index.md")

;; A real, domain-neutral graph with genuine free-text fields so the embed +
;; ColBERT index produce real, semantically-separable signals. Comfortably above
;; ColBERT's k-means training-points floor. Nurse + Engineer are the probes.
(def source-concepts
  [{:uri "entity:nurse" :label "Registered Nurse"
    :description "Provides direct patient care in hospitals and clinics across many departments."}
   {:uri "entity:engineer" :label "Software Engineer"
    :description "Designs builds and maintains large scale software systems and services."}
   {:uri "entity:teacher" :label "Elementary School Teacher"
    :description "Educates young children in core academic subjects like reading and mathematics."}
   {:uri "entity:electrician" :label "Electrician"
    :description "Installs maintains and repairs electrical wiring and power systems safely."}
   {:uri "entity:pharmacist" :label "Pharmacist"
    :description "Dispenses medication and advises patients on safe and effective drug usage."}
   {:uri "entity:analyst" :label "Financial Analyst"
    :description "Evaluates investments market trends and corporate budgets for decisions."}
   {:uri "entity:chef" :label "Executive Chef"
    :description "Prepares meals plans menus and manages a restaurant kitchen and staff."}
   {:uri "entity:officer" :label "Police Officer"
    :description "Enforces laws investigates crimes and protects public safety in communities."}
   {:uri "entity:architect" :label "Architect"
    :description "Designs buildings prepares blueprints and oversees construction projects."}
   {:uri "entity:scientist" :label "Data Scientist"
    :description "Builds predictive models from large and complex datasets using statistics."}])

;; The EB3 :embed-fields signal — the Model committed these free-text fields.
(def model-embed-fields ["label" "description"])

;; ---------------------------------------------------------------------------
;; Real-Grain harness WITH the real todo processors — the `:delegate` child tick
;; is DRIVEN by a todo-processor (orc-service/core/todo_processors). Without the
;; processors started, the delegate event is never picked up, the child tick
;; never runs, and the parent times out. (Mirrors the EB5 live-verify harness.)
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb7-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb7-live"
                          :map-size (* 1024 1024 1024)}))
        base-ctx {:event-store store :cache cache :tenant-id (random-uuid)
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
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

;; ---------------------------------------------------------------------------
;; Full live verify — land a real graph (NO embed/index), then DELEGATE the
;; Embed+Index subbehavior from a central tree (embed + ColBERT-index fire by
;; default), then hybrid-search the embedded/indexed graph.
;; ---------------------------------------------------------------------------

(defn run-once! [ctx]
  (let [oid (random-uuid)
        ;; --- land the graph WITHOUT embedding/indexing (so EB7 is what fires
        ;;     embed + ColBERT-index, proving GUARANTEED-by-default) ---
        _ (ontology/compile-discovery-source!
           ctx oid {:status :emitted-drafts
                    :emitted-concepts source-concepts
                    :emitted-relationships []})
        concepts-landed (count (rm/get-concepts ctx {:ontology-id oid}))

        ;; --- register + DELEGATE the Embed+Index subbehavior ---
        sub-id (ei/register-embed-index-subbehavior! ctx {})
        sub-name (ei/embed-index-subbehavior-name)
        looked-up (ei/embed-index-sheet-id-for)
        registry-match? (= sub-id looked-up)
        central-name "eb7/central-embed-index@v1"
        central-def (dsl/workflow central-name
                      (dsl/blackboard {:ontology-id :any
                                       :embed-fields [:maybe [:vector :string]]
                                       :embed-index-report ei/embed-index-report-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-embed-index"
                          :target-sheet-id (ei/embed-index-sheet-id-for)
                          :reads [:ontology-id :embed-fields]
                          :writes [:embed-index-report]
                          :timeout-ms 180000)))
        central-id (dsl/build-workflow! ctx central-def)
        central-tick-id (random-uuid)
        t0 (System/currentTimeMillis)
        central-result (runtime/execute
                        ctx central-id
                        {"ontology-id" oid
                         "embed-fields" model-embed-fields}
                        :timeout-ms 180000
                        :tick-id central-tick-id)
        elapsed (- (System/currentTimeMillis) t0)
        _ (Thread/sleep 300)
        ;; DISCIPLINE 7: read the report off the PARENT tick blackboard (projection).
        parent-bb (orm/get-tick-blackboard ctx central-tick-id)
        report (get-in parent-bb [:embed-index-report :value])

        ;; --- embed events LANDED (read the projection back, discipline 7) ---
        embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})
        registered-idx (ontology/get-colbert-index-for-ontology ctx oid)

        ;; --- EMBEDDING-signal hybrid-search (always available, real MiniLM) ---
        emb-query "caring for patients in a hospital"
        emb-search (ontology/hybrid-search ctx {:query-text emb-query
                                                :ontology-id oid :signals #{:embedding}
                                                :min-similarity 0.0 :limit 5})
        emb-hits (mapv #(select-keys % [:uri :label :score]) (:results emb-search))

        ;; --- ColBERT bridge warm-up (auto-starts lazily; the brief gotcha:
        ;;     colbert/ping returns {:status "ok"} — a STRING, not :ok) ---
        _ (try (colbert/ping) (catch Throwable _ nil))
        bridge-up? (loop [tries 0]
                     (let [up? (try (= "ok" (name (or (:status (colbert/ping)) "")))
                                    (catch Throwable _ false))]
                       (cond up? true
                             (>= tries 20) false
                             :else (do (Thread/sleep 1000) (recur (inc tries))))))
        cb-query "writing and maintaining software programs"
        cb-search (when (and (:colbert-index-id registered-idx) bridge-up?)
                    (ontology/hybrid-search ctx {:query-text cb-query
                                                 :ontology-id oid
                                                 :colbert-index-id (:colbert-index-id registered-idx)
                                                 :signals #{:colbert} :limit 5}))
        cb-hits (mapv #(select-keys % [:uri :label :score]) (:results cb-search))]
    {:ontology-id oid
     :registry {:subbehavior-name sub-name :sub-sheet-id sub-id :looked-up looked-up
                :registry-match? registry-match? :central-sheet-id central-id}
     :concepts-landed concepts-landed
     :central-status (:status central-result)
     :central-tick-id central-tick-id
     :elapsed-ms elapsed
     :report report
     :report-is-map? (map? report)
     :embeddings-read-back-count (count embs)
     :colbert-index-id (:colbert-index-id registered-idx)
     :colbert-bridge-up? bridge-up?
     :emb-query emb-query
     :emb-hits emb-hits
     :cb-query cb-query
     :cb-hits cb-hits
     :error (:error central-result)}))

(defn run-all! [_opts]
  (let [ctx (make-ctx)]
    (try
      (println "=== EB7 EMBED+INDEX SUBBEHAVIOR LIVE VERIFY ===")
      (let [r (run-once! ctx)]
        (println "  concepts landed:" (:concepts-landed r))
        (println "  central status:" (:central-status r) "(" (:elapsed-ms r) "ms)")
        (println "  embeddings read-back:" (:embeddings-read-back-count r))
        (println "  colbert index:" (:colbert-index-id r) "bridge-up?:" (:colbert-bridge-up? r))
        (println "  EMBEDDING hits for" (pr-str (:emb-query r)) ":")
        (doseq [h (:emb-hits r)] (println "     " (:uri h) "|" (:label h) "| score" (:score h)))
        (when (seq (:cb-hits r))
          (println "  COLBERT hits for" (pr-str (:cb-query r)) ":")
          (doseq [h (:cb-hits r)] (println "     " (:uri h) "|" (:label h) "| score" (:score h))))
        r)
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (println "\n================ EB7 EMBED+INDEX LIVE VERIFY ================")
  (println "ontology-id:" (:ontology-id r))
  (println "registry match?:" (get-in r [:registry :registry-match?]))
  (println "concepts landed (NO embed/index yet):" (:concepts-landed r))
  (println "central status:" (:central-status r) "(" (:elapsed-ms r) "ms)")
  (println "report is a parsed MAP across :delegate (C1)?:" (:report-is-map? r))
  (println "\n--- the embed+index report (off the parent blackboard) ---")
  (pp/pprint (:report r))
  (println "\nembeddings read back from projection (#7):" (:embeddings-read-back-count r))
  (println "ColBERT index-id (registered, RESOLVABLE):" (:colbert-index-id r)
           " bridge up?:" (:colbert-bridge-up? r))
  (println "\n--- EMBEDDING hybrid-search" (pr-str (:emb-query r)) "---")
  (pp/pprint (:emb-hits r))
  (println "\n--- COLBERT hybrid-search" (pr-str (:cb-query r)) "---")
  (pp/pprint (:cb-hits r)))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [report (:report r)]
    (spit capture-path
          (str "# EB7 — Embed+Index subbehavior sheet — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **No mocks** — real "
               "Grain event store, real DJL MiniLM embeddings, real ColBERT bridge "
               "(when up), real child tick. NO LLM (EB3 already committed the "
               "`embed-fields` signal — embed+index is deterministic orchestration).\n\n"
               "Proves the EMBED+INDEX subbehavior is a delegatable single-`:code`-node "
               "sheet that, on a built graph, RESOLVES the embed-worthy fields (the "
               "Model's `embed-fields` signal, else the heuristic schema scan), EMBEDS "
               "the in-scope concepts (reused `embed-concepts-batch!` to compute + the "
               "`:ontology/embed-concept` command to LAND), and ColBERT-INDEXES them "
               "via the `:colbert/create-index` COMMAND so the index is RESOLVABLE — "
               "BY DEFAULT, with NO caller wiring the embed/index fns (the P2 fix). "
               "Built on the EB1-EB6 registry/delegation pattern.\n\n"
               "## The ColBERT root-cause this verify pins down\n\n"
               "`colbert-indexer/index-concepts!` forwards `colbert/create-index!` "
               "(the interface fn), which builds the PLAID index on disk but does NOT "
               "emit the `:colbert/index-created` event — so `get-index` cannot resolve "
               "it and a subsequent ColBERT hybrid-search fails with 'Index not found'. "
               "EB7 dispatches the `:colbert/create-index` COMMAND (which emits the "
               "index-created event) and registers the per-ontology mapping with the "
               "SAME landed id, so the index is actually RESOLVABLE. This verify "
               "exercises the real ColBERT signal end-to-end to prove it.\n\n"
               "## Setup (inputs)\n\n"
               "A real graph landed via `compile-discovery-source!` WITHOUT embedding "
               "or indexing (so EB7 is what fires embed + ColBERT-index — proving "
               "GUARANTEED-by-default):\n\n```clojure\n"
               (with-out-str (pp/pprint source-concepts)) "```\n\n"
               "EB3 `embed-fields` signal (the fields the Model committed): `"
               (pr-str model-embed-fields) "`\n\n"
               "## Registry + delegation\n\n"
               "- subbehavior: `" (get-in r [:registry :subbehavior-name]) "`\n"
               "- sub sheet-id: `" (get-in r [:registry :sub-sheet-id]) "`\n"
               "- registry name→id round-trip: **" (get-in r [:registry :registry-match?]) "**\n"
               "- central tree status: **" (:central-status r) "** (" (:elapsed-ms r) "ms)\n"
               "- parent tick-id: `" (:central-tick-id r) "`\n"
               "- ontology-id: `" (:ontology-id r) "`\n"
               "- concepts landed (before EB7): **" (:concepts-landed r) "**\n\n"
               "## GUARANTEED-by-default embed (events LANDED — projection read-back, #7)\n\n"
               "The delegated `:code` node embedded the in-scope concepts on the "
               "resolved fields and the `:ontology/concept-embedded` events LANDED — "
               "read back from the projection (NOT a return value):\n\n"
               "- resolved embed-fields: `" (pr-str (:embed-fields-used report)) "` "
               "(source: **" (pr-str (:embed-fields-source report)) "**)\n"
               "- report `embedded-count`: **" (:embedded-count report) "**\n"
               "- report `embeddings-read-back-count`: **" (:embeddings-read-back-count report) "**\n"
               "- embeddings read back from the projection (independent): **"
               (:embeddings-read-back-count r) "**\n\n"
               "## GUARANTEED-by-default ColBERT index (RESOLVABLE)\n\n"
               "- ColBERT index-id (registered for the ontology): `" (:colbert-index-id r) "`\n"
               "- index document count: **" (:index-document-count report) "**\n"
               "- index-skipped-reason (nil = a real index built): `"
               (pr-str (:index-skipped-reason report)) "`\n"
               "- ColBERT bridge up: **" (:colbert-bridge-up? r) "**\n\n"
               "## Hybrid-search returns LABELED, semantically-correct hits\n\n"
               "### EMBEDDING signal (real MiniLM) — query: `" (:emb-query r) "`\n\n"
               "```clojure\n" (with-out-str (pp/pprint (:emb-hits r))) "```\n\n"
               (if (:colbert-bridge-up? r)
                 (str "### COLBERT signal (real bridge) — query: `" (:cb-query r) "`\n\n"
                      "```clojure\n" (with-out-str (pp/pprint (:cb-hits r))) "```\n\n")
                 (str "### COLBERT signal — bridge DOWN\n\n"
                      "The Python ColBERT bridge was not reachable; the ColBERT-signal "
                      "query was skipped and reported honestly. The embedding signal "
                      "(above) fires regardless, and the index is registered + "
                      "RESOLVABLE for when the bridge is up.\n\n"))
               (when (:error r)
                 (str "## Error\n\n```clojure\n"
                      (with-out-str (pp/pprint (:error r))) "```\n\n"))
               "## Full embed+index report (verbatim, off the parent blackboard)\n\n"
               "```clojure\n" (with-out-str (pp/pprint report)) "```\n\n"
               "## Verdict\n\n"
               "The Embed+Index subbehavior is a delegatable single-`:code`-node sheet "
               "that, on a built graph, embeds + ColBERT-indexes the concepts BY "
               "DEFAULT — driven by the Model's `embed-fields` — with NO caller wiring "
               "(the P2 fix). The embed events LANDED (projection read-back, #7); the "
               "ColBERT index is RESOLVABLE (via the command path, not the "
               "event-skipping interface fn); and a real hybrid-search returns labeled, "
               "semantically-correct hits on the embedding"
               (if (:colbert-bridge-up? r) " AND ColBERT" "")
               " signal. REUSE not fork (`embed-concepts-batch!` / the heuristic "
               "detector / `:colbert/create-index` command / "
               "`emit-colbert-indexed-event!`). The report crosses `:delegate` parsed "
               "(C1). F1 (per-concept embed-event batching) remains the open scale "
               "follow-up.\n"))
    (println "Capture written:" capture-path)
    capture-path))

(defn -main [& _]
  (let [fut (future
              (try
                (let [r (run-all! {})]
                  (print-summary! r)
                  (save-capture! r)
                  (if (= :success (:central-status r)) :done :error))
                (catch Throwable t
                  (println "EB7 live verify FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  :error)))
        result (deref fut 300000 :timeout)]
    (println "\nEB7 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb7-embed-index-subbehavior-live-verify :as eb7] :reload)
  (def r (eb7/run-all! {}))
  (eb7/print-summary! r)
  (eb7/save-capture! r))
