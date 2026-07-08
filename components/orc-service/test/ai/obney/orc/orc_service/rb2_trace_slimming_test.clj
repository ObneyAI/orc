(ns ai.obney.orc.orc-service.rb2-trace-slimming-test
  "RB-2a: The stored :sheet/execution-traced event must no longer inline each
   node's full :inputs/:outputs (they were redundant with the granular
   [:tick tick-id] node-execution events and bloated the event to ~19 MB).
   The on-demand node-trace-detail query must still return the SAME full
   inputs/outputs, now sourced from those granular events."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.todo-processors :as td]
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
