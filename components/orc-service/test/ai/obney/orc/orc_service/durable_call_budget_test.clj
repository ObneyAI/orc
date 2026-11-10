(ns ai.obney.orc.orc-service.durable-call-budget-test
  "Generated from the RR-11 ProviderCallReservation obligations."
  (:require [clojure.java.io :as io]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.provider-call-reservations :as reservations]
            [ai.obney.orc.orc-service.core.todo-processors :as todo]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.budget-reservation-fixture :as barrier]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.time.interface :as time]
            [clojure.test :refer [deftest is testing]]))

(defn- an-hour-later-clock
  "Context map whose lease clock reads an hour after the crashed owner's last renewal."
  [m]
  (assoc m :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600)))

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
        node-start-id (random-uuid)
        campaign-node-id (random-uuid)
        candidate {:admission-kind :campaign
                   :node-start-id node-start-id
                   :exec-context {}
                   :budget-sheet-id budget-sheet-id
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
              :options {:llm-call-budget 2
                        :llm-budget-accounting-version 1}
              :execution-snapshot
              {:nodes-by-id {physical-node-id {:id physical-node-id}
                             campaign-node-id {:id campaign-node-id}}}}
        frontier {:event/type :rlm/researcher-frontier-claimed
                  :sheet-id budget-sheet-id
                  :tick-id budget-tick-id
                  :node-id campaign-node-id
                  :ownership-epoch 2}
        node-start {:event/id node-start-id
                    :inputs {}
                    :event/type :sheet/node-execution-started
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
    (is (= :incomplete-history
           (:decision
            (reservations/reservation-decision
             candidate
             [(update root :options dissoc :llm-budget-accounting-version)
              node-start frontier iteration-claim existing])))
        "a budget without complete-history evidence cannot admit a provider")
    (is (false? (accepts? candidate
                         [(update root :options dissoc :llm-budget-accounting-version)
                          node-start frontier iteration-claim existing])))
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
             ["wrong canonical node start" (assoc candidate :node-start-id (random-uuid))
              [root node-start frontier iteration-claim]]
             ["wrong execution context" (assoc candidate :exec-context
                                               {::todo/map-each-index 0})
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
              (is (some? provider-claim) "reservation belongs to an actual provider effect")
              (is (= (str (:logical-action-identity provider-claim) "/candidate/0")
                     (:logical-action-identity reservation))
                  "the physical reservation scopes the effect action to candidate zero")
              (is (= (reservations/invocation-identity
                      (:trace-id result)
                      (str (:logical-action-identity provider-claim) "/candidate/0")
                      (:ownership-epoch provider-claim)
                      0)
                     (:invocation-identity reservation)))
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
                owner-start
                (first (filter #(and (= :sheet/node-execution-started (:event/type %))
                                     (= (:node-start-id projected) (:event/id %)))
                               (h/read-all-events ctx)))
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
                                [:admission-kind :campaign-sheet-id :node-start-id :exec-context
                                 :budget-sheet-id :budget-tick-id
                                 :sheet-id :tick-id :node-id
                                 :campaign-tick-id :campaign-node-id
                                 :iteration-index :logical-action-identity
                                 :invocation-identity :provider-attempt-ordinal
                                 :ownership-epoch :reserved-at])
                   projected))
            (is (= :campaign (:admission-kind projected)))
            (is (= sheet-id (:campaign-sheet-id projected)))
            (is (some? owner-start) "projection identifies an actual canonical node start")
            (is (= [sheet-id budget-tick-id (:node-id projected)]
                   ((juxt :sheet-id :tick-id :node-id) owner-start)))
            (is (= {} (:exec-context projected)))
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

(defn- race-next-reservation! [ctx first-reservation]
  ;; Exercise the native command admission contract. These deliveries do not
  ;; impersonate HTTP dispatches; the actual original provider is held separately.
  (let [ordinal (inc (:provider-attempt-ordinal first-reservation))
        invocation (reservations/invocation-identity
                    (:budget-tick-id first-reservation)
                    (:logical-action-identity first-reservation)
                    (:ownership-epoch first-reservation)
                    ordinal)
        command (merge (select-keys first-reservation
                                   [:admission-kind :node-start-id :exec-context
                                    :budget-sheet-id :budget-tick-id
                                    :sheet-id :tick-id :node-id :campaign-sheet-id
                                    :campaign-tick-id :campaign-node-id
                                    :iteration-index :logical-action-identity
                                    :ownership-epoch])
                       {:command/name :sheet/reserve-provider-call
                        :provider-attempt-ordinal ordinal
                        :invocation-identity invocation
                        :reserved-at (str (time/now))})
        ready (java.util.concurrent.CountDownLatch. 2)
        release (promise)
        deliver! (fn []
                   (.countDown ready)
                   (when-not (deref release 5000 false)
                     (throw (ex-info "Command race barrier was not released" {})))
                   (h/run-command ctx (assoc command :command/id (random-uuid)
                                                    :command/timestamp (time/now))))
        first-delivery (future (deliver!))
        second-delivery (future (deliver!))]
    (try
      (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS)
          "both actual command deliveries are waiting before release")
      (deliver release true)
      {:invocation invocation
       :results [(deref first-delivery 10000 ::not-joined)
                 (deref second-delivery 10000 ::not-joined)]}
      (finally
        (deliver release true)
        (is (not= ::not-joined (deref first-delivery 10000 ::not-joined)))
        (is (not= ::not-joined (deref second-delivery 10000 ::not-joined)))))))

(defn- verify-reservation-race! [live-owner?]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "orc-reservation-race-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        transport (barrier/start-held-provider!)
        provider (keyword (str "reservation-race-" (random-uuid)))]
    (try
      (llm/register-provider! provider
        {:provider :openrouter :model "fixture/reservation-race"
         :output-mode :json-schema
         :config {:api-key "fixture" :api-base (:url transport) :timeout 20000}})
      (h/with-async-test-context
        [ctx {:event-store-conn {:type :sqlite
                                :database-file (str (io/file directory "store.sqlite"))
                                :maximum-pool-size 2}
              :context {:tenant-id (random-uuid) :llm-provider provider}}]
        (let [workflow
              (sheet/workflow (str "provider-reservation-race-" live-owner?)
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "Finish." :writes [:summary] :max-iterations 1
                  :options {:output-mode :json-schema :max-retries 0}
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 15000 :iteration-ms 18000
                                   :campaign-ms 25000}}))
              sheet-id (sheet/build-workflow! ctx workflow)
              tick-id (random-uuid)
              execution (future (sheet/execute ctx sheet-id {} :tick-id tick-id
                                              :timeout-ms 25000 :llm-call-budget 3))]
          (try
            (is (true? (deref (:entered transport) 10000 false))
                "a real HTTP request reaches the held provider")
            (when-not live-owner?
              (deliver (:release transport) true)
              (is (= :success (:status (deref execution 15000 {})))))
            (let [events-before (h/read-all-events ctx)
                  initial-ledger (sheet/get-provider-call-reservations ctx sheet-id tick-id)
                  first-reservation (first initial-ledger)
                  owner-start (first (filter #(= (:node-start-id first-reservation)
                                                 (:event/id %)) events-before))
                  race (race-next-reservation! ctx first-reservation)
                  categories (mapv :cognitect.anomalies/category (:results race))
                  ledger (sheet/get-provider-call-reservations ctx sheet-id tick-id)
                  proof {:live-owner? live-owner? :tenant (:tenant-id ctx)
                         :sheet sheet-id :tick tick-id :events-before events-before
                         :initial-ledger initial-ledger :race race :ledger ledger}]
              (spit (io/file directory "proof.edn") (pr-str proof))
              (println "RESERVATION-RACE-PROOF" (.getAbsolutePath directory))
              (is (= 1 (count initial-ledger)))
              (is (some? owner-start) "owner identity is from an actual canonical start")
              (is (= [sheet-id tick-id (:node-id first-reservation)]
                     ((juxt :sheet-id :tick-id :node-id) owner-start)))
              (is (= (not live-owner?)
                     (boolean (some #(and (= :sheet/tree-tick-completed (:event/type %))
                                          (= tick-id (:tick-id %))
                                          (= :success (:root-status %))) events-before)))
                  "the race runs in the declared live or completed owner state")
              (is (= (if live-owner? 1 2)
                     (count (filter #{:cognitect.anomalies/conflict} categories)))
                  (pr-str (:results race)))
              (is (= (if live-owner? 1 0) (count (remove :cognitect.anomalies/category
                                                       (:results race))))
                  "only a live-owner append winner receives admission")
              (is (= (if live-owner? 2 1) (count ledger)))
              (is (= (if live-owner? 1 0)
                     (count (filter #(= (:invocation race) (:invocation-identity %)) ledger)))))
            (finally
              (deliver (:release transport) true)
              (let [result (deref execution 30000 ::not-joined)]
                (is (not= ::not-joined result) "actual public execute body joins")
                (is (= :success (:status result)) (pr-str result)))))
          (is (= 1 (count @(:requests transport)))
              "native admission commands cause no fictional provider entries")
          (is (empty? @(:errors transport)))))
      (finally (barrier/stop! transport)))))

(deftest duplicate-deliveries-authorize-only-the-append-winner
  (testing "two native reservation deliveries race while the canonical owner is inside real HTTP"
    (verify-reservation-race! true)))

(deftest completed-owner-cannot-acquire-new-provider-reservation
  (testing "both duplicate deliveries after success are rejected without a new ledger row"
    (verify-reservation-race! false)))

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
              (sheet/blackboard {:summary :string
                                 :seed [:string {:description "Text produced by the inline specialist for subsequent workflow work."}]})
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
              (sheet/blackboard {:summary :string
                                 :seed [:string {:description "Text produced by the inline specialist for subsequent workflow work."}]})
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
                             :context (an-hour-later-clock
                                       {:llm-provider :test
                                        :campaign-now-ms-fn (constantly 1000)})})
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

(deftest durable-admission-ignores-process-local-budget-corruption
  (testing "corrupt, absent, nil, high and low cache values cannot change durable cap five"
    (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                              "orc-budget-cache-corruption-"
                              (make-array java.nio.file.attribute.FileAttribute 0)))
          root-tick-id (random-uuid)
          provider (keyword (str "budget-cache-corruption-" (random-uuid)))
          cache (budget-cache-atom)
          original-entry (find @cache root-tick-id)
          runtime (atom nil)
          entries (atom [])
          mutations (atom [])
          mutate! (fn [at operation value]
                    (swap! mutations conj {:at at :operation operation :value value})
                    (if (= :evict operation)
                      (swap! cache dissoc root-tick-id)
                      (swap! cache assoc root-tick-id value)))
          transport
          (barrier/start-capacity-provider!
           (fn [ordinal]
             (let [{:keys [ctx sheet-id]} @runtime]
               (swap! entries conj
                      {:ordinal ordinal
                       :cache-present? (contains? @cache root-tick-id)
                       :cache-value (get @cache root-tick-id)
                       :ledger (sheet/get-provider-call-reservations ctx sheet-id root-tick-id)
                       :events (h/read-all-events ctx)}))
             (case ordinal
               1 (mutate! 1 :evict nil)
               2 (mutate! 2 :nil nil)
               3 (mutate! 3 :stale-high 999)
               5 (mutate! 5 :stale-low 0)
               nil)))]
      (try
        (llm/register-provider! provider
          {:provider :openrouter :model "fixture/budget-cache-corruption"
           :output-mode :json-schema
           :config {:api-key "fixture" :api-base (:url transport) :timeout 30000}})
        (h/with-async-test-context
          [ctx {:event-store-conn {:type :sqlite
                                  :database-file (str (io/file directory "store.sqlite"))
                                  :maximum-pool-size 2}
                :context {:tenant-id (random-uuid) :llm-provider provider}}]
          (let [workflow
                (sheet/workflow "rr11-durable-cache-agreement"
                  (sheet/blackboard {:summary :string})
                  (sheet/repl-researcher "researcher"
                    :instruction "save then finish"
                    :writes [:summary]
                    :max-iterations 7
                    :options {:output-mode :json-schema :max-retries 0}
                    :rlm {:checkpointed? true
                          :quantum {:max-iterations 3}
                          :timeouts {:provider-ms 1000
                                     :iteration-ms 3000
                                     :campaign-ms 10000}}))
                sheet-id (sheet/build-workflow! ctx workflow)
                node-id (:id (first (filter #(= "researcher" (:name %))
                                           (sheet/get-nodes-for-sheet ctx sheet-id))))]
            (reset! runtime {:ctx ctx :sheet-id sheet-id})
            (mutate! :before-execute :corrupt "not-a-count")
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id root-tick-id
                                        :timeout-ms 15000
                                        :llm-call-budget 5)
                  ledger (sheet/get-provider-call-reservations ctx sheet-id root-tick-id)
                  events (h/read-all-events ctx)
                  records (sheet/get-researcher-iteration-records ctx sheet-id root-tick-id node-id)
                  root-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                  (= root-tick-id (:tick-id %))) events))
                  cancellations (filterv #(and (= :sheet/tick-cancelled (:event/type %))
                                                (= root-tick-id (:tick-id %))) events)
                  expected-codes ["(store! :one 1)" "(store! :two 2)" "(store! :three 3)"
                                  "(store! :four 4)" "(store! :five 5)"]
                  proof {:directory (str directory) :root root-tick-id :sheet sheet-id
                         :tenant (:tenant-id ctx) :node node-id :result result
                         :ledger ledger :events events :records records
                         :entries @entries :mutations @mutations
                         :requests @(:requests transport) :responses @(:responses transport)
                         :server-errors @(:errors transport)}]
              (spit (io/file directory "proof.edn") (pr-str proof))
              (println "CACHE-CORRUPTION-PROOF" (.getAbsolutePath directory))
              (is (= :failure (:status result)) (pr-str result))
              (is (true? (:cancelled? result)) (pr-str result))
              (is (= "LLM call budget exceeded: 5/5" (:error result)))
              (is (= 5 (count @(:requests transport))) "no sixth actual HTTP request")
              (is (empty? @(:errors transport)))
              (is (= [1 2 3 4 5] (mapv (comp count :ledger) @entries))
                  "every actual provider entry sees its already durable reservation")
              (is (= expected-codes (mapv :code @(:responses transport)))
                  "all five original store operations are returned through real HTTP")
              (is (= expected-codes (mapv :code (take 5 records)))
                  "the original five-store workflow is recorded, not replaced by a final response")
              (is (= [{:cache-present? true :cache-value "not-a-count"}
                      {:cache-present? false :cache-value nil}
                      {:cache-present? true :cache-value nil}
                      {:cache-present? true :cache-value 999}
                      {:cache-present? true :cache-value 999}]
                     (mapv #(select-keys % [:cache-present? :cache-value]) @entries)))
              (is (= [:corrupt :evict :nil :stale-high :stale-low]
                     (mapv :operation @mutations)))
              (is (= 0 (:value (last @mutations)))
                  "stale-low is installed before the denied sixth admission")
              (is (= 5 (count ledger)))
              (is (= 5 (count (set (map :invocation-identity ledger)))))
              (is (= [sheet-id root-tick-id] ((juxt :sheet-id :tick-id) root-start)))
              (is (= 5 (get-in root-start [:options :llm-call-budget])))
              (is (= 1 (get-in root-start [:options :llm-budget-accounting-version])))
              (is (= 1 (count cancellations)))
              (is (= "LLM call budget exceeded: 5/5" (:reason (first cancellations))))
              (doseq [{:keys [ledger events ordinal]} @entries]
                (let [raw (filterv #(= :sheet/provider-call-reserved (:event/type %)) events)
                      expected (->> raw
                                    (map #(select-keys %
                                             [:admission-kind :node-start-id :exec-context
                                              :candidate-index :node-attempt :campaign-sheet-id
                                              :budget-sheet-id :budget-tick-id :sheet-id :tick-id :node-id
                                              :campaign-tick-id :campaign-node-id :iteration-index
                                              :logical-action-identity :invocation-identity
                                              :provider-attempt-ordinal :ownership-epoch :reserved-at]))
                                    (sort-by (juxt :provider-attempt-ordinal :invocation-identity)) vec)]
                  (is (= ordinal (count raw)))
                  (is (= expected ledger) "complete public rows match canonical admission facts")
                  (doseq [row ledger]
                    (let [start (first (filter #(= (:node-start-id row) (:event/id %)) events))]
                      (is (= :campaign (:admission-kind row)))
                      (is (= [sheet-id root-tick-id]
                             ((juxt :budget-sheet-id :budget-tick-id) row)))
                      (is (= :sheet/node-execution-started (:event/type start)))
                      (is (= [sheet-id root-tick-id node-id]
                             ((juxt :sheet-id :tick-id :node-id) row)
                             ((juxt :sheet-id :tick-id :node-id) start)))
                      (is (= (select-keys (:inputs start)
                                         [::todo/map-each-index ::todo/map-each-parent ::todo/tick-iteration])
                             (:exec-context row)))
                      (is (= [sheet-id root-tick-id node-id]
                             ((juxt :campaign-sheet-id :campaign-tick-id :campaign-node-id) row)))))))
              (is (nil? (get @cache root-tick-id))
                  "terminal cleanup removes this root's hot entry")
              (is (not (contains? @cache root-tick-id))))))
        (finally
          (barrier/stop! transport)
          (if original-entry
            (swap! cache assoc root-tick-id (val original-entry))
            (swap! cache dissoc root-tick-id)))))))

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

(deftest non-checkpointed-budgeted-researcher-uses-durable-node-admission
  (testing "capped checkpoint opt-out reserves outer and inline calls without campaign claims"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            reservation-counts-at-entry (atom [])
            workflow
            (sheet/workflow "rr11-non-checkpointed-compatibility"
              (sheet/blackboard {:summary :string
                                 :seed [:string {:description "Text produced by the inline specialist for subsequent workflow work."}]})
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
                        (swap! reservation-counts-at-entry conj
                               (count (filter #(= :sheet/provider-call-reserved (:event/type %))
                                              (h/read-all-events ctx))))
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
                events (h/read-tick-events ctx (:trace-id result))
                event-types (mapv :event/type events)
                root-start (first (filter #(= :sheet/tree-tick-started (:event/type %)) events))
                node-start (first (filter #(= :sheet/node-execution-started (:event/type %)) events))
                owner-context (select-keys (:inputs node-start)
                                           [::todo/map-each-index ::todo/map-each-parent
                                            ::todo/tick-iteration])
                raw-reservations (filterv #(= :sheet/provider-call-reserved (:event/type %)) events)
                ledger (sheet/get-provider-call-reservations ctx sheet-id (:trace-id result))
                expected-ledger
                (mapv (fn [raw]
                        {:admission-kind :ordinary
                         :budget-sheet-id sheet-id :budget-tick-id (:trace-id result)
                         :sheet-id sheet-id :tick-id (:trace-id result)
                         :node-id (:node-id node-start) :node-start-id (:event/id node-start)
                         :exec-context owner-context :candidate-index 0 :node-attempt 1
                         :logical-action-identity (:logical-action-identity raw)
                         :invocation-identity (:invocation-identity raw)
                         :provider-attempt-ordinal 0 :reserved-at (:reserved-at raw)})
                      (sort-by (juxt :provider-attempt-ordinal :invocation-identity)
                               raw-reservations))
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
            (is (= [1 2] @reservation-counts-at-entry)
                "each actual provider entry sees its already durable reservation")
            (is (some? root-start))
            (is (some? node-start))
            (is (= [sheet-id (:trace-id result)]
                   ((juxt :sheet-id :tick-id) root-start)
                   ((juxt :sheet-id :tick-id) node-start)))
            (is (= 2 (get-in root-start [:options :llm-call-budget])))
            (is (= 1 (get-in root-start [:options :llm-budget-accounting-version])))
            (is (= [sheet-id (:trace-id result)]
                   ((juxt :llm-budget-root-sheet-id :llm-budget-root-tick-id)
                    (:options root-start))))
            (is (= {} owner-context))
            (is (= 2 (count raw-reservations) (count ledger)))
            (is (= expected-ledger ledger)
                "the complete public rows identify ordinary calls owned by the actual canonical start")
            (is (= 2 (count (set (map :logical-action-identity ledger)))))
            (is (= 2 (count (set (map :invocation-identity ledger)))))
            (is (every? #(and (string? (:logical-action-identity %))
                              (seq (:logical-action-identity %))
                              (= (pr-str [:ordinary (:trace-id result) (:trace-id result)
                                          (:event/id node-start) [nil nil nil]
                                          (:logical-action-identity %) 0 1 0])
                                 (:invocation-identity %))) ledger)
                "each physical identity binds the actual root, start, context, action, candidate and attempt")
            (is (= [:sheet/tree-tick-started
                    :sheet/node-execution-started
                    :sheet/provider-call-reserved
                    :sheet/provider-call-reserved
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
                     :seed nil
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
            (is (not-any? #{:rlm/researcher-frontier-claimed
                            :rlm/researcher-effect-claimed}
                          event-types)
                (pr-str event-types))))))))
