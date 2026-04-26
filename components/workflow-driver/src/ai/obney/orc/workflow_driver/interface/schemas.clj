(ns ai.obney.orc.workflow-driver.interface.schemas
  "Event schemas for the workflow-driver agent's optimization arc.

   Loading this namespace registers each event type with grain so the
   event-store will accept appends. Every event carries a :session-id;
   the loop tags events with `[:driver/session <session-id>]` plus
   `[:sheet <sheet-id>]` so a single tag-scoped read reconstructs the
   full arc."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas driver-events
  {:driver/session-started
   [:map
    [:session-id :uuid]
    [:sheet-id :uuid]
    [:objective :string]
    [:max-turns :int]
    [:min-pass-rate :double]
    [:min-judge-score {:optional true} [:maybe :double]]
    [:judges [:vector :keyword]]
    [:model :string]]

   :driver/turn-began
   [:map
    [:session-id :uuid]
    [:sheet-id :uuid]
    [:turn :int]
    [:prior-attempts :int]]

   :driver/proposal-emitted
   [:map
    [:session-id :uuid]
    [:sheet-id :uuid]
    [:turn :int]
    [:reasoning {:optional true} [:maybe :string]]
    [:workflow-form-length :int]    ; raw source length, source itself isn't a domain key
    [:model :string]
    [:prompt-tokens {:optional true} [:maybe :int]]
    [:completion-tokens {:optional true} [:maybe :int]]
    [:total-tokens {:optional true} [:maybe :int]]]

   :driver/submit-result
   [:map
    [:session-id :uuid]
    [:sheet-id :uuid]
    [:turn :int]
    [:status [:enum :ok :parse-error :name-mismatch :build-error]]
    [:added-count :int]
    [:removed-count :int]
    [:modified-count :int]
    [:error {:optional true} [:maybe :string]]]

   :driver/eval-completed
   [:map
    [:session-id :uuid]
    [:sheet-id :uuid]
    [:turn :int]
    [:total :int]
    [:pass-count :int]
    [:fail-count :int]
    [:pass-rate :double]
    [:avg-judge-score {:optional true} [:maybe :double]]]

   :driver/turn-decided
   [:map
    [:session-id :uuid]
    [:sheet-id :uuid]
    [:turn :int]
    [:decision [:enum :publish :continue :surrender]]]

   :driver/session-ended
   [:map
    [:session-id :uuid]
    [:sheet-id :uuid]
    [:status [:enum :published :surrendered :error]]
    [:version-number {:optional true} [:maybe :int]]
    [:reason {:optional true} [:maybe :keyword]]
    [:error {:optional true} [:maybe :string]]
    [:turns-count :int]
    [:total-tokens {:optional true} [:maybe :int]]]})
