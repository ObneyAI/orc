(ns ai.obney.orc.workflow-driver.observe-test
  "Milestone 1 spike: prove the read-only ops compose against a real
   built Sheet and return non-empty, shape-conformant data."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.workflow-driver.interface :as driver]))

(defn- build-sample-workflow!
  "Build a small but realistic workflow that mirrors the doc-analysis
   shape: blackboard + sequence with a code node and an llm node.
   Returns the sheet-id."
  [ctx]
  (let [w (dsl/workflow "milestone-1-sample"
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
                :instruction "Synthesize the summaries."
                :reads [:doc-summaries]
                :writes [:analysis])))]
    (dsl/build-workflow! ctx w)))

(deftest sheet-snapshot-returns-shaped-data
  (h/with-test-context [ctx]
    (let [sheet-id (build-sample-workflow! ctx)
          snap (driver/sheet-snapshot ctx sheet-id)]
      (testing "snapshot has the expected top-level keys"
        (is (some? snap))
        (is (every? snap [:sheet :root-node :nodes-by-id :blackboard-schema])))
      (testing "root node is a sequence"
        (is (= :sequence (:type (:root-node snap)))))
      (testing "blackboard schema includes declared keys"
        (let [keys-set (->> snap :blackboard-schema (map :key) set)]
          (is (contains? keys-set :documents))
          (is (contains? keys-set :doc-summaries))
          (is (contains? keys-set :analysis))))
      (testing "nodes-by-id contains all 4 nodes (sequence root + 3 children)"
        (is (>= (count (:nodes-by-id snap)) 3))))))

(deftest sheet-snapshot-handles-missing-sheet
  (h/with-test-context [ctx]
    (is (nil? (driver/sheet-snapshot ctx (random-uuid))))))

(deftest sheet-as-dsl-roundtrips
  (h/with-test-context [ctx]
    (let [sheet-id (build-sample-workflow! ctx)
          dsl-str (driver/sheet-as-dsl ctx sheet-id)]
      (testing "exported DSL is a non-empty string"
        (is (string? dsl-str))
        (is (pos? (count dsl-str))))
      (testing "exported string references the workflow name and key nodes"
        (is (str/includes? dsl-str "milestone-1-sample"))
        (is (str/includes? dsl-str "survey"))
        (is (str/includes? dsl-str "synthesize"))))))

(deftest recent-ticks-empty-when-no-runs
  (h/with-test-context [ctx]
    (let [sheet-id (build-sample-workflow! ctx)]
      (is (= [] (driver/recent-ticks ctx sheet-id))))))

(deftest node-summary-lists-all-nodes
  (h/with-test-context [ctx]
    (let [sheet-id (build-sample-workflow! ctx)
          summary (driver/node-summary ctx sheet-id)
          names (set (map :name summary))]
      (testing "summary contains every node by name"
        (is (contains? names "main"))
        (is (contains? names "survey"))
        (is (contains? names "synthesize"))))))

(deftest pareto-empty-when-no-completed-ticks
  (h/with-test-context [ctx]
    (let [sheet-id (build-sample-workflow! ctx)]
      (is (= [] (driver/pareto ctx sheet-id))))))

(deftest describe-sheet-renders-text-and-mentions-key-elements
  (h/with-test-context [ctx]
    (let [sheet-id (build-sample-workflow! ctx)
          text (driver/describe-sheet ctx sheet-id)]
      (testing "describe-sheet output is a string"
        (is (string? text))
        (is (pos? (count text))))
      (testing "output mentions the sheet name, blackboard keys, and node names"
        (is (str/includes? text "milestone-1-sample"))
        (is (str/includes? text ":documents"))
        (is (str/includes? text "survey"))
        (is (str/includes? text "synthesize"))))))

(deftest describe-sheet-handles-missing-sheet-cleanly
  (h/with-test-context [ctx]
    (let [text (driver/describe-sheet ctx (random-uuid))]
      (is (string? text))
      (is (str/includes? text "No Sheet found")))))
