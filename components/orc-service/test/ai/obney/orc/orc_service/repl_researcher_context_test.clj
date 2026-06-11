(ns ai.obney.orc.orc-service.repl-researcher-context-test
  "C-1 — :context wiring for repl-researcher nodes.

   The existing apply-ontology-context pipeline (in todo_processors)
   reads :context from any node config and prepends formatted ontology
   knowledge to that node's :instruction at execute time. For :leaf
   :ai nodes the DSL accepts :context and build-workflow emits a
   :sheet/set-node-context command — the pipeline 'just works.'

   But for :repl-researcher, the DSL had no :context keyword arg and
   build-workflow never emitted set-node-context. The runtime hook
   was wired generically but the DSL/build path didn't surface :context
   for this node type — closing that gap is the wedge for hand-authored
   RLM principle injection in C-1."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]))

(deftest repl-researcher-context-propagates-through-build
  (testing "A repl-researcher node configured with :context has that :context
            visible on the runtime node config after build-workflow"
    (h/with-test-context [ctx]
      (let [task-class-id #uuid "00000000-c1c1-4001-b001-d0c0a0a0a0a1"
            wf (dsl/workflow "rlm-context-wiring"
                 (dsl/blackboard {:document :string :summary :string})
                 (dsl/repl-researcher "researcher"
                   :model "google/gemini-2.5-flash"
                   :instruction "Summarize the document."
                   :reads [:document]
                   :writes [:summary]
                   :rlm true
                   :context {:problem-type "problem:Extraction"
                             :tree-id task-class-id
                             :self-learning? true
                             :include-patterns true
                             :include-failures true}))
            sheet-id (dsl/build-workflow! ctx wf)
            nodes (rm/get-nodes-for-sheet ctx sheet-id)
            researcher (some (fn [n] (when (= :repl-researcher (:type n)) n))
                             nodes)]
        (is (some? researcher)
            "A :repl-researcher node exists on the built sheet")
        (is (some? (:context researcher))
            "The repl-researcher's runtime config carries :context (so apply-ontology-context can fire at execute time)")
        (is (= task-class-id (get-in researcher [:context :tree-id]))
            "The task-class-id provided in the DSL is preserved as :context.tree-id")
        (is (true? (get-in researcher [:context :self-learning?]))
            "Self-learning flag preserved")
        (is (= "problem:Extraction" (get-in researcher [:context :problem-type]))
            "Problem type preserved")))))
