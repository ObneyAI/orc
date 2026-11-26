(ns ai.obney.orc.orc-service.late-emitted-model-authority-test
  "An actual sequence emits a never-started descendant after parent reclaim.
   Researcher and semantic bodies are staged; the prefix runs through the real
   public code executor. No provider/model-quality claim is made."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.emitted-model-authority-test :as original]
            [ai.obney.orc.orc-service.retained-prime-model-plan-test :as prime]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.core.logic :as service-logic]
            [ai.obney.orc.orc-service.core.read-models :as service-read-models]))

(defn gated-prefix [{:keys [probe-effects]}]
  (let [{:keys [entered release]} @probe-effects]
    (deliver entered true)
    (when-not (= true (deref release 5000 :timeout))
      (throw (ex-info "Owned authority-test prefix was not released" {})))
    {:prefix true}))

(deftest first-descendant-created-after-current-parent-reclaim-binds-current-authority
  (original/staged-parent! 3
    (fn [ctx parent primary _ parent-staged]
      (let [entered (promise) release (promise) staged (atom [])
            logical (str "late-first-descendant/" (random-uuid))
            child (java.util.UUID/nameUUIDFromBytes (.getBytes logical "UTF-8"))
            _ (reset! (:probe-effects ctx) {:entered entered :release release})
            _ (prime/frontier! ctx parent 1)
            _ (original/claim! ctx parent logical 1)
            sheet (orc/build-workflow! ctx
                    (orc/workflow (str "late-original-descendant-" (random-uuid))
                      (orc/blackboard {:prefix [:boolean {:description "Actual prefix release."}]
                                       :value [:string {:description "Semantic child output."}]})
                      (orc/sequence "ordered"
                        (orc/code "prefix" :fn "ai.obney.orc.orc-service.late-emitted-model-authority-test/gated-prefix" :reads [] :writes [:prefix])
                        (orc/llm "semantic" :instruction "Return the requested value." :reads [:prefix] :writes [:value]))))
            actual processors/execute-leaf-node]
        (try
          (with-redefs [processors/execute-leaf-node
                        (fn [context]
                          (let [event (:event context)
                                node (orc/get-node context (:sheet-id event) (:node-id event))]
                            (if (= :ai (:executor node))
                              (swap! staged conj context)
                              (actual context))))]
            (h/run-and-apply! ctx (prime/command :sheet/tick-tree
              {:sheet-id sheet :tick-id child :parent-tick-id (:tick-id parent) :inputs {}
               :options {:timeout-ms 30000 :llm-call-budget 3 :checkpointed-campaign? true
                         :researcher-campaign-sheet-id (:sheet-id parent)
                         :researcher-campaign-tick-id (:tick-id parent)
                         :researcher-campaign-node-id (:node-id parent)
                         :researcher-campaign-exec-context {} :researcher-iteration-index 0
                         :researcher-ownership-epoch 1}}))
            (is (= true (deref entered 3000 :timeout)))
            (is (empty? @staged) "The semantic child has never started")
            (let [current-parent (original/recover-parent! ctx parent logical 2 parent-staged)]
              (deliver release true)
              (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
              (let [start (:event (first @staged))
                    node (:node-id start)
                    owner {:sheet-id sheet :tick-id child :node-id node :node-start-id (:event/id start)
                           :exec-context {} :role :subcall :logical-action-id (pr-str [child node {}])}
                    _ (let [campaign (service-read-models/researcher-campaign ctx child)
                            facts (processors/read-facts ctx owner campaign)]
                        (doseq [incomplete [(dissoc campaign :ownership-epoch)
                                            (assoc campaign :ownership-epoch 0)]]
                          (is (= :refused (:status (service-logic/assess owner incomplete facts)))
                              "Incomplete old snapshot ownership cannot authorize new work")))
                    result (original/authorize! ctx owner)
                    bindings (filterv #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))]
                (is (nil? (:resumed-from-event-id start)))
                (is (nil? (:cognitect.anomalies/category result)) (pr-str result))
                (is (= 1 (count bindings)))
                (is (= (:event/id start) (:node-start-id (first bindings))))
                (is (= (:node-start-id current-parent) (:parent-node-start-id (first bindings))))
                (is (= 2 (:ownership-epoch (first bindings))))
                (is (= logical (:generated-action-id (first bindings))))
                (is (not (contains? (first bindings) :configuration-id))))))
          (finally (deliver release true)))))))

(deftest old-unresumed-start-cannot-borrow-a-later-parent-claim
  (original/staged-child!
    (fn [ctx owner parent logical _ parent-staged _]
      (original/recover-parent! ctx parent logical 2 parent-staged)
      (let [result (original/authorize! ctx owner)]
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (= :emitted-resumed-start-required (:reason result)))
        (is (empty? (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))))))))

(defn with-late-configured-child! [f]
  (original/staged-parent! 3
    (fn [ctx parent primary backup parent-staged]
      (let [entered (promise) release (promise) staged (atom [])
            logical (str "late-configured-descendant/" (random-uuid))
            child (java.util.UUID/nameUUIDFromBytes (.getBytes logical "UTF-8"))
            _ (reset! (:probe-effects ctx) {:entered entered :release release})
            _ (prime/frontier! ctx parent 1)
            _ (original/claim! ctx parent logical 1)
            authored {:model "fixture/original" :fallback-models [{:provider backup :model "fixture/backup"}]}
            plan (update (prime/profile-plan primary "fixture/original") :candidates conj
                         (first (:candidates (prime/profile-plan backup "fixture/backup"))))
            sheet (orc/build-workflow! ctx
                    (orc/workflow (str "late-configured-child-" (random-uuid))
                      (orc/blackboard {:prefix [:boolean {:description "Actual prefix release."}]
                                       :value [:string {:description "Semantic child output."}]})
                      (orc/sequence "ordered"
                        (orc/code "prefix" :fn "ai.obney.orc.orc-service.late-emitted-model-authority-test/gated-prefix" :reads [] :writes [:prefix])
                        (orc/llm "semantic" :instruction "Return the requested value." :model "fixture/original"
                          :fallback-models (:fallback-models authored) :reads [:prefix] :writes [:value]))))
            actual processors/execute-leaf-node]
        (try
          (with-redefs [processors/execute-leaf-node
                        (fn [context]
                          (let [event (:event context)
                                node (orc/get-node context (:sheet-id event) (:node-id event))]
                            (if (= :ai (:executor node)) (swap! staged conj context) (actual context))))]
            (h/run-and-apply! ctx (prime/command :sheet/tick-tree
              {:sheet-id sheet :tick-id child :parent-tick-id (:tick-id parent) :inputs {}
               :options {:timeout-ms 30000 :llm-call-budget 3 :checkpointed-campaign? true
                         :researcher-campaign-sheet-id (:sheet-id parent) :researcher-campaign-tick-id (:tick-id parent)
                         :researcher-campaign-node-id (:node-id parent) :researcher-campaign-exec-context {}
                         :researcher-iteration-index 0 :researcher-ownership-epoch 1}}))
            (is (= true (deref entered 3000 :timeout)))
            (is (empty? @staged))
            (let [current-parent (original/recover-parent! ctx parent logical 2 parent-staged)]
              (deliver release true)
              (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
              (let [start (:event (first @staged)) node (:node-id start)
                    owner {:sheet-id sheet :tick-id child :node-id node :node-start-id (:event/id start)
                           :exec-context {} :role :subcall :logical-action-id (pr-str [child node {}])}]
                (is (nil? (:resumed-from-event-id start)))
                (f ctx owner current-parent logical authored plan staged parent-staged))))
          (finally (deliver release true)))))))

(deftest first-configured-descendant-retains-plan-before-binding-without-authorizing-dispatch
  (with-late-configured-child!
    (fn [ctx owner parent logical authored plan _ _]
      (let [prepared (processors/prepare-emitted-call ctx owner authored (constantly plan))
            owner (assoc owner :configuration-id (:configuration-id prepared))
            cb (:callbacks prepared)
            request (original/started-request prepared)
            retained (first (filter #(= :sheet/model-plan-retained (:event/type %)) (original/events ctx)))]
        (is (seq (:configuration-id owner)))
        (is (= plan (:plan retained)))
        (is (nil? (:cognitect.anomalies/category ((:claim! cb) 0 nil))))
        (is (= :cognitect.anomalies/conflict
               (:cognitect.anomalies/category ((:record-request! cb) request)))
            "Plan retention cannot authorize an actual request start")
        (is (= :cognitect.anomalies/conflict
               (:cognitect.anomalies/category (original/reserve! ctx (original/reservation owner parent 2 0)))))
        (is (empty? (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))))
        (is (empty? (filter #(= :sheet/model-request-recorded (:event/type %)) (original/events ctx))))
        (let [result (original/authorize! ctx owner)
              bindings (filterv #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))]
          (is (nil? (:cognitect.anomalies/category result)) (pr-str result))
          (is (= 1 (count bindings)))
          (is (= (:node-start-id owner) (:node-start-id (first bindings))))
          (is (= (:node-start-id parent) (:parent-node-start-id (first bindings))))
          (is (= 2 (:ownership-epoch (first bindings))))
          (is (= logical (:generated-action-id (first bindings))))
          (is (= (:configuration-id owner) (:configuration-id (first bindings))))
          (is (nil? (:cognitect.anomalies/category ((:record-request! cb) request))))
          (is (nil? (:cognitect.anomalies/category (original/reserve! ctx (original/reservation owner parent 2 0)))))
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category (original/authorize! ctx (assoc owner :configuration-id "other-plan"))))))))))

(deftest provisional-retention-cannot-replace-a-different-retained-configuration
  (with-late-configured-child!
    (fn [ctx owner _ _ authored plan _ _]
      (let [prepared (processors/prepare-emitted-call ctx owner authored (constantly plan))
            retained (first (filter #(= :sheet/model-plan-retained (:event/type %)) (original/events ctx)))
            different (assoc-in plan [:candidates 0 :model] "fixture/conflicting")
            result (h/run-and-apply! ctx (prime/command :sheet/retain-model-plan
                      (assoc owner :configuration-id (service-logic/retained-configuration-id different)
                                   :plan-scope-id (:plan-scope-id retained) :plan different)))]
        (is (seq (:configuration-id prepared)))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (= [(:configuration-id prepared)]
               (mapv :configuration-id (filter #(= :sheet/model-plan-retained (:event/type %)) (original/events ctx)))))
        (is (empty? (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))))))))

(deftest provisional-retention-rechecks-parent-and-child-ownership-in-append-window
  (doseq [movement [:parent-claim :parent-start :child-start]]
    (with-late-configured-child!
      (fn [ctx owner parent logical authored plan child-staged parent-staged]
        (let [actual-append es/append fired (atom false)
              result
              (with-redefs [es/append
                            (fn [store options]
                              (when (and (some #(= :sheet/model-plan-retained (:event/type %)) (:events options))
                                         (compare-and-set! fired false true))
                                (case movement
                                  :parent-claim (prime/frontier! ctx parent 3)
                                  :parent-start (original/recover-parent! ctx parent logical 3 parent-staged)
                                  :child-start (original/resumed-start! ctx owner nil child-staged)))
                              (actual-append store options))]
                (try
                  (processors/prepare-emitted-call ctx owner authored (constantly plan))
                  :unexpected-success
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))]
          (is @fired)
          (is (= :model-plan-ownership-lost result))
          (is (empty? (filter #(= :sheet/model-plan-retained (:event/type %)) (original/events ctx)))))))))

(defn with-first-start-before-parent-claim! [ordering configured? f]
  (original/staged-parent! 3
    (fn [ctx parent primary backup parent-staged]
      (let [entered (promise) release (promise) staged (atom [])
            logical (str "child-first-parent-recovery/" (random-uuid))
            child (java.util.UUID/nameUUIDFromBytes (.getBytes logical "UTF-8"))
            deadline (+ (System/currentTimeMillis) 10000)
            _ (reset! (:probe-effects ctx) {:entered entered :release release})
            _ (prime/frontier! ctx parent 1)
            _ (original/claim! ctx parent logical 1)
            authored {:model "fixture/original" :fallback-models [{:provider backup :model "fixture/backup"}]}
            plan (update (prime/profile-plan primary "fixture/original") :candidates conj
                         (first (:candidates (prime/profile-plan backup "fixture/backup"))))
            sheet (orc/build-workflow! ctx
                    (orc/workflow (str "child-first-native-sequence-" (random-uuid))
                      (orc/blackboard {:prefix [:boolean {:description "Actual prefix release."}]
                                       :value [:string {:description "Semantic output."}]})
                      (orc/sequence "ordered"
                        (orc/code "prefix" :fn "ai.obney.orc.orc-service.late-emitted-model-authority-test/gated-prefix" :reads [] :writes [:prefix])
                        (if configured?
                          (orc/llm "semantic" :instruction "Return a value." :model "fixture/original"
                            :fallback-models (:fallback-models authored) :reads [:prefix] :writes [:value])
                          (orc/llm "semantic" :instruction "Return a value." :model "fixture/original" :reads [:prefix] :writes [:value])))))
            actual processors/execute-leaf-node]
        (try
          (with-redefs [processors/execute-leaf-node
                        (fn [context]
                          (let [event (:event context) node (orc/get-node context (:sheet-id event) (:node-id event))]
                            (if (= :ai (:executor node)) (swap! staged conj context) (actual context))))]
            (h/run-and-apply! ctx (prime/command :sheet/tick-tree
              {:sheet-id sheet :tick-id child :parent-tick-id (:tick-id parent) :inputs {}
               :options {:timeout-ms 10000 :execution-deadline-ms deadline :llm-call-budget 3 :checkpointed-campaign? true
                         :researcher-campaign-sheet-id (:sheet-id parent) :researcher-campaign-tick-id (:tick-id parent)
                         :researcher-campaign-node-id (:node-id parent) :researcher-campaign-exec-context {}
                         :researcher-iteration-index 0 :researcher-ownership-epoch 1}}))
            (is (= true (deref entered 3000 :timeout)))
            (let [current (original/resumed-start! ctx parent 2 parent-staged)]
              (when (= :before-claim ordering) (prime/frontier! ctx current 2))
              (deliver release true)
              (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 3000))
              (let [start (:event (first @staged)) node (:node-id start)
                    owner {:sheet-id sheet :tick-id child :node-id node :node-start-id (:event/id start)
                           :exec-context {} :role :subcall :logical-action-id (pr-str [child node {}])}]
                (is (nil? (:resumed-from-event-id start)))
                (f ctx owner current logical authored plan configured? ordering deadline parent-staged))))
          (finally (deliver release true)))))))

(deftest late-first-start-waits-for-parent-frontier-and-generated-claim
  (doseq [ordering [:before-frontier :before-claim] configured? [false true]]
    (with-first-start-before-parent-claim! ordering configured?
      (fn [ctx owner parent logical authored plan configured? ordering deadline _]
        (let [campaign (service-read-models/researcher-campaign ctx (:tick-id owner))
              before (service-logic/assess owner campaign (processors/read-facts ctx owner campaign))
              prepared (when configured?
                         (try {:value (processors/prepare-emitted-call ctx owner authored (constantly plan))}
                              (catch clojure.lang.ExceptionInfo e {:error (:reason (ex-data e))})))
              owner (cond-> owner configured? (assoc :configuration-id (get-in prepared [:value :configuration-id])))
              sleeping (promise) work (atom nil)]
          (is (= :pending (:status before)) (str ordering " configured=" configured? " " before))
          (is (nil? (:error prepared)) (pr-str prepared))
          (when-not (:error prepared)
            (try
              (reset! work
                (future
                  (try
                    (processors/ensure!
                     (assoc ctx :orc/model-authority-sleep!
                            (fn [ms] (deliver sleeping true) (Thread/sleep ms))) owner deadline)
                    (catch clojure.lang.ExceptionInfo e {:error (:reason (ex-data e))}))))
              (is (= true (deref sleeping 500 :not-pending)))
              (is (empty? (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))))
              (is (empty? (filter #(= :sheet/provider-call-reserved (:event/type %)) (original/events ctx))))
              (is (empty? (filter #(= :sheet/model-request-recorded (:event/type %)) (original/events ctx))))
              (when (= :before-frontier ordering) (prime/frontier! ctx parent 2))
              (is (= :pending (:status (service-logic/assess owner campaign (processors/read-facts ctx owner campaign)))))
              (original/claim! ctx parent logical 2)
              (let [bound (deref @work 3000 {:error :timeout})
                    bindings (filterv #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))]
                (is (= 2 (:ownership-epoch bound)) (pr-str bound))
                (is (= (:node-start-id parent) (:parent-node-start-id bound)))
                (is (= (:node-start-id owner) (:node-start-id bound)))
                (is (= 1 (count bindings)))
                (is (= (:generated-claim-id bound)
                       (:event/id (last (filter #(and (= :rlm/researcher-effect-claimed (:event/type %))
                                                      (= 2 (:ownership-epoch %)) (= :generated-child (:kind %)))
                                               (original/events ctx))))))
                (is (= deadline (get-in (orc/get-tick-execution-context ctx (:tick-id owner)) [:options :execution-deadline-ms])))
                (is (= bound (processors/ensure! ctx owner deadline)))
                (is (= 1 (count (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))))))
              (finally (when @work (future-cancel @work))))))))))

(deftest pending-first-start-cannot-borrow-a-later-parent-start-or-frontier
  (doseq [movement [:parent-start :frontier] configured? [false true]]
    (with-first-start-before-parent-claim! :before-claim configured?
      (fn [ctx owner parent logical authored plan configured? _ deadline parent-staged]
        (let [prepared (when configured? (processors/prepare-emitted-call ctx owner authored (constantly plan)))
              owner (cond-> owner configured? (assoc :configuration-id (:configuration-id prepared)))
              sleeping (promise)
              work (future
                     (try
                       (processors/ensure!
                        (assoc ctx :orc/model-authority-sleep!
                               (fn [ms] (deliver sleeping true) (Thread/sleep ms))) owner deadline)
                       (catch clojure.lang.ExceptionInfo e {:error (:reason (ex-data e))})))]
          (try
            (is (= true (deref sleeping 500 :not-pending)))
            (case movement
              :parent-start (original/recover-parent! ctx parent logical 3 parent-staged)
              :frontier (do (prime/frontier! ctx parent 3) (original/claim! ctx parent logical 3)))
            (let [result (deref work 3000 {:error :timeout})]
              (is (contains? #{:emitted-resumed-start-required :emitted-authority-superseded} (:error result))
                  (pr-str result)))
            (is (empty? (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))))
            (is (empty? (filter #(= :sheet/provider-call-reserved (:event/type %)) (original/events ctx))))
            (is (empty? (filter #(= :sheet/model-request-recorded (:event/type %)) (original/events ctx))))
            (finally (future-cancel work))))))))

(deftest pending-configured-first-start-still-requires-its-exact-retained-plan
  (with-first-start-before-parent-claim! :before-claim true
    (fn [ctx owner parent logical authored plan _ _ deadline _]
      (let [_ (processors/prepare-emitted-call ctx owner authored (constantly plan))
            owner (assoc owner :configuration-id "unrelated-retained-plan")
            sleeping (promise)
            work (future
                   (try
                     (processors/ensure!
                      (assoc ctx :orc/model-authority-sleep!
                             (fn [ms] (deliver sleeping true) (Thread/sleep ms))) owner deadline)
                     (catch clojure.lang.ExceptionInfo e {:error (:reason (ex-data e))})))]
        (try
          (is (= true (deref sleeping 500 :not-pending)))
          (original/claim! ctx parent logical 2)
          (is (= :emitted-configuration-mismatch (:error (deref work 3000 {:error :timeout}))))
          (is (empty? (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (original/events ctx))))
          (finally (future-cancel work)))))))
