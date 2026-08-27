(ns ai.obney.orc.orc-service.d008-partial-status-schemas-test
  "D-008 completion (schema catch-up). Map-each emits :status :partial (D-008,
   commit 40e94bea — already in main), and D-008 added :partial to most status
   enums — but MISSED four: the run trace event + the run-query/screen schemas.
   Without :partial in those enums, querying or displaying a PARTIAL run fails
   schema validation. These tests lock in the four straggler enums so the fix
   can't silently regress. (Each also asserts a bogus status is still rejected,
   proving the enum still constrains — the fix widens, it doesn't open.)"
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]))

(deftest execution-traced-event-accepts-partial-status
  (testing "trace creation and revision events accept :status :partial"
    (doseq [event-type [:sheet/execution-traced
                        :sheet/execution-trace-refreshed]]
      (let [schema (schemas/events event-type)
            trace-id (random-uuid)
            ev (cond-> {:trace-id trace-id :sheet-id (random-uuid)
                        :root-trace-id trace-id :child-trace-ids []
                        :started-at "t0" :completed-at "t1" :duration-ms 1
                        :status :partial
                        :input-snapshot {} :output-snapshot {} :node-traces []}
                 (= :sheet/execution-trace-refreshed event-type)
                 (assoc :source-event-count 1))]
        (is (m/validate schema ev)
            (str event-type " must validate :partial — map-each emits it"))
        (is (not (m/validate schema (assoc ev :status :bogus)))
            (str event-type " still constrains bogus statuses"))))))

(deftest get-traces-query-accepts-partial-status
  (testing ":sheet/get-traces status filter accepts :partial"
    (let [schema (schemas/queries :sheet/get-traces)]
      (is (m/validate schema {:sheet-id (random-uuid) :status :partial})
          "filtering a get-traces query by :partial must validate")
      (is (not (m/validate schema {:sheet-id (random-uuid) :status :bogus}))
          "enum still constrains"))))

(deftest runs-screen-query-accepts-partial-status
  (testing ":sheet/runs-screen status filter accepts :partial"
    (let [schema (schemas/queries :sheet/runs-screen)]
      (is (m/validate schema {:status :partial})
          "filtering the runs screen by :partial must validate")
      (is (not (m/validate schema {:status :bogus}))
          "enum still constrains"))))

(deftest runs-screen-result-summary-accepts-partial-status
  (testing ":sheet/runs-screen-result trace summaries accept :status :partial"
    (let [schema (schemas/queries :sheet/runs-screen-result)
          trace-id (random-uuid)
          summary {:trace-id trace-id :sheet-id (random-uuid)
                   :root-trace-id trace-id :child-trace-ids []
                   :sheet-name "s" :status :partial
                   :started-at "t0" :duration-ms 1 :node-count 1}]
      (is (m/validate schema {:traces [summary] :total 1})
          "a runs-screen row with :partial status must validate")
      (is (not (m/validate schema {:traces [(assoc summary :status :bogus)] :total 1}))
          "enum still constrains"))))
