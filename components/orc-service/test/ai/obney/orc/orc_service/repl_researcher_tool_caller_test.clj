(ns ai.obney.orc.orc-service.repl-researcher-tool-caller-test
  "The opt-in :tool-caller-fn hook on repl-researcher nodes.

   Leaf tool invocation is context-aware but policy-agnostic: a node may
   name a builder fn that constructs its call-tool-fn from the blackboard
   + base context, letting a consumer thread per-execution context
   (identity/consent/confinement/routing) into tool calls. Absent, the
   static (:call-tool-fn context) is used unchanged — so the hook is
   additive and existing nodes are byte-identical."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor :as executor]))

;; A builder the hook resolves by FQN. Returns a call-tool-fn that
;; records the per-execution context it was built with.
(defn recording-builder
  [blackboard context]
  (fn [tool-name args]
    {:tool tool-name :args args
     :saw-blackboard blackboard
     :saw-context-key (:probe context)}))

(deftest tool-caller-fn-hook
  (testing "absent :tool-caller-fn → static (:call-tool-fn context) unchanged"
    (let [static-fn (fn [_ _] :static)
          chosen (executor/node-call-tool-fn {:name "n"} {:bb 1} {:call-tool-fn static-fn})]
      (is (identical? static-fn chosen)
          "existing nodes get the exact static caller, no wrapping")))

  (testing "present :tool-caller-fn → builder invoked with blackboard + context"
    (let [node {:name "explore"
                :tool-caller-fn (str `recording-builder)}
          blackboard {:tool-context {:session-id 42}}
          context {:call-tool-fn (fn [_ _] :static) :probe :ctx-value}
          built (executor/node-call-tool-fn node blackboard context)
          result (built "fs/list" {"path" "src"})]
      (is (= "fs/list" (:tool result)))
      (is (= {:session-id 42} (get-in result [:saw-blackboard :tool-context]))
          "builder receives the node's blackboard (carrying per-exec context)")
      (is (= :ctx-value (:saw-context-key result))
          "builder receives the base context too")))

  (testing "unresolvable builder → falls back to static caller, never fails the node"
    (let [static-fn (fn [_ _] :static)
          chosen (executor/node-call-tool-fn
                  {:name "n" :tool-caller-fn "no.such/builder"}
                  {} {:call-tool-fn static-fn})]
      (is (identical? static-fn chosen)))))
