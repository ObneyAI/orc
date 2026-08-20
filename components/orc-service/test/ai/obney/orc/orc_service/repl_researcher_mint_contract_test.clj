(ns ai.obney.orc.orc-service.repl-researcher-mint-contract-test
  "DET-E2E-163: the available built-in mint affordance has an authoritative contract."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private mint-body
  {:capabilities ["preserve independently verified claims across branches"]
   :strengths [{:trait "retain branch-local evidence until deterministic reconciliation"
                :good-when "independent branches may disagree"
                :recommended-pattern "[:parallel ...] followed by a deterministic reconciliation node"
                :confidence 0.7
                :evidence-count 1}]
   :weaknesses [{:trait "unreconciled branch results can conflict"
                 :avoid-when "there is no deterministic reconciliation rule"
                 :recommended-alternative "add an explicit reconciliation node before finalization"
                 :confidence 0.7
                 :evidence-count 1}]
   :representative-uses ["independent evidence review"]
   :avoid-when ["the branches are order-dependent"]
   :summary "Preserve independently verified claims across branches, then reconcile them deterministically."
   :version 1
   :consolidated-from-event-count 0})

(def ^:private expected-contract
  {:arguments (array-map
               :name :string
               :body ontology-schemas/description-body
               :parent {:optional true :schema [:or :uuid :string]})
   :returns :string})

(deftest det-e2e-163-phase1-always-receives-authoritative-mint-contract
  (testing "classifier-disabled Phase 1 receives the exact contract and can mint durably"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [captured (atom nil)
            definition
            (sheet/workflow "det-e2e-163-mint-contract"
              (sheet/blackboard {:question :string :answer :string})
              (sheet/repl-researcher "mint-contract-researcher"
                :instruction "Contribute the reusable behavior, then answer the question."
                :reads [:question]
                :writes [:answer]
                :rlm {:recursive? true :auto-classify? false}
                :max-iterations 2))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [_ module inputs _]
                        (reset! captured {:module module :inputs inputs})
                        {:outputs
                         {:reasoning "The reusable behavior is novel and should be minted."
                          :code (str "(do (mint-behavior! \"det-e2e-163-evidence-reconciliation\" "
                                     (pr-str mint-body)
                                     " :parent nil) (final! {:answer \"done\"}))")}
                         :usage {:prompt_tokens 1
                                 :completion_tokens 1
                                 :total_tokens 2}})]
          (let [result (sheet/execute ctx sheet-id {:question "What pattern should persist?"}
                                      :timeout-ms 30000)
                inputs (:inputs @captured)
                module (:module @captured)
                audits (into []
                             (comp (filter #(= :ontology/behavioral-subtree-minted
                                               (:event/type %)))
                                   (filter #(= "det-e2e-163-evidence-reconciliation"
                                               (:name %))))
                             (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)}))]
            (is (= expected-contract (:mint-behavior-contract inputs))
                "the actual model input carries the public ontology schema byte-for-byte")
            (is (some #(= :mint-behavior-contract (:name %)) (:inputs module))
                "the Phase-1 module declares the authoritative contract input")
            (is (str/includes? (:instructions module) "mint-behavior!")
                "the module identifies the callable primitive")
            (is (str/includes? (:instructions module) (pr-str expected-contract))
                "the module renders the same authoritative contract supplied as input")
            (is (= :success (:status result)) (pr-str result))
            (is (= "done" (get-in result [:outputs :answer])))
            (is (= 1 (count audits)) (pr-str audits))
            (is (= sheet-id (:minted-by-sheet-id (first audits))))
            (is (= (:trace-id result) (:minted-by-tick-id (first audits))))))))))

(deftest det-e2e-163-unregistered-mint-command-is-not-a-sandbox-affordance
  (testing "a consumer without the optional ontology command cannot see or invoke minting"
    (h/with-test-context [ctx]
      (let [rlm-ctx (rlm-sandbox/build-rlm-context
                     {:provider :test
                      :blackboard {}
                      :declared-writes [:answer]
                      :event-store (:event-store ctx)
                      :tenant-id (:tenant-id ctx)
                      :cache (:cache ctx)
                      :command-registry {}
                      :sheet-id (random-uuid)
                      :tick-id (random-uuid)})
            result (rlm-sandbox/execute-rlm-code
                    rlm-ctx
                    "(mint-behavior! \"must-not-exist\" {})")]
        (is (re-find #"Could not resolve symbol: mint-behavior!"
                     (or (:error result) ""))
            (str "an unregistered optional command must not leave a false SCI binding: "
                 (pr-str result)))))))

(deftest det-e2e-163-malformed-registered-mint-schema-fails-closed
  (testing "a registered command without its authoritative body shape is never advertised"
    (with-redefs [m/form (fn [& _] [:map [:name :string]])]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"mint-behavior.*schema.*name.*body.*parent-behavior"
           (#'executor/registered-mint-behavior-contract
            {:ontology/mint-behavioral-subtree identity}))))))
