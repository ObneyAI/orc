(ns ai.obney.orc.orc-service.repl-researcher-output-contract-test
  "DET-E2E-157: authoritative write schemas are disclosed to Phase 1."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private structured-schema
  [:map {:closed true}
   [:explanation [:string {:min 1 :max 4000}]]
   [:details
    [:map {:closed true}
     [:source-count :int]
     [:note {:optional true} :string]]]
   [:evidence
    [:vector
     [:map {:closed true}
      [:title :string]]]]
   [:confidence [:or [:enum :high :medium] :double]]])

(deftest det-e2e-157-phase1-receives-exact-declared-write-schemas
  (testing "the public async path gives Phase 1 every authoritative Malli schema"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [captured-module (atom nil)
            definition
            (sheet/workflow "det-e2e-157-output-contract"
              (sheet/blackboard {:question :string
                                 :research-draft structured-schema
                                 :summary :string})
              (sheet/repl-researcher "schema-guided-researcher"
                :instruction "Research the question and return the requested result."
                :reads [:question]
                :writes [:research-draft :summary]
                :rlm {:recursive? false}))
            sheet-id (sheet/build-workflow! ctx definition)
            expected-contract (array-map :research-draft structured-schema
                                         :summary :string)]
        (with-redefs [llm/predict
                      (fn [_ module _ _]
                        (reset! captured-module module)
                        {:outputs
                         {:code
                          (str "(final! {:research-draft "
                               "{:explanation \"Grounded result\" "
                               ":details {:source-count 1} "
                               ":evidence [{:title \"Source\"}] "
                               ":confidence :high} "
                               ":summary \"Grounded result\"})")}
                         :usage {:prompt_tokens 1
                                 :completion_tokens 1
                                 :total_tokens 2}})]
          (let [result (sheet/execute ctx sheet-id {:question "What is known?"}
                                      :timeout-ms 30000)
                instructions (:instructions @captured-module)]
            (is (= :success (:status result)) (pr-str result))
            (is (str/includes? instructions "Authoritative declared write schemas"))
            (is (str/includes? instructions (pr-str expected-contract))
                "nested, closed, optional, collection, union, and scalar schemas remain exact")
            (is (str/includes? instructions
                               "Every value passed to final! MUST satisfy its declared Malli schema"))
            (is (= {:explanation "Grounded result"
                    :details {:source-count 1}
                    :evidence [{:title "Source"}]
                    :confidence :high}
                   (get-in result [:outputs :research-draft])))
            (is (= "Grounded result" (get-in result [:outputs :summary])))))))))
