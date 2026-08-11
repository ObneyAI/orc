(ns ai.obney.orc.orc-service.deterministic-ontology-e2e-test
  "Deterministic end-to-end coverage for ontology and self-learning flows."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.orc.colbert.interface]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.colbert.core.operations :as colbert-ops]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- command! [ctx command-name body]
  (cp/process-command
   (assoc ctx :command
          (merge {:command/name command-name
                  :command/id (random-uuid)
                  :command/timestamp (time/now)}
                 body))))

(defn- events-of-type [ctx event-type]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx) :types #{event-type}})))

(defn- derived-mint-id [name parent]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "mint:" name ":" parent) "UTF-8")))

(def description-body
  {:capabilities ["deterministic capability"]
   :strengths []
   :weaknesses []
   :representative-uses ["deterministic fixture"]
   :avoid-when ["never in fixture"]
   :summary "Deterministic ontology description."
   :version 1
   :consolidated-from-event-count 1})

(defn- index-stub [calls]
  (fn [_ctx opts]
    (swap! calls conj opts)
    (let [documents (vec (:collection opts))
          index-id (random-uuid)]
      {:index-id index-id
       :index-path (str "/tmp/det-e2e-index-" index-id)
       :num-passages (count documents)
       :duration-ms 1
       :document-ids (or (:document-ids opts) (mapv str (range (count documents))))
       :document-metadatas (or (:document-metadatas opts) [])
       :document-count (count documents)
       :model-name (or (:model-name opts) "colbert-ir/colbertv2.0")
       :index-name (:index-name opts)
       :config {:split-documents? true :max-document-length 256 :use-faiss? false}})))

(defn- inject-index-created! [ctx]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :colbert/index-created :tags #{}
                         :body {:index-id (random-uuid)
                                :index-name "ontology-descriptions"
                                :index-path "/tmp/det-e2e-existing"
                                :documents [] :document-ids []
                                :document-count 0 :passage-count 0
                                :model-name "colbert-ir/colbertv2.0"
                                :config {:split-documents? true
                                         :max-document-length 256
                                         :use-faiss? false}
                                :created-at "2026-08-06T00:00:00Z"}})]}))

(defn- record-description! [ctx target-id body]
  (command! ctx :ontology/record-node-type-description
            {:target-id target-id :body body}))

(defn- classify!
  ([ctx sheet-id tick-id class-id]
   (classify! ctx sheet-id tick-id class-id nil))
  ([ctx sheet-id tick-id class-id behavioral-subtrees]
   (command! ctx :ontology/assign-task-class
             (cond-> {:source-sheet-id sheet-id :source-tick-id tick-id
                      :source-node-id (random-uuid) :assigned-tree-id class-id
                      :confidence 0.95 :top-candidates [] :reasoning "deterministic"
                      :was-fresh-mint? false}
               behavioral-subtrees
               (assoc :behavioral-subtrees behavioral-subtrees)))))

(defn- score! [ctx sheet-id tick-id score]
  (command! ctx :evaluation/record-judge-score
            {:sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
             :judge-name "quality" :judge-config {} :score score
             :feedback "deterministic" :dimensions []}))

(defn- occurrence!
  ([ctx class-id sheet-id fingerprint score]
   (occurrence! ctx class-id sheet-id fingerprint score nil))
  ([ctx class-id sheet-id fingerprint score behavioral-subtrees]
   (let [tick-id (random-uuid)]
     (classify! ctx sheet-id tick-id class-id behavioral-subtrees)
     (score! ctx sheet-id tick-id score)
     (command! ctx :sheet/record-rlm-tree-execution-completion
               {:sheet-id sheet-id :tick-id tick-id :trajectory []
                :total-usage {:total-tokens 0} :tree-fingerprint fingerprint
                :status :success :duration-ms 1}))))

(deftest det-e2e-081-seed-bootstrap
  (testing "baseline bootstrap has stable identities and graph edges across reruns"
    (h/with-async-test-context [ctx]
      (let [{:keys [node-types tree-classes behavioral-subtrees
                    behavioral-subtree-children]} (ontology/baseline-seeds)
            expected (+ (count node-types) (* 2 (count tree-classes))
                        (count behavioral-subtrees)
                        (count behavioral-subtree-children))
            first-results (ontology/seed-baseline-corpus! ctx)
            child-ids (mapv #(derived-mint-id (:name %) (:parent-behavior %))
                            behavioral-subtree-children)]
        (is (= expected (count first-results)))
        (is (h/settle-until!
             #(every? (fn [[child id]]
                        (contains?
                         (:broader
                          (ontology/get-concept-by-uri
                           ctx (str "behavioral-subtree:" id)))
                         (str "behavioral-subtree:" (:parent-behavior child))))
                      (map vector behavioral-subtree-children child-ids))))
        (doseq [[child id] (map vector behavioral-subtree-children child-ids)]
          (let [concept (ontology/get-concept-by-uri
                         ctx (str "behavioral-subtree:" id))]
            (is (contains? (:broader concept)
                           (str "behavioral-subtree:" (:parent-behavior child))))))
        (let [second-results (ontology/seed-baseline-corpus! ctx)
              representative (first tree-classes)]
          (is (= expected (count second-results)))
          (is (h/settle-until!
               #(= 2 (count (ontology/get-description-history
                             ctx :tree-class (:target-id representative))))))
          (is (= (:body representative)
                 (ontology/get-description ctx :tree-class
                                           (:target-id representative))))
          (doseq [[child id] (map vector behavioral-subtree-children child-ids)]
            (is (= 2 (count (ontology/get-description-history
                             ctx :tree-fingerprint id))))))))))

(deftest det-e2e-082-record-strength-flow
  (testing "strength command reaches the event, profile, retrieval, and injected context"
    (h/with-async-test-context [ctx]
      (let [tree-id (random-uuid)
            trace-id (random-uuid)
            result (command! ctx :ontology/record-tree-strength
                             {:tree-id tree-id
                              :pattern-uri "success:CarefulExtraction"
                              :confidence 0.91
                              :evidence-trace-ids [trace-id]
                              :avg-score 0.88
                              :context-conditions {:document-kind "invoice"}
                              :action-taken {:type "validate" :reason "prevent drift"}
                              :domain-type "document-processing"
                              :expected-outcome "typed fields"})]
        (is (not (h/is-anomaly? result)))
        (is (= :ontology/tree-strength-recorded
               (:event/type (first (:command-result/events result)))))
        (is (h/settle-until! #(= 1 (count (:strengths
                                           (ontology/get-tree-profile ctx tree-id))))))
        (let [profile (ontology/get-tree-profile ctx tree-id)
              strength (first (:strengths profile))
              context (ontology/build-actionable-context
                       ctx tree-id "problem:InvoiceExtraction")]
          (is (= "success:CarefulExtraction" (:pattern strength)))
          (is (= {:document-kind "invoice"} (:context-conditions strength)))
          (is (= {:type "validate" :reason "prevent drift"}
                 (:action-taken strength)))
          (is (:has-patterns? context))
          (is (= 1 (:strength-count context)))
          (is (str/includes? (:formatted-context context) "validate"))
          (is (str/includes? (:formatted-context context) "document-kind=invoice")))))))

(deftest det-e2e-083-record-weakness-flow
  (testing "weakness command preserves trigger/context and failure classification through injection"
    (h/with-async-test-context [ctx]
      (let [tree-id (random-uuid)
            result (command! ctx :ontology/record-tree-weakness
                             {:tree-id tree-id
                              :failure-uri "failure:Grounding"
                              :subtype-uri "failure:UnsupportedClaim"
                              :frequency 0.75
                              :severity :high
                              :triggers ["missing citation" "ambiguous source"]
                              :evidence-trace-ids [(random-uuid)]
                              :failure-context {:source-count 0}
                              :attempted-action {:type "answer" :reason "rushed"}
                              :domain-type "research"})]
        (is (not (h/is-anomaly? result)))
        (is (= :ontology/tree-weakness-recorded
               (:event/type (first (:command-result/events result)))))
        (is (h/settle-until! #(= 1 (count (:weaknesses
                                           (ontology/get-tree-profile ctx tree-id))))))
        (let [weakness (first (:weaknesses (ontology/get-tree-profile ctx tree-id)))
              context (ontology/build-actionable-context ctx tree-id "problem:Research")]
          (is (= "failure:Grounding" (:failure weakness)))
          (is (= "failure:UnsupportedClaim" (:subtype weakness)))
          (is (= ["missing citation" "ambiguous source"] (:triggers weakness)))
          (is (= {:source-count 0} (:failure-context weakness)))
          (is (:has-patterns? context))
          (is (= 1 (:weakness-count context)))
          (is (str/includes? (:formatted-context context) "Patterns to Avoid"))
          (is (str/includes? (:formatted-context context) "75%")))))))

(deftest det-e2e-084-ontology-isolation
  (testing "the same concept URI remains independently queryable in two ontologies"
    (h/with-async-test-context [ctx]
      (let [ontology-a (random-uuid)
            ontology-b (random-uuid)
            uri "concept:SharedName"
            parent-uri "concept:SharedParent"]
        (doseq [[ontology-id name] [[ontology-a "Isolation A"]
                                    [ontology-b "Isolation B"]]]
          (let [created (ontology/create-ontology!
                         ctx {:command/id ontology-id
                              :name name
                              :scope :custom})]
            (is (= ontology-id (:ontology-id created)))))
        (command! ctx :ontology/create-concept
                  {:ontology-id ontology-a :uri uri :label "A"
                   :description "belongs to A" :scope :problem})
        (command! ctx :ontology/create-concept
                  {:ontology-id ontology-b :uri uri :label "B"
                   :description "belongs to B" :scope :problem})
        (doseq [ontology-id [ontology-a ontology-b]]
          (command! ctx :ontology/create-concept
                    {:ontology-id ontology-id :uri parent-uri
                     :label (str "parent-" ontology-id)
                     :description "scoped parent" :scope :problem}))
        (command! ctx :ontology/create-relationship
                  {:source-ontology-id ontology-a
                   :target-ontology-id ontology-a
                   :source-uri uri :target-uri parent-uri
                   :predicate "skos:broader"})
        (is (h/settle-until! #(= "belongs to B"
                                  (:description (ontology/get-concept-by-uri
                                                  ctx ontology-b uri)))))
        (is (nil? (ontology/get-concept-by-uri ctx uri))
            "an unscoped lookup must not guess when a URI is ambiguous")
        (is (= 4 (count (ontology/get-concepts ctx))))
        (is (= [{:ontology-id ontology-a :description "belongs to A"}]
               (mapv #(select-keys % [:ontology-id :description])
                     (filter #(= uri (:uri %))
                             (ontology/get-concepts ctx {:ontology-id ontology-a})))))
        (is (= [{:ontology-id ontology-b :description "belongs to B"}]
               (mapv #(select-keys % [:ontology-id :description])
                     (filter #(= uri (:uri %))
                             (ontology/get-concepts ctx {:ontology-id ontology-b})))))
        (is (h/settle-until!
             #(contains? (:broader (ontology/get-concept-by-uri ctx ontology-a uri))
                         parent-uri)))
        (is (not (contains? (:broader (ontology/get-concept-by-uri ctx ontology-b uri))
                            parent-uri))
            "a relationship in ontology A must not mutate ontology B")))))

(deftest det-e2e-085-string-uuid-normalization
  (testing "string and UUID ontology identifiers select one canonical identity"
    (h/with-async-test-context [ctx]
      (let [string-id "det-e2e-085"
            canonical (java.util.UUID/nameUUIDFromBytes (.getBytes string-id "UTF-8"))]
        (is (= canonical
               (:ontology-id
                (ontology/create-ontology!
                 ctx {:command/id canonical
                      :name "Canonical identity"
                      :scope :custom}))))
        (command! ctx :ontology/create-concept
                  {:ontology-id canonical :uri "concept:Canonical"
                   :label "canonical" :description "one identity" :scope :problem})
        (is (h/settle-until! #(some? (ontology/get-concept-by-uri
                                      ctx "concept:Canonical"))))
        (is (= (ontology/get-concepts ctx {:ontology-id canonical})
               (ontology/get-concepts ctx {:ontology-id string-id})))))))

(deftest det-e2e-086-concept-graph-export
  (testing "a seeded and extended graph exports stable valid Turtle relationships"
    (h/with-async-test-context [ctx]
      (let [ontology-id (random-uuid)]
        (is (= ontology-id
               (:ontology-id
                (ontology/create-ontology!
                 ctx {:command/id ontology-id
                      :name "Export graph"
                      :scope :custom}))))
        (command! ctx :ontology/create-concept
                  {:ontology-id ontology-id :uri "problem:Parent"
                   :label "Parent" :description "root" :scope :problem})
        (command! ctx :ontology/create-concept
                  {:ontology-id ontology-id :uri "problem:Child"
                   :label "Child" :description "leaf" :scope :problem
                   :broader ["problem:Parent"]})
        (is (h/settle-until! #(= 2 (count (ontology/get-concepts
                                           ctx {:ontology-id ontology-id})))))
        (let [first-export (ontology/export-turtle
                            ctx {:ontology-id ontology-id
                                 :include-profiles? false
                                 :include-experiences? false})
              second-export (ontology/export-turtle
                             ctx {:ontology-id ontology-id
                                  :include-profiles? false
                                  :include-experiences? false})]
          (is (= first-export second-export))
          (is (ontology/validate-turtle first-export))
          (is (str/includes? first-export "problem:Parent"))
          (is (str/includes? first-export "problem:Child"))
          (is (str/includes? first-export "skos:broader")))))))

(deftest det-e2e-087-behavioral-subtree-minting
  (testing "minting uses stable identity while retaining provenance audit events"
    (h/with-async-test-context [ctx]
      (let [name "deterministic-specialization"
            parent (random-uuid)
            target-id (derived-mint-id name parent)
            body (assoc description-body :parent-behavior parent)]
        (dotimes [_ 2]
          (let [result (command! ctx :ontology/mint-behavioral-subtree
                                 {:name name :parent-behavior parent
                                  :body body :provenance :human-authored})]
            (is (not (h/is-anomaly? result)))
            (is (= [:ontology/behavioral-subtree-minted
                    :ontology/tree-description-updated]
                   (mapv :event/type (:command-result/events result))))))
        (is (h/settle-until!
             #(and (= 2 (count (ontology/get-description-history
                                ctx :tree-fingerprint target-id)))
                    (contains? (:broader (ontology/get-concept-by-uri
                                          ctx (str "behavioral-subtree:" target-id)))
                               (str "behavioral-subtree:" parent)))))
        (let [audits (filterv #(= target-id (:target-id %))
                              (events-of-type ctx :ontology/behavioral-subtree-minted))
              concept (ontology/get-concept-by-uri
                       ctx (str "behavioral-subtree:" target-id))]
          (is (= 2 (count audits)))
          (is (= #{:human-authored} (set (map :provenance audits))))
          (is (= #{target-id} (set (map :target-id audits))))
          (is (= :behavioral-subtree
                 (:scope (ontology/get-description ctx :tree-fingerprint target-id))))
          (is (contains? (:broader concept)
                         (str "behavioral-subtree:" parent))))))))

(deftest det-e2e-088-description-consolidation-event-chain
  (testing "deterministic reflection replaces current text, increments version, and receives the prior body"
    (h/with-async-test-context [ctx]
      (let [prior (assoc description-body :summary "prior description" :version 3)
            reflected (assoc description-body :summary "reflected replacement")
            captured (atom [])]
        (record-description! ctx :code prior)
        (is (h/settle-until! #(= prior (ontology/get-description ctx :node-type :code))))
        (with-redefs [llm/predict
                      (fn [_ _ inputs _]
                        (swap! captured conj inputs)
                        {:outputs (select-keys reflected
                                              [:capabilities :strengths :weaknesses
                                               :representative-uses :avoid-when :summary])
                         :usage {:total-tokens 1} :model "deterministic"})]
          (command! ctx :ontology/request-consolidation
                    {:target-type :node-type :target-id :code :on-demand? true})
          (is (h/settle-until!
               #(= "reflected replacement"
                   (:summary (ontology/get-description ctx :node-type :code)))))
          (let [current (ontology/get-description ctx :node-type :code)
                history (ontology/get-description-history ctx :node-type :code)
                input (first @captured)]
            (is (= 4 (:version current)))
            (is (= 2 (count history)))
            (is (= ["prior description" "reflected replacement"]
                   (mapv (comp :summary :body) history)))
            (is (str/includes? (str (:current-description input))
                               "prior description"))))))))

(deftest det-e2e-089-reindex-threshold
  (testing "crossing the configured description threshold rebuilds exactly once and resets state"
    ;; Reindex latches are process-global and keyed by tenant. A unique tenant
    ;; prevents high-volume seed tests in the same JVM from owning this test's
    ;; steady-state latch while their asynchronous rebuild drains.
    (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid)}}]
      (inject-index-created! ctx)
      (is (h/settle-until! #(true? (:index-built? (ontology/get-reindex-state ctx)))))
      (is (= 10 (:reindex-threshold-events (ontology/get-reindex-config ctx))))
      (let [calls (atom [])]
        (with-redefs [colbert-ops/create-index! (index-stub calls)]
          (dotimes [i 9]
            (record-description! ctx (keyword (str "det-089-" i)) description-body))
          (is (h/settle-until! #(= 9 (:events-since-last-rebuild
                                      (ontology/get-reindex-state ctx)))))
          (is (empty? @calls))
          (record-description! ctx :det-089-crossing description-body)
          (is (h/settle-until! #(and (= 1 (count @calls))
                                     (= 0 (:events-since-last-rebuild
                                           (ontology/get-reindex-state ctx))))))
          (Thread/sleep 200)
          (is (= 1 (count @calls))))))))

(deftest det-e2e-090-immediate-mint-reindex
  (testing "one mint forces a rebuild below the ordinary event threshold"
    (h/with-async-test-context [ctx]
      (inject-index-created! ctx)
      (command! ctx :ontology/set-reindex-config
                {:reindex-threshold-events 10 :reindex-timer-minutes 60})
      (is (h/settle-until! #(true? (:index-built? (ontology/get-reindex-state ctx)))))
      (let [calls (atom [])
            name "det-e2e-090-mint"
            target-id (derived-mint-id name nil)]
        (with-redefs [colbert-ops/create-index! (index-stub calls)]
          (command! ctx :ontology/mint-behavioral-subtree
                    {:name name :body description-body :provenance :human-authored})
          (is (h/settle-until! #(= 1 (count @calls))))
          (is (< 1 10))
          (is (contains? (set (map :target-id
                                   (:document-metadatas (first @calls))))
                         target-id))
          (is (= 0 (:events-since-last-rebuild
                    (ontology/get-reindex-state ctx)))))))))

(deftest det-e2e-091-harvester-threshold-crossing
  (testing "real observations mint once at the gate and replay cannot harvest twice"
    (h/with-async-test-context [ctx]
      (let [class-id (random-uuid)
            parent-id (random-uuid)
            class-body (assoc description-body
                              :summary "coherent classify then extract"
                              :strengths [{:trait "route before extraction"
                                           :good-when "mixed documents"
                                           :recommended-pattern "[:sequence [:llm] [:map-each]]"
                                           :confidence 0.9 :evidence-count 10
                                           :first-observed-at "2026-08-01T00:00:00Z"
                                           :last-reinforced-at "2026-08-06T00:00:00Z"}]
                              :consolidated-from-event-count 10)]
        (command! ctx :ontology/record-tree-description
                  {:target-id parent-id
                   :body (assoc description-body :scope :behavioral-subtree)})
        (command! ctx :ontology/record-tree-class-description
                  {:target-id class-id :body class-body})
        (dotimes [i 9]
          (occurrence! ctx class-id (random-uuid) "one-shape" 0.9
                       (when (zero? i)
                         [{:behavior-id parent-id :confidence 0.9
                           :reasoning "deterministic anchor"}])))
        (Thread/sleep 200)
        (is (empty? (filter #(= class-id (:harvested-from-tree-class %))
                            (events-of-type ctx :ontology/behavioral-subtree-minted))))
        (occurrence! ctx class-id (random-uuid) "one-shape" 0.9)
        (is (h/settle-until!
             #(some? (ontology/get-tree-class-judge-averages ctx class-id))))
        (is (< (Math/abs (- 0.9
                            (get (ontology/get-tree-class-judge-averages ctx class-id)
                                 "quality")))
               1.0e-9))
        (harvest/maybe-harvest! ctx class-id)
        (is (h/settle-until!
             #(= 1 (count (filter (fn [event]
                                    (= class-id (:harvested-from-tree-class event)))
                                  (events-of-type ctx
                                                  :ontology/behavioral-subtree-minted))))))
        (harvest/maybe-harvest! ctx class-id)
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 200)
        (is (= 1 (count (filter #(= class-id (:harvested-from-tree-class %))
                                (events-of-type ctx
                                                :ontology/behavioral-subtree-minted)))))))))

(deftest det-e2e-092-cross-observation-baseline
  (testing "aggregate judge baselines retain all historical outcomes across observations"
    (h/with-async-test-context [ctx]
      (let [class-id (random-uuid)
            sheet-id (random-uuid)
            scores [0.2 0.6 1.0]]
        (doseq [score scores]
          (let [tick-id (random-uuid)]
            (classify! ctx sheet-id tick-id class-id)
            (score! ctx sheet-id tick-id score)))
        (is (h/settle-until!
             #(= {"quality" 0.6}
                 (ontology/get-tree-class-judge-averages ctx class-id))))
        (let [score-events (filter #(and (= sheet-id (:sheet-id %))
                                         (= "quality" (:judge-name %)))
                                  (events-of-type ctx :judge/score-emitted))]
          (is (= scores (mapv :score score-events)))
          (is (= 3 (count (set (map :tick-id score-events)))))
          (is (= class-id (ontology/get-tree-class-for-sheet ctx sheet-id))))))))
