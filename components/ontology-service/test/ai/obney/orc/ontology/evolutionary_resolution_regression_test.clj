(ns ai.obney.orc.ontology.evolutionary-resolution-regression-test
  (:require [ai.obney.ontology.core.graph-evolver :as graph-evolver]
            [ai.obney.ontology.core.entity-resolver :as resolver]
            [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.test :refer [deftest is testing]]))

(deftest aliases-and-existing-identities-participate-in-resolution
  (testing "an extracted alias resolves a renamed concept across sources"
    (let [source-a (random-uuid)
          source-b (random-uuid)
          a {:uri "urn:a" :label "Priority Queue" :alt-labels []
             :entity-type "Component" :source-id source-a}
          b {:uri "urn:b" :label "Sentinel work queue"
             :alt-labels ["Priority Queue"]
             :entity-type "Component" :source-id source-b}
          batch (resolver/resolve-within-batch [a b] {})
          different-types (resolver/resolve-within-batch [a (assoc b :entity-type "Queue")] {})]
      (is (= 1 (:exact-matches batch)))
      (is (= #{"urn:a" "urn:b"} (set (keys (:canonical-map batch)))))
      (is (zero? (:exact-matches different-types)))
      (is (empty? (:canonical-map different-types)))))

  (testing "incremental matching retains the real existing URI and type"
    (let [existing {:uri "urn:existing" :label "Sentinel Queue"
                    :alt-labels ["Priority Queue"] :entity-type "Component"
                    :source-id (random-uuid)}
          new {:uri "urn:new" :label "Sentinel work queue"
               :alt-labels ["Priority Queue"] :entity-type "Component"
               :source-id (random-uuid)}
          result (resolver/resolve-incremental
                  [new] [existing] #{"urn:existing"} {})]
      (is (= 1 (:exact-matches result)))
      (is (= "urn:existing" (get (:canonical-map result) "urn:new"))))))

(deftest ttl-snapshots-are-content-stable-and-literal-colons-are-not-prefixes
  (let [fixture {:concepts {"urn:queue" {:uri "urn:queue"
                                          :label "Queue: priority"
                                          :entity-type "Component"}}
                 :relationships []}
        first-snapshot (graph-evolver/generate-ttl-snapshot fixture {})
        second-snapshot (graph-evolver/generate-ttl-snapshot fixture {})
        owl-first (graph-evolver/generate-owl-ttl-snapshot fixture {})
        owl-second (graph-evolver/generate-owl-ttl-snapshot fixture {})]
    (is (= (:ttl-string first-snapshot) (:ttl-string second-snapshot)))
    (is (= (:ttl-string owl-first) (:ttl-string owl-second)))
    (is (:valid? (logic/validate-turtle (:ttl-string first-snapshot))))
    (is (:valid? (logic/validate-turtle (:ttl-string owl-first))))))
