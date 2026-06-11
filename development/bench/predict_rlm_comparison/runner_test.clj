(ns predict-rlm-comparison.runner-test
  "Unit tests for the small testable helpers in the comparison runner.

   End-to-end behaviour (live LLM call, EDN/trace file creation) is verified
   separately by running an existing task through the new runner; see
   docs/issues/predict-rlm/PR05-comparison-runner-with-capture.md."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [predict-rlm-comparison.runner :as runner]))

(use-fixtures :each
  (fn [f]
    ;; Ensure clean lock state across tests
    (runner/release-task-lock!)
    (try (f) (finally (runner/release-task-lock!)))))

(deftest lock-acquired-when-free
  (is (true? (runner/try-acquire-task-lock! "alpha"))))

(deftest lock-rejected-when-held
  (is (true? (runner/try-acquire-task-lock! "alpha")))
  (is (false? (runner/try-acquire-task-lock! "beta"))
      "second acquire returns false"))

(deftest lock-reusable-after-release
  (is (true? (runner/try-acquire-task-lock! "alpha")))
  (runner/release-task-lock!)
  (is (true? (runner/try-acquire-task-lock! "beta"))))

(deftest lock-state-reports-holder
  (runner/try-acquire-task-lock! "gamma")
  (let [s (runner/task-lock-state)]
    (is (map? s))
    (is (= "gamma" (:task-name s)))))
