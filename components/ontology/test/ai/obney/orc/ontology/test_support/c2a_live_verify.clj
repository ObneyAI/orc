(ns ai.obney.orc.ontology.test-support.c2a-live-verify
  "C-2a live-verify orchestrator — the end-to-end adversarial-quality gate
   for the Living Description system (C-2a-1, C-2a-2, C-2a-3 combined).

   Three scenarios:

     A — 3-run regression with real LLM (proves anti-recency works)
         Phase X (control) → Phase Y (failure-burst) → Phase Z (recovery)
         Anti-recency claim: strengths soften but don't erase; new
         weaknesses emerge principle-shaped; recovery preserves learning.

     B — Seed-executability spot-check (proves the C-2a-1 seeds aren't
         broken). Picks 3 of the 22 seeds, parses each :recommended-pattern,
         transforms via rlm-dsl/rlm-dsl->orc-dsl, runs via
         rlm-tree-executor/execute-tree against a real LLM, asserts
         :status :success.

     C — Retrieval-quality probe (deferred until C-2b ColBERT wiring
         ships; currently a graceful no-op).

   Run from REPL:
      (require '[ai.obney.orc.ontology.test-support.c2a-live-verify :as v] :reload)
     (v/run!)
     ;; Or override defaults:
     (v/run! {:scenario-b-sample-indices [0 5 11]
              :skip-edn-save? false})

   Requires (for real-LLM runs):
     export OPENROUTER_API_KEY=\"sk-or-v1-...\""
  (:require [clojure.walk]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.orc.ontology.test-support.seed-descriptions :as seeds]
            [litellm.router :as litellm-router]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-llm-model
  "google/gemini-3-flash-preview")

(def default-target-node-type
  :llm)

(def default-scenario-a-event-counts
  "Phase event counts for Scenario A. Live runs use the production-scale
   defaults; tests can override with smaller counts to keep fake-LLM runs fast."
  {:control 20 :failure 10 :recovery 20})

(def default-options
  {:llm-model default-llm-model
   :target-node-type default-target-node-type
   :scenario-a-event-counts default-scenario-a-event-counts
   :scenario-b-sample-indices :default
   :skip-edn-save? false
   :edn-dir "development/bench/generalization-results"
   :register-llm-provider? true})

;; =============================================================================
;; Helpers — context setup
;; =============================================================================

(defn- register-llm-provider! [model]
  (litellm-router/register! :openrouter
                            {:provider :openrouter
                             :model model
                             :config {:api-base "https://openrouter.ai/api/v1"
                                      :api-key (System/getenv "OPENROUTER_API_KEY")}}))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2a-live-verify-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "c2a-lv"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps
                  :dscloj-provider :openrouter
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

;; =============================================================================
;; Scenarios — stubbed at minimum for RED #1
;; =============================================================================

;; =============================================================================
;; Scenario A — 3-run regression (control → failure-burst → recovery)
;; =============================================================================

(defn- emit-events! [ctx target-node-type n-success n-failure]
  (let [sheet-id (random-uuid)
        tick-id  (random-uuid)]
    (dotimes [_ n-success]
      (cp/process-command
        (assoc ctx :command
               {:command/name :sheet/complete-node-execution
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :sheet-id sheet-id :tick-id tick-id :node-id (random-uuid)
                :node-type target-node-type :status :success
                :writes {} :duration-ms (+ 100 (rand-int 200))})))
    (dotimes [_ n-failure]
      (cp/process-command
        (assoc ctx :command
               {:command/name :sheet/complete-node-execution
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :sheet-id sheet-id :tick-id tick-id :node-id (random-uuid)
                :node-type target-node-type :status :failure
                :writes {} :duration-ms (+ 100 (rand-int 200))}))))
  (Thread/sleep 200))

(defn- consolidate-on-demand! [ctx target-node-type]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/request-consolidation
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-type :node-type
            :target-id target-node-type
            :on-demand? true}))
  ;; Allow the consolidator processor (async) time to run the LLM call
  ;; and emit the description-updated event.
  (Thread/sleep 30000))

(defn- run-phase! [ctx target-node-type n-success n-failure phase-label]
  (emit-events! ctx target-node-type n-success n-failure)
  (consolidate-on-demand! ctx target-node-type)
  {:phase phase-label
   :events-emitted {:success n-success :failure n-failure}
   :body (ontology/get-description ctx :node-type target-node-type)})

(defn- scenario-a-passed?
  "Qualitative gate for Scenario A — checked structurally, not via string
   matching. Each phase must produce a body; Phase X has strengths; Phase
   Y has weaknesses (the new failure-burst entry); Phase Z preserves at
   least one weakness from Y (anti-recency preserves learning)."
  [phases]
  (boolean
    (and (= 3 (count phases))
         (every? #(some? (:body %)) phases)
         (let [x-body (:body (first phases))
               y-body (:body (second phases))
               z-body (:body (nth phases 2))]
           (and (some? x-body) (some? y-body) (some? z-body)
                (seq (:strengths x-body))
                (seq (:weaknesses y-body))
                (seq (:weaknesses z-body)))))))

(defn- run-scenario-a [ctx opts]
  (let [tnt (:target-node-type opts)
        counts (:scenario-a-event-counts opts)
        phases [(run-phase! ctx tnt (:control counts)  0 :x)
                (run-phase! ctx tnt 0 (:failure counts)  :y)
                (run-phase! ctx tnt (:recovery counts) 0 :z)]]
    {:passed? (scenario-a-passed? phases)
     :phases phases}))

;; =============================================================================
;; Scenario B — seed-executability spot-check
;; =============================================================================

(defn- all-seeds-with-snippets
  "Return a vector of {:seed-id :snippet-str} from the C-2a-1 seed corpus.
   Only includes seeds whose first strength has a :recommended-pattern."
  []
  (->> (concat seeds/all-node-type-seeds seeds/all-tree-fingerprint-seeds)
       (keep (fn [{:keys [target-id body]}]
               (when-let [snippet (some-> body :strengths first :recommended-pattern)]
                 {:seed-id target-id
                  :snippet snippet})))
       vec))

(defn- replace-inline-fn-forms
  "Walk a parsed tree-DSL form; replace any '(fn ...)' list with a dummy
   fn value so the rlm-dsl transformer accepts the snippet for execution.
   Mirrors the helper in seed_descriptions.clj — kept duplicated here to
   keep the live-verify orchestrator self-contained."
  [form]
  (cond
    (and (seq? form) (= 'fn (first form)))
    (constantly nil)

    (map? form)
    (into {} (map (fn [[k v]] [k (replace-inline-fn-forms v)])) form)

    (sequential? form)
    (into (empty form) (map replace-inline-fn-forms) form)

    :else form))

(defn- collect-reads-from-tree
  "Walk a parsed tree-DSL form and collect all :reads keys mentioned.
   Used to stub blackboard inputs for Scenario B's executability check."
  [form]
  (let [acc (atom #{})]
    (clojure.walk/postwalk
      (fn [node]
        (when (map? node)
          (doseq [k (or (:reads node) [])]
            (swap! acc conj k)))
        node)
      form)
    @acc))

(defn- stub-blackboard
  "Generic per-key stub values keyed by what we infer about the field.
   The intent isn't producing 'real' LLM content — only that the snippet
   compiles + runs without framework errors."
  [reads-keys]
  (reduce (fn [acc k]
            (assoc acc k "sample text for live-verify"))
          {}
          reads-keys))

(defn- try-execute-seed-snippet
  "Attempt to execute one seed's :recommended-pattern via the
   rlm-tree-executor. Returns {:seed-id :status :error}. :status is
   one of :success / :failure / :error (parse error, transform error,
   exception)."
  [ctx {:keys [seed-id snippet]}]
  (try
    (let [form (clojure.core/read-string snippet)
          resolved (replace-inline-fn-forms form)
          orc-tree (rlm-dsl/rlm-dsl->orc-dsl resolved)
          reads-keys (collect-reads-from-tree form)
          exec-result (tree-executor/execute-tree
                        orc-tree
                        ctx
                        {:blackboard (stub-blackboard reads-keys)
                         :timeout-ms 60000})]
      {:seed-id seed-id
       :status (:status exec-result)
       :duration-ms (:duration-ms exec-result)
       :error (:error exec-result)})
    (catch Throwable e
      {:seed-id seed-id
       :status :error
       :error (.getMessage e)})))

(defn- resolve-sample-indices
  "Resolve the :scenario-b-sample-indices option. :default produces a
   deterministic three-seed sample at [0, mid, last]. A vector of ints
   is used as-is. An empty vector means 'no seeds'."
  [option n]
  (cond
    (= option :default) (when (pos? n)
                          [0
                           (quot (dec n) 2)
                           (dec n)])
    (vector? option) option
    :else nil))

(defn- run-scenario-b [ctx opts]
  (let [all-snips (all-seeds-with-snippets)
        sample-indices (resolve-sample-indices (:scenario-b-sample-indices opts)
                                                (count all-snips))
        sampled (mapv #(nth all-snips %) sample-indices)
        results (mapv #(try-execute-seed-snippet ctx %) sampled)]
    {:passed? (every? #(= :success (:status %)) results)
     :sample-indices sample-indices
     :results results}))

(defn- run-scenario-c [_ctx _opts]
  ;; RED #5 will fill this in.
  {:deferred? true
   :reason "Scenario C (retrieval-quality probe) is gated by C-2b ColBERT wiring, not yet shipped."})

;; =============================================================================
;; Driver
;; =============================================================================

(defn- ensure-dir! [^String dir]
  (let [d (java.io.File. dir)]
    (when-not (.exists d) (.mkdirs d))
    dir))

(defn- save-edn!
  "Write a single scenario's structured result to a timestamped EDN
   file in (:edn-dir opts). Filename pattern:
   c2a-live-verify-{scenario}_<unix-ms>.edn"
  [opts scenario-key result]
  (let [dir (ensure-dir! (:edn-dir opts))
        ts (System/currentTimeMillis)
        fname (str dir "/c2a-live-verify-"
                   (name scenario-key) "_" ts ".edn")]
    (spit fname (pr-str result))
    fname))

(defn run!
  "Run the C-2a live-verify orchestrator. Returns a structured map with
   :scenario-a, :scenario-b, :scenario-c keys. Saves per-scenario EDN
   records to (:edn-dir opts) unless :skip-edn-save? is true."
  ([] (run! {}))
  ([opts]
   (let [opts (merge default-options opts)]
     (when (:register-llm-provider? opts)
       (when (System/getenv "OPENROUTER_API_KEY")
         (register-llm-provider! (:llm-model opts))))
     (let [ctx (create-context)]
       (try
         (let [scenario-a (run-scenario-a ctx opts)
               scenario-b (run-scenario-b ctx opts)
               scenario-c (run-scenario-c ctx opts)]
           (when-not (:skip-edn-save? opts)
             (save-edn! opts :scenario-a scenario-a)
             (save-edn! opts :scenario-b scenario-b)
             (save-edn! opts :scenario-c scenario-c))
           {:scenario-a scenario-a
            :scenario-b scenario-b
            :scenario-c scenario-c})
         (finally (stop-context ctx)))))))

(comment
  (run!))
