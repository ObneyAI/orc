(ns ai.obney.orc.ontology.contribution-integration-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.evolutionary-builder :as builder]
            [ai.obney.orc.ontology.interface.evolutionary :as evolutionary]))

(deftest extraction-workflow-submits-durable-graph-contributions
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx {:event-store store :tenant-id (random-uuid)}
        calls (atom [])
        config {:enable-colbert? false :enable-embeddings? false}]
    (try
      (with-redefs [builder/extract-from-source
                    (fn [_ source-id _ content _]
                      (swap! calls conj content)
                      {:concepts [{:uri (str "urn:" content) :label content
                                   :definition (str content " definition") :entity-type "Thing"
                                   :source-id source-id}]
                       :relationships []
                       :tbox {:classes [{:uri (str "urn:class:" content) :label content}]}
                       :abox [{:uri (str "urn:instance:" content) :type (str "urn:class:" content)}]})]
        (let [built (builder/build-from-sources ctx {:sources [{:type "text" :content "alpha"}]
                                                       :config config})
              oid (:ontology-id built)
              evolved (builder/evolve ctx {:ontology-id oid
                                           :sources [{:type "text" :content "alpha"}
                                                     {:type "text" :content "beta"}]
                                           :config config})
              snapshot (ontology/export-snapshot ctx oid)
              events (into [] (es/read store {:tenant-id (:tenant-id ctx)}))]
          (is (= ["alpha" "beta"] @calls) "Committed source hashes suppress repeated extraction")
          (is (= #{"urn:alpha" "urn:beta"} (set (map :uri (:concepts snapshot)))))
          (is (= 2 (count (:classes (:tbox snapshot)))) "Incremental contributions preserve prior schema")
          (is (= 2 (count (:abox snapshot))))
          (is (= 2 (count (ontology/get-contributions ctx oid))))
          (is (= 2 (count (ontology/get-sources ctx oid))))
          (is (= 1 (:new-sources-processed evolved)))
          (is (= (:triple-count snapshot) (:total-triples evolved)))
          (is (= #{:ontology/ontology-created :ontology/contribution-imported}
                 (set (map :event/type (filter #(contains? ontology/graph-event-types (:event/type %)) events)))) "Only the standalone API persists graph events")
          (is (= [:evolutionary/build-started :evolutionary/build-completed]
                 (mapv :event/type (filter #(= (:build-id built) (:build-id %)) events))))))
      (finally (es/stop store)))))

(deftest public-workflow-and-scoped-source-queries
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx {:event-store store :tenant-id (random-uuid)}
        content "<urn:source> <http://www.w3.org/2000/01/rdf-schema#label> \"Imported\"@en ."
        config {:enable-colbert? false :enable-embeddings? false}]
    (try
      (let [result (evolutionary/build-from-sources ctx
                     {:sources [{:type "rdf" :content content}] :config config})
            oid (:ontology-id result)]
        (is (nil? (:cognitect.anomalies/category result)) (pr-str result))
        (is (uuid? oid))
        (is (= 1 (count (evolutionary/get-concepts ctx oid))))
        (is (= 1 (count (evolutionary/get-build-history ctx oid))))
        (is (:processed? (evolutionary/check-source-processed ctx oid nil content)))
        (is (empty? (evolutionary/get-all-sources (assoc ctx :tenant-id (random-uuid)) oid)))
        (is (empty? (evolutionary/get-all-sources ctx (random-uuid)))))
      (finally (es/stop store)))))

(deftest failed-source-workflow-retains-commits-and-records-failure
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx {:event-store store :tenant-id (random-uuid)}
        oid (:ontology-id (ontology/create-ontology! ctx {:name "Failure history"}))
        read-type #(into [] (es/read store {:tenant-id (:tenant-id ctx) :types #{%}}))]
    (try
      (with-redefs [builder/extract-from-source
                    (fn [_ source-id _ content _]
                      (is (= 1 (count (read-type :evolutionary/build-started)))
                          "Build start is durable before extraction runs")
                      (when (= content "broken") (throw (ex-info "Extraction failed" {})))
                      {:concepts [{:uri "urn:accepted" :label "Accepted" :definition "Committed"
                                   :source-id source-id}] :relationships []})]
        (let [result (evolutionary/evolve ctx {:ontology-id oid
                                             :sources [{:type "text" :content "accepted"}
                                                       {:type "text" :content "broken"}]
                                             :config {:enable-embeddings? false :enable-colbert? false}})]
          (is (some? (:cognitect.anomalies/category result)))
          (is (= 1 (count (ontology/get-contributions ctx oid))))
          (is (= "Accepted" (:label (ontology/get-concept-by-uri ctx oid "urn:accepted"))))
          (is (= 1 (count (read-type :evolutionary/build-failed))))
          (is (empty? (read-type :evolutionary/build-completed)))))
      (finally (es/stop store)))))
