(ns ai.obney.orc.orc-service.generated-tree-tool-gate-test
  "Phase 4B: tool calls inside RLM-generated (Phase-2) trees gate.

   When a repl-researcher opts into a gated tool-caller via :tool-caller-fn,
   its generated code nodes must use the SAME gated caller — not the static
   one. The gated caller is a closure built from :tool-context, which can't
   thread across the (async) child-tick boundary, so the parent's builder FQN
   is STAMPED onto each generated `:code` node (inject-tool-caller-fn), and
   `execute-code` rebuilds the gated caller from the node's own blackboard
   :tool-context via `node-call-tool-fn` — exactly how the parent node does.

   These tests exercise that logic directly (no full create-sheet/child-tick
   harness needed): the tree-walking stamper, and execute-code's gating."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(def recorder (atom []))

(defn gated-builder
  [blackboard _context]
  (let [tc (get-in blackboard [:tool-context :value])]
    (swap! recorder conj {:kind :builder-invoked :tool-context tc})
    (fn [tool args]
      (swap! recorder conj {:kind :gated-call :tool tool :args args :tool-context tc})
      {"ok" true})))

(defn tool-using-code-fn
  [ctx]
  (let [ctf (:call-tool-fn ctx)]
    (ctf "fs/list" {"path" "src"})
    {:tool-result :done}))

(def ^:private this-ns "ai.obney.orc.orc-service.generated-tree-tool-gate-test")
(def ^:private code-fqn (str this-ns "/tool-using-code-fn"))
(def ^:private builder-fqn (str this-ns "/gated-builder"))

(defn- run-code-node [node]
  (reset! recorder [])
  (let [static-caller (fn [_ _] (swap! recorder conj {:kind :static-call}) {"ok" true})
        blackboard {:tool-context {:value {:session-id "S-123" :request-id "R-9"} :version 1}}
        result (executor/execute-code node blackboard {:call-tool-fn static-caller})]
    {:result result :events @recorder}))

(deftest generated-code-node-with-tool-caller-fn-gates-its-tool-call
  (testing "a :code node declaring :tool-caller-fn rebuilds the gated caller
            from its blackboard :tool-context and routes its tool call through it"
    (let [{:keys [result events]}
          (run-code-node {:fn code-fqn
                          :tool-caller-fn builder-fqn
                          :reads [:tool-context]
                          :writes [:tool-result]})]
      (is (= :success (:status result)))
      (is (some #(= :builder-invoked (:kind %)) events)
          "the gated builder was invoked (gated path taken, not static)")
      (let [call (first (filter #(= :gated-call (:kind %)) events))]
        (is (some? call) "the code node's tool call went through the gated caller")
        (is (= "fs/list" (:tool call)))
        (is (= {:session-id "S-123" :request-id "R-9"} (:tool-context call))
            "the gated caller carried session data rebuilt from :tool-context"))
      (is (not-any? #(= :static-call (:kind %)) events)
          "the static caller was never used"))))

(deftest generated-code-node-without-tool-caller-fn-uses-static-caller
  (testing "a :code node with NO :tool-caller-fn uses the static caller
            unchanged — no builder is ever invoked (backwards compatible)"
    (let [{:keys [result events]}
          (run-code-node {:fn code-fqn
                          :reads [:tool-context]
                          :writes [:tool-result]})]
      (is (= :success (:status result)))
      (is (not-any? #(= :builder-invoked (:kind %)) events)
          "no gated builder is built when :tool-caller-fn is absent")
      (is (some #(= :static-call (:kind %)) events)
          "the code node's tool call went through the static caller"))))

(deftest inject-tool-caller-fn-stamps-generated-code-nodes
  (testing "inject-tool-caller-fn stamps the builder FQN onto each generated
            sheet/code node, leaving other node types untouched"
    (let [inject @#'executor/inject-tool-caller-fn
          tree '(sheet/sequence
                  (sheet/code :fn "a/b" :reads [:x] :writes [:y])
                  (sheet/llm :instruction "..." :reads [] :writes [:z])
                  (sheet/code :fn "c/d" :reads [] :writes [:w]))
          stamped (inject tree "ns/builder")
          code-forms (filter #(and (seq? %) (= 'sheet/code (first %)))
                             (tree-seq seq? identity stamped))]
      (is (= 2 (count code-forms)))
      (is (every? #(= "ns/builder" (-> (apply hash-map (rest %)) :tool-caller-fn))
                  code-forms)
          "every generated code node carries the parent's builder FQN")
      (let [llm-form (first (filter #(and (seq? %) (= 'sheet/llm (first %)))
                                    (tree-seq seq? identity stamped)))]
        (is (not (contains? (apply hash-map (rest llm-form)) :tool-caller-fn))
            "non-code nodes are untouched"))))
  (testing "nil tool-caller-fn is a no-op (backwards compatible)"
    (let [inject @#'executor/inject-tool-caller-fn
          tree '(sheet/code :fn "a/b" :reads [] :writes [])]
      (is (= tree (inject tree nil))))))
