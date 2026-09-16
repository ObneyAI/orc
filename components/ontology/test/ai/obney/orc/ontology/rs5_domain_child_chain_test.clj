(ns ai.obney.orc.ontology.rs5-domain-child-chain-test
  "RS-5 — the chain reaches retrieval and harvest.

   Proves, on synthesised durable events, that a minted domain child (RS-3)
   is a first-class :tree-class citizen for everything downstream of
   classification: the retrieval index feed, the retrieval gate's
   recurrence band, walk-down from its parent, and harvest into a
   behavioral child — WITHOUT changing harvest's gate, the consolidator's
   policy, or the retrieval gate.

   Cycle 1 — index feed (GREEN on first write; documents an existing path).
   Cycle 2 — retrieval-gate band on the child (GREEN on first write).
   Cycle 3 — RED: walk-down's synthetic child candidate hardcodes
             :tree-fingerprint (gap A).
   Cycle 4 — RED: the harvested body carries no :domain-label (gap B)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            ;; Register :evaluation/record-judge-score so judge-score! works.
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.ontology.test-helpers :as th]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; ---------------------------------------------------------------------------
;; Context helpers — mirror el4_harvest_test.clj (processor-optional).
;; ---------------------------------------------------------------------------
(defn- create-context
  ([] (create-context {}))
  ([{:keys [processor-names] :or {processor-names #{}}}]
   (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
         event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
         cache-dir (str "/tmp/rs5-test-" (random-uuid))
         cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
         tenant-id (random-uuid)
         base-ctx {:event-store event-store
                   :cache cache
                   :tenant-id tenant-id
                   :event-pubsub ps
                   :command-registry (cp/global-command-registry)
                   :query-registry (qp/global-query-registry)
                   ::cache-dir cache-dir}
         processors (reduce-kv
                      (fn [acc proc-name {:keys [handler-fn topics]}]
                        (if (contains? processor-names proc-name)
                          (assoc acc proc-name
                                 (tp/start {:event-pubsub ps :topics topics
                                            :handler-fn handler-fn :context base-ctx}))
                          acc))
                      {} @tp/processor-registry*)]
     (assoc base-ctx :processors processors))))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defmacro with-harvest-processor-ctx [[sym] & body]
  `(let [~sym (create-context {:processor-names #{:ontology/on-tree-class-check-harvest}})]
     (try ~@body (finally (stop-context ~sym)))))

;; ---------------------------------------------------------------------------
;; RS-3 command helpers (reused verbatim from rs3_domain_child_birth_test.clj)
;; ---------------------------------------------------------------------------
(defn- mint-command [parent-id child-id label]
  {:command/name :ontology/mint-domain-child
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :parent-tree-id parent-id
   :child-tree-id child-id
   :domain-label label
   :source-sheet-id (random-uuid)
   :source-tick-id (random-uuid)
   :source-node-id (random-uuid)})

(defn- dispatch!
  "Dispatch through the real command processor, which APPENDS the result's
   events itself (Grain command-processor-v2 `execute-command`). Never
   follow it with `th/apply-events!` — that appended every event a second
   time (RS-5 inspection finding: one mint left ten events in the store)."
  [ctx command]
  (cp/process-command (assoc ctx :command command)))

(defn- claim-command [ctx child-id content]
  {:command/name :ontology/record-claim-deltas
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :granularity :tree-class
   :target-identifier child-id
   :deltas [{:operation :add
             :kind :representative-use
             :content content
             :context-guard nil
             :recommendation nil
             :episodes []
             :from-legacy-corpus false
             :evidence-basis :classification-signature}]
   :evidence-event-count 0
   :claim-set-version (ontology/get-claim-set-version ctx :tree-class child-id)})

;; ---------------------------------------------------------------------------
;; el4_harvest_test-style event-stream fixtures (reused verbatim in shape)
;; ---------------------------------------------------------------------------
(defn- verdict-occurrence! [ctx sheet-id tick-id node-id class-id verdict]
  (es/append
   (:event-store ctx)
   {:tenant-id (:tenant-id ctx)
    :events [(es/->event
              {:type :ontology/tree-class-occurrence-recorded
               :tags #{[:tick tick-id] [:description-target class-id]}
               :body {:source-sheet-id sheet-id
                      :source-tick-id tick-id
                      :source-node-id node-id
                      :source-completion-event-id (random-uuid)
                      :assigned-tree-id class-id
                      :verdict verdict
                      :recorded-at (str (time/now))}})]}))

(defn- judge-score!
  [ctx sheet-id tick-id judge-name score]
  (cp/process-command
   (assoc ctx :command
          {:command/name :evaluation/record-judge-score
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id (random-uuid)
           :tick-id tick-id
           :judge-name judge-name
           :judge-config {}
           :score score
           :feedback ""
           :dimensions []})))

(defn- seed-parent-behavior! [ctx parent-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-tree-description
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :target-id parent-id
           :body {:capabilities ["abstract parent"]
                  :strengths [] :weaknesses []
                  :representative-uses [] :avoid-when []
                  :summary "abstract behavioral parent"
                  :version 1 :consolidated-from-event-count 1
                  :scope :behavioral-subtree}})))

(defn- occurrence!
  "One real observation of a tree-class: classify + judge-score on the HOST
   sheet + turn tick, and a PRODUCTION-SHAPED bookend on its own EPHEMERAL
   sheet/tick carrying the [source-sheet-id source-tick-id] linkage (HP-2)."
  [ctx class-id sheet-id fingerprint score behavioral-subtrees]
  (let [tick-id (random-uuid)
        node-id (random-uuid)]
    (cp/process-command
     (assoc ctx :command
            (cond-> {:command/name :ontology/assign-task-class
                     :command/id (random-uuid)
                     :command/timestamp (time/now)
                     :source-sheet-id sheet-id
                     :source-tick-id tick-id
                     :source-node-id node-id
                     :assigned-tree-id class-id
                     :confidence 0.95
                     :top-candidates []
                     :reasoning "test"
                     :was-fresh-mint? false}
              behavioral-subtrees (assoc :behavioral-subtrees behavioral-subtrees))))
    (judge-score! ctx sheet-id tick-id "quality" score)
    (cp/process-command
     (assoc ctx :command
            {:command/name :sheet/record-rlm-tree-execution-completion
             :command/id (random-uuid)
             :command/timestamp (time/now)
             :sheet-id (random-uuid)
             :tick-id (random-uuid)
             :source-sheet-id sheet-id
             :source-tick-id tick-id
             :trajectory []
             :total-usage {:total-tokens 0}
             :tree-fingerprint fingerprint
             :status :success
             :duration-ms 100}))
    (verdict-occurrence! ctx sheet-id tick-id node-id class-id :success)))

(defn- minted-harvest-events [ctx class-id]
  (->> (into [] (es/read (:event-store ctx)
                         {:types #{:ontology/behavioral-subtree-minted}
                          :tenant-id (:tenant-id ctx)}))
       (filter #(and (= :harvested (:provenance %))
                     (= class-id (:harvested-from-tree-class %))))))

;; el1b-style :tree-class candidate fixture, shaped exactly as
;; search-descriptions returns (per el1b_convergence_capture_test).
(defn- tree-class-candidate [target-id fitness raw-score]
  {:content "x" :score raw-score :rank 1
   :document-id (str "tc::" target-id)
   :document-metadata {:granularity :tree-class
                       :target-id target-id
                       :confidence 1.0}
   :reasoning "structural fit"
   :fitness-score fitness
   :rerank-source :reranker})

(defn- surfaced? [result target-id]
  (boolean (some #(= target-id (-> % :document-metadata :target-id))
                 (:top-candidates result))))

;; =============================================================================
;; CYCLE 1 — the index feed already reaches the domain child's CV-1
;; description. Expected GREEN on first write (the feed already reads
;; assembled bodies) — reported as a finding, not a red-green cycle.
;; =============================================================================

(deftest cycle1-domain-child-feeds-the-document-collection
  (testing "the child's :tree-class birth-claim description is collected AND
            built into the document-collection feed exactly like any other
            :tree-class description"
    (with-test-ctx [ctx]
      (let [parent-id (random-uuid)
            child-id (random-uuid)
            signature "marathon training plan: 16-week schedule for a first-time marathoner"]
        (dispatch! ctx (mint-command parent-id child-id "marathon-training-plan"))
        (dispatch! ctx (claim-command ctx child-id signature))
        (let [descriptions (#'ont-tp/collect-current-descriptions ctx)
              entry (some #(when (and (= :tree-class (:granularity %))
                                       (= child-id (:target-id %)))
                             %)
                          descriptions)]
          (is (some? entry) "the child's :tree-class description is in the collected set")
          (is (str/includes? (-> entry :body :summary) signature)
              "the injected CV-1 signature reaches the assembled :summary")
          (is (= 0 (-> entry :body :consolidated-from-event-count))
              "the birth claim's :evidence-event-count 0 carries through")
          (let [{:keys [collection document-ids document-metadatas]}
                (#'ont-tp/build-document-collection descriptions)
                expected-doc-id (str :tree-class ":" (pr-str child-id))
                idx (.indexOf ^java.util.List document-ids expected-doc-id)]
            (is (not= -1 idx)
                (str "a \"" expected-doc-id "\" document id exists, got " document-ids))
            (is (str/includes? (nth collection idx) signature))
            (let [metadata (nth document-metadatas idx)]
              (is (= :tree-class (:granularity metadata)))
              (is (= child-id (:target-id metadata)))
              (is (= 0.0 (:confidence metadata))
                  "a newborn child has no :strengths yet -> average-confidence 0.0"))))))))

;; =============================================================================
;; CYCLE 2 — the retrieval-gate band already applies to the domain child
;; exactly as to any other :tree-class. Expected GREEN on first write for a
;; seeded/minted class — reported as a finding, not a red-green cycle.
;; =============================================================================

(deftest cycle2-retrieval-gate-band-on-the-domain-child
  (testing "total 0 (just-minted): matched and surfaced; total 2 (0 < total <
            gate): still matched (accrual is gate-independent) but filtered
            from :top-candidates; total 3 (>= gate): matched and surfaced
            again"
    (with-test-ctx [ctx]
      (let [parent-id (random-uuid)
            child-id (random-uuid)]
        (dispatch! ctx (mint-command parent-id child-id "marathon-training-plan"))
        (with-redefs [ontology/search-descriptions
                      (fn [_ _] [(tree-class-candidate child-id 0.95 25.0)])]
          (let [r0 (ontology/classify-task ctx {:task-signature "x"
                                                :threshold 0.7
                                                :retrieval-gate 3
                                                :walk-down? false})]
            (is (= :matched (:outcome r0)))
            (is (= child-id (:assigned-tree-id r0)))
            (is (surfaced? r0 child-id) "total 0: surfaced (curated/new-mint band)"))

          (dotimes [_ 2]
            (verdict-occurrence! ctx (random-uuid) (random-uuid) (random-uuid) child-id :success))
          (let [r2 (ontology/classify-task ctx {:task-signature "x"
                                                :threshold 0.7
                                                :retrieval-gate 3
                                                :walk-down? false})]
            (is (= :matched (:outcome r2)) "still matchable-for-accrual below the gate")
            (is (= child-id (:assigned-tree-id r2)))
            (is (not (surfaced? r2 child-id)) "total 2: filtered out of the surfaced set"))

          (verdict-occurrence! ctx (random-uuid) (random-uuid) (random-uuid) child-id :success)
          (let [r3 (ontology/classify-task ctx {:task-signature "x"
                                                :threshold 0.7
                                                :retrieval-gate 3
                                                :walk-down? false})]
            (is (= :matched (:outcome r3)))
            (is (= child-id (:assigned-tree-id r3)))
            (is (surfaced? r3 child-id) "total 3 >= gate: surfaced again")))))))

;; =============================================================================
;; CYCLE 3 — RED: walk-down's synthetic child candidate hardcodes
;; :tree-fingerprint (gap A). GREEN once pick-best-child reads :scope.
;; =============================================================================

(deftest cycle3-walk-down-candidate-carries-tree-class-scope
  (testing "pick-best-child's synthetic candidate for a domain child
            described under :tree-class scope carries :document-metadata
            :granularity :tree-class in the rerank! call it makes, not the
            hardcoded :tree-fingerprint"
    (with-test-ctx [ctx]
      (let [parent-id (random-uuid)
            child-id (random-uuid)
            captured (atom nil)]
        (dispatch! ctx (mint-command parent-id child-id "marathon-training-plan"))
        (dispatch! ctx (claim-command ctx child-id "marathon training plan: 16-week schedule"))
        (with-redefs [reranker/rerank!
                      (fn [_ opts]
                        (reset! captured opts)
                        (mapv (fn [c] {:document-id (:document-id c)
                                       :reasoning "test"
                                       :fitness-score 0.95})
                              (:candidates opts)))]
          (let [children (#'tc/get-tree-class-children ctx parent-id)
                best (#'tc/pick-best-child ctx "intent" children 0.7 nil)]
            (is (= 1 (count children)))
            (is (= :tree-class (:scope (first children)))
                "get-tree-class-children reports the scope it actually found the child under")
            (let [child-candidate (first (:candidates @captured))]
              (is (= :tree-class (-> child-candidate :document-metadata :granularity))
                  "the candidate passed to rerank! carries the :tree-class scope, not a hardcoded axis")
              (is (= child-id (-> child-candidate :document-metadata :target-id))))
            (is (= child-id (:target-id best)))))))))

(deftest cycle3-guard-walk-down-candidate-keeps-tree-fingerprint-for-seeded-children
  (testing "GUARD: a seeded child described only at :tree-fingerprint scope
            still carries :tree-fingerprint in the rerank! candidate — the
            fallback + its default axis are preserved"
    (with-test-ctx [ctx]
      (let [parent-uri "tree-class:seed-parent"
            child-fp "seed:tree:ChunkedExtraction"
            body {:capabilities [] :strengths [] :weaknesses [] :avoid-when []
                  :representative-uses [] :summary "seed child" :version 1
                  :consolidated-from-event-count 0
                  :parent-tree-id "seed-parent"}
            captured (atom nil)]
        (dispatch! ctx {:command/name :ontology/record-tree-description
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :target-id child-fp
                        :body body})
        (let [ev (last (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:ontology/tree-description-updated}})))]
          (ont-tp/on-tree-description-updated-project-concept (assoc ctx :event ev)))
        (with-redefs [reranker/rerank!
                      (fn [_ opts]
                        (reset! captured opts)
                        (mapv (fn [c] {:document-id (:document-id c)
                                       :reasoning "test"
                                       :fitness-score 0.95})
                              (:candidates opts)))]
          (let [children (#'tc/get-tree-class-children ctx "seed-parent")
                best (#'tc/pick-best-child ctx "intent" children 0.7 nil)]
            (is (= 1 (count children)))
            (is (= :tree-fingerprint (:scope (first children))))
            (is (= :tree-fingerprint
                   (-> @captured :candidates first :document-metadata :granularity))
                "seeded child still carries :tree-fingerprint")
            (is (= child-fp (:target-id best)))))))))

;; =============================================================================
;; CYCLE 4 — RED: the harvested body carries no :domain-label (gap B).
;; GREEN once harvest-body reads the domain child's tree-class concept.
;; =============================================================================

(deftest cycle4-harvest-promotes-the-domain-child-with-its-label
  (testing "the harvested behavioral child's body carries :domain-label read
            from the domain child's tree-class CONCEPT, plus the injected
            CV-1 signature in :representative-uses"
    (with-test-ctx [ctx]
      (let [parent-tree-id (random-uuid)
            child-id (random-uuid)
            parent-behavior-id (random-uuid)
            label "marathon-training-plan"
            signature "marathon training plan: 16-week schedule for a first-time marathoner"]
        (dispatch! ctx (mint-command parent-tree-id child-id label))
        (dispatch! ctx (claim-command ctx child-id signature))
        (seed-parent-behavior! ctx parent-behavior-id)
        (dotimes [i 12]
          (occurrence! ctx child-id (random-uuid) "shape-A" 0.85
                       (when (zero? i)
                         [{:behavior-id parent-behavior-id :confidence 0.9 :reasoning "x"}])))
        (Thread/sleep 300)
        (harvest/maybe-harvest! ctx child-id)
        (Thread/sleep 200)
        (let [minted (minted-harvest-events ctx child-id)]
          (is (= 1 (count minted)) "exactly one harvested behavior minted")
          (let [ev (first minted)
                target-id (:target-id ev)
                desc (ontology/get-description ctx :tree-fingerprint target-id)]
            (is (= parent-behavior-id (:parent-behavior ev))
                "parent-behavior = nearest abstract via skos:broader")
            (is (some? desc) "harvested behavior is stored + retrievable in the ontology")
            (is (some #{signature} (:representative-uses desc))
                "the injected CV-1 signature reaches the harvested body")
            (is (= label (:domain-label desc))
                "the harvested body carries the domain label from the child's concept")))))))

(deftest cycle4-processor-driven-harvest-mints-once-with-the-label
  (testing "the registered on-tree-class-check-harvest processor mints the
            good domain-child class from real events, end-to-end (no direct
            maybe-harvest! call), with the label carried through"
    (with-harvest-processor-ctx [ctx]
      (let [parent-tree-id (random-uuid)
            child-id (random-uuid)
            parent-behavior-id (random-uuid)
            label "recipe-scaling"
            signature "recipe scaling: convert servings and adjust ingredient ratios"]
        (dispatch! ctx (mint-command parent-tree-id child-id label))
        (dispatch! ctx (claim-command ctx child-id signature))
        (seed-parent-behavior! ctx parent-behavior-id)
        (dotimes [i 12]
          (occurrence! ctx child-id (random-uuid) "shape-A" 0.85
                       (when (zero? i)
                         [{:behavior-id parent-behavior-id :confidence 0.9 :reasoning "x"}])))
        (Thread/sleep 500)
        (let [minted (minted-harvest-events ctx child-id)]
          (is (= 1 (count minted)) "the processor auto-harvested the good domain-child class")
          (let [desc (ontology/get-description ctx :tree-fingerprint (:target-id (first minted)))]
            (is (= label (:domain-label desc))
                "the processor-driven mint also carries the domain label")))))))
