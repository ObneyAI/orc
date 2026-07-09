(ns ai.obney.orc.evaluation.trace-extraction-test
  "RB-2c: get-traces-raw is a PUBLIC library API that reads the raw
   :sheet/execution-traced events. RB-2b stopped storing the trace-level
   :input-snapshot/:output-snapshot on those events (each was a full copy of
   the tick blackboard), so get-traces-raw must now hydrate them on demand
   from the tick's durable events (trace-id == tick-id):

   - :output-snapshot ← the tick's FINAL :sheet/tree-tick-completed :outputs
   - :input-snapshot  ← the accumulated tick blackboard (non-nil values),
     the pure event fold that mirrors the tick-execution-contexts read model

   Contract: STORED WINS — a trace event that still carries the keys
   (pre-RB-2b events) is returned verbatim; hydration happens AFTER
   limit/sort so only returned traces pay the event read."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.evaluation.core.trace-extraction :as tx]
            ;; grain's event store rejects appends for event types with no
            ;; registered schema — load the :sheet/* event schemas.
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Test context — a bare in-memory grain store (get-traces-raw needs only
;; :event-store + :tenant-id)
;; =============================================================================

(defmacro with-ctx
  [[ctx-sym] & body]
  `(let [store# (es/start {:conn {:type :in-memory}
                           :event-pubsub nil
                           :logger nil})
         ~ctx-sym {:event-store store# :tenant-id (random-uuid)}]
     (try
       ~@body
       (finally
         (es/stop store#)))))

(defn- append!
  "Append one event, failing LOUDLY on the store's anomaly-map returns so a
   broken fixture can't masquerade as an empty query result."
  [ctx type tags body]
  (let [evt (es/->event {:type type :tags tags :body body})]
    (when (::anom/category evt)
      (throw (ex-info "->event failed" evt)))
    (let [result (es/append (:event-store ctx)
                            {:tenant-id (:tenant-id ctx)
                             :events [evt]})]
      (when (::anom/category result)
        (throw (ex-info "append failed" result))))))

(defn- append-tick-events!
  "Seed the durable [:tick tick-id] events a real async execution leaves
   behind: a snapshot-based tree-tick-started, a value write, an intermediate
   :running completion (no outputs), and the final completion."
  [ctx sheet-id tick-id inputs final-outputs]
  (append! ctx :sheet/tree-tick-started
           #{[:sheet sheet-id] [:tick tick-id]}
           {:sheet-id sheet-id
            :tick-id tick-id
            :inputs inputs
            :execution-snapshot {:blackboard-entries {:draft {:sheet-id sheet-id
                                                              :key :draft
                                                              :schema :string
                                                              :value nil
                                                              :version 0}}
                                 :nodes-by-id {}
                                 :root-node-id (random-uuid)}})
  (append! ctx :sheet/execution-value-written
           #{[:sheet sheet-id] [:tick tick-id]}
           {:sheet-id sheet-id :tick-id tick-id
            :key :summary :value "a concise summary"})
  ;; ticks re-tick: an intermediate :running completion carries no :outputs
  (append! ctx :sheet/tree-tick-completed
           #{[:sheet sheet-id] [:tick tick-id]}
           {:sheet-id sheet-id :tick-id tick-id :root-status :running})
  (append! ctx :sheet/tree-tick-completed
           #{[:sheet sheet-id] [:tick tick-id]}
           {:sheet-id sheet-id :tick-id tick-id :root-status :success
            :outputs final-outputs}))

(defn- append-slim-trace!
  "The RB-2b assembler's stored event: NO :input-snapshot/:output-snapshot.
   Body/tags mirror the store-execution-trace command (commands.clj ~L1220)."
  [ctx sheet-id trace-id started-at]
  (append! ctx :sheet/execution-traced
           #{[:sheet sheet-id] [:trace trace-id]}
           {:trace-id trace-id
            :sheet-id sheet-id
            :started-at started-at
            :completed-at started-at
            :duration-ms 500
            :status :success
            :node-traces []}))

(defn- append-stored-trace!
  "A pre-RB-2b event that still CARRIES the snapshots."
  [ctx sheet-id trace-id started-at input-snapshot output-snapshot]
  (append! ctx :sheet/execution-traced
           #{[:sheet sheet-id] [:trace trace-id]}
           {:trace-id trace-id
            :sheet-id sheet-id
            :started-at started-at
            :completed-at started-at
            :duration-ms 500
            :status :success
            :input-snapshot input-snapshot
            :output-snapshot output-snapshot
            :node-traces []}))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest get-traces-raw-hydrates-slim-traces
  (testing "A slim trace (RB-2b events, no stored snapshots) comes back with
            :input-snapshot = accumulated tick blackboard (non-nil values)
            and :output-snapshot = the FINAL tree-tick-completed :outputs."
    (with-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)          ;; trace-id == tick-id
            inputs {:document "the source text"}
            ;; final outputs gather every entry incl. nil (no nil filtering)
            final-outputs {:document "the source text"
                           :summary "a concise summary"
                           :draft nil}]
        (append-tick-events! ctx sheet-id tick-id inputs final-outputs)
        (append-slim-trace! ctx sheet-id tick-id "2026-07-09T10:00:00Z")
        (let [traces (tx/get-traces-raw ctx {:sheet-id sheet-id})]
          (is (= 1 (count traces)))
          (let [trace (first traces)]
            (is (= {:document "the source text"
                    :summary "a concise summary"}
                   (:input-snapshot trace))
                "input-snapshot hydrated: accumulated blackboard, non-nil values only")
            (is (= final-outputs (:output-snapshot trace))
                "output-snapshot hydrated from the final tree-tick-completed, verbatim")))))))

(deftest get-traces-raw-stored-snapshots-win
  (testing "A trace event that still carries the snapshots (pre-RB-2b) is
            returned VERBATIM — hydration must not clobber stored values,
            even when tick events exist that would reconstruct differently."
    (with-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)
            stored-input {:stored "input"}
            stored-output {:stored "output"}]
        ;; tick events exist and would hydrate to something ELSE
        (append-tick-events! ctx sheet-id tick-id
                             {:document "would-be-hydrated"}
                             {:would-be "hydrated"})
        (append-stored-trace! ctx sheet-id tick-id "2026-07-09T10:00:00Z"
                              stored-input stored-output)
        (let [trace (first (tx/get-traces-raw ctx {:sheet-id sheet-id}))]
          (is (= stored-input (:input-snapshot trace)) "stored input-snapshot wins")
          (is (= stored-output (:output-snapshot trace)) "stored output-snapshot wins"))))))

(deftest get-traces-raw-limit-and-sort-preserved
  (testing ":limit still returns the NEWEST traces by :started-at (descending),
            and the returned (post-limit) traces are hydrated."
    (with-ctx [ctx]
      (let [sheet-id (random-uuid)
            old-tick (random-uuid)
            new-tick (random-uuid)]
        (append-tick-events! ctx sheet-id old-tick
                             {:document "old"} {:document "old"})
        (append-tick-events! ctx sheet-id new-tick
                             {:document "new"} {:document "new"})
        (append-slim-trace! ctx sheet-id old-tick "2026-07-08T10:00:00Z")
        (append-slim-trace! ctx sheet-id new-tick "2026-07-09T10:00:00Z")
        (let [traces (tx/get-traces-raw ctx {:sheet-id sheet-id :limit 1})]
          (is (= 1 (count traces)) "limit respected")
          (let [trace (first traces)]
            (is (= new-tick (:trace-id trace)) "newest trace returned (sort preserved)")
            (is (= {:document "new" :summary "a concise summary"}
                   (:input-snapshot trace))
                "the returned trace is hydrated")
            (is (= {:document "new"} (:output-snapshot trace)))))))))
