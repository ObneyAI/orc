(ns ai.obney.orc.orc-service.core.researcher-models
  "Request-local model selection for prime and inline researcher calls."
  (:require [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.model-fallback :as fallback]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.core.ordinary-model-plan :as ordinary-plan]))

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

(defn prepare-prime
  "Prepare only checkpointed configured prime calls. Other researcher surfaces
   retain their existing request identities and preparation."
  [node provider module inputs options context]
  (let [ctx (or (get-in context [:model-role-context :context]) context)
        call-owner (assoc (owner context :prime "unprepared")
                          :ownership-epoch (:researcher-ownership-epoch context)
                          :legacy-completed-action-id (:legacy-completed-prime-action-id context))
        authored (select-keys node [:provider :model :options :fallback-models :fallback-policy])]
    (journal/prepare-prime-call
     ctx call-owner authored (:researcher-iteration context) (:researcher-effect-claims context)
     #(ordinary-plan/resolve-plan node (or (:provider node) provider) module
                                  (dissoc options :orc/idempotency-key))
     (fn [plan configuration-id]
       (effects/provider-logical-action-identity
        {:tick-id (:tick-id context) :node-id (:node-id context)
         :iteration-index (:researcher-iteration context)
         :provider (:provider (first (:candidates plan))) :model (:model node)
         :module module :inputs inputs
         :options {:request-version "prime-request-v1" :role :prime :name (:name node)
                   :role-plan-id (journal/prime-plan-scope-id call-owner authored)
                   :configuration-id configuration-id}})))))

(defn prepare-inline
  "Retain checkpointed inline specialist policy under its unchanged effect ID."
  [node provider module options context logical-action-id]
  (journal/prepare-inline-call
   (or (get-in context [:model-role-context :context]) context)
   (assoc (owner context :subcall logical-action-id)
          :ownership-epoch (:researcher-ownership-epoch context))
   (select-keys node [:provider :model :options :fallback-models :fallback-policy])
   #(ordinary-plan/resolve-plan node (or (:provider node) provider) module
                                (dissoc options :orc/idempotency-key))))

(defn- persist! [record! receipt]
  (when record!
    (let [r (record! receipt)]
      (when (:cognitect.anomalies/category r)
        (throw (ex-info "Model request receipt could not be persisted"
                        {:failure-kind :observation-persistence-failed}))))))

(defn- distinct-completed-receipts [prepared receipts]
  (let [receipts (vec (distinct receipts))
        by-attempt (group-by :attempt-ordinal receipts)]
    (when (and prepared
               (or (some #(not (nat-int? (:attempt-ordinal %))) receipts)
                   (some #(< 1 (count %)) (vals by-attempt))))
      (throw (ex-info "Historical model request ordinal has conflicting receipts"
                      {:failure-kind :model-plan-incompatible :reason :historical-operation-ambiguous})))
    receipts))

(defn- exact-replay-success
  "A saved success authorizes incorporation, never another physical request.
   Earlier missing outcomes do not make that exact result disappear."
  [prepared receipts]
  (when (:replay-only? prepared)
    (let [successes (filterv #(= :success (get-in % [:outcome :status])) receipts)]
      (when (< 1 (count successes))
        (throw (ex-info "Historical model request has competing successful receipts"
                        {:failure-kind :model-plan-incompatible :reason :historical-operation-ambiguous})))
      (when-let [receipt (first successes)]
        (let [ordinals (vec (sort (map :attempt-ordinal receipts)))
              missing (- (inc (last ordinals)) (count ordinals))
              unreported (mapv :attempt-ordinal (filter #(nil? (get-in % [:outcome :usage])) receipts))
              known-usage (keep #(get-in % [:outcome :usage]) receipts)
              usage (when (seq known-usage) (reduce #(merge-with + %1 %2) {} known-usage))
              outcome (:outcome receipt)
              accounting {:source :retained-model-request-receipts
                          :scope :candidate-physical-attempts
                          :selected-request-id (:request-id receipt)
                          :selected-attempt-ordinal (:attempt-ordinal receipt)
                          :known-completed-attempt-ordinals ordinals
                          :unrecorded-attempt-ordinal-count missing
                          :unreported-usage-attempt-ordinals unreported
                          :usage-complete? (and (zero? missing) (empty? unreported))}]
          (-> outcome
              (assoc :usage usage)
              (update :provider-evidence #(assoc (or % {}) :runtime-derived-recovery-accounting accounting))))))))

(defn predict
  "Retry a failed model request without replaying code/tools. Candidate receipts
   and successful role selection survive checkpoint/restart."
  [node provider module inputs options context role logical-action-id]
  (let [provider (or (:provider node) provider)
        configured? (contains? node :fallback-models)
        prepared (case role
                   :prime (:prepared-prime-model-call context)
                   :subcall (:prepared-inline-model-call context)
                   nil)
        policy (or (:policy prepared) (:fallback-policy node) :temporary-transport)
        ;; Effective execution candidates are separate from the legacy resolver
        ;; below, whose options preimage still identifies historical callbacks.
        ;; Opting out of checkpoints does not opt out of a common response mode.
        candidates (cond
                     prepared (:candidates prepared)
                     configured? (:candidates (ordinary-plan/resolve-plan
                                               node provider module (dissoc options :orc/idempotency-key)))
                     :else (resolved-plan node provider module options))
        config-candidates (when-not prepared
                            (resolved-plan node provider module (or (:model-configuration-options context) options)))
        callbacks (or (:callbacks prepared) (when configured?
                    (journal/callbacks (or (get-in context [:model-role-context :context]) context)
                                       (owner context role logical-action-id) config-candidates policy)))
        invoke! (fn [index candidate]
                  (let [max-retries (or (get-in candidate [:options :max-retries]) 0)
                        receipts (distinct-completed-receipts
                                   prepared (filterv #(and (= index (:candidate-index %)) (= :completed (:phase %)))
                                                     (:request-receipts callbacks)))
                        completed (into {} (map (juxt :attempt-ordinal :outcome) receipts))]
                    (if-let [known-success (exact-replay-success prepared receipts)]
                      known-success
                    (loop [attempt 0 usage nil]
                      (let [known (get completed attempt)
                            _ (when (and prepared (nil? known))
                                (when (:replay-only? prepared)
                                  (throw (ex-info "Historical researcher profile cannot authorize a fresh request"
                                                  {:failure-kind :model-plan-incompatible
                                                   :reason :legacy-effective-profile-unretained})))
                                (let [resolved (llm/preflight-provider!
                                                (:provider candidate) module
                                                (cond-> (:options candidate)
                                                  (:model candidate) (assoc :model (:model candidate))))]
                                  (when-not (and (= (:protocol candidate) (:protocol resolved))
                                                 (= (get-in candidate [:profile :output-mode]) (:output-mode resolved)))
                                    (throw (ex-info "Retained researcher profile cannot be preserved"
                                                    {:failure-kind :model-plan-incompatible
                                                     :reason :effective-profile-cannot-be-preserved})))))
                            now ((or (:campaign-now-ms-fn context) #(System/currentTimeMillis)))
                            remaining (some-> (:execution-deadline-ms context) (- now))
                            reservation (when-not (or known (and remaining (not (pos? remaining))))
                                          (if-let [reserve! (:reserve-provider-call! context)]
                                            (reserve! {:candidate-index index :node-attempt 1
                                                       :iteration-index (:researcher-iteration context)
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
                              (cond-> {:status :timeout
                                       :error (or (:cognitect.anomalies/message reservation)
                                                  "LLM call budget exhausted before provider attempt")}
                                (:failure-kind reservation) (assoc :failure-kind (:failure-kind reservation)))
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
                                                                (cond-> (merge (:options candidate)
                                                                                ;; Predictor controls belong to this request,
                                                                                ;; not the retained provider profile.
                                                                                (select-keys options [:with-metadata? :validate? :with-provider-evidence?])
                                                                                {:max-retries 0})
                                                                  (:model candidate) (assoc :model (:model candidate))
                                                                  timeout (assoc :timeout-ms (max 1 timeout))))]
                                               {:status :success :result prediction :outputs (:outputs prediction)
                                                :usage (:usage prediction) :model (:model prediction)})
                                             (catch Exception e
                                               (let [timeout? (llm/transport-timeout? e)]
                                                 (cond-> (merge {:status :failure
                                                                 :error (if timeout?
                                                                          "Provider call deadline exceeded"
                                                                          (.getMessage e))
                                                                 :usage (get-in (ex-data e) [:provider-evidence :usage])}
                                                                (select-keys (ex-data e) [:failure-kind :provider-evidence]))
                                                   timeout? (assoc :timeout-kind :provider)))))]
                                (persist! (:record-request! callbacks)
                                          (assoc receipt :phase :completed
                                                 :outcome (fallback/durable-outcome result)))
                                result))
                            usage (when (or usage (:usage outcome)) (merge-with + (or usage {}) (or (:usage outcome) {})))
                            ;; Legacy/future profiles authorize no dispatch. They
                            ;; can nevertheless traverse an already recorded
                            ;; transport retry to its exact known response.
                            replay-next? (and (:replay-only? prepared)
                                              (contains? completed (inc attempt)))
                            _ (when (and (:replay-only? prepared)
                                         (= :transport-failure (:failure-kind outcome))
                                         (some #(> % attempt) (keys completed))
                                         (not replay-next?))
                                (throw (ex-info "Historical model request receipts have an ambiguous attempt gap"
                                                {:failure-kind :model-plan-incompatible
                                                 :reason :historical-operation-ambiguous})))]
                        (if (and (or (< attempt max-retries) replay-next?)
                                 (= :transport-failure (:failure-kind outcome)))
                          (do
                            (when (pos? (long (or (get-in candidate [:options :retry-delay-ms]) 0)))
                              (Thread/sleep (long (min (max 0 (or remaining Long/MAX_VALUE))
                                                       (get-in candidate [:options :retry-delay-ms])))))
                            (recur (inc attempt) usage))
                          (assoc outcome :usage usage)))))))
        outcome (if configured?
                  (fallback/run-candidates (merge callbacks {:candidates candidates :policy policy :invoke! invoke!}))
                  (invoke! 0 (first candidates)))]
    (if (= :success (:status outcome))
      (cond-> (assoc (:result outcome) :usage (:usage outcome))
        (:model-fallback outcome) (assoc :model-fallback (:model-fallback outcome)))
      (throw (ex-info (or (:error outcome) "Model request is already in progress")
                      (select-keys outcome [:status :usage :failure-kind :provider-evidence :model-fallback :model-call-in-flight? :timeout-kind]))))))
