(ns ai.obney.orc.workflow-driver.core.loop
  "The driver loop — the multi-turn orchestrator that ties M1 (observe),
   M2 (emit + submit), and M3 (eval + publish/revert) into a single
   agent that owns and improves a target Sheet.

   Per-turn shape:
     1. observe         — describe-sheet snapshot is built inside propose
     2. propose         — LLM emits a complete (workflow …) form
     3. submit          — parse + name-match + build-workflow! + diff
     4. evaluate        — run-eval-set! against the new draft
     5. decide          — publish if pass-rate ≥ threshold, else iterate
                          with rejection feedback in :prior-attempts

   Termination:
     :published    — eval pass-rate ≥ threshold, version committed
     :surrendered  — max-turns hit without a publishable draft
     :error        — unrecoverable issue (sheet missing, etc.)"
  (:require [ai.obney.orc.workflow-driver.core.emit :as emit]
            [ai.obney.orc.workflow-driver.core.emit-agent :as emit-agent]
            [ai.obney.orc.workflow-driver.core.eval-set :as eval-set]
            [ai.obney.orc.workflow-driver.core.publish :as publish]
            [ai.obney.orc.workflow-driver.core.events :as events]
            ;; Force schema registration on load so :driver/* events are
            ;; appendable.
            [ai.obney.orc.workflow-driver.interface.schemas]
            [ai.obney.orc.orc-service.interface :as orc]))

(defn- accumulate-usage
  [acc usage]
  (if-not usage
    acc
    (-> acc
        (update :prompt-tokens (fnil + 0) (or (:prompt-tokens usage) 0))
        (update :completion-tokens (fnil + 0) (or (:completion-tokens usage) 0))
        (update :total-tokens (fnil + 0) (or (:total-tokens usage) 0)))))

(defn- summarize-submit-rejection
  [submit-result]
  (case (:status submit-result)
    :parse-error    (str "parse-error: " (:error submit-result))
    :name-mismatch  (str "name-mismatch: expected \"" (:expected submit-result)
                         "\" but got \"" (:got submit-result) "\"")
    :build-error    (str "build-error: " (:error submit-result))
    (str (name (:status submit-result)))))

(defn- summarize-eval-failure
  [eval-report]
  (str "eval-failed: " (:pass-count eval-report) "/" (:total eval-report)
       " passed (need pass-rate ≥ threshold)"))

(defn run-driver-loop!
  "Run the workflow-driver agent against a target Sheet for at most
   `max-turns` turns. Each turn the agent emits a complete (workflow …)
   form; the loop applies it, evaluates it, and either publishes (on
   pass-rate ≥ threshold) or feeds the rejection back to the agent's
   next turn.

   Required:
     :sheet-id       UUID of the target Sheet
     :objective      string — what the agent should improve
     :eval-set       vector of {:name :inputs} items

   Options:
     :max-turns       budget (default 5)
     :min-pass-rate   publish threshold (default 1.0)
     :model           DSCloj model id (default \"google/gemini-2.5-flash\")
     :provider        DSCloj provider (default :openrouter)
     :tick-timeout-ms per-eval-tick timeout (default 60000)
     :description     description tag attached to the published version
     :on-turn         optional (fn [turn-event]) callback for tracing

   Returns:
     {:status :published | :surrendered | :error
      :version-number n          ;; only on :published
      :final-eval <report>
      :turns [{:turn n
               :proposal {:reasoning … :workflow-form … :usage …}
               :submit {:status … :diff …}
               :eval <report-or-nil>
               :decision :publish | :continue | :surrender} …]
      :usage {:prompt-tokens … :completion-tokens … :total-tokens …}}"
  [ctx {:keys [sheet-id objective eval-set
               max-turns min-pass-rate
               judges min-judge-score
               model provider tick-timeout-ms
               description on-turn]
        :or {max-turns 5
             min-pass-rate 1.0
             judges []
             model "google/gemini-2.5-flash"
             provider :openrouter
             tick-timeout-ms 60000}}]
  (when-not (orc/get-sheet ctx sheet-id)
    (throw (ex-info "Sheet not found" {:sheet-id sheet-id})))
  (let [session-id (random-uuid)
        emit-once-fn
        (fn [turn prior-attempts]
          (emit-agent/propose-tree-via-llm! ctx sheet-id objective
            {:model model
             :provider provider
             :prior-attempts prior-attempts}))

        on-turn (or on-turn (fn [_]))]
    (events/session-started! ctx session-id sheet-id
      {:objective objective
       :max-turns max-turns
       :min-pass-rate (double min-pass-rate)
       :min-judge-score (some-> min-judge-score double)
       :judges judges
       :model model})
    (let [end-session
          (fn [result]
            (events/session-ended! ctx session-id sheet-id result)
            (assoc result :session-id session-id))]
      (loop [turn 1
             prior-attempts []
             turns []
             usage {}]
        (if (> turn max-turns)
          (let [final-report (eval-set/run-eval-set! ctx sheet-id eval-set
                               {:timeout-ms tick-timeout-ms})]
            (end-session
              {:status :surrendered
               :reason :max-turns-reached
               :final-eval final-report
               :turns turns
               :usage usage}))
          (do
            (events/turn-began! ctx session-id sheet-id turn (count prior-attempts))
            (let [proposal (emit-once-fn turn prior-attempts)
                  _ (events/proposal-emitted! ctx session-id sheet-id turn proposal)
                  new-usage (accumulate-usage usage (:usage proposal))
                  submit-result (emit/submit-tree! ctx sheet-id (:workflow-form proposal))
                  _ (events/submit-result! ctx session-id sheet-id turn submit-result)
                  ;; Eval only if submit succeeded
                  eval-report (when (= :ok (:status submit-result))
                                (eval-set/run-eval-set! ctx sheet-id eval-set
                                  {:timeout-ms tick-timeout-ms
                                   :judges judges}))
                  _ (when eval-report
                      (events/eval-completed! ctx session-id sheet-id turn eval-report))
                  pass-rate-ok? (and eval-report
                                     (>= (:pass-rate eval-report) min-pass-rate))
                  judge-ok? (or (nil? min-judge-score)
                                (and eval-report
                                     (some? (:avg-judge-score eval-report))
                                     (>= (:avg-judge-score eval-report) min-judge-score)))
                  passes? (and pass-rate-ok? judge-ok?)
                  decision (cond
                             passes? :publish
                             (= turn max-turns) :surrender
                             :else :continue)
                  _ (events/turn-decided! ctx session-id sheet-id turn decision)
                  turn-event {:turn turn
                              :proposal (select-keys proposal
                                          [:reasoning :workflow-form :model :usage])
                              :submit (select-keys submit-result [:status :diff
                                                                  :error :expected :got])
                              :eval eval-report
                              :decision decision}]
              (on-turn turn-event)
              (case decision
                :publish
                ;; The loop already eval'd this draft above and the gate
                ;; passed — committing the version without a redundant
                ;; (and variance-prone) re-eval. publish! is for ad-hoc
                ;; publish-with-gate; the loop wants a cheap commit.
                (let [pub (publish/commit-version! ctx sheet-id
                            {:description description})]
                  (if (= :ok (:status pub))
                    (end-session
                      {:status :published
                       :version-number (:version-number pub)
                       :snapshot-id (:snapshot-id pub)
                       :final-eval eval-report
                       :turns (conj turns turn-event)
                       :usage new-usage})
                    (end-session
                      {:status :error
                       :error (:error pub)
                       :turns (conj turns turn-event)
                       :usage new-usage})))

                :surrender
                (end-session
                  {:status :surrendered
                   :reason :max-turns-without-publishable-draft
                   :final-eval eval-report
                   :turns (conj turns turn-event)
                   :usage new-usage})

            :continue
            (let [reason (cond
                           (not= :ok (:status submit-result))
                           (summarize-submit-rejection submit-result)
                           (some? eval-report)
                           (summarize-eval-failure eval-report)
                           :else "unknown")
                  attempt {:turn turn
                           :status (if (= :ok (:status submit-result))
                                     :eval-failed
                                     :rejected)
                           :reason reason}]
              (recur (inc turn)
                     (conj prior-attempts attempt)
                     (conj turns turn-event)
                     new-usage))))))))))
