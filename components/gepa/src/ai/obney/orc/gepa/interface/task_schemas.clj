(ns ai.obney.orc.gepa.interface.task-schemas
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas task-types
  {:gepa/task-boundary
   [:map [:kind [:enum :candidate :subsample]]
    [:candidate-id {:optional true} :uuid]
    [:parent-id {:optional true} :uuid]
    [:iteration {:optional true} :int]]
   :gepa/task-manifest-entry
   [:map [:call-id :uuid] [:trace-id :uuid] [:role :keyword]
    [:example-index :int] [:input :map] [:instructions [:map-of :string :string]]
    [:metric-retry-safe? {:optional true} :boolean]
    [:max-metric-attempts {:optional true} pos-int?]
    [:sheet-id {:optional true} :uuid]]
   :gepa/task-result
   [:map [:output :map] [:score [:and :double [:>= 0.0] [:<= 1.0]]]
    [:feedback {:optional true} [:maybe :string]]
    [:model {:optional true} [:maybe :string]]
    [:usage {:optional true} [:maybe :map]]]})

(defschemas task-commands
  {:gepa/manage-task
   [:map [:operation [:enum :open-boundary :claim :renew :dispatch :workflow-completed :metric-started :finish :attention]]
    [:optimization-id :uuid] [:boundary-id :uuid]
    [:boundary {:optional true} :gepa/task-boundary]
    [:tasks {:optional true} [:vector :gepa/task-manifest-entry]]
    [:call-id {:optional true} :uuid] [:owner-id {:optional true} :uuid]
    [:epoch {:optional true} pos-int?]
    [:attempt-id {:optional true} :uuid]
    [:attempt-kind {:optional true} [:enum :evaluation :metric]]
    [:result {:optional true} :map]
    [:reason {:optional true} [:map [:code :keyword] [:message :string]]]]})

(defschemas task-events
  {:gepa/task-adoption-blocked
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:reason [:enum :ambiguous-legacy-task]] [:source-event-ids [:vector :uuid]]]
   :gepa/task-boundary-opened
   [:map [:optimization-id :uuid] [:boundary-id :uuid]
    [:boundary :gepa/task-boundary] [:tasks [:vector :gepa/task-manifest-entry]]]
   :gepa/task-claimed
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:trace-id :uuid] [:owner-id :uuid] [:epoch pos-int?]
    [:lease-expires-at :string]]
   :gepa/task-lease-renewed
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:lease-expires-at :string]]
   :gepa/task-attempt-dispatched
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:attempt-id :uuid]
    [:attempt-kind [:enum :evaluation :metric]] [:attempt-number pos-int?]]
   :gepa/task-workflow-completed
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:result :map]]
   :gepa/task-metric-started
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:attempt-id :uuid]]
   :gepa/task-finished
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:attempt-id :uuid] [:result :gepa/task-result]]
   :gepa/task-recovery-required
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:reason [:map [:code :keyword] [:message :string]]]]
   :gepa/task-adopted
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:trace-id :uuid] [:result :gepa/task-result]
    [:source-event-ids [:vector :uuid]] [:source-trace-ids [:vector :uuid]]
    [:observed-attempts pos-int?]]})
