(ns ai.obney.orc.orc-service.delegate-identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def delegate-child-tick-id #'tp/delegate-child-tick-id)
(def claim-delegate! #'tp/claim-delegate!)
(def release-delegate! #'tp/release-delegate!)

(defn recovered-output [_] {:output "recovered"})

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
