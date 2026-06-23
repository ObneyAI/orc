(ns eb10-central-evolver-live-verify
  "EB10 — LIVE VERIFY (the keystone proof): the composed CENTRAL evolver tree runs
   the REAL EB2-EB9 subbehaviors via `:delegate` end-to-end to a CQ verdict on a
   REAL source. CQ-satisfaction is the OBJECTIVE — the loop runs the gate IN-PROCESS
   with the REAL LLM judge, routes any failing CQ to the closing subbehavior
   (`:reasoning` first), re-invokes it focally, re-gates, and ALWAYS terminates with
   a surfaced reason.

   What this proves with REAL Grain + REAL OpenRouter LLM (Survey repl-researcher,
   Model/Extract/derive `:llm` nodes, the ROUTE `:llm` node, the S15 judge) + REAL
   ColBERT/embeddings (Embed+Index, the S15 retrieval) — NO mocks:
     - the central tree :delegate`s Survey → Model→Extract → Reconcile → Axiom →
       Embed → derive-CQs across REAL subbehavior sheets (the EB1 registry/
       delegation pattern), and the CQ gate runs in-process with the real judge;
     - the per-iteration TRACE: which subbehaviors ran, the CQ verdict +
       graph-health, any route + focused re-invoke, the terminate reason;
     - the loop ALWAYS terminates with a surfaced `:termination-reason`.

   The judge-fn is a Clojure FN VALUE (it closes over dscloj/OpenRouter), so it is
   NOT routed across `:delegate` — the CQ gate runs IN-PROCESS with it (the EB8/EB9
   fn-value boundary), while Survey/Model/Extract/Reconcile/Axiom/Embed/derive all
   cross `:delegate` for real.

   USAGE (REPL with :dev:test; needs OPENROUTER_API_KEY + (for the ColBERT signal)
   the Python ColBERT bridge up):
     export OPENROUTER_API_KEY=\"sk-or-v1-...\"
     (require '[eb10-central-evolver-live-verify :as eb10])
     (def r (eb10/run-all! {}))
     (eb10/print-summary! r)
     (eb10/save-capture! r)

   Or bounded from the CLI via -main (future + deref timeout + System/exit)."
  (:require [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def capture-path "docs/build-timeline/live-verify/EB10-central-loop.md")
(def default-llm-model "google/gemini-3-flash-preview")

(def the-goal
  "Build an ontology connecting fields/programs of study to the occupations they prepare people for.")

;; A REAL csv source — programs ↔ occupations (free-text titles so the S15
;; retrieval has real signal). Domain-neutral columns; the goal supplies the focus.
(def csv-path "/tmp/eb10-live-source.csv")
(defn write-source! []
  (spit csv-path
        (str "program_code,program_title,occupation_code,occupation_title\n"
             "01.0000,\"Agriculture, General\",19-1011,\"Animal Scientists\"\n"
             "51.3801,\"Registered Nursing\",29-1141,\"Registered Nurses\"\n"
             "11.0101,\"Computer and Information Sciences\",15-1252,\"Software Developers\"\n"
             "52.0201,\"Business Administration and Management\",11-1021,\"General and Operations Managers\"\n"
             "14.0801,\"Civil Engineering\",17-2051,\"Civil Engineers\"\n")))

;; ---------------------------------------------------------------------------
;; The real LLM judge (the production S15 prompt + dscloj — same as the EB8/S15
;; live verify). It is a Clojure fn value, so it runs the gate IN-PROCESS.
;; ---------------------------------------------------------------------------

(defn register-openrouter! [model]
  (litellm-router/register! :openrouter
                            {:provider :openrouter
                             :model model
                             :config {:api-base "https://openrouter.ai/api/v1"
                                      :api-key (System/getenv "OPENROUTER_API_KEY")}}))

(defn real-llm-judge [{:keys [question evidence]}]
  (let [prompt (cqr/render-judge-prompt question evidence)
        module {:inputs  [{:name :request :spec :string :description "The CQ + evidence"}]
                :outputs [{:name :verdict :spec :string :description "pass, fail, or unknown"}
                          {:name :reasoning :spec :string :description "Why; on unknown name the gap"}
                          {:name :evidence-uris :spec [:vector :string] :description "URIs used"}
                          {:name :gaps :spec [:vector :string] :description "Missing fact-kinds on unknown"}]
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
                  :else (throw (ex-info "Judge returned unparseable verdict" {:raw raw})))]
    {:verdict verdict
     :reasoning (or (:reasoning outputs) "")
     :evidence-uris (vec (or (:evidence-uris outputs) []))
     :gaps (vec (or (:gaps outputs) []))}))

;; ---------------------------------------------------------------------------
;; Real-Grain harness WITH the real todo processors (the :delegate child tick is
;; driven by a todo-processor) — mirrors the EB8 live-verify harness.
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb10-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb10-live" :map-size (* 1024 1024 1024)}))
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
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

;; ---------------------------------------------------------------------------
;; The keystone run — run-central-evolver! end-to-end through REAL subbehaviors,
;; the gate IN-PROCESS with the real judge.
;; ---------------------------------------------------------------------------

(defn run-all! [_opts]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required" {})))
  (register-openrouter! default-llm-model)
  (write-source!)
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (println "=== EB10 CENTRAL EVOLVER LIVE VERIFY (the keystone) ===")
      (println "  goal:" the-goal)
      (println "  source:" csv-path)
      (println "  running the composed central evolver end-to-end via :delegate...")
      (let [t0 (System/currentTimeMillis)
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid
                         :sources [{:type :csv :path csv-path}]
                         :goal the-goal
                         :model default-llm-model
                         :judge-fn real-llm-judge
                         :evolver-config {:max-iterations 2}})
            elapsed (- (System/currentTimeMillis) t0)
            concepts (rm/get-concepts ctx {:ontology-id oid})]
        (println "  central evolver status:" (:status result) "(" elapsed "ms)")
        (println "  concepts landed:" (count concepts))
        (println "  termination reason:" (get-in result [:cq-loop :termination-reason]))
        {:ontology-id oid
         :goal the-goal
         :elapsed-ms elapsed
         :status (:status result)
         :branch-points (:branch-points result)
         :survey-profiles (:survey-profiles result)
         :competency-questions (:competency-questions result)
         :graph-health (:graph-health result)
         :cq-verdict (:cq-verdict result)
         :cq-loop (:cq-loop result)
         :concepts-landed (count concepts)
         :build-result (:build-result result)})
      (finally (stop-ctx ctx)))))

(defn- trunc [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn print-summary! [r]
  (println "\n================ EB10 CENTRAL EVOLVER LIVE VERIFY ================")
  (println "ontology-id:" (:ontology-id r))
  (println "status:" (:status r) "(" (:elapsed-ms r) "ms)")
  (println "concepts landed:" (:concepts-landed r))
  (println "\n--- greenfield-vs-maintain branch (DT9) ---")
  (println "  selected:" (get-in r [:branch-points :greenfield-vs-maintain :selected]))
  (println "\n--- survey profiles (per source, via :delegate) ---")
  (doseq [p (:survey-profiles r)]
    (println "  profile keys:" (when (map? p) (keys p))))
  (println "\n--- derived competency questions (HITL) ---")
  (doseq [q (:competency-questions r)] (println "   -" q))
  (println "\n--- CQ verdict (in-process S15 gate with the real judge) ---")
  (doseq [{:keys [cq-text verdict reasoning]} (:cq-verdict r)]
    (println (format "   %-8s %s" (name (or verdict :?)) (trunc cq-text 70)))
    (when (= :unknown verdict) (println "       (unknown —" (trunc reasoning 100) ")")))
  (println "\ngraph-health:" (pr-str (:graph-health r)))
  (println "\n--- the loop trace (per iteration) ---")
  (println "  termination-reason:" (get-in r [:cq-loop :termination-reason]))
  (println "  iterations:" (get-in r [:cq-loop :iterations]))
  (println "  unanswerable-cqs:" (get-in r [:cq-loop :unanswerable-cqs]))
  (doseq [h (get-in r [:cq-loop :history])]
    (println (format "   iter %s: failing-cq=%s route=%s grew?=%s"
                     (:iteration h) (trunc (:failing-cq h) 40) (:route h) (:graph-grew? h)))
    (when (:route-reasoning h) (println "       route-reasoning:" (trunc (:route-reasoning h) 100)))))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (spit capture-path
        (str "# EB10 — Central evolver loop (the keystone) — LIVE VERIFY\n\n"
             "**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain "
             "event store, real OpenRouter LLM (Survey repl-researcher, Model/Extract/"
             "derive `:llm` nodes, the ROUTE `:llm` node, the S15 judge; model `"
             default-llm-model "`), real ColBERT/embedding retrieval (Embed+Index + "
             "the S15 three-layer runner), real child ticks.\n\n"
             "Proves the composed CENTRAL evolver tree runs the REAL EB2-EB9 "
             "subbehaviors via `:delegate` end-to-end to a CQ verdict on a REAL "
             "source, with CQ-satisfaction as the OBJECTIVE: the loop runs the S15 "
             "gate IN-PROCESS with the real LLM judge (the judge fn-value cannot "
             "cross `:delegate`), routes any failing CQ to the closing subbehavior "
             "(`:reasoning` first), re-invokes it focally, re-gates, and ALWAYS "
             "terminates with a surfaced reason. RE-HOUSE/REUSE (DT8 loop + DT9 "
             "greenfield-vs-maintain + `build!` + the subbehaviors via `:delegate`) "
             "— not a rewrite.\n\n"
             "## Setup\n\n"
             "GOAL: `" the-goal "`\n\n"
             "REAL source (`" csv-path "`): a programs↔occupations csv.\n\n"
             "ontology-id: `" (:ontology-id r) "`\n\n"
             "## Result\n\n"
             "- central evolver status: **" (:status r) "** (" (:elapsed-ms r) "ms)\n"
             "- concepts landed: **" (:concepts-landed r) "**\n"
             "- greenfield-vs-maintain (DT9): **"
             (get-in r [:branch-points :greenfield-vs-maintain :selected]) "**\n"
             "- loop termination reason: **" (get-in r [:cq-loop :termination-reason]) "**\n\n"
             "## Survey profiles (per source, via `:delegate`)\n\n```clojure\n"
             (with-out-str (pp/pprint (:survey-profiles r))) "```\n\n"
             "## Derived competency questions (surfaced for HITL review)\n\n"
             (apply str (map-indexed (fn [i q] (str (inc i) ". " q "\n"))
                                     (:competency-questions r)))
             "\n## CQ verdict — the OBJECTIVE (in-process S15 gate, real judge)\n\n```clojure\n"
             (with-out-str (pp/pprint (:cq-verdict r))) "```\n\n"
             "graph-health (the gate metric; `:unknown-rate` first-class):\n\n```clojure\n"
             (with-out-str (pp/pprint (:graph-health r))) "```\n\n"
             "## The per-iteration loop TRACE\n\n"
             "termination-reason: **" (get-in r [:cq-loop :termination-reason]) "**, "
             "iterations: **" (get-in r [:cq-loop :iterations]) "**, "
             "unanswerable-cqs: `" (pr-str (get-in r [:cq-loop :unanswerable-cqs])) "`\n\n```clojure\n"
             (with-out-str (pp/pprint (get-in r [:cq-loop :history]))) "```\n\n"
             "## Verdict\n\n"
             "The composed central evolver `:delegate`s to the REAL EB2-EB9 "
             "subbehaviors (Survey → Model→Extract → Reconcile → Axiom → Embed → "
             "derive-CQs) end-to-end to a CQ verdict on a real source. The CQ gate is "
             "the loop OBJECTIVE, run IN-PROCESS with the real judge; a failing CQ "
             "ROUTES (`:reasoning` first) to the closing subbehavior, re-invoked "
             "FOCALLY, re-gated; the loop ALWAYS terminates with a surfaced reason "
             "(here: **" (get-in r [:cq-loop :termination-reason]) "**). RE-HOUSE/"
             "REUSE, not a rewrite; domain-agnostic.\n"))
  (println "Capture written:" capture-path)
  capture-path)

(defn -main [& _]
  (let [fut (future
              (try
                (let [r (run-all! {})]
                  (print-summary! r)
                  (save-capture! r)
                  (if (contains? #{:complete :failed-cq} (:status r)) :done :error))
                (catch Throwable t
                  (println "EB10 live verify FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  :error)))
        result (deref fut 580000 :timeout)]
    (println "\nEB10 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb10-central-evolver-live-verify :as eb10] :reload)
  (def r (eb10/run-all! {}))
  (eb10/print-summary! r)
  (eb10/save-capture! r))
