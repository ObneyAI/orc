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
  (testing ":sheet/execution-traced (trace event) accepts :status :partial"
    (let [schema (schemas/events :sheet/execution-traced)
          ev {:trace-id (random-uuid) :sheet-id (random-uuid)
              :started-at "t0" :completed-at "t1" :duration-ms 1
              :status :partial
              :input-snapshot {} :output-snapshot {} :node-traces []}]
      (is (m/validate schema ev)
          ":partial must validate — map-each emits it (D-008 completion)")
      (is (not (m/validate schema (assoc ev :status :bogus)))
          "enum still constrains — a bogus status is rejected"))))

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
          summary {:trace-id (random-uuid) :sheet-id (random-uuid)
                   :sheet-name "s" :status :partial
                   :started-at "t0" :duration-ms 1 :node-count 1}]
      (is (m/validate schema {:traces [summary] :total 1})
          "a runs-screen row with :partial status must validate")
      (is (not (m/validate schema {:traces [(assoc summary :status :bogus)] :total 1}))
          "enum still constrains"))))
