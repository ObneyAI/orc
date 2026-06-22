(ns ai.obney.orc.evaluation.grounding-judge-test
  "PA-3 — DETERMINISTIC contract tests for the redesigned grounding judge.

   These exercise the parts that are NOT LLM output (discipline #5):
     - the judge returns a back-compatible :grounding-result shape;
     - :score is DERIVED from the discrete :level via the Scale, not self-
       reported;
     - the decoupled instruction composes stance + criteria + bands and
       contains NO json-in-prompt;
     - the no-run-through gate throws on empty/garbage model output.

   The LLM's actual band CHOICE on real traces is calibrated live in the
   prototype, not asserted here."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.orc.evaluation.core.scale :as scale]))

(def sample-trace
  {:inputs {:context "FAQ: The gym is open Monday-Friday 6am-10pm."}
   :response "The gym is open Monday-Friday 6am-10pm."
   :instruction "Answer based only on the provided FAQ."})

;; =============================================================================
;; Back-compatible shape (mock path — no LLM)
;; =============================================================================

(deftest grounding-judge-mock-returns-back-compatible-shape
  (testing "Mock grounding judge returns :grounding-result with the consumer-facing fields"
    (judges/with-mock-llm
      (let [{:keys [grounding-result]} (judges/grounding-judge
                                         {:inputs {:trace-data sample-trace}})]
        (is (number? (:score grounding-result)) "Has a numeric :score (consumer break guard)")
        (is (<= 0.0 (:score grounding-result) 1.0) ":score is in [0,1]")
        (is (vector? (:grounded-claims grounding-result)))
        (is (vector? (:ungrounded-claims grounding-result)))
        (is (string? (:feedback grounding-result)))))))

(deftest grounding-judge-score-is-derived-from-discrete-level
  (testing ":score is the deterministic Scale mapping of the discrete :level, not a self-reported float"
    (judges/with-mock-llm
      (let [{:keys [grounding-result]} (judges/grounding-judge
                                         {:inputs {:trace-data sample-trace}})
            the-scale (:scale (rubrics/get-tier1-rubric :grounding))]
        (is (contains? grounding-result :level) "Carries the discrete band level (reason-before-score)")
        (is (some #{(:level grounding-result)} (scale/levels the-scale)) ":level is a valid band")
        (is (= (scale/level->unit-score the-scale (:level grounding-result))
               (:score grounding-result))
            ":score must equal the Scale's deterministic mapping of :level")
        ;; mock returns level 4 → 0.75 on the 1-5 scale
        (is (= 4 (:level grounding-result)))
        (is (= 0.75 (:score grounding-result)))))))

(deftest grounding-judge-carries-reasoning-before-score
  (testing "Tier-1 grounding result carries :reasoning (the reason-before-score field)"
    (judges/with-mock-llm
      (let [{:keys [grounding-result]} (judges/grounding-judge
                                         {:inputs {:trace-data sample-trace}})]
        (is (string? (:reasoning grounding-result)))
        (is (seq (:reasoning grounding-result)) "Reasoning is non-empty")))))

;; =============================================================================
;; Decoupled instruction — structured-output rule
;; =============================================================================

(deftest grounding-instruction-is-decoupled-and-has-no-json-in-prompt
  (testing "Instruction composes stance + criteria + bands; contains NO json-in-prompt directive"
    (let [rubric (rubrics/get-tier1-rubric :grounding)
          instr (judges/build-grounding-instruction rubric)]
      (is (string? instr))
      ;; stance present (adversarial)
      (is (re-find #"(?i)skeptical|adversarial" instr) "Adversarial stance present")
      ;; criteria present (grounding definition)
      (is (re-find #"(?i)grounding|source" instr) "Criteria present")
      ;; bands rendered into the instruction
      (is (re-find #"(?i)fully grounded" instr) "Band-5 text present")
      (is (re-find #"(?i)fabricat" instr) "Band-1 text present")
      ;; structured-output rule: NO json example / 'return only JSON'
      (is (not (re-find #"(?i)return only json" instr)))
      (is (not (re-find #"\{\s*\"score\"" instr)) "No JSON object literal in the prompt")
      (is (not (re-find #"```json" instr)) "No json code-fence in the prompt"))))

;; =============================================================================
;; No-run-through gate (via the judge fn's real path, with redefs)
;; =============================================================================

(deftest grounding-judge-gate-throws-on-empty-llm-output
  (testing "When the LLM returns empty structured output, the judge throws (no silent 0)"
    (with-redefs [judges/call-grounding-judge-llm (fn [& _] {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)empty|no.?run.?through|missing"
            (judges/grounding-judge {:inputs {:trace-data sample-trace}}))))))

(deftest grounding-judge-gate-throws-on-missing-level
  (testing "When the LLM returns output without a usable :level, the judge throws"
    (with-redefs [judges/call-grounding-judge-llm
                  (fn [& _] {:reasoning "I analyzed it" :feedback "ok"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)level|score|no.?run.?through"
            (judges/grounding-judge {:inputs {:trace-data sample-trace}}))))))

(deftest grounding-judge-maps-real-shaped-level-to-score
  (testing "A well-shaped LLM output (level 2) maps deterministically to 0.25"
    (with-redefs [judges/call-grounding-judge-llm
                  (fn [& _] {:level 2
                             :reasoning "Several fabricated specifics."
                             :grounded-claims ["a"]
                             :ungrounded-claims ["b" "c"]
                             :feedback "Remove invented figures."})]
      (let [{:keys [grounding-result]} (judges/grounding-judge
                                         {:inputs {:trace-data sample-trace}})]
        (is (= 2 (:level grounding-result)))
        (is (= 0.25 (:score grounding-result)))
        (is (= ["b" "c"] (:ungrounded-claims grounding-result)))))))
