(ns ai.obney.orc.orc-service.real-llm-adaptive-loop-e2e-test
  "Gated real-OpenRouter journeys for DET-E2E-101 and DET-E2E-102.

  Every model-backed role uses its explicitly pinned live model. No provider
  behavior is replaced or scripted."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.colbert.interface]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.core.read-models :as read-models]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- command! [ctx command]
  (cp/process-command
   (assoc ctx :command (merge {:command/id (random-uuid)
                               :command/timestamp (time/now)}
                              command))))

(defn- trace-events [ctx trace-id event-type]
  (filterv #(and (= event-type (:event/type %))
                 (= trace-id (:tick-id %)))
           (live/events ctx)))

(defn- injection-record [ctx sheet-id root-trace-id]
  (some #(when (= root-trace-id (:root-trace-id %)) %)
        (vals (read-models/get-injection-records ctx sheet-id))))

(defn evaluate-real-output
  "Deterministic consumer evaluator for a nondeterministic real-model output."
  [{:keys [inputs]}]
  (let [answer (get-in inputs [:host-outputs :answer])
        answered? (and (string? answer) (not (str/blank? answer)))]
    {:score (if answered? 1.0 0.0)
     :feedback (if answered?
                 "The real execution produced a non-empty answer."
                 "The real execution did not produce an answer.")}))

(def ^:private host-io-schema
  [:map [:answer {:optional true} :string]])

(def ^:private host-trace-schema
  [:map [:node-id {:optional true} :uuid]])

(defn- attach-output-judge! [ctx sheet-id node-id]
  (let [eval-sheet-id
        (sheet/build-workflow!
         ctx
         (sheet/workflow "det-e2e-101-output-evaluator"
           (sheet/blackboard {:host-inputs host-io-schema :host-outputs host-io-schema
                              :host-instruction :string :host-trace host-trace-schema
                              :score :double :feedback :string})
           (sheet/code "evaluate-real-output"
             :fn "ai.obney.orc.orc-service.real-llm-adaptive-loop-e2e-test/evaluate-real-output"
             :reads [:host-outputs]
             :writes [:score :feedback])))]
    (h/run-and-apply!
     ctx (h/make-declare-judge-command
          sheet-id "real-output-present"
          {:type :custom :sheet-id eval-sheet-id}))
    (h/run-and-apply!
     ctx (h/make-set-node-judges-command
          sheet-id node-id ["real-output-present"]))))

(def baseline-body
  {:capabilities ["Solve a question with traceable evidence"]
   :strengths [{:trait "retain-evaluation-evidence"
                :good-when "later executions need proven guidance"
                :recommended-pattern "link guidance to the evaluated trace"
                :confidence 0.9 :evidence-count 3
                :first-observed-at "2026-08-01T00:00:00Z"
                :last-reinforced-at "2026-08-06T00:00:00Z"}]
   :weaknesses []
   :representative-uses ["adaptive question answering"]
   :avoid-when ["there is no evaluation evidence"]
   :summary "Baseline adaptive researcher guidance."
   :version 1
   :consolidated-from-event-count 3})

(def closed-loop-instruction
  (str "Answer the question using recursive RLM mode. Emit a small real tree, "
       "inspect its result, and call final! with a concise answer."))

(deftest det-e2e-101-real-llm-closed-self-learning-loop
  (testing "evaluated evidence changes matching later guidance but not a control"
    (do
      (live/with-real-openrouter
      (live/register-openrouter!)
      (h/with-async-test-context
        [ctx {:context {:llm-provider :openrouter
                        :model live/openrouter-model
                        :ontology-consolidator-model live/openrouter-model
                        :injection-capture-rendered-block? true}}]
        (command! ctx {:command/name :ontology/record-node-type-description
                       :target-id :repl-researcher :body baseline-body})
        (command! ctx {:command/name :ontology/set-consolidation-threshold
                       :target-type :node-type :threshold 100})
        (command! ctx {:command/name :ontology/set-living-description-enabled
                       :enabled? true})
        (let [{:keys [sheet-id node-id]} (live/build-recursive-rlm!
                                          ctx {:name "det-e2e-101-adaptive"
                                               :instruction closed-loop-instruction
                                               :rlm {:auto-classify? true}
                                               :max-iterations 6})
              _ (attach-output-judge! ctx sheet-id node-id)
              first-result (sheet/execute ctx sheet-id
                                          {:question "Explain evidence retention."}
                                          :timeout-ms 180000 :llm-call-budget 10)
              first-trace (:trace-id first-result)
              host-completion (some #(when (and
                                             (= :sheet/node-execution-completed
                                                (:event/type %))
                                             (= first-trace (:tick-id %))
                                             (= node-id (:node-id %)))
                                      %)
                                    (live/events ctx))]
          (is (= :success (:status first-result)) (pr-str first-result))
          (is (seq (live/assert-live-provenance! ctx first-trace)))
          (is (ontology/get-living-description-enabled? ctx)
              "the evaluator runtime gate is durably enabled")
          (is host-completion "the host completion event is durable")
          (is (some #(= first-trace (:execution-id %))
                    (live/events ctx :rlm/tree-generated))
              "the real model must emit the tree that the evaluator scores")
          (is (h/settle-until!
               #(seq (trace-events ctx first-trace :judge/score-emitted))
               :timeout-ms 15000)
              (str "the evaluator must durably score the first trace; "
                   "all judge events="
                   (pr-str (live/events ctx :judge/score-emitted))))
          (let [judge-events (trace-events ctx first-trace :judge/score-emitted)]
            (is (= 1 (count judge-events)))
            (doseq [event judge-events]
              (is (= "real-output-present" (:judge-name event)))
              (is (number? (:score event)))))
          (let [consolidation-result
                (command! ctx {:command/name :ontology/request-consolidation
                               :target-type :node-type
                               :target-id :repl-researcher
                               :on-demand? true})]
            (is (seq (:command-result/events consolidation-result))
                "consolidation is requested only after evaluation is durable"))
          (is (h/settle-until!
               #(> (count (ontology/get-description-history
                           ctx :node-type :repl-researcher)) 1)
               :timeout-ms 180000)
              "evaluation evidence must produce a successor description")
          (let [history (ontology/get-description-history
                         ctx :node-type :repl-researcher)
                successor (last history)
                successor-body (:body successor)
                successor-event (last (filter #(and
                                                (= :ontology/node-type-description-updated
                                                   (:event/type %))
                                                (= :repl-researcher (:target-id %)))
                                              (live/events ctx)))
                second-result (sheet/execute ctx sheet-id
                                             {:question "Explain evidence retention again."}
                                             :timeout-ms 180000 :llm-call-budget 10)
                injected (injection-record ctx sheet-id (:trace-id second-result))
                control (live/build-recursive-rlm!
                         ctx {:name "det-e2e-101-control"
                              :instruction closed-loop-instruction
                              :rlm {:auto-classify? false}
                              :max-iterations 6})
                control-result (sheet/execute ctx (:sheet-id control)
                                              {:question "Unrelated control question."}
                                              :timeout-ms 180000 :llm-call-budget 10)
                control-injected (injection-record ctx (:sheet-id control)
                                                   (:trace-id control-result))]
            (is (= :success (:status second-result)) (pr-str second-result))
            (is (= :success (:status control-result)) (pr-str control-result))
            (live/assert-pinned-model! (:model-provenance successor-event))
            (is (some #(= first-trace (:tick-id %))
                      (trace-events ctx first-trace :judge/score-emitted))
                "projected learning is backed by the first durable evaluation")
            (is (str/includes? (:rendered-block injected) (:summary successor-body))
                "the matching execution receives the exact current body")
            (is (str/includes? (:rendered-block injected)
                               (str (:target-id successor-event)))
                "the injected payload retains the guidance identity")
            (is (or (nil? control-injected)
                    (and (not (str/includes? (:rendered-block control-injected)
                                             (:summary successor-body)))
                         (not (str/includes? (:rendered-block control-injected)
                                             (str (:target-id successor-event))))))
                "an unclassified control receives neither guidance identity nor body"))))))))

(def mint-name "det-e2e-102-novel-evidence-lattice")
(def mint-sentinel "NOVEL-EVIDENCE-LATTICE-102")

(def mint-instruction
  (str "Use recursive RLM mode. Before final!, call mint-behavior! exactly once "
       "with name `" mint-name "`. Its valid body must include summary `"
       mint-sentinel " preserves independently verified claims across branches`, "
       "one capability, principle-shaped strengths and weaknesses, one "
       "representative use, version 1 and consolidated-from-event-count 0. "
       "Then emit a small tree and finish the question."))

(deftest det-e2e-102-real-llm-mint-index-retrieve-and-reuse
  (testing "a real RLM mint is indexed, classified and injected into a later run"
    (do
      (live/with-real-openrouter
      (live/register-openrouter-model! live/openrouter-strong-model)
      (h/with-async-test-context
        [ctx {:context {:llm-provider :openrouter
                        :model live/openrouter-strong-model
                        :injection-capture-rendered-block? true}}]
        (let [signature (str mint-sentinel " independently verified claims")
              before (ontology/classify-behaviors
                      ctx {:task-signature signature :threshold 0.75 :top-n 5
                           :model live/openrouter-strong-model})
              {:keys [sheet-id]} (live/build-recursive-rlm!
                                  ctx {:name "det-e2e-102-mint"
                                       :instruction mint-instruction
                                       :rlm {:auto-classify? false}
                                       :model live/openrouter-strong-model
                                       :max-iterations 7})
              result (sheet/execute ctx sheet-id {:question signature}
                                    :timeout-ms 180000 :llm-call-budget 12)
              trace-id (:trace-id result)]
          (is (= :success (:status result)) (pr-str result))
          (is (seq (live/assert-live-provenance-for-model!
                    ctx trace-id live/openrouter-strong-model)))
          (is (h/settle-until!
               #(some (fn [e] (= mint-name (:name e)))
                      (live/events ctx :ontology/behavioral-subtree-minted))
               :timeout-ms 30000))
          (let [audits (filterv #(= mint-name (:name %))
                                (live/events ctx :ontology/behavioral-subtree-minted))
                audit (first audits)
                behavior-id (:target-id audit)]
            (is (= 1 (count audits)) "the real model must mint exactly once")
            (is (= trace-id (:minted-by-tick-id audit)))
            (is (= sheet-id (:minted-by-sheet-id audit)))
            (is (not-any? #(= behavior-id (:behavior-id %)) (:behaviors before))
                "pre-mint classification cannot retrieve the future identity")
            (is (h/settle-until!
                 #(and (:index-built? (ontology/get-reindex-state ctx))
                       (zero? (:events-since-last-rebuild
                               (ontology/get-reindex-state ctx))))
                 :timeout-ms 180000)
                "mint-triggered index rebuild must activate")
            (let [index-events (live/events ctx :colbert/index-created)
                  indexed? (some (fn [event]
                                   (some #(= behavior-id (:target-id %))
                                         (:document-metadatas event)))
                                 index-events)
                  classified (ontology/classify-behaviors
                              ctx {:task-signature signature :threshold 0.6 :top-n 5
                                   :model live/openrouter-strong-model})
                  match (some #(when (= behavior-id (:behavior-id %)) %) 
                              (:behaviors classified))
                  later (live/build-recursive-rlm!
                         ctx {:name "det-e2e-102-reuse"
                              :instruction closed-loop-instruction
                              :model live/openrouter-strong-model
                              :rlm {:auto-classify? true
                                    :auto-classify-behavioral-threshold 0.6}
                              :max-iterations 6})
                  later-result (sheet/execute ctx (:sheet-id later)
                                              {:question signature}
                                              :timeout-ms 180000 :llm-call-budget 10)
                  injected (injection-record ctx (:sheet-id later)
                                             (:trace-id later-result))
                  description (ontology/get-description
                               ctx :tree-fingerprint behavior-id)]
              (is indexed? "the active index source corpus names the minted identity")
              (is match (pr-str classified))
              (is (>= (:confidence match) 0.6))
              (is (= mint-sentinel (first (str/split (:summary description) #" "))))
              (is (= :success (:status later-result)) (pr-str later-result))
              (is (str/includes? (:rendered-block injected) mint-sentinel))
              (is (str/includes? (:rendered-block injected) (str behavior-id)))
              (is (= trace-id (:minted-by-tick-id audit))
                  "description provenance resolves through its audit to the live trace")))))))))
