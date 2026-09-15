(ns ai.obney.orc.orc-service.durable-call-budget-test
  "Generated from the RR-11 ProviderCallReservation obligations."
  (:require [clojure.java.io :as io]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.provider-call-reservations :as reservations]
            [ai.obney.orc.orc-service.core.todo-processors :as todo]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.time.interface :as time]
            [clojure.test :refer [deftest is testing]]))

(defn- budget-cache-atom []
  (var-get (ns-resolve 'ai.obney.orc.orc-service.core.todo-processors
                       'tick-llm-counts)))

(deftest provider-invocation-identities-are-scoped-to-the-durable-budget-root
  (testing "root-local CAS streams cannot produce the same global reservation identity"
    (let [logical-action-identity "same-logical-provider-action"
          ownership-epoch 1
          provider-attempt-ordinal 0]
      (is (not=
           (reservations/invocation-identity
            (random-uuid) logical-action-identity ownership-epoch
            provider-attempt-ordinal)
           (reservations/invocation-identity
            (random-uuid) logical-action-identity ownership-epoch
            provider-attempt-ordinal))))))

(deftest reservation-cas-requires-the-exact-durable-root-node-campaign-and-iteration
  (let [budget-sheet-id (random-uuid)
        budget-tick-id (random-uuid)
        physical-node-id (random-uuid)
        campaign-node-id (random-uuid)
        candidate {:budget-sheet-id budget-sheet-id
                   :budget-tick-id budget-tick-id
                   :sheet-id budget-sheet-id
                   :tick-id budget-tick-id
                   :node-id physical-node-id
                   :campaign-sheet-id budget-sheet-id
                   :campaign-tick-id budget-tick-id
                   :campaign-node-id campaign-node-id
                   :iteration-index 3
                   :invocation-identity "invocation-new"
                   :ownership-epoch 2}
        root {:event/type :sheet/tree-tick-started
              :sheet-id budget-sheet-id
              :tick-id budget-tick-id
              :options {:llm-call-budget 2}
              :execution-snapshot
              {:nodes-by-id {physical-node-id {:id physical-node-id}
                             campaign-node-id {:id campaign-node-id}}}}
        frontier {:event/type :rlm/researcher-frontier-claimed
                  :sheet-id budget-sheet-id
                  :tick-id budget-tick-id
                  :node-id campaign-node-id
                  :ownership-epoch 2}
        node-start {:event/type :sheet/node-execution-started
                    :sheet-id budget-sheet-id
                    :tick-id budget-tick-id
                    :node-id physical-node-id}
        iteration-claim {:event/type :rlm/researcher-effect-claimed
                         :sheet-id budget-sheet-id
                         :tick-id budget-tick-id
                         :node-id campaign-node-id
                         :iteration-index 3
                         :ownership-epoch 2}
        existing {:event/type :sheet/provider-call-reserved
                  :invocation-identity "invocation-existing"}
        accepts? (fn [request events]
                   ((:predicate-fn (reservations/reservation-cas request))
                    events))]
    (is (true? (accepts? candidate [root node-start frontier iteration-claim existing])))
    (is (= {:decision :duplicate :current 1}
           (select-keys
            (reservations/reservation-decision
             (assoc candidate :invocation-identity "invocation-existing")
             [root node-start frontier iteration-claim existing])
            [:decision :current]))
        "a duplicate conflict retains an authoritative cache count")
    (is (= {:decision :rejected :current 1}
           (select-keys
            (reservations/reservation-decision
             (assoc candidate :ownership-epoch 1)
             [root node-start frontier iteration-claim existing])
            [:decision :current]))
        "a stale/invalid conflict cannot poison the cache with nil")
    (doseq [[label request events]
            [["missing durable budget" candidate
              [(dissoc root :options) node-start frontier iteration-claim]]
             ["mismatched budget root" (assoc candidate :budget-sheet-id
                                                (random-uuid))
              [root node-start frontier iteration-claim]]
             ["stale ownership epoch" (assoc candidate :ownership-epoch 1)
              [root node-start frontier iteration-claim]]
             ["wrong campaign node" (assoc candidate :campaign-node-id
                                             (random-uuid))
              [root node-start frontier iteration-claim]]
             ["wrong trace family" (assoc candidate :tick-id (random-uuid))
              [root node-start frontier iteration-claim]]
             ["wrong physical node" (assoc candidate :node-id (random-uuid))
              [root node-start frontier iteration-claim]]
             ["wrong campaign iteration" (assoc candidate :iteration-index 4)
              [root node-start frontier iteration-claim]]
             ["duplicate invocation" (assoc candidate :invocation-identity
                                              "invocation-existing")
              [root node-start frontier iteration-claim existing]]
             ["exhausted durable capacity" candidate
              [root node-start frontier iteration-claim existing
               (assoc existing :invocation-identity "invocation-2")]]]]
      (is (false? (accepts? request events)) label))))

(deftest det-e2e-273-provider-call-is-durably-reserved-before-invocation
  (testing "a checkpointed campaign persists its exact reservation before provider dispatch"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            events-at-provider (atom nil)
            workflow
            (sheet/workflow "det-e2e-273-provider-reservation"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "Finish deterministically."
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx workflow)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (with-redefs [llm/predict
                      (fn [_provider _module _inputs _options]
                        (swap! provider-calls inc)
                        (reset! events-at-provider (h/read-all-events ctx))
                        {:outputs {:code "(final! {:summary \"reserved\"})"}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (let [result (sheet/execute ctx sheet-id {}
                                      :timeout-ms 10000
                                      :llm-call-budget 1)
                reservation
                (first (filter #(= :sheet/provider-call-reserved (:event/type %))
                               @events-at-provider))
                provider-claim
                (first (filter #(and (= :rlm/researcher-effect-claimed
                                        (:event/type %))
                                     (= :provider (:kind %)))
                               @events-at-provider))]
            (is (= :success (:status result)))
            (is (= "reserved" (get-in result [:outputs :summary])))
            (is (= 1 @provider-calls))
            (is (some? reservation)
                "the durable reservation must already exist when llm/predict begins")
            (when reservation
              (is (= sheet-id (:budget-sheet-id reservation)))
              (is (= (:trace-id result) (:budget-tick-id reservation)))
              (is (= sheet-id (:sheet-id reservation)))
              (is (= (:trace-id result) (:tick-id reservation)))
              (is (= node-id (:node-id reservation)))
              (is (= (:logical-action-identity provider-claim)
                     (:logical-action-identity reservation)))
              (is (string? (:invocation-identity reservation)))
              (is (= 0 (:provider-attempt-ordinal reservation)))
              (is (= (:trace-id result) (:campaign-tick-id reservation)))
              (is (= node-id (:campaign-node-id reservation)))
              (is (= 0 (:iteration-index reservation)))
              (is (= 1 (:ownership-epoch reservation)))
              (is (some? (:reserved-at reservation))))))))))

(deftest provider-call-reservation-has-a-replayable-public-ledger
  (testing "a reservation projects unchanged and a duplicate invocation cannot add a row"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [workflow
            (sheet/workflow "provider-reservation-public-ledger"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "Finish deterministically."
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code "(final! {:summary \"ledger\"})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000
                                      :llm-call-budget 1)
                budget-tick-id (:trace-id result)
                raw (first (filter #(= :sheet/provider-call-reserved
                                        (:event/type %))
                                   (h/read-all-events ctx)))
                projected (first (sheet/get-provider-call-reservations
                                  ctx sheet-id budget-tick-id))
                duplicate-result
                (h/run-command
                 ctx
                 (merge {:command/id (random-uuid)
                         :command/timestamp (time/now)
                         :command/name :sheet/reserve-provider-call
                         :campaign-sheet-id (:sheet-id raw)}
                        (select-keys raw
                                     [:budget-sheet-id :budget-tick-id
                                      :sheet-id :tick-id :node-id
                                      :campaign-tick-id :campaign-node-id
                                      :iteration-index :logical-action-identity
                                      :invocation-identity
                                      :provider-attempt-ordinal
                                      :ownership-epoch :reserved-at])))]
            (is (= (select-keys raw
                                [:budget-sheet-id :budget-tick-id
                                 :sheet-id :tick-id :node-id
                                 :campaign-tick-id :campaign-node-id
                                 :iteration-index :logical-action-identity
                                 :invocation-identity :provider-attempt-ordinal
                                 :ownership-epoch :reserved-at])
                   projected))
            (is (= :cognitect.anomalies/conflict
                   (:cognitect.anomalies/category duplicate-result)))
            (is (= [projected]
                   (sheet/get-provider-call-reservations
                    ctx sheet-id budget-tick-id)))))))))

(deftest generated-child-node-and-provider-retries-reserve-distinct-attempts
  (testing "one logical generated leaf reserves every physical attempt across both retry layers"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-call-number (atom 0)
            reservation-counts-at-entry (atom [])
            source-tree
            '[:sequence
              [:llm {:instruction "Produce the summary."
                     :writes [:summary]
                     :output-schemas {:summary :string}
                     :retry {:max-attempts 2 :backoff-ms [1]}}]
              [:final {:keys [:summary]}]]
            code (str "(emit-tree! (quote " (pr-str source-tree) "))")
            workflow
            (sheet/workflow "provider-reservation-generated-retries"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "Emit the child."
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 3000
                                 :iteration-ms 8000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx workflow)
            researcher-id (:id (first (filter #(= "researcher" (:name %))
                                               (sheet/get-nodes-for-sheet
                                                ctx sheet-id))))]
        (with-redefs [llm/predict
                      (fn [& _]
                        (let [call-number (swap! provider-call-number inc)]
                          (swap! reservation-counts-at-entry conj
                                 (count (filter #(= :sheet/provider-call-reserved
                                                    (:event/type %))
                                                (h/read-all-events ctx))))
                          (case call-number
                            1 {:outputs {:code code}}
                            2 (throw (ex-info "first node attempt/provider attempt 0" {}))
                            3 (throw (ex-info "first node attempt/provider retry 1" {}))
                            4 {:outputs {:summary "retry-complete"}})))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000
                                      :llm-call-budget 10)
                root-tick-id (:trace-id result)
                reservations (sheet/get-provider-call-reservations
                              ctx sheet-id root-tick-id)
                child-reservations (filterv #(not= root-tick-id (:tick-id %))
                                            reservations)
                generated-child-claims
                (filterv #(= :generated-child (:kind %))
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id root-tick-id researcher-id))]
            (is (= :success (:status result)) (pr-str result))
            (is (= "retry-complete" (get-in result [:outputs :summary])))
            (is (= 4 @provider-call-number))
            (is (= [1 2 3 4] @reservation-counts-at-entry)
                "every reservation is visible before its corresponding provider entry")
            (is (= 3 (count child-reservations)) (pr-str reservations))
            (is (= 1 (count (set (map :logical-action-identity
                                      child-reservations)))))
            (is (= 3 (count (set (map :invocation-identity
                                      child-reservations)))))
            (is (= [0 1 2] (mapv :provider-attempt-ordinal
                                 child-reservations)))
            (is (= #{1} (set (map :ownership-epoch child-reservations))))
            (is (= 1 (count (set (map :tick-id child-reservations)))))
            (is (= 1 (count (set (map :node-id child-reservations)))))
            (is (= 1 (count generated-child-claims)))
            (is (= :completed (:status (first generated-child-claims))))))))))

(deftest parallel-descendants-atomically-share-the-final-durable-slot
  (testing "only the reservation append winner enters the provider"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entries (atom 0)
            reservation-arrivals (atom 0)
            both-reservations-arrived (promise)
            real-process-command cp/process-command
            source-tree
            '[:sequence
              [:parallel
               [:llm {:instruction "left"
                      :writes [:left]
                      :output-schemas {:left :string}}]
               [:llm {:instruction "right"
                      :writes [:right]
                      :output-schemas {:right :string}}]]
              [:final {:keys [:left :right]}]]
            code (str "(emit-tree! (quote " (pr-str source-tree) "))")
            workflow
            (sheet/workflow "provider-reservation-atomic-final-slot"
              (sheet/blackboard {:left :string :right :string})
              (sheet/repl-researcher "researcher"
                :instruction "Emit two parallel calls."
                :writes [:left :right]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 3000
                                 :iteration-ms 8000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [cp/process-command
                      (fn [command-context]
                        (let [command (:command command-context)]
                          (when (and (= :sheet/reserve-provider-call
                                        (:command/name command))
                                     (not= (:tick-id command)
                                           (:budget-tick-id command)))
                            (when (= 2 (swap! reservation-arrivals inc))
                              (deliver both-reservations-arrived true))
                            (deref both-reservations-arrived 5000 false))
                          (real-process-command command-context)))
                      llm/predict
                      (fn [_provider module _inputs _options]
                        (let [entry (swap! provider-entries inc)
                              output-name (-> module :outputs first :name)]
                          (if (= entry 1)
                            {:outputs {:code code}}
                            {:outputs {output-name (name output-name)}})))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000
                                      :llm-call-budget 2)
                reservations (sheet/get-provider-call-reservations
                              ctx sheet-id (:trace-id result))
                child-reservations (filterv #(not= (:trace-id result)
                                                    (:tick-id %))
                                            reservations)
                events (h/read-all-events ctx)
                root-cancellations
                (filterv #(and (= :sheet/tick-cancelled (:event/type %))
                               (= (:trace-id result) (:tick-id %)))
                         events)
                child-tick-id (:tick-id (first child-reservations))
                child-cancellations
                (filterv #(and (= :sheet/tick-cancelled (:event/type %))
                               (= child-tick-id (:tick-id %)))
                         events)]
            (is (= 2 (count reservations)) (pr-str reservations))
            (is (= 2 @reservation-arrivals)
                "both child attempts reached the reservation CAS barrier")
            (is (true? (deref both-reservations-arrived 0 false)))
            (is (= 1 (count child-reservations)))
            (is (= 2 @provider-entries)
                "one outer call and only one of the two parallel leaves enter")
            (is (= 1 (count root-cancellations)) (pr-str root-cancellations))
            (is (= 1 (count child-cancellations)) (pr-str child-cancellations))
            (is (= :failure (:status result)) (pr-str result))
            (is (true? (:cancelled? result)) (pr-str result))))))))

(deftest duplicate-deliveries-authorize-only-the-append-winner
  (testing "two identical reservation deliveries cannot both cross the provider gate"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [workflow
            (sheet/workflow "provider-reservation-duplicate-barrier"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "Finish."
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code "(final! {:summary \"done\"})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000
                                      :llm-call-budget 3)
                first-reservation
                (first (sheet/get-provider-call-reservations
                        ctx sheet-id (:trace-id result)))
                ordinal 1
                invocation-identity
                (reservations/invocation-identity
                 (:budget-tick-id first-reservation)
                 (:logical-action-identity first-reservation)
                 (:ownership-epoch first-reservation)
                 ordinal)
                duplicate-command
                (merge {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/reserve-provider-call
                        :campaign-sheet-id sheet-id
                        :provider-attempt-ordinal ordinal
                        :invocation-identity invocation-identity
                        :reserved-at "2030-01-01T00:00:00Z"}
                       (select-keys first-reservation
                                    [:budget-sheet-id :budget-tick-id
                                     :sheet-id :tick-id :node-id
                                     :campaign-tick-id :campaign-node-id
                                     :iteration-index :logical-action-identity
                                     :ownership-epoch]))
                release (promise)
                provider-entries (atom 0)
                deliver! (fn []
                           @release
                           (let [command-result
                                 (h/run-command
                                  ctx (assoc duplicate-command
                                             :command/id (random-uuid)))]
                             (when-not (:cognitect.anomalies/category
                                        command-result)
                               (swap! provider-entries inc))
                             command-result))
                first-delivery (future (deliver!))
                second-delivery (future (deliver!))]
            (deliver release true)
            (let [results [@first-delivery @second-delivery]
                  categories (mapv :cognitect.anomalies/category results)
                  ledger (sheet/get-provider-call-reservations
                          ctx sheet-id (:trace-id result))]
              (is (= 1 (count (remove nil? categories))) (pr-str results))
              (is (= #{:cognitect.anomalies/conflict}
                     (set (remove nil? categories))))
              (is (= 1 @provider-entries))
              (is (= 2 (count ledger)))
              (is (= 1 (count (filter #(= invocation-identity
                                         (:invocation-identity %))
                                      ledger)))))))))))

(deftest durable-reservation-append-anomaly-fails-closed
  (testing "a non-conflict storage anomaly cannot authorize provider dispatch"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            real-process-command cp/process-command
            workflow
            (sheet/workflow "provider-reservation-append-failure"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "Never reach the provider."
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [cp/process-command
                      (fn [command-context]
                        (if (= :sheet/reserve-provider-call
                               (get-in command-context [:command :command/name]))
                          {:cognitect.anomalies/category
                           :cognitect.anomalies/fault
                           :cognitect.anomalies/message
                           "injected durable append failure"}
                          (real-process-command command-context)))
                      llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code "(final! {:summary \"unsafe\"})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000
                                      :llm-call-budget 1)]
            (is (not= :success (:status result)) (pr-str result))
            (is (zero? @provider-calls))
            (is (empty? (sheet/get-provider-call-reservations
                         ctx sheet-id (:trace-id result))))))))))

(deftest outer-inline-and-generated-leaf-share-one-durable-budget-root
  (testing "all checkpointed campaign provider boundaries charge one trace-family ledger"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entries (atom [])
            child-tree
            '[:sequence
              [:llm {:instruction "finish from seed"
                     :reads [:seed]
                     :writes [:summary]
                     :output-schemas {:summary :string}}]
              [:final {:keys [:summary]}]]
            code (str "(do (llm \"inline\" :instruction \"seed\" :writes [:seed]) "
                      "(emit-tree! (quote " (pr-str child-tree) ")))")
            workflow
            (sheet/workflow "provider-reservation-whole-trace"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "Call inline then emit a child."
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 3000
                                 :iteration-ms 8000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [llm/predict
                      (fn [_provider module _inputs _options]
                        (let [output-names (set (map :name (:outputs module)))
                              output-name (cond
                                            (contains? output-names :code) :code
                                            (contains? output-names :seed) :seed
                                            (contains? output-names :summary) :summary)]
                          (swap! provider-entries conj output-name)
                          (case output-name
                            :code {:outputs {:code code}}
                            :seed {:outputs {:seed "durable-seed"}}
                            :summary {:outputs {:summary "whole-trace"}})))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000
                                      :llm-call-budget 3)
                reservations (sheet/get-provider-call-reservations
                              ctx sheet-id (:trace-id result))]
            (is (= :success (:status result)) (pr-str result))
            (is (= "whole-trace" (get-in result [:outputs :summary])))
            (is (= [:code :seed :summary] @provider-entries))
            (is (= 3 (count reservations)) (pr-str reservations))
            (is (= #{(:trace-id result)}
                   (set (map :budget-tick-id reservations))))
            (is (= #{sheet-id}
                   (set (map :budget-sheet-id reservations))))
            (is (= 2 (count (filter #(= (:trace-id result) (:tick-id %))
                                    reservations))))
            (is (= 1 (count (filter #(not= (:trace-id result) (:tick-id %))
                                    reservations))))))))))

(deftest descendant-checkpointed-campaign-cannot-reset-the-ancestor-budget
  (testing "a delegated campaign is fenced by and queryable only through the ancestor root"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entries (atom 0)
            child
            (sheet/workflow "descendant-budget-campaign"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "descendant-researcher"
                :instruction "Use one inline call."
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 3000
                                 :iteration-ms 8000
                                 :campaign-ms 15000}}))
            child-sheet-id (sheet/build-workflow! ctx child)
            middle
            (sheet/workflow "intermediate-descendant"
              (sheet/blackboard {:summary :string})
              (sheet/delegate "nested-descendant"
                :target-sheet-id child-sheet-id
                :writes [:summary]))
            middle-sheet-id (sheet/build-workflow! ctx middle)
            parent
            (sheet/workflow "ancestor-budget-root"
              (sheet/blackboard {:summary :string})
            (sheet/delegate "descendant"
                :target-sheet-id middle-sheet-id
                :writes [:summary]))
            parent-sheet-id (sheet/build-workflow! ctx parent)]
        (with-redefs [llm/predict
                      (fn [_provider module _inputs _options]
                        (swap! provider-entries inc)
                        (if (some #(= :code (:name %)) (:outputs module))
                          {:outputs
                           {:code
                            "(do (llm \"inline\" :instruction \"second\" :writes [:seed]) (final! {:summary \"done\"}))"}}
                          {:outputs {:seed "should-not-enter"}}))]
          (let [result (sheet/execute ctx parent-sheet-id {}
                                      :timeout-ms 15000
                                      :llm-call-budget 1)
                root-tick-id (:trace-id result)
                root-ledger (sheet/get-provider-call-reservations
                             ctx parent-sheet-id root-tick-id)
                reservation (first root-ledger)
                child-tick-id (:tick-id reservation)
                child-ledger (when child-tick-id
                               (sheet/get-provider-call-reservations
                                ctx child-sheet-id child-tick-id))
                events (h/read-all-events ctx)
                frontier (first
                          (filter #(and (= :rlm/researcher-frontier-claimed
                                           (:event/type %))
                                        (= child-tick-id (:tick-id %)))
                                  events))]
            (is (= 1 @provider-entries)
                "the descendant's second call cannot reset the ancestor capacity")
            (is (= 1 (count root-ledger)) (pr-str root-ledger))
            (is (= parent-sheet-id (:budget-sheet-id reservation)))
            (is (= root-tick-id (:budget-tick-id reservation)))
            (is (= child-sheet-id (:sheet-id reservation)))
            (is (not= root-tick-id child-tick-id))
            (is (empty? child-ledger)
                "the reservation belongs only to the ancestor-root ledger")
            (is (contains? (:event/tags frontier) [:tick root-tick-id])
                (pr-str frontier))
            (is (= :failure (:status result)) (pr-str result))
            (is (true? (:cancelled? result)) (pr-str result))))))))

(deftest sqlite-reopen-keeps-a-pre-return-reservation-spent
  (testing "normal automatic recovery cannot reuse capacity reserved before a crashed return"
    (let [db-file (str "/tmp/rr11-budget-recovery-" (random-uuid) ".db")
          connection {:type :sqlite :database-file db-file :maximum-pool-size 2}
          first-context (atom nil)
          reopened-context (atom nil)
          periodic-triggers (atom nil)
          provider-entries (atom 0)
          first-provider-entered (promise)
          release-crashed-provider (promise)
          tick-id (random-uuid)]
      (try
        (let [ctx (h/create-async-test-context
                   {:event-store-conn connection
                    :context {:llm-provider :test
                              :campaign-now-ms-fn (constantly 1000)}})
              _ (reset! first-context ctx)
              workflow
              (sheet/workflow "rr11-sqlite-reservation-recovery"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "finish after recovery"
                  :writes [:summary]
                  :max-iterations 2
                  :rlm {:checkpointed? true
                        :quantum {:max-iterations 1}
                        :timeouts {:provider-ms 5000
                                   :iteration-ms 7000
                                   :campaign-ms 15000}}))
              sheet-id (sheet/build-workflow! ctx workflow)
              nodes-ready? (h/settle-until!
                            #(seq (sheet/get-nodes-for-sheet ctx sheet-id))
                            :timeout-ms 5000)
              crashed-provider
              (fn [& _]
                (swap! provider-entries inc)
                (deliver first-provider-entered true)
                (deref release-crashed-provider 5000 nil)
                (throw (ex-info "simulated process crash after reservation" {})))]
          ;; The crash stub is installed on THIS thread and removed on this
          ;; thread, only after the crashed run's future has returned. A
          ;; `with-redefs` inside the future restored the root binding from
          ;; the future's thread whenever it finished — which on a slow runner
          ;; was AFTER the second `with-redefs` below had captured the stub as
          ;; the value to restore, leaving the crash stub as the permanent root
          ;; binding of `llm/predict` for every later namespace in the JVM
          ;; (CI: 26 failures in deterministic-value-storage-e2e-test).
          (with-redefs [llm/predict crashed-provider]
            (let [original-run (future
                                 (sheet/execute ctx sheet-id {}
                                                :tick-id tick-id
                                                :timeout-ms 12000
                                                :llm-call-budget 1))]
              (is nodes-ready?)
              (is (true? (deref first-provider-entered 5000 false)))
              (is (= 1 (count (sheet/get-provider-call-reservations
                               ctx sheet-id tick-id))))
              (h/stop-async-context ctx)
              (reset! first-context nil)
              (todo/clear-llm-count! tick-id)
              (deliver release-crashed-provider true)
              ;; The run's own outcome is irrelevant here (its context was
              ;; stopped underneath it, so it may return a result or throw a
              ;; closed-pool exception); what matters is that it has RETURNED
              ;; before the stub is removed.
              (is (not= ::still-running
                        (try (deref original-run 20000 ::still-running)
                             (catch java.util.concurrent.ExecutionException e
                               (or (ex-cause e) e))))
                  "the crashed run returns before the crash stub is removed")))

          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! provider-entries inc)
                          {:outputs {:code "(final! {:summary \"unsafe\"})"}})]
            (let [reopened (h/create-async-test-context
                            {:event-store-conn connection
                             :context {:llm-provider :test
                                       :campaign-now-ms-fn (constantly 1000)}})
                  _ (reset! reopened-context reopened)
                  triggers
                  (periodic/start-periodic-triggers!
                   {:append-fn #(es/append (:event-store reopened) %)
                    :tenant-ids-fn
                    #(set (keys (es/tenants (:event-store reopened))))})]
              (reset! periodic-triggers triggers)
              (is (h/settle-until!
                   #(seq (into []
                               (es/read (:event-store reopened)
                                        {:tenant-id (:tenant-id reopened)
                                         :types #{:sheet/tick-cancelled}
                                         :tags #{[:tick tick-id]}})))
                   :timeout-ms 7000)
                  "the reopened processors recover and durably cancel without an explicit resume call")
              (let [ledger (sheet/get-provider-call-reservations
                            reopened sheet-id tick-id)
                    cancellations
                    (into [] (es/read (:event-store reopened)
                                      {:tenant-id (:tenant-id reopened)
                                       :types #{:sheet/tick-cancelled}
                                       :tags #{[:tick tick-id]}}))]
                (is (= 1 @provider-entries)
                    "the pre-return reservation prevents a recovered provider entry")
                (is (= 1 (count ledger)) (pr-str ledger))
                (is (= 1 (count cancellations))
                    "automatic recovery produces the established cancellation fact once")))))
        (finally
          (deliver release-crashed-provider true)
          (when @periodic-triggers
            (periodic/stop-periodic-triggers! @periodic-triggers))
          (when @reopened-context
            (h/stop-async-context @reopened-context))
          (when @first-context
            (h/stop-async-context @first-context))
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest yielded-quanta-do-not-readd-cumulative-campaign-usage
  (testing "large and one-iteration quanta expose the same once-counted usage everywhere"
    (letfn [(run-campaign [quantum-max]
              (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
                (let [calls (atom 0)
                      workflow
                      (sheet/workflow (str "rr11-usage-" quantum-max)
                        (sheet/blackboard {:summary :string})
                        (sheet/repl-researcher "researcher"
                          :instruction "save then finish"
                          :writes [:summary]
                          :max-iterations 3
                          :rlm {:checkpointed? true
                                :quantum {:max-iterations quantum-max}
                                :timeouts {:provider-ms 1000
                                           :iteration-ms 3000
                                           :campaign-ms 10000}}))
                      sheet-id (sheet/build-workflow! ctx workflow)
                      researcher-id
                      (:id (first (filter #(= "researcher" (:name %))
                                          (sheet/get-nodes-for-sheet ctx sheet-id))))]
                  (with-redefs [llm/predict
                                (fn [& _]
                                  (case (swap! calls inc)
                                    1 {:outputs {:code "(store! :memo \"kept\")"}
                                       :usage {:prompt_tokens 2
                                               :completion_tokens 1
                                               :total_tokens 3}}
                                    2 {:outputs
                                       {:code
                                        "(final! {:summary (get-var :memo)})"}
                                       :usage {:prompt_tokens 2
                                               :completion_tokens 1
                                               :total_tokens 3}}))]
                    (let [result (sheet/execute ctx sheet-id {}
                                                :timeout-ms 15000
                                                :llm-call-budget 10)
                          events (h/read-tick-events ctx (:trace-id result))
                          completion
                          (first (filter #(and (= :sheet/node-execution-completed
                                                  (:event/type %))
                                               (= researcher-id (:node-id %)))
                                         events))
                          records (filterv #(and (= :rlm/researcher-iteration-recorded
                                                    (:event/type %))
                                                 (= researcher-id (:node-id %)))
                                           events)]
                      {:result result
                       :completion completion
                       :records records}))))) ]
      (let [large (run-campaign 3)
            sliced (run-campaign 1)
            total #(get-in % [:result :usage :total-tokens])
            by-node-total
            #(reduce + 0 (map :total-tokens
                              (vals (get-in % [:result :usage :by-node]))))
            completion-total #(get-in % [:completion :usage :total-tokens])]
        (is (= 6 (total large)))
        (is (= (total large) (total sliced))
            (pr-str {:large (:result large) :sliced (:result sliced)}))
        (is (= 6 (completion-total large) (completion-total sliced)))
        (is (= 6 (by-node-total large) (by-node-total sliced)))
        (is (= 2 (count (:records large)) (count (:records sliced))))))))

(deftest durable-budget-cache-hydrates-reconciles-and-cleans-up
  (testing "the hot cache mirrors durable reservations but never decides admission"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entries (atom [])
            root-tick-id (random-uuid)
            workflow
            (sheet/workflow "rr11-durable-cache-agreement"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "save then finish"
                :writes [:summary]
                :max-iterations 7
                :rlm {:checkpointed? true
                      :quantum {:max-iterations 3}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (let [entry (swap! provider-entries conj
                                           (get @(budget-cache-atom)
                                                root-tick-id))
                              ordinal (count entry)]
                          (case ordinal
                            1 (do
                                ;; Simulate eviction immediately after a warm
                                ;; append; the next attempt must rehydrate.
                                (swap! (budget-cache-atom)
                                       dissoc
                                       root-tick-id)
                                {:outputs {:code "(store! :one 1)"}})
                            2 (do
                                ;; A duplicate/stale conflict in an older
                                ;; runtime could leave a non-numeric cache
                                ;; residue. It must be treated as a miss.
                                (swap! (budget-cache-atom)
                                       assoc
                                       root-tick-id
                                       nil)
                                {:outputs {:code "(store! :two 2)"}})
                            3 (do
                                ;; Stale-high may not deny valid capacity.
                                (swap! (budget-cache-atom)
                                       assoc
                                       root-tick-id
                                       999)
                                {:outputs {:code "(store! :three 3)"}})
                            4 {:outputs {:code "(store! :four 4)"}}
                            5 (do
                                ;; The next attempt is over durable capacity;
                                ;; stale-low cache state must not admit it.
                                (swap! (budget-cache-atom)
                                       assoc
                                       root-tick-id
                                       0)
                                {:outputs {:code "(store! :five 5)"}}))))]
          (let [result (sheet/execute ctx sheet-id {}
                                      :tick-id root-tick-id
                                      :timeout-ms 15000
                                      :llm-call-budget 5)
                root (:trace-id result)
                ledger (sheet/get-provider-call-reservations ctx sheet-id root)]
            (is (= :failure (:status result)) (pr-str result))
            (is (true? (:cancelled? result)) (pr-str result))
            (is (= [1 2 3 4 5] @provider-entries)
                "cold hydration and the warm increment agree at provider entry")
            (is (= 5 (count ledger))
                "the stale-low cache cannot authorize an exhausted sixth attempt")
            (is (nil? (get @(budget-cache-atom) root))
                "terminal cleanup removes the hot root entry")))))))

(deftest durable-cache-hydration-read-failure-fails-closed
  (testing "a reservation remains spent but its provider is not dispatched when hydration cannot read"
    (let [provider-entries (atom 0)]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :provider-call-reservation-read-fn
                        (fn [_]
                          (throw (ex-info "injected durable read failure" {})))}}]
        (let [workflow
              (sheet/workflow "rr11-cache-read-failure"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "must stop before provider"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 3000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx workflow)]
          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! provider-entries inc)
                          {:outputs {:code "(final! {:summary \"unsafe\"})"}})]
            (let [result (sheet/execute ctx sheet-id {}
                                        :timeout-ms 10000
                                        :llm-call-budget 2)
                  ledger (sheet/get-provider-call-reservations
                          ctx sheet-id (:trace-id result))]
              (is (not= :success (:status result)) (pr-str result))
              (is (zero? @provider-entries))
              (is (= 1 (count ledger))
                  "the append won before the read failed, so the slot stays spent"))))))))

(deftest concurrent-successful-hydration-cannot-overwrite-a-newer-cache-count
  (testing "a delayed count-N hydration cannot replace a concurrent count-N+1 update"
    (let [real-read (atom nil)
          hydration-reads (atom 0)
          release-slow-read (promise)
          child-provider-entries (atom 0)
          cache-at-slow-provider (atom nil)]
      (h/with-async-test-context
        [ctx {:context
              {:llm-provider :test
               :provider-call-reservation-read-fn
               (fn [query]
                 (let [rows (into [] (@real-read query))
                       read-number (swap! hydration-reads inc)]
                   (when (= 2 read-number)
                     (deref release-slow-read 5000 nil))
                   rows))}}]
        (reset! real-read #(es/read (:event-store ctx) %))
        (let [tree '[:sequence
                     [:parallel
                      [:llm {:instruction "left" :writes [:left]
                             :output-schemas {:left :string}}]
                      [:llm {:instruction "right" :writes [:right]
                             :output-schemas {:right :string}}]]
                     [:final {:keys [:left :right]}]]
              code (str "(emit-tree! (quote " (pr-str tree) "))")
              workflow
              (sheet/workflow "rr11-cache-concurrent-hydration"
                (sheet/blackboard {:left :string :right :string})
                (sheet/repl-researcher "researcher"
                  :instruction "emit parallel calls"
                  :writes [:left :right]
                  :max-iterations 1
                  :rlm {:checkpointed? true
                        :recursive? false
                        :timeouts {:provider-ms 3000
                                   :iteration-ms 8000
                                   :campaign-ms 15000}}))
              sheet-id (sheet/build-workflow! ctx workflow)]
          (with-redefs [llm/predict
                        (fn [_provider module _inputs _options]
                          (let [output-names (set (map :name (:outputs module)))
                                output-name (cond
                                              (contains? output-names :code) :code
                                              (contains? output-names :left) :left
                                              (contains? output-names :right) :right)]
                            (if (= :code output-name)
                              (do
                                ;; Force both children down the cold path.
                                (reset! (budget-cache-atom) {})
                                {:outputs {:code code}})
                              (let [entry (swap! child-provider-entries inc)]
                                (when (= 1 entry)
                                  (deliver release-slow-read true))
                                (when (= 2 entry)
                                  (reset! cache-at-slow-provider
                                          (-> @(budget-cache-atom) vals first)))
                                {:outputs {output-name (name output-name)}}))))]
            (let [result (sheet/execute ctx sheet-id {}
                                        :timeout-ms 15000
                                        :llm-call-budget 3)
                  ledger (sheet/get-provider-call-reservations
                          ctx sheet-id (:trace-id result))]
              (is (= :success (:status result)) (pr-str result))
              (is (= 2 @child-provider-entries))
              (is (= 3 (count ledger)))
              (is (= (count ledger) @cache-at-slow-provider)
                  "the delayed hydration may not overwrite the newer count"))))))))

(deftest checkpointed-generated-child-without-a-budget-keeps-legacy-tick-tags
  (testing "campaign identity crosses Phase 2 without inventing an RR11 budget root"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entries (atom [])
            child-tree
            '[:sequence
              [:llm {:instruction "finish"
                     :writes [:summary]
                     :output-schemas {:summary :string}}]
              [:final {:keys [:summary]}]]
            code (str "(emit-tree! (quote " (pr-str child-tree) "))")
            workflow
            (sheet/workflow "rr11-checkpointed-no-budget-child"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "emit one child"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 3000
                                 :iteration-ms 8000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [llm/predict
                      (fn [_provider module _inputs _options]
                        (let [outputs (set (map :name (:outputs module)))]
                          (if (contains? outputs :code)
                            (do
                              (swap! provider-entries conj :code)
                              {:outputs {:code code}})
                            (do
                              (swap! provider-entries conj :summary)
                              {:outputs {:summary "legacy-child"}}))))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                events (h/read-all-events ctx)
                root-tick-id (:trace-id result)
                child-node-starts
                (filterv #(and (= :sheet/node-execution-started
                                  (:event/type %))
                               (not= root-tick-id (:tick-id %)))
                         events)
                reservations
                (filterv #(= :sheet/provider-call-reserved (:event/type %))
                         events)]
            (is (= :success (:status result)) (pr-str result))
            (is (= "legacy-child" (get-in result [:outputs :summary])))
            (is (= [:code :summary] @provider-entries))
            (is (seq child-node-starts) (pr-str events))
            (is (empty? reservations) (pr-str reservations))
            (is (not-any? #(contains? (:event/tags %)
                                      [:tick root-tick-id])
                          child-node-starts)
                (pr-str child-node-starts))))))))

(deftest non-checkpointed-budgeted-researcher-retains-compatibility-events-and-result
  (testing "non-checkpointed outer and inline calls use the legacy budget path only"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            workflow
            (sheet/workflow "rr11-non-checkpointed-compatibility"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "call inline then finish"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? false
                      :recursive? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx workflow)]
        (with-redefs [llm/predict
                      (fn [_provider module _inputs _options]
                        (if (= 1 (swap! calls inc))
                          {:outputs
                           {:code
                            "(do (llm \"inline\" :instruction \"second\" :writes [:seed]) (final! {:summary \"legacy\"}))"}}
                          {:outputs {:seed "ok"}}))]
          (let [result (sheet/execute ctx sheet-id {}
                                      :timeout-ms 10000
                                      :llm-call-budget 2)
                _ (h/settle-until!
                   #(some #{:sheet/execution-traced}
                          (map :event/type
                               (h/read-tick-events ctx (:trace-id result))))
                   :timeout-ms 3000)
                event-types (mapv :event/type
                                  (h/read-tick-events ctx (:trace-id result)))
                normalized-result
                (-> result
                    (dissoc :duration-ms :trace-id :node-trace)
                    (update-in [:outputs :iterations]
                               #(mapv (fn [iteration]
                                        (dissoc iteration :provider-latency-ms))
                                      %)))]
            (is (= :success (:status result)) (pr-str result))
            (is (= "legacy" (get-in result [:outputs :summary])))
            (is (= 2 @calls))
            (is (= [:sheet/tree-tick-started
                    :sheet/node-execution-started
                    :rlm/researcher-iterations
                    :sheet/execution-value-written
                    :sheet/execution-value-written
                    :sheet/node-execution-completed
                    :sheet/tree-tick-completed
                    :sheet/execution-traced]
                   event-types)
                (pr-str event-types))
            (is (= {:generated-tree-raw nil
                    :status :success
                    :outputs
                    {:summary "legacy"
                     :iterations
                     [{:result-profile {:type :map :length 1}
                       :vars-updated []
                       :vars-created [:seed]
                       :reasoning nil
                       :stdout-profile {:type :string
                                        :length 0
                                        :word-count 1
                                        :line-count 1}
                       :error-class nil
                       :provider-usage nil
                       :result "\"FINAL_ANSWER: {:summary \\\"legacy\\\"}\""
                       :code
                       "(do (llm \"inline\" :instruction \"second\" :writes [:seed]) (final! {:summary \"legacy\"}))"
                       :error nil
                       :stdout ""}]}
                    :terminal-reason :success
                    :consumed-ticks 1
                    :error nil
                    :configured-max-ticks 10}
                   normalized-result)
                (pr-str normalized-result))
            (is (not-any? #{:sheet/provider-call-reserved
                            :rlm/researcher-frontier-claimed
                            :rlm/researcher-effect-claimed}
                          event-types)
                (pr-str event-types))))))))
