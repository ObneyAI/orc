(ns ai.obney.orc.ontology.core.build-lifecycle
  "Durable ORC source-workflow status, separate from graph contributions."
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

(def event-types #{:evolutionary/build-started :evolutionary/build-completed :evolutionary/build-failed})
(defschemas commands
  {:ontology/record-build-state
   [:map [:event-type [:enum :evolutionary/build-started :evolutionary/build-completed :evolutionary/build-failed]]
    [:details [:map [:ontology-id :uuid] [:build-id :uuid]]]]})

(defcommand :ontology record-build-state {:authorized? #(some? (:tenant-id %))}
  [ctx]
  (let [{:keys [event-type details]} (:command ctx)
        query {:tenant-id (:tenant-id ctx) :types event-types :tags #{[:build (:build-id details)]}}
        prior (into [] (es/read (:event-store ctx) query))
        types (set (map :event/type prior))]
    (cond
      (types event-type) {:command-result/events []}
      (or (some types [:evolutionary/build-completed :evolutionary/build-failed])
          (and (not= event-type :evolutionary/build-started) (not (types :evolutionary/build-started))))
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message "Invalid build status transition"}
      :else
      {:command-result/events [(es/->event {:type event-type
                                           :tags #{[:ontology (:ontology-id details)] [:build (:build-id details)]}
                                           :body details})]
       :command-result/cas {:types event-types :tags #{[:build (:build-id details)]}
                            :predicate-fn #(= (count prior) (count (into [] %)))}})))

(defn record! [ctx event-type details]
  (let [result (cp/process-command
                (assoc ctx :command {:command/name :ontology/record-build-state
                                     :command/id (random-uuid) :command/timestamp (time/now)
                                     :event-type event-type :details details}))]
    (when (:cognitect.anomalies/category result)
      (throw (ex-info "Cannot record build status" result)))
    result))
