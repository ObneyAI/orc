(ns ai.obney.orc.ontology.el4-harvest-test
  "EL-4 (ADR 0015, emergence loop TERMINUS): HARVEST — crystallize a
   recurring + well-scored + coherent :tree-class into a named durable
   behavioral-subtree.

   Three vertical slices:
     Slice 1 — tree-class-judge-averages STANDING read-model (queryable at
               harvest time). Correctness ORACLE: the projected per-class
               per-judge mean EQUALS the consolidator's private
               tree-class-aggregate-metrics :judge-averages for the SAME
               event stream (parity).
     Slice 2 — the pure conservative harvest GATE (truth-table).
     Slice 3 — the harvest PROCESSOR: gate → mint-behavioral-subtree with
               :provenance :harvested, fire-once, waterfall parent."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            ;; Register the :evaluation/record-judge-score command handler +
            ;; its schemas so judge-score! actually emits :judge/score-emitted.
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            ;; CC-24b (ADR 0029): the harvest floors are lattice points of the
            ;; SHIPPED judge scale, so the gate's tests pin the real scale.
            [ai.obney.orc.evaluation.core.scale :as scale]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog.core :as mulog-core]))

;; ---------------------------------------------------------------------------
;; Stub the consolidator's reflection LLM so autonomous consolidations fired
;; by the threshold processor complete fast + deterministically (same reason
;; as consolidation_trigger_test — avoid real OpenRouter + retry-loop bleed).
;; ---------------------------------------------------------------------------
(defn- stub-predict-fixture [f]
  (with-redefs [llm/predict
                (fn [_provider _module _inputs _options]
                  {:outputs {:capabilities ["x"]
                             :strengths [{:trait "x" :good-when "x"
                                          :recommended-pattern "x"
                                          :confidence 1.0 :evidence-count 1
                                          :first-observed-at "2026-06-08T00:00:00Z"
                                          :last-reinforced-at "2026-06-08T00:00:00Z"}]
                             :weaknesses []
                             :representative-uses ["x"]
                             :avoid-when []
                             :summary "el4 stub"}
                   :usage {:total-tokens 1}
                   :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

;; ---------------------------------------------------------------------------
;; Context helpers (mirror consolidation_trigger_test)
;; ---------------------------------------------------------------------------
(defn- create-context
  "Default: every registered todo-processor running (the end-to-end shape).

   CC-26: `{:processors? false}` starts the SAME event store + read-model
   plumbing with NO todo-processors. The harvest processor is subscribed to
   :ontology/task-classified, so in a processor-full context a class
   auto-harvests the instant it crosses the gate MID-FIXTURE — which makes any
   fixture whose LATER occurrences carry the signal under test untestable (the
   mint fires before the fixture finishes). Read-models are projected on demand
   by rmp/project, not by a todo-processor, so a processor-free context still
   reads back the real projections; the test drives maybe-harvest! explicitly."
  ([] (create-context {:processors? true}))
  ([{:keys [processors?]}]
   (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/el4-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (if processors?
                     (reduce-kv
                       (fn [acc proc-name {:keys [handler-fn topics]}]
                         (assoc acc proc-name
                                (tp/start {:event-pubsub ps :topics topics
                                           :handler-fn handler-fn :context base-ctx})))
                       {} @tp/processor-registry*)
                     {})]
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

(defmacro with-gate-ctx
  "CC-26: a processor-free context — the fixture, not the harvest processor,
   decides when maybe-harvest! runs."
  [[sym] & body]
  `(let [~sym (create-context {:processors? false})]
     (try ~@body (finally (stop-context ~sym)))))

;; ---------------------------------------------------------------------------
;; Event-stream fixtures — real commands, explicit sheet-ids so the
;; sheet -> tree-class JOIN is deterministic.
;; ---------------------------------------------------------------------------
(defn- classify!
  ([ctx sheet-id tree-class-id] (classify! ctx sheet-id tree-class-id (random-uuid)))
  ([ctx sheet-id tree-class-id tick-id]
   (cp/process-command
     (assoc ctx :command
            {:command/name :ontology/assign-task-class
             :command/id (random-uuid)
             :command/timestamp (time/now)
             :source-sheet-id sheet-id
             :source-tick-id tick-id
             :source-node-id (random-uuid)
             :assigned-tree-id tree-class-id
             :confidence 0.95
             :top-candidates []
             :reasoning "test"
             :was-fresh-mint? false}))))

(defn- judge-score!
  ([ctx sheet-id judge-name score] (judge-score! ctx sheet-id (random-uuid) judge-name score))
  ([ctx sheet-id tick-id judge-name score]
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
             :dimensions []}))))

;; ===========================================================================
;; SLICE 1 — tree-class-judge-averages read-model PARITY
;; ===========================================================================

(deftest slice1-judge-averages-read-model-parity
  (testing "get-tree-class-judge-averages == consolidator's private tree-class-aggregate-metrics :judge-averages for the SAME event stream"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            class-b (random-uuid)
            sheet-a1 (random-uuid)
            sheet-a2 (random-uuid)
            sheet-b1 (random-uuid)
            tick-a1 (random-uuid)
            tick-a2 (random-uuid)
            tick-b1 (random-uuid)]
        ;; class-a: two sheets (each its own occurrence/tick), judge :quality
        ;; scored twice + :fit once — score calls reuse the SAME tick-id as
        ;; their occurrence's classify! call, mirroring how a real turn's
        ;; classify + judge-score events share one tick-id.
        (classify! ctx sheet-a1 class-a tick-a1)
        (classify! ctx sheet-a2 class-a tick-a2)
        (judge-score! ctx sheet-a1 tick-a1 "quality" 0.8)
        (judge-score! ctx sheet-a2 tick-a2 "quality" 0.6)   ;; quality mean = 0.7
        (judge-score! ctx sheet-a1 tick-a1 "fit" 0.9)       ;; fit mean = 0.9
        ;; class-b: one sheet, judge :quality once
        (classify! ctx sheet-b1 class-b tick-b1)
        (judge-score! ctx sheet-b1 tick-b1 "quality" 0.2)   ;; quality mean = 0.2
        (Thread/sleep 250)

        ;; ORACLE: the consolidator's real (private) aggregate
        (let [agg-a (#'consolidator/tree-class-aggregate-metrics ctx class-a)
              agg-b (#'consolidator/tree-class-aggregate-metrics ctx class-b)
              rm-a  (ontology/get-tree-class-judge-averages ctx class-a)
              rm-b  (ontology/get-tree-class-judge-averages ctx class-b)]
          (is (= (:judge-averages agg-a) rm-a)
              (str "class-a parity. aggregate=" (:judge-averages agg-a) " read-model=" rm-a))
          (is (= (:judge-averages agg-b) rm-b)
              (str "class-b parity. aggregate=" (:judge-averages agg-b) " read-model=" rm-b))
          ;; Non-vacuous: the values are the real means, not both-nil.
          (is (= {"quality" 0.7 "fit" 0.9} rm-a) "class-a means computed")
          (is (= {"quality" 0.2} rm-b) "class-b mean computed"))))))

(deftest slice1-no-scores-returns-nil-like-aggregate
  (testing "A class with classifications but NO judge scores -> read-model nil, matching aggregate (which omits :judge-averages)"
    (with-test-ctx [ctx]
      (let [class-c (random-uuid)
            sheet-c1 (random-uuid)]
        (classify! ctx sheet-c1 class-c)
        (Thread/sleep 200)
        (let [agg (#'consolidator/tree-class-aggregate-metrics ctx class-c)]
          (is (nil? (:judge-averages agg)) "aggregate omits judge-averages when no scores")
          (is (nil? (ontology/get-tree-class-judge-averages ctx class-c))
              "read-model returns nil when no scores — parity on the empty case"))))))

(deftest slice1-reclassification-does-not-corrupt-sibling-classes
  (testing "SJ-1: the SAME shared sheet-id (the static workflow-definition sheet every turn of one task-shape
             reuses) is classified to TWO different tree-classes across separate occurrences — each class's
             judge-average must reflect ONLY its own occurrences' scores, not get misattributed via a
             sheet-id-only join that the temporally-last classification silently overwrites"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            class-b (random-uuid)
            shared-sheet (random-uuid)
            tick-1 (random-uuid)
            tick-2 (random-uuid)
            tick-3 (random-uuid)]
        ;; occurrences 1 & 2 classify the shared sheet onto class-a
        (classify! ctx shared-sheet class-a tick-1)
        (judge-score! ctx shared-sheet tick-1 "quality" 0.9)
        (classify! ctx shared-sheet class-a tick-2)
        (judge-score! ctx shared-sheet tick-2 "quality" 0.7)
        ;; occurrence 3 — the SAME shared sheet-id, RECLASSIFIED to a DIFFERENT
        ;; class (class-b), temporally LAST — this is exactly the ordering that
        ;; corrupts the old sheet-id-only join
        (classify! ctx shared-sheet class-b tick-3)
        (judge-score! ctx shared-sheet tick-3 "quality" 0.2)
        (Thread/sleep 250)

        (let [rm-a (ontology/get-tree-class-judge-averages ctx class-a)
              rm-b (ontology/get-tree-class-judge-averages ctx class-b)]
          (is (= {"quality" 0.8} rm-a)
              (str "class-a (occurrences 1+2 only) mean should be (0.9+0.7)/2=0.8, got " rm-a))
          (is (= {"quality" 0.2} rm-b)
              (str "class-b (occurrence 3 only) mean should be 0.2 — NOT diluted by class-a's scores, got " rm-b)))))))

;; ===========================================================================
;; HP-2 — production-faithful execution<->classification linkage
;; ===========================================================================
;; In PRODUCTION the :sheet/rlm-tree-execution-completed bookend carries the
;; EPHEMERAL Phase-2 sheet in :sheet-id and its own ephemeral tick in
;; :tick-id — the classified HOST sheet + TURN tick live in :source-sheet-id
;; / :source-tick-id. Pre-HP-2 the shape/exec/judge aggregate joins matched
;; the host sheet-id set against the ephemeral :sheet-id (disjoint domains →
;; 0 rows always), and the aggregate judge join was bare-shared-sheet (every
;; class absorbed every class's scores). These fixtures use DISJOINT ids for
;; every axis a production event would have distinct — the SJ-1 lesson:
;; same-id fixtures hide exactly this bug family.

(defn- production-occurrence!
  "One PRODUCTION-SHAPED observation: classification + judge score on the
   shared HOST sheet + per-turn tick; bookend on its own EPHEMERAL sheet +
   ephemeral tick, carrying the host linkage in :source-sheet-id /
   :source-tick-id."
  [ctx class-id host-sheet turn-tick fingerprint score]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :source-sheet-id host-sheet
            :source-tick-id turn-tick
            :source-node-id (random-uuid)
            :assigned-tree-id class-id
            :confidence 0.95
            :top-candidates []
            :reasoning "test"
            :was-fresh-mint? false}))
  (judge-score! ctx host-sheet turn-tick "quality" score)
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/record-rlm-tree-execution-completion
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id (random-uuid)          ;; EPHEMERAL Phase-2 sheet
            :tick-id (random-uuid)           ;; EPHEMERAL Phase-2 tick
            :source-sheet-id host-sheet
            :source-tick-id turn-tick
            :trajectory []
            :total-usage {:total-tokens 0}
            :tree-fingerprint fingerprint
            :status :success
            :duration-ms 100})))

(deftest hp2-distinct-tree-shapes-production-faithful
  (testing "distinct-tree-shapes counts the class's executions' fingerprints via the
             [source-sheet-id source-tick-id] linkage — NOT the ephemeral :sheet-id"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            host (random-uuid)]
        (production-occurrence! ctx class-a host (random-uuid) "shape-A" 0.9)
        (production-occurrence! ctx class-a host (random-uuid) "shape-B" 0.9)
        (Thread/sleep 250)
        (is (= 2 (harvest/distinct-tree-shapes ctx class-a))
            "two production-shaped occurrences with two fingerprints -> 2 distinct shapes")))))

(deftest hp2-consolidator-gather-attaches-execution-evidence
  (testing "gather-recent-tree-class-events joins each observation to its bookend via
             [source-sheet-id source-tick-id] so the reflection LLM sees execution evidence"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            host (random-uuid)]
        (production-occurrence! ctx class-a host (random-uuid) "shape-A" 0.9)
        (Thread/sleep 250)
        (let [obs (#'consolidator/gather-recent-tree-class-events ctx class-a)]
          (is (= 1 (count obs)))
          (is (some? (:execution (first obs)))
              "observation carries the joined :execution submap (was ALWAYS nil pre-HP-2)")
          (is (= "shape-A" (get-in (first obs) [:execution :tree-fingerprint]))))))))

(deftest hp2-aggregate-metrics-production-faithful
  (testing "tree-class-aggregate-metrics counts successes/failures/shapes via the
             occurrence linkage (all were 0 pre-HP-2 due to the disjoint-domain filter)"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            host (random-uuid)]
        (production-occurrence! ctx class-a host (random-uuid) "shape-A" 0.9)
        (production-occurrence! ctx class-a host (random-uuid) "shape-A" 0.7)
        (Thread/sleep 250)
        (let [agg (#'consolidator/tree-class-aggregate-metrics ctx class-a)]
          (is (= 2 (:total-assignments agg)))
          (is (= 2 (:success-count agg)) "successes counted via the pair join")
          (is (= 1 (:distinct-tree-shapes agg)) "one distinct fingerprint"))))))

(deftest hp2-aggregate-judge-scoped-to-occurrence
  (testing "aggregate :judge-averages is scoped by [sheet-id tick-id] occurrence pairs —
             two classes sharing one HOST sheet must NOT absorb each other's scores
             (the pre-SJ-1 misattribution that survived in the consolidator's copy)"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            class-b (random-uuid)
            host (random-uuid)]
        (production-occurrence! ctx class-a host (random-uuid) "shape-A" 1.0)
        (production-occurrence! ctx class-b host (random-uuid) "shape-B" 0.0)
        (Thread/sleep 250)
        (let [agg-a (#'consolidator/tree-class-aggregate-metrics ctx class-a)
              agg-b (#'consolidator/tree-class-aggregate-metrics ctx class-b)]
          (is (= {"quality" 1.0} (:judge-averages agg-a))
              (str "class-a sees ONLY its own occurrence's score, got " (:judge-averages agg-a)))
          (is (= {"quality" 0.0} (:judge-averages agg-b))
              (str "class-b sees ONLY its own occurrence's score, got " (:judge-averages agg-b))))))))

;; ===========================================================================
;; SLICE 2 — the conservative harvest GATE (pure fn truth-table)
;; ===========================================================================

(def ^:private good-class
  "Recurring + well-scored on BOTH axes + coherent. CC-26: the single
   :judge-average scalar was replaced by the two marginals it had flattened —
   :judge-trailing-averages (per DIMENSION, over the class's most recent
   :dimension-window scored occurrences — CC-24b/ADR 0029 replaced the
   lifetime mean here) and :occurrence-scores (per OCCURRENCE, temporal order,
   most recent last)."
  {:occurrences 12
   :judge-trailing-averages {"quality" 0.85}
   :occurrence-scores (vec (repeat 12 0.85))
   :distinct-tree-shapes 3})

(deftest slice2-gate-passes-the-good-class
  (testing "recurring + well-scored + coherent -> harvest-candidate? true"
    (is (true? (harvest/harvest-candidate? good-class harvest/default-harvest-config)))))

(deftest slice2-gate-fails-below-occurrences
  (testing "below the occurrence floor -> false (not recurring enough)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :occurrences 5)
                  harvest/default-harvest-config)))))

(deftest slice2-gate-fails-below-consistency-floor
  (testing "occurrences that all sit below the consistency floor -> false (not well-scored)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :occurrence-scores (vec (repeat 12 0.6)))
                  harvest/default-harvest-config)))))

(deftest slice2-gate-fails-below-dimension-floor
  (testing "a judge dimension below the dimension floor -> false, even when every occurrence's
             aggregate clears the consistency floor (the axes are independent)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :judge-trailing-averages {"quality" 1.0 "grounding" 0.6})
                  harvest/default-harvest-config)))))

(deftest slice2-gate-fails-grab-bag
  (testing "too many distinct tree-shapes relative to occurrences -> false (grab-bag, not a coherent cluster)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :distinct-tree-shapes 11)
                  harvest/default-harvest-config)))))

(deftest slice2-gate-fails-nil-judge-signal
  (testing "no judge signal at all -> false (cannot be well-scored) — on EITHER axis"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :occurrence-scores nil)
                  harvest/default-harvest-config)))
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :occurrence-scores [])
                  harvest/default-harvest-config))
        "an empty score history is not 'has qualified repeatedly'")
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :judge-trailing-averages nil)
                  harvest/default-harvest-config))
        "a class no judge ever scored has not been judged well")
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :judge-trailing-averages {})
                  harvest/default-harvest-config)))))

(deftest slice2-knobs-are-tunable
  (testing "loosening the config flips a class that fails under defaults to pass"
    (let [borderline {:occurrences 6
                      :judge-trailing-averages {"quality" 0.7}
                      :occurrence-scores (vec (repeat 6 0.7))
                      :distinct-tree-shapes 4}]
      (is (false? (harvest/harvest-candidate? borderline harvest/default-harvest-config))
          "fails under the HIGH default bar")
      (is (true? (harvest/harvest-candidate?
                   borderline
                   {:min-occurrences 5 :dimension-floor 0.65
                    :consistency-window 5 :consistency-floor 0.65
                    :max-shapes-ratio 0.8}))
          "passes under a deliberately looser config"))))

;; ===========================================================================
;; SLICE 3 — the harvest PROCESSOR / maybe-harvest! orchestration
;; ===========================================================================

(defn- record-tree-class-desc! [ctx class-id body]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-tree-class-description
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-id class-id
            :body body})))

(defn- seed-parent-behavior! [ctx parent-id]
  ;; Seed an ABSTRACT behavioral parent (no broader) so nearest-abstract
  ;; resolution lands on it. R05a projects behavioral-subtree:<parent-id>.
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
   sheet/tick carrying the [source-sheet-id source-tick-id] linkage (HP-2 —
   the earlier same-sheet/same-tick bookend fixture hid the disjoint-domain
   join bugs exactly the way SJ-1's same-id fixtures did)."
  [ctx class-id sheet-id fingerprint score behavioral-subtrees]
  (let [tick-id (random-uuid)]
    (cp/process-command
      (assoc ctx :command
             (cond-> {:command/name :ontology/assign-task-class
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :source-sheet-id sheet-id
                      :source-tick-id tick-id
                      :source-node-id (random-uuid)
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
              :sheet-id (random-uuid)          ;; EPHEMERAL Phase-2 sheet
              :tick-id (random-uuid)           ;; EPHEMERAL Phase-2 tick
              :source-sheet-id sheet-id
              :source-tick-id tick-id
              :trajectory []
              :total-usage {:total-tokens 0}
              :tree-fingerprint fingerprint
              :status :success
              :duration-ms 100}))))

(def ^:private good-body
  {:capabilities ["classify a document then extract fields per class"]
   :strengths [{:trait "route by class before extraction"
                :good-when "documents fall into a few stable classes"
                :recommended-pattern "[:llm {:reads [:doc] :writes [:class]}] [:map-each ...]"
                :confidence 0.9 :evidence-count 12
                :first-observed-at "2026-06-01T00:00:00Z"
                :last-reinforced-at "2026-06-20T00:00:00Z"}]
   :weaknesses []
   :representative-uses ["mixed-format intake pipelines"]
   :avoid-when ["single-class corpora — routing is wasted overhead"]
   :summary "Classify-then-extract routing behavior for mixed document intake."
   :version 3
   :consolidated-from-event-count 12})

(defn- setup-good-class!
  "Seed a recurring + well-scored + coherent tree-class with a consolidated
   description + a resolvable abstract parent. Returns {:class-id :parent-id}."
  [ctx]
  (let [class-id (random-uuid)
        parent-id (random-uuid)]
    (seed-parent-behavior! ctx parent-id)
    (record-tree-class-desc! ctx class-id good-body)
    (dotimes [i 12]
      (occurrence! ctx class-id (random-uuid) "shape-A" 0.85
                   (when (zero? i) [{:behavior-id parent-id :confidence 0.9 :reasoning "x"}])))
    (Thread/sleep 300)
    {:class-id class-id :parent-id parent-id}))

(defn- minted-harvest-events [ctx class-id]
  (->> (into [] (es/read (:event-store ctx)
                         {:types #{:ontology/behavioral-subtree-minted}
                          :tenant-id (:tenant-id ctx)}))
       (filter #(and (= :harvested (:provenance %))
                     (= class-id (:harvested-from-tree-class %))))))

(deftest slice3-harvests-the-good-class
  (testing "maybe-harvest! promotes a recurring+well-scored+coherent class to a named behavioral-subtree (read back the ontology)"
    (with-test-ctx [ctx]
      (let [{:keys [class-id parent-id]} (setup-good-class! ctx)]
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 200)
        (let [minted (minted-harvest-events ctx class-id)]
          (is (= 1 (count minted)) "exactly one harvested behavior minted")
          (let [ev (first minted)
                target-id (:target-id ev)
                desc (ontology/get-description ctx :tree-fingerprint target-id)]
            (is (= :harvested (:provenance ev)) "provenance is :harvested (distinct audit trail)")
            (is (= parent-id (:parent-behavior ev)) "parent-behavior = nearest abstract via skos:broader")
            (is (= (str "harvested-tree-class-" class-id) (:name ev))
                "stable name derived from the tree-class identity")
            (is (uuid? target-id) "stable derived target-id")
            ;; read the ontology back — never trust the return value
            (is (some? desc) "harvested behavior is stored + retrievable in the ontology")
            (is (= :behavioral-subtree (:scope desc)) "stamped :scope :behavioral-subtree")
            (is (= (:avoid-when good-body) (:avoid-when desc))
                "consolidated :avoid-when transplanted")
            (is (string? (:recommended-pattern desc)) "worked DSL recorded as :recommended-pattern")
            ;; Harvest fires the MOMENT the gate clears (>= min-occurrences),
            ;; stamping the occurrence count at that crossing (>= 10). The
            ;; registered processor may fire during setup at exactly 10, so
            ;; assert the floor, not the final 12.
            (is (>= (:consolidated-from-event-count desc) 10)
                "stamped :consolidated-from-event-count (>= gate floor) so anti-recency engages")))))))

(deftest slice3-fires-once-per-crossing
  (testing "a second maybe-harvest! after harvesting does NOT mint again (fire-once via class-id guard)"
    (with-test-ctx [ctx]
      (let [{:keys [class-id]} (setup-good-class! ctx)]
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (true? (harvest/already-harvested? ctx class-id)) "guard sees the harvest")
        (harvest/maybe-harvest! ctx class-id)
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (= 1 (count (minted-harvest-events ctx class-id)))
            "still exactly one mint — no re-harvest spam")))))

(deftest slice3-skips-junk-below-occurrences
  (testing "a class with too few occurrences is NOT harvested (the gate skips junk — proven, not hidden)"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            parent-id (random-uuid)]
        (seed-parent-behavior! ctx parent-id)
        (record-tree-class-desc! ctx class-id good-body)
        (dotimes [i 4]
          (occurrence! ctx class-id (random-uuid) "shape-A" 0.85
                       (when (zero? i) [{:behavior-id parent-id :confidence 0.9 :reasoning "x"}])))
        (Thread/sleep 250)
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (false? (harvest/already-harvested? ctx class-id)) "junk not marked harvested")
        (is (empty? (minted-harvest-events ctx class-id)) "no behavior minted for a below-N class")))))

(deftest slice3-skips-grab-bag
  (testing "a recurring+well-scored class that is a GRAB-BAG (many distinct shapes) is NOT harvested"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            parent-id (random-uuid)]
        (seed-parent-behavior! ctx parent-id)
        (record-tree-class-desc! ctx class-id good-body)
        ;; 12 occurrences but a DIFFERENT fingerprint each time -> grab-bag
        (dotimes [i 12]
          (occurrence! ctx class-id (random-uuid) (str "shape-" i) 0.85
                       (when (zero? i) [{:behavior-id parent-id :confidence 0.9 :reasoning "x"}])))
        (Thread/sleep 300)
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "grab-bag (distinct-shapes ~= occurrences) fails the coherence gate")))))

(deftest slice3-processor-drives-harvest-end-to-end
  (testing "the registered on-tree-class-check-harvest processor mints the good class from real events (no direct call)"
    (with-test-ctx [ctx]
      (let [{:keys [class-id]} (setup-good-class! ctx)]
        ;; one more real occurrence to trigger the processor after the
        ;; description + volume are already in place
        (occurrence! ctx class-id (random-uuid) "shape-A" 0.85 nil)
        (Thread/sleep 500)
        (is (= 1 (count (minted-harvest-events ctx class-id)))
            "the processor auto-harvested the good class end-to-end")))))

;; ===========================================================================
;; CC-26 — the harvest gate: consistency over TIME, floors across DIMENSIONS
;; ===========================================================================
;;
;; The defect: harvest-candidate? gated on ONE scalar — a mean over per-judge
;; lifetime means, each itself a mean over occurrences. A mean of means. It
;; collapsed two axes the spec (rule PromoteWellScoredClass) keeps separate:
;;
;;   dimension_floor    — across JUDGES at one moment (quality.all(...))
;;   consistency_floor  — across OCCURRENCES over time (consistently_qualified)
;;
;; These fixtures keep the axes separate ON PURPOSE: the consistency fixtures
;; use ONE judge (so the dimension axis cannot be what rejects them) and the
;; dimension fixture makes every occurrence's mean exactly clear the
;; consistency floor (so the consistency axis cannot be what rejects it).
;; Each test therefore isolates the clause it names.

(def ^:private one-judge "quality")

(defn- multi-judge-occurrence!
  "One occurrence of `class-id` on the shared HOST sheet with its own turn
   tick, scored by EVERY judge in `judge->score` at that same occurrence, plus
   a production-shaped bookend carrying the [source-sheet-id source-tick-id]
   linkage."
  [ctx class-id host-sheet fingerprint judge->score behavioral-subtrees]
  (let [tick-id (random-uuid)]
    (cp/process-command
      (assoc ctx :command
             (cond-> {:command/name :ontology/assign-task-class
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :source-sheet-id host-sheet
                      :source-tick-id tick-id
                      :source-node-id (random-uuid)
                      :assigned-tree-id class-id
                      :confidence 0.95
                      :top-candidates []
                      :reasoning "test"
                      :was-fresh-mint? false}
               behavioral-subtrees (assoc :behavioral-subtrees behavioral-subtrees))))
    (doseq [[judge-name score] judge->score]
      (judge-score! ctx host-sheet tick-id judge-name score))
    (cp/process-command
      (assoc ctx :command
             {:command/name :sheet/record-rlm-tree-execution-completion
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :sheet-id (random-uuid)          ;; EPHEMERAL Phase-2 sheet
              :tick-id (random-uuid)           ;; EPHEMERAL Phase-2 tick
              :source-sheet-id host-sheet
              :source-tick-id tick-id
              :trajectory []
              :total-usage {:total-tokens 0}
              :tree-fingerprint fingerprint
              :status :success
              :duration-ms 100}))))

(defn- seed-scored-class!
  "Seed ONE tree-class from `per-occurrence` — a seq of {judge-name -> score}
   maps, ONE PER OCCURRENCE, in temporal order (most recent LAST) — plus a
   consolidated description and a resolvable abstract parent. Every occurrence
   shares ONE production-faithful HOST sheet (SJ-1: distinct-sheet fixtures
   hide the occurrence-join bugs) and one tree-fingerprint, so the coherence
   clause never confounds the axis under test.

   `{:anchor? false}` seeds the SAME class with no behavioral classification
   at all, so nearest-abstract-behavior resolves to nil.

   Returns {:class-id :parent-id}."
  ([ctx per-occurrence] (seed-scored-class! ctx per-occurrence {:anchor? true}))
  ([ctx per-occurrence {:keys [anchor?]}]
   (let [class-id (random-uuid)
         parent-id (random-uuid)
         host-sheet (random-uuid)]
     (seed-parent-behavior! ctx parent-id)
     (record-tree-class-desc! ctx class-id good-body)
     (doseq [[i judge->score] (map-indexed vector per-occurrence)]
       (multi-judge-occurrence!
         ctx class-id host-sheet "shape-A" judge->score
         (when (and anchor? (zero? i))
           [{:behavior-id parent-id :confidence 0.9 :reasoning "x"}])))
     (Thread/sleep 250)
     {:class-id class-id :parent-id parent-id})))

(defn- single-judge-occurrences
  "`scores` -> per-occurrence maps for the ONE judge the corpus actually has."
  [scores]
  (mapv (fn [s] {one-judge s}) scores))

;; --- Cycle 1: the LIVE defect. Obligation rule-failure.PromoteWellScoredClass.4
;;     (`requires: consistently_qualified(tree_class, consistency_window)`).

(deftest cc26-recent-catastrophic-occurrences-veto-promotion
  (testing "20 occurrences, 16 perfect + 4 DISASTROUS — lifetime mean exactly 0.800, which the
             scalar gate promoted. Four catastrophic failures in twenty must NOT become durable,
             reusable knowledge: the last consistency_window occurrences must EACH clear
             consistency_floor, not average to it."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences
                                       (concat (repeat 16 1.0) (repeat 4 0.0))))]
        ;; MEASUREMENT GUARD — this fixture really is the 0.800 boundary case,
        ;; and really is single-judge, so the DIMENSION axis cannot be what
        ;; rejects it (0.8 >= dimension_floor 0.8).
        (let [avgs (ontology/get-tree-class-judge-averages ctx class-id)]
          (is (= {one-judge 0.8} avgs)
              (str "fixture must sit exactly on the old scalar boundary, got " avgs)))
        (is (= 20 (rm/get-consolidation-total ctx :tree-class class-id))
            "fixture must really have 20 occurrences")

        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "a class whose recent occurrences include catastrophic failures must NOT be harvested")))))

;; --- Cycle 3: the window is RECENT, not lifetime. Guards against
;;     over-correcting cycle 1 into "any historical failure vetoes forever".
;;     Obligation rule-success.PromoteWellScoredClass.

(deftest cc26-old-failures-do-not-veto-forever
  (testing "the SAME 20 occurrences and the SAME lifetime mean of 0.800 as the cycle-1 fixture, with
             the four disastrous occurrences OLDEST instead of newest: the class has since qualified
             in each of its last consistency_window occurrences, so it MUST promote. consistently_
             qualified is a WINDOW property — a class that learned is not condemned by its history."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id parent-id]} (seed-scored-class!
                                           ctx (single-judge-occurrences
                                                 (concat (repeat 4 0.0) (repeat 16 1.0))))]
        ;; MEASUREMENT GUARD — identical lifetime mean to the cycle-1 fixture,
        ;; so ORDER is provably the only difference between promote and veto.
        (let [avgs (ontology/get-tree-class-judge-averages ctx class-id)]
          (is (= {one-judge 0.8} avgs)
              (str "same 0.800 lifetime mean as the vetoed fixture, got " avgs)))

        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (let [minted (minted-harvest-events ctx class-id)]
          (is (= 1 (count minted))
              "recent-window qualification promotes despite older catastrophic occurrences")
          (is (= parent-id (:parent-behavior (first minted)))
              "and it is anchored under the resolved abstract parent"))))))

;; --- Cycle 4: the LATENT dimensional case. Obligation
;;     rule-failure.PromoteWellScoredClass.3
;;     (`requires: quality.all(dimension => dimension.score >= dimension_floor(dimension))`).

(def ^:private five-judges
  "FIVE judge dimensions — one MORE than the corpus has, deliberately: the
   defect is latent at judge-count 1 and fires the day a fifth is added
   (measured: 3 judges -> 0.667 fails, 4 -> 0.750 fails, 5 -> exactly 0.800,
   promotes)."
  ["coding-outcome" "grounding" "completeness" "reasoning" "safety"])

(deftest cc26-one-catastrophic-dimension-vetoes-promotion
  (testing "12 occurrences, FIVE judges, four scoring 1.0 and one scoring ZERO at every occurrence.
             The collapsed mean-of-means is exactly 0.800 and promoted. One catastrophic DIMENSION
             must veto promotion and must not be compensable by strength elsewhere."
    (with-gate-ctx [ctx]
      (let [judge->score (into {} (map-indexed (fn [i j] [j (if (zero? i) 0.0 1.0)]) five-judges))
            {:keys [class-id]} (seed-scored-class! ctx (repeat 12 judge->score))]
        ;; MEASUREMENT GUARDS — this fixture isolates the DIMENSION axis:
        ;;   (a) five real dimensions, one of them zero;
        ;;   (b) the mean over dimensions is EXACTLY the old 0.800 boundary;
        ;;   (c) every occurrence's aggregate is 0.8, so it CLEARS the
        ;;       consistency floor — the consistency axis cannot be what
        ;;       rejects this class.
        (let [avgs (ontology/get-tree-class-judge-averages ctx class-id)]
          (is (= 5 (count avgs)) (str "five judge dimensions projected, got " avgs))
          (is (= #{0.0 1.0} (set (vals avgs))) (str "one dimension at zero, four perfect, got " avgs))
          (is (= 0.8 (/ (reduce + 0.0 (vals avgs)) (double (count avgs))))
              "the collapsed mean-of-means sits exactly on the old 0.800 boundary"))
        (let [scores (harvest/occurrence-scores ctx class-id)]
          (is (= 12 (count scores)) (str "twelve scored occurrences, got " (count scores)))
          (is (every? #(>= % 0.8) scores)
              (str "every occurrence CLEARS the consistency floor — so consistency cannot be the "
                   "clause that rejects this class, got " scores)))

        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "a dimension scoring zero vetoes promotion, uncompensated by four perfect dimensions")))))

;; --- Cycle 5: the dimensional change is a PROVABLE NO-OP at judge-count 1.
;;     Obligation rule-success.PromoteWellScoredClass (the promoting case is
;;     unchanged for the corpus as it stands today).

(deftest cc26-dimension-floor-is-a-no-op-at-one-judge
  (testing "with ONE judge dimension, `every? >= floor` and the old collapsed `mean >= floor` are
             the SAME predicate — so replacing the mean with quality.all(...) cannot change any
             decision on today's corpus. Swept, not asserted."
    (is (= 1 harvest/known-judge-dimension-count)
        "the load-bearing assumption this no-op proof is scoped to")
    (let [floor (:dimension-floor harvest/default-harvest-config)
          ;; dense sweep of the unit interval + both sides of the floor
          values (concat (map #(/ % 100.0) (range 0 101))
                         [floor 0.7999999999 0.8000000001 0.0 1.0])
          judged (for [v values
                       :let [avgs {one-judge v}
                             ;; the OLD gate: mean over (vals judge-avgs).
                             ;; CC-29: BOTH comparators carry the floor-
                             ;; comparison tolerance, so this sweep keeps
                             ;; isolating the CC-26 STRUCTURAL change
                             ;; (mean -> quality.all) — the tolerance is a
                             ;; separate, orthogonal fix at the comparison
                             ;; seam, and hand-picked sub-tolerance values
                             ;; (0.7999999999) must not read as structural
                             ;; disagreements.
                             old-scalar (/ (reduce + 0.0 (vals avgs)) (double (count avgs)))
                             old? (>= old-scalar (- floor harvest/floor-comparison-tolerance))
                             new? (harvest/every-dimension-qualified? avgs floor)]]
                   {:v v :old old? :new new?})
          disagreements (remove #(= (:old %) (:new %)) judged)]
      (println "  [cc26 no-op sweep] N =" (count judged) "single-judge values;"
               (count (filter :old judged)) "promote under the OLD mean;"
               (count (filter :new judged)) "under the NEW per-dimension rule;"
               (count disagreements) "disagreements")
      ;; NON-VACUOUS BY CONSTRUCTION: the sweep must contain BOTH verdicts,
      ;; otherwise "they always agree" would be trivially true.
      (is (<= 100 (count judged)) "the sweep really covers the unit interval")
      (is (seq (filter :old judged)) "the sweep contains promoting values")
      (is (seq (remove :old judged)) "the sweep contains rejecting values")
      (is (empty? disagreements)
          (str "no-op claim FALSIFIED at judge-count 1: " (pr-str (vec disagreements))
               " — if this fires, the extension from 'pin it with a failing-in-waiting test' to "
               "'implement it' was unjustified and must be reported, not patched")))

    ;; And the SAME two predicates genuinely DIVERGE off the assumption — the
    ;; sweep above is an agreement about judge-count 1, not about the rules.
    (let [floor (:dimension-floor harvest/default-harvest-config)
          five {"coding-outcome" 0.0 "grounding" 1.0 "completeness" 1.0
                "reasoning" 1.0 "safety" 1.0}]
      (is (true? (>= (/ (reduce + 0.0 (vals five)) (double (count five))) floor))
          "at FIVE judges the old collapsed mean is exactly 0.800 and PROMOTES a zero dimension")
      (is (false? (harvest/every-dimension-qualified? five floor))
          "the new per-dimension rule vetoes it — so the no-op is a fact about judge-count 1, "))))

(deftest cc26-dimension-change-is-a-no-op-on-real-single-judge-streams
  (testing "REGRESSION on REAL projections, not hand-built maps: for single-judge classes the OLD
             collapsed rule (mean over (vals judge-averages), tolerant floor compare) and the NEW
             per-dimension rule return the SAME verdict. The read-model's own float accumulation is
             included on purpose — a knife-edge class whose twelve occurrences each score exactly
             0.8 projects to 0.7999999999999999. Pre-CC-29 both rules REJECTED it (the measured
             float artifact); with the CC-29 floor-comparison tolerance both rules PROMOTE it —
             identically under either structure, so the structural no-op claim is unchanged."
    (let [floor (:dimension-floor harvest/default-harvest-config)
          tolerant-floor (- floor harvest/floor-comparison-tolerance)
          cases [1.0 0.9 0.85 0.8 0.75 0.6 0.0]
          results
          (vec (for [score cases]
                 (with-gate-ctx [ctx]
                   (let [{:keys [class-id]} (seed-scored-class!
                                              ctx (single-judge-occurrences (repeat 12 score)))
                         avgs (ontology/get-tree-class-judge-averages ctx class-id)
                         ;; CC-29: the OLD comparator carries the same
                         ;; tolerance as the fixed predicate — this test
                         ;; isolates the CC-26 structural change, not the
                         ;; orthogonal comparison-seam fix.
                         old? (>= (/ (reduce + 0.0 (vals avgs)) (double (count avgs))) tolerant-floor)
                         new? (harvest/every-dimension-qualified? avgs floor)]
                     (is (= harvest/known-judge-dimension-count (count avgs))
                         (str "single-judge fixture, got " avgs))
                     {:score score :projected (get avgs one-judge) :old old? :new new?}))))]
      (println "  [cc26 real-stream no-op] N =" (count results) "single-judge classes:"
               (pr-str results))
      (is (= (count cases) (count results)) "every case really ran")
      (is (seq (filter :old results)) "non-vacuous: some cases promote")
      (is (seq (remove :old results)) "non-vacuous: some cases are rejected")
      (is (empty? (remove #(= (:old %) (:new %)) results))
          (str "no-op FALSIFIED on real projections: "
               (pr-str (vec (remove #(= (:old %) (:new %)) results)))))
      ;; The knife-edge DRIFT is a PRE-EXISTING read-model float artifact —
      ;; the projection still lands strictly below the floor literal. CC-29
      ;; fixed its VERDICT at the comparison seam: both rules now promote the
      ;; at-floor class, identically (cc29-class-exactly-on-the-floor-qualifies
      ;; pins the fix itself; here it must not read as a structural divergence).
      (let [knife (first (filter #(= 0.8 (:score %)) results))]
        (is (> 0.8 (:projected knife))
            (str "twelve scores of exactly 0.8 still project BELOW 0.8: " (pr-str knife)))
        (is (= true (:old knife) (:new knife))
            "old and new agree in promoting it — the tolerance, not the structure, decides")))))

(deftest cc26-ordinary-single-judge-class-still-promotes
  (testing "REGRESSION end-to-end: an ordinary well-scored one-judge class still mints"
    (with-gate-ctx [ctx]
      (let [{:keys [class-id parent-id]} (seed-scored-class!
                                           ctx (single-judge-occurrences (repeat 12 0.85)))]
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (let [minted (minted-harvest-events ctx class-id)]
          (is (= 1 (count minted)) "unchanged promoting behaviour at judge-count 1")
          (is (= parent-id (:parent-behavior (first minted)))))))))

;; --- Obligation rule-failure.PromoteWellScoredClass.6
;;     (`requires: parent != null`). Beyond the brief's five cycles: the
;;     obligation audit found this clause of the rule under change had NO test
;;     on either side of it, so it is closed here rather than tracked as a gap.

(deftest cc26-unanchored-class-is-not-harvested
  (testing "a class that clears every quality clause but has NO behavioral classification resolves
             to a nil abstract parent, and harvest is SKIPPED rather than creating an orphan"
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences (repeat 12 0.9))
                                 {:anchor? false})]
        ;; MEASUREMENT GUARDS — the class is otherwise a promoting class, and
        ;; the ONLY thing missing is the anchor.
        (is (true? (harvest/harvest-candidate?
                     {:occurrences (rm/get-consolidation-total ctx :tree-class class-id)
                      :judge-trailing-averages (ontology/get-tree-class-judge-recent-averages
                                                 ctx class-id (:dimension-window harvest/default-harvest-config))
                      :occurrence-scores (harvest/occurrence-scores ctx class-id)
                      :distinct-tree-shapes (harvest/distinct-tree-shapes ctx class-id)}
                     harvest/default-harvest-config))
            "the quality gate itself passes — only the anchor is missing")
        (is (nil? (harvest/nearest-abstract-behavior ctx class-id))
            "no behavioral classification -> no abstract parent")

        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "no orphan behavior is minted")))))

;; --- Live QA through the REGISTERED PROCESSOR, not just maybe-harvest!.
;;     The cycle-1 fixture cannot be built with processors running (the class
;;     crosses the gate mid-fixture, while its occurrences are still perfect,
;;     and mints before the catastrophic tail exists). A good-then-bad class
;;     IS constructible, and drives the same veto through the real event path.

(deftest cc26-processor-does-not-harvest-a-recently-failing-class
  (testing "with EVERY registered processor running and no direct maybe-harvest! call: a class whose
             recent occurrences are catastrophic is not minted, while a healthy class in the SAME
             context is — so the negative result is the gate, not a dead processor"
    (with-test-ctx [ctx]
      (let [failing (seed-scored-class!
                      ctx (single-judge-occurrences
                            (concat (repeat 6 1.0) (repeat 14 0.0))))
            healthy (seed-scored-class!
                      ctx (single-judge-occurrences (repeat 12 0.9)))]
        (Thread/sleep 600)
        ;; POSITIVE CONTROL — the processor really is live in this context.
        (is (= 1 (count (minted-harvest-events ctx (:class-id healthy))))
            "the healthy class was auto-harvested end-to-end (control: processors are running)")
        (is (= 20 (rm/get-consolidation-total ctx :tree-class (:class-id failing)))
            "the failing class really accumulated 20 occurrences")
        (is (empty? (minted-harvest-events ctx (:class-id failing)))
            "no processor-driven mint for a class failing its recent window")))))

;; --- The window is EVERY, not a MEAN. Obligation
;;     rule-failure.PromoteWellScoredClass.4, discriminating against the
;;     specific wrong implementation the spec names: "Deliberately not a mean
;;     over the window: that would reintroduce the same defect one level up."
;;     Found by mutation — a mean-over-the-window implementation survived every
;;     other test in this namespace, because their windows also fail on average.

(deftest cc26-window-is-per-occurrence-not-a-mean-over-the-window
  (testing "20 occurrences, 19 perfect + ONE disastrous, the disastrous one MOST RECENT: lifetime
             mean 0.950 (the old gate promoted it) AND the window's own mean is exactly 0.800 (a
             mean-over-the-window gate would promote it too). The per-occurrence rule vetoes it."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences
                                       (concat (repeat 19 1.0) [0.0])))
            window (take-last (:consistency-window harvest/default-harvest-config)
                              (harvest/occurrence-scores ctx class-id))]
        ;; MEASUREMENT GUARDS — this fixture defeats BOTH wrong gates.
        (is (= {one-judge 0.95} (ontology/get-tree-class-judge-averages ctx class-id))
            "lifetime mean 0.950 — comfortably above the old scalar bar")
        (is (= [1.0 1.0 1.0 1.0 0.0] window) (str "window shape, got " window))
        (is (>= (/ (reduce + 0.0 window) (double (count window)))
                (:consistency-floor harvest/default-harvest-config))
            "the window's MEAN clears the consistency floor — so only a per-occurrence rule can
             reject this class")

        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "one disastrous occurrence in the window vetoes promotion — it is not averaged away")))))

;; ===========================================================================
;; CC-29 — floor verdicts are exact on the DISCRETE judge scale
;; ===========================================================================
;;
;; The artifact (measured, CC-26 real-stream check): a class whose EVERY judge
;; score was exactly 0.8 projected its mean as 0.7999999999999999 — double
;; accumulation, (/ (reduce + 0.0 (repeat 12 0.8)) 12.0) — and was REJECTED by
;; the raw >= 0.8 floor. Judge scores are band values (quantum 0.25 at one
;; judge on the shipped 1-5 scale; 0.25/J when J judges are averaged), so any
;; legitimate below-floor mean differs from the floor by at least
;; quantum/count — orders of magnitude above binary-representation error. The
;; fix (spec: config floor_comparison_tolerance) is a named tolerance at the
;; floor-COMPARISON seam in harvest.clj only — not exact/rational arithmetic
;; in the folds — and can never change a verdict between two values the scale
;; can actually distinguish (pinned by the sweep below, which deliberately
;; sweeps a FINER 20ths grid than the real lattice: a tolerance safe against
;; the finer grid is safe against the real one).
;;
;; CC-24b (ADR 0029) moved the shipped floors onto the band lattice (0.8 ->
;; 0.75) and moved the dimension axis onto a trailing window. The two fixtures
;; that REPRODUCE the measured artifact keep comparing at 0.8 — the floor the
;; artifact was actually measured at — because they exist to reproduce a
;; specific real event, not to track whatever the shipped floor happens to be;
;; the sweep below is what pins the property AT the shipped floor. The fixtures
;; that assert a genuine below-floor REJECTION are re-anchored to the shipped
;; floor and the shipped axis, because a rejection is a claim about today's
;; gate.

(def ^:private cc29-measured-artifact-floor
  "The floor the CC-29 representation artifact was MEASURED at. CC-24b moved
   the shipped floor onto the band lattice (0.75); the artifact happened at
   0.8 and is reproduced here at 0.8, so this fixture keeps testing the
   tolerance rather than becoming a tautology at a lower floor."
  0.8)

(deftest cc29-class-exactly-on-the-floor-qualifies
  (testing "the measured artifact: twelve occurrences each scored exactly 0.8 (the floor band at
             the time) — the mean the REAL read-model fold projects is 0.7999999999999999,
             strictly below the 0.8 floor literal, and the class was rejected. A verdict on the
             discrete scale must treat an at-floor class as qualified: representation error is
             not a quality distinction. Both projections drift identically, so the tolerance is
             load-bearing on the LIFETIME accessor and on the TRAILING one CC-24b added."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id parent-id]} (seed-scored-class!
                                           ctx (single-judge-occurrences (repeat 12 0.8)))
            avgs (ontology/get-tree-class-judge-averages ctx class-id)
            trailing (ontology/get-tree-class-judge-recent-averages
                       ctx class-id (:dimension-window harvest/default-harvest-config))]
        ;; MEASUREMENT GUARDS — the drift is REPRODUCED from real arithmetic
        ;; (the standing read-model's own accumulation over real events), never
        ;; a hand-typed drifted literal:
        (is (= {one-judge 0.7999999999999999} avgs)
            (str "twelve real 0.8 scores project to the drifted double, got " avgs))
        (is (< (get avgs one-judge) 0.8)
            "…which sits strictly BELOW the floor literal — the artifact is real")
        (is (= (get avgs one-judge)
               (/ (reduce + 0.0 (repeat 12 0.8)) (double 12)))
            "and it is exactly what double accumulation of twelve 0.8s produces")
        ;; Axis isolation: each single-judge occurrence score is the raw band
        ;; value 0.8, bit-identical to the floor literal, so the consistency
        ;; axis clears even under the raw comparison — the dimension floor was
        ;; the ONLY rejector.
        (is (= (vec (repeat 12 0.8)) (harvest/occurrence-scores ctx class-id))
            "occurrence scores are the bit-identical band value")
        (is (= {one-judge 0.7999999999999999} trailing)
            (str "the TRAILING projection drifts identically (ten 0.8s), got " trailing))

        (is (true? (harvest/every-dimension-qualified? avgs cc29-measured-artifact-floor))
            "an at-floor dimension mean qualifies — the floor comparison tolerates representation error")
        (is (true? (harvest/every-dimension-qualified? trailing cc29-measured-artifact-floor))
            "…on the trailing projection too")
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (let [minted (minted-harvest-events ctx class-id)]
          (is (= 1 (count minted))
              "the at-floor class is harvested end-to-end")
          (is (= parent-id (:parent-behavior (first minted)))
              "under its resolved abstract parent"))))))

(deftest cc29-genuinely-below-floor-is-still-rejected
  (testing "verdicts still distinguish real differences, re-anchored onto the axis CC-24b ships:
             20 occurrences on the REAL lattice, all band 4 (0.75) except ONE band 3 (0.5)
             placed at the head of the trailing window. Its trailing-10 mean is 0.725 —
             quantum/window (0.25/10 = 0.025) below the floor, the SMALLEST legitimate
             below-floor distinction the scale can express at this window — and it must still be
             rejected with the tolerance in place. (0.725 is not a made-up number: it is exactly
             what the real durable class projects at its 90th scored occurrence.)"
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences
                                       (concat (repeat 10 0.75) [0.5] (repeat 9 0.75))))
            floor (:dimension-floor harvest/default-harvest-config)
            window (:dimension-window harvest/default-harvest-config)
            trailing (ontology/get-tree-class-judge-recent-averages ctx class-id window)
            m (get trailing one-judge)]
        ;; MEASUREMENT GUARDS — the band-3 occurrence is the OLDEST member of
        ;; the trailing window and sits OUTSIDE the consistency window, so the
        ;; consistency axis clears and the dimension floor is isolated.
        (is (= [0.75 0.75 0.75 0.75 0.75]
               (vec (take-last 5 (harvest/occurrence-scores ctx class-id))))
            "recent window clears — dimension axis isolated")
        (is (true? (harvest/consistently-qualified?
                     (harvest/occurrence-scores ctx class-id)
                     (:consistency-window harvest/default-harvest-config)
                     (:consistency-floor harvest/default-harvest-config)))
            "…proven, not assumed: the consistency axis is not the rejector")
        (is (< (Math/abs (- m 0.725)) 1.0E-9)
            (str "trailing mean is quantum/window (0.025) below the floor, got " m))

        (is (false? (harvest/every-dimension-qualified? trailing floor))
            "one band-quantum short at window 10 is a REAL distinction — still rejected")
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "and no mint end-to-end")))))

(deftest cc29-window-score-exactly-on-the-floor-qualifies
  (testing "the consistency-axis symmetry: a per-occurrence aggregate COMPUTED from real judge
             scores can drift exactly like the lifetime mean — three judges banding 0.6/0.9/0.9
             have true mean exactly 0.8 but the real occurrence-scores computation produces
             0.7999999999999999. An at-floor window score must clear the consistency floor."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (repeat 12 {"grounding" 0.6 "quality" 0.9 "fit" 0.9}))
            scores (harvest/occurrence-scores ctx class-id)]
        ;; MEASUREMENT GUARDS — the drift comes from the REAL per-occurrence
        ;; aggregation over real events (band values only; quantum 0.05):
        (is (= 12 (count scores)) (str "twelve scored occurrences, got " (count scores)))
        (is (every? #(= 0.7999999999999999 %) scores)
            (str "every occurrence aggregate drifts to the measured double, got " (vec (distinct scores))))
        (is (every? #(< % 0.8) scores)
            "…strictly below the floor literal — the artifact is real on this axis too")

        (is (true? (harvest/consistently-qualified?
                     scores
                     (:consistency-window harvest/default-harvest-config)
                     cc29-measured-artifact-floor))
            "an at-floor window qualifies — the floor comparison tolerates representation error")
        ;; Predicate-level ON PURPOSE: this fixture's genuinely-0.6 grounding
        ;; DIMENSION must veto the class end-to-end regardless — the tolerance
        ;; admits representation error, never a real quantum-sized deficit.
        (is (false? (harvest/every-dimension-qualified?
                      (ontology/get-tree-class-judge-recent-averages
                        ctx class-id (:dimension-window harvest/default-harvest-config))
                      (:dimension-floor harvest/default-harvest-config)))
            "the 0.6 dimension still vetoes — tolerance changes no scale-expressible verdict")))))

(deftest cc29-genuinely-below-window-score-is-still-rejected
  (testing "the window still distinguishes real differences: the same drifted at-floor occurrences
             with the MOST RECENT occurrence banded a genuine quantum short (0.75) must be
             rejected — and WITHOUT that occurrence the same drifted window qualifies, so the
             verdict flip is the 0.75, not the tolerance."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (conj (vec (repeat 11 {"grounding" 0.6 "quality" 0.9 "fit" 0.9}))
                                           {"quality" 0.75}))
            scores (harvest/occurrence-scores ctx class-id)
            window (:consistency-window harvest/default-harvest-config)
            ;; the floor the artifact was measured at — see the section header
            floor cc29-measured-artifact-floor]
        (is (= 12 (count scores)) (str "twelve scored occurrences, got " (count scores)))
        (is (= 0.75 (peek scores)) "most recent occurrence banded a genuine 0.75")
        (is (every? #(= 0.7999999999999999 %) (pop scores))
            "…the other eleven sit AT the floor via the real drifted computation")

        (is (false? (harvest/consistently-qualified? scores window floor))
            "one occurrence a full band-quantum short is a REAL distinction — still rejected")
        (is (true? (harvest/consistently-qualified? (pop scores) window floor))
            "without it the drifted at-floor window qualifies — the rejector is the 0.75")))))

(deftest cc29-tolerance-never-flips-a-scale-distinguishable-verdict
  (testing "the spec's safe-window argument, swept rather than asserted AT THE SHIPPED FLOOR:
             for EVERY mean a band multiset can produce at counts 1..40 on a 20ths grid — four
             times finer than the real 0.25 lattice, so the argument is tested against a harder
             case than the scale can actually produce — the floor verdict on the
             double-ACCUMULATED mean equals the EXACT rational verdict. The tolerance admits
             binary-representation error only — it can never change a verdict between two
             values the scale can actually distinguish. CC-24b: `floor` is read from the config
             and compared exactly, so moving the floor onto the lattice re-runs the whole
             argument at the new value instead of silently leaving it pinned at the old one."
    (let [sweep (fn [floor]
                  (let [exact-floor (rationalize floor)]
                    (vec (for [n (range 1 41)
                               k (range 0 (inc (* 20 n)))   ;; total score in 20ths
                               :let [q (quot k n) r (rem k n)
                                     ;; a concrete band multiset summing to k/20
                                     bands (concat (repeat r (/ (inc q) 20.0))
                                                   (repeat (- n r) (/ q 20.0)))
                                     drifted (/ (reduce + 0.0 bands) (double n))
                                     exact? (>= (/ k (* 20 n)) exact-floor)
                                     verdict? (harvest/every-dimension-qualified?
                                                {one-judge drifted} floor)]]
                           {:n n :k k :drifted drifted :exact exact? :verdict verdict?}))))
          disagreements (fn [checks] (remove #(= (:exact %) (:verdict %)) checks))
          drifting-at-floor (fn [checks floor]
                              (filter #(and (:exact %) (< (:drifted %) floor)) checks))
          shipped-floor (:dimension-floor harvest/default-harvest-config)
          shipped (sweep shipped-floor)
          ;; The floor the artifact was MEASURED at. The tolerance has to be
          ;; proven safe wherever the floor is put, and the artifact is only
          ;; REACHABLE at a non-dyadic floor — see below.
          measured (sweep cc29-measured-artifact-floor)]
      (println "  [cc29 sweep] N =" (count shipped) "band-multiset means per floor;"
               "at" shipped-floor ":" (count (drifting-at-floor shipped shipped-floor))
               "drift below the floor literal," (count (disagreements shipped)) "disagreements;"
               "at" cc29-measured-artifact-floor ":"
               (count (drifting-at-floor measured cc29-measured-artifact-floor))
               "drift," (count (disagreements measured)) "disagreements")
      (is (<= 16000 (count shipped)) "the sweep really covers the reachable means")
      (is (seq (remove :exact shipped)) "non-vacuous: the sweep contains genuinely-below-floor means")
      (is (seq (filter :exact shipped)) "non-vacuous: the sweep contains qualifying means")
      ;; NON-VACUITY of the TOLERANCE itself: there must exist at-floor means
      ;; whose double accumulation lands below the floor literal, or "the
      ;; tolerance never flips a verdict" is trivially true. At the MEASURED
      ;; floor there are.
      (is (seq (drifting-at-floor measured cc29-measured-artifact-floor))
          "non-vacuous: at the measured floor the sweep CONTAINS at-floor means whose double
           accumulation drifts below the floor literal")
      (is (empty? (disagreements measured))
          (str "tolerance FALSIFIED at the measured floor: "
               (pr-str (vec (take 5 (disagreements measured))))))
      (is (empty? (disagreements shipped))
          (str "tolerance FALSIFIED at the shipped floor: "
               (pr-str (vec (take 5 (disagreements shipped))))))
      ;; CC-24b FINDING, pinned rather than hidden: at the SHIPPED floor the
      ;; artifact is UNREACHABLE. 0.75 is a dyadic rational (3/4), so a
      ;; multiset whose exact mean is the floor is a multiset of 0.75s, whose
      ;; sum and quotient are both exact in binary — nothing to drift. Moving
      ;; the floor onto the band lattice did not merely rename the tolerance's
      ;; job, it removed the failure mode at the dimension floor. The tolerance
      ;; stays because it is proven safe (above) and because the CONSISTENCY
      ;; axis still aggregates ACROSS judges per occurrence, where a
      ;; non-dyadic per-occurrence mean is reachable at any judge count that is
      ;; not a power of two (cc29-window-score-exactly-on-the-floor-qualifies
      ;; is exactly that case, measured).
      (is (empty? (drifting-at-floor shipped shipped-floor))
          (str "the shipped floor is dyadic, so an at-floor mean cannot drift below it; if this "
               "fires, the floor moved OFF a dyadic value and the tolerance is load-bearing "
               "again at the dimension floor: " (pr-str (vec (take 5 (drifting-at-floor shipped shipped-floor)))))))))

;; ===========================================================================
;; CC-24b (ADR 0029) — floors ON the band lattice + a TRAILING dimension window
;; ===========================================================================
;;
;; Two ADR-0027 findings, measured in CC-24a (evidence/cc24/CC24-MEASUREMENT.md):
;;
;;   1. The floors were OFF-LATTICE. scale.clj maps a discrete 1-5 judge scale
;;      by (n-1)/(max-min), so the achievable per-occurrence set at one judge is
;;      exactly {0, 0.25, 0.5, 0.75, 1.0}. The shipped 0.8 sat in the gap between
;;      band 4 (good work) and band 5, silently meaning "PERFECT ONLY": every
;;      floor in {0.60,0.70,0.75} produced identical gate behaviour, as did every
;;      floor in {0.80,0.90,1.00}. 0.75 is the lattice point that says what we
;;      mean — band 4 or better qualifies.
;;
;;   2. The dimension axis was INERT. Per-judge LIFETIME mean against the floor
;;      fired 0 times in 105 real positions, at 0.80 AND at 0.75; the dominant
;;      class would have needed ~97 consecutive perfect occurrences, because a
;;      lifetime mean can never forget — including evidence produced by ENGINE
;;      defects we have since fixed. A TRAILING window of the last 10 scored
;;      occurrences fires 38/105 and abstains 67/105 at 0.75: it can both fire
;;      and abstain on real data, which is ADR 0027's acceptance bar.
;;
;; The consistency axis (ALL-of-5-in-window) and the EL-5 z-gate were measured
;; and ratified UNCHANGED, so nothing below touches their semantics.

(def ^:private cc24-durable
  "The CC-24a BANKED durable-Postgres dominant tree-class, derived from the raw
   event dump (never transcribed): the real per-occurrence score sequence in
   occurrence order, with nil for each of the 51 UNSCORED occurrences."
  (delay (edn/read-string (slurp (io/resource "cc24b_durable_dominant_class.edn")))))

(defn- mean* [xs] (/ (reduce + 0.0 xs) (double (count xs))))

(defn- prefix-through-scored
  "The real occurrence prefix (scored AND unscored, in order) ending at the
   `n`th SCORED occurrence — the shape a class actually had at that moment."
  [occurrence-scores n]
  (loop [i 0 scored 0]
    (if (or (= scored n) (>= i (count occurrence-scores)))
      (vec (take i occurrence-scores))
      (recur (inc i) (if (nth occurrence-scores i) (inc scored) scored)))))

(defn- seed-real-occurrences!
  "Seed ONE tree-class from a real occurrence-score prefix — nil means an
   UNSCORED occurrence (no judge signal; skip-not-zero), which really is 32.7%
   of this class's history and is what makes the read-model's recent-occurrence
   bound load-bearing."
  [ctx occurrence-scores]
  (seed-scored-class! ctx (mapv (fn [s] (if s {one-judge s} {})) occurrence-scores)))

(defn- capture-mulog
  "Intercept the fn mu/log expands to and collect {:event :pairs} entries."
  [thunk]
  (let [logs (atom [])]
    (with-redefs [mulog-core/log* (fn [_logger event-name pairs]
                                    (swap! logs conj {:event event-name
                                                      :pairs (apply hash-map pairs)}))]
      (let [result (thunk)]
        [@logs result]))))

;; --- RED 1: the ADR-0027 guard. The floors are LATTICE POINTS of the shipped
;;     judge scale. If a future scale change moves the lattice out from under
;;     them, this fails loudly instead of silently re-breaking ADR 0027's law.

(deftest cc24b-harvest-floors-are-points-on-the-shipped-judge-lattice
  (testing "every SHIPPED rubric scale is the discrete 1-5 mapped (n-1)/4, so the achievable
             per-judge score set is exactly {0, 0.25, 0.5, 0.75, 1.0} — and BOTH harvest floors
             are members of it. A floor that is not a lattice point is a value whose stated
             meaning differs from its operative meaning (ADR 0027, ADR 0029 decision 1)."
    (let [shipped [rubrics/GROUNDING_SCALE rubrics/INSTRUCTION_FOLLOWING_SCALE
                   rubrics/REASONING_SCALE rubrics/COMPLETENESS_SCALE]
          lattices (mapv (fn [s] (into (sorted-set) (map #(scale/level->unit-score s %))
                                       (scale/levels s)))
                         shipped)]
      (is (= 4 (count shipped)) "all four shipped rubric scales are under test")
      (is (= [#{0.0 0.25 0.5 0.75 1.0}] (distinct lattices))
          (str "the shipped judge lattice, got " (pr-str lattices)))
      (let [lattice (first lattices)]
        (is (contains? lattice (:dimension-floor harvest/default-harvest-config))
            (str "dimension-floor is OFF the lattice: "
                 (:dimension-floor harvest/default-harvest-config) " not in " lattice))
        (is (contains? lattice (:consistency-floor harvest/default-harvest-config))
            (str "consistency-floor is OFF the lattice: "
                 (:consistency-floor harvest/default-harvest-config) " not in " lattice))))))

;; --- RED 2: the ratified values, and what they MEAN at the band boundary.

(deftest cc24b-floors-admit-band-four-and-reject-band-three
  (testing "ADR 0029 decision 1: both floors are 0.75 and the dimension window is 10. A class
             whose every judge trails at exactly band 4 (0.75 — described to the judge as good
             work) QUALIFIES; one trailing at band 3 (0.5) does not."
    (is (= 0.75 (:dimension-floor harvest/default-harvest-config)))
    (is (= 0.75 (:consistency-floor harvest/default-harvest-config)))
    (is (= 10 (:dimension-window harvest/default-harvest-config)))
    (is (= 5 (:consistency-window harvest/default-harvest-config))
        "the consistency window is ratified UNCHANGED")
    (let [floor (:dimension-floor harvest/default-harvest-config)]
      (is (true? (harvest/every-dimension-qualified? {one-judge 0.75} floor))
          "band 4 counts")
      (is (true? (harvest/every-dimension-qualified? {one-judge 0.75 "grounding" 1.0} floor))
          "band 4 counts on every dimension")
      (is (false? (harvest/every-dimension-qualified? {one-judge 0.75 "grounding" 0.5} floor))
          "one dimension at band 3 vetoes")
      (is (true? (harvest/consistently-qualified? (vec (repeat 5 0.75)) 5 floor))
          "a window of band-4 occurrences counts")
      (is (false? (harvest/consistently-qualified? [0.75 0.75 0.75 0.75 0.5] 5 floor))
          "one band-3 occurrence in the window vetoes"))))

;; --- RED 3 (the crux): the dimension axis reads a TRAILING WINDOW.
;;     Fixtures are the CC-24a BANKED real distribution, loaded not transcribed.

(deftest cc24b-banked-fixture-reproduces-the-measurement
  (testing "the banked fixture really is the measured corpus — 156 occurrences, 105 scored,
             51 unscored, one judge, lifetime mean 0.6167, trailing-10 mean 0.275"
    (let [fx @cc24-durable]
      (is (= 156 (:occurrences fx)))
      (is (= 105 (:scored-occurrences fx)))
      (is (= 51 (:unscored-occurrences fx)))
      (is (= ["implementation-turn/coding-outcome"] (:judges fx)))
      (is (= 105 (count (:scores fx))))
      (is (= 156 (count (:occurrence-scores fx))))
      (is (= {0.0 25, 0.25 4, 0.5 14, 0.75 21, 1.0 41} (into {} (:distribution fx))))
      (is (< (Math/abs (- (mean* (:scores fx)) 0.6166666666666667)) 1.0E-12))
      (is (= 0.275 (mean* (take-last 10 (:scores fx)))))
      (is (= #{0.0 0.25 0.5 0.75 1.0} (set (:scores fx)))
          "every real value is a band — the lattice is not a model, it is the data"))))

(deftest cc24b-lifetime-mean-is-inert-and-the-trailing-window-is-not
  (testing "ADR 0027's acceptance bar, replayed through the SHIPPED predicates over the banked
             real sequence: at floor 0.75 the raw LIFETIME mean fires 0 of 105 real positions
             (it cannot express the property at all), the TRAILING-10 mean fires 38 and abstains
             67, and the ratified-unchanged consistency rule fires 15 of 101."
    (let [scores (vec (:scores @cc24-durable))
          floor (:dimension-floor harvest/default-harvest-config)
          window (:dimension-window harvest/default-harvest-config)
          cwindow (:consistency-window harvest/default-harvest-config)
          sweep (fn [f]
                  (vec (for [i (range 1 (inc (count scores)))
                             :let [p (subvec scores 0 i)]]
                         {:i i
                          :lifetime (harvest/every-dimension-qualified?
                                      {one-judge (mean* p)} f)
                          :trailing (harvest/every-dimension-qualified?
                                      {one-judge (mean* (take-last window p))} f)
                          :consistent (harvest/consistently-qualified? p cwindow f)})))
          positions (sweep floor)
          ;; the OLD off-lattice floor, swept too: the ADR's table has two rows,
          ;; and pinning both proves this replay tracks the floor rather than
          ;; happening to agree at one value.
          old-positions (sweep 0.8)
          fires (fn [ps k] (count (filter k ps)))]
      (is (= 105 (count positions)) "every real position swept")
      (is (= 0.75 floor) "…at the SHIPPED floor")
      (is (= 0 (fires positions :lifetime))
          "the raw lifetime mean fires ZERO times — the measured inertness")
      (is (= 0 (fires old-positions :lifetime))
          "…and fired zero times at the OLD 0.80 floor too: no retuning could have fixed it")
      (is (= 38 (fires positions :trailing))
          (str "trailing-10 fires 38/105 at 0.75, got " (fires positions :trailing)))
      (is (= 27 (fires old-positions :trailing))
          (str "…and 27/105 at 0.80, the ADR's other row, got " (fires old-positions :trailing)))
      (is (= 67 (count (remove :trailing positions)))
          "…and abstains on the rest — it can do BOTH on real data")
      (is (= 15 (fires (filter #(>= (:i %) cwindow) positions) :consistent))
          "the consistency axis is unchanged and was never the problem")
      (is (= 9 (fires (filter #(>= (:i %) cwindow) old-positions) :consistent))
          "…9/101 at 0.80, the measured figure ADR 0029 decision 3 rests on"))))

(deftest cc24b-trailing-window-earns-eligibility-a-lifetime-mean-cannot
  (testing "THE REAL SHAPE (banked, occurrence prefix through the 22nd scored occurrence of the
             durable dominant class): an early block of zeros produced by since-fixed ENGINE
             defects, followed by recent qualifying work. The LIFETIME mean is 0.5795 and can
             never clear the floor; the class's most recent 10 scored occurrences mean 0.825 and
             DO. It is harvested end-to-end."
    (with-gate-ctx [ctx]
      (let [fx @cc24-durable
            prefix (prefix-through-scored (:occurrence-scores fx) 22)
            scored (vec (keep identity prefix))
            {:keys [class-id parent-id]} (seed-real-occurrences! ctx prefix)
            floor (:dimension-floor harvest/default-harvest-config)
            window (:dimension-window harvest/default-harvest-config)
            lifetime (ontology/get-tree-class-judge-averages ctx class-id)
            trailing (ontology/get-tree-class-judge-recent-averages ctx class-id window)]
        ;; MEASUREMENT GUARDS — the fixture really is the banked real shape.
        (is (= 22 (count scored)) "twenty-two real scored occurrences")
        (is (= 5 (count (filter zero? (take 10 scored))))
            (str "the early block of engine-defect zeros is present, got " (pr-str scored)))
        (is (pos? (count (remove some? prefix)))
            (str "the real prefix really interleaves UNSCORED occurrences (skip-not-zero), got "
                 (count (remove some? prefix)) " of " (count prefix)))
        (is (= (harvest/occurrence-scores ctx class-id) scored)
            "the real event path reproduces the banked sequence exactly")

        (is (< (Math/abs (- (get lifetime one-judge) 0.5795454545454546)) 1.0E-9)
            (str "lifetime mean, got " lifetime))
        (is (false? (harvest/every-dimension-qualified? lifetime floor))
            "the LIFETIME mean cannot clear the floor — the mechanism ADR 0029 replaced")
        (is (= {one-judge 0.825} trailing)
            (str "the trailing-10 mean clears the floor, got " trailing))
        (is (true? (harvest/every-dimension-qualified? trailing floor))
            "the TRAILING window earns eligibility the lifetime mean cannot express")

        ;; …and it really is the dimension axis that flips: same metrics, one key.
        (let [metrics {:occurrences (rm/get-consolidation-total ctx :tree-class class-id)
                       :occurrence-scores (harvest/occurrence-scores ctx class-id)
                       :distinct-tree-shapes (harvest/distinct-tree-shapes ctx class-id)}]
          (is (true? (harvest/harvest-candidate?
                       (assoc metrics :judge-trailing-averages trailing)
                       harvest/default-harvest-config)))
          (is (false? (harvest/harvest-candidate?
                        (assoc metrics :judge-trailing-averages lifetime)
                        harvest/default-harvest-config))
              "the ONLY difference is trailing-vs-lifetime"))

        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 200)
        (let [minted (minted-harvest-events ctx class-id)]
          (is (= 1 (count minted))
              "a class can earn a mint on sustained recent quality despite poisoned history")
          (is (= parent-id (:parent-behavior (first minted)))))))))

(deftest cc24b-trailing-window-loses-eligibility-a-lifetime-mean-would-keep
  (testing "THE MIRROR, and the same real class: at its 90th scored occurrence its recent 10
             mean 0.725 — one band-quantum-per-window BELOW the floor — while its last five
             occurrences all still clear the consistency floor, so the DIMENSION axis is what
             rejects. Eligibility can be LOST as well as gained: enforcement is continuously
             earned (ADR 0029 consequence 3)."
    (with-gate-ctx [ctx]
      (let [fx @cc24-durable
            prefix (prefix-through-scored (:occurrence-scores fx) 90)
            scored (vec (keep identity prefix))
            {:keys [class-id]} (seed-real-occurrences! ctx prefix)
            floor (:dimension-floor harvest/default-harvest-config)
            window (:dimension-window harvest/default-harvest-config)
            trailing (ontology/get-tree-class-judge-recent-averages ctx class-id window)
            occ-scores (harvest/occurrence-scores ctx class-id)]
        (is (= 90 (count scored)) "ninety real scored occurrences")
        (is (= scored occ-scores) "the real event path reproduces the banked sequence exactly")
        ;; AXIS ISOLATION — the consistency axis PASSES here, so only the
        ;; dimension axis can be the rejector.
        (is (true? (harvest/consistently-qualified?
                     occ-scores (:consistency-window harvest/default-harvest-config) floor))
            (str "the last five occurrences all clear the floor, got "
                 (pr-str (vec (take-last 5 occ-scores)))))
        (is (= {one-judge 0.725} trailing)
            (str "the trailing-10 mean has fallen below the floor, got " trailing))
        (is (false? (harvest/every-dimension-qualified? trailing floor))
            "quantum/window below the floor is a REAL distinction — eligibility is lost")
        ;; LOST, not merely absent: this SAME class's trailing window qualified
        ;; earlier in the very same history (the sibling test drives that state
        ;; through the real event path and mints from it).
        (is (true? (harvest/every-dimension-qualified?
                     {one-judge (mean* (take-last window (subvec scored 0 22)))} floor))
            "…and it DID qualify at its 22nd scored occurrence — enforcement is continuously earned")
        (is (false? (harvest/harvest-candidate?
                      {:occurrences (rm/get-consolidation-total ctx :tree-class class-id)
                       :judge-trailing-averages trailing
                       :occurrence-scores occ-scores
                       :distinct-tree-shapes (harvest/distinct-tree-shapes ctx class-id)}
                      harvest/default-harvest-config))
            "the gate rejects it")
        (harvest/maybe-harvest! ctx class-id)
        (Thread/sleep 200)
        (is (empty? (minted-harvest-events ctx class-id))
            "and nothing is minted end-to-end")))))

(deftest cc24b-recent-occurrence-state-is-bounded
  (testing "CC-22's lesson: the recent-occurrence sequence the read-model keeps per class is
             BOUNDED deliberately, and the bound is large enough that the trailing window is
             still computable when a third of the class's occurrences carry no judge signal
             (measured: 29 consecutive real occurrences are needed to hold 10 scored ones)."
    (with-gate-ctx [ctx]
      (let [fx @cc24-durable
            {:keys [class-id]} (seed-real-occurrences! ctx (vec (:occurrence-scores fx)))
            state (rmp/project ctx :ontology/tree-class-judge-averages)
            retained (get-in state [:class->recent-occurrences class-id])]
        (is (= 156 (rm/get-consolidation-total ctx :tree-class class-id))
            "the whole real history was seeded")
        (is (= rm/recent-occurrence-bound (count retained))
            (str "the retained sequence is capped at the bound, got " (count retained)))
        (is (<= 29 rm/recent-occurrence-bound)
            "the bound exceeds the measured worst-case span needed for 10 scored occurrences")
        ;; …and the bound is a real bound, not a VIEW over an unbounded root.
        ;; `subvec` returns a SubVector that pins the whole underlying vector,
        ;; and `conj` on one appends to that root — so a subvec-trimmed
        ;; sequence reports (count 40) while retaining every key ever folded.
        ;; Measured on this very fixture during CC-24b: count 40, root 156.
        ;; A count assertion alone cannot see that, so assert the retention.
        (is (not (instance? clojure.lang.APersistentVector$SubVector retained))
            (str "the retained sequence is a VIEW over an unbounded root vector — the count is "
                 "bounded but the memory is not (CC-22): " (class retained)))
        ;; The bound is not merely small — it is still CORRECT: the trailing
        ;; window over the real (unscored-riddled) tail is the measured 0.275.
        (is (= {one-judge 0.275}
               (ontology/get-tree-class-judge-recent-averages
                 ctx class-id (:dimension-window harvest/default-harvest-config)))
            "the bounded state still yields the measured trailing-10 mean")))))

(deftest cc24b-lifetime-accessor-is-unchanged-alongside-the-trailing-one
  (testing "the LIFETIME accessor keeps its meaning and its consolidator parity: on the same
             real stream get-tree-class-judge-averages still projects the lifetime mean the
             consolidator's private aggregate computes, while the NEW accessor projects the
             trailing window. Both are live; neither replaced the other."
    (with-gate-ctx [ctx]
      (let [fx @cc24-durable
            prefix (prefix-through-scored (:occurrence-scores fx) 22)
            {:keys [class-id]} (seed-real-occurrences! ctx prefix)
            lifetime (ontology/get-tree-class-judge-averages ctx class-id)
            trailing (ontology/get-tree-class-judge-recent-averages ctx class-id 10)
            agg (#'consolidator/tree-class-aggregate-metrics ctx class-id)]
        (is (= (:judge-averages agg) lifetime)
            (str "parity oracle holds. aggregate=" (:judge-averages agg) " read-model=" lifetime))
        (is (not= lifetime trailing)
            (str "and the two accessors really are different projections: "
                 lifetime " vs " trailing))))))

;; --- RED 4: the coherence abstention becomes OBSERVABLE (ADR 0029 decision 5).
;;     No behaviour change — visibility only. Measured: coherence passes
;;     VACUOUSLY for 100% of occurrences in both real stores (0/138
;;     tree-execution events carry a fingerprint), so a gate report that says
;;     "coherent" is reporting a signal it has never once seen.

(deftest cc24b-coherence-abstention-is-observable-in-the-gate-report
  (testing "a class with NO tree shapes at all must be REPORTED as abstaining on coherence,
             not silently reported as coherent — while the verdict itself is unchanged"
    (let [no-shapes {:occurrences 12
                     :judge-trailing-averages {one-judge 0.75}
                     :occurrence-scores (vec (repeat 12 0.75))
                     :distinct-tree-shapes 0}
          with-shapes (assoc no-shapes :distinct-tree-shapes 3)
          grab-bag (assoc no-shapes :distinct-tree-shapes 11)
          report-a (harvest/harvest-gate-report no-shapes harvest/default-harvest-config)
          report-b (harvest/harvest-gate-report with-shapes harvest/default-harvest-config)
          report-c (harvest/harvest-gate-report grab-bag harvest/default-harvest-config)]
      (is (= :abstained (get-in report-a [:coherence :verdict]))
          (str "no shapes -> ABSTAINED, got " (pr-str report-a)))
      (is (= :qualified (get-in report-b [:coherence :verdict]))
          (str "real shapes -> a real verdict, got " (pr-str report-b)))
      (is (= :rejected (get-in report-c [:coherence :verdict]))
          (str "a grab-bag is still rejected, got " (pr-str report-c)))
      ;; NO BEHAVIOUR CHANGE — the abstention still passes, exactly as before.
      (is (true? (:candidate? report-a)) "the abstention still passes the gate")
      (is (= (harvest/harvest-candidate? no-shapes harvest/default-harvest-config)
             (:candidate? report-a))
          "the report agrees with the gate it reports on")
      (is (= (harvest/harvest-candidate? grab-bag harvest/default-harvest-config)
             (:candidate? report-c))))))

(deftest cc24b-coherence-abstention-is-logged-by-maybe-harvest
  (testing "…and the report really reaches an operator: maybe-harvest! emits the gate report,
             and on the real corpus (0/138 bookends carry a fingerprint) it says ABSTAINED"
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences (repeat 12 1.0)))
            [logs _] (capture-mulog #(harvest/maybe-harvest! ctx class-id))
            reports (filter #(= ::harvest/harvest-gate-report (:event %)) logs)]
        ;; the fixture's bookends DO carry a fingerprint, so first prove the
        ;; report is emitted at all and reports a real coherence verdict…
        (is (= 1 (count reports))
            (str "exactly one gate report is logged, got " (pr-str (mapv :event logs))))
        (is (= :qualified (get-in (first reports) [:pairs :coherence :verdict]))
            (str "shapes present -> qualified, got " (pr-str (first reports))))))))

(deftest cc24b-shapeless-class-logs-the-abstention
  (testing "the SHAPELESS case measured in both real stores: no tree-execution evidence at all,
             so the coherence clause passes vacuously — and the log SAYS so"
    (with-gate-ctx [ctx]
      (let [class-id (random-uuid)
            parent-id (random-uuid)
            host (random-uuid)]
        (seed-parent-behavior! ctx parent-id)
        (record-tree-class-desc! ctx class-id good-body)
        ;; classification + judge score ONLY — no bookend, which is exactly the
        ;; VOLUME store's shape (sonnet solved every task by direct tool call).
        (dotimes [i 12]
          (let [tick (random-uuid)]
            (cp/process-command
              (assoc ctx :command
                     (cond-> {:command/name :ontology/assign-task-class
                              :command/id (random-uuid)
                              :command/timestamp (time/now)
                              :source-sheet-id host
                              :source-tick-id tick
                              :source-node-id (random-uuid)
                              :assigned-tree-id class-id
                              :confidence 0.95
                              :top-candidates []
                              :reasoning "test"
                              :was-fresh-mint? false}
                       (zero? i) (assoc :behavioral-subtrees
                                        [{:behavior-id parent-id :confidence 0.9 :reasoning "x"}]))))
            (judge-score! ctx host tick one-judge 1.0)))
        (Thread/sleep 250)
        (is (zero? (harvest/distinct-tree-shapes ctx class-id))
            "the measured reality: no shape evidence exists")
        (let [[logs _] (capture-mulog #(harvest/maybe-harvest! ctx class-id))
              report (first (filter #(= ::harvest/harvest-gate-report (:event %)) logs))]
          (is (some? report) "a gate report is logged")
          (is (= :abstained (get-in report [:pairs :coherence :verdict]))
              (str "the coherence abstention is VISIBLE, got " (pr-str report)))
          (is (true? (get-in report [:pairs :candidate?]))
              "and the verdict is unchanged — visibility only"))))))
