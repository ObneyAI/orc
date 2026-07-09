(ns ai.obney.orc.orc-service.rb2-trace-slimming-test
  "RB-2a: The stored :sheet/execution-traced event must no longer inline each
   node's full :inputs/:outputs (they were redundant with the granular
   [:tick tick-id] node-execution events and bloated the event to ~19 MB).
   The on-demand node-trace-detail query must still return the SAME full
   inputs/outputs, now sourced from those granular events.

   RB-2b: The stored :sheet/execution-traced event must also no longer carry
   the trace-level :input-snapshot/:output-snapshot (full copies of the tick
   blackboard — the residual ~28 MB/event after RB-2a). The detail queries
   (get-trace, run-detail-screen, runs-screen selected-trace) source them on
   demand: :output-snapshot from the tick's FINAL :sheet/tree-tick-completed
   event's :outputs, :input-snapshot from the same tick-execution-context
   blackboard reconstruction the assembler used (trace-id == tick-id)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.todo-processors :as td]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Event fixtures — append real node-execution events to a real store
;; =============================================================================

(defn- append-started!
  [ctx sheet-id tick-id node-id inputs]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :sheet/node-execution-started
                         :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
                         :body {:sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :inputs inputs}})]}))

(defn- append-completed!
  "Append a node-execution-completed event. :inputs carries the execution
   context (map-each keys) exactly as the real complete-node-execution command
   does (todo_processors.clj:1144) — this is what correlates it to the matching
   started event."
  [ctx sheet-id tick-id node-id status writes inputs]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :sheet/node-execution-completed
                         :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
                         :body (cond-> {:sheet-id sheet-id
                                        :tick-id tick-id
                                        :node-id node-id
                                        :status status}
                                 (seq writes) (assoc :writes writes)
                                 (seq inputs) (assoc :inputs inputs))})]}))

;; =============================================================================
;; Test 1 — reroute preserves the UI contract (full inputs/outputs, byte-identical)
;; =============================================================================

(deftest node-trace-detail-sources-inputs-outputs-from-granular-events
  (testing "node-trace-detail returns the FULL inputs (context keys stripped)
            and outputs (node's :writes), sourced from the granular
            node-execution-started/completed events for the tick."
    (h/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)          ;; trace-id == tick-id
            node-id  (random-uuid)
            ;; :inputs carries a real user key PLUS a map-each execution-context
            ;; key. The context key must be stripped from the returned inputs
            ;; (exactly as the pre-RB-2a builder did at todo_processors L2575-2577).
            started-inputs {:document "the source text"
                            :threshold 0.75
                            ::td/map-each-index 3}
            writes {:summary "a concise summary"
                    :concept-drafts [{:id 1} {:id 2} {:id 3}]}]
        (append-started! ctx sheet-id tick-id node-id started-inputs)
        ;; completed carries the execution context (map-each key) so it
        ;; correlates to the started event — mirrors the real command.
        (append-completed! ctx sheet-id tick-id node-id :success writes
                           {::td/map-each-index 3})
        (let [result (h/run-query ctx {:query/name :sheet/node-trace-detail
                                       :query/id (random-uuid)
                                       :trace-id tick-id
                                       :node-id node-id
                                       :auth-claims {:user "test"}})
              detail (:query/result result)]
          (is (some? detail) "query returns a result (not an anomaly)")
          (is (= node-id (:node-id detail)))
          (is (= {:document "the source text" :threshold 0.75} (:inputs detail))
              "inputs are the started event's inputs with the ::map-each-index context key stripped")
          (is (= writes (:outputs detail))
              "outputs are the completed event's :writes, verbatim"))))))

(deftest node-trace-detail-not-found-when-no-events
  (testing "Unknown node-id / no granular events → not-found anomaly (path preserved)"
    (h/with-test-context [ctx]
      (let [result (h/run-query ctx {:query/name :sheet/node-trace-detail
                                     :query/id (random-uuid)
                                     :trace-id (random-uuid)
                                     :node-id (random-uuid)
                                     :auth-claims {:user "test"}})]
        (is (= ::anom/not-found (::anom/category result)))))))

;; =============================================================================
;; Test 2 — slimming: built node-traces carry NO :inputs/:outputs
;; =============================================================================

(deftest build-node-traces-omits-inputs-and-outputs
  (testing "build-node-traces produces light-metadata entries only — no
            :inputs / :outputs keys (they now live in the granular events)."
    (let [node-id (random-uuid)
          nodes-by-id {node-id {:name "Extract" :type :leaf :parent-id nil}}
          ts (time/now)
          started-events [{:event/type :sheet/node-execution-started
                           :event/timestamp ts
                           :node-id node-id
                           :inputs {:document "big text" ::td/map-each-index 0}}]
          completed-events [{:event/type :sheet/node-execution-completed
                             :event/timestamp ts
                             :node-id node-id
                             :status :success
                             :duration-ms 120
                             ;; execution context correlates it to the started event
                             :inputs {::td/map-each-index 0}
                             :writes {:concept-drafts [{:id 1} {:id 2}]}}]
          traces (td/build-node-traces nodes-by-id started-events completed-events)]
      (is (= 1 (count traces)))
      (let [t (first traces)]
        (is (not (contains? t :inputs)) "node trace must NOT carry :inputs")
        (is (not (contains? t :outputs)) "node trace must NOT carry :outputs")
        ;; light metadata preserved
        (is (= node-id (:node-id t)))
        (is (= "Extract" (:node-name t)))
        (is (= :leaf (:node-type t)))
        (is (= :success (:status t)))
        (is (= 120 (:duration-ms t)))))))

;; =============================================================================
;; Test 3 — summary path regression: run-detail-screen / runs-screen unaffected
;; =============================================================================

(defn- store-trace!
  [ctx sheet-id trace-id node-traces]
  (h/run-command ctx {:command/name :sheet/store-execution-trace
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :auth-claims {:user "test"}
                      :trace-id trace-id
                      :sheet-id sheet-id
                      :started-at (str (time/now))
                      :completed-at (str (time/now))
                      :duration-ms 500
                      :status :success
                      :input-snapshot {}
                      :output-snapshot {}
                      :node-traces node-traces}))

;; =============================================================================
;; RB-2b Test 1 — the stored event carries NO trace-level snapshots
;; =============================================================================

(defn- store-slim-trace!
  "Store a trace the way the RB-2b assembler now sends it: NO
   :input-snapshot/:output-snapshot on the command."
  [ctx sheet-id trace-id node-traces]
  (h/run-command ctx {:command/name :sheet/store-execution-trace
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :auth-claims {:user "test"}
                      :trace-id trace-id
                      :sheet-id sheet-id
                      :started-at (str (time/now))
                      :completed-at (str (time/now))
                      :duration-ms 500
                      :status :success
                      :node-traces node-traces}))

(defn- read-traced-event
  [ctx trace-id]
  (->> (into [] (es/read (:event-store ctx)
                         {:tags #{[:trace trace-id]}
                          :tenant-id (:tenant-id ctx)}))
       (filter #(= :sheet/execution-traced (:event/type %)))
       first))

(deftest stored-execution-traced-event-carries-no-snapshots
  (testing "store-execution-trace without snapshot keys is accepted and the
            persisted :sheet/execution-traced event carries NO
            :input-snapshot/:output-snapshot keys."
    (h/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            trace-id (random-uuid)
            result (store-slim-trace! ctx sheet-id trace-id [])]
        (is (not (::anom/category result))
            (str "command must be accepted without snapshots: " (::anom/message result)))
        (let [evt (read-traced-event ctx trace-id)]
          (is (some? evt) "the :sheet/execution-traced event was persisted")
          (is (not (contains? evt :input-snapshot))
              "persisted event must NOT carry :input-snapshot")
          (is (not (contains? evt :output-snapshot))
              "persisted event must NOT carry :output-snapshot"))))))

;; =============================================================================
;; RB-2b Test 3 — live end-to-end: the ASSEMBLER no longer sends the snapshots
;; =============================================================================

(defn- create-condition-workflow!
  "Minimal real workflow (sequence -> condition on a declared key), mirroring
   gepa-primitives-test's fixture. Returns sheet-id."
  [ctx]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "RB-2b"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)
        seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        seq-id (-> seq-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Root"))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :test-key :string))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :other-key :string))
        cond-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id seq-id))
        cond-id (-> cond-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id cond-id "check-exists"))
        _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id cond-id {:key :test-key :op :exists}))]
    sheet-id))

(defn- wait-for-traced-event
  "Poll the store for the async-assembled :sheet/execution-traced event."
  [ctx trace-id & {:keys [max-attempts delay-ms] :or {max-attempts 40 delay-ms 50}}]
  (loop [attempt 0]
    (or (read-traced-event ctx trace-id)
        (when (< attempt max-attempts)
          (Thread/sleep delay-ms)
          (recur (inc attempt))))))

(deftest assembler-stores-slim-trace-live
  (testing "A REAL execution's assembled :sheet/execution-traced event carries
            NO :input-snapshot/:output-snapshot, and get-trace still returns
            them byte-identically to the pre-RB-2b stored values:
            input-snapshot == the accumulated tick blackboard (here: the
            execution inputs), output-snapshot == the final
            :sheet/tree-tick-completed event's :outputs."
    (h/with-async-test-context [ctx]
      (let [sheet-id (create-condition-workflow! ctx)
            inputs {:test-key "input-val" :other-key "other-val"}
            result (sheet/execute ctx sheet-id inputs)
            trace-id (:trace-id result)]
        (is (= :success (:status result)))
        (let [evt (wait-for-traced-event ctx trace-id)]
          (is (some? evt) "trace event assembled and stored")
          (is (not (contains? evt :input-snapshot))
              "assembler must NOT put :input-snapshot on the stored event")
          (is (not (contains? evt :output-snapshot))
              "assembler must NOT put :output-snapshot on the stored event"))
        ;; Byte-identity with the pre-change stored values, live:
        (let [final-outputs (->> (into [] (es/read (:event-store ctx)
                                                   {:tags #{[:tick trace-id]}
                                                    :tenant-id (:tenant-id ctx)}))
                                 (filter #(= :sheet/tree-tick-completed (:event/type %)))
                                 last
                                 :outputs)
              trace (-> (h/run-query ctx {:query/name :sheet/get-trace
                                          :query/id (random-uuid)
                                          :trace-id trace-id
                                          :auth-claims {:user "test"}})
                        :query/result)]
          (is (= inputs (:input-snapshot trace))
              "input-snapshot = accumulated blackboard (the seed inputs here — no writes)")
          (is (= (or final-outputs {}) (:output-snapshot trace))
              "output-snapshot = final tree-tick-completed :outputs, verbatim"))))))

;; =============================================================================
;; RB-2b Test 2 — detail queries hydrate the snapshots from durable events
;; =============================================================================

(defn- append-tick-started!
  "Append a snapshot-based :sheet/tree-tick-started (the async path) so the
   tick-execution-contexts read model stores a context for this tick —
   exactly what the assembler's input-snapshot reconstruction reads."
  [ctx sheet-id tick-id inputs blackboard-entries]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :sheet/tree-tick-started
                         :tags #{[:sheet sheet-id] [:tick tick-id]}
                         :body {:sheet-id sheet-id
                                :tick-id tick-id
                                :inputs inputs
                                :execution-snapshot {:blackboard-entries blackboard-entries
                                                     :nodes-by-id {}
                                                     :root-node-id (random-uuid)}}})]}))

(defn- append-value-written!
  [ctx sheet-id tick-id k v]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :sheet/execution-value-written
                         :tags #{[:sheet sheet-id] [:tick tick-id]}
                         :body {:sheet-id sheet-id
                                :tick-id tick-id
                                :key k
                                :value v}})]}))

(defn- append-tick-completed!
  [ctx sheet-id tick-id root-status outputs]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :sheet/tree-tick-completed
                         :tags #{[:sheet sheet-id] [:tick tick-id]}
                         :body (cond-> {:sheet-id sheet-id
                                        :tick-id tick-id
                                        :root-status root-status}
                                 outputs (assoc :outputs outputs))})]}))

(deftest detail-queries-hydrate-snapshots-from-durable-events
  (testing "For a slim trace (no stored snapshots), get-trace,
            run-detail-screen and runs-screen's selected-trace return
            :input-snapshot reconstructed from the tick-execution-context
            blackboard (ACCUMULATED at completion, non-nil values only — the
            exact pre-RB-2b assembler semantics) and :output-snapshot from the
            tick's FINAL :sheet/tree-tick-completed :outputs, verbatim."
    (h/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)          ;; trace-id == tick-id
            ;; Seed: one input + one declared-but-never-written (nil) key.
            inputs {:document "the source text"}
            bb-entries {:draft {:sheet-id sheet-id :key :draft
                                :schema :string :value nil :version 0}}
            ;; Final outputs gather EVERY blackboard entry incl. nil values
            ;; (the tree-tick-completed :else branch does no nil filtering).
            final-outputs {:document "the source text"
                           :summary "a concise summary"
                           :draft nil}
            ;; Pre-change semantics: accumulated blackboard, non-nil only.
            expected-input {:document "the source text"
                            :summary "a concise summary"}]
        (append-tick-started! ctx sheet-id tick-id inputs bb-entries)
        (append-value-written! ctx sheet-id tick-id :summary "a concise summary")
        ;; Ticks re-tick: an intermediate completed (:running, no outputs)
        ;; precedes the final one — hydration must take the LAST.
        (append-tick-completed! ctx sheet-id tick-id :running nil)
        (append-tick-completed! ctx sheet-id tick-id :success final-outputs)
        (store-slim-trace! ctx sheet-id tick-id [])
        (let [detail (-> (h/run-query ctx {:query/name :sheet/run-detail-screen
                                           :query/id (random-uuid)
                                           :trace-id tick-id
                                           :auth-claims {:user "test"}})
                         :query/result :trace)]
          (is (= expected-input (:input-snapshot detail))
              "run-detail-screen input-snapshot = accumulated blackboard, non-nil values")
          (is (= final-outputs (:output-snapshot detail))
              "run-detail-screen output-snapshot = final tree-tick-completed :outputs, verbatim"))
        (let [selected (-> (h/run-query ctx {:query/name :sheet/runs-screen
                                             :query/id (random-uuid)
                                             :trace-id tick-id
                                             :auth-claims {:user "test"}})
                           :query/result :selected-trace)]
          (is (= expected-input (:input-snapshot selected)))
          (is (= final-outputs (:output-snapshot selected))))
        (let [trace (-> (h/run-query ctx {:query/name :sheet/get-trace
                                          :query/id (random-uuid)
                                          :trace-id tick-id
                                          :auth-claims {:user "test"}})
                        :query/result)]
          (is (= expected-input (:input-snapshot trace)))
          (is (= final-outputs (:output-snapshot trace))))))))

(deftest stored-snapshots-win-for-older-events
  (testing "Back-compat: a trace whose stored event still carries snapshots
            (pre-RB-2b events) returns the STORED values verbatim — hydration
            must not clobber them (even when no tick events exist)."
    (h/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            trace-id (random-uuid)]
        (store-trace! ctx sheet-id trace-id [])   ;; stores {} snapshots
        (let [detail (-> (h/run-query ctx {:query/name :sheet/run-detail-screen
                                           :query/id (random-uuid)
                                           :trace-id trace-id
                                           :auth-claims {:user "test"}})
                         :query/result :trace)]
          (is (= {} (:input-snapshot detail)) "stored {} preserved verbatim")
          (is (= {} (:output-snapshot detail)) "stored {} preserved verbatim"))))))

(deftest summary-screens-unchanged-with-slim-node-traces
  (testing "run-detail-screen + runs-screen still return their light summaries
            (node-count, metadata) when node-traces carry no inputs/outputs."
    (h/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            trace-id (random-uuid)
            n1 (random-uuid)
            n2 (random-uuid)
            slim-node-traces [{:node-id n1 :node-name "A" :node-type :leaf
                               :status :success :started-at (str (time/now))}
                              {:node-id n2 :node-name "B" :node-type :leaf
                               :status :success :started-at (str (time/now))}]]
        (store-trace! ctx sheet-id trace-id slim-node-traces)
        (let [detail (-> (h/run-query ctx {:query/name :sheet/run-detail-screen
                                           :query/id (random-uuid)
                                           :trace-id trace-id
                                           :auth-claims {:user "test"}})
                         :query/result :trace)]
          (is (= trace-id (:trace-id detail)))
          (is (= 2 (count (:node-traces detail))) "both node traces present")
          (is (every? #(and (not (contains? % :inputs))
                            (not (contains? % :outputs)))
                      (:node-traces detail))
              "summary node-traces carry no inputs/outputs"))
        (let [runs (-> (h/run-query ctx {:query/name :sheet/runs-screen
                                         :query/id (random-uuid)
                                         :auth-claims {:user "test"}})
                       :query/result)]
          (is (>= (:total runs) 1))
          (let [row (first (filter #(= trace-id (:trace-id %)) (:traces runs)))]
            (is (some? row) "our trace appears in the runs list")
            (is (= 2 (:node-count row)) "node-count still derived from node-traces")))))))
