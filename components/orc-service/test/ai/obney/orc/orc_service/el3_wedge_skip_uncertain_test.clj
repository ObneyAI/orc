(ns ai.obney.orc.orc-service.el3-wedge-skip-uncertain-test
  "EL-3 (ADR 0015): the C-2c-2 wedge maybe-auto-classify-and-set-context
   respects the three-state :outcome.

   Detect-and-defer: when the structural OR behavioral classification outcome
   is :uncertain (a reranker fallback), the wedge SKIPS the
   :ontology/assign-task-class dispatch entirely — no fresh-mint event, no
   class creation/accrual (we do not know the class) — but STILL sets the node
   :context so the R-Inject reranker-fallback caution surfaces.

   :matched / :novel keep dispatching as today.

   The defeat condition is an :uncertain outcome that still mints/assigns a
   class. We assert by READING the event store back (the absent
   :ontology/task-classified event), not a return value."
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
        cache-dir (str "/tmp/el3-wedge-" (random-uuid))
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

(defn- task-classified-events [ctx]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :types #{:ontology/task-classified}})))

(defn- node []
  {:id (random-uuid)
   :name "test-node"
   :type :repl-researcher
   :instruction "do the thing"
   :reads [] :writes []
   :rlm {:auto-classify? true}})

;; =============================================================================
;; RED — structural :uncertain → wedge SKIPS assign-task-class entirely
;; =============================================================================

(deftest wedge-skips-assign-when-structural-outcome-uncertain
  (testing "structural :outcome :uncertain → NO :ontology/assign-task-class command, NO task-classified event"
    (with-test-ctx [ctx]
      (let [uncertain-structural {:assigned-tree-id nil
                                  :confidence 0.0
                                  :top-candidates []
                                  :reasoning "reranker fell back; deferred"
                                  :outcome :uncertain
                                  :parent-tree-id nil
                                  :rerank-fallback? true}
            behavioral {:behaviors [] :outcome :uncertain :rerank-fallback? true}]
        (with-redefs [ontology/classify-task (fn [_ _] uncertain-structural)
                      ontology/classify-behaviors (fn [_ _] behavioral)]
          (let [result-node (tp/maybe-auto-classify-and-set-context (node) ctx)
                events (task-classified-events ctx)]
            ;; DEFEAT CONDITION (read the store back): an :uncertain outcome
            ;; must NOT mint/assign a class.
            (is (= 0 (count events))
                "DEFEAT CONDITION: :uncertain dispatched an assign-task-class command (minted/assigned)")
            ;; But the node :context is STILL set so the R-Inject caution surfaces.
            (is (some? (:context result-node))
                ":context is still set under :uncertain so the caution surfaces")
            (is (true? (get-in result-node [:context :r05-classifier :structural :rerank-fallback?]))
                "the structural rerank-fallback? caution is carried on :context")))))))

(deftest wedge-skips-assign-when-behavioral-outcome-uncertain
  (testing "structural matched but behavioral :outcome :uncertain → still SKIP assign (defer)"
    (with-test-ctx [ctx]
      (let [matched-structural {:assigned-tree-id (random-uuid)
                                :confidence 0.9
                                :top-candidates []
                                :reasoning "structural fit"
                                :outcome :matched
                                :was-fresh-mint? false
                                :parent-tree-id nil
                                :rerank-fallback? false}
            uncertain-behavioral {:behaviors [] :outcome :uncertain :rerank-fallback? true}]
        (with-redefs [ontology/classify-task (fn [_ _] matched-structural)
                      ontology/classify-behaviors (fn [_ _] uncertain-behavioral)]
          (let [result-node (tp/maybe-auto-classify-and-set-context (node) ctx)
                events (task-classified-events ctx)]
            (is (= 0 (count events))
                "DEFEAT CONDITION: behavioral :uncertain still dispatched an assign-task-class command")
            (is (some? (:context result-node))
                ":context still set so the caution surfaces")
            (is (true? (get-in result-node [:context :r05-classifier :behavioral :rerank-fallback?]))
                "the behavioral rerank-fallback? caution is carried on :context")))))))

;; =============================================================================
;; GREEN-GUARD — :matched / :novel still dispatch (no over-skip regression)
;; =============================================================================

(deftest wedge-still-dispatches-when-matched
  (testing ":outcome :matched → assign-task-class dispatched as today"
    (with-test-ctx [ctx]
      (let [assigned (random-uuid)
            matched-structural {:assigned-tree-id assigned
                                :confidence 0.9
                                :top-candidates []
                                :reasoning "fit"
                                :outcome :matched
                                :was-fresh-mint? false
                                :parent-tree-id nil
                                :rerank-fallback? false}
            matched-behavioral {:behaviors [{:behavior-id (random-uuid) :confidence 0.8
                                             :was-fresh-mint? false :reasoning "ok"
                                             :rerank-source :reranker}]
                                :outcome :matched :rerank-fallback? false}]
        (with-redefs [ontology/classify-task (fn [_ _] matched-structural)
                      ontology/classify-behaviors (fn [_ _] matched-behavioral)]
          (let [_ (tp/maybe-auto-classify-and-set-context (node) ctx)
                events (task-classified-events ctx)]
            (is (= 1 (count events)) ":matched still dispatches exactly one assign-task-class")
            (is (= assigned (:assigned-tree-id (first events)))
                "the matched tree-id is assigned")))))))

(deftest wedge-still-dispatches-when-novel
  (testing ":outcome :novel (confident no-match) → assign-task-class dispatched (novelty marker), NOT skipped"
    (with-test-ctx [ctx]
      (let [novel-structural {:assigned-tree-id (random-uuid)
                              :confidence 0.0
                              :top-candidates []
                              :reasoning "minting fresh"
                              :outcome :novel
                              :was-fresh-mint? true
                              :parent-tree-id nil
                              :rerank-fallback? false}
            novel-behavioral {:behaviors [{:behavior-id (random-uuid) :confidence 0.0
                                           :was-fresh-mint? true :reasoning "minting fresh"
                                           :rerank-source :reranker}]
                              :outcome :novel :rerank-fallback? false}]
        (with-redefs [ontology/classify-task (fn [_ _] novel-structural)
                      ontology/classify-behaviors (fn [_ _] novel-behavioral)]
          (let [_ (tp/maybe-auto-classify-and-set-context (node) ctx)
                events (task-classified-events ctx)]
            (is (= 1 (count events))
                ":novel STILL dispatches (it is a confident no-match, not uncertainty)")
            (is (true? (:was-fresh-mint? (first events)))
                "novel carries the fresh-mint novelty marker on the event")))))))
