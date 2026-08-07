(ns ai.obney.orc.orc-service.real-llm-gepa-e2e-test
  "Gated, real-OpenRouter acceptance tests for DET-E2E-110 through 112.

   Nothing in this namespace replaces task-model or proposer behavior. When
   the explicit live gate is closed the tests do not register a provider."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.read-models]
            [ai.obney.orc.gepa.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(def deliberately-bad-instruction
  "Always answer with exactly BANANA. The input is an opaque identifier: do not interpret it and never answer anything except BANANA.")

(def examples
  [{"question" "ALPHA" "expected" "ORCHID"}
   {"question" "BETA" "expected" "COBALT"}])

(defn exact-answer-metric
  [example outputs]
  (if (= (get example "expected") (:answer outputs)) 1.0 0.0))

(defn- live-gepa-workflow
  []
  (sheet/workflow "det-e2e-real-gepa"
    (sheet/blackboard {:question :string :answer :string})
    (sheet/llm "responder"
      :model live/openrouter-model
      :instruction deliberately-bad-instruction
      :reads [:question]
      :writes [:answer]
      :options {:use-function-calling? true})))

(defn- optimization-events
  [ctx optimization-id]
  (->> (live/events ctx)
       (filter #(= optimization-id (:optimization-id %)))
       vec))

(defn- dominates?
  [a b]
  (and (every? true? (map >= (:scores a) (:scores b)))
       (some true? (map > (:scores a) (:scores b)))))

(defn- version-instruction
  [version component]
  (letfn [(walk [node]
            (cons node (mapcat walk (:children node))))]
    (some (fn [node]
            (when (= component (:name node)) (:instruction node)))
          (walk (get-in version [:snapshot :nodes])))))

(defn- tick-instruction
  [ctx tick-id component]
  (let [started (some #(when (= tick-id (:tick-id %)) %)
                      (live/events ctx :sheet/tree-tick-started))]
    (some (fn [[_ node]]
            (when (= component (:name node)) (:instruction node)))
          (get-in started [:execution-snapshot :nodes-by-id]))))

(defn- restart-processors
  [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (rmp/l1-clear!)
  (let [base (dissoc ctx :processors)
        processors (reduce-kv
                     (fn [acc name {:keys [handler-fn topics]}]
                       (assoc acc name
                              (tp/start {:event-pubsub (:event-pubsub ctx)
                                         :topics topics
                                         :handler-fn handler-fn
                                         :context base})))
                     {} @tp/processor-registry*)]
    (assoc ctx :processors processors)))

(defn- await-resume-terminal!
  [ctx optimization-id timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [state (gepa/get-optimization-summary ctx optimization-id)]
        (cond
          (#{:completed :failed} (:status state)) state
          (>= (System/currentTimeMillis) deadline) state
          :else (do (Thread/sleep 100)
                    (recur)))))))

(deftest det-e2e-110-real-llm-gepa-lifecycle-to-pareto-completion
  (live/with-real-openrouter
    (live/register-openrouter!)
    (h/with-async-test-context [ctx]
      (let [ctx (assoc ctx :dscloj-provider :openrouter)
            sheet-id (sheet/build-workflow! ctx (live-gepa-workflow))
            configured-budget 20
            result (gepa/optimize!
                     ctx {:sheet-id sheet-id
                          :trainset examples
                          :valset examples
                          :metric-fn exact-answer-metric
                          :config {:max-metric-calls configured-budget
                                   :reflection-minibatch-size 1
                                   :reflection-lm live/openrouter-model
                                   :use-merge true
                                   :skip-perfect-score false}
                          :inherit-from-previous false
                          :block? true
                          :timeout-ms 600000})
            optimization-id (:optimization-id result)
            events (optimization-events ctx optimization-id)
            created-by-id (->> events
                               (filter #(= :gepa/candidate-created (:event/type %)))
                               (map (juxt :candidate-id identity))
                               (into {}))
            candidates (->> events
                            (filter #(= :gepa/candidate-evaluated (:event/type %)))
                            (map #(assoc (select-keys % [:candidate-id :scores :aggregate-score])
                                         :parent-ids
                                         (:parent-ids (get created-by-id (:candidate-id %)))))
                            vec)
            candidate-ids (set (map :candidate-id candidates))
            proposals (filter #(= :gepa/proposer-call-completed (:event/type %)) events)
            decisions (filter #(= :gepa/subsample-evaluated (:event/type %)) events)
            terminals (filter #(#{:gepa/optimization-completed
                                  :gepa/optimization-failed} (:event/type %)) events)
            state (gepa/get-population-state ctx optimization-id)
            frontier-state (gepa/get-pareto-frontier-state ctx optimization-id)
            frontier-ids (->> (vals (or (:best-at frontier-state) {}))
                              (mapcat #(if (set? %) % [%]))
                              set)
            frontier (filter #(contains? frontier-ids (:candidate-id %)) candidates)]
        (is (= :completed (:status result)) (pr-str result))
        (is (seq candidates) "Seed and proposed candidates must have durable evaluations")
        (is (seq proposals) "The lifecycle must include a real reflection proposal")
        (doseq [proposal proposals]
          (live/assert-pinned-model! proposal)
          (is (= :openrouter (:provider proposal)))
          (is (pos? (get-in proposal [:usage :total-tokens] 0)))
          (is (seq (:prompt proposal)))
          (is (seq (:response proposal)))
          (is (= 64 (count (:prompt-sha256 proposal))))
          (is (= 64 (count (:response-sha256 proposal)))))
        (doseq [decision decisions]
          (is (= (:accepted? decision)
                 (> (:proposed-sum decision) (:parent-sum decision)))
              (str "Strict acceptance mismatch: " (pr-str decision))))
        (doseq [candidate candidates
                parent-id (remove nil? (:parent-ids candidate))]
          (is (contains? candidate-ids parent-id)
              (str "Missing candidate ancestor " parent-id)))
        (doseq [a frontier b frontier :when (not= a b)]
          (is (not (dominates? a b))
              (str "Pareto member is dominated: " (pr-str [a b]))))
        (is (<= (:total-metric-calls state) configured-budget)
            (str "GEPA exceeded metric budget: " (pr-str state)))
        (is (= 1 (count terminals)) (pr-str terminals))
        (is (contains? candidate-ids (get-in result [:best-candidate :candidate-id])))
        (doseq [evaluation (filter #(= :gepa/candidate-evaluated (:event/type %)) events)
                trace-id (:trace-ids evaluation)]
          (is (some #(= trace-id (:tick-id %))
                    (live/events ctx :sheet/tree-tick-completed))
              (str "Evaluation points at a missing durable trace " trace-id)))))))

(deftest det-e2e-112-real-llm-optimized-instruction-publication-isolation
  (live/with-real-openrouter
    (live/register-openrouter!)
    (h/with-async-test-context [ctx]
      (let [ctx (assoc ctx :dscloj-provider :openrouter)
            sheet-id (sheet/build-workflow! ctx (live-gepa-workflow))
            _ (h/run-and-apply! ctx
                                (h/make-publish-version-command sheet-id
                                  :description "immutable optimization source"))
            v1-before (sheet/get-version ctx sheet-id 1)
            result (gepa/optimize!
                     ctx {:sheet-id sheet-id
                          :trainset examples
                          :valset examples
                          :metric-fn exact-answer-metric
                          :config {:max-metric-calls 20
                                   :reflection-minibatch-size 1
                                   :reflection-lm live/openrouter-model
                                   :use-merge false
                                   :skip-perfect-score false}
                          :inherit-from-previous false
                          :block? true
                          :timeout-ms 600000})
            optimization-id (:optimization-id result)
            winner (:best-candidate result)
            winner-instruction (get (:instructions winner) "responder")
            application (gepa/apply-winner! ctx optimization-id 1)
            v1-after (sheet/get-version ctx sheet-id 1)
            v2 (sheet/get-version ctx sheet-id 2)
            [v1-exec v2-exec] (mapv deref
                                    [(future (sheet/execute ctx sheet-id
                                               {:question "ALPHA"}
                                               :use-version 1 :timeout-ms 180000))
                                     (future (sheet/execute ctx sheet-id
                                               {:question "ALPHA"}
                                               :use-version 2 :timeout-ms 180000))])
            application-events (filter #(and (= :gepa/winner-applied (:event/type %))
                                             (= optimization-id (:optimization-id %)))
                                       (live/events ctx))
            proposer-events (filter #(and (= :gepa/proposer-call-completed (:event/type %))
                                          (= optimization-id (:optimization-id %)))
                                    (live/events ctx))]
        (is (= :completed (:status result)) (pr-str result))
        (is (seq proposer-events) "Winner lifecycle must have real proposer provenance")
        (doseq [event proposer-events] (live/assert-pinned-model! event))
        (is (= v1-before v1-after) "Applying the winner must not rewrite v1")
        (is (= deliberately-bad-instruction
               (version-instruction v1-after "responder")))
        (is (= winner-instruction (version-instruction v2 "responder")))
        (is (not= (version-instruction v1-after "responder")
                  (version-instruction v2 "responder")))
        (is (= 2 (:target-version application)))
        (is (not= (:source-fingerprint application)
                  (:target-fingerprint application)))
        (is (= 1 (count application-events)))
        (is (= 1 (:source-version (first application-events))))
        (is (= (:candidate-id winner) (:candidate-id (first application-events))))
        (is (= :success (:status v1-exec)) (pr-str v1-exec))
        (is (= :success (:status v2-exec)) (pr-str v2-exec))
        (is (= deliberately-bad-instruction
               (tick-instruction ctx (:trace-id v1-exec) "responder")))
        (is (= winner-instruction
               (tick-instruction ctx (:trace-id v2-exec) "responder")))
        (is (= 1 (:source-version (first application-events)))
            "Optimization provenance continues to name v1")))))

(deftest det-e2e-111-real-llm-gepa-failure-and-resumability-boundary
  (live/with-real-openrouter
    (live/register-openrouter!)
    (h/with-async-test-context [original-ctx]
      (let [ctx (assoc original-ctx :dscloj-provider :openrouter)
            sheet-id (sheet/build-workflow! ctx (live-gepa-workflow))
            budget 20
            start (gepa/optimize!
                    ctx {:sheet-id sheet-id
                         :trainset examples :valset examples
                         :metric-fn exact-answer-metric
                         :config {:max-metric-calls budget
                                  :reflection-minibatch-size 1
                                  :reflection-lm live/openrouter-model
                                  :use-merge false
                                  :skip-perfect-score false
                                  :pause-after-proposal? true}
                         :inherit-from-previous false
                         :block? false})
            optimization-id (:optimization-id start)]
        (is (h/settle-until!
              #(seq (optimization-events ctx optimization-id))
              :timeout-ms 180000))
        (is (h/settle-until!
              #(some (fn [e] (= :gepa/proposal-ready (:event/type e)))
                     (optimization-events ctx optimization-id))
              :timeout-ms 300000)
            "A real proposal must become durable before interruption")
        (let [before-events (optimization-events ctx optimization-id)
              proposal (first (filter #(= :gepa/proposal-ready (:event/type %)) before-events))
              before-proposer-ids (set (map :call-id
                                            (filter #(= :gepa/proposer-call-completed
                                                        (:event/type %)) before-events)))
              before-task-ids (set (map :call-id
                                        (filter #(= :gepa/task-call-completed
                                                    (:event/type %)) before-events)))
              _ (is (uuid? (:proposal-id proposal)))
              _ (is (empty? (filter #(and (= :gepa/subsample-evaluated (:event/type %))
                                           (= (:parent-id proposal) (:parent-id %))
                                           (= (:iteration proposal) (:iteration %)))
                                    before-events))
                    "Proposal has no terminal evaluation at the interruption point")
              rebuilt (restart-processors ctx)
              resume-result (gepa/resume! rebuilt optimization-id)
              final-state (await-resume-terminal! rebuilt optimization-id 600000)
              after-events (optimization-events rebuilt optimization-id)
              proposer-calls (filter #(= :gepa/proposer-call-completed (:event/type %)) after-events)
              task-calls (filter #(= :gepa/task-call-completed (:event/type %)) after-events)
              proposal-results (filter #(and (= :gepa/subsample-evaluated (:event/type %))
                                             (= (:parent-id proposal) (:parent-id %))
                                             (= (:iteration proposal) (:iteration %)))
                                       after-events)]
          (is (= :proposal-ready (:boundary resume-result)))
          (is (= :completed (:status final-state)) (pr-str final-state))
          (is (= 1 (count proposal-results)) "Interrupted proposal evaluates once")
          (is (= (count proposer-calls) (count (set (map :call-id proposer-calls))))
              "No proposer call ID is repeated")
          (is (= (count task-calls) (count (set (map :call-id task-calls))))
              "No task call ID is repeated")
          (is (every? (set (map :call-id proposer-calls)) before-proposer-ids))
          (is (every? (set (map :call-id task-calls)) before-task-ids))
          (doseq [call (concat proposer-calls task-calls)]
            (when (:model call) (live/assert-pinned-model! call)))
          (is (= (- budget (count before-task-ids))
                 (:remaining-budget resume-result))
              "Remaining metric budget subtracts only completed durable task calls")
          (is (<= (:total-metric-calls final-state) budget))
          (doseq [[_ processor] (:processors rebuilt)] (tp/stop processor)))))))
