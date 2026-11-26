(ns ai.obney.orc.evaluation.core.commands
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.evaluation.core.logic :as logic]))

;; --- Commands ---

(defn- existing-judge-score?
  "True when a :judge/score-emitted event already exists for the identity
   tuple [sheet-id node-id tick-id judge-name]. Reading the event store
   directly (rather than a read-model) makes the common duplicate path
   immediately visible. The command-result CAS below is the authoritative
   race fence when two writers both observe no prior event."
  [{:keys [event-store tenant-id]} sheet-id node-id tick-id judge-name]
  (boolean
    (some (partial logic/same-judge-score?
                   sheet-id node-id tick-id judge-name)
          (into [] (es/read event-store {:types #{:judge/score-emitted}
                                         :tags #{[:tick tick-id]}
                                         :tenant-id tenant-id})))))

(defn- existing-composite-score?
  "True when a :judge/composite-score-computed event already exists for the
   composite's identity (its subject completion, else [sheet-id node-id tick-id]).

   RR-23: scoped by a tag (the subject, else the tick) rather than a full-type
   scan: this duplicate check runs once per composite dispatched."
  [{:keys [event-store tenant-id]} sheet-id node-id tick-id subject]
  (boolean
    (some (partial logic/same-composite-score? sheet-id node-id tick-id subject)
          (into [] (es/read event-store {:types #{:judge/composite-score-computed}
                                         :tags (logic/composite-scope-tags tick-id subject)
                                         :tenant-id tenant-id})))))

(cp/defcommand :evaluation record-judge-score
  {:authorized? logic/within-process?}
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
    {:command-result/events []}
    {:command-result/cas
     {:types #{:judge/score-emitted}
      :tags #{[:tick tick-id]}
      :predicate-fn
      (fn [existing]
        (not-any? (partial logic/same-judge-score?
                           sheet-id node-id tick-id judge-name)
                  (into [] existing)))}
     :command-result/events
     [(es/->event
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

(cp/defcommand :evaluation record-composite-score
  {:authorized? logic/within-process?}
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
    {:command-result/events []}
    {:command-result/cas
     {:types #{:judge/composite-score-computed}
      :tags (logic/composite-scope-tags tick-id subject-completion-id)
      :predicate-fn
      (fn [existing]
        (not-any? (partial logic/same-composite-score? sheet-id node-id tick-id subject-completion-id)
                  (into [] existing)))}
     :command-result/events
     [(es/->event
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

(defn- assessment-history
  "Every lifecycle event (requested, terminal) recorded for `assessment-id`."
  [{:keys [event-store tenant-id]} assessment-id]
  (into [] (es/read event-store {:types logic/lifecycle-event-types
                                 :tags #{[:assessment assessment-id]}
                                 :tenant-id tenant-id})))

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
                 (logic/same-judge-score? (:sheet-id request) (:node-id request)
                                    (:tick-id request) (:judge-name request) event)))
          (into [] (es/read event-store {:types #{:judge/score-emitted}
                                         :tags #{[:tick (:tick-id request)]}
                                         :tenant-id tenant-id})))))

(cp/defcommand :evaluation record-assessment-outcome
  {:authorized? logic/within-process?}
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
        request (first (filter #(= logic/request-event-type (:event/type %)) history))]
    (cond
      (nil? request)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Assessment " assessment-id " was never requested")}

      (logic/outcome-error command)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (logic/outcome-error command)}

      (not (logic/open-assessment? history))
      {:command-result/events []}

      :else
      {:command-result/cas
       {:types logic/lifecycle-event-types
        :tags #{[:assessment assessment-id]}
        :predicate-fn (fn [existing] (logic/open-assessment? (into [] existing)))}
       :command-result/events
       (cond-> [(logic/terminal-event request command)]
         (and (= :scored (:status command))
              (contains? (:purposes request) :learning)
              (some? (:feedback command))
              (not (tree-shape-score-exists? ctx request)))
         (conj (logic/legacy-score-event request command)))})))
