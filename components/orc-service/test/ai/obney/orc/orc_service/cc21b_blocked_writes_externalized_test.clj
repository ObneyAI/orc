(ns ai.obney.orc.orc-service.cc21b-blocked-writes-externalized-test
  "CC-21b / O7 — a `:blocked` completion externalises its `:writes` like a
   `:success` one.

   `externalize-writes?` reads

     (and tick-scoped? (#{:success :tree-generated} status) (seq writes))

   and the status set is the bug. The gate's own comment says what it is FOR:
   `:writes` may be reduced to shape when — and only when — the values are
   durable elsewhere, because 'when the write events are NOT emitted, the
   completion event is the only record the values have'. That is a DURABILITY
   condition, and `tick-scoped?` is the whole of it. The status filter adds
   nothing to durability; WS-2a added `:blocked` and the externalisation gate
   never learned about it.

   What it costs, measured on the real corpus (CC-21a): of 178
   `:repl-researcher` observations, 57 are `:blocked`, and their inlined
   `:writes` are 1,331,342 B — 90.8% of everything left after the rest of the
   producer-side fix. Replaying the emitter over those 178 executions lands at
   1,466,075 B, 59.6% of the provider's context limit: a 4.4x win that is
   still not safe. So O7 is necessary and NOT sufficient — the consolidator's
   evidence projection is the other half, and neither alone suffices.

   This test asserts through the projection (the event store), never a
   command's return value, and it is PAIRED: the `:success` arm proves the
   setup can externalise at all, so a green `:blocked` arm cannot be an
   artifact of a tick that was never tick-scoped."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.time.interface :as time]))

(defn seed-leaf [_] {:seed "seeded"})

;; The write-key names are the real ones from the failing production node
;; (`:turns` is the orc-sessions session transcript that made the window
;; 6.4 MB); the values are small because this test is about WHERE the values
;; land, not how big they are.
(def ^:private blocked-writes
  {:turns [{:turn-id "t-1" :assistant "…"} {:turn-id "t-2" :assistant "…"}]
   :session {:session-id "s-1" :mode :implement}
   :tool-context {:last-tool "shell/exec"}
   :iterations 3
   :nothing-written nil})

(defn- setup-sheet! [ctx]
  (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name "cc21b-o7"))
                     :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :seed :string))
    (let [root (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                   :command-result/events first :node-id)
          leaf (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id root))
                   :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                              sheet-id leaf :code
                              :fn "ai.obney.orc.orc-service.cc21b-blocked-writes-externalized-test/seed-leaf"))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf [] [:seed]))
      sheet-id)))

(defn- tick-id-of-run
  "A REAL tick-scoped execution context — the other half of the gate. Taken
   from an actual run rather than invented, so `tick-scoped?` is true for the
   same reason it is true in production."
  [ctx]
  (->> (h/read-all-events ctx)
       (filter #(= :sheet/tree-tick-started (:event/type %)))
       first
       :tick-id))

(defn- complete-node!
  "Dispatch one `:sheet/complete-node-execution` into that live tick and read
   the result back OUT of the store."
  [ctx sheet-id tick-id status]
  (let [node-id (random-uuid)]
    (h/run-and-apply! ctx {:command/name :sheet/complete-node-execution
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :node-type :repl-researcher
                           :status status
                           :writes blocked-writes
                           :duration-ms 10})
    (let [events (h/read-all-events ctx)]
      {:node-id node-id
       :events events
       :completion (->> events
                        (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                      (= node-id (:node-id %))))
                        first)
       :write-events (->> events
                          (filter #(and (= :sheet/execution-value-written (:event/type %))
                                        (= node-id (:node-id %))))
                          vec)})))

(deftest a-blocked-completion-externalises-its-writes-like-a-successful-one
  (h/with-async-test-context [ctx]
    (let [sheet-id (setup-sheet! ctx)
          _ (runtime/execute ctx sheet-id {})
          tick-id (tick-id-of-run ctx)
          success (complete-node! ctx sheet-id tick-id :success)
          blocked (complete-node! ctx sheet-id tick-id :blocked)
          expected-keys (set (keys blocked-writes))]

      (testing "the setup is capable of externalising at all (the control arm)"
        (is (some? tick-id) "a real tick-scoped execution context exists")
        (is (some? (:completion success)) "the :success completion landed")
        (is (not (contains? (:completion success) :writes))
            "a :success completion carries no inline :writes")
        (is (= (count blocked-writes) (count (:write-events success)))
            (str "the :success arm emitted one write event per write key; N = "
                 (count (:write-events success)))))

      (testing "a :blocked completion does the same"
        (is (some? (:completion blocked)) "the :blocked completion landed")
        (is (= :blocked (:status (:completion blocked))))
        (is (not (contains? (:completion blocked) :writes))
            "THE DEFECT: a :blocked completion still inlined its whole :writes
             map — 57 of 178 observations, 90.8% of the residual bytes")
        (is (= (count blocked-writes) (count (:write-events blocked)))
            (str "one write event per write key; N = "
                 (count (:write-events blocked)))))

      (testing "shape, not values — the completion still says what was written"
        (is (= expected-keys (set (:write-keys (:completion blocked))))
            "every write key is still named on the completion event")
        (is (= (set (keys (remove (comp nil? val) blocked-writes)))
               (set (keys (:write-profile (:completion blocked)))))
            "with a shape profile for each non-nil value"))

      (testing "and NOTHING was lost — the values are durable in the write log"
        ;; This is the gate's own reasoning, asserted rather than assumed:
        ;; reducing :writes to shape is only safe because the write events
        ;; exist. If they did not, this would come back empty.
        (let [recovered (value-log/writes-for (:events blocked) (:completion blocked))]
          (is (= blocked-writes recovered)
              "every blocked write round-trips out of the canonical write log,
               nil-valued keys included")))

      (testing "the block payload — WHY it blocked — is not what we traded away"
        (is (= (:status (:completion blocked)) :blocked)
            "status survives; only the value payload moved")))))
