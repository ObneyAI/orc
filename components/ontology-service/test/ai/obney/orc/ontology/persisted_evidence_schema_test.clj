(ns ai.obney.orc.ontology.persisted-evidence-schema-test
  (:require [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.orc.ontology.interface.schemas]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

(defn- valid? [name body]
  (m/validate (get @schema-util/registry* name) body))

(deftest graph-scopes-on-orc-operations
  (let [id (random-uuid)]
    (doseq [name [:ontology/get-concepts :ontology/embed-concepts-batch]]
      (is (valid? name {:ontology-ids [id]}))
      (is (valid? name {:ontology-ids #{id}}))
      (is (not (valid? name {:ontology-ids ["unresolved"]}))))
    (doseq [name [:ontology/embed-concept :ontology/get-concept-embedding]]
      (is (valid? name {:uri "urn:concept" :ontology-id id}))
      (is (not (valid? name {:uri "urn:concept" :ontology-id "unresolved"}))))))

(deftest recording-domain-observations
  (let [conditions {:region {"floor" 2 :zones #{:west :east}} :battery 90}
        body {:tree-id (random-uuid) :pattern-uri "success:Stabilize"
              :confidence 0.95 :evidence-trace-ids [] :avg-score 0.9
              :recorded-at "2026-03-01T00:00:00Z"
              :action-taken {:type "stabilize" :target [0 0 1.0]}}]
    (doseq [name [:ontology/record-tree-strength :ontology/tree-strength-recorded]
            field [:context-conditions :state-conditions]]
      (is (valid? name (assoc body field conditions)))
      (is (not (valid? name (assoc-in body [field :region] {:handler identity}))))
      (is (not (valid? name (assoc-in body [:action-taken :target] (Object.))))))))

(deftest persisted-rule-conditions-and-actions
  (let [body {:rule-id (random-uuid) :tree-id (random-uuid) :problem-type "routing"
              :extracted-at "2026-03-01T00:00:00Z"
              :rule {:condition {:battery ["between" 10 50] :region :west}
                     :action {:type :return :target "base" :reason "low charge"}
                     :confidence 0.9 :success-rate 0.8 :evidence-episodes []}}]
    (is (valid? :ontology/learned-rule-extracted body))
    (is (not (valid? :ontology/learned-rule-extracted
                     (assoc-in body [:rule :condition :battery] {:unsupported 1}))))
    (is (not (valid? :ontology/learned-rule-extracted
                     (assoc-in body [:rule :action :reason] 123))))))

(deftest build-started-records-typed-config-and-extension-data
  (let [id (random-uuid)
        body {:build-id (random-uuid) :ontology-id id :mode "batch" :source-count 1
              :started-at "2026-03-01T00:00:00Z"
              :config {:ontology-id id :base-uri "urn:source:" :similarity-threshold 0.85
                       :enable-colbert? true :colbert-index-name nil
                       :colbert-fields [:description] :embedding-fields nil
                       :source-options {:labels ["title" "body"]}}}]
    (is (valid? :evolutionary/build-started body))
    (is (not (valid? :evolutionary/build-started
                     (assoc-in body [:config :enable-colbert?] "true"))))
    (is (not (valid? :evolutionary/build-started
                     (assoc-in body [:config :colbert-fields] "description"))))
    (is (not (valid? :evolutionary/build-started
                     (assoc-in body [:config :source-options :loader] identity))))))

(deftest anti-recency-target-identities
  (let [body {:bucket :strengths :entry-trait "Grounded" :prior-confidence 0.9
              :llm-confidence 0.5 :clamped-confidence 0.85 :reason :prior-evidence
              :detected-at "2026-03-01T00:00:00Z"}]
    (doseq [[kind id] [[:node-type :llm] [:node-instance [(random-uuid) (random-uuid)]]
                       [:tree-fingerprint "3b8c"] [:tree-class (random-uuid)]]]
      (is (valid? :ontology/anti-recency-clamp-applied
                  (assoc body :target-type kind :target-id id))))
    (is (not (valid? :ontology/anti-recency-clamp-applied
                     (assoc body :target-type :node-instance :target-id [(random-uuid)]))))))

(deftest contribution-source-and-extracted-properties
  (let [source-id (random-uuid)
        source {:source-id source-id :content-hash "abc"
                :producer {:application "orc" :build-reference (str (random-uuid))}}
        contribution {:ontology-id (random-uuid) :contribution-id (random-uuid)
                      :source source :concepts []}
        extracted {:source-id source-id :ontology-id (random-uuid)
                   :individuals [{:uri "urn:invoice:12" :type "invoice" :label "Invoice 12"
                                  :properties {"total" 17.50M :lines [{"sku" "A1"}]}}]
                   :extracted-at "2026-03-01T00:00:00Z"}]
    (doseq [name [:ai.obney.ontology.core.api-schemas/contribution-request
                  :ontology/import-contribution]]
      (is (valid? name contribution))
      (is (not (valid? name (assoc-in contribution [:source :producer] identity))))
      (is (not (valid? name (assoc-in contribution [:source :content-hash] 42)))))
    (is (valid? :evolutionary/abox-extracted extracted))
    (is (not (valid? :evolutionary/abox-extracted
                     (assoc-in extracted [:individuals 0 :properties "total"] (Object.)))))))

(deftest site-patterns-carry-structured-evidence
  (let [body {:site-id (random-uuid) :domain "example.org" :pattern-type :pagination
              :pattern-data {:selector "a[rel=next]" :stop {:seen-ids ["a" "b"]}}
              :confidence 0.9 :learned-at "2026-03-01T00:00:00Z"}]
    (doseq [name [:site/record-site-pattern :site/pattern-learned]]
      (is (valid? name body))
      (is (not (valid? name (assoc-in body [:pattern-data :stop :handler] identity)))))))
