(ns ai.obney.orc.ontology.tree-class-hierarchy-test
  "Tests for C-2d-1: foundation slice of the hierarchical tree-class
   taxonomy.

   Covers:
   - Optional :parent-tree-id on the description-body schema
   - Reactive processor that projects tree-class parents into the
     :ontology/concepts graph as SKOS broader/narrower relationships
   - Hand-authored parent links on the 5 benchmark seeds
   - End-to-end seed-all → concept graph state

   Walk-down classifier behavior + consolidator parent-inference live
   in C-2d-2; live verify in C-2d-3."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2d1-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}]
    base-ctx))

(defn- stop-context [ctx]
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

(defn- emit-tree-description-updated!
  "Helper: dispatch :ontology/record-tree-description so the event lands
   in the store. Returns the event after it lands."
  [ctx target-id parent-tree-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-tree-description
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-id target-id
            :body (cond-> {:capabilities ["x"]
                           :strengths []
                           :weaknesses []
                           :representative-uses ["x"]
                           :avoid-when ["x"]
                           :summary "x"
                           :version 1
                           :consolidated-from-event-count 1}
                    parent-tree-id (assoc :parent-tree-id parent-tree-id))})))

(defn- run-tree-class-processor!
  "Drive the wedge by reading all :ontology/tree-description-updated
   events in the store and invoking the processor handler on each."
  [ctx]
  (let [handler (requiring-resolve
                  'ai.obney.orc.ontology.core.todo-processors/on-tree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (handler (assoc ctx :event e)))))

;; =============================================================================
;; RED #1 — description-body schema accepts :parent-tree-id (optional)
;; =============================================================================

(deftest description-body-schema-accepts-parent-tree-id
  (testing "description-body validates WITHOUT :parent-tree-id (legacy shape)"
    (let [body {:capabilities ["x"]
                :strengths []
                :weaknesses []
                :representative-uses ["x"]
                :avoid-when ["x"]
                :summary "x"
                :version 1
                :consolidated-from-event-count 1}]
      (is (m/validate ontology-schemas/description-body body)
          "Legacy descriptions without :parent-tree-id remain valid")))

  (testing "description-body validates WITH :parent-tree-id as UUID"
    (let [body {:capabilities ["x"]
                :strengths []
                :weaknesses []
                :representative-uses ["x"]
                :avoid-when ["x"]
                :summary "x"
                :version 1
                :consolidated-from-event-count 1
                :parent-tree-id (random-uuid)}]
      (is (m/validate ontology-schemas/description-body body)
          "UUID :parent-tree-id (e.g., a task-class UUID) is accepted")))

  (testing "description-body validates WITH :parent-tree-id as string fingerprint"
    (let [body {:capabilities ["x"]
                :strengths []
                :weaknesses []
                :representative-uses ["x"]
                :avoid-when ["x"]
                :summary "x"
                :version 1
                :consolidated-from-event-count 1
                :parent-tree-id "seed:tree:ChunkedExtraction"}]
      (is (m/validate ontology-schemas/description-body body)
          "String :parent-tree-id (e.g., a stable pattern fingerprint) is accepted")))

  (testing "description-body REJECTS :parent-tree-id that is neither UUID nor string"
    (let [body {:capabilities ["x"]
                :strengths []
                :weaknesses []
                :representative-uses ["x"]
                :avoid-when ["x"]
                :summary "x"
                :version 1
                :consolidated-from-event-count 1
                :parent-tree-id 42}]
      (is (not (m/validate ontology-schemas/description-body body))
          "Integer :parent-tree-id is rejected (only :uuid or :string)"))))

;; =============================================================================
;; RED #5 — 5 benchmark seeds carry :parent-tree-id; 17 others don't
;; =============================================================================

(deftest benchmark-seeds-carry-parent-tree-id
  ;; The seed-descriptions fixture is a brick test-support ns; pull it in via
  ;; requiring-resolve so the test ns doesn't take a static dep on the fixture
  ;; at load time.
  (let [_ (require 'ai.obney.orc.ontology.test-support.seed-descriptions)
        seeds-ns (find-ns 'ai.obney.orc.ontology.test-support.seed-descriptions)
        seed (fn [sym] @(ns-resolve seeds-ns sym))
        all-tf (seed 'all-tree-fingerprint-seeds)
        all-nt (seed 'all-node-type-seeds)
        chunked-extraction-uuid (seed 'chunked-extraction-task-class-id)
        parallel-fp (seed 'parallel-independent-fp)
        chunked-fp (seed 'chunked-extraction-fp)
        parent-of (fn [seed-sym]
                    (-> (seed seed-sym) :body :parent-tree-id))]

    (testing "5 benchmark-specific tree-fingerprint seeds carry :parent-tree-id"
      (is (= chunked-extraction-uuid (parent-of 'document-analysis-tree-seed))
          "document-analysis → child of chunked-extraction task-class")
      (is (= chunked-extraction-uuid (parent-of 'risk-analysis-tree-seed))
          "risk-analysis → child of chunked-extraction task-class")
      (is (= chunked-extraction-uuid (parent-of 'legal-issue-detection-tree-seed))
          "legal-issue-detection → child of chunked-extraction task-class")
      (is (= parallel-fp (parent-of 'contract-comparison-tree-seed))
          "contract-comparison → child of parallel-independent pattern")
      (is (= chunked-fp (parent-of 'chunked-extraction-tree-seed))
          "chunked-extraction-tree-seed (benchmark instance) → child of chunked-extraction-pattern"))

    (testing "7 abstract pattern seeds remain top-level (no :parent-tree-id)"
      (doseq [sym '[chunked-extraction-pattern-seed
                    sequential-pipeline-pattern-seed
                    parallel-independent-pattern-seed
                    validation-loop-pattern-seed
                    fallback-recovery-pattern-seed
                    map-reduce-pattern-seed
                    research-then-synthesize-pattern-seed]]
        (is (nil? (parent-of sym))
            (str sym " is an abstract pattern; should have no :parent-tree-id"))))

    (testing "10 node-type seeds remain top-level (no :parent-tree-id)"
      (doseq [sym '[llm-node-type-seed
                    code-node-type-seed
                    map-each-node-type-seed
                    parallel-node-type-seed
                    sequence-node-type-seed
                    fallback-node-type-seed
                    condition-node-type-seed
                    chunk-document-node-type-seed
                    aggregate-node-type-seed
                    final-node-type-seed]]
        (is (nil? (parent-of sym))
            (str sym " is a node-type; :parent-tree-id is a tree-class concept"))))

    (testing "Total seed counts after C-2d-1 + R02 additions"
      ;; 12 baseline + 11 R02 children = 23 tree-fingerprint seeds
      (is (= 23 (count all-tf))
          "23 tree-fingerprint seeds (12 C-2d-1 baseline + 11 R02 children)")
      (is (= 10 (count all-nt))
          "Still 10 node-type seeds (unaffected by R02)"))))

;; =============================================================================
;; RED #2 — reactive processor emits concept-creates + broader for event with :parent-tree-id
;; =============================================================================

(deftest processor-projects-parent-tree-id-into-concept-graph
  (testing "After :ontology/tree-description-updated with :parent-tree-id lands, the processor ensures concepts exist + emits skos:broader relationship"
    (with-test-ctx [ctx]
      (let [target-id (random-uuid)
            parent-id (random-uuid)]
        (emit-tree-description-updated! ctx target-id parent-id)
        (Thread/sleep 100)
        (run-tree-class-processor! ctx)
        (Thread/sleep 100)
        (let [concepts (rmp/project ctx :ontology/concepts)
              target-uri (str "tree-class:" target-id)
              parent-uri (str "tree-class:" parent-id)
              target-concept (get concepts target-uri)
              parent-concept (get concepts parent-uri)]
          (is (some? target-concept)
              "Target concept exists at tree-class:<target-id>")
          (is (= :tree-class (:scope target-concept))
              "Target concept has :scope :tree-class")
          (is (some? parent-concept)
              "Parent concept exists at tree-class:<parent-id>")
          (is (= :tree-class (:scope parent-concept))
              "Parent concept has :scope :tree-class")
          (is (contains? (:broader target-concept) parent-uri)
              "Target's :broader set contains the parent URI (skos:broader)")
          (is (contains? (:narrower parent-concept) target-uri)
              "Parent's :narrower set contains the target URI (reverse projection)"))))))

;; =============================================================================
;; RED #3 — reactive processor no-op for event without :parent-tree-id
;; =============================================================================

(deftest processor-no-op-without-parent-tree-id
  (testing "When :ontology/tree-description-updated has NO :parent-tree-id (top-level abstract pattern), the processor doesn't create concepts or relationships"
    (with-test-ctx [ctx]
      (let [target-id (random-uuid)]
        ;; Emit without :parent-tree-id
        (emit-tree-description-updated! ctx target-id nil)
        (Thread/sleep 100)
        (run-tree-class-processor! ctx)
        (Thread/sleep 100)
        (let [events (into [] (es/read (:event-store ctx)
                                        {:tenant-id (:tenant-id ctx)}))
              concept-events (filter #(= :ontology/concept-created (:event/type %)) events)
              rel-events (filter #(= :ontology/relationship-created (:event/type %)) events)]
          (is (empty? concept-events)
              "No concept-created events emitted")
          (is (empty? rel-events)
              "No relationship-created events emitted"))))))

;; =============================================================================
;; RED #4 — reactive processor is idempotent
;; =============================================================================

(deftest processor-is-idempotent
  (testing "Running the processor twice on the same event doesn't duplicate the relationship"
    (with-test-ctx [ctx]
      (let [target-id (random-uuid)
            parent-id (random-uuid)]
        (emit-tree-description-updated! ctx target-id parent-id)
        (Thread/sleep 100)
        ;; First pass
        (run-tree-class-processor! ctx)
        (Thread/sleep 100)
        (let [events-after-first (into [] (es/read (:event-store ctx)
                                                    {:tenant-id (:tenant-id ctx)}))
              rel-count-1 (count (filter #(= :ontology/relationship-created (:event/type %))
                                          events-after-first))
              concept-count-1 (count (filter #(= :ontology/concept-created (:event/type %))
                                              events-after-first))]
          ;; Second pass — should be a no-op because the broader link is already in place
          (run-tree-class-processor! ctx)
          (Thread/sleep 100)
          (let [events-after-second (into [] (es/read (:event-store ctx)
                                                      {:tenant-id (:tenant-id ctx)}))
                rel-count-2 (count (filter #(= :ontology/relationship-created (:event/type %))
                                            events-after-second))
                concept-count-2 (count (filter #(= :ontology/concept-created (:event/type %))
                                                events-after-second))]
            (is (= rel-count-1 rel-count-2)
                "No additional relationship-created events on the second pass")
            (is (= concept-count-1 concept-count-2)
                "No additional concept-created events on the second pass")))))))

;; =============================================================================
;; RED #6 — end-to-end: seed-all + processor produces expected concept graph
;; =============================================================================

(deftest end-to-end-seeding-builds-expected-tree-class-graph
  (testing "After running seed-all! + the processor, the concept graph has the expected hierarchy"
    (require 'ai.obney.orc.ontology.test-support.seed-descriptions)
    (let [seed-fn (ns-resolve (find-ns 'ai.obney.orc.ontology.test-support.seed-descriptions) 'seed-all!)
          chunked-extraction-fp (deref (ns-resolve (find-ns 'ai.obney.orc.ontology.test-support.seed-descriptions) 'chunked-extraction-fp))
          parallel-independent-fp (deref (ns-resolve (find-ns 'ai.obney.orc.ontology.test-support.seed-descriptions) 'parallel-independent-fp))
          chunked-extraction-task-id (deref (ns-resolve (find-ns 'ai.obney.orc.ontology.test-support.seed-descriptions) 'chunked-extraction-task-class-id))]
      (with-test-ctx [ctx]
        (seed-fn ctx)
        (Thread/sleep 500)
        (run-tree-class-processor! ctx)
        (Thread/sleep 200)
        (let [concepts (rmp/project ctx :ontology/concepts)
              tree-class-concepts (filter #(= :tree-class (:scope %)) (vals concepts))
              concepts-with-broader (filter #(seq (:broader %)) tree-class-concepts)
              top-level (filter #(empty? (:broader %)) tree-class-concepts)
              uri-fn (fn [id] (str "tree-class:" id))]

          ;; Post-R02 expectations:
          ;; - C-2d-1 baseline: 5 children (doc-analysis / risk-analysis /
          ;;   legal-issue / contract-comparison / chunked-extraction-task-id)
          ;;   + 2 pure-top-level (chunked-extraction-fp /
          ;;   parallel-independent-fp) = 7 baseline concepts.
          ;; - R02: 11 new children + 5 newly-lazy-created flat top-level
          ;;   parents (SP, VL, FR, MR, RTS).
          ;; Total: 7 + 11 + 5 = 23 :tree-class concepts.
          ;; Concepts with broader: 5 baseline + 11 R02 children = 16.
          ;; Top-level (empty :broader): 7 (2 baseline + 5 R02-elevated).
          (is (= 23 (count tree-class-concepts))
              "23 :tree-class concepts post-R02 (7 baseline + 11 R02 children + 5 R02-elevated top-level)")
          (is (= 16 (count concepts-with-broader))
              "16 concepts have non-empty :broader (5 baseline + 11 R02 children)")
          (is (= 7 (count top-level))
              "7 pure-top-level concepts (2 baseline + 5 R02-elevated: SP/VL/FR/MR/RTS)")

          (testing "Specific links materialize as expected"
            (let [ce-fp-concept (get concepts (uri-fn chunked-extraction-fp))
                  pi-fp-concept (get concepts (uri-fn parallel-independent-fp))
                  ce-task-concept (get concepts (uri-fn chunked-extraction-task-id))]
              (is (some? ce-fp-concept)
                  "chunked-extraction-fp materialized as a concept")
              (is (some? pi-fp-concept)
                  "parallel-independent-fp materialized as a concept")
              (is (some? ce-task-concept)
                  "chunked-extraction-task-class-id materialized as a concept")
              (is (contains? (:narrower ce-fp-concept) (uri-fn chunked-extraction-task-id))
                  "chunked-extraction-fp has chunked-extraction-task-id as a narrower")
              (is (contains? (:broader ce-task-concept) (uri-fn chunked-extraction-fp))
                  "chunked-extraction-task-id has chunked-extraction-fp as broader (intermediate node)"))))))))
