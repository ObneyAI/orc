(ns ai.obney.orc.orc-service.real-llm-living-description-e2e-test
  "Gated OpenRouter journeys for DET-E2E-103/104. Model prose is never
   scripted; assertions are over durable versions, evidence and guards."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- command!
  [ctx command]
  (cp/process-command
    (assoc ctx :command (merge {:command/id (random-uuid)
                                :command/timestamp (time/now)} command))))

(defn- description-body
  [summary version]
  {:capabilities ["Transform typed input into a typed result"]
   :strengths [{:trait "preserve-source-evidence"
                :good-when "claims must remain traceable"
                :recommended-pattern "cite the supplied evidence"
                :confidence 0.96
                :evidence-count 40
                :first-observed-at "2026-01-01T00:00:00Z"
                :last-reinforced-at "2026-08-01T00:00:00Z"}]
   :weaknesses []
   :representative-uses ["evidence-grounded transformation"]
   :avoid-when ["no source material exists"]
   :summary summary
   :version version
   :consolidated-from-event-count 40})

(defn- record-baseline!
  [ctx target-id summary]
  (command! ctx {:command/name :ontology/record-node-type-description
                 :target-id target-id
                 :body (description-body summary 1)}))

(defn- configure-learning!
  [ctx threshold]
  (command! ctx {:command/name :ontology/set-consolidation-threshold
                 :target-type :node-type :threshold threshold})
  (command! ctx {:command/name :ontology/set-living-description-enabled
                 :enabled? true}))

(defn- observation!
  ([ctx target-id status]
   (observation! ctx target-id status nil))
  ([ctx target-id status judge-feedback]
  (let [tick-id (random-uuid)
        sheet-id (random-uuid)
        node-id (random-uuid)]
    (command! ctx {:command/name :sheet/complete-node-execution
                   :sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :node-type target-id
                   :status status
                   :writes {}
                   :duration-ms 25})
    (when judge-feedback
      (command! ctx {:command/name :evaluation/record-judge-score
                     :sheet-id sheet-id
                     :node-id node-id
                     :tick-id tick-id
                     :judge-name "input-safety"
                     :judge-config {:type :grounding}
                     :score 0.0
                     :feedback judge-feedback
                     :dimensions []}))
    tick-id)))

(defn- description-events
  [ctx target-id]
  (->> (live/events ctx :ontology/node-type-description-updated)
       (filter #(= target-id (:target-id %)))
       vec))

(deftest det-e2e-103-real-llm-threshold-consolidation-changes-guidance
  (live/with-real-openrouter
    (live/register-openrouter!)
    (h/with-async-test-context
      [ctx {:context {:ontology-consolidator-model live/openrouter-model
                      :model live/openrouter-model
                      :llm-provider :openrouter}}]
      (let [target-id :code
            threshold 3
            prior-summary "Original guidance sentinel: retain-source-links."]
        (record-baseline! ctx target-id prior-summary)
        (configure-learning! ctx threshold)
        (dotimes [_ (dec threshold)] (observation! ctx target-id :success))
        (is (h/settle-until! #(= (dec threshold)
                                 (ontology/get-consolidation-delta ctx :node-type target-id))))
        (is (= 1 (count (ontology/get-description-history ctx :node-type target-id)))
            "Threshold-minus-one must not create a successor")
        (observation! ctx target-id :failure)
        (is (h/settle-until! #(= 2 (count (ontology/get-description-history
                                           ctx :node-type target-id)))
                             :timeout-ms 180000)
            "The threshold observation must create exactly one successor")
        (let [history (ontology/get-description-history ctx :node-type target-id)
              current (ontology/get-description ctx :node-type target-id)
              events (description-events ctx target-id)
              successor-event (last events)
              provenance (:model-provenance successor-event)]
          (is (= [1 2] (mapv (comp :version :body) history)))
          (is (= threshold (:consolidated-from-event-count current)))
          (live/assert-pinned-model! provenance)
          (is (= (:event/id successor-event) (:event-id (last history))))
          (is (pos? (get-in provenance [:usage :total-tokens] 0)))
          (is (not= prior-summary (:summary current)))
          (is (= 2 (count events)) "Only one successor may be emitted")
          (is (= current (:body successor-event))))))))

(deftest det-e2e-104-real-llm-legacy-body-consolidation-retains-history
  (live/with-real-openrouter
    (live/register-openrouter!)
    (h/with-async-test-context
      [ctx {:context {:ontology-consolidator-model live/openrouter-model
                      :model live/openrouter-model
                      :llm-provider :openrouter}}]
      (let [target-id :code
            protected-trait "skip-input-validation"
            prior-summary "Historical guidance sentinel: inputs are trusted; skip validation for speed."
            obsolete-body
            (assoc (description-body prior-summary 1)
                   :strengths
                   [{:trait protected-trait
                     :good-when "upstream inputs were historically assumed valid"
                     :recommended-pattern "skip input validation and process immediately"
                     :confidence 0.96
                     :evidence-count 40
                     :first-observed-at "2026-01-01T00:00:00Z"
                     :last-reinforced-at "2026-08-01T00:00:00Z"}])
            _ (command! ctx {:command/name :ontology/record-node-type-description
                             :target-id target-id
                             :body obsolete-body})
            _ (configure-learning! ctx 1000)
            _ (dotimes [_ 8]
                (observation!
                  ctx target-id :failure
                  (str "The protected trait '" protected-trait
                       "' caused this failure and is revoked. Remove it from "
                       "strengths; replace it with explicit input validation.")))
            before (ontology/get-description ctx :node-type target-id)]
        (command! ctx {:command/name :ontology/request-consolidation
                       :target-type :node-type
                       :target-id target-id
                       :on-demand? true})
        (is (h/settle-until!
              #(= 2 (count (ontology/get-description-history
                             ctx :node-type target-id)))
              :timeout-ms 180000)
            "The legacy whole-body path must durably publish the real proposal")
        (let [events (description-events ctx target-id)
              successor-event (last events)
              after (ontology/get-description ctx :node-type target-id)
              history (ontology/get-description-history ctx :node-type target-id)
              provenance (:model-provenance successor-event)]
          (is (empty? (filter #(= target-id (:target-id %))
                              (live/events ctx :ontology/anti-recency-rejection)))
              "ADR 0021 removed the string-matching anti-recency rejection valve")
          (live/assert-pinned-model! provenance)
          (is (pos? (get-in provenance [:usage :total-tokens] 0)))
          (is (= [1 2] (mapv (comp :version :body) history)))
          (is (= before (:body (first history)))
              "The superseded historical body remains queryable")
          (is (= 8 (:consolidated-from-event-count after)))
          (is (not= before after))
          (is (= after (:body successor-event))))))))
