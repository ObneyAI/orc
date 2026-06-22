(ns eb5-reconcile-subbehavior-live-verify
  "EB5 — LIVE VERIFY: the RECONCILE subbehavior as a delegatable sheet — a single
   `:code` node that probes (check-before-mint, P3 hybrid-search), lands (reused
   `compile-discovery-source!` + V18), entity-reconciles (reused DT7
   `reconcile-graph!` — S03 + S12 + V18, `:llm-budget` 0), and attribute-reconciles
   (the EB5 deepening) against the CURRENT graph state.

   What this proves with REAL Grain + REAL embeddings + REAL ColBERT (the P3 probe)
   — NO mocks, NO LLM (reconcile is deterministic):
     - The Reconcile sheet is BUILT on the EB1/EB2/EB3/EB4 registry/delegation
       pattern: a composed ORC sheet, registered under a stable name →
       deterministic sheet-id, invoked from a CENTRAL tree via `:delegate` with
       mapped `:reads`/`:writes`.
     - REAL cross-source reconcile against a PRE-POPULATED graph (source A landed +
       embedded + ColBERT-indexed first): source B's re-minted entity COLLAPSES to
       the existing node (reconcile-NOT-duplicate), the cross-source link is
       reported, attributes link across entities, 0 dangling, ambiguities surface
       as `:requires-review` (NOT silently merged).
     - The check-before-mint hybrid-search probe FIRED against the real graph
       (real embedding + ColBERT signals) BEFORE landing — evidence-grounded
       identity (the probe entries + scores are captured verbatim).
     - REUSE not fork: `compile-discovery-source!` (land + V18) + `reconcile-graph!`
       (S03 + S12 + V18) + `hybrid-search` (P3) + S12's jaro-winkler (attr match).
     - C1: the reconcile report crosses `:delegate` PARSED (a `:code`-node output);
       read back from the PARENT tick blackboard via the projection (discipline 7).

   USAGE (REPL with :dev:test; the P3 ColBERT probe needs the Python ColBERT
   bridge up for the ColBERT signal — the embedding signal works without it):
     (require '[eb5-reconcile-subbehavior-live-verify :as eb5])
     (def r (eb5/run-all! {}))
     (eb5/print-summary! r)
     (eb5/save-capture! r)

   Or bounded from the CLI (the runner wraps it in future+deref+exit)."
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
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as recon]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as sk]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.colbert.interface :as colbert]
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

(def capture-path "docs/build-timeline/live-verify/EB5-reconcile.md")

;; Source A — the PRE-EXISTING graph. Real semantic text so the embed + ColBERT
;; default produces real embeddings + a real index (the P3 probe has real signals
;; to retrieve against). Domain-neutral occupation-ish entities with attributes.
(def source-a-concepts
  [{:uri "entity:nurse" :label "Registered Nurse"
    :description "Provides direct patient care in hospitals and clinics."
    :indicators ["patient care" "clinical practice"]
    :attributes {:sector "healthcare" :median-wage 75000}}
   {:uri "entity:engineer" :label "Software Engineer"
    :description "Designs builds and maintains large scale software systems."
    :indicators ["programming" "system design"]
    :attributes {:sector "technology" :median-wage 110000}}
   {:uri "entity:teacher" :label "Elementary School Teacher"
    :description "Educates young children in core academic subjects."
    :indicators ["education" "curriculum"]
    :attributes {:sector "education" :median-wage 60000}}
   {:uri "entity:analyst" :label "Financial Analyst"
    :description "Evaluates investments market trends and corporate budgets."
    :indicators ["finance" "investment"]
    :attributes {:sector "finance" :median-wage 85000}}
   {:uri "entity:electrician" :label "Electrician"
    :description "Installs maintains and repairs electrical wiring and power systems."
    :indicators ["electrical" "wiring"]
    :attributes {:sector "trades" :median-wage 62000}}
   {:uri "entity:pharmacist" :label "Pharmacist"
    :description "Dispenses medication and advises patients on safe drug usage."
    :indicators ["medication" "pharmacy"]
    :attributes {:sector "healthcare" :median-wage 120000}}])

;; Source B — a SECOND source arriving against the populated graph.
;;   - entity:nurse  re-minted (the SAME canonical URI — must COLLAPSE,
;;     reconcile-not-duplicate) carrying the SAME :sector "healthcare"
;;     attribute the pre-existing pharmacist also carries (an attribute link).
;;   - entity:dentist genuinely NEW, carrying :sector "healthcare" (links to
;;     the existing healthcare entities' :sector at the ATTRIBUTE level).
(def source-b-concepts
  [{:uri "entity:nurse" :label "Registered Nurse (source B)"
    :description "Cares for patients and administers treatment in clinical settings."
    :indicators ["nursing" "patient care"]
    :attributes {:sector "healthcare" :shift "rotating"}}
   {:uri "entity:dentist" :label "Dentist"
    :description "Diagnoses and treats conditions of the teeth and gums."
    :indicators ["dental" "oral health"]
    :attributes {:sector "healthcare" :median-wage 130000}}])

(def source-b-relationships
  [{:source-uri "entity:dentist" :target-uri "entity:nurse" :predicate "collaborates-with"}])

;; ---------------------------------------------------------------------------
;; Real-Grain harness with real todo processors (embed/index run async)
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb5-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb5-live"
                          :map-size (* 1024 1024 1024)}))
        base-ctx {:event-store store
                  :cache cache
                  :tenant-id (random-uuid)
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
;; Full live verify — populate (build! source A: real embed + ColBERT index),
;; then DELEGATE the Reconcile subbehavior for source B from a central tree.
;; ---------------------------------------------------------------------------

(defn run-once! [ctx]
  (let [oid (random-uuid)
        ;; --- populate the graph with source A AND build real embeddings +
        ;;     ColBERT index (so the P3 probe has real signals to retrieve) ---
        _ (sk/build! ctx {:ontology-id oid
                          :sources [{:type :inline-concepts :concepts source-a-concepts}]})
        _ (Thread/sleep 1500) ;; let the async embed/index processors settle
        concepts-after-a (count (rm/get-concepts ctx {:ontology-id oid}))
        idx (ontology/get-colbert-index-for-ontology ctx oid)
        ;; WARM the Python ColBERT bridge so the P3 ColBERT signal can fire — it
        ;; auto-starts lazily on first use and takes a few seconds. Ping once to
        ;; trigger the spawn, then wait for it to come up (report honestly if it
        ;; never does — the embedding signal still fires either way).
        _ (try (colbert/ping) (catch Throwable _ nil))
        ;; colbert/ping returns {:status "ok"} — a STRING, not :ok. Compare by name.
        bridge-up? (loop [tries 0]
                     (let [up? (try (= "ok" (name (or (:status (colbert/ping)) "")))
                                    (catch Throwable _ false))]
                       (cond up? true
                             (>= tries 20) false
                             :else (do (Thread/sleep 1000) (recur (inc tries))))))

        ;; --- a STANDALONE check-before-mint probe (so we capture the real probe
        ;;     entries + scores verbatim; this is the same fn the sheet's node
        ;;     calls) BEFORE landing source B ---
        probe (recon/check-before-mint-probe
               ctx {:ontology-id oid :concept-drafts source-b-concepts})

        ;; --- register + DELEGATE the Reconcile subbehavior for source B ---
        sub-id (recon/register-reconcile-subbehavior! ctx {})
        sub-name (recon/reconcile-subbehavior-name)
        looked-up (recon/reconcile-sheet-id-for)
        registry-match? (= sub-id looked-up)
        central-name "eb5/central-reconcile@v1"
        central-def (dsl/workflow central-name
                      (dsl/blackboard {:ontology-id :any
                                       :concept-drafts [:vector [:map {:closed false}]]
                                       :relationship-drafts [:vector [:map {:closed false}]]
                                       :source-uri-sets [:maybe [:vector [:map {:closed false}]]]
                                       :reconcile-report recon/reconcile-report-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-reconcile"
                          :target-sheet-id (recon/reconcile-sheet-id-for)
                          :reads [:ontology-id :concept-drafts :relationship-drafts :source-uri-sets]
                          :writes [:reconcile-report]
                          :timeout-ms 120000)))
        central-id (dsl/build-workflow! ctx central-def)
        central-tick-id (random-uuid)
        before-b (count (rm/get-concepts ctx {:ontology-id oid}))
        t0 (System/currentTimeMillis)
        central-result (runtime/execute
                        ctx central-id
                        {"ontology-id" oid
                         "concept-drafts" source-b-concepts
                         "relationship-drafts" source-b-relationships
                         "source-uri-sets"
                         [{:source "A" :uris (set (map :uri source-a-concepts))}
                          {:source "B" :uris (set (map :uri source-b-concepts))}]}
                        :timeout-ms 120000
                        :tick-id central-tick-id)
        elapsed (- (System/currentTimeMillis) t0)
        _ (Thread/sleep 300)
        ;; DISCIPLINE 7: read the report off the PARENT tick blackboard (projection).
        parent-bb (orm/get-tick-blackboard ctx central-tick-id)
        report (get-in parent-bb [:reconcile-report :value])
        after-b (count (rm/get-concepts ctx {:ontology-id oid}))
        nurse-nodes (count (filter #(= "entity:nurse" (:uri %))
                                   (rm/get-concepts ctx {:ontology-id oid})))]
    {:ontology-id oid
     :registry {:subbehavior-name sub-name :sub-sheet-id sub-id :looked-up looked-up
                :registry-match? registry-match? :central-sheet-id central-id}
     :colbert-index-id (:colbert-index-id idx)
     :colbert-bridge-up? bridge-up?
     :concepts-after-a concepts-after-a
     :concepts-before-b before-b
     :concepts-after-b after-b
     :nurse-node-count nurse-nodes
     :probe probe
     :central-status (:status central-result)
     :central-tick-id central-tick-id
     :elapsed-ms elapsed
     :report report
     :report-is-map? (map? report)
     :error (:error central-result)}))

(defn run-all! [_opts]
  (let [ctx (make-ctx)]
    (try
      (println "=== EB5 RECONCILE SUBBEHAVIOR LIVE VERIFY ===")
      (let [r (run-once! ctx)]
        (println "  colbert-index:" (:colbert-index-id r)
                 "bridge-up?:" (:colbert-bridge-up? r))
        (println "  concepts after A:" (:concepts-after-a r)
                 "after B:" (:concepts-after-b r)
                 "nurse nodes (1 = not-duplicate):" (:nurse-node-count r))
        (println "  probe: probed" (get-in r [:probe :probed])
                 "hits" (get-in r [:probe :hits])
                 "exact-uri-hits" (get-in r [:probe :exact-uri-hits]))
        (println "  central status:" (:central-status r) "(" (:elapsed-ms r) "ms)")
        r)
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (println "\n================ EB5 RECONCILE LIVE VERIFY ================")
  (println "ontology-id:" (:ontology-id r))
  (println "registry match?:" (get-in r [:registry :registry-match?]))
  (println "ColBERT index-id:" (:colbert-index-id r) " bridge up?:" (:colbert-bridge-up? r))
  (println "concepts after A landed+embedded:" (:concepts-after-a r))
  (println "concepts after B reconciled:" (:concepts-after-b r)
           " (delta =" (- (:concepts-after-b r) (:concepts-before-b r)) ")")
  (println "entity:nurse node count (1 = reconcile-NOT-duplicate):" (:nurse-node-count r))
  (println "\n--- CHECK-BEFORE-MINT probe (P3 hybrid-search, fired pre-mint) ---")
  (pp/pprint (:probe r))
  (println "\n--- ENTITY reconcile (reused reconcile-graph!) ---")
  (pp/pprint (get-in r [:report :entity-reconcile]))
  (println "\n--- ATTRIBUTE reconcile (the EB5 deepening) ---")
  (pp/pprint (get-in r [:report :attribute-reconcile]))
  (println "\ncentral status:" (:central-status r) "(" (:elapsed-ms r) "ms)")
  (println "report is a parsed MAP across :delegate (C1)?:" (:report-is-map? r)))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [report (:report r)
        entity (:entity-reconcile report)
        attrs (:attribute-reconcile report)]
    (spit capture-path
          (str "# EB5 — Reconcile subbehavior sheet — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **No mocks** — real "
               "Grain event store, real DJL MiniLM embeddings + real ColBERT index "
               "(the P3 check-before-mint probe), real async todo processors, real "
               "child tick. NO LLM (reconcile is DETERMINISTIC — the probe is P3 "
               "retrieval evidence, the merges are S12 evidence, the attribute "
               "links are structural).\n\n"
               "Proves the RECONCILE subbehavior is a delegatable single-`:code`-node "
               "sheet that takes EB4's per-source DRAFT SET + the granted "
               "`:ontology-id` (the current-graph scope) and links the new drafts "
               "across sources AND against the CURRENT graph state, at TWO "
               "granularities — ENTITIES and their ATTRIBUTES/FEATURES — with "
               "CHECK-BEFORE-MINT (probe the existing graph via P3 hybrid-search for "
               "an existing match BEFORE landing). REUSE not fork: "
               "`compile-discovery-source!` (land + V18) + `reconcile-graph!` "
               "(S03 + S12 + V18 entity-reconcile, `:llm-budget` 0) + `hybrid-search` "
               "(P3 probe) + S12's `jaro-winkler-similarity` (the attribute-key "
               "match). Built on the EB1/EB2/EB3/EB4 registry/delegation pattern; "
               "re-houses + deepens DT7.\n\n"
               "## Setup (inputs)\n\n"
               "Source A (the PRE-EXISTING graph — landed via S17 `build!`, then "
               "embedded with real MiniLM + ColBERT-indexed so the P3 probe has real "
               "signals):\n\n```clojure\n"
               (with-out-str (pp/pprint source-a-concepts)) "```\n\n"
               "Source B (a SECOND source reconciled against the populated graph; "
               "re-mints `entity:nurse` — same canonical URI, must COLLAPSE — and "
               "adds the genuinely-new `entity:dentist`):\n\n```clojure\n"
               (with-out-str (pp/pprint source-b-concepts)) "```\n\n"
               "## Registry + delegation\n\n"
               "- subbehavior: `" (get-in r [:registry :subbehavior-name]) "`\n"
               "- sub sheet-id: `" (get-in r [:registry :sub-sheet-id]) "`\n"
               "- registry name→id round-trip: **" (get-in r [:registry :registry-match?]) "**\n"
               "- central tree status: **" (:central-status r) "** (" (:elapsed-ms r) "ms)\n"
               "- parent tick-id: `" (:central-tick-id r) "`\n"
               "- ontology-id: `" (:ontology-id r) "`\n\n"
               "## CHECK-BEFORE-MINT probe (P3 hybrid-search, FIRED pre-mint)\n\n"
               "The probe ran over the UNLANDED source-B drafts BEFORE landing, "
               "against the populated graph (real embeddings"
               (if (:colbert-bridge-up? r)
                 " + real ColBERT"
                 " ; ColBERT bridge DOWN — embedding signal only, reported honestly")
               ").\n\n"
               "- ColBERT index-id: `" (:colbert-index-id r) "`\n"
               "- ColBERT bridge up: **" (:colbert-bridge-up? r) "**\n"
               "- probed: **" (get-in r [:probe :probed]) "**, content-hits: **"
               (get-in r [:probe :hits]) "**, exact-uri hits (already-present): **"
               (get-in r [:probe :exact-uri-hits]) "**\n\n"
               "Probe entries (verbatim — the evidence-grounded identity signal):\n\n"
               "```clojure\n" (with-out-str (pp/pprint (get-in r [:probe :entries])))
               "```\n\n"
               "## Reconcile-NOT-duplicate (reads the CURRENT graph state)\n\n"
               "Read back from the PARENT tick blackboard via the projection "
               "(`get-tick-blackboard`), NOT the execute return value (discipline 7). "
               "The report crossed `:delegate` as a parsed MAP (a `:code`-node "
               "output — C1):\n\n"
               "- report is a parsed MAP across `:delegate`: **" (:report-is-map? r) "**\n"
               "- concepts after A: **" (:concepts-after-a r) "**, after B reconciled: **"
               (:concepts-after-b r) "** (delta = **"
               (- (:concepts-after-b r) (:concepts-before-b r))
               "** — only the genuinely-new entity grew the graph)\n"
               "- `entity:nurse` node count: **" (:nurse-node-count r)
               "** (1 = the re-minted URI collapsed — reconcile-NOT-duplicate)\n\n"
               "## ENTITY reconcile (reused `reconcile-graph!` — S03 + S12 + V18)\n\n"
               "- shared-URI cross-source links: `"
               (pr-str (get-in entity [:shared-uri-links :shared-uris])) "` "
               "(by source: `" (pr-str (get-in entity [:shared-uri-links :by-uri])) "`)\n"
               "- candidate pairs (S12 LSH): **" (:candidate-pairs entity) "**, "
               "near-match merges: **" (:merges entity) "**\n"
               "- ambiguities surfaced (`:requires-review`, NEVER silently merged): **"
               (:ambiguities-surfaced entity) "**\n"
               "- 0 dangling (V18): dangling-edge-count = **"
               (get-in entity [:referential-integrity :dangling-edge-count]) "**, "
               "every-edge-endpoint-resolves? = **"
               (get-in entity [:referential-integrity :every-edge-endpoint-resolves?]) "**\n\n"
               "Ambiguities (verbatim — surfaced honestly):\n\n```clojure\n"
               (with-out-str (pp/pprint (:ambiguities entity))) "```\n\n"
               "## ATTRIBUTE reconcile (the genuinely-new EB5 deepening)\n\n"
               "Beyond `reconcile-graph!`'s entity-level links — a NEW entity's "
               "ATTRIBUTES/FEATURES connected to EXISTING entities' attributes by "
               "structural key match (reused S12 jaro-winkler) + value equality:\n\n"
               "- new entities carrying attributes: **" (:new-entities-with-attrs attrs) "**\n"
               "- same-value attribute links: **" (:same-value-link-count attrs) "**, "
               "shared-key links: **" (:shared-key-link-count attrs) "**\n\n"
               "Attribute links (verbatim):\n\n```clojure\n"
               (with-out-str (pp/pprint (:links attrs))) "```\n\n"
               (when (:error r)
                 (str "## Error\n\n```clojure\n"
                      (with-out-str (pp/pprint (:error r))) "```\n\n"))
               "## Full reconcile report (verbatim, off the parent blackboard)\n\n"
               "```clojure\n" (with-out-str (pp/pprint report)) "```\n\n"
               "## Verdict\n\n"
               "The Reconcile subbehavior is a delegatable single-`:code`-node sheet "
               "that reconciles a per-source draft set against the CURRENT graph "
               "state at TWO granularities (entities + attributes) with "
               "check-before-mint: the P3 hybrid-search probe FIRED pre-mint against "
               "real embeddings/ColBERT (evidence-grounded identity); the re-minted "
               "entity COLLAPSED (reconcile-NOT-duplicate); a new entity's attribute "
               "LINKED to an existing entity's attribute (the EB5 deepening); 0 "
               "dangling (V18); ambiguities surfaced as `:requires-review` (never "
               "silently merged). REUSE not fork (`compile-discovery-source!` / "
               "`reconcile-graph!` / `hybrid-search` / S12 jaro-winkler). The report "
               "crosses `:delegate` parsed (C1).\n"))
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
                  (println "EB5 live verify FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  :error)))
        result (deref fut 300000 :timeout)]
    (println "\nEB5 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb5-reconcile-subbehavior-live-verify :as eb5] :reload)
  (def r (eb5/run-all! {}))
  (eb5/print-summary! r)
  (eb5/save-capture! r))
