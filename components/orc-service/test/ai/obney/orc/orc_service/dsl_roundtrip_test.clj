(ns ai.obney.orc.orc-service.dsl-roundtrip-test
  "Tests for deterministic round-trip DSL code generation.

   Verifies that: build-workflow! -> export-sheet -> export-to-dsl -> eval -> build-workflow!
   produces structurally identical behavior trees.

   All nodes must have names for stable identity (UUID v5)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]))

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

;; =============================================================================
;; Simple Round-Trip Tests
;; =============================================================================

(deftest simple-llm-workflow-roundtrip-test
  (testing "simple LLM workflow survives round-trip"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "simple-llm"
                           (dsl/blackboard {:input :string
                                            :output :string})
                           (dsl/sequence "main"
                             (dsl/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process the input"
                               :reads [:input]
                               :writes [:output])))
            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)

            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (dsl/build-workflow! ctx regenerated-def)

            export-1 (dsl/export-sheet ctx sheet-id-1)
            export-2 (dsl/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2)
            "Same workflow should produce same sheet-id")
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "Round-trip should produce structurally identical workflow")))))

(deftest simple-code-workflow-roundtrip-test
  (testing "simple code executor workflow survives round-trip"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "simple-code"
                           (dsl/blackboard {:x :int
                                            :result :int})
                           (dsl/sequence "main"
                             (dsl/code "compute"
                               :fn "some.ns/compute-fn"
                               :reads [:x]
                               :writes [:result])))
            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (dsl/build-workflow! ctx regenerated-def)

            export-1 (dsl/export-sheet ctx sheet-id-1)
            export-2 (dsl/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2)))))))

;; =============================================================================
;; All Node Types Test
;; =============================================================================

(deftest all-node-types-roundtrip-test
  (testing "workflow with all node types survives round-trip"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "comprehensive"
                           (dsl/blackboard {:input :string
                                            :items [:vector :map]
                                            :flag :boolean
                                            :result :any
                                            :score :int
                                            :item :map
                                            :item-result :any
                                            :results [:vector :any]})
                           (dsl/sequence "main"
                             ;; LLM node
                             (dsl/llm "ai-step"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process input and extract items"
                               :reads [:input]
                               :writes [:items])

                             ;; Code node
                             (dsl/code "code-step"
                               :fn "my.ns/transform"
                               :reads [:items]
                               :writes [:items])

                             ;; Condition node
                             (dsl/condition "check-flag"
                               :check {:key :flag :op :truthy})

                             ;; LLM condition node
                             (dsl/llm-condition "llm-check"
                               :model "google/gemini-2.5-flash"
                               :instruction "Should we continue?"
                               :reads [:items])

                             ;; Fallback node
                             (dsl/fallback "try-options"
                               (dsl/llm "option-1"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Try option 1"
                                 :reads [:items]
                                 :writes [:result])
                               (dsl/llm "option-2"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Try option 2"
                                 :reads [:items]
                                 :writes [:result]))

                             ;; Parallel node with custom policies
                             (dsl/parallel "scoring" {:success-policy :any}
                               (dsl/llm "parallel-a"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Path A"
                                 :reads [:items]
                                 :writes [:score])
                               (dsl/llm "parallel-b"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Path B"
                                 :reads [:items]
                                 :writes [:score]))

                             ;; Map-each node
                             (dsl/map-each "process-items"
                               :from :items
                               :as :item
                               :into :results
                               :parallel 3
                               (dsl/llm "process"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Process item"
                                 :reads [:item]
                                 :writes [:item-result]))))

            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (dsl/build-workflow! ctx regenerated-def)

            export-1 (dsl/export-sheet ctx sheet-id-1)
            export-2 (dsl/export-sheet ctx sheet-id-2)]

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
      (let [workflow-def (dsl/workflow "idempotent-workflow"
                           (dsl/blackboard {:input :string :output :string})
                           (dsl/sequence "main"
                             (dsl/llm "step"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process"
                               :reads [:input]
                               :writes [:output])))
            sheet-id-1 (dsl/build-workflow! ctx workflow-def)
            sheet-id-2 (dsl/build-workflow! ctx workflow-def)]
        (is (= sheet-id-1 sheet-id-2)
            "Same workflow definition should return same sheet-id")))))

(deftest build-workflow-update-preserves-id-test
  (testing "modifying workflow and rebuilding preserves sheet-id"
    (h/with-test-context [ctx]
      (let [workflow-v1 (dsl/workflow "evolving-workflow"
                          (dsl/blackboard {:input :string :output :string})
                          (dsl/sequence "main"
                            (dsl/llm "step-1"
                              :model "google/gemini-2.5-flash"
                              :instruction "Version 1"
                              :reads [:input]
                              :writes [:output])))
            workflow-v2 (dsl/workflow "evolving-workflow"
                          (dsl/blackboard {:input :string :output :string
                                           :final :string :extra :int})
                          (dsl/sequence "main"
                            (dsl/llm "step-1"
                              :model "google/gemini-2.5-flash"
                              :instruction "Version 2 - modified"
                              :reads [:input]
                              :writes [:output])
                            (dsl/llm "step-2"
                              :model "google/gemini-2.5-flash"
                              :instruction "New step"
                              :reads [:output]
                              :writes [:final])))
            sheet-id-1 (dsl/build-workflow! ctx workflow-v1)
            sheet-id-2 (dsl/build-workflow! ctx workflow-v2)]
        (is (= sheet-id-1 sheet-id-2)
            "Updated workflow should preserve sheet-id")

        ;; Verify the content was actually updated
        (let [exported (dsl/export-sheet ctx sheet-id-2)
              bb-keys (set (keys (:blackboard-schema exported)))]
          (is (contains? bb-keys :extra)
              "Blackboard should have new key")
          (is (= 2 (count (get-in exported [:nodes :children])))
              "Should have 2 children now"))))))

(deftest different-workflows-get-different-ids-test
  (testing "different workflow names get different sheet-ids"
    (h/with-test-context [ctx]
      (let [workflow-a (dsl/workflow "workflow-a"
                         (dsl/blackboard {:x :int})
                         (dsl/sequence "main"))
            workflow-b (dsl/workflow "workflow-b"
                         (dsl/blackboard {:x :int})
                         (dsl/sequence "main"))
            sheet-id-a (dsl/build-workflow! ctx workflow-a)
            sheet-id-b (dsl/build-workflow! ctx workflow-b)]
        (is (not= sheet-id-a sheet-id-b)
            "Different workflow names should get different sheet-ids")))))

(deftest full-roundtrip-preserves-identity-test
  (testing "export -> modify -> import preserves sheet identity"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "roundtrip-identity"
                           (dsl/blackboard {:data :string :result :any})
                           (dsl/sequence "main"
                             (dsl/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Original"
                               :reads [:data]
                               :writes [:result])))
            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                          (eval (read-string dsl-code)))

            sheet-id-2 (dsl/build-workflow! ctx regenerated)]

        (is (= sheet-id-1 sheet-id-2)
            "Round-trip should preserve sheet identity")))))

;; =============================================================================
;; Deterministic Node ID Tests
;; =============================================================================

(deftest node-ids-are-deterministic-test
  (testing "same node names produce same node IDs across rebuilds"
    (h/with-test-context [ctx]
      (let [workflow-def (dsl/workflow "deterministic-nodes"
                           (dsl/blackboard {:x :int :y :int :z :int})
                           (dsl/sequence "main"
                             (dsl/llm "step-a"
                               :model "google/gemini-2.5-flash"
                               :instruction "Step A"
                               :reads [:x]
                               :writes [:y])
                             (dsl/llm "step-b"
                               :model "google/gemini-2.5-flash"
                               :instruction "Step B"
                               :reads [:y]
                               :writes [:z])))
            ;; Build twice
            _ (dsl/build-workflow! ctx workflow-def)
            export-1 (dsl/export-sheet ctx (dsl/sheet-id-for-name "deterministic-nodes"))

            _ (dsl/build-workflow! ctx workflow-def)
            export-2 (dsl/export-sheet ctx (dsl/sheet-id-for-name "deterministic-nodes"))

            ;; Extract node IDs
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
      (let [original-def (dsl/workflow "idempotent-test"
                           (dsl/blackboard {:input :string :output :string})
                           (dsl/sequence "main"
                             (dsl/llm "step"
                               :model "google/gemini-2.5-flash"
                               :instruction "Do something"
                               :reads [:input]
                               :writes [:output])))
            sheet-id (dsl/build-workflow! ctx original-def)
            exported (dsl/export-sheet ctx sheet-id)
            dsl-1 (dsl/export-to-dsl exported)
            dsl-2 (dsl/export-to-dsl exported)]
        (is (= dsl-1 dsl-2)
            "DSL generation should be deterministic")))))

;; =============================================================================
;; Valid Clojure Test
;; =============================================================================

(deftest generated-code-is-valid-clojure-test
  (testing "generated DSL is parseable Clojure"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "valid-clojure"
                           (dsl/blackboard {:data :any})
                           (dsl/sequence "main"
                             (dsl/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process data"
                               :reads [:data]
                               :writes [:data])))
            sheet-id (dsl/build-workflow! ctx original-def)
            exported (dsl/export-sheet ctx sheet-id)
            dsl-code (dsl/export-to-dsl exported)]
        (is (some? (read-string dsl-code))
            "Generated code should be valid Clojure")))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest empty-reads-writes-test
  (testing "handles nodes with empty reads/writes"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "empty-io"
                           (dsl/blackboard {:result :string})
                           (dsl/sequence "main"
                             (dsl/code "no-input"
                               :fn "my.ns/generate"
                               :writes [:result])))
            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (dsl/build-workflow! ctx regenerated-def)

            export-1 (dsl/export-sheet ctx sheet-id-1)
            export-2 (dsl/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2)))))))

(deftest special-characters-in-strings-test
  (testing "handles special characters in instructions"
    (h/with-test-context [ctx]
      (let [instruction "Process \"quoted text\" and handle:\n- newlines\n- tabs\t\n- backslashes \\ etc."
            original-def (dsl/workflow "special-chars"
                           (dsl/blackboard {:input :string :output :string})
                           (dsl/sequence "main"
                             (dsl/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction instruction
                               :reads [:input]
                               :writes [:output])))
            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (dsl/build-workflow! ctx regenerated-def)

            export-1 (dsl/export-sheet ctx sheet-id-1)
            export-2 (dsl/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "Special characters should be properly escaped and round-trip")))))

(deftest deeply-nested-tree-test
  (testing "handles deeply nested workflow trees"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "deep-nesting"
                           (dsl/blackboard {:data :any})
                           (dsl/sequence "root"
                             (dsl/fallback "level-1"
                               (dsl/sequence "level-2"
                                 (dsl/parallel "level-3"
                                   (dsl/sequence "level-4a"
                                     (dsl/llm "deep-1"
                                       :model "google/gemini-2.5-flash"
                                       :instruction "Level 5"
                                       :reads [:data]
                                       :writes [:data]))
                                   (dsl/llm "deep-2"
                                     :model "google/gemini-2.5-flash"
                                     :instruction "Level 4 parallel"
                                     :reads [:data]
                                     :writes [:data])))
                               (dsl/llm "fallback"
                                 :model "google/gemini-2.5-flash"
                                 :instruction "Fallback option"
                                 :reads [:data]
                                 :writes [:data]))))
            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (dsl/build-workflow! ctx regenerated-def)

            export-1 (dsl/export-sheet ctx sheet-id-1)
            export-2 (dsl/export-sheet ctx sheet-id-2)]

        (is (= sheet-id-1 sheet-id-2))
        (is (= (normalize-export export-1)
               (normalize-export export-2))
            "Deeply nested trees should survive round-trip")))))

(deftest retry-config-test
  (testing "handles retry configuration"
    (h/with-test-context [ctx]
      (let [original-def (dsl/workflow "retry-test"
                           (dsl/blackboard {:input :string :output :string})
                           (dsl/sequence "main"
                             (dsl/llm "with-retry"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process with retry"
                               :reads [:input]
                               :writes [:output]
                               :retry {:max-attempts 3 :backoff-ms [100 500 1000]})))
            sheet-id-1 (dsl/build-workflow! ctx original-def)

            exported (dsl/export-sheet ctx sheet-id-1)
            dsl-code (dsl/export-to-dsl exported)
            regenerated-def (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                              (eval (read-string dsl-code)))
            sheet-id-2 (dsl/build-workflow! ctx regenerated-def)

            export-1 (dsl/export-sheet ctx sheet-id-1)
            export-2 (dsl/export-sheet ctx sheet-id-2)]

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
      (let [original-def (dsl/workflow "pretty-test"
                           (dsl/blackboard {:input :string :output :string})
                           (dsl/sequence "main"
                             (dsl/llm "process"
                               :model "google/gemini-2.5-flash"
                               :instruction "Process input"
                               :reads [:input]
                               :writes [:output])))
            sheet-id (dsl/build-workflow! ctx original-def)
            exported (dsl/export-sheet ctx sheet-id)
            dsl-code (dsl/export-to-dsl exported :pretty? true)]

        (is (clojure.string/includes? dsl-code "\n")
            "Pretty output should have newlines")
        (is (clojure.string/includes? dsl-code "(workflow")
            "Should start with workflow")
        (is (clojure.string/includes? dsl-code "(blackboard")
            "Should contain blackboard")
        (is (clojure.string/includes? dsl-code "(sequence")
            "Should contain sequence")))))
