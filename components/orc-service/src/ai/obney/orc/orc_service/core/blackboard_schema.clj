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
      (violation path :map-fields-missing schema)
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
