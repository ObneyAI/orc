(ns ai.obney.orc.gepa.atomic-result-publication-test
  "Real Grain publication races at GEPA's durable result boundaries."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- start-optimization [ctx]
  (get-in (tu/process-command! ctx
            {:command/name :gepa/start-optimization
             :sheet-id (random-uuid)
             :trainset [{"question" "q"}]
             :valset [{"question" "q"}]
             :config {:max-metric-calls 10}})
          [:command/result :optimization-id]))

(defn- synchronize-read! [^CountDownLatch barrier]
  (.countDown barrier)
  (when-not (.await barrier 10 TimeUnit/SECONDS)
    (throw (ex-info "Concurrent result reads did not meet" {}))))

(deftest concurrent-candidate-results-publish-once
  (tu/with-test-context [ctx]
    (let [optimization-id (start-optimization ctx)
          candidate-id (get-in (tu/process-command! ctx
                                {:command/name :gepa/create-candidate
                                 :optimization-id optimization-id
                                 :instructions {"answer" "Respond"}
                                 :parent-ids [] :mutation-reason "fixture"})
                              [:command/result :candidate-id])
          command {:command/name :gepa/record-evaluation-result
                   :optimization-id optimization-id :candidate-id candidate-id
                   :scores [1.0] :trace-ids [(random-uuid)] :metric-calls 1}
          original-read rm/get-candidate
          arrived (CountDownLatch. 2)
          results (with-redefs [rm/get-candidate
                               (fn [context opt-id cand-id]
                                 (let [actual (original-read context opt-id cand-id)]
                                   (synchronize-read! arrived)
                                   actual))]
                    (let [a (future (tu/process-command! ctx command))
                          b (future (tu/process-command! ctx command))]
                      [(deref a 15000 ::timeout) (deref b 15000 ::timeout)]))
          facts (into [] (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx)
                                   :types #{:gepa/candidate-evaluated}
                                   :tags #{[:optimization optimization-id]}}))]
      (is (uuid? candidate-id))
      (is (not-any? #{::timeout} results))
      (is (not-any? #(= :cognitect.anomalies/fault
                        (:cognitect.anomalies/category %)) results))
      (is (= 1 (count (filter #(= :cognitect.anomalies/conflict
                                  (:cognitect.anomalies/category %)) results))))
      (is (= 1 (count facts)) "One physical append owns the candidate result")
      (is (= 1 (:total-metric-calls (rm/get-optimization-summary ctx optimization-id)))))))

(deftest concurrent-task-results-publish-once
  (tu/with-test-context [ctx]
    (let [optimization-id (start-optimization ctx)
          call-id (random-uuid)
          command {:command/name :gepa/record-task-call
                   :optimization-id optimization-id :call-id call-id
                   :role :candidate :example-index 0 :trace-id (random-uuid)
                   :output {:answer "original"} :score 1.0}
          original-read es/read
          arrived (CountDownLatch. 2)
          results (with-redefs [es/read
                               (fn [store options]
                                 (let [actual (original-read store options)]
                                   (if (and (identical? store (:event-store ctx))
                                            (= #{:gepa/task-call-completed} (:types options)))
                                     (let [snapshot (into [] actual)]
                                       (synchronize-read! arrived)
                                       snapshot)
                                     actual)))]
                    (let [a (future (tu/process-command! ctx command))
                          b (future (tu/process-command! ctx command))]
                      [(deref a 15000 ::timeout) (deref b 15000 ::timeout)]))
          facts (into [] (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx)
                                   :types #{:gepa/task-call-completed}
                                   :tags #{[:call call-id]}}))]
      (is (not-any? #{::timeout} results))
      (is (not-any? #(= :cognitect.anomalies/fault
                        (:cognitect.anomalies/category %)) results))
      (is (= 1 (count (filter #(= :cognitect.anomalies/conflict
                                  (:cognitect.anomalies/category %)) results))))
      (is (= 1 (count facts)) "One physical append owns the task result")
      (is (= {:answer "original"} (:output (first facts)))))))

(deftest task-result-replay-requires-identical-evidence
  (tu/with-test-context [ctx]
    (let [optimization-id (start-optimization ctx)
          call-id (random-uuid)
          command {:command/name :gepa/record-task-call
                   :optimization-id optimization-id :call-id call-id
                   :role :candidate :example-index 0 :trace-id (random-uuid)
                   :output {:answer "original"} :score 1.0}
          first-result (tu/process-command! ctx command)
          replay (tu/process-command! ctx command)
          competing (tu/process-command! ctx
                      (assoc command :score 0.0 :output {:answer "different"}))
          facts (into [] (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx)
                                   :types #{:gepa/task-call-completed}
                                   :tags #{[:call call-id]}}))]
      (is (not (:cognitect.anomalies/category first-result)))
      (is (= true (get-in replay [:command/result :reused?])))
      (is (empty? (:command-result/events replay)))
      (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category competing))
          "Different evidence cannot be reported as an identical replay")
      (is (= 1 (count facts)))
      (is (= (select-keys command [:trace-id :output :score])
             (select-keys (first facts) [:trace-id :output :score]))))))
