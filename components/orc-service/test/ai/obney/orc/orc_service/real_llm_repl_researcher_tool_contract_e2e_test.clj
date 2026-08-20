(ns ai.obney.orc.orc-service.real-llm-repl-researcher-tool-contract-e2e-test
  "DET-E2E-160: a real Phase-1 model chains tools from declared contracts."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private evidence-marker "SCHEMA_CHAIN_EVIDENCE_160")

(def ^:private search-result-schema
  [:map {:closed true}
   [:status [:enum :succeeded :unavailable]]
   [:candidates
    [:vector
     [:map {:closed true}
      [:source-title :string]
      [:artifact-reference :string]]]]])

(def ^:private retrieve-result-schema
  [:map {:closed true}
   [:status [:enum :succeeded :unavailable]]
   [:artifact-reference :string]
   [:evidence-marker :string]
   [:content :string]])

(def ^:private answer-schema
  [:map {:closed true}
   [:explanation [:string {:min 1 :max 2000}]]
   [:artifact-reference :string]
   [:evidence-marker :string]])

(deftest det-e2e-160-real-phase1-chains-tools-from-result-contract
  (testing "the model reads :candidates and feeds its artifact reference into retrieval"
    (live/with-real-openrouter
      (live/register-openrouter!)
      (let [calls (atom [])
            caller (fn call-tool
                     ([tool args]
                      (swap! calls conj {:tool tool :args args})
                      (case tool
                        "academic-search"
                        {:status :succeeded
                         :candidates [{:source-title "Contract-Guided Research"
                                       :artifact-reference "paper-160"}]}

                        "academic-retrieve"
                        (let [reference (or (get args :artifact-reference)
                                            (get args "artifact-reference"))]
                          {:status :succeeded
                           :artifact-reference reference
                           :evidence-marker evidence-marker
                           :content "Explicit result contracts let agents compose tools reliably."})))
                     ([tool args _tool-context]
                      (call-tool tool args)))]
        (h/with-async-test-context
          [ctx {:context {:llm-provider :openrouter
                          :model live/openrouter-model
                          :call-tool-fn caller}}]
          (let [definition
                (sheet/workflow "det-e2e-160-result-schema-guided-tool-chain"
                  (sheet/blackboard {:question :string :research-answer answer-schema})
                  (sheet/repl-researcher "contract-guided-live-researcher"
                    :model live/openrouter-model
                    :instruction "Research the question using the bound academic capabilities, retrieve the most relevant evidence, and provide a concise grounded result."
                    :reads [:question]
                    :writes [:research-answer]
                    :mcp-tools ["academic-search" "academic-retrieve"]
                    :tool-contracts
                    {"academic-search"
                     {:arguments [:map {:closed true} [:query :string]]
                      :result search-result-schema}
                     "academic-retrieve"
                     {:arguments [:map {:closed true} [:artifact-reference :string]]
                      :result retrieve-result-schema}}
                    :rlm {:recursive? false}
                    :max-iterations 5))
                sheet-id (sheet/build-workflow! ctx definition)
                result (sheet/execute ctx sheet-id
                                      {:question "Why do explicit contracts improve reliable agent tool composition?"}
                                      :timeout-ms 240000)
                answer (get-in result [:outputs :research-answer])
                retrieve-call (some #(when (= "academic-retrieve" (:tool %)) %) @calls)
                retrieved-reference (or (get-in retrieve-call [:args :artifact-reference])
                                        (get-in retrieve-call [:args "artifact-reference"]))]
            (is (= :success (:status result)) (pr-str result))
            (is (= ["academic-search" "academic-retrieve"] (mapv :tool @calls))
                (str "the real model must execute the declared chain exactly once: " (pr-str @calls)))
            (is (= "paper-160" retrieved-reference)
                "the second call uses the artifact reference nested under the search result's :candidates field")
            (is (m/validate answer-schema answer) (pr-str answer))
            (is (= "paper-160" (:artifact-reference answer)))
            (is (= evidence-marker (:evidence-marker answer))
                "the final value uses data available only from the retrieved result")
            (live/assert-live-provenance! ctx (:trace-id result))))))))
