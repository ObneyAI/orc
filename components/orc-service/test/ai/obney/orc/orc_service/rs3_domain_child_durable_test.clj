(ns ai.obney.orc.orc-service.rs3-domain-child-durable-test
  "RS-3 cycles 4-6 — what RS-2 decides in memory becomes durable at
   classification time, through the LIVE wedge (`maybe-auto-classify-and-
   set-context`), real grain throughout (real in-memory event store, real
   command dispatch, real read-models) — never a command's return value; every
   assertion reads the event store / read-models back.

   The rerank seam is stubbed at `ontology/search-descriptions` (the fully-
   joined typed payload classify-task consumes — RS-1's :domain-coverage/
   :domain-label/:domain-reasoning already joined onto the candidate, exactly
   as rs2_domain_child_classifier_test's `tree-class-candidate` helper shapes
   it) so the REAL classify-task, including RS-2's `assign-domain-child`
   logic, runs; `task-classifier/get-consolidation-total*` is stubbed to 0 so
   the retrieval gate never filters. Seam 3 (RS-3 issue): mirrors cc6_cv1_
   claim_capture_test / el3_wedge_skip_uncertain_test / cc23_classification_
   observability_test's exact sync test-context pattern, plus the ontology
   projectors (read via `ontology/get-narrower-concepts` etc. — pull-based,
   no processor thread required — see RS-P2's probe)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface.schemas :as sheet-schemas]
            [malli.core :as m]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test context — mirrors cc6/el3/cc23's exact sync pattern
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rs3-durable-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id (random-uuid)
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :sheet-id (random-uuid)
     :tick-id (random-uuid)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- events-of [ctx type]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :types #{type}})))

(defn- node []
  {:id (random-uuid)
   :name "rs3-durable-node"
   :type :repl-researcher
   :instruction "produce a 16-week marathon training plan"
   :reads [] :writes []
   :rlm {:auto-classify? true}})

(defn- tree-class-uri [id] (str "tree-class:" id))

;; =============================================================================
;; Typed :tree-class candidate — RS-1's domain verdict already joined, exactly
;; as rs2_domain_child_classifier_test's `tree-class-candidate` shapes it.
;; =============================================================================

(defn- tree-class-candidate
  [id fitness & {:keys [domain-coverage domain-label domain-reasoning]}]
  {:content "x" :score fitness :rank 1
   :document-id (str id)
   :document-metadata {:granularity :tree-class :target-id (str id) :confidence 1.0}
   :reasoning "principle-shaped fit"
   :fitness-score fitness
   :rerank-source :reranker
   :domain-coverage domain-coverage
   :domain-label domain-label
   :domain-reasoning domain-reasoning})

(defmacro with-domain-candidate
  "Stub the retrieval seam so classify-task's top-1 :tree-class candidate
   carries the given RS-1 domain verdict, and the retrieval gate never
   filters (get-consolidation-total* -> 0)."
  [candidate & body]
  `(with-redefs [ontology/search-descriptions (fn [_# _#] [~candidate])
                 tc/get-consolidation-total* (fn [_# _# _#] 0)]
     ~@body))

;; =============================================================================
;; CYCLE 4 — a :tree-class match with no children and a :partial verdict
;; mints a domain child, durably, through the live wedge.
;; =============================================================================

(deftest partial-verdict-no-children-mints-domain-child-durably
  (testing "after the wedge runs: a classified event carrying :assigned-via
            :mint-domain-child, the parent, the label, the verdict and the
            considered labels; a :representative-use claim on the child; and
            the SKOS edge from child to parent"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            candidate (tree-class-candidate class-id 0.95
                        :domain-coverage :partial
                        :domain-label "Marathon Training Plan"
                        :domain-reasoning "Shares subject matter but not the output kind.")]
        (with-domain-candidate candidate
          (tp/maybe-auto-classify-and-set-context (node) ctx))
        (let [classified (events-of ctx :ontology/task-classified)
              minted (events-of ctx :ontology/domain-child-minted)
              event (first classified)
              mint-event (first minted)
              child-id (:assigned-tree-id event)]
          (testing "the classified event"
            (is (= 1 (count classified)) "exactly one classification event")
            (is (= :matched (:outcome event))
                "RS-6: the three-state outcome is on the event beside the provenance")
            (is (= :mint-domain-child (:assigned-via event)))
            (is (= class-id (:parent-tree-id event)))
            (is (= "marathon-training-plan" (:domain-label event))
                "canonicalised label")
            (is (= {:domain-coverage :partial
                    :domain-label "Marathon Training Plan"
                    :domain-reasoning "Shares subject matter but not the output kind."}
                   (:domain-verdict event))
                "the RAW verdict is carried")
            (is (= [] (:domain-children-considered event)))
            (is (true? (:was-fresh-mint? event))))
          (testing "the mint event"
            (is (= 1 (count minted)) "exactly one domain-child-minted event")
            (is (= class-id (:parent-tree-id mint-event)))
            (is (= child-id (:child-tree-id mint-event)))
            (is (= "marathon-training-plan" (:domain-label mint-event))))
          (testing "the claim"
            (let [claims (ontology/get-claims ctx :tree-class child-id)]
              (is (= 1 (count claims)) "exactly one claim captured for the child")
              (is (= :representative-use (:kind (first claims))))))
          (testing "the parent's narrower set (the SKOS edge)"
            (is (contains? (ontology/get-narrower-concepts ctx (tree-class-uri class-id))
                           (tree-class-uri child-id))
                "the child is reachable as a child of its parent"))
          (testing "the child concept carries the judged label from birth"
            (is (= "marathon-training-plan"
                   (:label (ontology/get-concept-by-uri ctx (tree-class-uri child-id)))))))))))

;; =============================================================================
;; CYCLE 5 — the SAME task again lands on the SAME child (LandOnDomainChild),
;; no second mint, no second claim (DomainChildIdentityIsStable durably
;; proven).
;; =============================================================================

(deftest same-task-again-lands-on-existing-child-no-second-mint
  (testing "recurrence: the same task classified again yields
            :land-on-domain-child with the SAME child identity; no second
            :domain-child-minted event, no second claim"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            candidate (tree-class-candidate class-id 0.95
                        :domain-coverage :partial
                        :domain-label "Marathon Training Plan"
                        :domain-reasoning "Shares subject matter but not the output kind.")
            n1 (node)]
        (with-domain-candidate candidate
          (tp/maybe-auto-classify-and-set-context n1 ctx))
        (let [first-child-id (:assigned-tree-id (first (events-of ctx :ontology/task-classified)))
              n2 (assoc (node) :id (random-uuid))
              tick-2-ctx (assoc ctx :tick-id (random-uuid))]
          ;; A SECOND child under the SAME parent with the SAME judged label —
          ;; once a class has children, the coverage verdict is not consulted
          ;; (D7b); the judged label decides. Same label -> lands on the
          ;; existing child.
          (with-domain-candidate candidate
            (tp/maybe-auto-classify-and-set-context n2 tick-2-ctx))
          (let [classified (events-of ctx :ontology/task-classified)
                minted (events-of ctx :ontology/domain-child-minted)
                second-event (some #(= (:id n2) (:source-node-id %)) classified)
                second-classified (first (filter #(= (:id n2) (:source-node-id %)) classified))]
            (is (= 2 (count classified)) "two classification events, one per node")
            (is (= 1 (count minted)) "still exactly ONE mint event — no second mint")
            (is (= :land-on-domain-child (:assigned-via second-classified)))
            (is (= first-child-id (:assigned-tree-id second-classified))
                "the SAME child identity — DomainChildIdentityIsStable")
            (is (= "marathon-training-plan" (:domain-label second-classified))
                "a landing records the landed child's label")
            (is (false? (:was-fresh-mint? second-classified)))
            (is (= 1 (count (ontology/get-claims ctx :tree-class first-child-id)))
                "still exactly one claim on the child — no second capture")))))))

;; =============================================================================
;; CYCLE 5b (inspection) — a DIFFERENT judged label under a parent that
;; already has a child mints a SIBLING (MintSiblingDomainChild): a second
;; mint event, a second edge, a second claim; the first child untouched.
;; =============================================================================

(deftest different-label-under-a-parent-with-a-child-mints-a-sibling
  (testing "with one child present the verdict is not consulted (D7b); a new
            judged label mints a sibling under the SAME parent, durably: two
            narrower concepts, two mint events, one claim per child"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            first-candidate (tree-class-candidate class-id 0.95
                              :domain-coverage :partial
                              :domain-label "Marathon Training Plan"
                              :domain-reasoning "Shares subject matter but not the output kind.")
            ;; A `covered` verdict — irrelevant once a child exists; the
            ;; label decides.
            second-candidate (tree-class-candidate class-id 0.95
                               :domain-coverage :covered
                               :domain-label "Ketogenic Meal Plan"
                               :domain-reasoning "Already covered by the class.")
            n1 (node)
            n2 (assoc (node) :id (random-uuid))]
        (with-domain-candidate first-candidate
          (tp/maybe-auto-classify-and-set-context n1 ctx))
        (with-domain-candidate second-candidate
          (tp/maybe-auto-classify-and-set-context n2 (assoc ctx :tick-id (random-uuid))))
        (let [classified (events-of ctx :ontology/task-classified)
              minted (events-of ctx :ontology/domain-child-minted)
              first-event (first (filter #(= (:id n1) (:source-node-id %)) classified))
              second-event (first (filter #(= (:id n2) (:source-node-id %)) classified))
              first-child (:assigned-tree-id first-event)
              second-child (:assigned-tree-id second-event)]
          (is (= :mint-sibling-domain-child (:assigned-via second-event)))
          (is (= class-id (:parent-tree-id second-event)))
          (is (= "ketogenic-meal-plan" (:domain-label second-event)))
          (is (= ["marathon-training-plan"] (:domain-children-considered second-event))
              "the sibling's label was considered and did not match")
          (is (not= first-child second-child) "a distinct identity")
          (is (true? (:was-fresh-mint? second-event)))
          (is (= 2 (count minted)) "two mint events, one per child")
          (is (= #{(tree-class-uri first-child) (tree-class-uri second-child)}
                 (ontology/get-narrower-concepts ctx (tree-class-uri class-id)))
              "both children hang under the parent")
          (is (= "ketogenic-meal-plan"
                 (:label (ontology/get-concept-by-uri ctx (tree-class-uri second-child)))))
          (is (= 1 (count (ontology/get-claims ctx :tree-class first-child))))
          (is (= 1 (count (ontology/get-claims ctx :tree-class second-child)))))))))

;; =============================================================================
;; CYCLE 6 — an :unknown verdict yields the structural assignment plus a
;; deferral event naming the domain axis; no child, no edge, no claim.
;; =============================================================================

(deftest unknown-verdict-defers-domain-axis-no-child-minted
  (testing "an :unknown domain verdict on a childless class: the structural
            :match assignment stands (a classified event still lands), PLUS a
            :ontology/task-classification-deferred event with :fallback-source
            :domain-coverage and reasoning naming the domain axis; no child
            concept, no edge, no claim"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            candidate (tree-class-candidate class-id 0.95
                        :domain-coverage :unknown
                        :domain-label nil
                        :domain-reasoning nil)]
        (with-domain-candidate candidate
          (tp/maybe-auto-classify-and-set-context (node) ctx))
        (let [classified (events-of ctx :ontology/task-classified)
              deferred (events-of ctx :ontology/task-classification-deferred)
              minted (events-of ctx :ontology/domain-child-minted)
              event (first classified)
              deferral (first deferred)]
          (testing "the structural assignment stands"
            (is (= 1 (count classified)))
            (is (= :match (:assigned-via event)))
            (is (= class-id (:assigned-tree-id event)) "no identity change — still the parent")
            (is (false? (:was-fresh-mint? event)))
            (is (= :domain (:axis (:domain-deferral event)))))
          (testing "the deferral event"
            (is (= 1 (count deferred)) "exactly one deferral event")
            (is (= :domain-coverage (:fallback-source deferral)))
            (is (str/includes? (:reasoning deferral) "domain")
                "the reasoning names the domain axis"))
          (testing "no child, no edge, no claim"
            (is (= 0 (count minted)) "no domain-child-minted event")
            (is (= #{} (ontology/get-narrower-concepts ctx (tree-class-uri class-id)))
                "no edge added to the parent")
            (is (= 0 (count (ontology/get-claims ctx :tree-class class-id)))
                "no representative-use claim recorded")))))))

;; =============================================================================
;; CYCLES 7-8 (inspection) — the CHECKPOINTED path. A checkpointed researcher
;; campaign (the public default) never dispatches the wedge's effects one by
;; one: `run-or-defer-classification-effect!` STAGES them on the context's
;; :classification-effects atom and the processor publishes them through ONE
;; atomic `:sheet/commit-researcher-classification` under the campaign
;; epoch (RR-9). That commit interprets effects by :command/name, so the
;; domain-child mint and the domain-axis deferral RS-3 added must be commands
;; the commit knows how to prepare and bind — else every domain mint on the
;; default path throws "Unsupported or invalid researcher classification
;; effect" and every domain deferral is refused as a second outcome.
;; =============================================================================

(defn- stage-through-wedge!
  "Run the live wedge with the checkpointed campaign's staging seam set, and
   return the staged effects in the order the wedge produced them."
  [ctx node-map]
  (let [effects (atom [])
        wedge-ctx (assoc ctx
                         :classification-effects effects
                         :researcher-ownership-epoch 1)]
    (tp/maybe-auto-classify-and-set-context node-map wedge-ctx)
    @effects))

(defn- claim-frontier!
  "The campaign fence: the commit's CAS requires the node's frontier to be
   claimed under the epoch it commits with (RR-9)."
  [ctx node-map]
  (let [result (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/claim-researcher-frontier
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id (:sheet-id ctx)
                        :tick-id (:tick-id ctx)
                        :node-id (:id node-map)
                        :ownership-epoch 1
                        :claimed-at "1970-01-01T00:00:01Z"}))]
    (assert (nil? (:cognitect.anomalies/category result)) (pr-str result))
    result))

(defn- commit-staged!
  [ctx node-map staged]
  (cp/process-command
   (assoc ctx :command
          {:command/name :sheet/commit-researcher-classification
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id (:sheet-id ctx)
           :tick-id (:tick-id ctx)
           :node-id (:id node-map)
           :ownership-epoch 1
           :effects (mapv :command staged)})))

(deftest checkpointed-commit-publishes-a-domain-child-mint-atomically
  (testing "on the checkpointed path a :partial verdict stages the mint, the
            CV-1 capture and the assignment; ONE commit under the campaign
            epoch publishes all three, and nothing is durable before it"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            candidate (tree-class-candidate class-id 0.95
                        :domain-coverage :partial
                        :domain-label "Marathon Training Plan"
                        :domain-reasoning "Shares subject matter but not the output kind.")
            n (node)
            _ (claim-frontier! ctx n)
            staged (with-domain-candidate candidate
                     (stage-through-wedge! ctx n))]
        (is (= [:domain-child-mint :convergence-capture :classification-outcome]
               (mapv :kind staged))
            "the wedge stages mint, capture, outcome — in that order")
        (is (= 0 (count (events-of ctx :ontology/task-classified)))
            "nothing is durable before the commit")
        (is (= 0 (count (events-of ctx :ontology/domain-child-minted))))
        (let [result (commit-staged! ctx n staged)]
          (is (nil? (:cognitect.anomalies/category result))
              (pr-str (select-keys result [:cognitect.anomalies/category
                                           :cognitect.anomalies/message
                                           :error/explain]))))
        (let [classified (events-of ctx :ontology/task-classified)
              minted (events-of ctx :ontology/domain-child-minted)
              event (first classified)
              child-id (:assigned-tree-id event)]
          (is (= 1 (count classified)))
          (is (= :mint-domain-child (:assigned-via event)))
          (is (= 1 (:researcher-ownership-epoch event))
              "the classification is bound to the campaign epoch")
          (is (= 1 (count minted)))
          (is (= child-id (:child-tree-id (first minted))))
          (is (= "marathon-training-plan"
                 (:label (ontology/get-concept-by-uri ctx (tree-class-uri child-id)))))
          (is (contains? (ontology/get-narrower-concepts ctx (tree-class-uri class-id))
                         (tree-class-uri child-id)))
          (is (= 1 (count (ontology/get-claims ctx :tree-class child-id)))))))))

(deftest checkpointed-commit-publishes-the-domain-deferral-beside-the-assignment
  (testing "on the checkpointed path an :unknown verdict stages the structural
            assignment AND the domain-axis deferral; ONE commit publishes
            both (the deferral is not a second outcome — it is the domain
            axis of the same outcome)"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            candidate (tree-class-candidate class-id 0.95
                        :domain-coverage :unknown
                        :domain-label nil
                        :domain-reasoning nil)
            n (node)
            _ (claim-frontier! ctx n)
            staged (with-domain-candidate candidate
                     (stage-through-wedge! ctx n))]
        (is (= [:classification-outcome :domain-classification-deferral]
               (mapv :kind staged)))
        (let [result (commit-staged! ctx n staged)]
          (is (nil? (:cognitect.anomalies/category result))
              (pr-str (select-keys result [:cognitect.anomalies/category
                                           :cognitect.anomalies/message]))))
        (let [classified (events-of ctx :ontology/task-classified)
              deferred (events-of ctx :ontology/task-classification-deferred)]
          (is (= 1 (count classified)))
          (is (= class-id (:assigned-tree-id (first classified))))
          (is (= 1 (:researcher-ownership-epoch (first classified))))
          (is (= 1 (count deferred)))
          (is (= :domain-coverage (:fallback-source (first deferred))))
          (is (= 1 (:researcher-ownership-epoch (first deferred))))
          (is (= 0 (count (events-of ctx :ontology/domain-child-minted)))))))))

(deftest checkpointed-commit-binds-domain-effects-to-their-assignment
  (testing "the commit's boundary rules for the two RS-3 effects: a mint is
            accepted only beside a domain-mint assignment naming the same
            child; a domain deferral only beside an assignment; never more
            than one of either; a NON-domain deferral is still an outcome"
    (let [schema (sheet-schemas/commands :sheet/commit-researcher-classification)
          sheet-id (random-uuid) tick-id (random-uuid) node-id (random-uuid)
          parent-id (random-uuid) child-id (random-uuid)
          bound {:source-sheet-id sheet-id :source-tick-id tick-id
                 :source-node-id node-id :researcher-ownership-epoch 1}
          assignment (merge bound
                            {:command/name :ontology/assign-task-class
                             :assigned-tree-id parent-id :confidence 0.9
                             :top-candidates [] :reasoning "structural match"
                             :was-fresh-mint? false :assigned-via :match})
          mint-assignment (merge bound
                                 {:command/name :ontology/assign-task-class
                                  :assigned-tree-id child-id :parent-tree-id parent-id
                                  :confidence 0.9 :top-candidates []
                                  :reasoning "domain mint" :was-fresh-mint? true
                                  :assigned-via :mint-domain-child
                                  :domain-label "marathon-training-plan"})
          mint {:command/name :ontology/mint-domain-child
                :parent-tree-id parent-id :child-tree-id child-id
                :domain-label "marathon-training-plan"
                :source-sheet-id sheet-id :source-tick-id tick-id :source-node-id node-id}
          capture {:command/name :ontology/record-claim-deltas
                   :granularity :tree-class :target-identifier child-id}
          domain-deferral (merge bound
                                 {:command/name :ontology/record-task-classification-deferral
                                  :fallback-source :domain-coverage
                                  :ranked-candidates [] :reasoning "domain axis deferred"})
          plain-deferral (assoc domain-deferral :fallback-source :colbert-fallback)
          base {:sheet-id sheet-id :tick-id tick-id :node-id node-id :ownership-epoch 1}
          valid? (fn [effects] (m/validate schema (assoc base :effects effects)))]
      (is (valid? [mint capture mint-assignment]) "mint + capture + mint-assignment")
      (is (valid? [assignment domain-deferral]) "assignment + domain deferral")
      (is (not (valid? [mint capture assignment]))
          "a mint beside a non-mint assignment")
      (is (not (valid? [mint capture (assoc mint-assignment :assigned-tree-id (random-uuid))]))
          "a mint whose child is not the assigned class")
      (is (not (valid? [capture mint-assignment]))
          "a domain-mint assignment without its mint")
      (is (not (valid? [mint mint capture mint-assignment])) "two mints")
      (is (not (valid? [(assoc mint :source-node-id (random-uuid)) capture mint-assignment]))
          "a mint from another campaign")
      (is (not (valid? [domain-deferral])) "a domain deferral is not an outcome by itself")
      (is (not (valid? [plain-deferral domain-deferral]))
          "a domain deferral beside a deferral outcome")
      (is (not (valid? [assignment domain-deferral domain-deferral])) "two domain deferrals")
      (is (not (valid? [assignment (assoc domain-deferral :researcher-ownership-epoch 2)]))
          "a domain deferral from another epoch")
      (is (not (valid? [assignment plain-deferral]))
          "a non-domain deferral is still a second outcome"))))
