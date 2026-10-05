(ns ai.obney.orc.orc-service.core.decision
  "Pure rules of a model decision (`sheet/llm-decision`): which options a
   decision offers, how a declaration is validated at build time, how a provider
   answer is judged against the set actually offered, and the durable decision
   record. No effects: the executor and the workflow builder call into this."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(defn schema-options
  "Offered options of a STATIC answer-key schema, or nil when the schema is
   neither boolean nor a finite enum.
   => {:kind :boolean|:enum :ids [...] :descriptions {id description}}"
  [schema]
  (when schema
    (let [s (try (m/schema schema) (catch Exception _ nil))]
      (case (some-> s m/type)
        :boolean {:kind :boolean :ids [true false] :descriptions {}}
        :enum (when (seq (m/children s))
                {:kind :enum
                 :ids (vec (m/children s))
                 :descriptions (or (:descriptions (m/properties s)) {})})
        nil))))

(defn declaration-error
  "Build-time rejection message for a decision node, or nil when it is
   well-formed. `node` is the workflow-DSL leaf map; `schema-of` maps a
   blackboard key to its (effective) schema."
  [node schema-of]
  (let [{:keys [name writes reads options-from min-confidence abstain instruction]} node
        problem (fn [msg] (str "Decision '" name "' " msg))]
    (cond
      (not (and (string? instruction) (not (str/blank? instruction))))
      (problem "requires an :instruction")

      (not= 1 (count writes))
      (problem (str "must write exactly one answer key, got " (pr-str writes)))

      (and options-from (not (some #{options-from} reads)))
      (problem (str ":options-from " (pr-str options-from) " must also be declared in :reads"))

      (and (some? min-confidence) (nil? abstain))
      (problem ":min-confidence requires :abstain (the option written when the floor is not met)")

      (and (some? abstain) (nil? min-confidence))
      (problem ":abstain is only meaningful with :min-confidence")

      (and (some? min-confidence) (not (<= 0.0 min-confidence 1.0)))
      (problem ":min-confidence must be between 0 and 1")

      options-from
      (let [answer (schema-of (first writes))]
        (when-not (and answer (#{:string :enum} (m/type (m/schema answer))))
          (problem (str "answer key " (pr-str (first writes))
                        " must be a :string (or enum) schema when :options-from supplies the options"))))

      :else
      (let [answer (schema-of (first writes))
            offered (schema-options answer)]
        (cond
          (nil? offered)
          (problem (str "answer key " (pr-str (first writes))
                        " must have a :boolean or finite [:enum ...] schema (or supply options "
                        "with :options-from); got " (pr-str answer)))

          (and (some? abstain) (not (some #{abstain} (:ids offered))))
          (problem (str ":abstain " (pr-str abstain) " must be one of the offered options "
                        (pr-str (:ids offered)))))))))

(defn runtime-options
  "Normalise a run-time options value into [{:id :description}], or nil when
   it is not a non-empty sequence of identified options."
  [value]
  (when (and (sequential? value) (seq value)
             (every? #(and (map? %) (some? (:id %))) value))
    (mapv (fn [o] {:id (:id o) :description (:description o)}) value)))

(defn render-options
  "Provider-facing text offering every option id with its description."
  [kind options]
  (if (= :boolean kind)
    "Answer with exactly one of: true, false."
    (str "Choose exactly one of the following options and answer with its id verbatim:\n"
         (str/join "\n"
                   (for [{:keys [id description]} options]
                     (str "- " id (when-not (str/blank? description)
                                    (str ": " description))))))))

(defn judge
  "Judge a provider answer against the set actually offered, applying the
   confidence floor. Returns
   {:valid? bool :value v :record {...}}; `:value` is what is written when valid."
  [{:keys [offered abstain min-confidence]} answer confidence]
  (let [in-set? (and (some? answer) (some #(= % answer) offered))
        base {:offered (vec offered)}]
    (cond
      (not in-set?)
      {:valid? false
       :record (cond-> (assoc base :abstained? false)
                 (some? answer) (assoc :answer answer))}

      (and (some? min-confidence)
           (or (nil? confidence) (< confidence min-confidence)))
      {:valid? true
       :value abstain
       :record (assoc base :answer answer :abstained? true :set-aside answer)}

      :else
      {:valid? true
       :value answer
       :record (assoc base :answer answer :abstained? false)})))
