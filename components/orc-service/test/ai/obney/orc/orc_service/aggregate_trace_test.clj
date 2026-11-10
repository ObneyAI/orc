(ns ai.obney.orc.orc-service.aggregate-trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.profile :as profile])
  (:import [java.nio.file Files]))

(def proofs (atom []))

(defn transform-item [{:keys [inputs]}]
  {:item (str "item-" (:item inputs))})

(defn transform-or-fail [{:keys [inputs]}]
  (if (= 2 (:item inputs))
    (throw (ex-info "Controlled item failure" {}))
    (transform-item {:inputs inputs})))

(defn old-source [_] {:result "old" :extra "unrelated old value"})
(defn new-source [_] {:result "a longer forwarded value"})

(deftest forwarded-profile-uses-only-the-declared-references
  ;; Deliberately stage earlier writes of the same node/context through public
  ;; commands. This guards command metadata; it does not assert that a normal
  ;; workflow repeats a terminal completion or changes historical rehydration.
  (h/with-test-context [ctx]
    (let [definition
          (sheet/workflow (str "declared-forwarded-profile-" (random-uuid))
            (sheet/blackboard {:result :string :extra :string})
            (sheet/sequence "root"
              (sheet/code "prior" :fn "ai.obney.orc.orc-service.aggregate-trace-test/old-source"
                :reads [] :writes [:result :extra])
              (sheet/code "later" :fn "ai.obney.orc.orc-service.aggregate-trace-test/new-source"
                :reads [] :writes [:result])))
          id (sheet/build-workflow! ctx definition)
          nodes (sheet/get-nodes-for-sheet ctx id)
          prior (:id (first (filter #(= "prior" (:name %)) nodes)))
          later (:id (first (filter #(= "later" (:name %)) nodes)))
          tick (random-uuid)
          command! (fn [command]
                     (let [result (h/run-and-apply! ctx
                                    (merge {:command/id (random-uuid) :command/timestamp (time/now)} command))]
                       (is (not (:cognitect.anomalies/category result)) (pr-str result))
                       result))
          _ (command! {:command/name :sheet/tick-tree :sheet-id id :tick-id tick :inputs {}})
          complete! (fn [node fields]
                      (command! (merge {:command/name :sheet/complete-node-execution
                                        :sheet-id id :tick-id tick :node-id node :node-type :leaf
                                        :status :success} fields)))
          _ (complete! prior {:writes (old-source nil)})
          _ (complete! later {:writes (new-source nil)})
          before (sheet/value-log-read-tick-events ctx (:tenant-id ctx) tick)
          source-event (last (filter #(and (= :sheet/execution-value-written (:event/type %))
                                           (= later (:node-id %)) (= :result (:key %))) before))
          source {:tick-id tick :event-id (:event/id source-event)}
          _ (complete! prior {:write-sources {:result source}})
          after (sheet/value-log-read-tick-events ctx (:tenant-id ctx) tick)
          completion (last (filter #(= :sheet/node-execution-completed (:event/type %)) after))]
      (is (some? source-event))
      (is (= [:result] (:write-keys completion)))
      (is (= {:result (profile/profile-value (:result (new-source nil)))} (:write-profile completion)))
      (is (= {:result source} (:write-sources completion)))
      (is (not (contains? completion :writes)))
      (is (= (count (filter #(= :sheet/execution-value-written (:event/type %)) before))
             (count (filter #(= :sheet/execution-value-written (:event/type %)) after))))
      (swap! proofs conj {:label "staged-prior-node-history" :completion completion
                         :canonical-writes (sheet/value-log-resolve-writes ctx (:tenant-id ctx) tick completion)
                         :declared-reference source :events after}))))

(defn- workflow [function-name]
  (sheet/workflow (str "aggregate-trace-" (random-uuid))
    (sheet/blackboard {:items [:vector :int] :item [:or :int :string]
                       :results [:vector [:or :int :string]]})
    (sheet/sequence "outer"
      (sheet/sequence "forwarder"
        (apply sheet/map-each "aggregate" :from :items :as :item :into :results :parallel 2
          (when function-name
            [(sheet/code "item" :fn (str "ai.obney.orc.orc-service.aggregate-trace-test/" function-name)
               :reads [:item] :writes [:item]
               :retry {:max-attempts 1 :backoff-ms []})]))))))

(deftest aggregate-and-forwarder-profiles-match-canonical-values
  (doseq [[label function-name items expected expected-status]
          [["complete" "transform-item" [1 2 3] ["item-1" "item-2" "item-3"] :success]
           ["empty" "transform-item" [] [] :success]
           ["partial" "transform-or-fail" [1 2 3] ["item-1" "item-3"] :partial]
           ["no-child" nil [1 2 3] [1 2 3] :success]]]
    (testing label
      (let [directory (str (Files/createTempDirectory "orc-aggregate-trace-"
                              (make-array java.nio.file.attribute.FileAttribute 0)))
            tenant (random-uuid)]
        (h/with-async-test-context
          [ctx {:context {:tenant-id tenant}
                :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite")
                                   :maximum-pool-size 3}}]
          (let [id (sheet/build-workflow! ctx (workflow function-name))
                result (sheet/execute ctx id {:items items} :timeout-ms 20000 :max-ticks 1)
                tick (:trace-id result)
                _ (is (= expected-status (:status result)) (pr-str result))
                _ (is (= expected (get-in result [:outputs :results])))
                _ (is (h/settle-until!
                        #(some? (get-in (h/run-query ctx (h/make-get-trace-query tick)) [:query/result :trace]))))
                trace (get-in (h/run-query ctx (h/make-get-trace-query tick)) [:query/result :trace])
                events (sheet/value-log-read-tick-events ctx tenant tick)
                completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
                nodes (into {} (map (juxt :node-id identity)) (:node-traces trace))
                aggregate (first (filter #(= "aggregate" (:node-name %)) (:node-traces trace)))
                aggregate-writes (filter #(and (= :sheet/execution-value-written (:event/type %))
                                               (= :results (:key %))) events)
                profile {:results (profile/profile-value expected)}]
            (is (some? aggregate))
            (is (= 1 (count aggregate-writes)) "aggregate payload has exactly one canonical home")
            (is (= (:node-id aggregate) (:node-id (first aggregate-writes))))
            (is (= expected (:value (first aggregate-writes))))
            (doseq [name ["aggregate" "forwarder" "outer"]
                    :let [node (first (filter #(= name (:node-name %)) (:node-traces trace)))
                          completion (first (filter #(= (:node-id node) (:node-id %)) completions))
                          detail (:query/result
                                   (h/run-query ctx {:query/name :sheet/node-trace-detail
                                                     :trace-id tick
                                                     :trace-instance-id (:trace-instance-id node)}))]]
              (is (some? completion) name)
              (is (= {:results expected} (sheet/value-log-resolve-writes ctx tenant tick completion)) name)
              (is (= [:results] (:write-keys completion)) name)
              (is (= profile (:write-profile completion)) name)
              (is (= [:results] (:write-keys node)) name)
              (is (= profile (:output-profile node)) name)
              (is (= {:results expected} (:outputs detail)) name)
              (is (not (contains? completion :writes)) "lifecycle metadata never duplicates the aggregate value"))
            (is (= profile (profile/profile-values {:results expected})))
            (doseq [completion completions
                    :let [node (get nodes (:node-id completion))]
                    :when (and (= "item" (:node-name node)) (= :success (:status completion)))
                    :let [input (sheet/value-log-resolve-reads ctx tenant tick completion)
                          output (sheet/value-log-resolve-writes ctx tenant tick completion)]]
              (is (= {:item (str "item-" (:item input))} output)
                  "each successful iteration retains its own input/output identity"))
            (swap! proofs conj {:label label :directory directory :result result :trace trace :events events})))))))
