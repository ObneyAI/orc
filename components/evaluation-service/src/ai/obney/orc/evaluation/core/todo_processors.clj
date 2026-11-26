(ns ai.obney.orc.evaluation.core.todo-processors
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.evaluation.core.logic :as logic]
            [ai.obney.orc.evaluation.core.queries :as queries]
            [ai.obney.orc.orc-service.interface :as orc]
            [com.brunobonacci.mulog :as u]))

;; --- Judge run ---

(defn- tick-completions [ctx tick-id]
  (when (and tick-id (:event-store ctx))
    (into [] (es/read (:event-store ctx)
                      {:types #{:sheet/node-execution-completed}
                       :tenant-id (:tenant-id ctx)
                       :tags #{[:tick tick-id]}}))))

(defn- finish
  "Turn the finished workflow `result` of a judge into its outcome."
  [ctx result {:keys [judge-type rubric timeout-ms provider]}]
  (let [tick-id (:trace-id result)
        completions (tick-completions ctx tick-id)
        provenance (logic/model-provenance completions provider)
        outcome (if (= :success (:status result))
                  (if rubric
                    (logic/rubric-outcome judge-type rubric (:outputs result))
                    (logic/legacy-score-outcome (:outputs result)))
                  (logic/execution-failure-outcome result completions timeout-ms))]
    (cond-> (assoc outcome :model-provenance provenance)
      tick-id (assoc :judge-tick-id tick-id))))

(defn- run-builtin
  [ctx judge-config evidence origin]
  (let [judge-type (:type judge-config)
        rubric (logic/effective-rubric judge-config)
        form (logic/grading-form rubric)
        iterations (not-empty (:researcher-iterations evidence))
        iterations? (and (boolean iterations) (not= :grounding judge-type))
        timeout-ms (or (:timeout-ms judge-config) logic/default-timeout-ms)
        provider (:llm-provider ctx)
        inputs (cond-> {:host-instruction (logic/compose-task evidence (:criteria judge-config))
                        :host-inputs (logic/host-value (or (:inputs evidence) {}))
                        :host-outputs (logic/host-value (or (:outputs evidence) {}))
                        :original-task (logic/host-value (or (:original-task evidence) {}))
                        :rubric (logic/rubric-value rubric)}
                 iterations? (assoc :host-iterations (logic/coerce-source-string iterations)))
        sheet-id (logic/build-behaviour! ctx judge-type form {:model (:model judge-config)
                                                        :iterations? iterations?})
        result (logic/execute-workflow ctx sheet-id inputs timeout-ms origin)]
    (finish ctx result {:judge-type judge-type :rubric rubric
                        :timeout-ms timeout-ms :provider provider})))

(defn- run-custom
  [ctx judge-config evidence origin]
  (let [
        eval-sheet-id (or (:eval-sheet-id judge-config) (:sheet-id judge-config))
        timeout-ms (or (:timeout-ms judge-config) logic/default-timeout-ms)]
    (cond
      (nil? eval-sheet-id)
      (logic/failed :missing-sheet-id "A custom judge needs the id of the workflow that grades.")

      :else
      (let [declared (orc/get-blackboard-by-key ctx eval-sheet-id)
            host-trace-schema (:schema (get declared :host-trace))
            trace-entry (cond-> {} (:node-id evidence) (assoc :node-id (:node-id evidence)))
            host-trace (if (= :vector (first host-trace-schema)) [trace-entry] trace-entry)
            rubric (:rubric judge-config)
            inputs (cond-> {:host-inputs (or (:inputs evidence) {})
                            :host-outputs (or (:outputs evidence) {})
                            :host-instruction (or (:instruction evidence) "")
                            :host-trace host-trace}
                     rubric (assoc :rubric (logic/rubric-value rubric))
                     (contains? declared :original-task)
                     (assoc :original-task (or (:original-task evidence) {}))
                     (contains? declared :host-family)
                     (assoc :host-family (logic/execution-family ctx evidence))
                     (contains? declared :child-assessments)
                     (assoc :child-assessments (or (:child-assessments evidence) [])))
            result (logic/execute-workflow ctx eval-sheet-id inputs timeout-ms origin)]
        (finish ctx result {:judge-type :custom :rubric rubric
                            :timeout-ms timeout-ms :provider (:llm-provider ctx)})))))

(defn run-judge
  "Run one judge against the assessed node's `evidence`
   (`{:inputs :outputs :instruction :write-keys :researcher-iterations
      :node-id}`) and return its OUTCOME (see the namespace doc).

   `judge-config` is the judge's read-model entry: its :type, and optionally
   :rubric, :model, :provider, :criteria, :timeout-ms (a custom judge's
   workflow is :eval-sheet-id). Never throws: every way a judge can fail is a
   :failed or :ungradable outcome with its reason.

   The evidence may carry the :assessment-id the judging serves: the judge's run
   is marked with it as its durable assessment origin. A judge run with no
   durable assessment (the legacy tree-shape path) is marked with a fresh id -
   it is assessment work all the same."
  [ctx judge-config evidence]
  (try
    (let [judge-type (:type judge-config)
          origin {:assessment-id (or (:assessment-id evidence) (random-uuid))}]
      (when (:provider judge-config)
        (u/log :ai.obney.orc.evaluation.core.judge-run/judge-provider-ignored
               :judge-type judge-type
               :provider (:provider judge-config)))
      (cond
        (= :custom judge-type) (run-custom ctx judge-config evidence origin)
        (contains? logic/builtin-types judge-type) (run-builtin ctx judge-config evidence origin)
        :else (logic/failed :unsupported-judge-type
                      (str "No judge behaviour exists for type " (pr-str judge-type) "."))))
    (catch Throwable t
      (u/log :ai.obney.orc.evaluation.core.judge-run/run-judge-threw
             :judge-type (:type judge-config)
             :error (ex-message t)
             :exception-class (.getName (class t)))
      (logic/failed :judge-execution-failed (or (ex-message t) (.getName (class t)))))))

;; --- Judge runtime ---

(defn- find-started-inputs
  "Gap-7 fix#1: when the completion event has no :inputs, reach back to
   the matching :sheet/node-execution-started event and return its
   :inputs. Without this, LLM judges on terminal repl-researcher
   completions get empty inputs context, the rubric prompt renders
   `{inputs}` as `{}`, OpenRouter responses lack a valid :score, and
   judges silently nil. Returns nil if no matching started event is
   found.

   RR-31: the query is scoped to this tick (`:tags #{[:tick tick-id]}`)
   rather than scanning every :sheet/node-execution-started event the
   tenant has ever emitted — the same O(store)-per-judged-completion shape
   as the survey-hang root cause. The in-memory filter below already
   narrowed to this tick-id; this just stops fetching every other tick's
   events to do it."
  [ctx sheet-id tick-id node-id]
  (when (and (:event-store ctx) sheet-id tick-id node-id)
    (let [started-events (into [] (es/read (:event-store ctx)
                                            {:types #{:sheet/node-execution-started}
                                             :tenant-id (:tenant-id ctx)
                                             :tags #{[:tick tick-id]}}))
          matching (first (filter #(and (= sheet-id (:sheet-id %))
                                         (= tick-id (:tick-id %))
                                         (= node-id (:node-id %)))
                                  started-events))]
      (:inputs matching))))

(defn- find-tick-repl-researcher-source
  "Gap-7b: given an :rlm/tree-generated event's tick-id, locate the
   matching :sheet/node-execution-started event for the host
   repl-researcher node. Returns nil when no matching started event
   exists or when none of the candidates is a repl-researcher.

   The bench's recursive RLM emits multiple node-execution-started
   events per tick (root + Phase 2 children + the repl-researcher).
   Plain first-match returns the wrong node — we filter to the one
   whose read-model entry is :repl-researcher.

   Modern :rlm/tree-generated events carry :sheet-id + :node-id in
   the body directly (the producer knows them); this helper is the
   fallback path for legacy events that omit those fields."
  [ctx tick-id]
  (when (and (:event-store ctx) tick-id)
    (let [started-events (into [] (es/read (:event-store ctx)
                                            {:types #{:sheet/node-execution-started}
                                             :tenant-id (:tenant-id ctx)}))
          candidates (filter #(= tick-id (:tick-id %)) started-events)
          matching (first (filter (fn [evt]
                                    (= :repl-researcher
                                       (:type (orc/get-node ctx (:sheet-id evt) (:node-id evt)))))
                                  candidates))]
      (when matching
        {:sheet-id (:sheet-id matching)
         :node-id (:node-id matching)
         :inputs (or (:inputs matching) {})}))))

(defn- build-trace-data
  "Build the `trace-data` map the evaluation judges expect:
   `{:inputs <host-input-values> :outputs <host-output-values>
     :instruction <host-instruction> :original-task <root-run-inputs>
     :researcher-iterations <ordered-durable-records, when applicable>}`.

   `event` is the `:sheet/node-execution-completed` event body.

   Evidence by scope: a leaf's :inputs are its own resolved reads; a composite
   (sequence, fallback, parallel, map-each) records no reads of its own, so its
   :inputs are what its descendants read from outside it. :outputs are the
   node's own writes resolved from the value log, INCLUDING writes forwarded
   from a delegate's child run (`:write-sources`). A composite has no
   instruction: :instruction stays nil rather than being invented. The run's
   original task is a separate item, never folded into the node's instruction.
   The engine's own bookkeeping keys are never part of the evidence.

   RR-31: when the completion records :read-keys, :inputs is resolved from
   the value log (`resolved-reads-inputs`) — the node's ACTUAL recorded
   reads, not the execution-context leftovers the event itself carries.
   Completions that record no :read-keys (direct-tick / researcher-terminal
   completions, which never went through the read-key bookkeeping) keep the
   pre-RR-31 behavior: direct :inputs on the event, else a reach-back to the
   matching :sheet/node-execution-started event. Researcher iterations are
   read from their durable projection rather than racing asynchronous
   execution-trace publication."
  [ctx event]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        node (logic/run-node ctx event)
        read-keys (:read-keys event)
        direct-inputs (:inputs event)
        composite? (and (seq (:children-ids node)) (empty? read-keys))
        inputs (logic/strip-engine-keys
                (cond
                  (seq read-keys) (logic/resolved-reads-inputs ctx tick-id event)
                  composite? (logic/composite-reads ctx tick-id event node)
                  :else (or (not-empty direct-inputs)
                            (find-started-inputs ctx sheet-id tick-id node-id)
                            {})))
        outputs (orc/value-log-resolve-writes (:event-store ctx) (:tenant-id ctx) tick-id event)
        write-keys (or (not-empty (:write-keys event))
                       (vec (keys outputs)))]
    (cond->
     {:node-id node-id
      :tick-id tick-id
      :exec-context (second (orc/value-log-execution-key event))
      :inputs inputs
      :outputs outputs
      :write-keys write-keys
      :original-task (logic/original-task ctx tick-id)
      :instruction (:instruction node)}
      (= :repl-researcher (:type node))
      (assoc :researcher-iterations
             (orc/get-researcher-iteration-records
              ctx sheet-id tick-id node-id)))))

(defn- invoke-custom-judge
  "Gap-4: run a consumer-defined judge workflow against the host node's
   trace-data and harvest a score. A custom judge is run exactly like every
   other judge (`judge-run/run-judge`): its workflow reads `:host-inputs`,
   `:host-outputs`, `:host-instruction`, `:host-trace` (and `:rubric` when the
   judge declares one) and writes either a numeric `:score` + `:feedback`
   (+ optional `:dimensions`) or, with a rubric, a `:band`.

   Returns the canonical {:score :feedback :dimensions} shape, or nil when the
   judge produced no valid result; every failure is logged loudly with its
   reason - silent skipping is the failure mode the unification arc fought.

   Confinement is durable, not a depth counter: the judge's run is marked as
   assessment work (see `judge-run/run-judge`), so nothing inside it is ever
   auto-assessed."
  [ctx judge-config trace-data]
  (logic/outcome->legacy :custom (run-judge ctx judge-config trace-data)))

(defn- invoke-judge
  "Invoke a single attached judge against the host node's trace-data.
   Returns the {:score :feedback :dimensions} canonical shape, or nil
   when the judge produced no valid result. Every LLM or custom judge is an
   ORC workflow run through `judge-run/run-judge`; heuristic-structural stays
   a deterministic code path (it is pure tree-shape arithmetic with no model
   call, so there is nothing for a workflow to budget, retry or account)."
  [ctx judge-config trace-data]
  (let [judge-type (:type judge-config)]
    (cond
      (contains? logic/llm-judge-types judge-type)
      (logic/outcome->legacy judge-type (run-judge ctx judge-config trace-data))

      (= :heuristic-structural judge-type)
      (logic/invoke-heuristic-structural trace-data)

      (= :custom judge-type)
      (invoke-custom-judge ctx judge-config trace-data)

      :else nil)))

(defn- run-judges-and-dispatch!
  "Run the resolved `effective` judges against `trace-data` in PARALLEL
   (one future per judge + per-judge timeout), then dispatch one
   `:evaluation/record-judge-score` command per judge that produced a
   result and, when ≥2 judges scored, one
   `:evaluation/record-composite-score` command.

   `source` carries :sheet-id / :node-id / :tick-id used to tag the
   emitted events. Runs entirely on the caller's thread — callers invoke
   it from inside the effect's background future so the pubsub/poller
   thread is never blocked.

   Preserves all prior judge behavior: tier-1 + :custom dispatch via
   invoke-judge, per-judge failure isolation
   (::judge-invocation-failed), per-judge timeout
   (::judge-invocation-timeout), and the ::all-judges-returned-nil
   audible-silence safeguard."
  [context source effective trace-data]
  (let [futures (mapv
                  (fn [{:keys [judge-name judge-config]}]
                    [judge-name judge-config
                     (future
                       (try (invoke-judge context judge-config trace-data)
                            (catch Throwable t
                              (u/log :ai.obney.orc.evaluation.core.judge-runtime/judge-invocation-failed
                                     :judge-name judge-name
                                     :error (.getMessage t)
                                     :exception-class (.getName (class t)))
                              nil)))])
                  effective)
        judge-results (mapv
                        (fn [[judge-name judge-config fut]]
                          (let [result (try (deref fut logic/per-judge-timeout-ms :ai.obney.orc.evaluation.core.judge-runtime/timeout)
                                            (catch Throwable t
                                              (u/log :ai.obney.orc.evaluation.core.judge-runtime/judge-deref-failed
                                                     :judge-name judge-name
                                                     :error (.getMessage t))
                                              nil))]
                            {:judge-name judge-name
                             :judge-config judge-config
                             :result (cond
                                       (= :ai.obney.orc.evaluation.core.judge-runtime/timeout result)
                                       (do (u/log :ai.obney.orc.evaluation.core.judge-runtime/judge-invocation-timeout
                                                  :judge-name judge-name
                                                  :timeout-ms logic/per-judge-timeout-ms)
                                           nil)
                                       :else result)}))
                        futures)
        scored (filter :result judge-results)]
    (if (seq scored)
      (do
        (doseq [{:keys [judge-name judge-config result]} scored]
          (if (contains? (logic/judge-purposes judge-config) :learning)
            (cp/process-command
              (assoc context :command
                     (logic/->record-judge-score-command source judge-name
                                                   judge-config result)))
            (u/log :ai.obney.orc.evaluation.core.judge-runtime/monitoring-only-judge-score-not-recorded
                   :sheet-id (:sheet-id source)
                   :node-id (:node-id source)
                   :tick-id (:tick-id source)
                   :judge-name judge-name
                   :purposes (logic/judge-purposes judge-config))))
        (when-let [composite-cmd (logic/->record-composite-score-command source judge-results)]
          (cp/process-command
            (assoc context :command composite-cmd))))
      (u/log :ai.obney.orc.evaluation.core.judge-runtime/all-judges-returned-nil
             :sheet-id (:sheet-id source)
             :node-id (:node-id source)
             :tick-id (:tick-id source)
             :effective-judge-names (mapv :judge-name effective)
             :has-generated-tree-raw? (some? (get-in trace-data
                                                     [:outputs :generated-tree-raw]))
             :writes-keys (vec (keys (or (:outputs trace-data) {})))))))

(defn- requested-assessment-ids
  "The ids of the assessments already requested for the completion `subject`."
  [{:keys [event-store tenant-id]} subject]
  (into #{}
        (map :assessment-id)
        (es/read event-store {:types #{logic/request-event-type}
                              :tags #{[:subject subject]}
                              :tenant-id tenant-id})))

(defn- family-dependency-ids
  "The ids of the assessments a judge on `completion` must wait for: one for
   every judge effective on every execution inside the completion's family, as
   the evaluation runtime will request them. Assessment ids are deterministic
   (subject completion, judge, revision), so the dependencies can be named at
   request time even for a child whose completion has not yet been processed by
   the evaluation processor: children complete before their parent, but their
   requests are made asynchronously and may land after it."
  [context completion]
  (let [family (logic/execution-family
                context {:tick-id (:tick-id completion)
                         :node-id (:node-id completion)
                         :exec-context (second (orc/value-log-execution-key completion))})
        effective (memoize (fn [sheet-id node-id kind]
                             (queries/get-effective-judges-for-node context sheet-id node-id kind)))]
    (into []
          (comp (mapcat (fn [entry]
                          (map (fn [judge]
                                 (logic/assessment-id
                                  (:event-id entry) (:judge-name judge)
                                  (logic/judge-revision-number (:judge-config judge))))
                               (effective (:sheet-id entry)
                                          (logic/judged-node-id context entry)
                                          (:completion-kind entry)))))
                (distinct))
          family)))

(defn on-node-execution-completed
  "Handler for :sheet/node-execution-completed: REQUEST the assessments, judge
   nothing.

   Resolves the node's effective judges (`get-effective-judges-for-node`: an
   explicit attachment always assesses; the five defaults only for a
   :repl-researcher with the Living Description flag on) and returns one
   `:evaluation/assessment-requested` event per judge as `:result/events`,
   fenced by a `:result/cas` that rejects the append when any of those
   assessments has been requested already - so delivering the same completion
   again requests nothing new.

   The request is recorded on the PURE path (events + handler CAS): no effect and
   no checkpoint watermark can skip it. Judging itself is the job of
   `on-assessment-requested`, which only ever starts from a durable request.

   Grain appends a handler-CAS outcome without a checkpoint, so the same
   completion is delivered again; its CAS is only a no-op if every delivery
   computes the same requests. Judges can be revised or attached between
   deliveries, so the request set is decided ONCE: a completion that already
   has any request is not requested again, and the CAS admits the append only
   while the completion has none. Returns nil when there is nothing to request."
  [{:keys [event] :as context}]
  (when-let [subject (:event/id event)]
    (when-not (orc/assessment-origin context (:tick-id event))
    (when (empty? (requested-assessment-ids context subject))
    (let [node-id (logic/judged-node-id context event)
          effective (queries/get-effective-judges-for-node context (:sheet-id event) node-id
                                                   (:completion-kind event))
          candidates (into []
                           (comp (map (fn [judge]
                                        [(logic/assessment-id
                                          subject (:judge-name judge)
                                          (logic/judge-revision-number (:judge-config judge)))
                                         judge]))
                                 (logic/distinct-by-first))
                           effective)
          fresh candidates
          dependencies (delay (family-dependency-ids context event))]
      (when (seq fresh)
        {:result/events (let [version (logic/node-version context event)
                              version-number (:version-number
                                              (orc/get-tick-execution-context context (:tick-id event)))]
                          (mapv (fn [[id judge]]
                                  (logic/->assessment-requested-event
                                   event id judge version node-id version-number
                                   (when (logic/declares-key? context (:judge-config judge)
                                                                  :child-assessments)
                                     @dependencies)))
                                fresh))
         :result/cas {:types #{logic/request-event-type}
                      :tags #{[:subject subject]}
                      :predicate-fn (fn [existing] (empty? (into [] existing)))}}))))))

(defn- assessment-events
  "Every lifecycle event (requested, terminal) matching `tags` for the tenant."
  [{:keys [event-store tenant-id]} tags]
  (into [] (es/read event-store {:types logic/lifecycle-event-types
                                 :tags tags
                                 :tenant-id tenant-id})))

(defn- assessment-settled?
  "True when the assessment already has its terminal outcome."
  [context assessment-id]
  (boolean (some logic/terminal-event? (assessment-events context #{[:assessment assessment-id]}))))

(defn- subject-completion
  "The durable `:sheet/node-execution-completed` event a request assesses."
  [{:keys [event-store tenant-id]} {:keys [tick-id subject-completion-id]}]
  (first (filter #(= subject-completion-id (:event/id %))
                 (into [] (es/read event-store {:types #{:sheet/node-execution-completed}
                                                :tags #{[:tick tick-id]}
                                                :tenant-id tenant-id})))))

(defn- child-assessment-evidence
  "What a parent's judge is shown of the assessments within its family: for each
   assessment it waited on, its judge, the node it assessed and its outcome -
   status, band, score and feedback when scored, reason and message when failed
   or ungradable. A field the outcome does not have is absent, never invented. A
   dependency that never ended (cannot happen once judging starts) shows as
   :pending."
  [context dependency-ids]
  (into []
        (keep (fn [id]
                (let [events (assessment-events context #{[:assessment id]})
                      requested (some #(when (= logic/request-event-type (:event/type %)) %) events)
                      terminal (some #(when (logic/terminal-event? %) %) events)]
                  (when requested
                    (merge (select-keys requested [:assessment-id :judge-name :node-id
                                                   :subject-completion-id])
                           {:node-name (:name (orc/get-node context (:sheet-id requested)
                                                            (:node-id requested)))
                            :status (get logic/terminal-status (:event/type terminal) :pending)}
                           (select-keys terminal [:band :score :feedback :dimensions
                                                  :reason :message]))))))
        dependency-ids))

(defn- assess
  "Run the judge of `request` against its subject completion and return
   `{:outcome ... :judge-config ...}`. Every way this can fail is an outcome with
   its reason; nothing here throws."
  [context request]
  (let [judge-config (logic/resolve-judge-config context request)
        completion (subject-completion context request)]
    (cond
      (nil? completion)
      {:outcome (logic/failed-outcome :subject-completion-missing
                                (str "The completion " (:subject-completion-id request)
                                     " this assessment judges is not in the event store."))}

      (nil? judge-config)
      {:outcome (logic/failed-outcome :judge-definition-missing
                                (str "Judge " (pr-str (:judge-name request)) " revision "
                                     (:judge-revision-number request)
                                     " is not declared on the sheet."))}

      (and (contains? logic/failed-statuses (:status completion))
           (not (:assess-failures? judge-config)))
      {:judge-config judge-config
       :outcome (logic/subject-failed-outcome completion)}

      :else
      {:judge-config judge-config
       :outcome
       (try
         (let [trace-data (build-trace-data context completion)]
           (if (= :heuristic-structural (:type judge-config))
             (logic/heuristic-outcome trace-data)
             (run-judge
              context judge-config
              (cond-> (assoc trace-data :assessment-id (:assessment-id request))
                (logic/declares-key? context judge-config :child-assessments)
                (assoc :child-assessments
                       (child-assessment-evidence context (:depends-on request)))))))
         (catch Throwable t
           (u/log :ai.obney.orc.evaluation.core.judge-runtime/assessment-judging-threw
                  :assessment-id (:assessment-id request)
                  :error (ex-message t)
                  :exception-class (.getName (class t)))
           (logic/failed-outcome :judge-execution-failed
                           (or (ex-message t) (.getName (class t))))))})))

(defn- record-composite-when-settled!
  "Gap-8 HonestComposite, computed from ASSESSMENTS: once every assessment
   requested for the completion has its terminal outcome, ONE composite is
   recorded for its (sheet, node, tick) (idempotent, like before).

   The composite is of the completion's LEARNING judges only - the scalar the
   learning loops optimise - so a monitoring-only judge's score is never mixed
   into it. It is the mean over the judges that scored (two or more), and it is
   never presented without its coverage: how many learning judges were expected,
   how many scored, failed and were ungradable, and `:partial` when any did not
   score."
  [context request]
  (let [events (assessment-events context #{[:subject (:subject-completion-id request)]})
        requests (filterv #(= logic/request-event-type (:event/type %)) events)
        terminal-by-id (into {} (comp (filter logic/terminal-event?) (map (juxt :assessment-id identity))) events)]
    (when (every? (comp terminal-by-id :assessment-id) requests)
      (let [learning (filterv #(contains? (set (:purposes %)) :learning) requests)
            outcome-of (fn [r] (get logic/terminal-status
                                    (:event/type (terminal-by-id (:assessment-id r)))))
            by-status (frequencies (map outcome-of learning))
            coverage {:expected (count learning)
                      :scored (get by-status :scored 0)
                      :failed (get by-status :failed 0)
                      :ungradable (get by-status :ungradable 0)}
            results (into []
                          (keep (fn [r]
                                  (when (= :scored (outcome-of r))
                                    {:judge-name (:judge-name r)
                                     :judge-config (logic/resolve-judge-config context r)
                                     :result {:score (:score (terminal-by-id (:assessment-id r)))}})))
                          learning)]
        (when-let [command (logic/->record-composite-score-command
                            {:sheet-id (:sheet-id request)
                             :node-id (:node-id request)
                             :tick-id (:tick-id request)
                             :subject-completion-id (:subject-completion-id request)}
                            results coverage)]
          (cp/process-command (assoc context :command command)))))))

(defn- judge-assessment!
  "Judge one requested assessment and record its outcome. Runs on the effect's
   background future, so no handler thread ever waits on a judge."
  [context request]
  (let [result (assess context request)
        recorded (cp/process-command
                  (assoc context :command (logic/->record-assessment-outcome-command request result)))]
    (when (:cognitect.anomalies/category recorded)
      (u/log :ai.obney.orc.evaluation.core.judge-runtime/assessment-outcome-not-recorded
             :assessment-id (:assessment-id request)
             :anomaly recorded))
    (record-composite-when-settled! context request)))

(defn- start-judging!
  "Start one background future that judges `request` and records its outcome,
   unless it is settled or already being judged. Never blocks."
  [context request]
  (let [id (:assessment-id request)]
    (when (logic/claim-judging! id)
      (future
        (try
          (when-not (assessment-settled? context id)
            (judge-assessment! context request))
          (catch Throwable t
            (u/log :ai.obney.orc.evaluation.core.judge-runtime/assessment-judging-future-failed
                   :assessment-id id
                   :error (ex-message t)
                   :exception-class (.getName (class t))))
          (finally
            (swap! logic/judging-in-flight disj id)))))))

(defn- dependencies-settled?
  "True when every assessment `request` waits on has its terminal outcome. A
   failed or ungradable one is as settled as a scored one; a request that waits
   on nothing is settled."
  [context request]
  (every? #(assessment-settled? context %) (:depends-on request)))

(defn on-assessment-requested
  "Handler for :evaluation/assessment-requested: judge it - once the assessments
   it waits on have all ended.

   A request that already has its terminal outcome (a replay) does nothing. A
   request that waits on assessments (a parent's judge that declares
   :child-assessments) that have not all ended does nothing yet: it stays
   durably PENDING, and `on-assessment-settled` starts it when the last one ends.
   Otherwise the handler returns a non-blocking `:result/effect` that starts one
   background future for the judgment; the future runs the judge and records the
   outcome through `:evaluation/record-assessment-outcome`. The request is
   durable before this ever runs, so an effect the processor skips leaves the
   assessment visibly PENDING - never silently gone."
  [{:keys [event] :as context}]
  (when (and (not (assessment-settled? context (:assessment-id event)))
             (dependencies-settled? context event))
    {:result/checkpoint :after
     :result/effect (fn [] (start-judging! context event))}))

(defn on-assessment-settled
  "Handler for an assessment's terminal outcome: start every request that waits
   on it and whose other dependencies have all ended too. Driven by the outcome
   event itself, so nothing polls. The requests are found by their
   [:depends-on id] tag."
  [{:keys [event event-store tenant-id] :as context}]
  (let [waiting (into []
                      (filter (fn [request]
                                (and (not (assessment-settled? context (:assessment-id request)))
                                     (dependencies-settled? context request))))
                      (es/read event-store {:types #{logic/request-event-type}
                                            :tags #{[:depends-on (:assessment-id event)]}
                                            :tenant-id tenant-id}))]
    (when (seq waiting)
      {:result/checkpoint :after
       :result/effect (fn [] (doseq [request waiting] (start-judging! context request)))})))

(defn- on-rlm-tree-generated
  "Handler for :rlm/tree-generated. Gated on the Living Description
   opt-in flag.

   The event body carries :execution-id (= tick-id) + :raw-dsl. As of
   Gap-7b's producer-side change, it also carries :sheet-id + :node-id
   directly — preferred. Legacy events (or non-bench producers) without
   those fields fall back to looking up the host repl-researcher node
   via the matching :sheet/node-execution-started event for the tick.

   Filters the resolver's effective judge list to tree-shape graders
   (currently heuristic-structural) and returns a non-blocking
   `:result/effect` that runs them in parallel (per-judge timeout) and
   dispatches the score-recording command in a background future — same
   async discipline as on-node-execution-completed. NO deref in the
   handler, NO :result/events."
  [{:keys [event] :as context}]
  (when (and (queries/living-description-enabled? context)
             (not (orc/assessment-origin context (:execution-id event))))
    (let [tick-id (:execution-id event)
          raw-dsl (:raw-dsl event)
          direct-sheet-id (:sheet-id event)
          direct-node-id (:node-id event)
          source (cond
                   (and direct-sheet-id direct-node-id)
                   {:sheet-id direct-sheet-id
                    :node-id direct-node-id
                    :inputs (or (find-started-inputs context direct-sheet-id
                                                     tick-id direct-node-id) {})}
                   :else (find-tick-repl-researcher-source context tick-id))]
      (when (and tick-id raw-dsl source)
        (let [{:keys [sheet-id node-id inputs]} source
              node (orc/get-node context sheet-id node-id)
              effective (queries/get-effective-judges-for-node context sheet-id node-id)
              tree-shape-effective (filterv #(contains? logic/tree-shape-judge-types
                                                       (:type (:judge-config %)))
                                            effective)]
          (when (seq tree-shape-effective)
            (let [trace-data {:inputs inputs
                              :outputs {:generated-tree-raw raw-dsl}
                              :instruction (or (:instruction node) "")}
                  cmd-source {:sheet-id sheet-id
                              :node-id node-id
                              :tick-id tick-id}]
              {:result/checkpoint :after
               :result/effect
               (fn []
                 (future
                   (try
                     (run-judges-and-dispatch! context cmd-source
                                               tree-shape-effective trace-data)
                     (catch Throwable t
                       (u/log :ai.obney.orc.evaluation.core.judge-runtime/judge-runtime-future-failed
                              :sheet-id sheet-id
                              :node-id node-id
                              :tick-id tick-id
                              :error (.getMessage t)
                              :exception-class (.getName (class t)))))))})))))))

(tp/defprocessor :evaluation on-node-execution-completed
  {:topics #{:sheet/node-execution-completed}}
  "Per-event evaluator runtime. Resolves the judges effective on the completing
   node (an explicit attachment always; the defaults only with the Living
   Description flag on) and REQUESTS one assessment per judge. Judging is the
   job of `on-assessment-requested`."
  [context]
  (on-node-execution-completed context))

(tp/defprocessor :evaluation on-rlm-tree-generated
  {:topics #{:rlm/tree-generated}}
  "Gap-7b: tree-shape grader on :rlm/tree-generated. The event fires
   once per campaign, at the terminal boundary, carrying the last tree
   the model emitted; this processor grades that tree's shape once, so
   the consolidator receives one structural signal per campaign
   alongside the terminal sum-up. Intermediate trees' shapes are
   durable on their iteration records, not on this event. Only runs
   tree-shape judges (currently heuristic-structural) — LLM output
   judges run on the terminal :sheet/node-execution-completed event
   where final outputs are available."
  [context]
  (on-rlm-tree-generated context))

(tp/defprocessor :evaluation on-assessment-settled
  {:topics #{:evaluation/assessment-scored
             :evaluation/assessment-failed
             :evaluation/assessment-ungradable}}
  "S9b: an assessment ended - start the parents' assessments that were waiting on
   it, once all of their dependencies have ended."
  [context]
  (on-assessment-settled context))

(tp/defprocessor :evaluation on-assessment-requested
  {:topics #{:evaluation/assessment-requested}}
  "S7: judge a requested assessment and record its outcome (scored, failed or
   ungradable). Starts from the durable request, never from a completion."
  [context]
  (on-assessment-requested context))

;; --- Alerts ---

(defn- last-signal [{:keys [event-store tenant-id]} cell-id]
  (last (into [] (es/read event-store {:types logic/signal-types
                                       :tags #{[:performance-cell cell-id]}
                                       :tenant-id tenant-id}))))

(defn on-assessment-outcome
  "Handler for a terminal assessment event: the pure result (event + CAS) of
   watching its judge's alert, or nil. nil at once, with no read, when the
   outcome carries no alert."
  [{:keys [event] :as context}]
  (when-let [alert (:alert event)]
    (let [k (logic/cell-key event)
          cell-id (logic/cell-id k)
          series (some-> (queries/cell context k) :series
                         (logic/series-through (:assessment-id event)))
          stats (when series (logic/window-stats series (:window alert)))]
      (when stats
        (let [previous (last-signal context cell-id)
              state (get logic/signal-state (:event/type previous) :healthy)
              signal (logic/next-signal alert stats state)]
          (when signal
            (let [previous-id (:event/id previous)]
              {:result/events
               [(es/->event
                 {:type signal
                  :tags #{[:sheet (:sheet-id event)] [:node (:node-id event)]
                          [:performance-cell cell-id] [:assessment (:assessment-id event)]}
                  :body (cond-> (merge {:sheet-id (:sheet-id event)
                                        :node-id (:node-id event)
                                        :judge-name (:judge-name event)
                                        :judge-revision-number (:judge-revision-number event)
                                        :assessment-id (:assessment-id event)
                                        :alert alert
                                        :signalled-at (str (time/now))}
                                       (select-keys stats [:window :scored :coverage-ratio]))
                          (:node-version event) (assoc :node-version (:node-version event))
                          (some? (:trailing-score stats)) (assoc :trailing-score (:trailing-score stats)))})]
               :result/cas
               {:types logic/signal-types
                :tags #{[:performance-cell cell-id]}
                :predicate-fn (fn [existing]
                                (= previous-id (:event/id (last (into [] existing)))))}})))))))

(tp/defprocessor :evaluation on-assessment-outcome-recorded
  {:topics logic/terminal-event-types}
  "S11: watch the alert of a judge that declared one. A judge with no alert is
   never read for and never signalled."
  [context]
  (on-assessment-outcome context))
