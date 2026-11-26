(ns ai.obney.orc.orc-service.core.execution-budget
  "Shared wall-clock deadline and active-attempt state for workflow execution."
  (:import [java.util.concurrent Future]))

(defrecord OwnershipMonitor [^Future monitor thread initial-state exited]
  Future
  (cancel [_ may-interrupt-if-running]
    (.cancel monitor may-interrupt-if-running))
  (isCancelled [_]
    (.isCancelled monitor))
  (isDone [_]
    (.isDone monitor))
  (get [_]
    (.get monitor))
  (get [_ timeout unit]
    (.get monitor timeout unit)))
