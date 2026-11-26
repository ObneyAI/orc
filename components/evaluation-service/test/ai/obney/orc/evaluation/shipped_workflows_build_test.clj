(ns ai.obney.orc.evaluation.shipped-workflows-build-test
  "Every evaluation workflow the component ships builds as an ordinary
   workflow. Building only: execution and aggregation are covered elsewhere."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface :as evaluation]))

(defn- build [definition-fn]
  (h/with-async-test-context [ctx]
    (try {:sheet-id (sheet/build-workflow! ctx (definition-fn))}
         (catch clojure.lang.ExceptionInfo e
           {:failure (ex-message e) :data (ex-data e)}))))

(defmacro ^:private builds [label definition-fn]
  `(deftest ~(symbol (str label "-builds"))
     (let [r# (build ~definition-fn)]
       (is (uuid? (:sheet-id r#)) (str ~label " builds: " (pr-str r#))))))

(builds "grounding-judge-sheet" evaluation/grounding-judge-sheet)
(builds "instruction-judge-sheet" evaluation/instruction-judge-sheet)
(builds "reasoning-judge-sheet" evaluation/reasoning-judge-sheet)
(builds "completeness-judge-sheet" evaluation/completeness-judge-sheet)
(builds "evaluation-suite" evaluation/evaluation-suite)
(builds "batch-evaluation-suite" evaluation/batch-evaluation-suite)
(builds "selective-judge-suite"
        #(evaluation/selective-judge-suite
          [:grounding :instruction-following :reasoning :completeness]))
(builds "selective-judge-suite-single-judge"
        #(evaluation/selective-judge-suite [:grounding]))
