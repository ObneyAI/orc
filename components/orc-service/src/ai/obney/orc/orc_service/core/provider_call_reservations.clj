(ns ai.obney.orc.orc-service.core.provider-call-reservations
  "Pure identity and atomic-admission rules for durable provider-call budget facts.")

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

(defn reservation-decision
  "Classify a reservation against one authoritative root-tag event stream.

   `:admit` means every relationship is valid and capacity remains. A duplicate
   identity is classified before capacity so a redelivery can never masquerade
   as budget exhaustion. `:exhausted` is returned only when every other
   admission requirement is valid."
  [{:keys [budget-sheet-id budget-tick-id sheet-id tick-id node-id
           campaign-sheet-id campaign-tick-id campaign-node-id iteration-index
           invocation-identity ownership-epoch]}
   events]
  (let [{:keys [root-start invoking-start node-execution-started?
                campaign-start campaign-epoch campaign-iterations duplicate?
                reservation-count]}
        (reduce
         (fn [state event]
           (case (:event/type event)
             :sheet/tree-tick-started
             (cond-> state
               (and (= budget-tick-id (:tick-id event))
                    (= budget-sheet-id (:sheet-id event)))
               (assoc :root-start event)

               (and (= tick-id (:tick-id event))
                    (= sheet-id (:sheet-id event)))
               (assoc :invoking-start event)

               (and (= campaign-tick-id (:tick-id event))
                    (= campaign-sheet-id (:sheet-id event)))
               (assoc :campaign-start event))

             :sheet/node-execution-started
             (if (and (= sheet-id (:sheet-id event))
                      (= tick-id (:tick-id event))
                      (= node-id (:node-id event)))
               (assoc state :node-execution-started? true)
               state)

             :rlm/researcher-frontier-claimed
             (if (and (= campaign-sheet-id (:sheet-id event))
                      (= campaign-tick-id (:tick-id event))
                      (= campaign-node-id (:node-id event)))
               (assoc state :campaign-epoch
                      (max (or (:campaign-epoch state) 0)
                           (:ownership-epoch event)))
               state)

             :rlm/researcher-effect-claimed
             (if (and (= campaign-sheet-id (:sheet-id event))
                      (= campaign-tick-id (:tick-id event))
                      (= campaign-node-id (:node-id event))
                      (= ownership-epoch (:ownership-epoch event)))
               (update state :campaign-iterations conj
                       (:iteration-index event))
               state)

             :sheet/provider-call-reserved
             (-> state
                 (update :reservation-count inc)
                 (cond-> (= invocation-identity
                            (:invocation-identity event))
                   (assoc :duplicate? true)))

             state))
         {:root-start nil
          :invoking-start nil
          :node-execution-started? false
          :campaign-start nil
          :campaign-epoch 0
          :campaign-iterations #{}
          :duplicate? false
          :reservation-count 0}
         events)
        budget (get-in root-start [:options :llm-call-budget])
        valid? (and root-start
                    invoking-start
                    node-execution-started?
                    campaign-start
                    (integer? budget)
                    (pos? budget)
                    (= ownership-epoch campaign-epoch)
                    (contains? campaign-iterations iteration-index))]
    (merge
     {:current reservation-count
      :budget budget
      :root-tick-id budget-tick-id
      :root-sheet-id budget-sheet-id}
     (cond
      (not valid?) {:decision :rejected}
      duplicate? {:decision :duplicate}
      (>= reservation-count budget)
      {:decision :exhausted}
      :else {:decision :admit}))))

(defn reservation-cas
  "Atomically admit one provider attempt against a durable budget root.

   The event-store supplies an IReduce stream. Consume it exactly once. All
   relevant facts carry the root tick tag, so the CAS itself uses that one tag
   (Grain combines multiple tags with AND semantics)."
  [{:keys [budget-sheet-id budget-tick-id sheet-id tick-id node-id
           campaign-sheet-id campaign-tick-id campaign-node-id iteration-index
           invocation-identity ownership-epoch]}]
  {:tags #{[:tick budget-tick-id]}
   :types #{:sheet/tree-tick-started
            :sheet/node-execution-started
            :rlm/researcher-frontier-claimed
            :rlm/researcher-effect-claimed
            reservation-event-type}
   :predicate-fn
   (fn [events]
     (= :admit (:decision (reservation-decision
                           {:budget-sheet-id budget-sheet-id
                            :budget-tick-id budget-tick-id
                            :sheet-id sheet-id
                            :tick-id tick-id
                            :node-id node-id
                            :campaign-sheet-id campaign-sheet-id
                            :campaign-tick-id campaign-tick-id
                            :campaign-node-id campaign-node-id
                            :iteration-index iteration-index
                            :invocation-identity invocation-identity
                            :ownership-epoch ownership-epoch}
                           events))))})

