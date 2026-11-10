(ns ai.obney.orc.orc-service.retained-profile-drift-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.ordinary-model-plan :as plan]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.retained-ordinary-model-plan-test :as fixture]
            [hato.client :as http])
  (:import [java.nio.file Files] [java.time Instant]))

(def proof (atom nil))

(deftest resumed-legacy-plan-refuses-new-paired-provider-default-before-dispatch
  (let [dir (.toFile (Files/createTempDirectory "orc-retained-drift-" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
        tenant (random-uuid) tick (random-uuid) effects (atom 0) captured (atom []) target (atom nil)
        primary (keyword (str "drift-primary-" (random-uuid))) backup (keyword (str "drift-backup-" (random-uuid)))
        config {:provider :openrouter :config {:api-key "SYNTHETIC-NOT-SENT"}}
        context {:tenant-id tenant :auth-claims {} :llm-provider primary :probe-effects effects}
        saved (atom nil) real-handler processors/execute-leaf-node]
    (llm/register-provider! primary (assoc config :model "fixture/primary"))
    (llm/register-provider! backup (assoc config :model "fixture/backup"))
    (with-redefs [processors/execute-leaf-node
                  (fn [ctx] (if (= @target (get-in ctx [:event :node-id]))
                              (swap! captured conj ctx) (real-handler ctx)))]
      (h/with-async-test-context [ctx {:event-store-conn conn :context context}]
        (let [sheet (orc/build-workflow! ctx (fixture/workflow backup))
              producer (:id (first (filter #(= "produce" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
          (reset! target producer)
          (h/run-and-apply! ctx (fixture/command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {}}))
          (is (h/settle-until! #(= 1 (count @captured)) :timeout-ms 5000))
          (let [event (:event (first @captured))
                node (get-in (rm/get-tick-execution-context ctx tick) [:nodes-by-id producer])
                owner {:sheet-id sheet :tick-id tick :node-id producer :node-start-id (:event/id event)
                       :exec-context {} :role :node :logical-action-id (pr-str [tick producer {}])}
                module {:instructions "Copy the source."
                        :inputs [{:name :source :spec :string}]
                        :outputs [{:name :answer :spec :string} {:name :accepted :spec :boolean}]}
                prepared (journal/prepare-ordinary-call
                          ctx owner (select-keys node [:provider :model :options :fallback-models :fallback-policy])
                          #(plan/resolve-plan node primary module (:options node)))]
            (is (= ["legacy-marker-v1" "legacy-marker-v1"]
                   (mapv #(get-in % [:profile :version]) (get-in prepared [:plan :candidates]))))
            (reset! saved {:sheet sheet :producer producer :prepared (dissoc prepared :callbacks)})))))
    (llm/register-provider! primary (assoc config :model "fixture/new-primary" :output-mode :json-schema))
    (llm/register-provider! backup (assoc config :model "fixture/new-backup" :output-mode :json-schema))
    (let [requests (atom [])]
      (with-redefs [http/post
                    (fn [_ options]
                      (swap! requests conj (:body options))
                      (future {:status 200 :body {:id "must-not-be-called" :model "fixture/resolved"
                                                  :choices [{:finish_reason "stop"
                                                             :message {:role "assistant" :content "{\"answer\":\"original-source\",\"accepted\":false}"}}]}}))]
        (h/with-async-test-context
          [ctx {:event-store-conn conn :context (assoc context :orc/instance-id (random-uuid)
                                                      :orc/clock-fn #(.plusSeconds (Instant/now) 300))}]
          (let [resumed (orc/resume-in-progress! ctx)
                _ (is (some :resumed? resumed))
                _ (is (h/settle-until! #(= :completed (:status (orc/get-tick ctx tick))) :timeout-ms 10000))
                events (orc/value-log-read-tick-events ctx tenant tick)
                completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                                (= (:producer @saved) (:node-id %))) events))]
            (is (empty? @requests))
            (is (= 1 @effects))
            (is (= :failure (:status completion)))
            (is (= :model-plan-incompatible (:failure-kind completion)))
            (is (= "Model plan cannot be resumed [retained-profile-cannot-be-preserved]" (:error completion)))
            (is (not-any? #(contains? #{:sheet/provider-call-reserved :sheet/provider-attempt-started
                                       :sheet/model-request-recorded} (:event/type %)) events))
            (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) events))))
            (reset! proof {:directory (.getAbsolutePath dir) :saved @saved :resumed resumed
                           :events events :completion completion :transport-calls (count @requests)
                           :prefix-effects @effects})))))))
