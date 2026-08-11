(ns ai.obney.orc.orc-service.trace-time-sqlite-test
  "SQLite-backed replay coverage for canonical mixed-offset trace timestamps."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.trace-time :as trace-time]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]))

(def tenant-id #uuid "00000000-0000-0000-0000-000000000000")

(defmacro with-sqlite-store [[store] & body]
  `(let [db-file# (str "/tmp/trace-time-" (random-uuid) ".db")
         ~store (es/start {:conn {:type :sqlite :database-file db-file#
                                  :maximum-pool-size 4}})]
     (try ~@body
          (finally
            (es/stop ~store)
            (doseq [suffix# ["" "-wal" "-shm"]]
              (io/delete-file (str db-file# suffix#) true))))))

(defn- trace-event [sheet-id trace-id status started-at]
  (es/->event
   {:type :sheet/execution-traced
    :tags #{[:sheet sheet-id] [:trace trace-id] [:tick trace-id]}
    :body {:trace-id trace-id :sheet-id sheet-id
           :root-trace-id trace-id :child-trace-ids []
           :started-at started-at :completed-at started-at
           :duration-ms 0 :status status
           :input-snapshot {} :output-snapshot {} :node-traces []}}))

(deftest mixed-offset-traces-replay-canonically-from-sqlite
  (testing "SQLite round-trip and clean projection replay retain instant chronology"
    (with-sqlite-store [store]
      (let [sheet-id (random-uuid)
            older-id (random-uuid)
            timeout-id (random-uuid)
            events [(trace-event sheet-id older-id :success "2026-08-11T13:51:00Z")
                    (trace-event sheet-id timeout-id :timeout "2026-08-11T06:52:00-07:00")]
            append-result (es/append store {:tenant-id tenant-id :events events})
            stored (into [] (es/read store {:tenant-id tenant-id
                                            :tags #{[:sheet sheet-id]}}))
            replayed (vals (reduce rm/traces* {} stored))
            ordered (sort (fn [a b]
                            (trace-time/compare-timestamps (:started-at b)
                                                           (:started-at a)))
                          replayed)]
        (is (nil? (:cognitect.anomalies/category append-result)))
        (is (= [timeout-id older-id] (mapv :trace-id ordered)))
        (is (= #{"2026-08-11T13:51:00Z" "2026-08-11T13:52:00Z"}
               (set (map :started-at replayed))))))))
