(ns ai.obney.orc.orc-service.rlm-fingerprint-test
  "Tests for C-2a-2's tree-fingerprint helper.

   The fingerprint is a stable string hash derived from a canonical
   normalized view of an emit-tree! S-expression. Two trees with the
   same structural shape produce the same hash; structurally different
   trees produce different hashes; the hash is stable across exact durable
   source serialization."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.logic :as logic]))


(deftest fingerprint-is-deterministic
  (testing "Given the same tree S-expression, fingerprint produces the same string each call"
    (let [tree '[:sequence
                 [:llm {:reads [:input] :writes [:extracted]}]
                 [:code {:reads [:extracted] :writes [:result]
                         :fn (fn [{:keys [extracted]}] {:result extracted})}]
                 [:final {:keys [:result]}]]]
      (is (= (logic/fingerprint tree) (logic/fingerprint tree))
          "Two calls on the same tree should produce identical fingerprint strings"))))


(deftest fingerprint-stable-across-durable-source-roundtrip
  (testing "quoted inline source and its EDN round-trip produce the same fingerprint"
    (let [source-tree
          '[:sequence
           [:llm {:reads [:input] :writes [:extracted]}]
           [:code {:reads [:extracted] :writes [:result]
                   :fn (fn [{:keys [inputs]}]
                         {:result (:extracted inputs)})}]
           [:final {:keys [:result]}]]
          source-text (pr-str source-tree)
          reopened-tree (read-string source-text)]
      (is (= source-tree reopened-tree))
      (is (= (logic/fingerprint source-tree)
             (logic/fingerprint reopened-tree))
          "durable source round-trip preserves the canonical shape hash"))))


(deftest fingerprint-differentiates-sequence-vs-parallel
  (testing "Same children under :sequence vs :parallel produce different fingerprints"
    (let [seq-tree '[:sequence
                     [:llm {:reads [:input] :writes [:a]}]
                     [:llm {:reads [:input] :writes [:b]}]]
          par-tree '[:parallel
                     [:llm {:reads [:input] :writes [:a]}]
                     [:llm {:reads [:input] :writes [:b]}]]]
      (is (not= (logic/fingerprint seq-tree) (logic/fingerprint par-tree))
          "Sequence and parallel composition are structurally distinct"))))

(deftest fingerprint-differentiates-bounded-vs-unbounded-max-concurrency
  (testing "Map-each with :max-concurrency 3 vs unset produces different fingerprints"
    (let [bounded   '[:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3}
                      [:llm {:reads [:chunk] :writes [:result]}]]
          unbounded '[:map-each {:from :chunks :as :chunk :into :results}
                      [:llm {:reads [:chunk] :writes [:result]}]]]
      (is (not= (logic/fingerprint bounded) (logic/fingerprint unbounded))
          ":max-concurrency is a structural choice the model is making — distinct fingerprints"))))

(deftest fingerprint-differentiates-different-reads-keys
  (testing "Same shape but different :reads keys produce different fingerprints"
    (let [tree-a '[:llm {:reads [:document] :writes [:summary]}]
          tree-b '[:llm {:reads [:input]    :writes [:summary]}]]
      (is (not= (logic/fingerprint tree-a) (logic/fingerprint tree-b))
          "Blackboard key names are semantic to the tree's purpose — distinct fingerprints"))))


(deftest fingerprint-collapses-different-fn-bodies
  (testing "Two trees with same shape but different inline :fn body produce the same fingerprint"
    (let [tree-a '[:code {:reads [:items] :writes [:total]
                          :fn (fn [{:keys [items]}] {:total (count items)})}]
          tree-b '[:code {:reads [:items] :writes [:total]
                          :fn (fn [{:keys [items]}] {:total (reduce + 0 items)})}]]
      (is (= (logic/fingerprint tree-a) (logic/fingerprint tree-b))
          "Different fn implementations on the same structural shape converge to one fingerprint"))))

(deftest fingerprint-collapses-different-instruction-strings
  (testing "Two trees with same shape but different :instruction text produce the same fingerprint"
    (let [tree-a '[:llm {:reads [:document] :writes [:summary]
                         :instruction "Write a one-paragraph summary."}]
          tree-b '[:llm {:reads [:document] :writes [:summary]
                         :instruction "Produce a concise executive overview."}]]
      (is (= (logic/fingerprint tree-a) (logic/fingerprint tree-b))
          "Instruction content varies; structural shape does not — converge to one fingerprint"))))
