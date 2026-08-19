(ns ai.obney.orc.orc-service.rr-cfg-auto-classify-rerank-model-test
  "RR-CFG, load-bearing: the R05 auto-classify path honours the configured
   reranker model.

   The PR-6 free shakeout set every model slot to a `:free` model and the
   store STILL recorded four PAID `google/gemini-3-flash-preview`
   executions. That billing came from the reranker firing inside
   `maybe-auto-classify-and-set-context` (R05) — the wedge that classifies
   an :auto-classify? repl-researcher node before it runs.

   So the defeat condition is stated exactly where the money was spent:
   drive the REAL R05 wedge with a context configured for a free model,
   then read the RECORDED EXECUTIONS back out of the event store and assert
   that no model outside the configured slot appears. Nothing here trusts a
   return value or a workflow map.

   Only the two true edges are faked: the ColBERT bridge (so there is a
   candidate to rerank without a live index) and the LLM provider (so no
   paid call is made). Everything between — classify-task,
   classify-behaviors, search-descriptions, apply-rerank, rerank!, sheet
   build + execution — is the real code path."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.llm.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test context
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr-cfg-r05-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :llm-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :sheet-id (random-uuid)
                  :tick-id (random-uuid)
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

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; Fixtures — the two real edges, faked
;; =============================================================================

(def ^:private free-model
  "Stands in for the `:free` slot a free shakeout configures. Deliberately
   NOT the ratified default, so a hardcoded resolution cannot accidentally
   satisfy the assertion."
  "qwen/qwen3.5-flash-02-23:free")

(def ^:private colbert-hit
  [{:content "Rename a symbol across a Clojure namespace."
    :score 0.9 :rank 1
    :document-id (str "tf::" (random-uuid))
    :document_metadata {:granularity "tree-fingerprint"
                        :target-id (str (random-uuid))
                        :confidence 0.8 :last-update "2026"}}])

(defn- fake-list-indexes [_ctx & _opts]
  [{:index-name "ontology-descriptions"
    :index-id (random-uuid)
    :created-at "2026-05-28T00:00:00Z"}])

(defn- with-faked-edges
  "ColBERT bridge + LLM provider only. Everything between is real."
  [f]
  (let [payload (json/write-str
                  (mapv (fn [c] {:document_id (:document-id c)
                                 :reasoning "fits the caller's intent"
                                 :fitness_score 0.95})
                        colbert-hit))]
    (with-redefs [colbert/list-indexes fake-list-indexes
                  colbert/search (fn [_ctx _opts] colbert-hit)
                  ai.obney.orc.llm.interface/predict
                  (fn [_provider _module _inputs options]
                    {:outputs {:reranked-json payload}
                     :usage {:total-tokens 100}
                     :model (:model options)})]
      (f))))

(defn- auto-classify-node []
  {:id (random-uuid)
   :name "rr-cfg-node"
   :type :repl-researcher
   :instruction "rename a symbol across the namespace"
   :reads [] :writes []
   :rlm {:auto-classify? true}})

(defn- read-recorded-models [ctx]
  (->> (into [] (es/read (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :types #{:sheet/node-execution-completed}}))
       (keep :model)
       (into #{})))

(defn- recorded-models
  "Every model the store says actually EXECUTED, read off
   :sheet/node-execution-completed's top-level :model.

   Polls rather than sleeping a fixed interval — the completion event is
   written by a command dispatched off the calling thread, so a fixed sleep
   would make these tests timing-dependent. Bounded, so a genuinely absent
   event fails the assertion rather than hanging."
  [ctx]
  (loop [waited 0]
    (let [models (read-recorded-models ctx)]
      (if (or (seq models) (>= waited 5000))
        models
        (do (Thread/sleep 25) (recur (+ waited 25)))))))

;; =============================================================================
;; The load-bearing test
;; =============================================================================

(deftest r05-auto-classify-rerank-uses-the-configured-model
  (testing "with :ontology-reranker-model configured, an auto-classify-driven rerank executes THAT model — no model outside the configured slot is billed"
    (with-test-ctx [ctx]
      (let [ctx (assoc ctx :ontology-reranker-model free-model)]
        (with-faked-edges
          (fn []
            (tp-core/maybe-auto-classify-and-set-context (auto-classify-node) ctx)
            (let [models (recorded-models ctx)]
              (is (seq models)
                  "sanity: the auto-classify path actually executed a rerank node")
              (is (contains? models free-model)
                  "the configured reranker model is what the store says executed")
              (is (not (contains? models reranker/default-model))
                  (str "DEFEAT CONDITION: a run configured for " free-model
                       " still billed " reranker/default-model
                       " on the auto-classify path — recorded models: "
                       (pr-str models))))))))))

(deftest r05-auto-classify-unconfigured-still-uses-the-ratified-default
  (testing "with nothing configured, the auto-classify rerank still executes the ratified default (GR-2 Q2 / ADR 0025) — the default value is unchanged"
    (with-test-ctx [ctx]
      (is (nil? (:ontology-reranker-model ctx))
          "sanity: nothing is configured on this context")
      (with-faked-edges
        (fn []
          (tp-core/maybe-auto-classify-and-set-context (auto-classify-node) ctx)
          (let [models (recorded-models ctx)]
            (is (contains? models "google/gemini-3-flash-preview")
                "the ratified default is what executes when nothing is configured")))))))
