(ns ai.obney.orc.orc-service.core.model-fallback
  (:require [clojure.string :as str]))

(def policies #{:temporary-transport :all-transport})

(defn temporary-transport? [{:keys [type http-status]}]
  (boolean
    (if (nil? type)
      (and (integer? http-status) (or (= 408 http-status) (<= 500 http-status 599)))
    (case type
      :litellm/rate-limit (or (nil? http-status) (= 429 http-status))
      :litellm/timeout (or (nil? http-status) (#{408 504} http-status))
      :litellm/connection-error (nil? http-status)
      (:litellm/server-error :litellm/provider-error)
      (and (integer? http-status) (<= 500 http-status 599))
      false))))

(defn admission
  "Classify the actual final candidate completion, never error prose."
  [policy completion]
  (let [transport? (and (= :failure (:status completion))
                        (= :transport-failure (:failure-kind completion)))
        allowed? (and transport?
                      (case policy
                        :all-transport true
                        :temporary-transport (temporary-transport? (get-in completion [:provider-evidence :transport]))
                        false))]
    {:allowed? (boolean allowed?)
     :reason (if allowed? :eligible-transport :policy-refused)}))

(def candidate-option-keys
  #{:temperature :max-tokens :top-p :top-k :frequency-penalty :presence-penalty
    :seed :reasoning :max-retries :timeout-ms})
(def reserved-option-keys
  #{:model :tick-id :sheet-id :node-id :node-start-id :context :stream :exec-context
    :execution-deadline-ms :llm-call-budget :llm-budget-root-tick-id :llm-budget-root-sheet-id
    :provider-reservation-context :node-attempt :max-node-attempts :provider-attempt-ordinal
    :logical-action-identity :ownership-epoch :budget :budget-tick-id :budget-sheet-id})
(defn invalid-options? [options]
  (or (not (map? options))
      (some #(or (not (keyword? %)) (namespace %) (reserved-option-keys %)
                 (str/ends-with? (name %) "!")) (keys options))
      (some fn? (tree-seq coll? seq options))))
(defn valid-retry? [retry]
  (and (map? retry) (every? #{:max-attempts :backoff-ms} (keys retry))
       (pos-int? (:max-attempts retry)) (vector? (:backoff-ms retry))
       (every? nat-int? (:backoff-ms retry))))
(defn valid-generation? [o]
  (and (or (not (contains? o :retry-delay-ms))
           (nat-int? (:retry-delay-ms o))
           (and (vector? (:retry-delay-ms o)) (seq (:retry-delay-ms o))
                (every? nat-int? (:retry-delay-ms o))))
       (or (not (contains? o :max-retries)) (nat-int? (:max-retries o)))
       (every? #(or (not (contains? o %)) (pos-int? (get o %))) [:max-tokens :timeout-ms :top-k])
       (every? #(or (not (contains? o %)) (number? (get o %)))
               [:temperature :top-p :frequency-penalty :presence-penalty])
       (or (not (contains? o :seed)) (integer? (:seed o)))))


(defn candidate-plan
  "Resolve an ordered request plan. Omitted backup providers always inherit the
   primary provider, not the previous candidate. Shared task options are retained."
  [node provider options]
  (let [provider (or (:provider node) provider)
        options (merge {:max-retries 0} options)
        primary {:provider provider :model (:model node) :options options}]
    (into [primary]
          (map (fn [candidate]
                 {:provider (or (:provider candidate) provider)
                  :model (:model candidate)
                  :options (merge options (:options candidate))}))
          (:fallback-models node))))

(defn durable-outcome [outcome]
  (cond-> (select-keys outcome [:status :outputs :usage :model :resolved-model :error
                                :failure-kind :provider-evidence :decisions :retryable?
                                :model-fallback :timeout-kind :output-exposed?])
    (:result outcome) (assoc :result (select-keys (:result outcome)
                                  [:outputs :code :usage :model :decisions :provider-evidence
                                   :provider-observations]))))

(defn run-candidates
  "Execute candidates within one logical call. Durable claims precede dispatch;
   outcomes precede advancement. A completed receipt replays without provider work."
  [{:keys [candidates policy start-index invoke! record! select! claim! completed-outcome output-exposed? initial-usage prior-attempts]
    :or {policy :temporary-transport start-index 0}}]
  (when-not (and (vector? candidates) (seq candidates)
                 (nat-int? start-index) (< start-index (count candidates))
                 (policies policy) (fn? invoke!) (fn? record!))
    (throw (ex-info "Invalid candidate execution contract" {:type :invalid-model-fallback})))
  (if completed-outcome
    (let [index (get-in completed-outcome [:model-fallback :candidate-index])
          selected (when (and (= :success (:status completed-outcome)) select! (some? index))
                     (select! index (nth candidates index)))]
      (if (:cognitect.anomalies/category selected)
        {:status :failure :retryable? false :error "Model selection replay could not be persisted"}
        completed-outcome))
    (if output-exposed?
      {:status :failure :retryable? false :output-exposed? true
       :error "Interrupted model stream already exposed output"}
    (loop [index start-index usage initial-usage attempts (vec prior-attempts)]
      (let [candidate (nth candidates index)
            claimed (when claim! (claim! index candidate))]
        (if (:cognitect.anomalies/category claimed)
          (if (:model-call-in-flight? claimed)
            {:status :running :model-call-in-flight? true :retryable? false}
            {:status :failure :retryable? false :usage usage :error "Model candidate claim was refused"})
          (let [outcome (invoke! index candidate)
                usage (when (or usage (:usage outcome))
                        (merge-with + (or usage {}) (or (:usage outcome) {})))
                eligible? (:allowed? (admission policy outcome))
                advance? (and eligible? (< (inc index) (count candidates))
                              (not (:output-exposed? outcome)))
                reason (cond (= :success (:status outcome)) :selected
                             (:output-exposed? outcome) :output-exposed
                             advance? :eligible-transport
                             eligible? :candidates-exhausted
                             :else :policy-refused)
                entry {:candidate-index index :provider (:provider candidate)
                       :requested-model (:model candidate) :status (:status outcome)
                       :failure-kind (:failure-kind outcome)
                       :provider-evidence (:provider-evidence outcome)
                       :reason reason :advanced? (boolean advance?)}
                attempts (conj attempts entry)
                result (assoc outcome :usage usage :retryable? false
                              :model-fallback {:policy policy :reason reason
                                               :candidate-index index
                                               :requested-model (:model candidate)
                                               :provider (:provider candidate) :attempts attempts})
                recorded (record! (assoc entry :outcome (durable-outcome result)))]
            (if (:cognitect.anomalies/category recorded)
              {:status :failure :retryable? false :usage usage
               :error "Model fallback outcome could not be persisted"}
              (if advance?
                (recur (inc index) usage attempts)
                (let [selected (when (and (= :success (:status outcome)) select!)
                                 (select! index candidate))]
                  (if (:cognitect.anomalies/category selected)
                    {:status :failure :retryable? false :usage usage
                     :error "Model fallback selection could not be persisted"}
                    result)))))))))))

(defn validate-config!
  "Validate node routing and inherited subcall configuration before building."
  [node]
  (when (contains? node :model-fallback-policy)
    (throw (ex-info "Composite model fallback is obsolete; configure fallback-models on a model node"
                    {:type :invalid-model-fallback :node (:name node)})))
  (when (or (contains? node :provider) (contains? node :fallback-models)
            (contains? node :fallback-policy))
    (let [kind (or (:node-type node) (:type node))]
      (when-not
        (and (or (nil? kind) (#{:llm-condition :repl-researcher} kind)
                 (and (= :leaf kind) (#{:ai :decision} (:executor node))))
             (or (not (contains? node :fallback-models))
                 (and (not (invalid-options? (or (:options node) {})))
                      (valid-generation? (or (:options node) {}))))
             (or (not (contains? node :provider)) (keyword? (:provider node)))
             (or (not (contains? node :fallback-policy)) (policies (:fallback-policy node)))
             (or (not (contains? node :fallback-models))
                 (and (vector? (:fallback-models node))
                      (every? (fn [candidate]
                                (and (map? candidate)
                                     (every? #{:model :provider :options} (keys candidate))
                                     (string? (:model candidate))
                                     (not (str/blank? (:model candidate)))
                                     (or (not (contains? candidate :provider))
                                         (keyword? (:provider candidate)))
                                     (or (not (contains? candidate :options))
                                         (and (map? (:options candidate))
                                              (every? candidate-option-keys (keys (:options candidate)))
                                              (valid-generation? (:options candidate))))))
                              (:fallback-models node)))))
        (throw (ex-info "Invalid node model fallback configuration"
                        {:type :invalid-model-fallback :node (:name node)})))))
  (when (map? (:rlm node))
    (let [rlm (:rlm node)
          mapping {:sub-provider :provider :sub-model :model :sub-options :options
                   :sub-fallback-models :fallback-models :sub-fallback-policy :fallback-policy}]
      (validate-config! (reduce-kv (fn [m source target]
                                    (if (contains? rlm source) (assoc m target (get rlm source)) m))
                                  {} mapping))))
  node)
