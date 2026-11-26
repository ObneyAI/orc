(ns ai.obney.orc.evaluation.core.read-models
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.evaluation.core.logic :as logic]))

;; --- Assessments ---

(rmp/defreadmodel :evaluation assessments
  {:events logic/lifecycle-event-types :version 1}
  [state event] (logic/assessments* state event))

;; --- Performance ---

(rmp/defreadmodel :evaluation performance
  {:events logic/lifecycle-event-types :version 1}
  [state event] (logic/performance* state event))

;; --- Judge runtime ---

(rmp/defreadmodel :evaluation judge-scores
  {:events #{:judge/score-emitted} :version 1}
  [state event] (logic/judge-scores* state event))
