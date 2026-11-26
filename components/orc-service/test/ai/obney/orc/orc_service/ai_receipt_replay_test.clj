(ns ai.obney.orc.orc-service.ai-receipt-replay-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.retained-ordinary-model-plan-test :as fixture]
            [hato.client :as http]
            [cognitect.anomalies :as anom])
  (:import [java.nio.file Files] [java.time Instant]))

(def packet-schema
  [:map {:description "Native saved packet; false, zero and required null are meaningful."}
   [:flag [:boolean {:description "Literal observed flag."}]]
   [:count [:int {:description "Literal observed count."}]]
   [:note [:maybe {:description "Required nullable note."} :string]]])
(def packet {:flag false :count 0 :note nil})
(def proofs (atom []))
(defn accepted? [result] (nil? (::anom/category result)))

(defn with-owner [f]
  (let [staged (atom []) tenant (random-uuid) tick (random-uuid)
        dir (.toFile (Files/createTempDirectory "orc-model-compatibility-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
        context {:tenant-id tenant :auth-claims {} :llm-provider :unregistered-no-dispatch}
        saved (atom nil)]
    (with-redefs [processors/execute-leaf-node #(swap! staged conj %)]
      (h/with-async-test-context [ctx {:event-store-conn conn :context context}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "ordinary-compatibility-" (random-uuid))
                        (orc/blackboard {:source :string :packet packet-schema})
                        (orc/llm "produce" :instruction "Copy the source."
                          :reads [:source] :writes [:packet]
                          :fallback-models [{:provider :unregistered-backup :model "fixture/backup"}]
                          :options {:max-retries 0})))
              node (first (filter #(= "produce" (:name %)) (orc/get-nodes-for-sheet ctx sheet)))
              authored (select-keys node [:provider :model :options :fallback-models :fallback-policy])]
          (h/run-and-apply! ctx (fixture/command :sheet/tick-tree
                                {:sheet-id sheet :tick-id tick :inputs {:source "original-source"}}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (let [event (:event (first @staged))
                owner {:sheet-id sheet :tick-id tick :node-id (:node-id event)
                       :node-start-id (:event/id event) :exec-context {} :role :node
                       :logical-action-id (pr-str [tick (:node-id event) {}])}]
            (reset! saved (merge {:ctx-options {:event-store-conn conn :context context}
                                 :directory (.getAbsolutePath dir) :owner owner :authored authored}
                                (f ctx owner authored)))))))
    @saved))

(defn retain! [ctx owner authored plan]
  (let [owned (assoc owner :configuration-id (journal/retained-configuration-id plan))]
    (is (accepted? (h/run-and-apply! ctx (fixture/command :sheet/retain-model-plan
                     (assoc owned :plan-scope-id (journal/plan-scope-id owner authored) :plan plan)))))
    (journal/callbacks ctx owned (:candidates plan) (:policy plan))))

(defn future-plan []
  (assoc-in (fixture/plan-fixture :unregistered-no-dispatch "fixture/original")
            [:candidates 0 :profile :version] "future-profile-v2"))


;; Synthetic decoded receipt fixtures written through current commands. These
;; prove recovery of durable native values, not hosted-provider model semantics.
(defn recover!
  ([receipts expected-status] (recover! receipts expected-status nil))
  ([receipts expected-status decorate]
  (let [saved (with-owner
                (fn [ctx owner authored]
                  (let [plan (assoc-in (future-plan) [:candidates 0 :options :max-retries] 0)
                        cb (retain! ctx owner authored plan)]
                    (is (accepted? ((:claim! cb) 0 (first (:candidates plan)))))
                    (doseq [receipt (if decorate (map #(decorate owner %) receipts) receipts)]
                      (when-let [observer (:observation-owner receipt)]
                        (is (accepted? (h/run-and-apply! ctx
                                         (fixture/command :sheet/start-provider-attempt observer)))))
                      (is (accepted? ((:record-request! cb) (assoc (dissoc receipt :outcome :provider-outcome) :phase :started))))
                      (when-not (= :started (:phase receipt))
                        (is (accepted? ((:record-request! cb) (assoc receipt :phase :completed))))))
                    {:configuration-id (journal/retained-configuration-id plan)})))
        tick (get-in saved [:owner :tick-id]) node (get-in saved [:owner :node-id])
        tenant (get-in saved [:ctx-options :context :tenant-id]) calls (atom 0)]
    (with-redefs [http/post (fn [& _] (swap! calls inc) (throw (ex-info "No recovery dispatch" {})))]
      (h/with-async-test-context
        [ctx (update-in (:ctx-options saved) [:context]
               assoc :orc/instance-id (random-uuid) :orc/clock-fn #(.plusSeconds (Instant/now) 300))]
        (is (some :resumed? (orc/resume-in-progress! ctx)))
        (is (h/settle-until! #(= :completed (:status (orc/get-tick ctx tick))) :timeout-ms 10000))
        (let [events (orc/value-log-read-tick-events ctx tenant tick)
              completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                             (= node (:node-id %))) events))
              writes (orc/value-log-resolve-writes ctx tenant tick completion)
              history (fixture/plan-history ctx tick)]
          (is (= expected-status (:status completion)))
          (is (= (if (= :success expected-status) {:packet packet} {}) writes))
          (is (= #{(:configuration-id saved)} (set (map :configuration-id history))))
          (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) history))))
          (is (= (reduce + (map #(if (= :started (:phase %)) 1 2) receipts)) (count (filter #(= :sheet/model-request-recorded (:event/type %)) history))))
          (is (not-any? #(= :sheet/provider-call-reserved (:event/type %)) events))
          (is (zero? @calls))
          (is (empty? (orc/resume-in-progress! ctx)))
          (swap! proofs conj {:directory (:directory saved) :events events :completion completion :writes writes})
          completion))))))

(defn receipt [ordinal outputs provider-outcome]
  {:request-id (random-uuid) :candidate-index 0 :attempt-ordinal ordinal
   :provider :unregistered-no-dispatch :requested-model "fixture/original"
   :outcome {:status :success :outputs outputs :model "fixture/original"
             :resolved-model "fixture/resolved" :usage {:input-tokens 2 :output-tokens 3 :total-tokens 5}}
   :provider-outcome provider-outcome})

(deftest unique-nonzero-accepted-response-replays-through-actual-ai-loop
  (let [completion (recover! [(receipt 2 {:packet packet} {:status :success})] :success)]
    (is (= {:input-tokens 2 :output-tokens 3 :total-tokens 5} (:usage completion)))
    (is (= {:source :runtime-receipts :coverage :partial :completed-receipts 1
            :missing-earlier-receipts 2}
           (get-in completion [:model-fallback :recovery-accounting])))))

(deftest predictor-success-schema-rejection-is-not-the-accepted-response
  (recover! [(receipt 0 {:packet {:flag "wrong" :count 0 :note nil}}
                      {:status :failure :failure-kind :schema-validation-failed})
             (receipt 1 {:packet packet} {:status :success})] :success))

(deftest two-distinct-accepted-responses-refuse-ambiguous-physical-history
  (let [completion (recover! [(receipt 0 {:packet packet} {:status :success})
                              (receipt 1 {:packet packet} {:status :success})] :failure)]
    (is (= :model-plan-incompatible (:failure-kind completion)))
    (is (re-find #"ambiguous-physical-response" (or (:error completion) "")))))

(deftest explicit-recorded-schema-rejection-cannot-be-promoted-by-current-validator
  (let [completion (recover! [(receipt 0 {:packet packet}
                                      {:status :failure :failure-kind :schema-validation-failed})] :failure)]
    (is (= :schema-validation-failed (:failure-kind completion)))))

(deftest missing-required-nullable-field-is-not-acceptance
  (recover! [(receipt 0 {:packet (dissoc packet :note)} {:status :success})
             (receipt 1 {:packet packet} {:status :success})] :success))

(deftest ordinal-reset-on-another-node-attempt-does-not-collapse-receipts
  (let [rejected (assoc (receipt 0 {:packet packet} {:status :failure :failure-kind :schema-validation-failed})
                        :observation-owner {:node-attempt 1})
        accepted (assoc (receipt 0 {:packet packet} {:status :success})
                        :observation-owner {:node-attempt 2})
        completion (recover! [rejected accepted] :success
                     (fn [owner receipt]
                       (update receipt :observation-owner
                         #(merge (select-keys owner [:sheet-id :tick-id :node-id :node-start-id :exec-context])
                                 {:invocation-id (random-uuid) :provider-attempt 1
                                  :candidate-index 0 :requested-provider :unregistered-no-dispatch
                                  :requested-model "fixture/original"} %))))]
    (is (= {:input-tokens 4 :output-tokens 6 :total-tokens 10} (:usage completion)))
    (is (= {:source :runtime-receipts :coverage :complete :completed-receipts 2
            :missing-earlier-receipts 0}
           (get-in completion [:model-fallback :recovery-accounting])))))

(deftest current-writer-refuses-conflicting-completion-and-foreign-scope
  (with-owner
    (fn [ctx owner authored]
      (let [plan (future-plan) cb (retain! ctx owner authored plan)
            request (receipt 0 {:packet packet} {:status :success})
            started (assoc (dissoc request :outcome :provider-outcome) :phase :started)
            completed (assoc request :phase :completed)]
        (is (accepted? ((:claim! cb) 0 (first (:candidates plan)))))
        (is (accepted? ((:record-request! cb) started)))
        (is (accepted? ((:record-request! cb) completed)))
        (is (= ::anom/conflict (::anom/category
              ((:record-request! cb) (assoc-in completed [:outcome :outputs] {})))))
        (doseq [foreign-owner [(assoc owner :configuration-id "foreign-configuration")
                               (assoc owner :node-id (random-uuid))]]
          (let [foreign (journal/callbacks ctx foreign-owner (:candidates plan) (:policy plan))]
            (is (empty? (:request-receipts foreign)))
            (is (= ::anom/conflict (::anom/category ((:record-request! foreign) started))))))
        (is (= 2 (count (:request-receipts
                         (journal/callbacks ctx (assoc owner :configuration-id
                                                 (journal/retained-configuration-id plan))
                           (:candidates plan) (:policy plan))))))
        (orc/cancel! ctx (:tick-id owner))
        {}))))

(deftest incomplete-earlier-request-is-not-refunded-or-invented-as-a-response
  (let [incomplete (-> (receipt 0 {} nil) (dissoc :outcome :provider-outcome) (assoc :phase :started))
        completion (recover! [incomplete (receipt 1 {:packet packet} {:status :success})] :success)]
    (is (= {:input-tokens 2 :output-tokens 3 :total-tokens 5} (:usage completion)))
    (is (= {:source :runtime-receipts :coverage :partial :completed-receipts 1
            :missing-earlier-receipts 0 :incomplete-receipts 1}
           (get-in completion [:model-fallback :recovery-accounting])))))

(deftest two-request-ids-cannot-claim-the-same-original-physical-invocation
  (let [invocation (random-uuid)
        completion (recover! [(receipt 0 {:packet packet} {:status :failure :failure-kind :schema-validation-failed})
                              (receipt 1 {:packet packet} {:status :success})] :failure
                     (fn [owner receipt]
                       (assoc receipt :observation-owner
                         (merge (select-keys owner [:sheet-id :tick-id :node-id :node-start-id :exec-context])
                                {:invocation-id invocation :node-attempt 1 :provider-attempt 1
                                 :candidate-index 0 :requested-provider :unregistered-no-dispatch
                                 :requested-model "fixture/original"}))))]
    (is (= :model-plan-incompatible (:failure-kind completion)))
    (is (re-find #"ambiguous-physical-response" (or (:error completion) "")))))
