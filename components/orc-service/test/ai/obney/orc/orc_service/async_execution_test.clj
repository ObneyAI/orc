(ns ai.obney.orc.orc-service.async-execution-test
  "Tests for async execution path through todo processors."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test Functions for Code Executor
;; =============================================================================

(defn identity-fn
  "Returns the input as output."
  [{:keys [inputs]}]
  {:output (get inputs :input)})

(defn transform-fn
  "Transforms input by uppercasing."
  [{:keys [inputs]}]
  {:output (clojure.string/upper-case (get inputs :input))})

(defn multi-output-fn
  "Returns multiple outputs."
  [{:keys [inputs]}]
  (let [x (get inputs :x)]
    {:doubled (* x 2)
     :squared (* x x)}))

;; =============================================================================
;; Helper: build sheet and dispatch async execution
;; =============================================================================

(defn- setup-simple-sheet!
  "Create a sheet with a sequence containing one code leaf node.
   Returns {:sheet-id ... :leaf-id ...}"
  [ctx & {:keys [name fn-sym reads writes]
          :or {name "Async Test Sheet"
               fn-sym "ai.obney.orc.orc-service.async-execution-test/identity-fn"
               reads [:input]
               writes [:output]}}]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name name))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    ;; Declare blackboard keys
    (doseq [k (concat reads writes)]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    ;; Create sequence with leaf
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
          leaf-id (-> leaf-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code :fn fn-sym))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id (vec reads) (vec writes)))
      {:sheet-id sheet-id :leaf-id leaf-id})))

(defn- dispatch-async-execute!
  "Dispatch a tick-tree command with inputs through the command pipeline.
   Returns {:tick-id ... :promise ...}"
  [ctx sheet-id inputs & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        cmd-result (cp/process-command
                     (assoc ctx :command
                            {:command/id (random-uuid)
                             :command/timestamp (time/now)
                             :command/name :sheet/tick-tree
                             :sheet-id sheet-id
                             :tick-id tick-id
                             :inputs inputs
                             :options {:timeout-ms timeout-ms}}))]
    {:tick-id tick-id
     :promise p
     :cmd-result cmd-result}))

(defn- wait-for-completion
  "Wait for async execution to complete. Returns the result or :timeout."
  [p & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [result (deref p timeout-ms ::timeout)]
    (if (= result ::timeout) :timeout result)))

(deftest child-tick-start-carries-parent-tag
  (testing "tick-tree indexes durable lineage for bounded result traces"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet! ctx)
            tick-id (random-uuid)
            parent-tick-id (random-uuid)
            result (cp/process-command
                    (assoc ctx :command
                           {:command/id (random-uuid)
                            :command/timestamp (time/now)
                            :command/name :sheet/tick-tree
                            :sheet-id sheet-id
                            :tick-id tick-id
                            :parent-tick-id parent-tick-id
                            :inputs {:input "hello"}}))
            event (first (:command-result/events result))]
        (is (= parent-tick-id (:parent-tick-id event)))
        (is (contains? (:event/tags event) [:parent-tick parent-tick-id]))))))

(deftest duplicate-tick-command-is-idempotent
  (testing "a stable tick identity can be dispatched repeatedly without another execution start"
    (h/with-async-test-context [ctx]
      (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name "duplicate-tick-command"))
                         :command-result/events first :sheet-id)
            _ (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
            tick-id (random-uuid)
            command (assoc (h/make-tick-tree-command sheet-id :tick-id tick-id) :inputs {})]
        (h/run-and-apply! ctx command)
        (h/run-and-apply! ctx command)
        (is (= 1 (count (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                      (= tick-id (:tick-id %)))
                                (h/read-all-events ctx)))))))))

;; =============================================================================
;; Async Execution Tests
;; =============================================================================

(deftest async-simple-execution-test
  (testing "async execution through todo processors returns correct output"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet! ctx)
            {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:input "hello"})
            result (wait-for-completion promise)]
        (is (not= :timeout result) "Execution should complete within timeout")
        (is (= :success (:status result)))
        (is (= "hello" (get (:outputs result) :output)))))))

(deftest async-transform-execution-test
  (testing "async execution with transform function"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet!
                                 ctx
                                 :fn-sym "ai.obney.orc.orc-service.async-execution-test/transform-fn")
            {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:input "hello"})
            result (wait-for-completion promise)]
        (is (= :success (:status result)))
        (is (= "HELLO" (get (:outputs result) :output)))))))

(deftest async-execution-isolation-test
  (testing "async execution doesn't mutate sheet's stored blackboard"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet! ctx)]
        ;; Set initial value on sheet's persistent blackboard
        (h/run-and-apply! ctx (h/make-set-key-value-command sheet-id :input "original"))

        ;; Execute with different input
        (let [{:keys [promise]} (dispatch-async-execute! ctx sheet-id {:input "overridden"})
              result (wait-for-completion promise)]
          (is (= :success (:status result)))
          (is (= "overridden" (get (:outputs result) :output))))

        ;; Verify original blackboard unchanged
        (let [bb (rm/get-blackboard-by-key ctx sheet-id)]
          (is (= "original" (get-in bb [:input :value]))))))))

(deftest async-concurrent-executions-isolation-test
  (testing "two concurrent async executions of the same sheet are isolated"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet! ctx)
            ;; Dispatch two executions with different inputs
            exec1 (dispatch-async-execute! ctx sheet-id {:input "first"})
            exec2 (dispatch-async-execute! ctx sheet-id {:input "second"})
            result1 (wait-for-completion (:promise exec1))
            result2 (wait-for-completion (:promise exec2))]
        (is (= :success (:status result1)))
        (is (= :success (:status result2)))
        (is (= "first" (get (:outputs result1) :output)))
        (is (= "second" (get (:outputs result2) :output)))))))

(deftest async-tick-scoped-blackboard-test
  (testing "execution-value-written events are scoped to tick"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet! ctx)
            {:keys [tick-id promise]} (dispatch-async-execute! ctx sheet-id {:input "test"})
            result (wait-for-completion promise)]
        (is (= :success (:status result)))
        ;; The tick-scoped blackboard holds METADATA — key, schema, version,
        ;; size profile, and a :source-event-id for written keys. Values are
        ;; resolved on demand from the write log; see core.value-log and
        ;; tick_context_equivalence_test for the round-trip proof.
        (let [tick-bb (rm/get-tick-blackboard ctx tick-id)]
          (is (some? tick-bb) "Tick should have a scoped blackboard")
          (is (contains? tick-bb :input) "the seeded key is present")
          (is (not (contains? (get tick-bb :input) :value))
              "the cache must not carry values")
          (is (some? (get-in tick-bb [:input :profile]))
              "a seeded key records the shape of its value")
          ;; And the value is still recoverable.
          (is (= "test" (get (value-log/resolve-values
                              (:event-store ctx) (:tenant-id ctx) tick-id
                              tick-bb [:input])
                             :input))))))))
