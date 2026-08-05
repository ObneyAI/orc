(ns ai.obney.orc.orc-service.trace-end-to-end-test
  "Deterministic, network-free E2E coverage for persisted ORC tracing."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [malli.core :as m]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.file-store.interface.protocol :as file-store]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private root-tick-id
  #uuid "00000000-0000-0000-0000-00000000e2e0")

(def ^:private correlation-a
  #uuid "00000000-0000-0000-0000-00000000c0a1")

(def ^:private correlation-b
  #uuid "00000000-0000-0000-0000-00000000c0b2")

(defrecord MemoryFileStore [objects]
  file-store/FileStore
  (start [this] this)
  (stop [this] this)
  (put-file [_ {:keys [file-id file-contents]}]
    (swap! objects assoc file-id file-contents)
    {})
  (get-file [_ {:keys [file-id]}] (get @objects file-id))
  (locate-file [_ {:keys [file-id]}] {:memory/key file-id}))

(defn double-item [{:keys [inputs]}]
  {:item (* 2 (:item inputs))})

(defn branch-left [_] {:left "L"})
(defn branch-right [_] {:right "R"})

(defn summarize [{:keys [inputs]}]
  {:final (str (:left inputs) ":" (:right inputs) ":" (:results inputs))})

(defn- fq [n]
  (str "ai.obney.orc.orc-service.trace-end-to-end-test/" n))

(defn- stable-uuid [value]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str value) "UTF-8")))

(defn- create-node! [ctx sheet-id type parent-id index]
  (let [node-id (stable-uuid [sheet-id type parent-id index])]
    (-> (h/run-and-apply! ctx
                          (cond-> (h/make-create-node-command sheet-id type :node-id node-id)
                            parent-id (assoc :parent-id parent-id)
                            (some? index) (assoc :index index)))
        :command-result/events first :node-id)))

(defn- code-node! [ctx sheet-id parent-id index fn-name reads writes]
  (let [node-id (create-node! ctx sheet-id :leaf parent-id index)]
    (h/run-and-apply! ctx (h/make-set-node-executor-command
                           sheet-id node-id :code :fn (fq fn-name)))
    (h/run-and-apply! ctx (h/make-set-node-io-command
                           sheet-id node-id reads writes))
    node-id))

(defn- sheet! [ctx name keys]
  (let [sheet-id (stable-uuid name)
        sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command
                                            :name name :sheet-id sheet-id))
                     :command-result/events first :sheet-id)]
    (doseq [[k schema] keys]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k schema)))
    sheet-id))

(defn- build-workflow! [ctx]
  (let [keys [[:items [:vector :int]] [:item :int] [:results [:vector :int]]
              [:left :string] [:right :string] [:final :string]]
        child-sheet (sheet! ctx "trace-e2e-child" keys)
        child-root (create-node! ctx child-sheet :sequence nil nil)
        _child-leaf (code-node! ctx child-sheet child-root 0 "summarize"
                                [:results :left :right] [:final])
        parent-sheet (sheet! ctx "trace-e2e-parent" keys)
        root (create-node! ctx parent-sheet :sequence nil nil)
        map-each (create-node! ctx parent-sheet :map-each root 0)
        map-child (code-node! ctx parent-sheet map-each 0 "double-item" [:item] [:item])
        _ (h/run-and-apply! ctx (h/make-set-map-each-config-command
                                 parent-sheet map-each :items :item :results
                                 :max-concurrency 2))
        parallel (create-node! ctx parent-sheet :parallel root 1)
        _ (h/run-and-apply! ctx (h/make-set-parallel-config-command
                                 parent-sheet parallel
                                 :success-policy :all :failure-policy :any))
        _left (code-node! ctx parent-sheet parallel 0 "branch-left" [] [:left])
        _right (code-node! ctx parent-sheet parallel 1 "branch-right" [] [:right])
        delegate (create-node! ctx parent-sheet :delegate root 2)
        _ (h/run-and-apply! ctx (h/make-set-delegate-config-command
                                 parent-sheet delegate child-sheet
                                 :reads [:results :left :right]
                                 :writes [:final]))]
    {:parent-sheet parent-sheet
     :child-sheet child-sheet
     :map-each map-each
     :map-child map-child
     :parallel parallel
     :delegate delegate}))

(defn- query [ctx name args]
  (h/run-query ctx (assoc args :query/name name)))

(defn- node-detail [ctx trace-id instance-id]
  (query ctx :sheet/node-trace-detail
         {:trace-id trace-id :trace-instance-id instance-id}))

(deftest deterministic-workflow-exposes-exact-trace-family
  (testing "one real workflow verifies instance identity, lineage, durations, and query contracts"
    (let [objects (atom {})
          store (->MemoryFileStore objects)]
      (h/with-async-test-context
        [ctx {:context {:orc/value-storage {:type :file-store
                                            :prefix "test/orc-values"}
                        :orc/file-store store}}]
        (let [{:keys [parent-sheet child-sheet map-child] :as workflow}
              (build-workflow! ctx)
              result (runtime/execute ctx parent-sheet {:items [1 2 3]}
                                      :tick-id root-tick-id
                                      :timeout-ms 20000)]
          (is (= :success (:status result)))
          (is (= "L:R:[2 4 6]" (get-in result [:outputs :final])))
          (is (seq @objects) "the run externalized canonical values")
          (is (h/settle-until!
               #(let [family (:query/result
                              (query ctx :sheet/get-trace-family
                                     {:trace-id root-tick-id}))]
                  (= 2 (count (:traces family)))))
              "parent and delegated child traces did not settle")

          (let [get-result (query ctx :sheet/get-trace {:trace-id root-tick-id})
                trace (get-in get-result [:query/result :trace])
                family-from-root (:query/result
                                  (query ctx :sheet/get-trace-family
                                         {:trace-id root-tick-id}))
                child-trace-id (first (:child-trace-ids trace))
                family-from-child (:query/result
                                   (query ctx :sheet/get-trace-family
                                          {:trace-id child-trace-id}))
                map-entries (filter #(= map-child (:node-id %))
                                    (:node-traces trace))
                tick-events (h/read-tick-events ctx root-tick-id)
                started-ids (set (map :event/id
                                      (filter #(= :sheet/node-execution-started
                                                  (:event/type %))
                                              tick-events)))]
            (is (m/validate (schemas/queries :sheet/get-trace-result)
                            (:query/result get-result)))
            (is (m/validate (schemas/queries :sheet/get-trace-family-result)
                            family-from-root))
            (is (= root-tick-id (:root-trace-id trace)))
            (is (nil? (:parent-trace-id trace)))
            (is (= 1 (count (:child-trace-ids trace))))
            (is (= child-sheet (:sheet-id (second (:traces family-from-root)))))
            (is (= family-from-root family-from-child))
            (is (= 2 (count (:traces family-from-root))))

            (is (= 3 (count map-entries)))
            (is (= 3 (count (set (map :trace-instance-id map-entries)))))
            (is (every? started-ids (map :trace-instance-id (:node-traces trace))))
            (let [details (mapv #(node-detail ctx root-tick-id (:trace-instance-id %))
                                map-entries)]
              (is (every? #(m/validate
                            (schemas/queries :sheet/node-trace-detail-result)
                            (:query/result %))
                          details))
              (is (= #{{:item 1} {:item 2} {:item 3}}
                     (set (map #(get-in % [:query/result :inputs]) details))))
              (is (= #{{:item 2} {:item 4} {:item 6}}
                     (set (map #(get-in % [:query/result :outputs]) details)))))

            (doseq [node-trace (:node-traces trace)]
              (is (uuid? (:trace-instance-id node-trace)))
              (is (nat-int? (:duration-ms node-trace))))
            (doseq [composite-id (select-keys workflow [:map-each :parallel :delegate])
                    :let [node-trace (some #(when (= (val composite-id) (:node-id %)) %)
                                           (:node-traces trace))]]
              (is (nat-int? (:duration-ms node-trace))))))))))

(deftest concurrent-operations-are-completely-and-exclusively-correlated
  (testing "one context value groups independent roots and inherited delegates"
    (let [objects (atom {})
          store (->MemoryFileStore objects)]
      (h/with-async-test-context
        [ctx {:context {:orc/value-storage {:type :file-store
                                            :prefix "test/orc-values"}
                        :orc/file-store store}}]
        (let [{:keys [parent-sheet child-sheet]} (build-workflow! ctx)
              run-operation
              (fn [correlation-id suffix]
                (let [operation-ctx (assoc ctx :orc/correlation-id correlation-id)
                      parent-id (stable-uuid [correlation-id suffix :parent])
                      standalone-id (stable-uuid [correlation-id suffix :standalone])
                      parent-result (runtime/execute operation-ctx parent-sheet
                                                     {:items [1 2 3]}
                                                     :tick-id parent-id
                                                     :timeout-ms 20000)
                      standalone-result (runtime/execute operation-ctx child-sheet
                                                         {:results [8 10]
                                                          :left "A"
                                                          :right "B"}
                                                         :tick-id standalone-id
                                                         :timeout-ms 20000)]
                  {:parent parent-result :standalone standalone-result}))
              future-a (future (run-operation correlation-a :a))
              future-b (future (run-operation correlation-b :b))
              result-a (deref future-a 45000 ::timeout)
              result-b (deref future-b 45000 ::timeout)]
          (is (not= ::timeout result-a))
          (is (not= ::timeout result-b))
          (doseq [result [result-a result-b]]
            (is (= :success (get-in result [:parent :status])))
            (is (= :success (get-in result [:standalone :status]))))

          (is (h/settle-until!
               #(and (= 3 (count (get-in (query ctx :sheet/get-correlated-traces
                                                {:correlation-id correlation-a})
                                         [:query/result :traces])))
                     (= 3 (count (get-in (query ctx :sheet/get-correlated-traces
                                                {:correlation-id correlation-b})
                                         [:query/result :traces])))))
              "all correlated traces did not settle")

          (let [correlated-a (:query/result
                              (query ctx :sheet/get-correlated-traces
                                     {:correlation-id correlation-a}))
                correlated-b (:query/result
                              (query ctx :sheet/get-correlated-traces
                                     {:correlation-id correlation-b}))
                ids-a (set (map :trace-id (:traces correlated-a)))
                ids-b (set (map :trace-id (:traces correlated-b)))]
            (doseq [[correlation-id correlated]
                    [[correlation-a correlated-a] [correlation-b correlated-b]]]
              (is (m/validate (schemas/queries :sheet/get-correlated-traces-result)
                              correlated))
              (is (= correlation-id (:correlation-id correlated)))
              (is (= #{1 2} (set (map (comp count :traces) (:families correlated)))))
              (is (every? #(= correlation-id (:correlation-id %))
                          (:traces correlated)))
              (let [events (into []
                                 (es/read (:event-store ctx)
                                          {:tenant-id (:tenant-id ctx)
                                           :tags #{[:correlation correlation-id]}}))
                    by-type (group-by :event/type events)]
                (is (= 3 (count (get by-type :sheet/tree-tick-started))))
                (is (= 3 (count (get by-type :sheet/tree-tick-completed))))
                (is (= 3 (count (get by-type :sheet/execution-traced))))
                (is (every? #(= correlation-id (:correlation-id %)) events))))
            (is (empty? (set/intersection ids-a ids-b)))))))))
