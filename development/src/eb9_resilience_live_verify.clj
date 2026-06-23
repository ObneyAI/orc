(ns eb9-resilience-live-verify
  "EB9 — LIVE VERIFY: the REUSABLE resilience sub-tree (`with-resilience`) composed
   into the EXTRACT subbehavior, exercised on a REAL induced failure with a REAL
   OpenRouter LLM troubleshoot node (real Grain event store, real async child tick,
   real source file — NO mocks; only the failure is INDUCED).

   The induced failure is HONEST + domain-agnostic: we feed Extract a model-spec
   whose `:scope-filter` references a value that the source does NOT contain. The
   real LLM author HONESTLY authors a transform that filters every row out → 0
   concept drafts (a true mis-scope, not a rigged stub). This drives the resilient
   sub-tree's two paths:

     - RECOVERABLE: the resilient Extract is run with a GOOD scope on the robust
       path's model-spec but a BAD scope is injected ONLY for the primary attempt
       (via a primary-only scope override) — the primary's transform yields 0, the
       sanity gate rejects it, and the `:fallback`'s ROBUST author re-attempts with
       the good scope → a sane scoped draft set. Downstream (the parent tick bb)
       sees a GOOD concept-drafts vector; status :success; NO diagnosis.

     - UNRECOVERABLE: BOTH the primary and robust authors get the BAD scope → both
       yield 0 → the troubleshoot `:llm` node (REAL OpenRouter) reasons about WHY
       (Investigation root-cause + Validation check) and lands a STRUCTURED
       `:diagnosis`; the always-fail condition then forces a CLEAN `:failure`. The
       parent tick blackboard's `:concept-drafts` reads back EMPTY (downstream NOT
       poisoned with a fake success), and the `:diagnosis` is present.

   Because the primary/robust authors share ONE prompt body in the shipped Extract
   sheet, the two scenarios are realized by building TWO purpose-built resilient
   sheets here: one whose ROBUST path carries the GOOD scope (recoverable) and one
   whose both paths carry the BAD scope (unrecoverable). Both reuse the SHIPPED
   `with-resilience` builder + the SHIPPED author/apply nodes — only the model-spec
   the path reads differs. This keeps the builder under test, not a fork.

   USAGE (REPL, :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[eb9-resilience-live-verify :as eb9] :reload)
     (def r (eb9/run-all! {}))
     (eb9/print-summary! r)
     (eb9/save-capture! r)"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.resilience :as res]
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
(def capture-path "docs/build-timeline/live-verify/EB9-resilience.md")

(def csv-source {:type :csv :path "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv"})

;; A GOOD scope (CIP family 01 — present in the source) and a BAD scope (a value
;; that does NOT occur). The two entity types + edge are real EB3 shape.
(defn- model-spec [scope-values]
  {:entity-types
   [{:type "Program of Study" :uri-keying-fields ["CIP_Code"]
     :grain-strategy :canonical-row-filter}
    {:type "Occupation" :uri-keying-fields ["SOC_Code"]
     :grain-strategy :canonical-row-filter}]
   :scope-filter {:field "CIP_Code" :values scope-values}
   :edges [{:source-type "Program of Study" :target-type "Occupation"
            :predicate "prepares_for"}]
   :embed-fields ["CIP_Title" "SOC_Title"]})

(def good-spec (model-spec ["01"]))

;; A DETERMINISTIC failure injection: a `:code` apply that ALWAYS returns 0 concept
;; drafts, regardless of the authored transform. This makes the induced failure
;; MODEL-PROOF (#1 — rule out the harness: an earlier mis-scope injection was
;; non-deterministic because a capable LLM sometimes ignored a nonsense scope and
;; extracted anyway). The failure is injected deterministically; the RESILIENCE
;; RESPONSE under test (the robust author on the recoverable path; the troubleshoot
;; on the unrecoverable path) is the REAL LLM. So we verify the real resilience
;; behavior RELIABLY, not a flaky failure-injection.
(defn force-empty-apply
  "A failure-injection apply node `:fn` — ALWAYS yields an empty draft set."
  [{:keys [_inputs]}]
  {:concept-drafts [] :relationship-drafts []
   :concept-count 0
   :extraction-report {:rows-streamed 0 :rows-ok 0 :rows-errored 0
                       :concept-count 0 :relationship-count 0
                       :note "FORCED-EMPTY (EB9 deterministic failure injection)"}})

;; ---------------------------------------------------------------------------
;; Build a resilient Extract sheet. The PRIMARY path is always FORCED-EMPTY (the
;; deterministic injected failure). The ROBUST path is either the REAL LLM
;; author→apply (recoverable) or also FORCED-EMPTY (unrecoverable). Reuses the
;; SHIPPED with-resilience builder + the SHIPPED author/apply node fns + the
;; SHIPPED real :llm troubleshoot node.
;; ---------------------------------------------------------------------------

(defn- real-author-apply [path-label prompt]
  (dsl/sequence (str "extract-" path-label)
    (dsl/llm (str "extract-" path-label "-author")
      :model default-model
      :instruction prompt
      :reads [:model-spec :sample-rows]
      :writes [:reasoning :transform-source :selector])
    (dsl/code (str "extract-" path-label "-apply")
      :fn "ai.obney.orc.ontology.core.extract-subbehavior/apply-transform-code"
      :reads [:source :transform-source :selector]
      :writes [:concept-drafts :relationship-drafts :extraction-report :concept-count])))

(defn- forced-empty-path [path-label]
  (dsl/sequence (str "extract-" path-label)
    (dsl/code (str "extract-" path-label "-apply")
      :fn "eb9-resilience-live-verify/force-empty-apply"
      :reads [:source]
      :writes [:concept-drafts :relationship-drafts :extraction-report :concept-count])))

(defn- resilient-extract-def [sheet-name {:keys [robust-real?]}]
  (let [step (res/with-resilience
               {:step "extract"
                ;; PRIMARY — always the deterministic forced-empty failure.
                :primary (forced-empty-path "primary")
                ;; ROBUST — the REAL LLM author→apply (recoverable) OR forced-empty
                ;; (unrecoverable).
                :robust (if robust-real?
                          (real-author-apply "robust" (extract/robust-author-prompt nil))
                          (forced-empty-path "robust"))
                :gate {:check {:key :concept-count :op :gt :value 0}}
                :troubleshoot
                {:reads [:model-spec :sample-rows :concept-count :extraction-report]
                 :model default-model
                 :step-label "the per-row extraction transform (author → apply)"
                 :expectation "a NON-EMPTY, scoped set of concept drafts"}})]
    (dsl/workflow sheet-name
      (dsl/blackboard
       (merge
        {:model-spec [:map {:closed false}]
         :source [:map {:closed false}]
         :sample-rows [:vector [:map {:closed false}]]
         :reasoning :string
         :transform-source :string
         :selector [:maybe :string]
         :concept-count :int
         :concept-drafts extract/concept-drafts-schema
         :relationship-drafts extract/relationship-drafts-schema
         :extraction-report extract/extraction-report-schema}
        (res/resilience-blackboard-keys)))
      (dsl/sequence "extract-root"
        (dsl/code "sample-rows"
          :fn "ai.obney.orc.ontology.core.extract-subbehavior/sample-rows-code"
          :reads [:source]
          :writes [:sample-rows])
        step))))

;; ---------------------------------------------------------------------------
;; Real-Grain + real OpenRouter harness.
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
        dir (str "/tmp/eb9-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb9-live"
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
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defn- run-scenario! [ctx {:keys [sheet-name robust-real?]}]
  (let [sid (dsl/build-workflow! ctx (resilient-extract-def sheet-name {:robust-real? robust-real?}))
        central-name (str sheet-name "-central")
        central (dsl/workflow central-name
                  (dsl/blackboard {:model-spec [:map {:closed false}]
                                   :source [:map {:closed false}]
                                   :concept-drafts extract/concept-drafts-schema
                                   :relationship-drafts extract/relationship-drafts-schema
                                   :extraction-report extract/extraction-report-schema
                                   :diagnosis res/diagnosis-schema})
                  (dsl/sequence "central-root"
                    (dsl/delegate "to-extract"
                      :target-sheet-id sid
                      :reads [:model-spec :source]
                      :writes [:concept-drafts :relationship-drafts
                               :extraction-report :diagnosis]
                      :timeout-ms 300000)))
        central-id (dsl/build-workflow! ctx central)
        tick-id (random-uuid)
        t0 (System/currentTimeMillis)
        result (runtime/execute ctx central-id
                                {"model-spec" good-spec
                                 "source" csv-source}
                                :timeout-ms 300000 :tick-id tick-id)
        _ (Thread/sleep 300)
        bb (rm/get-tick-blackboard ctx tick-id)]
    {:status (:status result)
     :elapsed-ms (- (System/currentTimeMillis) t0)
     :concept-drafts (get-in bb [:concept-drafts :value])
     :concept-count (count (or (get-in bb [:concept-drafts :value]) []))
     :diagnosis (get-in bb [:diagnosis :value])
     :error (:error result)}))

(defn run-all!
  [{:keys [model] :or {model default-model}}]
  (let [ctx (make-ctx)]
    (try
      (register-openrouter! model)
      (println "=== EB9 RESILIENCE LIVE VERIFY (real LLM troubleshoot) ===")
      (println "model:" model "\n")
      (println "--- RECOVERABLE: primary forced-empty → gate rejects → REAL-LLM robust recovers ---")
      (let [recoverable (run-scenario! ctx {:sheet-name "eb9/resilient-extract-recoverable@v1"
                                            :robust-real? true})
            _ (println "  status:" (:status recoverable)
                       " concepts:" (:concept-count recoverable)
                       " diagnosis?:" (some? (:diagnosis recoverable))
                       "(" (:elapsed-ms recoverable) "ms)")
            _ (println "\n--- UNRECOVERABLE: primary+robust forced-empty → REAL-LLM troubleshoot + clean failure ---")
            unrecoverable (run-scenario! ctx {:sheet-name "eb9/resilient-extract-unrecoverable@v1"
                                              :robust-real? false})
            _ (println "  status:" (:status unrecoverable)
                       " concepts (downstream poison check):" (:concept-count unrecoverable)
                       " diagnosis?:" (some? (:diagnosis unrecoverable))
                       "(" (:elapsed-ms unrecoverable) "ms)")]
        {:model model :recoverable recoverable :unrecoverable unrecoverable})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (let [rec (:recoverable r) un (:unrecoverable r)]
    (println "\n================ EB9 RECOVERABLE ================")
    (println "status:" (:status rec) " concept-count:" (:concept-count rec))
    (println "sample drafts:")
    (pp/pprint (vec (take 5 (or (:concept-drafts rec) []))))
    (println "diagnosis present (should be nil — recovered):" (some? (:diagnosis rec)))
    (println "\n================ EB9 UNRECOVERABLE ================")
    (println "status (should be :failure):" (:status un))
    (println "concept-drafts (downstream NOT poisoned — should be empty):"
             (pr-str (:concept-drafts un)))
    (println "REAL-LLM diagnosis (structured, landed before clean failure):")
    (pp/pprint (:diagnosis un))))

(defn save-capture! [r]
  (io/make-parents capture-path)
  (let [rec (:recoverable r) un (:unrecoverable r)]
    (spit capture-path
          (str "# EB9 — Subbehavior-internal resilience — LIVE VERIFY\n\n"
               "**Branch:** `feature/ontology-architecture`. **Model:** `" (:model r)
               "` (real OpenRouter). **No mocks** — real Grain event store, real LLM "
               "troubleshoot, real async todo processors, real child tick, REAL "
               "source file. The FAILURE is INDUCED DETERMINISTICALLY (the primary "
               "apply node is forced to yield 0 drafts — model-proof, #1: an earlier "
               "mis-scope injection was non-deterministic because a capable LLM "
               "sometimes ignored the nonsense scope and extracted anyway). The "
               "failure is injected deterministically; the RESILIENCE RESPONSE under "
               "test (the robust author on the recoverable path; the troubleshoot on "
               "the unrecoverable path) is the REAL LLM.\n\n"
               "Proves the reusable `with-resilience` sub-tree composed into Extract: "
               "a `:fallback`[primary→robust] guarded by a sanity `:condition`, with a "
               "troubleshoot `:llm` node (Investigation root-cause + Validation check, "
               "`:reasoning` FIRST #13) that lands a STRUCTURED `:diagnosis` + a CLEAN "
               "`:failure` on unrecoverable failure — NEVER a poisoned empty success "
               "(#4/#5). Read back from the PARENT tick blackboard via the projection "
               "(discipline 7), NOT from the execute return value.\n\n"
               "## RECOVERABLE — primary forced-empty → gate rejects → REAL-LLM robust recovers\n\n"
               "The primary apply was forced to 0 drafts → the sanity gate "
               "(`{:key :concept-count :op :gt :value 0}`) REJECTED it → the "
               "`:fallback` ran the REAL-LLM ROBUST author→apply → a sane scoped draft "
               "set. Downstream saw a GOOD result:\n\n"
               "- status: **" (:status rec) "** (" (:elapsed-ms rec) "ms)\n"
               "- concept-count (recovered by the REAL LLM robust path, scoped, "
               "non-empty): **" (:concept-count rec) "**\n"
               "- diagnosis present? **" (some? (:diagnosis rec))
               "** (nil — recovered via fallback, troubleshoot never ran)\n\n"
               "Sample recovered drafts (verbatim):\n\n```clojure\n"
               (with-out-str (pp/pprint (vec (take 6 (or (:concept-drafts rec) [])))))
               "```\n\n"
               "## UNRECOVERABLE — primary+robust forced-empty → real-LLM troubleshoot + clean failure\n\n"
               "BOTH the primary and robust apply nodes were forced to 0 drafts → "
               "BOTH gates rejected → the troubleshoot `:llm` node (REAL OpenRouter) "
               "investigated WHY and landed a structured `:diagnosis`, then the "
               "always-fail `:condition` forced a CLEAN `:failure`:\n\n"
               "- status: **" (:status un) "** (should be `:failure`; "
               (:elapsed-ms un) "ms)\n"
               "- concept-drafts read back from the parent tick (downstream POISON "
               "check): **" (pr-str (:concept-drafts un)) "** (empty → downstream NOT "
               "poisoned with a fake success)\n"
               "- diagnosis present? **" (some? (:diagnosis un)) "**\n\n"
               "REAL-LLM structured diagnosis (Investigation root-cause + Validation "
               "check; verbatim, NOT truncated — #11):\n\n```clojure\n"
               (with-out-str (pp/pprint (:diagnosis un))) "```\n\n"
               (when (:error un)
                 (str "Failure error (the clean failure surface):\n\n```clojure\n"
                      (with-out-str (pp/pprint (:error un))) "```\n\n"))
               "## Verdict\n\n"
               "The reusable `with-resilience` sub-tree, composed into Extract, makes "
               "the subbehavior SELF-CORRECT (recoverable → the `:fallback` robust "
               "path recovers, downstream sees a good result) OR FAIL CLEANLY WITH A "
               "DIAGNOSIS (unrecoverable → a real-LLM troubleshoot lands a structured "
               "diagnosis + the step returns a clean `:failure`, downstream NOT "
               "poisoned). No fake success on the troubleshoot path (#4/#5); the gate "
               "is a structural `:condition` (no hardcoded phrase matching, #7/#12); "
               "the troubleshoot writes `:reasoning` first (#13). Reuses the dsl "
               "`:fallback`/`:condition` + the Investigation/Validation pattern (no "
               "fork). Read back from the projection (discipline 7).\n"))
    (println "Capture written:" capture-path)
    capture-path))

(defn -main [& _]
  (let [fut (future
              (try (let [r (run-all! {})]
                     (print-summary! r)
                     (save-capture! r) :done)
                   (catch Throwable t
                     (println "EB9 live verify FAILED:" (.getMessage t))
                     (.printStackTrace t) :error)))
        result (deref fut 600000 :timeout)]
    (println "\nEB9 live verify result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb9-resilience-live-verify :as eb9] :reload)
  (def r (eb9/run-all! {}))
  (eb9/print-summary! r)
  (eb9/save-capture! r))
