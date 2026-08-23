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

(defn increment-delegate-counter [_]
  {:counter (str (swap! delegate-counter inc))})

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
            (is (= :success (:terminal-reason trace)))))))))

(deftest delegate-child-identity-is-stable-across-reticks-and-redelivery
  (testing "one logical delegate frontier always resolves to one child tick"
    (let [parent (random-uuid)
          node (random-uuid)
          first-id (delegate-child-tick-id parent node {})]
      (is (= first-id (delegate-child-tick-id parent node {})))
      (is (= first-id
             (delegate-child-tick-id
              parent node
              {::tp/tick-iteration 100})))
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
