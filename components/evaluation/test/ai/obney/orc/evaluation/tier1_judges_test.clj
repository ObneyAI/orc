(ns ai.obney.orc.evaluation.tier1-judges-test
  "PA-4 — DETERMINISTIC contract tests for the THREE migrated tier-1 judges
   (instruction-following, reasoning, completeness), mirroring PA-3's
   grounding_judge_test.clj exactly.

   These exercise the parts that are NOT LLM output (discipline #1/#5):
     - get-tier1-rubric returns a decoupled criteria × stance × scale bundle
       for each migrated dimension;
     - each judge returns a back-compatible :*-result shape;
     - :score is DERIVED from the discrete :level via the Scale, not self-
       reported;
     - the decoupled instruction composes stance + criteria + bands and
       contains NO json-in-prompt;
     - the no-run-through gate throws on empty/garbage model output;
     - :judge/score-emitted shape preserved (numeric :score + string
       :feedback survive to the consumer-facing map).

   The LLM's actual band CHOICE on real traces is calibrated live
   (development/src/prototype_tier1_calibration.clj), not asserted here."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.orc.evaluation.core.scale :as scale]))

(def sample-trace
  {:inputs {:context "FAQ: The gym is open Monday-Friday 6am-10pm."}
   :response "The gym is open Monday-Friday 6am-10pm."
   :instruction "Answer based only on the provided FAQ, in one sentence."})

;; =============================================================================
;; get-tier1-rubric — decoupled criteria × stance × scale, per dimension
;; =============================================================================

(deftest get-tier1-rubric-returns-decoupled-bundle-for-each-dimension
  (testing "Each migrated dimension resolves to a decoupled tier-1 bundle"
    (doseq [k [:instruction-following :reasoning :completeness]]
      (let [r (rubrics/get-tier1-rubric k)]
        (is (some? r) (str k " resolves to a tier-1 rubric"))
        (is (string? (:criteria r)) (str k " carries decoupled :criteria"))
        (is (string? (:stance r)) (str k " carries decoupled :stance"))
        (is (= :discrete (:kind (:scale r))) (str k " carries a discrete Scale"))
        (is (= 5 (count (:bands (:scale r)))) (str k " Scale has 5 bands"))
        ;; Decoupling: the Scale must not embed criteria/stance/prompt.
        (is (nil? (:criteria (:scale r))) "Scale carries no criteria")
        (is (nil? (:stance (:scale r))) "Scale carries no stance")
        (is (nil? (:prompt (:scale r))) "Scale carries no prompt")))))

(deftest get-tier1-rubric-grounding-still-works
  (testing "PA-3 grounding still resolves (no regression)"
    (is (some? (rubrics/get-tier1-rubric :grounding)))))

(deftest get-tier1-rubric-unknown-returns-nil
  (testing "An unknown key returns nil"
    (is (nil? (rubrics/get-tier1-rubric :nope)))))

;; =============================================================================
;; Back-compatible shape (mock path — no LLM)
;; =============================================================================

(deftest instruction-following-judge-mock-returns-back-compatible-shape
  (testing "Mock instruction-following judge returns :instruction-result with consumer-facing fields"
    (judges/with-mock-llm
      (let [{:keys [instruction-result]} (judges/instruction-following-judge
                                           {:inputs {:trace-data sample-trace}})]
        (is (number? (:score instruction-result)) "Has a numeric :score (consumer break guard)")
        (is (<= 0.0 (:score instruction-result) 1.0) ":score is in [0,1]")
        (is (vector? (:requirements-met instruction-result)))
        (is (vector? (:requirements-missed instruction-result)))
        (is (string? (:feedback instruction-result)))))))

(deftest reasoning-judge-mock-returns-back-compatible-shape
  (testing "Mock reasoning judge returns :reasoning-result with consumer-facing fields"
    (judges/with-mock-llm
      (let [{:keys [reasoning-result]} (judges/reasoning-judge
                                         {:inputs {:trace-data sample-trace}})]
        (is (number? (:score reasoning-result)))
        (is (<= 0.0 (:score reasoning-result) 1.0))
        (is (vector? (:reasoning-strengths reasoning-result)))
        (is (vector? (:reasoning-weaknesses reasoning-result)))
        (is (string? (:feedback reasoning-result)))))))

(deftest completeness-judge-mock-returns-back-compatible-shape
  (testing "Mock completeness judge returns :completeness-result with consumer-facing fields"
    (judges/with-mock-llm
      (let [{:keys [completeness-result]} (judges/completeness-judge
                                            {:inputs {:trace-data sample-trace}})]
        (is (number? (:score completeness-result)))
        (is (<= 0.0 (:score completeness-result) 1.0))
        (is (vector? (:aspects-covered completeness-result)))
        (is (vector? (:aspects-missing completeness-result)))
        (is (string? (:feedback completeness-result)))))))

;; =============================================================================
;; :score derived from discrete :level (NOT self-reported)
;; =============================================================================

(deftest instruction-following-score-is-derived-from-discrete-level
  (testing ":score is the deterministic Scale mapping of the discrete :level"
    (judges/with-mock-llm
      (let [{:keys [instruction-result]} (judges/instruction-following-judge
                                           {:inputs {:trace-data sample-trace}})
            the-scale (:scale (rubrics/get-tier1-rubric :instruction-following))]
        (is (contains? instruction-result :level) "Carries the discrete band level")
        (is (some #{(:level instruction-result)} (scale/levels the-scale)) ":level is a valid band")
        (is (= (scale/level->unit-score the-scale (:level instruction-result))
               (:score instruction-result))
            ":score must equal the Scale's deterministic mapping of :level")))))

(deftest reasoning-score-is-derived-from-discrete-level
  (testing ":score is the deterministic Scale mapping of the discrete :level"
    (judges/with-mock-llm
      (let [{:keys [reasoning-result]} (judges/reasoning-judge
                                         {:inputs {:trace-data sample-trace}})
            the-scale (:scale (rubrics/get-tier1-rubric :reasoning))]
        (is (contains? reasoning-result :level))
        (is (some #{(:level reasoning-result)} (scale/levels the-scale)))
        (is (= (scale/level->unit-score the-scale (:level reasoning-result))
               (:score reasoning-result)))))))

(deftest completeness-score-is-derived-from-discrete-level
  (testing ":score is the deterministic Scale mapping of the discrete :level"
    (judges/with-mock-llm
      (let [{:keys [completeness-result]} (judges/completeness-judge
                                            {:inputs {:trace-data sample-trace}})
            the-scale (:scale (rubrics/get-tier1-rubric :completeness))]
        (is (contains? completeness-result :level))
        (is (some #{(:level completeness-result)} (scale/levels the-scale)))
        (is (= (scale/level->unit-score the-scale (:level completeness-result))
               (:score completeness-result)))))))

;; =============================================================================
;; Reason-before-score field present
;; =============================================================================

(deftest tier1-judges-carry-reasoning-before-score
  (testing "Each tier-1 result carries :reasoning (the reason-before-score field)"
    (judges/with-mock-llm
      (let [i (:instruction-result (judges/instruction-following-judge {:inputs {:trace-data sample-trace}}))
            r (:reasoning-result (judges/reasoning-judge {:inputs {:trace-data sample-trace}}))
            c (:completeness-result (judges/completeness-judge {:inputs {:trace-data sample-trace}}))]
        (doseq [res [i r c]]
          (is (string? (:reasoning res)))
          (is (seq (:reasoning res)) "Reasoning is non-empty"))))))

;; =============================================================================
;; Decoupled instruction — structured-output rule
;; =============================================================================

(deftest tier1-instructions-are-decoupled-and-have-no-json-in-prompt
  (testing "Each instruction composes stance + criteria + bands; NO json-in-prompt"
    (doseq [k [:instruction-following :reasoning :completeness]]
      (let [rubric (rubrics/get-tier1-rubric k)
            instr (judges/build-tier1-instruction rubric)]
        (is (string? instr))
        ;; adversarial stance present
        (is (re-find #"(?i)skeptical|adversarial" instr) (str k ": adversarial stance present"))
        ;; bands rendered into the instruction (band-1 and band-5 anchors)
        (is (re-find #"(?i)\n- 1:" instr) (str k ": band-1 rendered"))
        (is (re-find #"(?i)\n- 5:" instr) (str k ": band-5 rendered"))
        ;; structured-output rule: NO json example / 'return only JSON'
        (is (not (re-find #"(?i)return only json" instr)) (str k ": no 'return only json'"))
        (is (not (re-find #"\{\s*\"score\"" instr)) (str k ": no JSON object literal"))
        (is (not (re-find #"```json" instr)) (str k ": no json code-fence"))))))

;; =============================================================================
;; No-run-through gate (via each judge fn's real path, with redefs)
;; =============================================================================

(deftest instruction-following-gate-throws-on-empty-llm-output
  (testing "Empty structured output → judge throws (no silent 0)"
    (with-redefs [judges/call-tier1-judge-llm (fn [& _] {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)empty|no.?run.?through|missing"
            (judges/instruction-following-judge {:inputs {:trace-data sample-trace}}))))))

(deftest reasoning-gate-throws-on-missing-level
  (testing "Output without a usable :level → judge throws"
    (with-redefs [judges/call-tier1-judge-llm
                  (fn [& _] {:reasoning "I analyzed it" :feedback "ok"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)level|score|no.?run.?through"
            (judges/reasoning-judge {:inputs {:trace-data sample-trace}}))))))

(deftest completeness-gate-throws-on-empty-llm-output
  (testing "Empty structured output → judge throws (no silent 0)"
    (with-redefs [judges/call-tier1-judge-llm (fn [& _] {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)empty|no.?run.?through|missing"
            (judges/completeness-judge {:inputs {:trace-data sample-trace}}))))))

;; =============================================================================
;; Well-shaped level maps deterministically to score (per dimension)
;; =============================================================================

(deftest instruction-following-maps-real-shaped-level-to-score
  (testing "A well-shaped output (level 2) maps deterministically to 0.25"
    (with-redefs [judges/call-tier1-judge-llm
                  (fn [& _] {:level 2
                             :reasoning "Missed a required component."
                             :requirements-met ["a"]
                             :requirements-missed ["b" "c"]
                             :feedback "Add the missing section."})]
      (let [{:keys [instruction-result]} (judges/instruction-following-judge
                                           {:inputs {:trace-data sample-trace}})]
        (is (= 2 (:level instruction-result)))
        (is (= 0.25 (:score instruction-result)))
        (is (= ["b" "c"] (:requirements-missed instruction-result)))))))

(deftest reasoning-maps-real-shaped-level-to-score
  (testing "A well-shaped output (level 4) maps deterministically to 0.75"
    (with-redefs [judges/call-tier1-judge-llm
                  (fn [& _] {:level 4
                             :reasoning "Logical flow with a minor unstated assumption."
                             :reasoning-strengths ["clear steps"]
                             :reasoning-weaknesses ["one gap"]
                             :feedback "State the assumption."})]
      (let [{:keys [reasoning-result]} (judges/reasoning-judge
                                         {:inputs {:trace-data sample-trace}})]
        (is (= 4 (:level reasoning-result)))
        (is (= 0.75 (:score reasoning-result)))
        (is (= ["one gap"] (:reasoning-weaknesses reasoning-result)))))))

(deftest completeness-maps-real-shaped-level-to-score
  (testing "A well-shaped output (level 5) maps deterministically to 1.0"
    (with-redefs [judges/call-tier1-judge-llm
                  (fn [& _] {:level 5
                             :reasoning "Every required aspect addressed with detail."
                             :aspects-covered ["a" "b"]
                             :aspects-missing []
                             :feedback "Complete."})]
      (let [{:keys [completeness-result]} (judges/completeness-judge
                                            {:inputs {:trace-data sample-trace}})]
        (is (= 5 (:level completeness-result)))
        (is (= 1.0 (:score completeness-result)))
        (is (= [] (:aspects-missing completeness-result)))))))
