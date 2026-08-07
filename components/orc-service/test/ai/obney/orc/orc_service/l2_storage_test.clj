(ns ai.obney.orc.orc-service.l2-storage-test
  "Storage-cost invariants for the L2 read-model cache (LMDB).

   storage_budget_test covers the EVENT LOG. This covers the other copy: the
   Fressian-encoded read-model state the projection layer writes to LMDB.

   The metric of record is WRITE TRAFFIC, not resting entry size. A partitioned
   read model projected with a :tags scope routes to p-partitioned-full
   (read-model-processor-v2/core.clj), which never consults L1 and re-encodes
   its whole entry on every projection that observes a new event — with no
   >=10-event threshold. So an entry embedding blackboard values is paid back
   once per write-bearing projection, not once per tick: measured at 11 full
   re-encodes on the four-leaf fixture below, and it grows with node count.

     l2-amplification = bytes written to L2 / canonical payload bytes

   Amplification is scale-free: growing the payload grows the canonical term
   too, so a fixed byte ceiling would be vacuous on small fixtures and flaky on
   large ones.

   Deterministic :code executors only — no network, no model."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Budgets — ceilings, not targets
;; =============================================================================

(def ^:private budgets
  "Measured on this fixture BEFORE the change (values cached in L2), by
   l2-baseline-report:

     scale 4:  425,765 bytes written to tick-execution-contexts for a
               40,000-byte payload  =  10.6x amplification, over 11 puts of
               the partition entry
     resting entry: 42,248 bytes — the whole document, verbatim
     scaling:  12,248 -> 102,258 bytes when the payload grows 10x  =  8.35x

   The entry tracks payload size almost exactly, and every projection that
   observes a new event pays it again. AFTER the change both terms collapse:
   the entry holds ~100 bytes of metadata per key regardless of value size.

   These are ceilings, not targets."
  {:l2-amplification   3.0
   :l2-entry-scaling   1.05})

;; =============================================================================
;; Deterministic executors
;; =============================================================================

(def ^:private payload-unit
  "One unit of synthetic payload, distinctive so a substring search over raw
   stored bytes is unambiguous."
  (apply str (repeat 1000 "abcdefghij")))

(defn produce-doc [{:keys [inputs]}]
  {:doc (apply str (repeat (or (:scale inputs) 1) payload-unit))})

(defn summarize-a [{:keys [inputs]}] {:out-a (str "A:" (count (:doc inputs)))})
(defn summarize-b [{:keys [inputs]}] {:out-b (str "B:" (count (:doc inputs)))})
(defn summarize-c [{:keys [inputs]}] {:out-c (str "C:" (count (:doc inputs)))})

;; =============================================================================
;; Fixture
;; =============================================================================

(defn- fq [s] (str "ai.obney.orc.orc-service.l2-storage-test/" s))

(defn- setup-fanout!
  "One large value with three readers. Every node execution triggers a fresh
   projection of the tick context, so a cached blackboard holding :doc is
   re-encoded on each of them."
  [ctx]
  (let [r (h/run-and-apply! ctx (h/make-create-sheet-command :name "L2Fanout"))
        sheet-id (-> r :command-result/events first :sheet-id)]
    (doseq [k [:scale :doc :out-a :out-b :out-c]]
      (h/run-and-apply! ctx (h/make-declare-key-command
                             sheet-id k (if (= k :scale) :int :string))))
    (let [seq-id (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                     :command-result/events first :node-id)
          add! (fn [idx f reads writes]
                 (let [id (-> (h/run-and-apply!
                               ctx (h/make-create-node-command
                                    sheet-id :leaf :parent-id seq-id :index idx))
                              :command-result/events first :node-id)]
                   (h/run-and-apply! ctx (h/make-set-node-executor-command
                                          sheet-id id :code :fn (fq f)))
                   (h/run-and-apply! ctx (h/make-set-node-io-command
                                          sheet-id id reads writes))
                   id))]
      (add! 0 "produce-doc" [:scale] [:doc])
      (add! 1 "summarize-a" [:doc] [:out-a])
      (add! 2 "summarize-b" [:doc] [:out-b])
      (add! 3 "summarize-c" [:doc] [:out-c])
      sheet-id)))

(defn- dispatch! [ctx sheet-id inputs]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        r (cp/process-command
           (assoc ctx :command {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/tick-tree
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :inputs inputs
                                :options {:timeout-ms 20000}}))]
    (is (not (:cognitect.anomalies/category r))
        (str "dispatch failed: " (:cognitect.anomalies/message r)))
    [(deref p 20000 ::timeout) tick-id]))

(defn- run-fixture!
  "Run the fanout at `scale`, returning everything needed for accounting.
   L2 counters are zeroed after sheet construction so the measurement covers
   the TICK, not the commands that built the sheet."
  [ctx scale]
  (let [sheet-id (setup-fanout! ctx)
        _ (h/l2-reset-stats! ctx)
        [result tick-id] (dispatch! ctx sheet-id {:scale scale})]
    (is (= :success (:status result)) (str "run failed: " (:error result)))
    (h/settle-until! #(h/trace-stored? ctx tick-id))
    {:sheet-id sheet-id
     :tick-id tick-id
     :payload-bytes (* scale (count payload-unit))
     :l2-total (h/l2-write-bytes ctx)
     :l2-tick-ctx (h/l2-write-bytes ctx "tick-execution-contexts")
     :entry-bytes (h/tick-context-l2-bytes ctx sheet-id tick-id 4)}))

;; =============================================================================
;; Baseline report
;; =============================================================================

(deftest l2-baseline-report
  (testing "L2 write accounting is measurable and attributable per read model"
    (h/with-async-test-context [ctx {:count-cache? true}]
      (let [{:keys [l2-total l2-tick-ctx payload-bytes entry-bytes]}
            (run-fixture! ctx 4)]
        (println)
        (println "=== L2 (LMDB read-model cache) accounting: fan-out, 4 units ===")
        (println (h/format-l2-stats (h/l2-stats ctx)))
        (println (format "payload:                 %d bytes" payload-bytes))
        (println (format "L2 writes (all models):  %d bytes (%.1fx payload)"
                         l2-total (double (/ l2-total (max payload-bytes 1)))))
        (println (format "L2 writes (tick ctx):    %d bytes (%.1fx payload)"
                         l2-tick-ctx (double (/ l2-tick-ctx (max payload-bytes 1)))))
        (println (format "resting entry:           %d bytes" entry-bytes))
        (is (pos? l2-total) "L2 accounting must observe writes")
        (is (pos? l2-tick-ctx) "tick-execution-contexts must be attributable")))))

;; =============================================================================
;; Invariant: the cached entry must not carry blackboard VALUES
;; =============================================================================

(deftest l2-holds-no-blackboard-values
  (testing "the stored tick-execution-context blackboard is metadata only"
    ;; Asserted on the DECODED STORED BYTES, not on an accessor's return: an
    ;; accessor-level check can be satisfied by a resolver that quietly re-adds
    ;; values, whereas this cannot.
    (h/with-async-test-context [ctx {:count-cache? true}]
      (let [{:keys [sheet-id tick-id]} (run-fixture! ctx 2)
            stored (h/tick-context-l2-entry ctx sheet-id tick-id 4)
            bb (some-> stored :data (get tick-id) :blackboard)]
        (is (some? stored) "expected a stored tick-execution-contexts entry")
        (is (seq bb) "expected a stored blackboard")
        (is (not (h/contains-value-key? bb))
            (str ":value must not appear anywhere in the cached blackboard, got "
                 (pr-str (into {} (map (fn [[k e]] [k (keys e)]) bb)))))))))

(deftest l2-entry-does-not-contain-payload
  (testing "the raw stored bytes contain no trace of the payload"
    ;; The strongest available proof, and the one that makes no assumption
    ;; about stored shape: search the raw LMDB bytes for the payload marker.
    ;; It cannot be satisfied by relocating a value to a different key.
    (h/with-async-test-context [ctx {:count-cache? true}]
      (let [{:keys [sheet-id tick-id]} (run-fixture! ctx 2)
            raw (h/l2-entry-raw ctx "tick-execution-contexts" 4
                                {:tags #{[:tick tick-id]}} sheet-id)]
        (is (some? raw) "expected a stored entry")
        (is (not (h/bytes-contain? raw payload-unit))
            "payload must not be present in the cached read-model entry")))))

(deftest l2-holds-a-profile-for-every-valued-key
  (testing "trace assembly can work from the cache alone"
    ;; assemble-execution-trace derives :input-snapshot / :output-snapshot from
    ;; value SHAPES. Once values leave the cache it must read those shapes from
    ;; the stored :profile, so every key that has a value needs one.
    (h/with-async-test-context [ctx {:count-cache? true}]
      (let [{:keys [sheet-id tick-id]} (run-fixture! ctx 2)
            stored (h/tick-context-l2-entry ctx sheet-id tick-id 4)
            bb (some-> stored :data (get tick-id) :blackboard)
            resolved (rm/get-tick-blackboard ctx tick-id)]
        (doseq [[k e] bb]
          (when (some? (:value (get resolved k)))
            (is (some? (:profile e))
                (str "key " k " has a value but no cached :profile"))))))))

;; =============================================================================
;; Invariant: L2 cost must not scale with payload
;; =============================================================================

(deftest l2-entry-does-not-scale-with-payload
  (testing "stored entry size is independent of payload size"
    (let [small (h/with-async-test-context [ctx {:count-cache? true}]
                  (:entry-bytes (run-fixture! ctx 1)))
          large (h/with-async-test-context [ctx {:count-cache? true}]
                  (:entry-bytes (run-fixture! ctx 10)))
          ratio (double (/ large (max small 1)))]
      (println (format "[l2-entry-scaling] %d -> %d bytes = %.2fx (budget %.2fx)"
                       small large ratio (:l2-entry-scaling budgets)))
      (is (pos? small) "expected a stored entry at scale 1")
      (is (<= ratio (:l2-entry-scaling budgets))
          (str "cached entry grew " (format "%.2f" ratio)
               "x when the payload grew 10x — it is storing the payload")))))

(deftest l2-write-traffic-within-budget
  (testing "bytes written to L2 do not track payload size"
    (h/with-async-test-context [ctx {:count-cache? true}]
      (let [{:keys [l2-tick-ctx payload-bytes]} (run-fixture! ctx 4)
            amp (double (/ l2-tick-ctx (max payload-bytes 1)))]
        (println (format "[l2-amplification] %.1fx (budget %.1fx) — %d bytes for %d payload"
                         amp (:l2-amplification budgets) l2-tick-ctx payload-bytes))
        (is (<= amp (:l2-amplification budgets))
            (str "tick-execution-contexts wrote " (format "%.1f" amp)
                 "x the payload to L2. Per-key breakdown:\n"
                 (h/format-l2-stats (h/l2-stats ctx))))))))
