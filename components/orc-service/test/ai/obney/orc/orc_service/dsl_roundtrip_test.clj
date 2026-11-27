(ns ai.obney.orc.orc-service.dsl-roundtrip-test
  "Tests for deterministic round-trip DSL code generation.

   Verifies that: build-workflow! -> export-sheet -> export-to-dsl -> eval -> build-workflow!
   produces structurally identical behavior trees.

   All nodes must have names for stable identity (UUID v5)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.commands :as service-commands]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- normalize-node
  "Remove non-deterministic fields from a node for comparison."
  [node]
  (when node
    (-> node
        (dissoc :id)
        (update :children #(when % (mapv normalize-node %))))))

(defn- normalize-export
  "Remove non-deterministic fields from an export for comparison."
  [exported]
  (-> exported
      (dissoc :exported-at :version)
      (update :sheet dissoc :id)
      (update :nodes normalize-node)))

(def ^:private researcher-roundtrip-fields
  [:model :instruction :reads :writes :mcp-tools :tool-contracts
   :tool-caller-fn :browser-tools :max-iterations :rlm :context :options])

(def ^:private delegate-roundtrip-fields
  [:target-sheet-id :reads :writes :timeout-ms :max-ticks :inherit-ontology?])

;; =============================================================================
;; Simple Round-Trip Tests
;; =============================================================================

(deftest det-e2e-164-researcher-configuration-survives-public-roundtrips
  (testing "researcher gates, contracts, context, and options survive export, DSL, and import"
    (h/with-test-context [ctx]
      (let [tool-contracts {"search" {:arguments [:map [:query :string]]
                                       :result [:map [:hits [:vector :string]]]}}
            expected {:model "google/gemini-3.6-flash"
                      :instruction "Research with the configured capabilities."
                      :reads [:question]
                      :writes [:answer :optional-note]
                      :mcp-tools ["search"]
                      :tool-contracts tool-contracts
                      :tool-caller-fn "consumer.security/build-tool-caller"
                      :browser-tools ["open" "snapshot"]
                      :max-iterations 4
                      :rlm {:recursive? true :auto-classify? false}
                      :context {:problem-type "problem:Research"
                                :self-learning? true}
                      :options {:optional-writes [:optional-note]
                                :tool-arg-specs {"search" ["query"]}}}
            definition (service-logic/workflow "det-e2e-164-researcher-roundtrip"
                         (service-logic/dsl-blackboard {:question :string
                                          :answer :string
                                          :optional-note :string})
                         (apply service-logic/repl-researcher "researcher"
                                (mapcat identity expected)))
            source-id (service-commands/build-workflow! ctx definition)
            exported (service-commands/export-sheet ctx source-id)
            exported-node (:nodes exported)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                          (eval (read-string dsl-code)))
            regenerated-node (:root-node regenerated)
            imported-id (service-commands/import-sheet
                         ctx
                         (assoc-in exported [:sheet :name]
                                   "det-e2e-164-researcher-imported"))
            imported-node (:nodes (service-commands/export-sheet ctx imported-id))]
        (is (= expected (select-keys exported-node researcher-roundtrip-fields))
            "exported EDN preserves the complete public researcher configuration")
        (is (= expected (select-keys regenerated-node researcher-roundtrip-fields))
            "rendered and evaluated public DSL preserves the same configuration")
        (is (= expected (select-keys imported-node researcher-roundtrip-fields))
            "EDN import persists the same configuration through commands and projection")))))

(deftest det-e2e-164-explicit-empty-researcher-choices-survive-public-roundtrips
  (testing "empty recursive configuration and context remain explicit choices"
    (h/with-test-context [ctx]
      (let [expected {:rlm {} :context {}}
            definition (service-logic/workflow "det-e2e-164-empty-researcher-choices"
                         (service-logic/dsl-blackboard {:question :string :answer :string})
                         (service-logic/repl-researcher "researcher"
                           :instruction "Research without injected context."
                           :reads [:question]
                           :writes [:answer]
                           :rlm {}
                           :context {}))
            source-id (service-commands/build-workflow! ctx definition)
            exported (service-commands/export-sheet ctx source-id)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                          (eval (read-string dsl-code)))
            imported-id (service-commands/import-sheet
                         ctx
                         (assoc-in exported [:sheet :name]
                                   "det-e2e-164-empty-researcher-choices-imported"))]
        (is (= expected (select-keys (:nodes exported) [:rlm :context]))
            "EDN export retains explicit empty researcher choices")
        (is (= expected (select-keys (:root-node regenerated) [:rlm :context]))
            "rendered and evaluated DSL retains explicit empty researcher choices")
        (is (= expected
               (select-keys (:nodes (service-commands/export-sheet ctx imported-id)) [:rlm :context]))
            "EDN import persists explicit empty researcher choices")))))

(deftest det-e2e-171-delegate-configuration-survives-public-roundtrips
  (testing "delegate target, mappings, budgets, and ontology choice survive every public transport"
    (h/with-test-context [ctx]
      (let [target-id (service-commands/build-workflow!
                       ctx
                       (service-logic/workflow "det-e2e-171-target"
                         (service-logic/dsl-blackboard {:input :string :output :string})
                         (service-logic/code "copy" :fn "consumer.delegate/copy"
                           :reads [:input] :writes [:output])))
            expected {:target-sheet-id target-id
                      :reads [:input]
                      :writes [:output]
                      :timeout-ms 4321
                      :max-ticks 17
                      :inherit-ontology? false}
            definition (service-logic/workflow "det-e2e-171-delegate-roundtrip"
                         (service-logic/dsl-blackboard {:input :string :output :string})
                         (apply service-logic/delegate "child" (mapcat identity expected)))
            source-id (service-commands/build-workflow! ctx definition)
            exported (service-commands/export-sheet ctx source-id)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                          (eval (read-string dsl-code)))
            imported-id (service-commands/import-sheet
                         ctx
                         (assoc-in exported [:sheet :name]
                                   "det-e2e-171-delegate-imported"))
            imported-node (:nodes (service-commands/export-sheet ctx imported-id))]
        (is (= expected (select-keys (:nodes exported) delegate-roundtrip-fields)))
        (is (= expected (select-keys (:root-node regenerated) delegate-roundtrip-fields)))
        (is (= expected (select-keys imported-node delegate-roundtrip-fields)))
        (is (= expected
               (select-keys (:root-node definition) delegate-roundtrip-fields))))))
  (testing "omitted limits and empty mappings remain omitted and empty"
    (let [target-id (random-uuid)
          node (service-logic/delegate "empty" :target-sheet-id target-id)]
      (is (= {:target-sheet-id target-id
              :reads []
              :writes []
              :inherit-ontology? true}
             (select-keys node delegate-roundtrip-fields)))
      (is (not (contains? node :timeout-ms)))
      (is (not (contains? node :max-ticks))))))

(def ^:private decision-roundtrip-fields
  [:executor :model :instruction :reads :writes :options-from :min-confidence
   :abstain :retry :options])

(deftest llm-decision-configuration-survives-public-roundtrips
  (testing "every llm-decision option survives build -> export -> DSL -> eval and export -> import"
    (h/with-test-context [ctx]
      (let [expected {:executor :decision
                      :model "vendor/router"
                      :instruction "Which listed report is requested?"
                      :reads [:request :catalog]
                      :writes [:report]
                      :options-from :catalog
                      :min-confidence 0.7
                      :abstain "none"
                      :retry {:max-attempts 2 :backoff-ms [100 200]}
                      :options {:temperature 0.0}}
            definition (service-logic/workflow "llm-decision-roundtrip"
                         (service-logic/dsl-blackboard
                          {:request :string
                           :catalog [:vector [:map [:id :string] [:description :string]]]
                           :report :string})
                         (service-logic/llm-decision "pick-report"
                           :model "vendor/router"
                           :instruction "Which listed report is requested?"
                           :reads [:request :catalog]
                           :writes [:report]
                           :options-from :catalog
                           :min-confidence 0.7
                           :abstain "none"
                           :retry {:max-attempts 2 :backoff-ms [100 200]}
                           :options {:temperature 0.0}))
            source-id (service-commands/build-workflow! ctx definition)
            exported (service-commands/export-sheet ctx source-id)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                          (eval (read-string dsl-code)))
            imported-id (service-commands/import-sheet
                         ctx
                         (assoc-in exported [:sheet :name] "llm-decision-imported"))
            imported-node (:nodes (service-commands/export-sheet ctx imported-id))]
        (is (= expected (select-keys (:nodes exported) decision-roundtrip-fields)))
        (is (= expected (select-keys (:root-node regenerated) decision-roundtrip-fields)))
        (is (= expected (select-keys imported-node decision-roundtrip-fields)))
        (is (re-find #"llm-decision" dsl-code)
            "the round trip reproduces sheet/llm-decision, not a plain llm leaf"))))
  (testing "a static enum decision round-trips with no optional fields"
    (h/with-test-context [ctx]
      (let [definition (service-logic/workflow "llm-decision-roundtrip-enum"
                         (service-logic/dsl-blackboard {:request :string
                                          :route [:enum {:descriptions {"a" "Alpha" "b" "Beta"}} "a" "b"]})
                         (service-logic/llm-decision "route" :instruction "Pick one."
                           :reads [:request] :writes [:route]))
            source-id (service-commands/build-workflow! ctx definition)
            exported (service-commands/export-sheet ctx source-id)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                          (eval (read-string (service-logic/export-to-dsl exported))))
            expected {:executor :decision :instruction "Pick one."
                      :reads [:request] :writes [:route]}]
        (is (= expected (select-keys (:nodes exported) decision-roundtrip-fields)))
        (is (= expected (->> (select-keys (:root-node regenerated) decision-roundtrip-fields)
                             (remove (comp nil? val))
                             (into {}))))))))

(deftest simple-llm-workflow-roundtrip-test
  (testing "simple LLM workflow survives round-trip"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "simple-llm"
                           (service-logic/dsl-blackboard {:input :string
                                            :output :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process the input"
                               :reads [:input]
                               :writes [:output])))
            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)

            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (service-commands/build-workflow! ctx regenerated-def)

            export-1 (service-commands/export-sheet ctx sheet-id-1)
            export-2 (service-commands/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2)
            "Same workflow should produce same sheet-id")
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "Round-trip should produce structurally identical workflow")))))

(deftest simple-code-workflow-roundtrip-test
  (testing "simple code executor workflow survives round-trip"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "simple-code"
                           (service-logic/dsl-blackboard {:x :int
                                            :result :int})
                           (service-logic/sequence "main"
                             (service-logic/code "compute"
                               :fn "some.ns/compute-fn"
                               :reads [:x]
                               :writes [:result])))
            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (service-commands/build-workflow! ctx regenerated-def)

            export-1 (service-commands/export-sheet ctx sheet-id-1)
            export-2 (service-commands/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2)))))))

;; =============================================================================
;; All Node Types Test
;; =============================================================================

(deftest all-node-types-roundtrip-test
  (testing "workflow with all node types survives round-trip"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "comprehensive"
                           (service-logic/dsl-blackboard {:input :string
                                            :items [:vector [:map [:value :string]]]
                                            :flag :boolean
                                            :result :string
                                            :score :int
                                            :item [:map [:value :string]]
                                            :item-result :string
                                            :results [:vector :string]})
                           (service-logic/sequence "main"
                             (service-logic/llm "ai-step"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process input and extract items"
                               :reads [:input]
                               :writes [:items])

                             (service-logic/code "code-step"
                               :fn "my.ns/transform"
                               :reads [:items]
                               :writes [:items])

                             (service-logic/condition "check-flag"
                               :check {:key :flag :op :truthy})

                             (service-logic/llm-condition "llm-check"
                               :model "google/gemini-2.5-flash"
                               :instruction "Should we continue?"
                               :reads [:items])

                             (service-logic/fallback "try-options"
                               (service-logic/llm "option-1"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Try option 1"
                                 :reads [:items]
                                 :writes [:result])
                               (service-logic/llm "option-2"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Try option 2"
                                 :reads [:items]
                                 :writes [:result]))

                             (service-logic/parallel "scoring" {:success-policy :any}
                               (service-logic/llm "parallel-a"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Path A"
                                 :reads [:items]
                                 :writes [:score])
                               (service-logic/llm "parallel-b"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Path B"
                                 :reads [:items]
                                 :writes [:score]))

                             (service-logic/map-each "process-items"
                               :from :items
                               :as :item
                               :into :results
                               :parallel 3
                               (service-logic/llm "process"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Process item"
                                 :reads [:item]
                                 :writes [:item-result]))))

            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (service-commands/build-workflow! ctx regenerated-def)

            export-1 (service-commands/export-sheet ctx sheet-id-1)
            export-2 (service-commands/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2)
            "Same workflow should produce same sheet-id")
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "All node types should survive round-trip")))))

;; =============================================================================
;; Idempotent Build Tests
;; =============================================================================

(deftest build-workflow-idempotent-test
  (testing "building same workflow twice returns same sheet-id"
    (h/with-test-context [ctx]
      (let [workflow-def (service-logic/workflow "idempotent-workflow"
                           (service-logic/dsl-blackboard {:input :string :output :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "step"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process"
                               :reads [:input]
                               :writes [:output])))
            sheet-id-1 (service-commands/build-workflow! ctx workflow-def)
            sheet-id-2 (service-commands/build-workflow! ctx workflow-def)]
        (is (= sheet-id-1 sheet-id-2)
            "Same workflow definition should return same sheet-id")))))

(deftest build-workflow-update-preserves-id-test
  (testing "modifying workflow and rebuilding preserves sheet-id"
    (h/with-test-context [ctx]
      (let [workflow-v1 (service-logic/workflow "evolving-workflow"
                          (service-logic/dsl-blackboard {:input :string :output :string})
                          (service-logic/sequence "main"
                            (service-logic/llm "step-1"
                              :model "google/gemini-2.5-flash"
                              :instruction "Version 1"
                              :reads [:input]
                              :writes [:output])))
            workflow-v2 (service-logic/workflow "evolving-workflow"
                          (service-logic/dsl-blackboard {:input :string :output :string
                                           :final :string :extra :int})
                          (service-logic/sequence "main"
                            (service-logic/llm "step-1"
                              :model "google/gemini-2.5-flash"
                              :instruction "Version 2 - modified"
                              :reads [:input]
                              :writes [:output])
                            (service-logic/llm "step-2"
                              :model "google/gemini-2.5-flash"
                              :instruction "New step"
                              :reads [:output]
                              :writes [:final])))
            sheet-id-1 (service-commands/build-workflow! ctx workflow-v1)
            sheet-id-2 (service-commands/build-workflow! ctx workflow-v2)]
        (is (= sheet-id-1 sheet-id-2)
            "Updated workflow should preserve sheet-id")

        (let [exported (service-commands/export-sheet ctx sheet-id-2)
              bb-keys (set (keys (:blackboard-schema exported)))]
          (is (contains? bb-keys :extra)
              "Blackboard should have new key")
          (is (= 2 (count (get-in exported [:nodes :children])))
              "Should have 2 children now"))))))

(deftest different-workflows-get-different-ids-test
  (testing "different workflow names get different sheet-ids"
    (h/with-test-context [ctx]
      (let [workflow-a (service-logic/workflow "workflow-a"
                         (service-logic/dsl-blackboard {:x :int})
                         (service-logic/sequence "main"))
            workflow-b (service-logic/workflow "workflow-b"
                         (service-logic/dsl-blackboard {:x :int})
                         (service-logic/sequence "main"))
            sheet-id-a (service-commands/build-workflow! ctx workflow-a)
            sheet-id-b (service-commands/build-workflow! ctx workflow-b)]
        (is (not= sheet-id-a sheet-id-b)
            "Different workflow names should get different sheet-ids")))))

(deftest full-roundtrip-preserves-identity-test
  (testing "export -> modify -> import preserves sheet identity"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "roundtrip-identity"
                           (service-logic/dsl-blackboard {:data :string :result :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Original"
                               :reads [:data]
                               :writes [:result])))
            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                          (eval (read-string dsl-code)))

            sheet-id-2 (service-commands/build-workflow! ctx regenerated)]

        (is (= sheet-id-1 sheet-id-2)
            "Round-trip should preserve sheet identity")))))

;; =============================================================================
;; Deterministic Node ID Tests
;; =============================================================================

(deftest node-ids-are-deterministic-test
  (testing "same node names produce same node IDs across rebuilds"
    (h/with-test-context [ctx]
      (let [workflow-def (service-logic/workflow "deterministic-nodes"
                           (service-logic/dsl-blackboard {:x :int :y :int :z :int})
                           (service-logic/sequence "main"
                             (service-logic/llm "step-a"
                               :model "google/gemini-2.5-flash"
                               :instruction "Step A"
                               :reads [:x]
                               :writes [:y])
                             (service-logic/llm "step-b"
                               :model "google/gemini-2.5-flash"
                               :instruction "Step B"
                               :reads [:y]
                               :writes [:z])))
            _ (service-commands/build-workflow! ctx workflow-def)
            export-1 (service-commands/export-sheet ctx (service-logic/sheet-id-for-name "deterministic-nodes"))

            _ (service-commands/build-workflow! ctx workflow-def)
            export-2 (service-commands/export-sheet ctx (service-logic/sheet-id-for-name "deterministic-nodes"))

            get-node-ids (fn [exp]
                           (let [nodes (:nodes exp)]
                             {:root (:id nodes)
                              :step-a (-> nodes :children first :id)
                              :step-b (-> nodes :children second :id)}))]

        (is (= (get-node-ids export-1) (get-node-ids export-2))
            "Node IDs should be identical across rebuilds")))))

;; =============================================================================
;; Idempotence Tests
;; =============================================================================

(deftest dsl-generation-idempotent-test
  (testing "generating DSL twice produces identical output"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "idempotent-test"
                           (service-logic/dsl-blackboard {:input :string :output :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "step"
                               :model "google/gemini-2.5-flash"
                               :instruction "Do something"
                               :reads [:input]
                               :writes [:output])))
            sheet-id (service-commands/build-workflow! ctx original-def)
            exported (service-commands/export-sheet ctx sheet-id)
            dsl-1 (service-logic/export-to-dsl exported)
            dsl-2 (service-logic/export-to-dsl exported)]
        (is (= dsl-1 dsl-2)
            "DSL generation should be deterministic")))))

;; =============================================================================
;; Valid Clojure Test
;; =============================================================================

(deftest generated-code-is-valid-clojure-test
  (testing "generated DSL is parseable Clojure"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "valid-clojure"
                           (service-logic/dsl-blackboard {:data :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process data"
                               :reads [:data]
                               :writes [:data])))
            sheet-id (service-commands/build-workflow! ctx original-def)
            exported (service-commands/export-sheet ctx sheet-id)
            dsl-code (service-logic/export-to-dsl exported)]
        (is (some? (read-string dsl-code))
            "Generated code should be valid Clojure")))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest empty-reads-writes-test
  (testing "handles nodes with empty reads/writes"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "empty-io"
                           (service-logic/dsl-blackboard {:result :string})
                           (service-logic/sequence "main"
                             (service-logic/code "no-input"
                               :fn "my.ns/generate"
                               :writes [:result])))
            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (service-commands/build-workflow! ctx regenerated-def)

            export-1 (service-commands/export-sheet ctx sheet-id-1)
            export-2 (service-commands/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2)))))))

(deftest special-characters-in-strings-test
  (testing "handles special characters in instructions"
    (h/with-test-context [ctx]
      (let [instruction "Process \"quoted text\" and handle:\n- newlines\n- tabs\t\n- backslashes \\ etc."
            original-def (service-logic/workflow "special-chars"
                           (service-logic/dsl-blackboard {:input :string :output :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction instruction
                               :reads [:input]
                               :writes [:output])))
            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (service-commands/build-workflow! ctx regenerated-def)

            export-1 (service-commands/export-sheet ctx sheet-id-1)
            export-2 (service-commands/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "Special characters should be properly escaped and round-trip")))))

(deftest deeply-nested-tree-test
  (testing "handles deeply nested workflow trees"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "deep-nesting"
                           (service-logic/dsl-blackboard {:data :string})
                           (service-logic/sequence "root"
                             (service-logic/fallback "level-1"
                               (service-logic/sequence "level-2"
                                 (service-logic/parallel "level-3"
                                   (service-logic/sequence "level-4a"
                                     (service-logic/llm "deep-1"
                                       :model "google/gemini-2.5-flash"
                                       :instruction "Level 5"
                                       :reads [:data]
                                       :writes [:data]))
                                   (service-logic/llm "deep-2"
                                     :model "google/gemini-2.5-flash"
                                     :instruction "Level 4 parallel"
                                     :reads [:data]
                                     :writes [:data])))
                               (service-logic/llm "fallback"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Fallback option"
                                 :reads [:data]
                                 :writes [:data]))))
            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (service-commands/build-workflow! ctx regenerated-def)

            export-1 (service-commands/export-sheet ctx sheet-id-1)
            export-2 (service-commands/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "Deeply nested trees should survive round-trip")))))

(deftest retry-config-test
  (testing "handles retry configuration"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "retry-test"
                           (service-logic/dsl-blackboard {:input :string :output :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "with-retry"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process with retry"
                               :reads [:input]
                               :writes [:output]
                               :retry {:max-attempts 3 :backoff-ms [100 500 1000]})))
            sheet-id-1 (service-commands/build-workflow! ctx original-def)

            exported (service-commands/export-sheet ctx sheet-id-1)
            dsl-code (service-logic/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (service-commands/build-workflow! ctx regenerated-def)

            export-1 (service-commands/export-sheet ctx sheet-id-1)
            export-2 (service-commands/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "Retry configuration should survive round-trip")))))

;; =============================================================================
;; Pretty Printing Test
;; =============================================================================

(deftest pretty-printing-test
  (testing "pretty-printed output is readable"
    (h/with-test-context [ctx]
      (let [original-def (service-logic/workflow "pretty-test"
                           (service-logic/dsl-blackboard {:input :string :output :string})
                           (service-logic/sequence "main"
                             (service-logic/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process input"
                               :reads [:input]
                               :writes [:output])))
            sheet-id (service-commands/build-workflow! ctx original-def)
            exported (service-commands/export-sheet ctx sheet-id)
            dsl-code (service-logic/export-to-dsl exported :pretty? true)]

        (is (clojure.string/includes? dsl-code "\n")
            "Pretty output should have newlines")
        (is (clojure.string/includes? dsl-code "(workflow")
            "Should start with workflow")
        (is (clojure.string/includes? dsl-code "(blackboard")
            "Should contain blackboard")
        (is (clojure.string/includes? dsl-code "(sequence")
            "Should contain sequence")))))
