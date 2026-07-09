(ns ai.obney.orc.orc-service.ce6b-rlm-tool-advertisement-test
  "CE-6b durable tests — advertise bound mcp-tools in the RLM code-gen prompt
   (ADR 0018).

   Root cause (grounded by the live S6-lite forensic): the Phase-1 sandbox
   BINDS the node's mcp-tools (af7c718e) and the task instruction verifiably
   told the model they exist, but the RLM code-gen module NEVER advertises
   them at the system level AND biases 'For ANY large data ... ALWAYS use
   emit-tree!' — so the model emits data-processing trees with zero tool
   calls. Fix: re-house the non-RLM researcher's tool-advertisement pattern
   (module input 'Available tools you can call as functions' + instructions
   section) into build-rlm-code-generation-module, and scope the emit-tree
   exclusivity to large-DATA processing when tools are bound.

   All assertions go through the REAL builder fn (no string copies of the
   prompt)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(def ^:private build-fn #'executor/build-rlm-code-generation-module)

(def ^:private coding-tools
  ["fs/read" "fs/write" "apply_patch" "shell/exec" "fs/list"])

(defn- build-module
  "Build the RLM code-gen module the way the executor call site does:
   node carries :mcp-tools; the builder derives/receives the same list."
  [node]
  (build-fn node "" [] {} {} {}))

(deftest tools-bound-module-advertises-them
  (testing "With :mcp-tools non-empty, the module/prompt advertises the bound tools"
    (let [node {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools coding-tools}
          module (build-module node)
          instructions (:instructions module)]
      (is (string? instructions))
      (testing "every bound tool NAME appears in the prompt"
        (doseq [tool coding-tools]
          (is (str/includes? instructions tool)
              (str "prompt must name bound tool " tool))))
      (testing "the directly-callable-functions statement"
        (is (re-find #"(?i)directly[- ]callable functions" instructions)
            "prompt states the tools are bound as directly callable functions in the sandbox"))
      (testing "the generic call-shape example uses a real bound tool name"
        (is (re-find #"\(fs/read \{\"" instructions)
            "prompt shows the call shape (tool {\"arg\" ...}) built from the node's own tool list"))
      (testing "inline-effects guidance (read -> patch/write -> verify in Phase-1)"
        (is (re-find #"(?i)workspace/file effects" instructions)
            "prompt tells the model workspace/file effects go through the bound tools")
        (is (re-find #"(?i)inline in your Phase-?1 code" instructions)
            "prompt says to call the bound tools INLINE in Phase-1 code")
        (is (re-find #"(?i)read .{0,10}patch/write .{0,10}verify" instructions)
            "prompt gives the read -> patch/write -> verify loop"))
      (testing "re-housed non-RLM precedent: a declared :tools module input"
        (is (some #(and (= :tools (:name %))
                        (= "Available tools you can call as functions"
                           (:description %)))
                  (:inputs module))
            "module declares the :tools input exactly like the non-RLM researcher precedent")))))

(deftest tools-absent-module-is-unchanged
  (testing "With :mcp-tools empty or absent, the module is byte-identical to the pre-CE-6b form"
    (let [base-node {:type :repl-researcher
                     :rlm {:recursive? true}
                     :writes [:answer]
                     :instruction "Implement the fix."}
          module-absent (build-module base-node)
          module-empty (build-module (assoc base-node :mcp-tools []))]
      (testing "empty and absent :mcp-tools produce identical modules"
        (is (= module-absent module-empty)))
      (testing "no tool advertisement leaks into the tools-absent module"
        (is (not (some #(= :tools (:name %)) (:inputs module-absent)))
            "no :tools input is declared")
        (is (not (str/includes? (:instructions module-absent) "## Bound Tools"))
            "no Bound Tools section in the prompt")
        (is (not (re-find #"(?i)workspace/file effects" (:instructions module-absent)))
            "no inline-effects guidance in the prompt"))
      (testing "the strong large-data emit-tree guidance keeps its original tools-absent form"
        (is (str/includes? (:instructions module-absent)
                           "For ANY large data (documents, collections, etc.), ALWAYS use emit-tree!:")
            "the original unqualified line survives verbatim when no tools are bound")))))

(deftest tools-bound-scopes-the-emit-tree-exclusivity
  (testing "With tools bound, the emit-tree bias is scoped to DATA processing, not exclusive"
    (let [node {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools coding-tools}
          instructions (:instructions (build-module node))]
      (is (not (re-find #"(?i)for any large data.{0,60}always use emit-tree!" instructions))
          "the unqualified ALWAYS-use-emit-tree exclusivity claim must be gone when tools are bound")
      (is (str/includes? instructions
                         "For large DATA processing (documents, collections), use emit-tree!; for workspace/file effects, call your bound tools directly inline:")
          "the scoped replacement keeps emit-tree for large data but routes effects to the bound tools"))))

;; =============================================================================
;; Cycle 4 — call-site threading: the RUNTIME inputs map handed to dscloj
;; carries the :tools value (a declared input with no value would advertise
;; an empty field). Mirrors CE-2's G2 capture through the REAL
;; execute-repl-researcher-rlm; dscloj/predict is redef'd to capture and
;; abort — no live LLM.
;; =============================================================================

(defn- capture-rlm-call
  "Drive the REAL execute-repl-researcher-rlm one iteration with
   dscloj/predict redef'd to CAPTURE the (module, inputs) handed to the
   model, then abort. Returns {:module ... :inputs ...} (or nil)."
  [node-extra]
  (let [captured (atom nil)
        node (merge {:type :repl-researcher
                     :name "ce6b-researcher"
                     :instruction "Do the coding task."
                     :reads []
                     :writes [:answer]
                     :max-iterations 1
                     :rlm {:recursive? true}}
                    node-extra)]
    (with-redefs [dscloj/predict (fn [_provider module inputs _opts]
                                   (reset! captured {:module module :inputs inputs})
                                   (throw (ex-info "capture-abort" {})))]
      (try (executor/execute-repl-researcher-rlm node {} :probe-provider {})
           (catch Throwable _ nil)))
    @captured))

(deftest runtime-inputs-carry-the-tools-value
  (testing "with :mcp-tools on the node, the runtime inputs map carries the joined tool list"
    (let [{:keys [module inputs]} (capture-rlm-call {:mcp-tools coding-tools})]
      (is (some? inputs) "dscloj/predict should have been called (inputs captured)")
      (is (contains? inputs :tools)
          "runtime inputs map should CONTAIN :tools (RED before the call-site threading)")
      (is (= (str/join ", " coding-tools) (:tools inputs))
          "the :tools input value is the joined bound-tool list from the node config")
      (is (contains? (set (map :name (:inputs module))) :tools)
          "the module handed to dscloj declares the :tools input field")))
  (testing "without :mcp-tools, the runtime inputs map omits :tools (backward-compatible)"
    (let [{:keys [module inputs]} (capture-rlm-call {})]
      (is (some? inputs) "dscloj/predict should have been called (inputs captured)")
      (is (not (contains? inputs :tools)) "no :tools value is injected")
      (is (not (contains? (set (map :name (:inputs module))) :tools))
          "no :tools field is declared"))))
