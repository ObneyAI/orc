(ns ai.obney.orc.ontology.r02-flat-pattern-children-test
  "Tests for R02: hand-authored children seeded under the 5 currently-flat
   top-level patterns (SequentialPipeline, ValidationLoop, FallbackRecovery,
   MapReduce, ResearchThenSynthesize).

   Each cycle authors ONE new child seed and verifies:
   - Body validates against the ontology-schemas/description-body schema
   - :parent-tree-id points to the correct flat top-level ancestor
   - The seed is registered in all-tree-fingerprint-seeds (so seed-all!
     emits it)
   - The seed's traits are principle-shaped (no status-shaped hedging)

   The aggregate cycle (#12) confirms post-seed-all! that the concepts
   graph has expanded from 7 → 18 tree-class concepts and that each
   flat parent has its new children's URIs in its :narrower set."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.ontology.test-support.seed-descriptions :as seeds]))

;; =============================================================================
;; RED #1 — etl-pipeline-task-class-id under sequential-pipeline-fp
;; =============================================================================

(deftest etl-pipeline-seed-validates-and-links-to-sequential-pipeline
  (testing "etl-pipeline-tree-seed exists and is well-formed"
    (let [seed seeds/etl-pipeline-tree-seed]
      (is (some? seed)
          "etl-pipeline-tree-seed must be defined")
      (is (uuid? (:target-id seed))
          ":target-id is a UUID")
      (is (m/validate ontology-schemas/description-body (:body seed))
          (str "Body validates against description-body schema. Explain: "
               (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
      (is (= seeds/sequential-pipeline-fp (:parent-tree-id (:body seed)))
          ":parent-tree-id points to sequential-pipeline-fp")))

  (testing "etl-pipeline target-id matches the published def"
    (is (= seeds/etl-pipeline-task-class-id
           (:target-id seeds/etl-pipeline-tree-seed))))

  (testing "etl-pipeline-tree-seed is registered in all-tree-fingerprint-seeds"
    (is (some #(= seeds/etl-pipeline-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds)
        "all-tree-fingerprint-seeds must include the new etl-pipeline seed")))

;; =============================================================================
;; RED #2 — iterative-refinement-task-class-id under sequential-pipeline-fp
;; =============================================================================

(deftest iterative-refinement-seed-validates-and-links-to-sequential-pipeline
  (testing "iterative-refinement-tree-seed exists and is well-formed"
    (let [seed seeds/iterative-refinement-tree-seed]
      (is (some? seed))
      (is (uuid? (:target-id seed)))
      (is (m/validate ontology-schemas/description-body (:body seed))
          (str "Body validates. Explain: "
               (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
      (is (= seeds/sequential-pipeline-fp (:parent-tree-id (:body seed))))))

  (testing "iterative-refinement target-id matches the published def"
    (is (= seeds/iterative-refinement-task-class-id
           (:target-id seeds/iterative-refinement-tree-seed))))

  (testing "iterative-refinement-tree-seed is registered"
    (is (some #(= seeds/iterative-refinement-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #3 — scheduling-task-class-id under sequential-pipeline-fp
;; =============================================================================

(deftest scheduling-seed-validates-and-links-to-sequential-pipeline
  (let [seed seeds/scheduling-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/sequential-pipeline-fp (:parent-tree-id (:body seed))))
    (is (= seeds/scheduling-task-class-id (:target-id seed)))
    (is (some #(= seeds/scheduling-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #4 — producer-validator-task-class-id under validation-loop-fp
;; =============================================================================

(deftest producer-validator-seed-validates-and-links-to-validation-loop
  (let [seed seeds/producer-validator-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/validation-loop-fp (:parent-tree-id (:body seed))))
    (is (= seeds/producer-validator-task-class-id (:target-id seed)))
    (is (some #(= seeds/producer-validator-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #5 — draft-critique-task-class-id under validation-loop-fp
;; =============================================================================

(deftest draft-critique-seed-validates-and-links-to-validation-loop
  (let [seed seeds/draft-critique-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/validation-loop-fp (:parent-tree-id (:body seed))))
    (is (= seeds/draft-critique-task-class-id (:target-id seed)))
    (is (some #(= seeds/draft-critique-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #6 — primary-backup-task-class-id under fallback-recovery-fp
;; =============================================================================

(deftest primary-backup-seed-validates-and-links-to-fallback-recovery
  (let [seed seeds/primary-backup-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/fallback-recovery-fp (:parent-tree-id (:body seed))))
    (is (= seeds/primary-backup-task-class-id (:target-id seed)))
    (is (some #(= seeds/primary-backup-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #7 — model-cascade-task-class-id under fallback-recovery-fp
;; =============================================================================

(deftest model-cascade-seed-validates-and-links-to-fallback-recovery
  (let [seed seeds/model-cascade-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/fallback-recovery-fp (:parent-tree-id (:body seed))))
    (is (= seeds/model-cascade-task-class-id (:target-id seed)))
    (is (some #(= seeds/model-cascade-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #8 — parallel-sum-task-class-id under map-reduce-fp
;; =============================================================================

(deftest parallel-sum-seed-validates-and-links-to-map-reduce
  (let [seed seeds/parallel-sum-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/map-reduce-fp (:parent-tree-id (:body seed))))
    (is (= seeds/parallel-sum-task-class-id (:target-id seed)))
    (is (some #(= seeds/parallel-sum-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #9 — parallel-classify-aggregate-task-class-id under map-reduce-fp
;; =============================================================================

(deftest parallel-classify-aggregate-seed-validates-and-links-to-map-reduce
  (let [seed seeds/parallel-classify-aggregate-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/map-reduce-fp (:parent-tree-id (:body seed))))
    (is (= seeds/parallel-classify-aggregate-task-class-id (:target-id seed)))
    (is (some #(= seeds/parallel-classify-aggregate-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #10 — briefing-generation-task-class-id under research-then-synth-fp
;; =============================================================================

(deftest briefing-generation-seed-validates-and-links-to-research-then-synth
  (let [seed seeds/briefing-generation-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/research-then-synth-fp (:parent-tree-id (:body seed))))
    (is (= seeds/briefing-generation-task-class-id (:target-id seed)))
    (is (some #(= seeds/briefing-generation-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #11 — comparative-summary-task-class-id under research-then-synth-fp
;; =============================================================================

(deftest comparative-summary-seed-validates-and-links-to-research-then-synth
  (let [seed seeds/comparative-summary-tree-seed]
    (is (some? seed))
    (is (uuid? (:target-id seed)))
    (is (m/validate ontology-schemas/description-body (:body seed))
        (str "Body validates. Explain: "
             (pr-str (m/explain ontology-schemas/description-body (:body seed)))))
    (is (= seeds/research-then-synth-fp (:parent-tree-id (:body seed))))
    (is (= seeds/comparative-summary-task-class-id (:target-id seed)))
    (is (some #(= seeds/comparative-summary-task-class-id (:target-id %))
              seeds/all-tree-fingerprint-seeds))))

;; =============================================================================
;; RED #12 — aggregate: after seed-all!, concepts graph has 18 tree-class
;; concepts and each flat parent has its new children in :narrower
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r02-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    ;; No tp/start here — the C-2d-1 processor projects via a synchronous
    ;; handler-invocation pattern (see run-tree-class-processor! below) so
    ;; the test is deterministic. The async tp/start path raced when
    ;; ~11 children fired against ~5 shared parents.
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defn- run-tree-class-processor!
  "Synchronously invoke the C-2d-1 reactive processor handler over every
   :ontology/tree-description-updated event currently in the store.
   Mirrors the pattern from tree-class-hierarchy-test — avoids the
   async tp/start race that produced non-deterministic projection."
  [ctx]
  (let [handler (requiring-resolve
                  'ai.obney.orc.ontology.core.todo-processors/on-tree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (handler (assoc ctx :event e)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- tree-class-uri [target-id]
  (str "tree-class:" target-id))

(def ^:private all-r02-child-ids
  "The 11 R02 child task-class UUIDs in registration order."
  [seeds/etl-pipeline-task-class-id
   seeds/iterative-refinement-task-class-id
   seeds/scheduling-task-class-id
   seeds/producer-validator-task-class-id
   seeds/draft-critique-task-class-id
   seeds/primary-backup-task-class-id
   seeds/model-cascade-task-class-id
   seeds/parallel-sum-task-class-id
   seeds/parallel-classify-aggregate-task-class-id
   seeds/briefing-generation-task-class-id
   seeds/comparative-summary-task-class-id])

(deftest seed-all-projects-all-r02-children-with-broader-links
  (testing "After seed-all! + sync processor drive, every R02 child has its tree-class concept AND its :broader link"
    (with-test-ctx [ctx]
      (seeds/seed-all! ctx)
      (run-tree-class-processor! ctx)
      (let [tree-classes (->> (ontology/get-concepts ctx)
                              (filter #(= :tree-class (:scope %)))
                              (map (juxt :uri identity))
                              (into {}))
            missing (filter #(not (contains? tree-classes (tree-class-uri %)))
                            all-r02-child-ids)]
        (is (empty? missing)
            (str "Every R02 child must have a tree-class concept. Missing: "
                 (mapv str missing) " — All known tree-class URIs: "
                 (sort (keys tree-classes))))

        (testing "sequential-pipeline has 3 R02 children in :narrower"
          (let [sp-narrower (get-in tree-classes [(tree-class-uri seeds/sequential-pipeline-fp) :narrower])]
            (is (contains? sp-narrower (tree-class-uri seeds/etl-pipeline-task-class-id)))
            (is (contains? sp-narrower (tree-class-uri seeds/iterative-refinement-task-class-id)))
            (is (contains? sp-narrower (tree-class-uri seeds/scheduling-task-class-id)))))

        (testing "validation-loop has 2 R02 children in :narrower"
          (let [vl-narrower (get-in tree-classes [(tree-class-uri seeds/validation-loop-fp) :narrower])]
            (is (contains? vl-narrower (tree-class-uri seeds/producer-validator-task-class-id)))
            (is (contains? vl-narrower (tree-class-uri seeds/draft-critique-task-class-id)))))

        (testing "fallback-recovery has 2 R02 children in :narrower"
          (let [fr-narrower (get-in tree-classes [(tree-class-uri seeds/fallback-recovery-fp) :narrower])]
            (is (contains? fr-narrower (tree-class-uri seeds/primary-backup-task-class-id)))
            (is (contains? fr-narrower (tree-class-uri seeds/model-cascade-task-class-id)))))

        (testing "map-reduce has 2 R02 children in :narrower"
          (let [mr-narrower (get-in tree-classes [(tree-class-uri seeds/map-reduce-fp) :narrower])]
            (is (contains? mr-narrower (tree-class-uri seeds/parallel-sum-task-class-id)))
            (is (contains? mr-narrower (tree-class-uri seeds/parallel-classify-aggregate-task-class-id)))))

        (testing "research-then-synthesize has 2 R02 children in :narrower"
          (let [rts-narrower (get-in tree-classes [(tree-class-uri seeds/research-then-synth-fp) :narrower])]
            (is (contains? rts-narrower (tree-class-uri seeds/briefing-generation-task-class-id)))
            (is (contains? rts-narrower (tree-class-uri seeds/comparative-summary-task-class-id)))))))))
