(ns ai.obney.orc.orc-service.core.ai-model-receipts)

(defn- request-identity [receipt]
  (dissoc receipt :phase :outcome :provider-outcome))

(defn- execution-identity [receipt]
  ;; Local retry ordinals legitimately restart on another node execution. Never
  ;; infer a physical request identity from that ordinal alone.
  (select-keys (:observation-owner receipt)
    [:node-start-id :node-attempt :node-id :exec-context]))

(defn select-response
  "Select an exact, uniquely accepted native response inside the callbacks'
   already-scoped operation/configuration. validate-response is the AI executor's
   original declared-write validator. Predictor success alone is not acceptance.
   Accounting describes retained runtime evidence, never inferred provider usage."
  [receipts validate-response]
  (let [requests (vals (group-by :request-id receipts))
        shared-invocations (vals (group-by #(get-in % [:observation-owner :invocation-id])
                                    (filter #(get-in % [:observation-owner :invocation-id]) receipts)))
        contradictory? (or (some #(> (count (set (map :request-id %))) 1) shared-invocations)
                           (some #(or (> (count (set (map request-identity %))) 1)
                                  (> (count (set (filter (comp #{:completed} :phase) %))) 1)) requests))
        completed (mapv #(first (filter (comp #{:completed} :phase) %)) requests)
        completed (filterv some? completed)
        accepted (filterv
                   (fn [receipt]
                     (let [prediction (:outcome receipt)
                           validation (validate-response prediction)]
                       (and (= :success (:status prediction))
                            (not (:error prediction))
                            (or (nil? (:provider-outcome receipt))
                                (= :success (get-in receipt [:provider-outcome :status])))
                            (empty? (:unparseable-keys validation))
                            (= :success (get-in validation [:schema-result :status])))))
                   completed)]
    (cond
      (or contradictory? (> (count accepted) 1)
          (some #(not (and (some? (:request-id %)) (nat-int? (:attempt-ordinal %)))) receipts))
      {:failure {:status :failure :retryable? false :failure-kind :model-plan-incompatible
                 :error "Model plan cannot be resumed [ambiguous-physical-response]"}}

      (= 1 (count accepted))
      (let [selected (first accepted)
            groups (vals (group-by execution-identity (map first requests)))
            missing (reduce + 0
                      (for [group groups
                            :let [ordinals (set (map :attempt-ordinal group))
                                  last-ordinal (apply max ordinals)]]
                        (- (inc last-ordinal) (count ordinals))))
            incomplete (- (count requests) (count completed))
            usage (reduce (fn [acc receipt]
                            (when (or acc (get-in receipt [:outcome :usage]))
                              (merge-with + (or acc {}) (or (get-in receipt [:outcome :usage]) {}))))
                          nil completed)
            partial? (or (pos? missing) (pos? incomplete)
                         (some #(not (map? (get-in % [:outcome :usage]))) completed))]
        {:receipt selected :completed completed :usage usage
         :accounting (cond-> {:source :runtime-receipts :coverage (if partial? :partial :complete)
                              :completed-receipts (count completed) :missing-earlier-receipts missing}
                       (pos? incomplete) (assoc :incomplete-receipts incomplete))})

      :else nil)))
