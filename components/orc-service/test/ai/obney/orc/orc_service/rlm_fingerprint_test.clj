(ns ai.obney.orc.orc-service.rlm-fingerprint-test
  "Tests for C-2a-2's tree-fingerprint helper.

   The fingerprint is a stable string hash derived from a canonical
   normalized view of an emit-tree! S-expression. Two trees with the
   same structural shape produce the same hash; structurally different
   trees produce different hashes; the hash is stable across the inline-fn
   sanitization step so that pre/post-sanitization views of the same
   logical tree converge."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as fp]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]))

;; =============================================================================
;; RED #1 — fingerprint is deterministic
;; =============================================================================

(deftest fingerprint-is-deterministic
  (testing "Given the same tree S-expression, fingerprint produces the same string each call"
    (let [tree '[:sequence
                 [:llm {:reads [:input] :writes [:extracted]}]
                 [:code {:reads [:extracted] :writes [:result]
                         :fn (fn [{:keys [extracted]}] {:result extracted})}]
                 [:final {:keys [:result]}]]]
      (is (= (fp/fingerprint tree) (fp/fingerprint tree))
          "Two calls on the same tree should produce identical fingerprint strings"))))

;; =============================================================================
;; RED #2 — fingerprint is stable across sanitize-tree-for-events
;; =============================================================================
;;
;; Load-bearing PRD claim: a tree carrying an inline fn value and the same
;; tree after `sanitize-tree-for-events` has replaced the fn with the
;; [:fn "<inline-fn>"] placeholder MUST produce the same fingerprint.

(deftest fingerprint-stable-across-sanitize-tree-for-events
  (testing "Pre-sanitization tree (with inline fn value) and post-sanitization tree (with placeholder) produce the SAME fingerprint"
    (let [pre-sanitization-tree
          [:sequence
           [:llm {:reads [:input] :writes [:extracted]}]
           [:code {:reads [:extracted] :writes [:result]
                   ;; live inline fn — what the model authors before sanitization
                   :fn (fn [{:keys [extracted]}] {:result extracted})}]
           [:final {:keys [:result]}]]
          post-sanitization-tree
          (tree-executor/sanitize-tree-for-events pre-sanitization-tree)]
      (is (not= pre-sanitization-tree post-sanitization-tree)
          "Sanity check: the sanitize-for-events transform DOES change the tree")
      (is (= (fp/fingerprint pre-sanitization-tree)
             (fp/fingerprint post-sanitization-tree))
          "Fingerprint should normalize both forms to the same canonical hash"))))

;; =============================================================================
;; RED #3 — fingerprint differentiates structurally-different trees
;; =============================================================================
;;
;; The fingerprint must NOT collapse too aggressively. Trees with different
;; composition (:sequence vs :parallel), different control args
;; (:max-concurrency bound vs unbound), or different :reads/:writes
;; blackboard keys ARE structurally distinct and must produce DIFFERENT
;; fingerprints.

(deftest fingerprint-differentiates-sequence-vs-parallel
  (testing "Same children under :sequence vs :parallel produce different fingerprints"
    (let [seq-tree '[:sequence
                     [:llm {:reads [:input] :writes [:a]}]
                     [:llm {:reads [:input] :writes [:b]}]]
          par-tree '[:parallel
                     [:llm {:reads [:input] :writes [:a]}]
                     [:llm {:reads [:input] :writes [:b]}]]]
      (is (not= (fp/fingerprint seq-tree) (fp/fingerprint par-tree))
          "Sequence and parallel composition are structurally distinct"))))

(deftest fingerprint-differentiates-bounded-vs-unbounded-max-concurrency
  (testing "Map-each with :max-concurrency 3 vs unset produces different fingerprints"
    (let [bounded   '[:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3}
                      [:llm {:reads [:chunk] :writes [:result]}]]
          unbounded '[:map-each {:from :chunks :as :chunk :into :results}
                      [:llm {:reads [:chunk] :writes [:result]}]]]
      (is (not= (fp/fingerprint bounded) (fp/fingerprint unbounded))
          ":max-concurrency is a structural choice the model is making — distinct fingerprints"))))

(deftest fingerprint-differentiates-different-reads-keys
  (testing "Same shape but different :reads keys produce different fingerprints"
    (let [tree-a '[:llm {:reads [:document] :writes [:summary]}]
          tree-b '[:llm {:reads [:input]    :writes [:summary]}]]
      (is (not= (fp/fingerprint tree-a) (fp/fingerprint tree-b))
          "Blackboard key names are semantic to the tree's purpose — distinct fingerprints"))))

;; =============================================================================
;; RED #4 — fingerprint collapses :fn bodies + :instruction strings
;; =============================================================================
;;
;; Two trees with identical structural shape but different :fn body or
;; :instruction text should produce the SAME fingerprint — those are
;; implementation/content, not structure.

(deftest fingerprint-collapses-different-fn-bodies
  (testing "Two trees with same shape but different inline :fn body produce the same fingerprint"
    (let [tree-a '[:code {:reads [:items] :writes [:total]
                          :fn (fn [{:keys [items]}] {:total (count items)})}]
          tree-b '[:code {:reads [:items] :writes [:total]
                          :fn (fn [{:keys [items]}] {:total (reduce + 0 items)})}]]
      (is (= (fp/fingerprint tree-a) (fp/fingerprint tree-b))
          "Different fn implementations on the same structural shape converge to one fingerprint"))))

(deftest fingerprint-collapses-different-instruction-strings
  (testing "Two trees with same shape but different :instruction text produce the same fingerprint"
    (let [tree-a '[:llm {:reads [:document] :writes [:summary]
                         :instruction "Write a one-paragraph summary."}]
          tree-b '[:llm {:reads [:document] :writes [:summary]
                         :instruction "Produce a concise executive overview."}]]
      (is (= (fp/fingerprint tree-a) (fp/fingerprint tree-b))
          "Instruction content varies; structural shape does not — converge to one fingerprint"))))
