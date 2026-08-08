(ns ai.obney.orc.orc-service.real-llm-projection-replay-e2e-test
  "Gated real-model replay journey for DET-E2E-119. Replay copies immutable
  evidence into a processor-free context, so a passing test proves that no
  provider call is needed to reconstruct public state."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- command! [ctx command]
  (cp/process-command
   (assoc ctx :command (merge {:command/id (random-uuid)
                               :command/timestamp (time/now)} command))))

(defn- workflow []
  (sheet/workflow "det-e2e-119-replay-source"
    (sheet/blackboard {:question :string :answer :string})
    (sheet/judges
     {:replay-judge {:type :instruction-following
                     :provider :openrouter
                     :model live/openrouter-model
                     :criteria "Answer the supplied question."}})
    (sheet/llm "replay-leaf"
      :model live/openrouter-model
      :instruction "Answer concisely and include the supplied sentinel."
      :reads [:question] :writes [:answer]
      :judges ["replay-judge"])))

(defn- metric [example outputs]
  (if (and (string? (:answer outputs))
           (.contains ^String (:answer outputs) (get example "sentinel")))
    1.0 0.0))

(defn- restore-events!
  "Restore persisted event envelopes into a fresh in-memory store.  `append`
  intentionally assigns new IDs/timestamps and is therefore ingestion, not
  replay; snapshot restoration must retain those stable provenance IDs."
  [ctx events]
  (let [tenant-id (:tenant-id ctx)
        events (vec events)]
    (assert (every? #(and (:event/id %) (:event/timestamp %)) events))
    (dosync
     (ref-set (-> ctx :event-store :state)
              {:events (mapv #(assoc % :grain/tenant-id tenant-id) events)
               :tenants {tenant-id
                         {:tenant/last-event-id (:event/id (last events))}}}))))

(defn- public-state [ctx sheet-id node-id tick-id ontology-id behavior-id optimization-id]
  {:sheet (sheet/get-sheet ctx sheet-id)
   :nodes (sheet/get-nodes-for-sheet ctx sheet-id)
   :blackboard (sheet/get-blackboard-by-key ctx sheet-id)
   :trace (get-in (h/run-query ctx (h/make-get-trace-query tick-id))
                  [:query/result :trace])
   :evaluation (evaluation/get-judge-scores ctx sheet-id node-id tick-id)
   :description (ontology/get-description ctx :tree-fingerprint behavior-id)
   :description-history (ontology/get-description-history
                         ctx :tree-fingerprint behavior-id)
   :gepa-summary (gepa/get-optimization-summary ctx optimization-id)
   :gepa-population (gepa/get-population-state ctx optimization-id)
   :ontology-id ontology-id})

(deftest det-e2e-119-real-llm-cross-component-projection-replay
  (testing "real nondeterministic outputs replay to equal public adaptive state without new calls"
    (live/with-real-openrouter
      (live/register-openrouter!)
      (h/with-async-test-context [ctx]
        (command! ctx {:command/name :ontology/set-living-description-enabled
                       :enabled? true})
        (let [ctx (assoc ctx :llm-provider :openrouter)
              sentinel "REPLAY-SENTINEL-119"
              sheet-id (sheet/build-workflow! ctx (workflow))
              node-id (some #(when (= "replay-leaf" (:name %)) (:id %))
                            (sheet/get-nodes-for-sheet ctx sheet-id))
              result (sheet/execute ctx sheet-id
                                    {:question (str "Return " sentinel)}
                                    :timeout-ms 180000)
              tick-id (:trace-id result)]
          (is (= :success (:status result)) (pr-str result))
          (is (seq (live/assert-live-provenance! ctx tick-id)))
          (is (h/settle-until!
               #(seq (evaluation/get-judge-scores
                      ctx sheet-id node-id tick-id))
               :timeout-ms 180000))
          (let [answer (get-in result [:outputs :answer])
                ontology-id (random-uuid)
                mint-name "det-e2e-119-replayed-behavior"
                mint-result (command!
                             ctx {:command/name :ontology/mint-behavioral-subtree
                                  :name mint-name
                                  :body {:capabilities ["Replay model-derived answer"]
                                         :strengths [] :weaknesses []
                                         :representative-uses [sentinel]
                                         :avoid-when ["events are unavailable"]
                                         :summary (str sentinel " " answer)
                                         :version 1
                                         :consolidated-from-event-count 1}
                                  :provenance :agent-minted
                                  :minted-by-sheet-id sheet-id
                                  :minted-by-tick-id tick-id})
                behavior-id (->> (:command-result/events mint-result)
                                 (some #(when (= :ontology/behavioral-subtree-minted
                                                 (:event/type %))
                                          (:target-id %))))
                optimization (gepa/optimize!
                              ctx {:sheet-id sheet-id
                                   :trainset [{"question" (str "Return " sentinel)
                                               "sentinel" sentinel}]
                                   :valset [{"question" (str "Return " sentinel)
                                             "sentinel" sentinel}]
                                   :metric-fn metric
                                   :config {:max-metric-calls 1
                                            :reflection-minibatch-size 1
                                            :reflection-lm live/openrouter-model
                                            :skip-perfect-score true}
                                   :inherit-from-previous false
                                   :block? true :timeout-ms 300000})
                optimization-id (:optimization-id optimization)
                source-events (live/events ctx)
                source-calls (filter #(and (= :sheet/node-execution-completed
                                                (:event/type %))
                                           (:model %)) source-events)]
            (is (uuid? behavior-id))
            (is (= :completed (:status optimization)) (pr-str optimization))
            (doseq [call source-calls]
              (live/assert-pinned-model! call)
              (is (map? (:usage call)))
              (is (or (seq (:write-sources call))
                      (seq (:write-keys call)))
                  "each call has durable content linkage as well as usage"))
            (let [before (public-state ctx sheet-id node-id tick-id
                                       ontology-id behavior-id optimization-id)
                  replay (h/create-test-context)]
              (try
                (let [domain-events (filterv #(not= "grain"
                                                     (namespace (:event/type %)))
                                             source-events)]
                  (restore-events! replay domain-events)
                  (is (= domain-events (live/events replay))
                      "replay preserves immutable event IDs and timestamps")
                  (let [after (public-state replay sheet-id node-id tick-id
                                            ontology-id behavior-id optimization-id)
                        replay-calls (live/model-completions replay)]
                    (is (= before after)
                        "all participating public projections reconstruct exactly")
                    (is (= (count source-calls) (count replay-calls)))
                    (is (= (mapv #(select-keys % [:model :usage :write-keys]) source-calls)
                           (mapv #(select-keys % [:model :usage :write-keys]) replay-calls))
                        "replay retained recorded calls and performed zero new calls")
                    (is (= (count (ontology/get-description-history
                                   ctx :tree-fingerprint behavior-id))
                           (count (ontology/get-description-history
                                   replay :tree-fingerprint behavior-id))))))
                (finally (h/stop-context replay))))))))))
