(ns ai.obney.orc.orc-service.weed-followups-test
  "Divergences found by the arc's whole-spec weed (check mode), each written RED
   before its fix:
   D1  a generated child cannot replace the researcher's gate or contracts
       (ConfiguredConsumerToolGateIsAuthoritative, DeclaredToolContractsAreEnforced)
   D3  family usage keeps a researcher's own cost (FamilyUsageIsQueryable)
   D4  contract failure messages never echo supplied (undeclared) keys
       (ToolOutcomesAreStructured)
   D6  a start queued longer than one lease length is not resumed while its
       owner lives (OwnedWorkIsNotAbandoned)
   R   a healthy non-checkpointed researcher is not replayed by recovery
       (OwnedWorkIsNotAbandoned — 'never begins a second invocation')
   D7  an explicit MCP dry run never calls out (ExecutorsUseTheNodesToolCaller)
   U2  a model decision gets the default provider retry and honours the deadline
   U3  a delegated child draws on the family wall-clock budget (FamilyBudgetIsShared)"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as mcp-client]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

;; ---------------------------------------------------------------------------
;; D1 — the researcher's gate and contracts always govern its generated subtree
;; ---------------------------------------------------------------------------

(def gate-calls (atom []))
(def raw-calls (atom []))

(defn researcher-gate [_blackboard _context]
  (fn [tool args & _] (swap! gate-calls conj [tool args]) {:hits ["gated"]}))

(defn model-supplied-gate [_blackboard _context]
  (fn [tool args & _] (swap! raw-calls conj [tool args]) {:hits ["bypass"]}))

(defn child-search [{:keys [call-tool-fn]}]
  (try {:out (pr-str (call-tool-fn "search" {"query" 42}))}
       (catch clojure.lang.ExceptionInfo e
         {:out (str "caught " (:orc.tool/outcome (ex-data e)))})))

(defn- emit-tree-with [opts]
  (str "(emit-tree! [:sequence [:code " (pr-str (merge {:fn "ai.obney.orc.orc-service.weed-followups-test/child-search"
                                                        :reads [] :writes [:out]}
                                                       opts))
       "] [:final {:keys [:out]}]])"))

(deftest generated-child-cannot-replace-the-researchers-contracts-or-gate
  (doseq [[label opts]
          [["model-authored empty contracts" {:tool-contracts {}}]
           ["model-authored permissive contracts" {:tool-contracts {"search" {:arguments [:map [:query :int]]}}}]
           ["model-authored gate" {:tool-caller-fn "ai.obney.orc.orc-service.weed-followups-test/model-supplied-gate"}]]]
    (testing label
      (reset! gate-calls []) (reset! raw-calls [])
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [definition (sheet/workflow (str "weed-d1-" (hash label))
                           (sheet/blackboard {:question :string :out :string})
                           (sheet/repl-researcher "researcher"
                             :instruction "delegate" :reads [:question] :writes [:out]
                             :mcp-tools ["search"]
                             :tool-caller-fn "ai.obney.orc.orc-service.weed-followups-test/researcher-gate"
                             :tool-contracts {"search" {:arguments [:map {:closed true} [:query :string]]}}
                             :rlm {:recursive? false} :max-iterations 2))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict (fn [& _] {:outputs {:code (emit-tree-with opts)}
                                               :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
            (let [result (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 30000)]
              (is (= "caught :invalid-arguments" (get-in result [:outputs :out]))
                  (pr-str (select-keys result [:status :error :outputs])))
              (is (empty? @raw-calls) "a model-authored gate is never used")
              (is (empty? @gate-calls) "the researcher's contract blocked the call"))))))))

;; ---------------------------------------------------------------------------
;; D3 — family usage keeps a researcher's own cost
;; ---------------------------------------------------------------------------

(deftest family-usage-keeps-a-researchers-own-cost
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [sheet-id (sheet/build-workflow!
                    ctx (sheet/workflow "weed-d3-cost"
                          (sheet/blackboard {:question :string :b :string})
                          (sheet/repl-researcher "researcher"
                            :instruction "finish" :reads [:question] :writes [:b]
                            :rlm {:recursive? false} :max-iterations 1)))]
      (with-redefs [llm/predict (fn [& _] {:outputs {:code "(final! {:b \"x\"})"}
                                           :usage {:prompt_tokens 1 :completion_tokens 1
                                                   :total_tokens 2 :cost 0.25}})]
        (let [result (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 30000)]
          (is (= :success (:status result)))
          (is (h/settle-until!
               #(= 0.25 (:cost (sheet/get-family-usage ctx (:trace-id result))))
               :timeout-ms 5000)
              (pr-str (sheet/get-family-usage ctx (:trace-id result)))))))))

;; ---------------------------------------------------------------------------
;; D4 — contract failures never echo supplied keys
;; ---------------------------------------------------------------------------

(def d4-calls (atom []))
(defn d4-caller [tool args & _] (swap! d4-calls conj [tool args]) {:hits ["x"]})

(defn d4-leaf [{:keys [call-tool-fn]}]
  (try {:out (pr-str (call-tool-fn "search" {"query" "q" "SECRET-EXTRA-KEY" 1}))}
       (catch clojure.lang.ExceptionInfo e {:out (str "msg " (ex-message e))})))

(deftest contract-failure-messages-never-echo-supplied-keys
  (h/with-async-test-context [ctx {:context {:call-tool-fn d4-caller}}]
    (let [result (sheet/execute ctx (sheet/build-workflow!
                                     ctx (sheet/workflow "weed-d4"
                                           (sheet/blackboard {:out :string})
                                           (sheet/code "leaf" :fn "ai.obney.orc.orc-service.weed-followups-test/d4-leaf"
                                             :tool-contracts {"search" {:arguments [:map {:closed true} [:query :string]]}}
                                             :writes [:out])))
                                {} :timeout-ms 10000)
          out (get-in result [:outputs :out])]
      (is (str/starts-with? out "msg ") out)
      (is (not (str/includes? out "SECRET-EXTRA-KEY")) out)
      (is (empty? @d4-calls)))))

;; ---------------------------------------------------------------------------
;; D6 — a start queued longer than one lease is still owned while its owner lives
;; ---------------------------------------------------------------------------

(defn d6-leaf [{:keys [inputs]}] {:out (* 2 (:n inputs))})

(deftest queued-start-outliving-one-lease-is-not-resumed-while-its-owner-lives
  (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                             :orc/execution-lease-ms 400}}]
    (let [_ (tp/stop (get-in ctx [:processors :sheet/execute-leaf-node]))
          sheet-id (sheet/build-workflow! ctx (sheet/workflow "weed-d6"
                                                (sheet/blackboard {:n :int :out :int})
                                                (sheet/code "double" :fn "ai.obney.orc.orc-service.weed-followups-test/d6-leaf"
                                                  :reads [:n] :writes [:out])))
          other-worker (assoc ctx :orc/instance-id (random-uuid))
          run (future (sheet/execute ctx sheet-id {:n 4} :timeout-ms 30000))
          starts #(filterv (fn [e] (= :sheet/node-execution-started (:event/type e)))
                           (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                                                 :tags #{[:sheet sheet-id]}})))]
      (is (h/settle-until! #(= 1 (count (starts))) :timeout-ms 10000))
      (Thread/sleep 1500)
      (is (empty? (filter :resumed? (sheet/resume-in-progress! other-worker)))
          "the owner keeps renewing its queued start's lease")
      (future-cancel run))))

;; ---------------------------------------------------------------------------
;; R — a healthy non-checkpointed researcher is not replayed by recovery
;; ---------------------------------------------------------------------------

(deftest healthy-non-checkpointed-researcher-is-not-replayed
  (let [entered (promise) release (promise) calls (atom 0)]
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [sheet-id (sheet/build-workflow!
                      ctx (sheet/workflow "weed-r-researcher"
                            (sheet/blackboard {:question :string :b :string})
                            (sheet/repl-researcher "researcher"
                              :instruction "finish" :reads [:question] :writes [:b]
                              :rlm {:recursive? false} :max-iterations 1)))]
        (with-redefs [llm/predict (fn [& _]
                                    (swap! calls inc)
                                    (deliver entered true)
                                    @release
                                    {:outputs {:code "(final! {:b \"x\"})"}
                                     :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
          (let [run (future (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 30000))]
            (is (deref entered 10000 false))
            (is (empty? (filter :resumed? (sheet/resume-in-progress! ctx)))
                "the running researcher is not resumed")
            (deliver release true)
            (is (= :success (:status (deref run 20000 ::timeout))))
            (is (= 1 @calls) "the researcher ran once")))))))

;; ---------------------------------------------------------------------------
;; D7 — an explicit MCP dry run never calls out
;; ---------------------------------------------------------------------------

(deftest explicit-dry-run-never-calls-out
  (let [session-calls (atom 0) caller-calls (atom 0)]
    (h/with-async-test-context [ctx {:context {:mcp/dry-run? true
                                               :call-tool-fn (fn [& _] (swap! caller-calls inc) {:via "caller"})}}]
      (with-redefs [mcp-client/call-tool (fn [& _] (swap! session-calls inc) {:via "session"})]
        (let [result (sheet/execute (assoc ctx :mcp-session :live)
                                    (sheet/build-workflow!
                                     ctx (sheet/workflow "weed-d7"
                                           (sheet/blackboard {:query :string
                                                              :seamEcho-result [:map [:dry-run? :boolean]]})
                                           (sheet/code "call-seamEcho"
                                             :fn "ai.obney.orc.mcp-sheet-builder.core.executors/call-mcp-tool"
                                             :reads [:query] :writes [:seamEcho-result])))
                                    {:query "q"} :timeout-ms 10000)]
          (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
          (is (true? (get-in result [:outputs :seamEcho-result :dry-run?])))
          (is (zero? @session-calls)) (is (zero? @caller-calls)))))))

;; ---------------------------------------------------------------------------
;; U2 — model decisions: default provider retry and deadline
;; ---------------------------------------------------------------------------

(def ^:private route-schema [:enum {:descriptions {"a" "A" "b" "B"}} "a" "b"])

(defn- decision-wf [n]
  (sheet/workflow n (sheet/blackboard {:request :string :route route-schema})
    (sheet/llm-decision "route" :instruction "Pick." :reads [:request] :writes [:route])))

(deftest decision-gets-the-default-provider-retry
  (h/with-async-test-context [ctx]
    (let [calls (atom 0)]
      (with-redefs [llm/predict (fn [_ module _ options]
                                  (if (= 1 (swap! calls inc))
                                    (throw (ex-info "transient" {}))
                                    {:outputs {(-> module :outputs first :name) "a"}
                                     :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                                     :model "m" :raw-response "a"}))]
        (let [result (sheet/execute (assoc ctx :llm-provider :deterministic)
                                    (sheet/build-workflow! ctx (decision-wf "weed-u2-retry"))
                                    {:request "q"} :timeout-ms 10000)]
          (is (= :success (:status result)))
          (is (= "a" (get-in result [:outputs :route])))
          (is (= 2 @calls)))))))

(deftest decision-attempt-outliving-the-deadline-is-not-retried
  (h/with-async-test-context [ctx]
    (let [calls (atom 0)]
      (with-redefs [llm/predict (fn [& _]
                                  (swap! calls inc)
                                  (Thread/sleep 1500)
                                  (throw (ex-info "late failure" {})))]
        (let [result (sheet/execute ctx (sheet/build-workflow! ctx (decision-wf "weed-u2-deadline"))
                                    {:request "q"} :timeout-ms 1000)]
          (Thread/sleep 2500)
          (is (not= :success (:status result)))
          (is (= 1 @calls) "no further provider invocation after the deadline"))))))

;; ---------------------------------------------------------------------------
;; U3 — a delegated child draws on the family wall-clock budget
;; ---------------------------------------------------------------------------

(defn slow-leaf [_] (Thread/sleep 3000) {:out "late"})

(deftest delegated-child-draws-on-the-family-deadline
  (h/with-async-test-context [ctx]
    (let [child-id (sheet/build-workflow!
                    ctx (sheet/workflow "weed-u3-child" (sheet/blackboard {:out :string})
                          (sheet/code "slow" :fn "ai.obney.orc.orc-service.weed-followups-test/slow-leaf"
                            :writes [:out])))
          parent-id (sheet/build-workflow!
                     ctx (sheet/workflow "weed-u3-parent" (sheet/blackboard {:out :string})
                           (sheet/delegate "child" :target-sheet-id child-id :writes [:out]
                             :timeout-ms 600000)))
          started (System/currentTimeMillis)
          result (sheet/execute ctx parent-id {} :timeout-ms 1000)
          elapsed (- (System/currentTimeMillis) started)]
      (is (not= :success (:status result)))
      (is (< elapsed 2500) "the family deadline bounds the child, not the delegate's own 600 s"))))
