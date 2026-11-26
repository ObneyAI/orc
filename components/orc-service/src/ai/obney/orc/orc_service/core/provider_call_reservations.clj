(ns ai.obney.orc.orc-service.core.provider-call-reservations
  "Pure identity and atomic-admission rules for durable provider-call budget facts."
  (:require [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.core.emitted-model-authority :as emitted-authority]))

(def reservation-event-type :sheet/provider-call-reserved)

(defn invocation-identity
  "Deterministic identity for one physical provider attempt.

   The durable budget root makes identities distinct across the root-local CAS
   streams. Logical action identity remains stable across retries; the
   non-negative physical ordinal distinguishes spent attempts within a root."
  [budget-tick-id logical-action-identity ownership-epoch provider-attempt-ordinal]
  (when-not (and (integer? provider-attempt-ordinal)
                 (not (neg? provider-attempt-ordinal)))
    (throw (ex-info "Provider attempt ordinal must be a non-negative integer"
                    {:provider-attempt-ordinal provider-attempt-ordinal})))
  (str "budget-root/" budget-tick-id "/" logical-action-identity
       "/epoch/" ownership-epoch
       "/provider-attempt/" provider-attempt-ordinal))

(def fact-types
  #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled
    :sheet/node-execution-started :sheet/node-execution-completed
    :rlm/researcher-frontier-claimed :rlm/researcher-effect-claimed
    :sheet/provider-call-reserved :sheet/emitted-model-work-authorized
    :sheet/model-plan-retained :sheet/model-candidate-claimed :sheet/model-candidate-recorded
    :sheet/model-request-recorded :rlm/researcher-effect-completed})

(defn reservation-identity
  "Keep campaign identities compatible; ordinary ownership includes the exact
   canonical start, item context, logical action, candidate and node attempt."
  [{:keys [admission-kind budget-tick-id tick-id node-start-id exec-context
           logical-action-identity candidate-index node-attempt ownership-epoch
           provider-attempt-ordinal]}]
  (if (= :ordinary admission-kind)
    (pr-str [:ordinary budget-tick-id tick-id node-start-id
             (mapv #(get exec-context %) [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                                         :ai.obney.orc.orc-service.core.todo-processors/map-each-parent
                                         :ai.obney.orc.orc-service.core.todo-processors/tick-iteration])
             logical-action-identity candidate-index node-attempt provider-attempt-ordinal])
    (invocation-identity budget-tick-id logical-action-identity ownership-epoch
                         provider-attempt-ordinal)))

(defn- lineage [starts tick-id root-id]
  (loop [tick tick-id seen #{} path []]
    (cond
      (or (nil? tick) (contains? seen tick) (nil? (get starts tick))) nil
      (= tick root-id) (conj path tick)
      :else (recur (:parent-tick-id (get starts tick)) (conj seen tick) (conj path tick)))))

(defn reservation-decision
  "Consume the dedicated root stream once. Duplicates cannot masquerade as
   exhaustion; canonical ownership and terminal fences are inside the CAS."
  [{:keys [budget-sheet-id budget-tick-id sheet-id tick-id node-id node-start-id
           exec-context admission-kind campaign-sheet-id campaign-tick-id
           campaign-node-id iteration-index invocation-identity ownership-epoch model-configuration-id model-logical-action-id]
    :as reservation}
   events]
  (let [events (into [] events)
        execution-key [tick-id node-id (or exec-context {})]
        state (reduce
                (fn [state event]
                  (let [type (:event/type event)
                        node-key [(:tick-id event) (:node-id event) (value-log/exec-context (:inputs event))]]
                    (case type
                      :sheet/tree-tick-started
                      (update state :starts #(if (contains? % (:tick-id event)) % (assoc % (:tick-id event) event)))
                      :sheet/tick-cancelled (update state :terminals conj (:tick-id event))
                      :sheet/tree-tick-completed
                      (if (= :running (:root-status event)) state (update state :terminals conj (:tick-id event)))
                      :sheet/node-execution-started
                      (-> state (assoc-in [:node-starts node-key] event) (update :node-completed disj node-key))
                      :sheet/node-execution-completed
                      (if (= :tree-generated (:completion-kind event)) state (update state :node-completed conj node-key))
                      :rlm/researcher-frontier-claimed
                      (if (and (= campaign-sheet-id (:sheet-id event)) (= campaign-tick-id (:tick-id event))
                               (= campaign-node-id (:node-id event)))
                        (update state :epoch max (:ownership-epoch event)) state)
                      :rlm/researcher-effect-claimed
                      (if (and (= campaign-sheet-id (:sheet-id event)) (= campaign-tick-id (:tick-id event))
                               (= campaign-node-id (:node-id event)) (= ownership-epoch (:ownership-epoch event)))
                        (update state :iterations conj (:iteration-index event)) state)
                      :sheet/provider-call-reserved
                      (-> state (update :count inc)
                          (update :duplicate? #(or % (= invocation-identity (:invocation-identity event)))))
                      state)))
                {:starts {} :node-starts {} :node-completed #{} :terminals #{}
                 :epoch 0 :iterations #{} :count 0 :duplicate? false} events)
        starts (:starts state)
        root (get starts budget-tick-id)
        invoking (get starts tick-id)
        campaign (get starts campaign-tick-id)
        path (lineage starts tick-id budget-tick-id)
        campaign-path (when campaign-tick-id (lineage starts campaign-tick-id budget-tick-id))
        start (get-in state [:node-starts execution-key])
        ordinary? (= :ordinary admission-kind)
        budget (get-in root [:options :llm-call-budget])
        history-known? (= 1 (get-in root [:options :llm-budget-accounting-version]))
        valid? (and root invoking path
                    (= budget-sheet-id (:sheet-id root)) (= sheet-id (:sheet-id invoking))
                    (integer? budget) (pos? budget)
                    (not-any? (:terminals state) path)
                    (if ordinary?
                      (and start (= node-start-id (:event/id start)) (= sheet-id (:sheet-id start))
                           (not (true? (get-in invoking [:options :checkpointed-campaign?])))
                           (not (contains? (:node-completed state) execution-key)))
                      (and campaign campaign-path
                           start (= node-start-id (:event/id start))
                           (= sheet-id (:sheet-id start))
                           (not (contains? (:node-completed state) execution-key))
                           (= campaign-sheet-id (:sheet-id campaign))
                           (not-any? (:terminals state) campaign-path)
                           (= ownership-epoch (:epoch state))
                           (or (= tick-id campaign-tick-id)
                               (emitted-authority/fresh-authorized?
                                 (cond-> {:sheet-id sheet-id :tick-id tick-id :node-id node-id :node-start-id node-start-id
                                          :exec-context (or exec-context {}) :logical-action-id model-logical-action-id}
                                   (some? model-configuration-id) (assoc :configuration-id model-configuration-id))
                                 {:sheet-id campaign-sheet-id :tick-id campaign-tick-id :node-id campaign-node-id
                                  :exec-context (or (get-in invoking [:options :researcher-campaign-exec-context]) {})
                                  :ownership-epoch (get-in invoking [:options :researcher-ownership-epoch])
                                  :iteration-index iteration-index} events))
                           (contains? (:iterations state) iteration-index))))]
    (merge {:current (:count state) :budget budget :root-tick-id budget-tick-id :root-sheet-id budget-sheet-id}
           {:decision (cond (not history-known?) :incomplete-history
                            (not valid?) :rejected
                            (:duplicate? state) :duplicate
                            (>= (:count state) budget) :exhausted
                            :else :admit)})))

(defn reservation-cas [reservation]
  {:tags #{[:llm-budget-root (:budget-tick-id reservation)]}
   :types fact-types
   :predicate-fn (fn [events] (= :admit (:decision (reservation-decision reservation events))))})
