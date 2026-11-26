(ns ai.obney.orc.orc-service.emitted-unconfigured-authority-test
  "Primary-only recovery must not require an authored fallback or retained model plan."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.core.emitted-model-authority :as model-authority]
            [ai.obney.orc.orc-service.retained-emitted-model-plan-test :as fixture]
            [ai.obney.orc.orc-service.retained-prime-model-plan-test :as prime]
            [ai.obney.orc.orc-service.emitted-model-authority-test :as authority]))

(def proof (atom nil))
(def proofs (atom []))
(defn run-primary-only! [cap]
  (let [server (fixture/server! (fn [_ _] {:value "single-original-value"}))
        preflights (atom []) predictions (atom [])
        real-preflight llm/preflight-provider! real-predict llm/predict]
    (try
      (authority/staged-parent! cap
        (fn [ctx parent primary _ parent-staged]
          (llm/register-provider! primary
            {:provider :openrouter :model "fixture/single" :output-mode :json-schema
             :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url server) :timeout 10000}})
          (let [staged (atom []) logical (str "primary-only-generated/" (random-uuid))
                tick (java.util.UUID/nameUUIDFromBytes (.getBytes logical "UTF-8"))
                _ (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent 1))))
                _ (is (nil? (:cognitect.anomalies/category (authority/claim! ctx parent logical 1))))
                sheet (orc/build-workflow! ctx
                        (orc/workflow (str "primary-only-child-" (random-uuid))
                          (orc/blackboard {:value [:string {:description "Original primary-only child value."}]})
                          (orc/llm "single" :instruction "Return the original requested value."
                            :provider primary :model "fixture/single" :reads [] :writes [:value]
                            :options {:output-mode :json-schema :max-retries 0 :max-tokens 4096})))
                options {:timeout-ms 120000 :checkpointed-campaign? true
                         :researcher-campaign-sheet-id (:sheet-id parent) :researcher-campaign-tick-id (:tick-id parent)
                         :researcher-campaign-node-id (:node-id parent) :researcher-campaign-exec-context {}
                         :researcher-iteration-index 0 :researcher-ownership-epoch 1}
                actual-leaf processors/execute-leaf-node]
            (with-redefs [processors/execute-leaf-node #(swap! staged conj %)
                          llm/preflight-provider! (fn [provider module options]
                                                    (swap! preflights conj {:new-capability-present? (contains? options :fresh-model-authority!)})
                                                    (real-preflight provider module options))
                          llm/predict (fn [provider module inputs options]
                                        (swap! predictions conj {:provider provider :module module :inputs inputs :options options})
                                        (real-predict provider module inputs options))]
              (h/run-and-apply! ctx (prime/command :sheet/tick-tree
                {:sheet-id sheet :tick-id tick :parent-tick-id (:tick-id parent) :inputs {} :options options}))
              (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
              (let [owner {:sheet-id sheet :tick-id tick :node-id (get-in (first @staged) [:event :node-id])
                           :node-start-id (get-in (first @staged) [:event :event/id]) :exec-context {}}
                    current (authority/resumed-start! ctx owner nil staged)
                    parent2 (authority/recover-parent! ctx parent logical 2 parent-staged)
                    before (authority/events ctx)
                    root-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                    (= (:tick-id parent) (:tick-id %))) before))
                    work (actual-leaf (last @staged))]
                (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 10000))
                (when (instance? java.util.concurrent.Future work)
                  (is (not= :timeout (deref work 10000 :timeout))))
                (let [result (runtime/durable-terminal-result ctx tick)
                      events (authority/events ctx)
                      child-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %)) (= tick (:tick-id %))) events))]
                  (is (= :success (:status result)) (pr-str result))
                  (is (= {:value "single-original-value"} (:outputs result)))
                  (is (= ["fixture/single"] (mapv #(get-in % [:body :model]) @(:requests server))))
                  (is (= (if cap 1 0) (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
                  (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) events)
                      "Execution authority must not invent a configured-alternatives plan")
                  (is (seq @preflights))
                  (is (every? #(false? (:new-capability-present? %)) @preflights))
                  (is (= 1 (count @predictions)))
                  (is (not (contains? (:options (first @predictions)) :fresh-model-authority!)))
                  (is (not-any? #(str/includes? (:raw %) "fresh-model-authority") @(:requests server)))
                  (when cap
                    (let [sent (first @predictions)
                          spent (first (filter #(= :sheet/provider-call-reserved (:event/type %)) events))]
                    (is (= (:logical-action-identity spent)
                           (effects/provider-logical-action-identity
                             {:tick-id tick :node-id (:node-id current) :iteration-index 0
                              :provider (:provider sent) :model "fixture/single" :module (:module sent)
                              :inputs (:inputs sent) :options (:options sent)}))
                        "The existing physical action preimage contains the same sanitized model request, never the host capability")))
                  (is (= 1 (get-in child-start [:options :researcher-ownership-epoch])))
                  (if cap
                    (is (= 6 (get-in root-start [:options :llm-call-budget])))
                    (is (not (contains? (:options root-start) :llm-call-budget))))
                  (is (= 2 (count (filter #(= :sheet/tree-tick-started (:event/type %)) events))))
                  (reset! proof {:result result :events events :before before :current-child current :parent2 parent2
                                 :requests @(:requests server) :responses @(:responses server)
                                 :root-start root-start :child-start child-start
                                 :preflights @preflights :predictions @predictions :cap cap})
                  (swap! proofs conj @proof)))))))
      (finally (prime/stop-fixture! server)))))

(deftest primary-only-emitted-child-recovers-without-invented-model-plan
  (run-primary-only! 6))
(deftest uncapped-primary-only-emitted-child-recovers-without-invented-cap
  (run-primary-only! nil))

(deftest omitting-configuration-cannot-evade-an-existing-configured-operation
  (authority/staged-child! 3
    (fn [ctx owner parent logical staged parent-staged _]
      (let [current (authority/resumed-start! ctx owner nil staged)
            _ (authority/recover-parent! ctx parent logical 2 parent-staged)
            absent (dissoc current :configuration-id)
            refused (authority/authorize! ctx absent)]
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category refused)))
        (is (= :emitted-configuration-mismatch (:reason refused)))
        (is (not-any? #(= :sheet/emitted-model-work-authorized (:event/type %)) (authority/events ctx)))
        (is (nil? (:cognitect.anomalies/category (authority/authorize! ctx current))))))))

(deftest pending-authority-coordination-obeys-deadline-and-interruption
  (authority/staged-child!
    (fn [ctx owner _ _ staged _ _]
      (let [current (authority/resumed-start! ctx owner nil staged)
            now (atom 0)
            context (assoc ctx :orc/model-authority-now-ms #(deref now)
                               :orc/model-authority-sleep! #(swap! now + %))
            expired (try (model-authority/ensure! context current 50) nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))
            interrupted (try
                          (.interrupt (Thread/currentThread))
                          (model-authority/ensure! ctx current (+ (System/currentTimeMillis) 10000))
                          false
                          (catch InterruptedException _ true)
                          (finally (Thread/interrupted)))]
        (is (= :model-plan-incompatible (:failure-kind expired)))
        (is (= :emitted-authority-deadline (:reason expired)))
        (is (= 50 @now))
        (is interrupted)
        (is (not-any? #(contains? #{:sheet/model-candidate-recorded :sheet/provider-call-reserved
                                    :sheet/emitted-model-work-authorized} (:event/type %))
                      (authority/events ctx)))))))
