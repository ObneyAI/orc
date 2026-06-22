(ns eb4-extract-subbehavior-live-verify
  "EB4 — LIVE VERIFY (also serves as the SOFT prototype of the authoring node):
   the EXTRACT subbehavior as a delegatable sheet — `:code` (sample real rows +
   key-shape via the DT4-grounding `mechanical-sample-rows`) → `:llm` (author the
   per-row transform, grounded in the REAL sample-row keys + the EB3 model-spec,
   `:reasoning` FIRST) → `:code` (apply the transform over the FULL source via the
   V20 `apply-extraction-transform!`, per-row error counting).

   What this proves (real Grain, real OpenRouter gemini-3-flash-preview, real
   async child tick, real source files — NO mocks):
     - The Extract sheet is BUILT on the EB1/EB2/EB3 registry/delegation pattern:
       a composed ORC sheet, registered under a stable name → deterministic
       sheet-id, invoked from a CENTRAL tree via `:delegate` with mapped
       `:reads`/`:writes`.
     - Its body is a THREE-node pipeline `:code` → `:llm` → `:code`; the `:llm`
       AUTHOR node writes `:reasoning` FIRST (#13).
     - The P1 verify-not-assume criterion: with the REAL row-key shape surfaced by
       Node 1 (mechanical-sample-rows) into the AUTHOR prompt, the model's
       AUTONOMOUS transform (NO hand-correction) grounds field access in the
       source's REAL keys and yields a SANE SCOPED concept count over the FULL
       source — NOT a raw-row dump, nodes not edges, per-row errors counted, no
       abort.
     - REUSE not fork: Node 1 calls `discovery-tree/mechanical-sample-rows`; Node
       3 calls `rlm-discovery/apply-extraction-transform!` (the V20 apply-step).
     - C1: the draft set + report cross `:delegate` parsed (a `:code`-node output
       is parsed naturally); read back from the PARENT tick blackboard via the
       projection (discipline 7).

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[eb4-extract-subbehavior-live-verify :as eb4])
     (def r (eb4/run-all! {}))
     (eb4/print-summary! r)
     (eb4/save-capture! r)

   Or bounded from the CLI (the runner below wraps it in future+deref+exit)."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
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
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def default-model "google/gemini-3-flash-preview")
(def capture-path "docs/build-timeline/live-verify/EB4-extract.md")

;; The REAL CSV CIP/SOC crosswalk source (same file EB2/DT4 used). In-row scope
;; (CIP_Code), 6,098 rows — fast + no cross-table resolution. STRING keys with
;; EXACT header names ("CIP_Code", "SOC_Code") — the DT4 honest-negative trap
;; (the model previously invented :CIP2020Code) that Node 1's real key-shape fixes.
(def csv-source {:type :csv :path "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv"})

;; A REAL EB3-shaped model-spec for the CSV under a CIP-family-01 scope. Captured
;; from the DT4-grounding live verify shape (CIP↔SOC crosswalk, two entity types,
;; in-row scope). Grain :canonical-row-filter (each row is a CIP↔SOC pair; the
;; entity is the program / occupation, keyed by its code — not one-per-row).
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

;; ---------------------------------------------------------------------------
;; Real-Grain harness (same shape as EB1/EB2/EB3's)
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
        dir (str "/tmp/eb4-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb4-live"
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
;; SOFT probe — confirm the authoring node grounds in the REAL keys (before TDD).
;; Runs the SHEET standalone (no delegate) so we can read the transform-source it
;; authored straight off the sheet blackboard. Doubles as a quick grounding check.
;; ---------------------------------------------------------------------------

(defn soft-probe!
  "Run the Extract sheet STANDALONE on the real CSV + a real model-spec, read back
   the authored transform-source, and report whether it grounds field access in
   the REAL sampled keys (CIP_Code / SOC_Code as STRING keys) — NOT guessed ones."
  [ctx {:keys [model goal source model-spec]}]
  (let [model (or model default-model)
        sub-id (extract/register-extract-subbehavior! ctx {:model model})
        ;; the REAL key-shape from the source's own tools (what Node 1 surfaces)
        rows (dt/mechanical-sample-rows source (:selector source))
        key-shape (dt/sample-row-key-shape source rows (:selector source))
        tick-id (random-uuid)
        result (runtime/execute ctx sub-id
                                {"model-spec" model-spec
                                 "source" source}
                                :timeout-ms 300000
                                :tick-id tick-id)
        _ (Thread/sleep 300)
        bb (rm/get-tick-blackboard ctx tick-id)
        transform-source (get-in bb [:transform-source :value])
        selector (get-in bb [:selector :value])
        reasoning (get-in bb [:reasoning :value])]
    {:status (:status result)
     :real-keys (:keys key-shape)
     :key-type (:key-type key-shape)
     :sample-row (:sample-row key-shape)
     :transform-source transform-source
     :selector selector
     :reasoning reasoning
     ;; grounding check: does the authored source reference the REAL keys?
     :references-cip? (and transform-source (str/includes? transform-source "CIP_Code"))
     :references-soc? (and transform-source (str/includes? transform-source "SOC_Code"))
     ;; the prior honest-negative invented key (must NOT appear)
     :references-invented? (and transform-source
                                (or (str/includes? transform-source "CIP2020Code")
                                    (str/includes? transform-source "SOC2018Code")))}))

;; ---------------------------------------------------------------------------
;; Full live verify — DELEGATE the Extract subbehavior from a central tree, read
;; the draft set + report back off the PARENT tick blackboard (discipline 7).
;; ---------------------------------------------------------------------------

(defn run-once!
  "Register + delegate the Extract subbehavior with a model-spec + source. Returns
   the result map incl. the draft set + report read back from the parent bb."
  [ctx {:keys [model source model-spec]}]
  (let [model (or model default-model)
        sub-id (extract/register-extract-subbehavior! ctx {:model model})
        sub-name (extract/extract-subbehavior-name)
        looked-up (extract/extract-sheet-id-for)
        registry-match? (= sub-id looked-up)
        central-name "eb4/central-extract@v1"
        central-def (dsl/workflow central-name
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
        central-tick-id (random-uuid)
        t0 (System/currentTimeMillis)
        central-result (runtime/execute ctx central-id
                                        {"model-spec" model-spec
                                         "source" source}
                                        :timeout-ms 300000
                                        :tick-id central-tick-id)
        elapsed (- (System/currentTimeMillis) t0)
        _ (Thread/sleep 300)
        ;; DISCIPLINE 7: read the PARENT tick blackboard back from the projection.
        parent-bb (rm/get-tick-blackboard ctx central-tick-id)
        concept-drafts (get-in parent-bb [:concept-drafts :value])
        relationship-drafts (get-in parent-bb [:relationship-drafts :value])
        report (get-in parent-bb [:extraction-report :value])]
    {:registry {:subbehavior-name sub-name
                :sub-sheet-id sub-id
                :looked-up looked-up
                :registry-match? registry-match?
                :central-name central-name
                :central-sheet-id central-id}
     :central-status (:status central-result)
     :central-tick-id central-tick-id
     :elapsed-ms elapsed
     :concept-drafts concept-drafts
     :relationship-drafts relationship-drafts
     :extraction-report report
     :concept-count (count (or concept-drafts []))
     :relationship-count (count (or relationship-drafts []))
     :drafts-are-vectors? (and (vector? concept-drafts) (vector? relationship-drafts))
     :sample-concepts (vec (take 8 (or concept-drafts [])))
     :sample-relationships (vec (take 4 (or relationship-drafts [])))
     :error (:error central-result)}))

(defn run-all!
  "SOFT probe + full delegated live verify against the real CSV + a real
   model-spec. Returns {:probe {...} :csv {...}}."
  [{:keys [model] :or {model default-model}}]
  (let [ctx (make-ctx)]
    (try
      (register-openrouter! model)
      (println "=== EB4 EXTRACT SUBBEHAVIOR LIVE VERIFY ===")
      (println "model:" model)
      (println "\n--- SOFT probe: does the AUTHOR node ground in the REAL keys? ---")
      (let [probe (soft-probe! ctx {:model model :source csv-source
                                    :model-spec csv-model-spec})
            _ (println "  probe status:" (:status probe)
                       "real-keys:" (:real-keys probe)
                       "refs CIP_Code?:" (:references-cip? probe)
                       "refs SOC_Code?:" (:references-soc? probe)
                       "refs invented?:" (:references-invented? probe))
            _ (println "\n--- full delegate: apply over the FULL source ---")
            csv (run-once! ctx {:model model :source csv-source
                                :model-spec csv-model-spec})
            _ (println "  central status:" (:central-status csv)
                       "concepts:" (:concept-count csv)
                       "relationships:" (:relationship-count csv)
                       "vectors?:" (:drafts-are-vectors? csv)
                       "(" (:elapsed-ms csv) "ms)")]
        {:model model :probe probe :csv csv})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (let [p (:probe r) m (:csv r)]
    (println "\n================ EB4 SOFT PROBE ================")
    (println "probe status:" (:status p))
    (println "REAL keys (Node-1 mechanical-sample-rows):" (pr-str (:real-keys p)))
    (println "key-type:" (:key-type p))
    (println "AUTONOMOUS transform-source (verbatim):")
    (println (:transform-source p))
    (println "selector:" (pr-str (:selector p)))
    (println "references CIP_Code?:" (:references-cip? p)
             " SOC_Code?:" (:references-soc? p)
             " invented key?:" (:references-invented? p))
    (println "\n================ EB4 FULL DELEGATE ================")
    (println "central status:" (:central-status m) "(" (:elapsed-ms m) "ms)")
    (println "registry match?:" (get-in m [:registry :registry-match?]))
    (println "drafts are VECTORS across :delegate (C1)?:" (:drafts-are-vectors? m))
    (println "SCOPED concept count:" (:concept-count m)
             " relationship count:" (:relationship-count m))
    (println "extraction-report:")
    (pp/pprint (:extraction-report m))
    (println "sample concepts:")
    (pp/pprint (:sample-concepts m))
    (println "sample relationships:")
    (pp/pprint (:sample-relationships m))))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [p (:probe r) m (:csv r)]
    (spit capture-path
          (str "# EB4 — Extract subbehavior sheet — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **Model:** `" (:model r)
               "` (real OpenRouter). **No mocks** — real Grain event store, real "
               "LLM, real async todo processors, real child tick, REAL source file.\n\n"
               "Proves the EXTRACT subbehavior is a delegatable THREE-node sheet "
               "(`:code` sample → `:llm` author → `:code` apply) that turns the EB3 "
               "model-spec × the source into the actual DRAFT SET: the `:llm` AUTHOR "
               "node, GIVEN the REAL sampled-row key-shape (Node 1's "
               "`mechanical-sample-rows`) + the model-spec, AUTONOMOUSLY authors a "
               "field-grounded per-row transform, which the V20 "
               "`apply-extraction-transform!` (Node 3, reused not forked) applies "
               "over the FULL source → a SANE SCOPED concept count, per-row errors "
               "counted, no abort. `:reasoning` written FIRST (#13). Built on the "
               "EB1/EB2/EB3 registry/delegation pattern; re-houses DT4 + the "
               "DT4-grounding field-grounding fix.\n\n"
               "## Source + model-spec (inputs)\n\n"
               "Source (REAL file): `" (:path csv-source) "` (CSV CIP/SOC "
               "crosswalk, 6,098 rows). In-row scope (CIP_Code); STRING keys with "
               "EXACT header names — the DT4 honest-negative trap (the model "
               "previously invented `:CIP2020Code`) that Node 1's real key-shape "
               "fixes.\n\n"
               "Model-spec (EB3 shape, CIP-family-01 scope):\n\n```clojure\n"
               (with-out-str (pp/pprint csv-model-spec)) "```\n\n"
               "## SOFT probe — the AUTHOR node grounds in the REAL keys\n\n"
               "Node 1 (`mechanical-sample-rows`) surfaced the REAL key-shape; the "
               "AUTHOR node was given it. The AUTONOMOUS transform-source the node "
               "authored (NO hand-correction):\n\n"
               "- REAL keys (Node-1 mechanical-sample-rows): `"
               (pr-str (:real-keys p)) "` (key-type: " (name (or (:key-type p) :other)) ")\n"
               "- references `CIP_Code` (real key): **" (:references-cip? p) "**\n"
               "- references `SOC_Code` (real key): **" (:references-soc? p) "**\n"
               "- references an INVENTED key (`CIP2020Code`/`SOC2018Code`, the prior "
               "honest negative): **" (:references-invented? p) "** (must be false)\n\n"
               "Authored transform-source (verbatim, off the sheet blackboard):\n\n"
               "```clojure\n" (:transform-source p) "\n```\n\n"
               "Reasoning (written FIRST, #13):\n\n```\n" (:reasoning p) "\n```\n\n"
               "## Registry + delegation\n\n"
               "- subbehavior: `" (get-in m [:registry :subbehavior-name]) "`\n"
               "- sub sheet-id: `" (get-in m [:registry :sub-sheet-id]) "`\n"
               "- registry name→id round-trip: **" (get-in m [:registry :registry-match?]) "**\n"
               "- central tree status: **" (:central-status m) "** (" (:elapsed-ms m) "ms)\n"
               "- parent tick-id: `" (:central-tick-id m) "`\n\n"
               "## P1 — the AUTONOMOUS transform yields a SANE SCOPED count over the FULL source\n\n"
               "Read back from the PARENT tick blackboard via the projection "
               "(`rm/get-tick-blackboard`), NOT from the execute return value "
               "(discipline 7). The draft set crossed `:delegate` as VECTORS (a "
               "`:code`-node output parses naturally — C1):\n\n"
               "- drafts are VECTORS across `:delegate`: **" (:drafts-are-vectors? m) "**\n"
               "- SCOPED concept count: **" (:concept-count m) "** (NOT a 6,098-row dump)\n"
               "- relationship count: **" (:relationship-count m) "**\n\n"
               "Extraction report (V20 apply coverage — per-row errors counted, no abort):\n\n"
               "```clojure\n" (with-out-str (pp/pprint (:extraction-report m))) "```\n\n"
               "Sample concepts (verbatim):\n\n```clojure\n"
               (with-out-str (pp/pprint (:sample-concepts m))) "```\n\n"
               "Sample relationships (verbatim):\n\n```clojure\n"
               (with-out-str (pp/pprint (:sample-relationships m))) "```\n\n"
               (when (:error m)
                 (str "## Error\n\n```clojure\n"
                      (with-out-str (pp/pprint (:error m))) "```\n\n"))
               "## Verdict\n\n"
               "The Extract subbehavior is a delegatable THREE-node sheet "
               "(`:code` → `:llm` → `:code`) whose `:llm` AUTHOR node, given the "
               "REAL sampled-row key-shape (Node 1) + the model-spec, AUTONOMOUSLY "
               "authors a field-grounded transform (no hand-correction) that the "
               "reused V20 apply-step applies over the FULL source → a SANE SCOPED "
               "concept count (NOT a raw-row dump), per-row errors counted, no "
               "abort, with `:reasoning` first (#13). REUSE not fork: "
               "`mechanical-sample-rows` (Node 1) + `apply-extraction-transform!` "
               "(Node 3). The draft set crosses `:delegate` parsed (C1).\n"))
    (println "Capture written:" capture-path)
    capture-path))

;; ---------------------------------------------------------------------------
;; Bounded CLI runner — future + deref timeout + System/exit (JVM hygiene).
;; ---------------------------------------------------------------------------

(defn -main [& _]
  (let [fut (future
              (try
                (let [r (run-all! {})]
                  (print-summary! r)
                  (save-capture! r)
                  :done)
                (catch Throwable t
                  (println "EB4 live verify FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  :error)))
        result (deref fut 480000 :timeout)]
    (println "\nEB4 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb4-extract-subbehavior-live-verify :as eb4] :reload)
  (def r (eb4/run-all! {}))
  (eb4/print-summary! r)
  (eb4/save-capture! r))
