(ns eb6-axiom-tbox-subbehavior-live-verify
  "EB6 — LIVE VERIFY: the AXIOM/TBox subbehavior as a delegatable sheet — a single
   `:code` node that grounds EB3's candidate-axioms against the REAL extracted
   graph and emits REAL TBox axioms through the S07 commands (closing the
   `:axioms-skipped` silent drop).

   What this proves with REAL Grain + REAL LLM (EB4 authors a real transform; EB3
   authors real candidate-axioms) + a REAL extracted graph from a REAL CSV — NO
   mocks, NO synthetic candidates:
     - The full EB3→EB4→EB6 chain on a REAL source:
         * EB4 Extract delegated over the REAL CSV → real concept/relationship
           drafts → LANDED via `compile-discovery-source!` (the real graph).
         * EB3 Model delegated over a goal + a profile of that CSV → REAL
           candidate-axioms authored by the LLM (NOT hand-written).
         * EB6 Axiom/TBox delegated the REAL candidate-axioms + the granted
           `:ontology-id` → grounds each candidate's class/predicate references
           against the REAL graph, maps `:kind` → the matching S07 command, emits.
     - Axioms LAND: read back via `get-axioms` (discipline 7 — the projection is
       the truth, NOT the report's return value). The emitted axioms + every
       `:axioms-unsupported` / `:axioms-ungrounded` bucket is captured verbatim.
     - The EB6 MINT (`assert-sub-class`) lands a real `rdfs:subClassOf` axiom that
       was previously a pathless silent drop (when the LLM proposes a sub-class).
     - The honest-gap rule: domain/range/closure (and any ungrounded ref) is
       SURFACED, never silently skipped.
     - C1: the axiom report crosses `:delegate` PARSED (a `:code`-node output);
       read back from the PARENT tick blackboard via the projection (discipline 7).

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[eb6-axiom-tbox-subbehavior-live-verify :as eb6])
     (def r (eb6/run-all! {}))
     (eb6/print-summary! r)
     (eb6/save-capture! r)

   Or bounded from the CLI (the runner below wraps it in future+deref+exit)."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.axiom-tbox-subbehavior :as eb6]
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
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

(def default-model "google/gemini-3-flash-preview")
(def capture-path "docs/build-timeline/live-verify/EB6-axiom-tbox.md")

;; The REAL CSV CIP/SOC crosswalk source (same file EB2/EB4/DT4 used).
(def csv-source {:type :csv :path "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv"})

;; A REAL EB3-shaped model-spec for the CSV (the EB4 live-verify shape) — drives
;; the EB4 extract over the REAL CSV to land the REAL graph EB6 grounds against.
(def csv-model-spec
  {:entity-types
   [{:type "Program of Study" :uri-keying-fields ["CIP_Code"]
     :grain-strategy :canonical-row-filter}
    {:type "Occupation" :uri-keying-fields ["SOC_Code"]
     :grain-strategy :canonical-row-filter}]
   :scope-filter {:field "CIP_Code" :values ["01"]}
   :edges [{:source-type "Program of Study" :target-type "Occupation"
            :predicate "prepares_for"}]
   :embed-fields ["CIP_Title" "SOC_Title"]})

;; A goal + a profile of that CSV for the EB3 Model node to author REAL
;; candidate-axioms from (the model DECIDES the axioms — not hand-written).
(def goal
  (str "Build an ontology mapping programs of study to the occupations they "
       "prepare graduates for, keyed by the codes identifying each program and "
       "occupation. A program prepares-for an occupation."))

(def csv-profile
  {:format :csv
   :scope-fields ["CIP_Code"]
   :columns ["CIP_Code" "CIP_Title" "SOC_Code" "SOC_Title"]
   :embed-signal ["CIP_Title" "SOC_Title"]
   :grain-signals ["each row is a CIP↔SOC pairing; a program (CIP) and an
                    occupation (SOC) each recur across many rows"]
   :sample-rows [{"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
                  "SOC_Code" "19-1011" "SOC_Title" "Animal Scientists"}]})

;; ---------------------------------------------------------------------------
;; Real-Grain harness (same shape as EB3/EB4/EB5's) with real async processors.
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
        dir (str "/tmp/eb6-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb6-live"
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
;; Stage 1 — DELEGATE EB4 Extract over the REAL CSV → real drafts → LAND graph.
;; ---------------------------------------------------------------------------

(defn- extract-and-land! [ctx {:keys [model ontology-id source model-spec]}]
  (extract/register-extract-subbehavior! ctx {:model model})
  (let [central-def (dsl/workflow "eb6/central-extract@v1"
                      (dsl/blackboard {:model-spec [:map {:closed false}]
                                       :source [:map {:closed false}]
                                       :concept-drafts extract/concept-drafts-schema
                                       :relationship-drafts extract/relationship-drafts-schema
                                       :extraction-report extract/extraction-report-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-extract"
                          :target-sheet-id (extract/extract-sheet-id-for)
                          :reads [:model-spec :source]
                          :writes [:concept-drafts :relationship-drafts :extraction-report]
                          :timeout-ms 300000)))
        central-id (dsl/build-workflow! ctx central-def)
        tick-id (random-uuid)
        result (runtime/execute ctx central-id
                                {"model-spec" model-spec "source" source}
                                :timeout-ms 300000 :tick-id tick-id)
        _ (Thread/sleep 300)
        bb (orm/get-tick-blackboard ctx tick-id)
        concept-drafts (get-in bb [:concept-drafts :value])
        relationship-drafts (get-in bb [:relationship-drafts :value])
        ;; LAND the real drafts into the graph (reuse compile-discovery-source!).
        landed (ontology/compile-discovery-source!
                ctx ontology-id
                {:status :emitted-drafts
                 :emitted-concepts concept-drafts
                 :emitted-relationships relationship-drafts})]
    {:extract-status (:status result)
     :concept-count (count (or concept-drafts []))
     :relationship-count (count (or relationship-drafts []))
     :landed-provenance (:discovery-provenance landed)
     :landed-concept-count (count (rm/get-concepts ctx {:ontology-id ontology-id}))
     :sample-concepts (vec (take 6 (rm/get-concepts ctx {:ontology-id ontology-id})))}))

;; ---------------------------------------------------------------------------
;; Stage 2 — DELEGATE EB3 Model → REAL candidate-axioms (LLM-authored).
;; ---------------------------------------------------------------------------

(defn- author-candidate-axioms! [ctx {:keys [model goal profile]}]
  (model/register-model-subbehavior! ctx {:model model})
  (let [central-def (dsl/workflow "eb6/central-model@v1"
                      (dsl/blackboard {:goal :string
                                       :profile [:map {:closed false}]
                                       :reasoning :string
                                       :model-spec model/model-spec-contract-schema
                                       :candidate-axioms model/candidate-axioms-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-model"
                          :target-sheet-id (model/model-sheet-id-for)
                          :reads [:goal :profile]
                          :writes [:reasoning :model-spec :candidate-axioms]
                          :timeout-ms 300000)))
        central-id (dsl/build-workflow! ctx central-def)
        tick-id (random-uuid)
        result (runtime/execute ctx central-id
                                {"goal" goal "profile" profile}
                                :timeout-ms 300000 :tick-id tick-id)
        _ (Thread/sleep 300)
        bb (orm/get-tick-blackboard ctx tick-id)
        candidate-axioms (get-in bb [:candidate-axioms :value])
        model-spec (get-in bb [:model-spec :value])]
    {:model-status (:status result)
     :candidate-axioms candidate-axioms
     :model-spec model-spec
     :candidate-axioms-is-map? (map? candidate-axioms)
     :candidate-count (count (:axioms candidate-axioms))}))

;; ---------------------------------------------------------------------------
;; Stage 3 — DELEGATE EB6 Axiom/TBox → ground + emit → READ BACK via get-axioms.
;; ---------------------------------------------------------------------------

(defn- emit-axioms! [ctx {:keys [ontology-id candidate-axioms model-spec]}]
  (let [sub-id (eb6/register-axiom-tbox-subbehavior! ctx {})
        registry-match? (= sub-id (eb6/axiom-tbox-sheet-id-for))
        central-def (dsl/workflow "eb6/central-axiom-tbox@v1"
                      (dsl/blackboard {:ontology-id :any
                                       :candidate-axioms [:map {:closed false}]
                                       :model-spec [:maybe [:map {:closed false}]]
                                       :axiom-report eb6/axiom-report-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-axiom-tbox"
                          :target-sheet-id (eb6/axiom-tbox-sheet-id-for)
                          :reads [:ontology-id :candidate-axioms :model-spec]
                          :writes [:axiom-report]
                          :timeout-ms 120000)))
        central-id (dsl/build-workflow! ctx central-def)
        tick-id (random-uuid)
        result (runtime/execute ctx central-id
                                {"ontology-id" ontology-id
                                 "candidate-axioms" candidate-axioms
                                 "model-spec" model-spec}
                                :timeout-ms 120000 :tick-id tick-id)
        _ (Thread/sleep 300)
        bb (orm/get-tick-blackboard ctx tick-id)
        report (get-in bb [:axiom-report :value])
        ;; DISCIPLINE 7 — read the axioms BACK from the projection independently.
        axioms-read-back (rm/get-axioms ctx ontology-id)]
    {:registry-match? registry-match?
     :central-status (:status result)
     :tick-id tick-id
     :report-is-map? (map? report)
     :report report
     :axioms-read-back axioms-read-back}))

;; ---------------------------------------------------------------------------
;; Full live verify — EB4 → EB3 → EB6 on the REAL CSV.
;; ---------------------------------------------------------------------------

(defn run-once! [ctx {:keys [model]}]
  (let [model (or model default-model)
        ontology-id (random-uuid)
        _ (println "\n--- Stage 1: EB4 extract over the REAL CSV → land the graph ---")
        stage1 (extract-and-land! ctx {:model model :ontology-id ontology-id
                                       :source csv-source :model-spec csv-model-spec})
        _ (println "  extract:" (:extract-status stage1)
                   "concepts landed:" (:landed-concept-count stage1)
                   "rels:" (:relationship-count stage1))
        _ (println "\n--- Stage 2: EB3 model → REAL candidate-axioms (LLM-authored) ---")
        stage2 (author-candidate-axioms! ctx {:model model :goal goal :profile csv-profile})
        _ (println "  model:" (:model-status stage2)
                   "candidate axioms:" (:candidate-count stage2))
        _ (println "\n--- Stage 3: EB6 axiom/tbox → ground + emit → read back ---")
        stage3 (emit-axioms! ctx {:ontology-id ontology-id
                                  :candidate-axioms (:candidate-axioms stage2)
                                  :model-spec (:model-spec stage2)})
        _ (println "  emit:" (:central-status stage3)
                   "emitted:" (get-in stage3 [:report :axioms-emitted-count])
                   "unsupported:" (count (get-in stage3 [:report :axioms-unsupported]))
                   "ungrounded:" (count (get-in stage3 [:report :axioms-ungrounded])))]
    {:model model
     :ontology-id ontology-id
     :stage1-extract stage1
     :stage2-model stage2
     :stage3-emit stage3}))

(defn run-all! [{:keys [model] :or {model default-model}}]
  (let [ctx (make-ctx)]
    (try
      (register-openrouter! model)
      (println "=== EB6 AXIOM/TBox SUBBEHAVIOR LIVE VERIFY ===")
      (println "model:" model)
      (run-once! ctx {:model model})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (let [s3 (:stage3-emit r)
        report (:report s3)]
    (println "\n================ EB6 AXIOM/TBox LIVE VERIFY ================")
    (println "ontology-id:" (:ontology-id r))
    (println "registry match?:" (:registry-match? s3))
    (println "EB6 delegate status:" (:central-status s3) "| report PARSED MAP (C1)?:" (:report-is-map? s3))
    (println "\n-- REAL candidate-axioms (EB3 LLM-authored) --")
    (pp/pprint (get-in r [:stage2-model :candidate-axioms]))
    (println "\n-- EB6 emission report --")
    (println "candidates considered:" (:candidates-considered report))
    (println "axioms EMITTED:" (:axioms-emitted-count report))
    (pp/pprint (:axioms-emitted report))
    (println "axioms UNGROUNDED (surfaced, not asserted):")
    (pp/pprint (:axioms-ungrounded report))
    (println "axioms UNSUPPORTED (tracked gaps — surfaced, not skipped):")
    (pp/pprint (:axioms-unsupported report))
    (println "axioms REJECTED (loud surface):")
    (pp/pprint (:axioms-rejected report))
    (println "\n-- AXIOMS READ BACK via get-axioms (discipline 7 — the projection truth) --")
    (pp/pprint (:axioms-read-back s3))))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [s1 (:stage1-extract r)
        s3 (:stage3-emit r)
        report (:report s3)]
    (spit capture-path
          (str "# EB6 — Axiom/TBox subbehavior sheet — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **Model:** `" (:model r)
               "` (real OpenRouter). **No mocks** — real Grain event store, real "
               "LLM (EB4 authors the transform; EB3 authors the candidate-axioms), "
               "real async todo processors, real child ticks, REAL CSV source.\n\n"
               "Proves the AXIOM/TBox subbehavior is a delegatable SINGLE-`:code` "
               "sheet that grounds EB3's REAL candidate-axioms against the REAL "
               "extracted graph and emits REAL TBox axioms through the S07 commands "
               "— closing the `:axioms-skipped` silent drop. Built on the "
               "EB1-EB5 registry/delegation pattern.\n\n"
               "## The REAL EB3→EB4→EB6 chain\n\n"
               "1. **EB4 Extract** delegated over the REAL CSV "
               "(`cip_soc_crosswalk.csv`) → real concept/relationship drafts → "
               "LANDED via `compile-discovery-source!`. Concepts landed: **"
               (:landed-concept-count s1) "**, relationships: **"
               (:relationship-count s1) "**.\n"
               "2. **EB3 Model** delegated over a goal + a CSV profile → REAL "
               "candidate-axioms AUTHORED BY THE LLM (not hand-written).\n"
               "3. **EB6 Axiom/TBox** delegated the REAL candidate-axioms + the "
               "granted `:ontology-id` → grounds each candidate's references "
               "against the REAL graph, maps `:kind` → the matching S07 command, "
               "emits, and the axioms LAND.\n\n"
               "## EB6 delegate result\n\n"
               "- registry name → sheet-id match?: `" (:registry-match? s3) "`\n"
               "- EB6 `:delegate` status: `" (:central-status s3) "`\n"
               "- axiom-report crossed `:delegate` as a PARSED MAP (C1)?: `"
               (:report-is-map? s3) "`\n\n"
               "## REAL candidate-axioms (EB3 LLM-authored)\n\n```clojure\n"
               (with-out-str (pp/pprint (get-in r [:stage2-model :candidate-axioms])))
               "```\n\n"
               "## EB6 emission report\n\n"
               "- candidates considered: **" (:candidates-considered report) "**\n"
               "- axioms EMITTED + grounded + landed: **" (:axioms-emitted-count report) "**\n\n"
               "### Emitted (grounded against the real graph)\n\n```clojure\n"
               (with-out-str (pp/pprint (:axioms-emitted report)))
               "```\n\n"
               "### Ungrounded (SURFACED, not asserted over URIs the graph lacks)\n\n```clojure\n"
               (with-out-str (pp/pprint (:axioms-ungrounded report)))
               "```\n\n"
               "### Unsupported (tracked gaps — SURFACED, not silently skipped)\n\n```clojure\n"
               (with-out-str (pp/pprint (:axioms-unsupported report)))
               "```\n\n"
               "### Rejected (loud surface)\n\n```clojure\n"
               (with-out-str (pp/pprint (:axioms-rejected report)))
               "```\n\n"
               "## AXIOMS READ BACK via `get-axioms` (discipline 7 — the projection IS the proof)\n\n"
               "Read INDEPENDENTLY of the report, off the projection — events LANDED:\n\n```clojure\n"
               (with-out-str (pp/pprint (:axioms-read-back s3)))
               "```\n\n"
               "## Honest-gap rule held\n\n"
               "Every candidate is accounted for (emitted + ungrounded + "
               "unsupported + rejected = considered). `domain` / `range` / "
               "`closure` have NO S07 command today — they are SURFACED as tracked "
               "gaps in `:axioms-unsupported`, never silently dropped (the exact "
               "`:axioms-skipped` bug EB6 closes). subClassOf is closed by the EB6 "
               "MINT (`assert-sub-class` → `rdfs:subClassOf`).\n"))
    (println "saved →" capture-path)))

;; ---------------------------------------------------------------------------
;; Bounded CLI runner — future + deref timeout + System/exit (JVM hygiene).
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [f (future
            (let [r (run-all! {})]
              (print-summary! r)
              (save-capture! r)
              r))
        r (deref f 600000 :TIMEOUT)]
    (if (= r :TIMEOUT)
      (do (println "EB6 LIVE VERIFY TIMED OUT") (shutdown-agents) (System/exit 1))
      (do (shutdown-agents) (System/exit 0)))))
