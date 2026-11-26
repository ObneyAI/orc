(ns ai.obney.orc.ontology.core.managed-graph
  "ORC vocabulary definitions and idempotent writes through the standalone library."
  (:require [ai.obney.ontology.interface :as graph]))

(defn- retry-key [value]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (pr-str value) "UTF-8")))

(defn definition [scope]
  (let [tree? (= :tree-class scope)
        label (if tree? "Tree class ontology" "Behavioral subtree ontology")]
    {:request-id (retry-key [:orc/managed-graph scope])
     :scope scope :name label :description (str "ORC managed " label)
     :base-uri (if tree? "tree-class:" "behavioral-subtree:")
     :predicate-policies {"behavior:composes-into" {:cross-ontology? true}}}))

(defn checked [result]
  (if (:cognitect.anomalies/category result)
    (throw (ex-info (:cognitect.anomalies/message result) result))
    result))

(defn- commit! [operation]
  ;; Concurrent processors may advance the graph between validation and append.
  ;; Retry only the store's CAS rejection, with the same semantic request key.
  (loop [attempt 0]
    (let [result (operation)]
      (if (and (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result))
               (:cas result) (< attempt 20))
        (do (Thread/sleep (long (rand-int (min 64 (bit-shift-left 2 (min attempt 5))))))
            (recur (inc attempt)))
        (checked result)))))

(defn ontology-id! [ctx scope]
  (:ontology-id (commit! #(graph/create-ontology! ctx (definition scope)))))

(defn- ensure-record! [present? create!]
  (when-not (present?)
    (commit! (fn []
               (let [result (create!)]
                 ;; A host batch can create this same record under a different request key.
                 (if (and (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result))
                          (present?))
                   nil
                   result))))))

(defn ensure-concept! [ctx scope target]
  (let [oid (ontology-id! ctx scope)
        uri (str (name scope) ":" target)]
    (ensure-record! #(checked (graph/get-concept-by-uri ctx oid uri))
                    #(graph/create-concept! ctx oid
                 {:request-id (retry-key [:orc/concept oid uri])
                  :uri uri :scope scope :label (str target)
                  :description (str (if (= scope :tree-class) "Tree-class" "Behavioral subtree")
                                    " concept for " target)
                  :provenance {:kind :system-static}}))
    {:ontology-id oid :uri uri}))

(defn ensure-edge! [ctx source target predicate]
  (let [edge {:source-ontology-id (:ontology-id source) :source-uri (:uri source)
              :target-ontology-id (:ontology-id target) :target-uri (:uri target)
              :predicate predicate}]
    (ensure-record!
      #(some (fn [existing] (= edge (select-keys existing (keys edge))))
             (checked (graph/get-relationships ctx (:ontology-id source))))
      #(graph/create-relationship! ctx
         (assoc edge :request-id (retry-key [:orc/edge (:ontology-id source) (:uri source)
                                             predicate (:ontology-id target) (:uri target)]))))))
