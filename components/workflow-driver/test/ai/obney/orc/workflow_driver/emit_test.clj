(ns ai.obney.orc.workflow-driver.emit-test
  "Verify submit-tree!: parsing, name-matching, idempotence, and the
   structural diff that downstream agent prompts rely on."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.workflow-driver.interface :as driver]))

(def ^:private base-form
  "(workflow \"emit-test-sheet\"
     (blackboard
       {:documents [:vector :string]
        :doc-summaries [:vector :string]
        :analysis :map})
     (sequence \"main\"
       (code \"survey\"
         :fn \"fake.ns/survey\"
         :reads [:documents]
         :writes [:doc-summaries])
       (llm \"synthesize\"
         :model \"google/gemini-2.5-flash\"
         :instruction \"Synthesize the per-document summaries.\"
         :reads [:doc-summaries]
         :writes [:analysis])))")

(defn- bootstrap-sheet! [ctx]
  (dsl/build-workflow! ctx
    (dsl/workflow "emit-test-sheet"
      (dsl/blackboard
        {:documents [:vector :string]
         :doc-summaries [:vector :string]
         :analysis :map})
      (dsl/sequence "main"
        (dsl/code "survey"
          :fn "fake.ns/survey"
          :reads [:documents]
          :writes [:doc-summaries])
        (dsl/llm "synthesize"
          :model "google/gemini-2.5-flash"
          :instruction "Synthesize the per-document summaries."
          :reads [:doc-summaries]
          :writes [:analysis])))))

(deftest parse-workflow-form-ok
  (testing "valid DSL source returns :ok with a workflow-def"
    (let [{:keys [status workflow-def]} (driver/parse-workflow-form base-form)]
      (is (= :ok status))
      (is (= "emit-test-sheet" (:workflow-name workflow-def)))
      (is (= :sequence (get-in workflow-def [:root-node :node-type]))))))

(deftest parse-workflow-form-parse-error
  (testing "syntactically broken source returns :parse-error"
    (let [{:keys [status error]} (driver/parse-workflow-form "(workflow \"x\"")]
      (is (= :parse-error status))
      (is (string? error)))))

(deftest parse-workflow-form-rejects-non-workflow-result
  (testing "form that doesn't return a workflow map is rejected"
    (let [{:keys [status error]} (driver/parse-workflow-form "(+ 1 2)")]
      (is (= :parse-error status))
      (is (string? error)))))

(deftest parse-workflow-form-rejects-eval-escape
  (testing "Java interop is not bound in the SCI sandbox"
    (let [{:keys [status]} (driver/parse-workflow-form "(System/exit 0)")]
      (is (= :parse-error status)))))

(deftest submit-tree-idempotent-on-identical-form
  (h/with-test-context [ctx]
    (let [sheet-id (bootstrap-sheet! ctx)
          result (driver/submit-tree! ctx sheet-id base-form)]
      (testing "re-submitting the same form yields :ok with empty diff"
        (is (= :ok (:status result)))
        (is (= sheet-id (:sheet-id result)))
        (is (= [] (get-in result [:diff :added])))
        (is (= [] (get-in result [:diff :removed])))
        (is (= [] (get-in result [:diff :modified])))))))

(deftest submit-tree-detects-instruction-change
  (h/with-test-context [ctx]
    (let [sheet-id (bootstrap-sheet! ctx)
          tweaked (clojure.string/replace base-form
                    "Synthesize the per-document summaries."
                    "Synthesize the summaries into a precise analysis with citations.")
          result (driver/submit-tree! ctx sheet-id tweaked)]
      (testing "modified instruction shows up in :modified, names stable"
        (is (= :ok (:status result)))
        (is (= [] (get-in result [:diff :added])))
        (is (= [] (get-in result [:diff :removed])))
        (let [modified (get-in result [:diff :modified])]
          (is (= 1 (count modified)))
          (is (= "synthesize" (-> modified first :name)))
          (is (some #{:instruction} (-> modified first :changed))))))))

(deftest submit-tree-detects-structural-add
  (h/with-test-context [ctx]
    (let [sheet-id (bootstrap-sheet! ctx)
          extended "(workflow \"emit-test-sheet\"
                      (blackboard
                        {:documents [:vector :string]
                         :doc-summaries [:vector :string]
                         :verified-analysis :map
                         :analysis :map})
                      (sequence \"main\"
                        (code \"survey\"
                          :fn \"fake.ns/survey\"
                          :reads [:documents]
                          :writes [:doc-summaries])
                        (llm \"synthesize\"
                          :model \"google/gemini-2.5-flash\"
                          :instruction \"Synthesize the per-document summaries.\"
                          :reads [:doc-summaries]
                          :writes [:analysis])
                        (llm \"verify\"
                          :model \"google/gemini-2.5-flash\"
                          :instruction \"Verify the synthesis is grounded.\"
                          :reads [:analysis]
                          :writes [:verified-analysis])))"
          result (driver/submit-tree! ctx sheet-id extended)]
      (testing "adding a node shows up in :added"
        (is (= :ok (:status result)))
        (let [added-names (set (map :name (get-in result [:diff :added])))]
          (is (contains? added-names "verify")))))))

(deftest submit-tree-rejects-name-mismatch
  (h/with-test-context [ctx]
    (let [sheet-id (bootstrap-sheet! ctx)
          mismatched (clojure.string/replace base-form
                       "emit-test-sheet"
                       "different-name")
          result (driver/submit-tree! ctx sheet-id mismatched)]
      (testing "mismatched workflow name is rejected"
        (is (= :name-mismatch (:status result)))
        (is (= "emit-test-sheet" (:expected result)))
        (is (= "different-name" (:got result)))))))

(deftest submit-tree-rejects-broken-source
  (h/with-test-context [ctx]
    (let [sheet-id (bootstrap-sheet! ctx)
          result (driver/submit-tree! ctx sheet-id "(workflow \"emit-test-sheet\"")]
      (testing "broken source is :parse-error, not :build-error"
        (is (= :parse-error (:status result)))))))
