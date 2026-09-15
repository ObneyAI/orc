(ns ai.obney.orc.orc-service.rr26-node-trace-status-test
  "RR-26 — the per-node trace schema must admit every status the engine writes.

   `:blocked` is a live node status (RR-13 made blocked outcomes durable,
   rejoinable evidence, and the executor writes it at several sites). The
   enclosing execution-trace schema already admits `:blocked` for the TICK's own
   status, but its `[:vector ::node-trace]` referenced a per-node status enum
   that omitted it — so one structure allowed `:blocked` at the tick level and
   forbade it one level down, for the same run."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface.schemas]
            [malli.core :as m]))

(def ^:private node-trace :ai.obney.orc.orc-service.interface.schemas/node-trace)

(defn- trace-record [status]
  {:node-id (random-uuid)
   :trace-instance-id (random-uuid)
   :node-name "researcher"
   :node-type :repl-researcher
   :path ["root" "researcher"]
   :status status
   :started-at "2026-01-01T00:00:00Z"})

(deftest the-node-trace-schema-admits-every-status-the-engine-writes
  (testing "a blocked node's trace record is valid"
    (is (m/validate node-trace (trace-record :blocked))
        (pr-str (m/explain node-trace (trace-record :blocked)))))
  (testing "the statuses that already validated still do"
    (doseq [status [:success :failure :running :partial :timeout]]
      (is (m/validate node-trace (trace-record status))
          (str status " " (pr-str (m/explain node-trace (trace-record status)))))))
  (testing "an invented status is still rejected"
    (is (not (m/validate node-trace (trace-record :not-a-real-status)))))
  (testing "the retired :skipped status is rejected (grill decision D1 — no producer ever existed)"
    (is (not (m/validate node-trace (trace-record :skipped)))
        (pr-str (m/explain node-trace (trace-record :skipped))))))
