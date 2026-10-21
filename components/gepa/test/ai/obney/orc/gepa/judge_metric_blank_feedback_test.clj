(ns ai.obney.orc.gepa.judge-metric-blank-feedback-test
  "A judge that returns no feedback must not mask GEPA's own fallback.

   GEPA's reflective dataset falls back to the score string with the Expected
   output (`reflective-feedback-for-instance`) when an instance has no rich
   feedback. A metric that renders a placeholder such as \"(no feedback)\" for
   blank judge feedback defeats that fallback: the proposer then sees a
   placeholder instead of what was expected. Driven through
   `make-judge-metric` with only the provider seam injected."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.gepa.core.metrics :as metrics]
            [ai.obney.orc.gepa.core.todo-processors :as tp]
            [ai.obney.orc.llm.interface :as llm]))

(defn- judge-answer [feedback]
  {:outputs {:level 4
             :reasoning "The answer is supported."
             :grounded-claims ["supported"]
             :ungrounded-claims []
             :feedback feedback}
   :usage {:total-tokens 1}
   :model "fake/judge"})

(deftest blank-judge-feedback-leaves-the-expected-fallback-in-force
  (testing "every dimension returned blank feedback: the metric's feedback is empty"
    (with-redefs [llm/predict (fn [& _] (judge-answer ""))]
      (let [metric (metrics/make-judge-metric {:grounding 1.0})
            {:keys [score feedback]} (metric {"q" "what?"} {:answer "supported"})]
        (is (= 0.75 score))
        (is (= "" feedback) (pr-str feedback))
        (is (string/includes?
             (tp/reflective-feedback-for-instance {0 feedback} 0 score "the expected answer")
             "Expected: the expected answer")
            "GEPA's existing Expected fallback fires")))))

(deftest real-judge-feedback-is-still-threaded
  (testing "non-blank feedback is rendered as before"
    (with-redefs [llm/predict (fn [& _] (judge-answer "Cite the ticket."))]
      (let [metric (metrics/make-judge-metric {:grounding 1.0})
            {:keys [feedback]} (metric {"q" "what?"} {:answer "supported"})]
        (is (string/includes? feedback "Cite the ticket.") (pr-str feedback))
        (is (not (string/includes? feedback "(no feedback)")))))))
