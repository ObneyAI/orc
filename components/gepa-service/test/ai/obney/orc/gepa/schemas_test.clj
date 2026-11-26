(ns ai.obney.orc.gepa.schemas-test
  (:require [ai.obney.orc.gepa.interface.schemas]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

(deftest persisted-timestamps-accept-grain-and-ledger-representations
  (let [written-at (java.time.OffsetDateTime/parse "2026-01-01T00:00:00-06:00")]
    (doseq [value [written-at (.toInstant written-at) (str (.toInstant written-at))]]
      (is (m/validate :gepa/timestamp value)))
    (doseq [value [nil 42 "2026-02-31" "not a timestamp"]]
      (is (false? (m/validate :gepa/timestamp value))))))

(deftest reflective-examples-validate-nested-input-and-output-values
  (let [example {"Inputs" {"evidence" [{:score 0.75 :tags #{:observed :verified}}]}
                 "Generated Outputs" {"rows" [nil {"answer" true}]}
                 "Feedback" "Both observations agree."}]
    (is (m/validate :gepa/reflective-example example))
    (is (false? (m/validate :gepa/reflective-example
                           (assoc-in example ["Inputs" "evidence" 0 :score] (Object.)))))))
