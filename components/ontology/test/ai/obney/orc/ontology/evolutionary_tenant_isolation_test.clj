(ns ai.obney.orc.ontology.evolutionary-tenant-isolation-test
  "Shared-store tenant isolation through the real contribution and workflow APIs."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.evolutionary :as evolutionary]))

(deftest evolutionary-public-queries-are-tenant-scoped
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx-a {:event-store store :tenant-id (random-uuid)}
        ctx-b {:event-store store :tenant-id (random-uuid)}
        oid (random-uuid) source-a (random-uuid) source-b (random-uuid)
        uri "urn:colliding:concept"
        canonical-a "urn:tenant-a:canonical" canonical-b "urn:tenant-b:canonical"
        populate! (fn [ctx source-id canonical label]
                    (ontology/create-ontology! ctx {:command/id oid :name label})
                    (ontology/create-concept! ctx oid {:uri canonical :label label :description label})
                    (evolutionary/evolve ctx
                      {:ontology-id oid
                       :sources [{:source-id source-id :type "rdf"
                                  :content (str "<" uri "> <http://www.w3.org/2000/01/rdf-schema#label> \"" label "\" .")}]
                       :config {:enable-colbert? false :enable-embeddings? false}}))]
    (try
      (let [a (populate! ctx-a source-a canonical-a "TENANT-A-SENTINEL")
            b (populate! ctx-b source-b canonical-b "TENANT-B-SENTINEL")]
        (is (nil? (:cognitect.anomalies/category a)) (pr-str a))
        (is (nil? (:cognitect.anomalies/category b)) (pr-str b))
        (is (= #{source-a} (set (map :source-id (evolutionary/get-all-sources ctx-a oid)))))
        (is (= #{source-b} (set (map :source-id (evolutionary/get-all-sources ctx-b oid)))))
        (is (= ["TENANT-A-SENTINEL"] (mapv :label (evolutionary/get-concepts ctx-a oid))))
        (is (= ["TENANT-B-SENTINEL"] (mapv :label (evolutionary/get-concepts ctx-b oid))))
        (is (= canonical-a (evolutionary/get-canonical-uri ctx-a oid uri)))
        (is (= canonical-b (evolutionary/get-canonical-uri ctx-b oid uri)))
        (is (nil? (evolutionary/get-source ctx-a oid source-b)))
        (is (nil? (evolutionary/get-source ctx-b oid source-a)))
        (is (= 1 (:build-count (evolutionary/get-statistics ctx-a oid))))
        (is (= 1 (:concept-count (evolutionary/get-statistics ctx-a oid))))
        (is (= (:build-id a) (get-in (evolutionary/get-statistics ctx-a oid) [:latest-build :build-id])))
        (is (= (:build-id b) (get-in (evolutionary/get-statistics ctx-b oid) [:latest-build :build-id]))))
      (finally (es/stop store)))))
