(ns eb8-validate-cq-subbehavior-live-verify
  "EB8 — LIVE VERIFY: the VALIDATE+CQ subbehavior as a delegatable sheet — a
   `:llm` DERIVE → `:code` PERSIST → `:code` GATE pipeline that (1) DERIVES the
   competency questions from the GOAL ⨯ the source PROFILE(s) (grounded +
   goal-anchored), (2) PERSISTS them as the S14 ORSD spec (the SAME spec build!'s
   S15 gate reads), and (3) runs the S15 CQ gate (SEMANTIC retrieve-then-judge) →
   the per-CQ verdict + graph-health. The derived CQs + rationale are SURFACED for
   HITL review.

   What this proves with REAL Grain + REAL OpenRouter LLM (derive + judge) + REAL
   ColBERT/embeddings (the S15 retrieval) — NO mocks:
     - The Validate+CQ sheet is BUILT on the EB1-EB7 registry/delegation pattern: a
       composed ORC sheet, registered under a stable name → deterministic sheet-id,
       invoked from a CENTRAL tree via `:delegate` with mapped `:reads`/`:writes`.
     - DERIVE: on a REAL profile + goal, the `:llm` node derives GROUNDED,
       GOAL-ANCHORED CQs (captured verbatim for HITL review).
     - PERSIST: the CQs land as the ORSD spec — read back via `get-ontology-spec`
       (discipline 7 — the projection, NOT a return value), proving they are the
       gate spec.
     - OVERRIDE: a second run with consumer-supplied CQs persists the SUPPLIED set
       (the HITL override path).
     - GATE: the S15 runner judges the persisted CQs with a REAL LLM judge (the
       production prompt) over the REAL graph (three-layer retrieve-then-judge) →
       per-CQ :pass/:fail/:unknown (read back via `get-cq-evaluation-latest`) +
       graph-health (unknown-rate first-class).

   The judge-fn is a Clojure FN VALUE (it closes over dscloj/OpenRouter), so it is
   NOT routed across the `:delegate` blackboard (which is event-sourced). Two
   captures: (A) the DELEGATED sheet path (DERIVE + PERSIST cross `:delegate`; the
   gate runs with no LLM judge so it surfaces the Layer-1 + honest-no-judge
   verdicts), and (B) the FULL GATE path — `run-gate!` called directly with the
   REAL LLM judge on the persisted spec, capturing the full retrieve-then-judge
   per-CQ verdicts + graph-health. Both are real; the split is the honest fn-value
   boundary, not a mock.

   USAGE (REPL with :dev:test; needs OPENROUTER_API_KEY + (for the ColBERT signal)
   the Python ColBERT bridge up):
     export OPENROUTER_API_KEY=\"sk-or-v1-...\"
     (require '[eb8-validate-cq-subbehavior-live-verify :as eb8])
     (def r (eb8/run-all! {}))
     (eb8/print-summary! r)
     (eb8/save-capture! r)

   Or bounded from the CLI via -main (future + deref timeout + System/exit)."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]
            [ai.obney.orc.ontology.core.validate-cq-subbehavior :as vcq]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.time.interface :as time]
            [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def capture-path "docs/build-timeline/live-verify/EB8-validate-cq.md")
(def default-llm-model "google/gemini-3-flash-preview")

;; ---------------------------------------------------------------------------
;; A REAL profile + goal. The profile is a captured-real EB2/DT2 profile (the
;; education crosswalk shape, VERBATIM). The graph below is a small real graph
;; whose concepts the goal's CQs are answerable over — so the gate has real
;; retrieve-then-judge work to do, not a degenerate empty graph.
;; ---------------------------------------------------------------------------

(def the-goal
  "Build an ontology connecting fields/programs of study to the occupations they prepare people for.")

(def csv-profile
  {:entity-candidates
   "Academic Programs (CIP), Occupational Titles (SOC), Crosswalk/Alignment mappings."
   :identifying-keys "'CIPCode', 'SOCCode'"
   :scope-fields "'CIPTitle', 'SOCTitle'"
   :linking-keys "'CIPCode', 'SOCCode'"
   :grain-signals "A many-to-many mapping; one program leads to many occupations."
   :sample [{"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
             "SOC_Code" "19-1011" "SOC_Title" "Animal Scientists"}]})

(def sql-profile
  {:entity-candidates ["Higher Education Institutions" "States"]
   :identifying-keys {"Higher Education Institutions" ["UNITID" "OPEID"]}
   :scope-fields ["STABBR" "SECTOR"]
   :linking-keys ["UNITID" "STABBR"]
   :grain-signals ["UNITID repeated across years"]
   :sample [{:UNITID 100654 :INSTNM "Alabama A & M University" :STABBR "AL"}]})

(def the-profile [csv-profile sql-profile])

;; A small real graph the CQs can be judged over — programs linked to occupations,
;; plus an institution. Free-text labels/descriptions so the S15 retrieval (embed +
;; lexical) has real signal. Domain-neutral fields; the goal supplies the focus.
(def graph-concepts
  [{:uri "concept:program/agriculture" :label "Agriculture, General"
    :description "An academic program of study in general agriculture (CIP 01.0000)."}
   {:uri "concept:program/nursing" :label "Registered Nursing"
    :description "An academic program preparing students for nursing careers (CIP 51.3801)."}
   {:uri "concept:occupation/animal-scientist" :label "Animal Scientists"
    :description "An occupation studying animals and livestock (SOC 19-1011)."}
   {:uri "concept:occupation/nurse" :label "Registered Nurses"
    :description "An occupation providing patient care in hospitals (SOC 29-1141)."}
   {:uri "concept:institution/alabama-am" :label "Alabama A & M University"
    :description "A higher education institution in Alabama (UNITID 100654)."}])

(def graph-edges
  [["concept:program/agriculture" "prepares-for" "concept:occupation/animal-scientist"]
   ["concept:program/nursing" "prepares-for" "concept:occupation/nurse"]
   ["concept:institution/alabama-am" "offers" "concept:program/agriculture"]])

(def consumer-override-cqs
  ["Which institutions offer a given program of study?"
   "What occupation does a given program prepare students for?"])

;; ---------------------------------------------------------------------------
;; The real LLM judge — wires the production S15 prompt template + dscloj (the
;; SAME judge a production consumer wires; identical to the S15 live verify).
;; ---------------------------------------------------------------------------

(defn register-openrouter! [model]
  (litellm-router/register! :openrouter
                            {:provider :openrouter
                             :model model
                             :config {:api-base "https://openrouter.ai/api/v1"
                                      :api-key (System/getenv "OPENROUTER_API_KEY")}}))

(defn real-llm-judge
  [{:keys [question evidence]}]
  (let [prompt (cqr/render-judge-prompt question evidence)
        module {:inputs  [{:name :request :spec :string
                           :description "The CQ + retrieved evidence"}]
                :outputs [{:name :verdict :spec :string
                           :description "One of: pass, fail, unknown"}
                          {:name :reasoning :spec :string
                           :description "Why; on :unknown name the missing fact-kind"}
                          {:name :evidence-uris :spec [:vector :string]
                           :description "URIs from the evidence that drove the verdict"}
                          {:name :gaps :spec [:vector :string]
                           :description "Missing fact-kinds on :unknown; empty on :pass/:fail"}]
                :instructions prompt}
        result (dscloj/predict :openrouter module
                               {:request "Evaluate per the rubric above."}
                               {:validate? false :with-metadata? false})
        outputs (or (:outputs result) result)
        raw (str/trim (str/lower-case (or (:verdict outputs) "")))
        verdict (cond
                  (#{"pass" "yes" "true"} raw) :pass
                  (#{"fail" "no" "false"} raw) :fail
                  (#{"unknown" "uncertain"} raw) :unknown
                  :else (throw (ex-info "Judge returned unparseable verdict"
                                        {:raw raw :outputs outputs})))]
    {:verdict verdict
     :reasoning (or (:reasoning outputs) "")
     :evidence-uris (vec (or (:evidence-uris outputs) []))
     :gaps (vec (or (:gaps outputs) []))}))

;; ---------------------------------------------------------------------------
;; Real-Grain harness WITH the real todo processors (the :delegate child tick is
;; driven by a todo-processor) — mirrors the EB5/EB7 live-verify harness.
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb8-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb8-live"
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

(defn- land-graph! [ctx oid]
  (ontology/compile-discovery-source!
   ctx oid {:status :emitted-drafts
            :emitted-concepts graph-concepts
            :emitted-relationships
            (mapv (fn [[s p t]] {:source-uri s :predicate p :target-uri t})
                  graph-edges)})
  (Thread/sleep 200))

;; ---------------------------------------------------------------------------
;; Capture A — the DELEGATED sheet path (DERIVE + PERSIST cross :delegate; the
;; gate runs with no LLM judge — surfaces Layer-1 + honest no-judge verdicts).
;; ---------------------------------------------------------------------------

(defn delegate-run! [ctx oid]
  (let [sub-id (vcq/register-validate-cq-subbehavior! ctx {:model default-llm-model})
        looked-up (vcq/validate-cq-sheet-id-for)
        registry-match? (= sub-id looked-up)
        central-name "eb8/central-validate-cq@v1"
        central-def (dsl/workflow central-name
                      (dsl/blackboard {:ontology-id :any
                                       :goal :string
                                       :profile vcq/profile-read-schema
                                       :consumer-cqs vcq/consumer-cqs-schema
                                       :judge-fn :any
                                       vcq/competency-questions-key vcq/competency-questions-schema
                                       vcq/cq-verdict-key vcq/cq-verdict-schema
                                       vcq/graph-health-key vcq/graph-health-schema})
                      (dsl/sequence "central-root"
                        (dsl/delegate "to-validate-cq"
                          :target-sheet-id (vcq/validate-cq-sheet-id-for)
                          :reads [:ontology-id :goal :profile :consumer-cqs :judge-fn]
                          :writes [vcq/competency-questions-key vcq/cq-verdict-key vcq/graph-health-key]
                          :timeout-ms 180000)))
        central-id (dsl/build-workflow! ctx central-def)
        tick-id (random-uuid)
        t0 (System/currentTimeMillis)
        central-result (runtime/execute
                        ctx central-id
                        {"ontology-id" oid
                         "goal" the-goal
                         "profile" the-profile}
                        :timeout-ms 180000
                        :tick-id tick-id)
        elapsed (- (System/currentTimeMillis) t0)
        _ (Thread/sleep 300)
        ;; DISCIPLINE 7: read the outputs off the PARENT tick blackboard (projection).
        parent-bb (orm/get-tick-blackboard ctx tick-id)
        derived-cqs (get-in parent-bb [vcq/competency-questions-key :value])
        ;; the persisted spec — the LOAD-BEARING proof the CQs are the gate spec.
        spec (ontology/get-ontology-spec ctx oid)]
    {:registry {:subbehavior-name (vcq/validate-cq-subbehavior-name)
                :sub-sheet-id sub-id :looked-up looked-up
                :registry-match? registry-match? :central-sheet-id central-id}
     :central-status (:status central-result)
     :central-tick-id tick-id
     :elapsed-ms elapsed
     :derived-cqs derived-cqs
     :derived-is-vector? (vector? derived-cqs)
     :persisted-spec spec
     :persisted-cqs (:competency-questions spec)
     :error (:error central-result)}))

;; ---------------------------------------------------------------------------
;; Capture B — the FULL GATE path: run-gate! directly with the REAL LLM judge on
;; the persisted spec (the retrieve-then-judge verdicts read back via the
;; projection). Captures per-CQ verdicts + graph-health.
;; ---------------------------------------------------------------------------

(defn full-gate-run! [ctx oid]
  (let [result (vcq/run-gate! ctx {:ontology-id oid :judge-fn real-llm-judge})
        latest (ontology/get-cq-evaluation-latest ctx oid)]
    {:cq-verdict (:cq-verdict result)
     :cq-verdict-read-back (vec latest)
     :graph-health (:graph-health result)
     :evaluated-count (:evaluated-count result)}))

;; ---------------------------------------------------------------------------
;; Capture C — the consumer-CQ OVERRIDE path (a fresh ontology-id; supplied CQs
;; persist instead of derived). Proven by reading the spec back (#7).
;; ---------------------------------------------------------------------------

(defn override-run! [ctx]
  (let [oid (random-uuid)
        _ (land-graph! ctx oid)
        result (vcq/persist-cqs! ctx {:ontology-id oid :goal the-goal
                                      :derived-cqs ["this derived set should be discarded?"]
                                      :consumer-cqs consumer-override-cqs})
        _ (Thread/sleep 100)
        spec (ontology/get-ontology-spec ctx oid)]
    {:ontology-id oid
     :origin (:origin result)
     :supplied consumer-override-cqs
     :persisted-cqs (:competency-questions spec)
     :override-held? (= consumer-override-cqs (:competency-questions spec))}))

(defn run-all! [_opts]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required" {})))
  (register-openrouter! default-llm-model)
  (let [ctx (make-ctx)]
    (try
      (println "=== EB8 VALIDATE+CQ SUBBEHAVIOR LIVE VERIFY ===")
      (let [oid (random-uuid)
            _ (land-graph! ctx oid)
            concepts-landed (count (rm/get-concepts ctx {:ontology-id oid}))
            _ (println "  graph landed:" concepts-landed "concepts")
            _ (println "  [A] delegating the Validate+CQ sheet (real LLM derive)...")
            a (delegate-run! ctx oid)
            _ (println "  [A] central status:" (:central-status a) "(" (:elapsed-ms a) "ms)")
            _ (println "  [A] derived CQs:")
            _ (doseq [q (:derived-cqs a)] (println "        -" q))
            _ (println "  [B] running the full S15 gate with the REAL LLM judge...")
            b (full-gate-run! ctx oid)
            _ (println "  [B] graph-health:" (pr-str (:graph-health b)))
            _ (println "  [C] consumer-CQ override path...")
            c (override-run! ctx)
            _ (println "  [C] override held?:" (:override-held? c))]
        {:ontology-id oid
         :concepts-landed concepts-landed
         :goal the-goal
         :profile the-profile
         :delegate a
         :gate b
         :override c})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (println "\n================ EB8 VALIDATE+CQ LIVE VERIFY ================")
  (println "ontology-id:" (:ontology-id r))
  (println "concepts landed:" (:concepts-landed r))
  (println "\n--- [A] DELEGATED sheet (DERIVE + PERSIST cross :delegate) ---")
  (println "registry match?:" (get-in r [:delegate :registry :registry-match?]))
  (println "central status:" (get-in r [:delegate :central-status])
           "(" (get-in r [:delegate :elapsed-ms]) "ms)")
  (println "derived CQs cross :delegate as a parsed VECTOR?:" (get-in r [:delegate :derived-is-vector?]))
  (println "\nDERIVED CQs (surfaced for HITL review):")
  (doseq [q (get-in r [:delegate :derived-cqs])] (println "   -" q))
  (println "\nPERSISTED ORSD spec (read back via get-ontology-spec, #7):")
  (pp/pprint (get-in r [:delegate :persisted-spec]))
  (println "\n--- [B] FULL S15 GATE (real retrieve-then-judge LLM) ---")
  (doseq [{:keys [cq-text verdict reasoning judged-by? layer]} (get-in r [:gate :cq-verdict])]
    (println (format "   %-8s judge=%s layer=%s  %s"
                     (name (or verdict :?)) (if judged-by? "Y" "N") (or layer "?")
                     (subs (str cq-text) 0 (min 70 (count (str cq-text))))))
    (when (= :unknown verdict)
      (println (format "       (unknown — %s)"
                       (subs (str reasoning) 0 (min 120 (count (str reasoning))))))))
  (println "\ngraph-health:")
  (pp/pprint (get-in r [:gate :graph-health]))
  (println "\n--- [C] consumer-CQ OVERRIDE ---")
  (println "override held? (supplied persisted, not derived):" (get-in r [:override :override-held?]))
  (println "persisted (supplied) CQs:")
  (doseq [q (get-in r [:override :persisted-cqs])] (println "   -" q)))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [a (:delegate r) b (:gate r) c (:override r)]
    (spit capture-path
          (str "# EB8 — Validate+CQ subbehavior sheet — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **No mocks** — real "
               "Grain event store, real OpenRouter LLM (CQ derivation + the S15 "
               "judge, model `" default-llm-model "`), real ColBERT/embedding "
               "retrieval (the S15 three-layer runner), real child tick.\n\n"
               "Proves the VALIDATE+CQ subbehavior is a delegatable `:llm` DERIVE → "
               "`:code` PERSIST → `:code` GATE sheet that, on a REAL profile + goal, "
               "DERIVES grounded goal-anchored competency questions, PERSISTS them as "
               "the S14 ORSD spec the S15 gate reads, and GATES the built graph with "
               "the S15 SEMANTIC retrieve-then-judge runner → per-CQ "
               "`:pass`/`:fail`/`:unknown` + graph-health. The derived CQs are "
               "SURFACED for HITL review; consumer-supplied CQs OVERRIDE the derived "
               "set. Built on the EB1-EB7 registry/delegation pattern. REUSE not fork "
               "(`cq-node-prompt` derivation body, `record-competency-questions!` "
               "persist, `evaluate-cqs!` gate).\n\n"
               "## The fn-value boundary (honest split, not a mock)\n\n"
               "The S15 judge is a Clojure FN VALUE (it closes over dscloj/OpenRouter), "
               "so it is NOT routed across the `:delegate` blackboard (which is "
               "event-sourced). Two real captures: **[A]** the DELEGATED sheet path "
               "(DERIVE + PERSIST cross `:delegate` end-to-end; the gate runs there "
               "with no LLM judge, surfacing Layer-1 + honest no-judge verdicts), and "
               "**[B]** the FULL GATE — `run-gate!` called directly with the REAL LLM "
               "judge on the persisted spec, capturing the full retrieve-then-judge "
               "verdicts + graph-health. Both are real; the split is the fn-value "
               "boundary, not a mock.\n\n"
               "## Setup (inputs)\n\n"
               "GOAL: `" the-goal "`\n\n"
               "REAL profile(s) (the EB2/DT2 profile contract — the CQs are grounded "
               "in what these show the sources contain):\n\n```clojure\n"
               (with-out-str (pp/pprint the-profile)) "```\n\n"
               "A real built graph the CQs are judged over (" (:concepts-landed r)
               " concepts):\n\n```clojure\n"
               (with-out-str (pp/pprint graph-concepts)) "```\n\n"
               "## Registry + delegation\n\n"
               "- subbehavior: `" (get-in a [:registry :subbehavior-name]) "`\n"
               "- sub sheet-id: `" (get-in a [:registry :sub-sheet-id]) "`\n"
               "- registry name→id round-trip: **" (get-in a [:registry :registry-match?]) "**\n"
               "- central tree status: **" (:central-status a) "** (" (:elapsed-ms a) "ms)\n"
               "- parent tick-id: `" (:central-tick-id a) "`\n"
               "- ontology-id: `" (:ontology-id r) "`\n\n"
               "## [A] DERIVED CQs — SURFACED FOR HITL REVIEW (verbatim)\n\n"
               "The `:llm` DERIVE node derived these competency questions from the "
               "goal ⨯ the profile(s); they crossed `:delegate` as a parsed VECTOR "
               "(**" (:derived-is-vector? a) "**) and are surfaced for human "
               "review/override:\n\n"
               (apply str (map-indexed (fn [i q] (str (inc i) ". " q "\n"))
                                       (:derived-cqs a)))
               "\n## [A] PERSISTED ORSD spec (read back via `get-ontology-spec`, #7)\n\n"
               "The derived CQs ARE the ORSD spec's `:competency-questions` (the SAME "
               "spec build!'s S15 gate reads) — read back from the projection, NOT a "
               "return value:\n\n```clojure\n"
               (with-out-str (pp/pprint (:persisted-spec a))) "```\n\n"
               "## [B] S15 GATE — per-CQ verdicts (real retrieve-then-judge)\n\n"
               "The S15 runner judged the persisted CQs with the REAL LLM judge over "
               "the REAL graph (three-layer retrieve-then-judge). Per-CQ verdicts, "
               "read back via `get-cq-evaluation-latest` (#7):\n\n```clojure\n"
               (with-out-str (pp/pprint (:cq-verdict b))) "```\n\n"
               "Graph-health metric (the `:unknown-rate` is first-class — NOT folded "
               "into fail-rate; it surfaces 'what does the graph not know yet'):\n\n"
               "```clojure\n" (with-out-str (pp/pprint (:graph-health b))) "```\n\n"
               "## [C] consumer-CQ OVERRIDE\n\n"
               "A run with consumer-supplied CQs persisted the SUPPLIED set (NOT the "
               "derived set) — the HITL override path. Override held: **"
               (:override-held? c) "**\n\nSupplied (and persisted) CQs:\n\n"
               (apply str (map (fn [q] (str "- " q "\n")) (:persisted-cqs c)))
               (when (:error a)
                 (str "\n## Error (delegated path)\n\n```clojure\n"
                      (with-out-str (pp/pprint (:error a))) "```\n\n"))
               "\n## Verdict\n\n"
               "The Validate+CQ subbehavior is a delegatable `:llm` DERIVE → `:code` "
               "PERSIST → `:code` GATE sheet that, on a real profile + goal, derives "
               "GROUNDED, GOAL-ANCHORED CQs (surfaced for HITL review), persists them "
               "as the ORSD spec the S15 gate reads (read back from the projection, "
               "#7), and gates the built graph with the S15 SEMANTIC retrieve-then-"
               "judge runner (per-CQ pass/fail/unknown + graph-health; `:unknown` "
               "first-class, never dropped). Consumer-supplied CQs OVERRIDE the "
               "derived set. The `:reasoning` is written FIRST on the DERIVE node "
               "(#13); validation is SEMANTIC (not lints / phrase matching, #7/#12); "
               "domain-agnostic (the CQs come from goal ⨯ profile, #12). REUSE not "
               "fork.\n"))
    (println "Capture written:" capture-path)
    capture-path))

(defn -main [& _]
  (let [fut (future
              (try
                (let [r (run-all! {})]
                  (print-summary! r)
                  (save-capture! r)
                  (if (= :success (get-in r [:delegate :central-status])) :done :error))
                (catch Throwable t
                  (println "EB8 live verify FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  :error)))
        result (deref fut 300000 :timeout)]
    (println "\nEB8 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb8-validate-cq-subbehavior-live-verify :as eb8] :reload)
  (def r (eb8/run-all! {}))
  (eb8/print-summary! r)
  (eb8/save-capture! r))
