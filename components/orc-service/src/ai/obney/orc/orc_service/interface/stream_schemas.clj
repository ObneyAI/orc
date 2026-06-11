(ns ai.obney.orc.orc-service.interface.stream-schemas
  "Malli schemas for the live-stream envelope (:orc.stream/* events).

   These describe the EPHEMERAL observation layer delivered by
   `subscribe-execution` / `execute-stream`. They are intentionally NOT
   registered as Grain event schemas — stream envelopes are never appended
   to the event store. Exported for consumer-side validation and codegen.

   Loss model: each subscription's :seq is strictly monotonic. A gap means
   the consumer fell behind a sliding buffer and lost events; everything
   durable is recoverable from the event store by [:tick tick-id] tags.")

;; A value that may have been size-capped by the hub (~16KB printed).
;; Oversized values are replaced by the truncation marker map; the raw
;; value remains in the durable event.
(def maybe-truncated
  [:or
   [:map
    [:orc.stream/truncated [:= true]]
    [:preview :string]
    [:full-size :int]]
   :any])

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

(defn- envelope [type-kw & entries]
  (into [:map
         [:orc.stream/type [:= type-kw]]
         [:seq :int]
         [:ts timestamp]
         [:tick-id :uuid]
         [:root-tick-id :uuid]
         [:sheet-id {:optional true} :uuid]
         [:node-id {:optional true} :uuid]
         [:node-name {:optional true} :string]
         [:node-type {:optional true} :keyword]
         [:map-each {:optional true} map-each-context]]
        entries))

(def stream-envelope
  "Dispatch-by-type schema for everything a subscription delivers."
  [:multi {:dispatch :orc.stream/type}

   ;; --- Tick lifecycle -------------------------------------------------
   [:tick-started
    (envelope :tick-started
              [:iteration {:optional true} :int]
              [:parent-tick-id {:optional true} :uuid]
              [:input-keys {:optional true} [:vector :keyword]])]

   [:tick-completed
    (envelope :tick-completed
              [:status [:enum :success :failure :tree-generated :partial :timeout]]
              [:outputs {:optional true} [:map-of :any maybe-truncated]]
              [:error {:optional true} :string])]

   [:tick-cancelled (envelope :tick-cancelled)]

   [:child-tick-linked
    (envelope :child-tick-linked
              [:parent-tick-id :uuid]
              [:child-tick-id :uuid])]

   ;; --- Node lifecycle / progress / incremental results -----------------
   [:node-started (envelope :node-started)]

   [:node-completed
    (envelope :node-completed
              [:status [:enum :success :failure :running :tree-generated :partial :timeout]]
              [:writes {:optional true} [:map-of :any maybe-truncated]]
              [:usage {:optional true} usage]
              [:duration-ms {:optional true} :int]
              [:error {:optional true} :string]
              [:completion-kind {:optional true} [:enum :tree-iteration :terminal]])]

   [:progress
    (envelope :progress
              [:kind [:enum :sequence :map-each]]
              [:index :int]
              [:total :int])]

   ;; --- RLM (repl-researcher) live visibility ---------------------------
   [:rlm-iteration-started
    (envelope :rlm-iteration-started
              [:iteration :int]
              [:max-iterations {:optional true} :int])]

   [:rlm-code-generated
    (envelope :rlm-code-generated
              [:iteration :int]
              [:code maybe-truncated]
              [:reasoning {:optional true} maybe-truncated])]

   [:rlm-sandbox-completed
    (envelope :rlm-sandbox-completed
              [:iteration :int]
              [:result {:optional true} maybe-truncated]
              [:stdout {:optional true} maybe-truncated]
              [:error {:optional true} :string]
              ;; keys are model-chosen via (store! k v) — usually keywords,
              ;; but the sandbox accepts any key type
              [:vars-created {:optional true} [:vector :any]]
              [:final? {:optional true} :boolean])]

   [:rlm-tree-generated
    (envelope :rlm-tree-generated
              [:raw-dsl maybe-truncated])]

   [:rlm-phase2-started
    (envelope :rlm-phase2-started
              [:child-tick-id :uuid]
              [:iteration {:optional true} :int])]

   [:rlm-phase2-completed
    (envelope :rlm-phase2-completed
              [:child-tick-id :uuid]
              [:status :keyword]
              [:duration-ms {:optional true} :int]
              [:error {:optional true} :string])]

   ;; --- Stage 2: LLM token streaming ------------------------------------
   ;; Progressive per-field snapshots from a streaming :ai leaf. Idempotent
   ;; for UIs: each event carries the full text-so-far per output field.
   [:llm-fields
    (envelope :llm-fields
              ;; values are usually strings (text-so-far) but reassembled
              ;; nested output schemas can yield maps/vectors
              [:fields [:map-of :keyword :any]]
              ;; retry attempt (0-based): a new attempt for the same node
              ;; means the previous partial output was abandoned — reset
              ;; accumulation
              [:attempt {:optional true} :int]
              [:final? {:optional true} :boolean])]

   ;; Raw text deltas (opt-in, debug-grade — includes structured-output
   ;; field markers).
   [:llm-raw-delta
    (envelope :llm-raw-delta
              [:text :string]
              [:attempt {:optional true} :int])]

   ;; --- Errors / terminal ------------------------------------------------
   [:error
    (envelope :error
              [:error :string]
              [:source {:optional true} :keyword])]

   [:stream-closed
    (envelope :stream-closed
              [:reason [:enum :completed :cancelled :timeout :ttl :closed-by-consumer]])]])
