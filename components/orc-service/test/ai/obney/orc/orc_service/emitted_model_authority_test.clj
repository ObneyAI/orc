(ns ai.obney.orc.orc-service.emitted-model-authority-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.retained-prime-model-plan-test :as prime]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.core.provider-call-reservations :as reservations]))

(def proofs (atom []))
(defn events [ctx] (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))
(defn claim! [ctx parent logical epoch]
  (h/run-and-apply! ctx (prime/command :sheet/claim-researcher-effect
    (merge (select-keys parent [:sheet-id :tick-id :node-id])
      {:ownership-epoch epoch :iteration-index 0 :kind :generated-child
       :logical-action-identity logical :attempt-identity (effects/attempt-identity logical epoch 0)
       :attempt-ordinal 0 :claimed-at (str (java.time.Instant/now))}))))
(defn resumed-start! [ctx owner epoch staged]
  (let [before (count @staged)]
    (is (nil? (:cognitect.anomalies/category
      (h/run-and-apply! ctx (prime/command :sheet/resume-node-execution
        (cond-> (merge (select-keys owner [:sheet-id :tick-id :node-id])
                  {:original-start-event-id (:node-start-id owner) :inputs {}})
          epoch (assoc :researcher-ownership-epoch epoch)))))))
    (is (h/settle-until! #(= (inc before) (count @staged)) :timeout-ms 5000))
    (cond-> (assoc owner :node-start-id (get-in (last @staged) [:event :event/id]))
      epoch (assoc :ownership-epoch epoch))))

(defn staged-parent! [cap f]
  (let [staged (atom []) primary (keyword (str "authority-primary-" (random-uuid)))
        backup (keyword (str "authority-backup-" (random-uuid)))]
    (with-redefs [processors/execute-repl-researcher-node #(swap! staged conj %)]
      (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str (prime/fresh-directory) "/events.sqlite")
                                                        :maximum-pool-size 2}
                                      :context {:tenant-id (random-uuid) :auth-claims {} :llm-provider primary
                                                :probe-effects (atom 0)}}]
        (let [sheet (orc/build-workflow! ctx (prime/workflow backup {})) tick (random-uuid)
              node (:id (first (filter #(= "research" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
          (h/run-and-apply! ctx (prime/command :sheet/tick-tree
            {:sheet-id sheet :tick-id tick :inputs {} :options (cond-> {:timeout-ms 120000} cap (assoc :llm-call-budget cap))}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (f ctx {:sheet-id sheet :tick-id tick :node-id node :role :prime :exec-context {}
                  :node-start-id (get-in (first @staged) [:event :event/id]) :ownership-epoch 1}
             primary backup staged))))))

(defn staged-child!
  ([f] (staged-child! nil f))
  ([cap f]
  (staged-parent! cap
    (fn [ctx parent primary _ parent-staged]
      (let [child-staged (atom []) logical (str "emitted-authority/" (random-uuid))
            child-tick (java.util.UUID/nameUUIDFromBytes (.getBytes logical "UTF-8"))
            _ (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent 1))))
            _ (is (nil? (:cognitect.anomalies/category (claim! ctx parent logical 1))))
            sheet (orc/build-workflow! ctx
                    (orc/workflow (str "emitted-authority-child-" (random-uuid))
                      (orc/blackboard {:value [:string {:description "Exact recovered child output."}]})
                      (orc/llm "child" :instruction "Return the requested value." :reads [] :writes [:value])))
            options {:researcher-campaign-sheet-id (:sheet-id parent) :researcher-campaign-tick-id (:tick-id parent)
                     :researcher-campaign-node-id (:node-id parent) :researcher-campaign-exec-context {}
                     :researcher-ownership-epoch 1 :researcher-iteration-index 0
                     :checkpointed-campaign? (boolean cap)}
            plan (prime/profile-plan primary "fixture/original")]
        (with-redefs [processors/execute-leaf-node #(swap! child-staged conj %)]
          (h/run-and-apply! ctx (prime/command :sheet/tick-tree
            {:sheet-id sheet :tick-id child-tick :parent-tick-id (:tick-id parent) :inputs {} :options options}))
          (is (h/settle-until! #(= 1 (count @child-staged)) :timeout-ms 5000))
          (let [node (:id (first (orc/get-nodes-for-sheet ctx sheet)))
                owner {:sheet-id sheet :tick-id child-tick :node-id node :exec-context {} :role :subcall
                       :node-start-id (get-in (first @child-staged) [:event :event/id])
                       :logical-action-id (pr-str [child-tick node {}])}
                prepared (journal/prepare-emitted-call ctx owner {:model "fixture/original"} (constantly plan))
                config (:configuration-id (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                                          (= child-tick (:tick-id %)) (= node (:node-id %))) (events ctx))))]
            (is (some? config))
            (f ctx (assoc owner :configuration-id config) parent logical child-staged parent-staged prepared))))))))

(deftest resumed-child-binds-one-exact-current-parent-authority
  (staged-child!
    (fn [ctx owner parent logical child-staged parent-staged _]
      (let [child-current (resumed-start! ctx owner nil child-staged)
            parent-current (resumed-start! ctx parent 2 parent-staged)
            _ (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent-current 2))))
            _ (is (nil? (:cognitect.anomalies/category (claim! ctx parent-current logical 2))))
            result (h/run-and-apply! ctx (prime/command :sheet/authorize-emitted-model-work child-current))
            auths (filterv #(= :sheet/emitted-model-work-authorized (:event/type %)) (events ctx))]
        (is (nil? (:cognitect.anomalies/category result)) (pr-str result))
        (is (= 1 (count auths)))
        (is (= (:node-start-id child-current) (:node-start-id (first auths))))
        (is (= (:node-start-id parent-current) (:parent-node-start-id (first auths))))
        (is (= 2 (:ownership-epoch (first auths))))
        (is (= logical (:generated-action-id (first auths))))
        (is (= (:configuration-id owner) (:configuration-id (first auths))))
        (let [domain-events #(vec (remove (fn [e] (= :grain/tx (:event/type e))) (events ctx)))
              before (domain-events) duplicate (h/run-and-apply! ctx (prime/command :sheet/authorize-emitted-model-work child-current))]
          (is (nil? (:cognitect.anomalies/category duplicate)))
          (is (= before (domain-events))))
        (swap! proofs conj {:kind :exact-current-authority :result result :authorizations auths :events (events ctx)})))))

(defn authorize! [ctx owner]
  (h/run-and-apply! ctx (prime/command :sheet/authorize-emitted-model-work owner)))
(defn recover-parent! [ctx parent logical epoch staged]
  (let [current (resumed-start! ctx parent epoch staged)]
    (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx current epoch))))
    (is (nil? (:cognitect.anomalies/category (claim! ctx current logical epoch))))
    current))
(defn callbacks [ctx owner prepared]
  (journal/callbacks ctx owner (:candidates prepared) :temporary-transport))
(defn started-request [prepared]
  (merge (select-keys (first (:candidates prepared)) [:provider])
         {:request-id (random-uuid) :requested-model "fixture/original" :candidate-index 0 :attempt-ordinal 0 :phase :started}))

(deftest resumed-authority-cannot-be-borrowed-by-stale-child-or-later-parent
  (staged-child!
    (fn [ctx owner parent logical child-staged parent-staged prepared]
      (let [current (resumed-start! ctx owner nil child-staged)
            parent2 (recover-parent! ctx parent logical 2 parent-staged)
            _ (is (nil? (:cognitect.anomalies/category (authorize! ctx current))))
            current-cb (callbacks ctx current prepared)
            request (started-request prepared)
            _ (is (nil? (:cognitect.anomalies/category ((:claim! current-cb) 0 nil))))
            stale-cb (callbacks ctx owner prepared)]
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (authorize! ctx owner))))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category ((:record-request! stale-cb) request))))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (authorize! ctx (assoc current :configuration-id "different-plan")))))
        (let [other (assoc-in (:plan (first (filter #(= :sheet/model-plan-retained (:event/type %)) (events ctx))))
                             [:candidates 0 :model] "fixture/contradictory")
              before (count (filter #(= :sheet/model-plan-retained (:event/type %)) (events ctx)))
              result (h/run-and-apply! ctx (prime/command :sheet/retain-model-plan
                       (merge current {:configuration-id (journal/retained-configuration-id other)
                                       :plan-scope-id "competing-plan-after-authority" :plan other})))]
          (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
          (is (= before (count (filter #(= :sheet/model-plan-retained (:event/type %)) (events ctx))))))
        (is (nil? (:cognitect.anomalies/category ((:record-request! current-cb) request))))
        (let [parent3 (recover-parent! ctx parent2 logical 3 parent-staged)]
          (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (authorize! ctx current))))
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category ((:record-request! current-cb) (assoc request :request-id (random-uuid) :attempt-ordinal 1)))))
          (is (= 1 (count (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (events ctx)))))
        (swap! proofs conj {:kind :stale-child-parent-and-configuration-fences :events (events ctx)}))))))

(deftest parent-advance-in-authorization-append-window-refuses-binding
  (staged-child!
    (fn [ctx owner parent logical child-staged parent-staged _]
      (let [current (resumed-start! ctx owner nil child-staged)
            parent2 (recover-parent! ctx parent logical 2 parent-staged)
            actual-append es/append fired (atom false)
            result (with-redefs [es/append
                                 (fn [store options]
                                   (when (and (some #(= :sheet/emitted-model-work-authorized (:event/type %)) (:events options))
                                              (compare-and-set! fired false true))
                                     (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent2 3)))))
                                   (actual-append store options))]
                     (authorize! ctx current))]
        (is @fired)
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (empty? (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (events ctx))))))))

(deftest child-first-delivery-waits-for-real-parent-reclaim-without-model-outcome
  (staged-child!
    (fn [ctx owner parent logical child-staged parent-staged prepared]
      (let [current (resumed-start! ctx owner nil child-staged)
            cb (callbacks ctx current prepared) read-seen (promise) actual-read es/read
            work (with-redefs [es/read (fn [store query]
                                        (when (contains? (:types query) :sheet/emitted-model-work-authorized)
                                          (deliver read-seen true))
                                        (actual-read store query))]
                   ;; The binding captures with-redefs only while the worker is
                   ;; observed pending and until its eventual body exits.
                   (let [work (future ((:fresh-authority! cb) (+ (System/currentTimeMillis) 5000)))]
                     (is (= true (deref read-seen 2000 :timeout)))
                     (is (not (future-done? work)))
                     (is (empty? (filter #(= :sheet/model-candidate-recorded (:event/type %)) (events ctx))))
                     (recover-parent! ctx parent logical 2 parent-staged)
                     (let [result (deref work 5000 :timeout)]
                       (is (map? result))
                       (is (= 2 (:ownership-epoch result))))))]
        (is (= 1 (count (filter #(= :sheet/emitted-model-work-authorized (:event/type %)) (events ctx)))))
        (swap! proofs conj {:kind :child-first-real-command-order :events (events ctx)})))))

(defn reservation [owner parent epoch ordinal]
  (let [r (merge (select-keys owner [:sheet-id :tick-id :node-id :node-start-id :exec-context])
                 {:admission-kind :campaign :budget-sheet-id (:sheet-id parent) :budget-tick-id (:tick-id parent)
                  :campaign-sheet-id (:sheet-id parent) :campaign-tick-id (:tick-id parent) :campaign-node-id (:node-id parent)
                  :iteration-index 0 :ownership-epoch epoch :provider-attempt-ordinal ordinal
                  :logical-action-identity "one-original-provider-task" :reserved-at (str (java.time.Instant/now))
                  :model-configuration-id (:configuration-id owner) :model-logical-action-id (:logical-action-id owner)})]
    (assoc r :invocation-identity (reservations/reservation-identity r))))
(defn reserve! [ctx r] (h/run-and-apply! ctx (prime/command :sheet/reserve-provider-call r)))

(deftest budget-and-journal-use-the-same-exact-binding-and-original-cap
  (staged-child! 1
    (fn [ctx owner parent logical child-staged parent-staged prepared]
      (let [current (resumed-start! ctx owner nil child-staged)
            parent2 (recover-parent! ctx parent logical 2 parent-staged)
            _ (is (nil? (:cognitect.anomalies/category (authorize! ctx current))))
            cb (callbacks ctx current prepared)
            _ (is (nil? (:cognitect.anomalies/category ((:claim! cb) 0 nil))))]
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (reserve! ctx (reservation owner parent2 2 0)))))
        (is (= :cognitect.anomalies/conflict
               (:cognitect.anomalies/category (reserve! ctx (assoc (reservation current parent2 2 0) :model-configuration-id "wrong")))))
        (is (nil? (:cognitect.anomalies/category (reserve! ctx (reservation current parent2 2 0)))))
        (is (nil? (:cognitect.anomalies/category ((:record-request! cb) (started-request prepared)))))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (reserve! ctx (reservation current parent2 2 1)))))
        (let [all (events ctx) root-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %)) (= (:tick-id parent) (:tick-id %))) all))]
          (is (= 1 (get-in root-start [:options :llm-call-budget])))
          (is (= 1 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) all))))
          (is (= 1 (count (filter #(= :sheet/model-request-recorded (:event/type %)) all)))))
        (swap! proofs conj {:kind :original-cap-shared-exact-authority :events (events ctx)})))))

(deftest later-child-start-revokes-budget-and-journal-authority
  (staged-child! 3
    (fn [ctx owner parent logical child-staged parent-staged prepared]
      (let [current (resumed-start! ctx owner nil child-staged)
            parent2 (recover-parent! ctx parent logical 2 parent-staged)
            _ (is (nil? (:cognitect.anomalies/category (authorize! ctx current))))
            cb (callbacks ctx current prepared)
            _ (is (nil? (:cognitect.anomalies/category ((:claim! cb) 0 nil))))
            next-child (resumed-start! ctx current nil child-staged)]
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (reserve! ctx (reservation current parent2 2 0)))))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category ((:record-request! cb) (started-request prepared)))))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (authorize! ctx current))))
        (is (nil? (:cognitect.anomalies/category (authorize! ctx next-child))))
        (is (nil? (:cognitect.anomalies/category (reserve! ctx (reservation next-child parent2 2 0)))))
        (swap! proofs conj {:kind :later-child-start-revokes :events (events ctx)})))))
