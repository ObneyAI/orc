(ns ai.obney.orc.ontology.custom-ontology-lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.evolutionary :as evolutionary]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn anomaly? [value]
  (some? (:cognitect.anomalies/category value)))

(deftest custom-ontology-lifecycle-is-projected-and-validated
  (h/with-test-context [ctx]
    (let [created (ontology/create-ontology!
                   ctx {:name "Sormo principal ontology"
                        :scope :custom
                        :description "Persistent principal concepts"
                        :base-uri "urn:sormo:principal:"})
          ontology-id (:ontology-id created)]
      (is (uuid? ontology-id))
      (is (= {:ontology-id ontology-id
              :name "Sormo principal ontology"
              :scope :custom
              :description "Persistent principal concepts"
              :base-uri "urn:sormo:principal:"
              :created-at (:created-at (ontology/get-ontology ctx ontology-id))}
             (ontology/get-ontology ctx ontology-id)))
      (is (ontology/ontology-exists? ctx ontology-id))
      (is (= [ontology-id] (mapv :ontology-id (ontology/list-ontologies ctx))))

      (let [parent (ontology/create-concept!
                    ctx ontology-id
                    {:uri "urn:sormo:communication"
                     :label "Communication"
                     :description "Communication preferences"
                     :scope :custom
                     :provenance {:kind :human-authored
                                  :created-by "principal:test"}})
            child (ontology/create-concept!
                   ctx ontology-id
                   {:uri "urn:sormo:concise-updates"
                    :label "Concise updates"
                    :description "A preference for concise updates"
                    :scope :custom
                    :broader ["urn:sormo:communication"]
                    :provenance {:kind :agent-authored
                                 :source-reference "sormo-source:test"}})]
        (is (= ontology-id (:ontology-id parent)))
        (is (uuid? (:concept-id parent)))
        (is (= :agent-authored
               (get-in (ontology/get-concept-by-uri
                        ctx ontology-id "urn:sormo:concise-updates")
                       [:provenance :kind])))
        (is (anomaly? (ontology/create-concept!
                       ctx ontology-id
                       {:uri "urn:sormo:concise-updates"
                        :label "Duplicate"
                        :description "Must be rejected"
                        :scope :custom})))

        ;; A command after projection-cache loss must validate against replayed
        ;; state and retain the original concept identity.
        (ai.obney.grain.read-model-processor-v2.interface/l1-clear!)
        (let [updated (ontology/update-concept!
                       ctx ontology-id (:concept-id child)
                       {:description "An evolved preference for concise updates"})]
          (is (= (:concept-id child) (:concept-id updated)))
          (is (= "An evolved preference for concise updates"
                 (:description (ontology/get-concept-by-uri
                                ctx ontology-id "urn:sormo:concise-updates")))))

        (let [relationship (ontology/create-relationship!
                            ctx {:source-ontology-id ontology-id
                                 :target-ontology-id ontology-id
                                 :source-uri "urn:sormo:concise-updates"
                                 :target-uri "urn:sormo:communication"
                                 :predicate "skos:related"})]
          (is (uuid? (:relationship-id relationship)) (pr-str relationship))
          (is (anomaly? (ontology/create-relationship!
                         ctx {:source-ontology-id ontology-id
                              :target-ontology-id ontology-id
                              :source-uri "urn:sormo:concise-updates"
                              :target-uri "urn:sormo:communication"
                              :predicate "skos:related"}))))))))

(deftest custom-ontology-lifecycle-is-tenant-isolated-and-replayable
  (h/with-test-context [base]
    (let [shared-id (random-uuid)
          ctx-a (assoc base :tenant-id (random-uuid))
          ctx-b (assoc base :tenant-id (random-uuid))
          _ (ontology/create-ontology! ctx-a
                                       {:command/id shared-id
                                        :name "Tenant A"
                                        :scope :custom})]
      (is (= "Tenant A" (:name (ontology/get-ontology ctx-a shared-id))))
      (is (nil? (ontology/get-ontology ctx-b shared-id)))
      (is (anomaly? (ontology/create-concept!
                     ctx-b shared-id
                     {:uri "urn:sormo:isolated"
                      :label "Isolated"
                      :description "Cannot cross tenants"
                      :scope :custom})))
      ;; Clear the process-local projection cache and prove reconstruction.
      (ai.obney.grain.read-model-processor-v2.interface/l1-clear!)
      (is (= "Tenant A" (:name (ontology/get-ontology ctx-a shared-id)))))))

(deftest public-create-is-idempotent-for-the-same-command-id
  (h/with-test-context [ctx]
    (let [command-id (random-uuid)
          params {:command/id command-id
                  :name "Retry-safe ontology"
                  :scope :custom
                  :base-uri "urn:retry:"}
          first-result (ontology/create-ontology! ctx params)
          retry-result (ontology/create-ontology! ctx params)
          events (into [] (es/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:ontology/ontology-created}}))]
      (is (= (:ontology-id first-result) (:ontology-id retry-result)))
      (is (= 1 (count events))))))

(deftest colliding-tenant-and-ontology-identities-remain-scoped
  (h/with-test-context [base]
    (let [ctx-a (assoc base :tenant-id (random-uuid))
          ctx-b (assoc base :tenant-id (random-uuid))
          ontology-a (:ontology-id (ontology/create-ontology!
                                    ctx-a {:name "Same" :scope :custom
                                           :base-uri "urn:same:"}))
          ontology-b (:ontology-id (ontology/create-ontology!
                                    ctx-b {:name "Same" :scope :custom
                                           :base-uri "urn:same:"}))
          concept-a (ontology/create-concept!
                     ctx-a ontology-a
                     {:uri "urn:same:concept" :label "Tenant A"
                      :description "A" :scope :custom})
          _concept-b (ontology/create-concept!
                      ctx-b ontology-b
                      {:uri "urn:same:concept" :label "Tenant B"
                       :description "B" :scope :custom})]
      (is (= "Tenant A" (:label (ontology/get-concept-by-uri
                                  ctx-a ontology-a "urn:same:concept"))))
      (is (= "Tenant B" (:label (ontology/get-concept-by-uri
                                  ctx-b ontology-b "urn:same:concept"))))
      (is (nil? (ontology/get-ontology ctx-a ontology-b)))
      (is (anomaly? (ontology/update-concept!
                     ctx-b ontology-a (:concept-id concept-a) {:label "stolen"})))
      (is (anomaly? (ontology/create-relationship!
                     ctx-a {:source-ontology-id ontology-a
                            :target-ontology-id ontology-b
                            :source-uri "urn:same:concept"
                            :target-uri "urn:same:concept"
                            :predicate "behavior:composes-into"}))))))

(deftest same-tenant-uri-collision-is-unambiguous-only-when-scoped
  (h/with-test-context [ctx]
    (let [one (:ontology-id (ontology/create-ontology!
                             ctx {:name "One" :scope :custom}))
          two (:ontology-id (ontology/create-ontology!
                             ctx {:name "Two" :scope :custom}))]
      (ontology/create-concept! ctx one
                                {:uri "urn:collision" :label "One"
                                 :description "One" :scope :custom})
      (ontology/create-concept! ctx two
                                {:uri "urn:collision" :label "Two"
                                 :description "Two" :scope :custom})
      (is (nil? (ontology/get-concept-by-uri ctx "urn:collision")))
      (is (= "One" (:label (ontology/get-concept-by-uri ctx one "urn:collision"))))
      (is (= "Two" (:label (ontology/get-concept-by-uri ctx two "urn:collision")))))))

(deftest manually-created-ontology-can-enter-the-evolutionary-lifecycle
  (h/with-test-context [ctx]
    (let [ontology-id (:ontology-id
                       (ontology/create-ontology!
                        ctx {:name "Manual then evolved"
                             :scope :custom
                             :base-uri "urn:manual:"}))
          manual (ontology/create-concept!
                  ctx ontology-id
                  {:uri "urn:manual:canonical"
                   :label "Canonical manual concept"
                   :description "Must keep its stable identity"
                   :scope :custom
                   :provenance {:kind :human-authored}})
          evolved (evolutionary/evolve ctx {:ontology-id ontology-id
                                             :sources []})
          projected (ontology/get-concept-by-uri
                     ctx ontology-id "urn:manual:canonical")]
      (is (not (anomaly? evolved)) (pr-str evolved))
      (is (= (:concept-id manual) (:id projected)))
      (is (= :human-authored (get-in projected [:provenance :kind]))))))

(deftest registered-rdf-source-evolves-the-manual-graph
  (h/with-test-context [ctx]
    (let [ontology-id (:ontology-id
                       (ontology/create-ontology!
                        ctx {:name "Sormo source evolution"
                             :scope :custom
                             :base-uri "urn:sormo:"}))
          manual (ontology/create-concept!
                  ctx ontology-id
                  {:uri "urn:sormo:canonical"
                   :label "Principal canonical concept"
                   :description "Manual identity wins URI resolution"
                   :scope :custom
                   :provenance {:kind :human-authored
                                :created-by "principal:test"}})
          rdf (str "<urn:sormo:canonical> "
                   "<http://www.w3.org/2000/01/rdf-schema#label> "
                   "\"Extracted duplicate label\" .\n"
                   "<urn:sormo:imported> "
                   "<http://www.w3.org/2000/01/rdf-schema#label> "
                   "\"Imported concept\" .\n"
                   "<urn:sormo:imported> "
                   "<http://www.w3.org/2004/02/skos/core#definition> "
                   "\"Imported description\" .\n"
                   "<urn:sormo:imported> "
                   "<http://www.w3.org/2004/02/skos/core#broader> "
                   "<urn:sormo:canonical> .\n")
          evolved (evolutionary/evolve
                   ctx {:ontology-id ontology-id
                        :sources [{:path "memory://sormo-concepts.nt"
                                   :type "rdf"
                                   :content rdf}]})
          manual-after (ontology/get-concept-by-uri
                        ctx ontology-id "urn:sormo:canonical")
          imported (ontology/get-concept-by-uri
                    ctx ontology-id "urn:sormo:imported")]
      (is (not (anomaly? evolved)) (pr-str evolved))
      (is (= (:concept-id manual) (:id manual-after)))
      (is (= "Principal canonical concept" (:label manual-after)))
      (is (= :human-authored (get-in manual-after [:provenance :kind])))
      (is (uuid? (:id imported)))
      (is (= "Imported concept" (:label imported)))
      (is (= "Imported description" (:description imported)))
      (is (= :source-extracted (get-in imported [:provenance :kind])))
      (is (string? (get-in imported [:provenance :source-reference])))
      (is (= #{"urn:sormo:canonical"} (:broader imported)))
      (let [before-replay {:manual manual-after :imported imported}]
        (ai.obney.grain.read-model-processor-v2.interface/l1-clear!)
        (is (= before-replay
               {:manual (ontology/get-concept-by-uri
                         ctx ontology-id "urn:sormo:canonical")
                :imported (ontology/get-concept-by-uri
                           ctx ontology-id "urn:sormo:imported")}))))))

(deftest concept-update-and-relationship-retries-are-idempotent
  (h/with-test-context [ctx]
    (let [ontology-id (:ontology-id
                       (ontology/create-ontology! ctx {:name "Retries" :scope :custom}))
          parent (ontology/create-concept!
                  ctx ontology-id
                  {:uri "urn:retry:parent" :label "Parent"
                   :description "Parent" :scope :custom})
          concept-command-id (random-uuid)
          concept-params {:command/id concept-command-id
                          :uri "urn:retry:child" :label "Child"
                          :description "Child" :scope :custom
                          :broader ["urn:retry:parent"]}
          child-1 (ontology/create-concept! ctx ontology-id concept-params)
          child-2 (ontology/create-concept! ctx ontology-id concept-params)
          update-id (random-uuid)
          update-1 (ontology/update-concept!
                    ctx ontology-id (:concept-id child-1)
                    {:label "Updated child"} {:command/id update-id})
          update-2 (ontology/update-concept!
                    ctx ontology-id (:concept-id child-1)
                    {:label "Updated child"} {:command/id update-id})
          relationship-id (random-uuid)
          edge {:command/id relationship-id
                :source-ontology-id ontology-id
                :target-ontology-id ontology-id
                :source-uri "urn:retry:child"
                :target-uri "urn:retry:parent"
                :predicate "skos:related"}
          edge-1 (ontology/create-relationship! ctx edge)
          edge-2 (ontology/create-relationship! ctx edge)
          events (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))]
      (is (= concept-command-id (:concept-id child-1) (:concept-id child-2)))
      (is (= (:concept-id update-1) (:concept-id update-2)))
      (is (= relationship-id (:relationship-id edge-1) (:relationship-id edge-2)))
      (is (= 1 (count (filter #(and (= :ontology/concept-created (:event/type %))
                                    (= concept-command-id (:concept-id %)))
                              events))))
      (is (= 1 (count (filter #(= update-id (:update-id %)) events))))
      (is (= 1 (count (filter #(= relationship-id (:relationship-id %)) events))))
      (is (= (:concept-id parent)
             (:id (ontology/get-concept-by-uri ctx ontology-id "urn:retry:parent")))))))

(deftest invalid-graph-mutations-are-rejected
  (h/with-test-context [ctx]
    (let [ontology-id (:ontology-id
                       (ontology/create-ontology! ctx {:name "Validation" :scope :custom}))
          one (ontology/create-concept!
               ctx ontology-id {:uri "urn:one" :label "One"
                                :description "One" :scope :custom})
          two (ontology/create-concept!
               ctx ontology-id {:uri "urn:two" :label "Two"
                                :description "Two" :scope :custom
                                :broader ["urn:one"]})]
      (is (anomaly? (ontology/create-concept!
                     ctx ontology-id {:uri " " :label "Bad"
                                      :description "Bad" :scope :custom})))
      (is (anomaly? (ontology/create-concept!
                     ctx ontology-id {:uri "urn:orphan" :label "Orphan"
                                      :description "Orphan" :scope :custom
                                      :broader ["urn:missing"]})))
      (is (anomaly? (ontology/update-concept!
                     ctx ontology-id (:concept-id one) {})))
      (is (anomaly? (ontology/update-concept!
                     ctx ontology-id (:concept-id one)
                     {:broader ["urn:two"]})))
      (is (anomaly? (ontology/create-relationship!
                     ctx {:source-ontology-id ontology-id
                          :target-ontology-id ontology-id
                          :source-uri "urn:one" :target-uri "urn:one"
                          :predicate "skos:related"})))
      (is (anomaly? (ontology/create-relationship!
                     ctx {:source-ontology-id ontology-id
                          :target-ontology-id ontology-id
                          :source-uri "urn:one" :target-uri "urn:missing"
                          :predicate "skos:related"})))
      (is (anomaly? (ontology/create-relationship!
                     ctx {:source-ontology-id ontology-id
                          :target-ontology-id ontology-id
                          :source-uri "urn:one" :target-uri "urn:two"
                          :predicate "unsupported"})))
      (is (= (:concept-id two)
             (:id (ontology/get-concept-by-uri ctx ontology-id "urn:two")))))))
