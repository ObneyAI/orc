(ns ai.obney.orc.evaluation.core.assessments
  "Assessments: the durable unit of judging (ADR 0008).

   An assessment is identified by the completion it assesses, the judge and the
   judge's revision. This namespace holds the pure rules of that identity and the
   shapes of its events; the effects live in the judge runtime (requesting and
   judging) and the evaluation commands (recording the outcome)."
  (:require [clj-uuid :as uuid]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]))

(def ^:private assessment-namespace
  "Fixed namespace of the name-based (v5) assessment ids."
  #uuid "5c1ae3a0-6f5d-5d59-9b49-3f2a1c7e8d10")

(defn assessment-id
  "The deterministic id of the assessment of `subject-completion-id` by the judge
   `judge-name` at `revision-number`. The same triple always yields the same id,
   so a replayed completion maps onto the assessment it already requested."
  [subject-completion-id judge-name revision-number]
  (uuid/v5 assessment-namespace
           (str subject-completion-id "|" judge-name "|" revision-number)))

(def default-purposes
  "The purposes of a judge that declares none (every declared judge has fed the
   learning loops): monitoring + learning."
  #{:monitoring :learning})

(def request-event-type :evaluation/assessment-requested)

(def terminal-event-types
  #{:evaluation/assessment-scored
    :evaluation/assessment-failed
    :evaluation/assessment-ungradable})

(def lifecycle-event-types
  (conj terminal-event-types request-event-type))

(def terminal-status
  {:evaluation/assessment-scored :scored
   :evaluation/assessment-failed :failed
   :evaluation/assessment-ungradable :ungradable})

(def status->terminal-event-type
  (into {} (map (fn [[t s]] [s t])) terminal-status))

;; =============================================================================
;; Read model - every assessment, with its status
;; =============================================================================

(defmulti assessments*
  "Apply a lifecycle event to the assessments state: `{assessment-id entry}`."
  (fn [_state event] (:event/type event)))

(defmethod assessments* :default [state _] state)

(defmethod assessments* :evaluation/assessment-requested
  [state event]
  (assoc state (:assessment-id event)
         (cond-> (merge {:status :pending :late false}
                        (select-keys event [:assessment-id :sheet-id :node-id :tick-id
                                            :subject-completion-id :judge-name
                                            :judge-revision-number :judge-type :purposes
                                            :requested-at :exec-context :node-version
                                            :depends-on])))))

(defn- settle
  "A terminal event settles a PENDING assessment once; a later one never rewrites it."
  [state event status fields]
  (let [id (:assessment-id event)
        entry (get state id {:assessment-id id :status :pending :late false})]
    (if (= :pending (:status entry))
      (assoc state id (merge entry (select-keys event fields) {:status status}))
      state)))

(defmethod assessments* :evaluation/assessment-scored
  [state event]
  (settle state event :scored [:band :score :feedback :dimensions :band-distribution
                               :model-provenance :judge-tick-id]))

(defmethod assessments* :evaluation/assessment-failed
  [state event]
  (settle state event :failed [:reason :message :model-provenance :judge-tick-id]))

(defmethod assessments* :evaluation/assessment-ungradable
  [state event]
  (settle state event :ungradable [:reason :message :band-distribution
                                   :model-provenance :judge-tick-id]))

(defreadmodel :evaluation assessments
  {:events lifecycle-event-types :version 1}
  [state event] (assessments* state event))

(defn get-assessments
  "The assessments of the tenant, oldest request first, optionally narrowed by
   any of `:sheet-id :node-id :tick-id :judge-name :status`. Each entry is the
   assessment's current state: its identity (`:assessment-id`,
   `:subject-completion-id`, `:judge-name`, `:judge-revision-number`), its
   `:status` (:pending until it ends :scored, :failed or :ungradable), and the
   outcome's own fields (`:band :score :feedback :dimensions` when scored,
   `:reason :message` otherwise, `:model-provenance`)."
  [ctx filters]
  (let [wanted (select-keys filters [:sheet-id :node-id :tick-id :judge-name :status])]
    (->> (vals (rmp/project ctx :evaluation/assessments))
         (filter (fn [entry] (every? (fn [[k v]] (= v (get entry k))) wanted)))
         (sort-by (juxt :requested-at :assessment-id))
         vec)))
