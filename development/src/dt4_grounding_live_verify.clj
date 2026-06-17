(ns dt4-grounding-live-verify
  "DT4-grounding — LIVE VERIFY: the focused Transform node AUTONOMOUSLY authors a
   FIELD-GROUNDED transform that yields a sane scoped concept count on a REAL
   source — NO hand-correction of field names.

   This is the mandatory live verification (Discipline #4) for the DT4-grounding
   fix. The honest negative from DT4 was: the model authored structurally-correct
   transforms whose per-row FIELD ACCESS was grounded in ASSUMED key names/shapes
   (e.g. (get row \"unitid\") where the real SQL key is :UNITID; (get row
   :CIP2020Code) where the real CSV key is \"CIP_Code\") → 0 concepts on the
   model's own output. The fix surfaces the REAL sampled-row key shape into the
   transform prompt seam (sample-row-key-shape -> key-shape-block) AND adds a
   sample-validation gate that rejects an empty-yield transform at authoring time.

   This driver runs the FULL discovery tree (Profile -> Model -> Transform ->
   [V20 apply] -> build!) on TWO real sources under scoped goals and captures the
   model's UNCORRECTED transform-source + the resulting non-zero scoped counts.
   NO field name is hand-corrected anywhere — what the model emits is what runs.

   No mocks: real Grain event store, real OpenRouter LLM, real SCI eval, real
   stream over the real DB / CSV.

   USAGE (REPL with :dev:test, OPENROUTER_API_KEY in env ONLY):
     (require '[dt4-grounding-live-verify :as g])
     (def sql (g/run-sql! {}))   (g/print-summary! sql)
     (def csv (g/run-csv! {}))   (g/print-summary! csv)
     (g/save-capture! [sql csv])"
  (:require [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [litellm.router :as litellm-router]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def ipeds-db "/Users/darylroberts/Downloads/output.db")
(def crosswalk-csv "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")
(def default-model "google/gemini-3-flash-preview")
(def capture-path "docs/build-timeline/live-verify/DT4-grounding.md")

;; Goals only — scope from the GOAL, no recipe, no field names, no transform.
(def sql-goal
  (str "Build an ontology of the educational programs/awards reported in this "
       "source for Louisiana students — one node per distinct program an "
       "institution awards (not per demographic sub-count), scoped to Louisiana "
       "institutions only."))

(def csv-goal
  (str "Build an ontology of fields/programs of study and the occupations they "
       "prepare people for, scoped to Agriculture programs (CIP family 01)."))

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
        dir (str "/tmp/dt4-grounding-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "dt4-grounding"
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

(defn- run-source!
  [{:keys [label source goal model budget]
    :or {model default-model
         budget {:max-iterations 16 :total-budget-ms 600000 :max-retries 3}}}]
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (register-openrouter! model)
      (println "=== DT4-GROUNDING LIVE VERIFY:" label "===")
      (println "ontology-id:" oid " model:" model)
      (println "source:" (:path source) " goal:" goal)
      (let [t0 (System/currentTimeMillis)
            result (dt/run-discovery-tree!
                    ctx {:ontology-id oid :source source :goal goal
                         :model model :budget budget})
            ms (- (System/currentTimeMillis) t0)
            bb (:blackboard result)
            transform-out (dt/node-output bb :transform)
            ;; read-back from the projection — the authoritative count.
            concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
            relationships (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))
            by-scheme (frequencies
                       (map #(let [u (str (:uri %))
                                   i (str/index-of u ":")]
                               (if i (subs u 0 i) "other"))
                            concepts))]
        (println "\nstatus:" (:status result) " (" ms "ms)")
        (println "sample-validation:" (pr-str (:sample-validation result)))
        (println "full-extraction:" (pr-str (:full-extraction result)))
        (println "concepts-in-graph:" (count concepts)
                 " relationships-in-graph:" (count relationships))
        (println "by-scheme:" by-scheme)
        {:label label
         :ontology-id oid :model model :budget budget :goal goal
         :source source
         :status (:status result)
         :ms ms
         :sample-validation (:sample-validation result)
         ;; the model's UNCORRECTED authored transform — VERBATIM, no edits.
         :transform-source (:transform-source transform-out)
         :selector (:selector transform-out)
         :model-spec (dt/node-output bb :model)
         :profile-sample (take 3 (:sample (dt/node-output bb :profile)))
         :full-extraction (:full-extraction result)
         :concepts-in-graph (count concepts)
         :relationships-in-graph (count relationships)
         :by-scheme by-scheme
         :sample-concepts (vec (take 8 (map #(select-keys % [:uri :label]) concepts)))
         :error (:error result)})
      (finally (stop-ctx ctx)))))

(defn run-sql! [opts]
  (run-source! (merge {:label "SQL IPEDS — Louisiana-scoped"
                       :source {:name :ipeds :type :sql :path ipeds-db}
                       :goal sql-goal}
                      opts)))

(defn run-csv! [opts]
  (run-source! (merge {:label "CSV CIP/SOC crosswalk — CIP-01 scoped"
                       :source {:name :crosswalk :type :csv :path crosswalk-csv}
                       :goal csv-goal}
                      opts)))

(defn print-summary! [r]
  (println "\n================ DT4-GROUNDING:" (:label r) "================")
  (println "status:" (:status r) " concepts:" (:concepts-in-graph r)
           " relationships:" (:relationships-in-graph r))
  (println "sample-validation:" (pr-str (:sample-validation r)))
  (println "selector:" (pr-str (:selector r)))
  (println "\n--- model's UNCORRECTED authored transform (VERBATIM) ---")
  (println (:transform-source r))
  (println "\n--- by-scheme ---" (pr-str (:by-scheme r))))

(defn save-capture! [results]
  (io/make-parents capture-path)
  (spit capture-path
        (str "# DT4-grounding — autonomous field-grounded transform — LIVE VERIFY\n\n"
             "**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.\n"
             "**Model:** `" (:model (first results)) "` (real OpenRouter). **No mocks.**\n\n"
             "The DT4-grounding fix surfaces the REAL sampled-row key shape into the\n"
             "transform prompt seam (`sample-row-key-shape` -> `key-shape-block`) and adds\n"
             "a sample-validation gate (`validate-transform-on-sample`) that rejects an\n"
             "empty-yield transform at authoring time. This capture drives the FULL\n"
             "discovery tree (Profile -> Model -> Transform -> [V20 apply] -> build!) on\n"
             "two real sources under scoped goals. The transform-source below is the\n"
             "model's UNCORRECTED, AUTONOMOUS output — VERBATIM, NO field name was\n"
             "hand-corrected anywhere. What the model emitted is what ran at full scale.\n\n"
             (apply str
                    (for [r results]
                      (str "---\n\n## " (:label r) "\n\n"
                           "GOAL: " (:goal r) "\n\n"
                           "Ontology-id: `" (:ontology-id r) "`. status: `"
                           (:status r) "` (" (:ms r) "ms).\n\n"
                           "### The model's UNCORRECTED, AUTONOMOUS transform (VERBATIM — no field correction)\n\n"
                           "Selector: `" (pr-str (:selector r)) "`\n\n"
                           "```clojure\n" (:transform-source r) "\n```\n\n"
                           "### Sample-validation gate (ran on the REAL profile-sampled rows BEFORE full apply)\n\n"
                           "```clojure\n" (with-out-str (pp/pprint (:sample-validation r))) "```\n\n"
                           "### Full-extraction coverage (V20 apply over the FULL source)\n\n"
                           "```clojure\n" (with-out-str (pp/pprint (:full-extraction r))) "```\n\n"
                           "### Read-back from the projection (authoritative)\n\n"
                           "- concepts in graph: **" (:concepts-in-graph r) "**\n"
                           "- relationships in graph: **" (:relationships-in-graph r) "**\n"
                           "- concepts by uri-scheme: `" (pr-str (:by-scheme r)) "`\n\n"
                           "Sample concepts:\n\n```clojure\n"
                           (with-out-str (pp/pprint (:sample-concepts r))) "```\n\n"
                           "### Model-spec the transform enforced\n\n```clojure\n"
                           (with-out-str (pp/pprint (:model-spec r))) "```\n\n"
                           "### A real profile-sampled row (the key shape surfaced to the prompt)\n\n```clojure\n"
                           (with-out-str (pp/pprint (:profile-sample r))) "```\n\n")))
             "## Proof no field-correction was applied\n\n"
             "The `:transform-source` blocks above are the verbatim `(node-output bb\n"
             ":transform)` `:transform-source` strings — read straight off the blackboard\n"
             "and handed UNCHANGED into the V20 `apply-extraction-transform!`. The driver\n"
             "(`development/src/dt4_grounding_live_verify.clj`) performs NO string\n"
             "rewriting of the transform: grep it for any `replace`/`get row` rewrite —\n"
             "there is none. The non-zero counts are produced by the model's own field\n"
             "access against the real row key shape.\n"))
  (println "Capture written:" capture-path)
  capture-path)

(comment
  (require '[dt4-grounding-live-verify :as g] :reload)
  (def sql (g/run-sql! {}))  (g/print-summary! sql)
  (def csv (g/run-csv! {}))  (g/print-summary! csv)
  (g/save-capture! [sql csv]))
