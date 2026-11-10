(ns ai.obney.orc.orc-service.reservation-owner-test
  "Public regression for BUDGET-5b's exact campaign owner seam."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.core.todo-processors :as todo]
            [ai.obney.orc.orc-service.core.provider-call-reservations :as reservations]
            [ai.obney.orc.orc-service.budget-reservation-fixture :as barrier]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(deftest campaign-reservation-cannot-omit-start-to-evade-context-ownership
  (testing "omitting the canonical start cannot authorize a different execution context"
    (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                              "orc-reservation-owner-"
                              (make-array java.nio.file.attribute.FileAttribute 0)))
          transport (barrier/start-held-provider!)
          provider (keyword (str "reservation-owner-" (random-uuid)))
          proof (atom {:directory (str directory)})]
      (try
        (llm/register-provider! provider
          {:provider :openrouter :model "fixture/reservation-owner"
           :output-mode :json-schema
           :config {:api-key "fixture" :api-base (:url transport) :timeout 20000}})
        (h/with-async-test-context
          [ctx {:event-store-conn {:type :sqlite
                                  :database-file (str (io/file directory "store.sqlite"))
                                  :maximum-pool-size 2}
                :context {:tenant-id (random-uuid) :llm-provider provider}}]
          (let [workflow
                (sheet/workflow "budget5b-exact-campaign-owner"
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
                  "the actual owner reaches and remains inside real HTTP")
              (let [events-before (h/read-all-events ctx)
                    initial-ledger (sheet/get-provider-call-reservations ctx sheet-id tick-id)
                    owner (first initial-ledger)
                    _ (when-not owner
                        (throw (ex-info "Held runtime did not create an actual reservation" {})))
                    node-start (first (filter #(= (:node-start-id owner) (:event/id %)) events-before))
                    root-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                   (= tick-id (:tick-id %))) events-before))
                    wrong-context (assoc (:exec-context owner) ::todo/map-each-index 0)
                    ordinal (inc (:provider-attempt-ordinal owner))
                    invocation (reservations/invocation-identity
                                (:budget-tick-id owner) (:logical-action-identity owner)
                                (:ownership-epoch owner) ordinal)
                    with-start
                    (merge (select-keys owner
                                        [:admission-kind :budget-sheet-id :budget-tick-id
                                         :sheet-id :tick-id :node-id :node-start-id
                                         :campaign-sheet-id :campaign-tick-id :campaign-node-id
                                         :iteration-index :logical-action-identity :ownership-epoch])
                           {:exec-context wrong-context
                            :provider-attempt-ordinal ordinal :invocation-identity invocation
                            :reserved-at (str (time/now))})
                    without-start (dissoc with-start :node-start-id)
                    submit! (fn [body]
                              (h/run-command ctx
                                (assoc body :command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :command/name :sheet/reserve-provider-call)))
                    supplied-result (submit! with-start)
                    supplied-ledger (sheet/get-provider-call-reservations ctx sheet-id tick-id)
                    omitted-result (submit! without-start)
                    omitted-ledger (sheet/get-provider-call-reservations ctx sheet-id tick-id)
                    events-after (h/read-all-events ctx)]
                (swap! proof merge
                       {:tenant (:tenant-id ctx) :sheet sheet-id :tick tick-id :owner owner
                        :node-start node-start :root-start root-start
                        :events-before events-before :initial-ledger initial-ledger
                        :with-start with-start :without-start without-start
                        :supplied-result supplied-result :supplied-ledger supplied-ledger
                        :omitted-result omitted-result :omitted-ledger omitted-ledger
                        :events-after events-after})
                (is (= 1 (count initial-ledger)))
                (is (= :campaign (:admission-kind owner)))
                (is (= :sheet/node-execution-started (:event/type node-start)))
                (is (= [sheet-id tick-id (:node-id owner)]
                       ((juxt :sheet-id :tick-id :node-id) node-start)))
                (is (= (:exec-context owner)
                       (select-keys (:inputs node-start)
                                    [::todo/map-each-index ::todo/map-each-parent ::todo/tick-iteration])))
                (is (not= wrong-context (:exec-context owner)))
                (is (m/validate schemas/ordinary-provider-reservation
                                (assoc with-start :admission-kind :ordinary
                                                  :candidate-index 0 :node-attempt 1))
                    "wrong ownership still has a schema-valid engine context")
                (is (m/validate schemas/campaign-provider-reservation with-start))
                (is (m/validate schemas/campaign-provider-reservation without-start)
                    "the regression reaches CAS rather than depending on schema rejection")
                (is (= [sheet-id tick-id]
                       ((juxt :budget-sheet-id :budget-tick-id) owner)
                       ((juxt :sheet-id :tick-id) root-start)))
                (is (= 1 (get-in root-start [:options :llm-budget-accounting-version])))
                (is (= 3 (get-in root-start [:options :llm-call-budget])))
                (is (some #(and (= :rlm/researcher-frontier-claimed (:event/type %))
                                (= (:node-id owner) (:node-id %))
                                (= (:ownership-epoch owner) (:ownership-epoch %))) events-before))
                (is (some #(and (= :rlm/researcher-effect-claimed (:event/type %))
                                (= (:node-id owner) (:node-id %))
                                (= (:iteration-index owner) (:iteration-index %))
                                (= (:ownership-epoch owner) (:ownership-epoch %))) events-before))
                (is (not-any? #(contains? #{:sheet/tree-tick-completed
                                           :sheet/node-execution-completed}
                                         (:event/type %)) events-before)
                    "both commands run before the actual owner completes")
                (is (= :cognitect.anomalies/conflict
                       (:cognitect.anomalies/category supplied-result))
                    "an actual start cannot authorize a different context")
                (is (= initial-ledger supplied-ledger))
                (is (= :cognitect.anomalies/conflict
                       (:cognitect.anomalies/category omitted-result))
                    "removing only the start ID cannot evade exact context ownership")
                (is (= initial-ledger omitted-ledger)
                    "no invalid physical reservation is appended")
                (is (not-any? #(= invocation (:invocation-identity %)) omitted-ledger))
                (is (= 1 (count @(:requests transport))) "native commands cause no extra HTTP")
                (is (not-any? #(= :sheet/tick-cancelled (:event/type %)) events-after)
                    "invalid ownership is not reported as proven exhaustion"))
              (finally
                (deliver (:release transport) true)
                (let [result (deref execution 30000 ::not-joined)]
                  (swap! proof assoc :final-result result)
                  (is (not= ::not-joined result) "actual public execute body joins")
                  (is (= :success (:status result)) (pr-str result))
                  (is (= "done" (get-in result [:outputs :summary]))))))))
        (finally
          (barrier/stop! transport)
          (swap! proof assoc :requests @(:requests transport)
                            :server-errors @(:errors transport) :http-executor-joined? true)
          (spit (io/file directory "proof.edn") (pr-str @proof))
          (println "RESERVATION-OWNER-PROOF" (.getAbsolutePath directory))
          (is (empty? @(:errors transport))))))))
