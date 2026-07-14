(ns ai.obney.orc.orc-service.async-execution-test
  "Tests for async execution path through todo processors."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
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

(defn slow-fn
  "Sleeps well past the caller's deref-timeout — the zombie-tick scenario."
  [{:keys [inputs]}]
  (Thread/sleep 3000)
  {:output (get inputs :input)})

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

(deftest deref-timeout-cancels-the-abandoned-tick-test
  (testing "MS-2 — when runtime/execute's deref times out, the still-running tick
            is best-effort CANCELLED (cascading to known child ticks), not
            abandoned. Root cause (2026-07-11 all-5-sources forensic): abandoned
            zombie reconcilers kept running past their delegate's deref-timeout
            and starved the 4-connection sqlite pool (Hikari total=4, active=4)."
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet!
                                ctx
                                :fn-sym "ai.obney.orc.orc-service.async-execution-test/slow-fn")
            tick-id (random-uuid)
            result (runtime/execute ctx sheet-id {:input "x"}
                                    :timeout-ms 500 :tick-id tick-id)]
        (is (= :timeout (:status result)) "the caller still gets the honest :timeout")
        ;; the abandoned tick received the cancellation marker: the
        ;; :sheet/tick-cancelled event exists for THIS tick (the same assertion
        ;; style as phase2-timeout-dispatches-cancel-tick / streaming cancel!).
        (let [deadline (+ (System/currentTimeMillis) 3000)
              cancelled-ids (loop []
                              (let [ids (->> (es/read (:event-store ctx)
                                                      {:types #{:sheet/tick-cancelled}
                                                       :tenant-id (:tenant-id ctx)})
                                             (into [])
                                             (map :tick-id)
                                             set)]
                                (if (or (contains? ids tick-id)
                                        (> (System/currentTimeMillis) deadline))
                                  ids
                                  (do (Thread/sleep 100) (recur)))))]
          (is (contains? cancelled-ids tick-id)
              (str "expected a :sheet/tick-cancelled event for the timed-out tick "
                   tick-id ", got " cancelled-ids)))
        (is (rm/is-tick-cancelled? ctx tick-id)
            "the tick read model shows the abandoned tick as :cancelled")))))

(deftest async-tick-scoped-blackboard-test
  (testing "execution-value-written events are scoped to tick"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-simple-sheet! ctx)
            {:keys [tick-id promise]} (dispatch-async-execute! ctx sheet-id {:input "test"})
            result (wait-for-completion promise)]
        (is (= :success (:status result)))
        ;; Verify tick-scoped blackboard has the values
        (let [tick-bb (rm/get-tick-blackboard ctx tick-id)]
          (is (some? tick-bb) "Tick should have a scoped blackboard")
          (is (= "test" (get-in tick-bb [:input :value]))))))))
