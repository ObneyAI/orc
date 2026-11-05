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
  (let [{:keys [name writes reads options-from bands-from min-confidence abstain instruction]} node
        problem (fn [msg] (str "Decision '" name "' " msg))]
    (cond
      (not (and (string? instruction) (not (str/blank? instruction))))
      (problem "requires an :instruction")

      (not= 1 (count writes))
      (problem (str "must write exactly one answer key, got " (pr-str writes)))

      (and bands-from options-from)
      (problem ":bands-from and :options-from are mutually exclusive: a decision offers either unordered options or ordered bands")

      (and bands-from (not (some #{bands-from} reads)))
      (problem (str ":bands-from " (pr-str bands-from) " must also be declared in :reads"))

      ;; A low-confidence grade is a later assessment concern, not an
      ;; abstention value: a band is always an ordered grade, never a stand-in.
      (and bands-from (or (some? min-confidence) (some? abstain)))
      (problem ":min-confidence and :abstain are not supported on a banded decision (:bands-from)")

      (and bands-from (let [answer (schema-of (first writes))]
                        (not (and answer
                                  (= :int (some-> (try (m/schema answer) (catch Exception _ nil))
                                                  m/type))))))
      (problem (str "answer key " (pr-str (first writes))
                    " must be an integer schema when :bands-from supplies the bands"))

      bands-from nil

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

          (and (some? abstain) (not (some #(= abstain %) (:ids offered))))
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

(defn rubric
  "Validate a run-time rubric value for a banded decision. A rubric is a map
   `{:bands {1 \"description\" 2 \"description\" ...} :criterion str? :stance str?}`
   whose bands are integer-keyed, contiguous, at least two levels, every
   description a non-blank string. Never guesses: returns
   {:rubric {:bands [[level description] ...] :criterion c :stance s}} when
   valid, or {:error message}."
  [value]
  (let [bands (when (map? value) (:bands value))
        levels (when (map? bands) (sort (keys bands)))]
    (cond
      (not (map? value))
      {:error (str "rubric must be a map with :bands, got " (pr-str value))}

      (not (and (map? bands) (seq bands)))
      {:error "rubric :bands must be a non-empty map of integer level to description"}

      (not (every? integer? levels))
      {:error (str "rubric band levels must be integers, got " (pr-str levels))}

      (< (count levels) 2)
      {:error "rubric must offer at least two bands"}

      (not= levels (range (first levels) (+ (first levels) (count levels))))
      {:error (str "rubric band levels must be contiguous, got " (pr-str levels))}

      (some #(not (and (string? (get bands %)) (not (str/blank? (get bands %))))) levels)
      {:error (str "every rubric band needs a non-blank description; missing or blank for levels "
                   (pr-str (filterv #(not (and (string? (get bands %)) (not (str/blank? (get bands %)))))
                                    levels)))}

      (and (some? (:criterion value)) (not (string? (:criterion value))))
      {:error "rubric :criterion must be a string"}

      (and (some? (:stance value)) (not (string? (:stance value))))
      {:error "rubric :stance must be a string"}

      :else
      {:rubric (cond-> {:bands (mapv (fn [l] [l (get bands l)]) levels)}
                 (not (str/blank? (:criterion value))) (assoc :criterion (:criterion value))
                 (not (str/blank? (:stance value))) (assoc :stance (:stance value)))})))

(defn render-bands
  "Provider-facing text offering the rubric: criterion, stance, then every band
   as \"n: description\" in order."
  [{:keys [bands criterion stance]}]
  (str/join "\n"
            (concat
             (when criterion [(str "Criterion: " criterion)])
             (when stance [(str "Stance: " stance)])
             ["Choose exactly the one band that applies and answer with its number verbatim:"]
             (for [[level description] bands]
               (str level ": " description)))))

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
