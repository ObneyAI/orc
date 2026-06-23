(ns eb10-central-evolver-prototype
  "EB10 PROTOTYPE — prove the composed central evolver tree (novel; do first).

   Three proofs, the first on REAL subbehaviors via :delegate, the other two with a
   STUBBED CQ verdict so the route-and-close + honest-terminate LOGIC is exercised
   deterministically (no LLM/ColBERT needed for the loop branching):

   (a) the composed central evolver :delegate`s to >=2 REAL subbehaviors end-to-end
       on a REAL source (Survey + the Model->Extract pipeline) — proving the
       :delegate composition runs the real subbehaviors;
   (b) on a STUBBED failing CQ verdict, the ROUTE seam maps it to the right
       subbehavior, re-invokes it FOCALLY, the close grows the graph, and the
       re-gate PASSES (route-and-close);
   (c) a STUBBED unanswerable CQ (route says :terminate) -> honest terminate
       (surfaced reason, no further routing, no false-green).

   Run bounded (future + deref timeout + System/exit). (a) needs OPENROUTER_API_KEY
   (the Survey repl-researcher + the Model/Extract :llm nodes); (b)/(c) are
   hermetic (stubbed seams)."
  (:require [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
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

(def default-llm-model "google/gemini-3-flash-preview")

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb10-proto-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb10-proto" :map-size (* 1024 1024 1024)}))
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

(defn register-openrouter! [model]
  (litellm-router/register! :openrouter
                            {:provider :openrouter
                             :model model
                             :config {:api-base "https://openrouter.ai/api/v1"
                                      :api-key (System/getenv "OPENROUTER_API_KEY")}}))

;; A small REAL csv source for proof (a)
(def csv-path "/tmp/eb10-proto-source.csv")
(defn write-source! []
  (spit csv-path
        (str "program_code,program_title,occupation_code,occupation_title\n"
             "01.0000,\"Agriculture, General\",19-1011,\"Animal Scientists\"\n"
             "51.3801,\"Registered Nursing\",29-1141,\"Registered Nurses\"\n"
             "11.0101,\"Computer Science\",15-1252,\"Software Developers\"\n")))

(def the-goal
  "Build an ontology connecting fields/programs of study to the occupations they prepare people for.")

;; ---------------------------------------------------------------------------
;; PROOF (a) — :delegate to >=2 REAL subbehaviors end-to-end (Survey + Model->Extract)
;; ---------------------------------------------------------------------------

(defn proof-a! [ctx]
  (println "  [a] registering subbehaviors + delegating Survey + Model->Extract on a REAL source...")
  (let [source {:type :csv :path csv-path}
        ;; Survey via :delegate (REAL repl-researcher)
        survey (ce/delegate-survey! ctx {:source source :goal the-goal :model default-llm-model})
        _ (println "      survey status:" (:status survey)
                   "profile keys:" (when (map? (:profile survey)) (keys (:profile survey))))
        ;; register the pipeline + Model->Extract via :delegate (REAL :llm nodes)
        {:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model default-llm-model})
        mx (ce/delegate-model-extract! ctx {:source source :goal the-goal
                                            :profile (:profile survey)
                                            :pipeline-sheet-id pipeline-sheet-id
                                            :model default-llm-model})
        _ (println "      model->extract status:" (:status mx)
                   "concept-drafts:" (count (:concept-drafts mx)))]
    {:survey-status (:status survey)
     :survey-profile (:profile survey)
     :mx-status (:status mx)
     :model-spec-is-map? (map? (:model-spec mx))
     :concept-draft-count (count (:concept-drafts mx))
     :delegated-2-subbehaviors? (and (= :success (:status survey))
                                     (= :success (:status mx)))}))

;; ---------------------------------------------------------------------------
;; PROOF (b) — STUBBED failing CQ -> ROUTE -> focal re-invoke -> re-gate PASSES
;; ---------------------------------------------------------------------------

(defn proof-b! [ctx]
  (println "  [b] stubbed failing CQ -> route :extract -> focal close grows graph -> re-gate PASSES...")
  (let [oid (random-uuid)
        gate-calls (atom 0)
        close-calls (atom 0)
        ;; gate: first call FAILS (pass-rate 0.0), second call PASSES (1.0).
        gate-fn (fn [_ctx _p]
                  (swap! gate-calls inc)
                  (if (= 1 @gate-calls)
                    {:cq-verdict [{:cq-text "Which program prepares for Animal Scientist?" :verdict :fail}]
                     :graph-health {:pass-rate 0.0 :unknown-rate 0.0}
                     :evaluated [{:cq-text "Which program prepares for Animal Scientist?" :verdict :fail}]}
                    {:cq-verdict [{:cq-text "Which program prepares for Animal Scientist?" :verdict :pass}]
                     :graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                     :evaluated [{:cq-text "Which program prepares for Animal Scientist?" :verdict :pass}]}))
        ;; route: maps the missing-entity gap to :extract (the closing subbehavior).
        route-fn (fn [_ctx {:keys [failing-cq]}]
                   {:route :extract :reasoning (str "the entity for '" failing-cq "' is missing — re-extract")})
        ;; the closing model->extract seam GROWS the graph (lands a concept).
        model-extract-fn (fn [c _p]
                           (swap! close-calls inc)
                           (ontology/compile-discovery-source!
                            c oid {:status :emitted-drafts
                                   :emitted-concepts [{:uri "concept:program/agriculture"
                                                       :label "Agriculture, General"}]
                                   :emitted-relationships []})
                           {:status :success :concept-drafts [{:uri "concept:program/agriculture"}]
                            :relationship-drafts [] :embed-fields []})
        reconcile-fn (fn [_ _] {:status :success :reconcile-report {}})
        embed-fn (fn [_ _] {:status :success :embed-index-report {}})
        result (ce/cq-objective-loop!
                ctx {:ontology-id oid :source {:type :csv :path csv-path}
                     :goal the-goal :profile {}
                     :pipeline-sheet-id (random-uuid) :route-sheet-id (random-uuid)
                     :gate-fn gate-fn :route-fn route-fn
                     :model-extract-fn model-extract-fn
                     :reconcile-fn reconcile-fn :embed-fn embed-fn})]
    (println "      loop status:" (:status result)
             "termination:" (get-in result [:cq-loop :termination-reason])
             "route closed:" (get-in result [:cq-loop :history 0 :route]))
    {:status (:status result)
     :termination-reason (get-in result [:cq-loop :termination-reason])
     :route-taken (get-in result [:cq-loop :history 0 :route])
     :close-calls @close-calls
     :gate-calls @gate-calls
     :route-and-close-passed? (and (= :complete (:status result))
                                   (= :cq-gate-passed (get-in result [:cq-loop :termination-reason]))
                                   (= :extract (get-in result [:cq-loop :history 0 :route]))
                                   (= 1 @close-calls))}))

;; ---------------------------------------------------------------------------
;; PROOF (c) — STUBBED unanswerable CQ (route :terminate) -> honest terminate
;; ---------------------------------------------------------------------------

(defn proof-c! [ctx]
  (println "  [c] stubbed unanswerable CQ -> route :terminate -> honest terminate (no spin, no false-green)...")
  (let [oid (random-uuid)
        route-calls (atom 0)
        close-calls (atom 0)
        ;; gate ALWAYS fails (the CQ is genuinely unanswerable).
        gate-fn (fn [_ _]
                  {:cq-verdict [{:cq-text "Which planet is this program on?" :verdict :unknown}]
                   :graph-health {:pass-rate 0.0 :unknown-rate 1.0}
                   :evaluated [{:cq-text "Which planet is this program on?" :verdict :unknown}]})
        ;; the ROUTE judges the source genuinely lacks the data -> :terminate.
        route-fn (fn [_ _]
                   (swap! route-calls inc)
                   {:route :terminate :reasoning "the source contains no planetary data — unanswerable"})
        ;; the close seam MUST NOT be called (route :terminate attempts no close).
        model-extract-fn (fn [_ _] (swap! close-calls inc) {:status :success})
        result (ce/cq-objective-loop!
                ctx {:ontology-id oid :source {:type :csv :path csv-path}
                     :goal the-goal :profile {}
                     :evolver-config {:max-iterations 5}
                     :pipeline-sheet-id (random-uuid) :route-sheet-id (random-uuid)
                     :gate-fn gate-fn :route-fn route-fn
                     :model-extract-fn model-extract-fn})]
    (println "      loop status:" (:status result)
             "termination:" (get-in result [:cq-loop :termination-reason])
             "unanswerable:" (get-in result [:cq-loop :unanswerable-cqs])
             "route-calls:" @route-calls "close-calls:" @close-calls)
    {:status (:status result)
     :termination-reason (get-in result [:cq-loop :termination-reason])
     :unanswerable-cqs (get-in result [:cq-loop :unanswerable-cqs])
     :route-calls @route-calls
     :close-calls @close-calls
     :honest-terminate? (and (= :failed-cq (:status result))
                             (= :all-remaining-unanswerable (get-in result [:cq-loop :termination-reason]))
                             (= ["Which planet is this program on?"]
                                (get-in result [:cq-loop :unanswerable-cqs]))
                             (zero? @close-calls)               ; no close attempted
                             (= 1 @route-calls))}))            ; routed once, then stopped (no spin)

(defn run-all! [{:keys [skip-a?]}]
  (when (and (not skip-a?) (not (System/getenv "OPENROUTER_API_KEY")))
    (throw (ex-info "OPENROUTER_API_KEY required for proof (a); pass {:skip-a? true} to skip it" {})))
  (when-not skip-a? (register-openrouter! default-llm-model))
  (write-source!)
  (let [ctx (make-ctx)]
    (try
      (println "=== EB10 CENTRAL EVOLVER PROTOTYPE ===")
      (let [a (when-not skip-a? (proof-a! ctx))
            b (proof-b! ctx)
            c (proof-c! ctx)]
        {:proof-a a :proof-b b :proof-c c
         :all-pass? (and (or skip-a? (:delegated-2-subbehaviors? a))
                         (:route-and-close-passed? b)
                         (:honest-terminate? c))})
      (finally (stop-ctx ctx)))))

(defn print-summary! [r]
  (println "\n================ EB10 PROTOTYPE SUMMARY ================")
  (println "(a) :delegate to >=2 real subbehaviors:"
           (if-let [a (:proof-a r)] (:delegated-2-subbehaviors? a) :SKIPPED))
  (when-let [a (:proof-a r)]
    (println "    survey status:" (:survey-status a) "| model->extract status:" (:mx-status a)
             "| concept-drafts:" (:concept-draft-count a)))
  (println "(b) route-and-close (fail->route :extract->focal re-invoke->re-gate PASS):"
           (:route-and-close-passed? (:proof-b r)))
  (println "(c) honest-terminate (unanswerable->route :terminate->surfaced, no spin):"
           (:honest-terminate? (:proof-c r)))
  (println "ALL PROOFS PASS?:" (:all-pass? r)))

(defn save-capture! [r]
  (let [path "docs/build-timeline/live-verify/EB10-prototype.md"]
    (io/make-parents path)
    (spit path
          (str "# EB10 — Central evolver loop — PROTOTYPE (3 proofs)\n\n"
               "**Branch:** `feature/ontology-architecture`. Proof (a) is REAL "
               "(real Grain + real OpenRouter `:delegate` to >=2 subbehaviors); "
               "proofs (b)/(c) exercise the route-and-close + honest-terminate LOOP "
               "LOGIC deterministically (stubbed CQ verdict + stubbed route/close "
               "seams) — the loop branching with no LLM/ColBERT.\n\n"
               "## (a) :delegate to >=2 REAL subbehaviors end-to-end\n\n```clojure\n"
               (with-out-str (pp/pprint (:proof-a r))) "```\n\n"
               "## (b) route-and-close — fail -> route :extract -> focal re-invoke -> re-gate PASS\n\n```clojure\n"
               (with-out-str (pp/pprint (:proof-b r))) "```\n\n"
               "## (c) honest-terminate — unanswerable -> route :terminate -> surfaced, no spin\n\n```clojure\n"
               (with-out-str (pp/pprint (:proof-c r))) "```\n\n"
               "## Verdict\n\nALL PROOFS PASS?: **" (:all-pass? r) "**\n"))
    (println "Capture written:" path)
    path))

(defn -main [& args]
  (let [skip-a? (some #{"skip-a"} args)
        fut (future
              (try
                (let [r (run-all! {:skip-a? skip-a?})]
                  (print-summary! r)
                  (save-capture! r)
                  (if (:all-pass? r) :done :error))
                (catch Throwable t
                  (println "EB10 prototype FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  :error)))
        result (deref fut 320000 :timeout)]
    (println "\nEB10 prototype result:" result)
    (shutdown-agents)
    (System/exit (if (= :done result) 0 1))))

(comment
  (require '[eb10-central-evolver-prototype :as p] :reload)
  (def r (p/run-all! {:skip-a? true}))
  (p/print-summary! r))
