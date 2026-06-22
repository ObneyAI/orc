(ns ai.obney.orc.gepa.judge-metric-test
  "TDD for the deterministic surface of the judge-backed GEPA metric and the
   rich-feedback reflective-dataset path. These tests cover PURE logic only —
   the weighted combine, the feedback formatting (weakest-first), and the
   reflective-dataset's selection of rich judge feedback over the legacy
   'scored 0.XX' string. NO LLM calls here (that is the wired end-to-end run)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as string]
            [ai.obney.orc.gepa.core.metrics :as metrics]
            [ai.obney.orc.gepa.core.todo-processors :as tp]))

;; =============================================================================
;; weighted-combine — weighted [0,1] score, normalized by weight sum
;; =============================================================================

(deftest weighted-combine-test
  (testing "weights a single dimension at full weight"
    (is (= 0.8 (metrics/weighted-combine [{:dim :grounding :score 0.8 :weight 1.0}]))))

  (testing "weights multiple dimensions proportionally"
    ;; 0.30*1.0 + 0.25*0.0 + 0.20*0.5 + 0.25*1.0 = 0.30 + 0 + 0.10 + 0.25 = 0.65
    (is (= 0.65 (metrics/weighted-combine
                  [{:dim :grounding :score 1.0 :weight 0.30}
                   {:dim :instruction-following :score 0.0 :weight 0.25}
                   {:dim :reasoning :score 0.5 :weight 0.20}
                   {:dim :completeness :score 1.0 :weight 0.25}]))))

  (testing "normalizes by the sum of weights (weights need not sum to 1)"
    ;; (2*1.0 + 2*0.0) / 4 = 0.5
    (is (= 0.5 (metrics/weighted-combine
                 [{:dim :a :score 1.0 :weight 2.0}
                  {:dim :b :score 0.0 :weight 2.0}]))))

  (testing "empty dimensions -> 0.0 (no NaN)"
    (is (= 0.0 (metrics/weighted-combine []))))

  (testing "all-zero weights -> 0.0 (no divide-by-zero)"
    (is (= 0.0 (metrics/weighted-combine [{:dim :a :score 1.0 :weight 0.0}])))))

;; =============================================================================
;; format-judge-feedback — actionable string, WEAKEST dimensions first
;; =============================================================================

(deftest format-judge-feedback-test
  (let [dims [{:dim :grounding :score 0.9 :feedback "well grounded" :reasoning "r-g"}
              {:dim :completeness :score 0.1 :feedback "missing aspects" :reasoning "r-c"}
              {:dim :reasoning :score 0.5 :feedback "ok logic" :reasoning "r-r"}]
        out (metrics/format-judge-feedback dims)]
    (testing "includes every dimension's feedback"
      (is (string/includes? out "well grounded"))
      (is (string/includes? out "missing aspects"))
      (is (string/includes? out "ok logic")))

    (testing "orders weakest dimension first"
      (let [i-completeness (string/index-of out "missing aspects")
            i-reasoning (string/index-of out "ok logic")
            i-grounding (string/index-of out "well grounded")]
        (is (< i-completeness i-reasoning))
        (is (< i-reasoning i-grounding))))

    (testing "names each dimension and surfaces its score"
      (is (string/includes? out "completeness"))
      (is (string/includes? out "0.10")))

    (testing "includes the reasoning when present"
      (is (string/includes? out "r-c")))))

;; =============================================================================
;; extract-response — the producer's answer, NOT an echoed input field
;; =============================================================================

(deftest extract-response-test
  (testing "prefers the written answer over an echoed input (the regression)"
    ;; The output blackboard echoes :user-message (the question) alongside the
    ;; written :assistant-response. We must pick the answer, not the question.
    (is (= "A behavior tree is a model of plan execution."
           (metrics/extract-response
             {"user-message" "What is a behavior tree?"}
             {:user-message "What is a behavior tree?"
              :assistant-response "A behavior tree is a model of plan execution."}))))

  (testing "string-keyed answer key wins"
    (is (= "42" (metrics/extract-response {"q" "x"} {"answer" "42" "q" "x"}))))

  (testing "falls back to a non-input string output when no canonical key"
    (is (= "the-written-value"
           (metrics/extract-response
             {"in" "echoed"}
             {:in "echoed" :some-output "the-written-value"}))))

  (testing "a bare string output passes through"
    (is (= "hi" (metrics/extract-response {"q" "x"} "hi")))))

;; =============================================================================
;; metric return-shape handling — number OR {:score :feedback}
;; =============================================================================

(deftest score+feedback-of-test
  (testing "a bare number metric result yields that score and nil feedback"
    (is (= {:score 0.42 :feedback nil} (tp/score+feedback-of 0.42))))

  (testing "a {:score :feedback} metric result is passed through"
    (is (= {:score 0.7 :feedback "do better"}
           (tp/score+feedback-of {:score 0.7 :feedback "do better"}))))

  (testing "a {:score ...} map with no feedback yields nil feedback"
    (is (= {:score 0.3 :feedback nil}
           (tp/score+feedback-of {:score 0.3})))))

;; =============================================================================
;; reflective-dataset feedback selection — RICH feedback over the score string
;; =============================================================================

(deftest reflective-feedback-for-instance-test
  (testing "uses the candidate's stored rich feedback for the instance when present"
    (let [feedbacks {0 "RICH: completeness (0.10): missing aspects"
                     1 "RICH: grounding (0.00): fabricated"}]
      (is (= "RICH: grounding (0.00): fabricated"
             (tp/reflective-feedback-for-instance feedbacks 1 0.0 "the-answer")))))

  (testing "falls back to the legacy score string when no rich feedback exists (back-compat)"
    (let [fb (tp/reflective-feedback-for-instance {} 2 0.0 "the-answer")]
      (is (string/includes? fb "scored"))
      (is (string/includes? fb "the-answer"))))

  (testing "falls back when the per-instance entry is blank"
    (let [fb (tp/reflective-feedback-for-instance {3 "   "} 3 0.5 "exp")]
      (is (string/includes? fb "scored")))))
