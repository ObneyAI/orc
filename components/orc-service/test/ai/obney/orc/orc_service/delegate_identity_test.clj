(ns ai.obney.orc.orc-service.delegate-identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def delegate-child-tick-id #'tp/delegate-child-tick-id)
(def claim-delegate! #'tp/claim-delegate!)
(def release-delegate! #'tp/release-delegate!)

(defn recovered-output [_] {:output "recovered"})

(def delegate-counter (atom 0))
(def delegate-release (atom nil))

(defn increment-delegate-counter [_]
  {:counter (str (swap! delegate-counter inc))})

(defn wait-for-delegate-release [_]
  (deref @delegate-release 5000 nil)
  {:output "delivered"})

(defn fail-delegated-child [_]
  (throw (ex-info "delegated child failed" {:kind :test})))

(deftest terminal-child-failure-is-delivered-to-parent
  (h/with-async-test-context [ctx]
    (let [child (sheet/workflow "failing-delegate-child"
                  (sheet/blackboard {})
                  (sheet/code "fail"
                    :fn "ai.obney.orc.orc-service.delegate-identity-test/fail-delegated-child"))
          child-sheet-id (sheet/build-workflow! ctx child)
          parent (sheet/workflow "failing-delegate-parent"
                   (sheet/blackboard {})
                   (sheet/delegate "child" :target-sheet-id child-sheet-id))
          parent-sheet-id (sheet/build-workflow! ctx parent)
          result (sheet/execute ctx parent-sheet-id {} :timeout-ms 5000)]
      (is (= :failure (:status result)))
      (is (re-find #"delegated child failed" (:error result))))))

(deftest terminal-child-event-wakes-parent-without-process-local-observer
  (testing "durable child completion advances a parent after its observer is lost"
    (h/with-async-test-context [ctx]
      (reset! delegate-release (promise))
      (let [child (sheet/workflow "observer-loss-child"
                    (sheet/blackboard {:output :string})
                    (sheet/code "wait"
                      :fn "ai.obney.orc.orc-service.delegate-identity-test/wait-for-delegate-release"
                      :writes [:output]))
            child-sheet-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "observer-loss-parent"
                     (sheet/blackboard {:output :string})
                     (sheet/delegate "child"
                       :target-sheet-id child-sheet-id
                       :writes [:output]
                       :timeout-ms 4000))
            parent-sheet-id (sheet/build-workflow! ctx parent)
            parent-result (future (sheet/execute ctx parent-sheet-id {} :timeout-ms 5000))
            child-tick-id (loop [attempt 0]
                            (or (some #(when (and (= :sheet/tree-tick-started (:event/type %))
                                                 (:parent-tick-id %))
                                        (:tick-id %))
                                      (h/read-all-events ctx))
                                (when (< attempt 200)
                                  (Thread/sleep 10)
                                  (recur (inc attempt)))))]
        (is (uuid? child-tick-id))
        (runtime/deregister-completion! child-tick-id)
        (deliver @delegate-release true)
        (let [result (deref parent-result 3000 ::timeout)]
          (is (not= ::timeout result) "parent wake does not depend on the lost observer")
          (is (= :success (:status result)))
          (is (= "delivered" (get-in result [:outputs :output]))))))))

(deftest recovery-consumes-child-completion-before-parent-continuation
  (testing "durable child state resumes a parent when delivery was interrupted after child completion"
    (h/with-async-test-context [ctx]
      (reset! delegate-release (promise))
      (let [child (sheet/workflow "completion-gap-child"
                    (sheet/blackboard {:output :string})
                    (sheet/code "wait"
                      :fn "ai.obney.orc.orc-service.delegate-identity-test/wait-for-delegate-release"
                      :writes [:output]))
            child-sheet-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "completion-gap-parent"
                     (sheet/blackboard {:output :string})
                     (sheet/delegate "child"
                       :target-sheet-id child-sheet-id
                       :writes [:output]
                       :timeout-ms 4000))
            parent-sheet-id (sheet/build-workflow! ctx parent)
            complete! tp/complete-delegate-parent!
            parent-result (with-redefs [tp/complete-delegate-parent! (fn [& _] nil)]
                            (let [result (future
                                           (sheet/execute ctx parent-sheet-id {}
                                                          :timeout-ms 5000))
                                  child-tick-id
                                  (loop [attempt 0]
                                    (or (some #(when (and (= :sheet/tree-tick-started
                                                               (:event/type %))
                                                          (:parent-tick-id %))
                                                 (:tick-id %))
                                              (h/read-all-events ctx))
                                        (when (< attempt 200)
                                          (Thread/sleep 10)
                                          (recur (inc attempt)))))]
                              (is (uuid? child-tick-id))
                              (deliver @delegate-release true)
                              (let [terminal
                                    (loop [attempt 0]
                                      (or (runtime/durable-terminal-result ctx child-tick-id)
                                          (when (< attempt 200)
                                            (Thread/sleep 10)
                                            (recur (inc attempt)))))]
                                (is (= :success (:status terminal)))
                                (is (= ::waiting (deref result 50 ::waiting))
                                    "the simulated interruption leaves the parent uncontinued")
                                {:future result
                                 :child-tick-id child-tick-id
                                 :terminal terminal})))]
        (complete! ctx (:child-tick-id parent-result) (:terminal parent-result))
        (let [result (deref (:future parent-result) 3000 ::timeout)
              deliveries (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                       (= (:child-tick-id parent-result)
                                          (:completion-id %)))
                                 (h/read-all-events ctx))]
          (is (not= ::timeout result))
          (is (= :success (:status result)))
          (is (= "delivered" (get-in result [:outputs :output])))
          (is (= 1 (count deliveries))))))))

(deftest delegate-max-ticks-validates-positive-integers
  (is (= 100 (:max-ticks (sheet/delegate "child" :target-sheet-id (random-uuid)
                                          :max-ticks 100))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                        (sheet/delegate "child" :target-sheet-id (random-uuid)
                                        :max-ticks 0)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                        (sheet/delegate "child" :target-sheet-id (random-uuid)
                                        :max-ticks 1.5))))

(deftest configured-delegate-budget-survives-one-hundred-child-ticks
  (testing "a child may return Running 99 times and succeed on tick 100"
    (h/with-async-test-context [ctx]
      (reset! delegate-counter 0)
      (let [child (sheet/workflow "long-running-child"
                    (sheet/blackboard {:counter :string})
                    (sheet/fallback "poll"
                      (sheet/sequence "continue"
                        (sheet/condition "not-done"
                          :check {:key :counter :op :not-equals :value "99"})
                        (sheet/code "increment"
                          :fn "ai.obney.orc.orc-service.delegate-identity-test/increment-delegate-counter"
                          :writes [:counter])
                        (sheet/condition "running"
                          :check {:key :counter :op :equals :value "never" :on-fail :running}))
                      (sheet/condition "done"
                        :check {:key :counter :op :equals :value "99"})))
            child-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "budgeted-parent"
                     (sheet/blackboard {:counter :string})
                     (sheet/delegate "child"
                       :target-sheet-id child-id
                       :reads [:counter]
                       :writes [:counter]
                       :max-ticks 100
                       :timeout-ms 30000))
            parent-id (sheet/build-workflow! ctx parent)
            result (sheet/execute ctx parent-id {:counter "0"}
                                  :timeout-ms 30000 :max-ticks 100)]
        (is (= :success (:status result)))
        (is (= 99 @delegate-counter))
        (is (= "99" (get-in result [:outputs :counter])))
        (let [child-completion (->> (h/read-all-events ctx)
                                    (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                                  (= 100 (:consumed-ticks %))))
                                    first)]
          (is (= 100 (:configured-max-ticks child-completion)))
          (is (= :success (:terminal-reason child-completion)))
          (let [trace (loop [attempt 0]
                        (or (rm/get-trace ctx (:tick-id child-completion))
                            (when (< attempt 100)
                              (Thread/sleep 10)
                              (recur (inc attempt)))))]
            (is (= 100 (:configured-max-ticks trace)))
            (is (= 100 (:consumed-ticks trace)))
            (is (= :success (:terminal-reason trace))))
          (let [child-result (runtime/durable-terminal-result ctx (:tick-id child-completion))]
            (run! deref
                  (repeatedly 100
                              #(future
                                 (tp/complete-delegate-parent!
                                  ctx (:tick-id child-completion) child-result)))))
          (let [deliveries (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                         (= (:tick-id child-completion) (:completion-id %)))
                                   (h/read-all-events ctx))]
            (is (= 1 (count deliveries)) "duplicate wakes deliver child completion once")))))))

(deftest delegate-child-identity-is-stable-within-an-invocation
  (testing "one parent iteration resolves duplicate delegate delivery to one child tick"
    (let [parent (random-uuid)
          node (random-uuid)
          first-id (delegate-child-tick-id parent node {})
          second-id (delegate-child-tick-id
                     parent node
                     {::tp/tick-iteration 2})]
      (is (= first-id (delegate-child-tick-id parent node {}))
          "duplicate delivery in iteration one is stable")
      (is (not= first-id second-id)
          "a later durable parent iteration is a new logical invocation")
      (is (= second-id
             (delegate-child-tick-id parent node {::tp/tick-iteration 2}))
          "duplicate delivery in the later invocation is stable")
      (is (not= first-id
                (delegate-child-tick-id
                 parent node
                 {::tp/map-each-index 1
                  ::tp/map-each-parent node}))))))

(deftest concurrent-duplicate-delivery-has-one-observer
  (testing "100 concurrent deliveries allocate one blocking observer"
    (let [child-id (random-uuid)
          claims (doall (map deref
                             (repeatedly 100
                                         #(future (claim-delegate! child-id)))))]
      (try
        (is (= 1 (count (filter true? claims))))
        (finally
          (release-delegate! child-id))))))

(deftest terminal-child-reattaches-from-durable-state
  (testing "a restarted observer receives a child's terminal outputs without redispatch"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "terminal-child-reattach"
                       (sheet/blackboard {:output :string})
                       (sheet/code "finish"
                         :fn "ai.obney.orc.orc-service.delegate-identity-test/recovered-output"
                         :writes [:output]))
            sheet-id (sheet/build-workflow! ctx workflow)
            child-tick-id (random-uuid)
            first-result (sheet/execute ctx sheet-id {} :tick-id child-tick-id)
            started-before (count (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                (= child-tick-id (:tick-id %)))
                                          (h/read-all-events ctx)))
            started-at (System/nanoTime)
            recovered (runtime/execute ctx sheet-id {} :tick-id child-tick-id
                                       :return-references? true)
            elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)
            started-after (count (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                               (= child-tick-id (:tick-id %)))
                                         (h/read-all-events ctx)))]
        (is (= :success (:status first-result)))
        (is (= :success (:status recovered)))
        (is (= "recovered" (get-in recovered [:outputs :output])))
        (is (= 1 started-before started-after))
        (is (< elapsed-ms 1000.0) "recovery must not wait for the old promise timeout")))))
