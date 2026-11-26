(ns ai.obney.orc.orc-service.retained-ordinary-model-plan-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.inherited-profile-fallback-repro-test]
            [hato.client :as http]
            [litellm.router :as router]
            [cognitect.anomalies :as anom])
  (:import [java.nio.file Files] [java.time Instant]))

(def proofs (atom []))
(defn command [name data]
  (merge {:command/name name :command/id (random-uuid) :command/timestamp (time/now)} data))

(defn workflow [backup]
  (orc/workflow (str "retained-ordinary-" (random-uuid))
    (orc/blackboard {:source :string :answer :string :accepted :boolean})
    (orc/sequence "task"
      (orc/code "prefix" :fn "ai.obney.orc.orc-service.inherited-profile-fallback-repro-test/prefix!"
        :reads [] :writes [:source])
      (orc/llm "produce" :instruction "Copy the source and report the assessment."
        :reads [:source] :writes [:answer :accepted]
        :fallback-models [{:provider backup :model "fixture/backup"}]
        :options {:max-retries 0 :max-tokens 256}))))

(defn- legacy-recovery-case [mode]
  (let [terminal? (= mode :terminal)
        ambiguous? (= mode :ambiguous)
        dir (.toFile (Files/createTempDirectory "orc-legacy-plan-" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
        tenant (random-uuid) tick (random-uuid) effects (atom 0) staged (atom []) target (atom nil)
        primary (keyword (str "legacy-primary-" (random-uuid)))
        backup (keyword (str "legacy-backup-" (random-uuid)))
        legacy-candidates [{:provider primary :model "fixture/primary" :options {:max-retries 0 :max-tokens 256}}
                           {:provider backup :model "fixture/backup" :options {:max-retries 0 :max-tokens 256}}]
        config-id (journal/configuration-id legacy-candidates :temporary-transport)
        alternate-candidates (assoc-in legacy-candidates [0 :options :max-tokens] 257)
        expected-configs (cond-> #{config-id}
                           ambiguous? (conj (journal/configuration-id alternate-candidates :temporary-transport)))
        base-context {:tenant-id tenant :auth-claims {} :llm-provider primary :probe-effects effects}
        saved (atom nil) real-handler processors/execute-leaf-node]
    (llm/register-provider! primary {:provider :openrouter :model "fixture/primary"
                                     :output-mode :function-calling :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (llm/register-provider! backup {:provider :openrouter :model "fixture/backup"
                                    :config {:api-key "SYNTHETIC-NOT-SENT"}})
    ;; This constructs historical-format facts through commands, not by bare
    ;; appends or a direct executor call. Only the initial producer dispatch is
    ;; held; prefix execution and the resumed dispatch use real processors.
    (with-redefs [processors/execute-leaf-node
                  (fn [ctx]
                    (if (= @target (get-in ctx [:event :node-id]))
                      (swap! staged conj ctx)
                      (real-handler ctx)))]
      (h/with-async-test-context [ctx {:event-store-conn conn :context base-context}]
        (let [sheet (orc/build-workflow! ctx (workflow backup))
              producer (:id (first (filter #(= "produce" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
          (reset! target producer)
          (h/run-and-apply! ctx (command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {}}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (let [event (:event (first @staged))
                owner {:sheet-id sheet :tick-id tick :node-id producer :node-start-id (:event/id event)
                       :exec-context {} :role :node :logical-action-id (pr-str [tick producer {}])}
                callbacks (journal/callbacks ctx owner legacy-candidates :temporary-transport)]
            (is (nil? (::anom/category ((:claim! callbacks) 0 (first legacy-candidates)))))
            (when (or terminal? ambiguous?)
              (is (nil? (::anom/category
                         ((:record! callbacks)
                          {:candidate-index 0 :provider primary :requested-model "fixture/primary"
                           :status :success :advanced? false
                           :outcome {:status :success :outputs {:answer "original-source" :accepted false}
                                     :model "fixture/primary"
                                     :model-fallback {:policy :temporary-transport :reason :selected
                                                      :candidate-index 0 :provider primary
                                                      :requested-model "fixture/primary" :attempts []}}})))))
            (when ambiguous?
              (let [other (journal/callbacks ctx owner alternate-candidates :temporary-transport)]
                (is (nil? (::anom/category ((:claim! other) 0 (first alternate-candidates)))))))
            (reset! saved {:sheet sheet :producer producer :owner owner
                           :before (orc/value-log-read-tick-events ctx tenant tick)})))))
    (router/unregister! primary)
    (router/unregister! backup)
    (let [http-calls (atom 0)]
      (with-redefs [http/post (fn [& _] (swap! http-calls inc)
                               (throw (ex-info "No HTTP is permitted during this recovery" {})))]
        (h/with-async-test-context
          [ctx {:event-store-conn conn
                :context (assoc base-context :orc/instance-id (random-uuid)
                                :orc/clock-fn #(.plusSeconds (Instant/now) 300))}]
          (let [resumed (orc/resume-in-progress! ctx)
                _ (is (some :resumed? resumed))
                _ (is (h/settle-until! #(= :completed (:status (orc/get-tick ctx tick))) :timeout-ms 10000))
                after (orc/value-log-read-tick-events ctx tenant tick)
                completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                              (= (:producer @saved) (:node-id %))) after))
                writes (orc/value-log-resolve-writes ctx tenant tick completion)
                history (filter #(contains? journal/event-types (:event/type %)) after)]
            (is (zero? @http-calls))
            (is (= 1 @effects))
            (is (= 0 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) after))))
            (is (= expected-configs (set (map :configuration-id history))))
            (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) after))
            (if terminal?
              (do (is (= :success (:status completion)))
                  (is (= {:answer "original-source" :accepted false} writes)))
              (do (is (= :failure (:status completion)))
                  (is (= :model-plan-incompatible (:failure-kind completion)))
                  (is (= (str "Model plan cannot be resumed ["
                              (if ambiguous? "historical-operation-ambiguous" "legacy-effective-profile-unretained") "]")
                         (:error completion)))
                  (is (empty? writes))))
            (swap! proofs conj {:kind (cond terminal? :legacy-terminal ambiguous? :legacy-ambiguous :else :legacy-incomplete)
                                :directory (.getAbsolutePath dir) :saved @saved :resumed resumed
                                :events after :completion completion :canonical-writes writes
                                :http-calls @http-calls :prefix-effects @effects})))))))

(deftest historical-terminal-outcome-replays-after-sqlite-reopen-with-provider-removed
  (legacy-recovery-case :terminal))

(deftest incomplete-legacy-profile-refuses-before-current-provider-resolution
  (legacy-recovery-case :incomplete))

(deftest ambiguous-legacy-histories-do-not-borrow-a-terminal-outcome
  (legacy-recovery-case :ambiguous))

(defn plan-fixture [provider model]
  {:version 1 :policy :temporary-transport
   :candidates [{:provider provider :model model :protocol :chat
                 :options {:output-mode :function-calling :force-tool-choice? false :max-retries 0}
                 :profile {:version "paired-function-v1-strict" :output-mode :function-calling
                           :force-tool-choice? false :strict-arguments? true}}]})

(defn plan-history [ctx tick]
  (:query/result (qp/process-query
                  (assoc ctx :query {:query/name :sheet/model-candidate-history
                                     :query/id (random-uuid) :query/timestamp (time/now) :tick-id tick}))))

(deftest competing-first-preparations-share-one-plan-and-refuse-stale-owners
  (let [staged (atom []) target (atom nil) effects (atom 0)
        real-handler processors/execute-leaf-node
        primary (keyword (str "cas-primary-" (random-uuid)))
        backup (keyword (str "cas-backup-" (random-uuid)))
        dir (.toFile (Files/createTempDirectory "orc-plan-cas-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [processors/execute-leaf-node
                  (fn [ctx] (if (= @target (get-in ctx [:event :node-id]))
                              (swap! staged conj ctx) (real-handler ctx)))]
      (h/with-async-test-context
        [ctx {:event-store-conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
              :context {:tenant-id (random-uuid) :auth-claims {} :llm-provider primary :probe-effects effects}}]
        (let [sheet (orc/build-workflow! ctx (workflow backup)) tick (random-uuid)
              producer (:id (first (filter #(= "produce" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
          (reset! target producer)
          (h/run-and-apply! ctx (command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {}}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (let [event (:event (first @staged))
                owner {:sheet-id sheet :tick-id tick :node-id producer :node-start-id (:event/id event)
                       :exec-context {} :role :node :logical-action-id (pr-str [tick producer {}])}
                authored {:options {:max-tokens 256} :fallback-models [{:provider backup :model "fixture/backup"}]}
                a (plan-fixture primary "fixture/default-a") b (plan-fixture primary "fixture/default-b")
                ready (java.util.concurrent.CountDownLatch. 2) release (promise)
                workers (mapv (fn [plan]
                                (future (journal/prepare-ordinary-call
                                         ctx owner authored
                                         #(do (.countDown ready) @release plan)))) [a b])]
            (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
            (deliver release true)
            (let [results (mapv #(deref % 10000 ::timeout) workers)
                  entries (plan-history ctx tick)
                  retained (filter #(= :sheet/model-plan-retained (:event/type %)) entries)
                  winner (first retained)]
              (is (not-any? #{::timeout} results))
              (is (= 1 (count retained)))
              (is (= 1 (count (set (map :configuration-id results)))))
              (is (every? #(= (:plan winner) (:plan %)) results))
              (is (contains? #{a b} (:plan winner)))
              (is (= (journal/retained-configuration-id (:plan winner)) (:configuration-id winner)))
              ;; Public resume changes ownership durably while the staged
              ;; fixture keeps both actual execution handlers from dispatching.
              (h/run-and-apply! ctx (command :sheet/resume-node-execution
                                            {:sheet-id sheet :tick-id tick :node-id producer
                                             :original-start-event-id (:node-start-id owner) :inputs {}}))
              (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
              (let [stale (h/run-and-apply! ctx
                            (command :sheet/retain-model-plan
                                     (assoc owner :configuration-id (journal/retained-configuration-id a)
                                            :plan-scope-id (journal/plan-scope-id owner authored) :plan a)))
                    claim ((get-in (first results) [:callbacks :claim!]) 0 (first (:candidates (:plan winner))))
                    other-tenant (h/run-and-apply! (assoc ctx :tenant-id (random-uuid))
                                   (command :sheet/retain-model-plan
                                            (assoc owner :configuration-id (journal/retained-configuration-id a)
                                                   :plan-scope-id (journal/plan-scope-id owner authored) :plan a)))]
                (is (= ::anom/conflict (::anom/category stale)))
                (is (= ::anom/conflict (::anom/category claim)))
                (is (= ::anom/conflict (::anom/category other-tenant))))
              (let [new-owner (assoc owner :node-start-id (get-in (last @staged) [:event :event/id]))
                    unsafe (assoc-in a [:candidates 0 :options :extra-body :headers :Authorization] "SYNTHETIC-MUST-NOT-PERSIST")
                    rejected (h/run-and-apply! ctx
                               (command :sheet/retain-model-plan
                                        (assoc new-owner :configuration-id (journal/retained-configuration-id unsafe)
                                               :plan-scope-id (journal/plan-scope-id owner authored) :plan unsafe)))]
                (is (= ::anom/incorrect (::anom/category rejected))))
              (let [after (plan-history ctx tick)]
                (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) after))))
                (is (not (.contains (pr-str after) "SYNTHETIC-MUST-NOT-PERSIST")))
                (is (not-any? #(= :sheet/model-candidate-claimed (:event/type %)) after))
                (swap! proofs conj {:kind :first-plan-cas :directory (.getAbsolutePath dir)
                                    :results (mapv #(dissoc % :callbacks) results)
                                    :history after :prefix-effects @effects})))
            (orc/cancel! ctx tick)))))))

(deftest mapped-ordinary-contexts-retain-independent-effective-plans
  (let [staged (atom []) primary (keyword (str "mapped-primary-" (random-uuid)))
        backup (keyword (str "mapped-backup-" (random-uuid)))
        dir (.toFile (Files/createTempDirectory "orc-plan-mapped-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [processors/execute-leaf-node #(swap! staged conj %)]
      (h/with-async-test-context
        [ctx {:event-store-conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
              :context {:tenant-id (random-uuid) :auth-claims {} :llm-provider primary}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "mapped-plans-" (random-uuid))
                        (orc/blackboard {:sources [:vector :string] :source :string :answer :boolean
                                         :answers [:vector [:map [:answer :boolean]]]})
                        (orc/map-each "sources" :from :sources :as :source :into :answers :parallel 2
                          (orc/llm "produce" :instruction "Assess the source." :reads [:source] :writes [:answer]
                            :fallback-models [{:provider backup :model "fixture/backup"}]
                            :options {:max-retries 0}))))
              tick (random-uuid)]
          (h/run-and-apply! ctx (command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {:sources ["first" "second"]}}))
          (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
          (let [owners (sort-by #(get (:exec-context %) :ai.obney.orc.orc-service.core.todo-processors/map-each-index)
                        (map (fn [{:keys [event]}]
                               (let [execution (value-log/exec-context (:inputs event))]
                                 {:sheet-id sheet :tick-id tick :node-id (:node-id event) :node-start-id (:event/id event)
                                  :exec-context execution :role :node
                                  :logical-action-id (pr-str [tick (:node-id event) execution])})) @staged))
                authored {:fallback-models [{:provider backup :model "fixture/backup"}] :options {:max-retries 0}}
                plans [(plan-fixture primary "fixture/mapped-a") (plan-fixture primary "fixture/mapped-b")]
                results (mapv (fn [owner plan] (journal/prepare-ordinary-call ctx owner authored (constantly plan))) owners plans)
                entries (filter #(= :sheet/model-plan-retained (:event/type %)) (plan-history ctx tick))]
            (is (= 2 (count entries)))
            (is (= 2 (count (set (map :plan-scope-id entries)))))
            (is (= 2 (count (set (map :exec-context entries)))))
            (is (= plans (mapv :plan results)))
            (doseq [[owner plan] (map vector owners plans)]
              (is (= plan (:plan (first (filter #(= (:exec-context owner) (:exec-context %)) entries))))))
            (is (not-any? #(= :sheet/model-candidate-claimed (:event/type %)) (plan-history ctx tick)))
            (swap! proofs conj {:kind :mapped-ordinary-plan-isolation :directory (.getAbsolutePath dir)
                                :owners owners :history entries :results (mapv #(dissoc % :callbacks) results)}))
          (orc/cancel! ctx tick))))))
