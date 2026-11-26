(ns ai.obney.orc.evaluation.rr26-composite-score-race-test
  "RR-26 — `record-composite-score` must be idempotent under CONCURRENT duplicate
   delivery, not merely under sequential re-delivery.

   `specs/evaluation.allium` `@invariant OneCompositePerCompletion` and
   `RecordCompositeEvaluation`'s `requires: not exists CompositeScore{...}` make
   this symmetric with `RecordSuccessfulJudgeEvaluation`. `record-judge-score`
   enforces it with a `:command-result/cas` whose predicate re-checks at append
   time; `record-composite-score` did only a check-then-write, so two deliveries
   that both read before either appends can both append. Judge dispatch runs one
   future per judge and the processor path is at-least-once, so concurrent
   delivery is the ordinary case, not an exotic one.

   The existing async suite re-delivers SEQUENTIALLY with a sleep between calls,
   so it passes either way and cannot be this guard."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn- create-ctx []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})]
    {:event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
     :tenant-id (random-uuid)
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)}))

(defn- stop-ctx [ctx]
  (pubsub/stop (:event-pubsub ctx))
  (es/stop (:event-store ctx)))

(defn- composite-command [{:keys [sheet-id node-id tick-id]}]
  {:command/name :evaluation/record-composite-score
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id :node-id node-id :tick-id tick-id
   :composite-score 0.71
   :contributing-judges [{:judge-name "g" :score 0.8 :weight 0.5}
                         {:judge-name "r" :score 0.6 :weight 0.5}]})

(defn- composites-for [ctx tick-id]
  (filterv #(= tick-id (:tick-id %))
           (into [] (es/read (:event-store ctx)
                             {:types #{:judge/composite-score-computed}
                              :tenant-id (:tenant-id ctx)}))))

(deftest det-e2e-290-concurrent-duplicate-composite-delivery-writes-one-event
  (testing "two deliveries of the same composite, released together, produce exactly one event"
    (let [ctx (create-ctx)]
      (try
        (let [trials 25
               results
               (doall
                (for [_ (range trials)]
                  (let [ids {:sheet-id (random-uuid) :node-id (random-uuid) :tick-id (random-uuid)}
                        ready (CountDownLatch. 2)
                        release (promise)
                        contenders
                        (doall
                         (repeatedly
                          2
                          (fn []
                            (future
                              (.countDown ready)
                              (deref release 5000 nil)
                              (cp/process-command (assoc ctx :command (composite-command ids)))))))]
                    (is (.await ready 5 TimeUnit/SECONDS)
                        "both deliveries reach the deterministic release barrier")
                    (deliver release true)
                    (mapv #(deref % 10000 ::timeout) contenders)
                    {:tick-id (:tick-id ids)
                     :events (composites-for ctx (:tick-id ids))})))
               duplicated (filterv #(not= 1 (count (:events %))) results)]
          (is (empty? duplicated)
              (str "every tuple must hold exactly one composite score; "
                   (count duplicated) " of " trials " tuples did not: "
                   (pr-str (mapv (fn [r] [(:tick-id r) (count (:events r))]) duplicated)))))
        (finally (stop-ctx ctx))))))
