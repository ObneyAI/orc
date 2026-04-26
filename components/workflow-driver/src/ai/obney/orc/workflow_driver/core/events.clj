(ns ai.obney.orc.workflow-driver.core.events
  "Emit driver-session events directly to the grain event store. Each
   event is tagged with `[:driver/session <session-id>]` and
   `[:sheet <sheet-id>]`. Tag-scoped reads reconstruct the full
   optimization arc:

     (es/read event-store {:tags #{[:driver/session sid]} :tenant-id …})

   Schemas are registered in `interface/schemas.clj`."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid]))

(defn- emit!
  [{:keys [event-store tenant-id]} event-type session-id sheet-id body]
  (let [base {:event/id (uuid/v7)
              :event/timestamp (time/now)
              :event/type event-type
              :event/tags #{[:driver/session session-id]
                            [:sheet sheet-id]}}
        evt (merge base
                   {:session-id session-id
                    :sheet-id sheet-id}
                   body)
        result (es/append event-store {:events [evt] :tenant-id tenant-id})]
    ;; Surface schema/append failures rather than swallowing them — a
    ;; missing event would silently break replay.
    (when (:cognitect.anomalies/category result)
      (throw (ex-info (str "Failed to append " event-type)
               {:event-type event-type
                :anomaly result})))
    result))

(defn session-started!
  [ctx session-id sheet-id
   {:keys [objective max-turns min-pass-rate min-judge-score judges model]}]
  (emit! ctx :driver/session-started session-id sheet-id
         {:objective objective
          :max-turns max-turns
          :min-pass-rate min-pass-rate
          :min-judge-score min-judge-score
          :judges (vec judges)
          :model model}))

(defn turn-began!
  [ctx session-id sheet-id turn prior-attempts-count]
  (emit! ctx :driver/turn-began session-id sheet-id
         {:turn turn
          :prior-attempts prior-attempts-count}))

(defn proposal-emitted!
  [ctx session-id sheet-id turn proposal]
  (emit! ctx :driver/proposal-emitted session-id sheet-id
         {:turn turn
          :reasoning (:reasoning proposal)
          :workflow-form-length (count (or (:workflow-form proposal) ""))
          :model (or (:model proposal) "")
          :prompt-tokens (get-in proposal [:usage :prompt-tokens])
          :completion-tokens (get-in proposal [:usage :completion-tokens])
          :total-tokens (get-in proposal [:usage :total-tokens])}))

(defn submit-result!
  [ctx session-id sheet-id turn submit-result]
  (let [diff (:diff submit-result)]
    (emit! ctx :driver/submit-result session-id sheet-id
           {:turn turn
            :status (:status submit-result)
            :added-count (count (:added diff))
            :removed-count (count (:removed diff))
            :modified-count (count (:modified diff))
            :error (:error submit-result)})))

(defn eval-completed!
  [ctx session-id sheet-id turn eval-report]
  (emit! ctx :driver/eval-completed session-id sheet-id
         {:turn turn
          :total (:total eval-report)
          :pass-count (:pass-count eval-report)
          :fail-count (:fail-count eval-report)
          :pass-rate (double (:pass-rate eval-report))
          :avg-judge-score (some-> eval-report :avg-judge-score double)}))

(defn turn-decided!
  [ctx session-id sheet-id turn decision]
  (emit! ctx :driver/turn-decided session-id sheet-id
         {:turn turn :decision decision}))

(defn session-ended!
  [ctx session-id sheet-id final-result]
  (emit! ctx :driver/session-ended session-id sheet-id
         {:status (:status final-result)
          :version-number (:version-number final-result)
          :reason (:reason final-result)
          :error (:error final-result)
          :turns-count (count (:turns final-result))
          :total-tokens (get-in final-result [:usage :total-tokens])}))
