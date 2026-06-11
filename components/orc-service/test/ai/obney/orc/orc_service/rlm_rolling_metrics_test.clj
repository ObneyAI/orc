(ns ai.obney.orc.orc-service.rlm-rolling-metrics-test
  "Tests for C-2a-2's cross-sheet rolling-metrics extensions:

     - :sheet/rlm-tree-execution-completed events tagged with
       :tree-fingerprint + :status + :duration-ms
     - :sheet/node-execution-completed events tagged with :node-type
     - Two new read-models: per-node-type and per-tree-fingerprint
       rolling-metrics aggregators
     - Existing per-(sheet, node-id) aggregator unchanged (regression)"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers (mirrors recursive_rlm_test.clj pattern)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2a2-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
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
;; RED #5 — :sheet/rlm-tree-execution-completed carries :tree-fingerprint :status :duration-ms
;; =============================================================================

(deftest tree-execution-completion-command-emits-event-with-new-fields
  (testing "Dispatching :sheet/record-rlm-tree-execution-completion with :tree-fingerprint :status :duration-ms emits an event carrying all three"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            fp "stable-fingerprint-abc123"
            cmd {:command/name :sheet/record-rlm-tree-execution-completion
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :tick-id tick-id
                 :trajectory []
                 :total-usage {:total-tokens 100}
                 :tree-fingerprint fp
                 :status :success
                 :duration-ms 12345}
            result (cp/process-command (assoc ctx :command cmd))
            event (first (:command-result/events result))]
        (is (= :sheet/rlm-tree-execution-completed (:event/type event))
            "Event type is the tree-completion bookend")
        (is (= fp (:tree-fingerprint event))
            "Event carries :tree-fingerprint from the command")
        (is (= :success (:status event))
            "Event carries :status from the command")
        (is (= 12345 (:duration-ms event))
            "Event carries :duration-ms from the command")))))

;; =============================================================================
;; RED #6 — :sheet/node-execution-completed carries :node-type
;; =============================================================================

(deftest node-execution-completion-command-emits-event-with-node-type
  (testing "Dispatching :sheet/complete-node-execution with :node-type emits an event carrying it"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            cmd {:command/name :sheet/complete-node-execution
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :tick-id tick-id
                 :node-id node-id
                 :node-type :llm
                 :status :success
                 :writes {}
                 :duration-ms 250}
            result (cp/process-command (assoc ctx :command cmd))
            completion-event (->> (:command-result/events result)
                                   (filter #(= :sheet/node-execution-completed (:event/type %)))
                                   first)]
        (is (some? completion-event)
            "A :sheet/node-execution-completed event should be emitted")
        (is (= :llm (:node-type completion-event))
            "Event carries :node-type from the command")))))

;; =============================================================================
;; RED #7 — per-node-type rolling-metrics aggregator
;; =============================================================================
;;
;; Two node-execution-completed events from DIFFERENT sheets but the SAME
;; :node-type should aggregate into a single per-node-type metric — the
;; aggregator is cross-sheet by design.

(defn- complete-node! [ctx sheet-id tick-id node-id node-type status duration-ms]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id node-id
            :node-type node-type
            :status status
            :writes {}
            :duration-ms duration-ms})))

(deftest per-node-type-aggregator-sums-across-sheets
  (testing "get-node-type-metrics returns sums across distinct sheets when node-type matches"
    (with-test-ctx [ctx]
      (let [sheet-a (random-uuid) sheet-b (random-uuid)
            tick-a (random-uuid) tick-b (random-uuid)
            node-a (random-uuid) node-b (random-uuid)]
        ;; Two :llm completions in different sheets, both successful
        (complete-node! ctx sheet-a tick-a node-a :llm :success 100)
        (complete-node! ctx sheet-b tick-b node-b :llm :success 150)
        ;; A :code completion that should NOT count toward :llm aggregation
        (complete-node! ctx sheet-a tick-a (random-uuid) :code :success 50)
        (Thread/sleep 100)
        (let [llm-metrics (orc/get-node-type-metrics ctx :llm)]
          (is (some? llm-metrics)
              "Per-node-type metrics should be present for :llm")
          (is (= 2 (:success-count llm-metrics))
              ":llm success-count sums across the two sheets")
          (is (= 0 (:failure-count llm-metrics))
              ":llm failure-count remains zero")
          (is (= 250 (:total-duration llm-metrics))
              ":llm total-duration sums the durations from both sheets"))))))

;; =============================================================================
;; RED #8 — per-tree-fingerprint rolling-metrics aggregator
;; =============================================================================
;;
;; Two :sheet/rlm-tree-execution-completed events with the SAME
;; :tree-fingerprint (whether from the same sheet or different sheets)
;; should aggregate into a single per-tree-fingerprint metric. Events with
;; a DIFFERENT fingerprint should not contaminate the count.

(defn- complete-tree! [ctx sheet-id tick-id tree-fingerprint status duration-ms]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/record-rlm-tree-execution-completion
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :trajectory []
            :total-usage {:total-tokens 0}
            :tree-fingerprint tree-fingerprint
            :status status
            :duration-ms duration-ms})))

(deftest per-tree-fingerprint-aggregator-sums-across-ticks
  (testing "get-tree-fingerprint-metrics returns sums across distinct ticks/sheets when fingerprint matches"
    (with-test-ctx [ctx]
      (let [sheet-a (random-uuid) sheet-b (random-uuid)
            fp-shared "fp-shared-shape"
            fp-other  "fp-different-shape"]
        ;; Two trees with the SAME fingerprint, in different sheets
        (complete-tree! ctx sheet-a (random-uuid) fp-shared :success 1000)
        (complete-tree! ctx sheet-b (random-uuid) fp-shared :failure 1500)
        ;; A tree with a different fingerprint that should NOT count toward fp-shared
        (complete-tree! ctx sheet-a (random-uuid) fp-other :success 500)
        (Thread/sleep 100)
        (let [shared-metrics (orc/get-tree-fingerprint-metrics ctx fp-shared)]
          (is (some? shared-metrics)
              "Per-tree-fingerprint metrics should be present for fp-shared")
          (is (= 1 (:success-count shared-metrics))
              "fp-shared success-count counts the one success across sheets")
          (is (= 1 (:failure-count shared-metrics))
              "fp-shared failure-count counts the one failure across sheets")
          (is (= 2500 (:total-duration shared-metrics))
              "fp-shared total-duration sums all matching events' durations"))))))

;; =============================================================================
;; RED #9 — legacy per-(sheet, node-id) aggregator unchanged
;; =============================================================================
;;
;; Regression gate. The existing get-node-rolling-metrics API must keep
;; working for callers that pre-date C-2a-2 — same shape, same semantics.

(deftest legacy-per-sheet-node-id-aggregator-still-works
  (testing "Existing get-node-rolling-metrics returns sheet-scoped metrics independent of the new cross-sheet aggregators"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)]
        ;; Three completions on the same (sheet, node)
        (complete-node! ctx sheet-id tick-id node-id :llm :success 100)
        (complete-node! ctx sheet-id tick-id node-id :llm :success 200)
        (complete-node! ctx sheet-id tick-id node-id :llm :failure 300)
        (Thread/sleep 100)
        (let [legacy (orc/get-node-rolling-metrics ctx sheet-id node-id)]
          (is (some? legacy)
              "Legacy per-(sheet, node-id) metrics should be present")
          (is (= sheet-id (:sheet-id legacy))
              "Legacy result keeps the :sheet-id stamp")
          (is (= node-id (:node-id legacy))
              "Legacy result keeps the :node-id stamp")
          (is (= 3 (:execution-count legacy))
              "Three executions recorded under the (sheet, node) pair")
          (is (< (Math/abs (- (/ 2.0 3) (:success-rate legacy))) 1e-9)
              "Two of three succeeded — success-rate ≈ 2/3")
          (is (< (Math/abs (- (/ 1.0 3) (:failure-rate legacy))) 1e-9)
              "One of three failed — failure-rate ≈ 1/3"))))))
