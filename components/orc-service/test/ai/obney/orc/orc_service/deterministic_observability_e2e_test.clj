(ns ai.obney.orc.orc-service.deterministic-observability-e2e-test
  "Deterministic end-to-end coverage for durable execution observability."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn uppercase [{:keys [inputs]}]
  {:middle (.toUpperCase ^String (:input inputs))})

(defn suffix [{:keys [inputs]}]
  {:output (str (:middle inputs) "!")})

(defn fail-deep [{:keys [inputs]}]
  (throw (ex-info "deliberate deep failure" {:secret (:secret inputs)})))

(defn echo-item [{:keys [inputs]}]
  {:item (:item inputs)})

(defn delegate-copy [{:keys [inputs]}]
  {:output (str (:input inputs) "-child")})

(defn maybe-fail [{:keys [inputs]}]
  (if (= "fail" (:input inputs))
    (throw (ex-info "filter fixture failure" {}))
    {:output (:input inputs)}))

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-observability-e2e-test/"
       function-name))

(defn- trace-for [ctx result]
  (let [trace-id (:trace-id result)]
    (is (uuid? trace-id))
    (is (h/settle-until!
         #(some? (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                         [:query/result :trace]))))
    (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
            [:query/result :trace])))

(defn- node-detail [ctx trace-id instance-id]
  (:query/result
   (h/run-query ctx {:query/name :sheet/node-trace-detail
                     :trace-id trace-id
                     :trace-instance-id instance-id})))

(defn- pipeline [name]
  (sheet/workflow name
    (sheet/blackboard {:input :string :middle :string :output :string})
    (sheet/sequence "main"
      (sheet/code "uppercase" :fn (fq "uppercase") :reads [:input] :writes [:middle])
      (sheet/code "suffix" :fn (fq "suffix") :reads [:middle] :writes [:output]))))

(deftest det-e2e-059-trace-shape-and-exact-io
  (testing "summary traces retain profiles while supported node detail rehydrates exact values"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (pipeline "det-e2e-059-trace-io"))
            result (sheet/execute ctx sheet-id {:input "hello"})
            trace (trace-for ctx result)
            leaves (filter #(= :leaf (:node-type %)) (:node-traces trace))
            details (mapv #(node-detail ctx (:trace-id result) (:trace-instance-id %)) leaves)]
        (is (= :success (:status trace)))
        (is (= 3 (count (:node-traces trace))))
        (is (every? :input-profile leaves))
        (is (every? :output-profile leaves))
        (is (every? #(not (contains? % :inputs)) leaves)
            "summary node entries do not inline exact values")
        (is (= [{:input "hello"} {:middle "HELLO"}] (mapv :inputs details)))
        (is (= [{:middle "HELLO"} {:output "HELLO!"}] (mapv :outputs details)))))))

(deftest det-e2e-060-failure-trace
  (testing "a deep failure retains exact failing input, lineage, status, error, and duration"
    (h/with-async-test-context [ctx]
      (let [definition (sheet/workflow "det-e2e-060-failure-trace"
                         (sheet/blackboard {:secret :string :unused :string})
                         (sheet/sequence "outer"
                           (sheet/parallel "inner"
                             {:success-policy :all :failure-policy :any}
                             (sheet/code "deep-failure" :fn (fq "fail-deep")
                               :reads [:secret] :writes [:unused]))))
            result (sheet/execute ctx (sheet/build-workflow! ctx definition)
                                  {:secret "exact-fixture"})
            trace (trace-for ctx result)
            failed (first (filter #(and (= :leaf (:node-type %))
                                        (= :failure (:status %)))
                                  (:node-traces trace)))
            detail (node-detail ctx (:trace-id result) (:trace-instance-id failed))]
        (is (= :failure (:status result)))
        (is (= :failure (:status trace)))
        (is (string? (:error failed)))
        (is (re-find #"deliberate deep failure" (:error failed)))
        (is (nat-int? (:duration-ms failed)))
        (is (uuid? (:parent-trace-instance-id failed)))
        (is (= {:secret "exact-fixture"} (:inputs detail)))))))

(deftest det-e2e-061-repeated-node-identity
  (testing "map-each retains one stable node ID and one unique instance ID per invocation"
    (h/with-async-test-context [ctx]
      (let [definition (sheet/workflow "det-e2e-061-repeated-node"
                         (sheet/blackboard {:items [:vector :int]
                                            :item :int
                                            :results [:vector :int]})
                         (sheet/map-each "items" :from :items :as :item :into :results :parallel 3
                           (sheet/code "echo" :fn (fq "echo-item")
                             :reads [:item] :writes [:item])))
            result (sheet/execute ctx (sheet/build-workflow! ctx definition)
                                  {:items [10 20 30 40]})
            trace (trace-for ctx result)
            leaves (filter #(= :leaf (:node-type %)) (:node-traces trace))]
        (is (= [10 20 30 40] (get-in result [:outputs :results])))
        (is (= 4 (count leaves)))
        (is (= 1 (count (set (map :node-id leaves)))))
        (is (= 4 (count (set (map :trace-instance-id leaves)))))
        (is (every? uuid? (map :parent-trace-instance-id leaves)))))))

(deftest det-e2e-062-trace-family-from-descendant
  (testing "family lookup from either root or delegated child returns the same complete family"
    (h/with-async-test-context [ctx]
      (let [child-id (sheet/build-workflow!
                      ctx
                      (sheet/workflow "det-e2e-062-child"
                        (sheet/blackboard {:input :string :output :string})
                        (sheet/code "copy" :fn (fq "delegate-copy")
                          :reads [:input] :writes [:output])))
            parent-id (sheet/build-workflow!
                       ctx
                       (sheet/workflow "det-e2e-062-parent"
                         (sheet/blackboard {:input :string :output :string})
                         (sheet/delegate "child" :target-sheet-id child-id
                           :reads [:input] :writes [:output])))
            result (sheet/execute ctx parent-id {:input "x"})
            trace (trace-for ctx result)
            child-id* (first (:child-trace-ids trace))
            root-query (get-in (h/run-query ctx {:query/name :sheet/get-trace-family
                                                  :trace-id (:trace-id result)})
                               [:query/result])
            child-query (get-in (h/run-query ctx {:query/name :sheet/get-trace-family
                                                   :trace-id child-id*})
                                [:query/result])]
        (is (uuid? child-id*))
        (is (= (:root-trace-id root-query) (:root-trace-id child-query)))
        (is (= (set (:traces root-query)) (set (:traces child-query))))
        (is (= #{(:trace-id result) child-id*}
               (set (map :trace-id (:traces root-query)))))
        (is (= 2 (count (:traces root-query))))))))

(deftest det-e2e-063-correlation-query
  (testing "caller correlation groups independent roots and descendants but excludes unrelated traces"
    (h/with-async-test-context [ctx]
      (let [child-id (sheet/build-workflow!
                      ctx
                      (sheet/workflow "det-e2e-063-child"
                        (sheet/blackboard {:input :string :output :string})
                        (sheet/code "copy" :fn (fq "delegate-copy")
                          :reads [:input] :writes [:output])))
            parent-id (sheet/build-workflow!
                       ctx
                       (sheet/workflow "det-e2e-063-parent"
                         (sheet/blackboard {:input :string :output :string})
                         (sheet/delegate "child" :target-sheet-id child-id
                           :reads [:input] :writes [:output])))
            correlation-id (random-uuid)
            first-result (sheet/execute ctx parent-id {:input "a"}
                                        :correlation-id correlation-id)
            second-result (sheet/execute ctx parent-id {:input "b"}
                                         :correlation-id correlation-id)
            unrelated (sheet/execute ctx parent-id {:input "other"})
            _ (doseq [r [first-result second-result unrelated]] (trace-for ctx r))
            queried (get-in (h/run-query ctx {:query/name :sheet/get-correlated-traces
                                               :correlation-id correlation-id})
                            [:query/result])
            ids (set (map :trace-id (:traces queried)))]
        (is (= correlation-id (:correlation-id queried)))
        (is (= 4 (count ids)) "two roots plus two delegated children")
        (is (contains? ids (:trace-id first-result)))
        (is (contains? ids (:trace-id second-result)))
        (is (not (contains? ids (:trace-id unrelated))))
        (is (= 2 (count (:families queried))))))))

(deftest det-e2e-064-trace-filters
  (testing "sheet, status, version, node, time, limit, and combined filters select exact traces"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow!
                      ctx
                      (sheet/workflow "det-e2e-064-filters"
                        (sheet/blackboard {:input :string :output :string})
                        (sheet/code "maybe" :fn (fq "maybe-fail")
                          :reads [:input] :writes [:output])))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))
            first-result (sheet/execute ctx sheet-id {:input "first"} :use-version 1)
            first-trace (trace-for ctx first-result)
            failed-result (sheet/execute ctx sheet-id {:input "fail"} :use-version 1)
            failed-trace (trace-for ctx failed-result)
            draft-result (sheet/execute ctx sheet-id {:input "draft"} :force-draft true)
            draft-trace (trace-for ctx draft-result)
            other-sheet-id (sheet/build-workflow!
                            ctx
                            (sheet/workflow "det-e2e-064-unrelated-sheet"
                              (sheet/blackboard {:input :string :output :string})
                              (sheet/code "maybe" :fn (fq "maybe-fail")
                                :reads [:input] :writes [:output])))
            unrelated-result (sheet/execute ctx other-sheet-id {:input "unrelated"})
            _ (trace-for ctx unrelated-result)
            node-id (-> failed-trace :node-traces first :node-id)
            query (fn [& opts]
                    (get-in (h/run-query ctx (apply h/make-get-traces-query sheet-id opts))
                            [:query/result]))
            successes (query :status :success)
            failures (query :status :failure)
            version-one (query :version-number 1)
            by-node (query :node-id node-id)
            since-first (query :since (:started-at first-trace))
            combined (query :version-number 1 :status :failure :node-id node-id :limit 1)]
        (is (= #{(:trace-id first-result) (:trace-id draft-result)}
               (set (map :trace-id (:traces successes)))))
        (is (not (some #{(:trace-id unrelated-result)}
                       (map :trace-id (:traces successes)))))
        (is (= [(:trace-id failed-result)] (mapv :trace-id (:traces failures))))
        (is (= #{(:trace-id first-result) (:trace-id failed-result)}
               (set (map :trace-id (:traces version-one)))))
        (is (= #{(:trace-id first-result) (:trace-id failed-result)}
               (set (map :trace-id (:traces by-node)))))
        (is (not (some #{(:trace-id first-result)} (map :trace-id (:traces since-first)))))
        (is (= [(:trace-id failed-result)] (mapv :trace-id (:traces combined))))
        (is (= 1 (count (:traces combined))))
        (is (= (:trace-id failed-trace) (:trace-id failed-result)))
        (is (= (:trace-id draft-trace) (:trace-id draft-result)))))))

(deftest det-e2e-146-mixed-offset-traces-order-before-limit
  (testing "mixed timestamp spellings normalize and compare by instant across filters and lookup"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (pipeline "det-e2e-146-trace-time"))
            success-id (random-uuid)
            timeout-id (random-uuid)
            failure-id (random-uuid)
            store! (fn [trace-id status started-at completed-at]
                     (h/run-and-apply!
                      ctx {:command/id (random-uuid)
                           :command/timestamp (java.time.OffsetDateTime/now)
                           :command/name :sheet/store-execution-trace
                           :trace-id trace-id :sheet-id sheet-id
                           :root-trace-id trace-id :child-trace-ids []
                           :started-at started-at :completed-at completed-at
                           :duration-ms 1000 :status status
                           :input-snapshot {} :output-snapshot {} :node-traces []}))]
        (store! success-id :success
                "2026-08-11T13:51:00Z" "2026-08-11T13:51:01Z")
        (store! timeout-id :timeout
                "2026-08-11T06:52:00-07:00" "2026-08-11T06:52:01-07:00")
        (store! failure-id :failure
                "2026-08-11T13:53:00Z" "2026-08-11T13:53:01Z")
        (let [query (fn [& opts]
                      (get-in (h/run-query ctx (apply h/make-get-traces-query sheet-id opts))
                              [:query/result]))
              recent (query :limit 2)
              since (query :since "2026-08-11T06:51:30-07:00")
              timeouts (query :status :timeout)
              direct (get-in (h/run-query ctx (h/make-get-trace-query timeout-id))
                             [:query/result :trace])]
          (is (= [failure-id timeout-id] (mapv :trace-id (:traces recent))))
          (is (= #{failure-id timeout-id} (set (map :trace-id (:traces since)))))
          (is (= [timeout-id] (mapv :trace-id (:traces timeouts))))
          (is (= timeout-id (:trace-id direct)))
          (is (= "2026-08-11T13:52:00Z" (:started-at direct)))
          (is (every? #(.endsWith ^String (:started-at %) "Z")
                      (:traces (query)))))))))

(deftest det-e2e-118-observability-outage-does-not-corrupt-execution
  (testing "blocking, failed, and unacknowledged exports remain bounded and isolated"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (pipeline "det-e2e-118-export-isolation"))
            control (sheet/execute ctx sheet-id {:input "same"})
            control-trace-ready?
            (h/settle-until!
             #(<= (count (:node-trace control))
                  (count (get-in (h/run-query
                                  ctx (h/make-get-trace-query (:trace-id control)))
                                 [:query/result :trace :node-traces])))
             :timeout-ms 120000)
            control-trace (get-in (h/run-query
                                   ctx (h/make-get-trace-query (:trace-id control)))
                                  [:query/result :trace])
            expected (mapv (juxt :node-id :node-type :status)
                           (:node-traces control-trace))
            release-first (promise)
            calls (atom 0)
            exporter (sheet/start-telemetry-exporter!
                      (:event-pubsub ctx)
                      #{:sheet/tree-tick-completed}
                      (fn [[event]]
                        (case (swap! calls inc)
                          1 (do (deref release-first 2000 nil)
                                {:accepted-ids #{(:event/id event)}})
                          2 (throw (ex-info "induced exporter outage" {}))
                          3 {:accepted-ids #{}}
                          {:accepted-ids #{(:event/id event)}}))
                      :capacity 4
                      :max-attempts 3
                      :event-predicate #(and (= sheet-id (:sheet-id %))
                                             (not= (:trace-id control) (:tick-id %))))
            foreign-sheet-id (sheet/build-workflow!
                              ctx (pipeline "det-e2e-118-unrelated-export-source"))
            results (mapv (fn [_] (sheet/execute ctx sheet-id {:input "same"}))
                          (range 4))
            foreign-result (sheet/execute ctx foreign-sheet-id {:input "other"})]
        (is (= :success (:status foreign-result))
            "an unrelated terminal exercises the exporter predicate")
        (is control-trace-ready? "the durable control trace is fully projected")
        ;; The exporter worker is deliberately blocked while all workflows run.
        (is (every? #(= (select-keys control [:status :outputs])
                        (select-keys % [:status :outputs]))
                    results))
        (deliver release-first true)
        (let [result-ticks (set (map :trace-id results))
              terminals (->> (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:sheet/tree-tick-completed}})
                             (into [])
                             (filter #(contains? result-ticks (:tick-id %)))
                             vec)]
          (is (h/settle-until!
               #(= (count terminals)
                   (:accepted (sheet/telemetry-exporter-stats exporter)))))
          (let [before-stop (sheet/telemetry-exporter-stats exporter)
                t0 (System/nanoTime)
                stopped (sheet/stop-telemetry-exporter! exporter :timeout-ms 2000)
                elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
            (is (= (mapv :event/id terminals) (:accepted-ids before-stop))
                "acknowledged stable IDs retain source correlation order exactly once")
            (is (= (count terminals) (count (distinct (:accepted-ids before-stop)))))
            (is (pos? (:failures before-stop)) "exporter failure is observable")
            (is (>= (:retried before-stop) 2)
                "throws and missing acknowledgements are explicitly retried")
            (is (zero? (:dropped before-stop)))
            (is (<= (:max-occupancy before-stop) (:capacity before-stop)))
            (is (:stopped? stopped))
            (is (< elapsed-ms 2000.0))))
        ;; Verify durable projections after the deliberately blocked exporter
        ;; has drained and stopped. The exporter may delay sibling subscribers,
        ;; but it must not alter their eventual durable result.
        (is (every?
             (fn [result]
               (and (h/settle-until!
                     #(= (count expected)
                         (count (get-in (h/run-query
                                        ctx (h/make-get-trace-query (:trace-id result)))
                                       [:query/result :trace :node-traces])))
                     :timeout-ms 120000)
                    (= expected
                       (mapv (juxt :node-id :node-type :status)
                             (:node-traces (trace-for ctx result))))))
             (into [control] results))
            "export state cannot alter durable trace counts or order")))))
