(ns ai.obney.orc.orc-service.result-delivery-trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]))

(def tenant-id #uuid "00000000-0000-0000-0000-000000000000")

(defmacro with-sqlite-store [[store] & body]
  `(let [db-file# (str "/tmp/result-delivery-trace-" (random-uuid) ".db")
         ~store (es/start {:conn {:type :sqlite
                                  :database-file db-file#
                                  :maximum-pool-size 8}})]
     (try
       ~@body
       (finally
         (es/stop ~store)
         (doseq [suffix# ["" "-wal" "-shm"]]
           (io/delete-file (str db-file# suffix#) true))))))

(defn- tick-started [sheet-id tick-id parent-tick-id]
  (es/->event
   {:type :sheet/tree-tick-started
    :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
            parent-tick-id (conj [:parent-tick parent-tick-id]))
    :body (cond-> {:sheet-id sheet-id :tick-id tick-id}
            parent-tick-id (assoc :parent-tick-id parent-tick-id))}))

(defn- write-event [sheet-id tick-id node-id k v]
  (es/->event
   {:type :sheet/execution-value-written
    :tags #{[:sheet sheet-id] [:tick tick-id]}
    :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
           :key k :value v}}))

(defn- completion-event [sheet-id tick-id node-id]
  (es/->event
   {:type :sheet/node-execution-completed
    :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
    :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
           :status :success :write-keys [:result]}}))

(defn- append! [store events]
  (let [result (es/append store {:tenant-id tenant-id :events (vec events)})]
    (is (nil? (:cognitect.anomalies/category result)))
    result))

(deftest node-trace-is-lineage-scoped-and-rehydrates-writes
  (testing "trace includes nested descendants but excludes concurrent unrelated ticks"
    (with-sqlite-store [store]
      (let [sheet-id (random-uuid)
            root-tick (random-uuid)
            child-tick (random-uuid)
            grandchild-tick (random-uuid)
            unrelated-tick (random-uuid)
            root-node (random-uuid)
            child-node (random-uuid)
            grandchild-node (random-uuid)
            unrelated-node (random-uuid)]
        (append! store
                 [(tick-started sheet-id root-tick nil)
                  (tick-started sheet-id unrelated-tick nil)
                  (tick-started sheet-id child-tick root-tick)
                  (tick-started sheet-id grandchild-tick child-tick)
                  (write-event sheet-id root-tick root-node :result :root)
                  (completion-event sheet-id root-tick root-node)
                  (write-event sheet-id child-tick child-node :result :child)
                  (completion-event sheet-id child-tick child-node)
                  (write-event sheet-id grandchild-tick grandchild-node :result :grandchild)
                  (completion-event sheet-id grandchild-tick grandchild-node)
                  ;; This event is appended later than the root start, which
                  ;; made the old timestamp-window implementation include it.
                  (write-event sheet-id unrelated-tick unrelated-node :result :unrelated)
                  (completion-event sheet-id unrelated-tick unrelated-node)])
        (let [trace (#'processors/build-node-trace store tenant-id root-tick)
              by-tick (into {} (map (juxt :tick-id identity)) trace)]
          (is (= #{root-tick child-tick grandchild-tick} (set (keys by-tick))))
          (is (= {:result :root} (:writes (get by-tick root-tick))))
          (is (= {:result :child} (:writes (get by-tick child-tick))))
          (is (= {:result :grandchild} (:writes (get by-tick grandchild-tick))))
          (is (not (contains? by-tick unrelated-tick))))))))

(deftest accumulated-unrelated-history-does-not-enter-node-trace
  (testing "trace cardinality is independent of tenant history size"
    (with-sqlite-store [store]
      (let [sheet-id (random-uuid)
            target-tick (random-uuid)
            target-node (random-uuid)]
        (append! store
                 (concat
                  [(tick-started sheet-id target-tick nil)]
                  (mapcat (fn [_]
                            (let [tick-id (random-uuid)
                                  node-id (random-uuid)]
                              [(tick-started sheet-id tick-id nil)
                               (completion-event sheet-id tick-id node-id)]))
                          (range 1000))
                  [(completion-event sheet-id target-tick target-node)]))
        (let [trace (#'processors/build-node-trace store tenant-id target-tick)]
          (is (= 1 (count trace)))
          (is (= target-tick (:tick-id (first trace))))
          (is (= target-node (:node-id (first trace)))))))))
