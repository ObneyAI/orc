(ns ai.obney.orc.orc-service.real-llm-arc-e2e-test
  "REAL-model end-to-end proof of the decision + tool runtime: one workflow in
   which a native decision model (Jev via OpenRouter, selected per node by
   :model) routes a request; the lookup branch calls a contract-enforced tool
   leaf and delegates a summary to a conversational child under the family
   budget; the research branch runs a real checkpointed researcher with a
   contract-enforced, checkpoint-safe tool; an empty request ends in clarify.
   Opt-in through ORC_OPENROUTER_E2E_TESTS. Asserts structure, routing on
   clear-cut inputs and durable evidence — no accuracy claim."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def host-calls (atom []))

(defn host-tools
  "The consumer's host tool capability (context-aware 3-argument contract)."
  ([tool args] (host-tools tool args nil))
  ([tool args tool-context]
   (swap! host-calls conj {:tool tool :args args :tool-context tool-context})
   (case tool
     "reports/figure" {:figure "Q3 2025 revenue was 4.2 million dollars."}
     "web/search" {:hits ["Churn rose after the August price change."
                          "Support tickets about billing doubled in August."]}
     (throw (ex-info "unknown tool" {:orc.tool/message (str "No such tool: " tool)})))))

(def ^:private routes
  {"lookup" "Retrieve a figure that already exists in a stored report."
   "research" "Investigate an open question that needs new evidence gathered."
   "clarify" "The request is too ambiguous to act on; ask the user what they mean."})

(defn- summarizer [ctx]
  (sheet/build-workflow!
   ctx (sheet/workflow "arc-e2e-summarizer"
         (sheet/blackboard {:facts [:map [:figure :string]] :summary :string})
         (sheet/llm "summarize" :model live/openrouter-model
           :instruction "Write one sentence stating the figure in the facts, without adding anything."
           :reads [:facts] :writes [:summary]))))

(defn- assistant [ctx child-id]
  (sheet/workflow "arc-e2e-assistant"
    (sheet/blackboard {:request :string
                       :route (into [:enum {:descriptions routes}] (keys routes))
                       :facts [:map [:figure :string]]
                       :summary :string})
    (sheet/sequence "main"
      (sheet/llm-decision "route" :model "jev"
        :instruction "Choose the operation that addresses the user's request."
        :reads [:request] :writes [:route]
        :min-confidence 0.6 :abstain "clarify")
      (sheet/fallback "dispatch"
        (sheet/sequence "lookup-route"
          (sheet/condition "is-lookup" :check {:key :route :op :equals :value "lookup"})
          (sheet/tool "fetch-figure" :tool "reports/figure" :reads [:request] :writes [:facts]
            :tool-contracts {"reports/figure" {:arguments [:map [:request :string]]
                                               :result [:map [:figure :string]]}})
          (sheet/delegate "summarize" :target-sheet-id child-id
            :reads [:facts] :writes [:summary]))
        (sheet/sequence "research-route"
          (sheet/condition "is-research" :check {:key :route :op :equals :value "research"})
          (sheet/repl-researcher "investigate" :model live/openrouter-model
            :instruction (str "The user's request is already available as the input `request` (a string); "
                              "use it directly and do not redefine it. Call web/search once with "
                              "{:query <a short query string about the request>}, then call final! with "
                              ":summary set to one non-empty sentence based only on the returned hits.")
            :reads [:request] :writes [:summary]
            :mcp-tools ["web/search"]
            :tool-contracts {"web/search" {:checkpoint-safe? true
                                           :arguments [:map [:query :string]]
                                           :result [:map [:hits [:vector :string]]]}}
            :rlm {:timeouts {:provider-ms 60000 :iteration-ms 90000 :campaign-ms 200000}}
            :max-iterations 4))
        (sheet/condition "is-clarify" :check {:key :route :op :equals :value "clarify"})))))

(defn- decision-record [ctx result]
  (:decision (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (:decision %))
                            (h/read-tick-events ctx (:trace-id result))))))

(defn- show [label ctx result]
  (println label (pr-str {:status (:status result) :error (:error result)
                          :outputs (:outputs result)
                          :decision (decision-record ctx result)
                          :family-usage (sheet/get-family-usage ctx (:trace-id result))
                          :host-calls (mapv #(update % :tool-context (fn [c] (some-> c keys sort)))
                                            @host-calls)})))

(deftest real-arc-end-to-end
  (live/with-real-openrouter
    (live/register-openrouter!)
    (llm/register-provider! :jev {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                                  :config {:api-key (System/getenv "OPENROUTER_API_KEY")}})
    (h/with-async-test-context [ctx {:context {:llm-provider :openrouter :call-tool-fn host-tools}}]
      (let [child-id (summarizer ctx)
            sheet-id (sheet/build-workflow! ctx (assistant ctx child-id))
            run! (fn [request]
                   (reset! host-calls [])
                   (sheet/execute ctx sheet-id {:request request} :timeout-ms 240000
                                  :llm-call-budget 30))]
        (testing "lookup: Jev routes, a contract-enforced tool leaf fetches, a delegated child summarises"
          (let [result (run! "Please pull the Q3 2025 revenue number from the board report we filed.")
                record (decision-record ctx result)
                usage (sheet/get-family-usage ctx (:trace-id result))]
            (show :ARC-LOOKUP ctx result)
            (is (= :success (:status result)))
            (is (= "lookup" (get-in result [:outputs :route])))
            (is (number? (:confidence record)) "the native decision model reported a confidence")
            (is (= [["reports/figure" {:request "Please pull the Q3 2025 revenue number from the board report we filed."}]]
                   (mapv (juxt :tool :args) @host-calls)))
            (is (not (str/blank? (get-in result [:outputs :summary]))))
            (is (pos? (:total-tokens usage 0)) "family usage includes the delegated child")))
        (testing "research: a real checkpointed researcher uses its contract-enforced tool"
          (let [result (run! "Why did customer churn rise last month?")
                searches (filter #(= "web/search" (:tool %)) @host-calls)]
            (show :ARC-RESEARCH ctx result)
            (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
            (is (= "research" (get-in result [:outputs :route])))
            (is (seq searches) "the researcher called its tool")
            (is (every? #(string? (get (:args %) "query" (get (:args %) :query))) searches)
                "every call that reached the tool honoured the argument contract")
            (is (every? #(some? (get-in % [:tool-context :orc/idempotency-key])) searches)
                "checkpointed calls carry ORC's idempotency key to the host")
            (is (not (str/blank? (get-in result [:outputs :summary]))))))
        (testing "an empty-of-meaning request ends in clarify with no tool effect"
          (let [result (run! "hmm")]
            (show :ARC-CLARIFY ctx result)
            (is (= :success (:status result)))
            (is (= "clarify" (get-in result [:outputs :route])))
            (is (empty? @host-calls))))))))
