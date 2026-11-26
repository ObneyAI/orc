(ns ai.obney.orc.ontology.graph-test-support
  "Graph fixtures use the standalone command and query boundary."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as graph]))

(defmacro with-context [[binding] & body]
  `(let [store# (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
         ~binding {:event-store store# :tenant-id (random-uuid)}]
     (try ~@body (finally (es/stop store#)))))

(defn successful! [result]
  (when (:cognitect.anomalies/category result)
    (throw (ex-info "Graph fixture command failed" result)))
  result)

(defn ontology! [ctx name]
  (:ontology-id (successful! (graph/create-ontology! ctx {:name name}))))

(defn concept! [ctx oid uri label & [fields]]
  (successful! (graph/create-concept! ctx oid
                 (merge {:uri uri :label label :description label :scope :custom} fields))))

(defn contribution! [ctx oid concepts & [relationships]]
  (successful! (graph/import-contribution! ctx oid
                 {:contribution-id (random-uuid)
                  :expected-revision (graph/get-revision ctx oid)
                  :source {:source-id (random-uuid) :content-hash "fixture-content"}
                  :concepts concepts :relationships (vec relationships)})))
