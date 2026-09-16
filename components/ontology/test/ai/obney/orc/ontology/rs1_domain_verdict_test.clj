(ns ai.obney.orc.ontology.rs1-domain-verdict-test
  "RS-1: the reranker gives a domain verdict beside fitness.

   Spec: `specs/ontology.allium` `enum DomainCoverage`, `value DomainVerdict`,
   `contract TaskClassification.judge_domain_coverage`. `judge_domain_coverage`
   is realised here as the reranker's per-candidate verdict — parsed,
   canonicalised and validated exactly as the existing three keys are.

   Seam 2 — the rerank workflow's parse step (`parse-reranked-json`) with
   constructed payloads. The reranker is stubbed with a typed payload this
   test constructs; no model-authored prose is asserted on."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [cheshire.core :as cheshire]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.reranker :as reranker]))

;; =============================================================================
;; RED #1 — the six-key payload parses to a validated entry carrying the
;; domain verdict beside the three canonical keys
;; =============================================================================

(deftest domain-verdict-fields-parse-and-validate
  (testing "A reranker payload carrying the six keys parses to an entry with
            :domain-coverage / :domain-label / :domain-reasoning beside the
            three canonical keys, and the entry validates"
    (let [payload (json/write-str
                    [{:document_id "a"
                      :reasoning "Fits the caller's structured-output need."
                      :fitness_score 0.87
                      :domain_reasoning "Shares subject matter (training plans) but not the output kind (a schedule, not a narrative)."
                      :domain_coverage "partial"
                      :domain_label "marathon-training-plan"}])
          result {:outputs {:reranked-json payload}}
          parsed (#'reranker/parse-reranked-json result)
          entry (first parsed)]
      (is (= 1 (count parsed)))
      (is (= :partial (:domain-coverage entry)))
      (is (= "marathon-training-plan" (:domain-label entry)))
      (is (= "Shares subject matter (training plans) but not the output kind (a schedule, not a narrative)."
             (:domain-reasoning entry)))
      (is (= "a" (:document-id entry)))
      (is (= "Fits the caller's structured-output need." (:reasoning entry)))
      (is (= 0.87 (:fitness-score entry)))
      (is (m/validate ontology-schemas/reranked-result entry)
          (str "Entry should validate. Explanation: "
               (pr-str (m/explain ontology-schemas/reranked-result entry)))))))

;; =============================================================================
;; RED #3 — the instruction carries the domain section; candidates render
;; :existing-domain-children for label reuse
;; =============================================================================

(deftest reranker-instruction-carries-domain-coverage-section
  (testing "The reranker instruction contains the RS-1 domain section VERBATIM
            — asserted against the constant this slice defines (OUR text,
            never model prose)"
    (let [instr (str @#'reranker/reranker-instruction)]
      (is (str/includes? instr (str @#'reranker/domain-coverage-section))
          "The instruction must include the domain-coverage section byte-for-byte")))

  (testing "The output contract now asks for EXACTLY six keys, not three"
    (let [instr (str @#'reranker/reranker-instruction)]
      (is (str/includes? instr "EXACTLY these six keys"))
      (is (not (str/includes? instr "EXACTLY these three keys"))
          "The shipped three-key output contract must be fully replaced"))))

(deftest candidate-schema-accepts-and-renders-existing-domain-children
  (testing "candidate-schema accepts an OPTIONAL :existing-domain-children"
    (let [candidate {:content "A document about marathon training"
                      :score 0.7
                      :document-id "a"
                      :existing-domain-children ["marathon-training-plan" "recipe-scaling"]}]
      (is (m/validate @#'reranker/candidate-schema candidate)
          (str "Explanation: " (pr-str (m/explain @#'reranker/candidate-schema candidate))))))

  (testing "candidate-schema still accepts a candidate WITHOUT the new key (backward compatible)"
    (let [candidate {:content "A document" :score 0.5 :document-id "b"}]
      (is (m/validate @#'reranker/candidate-schema candidate))))

  (testing "The candidates JSON rendered for the LLM (cheshire, the same
            encoder orc-service's executor uses for blackboard :reads) includes
            a candidate's :existing-domain-children"
    (let [candidate {:content "A document about marathon training"
                      :score 0.7
                      :document-id "a"
                      :existing-domain-children ["marathon-training-plan" "recipe-scaling"]}
          rendered (cheshire/generate-string candidate)]
      (is (str/includes? rendered "existing-domain-children"))
      (is (str/includes? rendered "marathon-training-plan"))
      (is (str/includes? rendered "recipe-scaling")))))

;; =============================================================================
;; RED #2 — a missing or malformed verdict reads as :unknown, never coerced
;; =============================================================================

(deftest missing-or-malformed-domain-coverage-defers-to-unknown
  (testing "A pre-RS-1 payload (the three shipped keys only, domain keys ABSENT)
            parses to an entry with :domain-coverage :unknown and nil label/
            reasoning, and the entry still validates"
    (let [payload (json/write-str
                    [{:document_id "a"
                      :reasoning "Fits the intent."
                      :fitness_score 0.6}])
          result {:outputs {:reranked-json payload}}
          parsed (#'reranker/parse-reranked-json result)
          entry (first parsed)]
      (is (= 1 (count parsed)))
      (is (= :unknown (:domain-coverage entry)))
      (is (nil? (:domain-label entry)))
      (is (nil? (:domain-reasoning entry)))
      (is (m/validate ontology-schemas/reranked-result entry)
          (str "Entry should validate. Explanation: "
               (pr-str (m/explain ontology-schemas/reranked-result entry))))))

  (testing "A MALFORMED domain_coverage (not one of the four values) is read
            as :unknown rather than coerced to the given string"
    (let [payload (json/write-str
                    [{:document_id "a"
                      :reasoning "Fits the intent."
                      :fitness_score 0.6
                      :domain_reasoning "Some reasoning."
                      :domain_coverage "maybe"
                      :domain_label "some-label"}])
          result {:outputs {:reranked-json payload}}
          parsed (#'reranker/parse-reranked-json result)
          entry (first parsed)]
      (is (= 1 (count parsed)))
      (is (= :unknown (:domain-coverage entry))
          "A malformed verdict must defer to :unknown, never be coerced to :maybe")
      (is (m/validate ontology-schemas/reranked-result entry)
          (str "Entry should validate. Explanation: "
               (pr-str (m/explain ontology-schemas/reranked-result entry)))))))
