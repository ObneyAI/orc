(ns ai.obney.orc.orc-service.runtime-test
  "Tests for the execute API (dispatch + wait via async pipeline)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]))

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
  "Simulates slow operation."
  [{:keys [inputs]}]
  (Thread/sleep 2000)
  {:output "done"})

;; =============================================================================
;; Execute API Tests
;; =============================================================================

(deftest execute-simple-sequence-test
  (testing "executes a sequence of code nodes"
    (h/with-async-test-context [ctx]
      ;; Create sheet
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Simple Sequence"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        ;; Declare blackboard
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))

        ;; Create sequence with one leaf
        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.runtime-test/transform-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:input] [:output]))

          ;; Execute
          (let [result (sheet/execute ctx sheet-id {:input "hello"})]
            (is (= :success (:status result)))
            (is (= "HELLO" (get-in result [:outputs :output])))))))))

(deftest execute-isolated-blackboard-test
  (testing "execution doesn't mutate sheet's stored blackboard"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Isolation Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        ;; Declare blackboard with initial value
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))
        (h/run-and-apply! ctx (h/make-set-key-value-command sheet-id :input "original"))

        ;; Create leaf
        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.runtime-test/identity-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:input] [:output]))

          ;; Execute with different input
          (let [result (sheet/execute ctx sheet-id {:input "overridden"})]
            (is (= :success (:status result)))
            (is (= "overridden" (get-in result [:outputs :output]))))

          ;; Verify original blackboard unchanged
          (let [bb (sheet/get-blackboard-by-key ctx sheet-id)]
            (is (= "original" (get-in bb [:input :value])))))))))

(deftest execute-timeout-test
  (testing "returns timeout status when execution exceeds limit"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Timeout Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.runtime-test/slow-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] [:output]))

          ;; Execute with very short timeout
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 100)]
            (is (= :timeout (:status result)))
            (is (some? (:error result)))))))))

(deftest execute-multi-output-test
  (testing "captures multiple outputs from a node"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Multi Output"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :x :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :doubled :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :squared :int))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.runtime-test/multi-output-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:x] [:doubled :squared]))

          (let [result (sheet/execute ctx sheet-id {:x 5})]
            (is (= :success (:status result)))
            (is (= 10 (get-in result [:outputs :doubled])))
            (is (= 25 (get-in result [:outputs :squared])))))))))

;; =============================================================================
;; Event Pipeline Tests
;; =============================================================================

(defn- read-events-by-type
  "Read events from event store filtering by type."
  [event-store type]
  (->> (es/read event-store {:types #{type} :tenant-id #uuid "00000000-0000-0000-0000-000000000000"})
       (filterv #(not= :grain/tx (:event/type %)))))

(deftest execute-emits-tick-started-event-test
  (testing "execute emits :sheet/tree-tick-started event through command pipeline"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Event Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.runtime-test/identity-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:input] [:output]))

          (sheet/execute ctx sheet-id {:input "test"})

          (let [tick-started-events (read-events-by-type (:event-store ctx) :sheet/tree-tick-started)]
            (is (= 1 (count tick-started-events)))
            (is (= sheet-id (:sheet-id (first tick-started-events))))))))))

(deftest execute-accepts-caller-supplied-tick-id-test
  (testing "execute uses caller supplied tick id for progress correlation"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Tick Correlation"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            tick-id (random-uuid)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.runtime-test/identity-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:input] [:output]))

          (let [result (sheet/execute ctx sheet-id {:input "test"} :tick-id tick-id)
                tick-started-events (read-events-by-type (:event-store ctx)
                                                         :sheet/tree-tick-started)]
            (is (= :success (:status result)))
            (is (= tick-id (:trace-id result)))
            (is (= [tick-id] (mapv :tick-id tick-started-events)))))))))

(deftest execute-emits-tick-completed-event-test
  (testing "execute emits :sheet/tree-tick-completed event with correct status"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Completion Event Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.runtime-test/identity-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:input] [:output]))

          (sheet/execute ctx sheet-id {:input "test"})

          (let [tick-completed-events (read-events-by-type (:event-store ctx) :sheet/tree-tick-completed)]
            (is (= 1 (count tick-completed-events)))
            (is (= sheet-id (:sheet-id (first tick-completed-events))))
            (is (= :success (:root-status (first tick-completed-events))))))))))
