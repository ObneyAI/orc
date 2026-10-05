(ns ai.obney.orc.orc-service.tool-contract-enforcement-test
  "Generated from LeafExecutor invariants DeclaredToolContractsAreEnforced,
   ToolArgumentIdentityIsExact and ToolOutcomesAreStructured
   (specs/orc-service.allium).

   Researcher cases run a real single-iteration researcher through the public
   workflow with only the model scripted: its code calls the bound tool and
   final!s the printed return value, so the structured outcome the sandbox
   handed back is observable in the run output. Code-leaf cases use the public
   `sheet/code` with declared :tool-contracts. The checkpointed case drives the
   researcher executor with recording effect-claim capabilities."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def tool-calls (atom []))
(def tool-reply (atom nil))

(defn- tool-caller [tool args & _]
  (swap! tool-calls conj [tool args])
  (let [reply @tool-reply]
    (if (instance? Throwable reply) (throw reply) reply)))

(def ^:private search-contract
  {"search" {:arguments [:map {:closed true} [:query :string]]
             :result [:map [:hits [:vector :string]]]}})

(defn- researcher-workflow [workflow-name contracts]
  (sheet/workflow workflow-name
    (sheet/blackboard {:question :string :out :string})
    (apply sheet/repl-researcher "researcher"
           (cond-> [:instruction "Search, then report what the tool returned."
                    :reads [:question] :writes [:out]
                    :mcp-tools ["search"]
                    :rlm {:recursive? false}
                    :max-iterations 1]
             contracts (conj :tool-contracts contracts)))))

(defn- run-researcher
  "Run the researcher whose single iteration evaluates `call-form` (a string of
   Clojure calling the tool) and final!s its printed value."
  [ctx workflow call-form reply]
  (reset! tool-calls [])
  (reset! tool-reply reply)
  (with-redefs [llm/predict
                (fn [& _]
                  {:outputs {:code (str "(final! {:out (pr-str " call-form ")})")}
                   :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
    (sheet/execute ctx (sheet/build-workflow! ctx workflow) {:question "q"}
                   :timeout-ms 30000)))

(def ^:private ok-reply {:hits ["revenue-q3"]})

;; ---------------------------------------------------------------------------
;; DeclaredToolContractsAreEnforced (researcher)
;; ---------------------------------------------------------------------------

(deftest researcher-invalid-arguments-never-reach-the-tool
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-bad-args" search-contract)
                                 "(search {\"query\" 42})" ok-reply)
          out (get-in result [:outputs :out])]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (empty? @tool-calls) "the tool is never invoked")
      (is (str/includes? out ":invalid-arguments") out))))

(deftest researcher-valid-arguments-reach-the-tool-unchanged
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-good-args" search-contract)
                                 "(search {\"query\" \"q3\"})" ok-reply)]
      (is (= :success (:status result)))
      (is (= [["search" {"query" "q3"}]] @tool-calls)
          "the tool receives the caller's arguments exactly as given")
      (is (str/includes? (get-in result [:outputs :out]) "revenue-q3")))))

(deftest researcher-invalid-result-is-never-a-success-value
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-bad-result" search-contract)
                                 "(search {\"query\" \"q3\"})" {:hits "SECRET-NOT-A-VECTOR"})
          out (get-in result [:outputs :out])]
      (is (= 1 (count @tool-calls)) "the tool ran; its result failed the contract")
      (is (str/includes? out ":invalid-result") out)
      (is (not (str/includes? out "SECRET-NOT-A-VECTOR"))
          "the rejected value is neither returned nor echoed"))))

(deftest researcher-without-contracts-is-unchanged
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-untyped" nil)
                                 "(search {\"query\" 42})" ok-reply)]
      (is (= :success (:status result)))
      (is (= [["search" {"query" 42}]] @tool-calls)
          "an undeclared contract stays untyped"))))

;; ---------------------------------------------------------------------------
;; ToolArgumentIdentityIsExact
;; ---------------------------------------------------------------------------

(deftest researcher-aliased-argument-keys-are-rejected
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-alias" search-contract)
                                 "(search {\"query\" \"a\" :query \"b\"})" ok-reply)]
      (is (empty? @tool-calls))
      (is (str/includes? (get-in result [:outputs :out]) ":invalid-arguments")))))

(deftest researcher-aliased-result-keys-are-rejected
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-result-alias" search-contract)
                                 "(search {\"query\" \"q3\"})"
                                 {:hits ["expected"] "hits" ["alias"]})]
      (is (str/includes? (get-in result [:outputs :out]) ":invalid-result")))))

;; ---------------------------------------------------------------------------
;; ToolOutcomesAreStructured
;; ---------------------------------------------------------------------------

(deftest researcher-tool-error-is-a-structured-outcome
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-tool-error" search-contract)
                                 "(search {\"query\" \"q3\"})"
                                 (ex-info "upstream exploded" {:secret "TOKEN-123"}))
          out (get-in result [:outputs :out])]
      (is (str/includes? out ":tool-error") out)
      (is (not (str/includes? out "TOKEN-123")) "exception data is never echoed"))))

(defn code-leaf-call
  "Consumer code: calls the tool and reports the outcome kind it observed."
  [{:keys [call-tool-fn inputs]}]
  (try
    {:out (pr-str (call-tool-fn "search" {"query" (:q inputs)}))}
    (catch clojure.lang.ExceptionInfo e
      {:out (str "caught " (:orc.tool/outcome (ex-data e)))})))

(defn code-leaf-unhandled
  [{:keys [call-tool-fn]}]
  {:out (pr-str (call-tool-fn "search" {"query" 42}))})

(defn- code-workflow [workflow-name f contracts]
  (sheet/workflow workflow-name
    (sheet/blackboard {:q [:or :string :int] :out :string})
    (apply sheet/code "search-leaf"
           (cond-> [:fn (str "ai.obney.orc.orc-service.tool-contract-enforcement-test/" f)
                    :reads [:q] :writes [:out]]
             contracts (conj :tool-contracts contracts)))))

(deftest code-leaf-declared-contract-is-enforced
  (h/with-async-test-context [ctx {:context {:call-tool-fn tool-caller}}]
    (testing "invalid arguments: the consumer sees the outcome kind; the tool is not called"
      (reset! tool-calls [])
      (reset! tool-reply ok-reply)
      (let [result (sheet/execute ctx (sheet/build-workflow!
                                       ctx (code-workflow "tce-code-bad" "code-leaf-call"
                                                          search-contract))
                                  {:q 42} :timeout-ms 10000)]
        (is (= "caught :invalid-arguments" (get-in result [:outputs :out])))
        (is (empty? @tool-calls))))
    (testing "invalid result: the consumer sees :invalid-result"
      (reset! tool-calls [])
      (reset! tool-reply {:hits "nope"})
      (let [result (sheet/execute ctx (sheet/build-workflow!
                                       ctx (code-workflow "tce-code-bad-result" "code-leaf-call"
                                                          search-contract))
                                  {:q "q3"} :timeout-ms 10000)]
        (is (= "caught :invalid-result" (get-in result [:outputs :out])))))
    (testing "an unhandled invalid call fails the node"
      (reset! tool-calls [])
      (let [result (sheet/execute ctx (sheet/build-workflow!
                                       ctx (code-workflow "tce-code-unhandled" "code-leaf-unhandled"
                                                          search-contract))
                                  {:q "x"} :timeout-ms 10000)]
        (is (= :failure (:status result)))
        (is (empty? @tool-calls))))))

(deftest code-leaf-contracts-survive-round-trip
  (h/with-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (code-workflow "tce-code-roundtrip" "code-leaf-call"
                                                             search-contract))
          node (some #(when (= "search-leaf" (:name %)) %) (sheet/get-nodes-for-sheet ctx sheet-id))]
      (is (= search-contract (:tool-contracts node))))))

;; ---------------------------------------------------------------------------
;; DeclaredToolContractsAreEnforced (checkpointed researcher claims no effect)
;; ---------------------------------------------------------------------------

(deftest checkpointed-researcher-claims-no-effect-for-invalid-arguments
  (let [node-id (random-uuid)
        tick-id (random-uuid)
        claims (atom [])
        calls (atom [])
        node {:id node-id :type :repl-researcher
              :instruction "call the tool" :reads [] :writes [:out]
              :mcp-tools ["search"]
              :tool-contracts {"search" (assoc (get search-contract "search")
                                               :checkpoint-safe? true)}
              :max-iterations 1
              :rlm {:checkpointed? true :recursive? false
                    :timeouts {:provider-ms 1000 :iteration-ms 2000 :campaign-ms 5000}}}
        context {:sheet-id (random-uuid) :tick-id tick-id :node-id node-id
                 :researcher-ownership-epoch 1
                 :call-tool-fn (fn [tool args _] (swap! calls conj [tool args]) ok-reply)
                 :claim-researcher-effect! (fn [claim] (swap! claims conj claim)
                                             {:command-result/events []})
                 :complete-researcher-effect! (fn [_] {:command-result/events []})}
        blackboard {:out {:key :out :schema :string :value nil :version 0}}]
    (with-redefs [llm/predict
                  (fn [& _]
                    {:outputs {:code "(final! {:out (pr-str (search {\"query\" 42}))})"}
                     :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
      (let [result (executor/execute-repl-researcher-rlm node blackboard :test context)]
        (is (empty? @calls) "the tool is never invoked")
        (is (empty? (filter #(= :tool (:kind %)) @claims))
            "no effect is claimed for arguments that fail the contract")
        (is (str/includes? (str (get-in result [:outputs :out])) ":invalid-arguments")
            (pr-str (select-keys result [:status :error :outputs])))))))

;; ---------------------------------------------------------------------------
;; ToolOutcomesAreStructured — every researcher-facing tool failure, typed or
;; not, plain or checkpointed; a host may declare a safe message to share.
;; ---------------------------------------------------------------------------

(deftest researcher-untyped-tool-error-is-structured-and-safe
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-untyped-error" nil)
                                 "(search {\"query\" \"q3\"})"
                                 (ex-info "Authorization: Bearer TOKEN-456" {}))
          out (get-in result [:outputs :out])]
      (is (str/includes? out ":tool-error") out)
      (is (not (str/includes? out "TOKEN-456")) "a raw exception message is never echoed"))))

(deftest host-declared-safe-message-reaches-the-researcher
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn tool-caller}}]
    (let [result (run-researcher ctx (researcher-workflow "tce-safe-message" search-contract)
                                 "(search {\"query\" \"q3\"})"
                                 (ex-info "internal detail TOKEN-789"
                                          {:orc.tool/message "Unknown report id; list reports first."}))
          out (get-in result [:outputs :out])]
      (is (str/includes? out ":tool-error") out)
      (is (str/includes? out "Unknown report id; list reports first.") out)
      (is (not (str/includes? out "TOKEN-789")) out))))

(deftest checkpointed-tool-error-is-structured
  (let [node-id (random-uuid)
        node {:id node-id :type :repl-researcher
              :instruction "call the tool" :reads [] :writes [:out]
              :mcp-tools ["search"]
              :tool-contracts {"search" (assoc (get search-contract "search")
                                               :checkpoint-safe? true)}
              :max-iterations 1
              :rlm {:checkpointed? true :recursive? false
                    :timeouts {:provider-ms 1000 :iteration-ms 2000 :campaign-ms 5000}}}
        context {:sheet-id (random-uuid) :tick-id (random-uuid) :node-id node-id
                 :researcher-ownership-epoch 1
                 :call-tool-fn (fn [_ _ _] (throw (ex-info "SECRET-TOKEN-1" {})))
                 :claim-researcher-effect! (fn [_] {:command-result/events []})
                 :complete-researcher-effect! (fn [_] {:command-result/events []})}
        blackboard {:out {:key :out :schema :string :value nil :version 0}}]
    (with-redefs [llm/predict
                  (fn [& _]
                    {:outputs {:code "(final! {:out (pr-str (search {\"query\" \"q3\"}))})"}
                     :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
      (let [out (str (get-in (executor/execute-repl-researcher-rlm node blackboard :test context)
                             [:outputs :out]))]
        (is (str/includes? out ":tool-error") out)
        (is (not (str/includes? out "SECRET-TOKEN-1")) out)))))

(defn code-leaf-catches-host-error
  [{:keys [call-tool-fn]}]
  (try
    {:out (pr-str (call-tool-fn "search" {"query" "q3"}))}
    (catch clojure.lang.ExceptionInfo e
      {:out (str "host data " (:host/code (ex-data e)))})))

(deftest code-leaf-sees-its-own-tool-exceptions-unchanged
  (testing "consumer code owns its host capability: a tool's own exception reaches it as thrown"
    (h/with-async-test-context [ctx {:context {:call-tool-fn tool-caller}}]
      (reset! tool-reply (ex-info "host failure" {:host/code 404}))
      (let [result (sheet/execute ctx (sheet/build-workflow!
                                       ctx (code-workflow "tce-code-host-error"
                                                          "code-leaf-catches-host-error"
                                                          search-contract))
                                  {:q "q3"} :timeout-ms 10000)]
        (is (= "host data 404" (get-in result [:outputs :out])))))))
