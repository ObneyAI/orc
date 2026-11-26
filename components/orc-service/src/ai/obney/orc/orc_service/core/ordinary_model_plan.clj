(ns ai.obney.orc.orc-service.core.ordinary-model-plan
  "Resolve one ordinary call's effective profile. No registry object is retained."
  (:require [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.model-fallback :as fallback]))

(defn- incompatible! [reason]
  (throw (ex-info (str "Model plan cannot be resumed [" (name reason) "]")
                  {:failure-kind :model-plan-incompatible :reason reason})))

(defn resolve-plan
  "Preflight a new call once, propagating the primary conversational mode before
   backup preflight. Native finite candidates keep their own protocol."
  [node provider module options]
  (let [raw (fallback/candidate-plan node provider options)
        ;; These controls are consumed by the predictor/registry, not sent as
        ;; retained generation options. All other options must be known data.
        transient-keys #{:model :orc/explicit-provider? :validate? :with-metadata?
                         :with-provider-evidence?}
        _ (doseq [candidate raw]
            (when-not (journal/valid-retained-options?
                       (apply dissoc (:options candidate) transient-keys))
              (incompatible! :unsupported-or-unsafe-options)))
        preflight (fn [index candidate inherited-mode]
                    (let [explicit? (or (contains? node :provider)
                                        (and (pos? index)
                                             (contains? (nth (:fallback-models node) (dec index)) :provider)))
                          opts (cond-> (:options candidate)
                                 inherited-mode (assoc :output-mode inherited-mode)
                                 (:model candidate) (assoc :model (:model candidate))
                                 explicit? (assoc :orc/explicit-provider? true))]
                      (llm/preflight-provider! (:provider candidate) module opts)))
        ;; Native finite protocols do not select a conversational output mode.
        ;; The first conversational candidate establishes that mode, including
        ;; nil for legacy mode; later conversational candidates retain it.
        resolved
        (loop [index 0 remaining raw selected-mode ::unset result []]
          (if-let [candidate (first remaining)]
            (let [resolved (preflight index candidate (when-not (= ::unset selected-mode) selected-mode))
                  chat? (= :chat (:protocol resolved))
                  mode (:output-mode resolved)]
              (when (and chat? (not= ::unset selected-mode) (not= selected-mode mode))
                (incompatible! :effective-profile-cannot-be-preserved))
              (recur (inc index) (rest remaining)
                     (if (and chat? (= ::unset selected-mode)) mode selected-mode)
                     (conj result resolved)))
            result))
        candidates
        (mapv (fn [{:keys [provider model protocol output-mode options]}]
                (let [native? (= :decision protocol)
                      paired? (contains? #{:json-schema :function-calling} output-mode)
                      force? (and (not native?) (boolean (:force-tool-choice? options)))
                      legacy-function? (and (not native?) (not paired?)
                                            (true? (:use-function-calling? options)))
                      profile {:version (cond native? "native-decision-v1"
                                              (= output-mode :function-calling) "paired-function-v1-strict"
                                              (= output-mode :json-schema) "strict-json-v1"
                                              legacy-function? "legacy-function-v1"
                                              :else "legacy-marker-v1")
                               :output-mode (when-not native? output-mode)
                               :force-tool-choice? force?
                               :strict-arguments? (= output-mode :function-calling)}
                      options (-> (select-keys options journal/retained-option-keys)
                                  (dissoc :model))
                      options (cond native? (dissoc options :output-mode :force-tool-choice? :use-function-calling?)
                                    paired? (-> options (dissoc :use-function-calling?)
                                                (assoc :output-mode output-mode :force-tool-choice? force?))
                                    :else (-> options (dissoc :output-mode)
                                              (assoc :use-function-calling? (boolean legacy-function?)
                                                     :force-tool-choice? force?)))]
                  {:provider provider :model model :protocol protocol
                   :options options :profile profile})) resolved)]
    {:version 1 :policy (or (:fallback-policy node) :temporary-transport)
     :candidates candidates}))
