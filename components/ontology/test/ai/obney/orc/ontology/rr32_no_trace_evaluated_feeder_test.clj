(ns ai.obney.orc.ontology.rr32-no-trace-evaluated-feeder-test
  "RR-32 — the automatic tree-profile feeder is retired: no processor
   subscribes to :evaluation/trace-evaluated (no producer has ever emitted
   it), the discovery namespace and the run-pattern-discovery command built
   on that dead event are gone, and their interface exports do not resolve.

   See docs/issues/rr-durable/RR-32-retire-the-automatic-tree-profile-feeder.md"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.interface]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]))

(deftest no-trace-evaluated-processor-test
  (testing "no registered processor declares topic :evaluation/trace-evaluated"
    (is (empty? (filter (fn [[_ config]]
                           (contains? (:topics config) :evaluation/trace-evaluated))
                         @tp/processor-registry*)))))

(deftest deleted-todo-processor-vars-test
  (testing "on-trace-evaluated and on-high-scoring-trace no longer resolve"
    (is (nil? (ns-resolve 'ai.obney.orc.ontology.core.todo-processors 'on-trace-evaluated)))
    (is (nil? (ns-resolve 'ai.obney.orc.ontology.core.todo-processors 'on-high-scoring-trace)))))

(deftest deleted-discovery-namespace-test
  (testing "the discovery namespace is gone entirely"
    (is (nil? (find-ns 'ai.obney.orc.ontology.core.discovery)))))

(deftest deleted-interface-exports-test
  (testing "the discovery-backed interface exports no longer resolve"
    (is (nil? (ns-resolve 'ai.obney.orc.ontology.interface 'get-low-scoring-evaluations)))
    (is (nil? (ns-resolve 'ai.obney.orc.ontology.interface 'build-discovery-workflow!)))
    (is (nil? (ns-resolve 'ai.obney.orc.ontology.interface 'discover-patterns)))))

(deftest deleted-run-pattern-discovery-command-test
  (testing ":ontology/run-pattern-discovery is not in the command registry"
    (is (nil? (get @command-processor/command-registry* :ontology/run-pattern-discovery)))))

(deftest classify-evaluation-skips-unknown-dimension-names-test
  (testing "an unknown dimension name produces no failure for it, and every returned failure carries a URI"
    (let [evaluation {:score 0.2
                       :dimensions [{:name "Novelty" :score 0.1 :feedback "…"}
                                    {:name "Source Grounding" :score 0.1 :feedback "…"}]}
          result (classifier/classify-evaluation evaluation)]
      (is (= 1 (count (:failures result))))
      (is (= "Source Grounding" (:dimension (first (:failures result)))))
      (is (every? string? (map :uri (:failures result))))
      (is (= (:uri (first (:failures result))) (:primary-failure-uri result))))))

(deftest dictionary-parity-test
  (testing "the classifier's dictionary and the static ontology's dimension lookup agree, key for key"
    (doseq [[dim-name uri] classifier/dimension->failure-uri]
      (is (= uri (static/get-failure-concept-for-dimension dim-name))
          (str dim-name " must resolve to the same URI in both lookups")))
    (is (nil? (static/get-failure-concept-for-dimension "Unknown")))
    (is (nil? (get classifier/dimension->failure-uri "Unknown")))))
