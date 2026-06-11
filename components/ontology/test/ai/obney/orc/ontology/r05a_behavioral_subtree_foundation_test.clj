(ns ai.obney.orc.ontology.r05a-behavioral-subtree-foundation-test
  "R05a — Foundation slice of C-2e (behavioral subtree layer).

   Covers:
   - Optional :scope, :composes-into, :parent-behavior on description-body
   - :behavioral-subtree value on the ontology-scope enum
   - Optional :behavioral-subtrees on :ontology/task-classified + :ontology/assign-task-class
   - Reactive processor projects behavioral-subtree concepts +
     behavior:composes-into edges into :ontology/concepts
   - 11 hand-authored top-level behavioral-subtree seeds with the audit
     table's cross-references to R02 children
   - Every abstract structural pattern is referenced by at least one
     behavioral seed's :composes-into
   - End-to-end: seed-all! + sync processor → concepts graph contains
     the post-R02 tree-class baseline (UNCHANGED) PLUS 11 behavioral-subtree
     concepts PLUS the expected behavior:composes-into edges

   R02 stays UNCHANGED — no R02 seeds deprecated, no R02 tests modified."
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
;; Test context helpers (mirror tree_class_hierarchy_test.clj)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r05a-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
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

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- base-body []
  {:capabilities ["x"]
   :strengths []
   :weaknesses []
   :representative-uses ["x"]
   :avoid-when ["x"]
   :summary "x"
   :version 1
   :consolidated-from-event-count 1})

;; =============================================================================
;; RED #1 — description-body schema accepts optional :scope :behavioral-subtree
;; =============================================================================

(deftest description-body-schema-accepts-scope
  (testing "description-body validates WITHOUT :scope (legacy shape)"
    (is (m/validate ontology-schemas/description-body (base-body))
        "Legacy descriptions without :scope remain valid"))

  (testing "description-body validates WITH :scope :behavioral-subtree"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :scope :behavioral-subtree))
        ":scope :behavioral-subtree is accepted"))

  (testing "description-body validates WITH :scope :tree-class"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :scope :tree-class))
        ":scope :tree-class remains accepted (existing scope)"))

  (testing "description-body REJECTS :scope with a non-enum value"
    (is (not (m/validate ontology-schemas/description-body
                         (assoc (base-body) :scope :invalid-scope)))
        "Non-enum :scope values are rejected")))

;; =============================================================================
;; RED #2 — description-body accepts optional :composes-into and :parent-behavior
;; =============================================================================

(deftest description-body-schema-accepts-composes-into-and-parent-behavior
  (testing "description-body validates with :composes-into vector of UUIDs"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :composes-into [(random-uuid) (random-uuid)]))
        "Vector of UUIDs is accepted in :composes-into"))

  (testing "description-body validates with :composes-into vector of strings"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :composes-into ["seed:tree:ChunkedExtraction"]))
        "Vector of string fingerprints is accepted in :composes-into"))

  (testing "description-body validates with :composes-into mixing UUIDs and strings"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :composes-into [(random-uuid) "seed:tree:X"]))
        "Mixed UUID + string :composes-into is accepted"))

  (testing "description-body validates with :parent-behavior as UUID"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :parent-behavior (random-uuid)))
        ":parent-behavior as UUID is accepted"))

  (testing "description-body validates with :parent-behavior as string"
    (is (m/validate ontology-schemas/description-body
                    (assoc (base-body) :parent-behavior "seed:behavior:analysis"))
        ":parent-behavior as string is accepted"))

  (testing "description-body REJECTS :parent-behavior of wrong type"
    (is (not (m/validate ontology-schemas/description-body
                         (assoc (base-body) :parent-behavior 42)))
        "Integer :parent-behavior is rejected")))

;; =============================================================================
;; RED #3 — ontology-scope enum accepts :behavioral-subtree
;; =============================================================================

(deftest ontology-scope-enum-accepts-behavioral-subtree
  (testing ":behavioral-subtree is a valid ontology-scope value"
    (is (m/validate ontology-schemas/ontology-scope :behavioral-subtree)
        ":behavioral-subtree is accepted by the ontology-scope enum"))

  (testing "Existing scope values remain valid"
    (doseq [scope [:failure :success :problem :node-type :custom :tree-class]]
      (is (m/validate ontology-schemas/ontology-scope scope)
          (str scope " remains a valid ontology-scope value")))))

;; =============================================================================
;; RED #4 — :ontology/task-classified event body accepts optional :behavioral-subtrees
;; =============================================================================

(defn- valid-task-classified-body []
  {:source-sheet-id (random-uuid)
   :source-tick-id (random-uuid)
   :source-node-id (random-uuid)
   :assigned-tree-id (random-uuid)
   :confidence 0.9
   :top-candidates []
   :reasoning "x"
   :classified-at "2026-05-28T00:00:00Z"
   :was-fresh-mint? false})

(defn- valid-assign-task-class-body []
  {:source-sheet-id (random-uuid)
   :source-tick-id (random-uuid)
   :source-node-id (random-uuid)
   :assigned-tree-id (random-uuid)
   :confidence 0.9
   :top-candidates []
   :reasoning "x"
   :was-fresh-mint? false})

(deftest task-classified-event-body-accepts-behavioral-subtrees
  (let [event-schema (get @schema-util/registry* :ontology/task-classified)
        cmd-schema (get @schema-util/registry* :ontology/assign-task-class)]

    (testing ":ontology/task-classified validates WITHOUT :behavioral-subtrees (legacy)"
      (is (m/validate event-schema (valid-task-classified-body))
          "Legacy task-classified events without :behavioral-subtrees remain valid"))

    (testing ":ontology/task-classified validates WITH :behavioral-subtrees"
      (is (m/validate event-schema
                      (assoc (valid-task-classified-body)
                             :behavioral-subtrees
                             [{:behavior-id (random-uuid) :confidence 0.85}]))
          ":behavioral-subtrees vector of maps is accepted"))

    (testing ":ontology/assign-task-class validates WITHOUT :behavioral-subtrees"
      (is (m/validate cmd-schema (valid-assign-task-class-body))
          "Legacy command without :behavioral-subtrees remains valid"))

    (testing ":ontology/assign-task-class validates WITH :behavioral-subtrees"
      (is (m/validate cmd-schema
                      (assoc (valid-assign-task-class-body)
                             :behavioral-subtrees
                             [{:behavior-id (random-uuid) :confidence 0.85}]))
          ":behavioral-subtrees forwarded through command body is accepted"))))

;; =============================================================================
;; Processor helpers — drive the new R05a reactive processor synchronously
;; =============================================================================

(defn- emit-behavioral-subtree-description!
  "Dispatch :ontology/record-tree-description with a description body
   carrying :scope :behavioral-subtree. The defcommand wraps it; the
   event lands; we then drive the R05a processor over it."
  [ctx target-id {:keys [parent-behavior composes-into]}]
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
                           :consolidated-from-event-count 1
                           :scope :behavioral-subtree}
                    parent-behavior (assoc :parent-behavior parent-behavior)
                    composes-into (assoc :composes-into composes-into))})))

(defn- run-behavioral-subtree-processor!
  "Drive the new R05a processor by reading every
   :ontology/tree-description-updated event in the store and invoking
   the handler on each. Mirrors the run-tree-class-processor! pattern."
  [ctx]
  (let [handler (requiring-resolve
                  'ai.obney.orc.ontology.core.todo-processors/on-behavioral-subtree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (handler (assoc ctx :event e)))))

(defn- behavioral-subtree-uri [target-id]
  (str "behavioral-subtree:" target-id))

(defn- tree-class-uri [target-id]
  (str "tree-class:" target-id))

;; =============================================================================
;; RED #5 — processor lazy-creates the behavioral-subtree concept
;; =============================================================================

(deftest processor-creates-behavioral-subtree-concept
  (with-test-ctx [ctx]
    (let [behavior-id (random-uuid)
          _ (emit-behavioral-subtree-description! ctx behavior-id {})
          _ (run-behavioral-subtree-processor! ctx)
          concepts (rmp/project ctx :ontology/concepts)
          uri (behavioral-subtree-uri behavior-id)]
      (testing "concept exists at behavioral-subtree:<id> URI"
        (is (contains? concepts uri)
            (str "Expected concept at URI " uri " to be lazy-created")))
      (testing "concept has :scope :behavioral-subtree"
        (is (= :behavioral-subtree (get-in concepts [uri :scope]))
            "Concept's scope is :behavioral-subtree, not :tree-class")))))

;; =============================================================================
;; RED #6 — processor emits behavior:composes-into per :composes-into entry
;; =============================================================================

(deftest processor-emits-composes-into-edges
  (with-test-ctx [ctx]
    (let [behavior-id (random-uuid)
          shell-id-1 (random-uuid)
          shell-id-2 (random-uuid)
          _ (emit-behavioral-subtree-description!
              ctx behavior-id
              {:composes-into [shell-id-1 shell-id-2]})
          _ (run-behavioral-subtree-processor! ctx)
          concepts (rmp/project ctx :ontology/concepts)
          behavior-uri (behavioral-subtree-uri behavior-id)
          shell-uri-1 (tree-class-uri shell-id-1)
          shell-uri-2 (tree-class-uri shell-id-2)
          composes-into (get-in concepts [behavior-uri :composes-into] #{})]
      (testing "behavior:composes-into edge to first shell exists"
        (is (contains? composes-into shell-uri-1)
            "Expected behavior:composes-into edge to first structural shell"))
      (testing "behavior:composes-into edge to second shell exists"
        (is (contains? composes-into shell-uri-2)
            "Expected behavior:composes-into edge to second structural shell"))
      (testing "shell's :composed-by reverse edge contains the behavior"
        (is (contains? (get-in concepts [shell-uri-1 :composed-by] #{}) behavior-uri)
            "Expected reverse edge from shell back to behavior (for R05b traversal)"))
      (testing "string fingerprint shells also accepted"
        (let [behavior-id-2 (random-uuid)
              shell-str "seed:tree:ChunkedExtraction"]
          (emit-behavioral-subtree-description!
            ctx behavior-id-2
            {:composes-into [shell-str]})
          (run-behavioral-subtree-processor! ctx)
          (let [concepts (rmp/project ctx :ontology/concepts)
                uri (behavioral-subtree-uri behavior-id-2)
                ci (get-in concepts [uri :composes-into] #{})]
            (is (contains? ci (tree-class-uri shell-str))
                "Expected behavior:composes-into edge to string-fingerprint shell")))))))

;; =============================================================================
;; RED #7 — processor emits skos:broader to parent-behavior
;; =============================================================================

(deftest processor-emits-skos-broader-to-parent-behavior
  (with-test-ctx [ctx]
    (let [parent-id (random-uuid)
          child-id (random-uuid)]
      ;; Seed the parent first so the child has something to broader to.
      (emit-behavioral-subtree-description! ctx parent-id {})
      (emit-behavioral-subtree-description! ctx child-id
                                            {:parent-behavior parent-id})
      (run-behavioral-subtree-processor! ctx)
      (let [concepts (rmp/project ctx :ontology/concepts)
            parent-uri (behavioral-subtree-uri parent-id)
            child-uri (behavioral-subtree-uri child-id)]
        (testing "child's :broader set contains parent's URI"
          (is (contains? (get-in concepts [child-uri :broader]) parent-uri)
              "Expected SKOS broader link from child to parent"))
        (testing "top-level (no parent-behavior) gets created but no broader link"
          (is (contains? concepts parent-uri)
              "Top-level parent concept exists")
          (is (empty? (get-in concepts [parent-uri :broader] #{}))
              "Top-level parent has no :broader set"))))))

;; =============================================================================
;; RED #8 — processor is idempotent (re-running on same event no-ops)
;; =============================================================================

(deftest processor-idempotent-on-repeat
  (with-test-ctx [ctx]
    (let [behavior-id (random-uuid)
          shell-id (random-uuid)
          parent-id (random-uuid)]
      (emit-behavioral-subtree-description! ctx parent-id {})
      (emit-behavioral-subtree-description!
        ctx behavior-id
        {:composes-into [shell-id]
         :parent-behavior parent-id})
      (run-behavioral-subtree-processor! ctx)
      (let [concepts-after-first (rmp/project ctx :ontology/concepts)
            ;; Re-run on the same events; should be a no-op.
            _ (run-behavioral-subtree-processor! ctx)
            concepts-after-second (rmp/project ctx :ontology/concepts)
            behavior-uri (behavioral-subtree-uri behavior-id)]
        (testing "re-running on the same event doesn't change concept count"
          (is (= (count concepts-after-first) (count concepts-after-second))
              "Concept count unchanged after second run"))
        (testing "re-running doesn't duplicate :broader entries"
          (is (= (count (get-in concepts-after-first [behavior-uri :broader] #{}))
                 (count (get-in concepts-after-second [behavior-uri :broader] #{})))
              ":broader set unchanged after second run"))))))

;; =============================================================================
;; RED #9 — C-2d-1 processor is NOT triggered by behavioral-subtree events
;;            (load-bearing: confirms the two processors don't cross-talk)
;; =============================================================================

(deftest c2d1-processor-skips-behavioral-subtree-events
  (with-test-ctx [ctx]
    (let [behavior-id (random-uuid)
          shell-id (random-uuid)
          _ (emit-behavioral-subtree-description!
              ctx behavior-id
              {:composes-into [shell-id]})
          ;; Drive ONLY the C-2d-1 processor; the new processor stays idle.
          c2d1-handler (requiring-resolve
                         'ai.obney.orc.ontology.core.todo-processors/on-tree-description-updated-project-concept)
          _ (doseq [e (into [] (es/read (:event-store ctx)
                                        {:tenant-id (:tenant-id ctx)
                                         :types #{:ontology/tree-description-updated}}))]
              (c2d1-handler (assoc ctx :event e)))
          concepts (rmp/project ctx :ontology/concepts)
          tree-class-uri-1 (tree-class-uri behavior-id)
          behavioral-uri (behavioral-subtree-uri behavior-id)]
      (testing "C-2d-1 processor doesn't project the behavioral concept under tree-class:<id>"
        (is (not (contains? concepts tree-class-uri-1))
            "Behavioral-subtree event must not leak into tree-class URI namespace"))
      (testing "And without the R05a processor running, no behavioral-subtree concept exists yet"
        (is (not (contains? concepts behavioral-uri))
            "Only the R05a processor creates behavioral-subtree:<id> concepts")))))

;; =============================================================================
;; RED #10 — 11 behavioral seeds present + each well-formed
;; =============================================================================

(deftest behavioral-seeds-present-and-well-formed
  (require 'seed-descriptions)
  (let [seeds-ns (find-ns 'seed-descriptions)
        seed (fn [sym] @(ns-resolve seeds-ns sym))
        all-bh (seed 'all-behavioral-subtree-seeds)
        principle-shaped? (ns-resolve seeds-ns 'principle-shaped?)
        description-body-well-formed? (ns-resolve seeds-ns 'description-body-well-formed?)]

    (testing "all-behavioral-subtree-seeds registry has exactly 12 entries (R05a's 11 + R07 Investigation)"
      (is (= 12 (count all-bh))
          "11 R05a competencies + 1 R07 Investigation seed"))

    (testing "each seed has stable :target-id via stable-uuid-from"
      (doseq [s all-bh]
        (is (uuid? (:target-id s)) "Each behavioral seed's :target-id is a UUID")))

    (testing "each seed body is well-formed + every strength/weakness is principle-shaped"
      (doseq [s all-bh]
        (let [body (:body s)]
          (is (description-body-well-formed? body)
              (str "Body well-formed for " (:target-id s)))
          (is (every? principle-shaped? (:strengths body))
              (str "Every strength is principle-shaped for " (:target-id s)))
          (is (every? principle-shaped? (:weaknesses body))
              (str "Every weakness is principle-shaped for " (:target-id s))))))

    (testing "each seed body declares :scope :behavioral-subtree"
      (doseq [s all-bh]
        (is (= :behavioral-subtree (get-in s [:body :scope]))
            (str (:target-id s) " carries :scope :behavioral-subtree"))))

    (testing "each seed body has nil/absent :parent-behavior (top-level bootstrap)"
      (doseq [s all-bh]
        (is (nil? (get-in s [:body :parent-behavior]))
            (str (:target-id s) " is top-level: :parent-behavior nil/absent"))))

    (testing "each seed body has non-empty :composes-into"
      (doseq [s all-bh]
        (is (seq (get-in s [:body :composes-into]))
            (str (:target-id s) " declares at least one structural shell"))))))

;; =============================================================================
;; RED #11 — cross-reference audit table (load-bearing for retrieval quality)
;; =============================================================================

(deftest cross-reference-audit-table
  (require 'seed-descriptions)
  (let [seeds-ns (find-ns 'seed-descriptions)
        seed (fn [sym] @(ns-resolve seeds-ns sym))
        ci-of (fn [seed-sym]
                (set (get-in (seed seed-sym) [:body :composes-into])))
        ;; R02 target ids
        etl-pipeline (seed 'etl-pipeline-task-class-id)
        iterative-refinement (seed 'iterative-refinement-task-class-id)
        producer-validator (seed 'producer-validator-task-class-id)
        draft-critique (seed 'draft-critique-task-class-id)
        parallel-classify-aggregate (seed 'parallel-classify-aggregate-task-class-id)
        briefing-generation (seed 'briefing-generation-task-class-id)
        comparative-summary (seed 'comparative-summary-task-class-id)
        parallel-sum (seed 'parallel-sum-task-class-id)
        ;; Abstract pattern fingerprint strings
        sp (seed 'sequential-pipeline-fp)
        pi (seed 'parallel-independent-fp)
        vl (seed 'validation-loop-fp)
        fr (seed 'fallback-recovery-fp)
        mr (seed 'map-reduce-fp)
        rts (seed 'research-then-synth-fp)
        ce (seed 'chunked-extraction-fp)]

    (testing "critique includes draft-critique + iterative-refinement"
      (is (contains? (ci-of 'critique-behavior-seed) draft-critique)
          "Critique → draft-critique (R02 VL child)")
      (is (contains? (ci-of 'critique-behavior-seed) iterative-refinement)
          "Critique → iterative-refinement (R02 SP child)"))

    (testing "validation includes producer-validator"
      (is (contains? (ci-of 'validation-behavior-seed) producer-validator)
          "Validation → producer-validator (R02 VL child)"))

    (testing "classification includes parallel-classify-aggregate"
      (is (contains? (ci-of 'classification-behavior-seed) parallel-classify-aggregate)
          "Classification → parallel-classify-aggregate (R02 MR child)"))

    (testing "synthesis includes briefing-generation + comparative-summary"
      (is (contains? (ci-of 'synthesis-behavior-seed) briefing-generation)
          "Synthesis → briefing-generation (R02 RTS child)")
      (is (contains? (ci-of 'synthesis-behavior-seed) comparative-summary)
          "Synthesis → comparative-summary (R02 RTS child)"))

    (testing "research includes briefing-generation"
      (is (contains? (ci-of 'research-behavior-seed) briefing-generation)
          "Research → briefing-generation (overlap with synthesis is fine)"))

    (testing "analysis includes comparative-summary"
      (is (contains? (ci-of 'analysis-behavior-seed) comparative-summary)
          "Analysis → comparative-summary (overlap with synthesis is fine)"))

    (testing "transformation includes etl-pipeline + parallel-sum"
      (is (contains? (ci-of 'transformation-behavior-seed) etl-pipeline)
          "Transformation → etl-pipeline (R02 SP child)")
      (is (contains? (ci-of 'transformation-behavior-seed) parallel-sum)
          "Transformation → parallel-sum (R02 MR child)"))

    (testing "every abstract structural pattern is referenced by ≥1 behavioral seed"
      (let [all-ci (set (mapcat (fn [s] (get-in (seed s) [:body :composes-into]))
                                '[research-behavior-seed
                                  extraction-behavior-seed
                                  analysis-behavior-seed
                                  synthesis-behavior-seed
                                  ideation-behavior-seed
                                  design-behavior-seed
                                  critique-behavior-seed
                                  validation-behavior-seed
                                  code-building-behavior-seed
                                  transformation-behavior-seed
                                  classification-behavior-seed]))]
        (doseq [[name fp] [["SequentialPipeline" sp]
                           ["ParallelIndependent" pi]
                           ["ValidationLoop" vl]
                           ["FallbackRecovery" fr]
                           ["MapReduce" mr]
                           ["ResearchThenSynthesize" rts]
                           ["ChunkedExtraction" ce]]]
          (is (contains? all-ci fp)
              (str name " (" fp ") referenced by at least one behavioral seed's :composes-into")))))))

;; =============================================================================
;; RED #12 — end-to-end: seed-all! + drive both processors → concept graph state
;; =============================================================================

(defn- drive-both-processors!
  "Synchronously drive C-2d-1's tree-class processor AND R05a's
   behavioral-subtree processor over every tree-description-updated
   event in the store. Mirrors the existing tree-class projector
   drive pattern used in r02_flat_pattern_children_test.clj."
  [ctx]
  (let [c2d1 (requiring-resolve
               'ai.obney.orc.ontology.core.todo-processors/on-tree-description-updated-project-concept)
        r05a (requiring-resolve
               'ai.obney.orc.ontology.core.todo-processors/on-behavioral-subtree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (c2d1 (assoc ctx :event e))
      (r05a (assoc ctx :event e)))))

(deftest end-to-end-seed-all-projects-both-layers
  (with-test-ctx [ctx]
    (require 'seed-descriptions)
    (let [seed-all! (requiring-resolve 'seed-descriptions/seed-all!)
          _ (seed-all! ctx)
          _ (drive-both-processors! ctx)
          concepts (rmp/project ctx :ontology/concepts)
          by-scope (group-by :scope (vals concepts))]

      (testing "tree-class concept count UNCHANGED from R02 baseline (23 tree-class concepts)"
        ;; 12 baseline (C-2d-1) + 11 R02 children = 23 tree-class concepts
        (is (= 23 (count (get by-scope :tree-class)))
            "R02 stays unchanged — 23 tree-class concepts after seed-all!"))

      (testing "12 behavioral-subtree concepts created (R05a's 11 + R07 Investigation)"
        (is (= 12 (count (get by-scope :behavioral-subtree)))
            "12 behavioral-subtree concepts — R05a's 11 top-level competencies + R07's Investigation"))

      (testing "behavior:composes-into edges exist on every behavioral seed"
        (require 'seed-descriptions)
        (let [seeds-ns (find-ns 'seed-descriptions)
              seed (fn [sym] @(ns-resolve seeds-ns sym))
              all-bh (seed 'all-behavioral-subtree-seeds)]
          (doseq [s all-bh]
            (let [uri (behavioral-subtree-uri (:target-id s))
                  ci (get-in concepts [uri :composes-into] #{})]
              (is (= (count (get-in s [:body :composes-into])) (count ci))
                  (str uri " has all " (count (get-in s [:body :composes-into]))
                       " composes-into edges from its seed"))))))

      (testing "shells carry :composed-by reverse edges from behaviors that compose into them"
        (require 'seed-descriptions)
        (let [seeds-ns (find-ns 'seed-descriptions)
              seed (fn [sym] @(ns-resolve seeds-ns sym))
              draft-critique (seed 'draft-critique-task-class-id)
              critique-uri (behavioral-subtree-uri (seed 'critique-behavior-id))
              shell-uri (tree-class-uri draft-critique)]
          (is (contains? (get-in concepts [shell-uri :composed-by]) critique-uri)
              "draft-critique shell's :composed-by contains critique behavior"))))))
