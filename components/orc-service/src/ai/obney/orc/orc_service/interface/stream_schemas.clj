(ns ai.obney.orc.orc-service.interface.stream-schemas
  "Malli schemas for the live-stream envelope (:orc.stream/* events).

   These describe the LIVE observation layer delivered by
   `subscribe-execution` / `execute-stream`. The envelope itself is never
   appended to the event store: some envelope kinds project durable Grain
   events, while finer-grained previews remain ephemeral. Exported for
   consumer-side validation and codegen.

   Loss model: each subscription's :seq is strictly monotonic. A gap means
   the consumer fell behind a sliding buffer and lost events; everything
   durable is recoverable from the event store by [:tick tick-id] tags."
  (:require [ai.obney.orc.orc-service.core.logic :as logic]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]))

(def maybe-truncated
  [:or
   [:map
    [:orc.stream/truncated [:= true]]
    [:preview :string]
    [:full-size :int]]
   logic/durable-value])

(def usage
  [:map
   [:prompt-tokens {:optional true} :int]
   [:completion-tokens {:optional true} :int]
   [:total-tokens {:optional true} :int]])

(def map-each-context
  "Correlation for events inside a map-each iteration: the map-each node
   and the item index. Repeated executions of the same child node-id are
   distinguished by this."
  [:map
   [:parent :uuid]
   [:index :int]])

(def timestamp
  "Hub emission time. Grain's time interface yields java.time.OffsetDateTime,
   which is not inst? — accept both."
  [:fn #(or (inst? %) (instance? java.time.OffsetDateTime %))])

(def base-envelope
  "Keys present on every stream envelope."
  [:map
   [:orc.stream/type :keyword]
   [:seq :int]
   [:ts timestamp]
   [:tick-id :uuid]
   [:root-tick-id :uuid]
   [:sheet-id {:optional true} :uuid]
   [:node-id {:optional true} :uuid]
   [:node-name {:optional true} :string]
   [:node-type {:optional true} :keyword]
   [:map-each {:optional true} map-each-context]])

(def stream-envelope
  "Dispatch-by-type schema for everything a subscription delivers."
  [:multi {:dispatch :orc.stream/type}

   ;; --- Tick lifecycle ---
   [:tick-started
    (logic/stream-envelope-schema base-envelope :tick-started
              [:iteration {:optional true} :int]
              [:parent-tick-id {:optional true} :uuid]
              [:input-keys {:optional true} [:vector :keyword]])]

   [:tick-completed
    (logic/stream-envelope-schema base-envelope :tick-completed
              [:status [:enum :success :failure :tree-generated :partial :timeout]]
              [:outputs {:optional true} [:map-of [:or :keyword :string] maybe-truncated]]
              [:error {:optional true} :string])]

   [:tick-cancelled (logic/stream-envelope-schema base-envelope :tick-cancelled)]

   [:child-tick-linked
    (logic/stream-envelope-schema base-envelope :child-tick-linked
              [:parent-tick-id :uuid]
              [:child-tick-id :uuid])]

   ;; --- Node lifecycle / progress / incremental results ---
   [:node-started (logic/stream-envelope-schema base-envelope :node-started)]

   [:node-completed
    (logic/stream-envelope-schema base-envelope :node-completed
              [:status [:enum :success :failure :running :tree-generated :partial :timeout]]
              [:writes {:optional true} [:map-of [:or :keyword :string] maybe-truncated]]
              [:usage {:optional true} usage]
              [:duration-ms {:optional true} :int]
              [:error {:optional true} :string]
              [:completion-kind {:optional true} [:enum :tree-iteration :terminal]])]

   [:progress
    (logic/stream-envelope-schema base-envelope :progress
              [:kind [:enum :sequence :map-each]]
              [:index :int]
              [:total :int])]

   [:rlm-iteration-started
    (logic/stream-envelope-schema base-envelope :rlm-iteration-started
              [:iteration :int]
              [:max-iterations {:optional true} :int])]

   [:rlm-iteration-recorded
    (logic/stream-envelope-schema base-envelope :rlm-iteration-recorded
              [:iteration-index [:and :int [:>= 0]]]
              [:attempt-ordinal [:and :int [:>= 0]]]
              [:iteration-record schemas/researcher-iteration-record])]

   [:rlm-code-generated
    (logic/stream-envelope-schema base-envelope :rlm-code-generated
              [:iteration :int]
              [:code maybe-truncated]
              [:reasoning {:optional true} maybe-truncated])]

   [:rlm-sandbox-completed
    (logic/stream-envelope-schema base-envelope :rlm-sandbox-completed
              [:iteration :int]
              [:result {:optional true} maybe-truncated]
              [:stdout {:optional true} maybe-truncated]
              [:error {:optional true} :string]
              [:vars-created {:optional true} [:vector [:or :keyword :symbol :string]]]
              [:final? {:optional true} :boolean])]

   [:rlm-tree-generated
    (logic/stream-envelope-schema base-envelope :rlm-tree-generated
              [:raw-dsl maybe-truncated])]

   [:rlm-phase2-started
    (logic/stream-envelope-schema base-envelope :rlm-phase2-started
              [:child-tick-id :uuid]
              [:iteration {:optional true} :int])]

   [:rlm-phase2-completed
    (logic/stream-envelope-schema base-envelope :rlm-phase2-completed
              [:child-tick-id :uuid]
              [:status :keyword]
              [:duration-ms {:optional true} :int]
              [:error {:optional true} :string])]

   [:llm-fields
    (logic/stream-envelope-schema base-envelope :llm-fields
              [:fields logic/workflow-values]
              [:attempt {:optional true} :int]
              [:final? {:optional true} :boolean])]

   [:llm-raw-delta
    (logic/stream-envelope-schema base-envelope :llm-raw-delta
              [:text :string]
              [:attempt {:optional true} :int])]

   ;; --- Errors / terminal ---
   [:error
    (logic/stream-envelope-schema base-envelope :error
              [:error :string]
              [:source {:optional true} :keyword])]

   [:stream-closed
    (logic/stream-envelope-schema base-envelope :stream-closed
              [:reason [:enum :completed :cancelled :timeout :ttl :closed-by-consumer]])]])
