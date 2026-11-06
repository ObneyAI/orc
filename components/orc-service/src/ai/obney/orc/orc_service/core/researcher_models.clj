(ns ai.obney.orc.orc-service.core.researcher-models
  "Request-local model selection for prime and inline researcher calls."
  (:require [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.model-fallback :as fallback]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.core.value-log :as value-log]))

(defn subcall-defaults [node]
  (let [rlm (if (map? (:rlm node)) (:rlm node) {})]
    (cond-> {}
      (contains? rlm :sub-model) (assoc :model (:sub-model rlm))
      (contains? rlm :sub-provider) (assoc :provider (:sub-provider rlm))
      (contains? rlm :sub-options) (assoc :options (:sub-options rlm))
      (contains? rlm :sub-fallback-models) (assoc :fallback-models (:sub-fallback-models rlm))
      (contains? rlm :sub-fallback-policy) (assoc :fallback-policy (:sub-fallback-policy rlm)))))

(defn inherit [defaults opts]
  (cond-> (merge defaults opts)
    (or (:options defaults) (:options opts))
    (assoc :options (merge (:options defaults) (:options opts)))))

(defn- resolved-plan [node provider module options]
  (mapv (fn [index candidate]
          (let [declared (if (zero? index) node (nth (:fallback-models node) (dec index)))
                opts (cond-> (:options candidate)
                       (:model candidate) (assoc :model (:model candidate))
                       (or (contains? node :provider) (contains? declared :provider))
                       (assoc :orc/explicit-provider? true))]
            (if (or (contains? node :fallback-models) (contains? node :provider))
              (select-keys (llm/preflight-provider! (:provider candidate) module opts) [:provider :model :options])
              (assoc candidate :options (dissoc opts :orc/explicit-provider?)))))
        (range) (fallback/candidate-plan node provider options)))

(defn logical-id [context role name module inputs node options]
  (let [provider (or (:provider node) (:llm-provider context) (:provider context))
        plan (resolved-plan node provider module options)]
    (effects/provider-logical-action-identity
     {:tick-id (:tick-id context) :node-id (:node-id context)
      :iteration-index (:researcher-iteration context)
      :provider provider :model (:model node) :module module :inputs inputs
      :options {:role role :name name
                :configuration-id (journal/configuration-id plan (or (:fallback-policy node) :temporary-transport))}})))

(defn- owner [context role logical-action-id]
  (merge (get-in context [:model-role-context :owner])
         {:sheet-id (:sheet-id context) :tick-id (:tick-id context) :node-id (:node-id context)
          :node-start-id (or (:node-start-id context) (get-in context [:event :event/id]))
          :exec-context (or (:exec-context context) (value-log/exec-context (get-in context [:event :inputs])))
          :role role :logical-action-id logical-action-id}))

(defn- persist! [record! receipt]
  (when record!
    (let [r (record! receipt)]
      (when (:cognitect.anomalies/category r)
        (throw (ex-info "Model request receipt could not be persisted"
                        {:failure-kind :observation-persistence-failed}))))))

(defn predict
  "Retry a failed model request without replaying code/tools. Candidate receipts
   and successful role selection survive checkpoint/restart."
  [node provider module inputs options context role logical-action-id]
  (let [provider (or (:provider node) provider)
        configured? (contains? node :fallback-models)
        policy (or (:fallback-policy node) :temporary-transport)
        candidates (resolved-plan node provider module options)
        config-candidates (resolved-plan node provider module (or (:model-configuration-options context) options))
        callbacks (when configured?
                    (journal/callbacks (or (get-in context [:model-role-context :context]) context)
                                       (owner context role logical-action-id) config-candidates policy))
        invoke! (fn [index candidate]
                  (let [max-retries (or (get-in candidate [:options :max-retries]) 0)
                        completed (into {} (for [r (:request-receipts callbacks)
                                                 :when (and (= index (:candidate-index r))
                                                            (= :completed (:phase r)))]
                                             [(:attempt-ordinal r) (:outcome r)]))]
                    (loop [attempt 0 usage nil]
                      (let [known (get completed attempt)
                            now ((or (:campaign-now-ms-fn context) #(System/currentTimeMillis)))
                            remaining (some-> (:execution-deadline-ms context) (- now))
                            reservation (when-not (or known (and remaining (not (pos? remaining))))
                                          (if-let [reserve! (:reserve-provider-call! context)]
                                            (reserve! {:iteration-index (:researcher-iteration context)
                                                       :logical-action-identity (str logical-action-id "/candidate/" index)
                                                       :provider-attempt-ordinal (+ (* (or (:effect-attempt-ordinal context) 0)
                                                                                      (inc max-retries)) attempt)})
                                            (when-let [reserve! (:reserve-llm-call! context)] (reserve!))))
                            outcome
                            (cond
                              known known
                              (and remaining (not (pos? remaining)))
                              {:status :timeout :error "Execution deadline exhausted before provider attempt"}
                              reservation
                              {:status :timeout :error "LLM call budget exhausted before provider attempt"}
                              :else
                              (let [receipt {:request-id (random-uuid) :candidate-index index
                                             :attempt-ordinal attempt :provider (:provider candidate)
                                             :requested-model (:model candidate)}
                                    _ (persist! (:record-request! callbacks) (assoc receipt :phase :started))
                                    result (try
                                             (let [configured-timeout (get-in candidate [:options :timeout-ms])
                                                   timeout (cond (and remaining configured-timeout) (min remaining configured-timeout)
                                                                 remaining remaining :else configured-timeout)
                                                   prediction (llm/predict (:provider candidate) module inputs
                                                                (cond-> (assoc (:options candidate) :max-retries 0)
                                                                  (:model candidate) (assoc :model (:model candidate))
                                                                  timeout (assoc :timeout-ms (max 1 timeout))))]
                                               {:status :success :result prediction :outputs (:outputs prediction)
                                                :usage (:usage prediction) :model (:model prediction)})
                                             (catch Exception e
                                               (merge {:status :failure :error (.getMessage e)
                                                       :usage (get-in (ex-data e) [:provider-evidence :usage])}
                                                      (select-keys (ex-data e) [:failure-kind :provider-evidence]))))]
                                (persist! (:record-request! callbacks)
                                          (assoc receipt :phase :completed
                                                 :outcome (fallback/durable-outcome result)))
                                result))
                            usage (when (or usage (:usage outcome)) (merge-with + (or usage {}) (or (:usage outcome) {})))]
                        (if (and (< attempt max-retries) (= :transport-failure (:failure-kind outcome)))
                          (do
                            (when (pos? (long (or (get-in candidate [:options :retry-delay-ms]) 0)))
                              (Thread/sleep (long (min (max 0 (or remaining Long/MAX_VALUE))
                                                       (get-in candidate [:options :retry-delay-ms])))))
                            (recur (inc attempt) usage))
                          (assoc outcome :usage usage))))))
        outcome (if configured?
                  (fallback/run-candidates (merge callbacks {:candidates candidates :policy policy :invoke! invoke!}))
                  (invoke! 0 (first candidates)))]
    (if (= :success (:status outcome))
      (cond-> (assoc (:result outcome) :usage (:usage outcome))
        (:model-fallback outcome) (assoc :model-fallback (:model-fallback outcome)))
      (throw (ex-info (or (:error outcome) "Model request is already in progress")
                      (select-keys outcome [:status :usage :failure-kind :provider-evidence :model-fallback :model-call-in-flight?]))))))
