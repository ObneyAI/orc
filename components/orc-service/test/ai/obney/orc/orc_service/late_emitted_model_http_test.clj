(ns ai.obney.orc.orc-service.late-emitted-model-http-test
  "Real native semantic dispatch over loopback HTTP; parent researcher starts are
   staged to control delivery order. No hosted model or quality claim."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.emitted-model-authority-test :as original]
            [ai.obney.orc.orc-service.retained-prime-model-plan-test :as prime]))

(def proofs (atom []))
(defn prefix! [{:keys [probe-effects]}]
  (let [{:keys [entered release count]} @probe-effects]
    (swap! count inc)
    (deliver entered true)
    (when-not (= true (deref release 10000 :timeout))
      (throw (ex-info "Owned native prefix was not released" {})))
    {:prefix true}))
(defn response [body]
  {:id "controlled-late-response" :model "fixture/late-resolved"
   :choices [{:index 0 :finish_reason "tool_calls"
              :message {:role "assistant" :content nil
                        :tool_calls [{:id "controlled-late-call" :type "function"
                          :function {:name "submit_response"
                                     :arguments (json/generate-string {:value "late-native-value"})}}]}}]
   :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})
(defn type-events [ctx t] (filterv #(= t (:event/type %)) (original/events ctx)))

(defn native-case! [ordering]
  (original/staged-parent! 3
    (fn [ctx parent primary backup staged]
      (let [fixture (prime/server! response)
            entered (promise) release (promise) prefix-count (atom 0)
            logical (str "late-native/" (random-uuid))
            child (java.util.UUID/nameUUIDFromBytes (.getBytes logical "UTF-8"))
            deadline (+ (System/currentTimeMillis) 30000)
            profile {:provider :openrouter :output-mode :function-calling
                     :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 5000}}]
        (try
          (llm/register-provider! primary (assoc profile :model "fixture/late"))
          (llm/register-provider! backup (assoc profile :model "fixture/late-backup"))
          (reset! (:probe-effects ctx) {:entered entered :release release :count prefix-count})
          (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent 1))))
          (is (nil? (:cognitect.anomalies/category (original/claim! ctx parent logical 1))))
          (let [sheet (orc/build-workflow! ctx
                        (orc/workflow (str "late-native-order-" ordering "-" (random-uuid))
                          (orc/blackboard {:prefix [:boolean {:description "Actual completed prefix."}]
                                           :value [:string {:description "Exact native semantic value."}]})
                          (orc/sequence "ordered"
                            (orc/code "prefix" :fn "ai.obney.orc.orc-service.late-emitted-model-http-test/prefix!"
                              :reads [] :writes [:prefix])
                            (orc/llm "semantic" :instruction "Return the exact requested value."
                              :model "fixture/late"
                              :fallback-models [{:provider backup :model "fixture/late-backup"}]
                              :options {:output-mode :function-calling :max-retries 0}
                              :reads [:prefix] :writes [:value]))))
                frozen-options {:timeout-ms 30000 :execution-deadline-ms deadline :llm-call-budget 3
                                :checkpointed-campaign? true
                                :researcher-campaign-sheet-id (:sheet-id parent)
                                :researcher-campaign-tick-id (:tick-id parent)
                                :researcher-campaign-node-id (:node-id parent)
                                :researcher-campaign-exec-context {} :researcher-iteration-index 0
                                :researcher-ownership-epoch 1}]
            (is (nil? (:cognitect.anomalies/category
                       (h/run-and-apply! ctx (prime/command :sheet/tick-tree
                         {:sheet-id sheet :tick-id child :parent-tick-id (:tick-id parent)
                          :inputs {} :options frozen-options})))))
            (is (= true (deref entered 5000 :timeout)))
            (let [current (original/resumed-start! ctx parent 2 staged)]
              (when (= :before-claim ordering) (prime/frontier! ctx current 2))
              (when (= :parent-ready ordering)
                (prime/frontier! ctx current 2)
                (original/claim! ctx current logical 2))
              (deliver release true)
              (is (h/settle-until! #(seq (type-events ctx :sheet/model-plan-retained)) :timeout-ms 8000))
              (let [retained (first (type-events ctx :sheet/model-plan-retained))
                    before-events (original/events ctx)
                    child-snapshot (orc/get-tick-execution-context ctx child)]
                (is (some? retained))
                (is (= ["fixture/late" "fixture/late-backup"] (mapv :model (get-in retained [:plan :candidates]))))
                (when (not= :parent-ready ordering)
                  (is (empty? @(:requests fixture)))
                  (is (empty? (type-events ctx :sheet/provider-call-reserved)))
                  (is (empty? (type-events ctx :sheet/model-request-recorded)))
                  (is (empty? (type-events ctx :sheet/emitted-model-work-authorized)))
                  (when (= :before-frontier ordering) (prime/frontier! ctx current 2))
                  (is (empty? @(:requests fixture)))
                  (original/claim! ctx current logical 2))
                (is (h/settle-until! #(some (fn [e] (= child (:tick-id e)))
                                          (type-events ctx :sheet/tree-tick-completed)) :timeout-ms 12000))
                (let [events (original/events ctx)
                      bindings (type-events ctx :sheet/emitted-model-work-authorized)
                      binding (first bindings)
                      reservations (type-events ctx :sheet/provider-call-reserved)
                      semantic (first (filter #(= "semantic" (:name %)) (orc/get-nodes-for-sheet ctx sheet)))
                      completions (filterv #(and (= :sheet/node-execution-completed (:event/type %))
                                                (= child (:tick-id %)) (= (:id semantic) (:node-id %))) events)
                      completion (first completions)
                      start (first (filter #(and (= :sheet/node-execution-started (:event/type %))
                                                 (= child (:tick-id %)) (= (:id semantic) (:node-id %))) events))
                      writes (when completion (orc/value-log-resolve-writes ctx (:tenant-id ctx) child completion))]
                  (is (empty? @(:errors fixture)))
                  (is (= 1 (count @(:requests fixture))))
                  (is (= "fixture/late" (get-in @(:requests fixture) [0 :body :model])))
                  (is (= :success (:status completion)) (pr-str completion))
                  (is (= {:value "late-native-value"} writes))
                  (is (= 1 (count bindings)))
                  (is (= 1 (count reservations)))
                  (is (= 1 (count completions)))
                  (is (nil? (:resumed-from-event-id start)))
                  (is (= (:event/id start) (:node-start-id binding)))
                  (is (= (:node-start-id current) (:parent-node-start-id binding)))
                  (is (= 2 (:ownership-epoch binding)))
                  (is (= (:configuration-id retained) (:configuration-id binding)))
                  (is (= [retained] (type-events ctx :sheet/model-plan-retained)))
                  (is (= 1 @prefix-count))
                  (is (= frozen-options (select-keys (:options child-snapshot) (keys frozen-options))))
                  (is (= (:options child-snapshot) (:options (orc/get-tick-execution-context ctx child))))
                  (let [root-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                       (= (:tick-id parent) (:tick-id %))) events))]
                    (is (= 3 (get-in root-start [:options :llm-call-budget]))))
                  (swap! proofs conj {:ordering ordering :tenant (:tenant-id ctx)
                    :sheet sheet :child child :parent current :frozen-options frozen-options
                    :before-events before-events :events events :requests @(:requests fixture)
                    :responses @(:responses fixture) :writes writes :prefix-count @prefix-count
                    :snapshot-options (:options child-snapshot)})))))
          (finally (deliver release true) (prime/stop-fixture! fixture)))))))

(deftest real-native-dispatch-waits-for-parent-frontier-then-claim
  (native-case! :before-frontier))
(deftest real-native-dispatch-waits-for-parent-claim
  (native-case! :before-claim))
(deftest real-native-first-start-after-ready-parent-dispatches-once
  (native-case! :parent-ready))
