(ns ai.obney.orc.orc-service.map-each-node-test
  "Tests for map-each node execution."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]))

;; =============================================================================
;; Test Functions
;; =============================================================================

(defn double-value [{:keys [inputs]}]
  (let [item (get inputs :current-item)]
    {:current-item (update item :value * 2)}))

(defn add-score [{:keys [inputs]}]
  (let [item (get inputs :current-item)]
    {:score (* (:id item) 10)}))

(def concurrency-tracker (atom {:max 0 :current 0}))

(defn tracked-process [{:keys [inputs]}]
  (swap! concurrency-tracker update :current inc)
  (swap! concurrency-tracker (fn [t] (assoc t :max (max (:max t) (:current t)))))
  (Thread/sleep 50)
  (swap! concurrency-tracker update :current dec)
  (let [item (get inputs :current-item)]
    {:current-item (assoc item :processed true)}))

(defn fail-on-id-2
  "Test fn: throws for items where :id = 2, succeeds otherwise.
   Used by D-008 partial-results tests."
  [{:keys [inputs]}]
  (let [item (get inputs :current-item)]
    (if (= 2 (:id item))
      (throw (ex-info "Intentional failure for id=2" {:item item}))
      {:current-item (assoc item :processed true)})))

;; =============================================================================
;; Map-Each Tests
;; =============================================================================

(deftest map-each-trace-execution-key-distinguishes-repeated-child-node
  (testing "same child node id gets distinct trace correlation keys per map-each index"
    (let [node-id (random-uuid)
          parent-id (random-uuid)
          started-0 {:node-id node-id
                     :inputs {:current-item {:id 1}
                              ::tp/map-each-index 0
                              ::tp/map-each-parent parent-id}}
          completed-0 {:node-id node-id
                       :inputs {::tp/map-each-index 0
                                ::tp/map-each-parent parent-id}
                       :writes {:current-item {:id 1 :processed true}}}
          started-1 {:node-id node-id
                     :inputs {:current-item {:id 2}
                              ::tp/map-each-index 1
                              ::tp/map-each-parent parent-id}}
          completed-1 {:node-id node-id
                       :inputs {::tp/map-each-index 1
                                ::tp/map-each-parent parent-id}
                       :writes {:current-item {:id 2 :processed true}}}
          completions (into {} (map (juxt tp/trace-execution-key identity))
                            [completed-0 completed-1])]
      (is (not= (tp/trace-execution-key started-0)
                (tp/trace-execution-key started-1)))
      (is (= {:current-item {:id 1 :processed true}}
             (:writes (get completions (tp/trace-execution-key started-0)))))
      (is (= {:current-item {:id 2 :processed true}}
             (:writes (get completions (tp/trace-execution-key started-1))))))))

(deftest map-each-basic-test
  (testing "processes each item in the list"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each Basic"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        ;; Declare blackboard
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        ;; Create map-each node
        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results))

          ;; Create child that doubles value
          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/double-value"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))

            ;; Execute
            (let [result (sheet/execute ctx sheet-id {:items [{:id 1 :value 5}
                                                               {:id 2 :value 10}
                                                               {:id 3 :value 15}]})]
              (is (= :success (:status result)))
              (let [results (get-in result [:outputs :results])]
                (is (= 3 (count results)))
                (is (= 10 (:value (first results))))
                (is (= 20 (:value (second results))))
                (is (= 30 (:value (nth results 2))))))))))))

(deftest map-each-empty-list-test
  (testing "handles empty source list"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each Empty"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results))

          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/double-value"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))

            (let [result (sheet/execute ctx sheet-id {:items []})]
              (is (= :success (:status result)))
              (is (= [] (get-in result [:outputs :results]))))))))))

(deftest map-each-concurrency-test
  (testing "respects max-concurrency limit"
    (h/with-async-test-context [ctx]
      (reset! concurrency-tracker {:max 0 :current 0})

      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each Concurrency"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          ;; Set max-concurrency to 2
          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results :max-concurrency 2))

          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/tracked-process"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))

            ;; Execute with 6 items
            (let [result (sheet/execute ctx sheet-id {:items (mapv #(hash-map :id %) (range 6))})]
              (is (= :success (:status result)))
              (is (= 6 (count (get-in result [:outputs :results]))))
              ;; Max concurrent should not exceed 2
              (is (<= (:max @concurrency-tracker) 2)))))))))

(deftest map-each-with-parallel-child-test
  (testing "map-each with parallel child merges outputs into items"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each + Parallel"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        ;; Create map-each
        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results))

          ;; Create child leaf that writes to separate key
          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/add-score"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:score]))

            ;; Execute
            (let [result (sheet/execute ctx sheet-id {:items [{:id 1 :name "A"}
                                                               {:id 2 :name "B"}
                                                               {:id 3 :name "C"}]})]
              (is (= :success (:status result)))
              (let [results (get-in result [:outputs :results])]
                (is (= 3 (count results)))
                ;; Each item should have the score merged in
                (is (= 10 (:score (first results))))
                (is (= 20 (:score (second results))))
                (is (= 30 (:score (nth results 2))))))))))))

;; =============================================================================
;; D-008: Partial results
;; =============================================================================

(deftest map-each-partial-failure-test
  (testing "when some children fail, map-each emits :partial with summary; output is successes-only"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Map Each Partial"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))

        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                  :items :current-item :results))

          (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
                leaf-id (-> leaf-result :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                    :fn "ai.obney.orc.orc-service.map-each-node-test/fail-on-id-2"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))

            ;; 5 items: ids 1, 2, 3, 4, 5. id=2 fails, so 4 succeed and 1 fails at index 1.
            (let [result (sheet/execute ctx sheet-id {:items [{:id 1} {:id 2} {:id 3} {:id 4} {:id 5}]})
                  tick-id (:trace-id result)
                  events (when tick-id
                           (into [] (es/read (:event-store ctx)
                                      {:tags #{[:tick tick-id]}
                                       :tenant-id (:tenant-id ctx)})))
                  map-each-completion (->> events
                                           (filter #(= :sheet/node-execution-completed (:event/type %)))
                                           (filter #(= map-id (:node-id %)))
                                           first)]
              (is (some? map-each-completion)
                  "Should find a node-execution-completed event for the map-each node")
              (is (= :partial (:status map-each-completion))
                  "Map-each status should be :partial when some children fail")
              ;; partial-summary populated
              (let [summary (:partial-summary map-each-completion)]
                (is (some? summary) ":partial-summary should be present")
                (is (= 5 (:total summary)))
                (is (= 4 (:succeeded summary)))
                (is (= 1 (:failed summary)))
                (is (= [1] (:failure-indices summary)) "id=2 is at index 1")
                (is (some? (get-in summary [:failure-reasons 1]))
                    "failure-reasons should contain an entry for index 1"))
              ;; Output blackboard key contains 4 items (successes only, no failure markers)
              (let [output-results (get-in result [:outputs :results])]
                (is (= 4 (count output-results))
                    "Output should contain only the 4 successful items (no failure markers inline)")
                (is (every? (fn [r] (not (and (map? r) (= :failure (:__status r)))))
                            output-results)
                    "No output item should carry :__status :failure marker")))))))))

(defn count-results
  "Test fn: writes a single :count value (the size of :results) to the blackboard.
   Used by D-008 sequence-parent-continuation test."
  [{:keys [inputs]}]
  (let [rs (get inputs :results)]
    {:count (count rs)}))

(deftest sequence-parent-treats-partial-as-continuation
  (testing "when map-each emits :partial, parent sequence continues to the next sibling"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Sequence Partial Continuation"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :count :int))

        ;; Build: [:sequence [:map-each (fail-on-id-2) :into :results] [:leaf count-results]]
        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each :parent-id seq-id))
              map-id (-> map-result :command-result/events first :node-id)
              _ (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                        :items :current-item :results))
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)
              _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                        :fn "ai.obney.orc.orc-service.map-each-node-test/fail-on-id-2"))
              _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))
              after-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id :index 1))
              after-id (-> after-result :command-result/events first :node-id)
              _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id after-id :code
                                        :fn "ai.obney.orc.orc-service.map-each-node-test/count-results"))
              _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id after-id [:results] [:count]))

              ;; 3 items: ids 1, 2, 3. id=2 fails → map-each is :partial with 2 succeeded.
              result (sheet/execute ctx sheet-id {:items [{:id 1} {:id 2} {:id 3}]})
              tick-id (:trace-id result)
              events (when tick-id
                       (into [] (es/read (:event-store ctx)
                                  {:tags #{[:tick tick-id]}
                                   :tenant-id (:tenant-id ctx)})))
              after-completion (->> events
                                    (filter #(= :sheet/node-execution-completed (:event/type %)))
                                    (filter #(= after-id (:node-id %)))
                                    first)]
          (is (= :partial (:status result))
              "Sequence root with partial map-each should surface :partial overall")
          (is (some? after-completion)
              "The leaf-after sibling MUST complete — proves sequence continued past :partial map-each")
          (is (= :success (:status after-completion))
              "leaf-after itself succeeds (writes :count from :results)")
          (is (= 2 (get-in result [:outputs :count]))
              "leaf-after sees :results with 2 successful items (id=2 was filtered out)"))))))
