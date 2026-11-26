(ns ai.obney.orc.ontology.format-rlm-principles-test
  "C-1 surgical extension of format-rich-pattern (private helper in
   retrieval.clj used by format-context-for-llm + build-actionable-context).

   The existing renderer drops :expected-outcome and :action-taken.reason —
   for RLM tree-design principles these are load-bearing (the WHY behind
   the rule). C-1 extends the renderer to surface them, plus puts the
   tree-DSL snippet in a Clojure code block.

   Tests use the public ontology/format-context-for-llm surface so they
   survive refactors of the internal helper."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface :as ontology]))

(deftest format-rich-pattern-renders-expected-outcome-and-reason
  (testing "format-context-for-llm renders :expected-outcome and :action-taken.reason for an RLM strength pattern"
    (let [pattern {:label "Bounded Map Each"
                   :uri "success:BoundedMapEach"
                   :confidence 1.0
                   :avg-score 0.95
                   :count 1
                   :context-conditions {:task-class :document-analysis
                                        :input-shape :large-chunked-text
                                        :symptom :rate-limit-risk-on-unbounded-parallelism}
                   :action-taken {:type "BoundedMapEach"
                                  :target "[:map-each {:from :chunks :max-concurrency 3} [:llm {...}]]"
                                  :reason "Sub-LLM rate limits exhaust on unbounded concurrency"}
                   :expected-outcome "Successful per-chunk extraction without rate-limit failures"
                   :domain-type "rlm-tree-design"}
          context {:recommended-patterns [pattern]}
          formatted (ontology/format-context-for-llm context {:include #{:patterns}})]
      (is (some? formatted)
          "format-context-for-llm returns a non-nil string when there's a recommended pattern")
      (is (re-find #"Sub-LLM rate limits exhaust" formatted)
          "Renders :action-taken.reason so the model sees WHY the principle exists")
      (is (re-find #"Successful per-chunk extraction" formatted)
          "Renders :expected-outcome so the model knows what success looks like")
      (is (re-find #"(?s)```\s*clojure\s*\n.*:max-concurrency" formatted)
          "Renders the tree-DSL snippet in a Clojure code block (not inline)")
      (is (re-find #"BoundedMapEach" formatted)
          "Pattern-URI name appears (existing behavior preserved)")
      (is (re-find #"task-class.*document-analysis" formatted)
          "Context conditions appear (existing behavior preserved)"))))
