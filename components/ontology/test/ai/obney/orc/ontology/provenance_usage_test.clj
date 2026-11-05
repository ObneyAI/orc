(ns ai.obney.orc.ontology.provenance-usage-test
  "ModelProvenance usage crosses public command/event/projection boundaries."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [litellm.router :as router]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.orc-service.test-helpers :as workflow-h]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.ontology.test-helpers :as h]))

(def rich-usage
  {:prompt-tokens 11 :completion-tokens 7 :total-tokens 18 :cost 0.00125
   :cost-details {:upstream-inference-cost 0.0009}
   :completion-tokens-details {:reasoning-tokens 3} :is-byok false
   :provider-extension {:reported nil}})

(def body
  {:capabilities ["Preserve verified evidence"] :strengths [] :weaknesses []
   :representative-uses ["Evidence review"] :avoid-when []
   :summary "Preserve verified evidence" :version 1 :consolidated-from-event-count 1})

(defn command! [ctx command]
  (cp/process-command
   (assoc ctx :command-registry (cp/global-command-registry)
              :query-registry (qp/global-query-registry)
              :command (merge {:command/id (random-uuid) :command/timestamp (time/now)} command))))

(defn events [ctx type]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{type}})))

(deftest descriptions-preserve-provider-usage-and-reject-invalid-token-counts
  (doseq [[command-name event-type granularity target]
          [[:ontology/record-node-type-description :ontology/node-type-description-updated :node-type :llm]
           [:ontology/record-node-instance-description :ontology/node-instance-description-updated :node-instance [(random-uuid) (random-uuid)]]
           [:ontology/record-tree-description :ontology/tree-description-updated :tree-fingerprint "usage-contract-tree"]]]
    (testing (str command-name)
      (h/with-test-context [ctx]
        (let [provenance {:trace-id (random-uuid) :model "fixture-model" :usage rich-usage}
              command {:command/name command-name :target-id target :body body :model-provenance provenance}
              result (command! ctx command)]
          (is (= 1 (count (:command-result/events result))) (pr-str result))
          (is (= provenance (:model-provenance (first (events ctx event-type)))))
          (is (= body (ontology/get-description ctx granularity target)))
          (let [invalid (command! ctx (assoc-in command [:model-provenance :usage :prompt-tokens] "eleven"))]
            (is (not (seq (:command-result/events invalid))) (pr-str invalid))
            (is (= 1 (count (events ctx event-type))))))))))

(deftest claim-deltas-preserve-provider-usage-and-reject-invalid-token-counts
  (h/with-test-context [ctx]
    (let [target (random-uuid) sheet-id (random-uuid) tick-id (random-uuid)
          provenance {:trace-id (random-uuid) :model "fixture-model" :usage rich-usage}
          judge (command! ctx {:command/name :evaluation/record-judge-score
                               :sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
                               :judge-name "verified-work" :judge-config {} :score 0.8
                               :feedback "The execution preserved the verified evidence and its supporting source."
                               :dimensions []})
          command {:command/name :ontology/record-claim-deltas
                   :granularity :tree-class :target-identifier target :claim-set-version 0
                   :model-provenance provenance
                   :deltas [{:operation :add :kind :strength :content "Preserve verified evidence"
                             :episodes [[sheet-id tick-id]] :from-legacy-corpus false}]}
          result (command! ctx command)]
      (is (seq (:command-result/events judge)) (pr-str judge))
      (is (seq (:command-result/events result)) (pr-str result))
      (is (= provenance (:model-provenance (first (events ctx :ontology/claim-deltas-recorded)))))
      (is (= ["Preserve verified evidence"] (mapv :content (ontology/get-claims ctx :tree-class target))))
      (let [invalid (command! ctx (-> command
                                     (assoc :claim-set-version 1)
                                     (assoc-in [:model-provenance :usage :completion-tokens] 1.5)))]
        (is (not (seq (:command-result/events invalid))) (pr-str invalid))
        (is (= 1 (count (events ctx :ontology/claim-deltas-recorded))))))))

(deftest successful-reflection-with-cost-produces-a-projected-successor
  ;; Inject only the provider boundary. The actual executor, marker parser,
  ;; consolidation commands, event validation and projections all run.
  (let [fields [:capabilities :strengths :weaknesses :representative-uses :avoid-when :summary]
        response (str/join "\n" (map #(str "[[ ## " (name %) " ## ]]\n"
                                                       (if (string? (get body %))
                                                         (get body %)
                                                         (json/generate-string (get body %)))) fields))]
    (router/register! :provenance-usage-fixture
                      {:provider :openrouter :model "fixture-model" :config {}})
    (with-redefs [router/completion
                  (fn [_ _]
                    {:model "fixture-model" :usage rich-usage
                     :choices [{:finish-reason "stop" :message {:role "assistant" :content response}}]})]
      (workflow-h/with-async-test-context
        [ctx {:context {:llm-provider :provenance-usage-fixture
                        :ontology-consolidator-model "fixture-model"}}]
        (let [seed (command! ctx {:command/name :ontology/record-node-type-description
                                 :target-id :llm :body body})]
          (is (seq (:command-result/events seed)) (pr-str seed)))
        (consolidator/consolidate! ctx :node-type :llm)
        (let [history (ontology/get-description-history ctx :node-type :llm)
              successor (last (events ctx :ontology/node-type-description-updated))
              completion (first (events ctx :sheet/node-execution-completed))]
          (is (= :success (:status completion)) (pr-str completion))
          (is (= 2 (count history)))
          (is (= 2 (:version (ontology/get-description ctx :node-type :llm))))
          ;; Completion provenance carries normalized totals/cost. Detailed
          ;; provider accounting is retained separately on the attempt event.
          (is (= (:usage completion) (get-in successor [:model-provenance :usage])))
          (is (= (select-keys rich-usage [:prompt-tokens :completion-tokens :total-tokens :cost])
                 (:usage completion)))
          (is (= rich-usage
                 (get-in (first (events ctx :sheet/provider-attempt-completed))
                         [:outcome :provider-observations :usage])))
          (is (= "fixture-model" (get-in successor [:model-provenance :model])))
          (is (= (:tick-id completion) (get-in successor [:model-provenance :trace-id])))
          (is (empty? (events ctx :ontology/description-consolidation-failed))))))))

(deftest absent-and-explicit-null-cost-remain-distinguishable
  (doseq [usage [{:total-tokens 2} {:total-tokens 2 :cost nil :cost-details nil}]]
    (h/with-test-context [ctx]
      (let [provenance {:trace-id (random-uuid) :model "fixture-model" :usage usage}
            result (command! ctx {:command/name :ontology/record-node-type-description
                                  :target-id :llm :body body :model-provenance provenance})]
        (is (seq (:command-result/events result)) (pr-str result))
        (is (= usage (get-in (first (events ctx :ontology/node-type-description-updated))
                            [:model-provenance :usage])))))))
