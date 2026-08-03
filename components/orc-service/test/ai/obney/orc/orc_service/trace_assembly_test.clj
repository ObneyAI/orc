(ns ai.obney.orc.orc-service.trace-assembly-test
  "Integration coverage for assemble-execution-trace.

   Two halves, asserted differently:
     - STRUCTURE (node identity, parenthood, status, timing, correlation)
       against the trace read model — that is what the trace is for.
     - VALUES through the :sheet/node-trace-detail query, the supported way
       to get a node's inputs/outputs. Asserting through the query rather
       than off the raw event keeps the storage layer free to stop inlining
       them."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Deterministic executors
;; =============================================================================

(defn upcase [{:keys [inputs]}] {:shout (clojure.string/upper-case (:phrase inputs))})
(defn exclaim [{:keys [inputs]}] {:loud (str (:shout inputs) "!")})
(defn boom [_] (throw (ex-info "deliberate failure" {})))
(defn double-item [{:keys [inputs]}]
  {:current-item (* 2 (:current-item inputs))})

(defn- fq [s] (str "ai.obney.orc.orc-service.trace-assembly-test/" s))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- add-leaf!
  [ctx sheet-id parent-id idx fn-name reads writes]
  (let [r (h/run-and-apply! ctx (h/make-create-node-command
                                 sheet-id :leaf :parent-id parent-id :index idx))
        leaf-id (-> r :command-result/events first :node-id)]
    (h/run-and-apply! ctx (h/make-set-node-executor-command
                           sheet-id leaf-id :code :fn (fq fn-name)))
    (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id reads writes))
    leaf-id))

(defn- setup-pipeline!
  "sequence[ upcase -> exclaim ], optionally with a failing third leaf."
  [ctx & {:keys [with-failure?]}]
  (let [sr (h/run-and-apply! ctx (h/make-create-sheet-command :name "Trace Pipeline"))
        sheet-id (-> sr :command-result/events first :sheet-id)]
    (doseq [k [:phrase :shout :loud :never]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-r :command-result/events first :node-id)
          a (add-leaf! ctx sheet-id seq-id 0 "upcase" [:phrase] [:shout])
          b (add-leaf! ctx sheet-id seq-id 1 "exclaim" [:shout] [:loud])
          c (when with-failure?
              (add-leaf! ctx sheet-id seq-id 2 "boom" [:loud] [:never]))]
      {:sheet-id sheet-id :seq-id seq-id :upcase a :exclaim b :boom c})))

(defn- setup-map-each!
  "map-each over :items, doubling each — the case where one node-id executes
   several times and trace correlation has to keep the iterations apart."
  [ctx]
  (let [sr (h/run-and-apply! ctx (h/make-create-sheet-command :name "Trace MapEach"))
        sheet-id (-> sr :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :int]))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :int))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :int]))
    (let [me-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
          me-id (-> me-r :command-result/events first :node-id)
          child (add-leaf! ctx sheet-id me-id 0 "double-item" [:current-item] [:current-item])]
      (h/run-and-apply! ctx (h/make-set-map-each-config-command
                             sheet-id me-id :items :current-item :results))
      {:sheet-id sheet-id :map-each me-id :child child})))

(defn- run!
  [ctx sheet-id inputs]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        res (cp/process-command
             (assoc ctx :command {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/tick-tree
                                  :sheet-id sheet-id
                                  :tick-id tick-id
                                  :inputs inputs
                                  :options {:timeout-ms 20000}}))]
    (is (not (:cognitect.anomalies/category res))
        (str "dispatch failed: " (:cognitect.anomalies/message res)))
    (let [result (deref p 20000 ::timeout)]
      ;; assemble-execution-trace stores the trace from a future, so the
      ;; trace lands slightly after the caller's promise is delivered.
      (Thread/sleep 1500)
      [result tick-id])))

(defn- node-detail
  "Fetch a node's inputs/outputs the supported way."
  [ctx trace-id node-id]
  (:query/result
   (h/run-query ctx {:query/name :sheet/node-trace-detail
                     :trace-id trace-id
                     :node-id node-id})))

;; =============================================================================
;; Structure
;; =============================================================================

(deftest trace-records-execution-structure
  (testing "a completed tick produces a trace keyed by tick-id with one entry per node"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id seq-id upcase exclaim]} (setup-pipeline! ctx)
            [result trace-id] (run! ctx sheet-id {:phrase "hello"})
            trace (rm/get-trace ctx trace-id)]
        (is (= :success (:status result)))
        (is (some? trace) "trace was stored and is retrievable by tick-id")
        (is (= trace-id (:trace-id trace)))
        (is (= sheet-id (:sheet-id trace)))
        (is (= :success (:status trace)))
        (is (some? (:started-at trace)))
        (is (some? (:completed-at trace)))
        (is (nat-int? (:duration-ms trace)))

        (let [by-id (into {} (map (juxt :node-id identity) (:node-traces trace)))]
          (is (= 3 (count (:node-traces trace)))
              "sequence + two leaves")
          (is (= #{seq-id upcase exclaim} (set (keys by-id))))

          (testing "node identity and parenthood are recorded"
            (is (= :sequence (:node-type (by-id seq-id))))
            (is (= :leaf (:node-type (by-id upcase))))
            (is (= seq-id (:parent-id (by-id upcase))))
            (is (= seq-id (:parent-id (by-id exclaim)))))

          (testing "per-node status and timing"
            (doseq [nid [seq-id upcase exclaim]]
              (is (= :success (:status (by-id nid)))
                  (str "node " nid " status"))
              (is (some? (:started-at (by-id nid)))
                  (str "node " nid " started-at")))))))))

(deftest trace-records-failure
  (testing "a failing leaf is recorded with :failure and an error string"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id boom]} (setup-pipeline! ctx :with-failure? true)
            [result trace-id] (run! ctx sheet-id {:phrase "hello"})
            trace (rm/get-trace ctx trace-id)
            failed (first (filter #(= boom (:node-id %)) (:node-traces trace)))]
        (is (= :failure (:status result)))
        (is (= :failure (:status trace)))
        (is (some? failed) "the failing leaf has a node-trace entry")
        (is (= :failure (:status failed)))
        (is (string? (:error failed)) "the error is captured as a string")))))

(deftest trace-keeps-map-each-iterations-distinct
  (testing "one child node-id executing N times yields N node-trace entries"
    ;; Keying only by node-id would collapse every iteration into whichever
    ;; completion was seen last — trace-execution-key exists to prevent that.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id child]} (setup-map-each! ctx)
            [result trace-id] (run! ctx sheet-id {:items [1 2 3]})
            trace (rm/get-trace ctx trace-id)
            child-entries (filter #(= child (:node-id %)) (:node-traces trace))]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (is (= [2 4 6] (:results (:outputs result))) "map-each doubled each item")
        (is (= 3 (count child-entries))
            (str "expected one entry per iteration, got " (count child-entries)))
        (is (every? #(= :success (:status %)) child-entries))))))

;; =============================================================================
;; Values, through the supported query
;; =============================================================================

(deftest node-trace-detail-returns-node-io
  (testing "node-trace-detail resolves a node's inputs and outputs"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id upcase exclaim]} (setup-pipeline! ctx)
            [_ trace-id] (run! ctx sheet-id {:phrase "hello"})]
        (testing "first leaf: reads :phrase, writes :shout"
          (let [d (node-detail ctx trace-id upcase)]
            (is (= upcase (:node-id d)))
            (is (= "HELLO" (get-in d [:outputs :shout])))))
        (testing "second leaf: reads the first leaf's output"
          (let [d (node-detail ctx trace-id exclaim)]
            (is (= "HELLO!" (get-in d [:outputs :loud])))))))))

(deftest trace-snapshots-cover-the-tick-blackboard
  (testing "the trace reports the blackboard keys the tick touched"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-pipeline! ctx)
            [_ trace-id] (run! ctx sheet-id {:phrase "hello"})
            trace (rm/get-trace ctx trace-id)]
        ;; Asserted as key COVERAGE, not as inlined values: which keys the
        ;; tick touched is the durable fact, and it is what a dashboard or
        ;; judge needs to decide what to drill into.
        (is (contains? (set (keys (:output-snapshot trace))) :loud)
            "the final written key is covered by the output snapshot")
        (is (contains? (set (keys (:input-snapshot trace))) :phrase)
            "the seeded input key is covered by the input snapshot")))))
