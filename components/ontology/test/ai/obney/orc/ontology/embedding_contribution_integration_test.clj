(ns ai.obney.orc.ontology.embedding-contribution-integration-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.read-models :as rm]))

(defn command! [ctx name params]
  (cp/process-command (assoc ctx :command (merge {:command/name name :command/id (random-uuid)
                                                 :command/timestamp (time/now)} params))))

(deftest model-generation-persists-only-through-ontology-updates
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        ctx {:event-store store :tenant-id (random-uuid)}]
    (try
      (let [a (:ontology-id (ontology/create-ontology! ctx {:name "A"}))
            b (:ontology-id (ontology/create-ontology! ctx {:name "B"}))]
        (doseq [oid [a b]]
          (ontology/create-concept! ctx oid {:uri "urn:shared" :label "Shared"
                                            :description "Shared concept" :scope :custom}))
        (with-redefs [embedding/embed-text (constantly [0.25 0.75])]
          (let [r (command! ctx :ontology/embed-concepts-batch {:ontology-id a :scope :custom})]
            (is (= 1 (get-in r [:command-result/data :embedded-count])) (pr-str r))
            (is (nil? (:embedding (ontology/get-concept-by-uri ctx b "urn:shared")))))
          (let [r (command! ctx :ontology/embed-concept {:ontology-id b :uri "urn:shared"})]
            (is (= 2 (get-in r [:command-result/data :dimensions])) (pr-str r)))
          (is (= [0.25 0.75] (:embedding (ontology/get-concept-by-uri ctx a "urn:shared"))))
          (is (= embedding/default-model-id (:embedding-model (ontology/get-concept-by-uri ctx a "urn:shared"))))
          (is (= #{[a "urn:shared"] [b "urn:shared"]} (set (keys (rm/get-all-concept-embeddings ctx)))))
          (is (nil? (rm/get-concept-embedding ctx "urn:shared")))
          (is (= [0.25 0.75] (:embedding (rm/get-concept-embedding ctx b "urn:shared"))))
          (is (empty? (rm/get-all-concept-embeddings (assoc ctx :tenant-id (random-uuid)))))
          (is (empty? (into [] (es/read store {:tenant-id (:tenant-id ctx)
                                              :types #{:ontology/concept-embedded}}))))))
      (finally (es/stop store)))))
