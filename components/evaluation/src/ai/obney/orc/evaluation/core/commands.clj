(ns ai.obney.orc.evaluation.core.commands
  "Command handlers that WRITE the per-event judge score events.

   These are the ONLY writers of `:judge/score-emitted` and
   `:judge/composite-score-computed`. The judge runtime (judge_runtime.clj)
   resolves + runs judges in a background future and dispatches these
   commands via `cp/process-command` once each judge result is ready —
   the same async pattern as orc-service's `execute-leaf-node` (slow work
   in a future, completion emitted via a command, never blocking the
   pubsub/poller thread).

   Both commands are IDEMPOTENT on their identity tuple so an at-least-once
   redelivery (effect-path replay) or a future re-run can't double-emit:
     - record-judge-score: idempotent on [sheet-id node-id tick-id judge-name]
     - record-composite-score: idempotent on [sheet-id node-id tick-id]

   The emitted event shapes/fields are IDENTICAL to what judge_runtime's
   pure builders produced before the async refactor — the consolidator,
   the :evaluation/judge-scores read-model, and the quality-report all
   read these and must not change."
  (:require [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.orc.evaluation.core.assessments :as assessments]
            [clojure.string :as str]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Auth
;; =============================================================================

(defn within-process?
  "Authorization predicate for the judge score-recording commands.

   These commands are dispatched in-process by the judge runtime's
   background future (never from an external/HTTP boundary), so a
   default-allow is correct here — matching how `execute-leaf-node`'s
   completion commands flow within the process. Grain's command-request
   handler is the boundary that enforces auth for externally-submitted
   commands; these are never submitted that way."
  [_ctx]
  true)

;; =============================================================================
;; Idempotency helpers
;; =============================================================================

(defn- same-judge-score?
  [sheet-id node-id tick-id judge-name event]
  (and (= sheet-id (:sheet-id event))
       (= node-id (:node-id event))
       (= tick-id (:tick-id event))
       (= judge-name (:judge-name event))))

(defn- same-composite-score?
  "Composite identity: the subject completion when the composite names one, else
   the legacy (sheet, node, tick) tuple."
  [sheet-id node-id tick-id subject event]
  (if subject
    (= subject (:subject-completion-id event))
    (and (= sheet-id (:sheet-id event))
         (= node-id (:node-id event))
         (= tick-id (:tick-id event)))))

(defn- existing-judge-score?
  "True when a :judge/score-emitted event already exists for the identity
   tuple [sheet-id node-id tick-id judge-name]. Reading the event store
   directly (rather than a read-model) makes the common duplicate path
   immediately visible. The command-result CAS below is the authoritative
   race fence when two writers both observe no prior event."
  [{:keys [event-store tenant-id]} sheet-id node-id tick-id judge-name]
  (boolean
    (some (partial same-judge-score?
                   sheet-id node-id tick-id judge-name)
          (into [] (es/read event-store {:types #{:judge/score-emitted}
                                         :tags #{[:tick tick-id]}
                                         :tenant-id tenant-id})))))

(defn- composite-scope-tags [tick-id subject]
  (if subject #{[:subject subject]} #{[:tick tick-id]}))

(defn- existing-composite-score?
  "True when a :judge/composite-score-computed event already exists for the
   composite's identity (its subject completion, else [sheet-id node-id tick-id]).

   RR-23: scoped by a tag (the subject, else the tick) rather than a full-type
   scan: this duplicate check runs once per composite dispatched."
  [{:keys [event-store tenant-id]} sheet-id node-id tick-id subject]
  (boolean
    (some (partial same-composite-score? sheet-id node-id tick-id subject)
          (into [] (es/read event-store {:types #{:judge/composite-score-computed}
                                         :tags (composite-scope-tags tick-id subject)
                                         :tenant-id tenant-id})))))

;; =============================================================================
;; Commands
;; =============================================================================

(defcommand :evaluation record-judge-score
  {:authorized? within-process?}
  "Record one judge's score for a (sheet, node, tick) by emitting a
   `:judge/score-emitted` event. The command body carries the exact
   fields the event shape requires. Idempotent on
   [sheet-id node-id tick-id judge-name] — a duplicate is a no-op
   (returns no events) rather than a double-emit.

   Emitted event shape is identical to the pre-async judge runtime's
   `->score-emitted-event` so downstream consumers are unchanged."
  [{{:keys [sheet-id node-id tick-id judge-name judge-config
            score feedback dimensions model-provenance emitted-at]} :command
    :as ctx}]
  (if (existing-judge-score? ctx sheet-id node-id tick-id judge-name)
    ;; Idempotent no-op: a score for this tuple already exists.
    {:command-result/events []}
    {:command-result/cas
     {:types #{:judge/score-emitted}
      :tags #{[:tick tick-id]}
      :predicate-fn
      (fn [existing]
        (not-any? (partial same-judge-score?
                           sheet-id node-id tick-id judge-name)
                  (into [] existing)))}
     :command-result/events
     [(->event
        {:type :judge/score-emitted
         :tags #{[:sheet sheet-id]
                 [:node node-id]
                 [:tick tick-id]}
         :body {:sheet-id sheet-id
                :tick-id tick-id
                :node-id node-id
                :judge-name judge-name
                :judge-config judge-config
                :score score
                :feedback feedback
                :dimensions dimensions
                :model-provenance model-provenance
                :emitted-at (or emitted-at (str (java.time.Instant/now)))}})]}))

(defcommand :evaluation record-composite-score
  {:authorized? within-process?}
  "Record the weighted composite across all judges that fired on a
   (sheet, node, tick) by emitting a `:judge/composite-score-computed`
   event. Idempotent on [sheet-id node-id tick-id].

   Emitted event shape is identical to the pre-async judge runtime's
   `->composite-score-event`."
  [{{:keys [sheet-id node-id tick-id composite-score
            contributing-judges coverage purpose subject-completion-id emitted-at]
     partial-composite? :partial} :command
    :as ctx}]
  (if (existing-composite-score? ctx sheet-id node-id tick-id subject-completion-id)
    ;; Idempotent no-op: a composite for this tuple already exists.
    {:command-result/events []}
    ;; The read above cannot fence a race: judge dispatch runs one future per
    ;; judge and the processor path is at-least-once, so two deliveries can both
    ;; read before either appends. The CAS re-checks at append time, which is the
    ;; only place OneCompositePerCompletion can actually be enforced — the same
    ;; fence `record-judge-score` uses above.
    {:command-result/cas
     {:types #{:judge/composite-score-computed}
      :tags (composite-scope-tags tick-id subject-completion-id)
      :predicate-fn
      (fn [existing]
        (not-any? (partial same-composite-score? sheet-id node-id tick-id subject-completion-id)
                  (into [] existing)))}
     :command-result/events
     [(->event
        {:type :judge/composite-score-computed
         :tags (cond-> #{[:sheet sheet-id]
                         [:node node-id]
                         [:tick tick-id]}
                 subject-completion-id (conj [:subject subject-completion-id]))
         :body (cond-> {:sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :contributing-judges contributing-judges
                        :emitted-at (or emitted-at (str (java.time.Instant/now)))}
               (some? composite-score) (assoc :composite-score composite-score)
               (some? subject-completion-id) (assoc :subject-completion-id subject-completion-id)
               (some? coverage) (assoc :coverage coverage)
               (some? partial-composite?) (assoc :partial partial-composite?)
               (some? purpose) (assoc :purpose purpose))})]}))

;; =============================================================================
;; Assessment outcome
;; =============================================================================

(defn- assessment-history
  "Every lifecycle event (requested, terminal) recorded for `assessment-id`."
  [{:keys [event-store tenant-id]} assessment-id]
  (into [] (es/read event-store {:types assessments/lifecycle-event-types
                                 :tags #{[:assessment assessment-id]}
                                 :tenant-id tenant-id})))

(defn- open-assessment?
  "True when `history` holds the request and no terminal outcome yet."
  [history]
  (and (some #(= assessments/request-event-type (:event/type %)) history)
       (not-any? #(contains? assessments/terminal-event-types (:event/type %)) history)))

(defn- outcome-error
  "nil when the command body is a well-formed outcome, else a message. A scored
   outcome has a score within [0,1], and - when its judge's rubric requires
   feedback - non-blank feedback; a failed or ungradable one has its reason and
   message - nothing is recorded without them."
  [{:keys [status score feedback reason message judge-config]}]
  (case status
    :scored (cond
              (not (number? score))
              "a scored outcome needs a :score"

              (not (<= 0.0 (double score) 1.0))
              (str "a scored outcome's :score must be within [0,1], got " (pr-str score))

              (and (= :required (get-in judge-config [:rubric :feedback]))
                   (not (and (string? feedback) (not (str/blank? feedback)))))
              "a scored outcome of a judge whose rubric requires feedback needs non-blank :feedback")
    (:failed :ungradable) (when-not (and reason message)
                            (str "a " (name status) " outcome needs a :reason and a :message"))
    nil))

(defn- tree-shape-score-exists?
  "True when a `:judge/score-emitted` of the tree-shape path (written by
   `:evaluation/record-judge-score`, so it carries no assessment) already exists
   for the request's (sheet, node, tick, judge). That score IS this judge's
   legacy record for the tick; an assessment's own legacy record would be a
   second one. Scores that carry an assessment are distinct executions of a node
   and never count here."
  [{:keys [event-store tenant-id]} request]
  (boolean
    (some (fn [event]
            (and (nil? (:assessment-id event))
                 (same-judge-score? (:sheet-id request) (:node-id request)
                                    (:tick-id request) (:judge-name request) event)))
          (into [] (es/read event-store {:types #{:judge/score-emitted}
                                         :tags #{[:tick (:tick-id request)]}
                                         :tenant-id tenant-id})))))

(defn- terminal-event
  [request-body {:keys [assessment-id status band score feedback dimensions band-distribution
                        reason message model-provenance judge-tick-id judge-config]}]
  (->event
   {:type (assessments/status->terminal-event-type status)
    :tags #{[:sheet (:sheet-id request-body)] [:node (:node-id request-body)]
            [:tick (:tick-id request-body)] [:assessment assessment-id]
            [:subject (:subject-completion-id request-body)]}
    ;; The cell this outcome belongs to (which node version, judge and revision),
    ;; so a stage that reacts to outcomes needs no read of the request to know it;
    ;; and the judge's declared alert, present only for a watched judge.
    :body (cond-> (merge {:assessment-id assessment-id}
                         (select-keys request-body [:sheet-id :node-id :node-version
                                                    :judge-name :judge-revision-number]))
            (:alert judge-config) (assoc :alert (:alert judge-config))
            (= :scored status) (assoc :score score :dimensions (vec dimensions))
            (and (= :scored status) (some? band)) (assoc :band band)
            (and (= :scored status) (some? feedback)) (assoc :feedback feedback)
            (not= :scored status) (assoc :reason reason :message message)
            (and (= :ungradable status) band-distribution) (assoc :band-distribution band-distribution)
            (and (= :scored status) band-distribution) (assoc :band-distribution band-distribution)
            (seq model-provenance) (assoc :model-provenance (vec model-provenance))
            judge-tick-id (assoc :judge-tick-id judge-tick-id))}))

(defn- legacy-score-event
  "The `:judge/score-emitted` record of a scored outcome of a LEARNING judge that
   has feedback - the one record the learning loops read. Carries the assessment
   it records, so distinct executions of one node keep distinct scores."
  [request-body {:keys [assessment-id band score feedback dimensions model-provenance judge-config]}]
  (->event
   {:type :judge/score-emitted
    :tags #{[:sheet (:sheet-id request-body)] [:node (:node-id request-body)]
            [:tick (:tick-id request-body)] [:assessment assessment-id]}
    :body (cond-> {:sheet-id (:sheet-id request-body)
                   :tick-id (:tick-id request-body)
                   :node-id (:node-id request-body)
                   :judge-name (:judge-name request-body)
                   :judge-config (or judge-config {:type (:judge-type request-body)})
                   :score score
                   :feedback feedback
                   :dimensions (vec dimensions)
                   :emitted-at (str (java.time.Instant/now))
                   :assessment-id assessment-id
                   :revision-number (:judge-revision-number request-body)}
            (some? band) (assoc :band band)
            (seq model-provenance)
            (assoc :model-provenance (select-keys (first model-provenance)
                                                  [:provider :model :usage])))}))

(defcommand :evaluation record-assessment-outcome
  {:authorized? within-process?}
  "Record the outcome of a requested assessment: exactly one terminal event
   (scored, failed or ungradable), however many times, however concurrently, the
   outcome is delivered. A scored outcome of a judge whose purposes include
   :learning, and that carries feedback, ALSO records the legacy
   `:judge/score-emitted` in the same append; a monitoring-only judge's, or a
   score-only outcome, never does - nor does one whose judge already has its
   tree-shape legacy score for the tick (one legacy score per judge and tick
   across the two paths).

   The append is fenced by a CAS on the assessment's own history (requested, no
   terminal yet), so two writers that both read before either appends cannot
   both record. A second outcome for the same assessment is a no-op."
  [{:keys [command] :as ctx}]
  (let [{:keys [assessment-id]} command
        history (assessment-history ctx assessment-id)
        request (first (filter #(= assessments/request-event-type (:event/type %)) history))]
    (cond
      (nil? request)
      {::anom/category ::anom/not-found
       ::anom/message (str "Assessment " assessment-id " was never requested")}

      (outcome-error command)
      {::anom/category ::anom/incorrect
       ::anom/message (outcome-error command)}

      (not (open-assessment? history))
      {:command-result/events []}

      :else
      {:command-result/cas
       {:types assessments/lifecycle-event-types
        :tags #{[:assessment assessment-id]}
        :predicate-fn (fn [existing] (open-assessment? (into [] existing)))}
       :command-result/events
       (cond-> [(terminal-event request command)]
         (and (= :scored (:status command))
              (contains? (:purposes request) :learning)
              (some? (:feedback command))
              (not (tree-shape-score-exists? ctx request)))
         (conj (legacy-score-event request command)))})))
