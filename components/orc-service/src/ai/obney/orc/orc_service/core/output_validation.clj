(ns ai.obney.orc.orc-service.core.output-validation
  "Shared declared-output normalization and validation for leaf and final candidates."
  (:require [ai.obney.orc.llm.interface :as llm]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]))

;; A map key the contract declares as a string stays a string; keys the contract
;; leaves to convention are read as keywords (llm.allium). Malli's stock
;; `key-transformer` keywordizes every key, which made `[:map ["0" :double]]`
;; unsatisfiable for code leaves. Mirrors the provider path in llm core.
(def ^:private declared-string-key-transformer
  (mt/transformer
   {:decoders
    {:map
     {:compile
      (fn [schema _]
        (let [declared-keys (set (map first (m/children schema)))
              string-keys (into #{} (filter string?) declared-keys)]
          (fn [x]
            (if (map? x)
              (reduce-kv (fn [m k v]
                           (assoc m
                                  (cond
                                    (contains? declared-keys k) k
                                    (and (string? k) (not (contains? string-keys k)))
                                    (keyword k)
                                    ;; A declared string key may arrive as the same
                                    ;; un-namespaced keyword (SIO parses provider JSON
                                    ;; with :key-fn keyword). Namespaced keywords and
                                    ;; undeclared names never map.
                                    (and (keyword? k) (nil? (namespace k))
                                         (contains? string-keys (name k)))
                                    (name k)
                                    :else k)
                                  v))
                         {}
                         x)
              x))))}}}))

(defn validate-outputs
  "Decode provider JSON values and normalize object keys according to each
   declared Malli schema, then reject an invalid successful leaf result before
   its writes become canonical. Provider JSON numbers are canonicalized through
   the schema; numeric strings remain invalid. Non-provider leaves receive only
   schema-directed key normalization and retain strict JVM value types."
  ([blackboard result]
   (validate-outputs blackboard result false))
  ([blackboard result provider-output?]
   (if (not= :success (:status result))
     result
     (let [key-transformer declared-string-key-transformer
           rejected-writes (:outputs result)
           normalized (reduce-kv
                       (fn [outputs key value]
                         (assoc outputs key
                                (if-let [schema (get-in blackboard [key :schema])]
                                  (if provider-output?
                                    (llm/decode-provider-value schema value)
                                    (m/decode schema value key-transformer))
                                  value)))
                       {}
                       (:outputs result))]
       (if-let [[key explanation]
                (some (fn [[key value]]
                        (when-let [schema (get-in blackboard [key :schema])]
                          (try
                            (when-not (m/validate schema value)
                              [key (me/humanize (m/explain schema value))])
                            (catch Exception e
                              [key {:invalid-schema (.getMessage e)}]))))
                      normalized)]
         (-> result
             (assoc :status :failure
                    :error (str "Blackboard schema validation failed for "
                                (pr-str key) ": " explanation)
                    :outputs {}
                    :rejected-writes rejected-writes))
         (assoc result :outputs normalized))))))
