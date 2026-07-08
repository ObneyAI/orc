(ns ai.obney.orc.orc-service.core.block
  "WS-2a — the orc-level BLOCK SIGNAL primitive (Option A: orc-defined signal,
   opaque payload).

   A leaf (e.g. a Phase-2 :code node whose gated tool call needs permission)
   raises a recognizable typed throwable that carries an OPAQUE payload orc
   never interprets. The engine recognizes the signal generically and completes
   the node/tick with :status :blocked (see execute-code / execute-leaf-node /
   the composite propagation), so the parent deref returns immediately instead
   of hanging the Phase-2 budget.

   The carrier is an `AssertionError` SUBCLASS on purpose: orc-sessions'
   existing Phase-1 turn-level `catch AssertionError` keeps catching it, so
   adopting the signal (WS-2c) does not regress the Phase-1 pending path.

   Public API (the ENGINE primitive — orc-sessions consumes it in WS-2c):
     (block! payload)          throws the signal carrying an opaque payload
     (blocking-condition? t)   predicate — is this throwable the block signal?
     (block-payload t)         accessor — the opaque payload (nil if not a signal)")

;; Marker interface: an instance is recognized as the block signal by type,
;; and exposes its opaque payload. Kept minimal — orc never inspects the
;; payload's shape.
(definterface IBlockingCondition
  (blockPayload []))

(defn block!
  "Throw the orc block signal carrying `payload` (opaque to orc). The carrier
   is an AssertionError subclass, so a Phase-1-style `catch AssertionError`
   still catches it; the engine's leaf path recognizes it via
   `blocking-condition?` and completes the node :blocked."
  [payload]
  (throw (proxy [AssertionError IBlockingCondition] ["orc blocking condition"]
           (blockPayload [] payload))))

(defn blocking-condition?
  "True iff `t` is an orc block signal (as raised by `block!`)."
  [t]
  (instance? IBlockingCondition t))

(defn block-payload
  "The opaque payload carried by a block signal, or nil if `t` is not one."
  [t]
  (when (instance? IBlockingCondition t)
    (.blockPayload ^IBlockingCondition t)))
