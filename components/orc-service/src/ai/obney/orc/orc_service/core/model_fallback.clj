(ns ai.obney.orc.orc-service.core.model-fallback
  (:require [clojure.string :as str]))

(def policies #{:temporary-transport :all-transport})

(defn temporary-transport? [{:keys [type http-status]}]
  (boolean
    (case type
      :litellm/rate-limit (or (nil? http-status) (= 429 http-status))
      :litellm/timeout (or (nil? http-status) (#{408 504} http-status))
      :litellm/connection-error (nil? http-status)
      (:litellm/server-error :litellm/provider-error)
      (and (integer? http-status) (<= 500 http-status 599))
      false)))

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
(defn validate-node!
  "Validate the full candidate contract, including hand-built/imported nodes."
  [node children]
  (when (:model-fallback-policy node)
    (let [first-child (first children)
          contract #(select-keys % [:instruction :reads :writes])
          common-options #(apply dissoc (or (:options %) {}) candidate-option-keys)]
      (when-not
        (and (policies (:model-fallback-policy node))
             (= :fallback (or (:node-type node) (:type node))) (seq children)
             (every? #(and (= :leaf (or (:node-type %) (:type %))) (= :ai (:executor %))
                            (string? (:model %)) (not (str/blank? (:model %)))
                            (= (contract first-child) (contract %))
                            (= (common-options first-child) (common-options %))
                            (string? (:instruction %)) (seq (:writes %))
                            (not (invalid-options? (or (:options %) {})))
                            (valid-generation? (or (:options %) {}))
                            (valid-retry? (:retry %))) children))
        (throw (ex-info "Invalid model fallback candidate contract"
                        {:type :invalid-model-fallback :node (:name node)})))))
  node)
