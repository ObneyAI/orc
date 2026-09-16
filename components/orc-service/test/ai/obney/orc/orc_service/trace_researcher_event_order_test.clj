(ns ai.obney.orc.orc-service.trace-researcher-event-order-test
  "The trace's `:researcher-events` are the campaign's durable account in durable
   order (RR-16/RR-17: one account of a campaign). Ordering must come from the
   events' durable position, never from a lexical comparison of rendered
   timestamps: the entries' `:at` strings are stamped by different producers at
   different precisions, and Java drops trailing zero groups, so
   `…49.640Z` sorts lexically AFTER `…49.640123Z` although it is earlier.
   Found by the RR-23 gate: `public-execution-reticks-and-traces-checkpointed-iterations`
   saw `[:effect-completed :action-completed :effect-claimed …]` — a claim after its own
   effect — once in five full brick runs."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]))

(defn- order
  "RED-first seam: `tp/order-researcher-events` is the pure ordering step this test
   pins; it does not exist until the fix lands."
  [entries]
  (if-let [f (resolve 'ai.obney.orc.orc-service.core.todo-processors/order-researcher-events)]
    (f entries)
    ::missing))

(deftest researcher-events-are-ordered-by-durable-position-not-by-rendered-timestamp
  (testing "a claim stamped at a whole-millisecond instant precedes the effect it claimed even though its string sorts later"
    (let [claim  {:type :effect-claimed  :at "2026-09-11T06:33:49.64Z"     ::tp/durable-order "01a08e6d-0000-7000-8000-000000000001"}
          done   {:type :effect-completed :at "2026-09-11T06:33:49.640123Z" ::tp/durable-order "01a08e6d-0000-7000-8000-000000000002"}
          action {:type :action-completed :at "2026-09-11T06:33:49.640200Z" ::tp/durable-order "01a08e6d-0000-7000-8000-000000000003"}
          ordered (order [action done claim])]
      (is (not= ::missing ordered))
      (is (= [:effect-claimed :effect-completed :action-completed] (mapv :type ordered)))
      (is (= "2026-09-11T06:33:49.64Z" (:at (first ordered))) ":at is kept for display")
      (is (not-any? #(contains? % ::tp/durable-order) ordered) "the ordering key is not leaked into the trace")))
  (testing "sanity: the lexical order of those :at strings is the WRONG order"
    (is (pos? (compare "2026-09-11T06:33:49.64Z" "2026-09-11T06:33:49.640123Z")))))
