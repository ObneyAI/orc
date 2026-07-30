(ns ai.obney.orc.orc-service.rr1-classify-placement-test
  "RR-1 (ADR 0020), executor side:

   1. PLACEMENT — `maybe-auto-classify-and-set-context` + R-Inject run
      INSIDE `execute-repl-researcher-node`'s future, so a slow classify
      costs turn latency only and never occupies the dispatch thread the
      todo-processor delivers OTHER ticks' node executions on. The
      function's own docstring already claimed this (\"Runs in a future
      … to avoid blocking pubsub\"); before RR-1 the wedge sat in the
      outer let, ahead of the future, contradicting it.

   2. SIGNAL — the R-Inject prepend still surfaces a caution when the
      reranker fell back, including the new :timeout-fallback flavour.

   Real grain throughout (commands -> schema-validated events ->
   projections; the started event the handler is re-driven with is READ
   BACK from the store, not fabricated). The only injected fault is the
   classifier seam itself — that IS the slow thing under test."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as gtp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Real-grain context (todo processors started — the real async pipeline)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr1-placement-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (gtp/start {:event-pubsub ps :topics topics
                                          :handler-fn handler-fn :context base-ctx})))
                     {} @gtp/processor-registry*)]
    (assoc base-ctx :event-pubsub ps :processors processors ::cache-dir cache-dir)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (gtp/stop p))
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
;; Fixtures: a real repl-researcher sheet with :auto-classify? true
;; =============================================================================

(defn- setup-auto-classify-sheet! [ctx]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "RR1 Placement"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k [:question :answer]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command
                                              sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id "Design a tree for the task" [:question] [:answer] []
                              :max-iterations 1
                              :rlm {:auto-classify? true}))
      {:sheet-id sheet-id :node-id node-id})))

(defn- dispatch-tick! [ctx sheet-id]
  (let [tick-id (random-uuid)]
    (cp/process-command
      (assoc ctx :command {:command/id (random-uuid)
                           :command/timestamp (time/now)
                           :command/name :sheet/tick-tree
                           :sheet-id sheet-id
                           :tick-id tick-id
                           :inputs {:question "what is the shape of this task?"}
                           :options {:timeout-ms 15000}}))
    tick-id))

(defn- await-started-event
  "Poll the store for the repl-researcher node's REAL
   :sheet/node-execution-started event (never fabricated)."
  [ctx node-id deadline-ms]
  (let [deadline (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (let [ev (->> (into [] (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:sheet/node-execution-started}}))
                    (filter #(= node-id (:node-id %)))
                    first)]
        (cond
          ev ev
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 50) (recur)))))))

(def ^:private fast-structural
  {:assigned-tree-id (random-uuid)
   :confidence 0.85
   :top-candidates []
   :reasoning "structural fit"
   :was-fresh-mint? false
   :rerank-fallback? false
   :parent-tree-id nil
   :outcome :matched})

(def ^:private fast-behavioral
  {:behaviors [] :rerank-fallback? false :outcome :matched})

;; =============================================================================
;; RED #6 — a slow classify does NOT hold the dispatch thread
;; =============================================================================

(def ^:private induced-classify-delay-ms
  "How long the injected classify stall lasts. Must be comfortably longer
   than the handler-return budget below so the two cannot be confused."
  4000)

(def ^:private handler-return-budget-ms
  "The dispatch thread must be released essentially immediately. Generous
   enough to absorb read-model projection + future setup on a loaded CI
   box, still an order of magnitude under the induced stall."
  1500)

(deftest slow-classify-does-not-block-the-dispatch-thread
  (testing "execute-repl-researcher-node returns to its caller (the todo-processor dispatch thread) immediately even when classify is slow — the wedge runs inside the future"
    (with-test-ctx [ctx]
      (let [{:keys [sheet-id node-id]} (setup-auto-classify-sheet! ctx)
            classify-ran (promise)]
        ;; Phase 1 — drive the REAL pipeline with a FAST classify so a real
        ;; :sheet/node-execution-started event lands in the store.
        (with-redefs [ontology/classify-task (fn [_ _] fast-structural)
                      ontology/classify-behaviors (fn [_ _] fast-behavioral)]
          (dispatch-tick! ctx sheet-id)
          (let [started (await-started-event ctx node-id 10000)]
            (is (some? started)
                "Sanity: the real pipeline emitted a :sheet/node-execution-started event for the repl-researcher node")
            (Thread/sleep 500)
            ;; Phase 2 — re-drive the handler with a SLOW classify and time
            ;; how long the CALLER is held.
            (with-redefs [ontology/classify-task (fn [_ _]
                                                   (Thread/sleep induced-classify-delay-ms)
                                                   (deliver classify-ran true)
                                                   fast-structural)
                          ontology/classify-behaviors (fn [_ _] fast-behavioral)]
              (let [t0 (System/currentTimeMillis)
                    _ (tp/execute-repl-researcher-node (assoc ctx :event started))
                    elapsed (- (System/currentTimeMillis) t0)]
                (is (< elapsed handler-return-budget-ms)
                    (str "the dispatch thread was released in " elapsed "ms; a classify stall of "
                         induced-classify-delay-ms "ms must NOT be on it"))
                (is (deref classify-ran (+ induced-classify-delay-ms 10000) false)
                    "the slow classify still RAN (inside the future) — the work moved, it was not skipped")))))))))

;; =============================================================================
;; RED #7 — R-Inject surfaces a caution for a :timeout-fallback candidate
;; =============================================================================

(defn- render-prepend
  "Run the real R-Inject helper and return the resulting instruction."
  [payload]
  (:instruction
    (tp/apply-r05-classifier-context
      {:id (random-uuid) :name "n" :type :repl-researcher
       :instruction "ORIGINAL-INSTRUCTION"
       :context {:tree-id nil :r05-classifier payload}}
      {})))

(deftest r-inject-cautions-on-timeout-fallback-behavior
  (testing "a behavioral candidate stamped :timeout-fallback still carries a per-candidate reranker-fallback caution (parity with :colbert-fallback)"
    (let [payload-for (fn [source]
                        {:structural {:assigned-tree-id (random-uuid)
                                      :confidence 0.0
                                      :was-fresh-mint? false
                                      :reasoning "r"
                                      :top-candidates []
                                      :rerank-fallback? true}
                         :behavioral {:behaviors [{:behavior-id (random-uuid)
                                                   :confidence 0.0
                                                   :was-fresh-mint? false
                                                   :reasoning "r"
                                                   :rerank-source source}]
                                      :rerank-fallback? true}})
          colbert-text (render-prepend (payload-for :colbert-fallback))
          timeout-text (render-prepend (payload-for :timeout-fallback))]
      (is (str/includes? colbert-text "treat with caution")
          "Sanity (today's behavior): a :colbert-fallback candidate is annotated with a caution")
      (is (str/includes? timeout-text "treat with caution")
          "a :timeout-fallback candidate is annotated too — the new flavour must not silently drop the caution")
      (is (str/includes? (str/lower-case timeout-text) "timed out")
          "and the annotation names the reason (timeout) so the model/auditor can tell the two apart"))))
