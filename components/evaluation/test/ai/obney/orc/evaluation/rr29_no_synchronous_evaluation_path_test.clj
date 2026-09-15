(ns ai.obney.orc.evaluation.rr29-no-synchronous-evaluation-path-test
  "RR-29 — the caller-less synchronous evaluation API (single-trace and
   batch evaluators, the multi-judge aggregate evaluator, plus their
   command/event schema declarations) is deleted. One evaluation path
   remains: the event-driven judge runtime.

   See docs/issues/rr-durable/RR-29-delete-the-dead-synchronous-evaluation-path.md"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.grain.schema-util.interface :as schema-util]))

(deftest no-synchronous-evaluate-vars-test
  (testing "the interface namespace resolves neither evaluate-trace nor evaluate-traces"
    (is (nil? (ns-resolve 'ai.obney.orc.evaluation.interface 'evaluate-trace)))
    (is (nil? (ns-resolve 'ai.obney.orc.evaluation.interface 'evaluate-traces)))))

(deftest no-synchronous-evaluate-all-var-test
  (testing "the interface namespace resolves neither evaluate-all (retired grill D3 — the spec's evaluate_all)"
    (is (nil? (ns-resolve 'ai.obney.orc.evaluation.interface 'evaluate-all)))))

(deftest no-synchronous-evaluate-schemas-test
  (testing "the schema registry has no entries for the deleted command/event types"
    (is (nil? (get @schema-util/registry* :evaluation/evaluate-batch)))
    (is (nil? (get @schema-util/registry* :evaluation/batch-completed)))
    (is (nil? (get @schema-util/registry* :evaluation/evaluate-trace)))
    (is (nil? (get @schema-util/registry* :evaluation/trace-evaluated)))))
