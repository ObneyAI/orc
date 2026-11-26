(ns ai.obney.orc.orc-service.dsl-roundtrip-bands-test
  "A banded decision's :bands-from survives build -> export -> DSL -> eval and
   export -> import, so a banded judge is not silently degraded to a plain
   decision by any public round trip."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.commands :as service-commands]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

(def ^:private fields [:executor :reads :writes :bands-from :instruction])

(deftest banded-decision-configuration-survives-public-roundtrips
  (h/with-test-context [ctx]
    (let [expected {:executor :decision
                    :instruction "Grade the evidence."
                    :reads [:evidence :rubric]
                    :writes [:band]
                    :bands-from :rubric}
          definition (service-logic/workflow "banded-decision-roundtrip"
                       (service-logic/dsl-blackboard
                        {:evidence :string
                         :rubric [:map [:bands [:map-of :int :string]]]
                         :band :int})
                       (service-logic/llm-decision "grade"
                         :instruction "Grade the evidence."
                         :reads [:evidence :rubric]
                         :bands-from :rubric
                         :writes [:band]))
          source-id (service-commands/build-workflow! ctx definition)
          exported (service-commands/export-sheet ctx source-id)
          dsl-code (service-logic/export-to-dsl exported)
          regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                        (eval (read-string dsl-code)))
          imported-id (service-commands/import-sheet
                       ctx (assoc-in exported [:sheet :name] "banded-decision-imported"))
          imported-node (:nodes (service-commands/export-sheet ctx imported-id))]
      (is (= expected (select-keys (:nodes exported) fields)))
      (is (= expected (select-keys (:root-node regenerated) fields)))
      (is (= expected (select-keys imported-node fields)))
      (is (re-find #":bands-from" dsl-code)))))
