(ns ai.obney.orc.orc-service.model-call-journal-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defn command [name data]
  (merge {:command/name name :command/id (random-uuid) :command/timestamp (time/now)} data))

(def candidates [{:provider :fixture :model "primary" :options {:timeout-ms 10000}}
                 {:provider :fixture :model "backup-1" :options {:timeout-ms 10000}}
                 {:provider :fixture :model "backup-2" :options {:timeout-ms 10000}}])
(def last-proof (atom nil))

(defn start-owner [context]
  (let [event (:event context)]
    {:sheet-id (:sheet-id event) :tick-id (:tick-id event) :node-id (:node-id event)
     :node-start-id (:event/id event) :exec-context (value-log/exec-context (:inputs event)) :role :prime}))

(deftest durable-sticky-selection-is-monotonic-and-mapped-researchers-are-isolated
  (let [starts (atom [])]
    (with-redefs [processors/execute-repl-researcher-node (fn [ctx] (swap! starts conj ctx) nil)]
      (h/with-async-test-context [base]
    (let [ctx (assoc base :auth-claims {})
          sheet (orc/build-workflow! ctx
                  (orc/workflow "journal-proof"
                    (orc/blackboard {:users [:vector :string] :user :string :result :string
                                     :results [:vector [:map [:result :string]]]})
                    (orc/map-each "users" :from :users :as :user :into :results :parallel 2
                      (orc/repl-researcher "research" :instruction "Inspect the fixture" :reads [:user] :writes [:result]))))
          tick (random-uuid)
          _ (h/run-and-apply! ctx (command :sheet/tick-tree {:sheet-id sheet :tick-id tick
                                                           :inputs {:users ["first" "second"]}}))
          _ (is (h/settle-until! #(= 2 (count @starts)) :timeout-ms 5000))
          owners (sort-by #(get (:exec-context %) :ai.obney.orc.orc-service.core.todo-processors/map-each-index)
                          (map start-owner @starts))
          owner-a (first owners) owner-b (second owners)
          bind (fn [owner logical plan]
                 (journal/callbacks ctx (assoc owner :logical-action-id logical) plan :temporary-transport))
          active (bind owner-a "already-in-flight" candidates)
          first-backup (bind owner-a "call-backup-1" candidates)
          second-backup (bind owner-a "call-backup-2" candidates)]
      (is (nil? (::anom/category ((:claim! active) 0 (first candidates)))))
      (is (:model-call-in-flight? ((:claim! active) 0 (first candidates)))
          "A duplicate worker is refused before dispatch without failing the running logical call")
      (doseq [[cb index] [[first-backup 1] [second-backup 2]]]
        (is (nil? (::anom/category ((:claim! cb) index (nth candidates index)))))
        (is (nil? (::anom/category ((:record! cb)
                                   {:candidate-index index :status :success :advanced? false
                                    :outcome {:status :success :outputs {:result "complete"}
                                              :model-fallback {:candidate-index index}}})))))
      (let [ready (java.util.concurrent.CountDownLatch. 2) release (promise)
            workers (mapv (fn [[cb index]]
                            (future (.countDown ready) @release ((:select! cb) index (nth candidates index))))
                          [[first-backup 1] [second-backup 2]])]
        (is (.await ready 2 java.util.concurrent.TimeUnit/SECONDS))
        (deliver release true)
        (let [results (mapv #(deref % 5000 ::timeout) workers)]
          (is (every? #(and (not= ::timeout %) (nil? (::anom/category %))) results) (pr-str results))))
      (is (= 2 (:start-index (bind owner-a "next-call" candidates))))
      (is (nil? (::anom/category ((:select! first-backup) 1 (second candidates)))))
      (is (= 2 (:start-index (bind owner-a "after-late-first-backup" candidates)))
          "A late successful earlier backup never moves the durable selection backward")
      (is (= (journal/configuration-id candidates :temporary-transport)
             (journal/configuration-id (mapv #(-> % (assoc :protocol :chat)
                                                   (update :options assoc :validate? false
                                                           :with-provider-evidence? true
                                                           :model-role-context {:callback (fn [])})) candidates)
                                       :temporary-transport))
          "Equivalent inline and emitted generation settings share a stable role configuration")
      (is (= 0 (:start-index (bind owner-b "mapped-other-user" candidates)))
          "The same researcher node and tick keep mapped execution contexts isolated")
      (is (= 0 (:start-index (bind owner-a "changed-config"
                                   (assoc-in candidates [0 :options :timeout-ms] 11000)))))
      (is (= 0 (:start-index (bind owner-a "already-in-flight" candidates)))
          "A started logical call keeps its own cursor even after other calls advance the role")
      (let [entry {:candidate-index 0 :status :failure :advanced? true
                   :outcome {:status :failure :usage {:total-tokens 7}
                             :model-fallback {:attempts [{:candidate-index 0 :status :failure}]}}}]
        (is (nil? (::anom/category ((:record! active) entry))))
        (let [resumed (bind owner-a "already-in-flight" candidates)]
          (is (= 1 (:start-index resumed)))
          (is (= {:total-tokens 7} (:initial-usage resumed)))
          (is (= [{:candidate-index 0 :status :failure}] (:prior-attempts resumed)))))
      (let [result (qp/process-query (assoc ctx :query
                                           {:query/name :sheet/model-candidate-history :query/id (random-uuid)
                                            :query/timestamp (time/now) :tick-id tick}))
            selected (filter #(= :sheet/model-candidate-selected (:event/type %)) (:query/result result))]
        (is (seq selected))
        (is (apply <= (map :candidate-index selected)))
        (reset! last-proof {:journal (:query/result result) :owner-a owner-a :owner-b owner-b})
        (orc/cancel! ctx tick)))))))
