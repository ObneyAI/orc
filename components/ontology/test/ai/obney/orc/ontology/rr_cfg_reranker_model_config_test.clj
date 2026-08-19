(ns ai.obney.orc.ontology.rr-cfg-reranker-model-config-test
  "RR-CFG: the reranker's model is CONFIGURABLE, not hardcoded.

   The defect this closes: during the PR-6 free shakeout every model slot
   was set to a `:free` model, yet the store still recorded PAID
   `google/gemini-3-flash-preview` executions — the reranker's model was a
   hardcoded var with no config seam, so a run configured entirely free
   silently billed a model outside every configured slot.

   The ratified default (grill GR-2 Q2 / CC-9c, ADR 0025) is UNCHANGED.
   This makes it OVERRIDABLE.

   Every assertion here reads the RECORDED EXECUTION back from the event
   store (`:sheet/node-execution-completed`'s top-level `:model`) — the
   field that says which model actually ran and therefore which one gets
   billed. Not the workflow map, not a return value."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.reranker :as reranker]
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
        cache-dir (str "/tmp/rr-cfg-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :llm-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
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
;; Fixtures
;; =============================================================================

(def ^:private free-model
  "Stands in for the `:free` slot a free shakeout configures. Never called —
   the provider boundary is faked — but it must NOT be the ratified default,
   so a hardcoded resolution cannot accidentally satisfy the assertion."
  "qwen/qwen3.5-flash-02-23:free")

(def ^:private candidates
  [{:content "A candidate description." :score 0.8 :document-id "a"
    :document-metadata {:granularity :node-type :target-id "a"
                        :confidence 0.8 :last-update "2026"}}])

(defn- with-faked-provider
  "Fake the provider boundary so no live call is made. Mirrors what a real
   provider returns, including the resolved :model, so the executor records a
   faithful completion event."
  [f]
  (let [payload (json/write-str [{:document_id "a" :reasoning "fits"
                                  :fitness_score 0.9}])]
    (with-redefs [ai.obney.orc.llm.interface/predict
                  (fn [_provider _module _inputs options]
                    {:outputs {:reranked-json payload}
                     :usage {:total-tokens 100}
                     :model (:model options)})]
      (f))))

(defn- read-recorded-models [ctx]
  (->> (into [] (es/read (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :types #{:sheet/node-execution-completed}}))
       (keep :model)
       (into #{})))

(defn- recorded-rerank-models
  "Every model the store says actually EXECUTED — read off
   :sheet/node-execution-completed's top-level :model.

   Polls rather than sleeps a fixed interval: the completion event is
   written by a command dispatched off the calling thread, so a fixed sleep
   would make these tests timing-dependent. Bounded so a genuinely absent
   event still fails the assertion instead of hanging."
  [ctx]
  (loop [waited 0]
    (let [models (read-recorded-models ctx)]
      (if (or (seq models) (>= waited 5000))
        models
        (do (Thread/sleep 25) (recur (+ waited 25)))))))

;; =============================================================================
;; Cycle 1 — a CONFIGURED reranker model is the model that executes
;; =============================================================================

(deftest configured-reranker-model-is-the-model-that-executes
  (testing "with :ontology-reranker-model configured on the context and NO explicit :model, the recorded execution's model is the configured one"
    (with-test-ctx [ctx]
      (let [ctx (assoc ctx :ontology-reranker-model free-model)]
        (with-faked-provider
          (fn []
            (reranker/rerank! ctx {:query "q" :intent "i" :candidates candidates})
            (let [models (recorded-rerank-models ctx)]
              (is (contains? models free-model)
                  "the configured reranker model is what the store says executed")
              (is (not (contains? models reranker/default-model))
                  (str "DEFEAT CONDITION: a run configured for " free-model
                       " still executed the hardcoded default "
                       reranker/default-model)))))))))

;; =============================================================================
;; Cycle 2 — precedence: explicit :model > configured > ratified default
;; =============================================================================

(deftest explicit-model-beats-configured-model
  (testing "an explicit :model argument wins over the configured context value"
    (with-test-ctx [ctx]
      (let [explicit "anthropic/claude-sonnet-4"
            ctx (assoc ctx :ontology-reranker-model free-model)]
        (with-faked-provider
          (fn []
            (reranker/rerank! ctx {:query "q" :intent "i"
                                   :candidates candidates
                                   :model explicit})
            (let [models (recorded-rerank-models ctx)]
              (is (contains? models explicit)
                  "the explicit per-call override is what executed")
              (is (not (contains? models free-model))
                  "the configured value did not win over the explicit argument"))))))))

(deftest unconfigured-context-still-yields-the-ratified-default
  (testing "with nothing configured and no explicit :model, the ratified default (GR-2 Q2 / ADR 0025) is what executes"
    (with-test-ctx [ctx]
      (is (nil? (:ontology-reranker-model ctx))
          "sanity: nothing is configured on this context")
      (with-faked-provider
        (fn []
          (reranker/rerank! ctx {:query "q" :intent "i" :candidates candidates})
          (let [models (recorded-rerank-models ctx)]
            (is (contains? models "google/gemini-3-flash-preview")
                "the ratified default is unchanged when nothing is configured")))))))
