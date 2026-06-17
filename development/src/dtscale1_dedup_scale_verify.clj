(ns dtscale1-dedup-scale-verify
  "DTscale-1 — SCALE LIVE VERIFY: drive the discovery tree over the REAL
   crosswalk CSV (thousands of cip:/soc: concepts) with a REAL LLM + REAL
   Grain, and prove the dedup stage completes in MINUTES, not hours — with
   the LSH/MinHash blocking + pure pre-filter + project-once fix in place.

   Captures (the scale proof):
     - concept count in the built graph
     - candidate-pair count BEFORE blocking (the old O(n^2) all-pairs) vs
       AFTER blocking (the LSH-pruned set)
     - dedup wall-time (from build! stage-timings)
     - verdict counts (merges / distinct / skipped / requires-review +
       prefiltered vs survivors)
     - whether embed + ColBERT index also complete at scale (second-wall note)

   JVM HYGIENE: this run is HARD-BOUNDED. `run!` spawns the work in a future
   and `(deref f timeout-ms ::timeout)`s it; `main`-style callers `System/exit`
   so a regression can NEVER hot-loop indefinitely. Always launch via a
   killable background process and confirm 0 orphan JVMs afterward.

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[dtscale1-dedup-scale-verify :as v])
     (v/run! {:path \"/Users/darylroberts/Downloads/cip_soc_crosswalk.csv\"
              :timeout-ms 1200000})"
  (:require [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.set :as set]
            [clojure.pprint :as pp]))

(def default-model "google/gemini-3-flash-preview")

(def domain-goal
  (str "Build an ontology of this crosswalk: mint each distinct code/title the "
       "source reports as its own node carrying its own values, and connect "
       "related codes. Cover the data."))

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
        dir (str "/tmp/dtscale1-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "dtscale1-live"
                          :map-size (* 4 1024 1024 1024)}))
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

(defn- record-spec! [ctx ontology-id body]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-ontology-spec
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :body body})))

(defn- always-pass-judge [_]
  {:verdict :pass :reasoning "scale-verify judge (mechanics)" :evidence-uris [] :gaps []})

(defn- all-pairs-count [n] (quot (* n (dec n)) 2))

(defn- run-impl
  [{:keys [model budget path]
    :or {model default-model
         budget {:max-iterations 12 :total-budget-ms 1500000 :max-retries 3}}}]
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (register-openrouter! model)
      (record-spec! ctx oid
                    {:purpose "DTscale-1 dedup scale verification"
                     :competency-questions
                     ["Does the graph contain code/title concepts?"]})
      (Thread/sleep 100)
      (println "=== DTscale-1 DEDUP SCALE LIVE VERIFY ===")
      (println "ontology-id:" oid "model:" model)
      (println "source:" path "budget:" budget)
      (let [t0 (System/currentTimeMillis)
            result (dt/run-discovery-tree!
                    ctx {:ontology-id oid
                         :source {:name :crosswalk :type :csv :path path}
                         :goal domain-goal
                         :model model
                         :budget budget
                         :judge-fn always-pass-judge
                         :exit-criterion {:pass-rate-min 0.0 :unknown-rate-max 1.0}})
            ms (- (System/currentTimeMillis) t0)
            build-result (:build-result result)
            concepts (->> (rm/get-concepts ctx {})
                          (filter #(= oid (:ontology-id %)))
                          (mapv (fn [c]
                                  (cond-> (select-keys c [:uri :label :description :type])
                                    (seq (:broader c)) (assoc :broader (vec (:broader c)))))))
            n (count concepts)
            ;; Candidate-pair count AFTER blocking (the LSH-pruned set the
            ;; dedup stage actually enumerates), measured directly on the
            ;; built graph. BEFORE = the old O(n^2) all-pairs the coarse
            ;; token-overlap blocker would have approached.
            t-block (System/currentTimeMillis)
            blocked-pairs (count (dedup/lsh-candidate-pairs concepts))
            block-ms (- (System/currentTimeMillis) t-block)
            dedup-ms (get-in build-result [:stage-timings :dedup])
            relationships (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
        (println "\n--- SCALE PROOF ---")
        (println "tree-status:" (:status result) "build-status:" (:build-status result)
                 "total-elapsed-ms:" ms)
        (println "concepts:" n "relationships:" (count relationships))
        (println "candidate pairs BEFORE blocking (n*(n-1)/2):" (all-pairs-count n))
        (println "candidate pairs AFTER LSH blocking:" blocked-pairs
                 (format "(%.5f of all-pairs, blocking took %dms)"
                         (if (pos? (all-pairs-count n))
                           (double (/ blocked-pairs (all-pairs-count n))) 0.0)
                         block-ms))
        (println "dedup stage wall-time (ms):" dedup-ms)
        (println "dedup-summary:" (pr-str (:dedup-summary build-result)))
        (println "stage-timings:" (pr-str (:stage-timings build-result)))
        {:ontology-id oid :model model :budget budget
         :tree-status (:status result) :build-status (:build-status result)
         :total-elapsed-ms ms
         :concepts n :relationships (count relationships)
         :pairs-before-blocking (all-pairs-count n)
         :pairs-after-blocking blocked-pairs
         :blocking-ms block-ms
         :dedup-ms dedup-ms
         :stage-timings (:stage-timings build-result)
         :dedup-summary (:dedup-summary build-result)
         :referential-integrity (:referential-integrity build-result)
         :stages-run (:stages-run build-result)
         :error (:error build-result)})
      (finally (stop-ctx ctx)))))

(defn run!
  "Hard-bounded scale verify. Runs `run-impl` in a future; if it exceeds
   `:timeout-ms` (default 20 min) the future is cancelled and ::timeout is
   returned — the caller MUST then kill the JVM by PID. Returns the capture
   map (or ::timeout)."
  [{:keys [timeout-ms] :or {timeout-ms 1200000} :as opts}]
  (let [f (future (run-impl opts))
        r (deref f timeout-ms ::timeout)]
    (when (= r ::timeout)
      (println "!!! TIMEOUT after" timeout-ms "ms — cancelling + signalling")
      (future-cancel f))
    r))

(defn -main [& args]
  (let [path (or (first args) "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")
        timeout-ms (if (second args) (Long/parseLong (second args)) 1200000)
        r (run! {:path path :timeout-ms timeout-ms})]
    (println "\n=== CAPTURE ===")
    (pp/pprint r)
    (flush)
    ;; HARD self-terminate so no JVM lingers, hot-loop or not.
    (shutdown-agents)
    (System/exit (if (= r ::timeout) 2 0))))
