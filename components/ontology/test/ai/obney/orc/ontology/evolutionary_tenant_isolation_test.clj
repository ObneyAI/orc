(ns ai.obney.orc.ontology.evolutionary-tenant-isolation-test
  "Regression coverage for DET-E2E-120's evolutionary query boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.ontology.interface.evolutionary :as evolutionary]
            [ai.obney.orc.ontology.test-helpers :as h]))

(defn- append! [ctx events]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx) :events (vec events)}))

(defn- event [type tags body]
  (es/->event {:type type :tags tags :body body}))

(deftest evolutionary-public-queries-are-tenant-scoped
  (testing "colliding ontology IDs and URIs never cross a shared event store"
    (h/with-test-context [base]
      (let [tenant-a (random-uuid)
            tenant-b (random-uuid)
            ctx-a (assoc base :tenant-id tenant-a)
            ctx-b (assoc base :tenant-id tenant-b)
            ontology-id (random-uuid)
            source-a (random-uuid)
            source-b (random-uuid)
            build-a (random-uuid)
            build-b (random-uuid)
            uri "urn:colliding:concept"
            canonical-a "urn:tenant-a:canonical"
            canonical-b "urn:tenant-b:canonical"
            source-event (fn [source-id sentinel]
                           (event :evolutionary/source-registered
                                  #{[:ontology ontology-id] [:source source-id]}
                                  {:source-id source-id
                                   :source-uri (str "memory:" sentinel)
                                   :source-type "text"
                                   :content-hash (apply str (repeat 64 sentinel))
                                   :file-size 1
                                   :namespace sentinel
                                   :registered-at "2026-08-06T00:00:00Z"}))
            concept-event (fn [source-id sentinel]
                            (event :evolutionary/concepts-extracted
                                   #{[:ontology ontology-id] [:source source-id]}
                                   {:source-id source-id :ontology-id ontology-id
                                    :concepts [{:uri uri :label sentinel
                                                :entity-type "class"
                                                :source-id source-id}]
                                    :extracted-at "2026-08-06T00:00:00Z"}))
            resolution-event (fn [canonical]
                               (event :evolutionary/entities-resolved
                                      #{[:ontology ontology-id]}
                                      {:ontology-id ontology-id
                                       :resolution-mode "batch"
                                       :matches [] :canonical-map {uri canonical}
                                       :alignment-triples []
                                       :exact-matches 0 :semantic-matches 0
                                       :resolved-at "2026-08-06T00:00:00Z"}))
            build-event (fn [build-id]
                          (event :evolutionary/build-completed
                                 #{[:ontology ontology-id]}
                                 {:build-id build-id
                                  :ontology-id ontology-id
                                  :total-sources 1
                                  :total-concepts 1
                                  :total-triples 0
                                  :entities-resolved 0
                                  :duration-ms 1
                                  :completed-at "2026-08-06T00:00:00Z"}))]
        (append! ctx-a [(source-event source-a "a")
                        (concept-event source-a "TENANT-A-SENTINEL")
                        (resolution-event canonical-a)
                        (build-event build-a)])
        (append! ctx-b [(source-event source-b "b")
                        (concept-event source-b "TENANT-B-SENTINEL")
                        (resolution-event canonical-b)
                        (build-event build-b)])
        (is (= #{source-a} (set (map :source-id
                                     (evolutionary/get-all-sources ctx-a)))))
        (is (= #{source-b} (set (map :source-id
                                     (evolutionary/get-all-sources ctx-b)))))
        (is (= ["TENANT-A-SENTINEL"]
               (mapv :label (evolutionary/get-concepts ctx-a ontology-id))))
        (is (= ["TENANT-B-SENTINEL"]
               (mapv :label (evolutionary/get-concepts ctx-b ontology-id))))
        (is (= canonical-a (evolutionary/get-canonical-uri ctx-a uri)))
        (is (= canonical-b (evolutionary/get-canonical-uri ctx-b uri)))
        (is (nil? (evolutionary/get-source ctx-a source-b)))
        (is (nil? (evolutionary/get-source ctx-b source-a)))
        (let [statistics-a (evolutionary/get-statistics ctx-a ontology-id)]
          (is (= ontology-id (:ontology-id statistics-a)))
          (is (= 1 (:build-count statistics-a)))
          (is (= 1 (:concept-count statistics-a)))
          (is (= build-a (get-in statistics-a [:latest-build :build-id]))))
        (is (= build-b
               (get-in (evolutionary/get-statistics ctx-b ontology-id)
                       [:latest-build :build-id])))))))
