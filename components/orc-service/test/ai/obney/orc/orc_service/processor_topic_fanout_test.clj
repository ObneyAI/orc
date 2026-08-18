(ns ai.obney.orc.orc-service.processor-topic-fanout-test
  "Guards the sheet-execution processor topology against checkpoint
   amplification (docs/issues/005-sheet-execution-checkpoint-amplification.md).

   Every processor registered on a topic durably advances its own checkpoint
   cursor per event, in its own fsync'd commit. Sequential causal chains poll
   one event at a time, so each extra subscriber on the node-execution topics
   adds a fixed fsync per node transition. These tests fail when a new
   :sheet processor is registered on those topics so the cost is a deliberate
   decision, not an accident."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))

(defn- sheet-processors-on
  [topic]
  (into #{}
        (keep (fn [[proc-name {:keys [topics]}]]
                (when (and (= "sheet" (namespace proc-name))
                           (contains? (set topics) topic))
                  proc-name)))
        @tp/processor-registry*))

(deftest node-execution-started-has-a-single-sheet-processor
  (testing "all node execution dispatches through one consolidated processor"
    (is (= #{:sheet/execute-leaf-node}
           (sheet-processors-on :sheet/node-execution-started))
        "the dispatcher must keep the :sheet/execute-leaf-node name — a fresh
         processor name has no checkpoint and replays the topic from the start
         of the stream")))

(deftest node-execution-completed-has-three-sheet-processors
  (testing "completion handling stays at three cursors"
    (is (= #{:sheet/handle-child-completion
             :sheet/handle-map-each-child-completion
             :sheet/complete-tree-tick}
           (sheet-processors-on :sheet/node-execution-completed)))))
