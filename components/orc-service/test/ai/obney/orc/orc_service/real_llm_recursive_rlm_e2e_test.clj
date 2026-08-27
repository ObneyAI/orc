(ns ai.obney.orc.orc-service.real-llm-recursive-rlm-e2e-test
  "Gated real-OpenRouter journeys for DET-E2E-105 and DET-E2E-106.

  These tests give the model adversarial tasks and judge durable outcomes. They
  never redefine, mock, replay, or script model behavior."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- root-events [ctx tick-id]
  (filterv #(or (= tick-id (:tick-id %))
                (= tick-id (:execution-id %))
                (= tick-id (:parent-tick-id %))
                (= tick-id (:root-tick-id %)))
           (live/events ctx)))

(defn- child-tick-ids [events root-tick-id]
  (->> events
       (filter #(and (= :sheet/tree-tick-started (:event/type %))
                     (= root-tick-id (:parent-tick-id %))))
       (map :tick-id)
       distinct
       vec))

(defn- usage-total [usage]
  (long (or (:total-tokens usage) (:total_tokens usage) 0)))

(deftest det-e2e-228-real-model-checkpointed-multi-iteration
  (testing "a pinned real model crosses a durable yield and finishes from prior state"
    (live/with-real-openrouter
      (live/register-openrouter!)
      (h/with-async-test-context
        [ctx {:context {:llm-provider :openrouter :model live/openrouter-model}}]
        (let [{:keys [sheet-id]} (live/build-recursive-rlm!
                                  ctx {:name "det-e2e-228-checkpointed"
                                       :instruction
                                       (str "Use at least two Phase-1 iterations. In the first iteration call "
                                            "(store! :evidence \"CHECKPOINTED\") and do not call final!. "
                                            "In the next iteration read :evidence and call final! with "
                                            "{:answer evidence}. Return exactly CHECKPOINTED.")
                                       :writes [:answer]
                                       :max-iterations 5
                                       :rlm {:checkpointed? true
                                             :quantum {:max-iterations 1}
                                             :timeouts {:provider-ms 60000
                                                        :iteration-ms 90000
                                                        :phase2-ms 90000
                                                        :campaign-ms 180000}}})
              started-at-ms (System/currentTimeMillis)
              result (sheet/execute ctx sheet-id {:question "Perform the checkpoint audit."}
                                    :timeout-ms 180000
                                    :llm-call-budget 6)
              elapsed-ms (- (System/currentTimeMillis) started-at-ms)
              tick-id (:trace-id result)
              events (root-events ctx tick-id)
              checkpoints (filter #(= :rlm/researcher-checkpointed (:event/type %)) events)]
          (is (= :success (:status result)) (pr-str result))
          (is (= "CHECKPOINTED" (get-in result [:outputs :answer])))
          (is (seq checkpoints) "at least one iteration crossed a durable yield")
          (is (every? #(number? (get-in % [:checkpoint :history 0 :provider-latency-ms]))
                      checkpoints))
          (is (pos? elapsed-ms) "the live journey records end-to-end latency")
          (is (h/settle-until! #(some? (rm/get-trace ctx tick-id))))
          (let [trace (rm/get-trace ctx tick-id)
                iterations (:researcher-iterations trace)]
            (is (<= 2 (count iterations)))
            (is (every? #(number? (:provider-latency-ms %)) iterations))
            (is (every? #(pos? (usage-total (:provider-usage %))) iterations))
            (is (some #{:yield} (map :type (:researcher-events trace))))
            (is (some #{:resume} (map :type (:researcher-events trace))))))))))

(def targeted-recovery-instruction
  (str
   "Solve the question using recursive RLM mode and finish with final!. "
   "This is a recovery audit. On the first attempt you MUST call emit-tree! "
   "with exactly this valid tree shape (preserve its explicit schemas and the "
   "throwing inline function): "
   "[:parallel "
   "[:code {:reads [] :writes [:preserved-value] "
   ":output-schemas {:preserved-value :string} "
   ":fn (fn [_] {:preserved-value \"PRESERVED\"})}] "
   "[:code {:reads [] :writes [:deliberate-failure] "
   ":output-schemas {:deliberate-failure :string} "
   ":fn (fn [_] (throw (ex-info \"DELIBERATE-RECOVERY-FAILURE\" {})))}]]. "
   "Do not simplify, replace, or skip this first tree. After the "
   "tree result returns, you MUST inspect it with tree-failures and at least "
   "one of tree-detail, node-output, or tree-events. Then emit a focused second "
   "tree which produces :answer without rerunning the successful leaf. Return "
   "the final answer and preserved value. Do not conceal the induced failure."))

(deftest det-e2e-105-real-llm-recursive-targeted-recovery
  (testing "a real recursive researcher drills into one failed tree and performs focused recovery"
    (live/with-real-openrouter
      (live/register-openrouter!)
      (h/with-async-test-context
        [ctx {:context {:llm-provider :openrouter :model live/openrouter-model}}]
        (let [{:keys [sheet-id]} (live/build-recursive-rlm!
                                  ctx {:name "det-e2e-105-targeted-recovery"
                                       :instruction targeted-recovery-instruction
                                       :writes [:answer :preserved-value]
                                       :max-iterations 8})
              result (sheet/execute ctx sheet-id
                                    {:question "Return the token RECOVERED."}
                                    :timeout-ms 180000
                                    :max-ticks 12
                                    :llm-call-budget 12)
              tick-id (:trace-id result)
              events (root-events ctx tick-id)
              child-ids (child-tick-ids events tick-id)
              iterations (mapcat :iterations
                                  (filter #(= :rlm/researcher-iterations
                                              (:event/type %)) events))
              iteration-code (str/join "\n" (keep :code iterations))
              child-completions (filter #(and (= :sheet/node-execution-completed
                                                   (:event/type %))
                                              (contains? (set child-ids) (:tick-id %)))
                                        (live/events ctx))
              successes (filter #(= :success (:status %)) child-completions)
              failures (filter #(= :failure (:status %)) child-completions)
              failed-node-ids (set (map :node-id failures))
              failed-writes (filter #(and (= :sheet/execution-value-written
                                               (:event/type %))
                                          (contains? failed-node-ids (:node-id %)))
                                    (live/events ctx))]
          (is (= :success (:status result)) (pr-str result))
          (is (<= 2 (count child-ids)) "recovery requires at least two emitted tree executions")
          (is (seq successes) "the first tree must contain durable successful work")
          (is (seq failures) "the deliberately induced leaf failure must remain visible")
          (is (re-find #"tree-failures" iteration-code)
              "a later real-model turn must invoke the failure drill-down primitive")
          (is (re-find #"tree-detail|node-output|tree-events" iteration-code)
              "a later turn must invoke a targeted detail primitive")
          (let [successful-node-ids (set (keep :node-id successes))
                repeats (frequencies (keep :node-id child-completions))]
            (is (not-any? #(> (get repeats % 0) 1) successful-node-ids)
                "successful leaf identities must not be rerun during recovery"))
          (is (= "PRESERVED" (or (get-in result [:outputs :preserved-value])
                                   (get-in result [:outputs "preserved-value"]))))
          (is (empty? failed-writes)
              "a failed leaf cannot leave a canonical blackboard write")
          (let [family-ticks (conj (set child-ids) tick-id)
                calls (filter #(and (= :sheet/node-execution-completed
                                         (:event/type %))
                                    (:model %)
                                    (contains? family-ticks (:tick-id %)))
                              (live/events ctx))]
            (is (seq calls) "the trace family must contain live model calls")
            (doseq [call calls]
              (live/assert-pinned-model! call)
              (is (pos? (usage-total (:usage call))))))
          (is (= 1 (count (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                        (= tick-id (:tick-id %)))
                                  events)))
              "the root trace family has one terminal completion"))))))

(def budget-exhaustion-instruction
  (str
   "Use recursive RLM mode. Repeatedly emit nested trees containing multiple "
   "real llm leaves before attempting final!. The task deliberately requires "
   "more than two model calls. Never replace an llm leaf with code. Summarize "
   "the question only after all nested calls finish."))

(deftest det-e2e-106-real-llm-nested-budget-propagation-and-exhaustion
  (testing "the smallest root LLM-call budget terminates and cancels all nested work"
    (live/with-real-openrouter
      (live/register-openrouter!)
      (h/with-async-test-context
        [ctx {:context {:llm-provider :openrouter :model live/openrouter-model}}]
        (let [{:keys [sheet-id]} (live/build-recursive-rlm!
                                  ctx {:name "det-e2e-106-budget-exhaustion"
                                       :instruction budget-exhaustion-instruction
                                       :max-iterations 10})
              result (sheet/execute ctx sheet-id
                                    {:question "Explain why budgets must compose."}
                                    :timeout-ms 180000
                                    :max-ticks 20
                                    :llm-call-budget 2)
              tick-id (:trace-id result)
              all-events (live/events ctx)
              events (root-events ctx tick-id)
              child-ids (child-tick-ids events tick-id)
              family-ticks (conj (set child-ids) tick-id)
              calls (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                  (:model %)
                                  (contains? family-ticks (:tick-id %)))
                            all-events)
              terminals (filter #(and (= tick-id (:tick-id %))
                                      (#{:sheet/tree-tick-completed
                                         :sheet/tree-tick-failed
                                         :sheet/tick-cancelled}
                                       (:event/type %))) events)
              error-text (str/lower-case (str (:error result) " "
                                              (str/join " " (keep :error events))))]
          (is (#{:failure :timeout} (:status result)) (pr-str result))
          (is (or (str/includes? error-text "budget")
                  (str/includes? error-text "llm-call"))
              "terminal evidence must identify the exhausted budget boundary")
          (is (= 1 (count terminals)) "the root has exactly one terminal decision")
          (is (<= (count calls) 2) "no deeper work may bypass the two-call root budget")
          (doseq [call calls]
            (live/assert-pinned-model! call)
            (is (pos? (usage-total (:usage call)))
                "every completed OpenRouter call is charged exactly once"))
          (is (= (reduce + 0 (map (comp usage-total :usage) calls))
                 (reduce + 0 (map (comp usage-total :usage)
                                  (distinct (map #(select-keys % [:node-id :tick-id :usage]) calls)))))
              "stable node/tick call identities do not double-charge usage")
          (let [terminal-id (:event/id (first terminals))
                terminal-index (first (keep-indexed
                                       #(when (= terminal-id (:event/id %2)) %1)
                                       all-events))
                after-terminal (if terminal-index
                                 (subvec all-events (inc terminal-index)) [])
                starts-after (filter #(and (= :sheet/node-execution-started
                                                (:event/type %))
                                           (contains? family-ticks (:tick-id %)))
                                     after-terminal)
                writes-after (filter #(and (= :sheet/execution-value-written
                                                (:event/type %))
                                           (contains? family-ticks (:tick-id %)))
                                     after-terminal)
                descendant-terminals (filter #(and (contains? (set child-ids) (:tick-id %))
                                                    (#{:sheet/tree-tick-completed
                                                       :sheet/tree-tick-failed
                                                       :sheet/tick-cancelled}
                                                     (:event/type %)))
                                             all-events)]
            (is (empty? starts-after) "no descendant starts after root termination")
            (is (empty? writes-after) "no family write arrives after shutdown")
            (is (= (count child-ids) (count descendant-terminals))
                (str "every started descendant reaches exactly one terminal state: "
                     (pr-str (mapv #(select-keys % [:event/type :tick-id :root-status :reason])
                                   descendant-terminals))))))))))
