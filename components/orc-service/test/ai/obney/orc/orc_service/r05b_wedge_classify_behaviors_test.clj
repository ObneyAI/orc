(ns ai.obney.orc.orc-service.r05b-wedge-classify-behaviors-test
  "R05b — C-2c-2 wedge integration test for classify-behaviors.

   Asserts the wedge calls BOTH classify-task and classify-behaviors when
   :auto-classify? true is set, and that classify-behaviors's behaviors
   vector is forwarded onto the dispatched :ontology/assign-task-class
   command body's :behavioral-subtrees field — which the R05b defcommand
   then propagates onto the emitted :ontology/task-classified event."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r05b-wedge-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :sheet-id (random-uuid)
     :tick-id (random-uuid)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
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

(defn- read-task-classified [ctx]
  (->> (into [] (es/read (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :types #{:ontology/task-classified}}))
       first))

;; =============================================================================
;; RED #9 — wedge calls BOTH classify-task and classify-behaviors,
;;            forwards behavioral-subtrees through to the event body
;; =============================================================================

(deftest wedge-calls-classify-behaviors-and-forwards-result
  (with-test-ctx [ctx]
    (let [assigned-tree-id (random-uuid)
          ;; Structural stub — single match, no walk-down, no rerank fallback.
          structural-result {:assigned-tree-id assigned-tree-id
                             :confidence 0.85
                             :top-candidates []
                             :reasoning "structural fit"
                             :was-fresh-mint? false
                             :rerank-fallback? false
                             :parent-tree-id nil}
          ;; Behavioral stub — 2 behaviors above threshold.
          behaviors [{:behavior-id (random-uuid) :confidence 0.92
                      :was-fresh-mint? false :reasoning "analysis dominates"
                      :rerank-source :reranker}
                     {:behavior-id (random-uuid) :confidence 0.81
                      :was-fresh-mint? false :reasoning "synthesis composes"
                      :rerank-source :reranker}]
          behavioral-result {:behaviors behaviors
                             :rerank-fallback? false}
          classify-task-calls (atom [])
          classify-behaviors-calls (atom [])
          node {:id (random-uuid)
                :name "test-node"
                :type :repl-researcher
                :instruction "do the thing"
                :reads []
                :writes []
                :rlm {:auto-classify? true}}]
      (with-redefs [ontology/classify-task
                    (fn [_ctx opts]
                      (swap! classify-task-calls conj opts)
                      structural-result)
                    ontology/classify-behaviors
                    (fn [_ctx opts]
                      (swap! classify-behaviors-calls conj opts)
                      behavioral-result)]
        (tp/maybe-auto-classify-and-set-context node ctx))

      (testing "classify-task was called once"
        (is (= 1 (count @classify-task-calls))
            "Wedge invoked classify-task with the node's signature"))

      (testing "classify-behaviors was called once"
        (is (= 1 (count @classify-behaviors-calls))
            "Wedge invoked classify-behaviors after classify-task"))

      (testing "classify-behaviors received :structural-context = classify-task's :assigned-tree-id"
        (is (= assigned-tree-id
               (-> @classify-behaviors-calls first :structural-context))
            "Wedge forwards the structural tree-id as the behavioral filter context"))

      (testing "emitted :ontology/task-classified event carries :behavioral-subtrees"
        (let [event (read-task-classified ctx)]
          (is (some? event) "Event landed")
          (is (= behaviors (:behavioral-subtrees event))
              "Behavioral classification forwarded all the way to the event body"))))))

;; =============================================================================
;; RED #10 — wedge omits :behavioral-subtrees when behavioral result is empty
;;            (defensive — preserves legacy event shape on opt-out paths)
;; =============================================================================

(deftest wedge-omits-behavioral-subtrees-when-behaviors-empty
  (with-test-ctx [ctx]
    (let [assigned-tree-id (random-uuid)
          structural-result {:assigned-tree-id assigned-tree-id
                             :confidence 0.85
                             :top-candidates []
                             :reasoning "structural fit"
                             :was-fresh-mint? false
                             :rerank-fallback? false
                             :parent-tree-id nil}
          ;; Behavioral result missing :behaviors entirely — defensive
          ;; fallback path when classify-behaviors hasn't run or fails.
          empty-behavioral {:behaviors nil :rerank-fallback? false}
          node {:id (random-uuid)
                :name "test-node"
                :type :repl-researcher
                :instruction "do the thing"
                :reads []
                :writes []
                :rlm {:auto-classify? true}}]
      (with-redefs [ontology/classify-task (constantly structural-result)
                    ontology/classify-behaviors (constantly empty-behavioral)]
        (tp/maybe-auto-classify-and-set-context node ctx))
      (let [event (read-task-classified ctx)]
        (is (some? event) "Event landed")
        (is (not (contains? event :behavioral-subtrees))
            "Legacy event shape preserved when behavioral result is empty/nil")))))
