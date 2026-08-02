(ns ai.obney.orc.orc-service.core.profile
  "Value shape profiling — size/density indicators for blackboard values.

   Events record the SHAPE of the values a node read and wrote rather than
   the values themselves: every value is already durable in that node's
   :sheet/execution-value-written events, which are the canonical record of
   every write. Storing them again on lifecycle and trace events made those
   events the bulk of the log.

   A profile is what remains useful without the value — enough for a
   dashboard to render a run, and enough for a judge or the RLM drill-down
   to decide what is worth fetching in full.

   Dependency-free by design: both the command side (commands.clj) and the
   processor side (todo_processors.clj) profile values, and neither should
   have to depend on the other to do it."
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
