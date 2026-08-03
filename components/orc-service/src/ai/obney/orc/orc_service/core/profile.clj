(ns ai.obney.orc.orc-service.core.profile
  "Value shape profiling — size/density indicators for blackboard values.

   Lifecycle and trace events record the SHAPE of what a node read and wrote
   rather than the values, which are durable in that node's
   :sheet/execution-value-written events. A profile is what stays useful
   without the value: enough to render a run, or to decide what is worth
   fetching in full.

   Dependency-free: both commands.clj and todo_processors.clj profile values
   and neither should depend on the other to do it."
  (:require [clojure.string :as str]))

(defn profile-value
  "Profile a single value's shape. Pure and deterministic."
  [v]
  (cond
    (string? v)
    {:type :string
     :length (count v)
     :word-count (count (str/split v #"\s+"))
     :line-count (count (str/split-lines v))}

    (sequential? v)
    {:type :vector
     :length (count v)}

    (map? v)
    {:type :map
     :length (count v)}

    :else
    {:type :other
     :length (count (str v))}))

(defn profile-values
  "Profile every value in a {key -> value} map, preserving the keys.
   nil values are dropped — a key with no value has no shape to record."
  [m]
  (reduce-kv (fn [acc k v]
               (if (nil? v) acc (assoc acc k (profile-value v))))
             {}
             (or m {})))

(defn compute-input-profile
  "Given a node's :reads list and a blackboard, build a profile map keyed by
   read key."
  [reads blackboard]
  (reduce (fn [acc k]
            (let [v (get-in blackboard [k :value])]
              (if (nil? v) acc (assoc acc k (profile-value v)))))
          {}
          (or reads [])))
