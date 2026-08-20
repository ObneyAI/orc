(ns ai.obney.orc.orc-service.repl-researcher-tool-contract-test
  "DET-E2E-159: authoritative bound tool contracts reach Phase 1."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private search-arguments
  [:map {:closed true}
   [:query :string]
   [:filters {:optional true}
    [:map {:closed true}
     [:years [:vector :int]]]]])

(def ^:private search-result
  [:map {:closed true}
   [:status [:enum :succeeded :unavailable]]
   [:candidates
    [:vector
     [:map {:closed true}
      [:source-uri :string]
      [:source-title :string]
      [:artifact-reference [:or :string :uuid]]]]]])

(def ^:private tool-contracts
  {"academic-search" {:arguments search-arguments :result search-result}
   "legacy-tool" {:arguments [:map [:query :string]]}
   "not-bound" {:arguments [:map [:secret :string]] :result :string}})

(defn- events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(deftest det-e2e-159-bound-tool-contracts-survive-and-reach-phase1
  (testing "the public durable path preserves complete contracts and exposes only bound tools"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [captured (atom nil)
            definition
            (sheet/workflow "det-e2e-159-bound-tool-contracts"
              (sheet/blackboard {:question :string :answer :string})
              (sheet/repl-researcher "contract-aware-researcher"
                :instruction "Research the question using the available tools."
                :reads [:question]
                :writes [:answer]
                :mcp-tools ["academic-search" "legacy-tool"]
                :tool-contracts tool-contracts
                :rlm {:recursive? false}))
            sheet-id (sheet/build-workflow! ctx definition)
            node (some #(when (= "contract-aware-researcher" (:name %)) %)
                       (sheet/get-nodes-for-sheet ctx sheet-id))
            config-event (some #(when (= :sheet/repl-researcher-config-set
                                         (:event/type %)) %)
                               (events ctx))
            replayed-node (get (reduce rm/nodes* {} (events ctx)) (:id node))
            expected-bound
            {"academic-search" {:arguments search-arguments :result search-result}
             "legacy-tool" {:arguments [:map [:query :string]] :result :untyped}}]
        (is (= tool-contracts (:tool-contracts node)))
        (is (= tool-contracts (:tool-contracts config-event)))
        (is (= tool-contracts (:tool-contracts replayed-node)))
        (with-redefs [llm/predict
                      (fn [_ module inputs _]
                        (reset! captured {:module module :inputs inputs})
                        {:outputs {:code "(final! {:answer \"done\"})"}
                         :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
          (let [result (sheet/execute ctx sheet-id {:question "What is known?"}
                                      :timeout-ms 30000)
                module (:module @captured)
                inputs (:inputs @captured)]
            (is (= :success (:status result)) (pr-str result))
            (is (= expected-bound (:tool-contracts inputs))
                "the actual model input retains exact schemas and labels the absent result untyped")
            (is (some #(= :tool-contracts (:name %)) (:inputs module)))
            (is (str/includes? (:instructions module) (pr-str expected-bound)))
            (is (not (str/includes? (:instructions module) "not-bound")))
            (is (str/includes? (:instructions module)
                               "authoritative argument and result schemas"))))))))

(deftest det-e2e-159-untyped-and-legacy-tools-remain-compatible
  (testing "tools without contracts are explicit and old argument-key declarations still work"
    (let [build-module #'executor/build-rlm-code-generation-module
          base {:type :repl-researcher
                :instruction "Use tools."
                :writes [:answer]
                :mcp-tools ["untyped" "legacy"]
                :options {:tool-arg-specs {"legacy" ["query"]}}}
          module (build-module base "" [] {} {} {} (:mcp-tools base))
          instructions (:instructions module)]
      (is (str/includes? instructions
                         (pr-str {"untyped" {:arguments :untyped :result :untyped}
                                  "legacy" {:arguments [:map ["query" :any]]
                                            :result :untyped}})))
      (is (str/includes? instructions "(legacy {\"query\" \"...\"})"))
      (is (str/includes? instructions "(untyped {\"arg\" \"value\"})")))))
