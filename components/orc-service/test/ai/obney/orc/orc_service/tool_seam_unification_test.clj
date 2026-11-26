(ns ai.obney.orc.orc-service.tool-seam-unification-test
  "Generated from orc-service DeclaredToolContractsAreEnforced (generated
   subtree clause) and mcp-sheet-builder ExecutorsUseTheNodesToolCaller.

   Every case runs through the public workflow. MCP transport is the only
   seam replaced (`mcp-client/call-tool` records instead of opening a
   connection); executors are the real `call-mcp-tool` and a really generated,
   checksum-loaded executor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as gen]
            [ai.obney.orc.mcp-sheet-builder.core.executor-runtime :as runtime]
            [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as mcp-client]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def caller-calls (atom []))
(def session-calls (atom []))

(defn- node-caller [tool args & _]
  (swap! caller-calls conj [tool args])
  {:via "node-caller"})

(defn- record-session-call [_session tool args]
  (swap! session-calls conj [tool args])
  {:via "session"})

(defn- reset-recorders! [] (reset! caller-calls []) (reset! session-calls []))

;; ---------------------------------------------------------------------------
;; A researcher's declared contracts govern its generated subtree
;; ---------------------------------------------------------------------------

(defn generated-leaf
  "Code a researcher's generated child runs: calls the tool with arguments that
   violate the researcher's declared contract, reporting what it observed."
  [{:keys [call-tool-fn]}]
  (try
    {:out (pr-str (call-tool-fn "search" {"query" 42}))}
    (catch clojure.lang.ExceptionInfo e
      {:out (str "caught " (:orc.tool/outcome (ex-data e)))})))

(deftest generated-child-tool-calls-honour-the-researchers-contracts
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn node-caller}}]
    (let [definition
          (sheet/workflow "seam-generated-contracts"
            (sheet/blackboard {:question :string :out :string})
            (sheet/repl-researcher "researcher"
              :instruction "Delegate the search to a generated child."
              :reads [:question] :writes [:out]
              :mcp-tools ["search"]
              :tool-contracts {"search" {:arguments [:map {:closed true} [:query :string]]}}
              :rlm {:recursive? false}
              :max-iterations 2))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code (str "(emit-tree! [:sequence "
                                            "[:code {:fn \"ai.obney.orc.orc-service.tool-seam-unification-test/generated-leaf\" "
                                            ":reads [] :writes [:out]}] "
                                            "[:final {:keys [:out]}]])")}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [result (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 30000)]
          (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
          (is (= "caught :invalid-arguments" (get-in result [:outputs :out])))
          (is (empty? @caller-calls) "the tool is never invoked"))))))

;; ---------------------------------------------------------------------------
;; MCP executors use the node's tool caller, then the session, else fail
;; ---------------------------------------------------------------------------

(def ^:private echo-tool
  {:name "seamEcho"
   :inputSchema {"type" "object"
                 "properties" {"query" {"type" "string"}}
                 "required" ["query"]}})

(defn- generated-executor-fqn []
  (let [exec-def (gen/build-executor-definition echo-tool)]
    (runtime/load-executor! (:tool-id exec-def) (:tool-name exec-def)
                            (:source-code exec-def) (:namespace-requires exec-def))
    (:fn-reference exec-def)))

(defn- generated-workflow [workflow-name fqn & {:keys [contracts]}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:query :string :seamEcho-result [:map [:via :string]]})
    (apply sheet/code "call-seamEcho"
           (cond-> [:fn fqn :reads [:query] :writes [:seamEcho-result]]
             contracts (conj :tool-contracts contracts)))))

(defn- generic-workflow [workflow-name]
  (sheet/workflow workflow-name
    (sheet/blackboard {:query :string :seamEcho-result [:map [:via :string]]})
    (sheet/code "call-seamEcho"
      :fn "ai.obney.orc.mcp-sheet-builder.core.executors/call-mcp-tool"
      :reads [:query] :writes [:seamEcho-result])))

(defn- run! [ctx workflow execute-ctx]
  (with-redefs [mcp-client/call-tool record-session-call]
    (sheet/execute (merge ctx execute-ctx) (sheet/build-workflow! ctx workflow)
                   {:query "revenue"} :timeout-ms 10000)))

(deftest generated-executor-calls-the-live-session-inside-a-workflow
  (reset-recorders!)
  (h/with-async-test-context [ctx]
    (let [result (run! ctx (generated-workflow "seam-gen-session" (generated-executor-fqn))
                       {:mcp-session :live-session})]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= [["seamEcho" {:query "revenue"}]] @session-calls)
          "a live session in the execution context reaches the generated executor")
      (is (= {:via "session"} (get-in result [:outputs :seamEcho-result]))))))

(deftest generated-executor-prefers-the-nodes-tool-caller
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:call-tool-fn node-caller}}]
    (let [result (run! ctx (generated-workflow "seam-gen-caller" (generated-executor-fqn))
                       {:mcp-session :live-session})]
      (is (= :success (:status result)))
      (is (= 1 (count @caller-calls)))
      (is (empty? @session-calls) "the gated node caller wins over the raw session")
      (is (= {:via "node-caller"} (get-in result [:outputs :seamEcho-result]))))))

(deftest generated-executor-honours-declared-contracts
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:call-tool-fn node-caller}}]
    (let [result (run! ctx (generated-workflow "seam-gen-contract" (generated-executor-fqn)
                                               :contracts {"seamEcho" {:arguments [:map [:query :int]]}})
                       {})]
      (is (= :failure (:status result)) "the declared contract rejects the call")
      (is (empty? @caller-calls)))))

(def ^:private stand-in-accepting-schema
  "Accepts the historical stand-in shape too, so only a real refusal (not a
   schema mismatch) can make the no-caller/no-session case fail."
  [:or [:map [:via :string]]
   [:map [:mock :boolean] [:tool :string] [:message :string]
    [:args [:map-of :keyword :string]]]])

(defn- permissive-workflow [workflow-name fqn]
  (sheet/workflow workflow-name
    (sheet/blackboard {:query :string :seamEcho-result stand-in-accepting-schema})
    (sheet/code "call-seamEcho" :fn fqn :reads [:query] :writes [:seamEcho-result])))

(deftest executors-without-caller-or-session-fail-explicitly
  (reset-recorders!)
  (h/with-async-test-context [ctx]
    (doseq [[label workflow] [["generated" (permissive-workflow "seam-gen-none" (generated-executor-fqn))]
                              ["generic" (permissive-workflow "seam-generic-none"
                                                              "ai.obney.orc.mcp-sheet-builder.core.executors/call-mcp-tool")]]]
      (testing label
        (let [result (run! ctx workflow {})]
          (is (= :failure (:status result))
              (str label " executor must not report a stand-in as success"))
          (is (not (str/includes? (pr-str (:outputs result)) ":mock"))))))))

(deftest generic-executor-prefers-the-nodes-tool-caller
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:call-tool-fn node-caller}}]
    (let [result (run! ctx (generic-workflow "seam-generic-caller") {:mcp-session :live-session})]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= 1 (count @caller-calls)))
      (is (empty? @session-calls)))))

(deftest explicit-dry-run-produces-a-marked-stand-in
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:mcp/dry-run? true}}]
    (let [result (run! ctx (sheet/workflow "seam-dry-run"
                             (sheet/blackboard {:query :string
                                                :seamEcho-result [:map [:dry-run? :boolean]]})
                             (sheet/code "call-seamEcho" :fn (generated-executor-fqn)
                               :reads [:query] :writes [:seamEcho-result]))
                       {})]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (true? (get-in result [:outputs :seamEcho-result :dry-run?])))
      (is (empty? @session-calls)))))
