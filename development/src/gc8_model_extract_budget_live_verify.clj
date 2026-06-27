(ns gc8-model-extract-budget-live-verify
  "GC-8 — the live proof GC-7 could not produce: a REAL model → extract over a REAL
   MULTI-container source (IPEDS `output.db`, ~59 tables → capped at
   `extract/default-max-containers` = 25 traversed SERIALLY) COMPLETES (no
   `:failed-at-model-extract` timeout) and LANDS drafts — read the per-source draft
   count back off the projection (Discipline 7), NOT a 0-draft false-green.

   Before GC-8 the OUTER Model→Extract `:delegate` inherited `delegate-subbehavior!`'s
   flat 180s deref-timeout. The Extract orchestrator runs up to 25 containers
   SERIALLY (~6-22s each + the EB9 resilience cascade) → ~290-375s total → the 180s
   ceiling cut the build at container ~10-11 and landed 0 drafts. GC-8 sizes the
   OUTER delegate `:timeout-ms` to `(cap × default-per-container-budget-ms) +
   overhead` (810s at the default cap) so the ceiling tracks the real serial work
   and never fires.

   This harness drives the EXACT seam GC-8 fixed — `ce/delegate-model-extract!` — on
   the real IPEDS source through the real per-source pipeline sheet, and reads the
   concept-drafts back. A 0-draft result is a FAIL.

   JVM HYGIENE (binding): the build runs inside a future with a HARD deref timeout +
   System/exit / .halt, so it can NEVER spin forever. The deref ceiling (20 min) is
   COMFORTABLY larger than the new 810s budget so the build can actually finish — but
   it MUST self-terminate. On the deref timeout we `.halt` (no orphan hot-loop JVM).

   Run (real LLM, OPENROUTER_API_KEY env only):
     clj -M:dev -e \"(require 'gc8-model-extract-budget-live-verify)(gc8-model-extract-budget-live-verify/-main)\""
  (:require [ai.obney.orc.orc-service.interface :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.evolutionary-commands]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.pprint :as pp]))

(def default-model "google/gemini-3-flash-preview")

;; The REAL multi-container source — NO :selector, so the Extract orchestrator
;; traverses the source's own tables (59 → capped at default-max-containers = 25)
;; SERIALLY (the exact path that timed out at the flat 180s before GC-8).
(def ipeds-source {:type :sql :path "/Users/darylroberts/Downloads/output.db"})

;; The domain GOAL — a goal, NOT a schema (no hardcoded entity-types/keys; the
;; builder discovers them). Domain-agnostic phrasing.
(def goal
  (str "Build a connected ontology of the education-and-career data in this source: "
       "the educational programs offered, the fields of study, the institutions, and "
       "any occupations / outcomes the data relates them to. Cover the source "
       "comprehensively. Where the source provides shared keys or code systems "
       "(e.g. CIP codes), use them so the same real-world entity resolves to ONE "
       "node. Carry any numeric outcome a concept has."))

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
        dir (str "/tmp/gc8-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "gc8-live"
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

(defn run-bounded!
  "Drive the REAL model→extract seam over the multi-container IPEDS source at the
   default cap. Survey (real :delegate) → produce a profile → delegate-model-extract!
   (the GC-8-fixed seam) → read the concept-drafts the OUTER delegate read back off
   the projection. Returns the terminal status + the per-source draft counts + the
   budget the seam used."
  [{:keys [model]}]
  (let [model (or model default-model)
        _ (register-openrouter! model)
        ctx (make-ctx)]
    (try
      (let [budget-ms (ce/model-extract-timeout-ms {:max-containers extract/default-max-containers})
            _ (println "GC-8 budget (default cap" extract/default-max-containers "):" budget-ms "ms"
                       (str "(was flat 180000 — " (format "%.1fx" (/ (double budget-ms) 180000)) ")"))
            ;; register the per-source pipeline (Model + Extract + the fixed pipeline
            ;; sheet) — the same registration the central evolver does.
            {:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model model :resilient? true})
            ;; STEP 1 — real Survey (:delegate) → a real profile.
            survey (ce/delegate-survey! ctx {:source ipeds-source :goal goal :model model})
            _ (println "survey status:" (:status survey))
            _ (when (not= :success (:status survey))
                (throw (ex-info "survey failed (cannot proceed to model→extract)" {:survey survey})))
            ;; STEP 2 — THE GC-8 SEAM: real model→extract over the multi-container
            ;; source at the default cap. Before GC-8 this hit the flat 180s and came
            ;; back :failed-at-model-extract with 0 drafts. (no :selector → the
            ;; orchestrator traverses the source's tables serially — the binding case)
            mx-start (System/currentTimeMillis)
            mx (ce/delegate-model-extract!
                ctx {:source ipeds-source :goal goal
                     :profile (:profile survey) :vocabulary nil
                     :pipeline-sheet-id pipeline-sheet-id :model model :resilient? true})
            mx-elapsed (- (System/currentTimeMillis) mx-start)
            report (:extraction-report mx)
            concept-drafts (:concept-drafts mx)
            relationship-drafts (:relationship-drafts mx)]
        {:status (:status mx)
         :budget-ms budget-ms
         :model-extract-elapsed-ms mx-elapsed
         :concept-draft-count (count concept-drafts)
         :relationship-draft-count (count relationship-drafts)
         :containers-total (:containers-total report)
         :containers-processed (:containers-processed report)
         :containers-with-drafts (:containers-with-drafts report)
         :error (:error mx)})
      (finally (stop-ctx ctx)))))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "OPENROUTER_API_KEY missing") (System/exit 2))
  (let [fut (future
              (try
                (run-bounded! {})
                (catch Throwable t
                  (println "GC-8 live model→extract FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  {:status :exception :error (.getMessage t)})))
        ;; HARD ceiling — 20 min, COMFORTABLY above the 810s (~13.5 min) budget so a
        ;; healthy build finishes; if the fix regressed and it spins, this deref
        ;; returns :TIMEOUT and we .halt rather than hang forever.
        result (deref fut (* 20 60 1000) :TIMEOUT)]
    (println "\n================ GC-8 LIVE MODEL→EXTRACT — TERMINAL RESULT ================")
    (if (= :TIMEOUT result)
      (do (println "!!! model→extract DID NOT TERMINATE within the 20-min ceiling — FINDING")
          (shutdown-agents)
          (.halt (Runtime/getRuntime) 3))
      (do (pp/pprint result)
          (let [ok? (and (= :success (:status result))
                         (pos? (long (or (:concept-draft-count result) 0))))]
            (println "\nVERDICT:" (if ok?
                                    "PASS — completed (no :failed-at-model-extract) AND landed drafts"
                                    "FAIL — either not :success or 0 drafts (false-green guard)"))
            (shutdown-agents)
            (System/exit (if ok? 0 1)))))))
