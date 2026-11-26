(ns ai.obney.orc.orc-service.core.blackboard-schema
  "Blackboard-specific schema policy.

   Malli accepts deliberately broad schemas such as :any, :some, and :map.
   Blackboard contracts do not: persisted workflow values need an intentional,
   recursively described shape."
  (:require [clojure.string :as str]))

(def ^:private unconstrained-scalars #{:any :some})
(def ^:private collection-tags #{:vector :sequential :set :list :coll :every})
(def ^:private branch-tags #{:or :and :tuple :cat :alt :merge :union})
(def ^:private named-branch-tags #{:orn :altn :catn :multi})
(def ^:private wrapper-tags #{:maybe :not :? :* :+ :repeat})
(def ^:private positional-collection-tags #{:tuple :cat :alt :catn :altn})

(defn- schema-args
  [schema]
  (let [args (subvec schema 1)]
    (if (map? (first args)) (subvec args 1) args)))

(declare violations)

(defn- violation
  [path reason schema]
  [{:path path :reason reason :schema schema}])

(defn- map-violations
  [schema path]
  (let [entries (schema-args schema)]
    (if (empty? entries)
      (if (and (map? (second schema)) (true? (:closed (second schema))))
        [] ; A closed empty map admits exactly one shape, not arbitrary data.
        (violation path :map-fields-missing schema))
      (mapcat
       (fn [entry]
         (if (and (vector? entry) (<= 2 (count entry)))
           (let [field (first entry)
                 field-schema (if (map? (second entry))
                                (nth entry 2 nil)
                                (second entry))]
             (if (nil? field-schema)
               (violation (conj path field) :map-field-schema-missing entry)
               (violations field-schema (conj path field))))
           []))
       entries))))

(defn- map-of-violations
  [schema path]
  (let [[key-schema value-schema] (schema-args schema)]
    (cond-> []
      (nil? key-schema)
      (into (violation (conj path :keys) :map-key-schema-missing schema))

      (some? key-schema)
      (into (violations key-schema (conj path :keys)))

      (nil? value-schema)
      (into (violation (conj path :values) :map-value-schema-missing schema))

      (some? value-schema)
      (into (violations value-schema (conj path :values))))))

(defn- collection-violations
  [schema path]
  (let [item-schema (first (schema-args schema))]
    (if (nil? item-schema)
      (violation (conj path :items) :collection-item-schema-missing schema)
      (violations item-schema (conj path :items)))))

(defn violations
  "Return every blackboard-specificity violation in schema.

   Paths describe the value shape rather than Malli's vector indexes, so a
   consumer sees locations such as [:payload :items] and [:values]."
  ([schema] (violations schema []))
  ([schema path]
   (cond
     (contains? unconstrained-scalars schema)
     (violation path :unconstrained-type schema)

     (= :map schema)
     (violation path :map-fields-missing schema)

     (= :map-of schema)
     (map-of-violations [:map-of] path)

     (contains? collection-tags schema)
     (violation (conj path :items) :collection-item-schema-missing schema)

     (contains? positional-collection-tags schema)
     (violation (conj path :items) :collection-item-schema-missing schema)

     (vector? schema)
     (let [tag (first schema)]
       (cond
         (= :map tag) (map-violations schema path)
         (= :map-of tag) (map-of-violations schema path)
         (contains? collection-tags tag) (collection-violations schema path)
         (contains? wrapper-tags tag)
         (if-let [inner (first (schema-args schema))]
           (violations inner path)
           (violation path :wrapped-schema-missing schema))
         (= :schema tag)
         (let [properties (when (map? (second schema)) (second schema))]
           (concat
            (mapcat (fn [[name registered-schema]]
                      (violations registered-schema (conj path :registry name)))
                    (:registry properties))
            (when-let [root-schema (first (schema-args schema))]
              (violations root-schema path))))
         (contains? branch-tags tag)
         (let [branches (schema-args schema)]
           (if (and (contains? positional-collection-tags tag) (empty? branches))
             (violation (conj path :items) :collection-item-schema-missing schema)
             (mapcat (fn [[index branch]]
                       (violations branch (conj path index)))
                     (map-indexed vector branches))))
         (contains? named-branch-tags tag)
         (let [branches (schema-args schema)]
           (if (and (contains? positional-collection-tags tag) (empty? branches))
             (violation (conj path :items) :collection-item-schema-missing schema)
             (mapcat (fn [entry]
                       (if (and (vector? entry) (<= 2 (count entry)))
                         (violations (second entry) (conj path (first entry)))
                         []))
                     branches)))
         :else []))

     :else [])))

(defn schema-map-violations
  "Return violations enriched with their top-level blackboard key."
  [schema-map]
  (->> schema-map
       (mapcat (fn [[key schema]]
                 (map #(assoc % :key key) (violations schema))))
       vec))

(defn violation-location
  [{:keys [key path]}]
  (pr-str (into [key] path)))

(defn feedback
  [violations]
  (str "Unconstrained blackboard schemas at "
       (str/join ", " (map #(str (violation-location %) " (" (pr-str (:schema %)) ")")
                            violations))
       " are forbidden"
       "; use the most specific schema possible for each value's intent"))

(declare infer-specific-schema)

(defn- collection-item-schema [values path]
  (when (empty? values)
    (throw (ex-info "Cannot infer the intent of an empty collection; provide an explicit, specific blackboard schema"
                    {:reason :empty-collection :path path})))
  (let [schemas (vec (distinct (map-indexed (fn [index value]
                                             (infer-specific-schema value (conj path index)))
                                           values)))]
    (if (= 1 (count schemas)) (first schemas) (into [:or] schemas))))

(defn infer-specific-schema
  "Infer observed structure, never semantic meaning, from an existing value.
   Use an authored schema first. Empty containers cannot reveal their intended
   member shape and fail with the exact value path instead of becoming :any."
  ([value] (infer-specific-schema value []))
  ([value path]
   (when (or (ratio? value)
             (and (float? value) (not (Double/isFinite (double value)))))
     (throw (ex-info "Cannot represent this numeric value losslessly in a model input; provide an explicit representation and contract"
                     {:reason :unrepresentable-value :path path :value-type (.getName (class value))})))
   (cond
     (nil? value) :nil
     (string? value) :string
     (boolean? value) :boolean
     (int? value) :int
     (integer? value) 'integer?
     (double? value) :double
     (float? value) 'float?
     (decimal? value) 'decimal?
     (keyword? value) :keyword
     (uuid? value) :uuid
     (inst? value) 'inst?
     (map? value)
     (do
       (when (empty? value)
         (throw (ex-info "Cannot infer the intent of an empty map; provide an explicit, field-specific blackboard schema"
                         {:reason :empty-map :path path})))
       (into [:map]
             (map (fn [[key item]] [key (infer-specific-schema item (conj path key))]))
             (sort-by (comp pr-str key) value)))
     (vector? value) [:vector (collection-item-schema value path)]
     (set? value) [:set (collection-item-schema (sort-by pr-str value) path)]
     (sequential? value) [:sequential (collection-item-schema value path)]
     :else
     (throw (ex-info "Cannot infer a specific blackboard schema; provide an explicit contract for this value"
                     {:reason :unrepresentable-value :path path :value-type (.getName (class value))})))))
