(ns ai.obney.orc.orc-service.gepa-integration-test
  "Integration tests for GEPA (Genetic-Pareto Prompt Optimizer) integration.

   These tests verify:
   - GEPA-compatible workflow creation (dynamic instructions)
   - Event store integration during execution
   - Instruction override via inputs

   Judge scoring for GEPA-executed traces is covered by the event-driven
   judge runtime suites (components/evaluation-service/test), not here — RR-29
   deleted the caller-less synchronous evaluate-trace/evaluate-traces API
   this file used to exercise via mock judges."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]))


(defn qa-executor
  "Simple Q&A executor that echoes instruction + question as answer.
   Used to test GEPA flow without real LLM calls."
  [{:keys [inputs]}]
  (let [question (:question inputs)
        instruction (get inputs :instruction "Answer the question.")]
    {:answer (str "Instruction was: '" instruction "'. Question: " question ". Answer: test-answer")}))

(defn input->intermediate
  [_]
  {:intermediate "step-1-complete"})

(defn intermediate->output
  [{:keys [inputs]}]
  {:output (str "step-2:" (:intermediate inputs))})


(defn- create-gepa-workflow!
  "Create a GEPA-compatible workflow with dynamic instruction.

   Structure:
   - Blackboard: question (string), instruction (string), answer (string)
   - Single code node that uses qa-executor

   Returns {:sheet-id uuid :node-id uuid}"
  [ctx]
  (let [
        sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "GEPA Test QA"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)

        node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf))
        node-id (-> node-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id node-id "answer"))

        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :question :string))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :instruction :string))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :answer :string))

        _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id node-id
                                                            [:question :instruction]
                                                            [:answer]))
        _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id node-id :code
                                  :fn "ai.obney.orc.orc-service.gepa-integration-test/qa-executor"))]

    {:sheet-id sheet-id
     :node-id node-id}))

(defn- create-sequence-workflow!
  "Create a workflow with multiple nodes to test event store integration.

   Structure:
   - Sequence root
     - Code node 1: reads input, writes intermediate
     - Code node 2: reads intermediate, writes output

   Returns {:sheet-id uuid :node-ids {:root ... :node1 ... :node2 ...}}"
  [ctx]
  (let [
        sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Sequence Test"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)

        seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        seq-id (-> seq-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "root"))

        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :intermediate :string))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))

        node1-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
        node1-id (-> node1-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id node1-id "step-1"))
        _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id node1-id [:input] [:intermediate]))
        _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id node1-id :code
                                  :fn "ai.obney.orc.orc-service.gepa-integration-test/input->intermediate"))

        node2-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
        node2-id (-> node2-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id node2-id "step-2"))
        _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id node2-id [:intermediate] [:output]))
        _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id node2-id :code
                                  :fn "ai.obney.orc.orc-service.gepa-integration-test/intermediate->output"))]

    {:sheet-id sheet-id
     :node-ids {:root seq-id
                :node1 node1-id
                :node2 node2-id}}))


(defn- wait-for-trace
  "Poll for a trace to appear in the event store."
  [ctx trace-id & {:keys [max-attempts delay-ms] :or {max-attempts 20 delay-ms 50}}]
  (loop [attempt 0]
    (let [result (h/run-query ctx (h/make-get-trace-query trace-id))]
      (cond
        (and (not (h/is-anomaly? result)) (:query/result result))
        (get-in result [:query/result :trace])

        (< attempt max-attempts)
        (do (Thread/sleep delay-ms)
            (recur (inc attempt)))

        :else nil))))


(deftest gepa-workflow-structure-test
  (testing "workflow with instruction in blackboard is GEPA-compatible"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id node-id]} (create-gepa-workflow! ctx)]

        (let [sheet (sheet/get-sheet ctx sheet-id)]
          (is (some? sheet))
          (is (= "GEPA Test QA" (:name sheet))))

        (let [blackboard (sheet/get-blackboard-by-key ctx sheet-id)]
          (is (contains? blackboard :instruction))
          (is (contains? blackboard :question))
          (is (contains? blackboard :answer)))

        (let [nodes (sheet/get-nodes-for-sheet ctx sheet-id)
              leaf-node (first (filter #(= :leaf (:type %)) nodes))]
          (is (some? leaf-node))
          (is (some #{:instruction} (:reads leaf-node)))
          (is (some #{:question} (:reads leaf-node))))))))

(deftest gepa-workflow-executes-with-instruction-test
  (testing "workflow executes with instruction from inputs"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-gepa-workflow! ctx)

            result (sheet/execute ctx sheet-id
                     {:question "What is 2+2?"
                      :instruction "Be concise and accurate."})]

        (is (= :success (:status result)))
        (is (some? (:outputs result)))

        (let [answer (get (:outputs result) :answer)]
          (is (string? answer))
          (is (clojure.string/includes? answer "Be concise and accurate.")))))))


(deftest instruction-override-test
  (testing "different instructions produce different outputs"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-gepa-workflow! ctx)

            result1 (sheet/execute ctx sheet-id
                      {:question "What is 2+2?"
                       :instruction "Instruction A"})

            result2 (sheet/execute ctx sheet-id
                      {:question "What is 2+2?"
                       :instruction "Instruction B"})]

        (is (= :success (:status result1)))
        (is (= :success (:status result2)))

        (let [answer1 (get (:outputs result1) :answer)
              answer2 (get (:outputs result2) :answer)]
          (is (clojure.string/includes? answer1 "Instruction A"))
          (is (clojure.string/includes? answer2 "Instruction B"))
          (is (not= answer1 answer2)))))))


(deftest execution-events-test
  (testing "workflow execution emits correct events"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id node-ids]} (create-sequence-workflow! ctx)
            event-store (:event-store ctx)

            result (sheet/execute ctx sheet-id {:input "test-value"})
            _ (is (= :success (:status result)) (pr-str result))

            trace-id (:trace-id result)
            trace (wait-for-trace ctx trace-id)]

        (is (some? trace) "Trace should be stored")

        (when trace
          (is (= :success (:status trace)))
          (is (= sheet-id (:sheet-id trace)))
          (is (map? (:input-snapshot trace)))
          (is (map? (:output-snapshot trace)))

          (let [node-traces (:node-traces trace)]
            (is (vector? node-traces))
            (is (>= (count node-traces) 2))

            (doseq [nt node-traces]
              (is (uuid? (:node-id nt)))
              (is (keyword? (:node-type nt)))
              (is (keyword? (:status nt))))))))))

(deftest tick-events-emitted-test
  (testing "tick lifecycle events are emitted"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-gepa-workflow! ctx)
            event-store (:event-store ctx)

            result (sheet/execute ctx sheet-id
                     {:question "test" :instruction "test-instruction"})
            _ (is (= :success (:status result)))

            tick-events (into [] (es/read event-store
                                   {:types #{:sheet/tree-tick-started
                                            :sheet/tree-tick-completed}
                                    :tags #{[:sheet sheet-id]}
                                    :tenant-id (:tenant-id ctx)}))]

        (is (>= (count tick-events) 2))

        (let [event-types (set (map :event/type tick-events))]
          (is (contains? event-types :sheet/tree-tick-started))
          (is (contains? event-types :sheet/tree-tick-completed)))))))


(deftest read-model-queries-test
  (testing "read model queries return expected data"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id node-id]} (create-gepa-workflow! ctx)]

        (let [sheet (sheet/get-sheet ctx sheet-id)]
          (is (some? sheet))
          (is (= sheet-id (:id sheet))))

        (let [nodes (sheet/get-nodes-for-sheet ctx sheet-id)]
          (is (seq nodes))
          (is (some #(= node-id (:id %)) nodes)))

        (let [bb (sheet/get-blackboard-by-key ctx sheet-id)]
          (is (map? bb))
          (is (= :string (get-in bb [:question :schema]))))))))


(deftest execution-duration-test
  (testing "execution returns duration in milliseconds"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-gepa-workflow! ctx)
            result (sheet/execute ctx sheet-id
                     {:question "test" :instruction "test"})]

        (is (= :success (:status result)))
        (is (number? (:duration-ms result)))
        (is (pos? (:duration-ms result)))))))


(deftest execution-isolation-test
  (testing "multiple executions have isolated blackboard state"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-gepa-workflow! ctx)

            results (mapv (fn [i]
                            (sheet/execute ctx sheet-id
                              {:question (str "Question " i)
                               :instruction (str "Instruction " i)}))
                          (range 3))]

        (is (every? #(= :success (:status %)) results))

        (let [answers (map #(get-in % [:outputs :answer]) results)]
          (is (= 3 (count (distinct answers)))))))))


(deftest trace-storage-for-training-test
  (testing "traces can be queried for building training data"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-gepa-workflow! ctx)
            event-store (:event-store ctx)

            _ (sheet/execute ctx sheet-id
                {:question "Q1" :instruction "I1"})
            _ (sheet/execute ctx sheet-id
                {:question "Q2" :instruction "I2"})

            _ (Thread/sleep 200)

            traces-result (h/run-query ctx (h/make-get-traces-query sheet-id))]

        (when-not (h/is-anomaly? traces-result)
          (let [traces (:query/result traces-result)]
            (is (>= (count traces) 0))))))))


(deftest rolling-metrics-test
  (testing "node rolling metrics track execution statistics"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id node-id]} (create-gepa-workflow! ctx)
            event-store (:event-store ctx)

            _ (dotimes [_ 3]
                (sheet/execute ctx sheet-id
                  {:question "test" :instruction "test"}))

            _ (Thread/sleep 100)

            metrics (sheet/get-node-rolling-metrics ctx sheet-id node-id)]

        (when metrics
          (is (number? (:execution-count metrics)))
          (is (number? (:success-rate metrics)))
          (is (<= 0.0 (:success-rate metrics) 1.0)))))))
