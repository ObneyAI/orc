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
  (:require [ai.obney.orc.ontology.core.todo-processors :as processors]
            [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.commands :as commands]
            [ai.obney.orc.evaluation.core.logic :as eval-logic]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
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

(defn- create-context
  "Default: no todo-processors. Processor-shaped tests opt into the harvest
   processor explicitly, so this namespace cannot start unrelated processors
   merely because an earlier test namespace registered them globally.

   `{:processor-names #{:ontology/on-tree-class-check-harvest}}` starts the
   same event store + read-model plumbing with only the processor under test.
   The harvest processor is subscribed to
   :ontology/task-classified, so in a processor-full context a class
   auto-harvests the instant it crosses the gate MID-FIXTURE — which makes any
   fixture whose LATER occurrences carry the signal under test untestable (the
   mint fires before the fixture finishes). Read-models are projected on demand
   by rmp/project, not by a todo-processor, so a processor-free context still
   reads back the real projections; the test drives maybe-harvest! explicitly."
  ([] (create-context {}))
  ([{:keys [processor-names] :or {processor-names #{}}}]
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

(defmacro with-gate-ctx
  "CC-26: a processor-free context — the fixture, not the harvest processor,
   decides when maybe-harvest! runs."
  [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defmacro with-harvest-processor-ctx [[sym] & body]
  `(let [~sym (create-context
                {:processor-names #{:ontology/on-tree-class-check-harvest}})]
     (try ~@body (finally (stop-context ~sym)))))

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
        (classify! ctx sheet-a1 class-a tick-a1)
        (classify! ctx sheet-a2 class-a tick-a2)
        (judge-score! ctx sheet-a1 tick-a1 "quality" 0.8)
        (judge-score! ctx sheet-a2 tick-a2 "quality" 0.6)
        (judge-score! ctx sheet-a1 tick-a1 "fit" 0.9)
        (classify! ctx sheet-b1 class-b tick-b1)
        (judge-score! ctx sheet-b1 tick-b1 "quality" 0.2)
        (Thread/sleep 250)

        (let [agg-a (#'processors/tree-class-aggregate-metrics ctx class-a)
              agg-b (#'processors/tree-class-aggregate-metrics ctx class-b)
              rm-a  (ontology/get-tree-class-judge-averages ctx class-a)
              rm-b  (ontology/get-tree-class-judge-averages ctx class-b)]
          (is (= (:judge-averages agg-a) rm-a)
              (str "class-a parity. aggregate=" (:judge-averages agg-a) " read-model=" rm-a))
          (is (= (:judge-averages agg-b) rm-b)
              (str "class-b parity. aggregate=" (:judge-averages agg-b) " read-model=" rm-b))
          (is (= {"quality" 0.7 "fit" 0.9} rm-a) "class-a means computed")
          (is (= {"quality" 0.2} rm-b) "class-b mean computed"))))))

(deftest slice1-no-scores-returns-nil-like-aggregate
  (testing "A class with classifications but NO judge scores -> read-model nil, matching aggregate (which omits :judge-averages)"
    (with-test-ctx [ctx]
      (let [class-c (random-uuid)
            sheet-c1 (random-uuid)]
        (classify! ctx sheet-c1 class-c)
        (Thread/sleep 200)
        (let [agg (#'processors/tree-class-aggregate-metrics ctx class-c)]
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
        (classify! ctx shared-sheet class-a tick-1)
        (judge-score! ctx shared-sheet tick-1 "quality" 0.9)
        (classify! ctx shared-sheet class-a tick-2)
        (judge-score! ctx shared-sheet tick-2 "quality" 0.7)
        (classify! ctx shared-sheet class-b tick-3)
        (judge-score! ctx shared-sheet tick-3 "quality" 0.2)
        (Thread/sleep 250)

        (let [rm-a (ontology/get-tree-class-judge-averages ctx class-a)
              rm-b (ontology/get-tree-class-judge-averages ctx class-b)]
          (is (= {"quality" 0.8} rm-a)
              (str "class-a (occurrences 1+2 only) mean should be (0.9+0.7)/2=0.8, got " rm-a))
          (is (= {"quality" 0.2} rm-b)
              (str "class-b (occurrence 3 only) mean should be 0.2 — NOT diluted by class-a's scores, got " rm-b)))))))


(defn- production-occurrence!
  "One PRODUCTION-SHAPED observation: classification + judge score on the
   shared HOST sheet + per-turn tick; bookend on its own EPHEMERAL sheet +
   ephemeral tick, carrying the host linkage in :source-sheet-id /
   :source-tick-id."
  [ctx class-id host-sheet turn-tick fingerprint score]
  (let [node-id (random-uuid)]
    (cp/process-command
      (assoc ctx :command
             {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :source-sheet-id host-sheet
            :source-tick-id turn-tick
            :source-node-id node-id
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
            :sheet-id (random-uuid)
            :tick-id (random-uuid)
            :source-sheet-id host-sheet
            :source-tick-id turn-tick
            :trajectory []
            :total-usage {:total-tokens 0}
            :tree-fingerprint fingerprint
            :status :success
            :duration-ms 100}))
    (verdict-occurrence! ctx host-sheet turn-tick node-id class-id :success)))

(deftest hp2-distinct-tree-shapes-production-faithful
  (testing "RR-21 retired harvest.clj's all-trees distinct-tree-shapes measure (the never-witnessed
             one this test originally pinned); winning-shape-coherence is what replaced it, and this
             proves the SAME HP-2 linkage the retired measure proved — the
             [source-sheet-id source-tick-id] join, NOT the ephemeral :sheet-id"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            host (random-uuid)]
        (production-occurrence! ctx class-a host (random-uuid) "shape-A" 0.9)
        (production-occurrence! ctx class-a host (random-uuid) "shape-B" 0.9)
        (Thread/sleep 250)
        (let [m (queries/winning-shape-coherence ctx class-a)]
          (is (= 2 (:successful-campaigns m)))
          (is (= 2 (:distinct-successful-shapes m))
              "two production-shaped occurrences with two fingerprints -> 2 distinct winning shapes"))))))

(deftest hp2-consolidator-gather-attaches-execution-evidence
  (testing "gather-recent-tree-class-events joins each observation to its bookend via
             [source-sheet-id source-tick-id] so the reflection LLM sees execution evidence"
    (with-test-ctx [ctx]
      (let [class-a (random-uuid)
            host (random-uuid)]
        (production-occurrence! ctx class-a host (random-uuid) "shape-A" 0.9)
        (Thread/sleep 250)
        (let [obs (#'processors/gather-recent-tree-class-events ctx class-a)]
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
        (let [agg (#'processors/tree-class-aggregate-metrics ctx class-a)]
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
        (let [agg-a (#'processors/tree-class-aggregate-metrics ctx class-a)
              agg-b (#'processors/tree-class-aggregate-metrics ctx class-b)]
          (is (= {"quality" 1.0} (:judge-averages agg-a))
              (str "class-a sees ONLY its own occurrence's score, got " (:judge-averages agg-a)))
          (is (= {"quality" 0.0} (:judge-averages agg-b))
              (str "class-b sees ONLY its own occurrence's score, got " (:judge-averages agg-b))))))))


(def ^:private good-class
  "Recurring + well-scored on BOTH axes. CC-26: the single :judge-average
   scalar was replaced by the two marginals it had flattened —
   :judge-trailing-averages (per DIMENSION, over the class's most recent
   :dimension-window scored occurrences — CC-24b/ADR 0029 replaced the
   lifetime mean here) and :occurrence-scores (per OCCURRENCE, temporal order,
   most recent last). RR-21: harvest-candidate? no longer reads a coherence
   metric at all (report-only rollout), so this fixture carries none."
  {:occurrences 12
   :judge-trailing-averages {"quality" 0.85}
   :occurrence-scores (vec (repeat 12 0.85))})

(deftest slice2-gate-passes-the-good-class
  (testing "recurring + well-scored + coherent -> harvest-candidate? true"
    (is (true? (logic/harvest-candidate? good-class logic/default-harvest-config)))))

(deftest slice2-gate-fails-below-occurrences
  (testing "below the occurrence floor -> false (not recurring enough)"
    (is (false? (logic/harvest-candidate?
                  (assoc good-class :occurrences 5)
                  logic/default-harvest-config)))))

(deftest slice2-gate-fails-below-consistency-floor
  (testing "occurrences that all sit below the consistency floor -> false (not well-scored)"
    (is (false? (logic/harvest-candidate?
                  (assoc good-class :occurrence-scores (vec (repeat 12 0.6)))
                  logic/default-harvest-config)))))

(deftest slice2-gate-fails-below-dimension-floor
  (testing "a judge dimension below the dimension floor -> false, even when every occurrence's
             aggregate clears the consistency floor (the axes are independent)"
    (is (false? (logic/harvest-candidate?
                  (assoc good-class :judge-trailing-averages {"quality" 1.0 "grounding" 0.6})
                  logic/default-harvest-config)))))

(deftest slice2-gate-no-longer-fails-grab-bag
  (testing "RR-21: coherence is REPORT-ONLY — harvest-candidate? no longer includes a coherence
             clause at all (the spec's rule PromoteWellScoredClass carries no coherence `requires`),
             so a rejected winning-shape-coherence verdict (too many distinct winning shapes
             relative to successful campaigns) no longer fails the gate. Pre-RR-21 this test named
             `slice2-gate-fails-grab-bag` and asserted `false?` here — the retired behaviour."
    (is (true? (logic/harvest-candidate?
                 (assoc good-class :winning-shape-coherence
                        {:successful-campaigns 12 :successful-shape-observations 12
                         :distinct-successful-shapes 11 :ratio 11/12 :status :rejected})
                 logic/default-harvest-config))
        "a grab-bag's rejected coherence no longer blocks promotion")))

(deftest slice2-gate-fails-nil-judge-signal
  (testing "no judge signal at all -> false (cannot be well-scored) — on EITHER axis"
    (is (false? (logic/harvest-candidate?
                  (assoc good-class :occurrence-scores nil)
                  logic/default-harvest-config)))
    (is (false? (logic/harvest-candidate?
                  (assoc good-class :occurrence-scores [])
                  logic/default-harvest-config))
        "an empty score history is not 'has qualified repeatedly'")
    (is (false? (logic/harvest-candidate?
                  (assoc good-class :judge-trailing-averages nil)
                  logic/default-harvest-config))
        "a class no judge ever scored has not been judged well")
    (is (false? (logic/harvest-candidate?
                  (assoc good-class :judge-trailing-averages {})
                  logic/default-harvest-config)))))

(deftest slice2-knobs-are-tunable
  (testing "loosening the config flips a class that fails under defaults to pass"
    (let [borderline {:occurrences 6
                      :judge-trailing-averages {"quality" 0.7}
                      :occurrence-scores (vec (repeat 6 0.7))
                      :distinct-tree-shapes 4}]
      (is (false? (logic/harvest-candidate? borderline logic/default-harvest-config))
          "fails under the HIGH default bar")
      (is (true? (logic/harvest-candidate?
                   borderline
                   {:min-occurrences 5 :dimension-floor 0.65
                    :consistency-window 5 :consistency-floor 0.65
                    :max-shapes-ratio 0.8}))
          "passes under a deliberately looser config"))))


(defn- record-tree-class-desc! [ctx class-id body]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-tree-class-description
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-id class-id
            :body body})))

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
   sheet/tick carrying the [source-sheet-id source-tick-id] linkage (HP-2 —
   the earlier same-sheet/same-tick bookend fixture hid the disjoint-domain
   join bugs exactly the way SJ-1's same-id fixtures did)."
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
        (processors/maybe-harvest! ctx class-id)
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
            (is (some? desc) "harvested behavior is stored + retrievable in the ontology")
            (is (= :behavioral-subtree (:scope desc)) "stamped :scope :behavioral-subtree")
            (is (= (:avoid-when good-body) (:avoid-when desc))
                "consolidated :avoid-when transplanted")
            (is (string? (:recommended-pattern desc)) "worked DSL recorded as :recommended-pattern")
            (is (>= (:consolidated-from-event-count desc) 10)
                "stamped :consolidated-from-event-count (>= gate floor) so anti-recency engages")))))))

(deftest slice3-fires-once-per-crossing
  (testing "a second maybe-harvest! after harvesting does NOT mint again (fire-once via class-id guard)"
    (with-test-ctx [ctx]
      (let [{:keys [class-id]} (setup-good-class! ctx)]
        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (true? (queries/already-harvested? ctx class-id)) "guard sees the harvest")
        (processors/maybe-harvest! ctx class-id)
        (processors/maybe-harvest! ctx class-id)
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
        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (false? (queries/already-harvested? ctx class-id)) "junk not marked harvested")
        (is (empty? (minted-harvest-events ctx class-id)) "no behavior minted for a below-N class")))))

(deftest slice3-harvests-a-grab-bag-report-only
  (testing "RR-21: a recurring+well-scored class that is a GRAB-BAG (many distinct winning shapes)
             IS harvested now — coherence is computed and reported (winning-shape-coherence
             :rejected) but no longer gates promotion. Pre-RR-21 this test named
             `slice3-skips-grab-bag` and asserted the mint was empty — the retired behaviour."
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            parent-id (random-uuid)]
        (seed-parent-behavior! ctx parent-id)
        (record-tree-class-desc! ctx class-id good-body)
        (dotimes [i 12]
          (occurrence! ctx class-id (random-uuid) (str "shape-" i) 0.85
                       (when (zero? i) [{:behavior-id parent-id :confidence 0.9 :reasoning "x"}])))
        (Thread/sleep 300)
        (let [m (queries/winning-shape-coherence ctx class-id)]
          (is (= :rejected (:status m)) "the winning-shape ratio really is a grab-bag"))
        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (= 1 (count (minted-harvest-events ctx class-id)))
            "grab-bag coherence no longer blocks promotion — report-only rollout (RR-21)")))))

(deftest slice3-processor-drives-harvest-end-to-end
  (testing "the registered on-tree-class-check-harvest processor mints the good class from real events (no direct call)"
    (with-harvest-processor-ctx [ctx]
      (let [{:keys [class-id]} (setup-good-class! ctx)]
        (occurrence! ctx class-id (random-uuid) "shape-A" 0.85 nil)
        (Thread/sleep 500)
        (is (= 1 (count (minted-harvest-events ctx class-id)))
            "the processor auto-harvested the good class end-to-end")))))


(def ^:private one-judge "quality")

(defn- multi-judge-occurrence!
  "One occurrence of `class-id` on the shared HOST sheet with its own turn
   tick, scored by EVERY judge in `judge->score` at that same occurrence, plus
   a production-shaped bookend carrying the [source-sheet-id source-tick-id]
   linkage."
  [ctx class-id host-sheet fingerprint judge->score behavioral-subtrees]
  (let [tick-id (random-uuid)
        node-id (random-uuid)]
    (cp/process-command
      (assoc ctx :command
             (cond-> {:command/name :ontology/assign-task-class
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :source-sheet-id host-sheet
                      :source-tick-id tick-id
                      :source-node-id node-id
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
              :sheet-id (random-uuid)
              :tick-id (random-uuid)
              :source-sheet-id host-sheet
              :source-tick-id tick-id
              :trajectory []
              :total-usage {:total-tokens 0}
              :tree-fingerprint fingerprint
              :status :success
              :duration-ms 100}))
    (verdict-occurrence! ctx host-sheet tick-id node-id class-id :success)))

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


(deftest cc26-recent-catastrophic-occurrences-veto-promotion
  (testing "20 occurrences, 16 perfect + 4 DISASTROUS — lifetime mean exactly 0.800, which the
             scalar gate promoted. Four catastrophic failures in twenty must NOT become durable,
             reusable knowledge: the last consistency_window occurrences must EACH clear
             consistency_floor, not average to it."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences
                                       (concat (repeat 16 1.0) (repeat 4 0.0))))]
        (let [avgs (ontology/get-tree-class-judge-averages ctx class-id)]
          (is (= {one-judge 0.8} avgs)
              (str "fixture must sit exactly on the old scalar boundary, got " avgs)))
        (is (= 20 (read-models/get-consolidation-total ctx :tree-class class-id))
            "fixture must really have 20 occurrences")

        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "a class whose recent occurrences include catastrophic failures must NOT be harvested")))))


(deftest cc26-old-failures-do-not-veto-forever
  (testing "the SAME 20 occurrences and the SAME lifetime mean of 0.800 as the cycle-1 fixture, with
             the four disastrous occurrences OLDEST instead of newest: the class has since qualified
             in each of its last consistency_window occurrences, so it MUST promote. consistently_
             qualified is a WINDOW property — a class that learned is not condemned by its history."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id parent-id]} (seed-scored-class!
                                           ctx (single-judge-occurrences
                                                 (concat (repeat 4 0.0) (repeat 16 1.0))))]
        (let [avgs (ontology/get-tree-class-judge-averages ctx class-id)]
          (is (= {one-judge 0.8} avgs)
              (str "same 0.800 lifetime mean as the vetoed fixture, got " avgs)))

        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (let [minted (minted-harvest-events ctx class-id)]
          (is (= 1 (count minted))
              "recent-window qualification promotes despite older catastrophic occurrences")
          (is (= parent-id (:parent-behavior (first minted)))
              "and it is anchored under the resolved abstract parent"))))))


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
        (let [avgs (ontology/get-tree-class-judge-averages ctx class-id)]
          (is (= 5 (count avgs)) (str "five judge dimensions projected, got " avgs))
          (is (= #{0.0 1.0} (set (vals avgs))) (str "one dimension at zero, four perfect, got " avgs))
          (is (= 0.8 (/ (reduce + 0.0 (vals avgs)) (double (count avgs))))
              "the collapsed mean-of-means sits exactly on the old 0.800 boundary"))
        (let [scores (queries/occurrence-scores ctx class-id)]
          (is (= 12 (count scores)) (str "twelve scored occurrences, got " (count scores)))
          (is (every? #(>= % 0.8) scores)
              (str "every occurrence CLEARS the consistency floor — so consistency cannot be the "
                   "clause that rejects this class, got " scores)))

        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "a dimension scoring zero vetoes promotion, uncompensated by four perfect dimensions")))))


(deftest cc26-dimension-floor-is-a-no-op-at-one-judge
  (testing "with ONE judge dimension, `every? >= floor` and the old collapsed `mean >= floor` are
             the SAME predicate — so replacing the mean with quality.all(...) cannot change any
             decision on today's corpus. Swept, not asserted."
    (is (= 1 logic/known-judge-dimension-count)
        "the load-bearing assumption this no-op proof is scoped to")
    (let [floor (:dimension-floor logic/default-harvest-config)
          values (concat (map #(/ % 100.0) (range 0 101))
                         [floor 0.7999999999 0.8000000001 0.0 1.0])
          judged (for [v values
                       :let [avgs {one-judge v}
                             old-scalar (/ (reduce + 0.0 (vals avgs)) (double (count avgs)))
                             old? (>= old-scalar (- floor logic/floor-comparison-tolerance))
                             new? (logic/every-dimension-qualified? avgs floor)]]
                   {:v v :old old? :new new?})
          disagreements (remove #(= (:old %) (:new %)) judged)]
      (println "  [cc26 no-op sweep] N =" (count judged) "single-judge values;"
               (count (filter :old judged)) "promote under the OLD mean;"
               (count (filter :new judged)) "under the NEW per-dimension rule;"
               (count disagreements) "disagreements")
      (is (<= 100 (count judged)) "the sweep really covers the unit interval")
      (is (seq (filter :old judged)) "the sweep contains promoting values")
      (is (seq (remove :old judged)) "the sweep contains rejecting values")
      (is (empty? disagreements)
          (str "no-op claim FALSIFIED at judge-count 1: " (pr-str (vec disagreements))
               " — if this fires, the extension from 'pin it with a failing-in-waiting test' to "
               "'implement it' was unjustified and must be reported, not patched")))

    (let [floor (:dimension-floor logic/default-harvest-config)
          five {"coding-outcome" 0.0 "grounding" 1.0 "completeness" 1.0
                "reasoning" 1.0 "safety" 1.0}]
      (is (true? (>= (/ (reduce + 0.0 (vals five)) (double (count five))) floor))
          "at FIVE judges the old collapsed mean is exactly 0.800 and PROMOTES a zero dimension")
      (is (false? (logic/every-dimension-qualified? five floor))
          "the new per-dimension rule vetoes it — so the no-op is a fact about judge-count 1, "))))

(deftest cc26-dimension-change-is-a-no-op-on-real-single-judge-streams
  (testing "REGRESSION on REAL projections, not hand-built maps: for single-judge classes the OLD
             collapsed rule (mean over (vals judge-averages), tolerant floor compare) and the NEW
             per-dimension rule return the SAME verdict. The read-model's own float accumulation is
             included on purpose — a knife-edge class whose twelve occurrences each score exactly
             0.8 projects to 0.7999999999999999. Pre-CC-29 both rules REJECTED it (the measured
             float artifact); with the CC-29 floor-comparison tolerance both rules PROMOTE it —
             identically under either structure, so the structural no-op claim is unchanged."
    (let [floor (:dimension-floor logic/default-harvest-config)
          tolerant-floor (- floor logic/floor-comparison-tolerance)
          cases [1.0 0.9 0.85 0.8 0.75 0.6 0.0]
          results
          (vec (for [score cases]
                 (with-gate-ctx [ctx]
                   (let [{:keys [class-id]} (seed-scored-class!
                                              ctx (single-judge-occurrences (repeat 12 score)))
                         avgs (ontology/get-tree-class-judge-averages ctx class-id)
                         old? (>= (/ (reduce + 0.0 (vals avgs)) (double (count avgs))) tolerant-floor)
                         new? (logic/every-dimension-qualified? avgs floor)]
                     (is (= logic/known-judge-dimension-count (count avgs))
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
        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (let [minted (minted-harvest-events ctx class-id)]
          (is (= 1 (count minted)) "unchanged promoting behaviour at judge-count 1")
          (is (= parent-id (:parent-behavior (first minted)))))))))


(deftest cc26-unanchored-class-is-not-harvested
  (testing "a class that clears every quality clause but has NO behavioral classification resolves
             to a nil abstract parent, and harvest is SKIPPED rather than creating an orphan"
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences (repeat 12 0.9))
                                 {:anchor? false})]
        (is (true? (logic/harvest-candidate?
                     {:occurrences (read-models/get-consolidation-total ctx :tree-class class-id)
                      :judge-trailing-averages (ontology/get-tree-class-judge-recent-averages
                                                 ctx class-id (:dimension-window logic/default-harvest-config))
                      :occurrence-scores (queries/occurrence-scores ctx class-id)}
                     logic/default-harvest-config))
            "the quality gate itself passes — only the anchor is missing")
        (is (nil? (queries/nearest-abstract-behavior ctx class-id))
            "no behavioral classification -> no abstract parent")

        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "no orphan behavior is minted")))))


(deftest cc26-processor-does-not-harvest-a-recently-failing-class
  (testing "with the registered harvest processor running and no direct maybe-harvest! call: a class whose
             recent occurrences are catastrophic is not minted, while a healthy class in the SAME
             context is — so the negative result is the gate, not a dead processor"
    (with-harvest-processor-ctx [ctx]
      (let [failing (seed-scored-class!
                      ctx (single-judge-occurrences
                            (concat (repeat 6 1.0) (repeat 14 0.0))))
            healthy (seed-scored-class!
                      ctx (single-judge-occurrences (repeat 12 0.9)))]
        (Thread/sleep 600)
        (is (= 1 (count (minted-harvest-events ctx (:class-id healthy))))
            "the healthy class was auto-harvested end-to-end (control: processors are running)")
        (is (= 20 (read-models/get-consolidation-total ctx :tree-class (:class-id failing)))
            "the failing class really accumulated 20 occurrences")
        (is (empty? (minted-harvest-events ctx (:class-id failing)))
            "no processor-driven mint for a class failing its recent window")))))


(deftest cc26-window-is-per-occurrence-not-a-mean-over-the-window
  (testing "20 occurrences, 19 perfect + ONE disastrous, the disastrous one MOST RECENT: lifetime
             mean 0.950 (the old gate promoted it) AND the window's own mean is exactly 0.800 (a
             mean-over-the-window gate would promote it too). The per-occurrence rule vetoes it."
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences
                                       (concat (repeat 19 1.0) [0.0])))
            window (take-last (:consistency-window logic/default-harvest-config)
                              (queries/occurrence-scores ctx class-id))]
        (is (= {one-judge 0.95} (ontology/get-tree-class-judge-averages ctx class-id))
            "lifetime mean 0.950 — comfortably above the old scalar bar")
        (is (= [1.0 1.0 1.0 1.0 0.0] window) (str "window shape, got " window))
        (is (>= (/ (reduce + 0.0 window) (double (count window)))
                (:consistency-floor logic/default-harvest-config))
            "the window's MEAN clears the consistency floor — so only a per-occurrence rule can
             reject this class")

        (processors/maybe-harvest! ctx class-id)
        (Thread/sleep 150)
        (is (empty? (minted-harvest-events ctx class-id))
            "one disastrous occurrence in the window vetoes promotion — it is not averaged away")))))


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
                       ctx class-id (:dimension-window logic/default-harvest-config))]
        (is (= {one-judge 0.7999999999999999} avgs)
            (str "twelve real 0.8 scores project to the drifted double, got " avgs))
        (is (< (get avgs one-judge) 0.8)
            "…which sits strictly BELOW the floor literal — the artifact is real")
        (is (= (get avgs one-judge)
               (/ (reduce + 0.0 (repeat 12 0.8)) (double 12)))
            "and it is exactly what double accumulation of twelve 0.8s produces")
        (is (= (vec (repeat 12 0.8)) (queries/occurrence-scores ctx class-id))
            "occurrence scores are the bit-identical band value")
        (is (= {one-judge 0.7999999999999999} trailing)
            (str "the TRAILING projection drifts identically (ten 0.8s), got " trailing))

        (is (true? (logic/every-dimension-qualified? avgs cc29-measured-artifact-floor))
            "an at-floor dimension mean qualifies — the floor comparison tolerates representation error")
        (is (true? (logic/every-dimension-qualified? trailing cc29-measured-artifact-floor))
            "…on the trailing projection too")
        (processors/maybe-harvest! ctx class-id)
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
            floor (:dimension-floor logic/default-harvest-config)
            window (:dimension-window logic/default-harvest-config)
            trailing (ontology/get-tree-class-judge-recent-averages ctx class-id window)
            m (get trailing one-judge)]
        (is (= [0.75 0.75 0.75 0.75 0.75]
               (vec (take-last 5 (queries/occurrence-scores ctx class-id))))
            "recent window clears — dimension axis isolated")
        (is (true? (logic/consistently-qualified?
                     (queries/occurrence-scores ctx class-id)
                     (:consistency-window logic/default-harvest-config)
                     (:consistency-floor logic/default-harvest-config)))
            "…proven, not assumed: the consistency axis is not the rejector")
        (is (< (Math/abs (- m 0.725)) 1.0E-9)
            (str "trailing mean is quantum/window (0.025) below the floor, got " m))

        (is (false? (logic/every-dimension-qualified? trailing floor))
            "one band-quantum short at window 10 is a REAL distinction — still rejected")
        (processors/maybe-harvest! ctx class-id)
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
            scores (queries/occurrence-scores ctx class-id)]
        (is (= 12 (count scores)) (str "twelve scored occurrences, got " (count scores)))
        (is (every? #(= 0.7999999999999999 %) scores)
            (str "every occurrence aggregate drifts to the measured double, got " (vec (distinct scores))))
        (is (every? #(< % 0.8) scores)
            "…strictly below the floor literal — the artifact is real on this axis too")

        (is (true? (logic/consistently-qualified?
                     scores
                     (:consistency-window logic/default-harvest-config)
                     cc29-measured-artifact-floor))
            "an at-floor window qualifies — the floor comparison tolerates representation error")
        (is (false? (logic/every-dimension-qualified?
                      (ontology/get-tree-class-judge-recent-averages
                        ctx class-id (:dimension-window logic/default-harvest-config))
                      (:dimension-floor logic/default-harvest-config)))
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
            scores (queries/occurrence-scores ctx class-id)
            window (:consistency-window logic/default-harvest-config)
            floor cc29-measured-artifact-floor]
        (is (= 12 (count scores)) (str "twelve scored occurrences, got " (count scores)))
        (is (= 0.75 (peek scores)) "most recent occurrence banded a genuine 0.75")
        (is (every? #(= 0.7999999999999999 %) (pop scores))
            "…the other eleven sit AT the floor via the real drifted computation")

        (is (false? (logic/consistently-qualified? scores window floor))
            "one occurrence a full band-quantum short is a REAL distinction — still rejected")
        (is (true? (logic/consistently-qualified? (pop scores) window floor))
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
                               k (range 0 (inc (* 20 n)))
                               :let [q (quot k n) r (rem k n)
                                     bands (concat (repeat r (/ (inc q) 20.0))
                                                   (repeat (- n r) (/ q 20.0)))
                                     drifted (/ (reduce + 0.0 bands) (double n))
                                     exact? (>= (/ k (* 20 n)) exact-floor)
                                     verdict? (logic/every-dimension-qualified?
                                                {one-judge drifted} floor)]]
                           {:n n :k k :drifted drifted :exact exact? :verdict verdict?}))))
          disagreements (fn [checks] (remove #(= (:exact %) (:verdict %)) checks))
          drifting-at-floor (fn [checks floor]
                              (filter #(and (:exact %) (< (:drifted %) floor)) checks))
          shipped-floor (:dimension-floor logic/default-harvest-config)
          shipped (sweep shipped-floor)
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
      (is (seq (drifting-at-floor measured cc29-measured-artifact-floor))
          "non-vacuous: at the measured floor the sweep CONTAINS at-floor means whose double
           accumulation drifts below the floor literal")
      (is (empty? (disagreements measured))
          (str "tolerance FALSIFIED at the measured floor: "
               (pr-str (vec (take 5 (disagreements measured))))))
      (is (empty? (disagreements shipped))
          (str "tolerance FALSIFIED at the shipped floor: "
               (pr-str (vec (take 5 (disagreements shipped))))))
      (is (empty? (drifting-at-floor shipped shipped-floor))
          (str "the shipped floor is dyadic, so an at-floor mean cannot drift below it; if this "
               "fires, the floor moved OFF a dyadic value and the tolerance is load-bearing "
               "again at the dimension floor: " (pr-str (vec (take 5 (drifting-at-floor shipped shipped-floor)))))))))


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


(deftest cc24b-harvest-floors-are-points-on-the-shipped-judge-lattice
  (testing "every SHIPPED rubric scale is the discrete 1-5 mapped (n-1)/4, so the achievable
             per-judge score set is exactly {0, 0.25, 0.5, 0.75, 1.0} — and BOTH harvest floors
             are members of it. A floor that is not a lattice point is a value whose stated
             meaning differs from its operative meaning (ADR 0027, ADR 0029 decision 1)."
    (let [shipped [eval-logic/GROUNDING_SCALE eval-logic/INSTRUCTION_FOLLOWING_SCALE
                   eval-logic/REASONING_SCALE eval-logic/COMPLETENESS_SCALE]
          lattices (mapv (fn [s] (into (sorted-set) (map #(eval-logic/level->unit-score s %))
                                       (eval-logic/levels s)))
                         shipped)]
      (is (= 4 (count shipped)) "all four shipped rubric scales are under test")
      (is (= [#{0.0 0.25 0.5 0.75 1.0}] (distinct lattices))
          (str "the shipped judge lattice, got " (pr-str lattices)))
      (let [lattice (first lattices)]
        (is (contains? lattice (:dimension-floor logic/default-harvest-config))
            (str "dimension-floor is OFF the lattice: "
                 (:dimension-floor logic/default-harvest-config) " not in " lattice))
        (is (contains? lattice (:consistency-floor logic/default-harvest-config))
            (str "consistency-floor is OFF the lattice: "
                 (:consistency-floor logic/default-harvest-config) " not in " lattice))))))


(deftest cc24b-floors-admit-band-four-and-reject-band-three
  (testing "ADR 0029 decision 1: both floors are 0.75 and the dimension window is 10. A class
             whose every judge trails at exactly band 4 (0.75 — described to the judge as good
             work) QUALIFIES; one trailing at band 3 (0.5) does not."
    (is (= 0.75 (:dimension-floor logic/default-harvest-config)))
    (is (= 0.75 (:consistency-floor logic/default-harvest-config)))
    (is (= 10 (:dimension-window logic/default-harvest-config)))
    (is (= 5 (:consistency-window logic/default-harvest-config))
        "the consistency window is ratified UNCHANGED")
    (let [floor (:dimension-floor logic/default-harvest-config)]
      (is (true? (logic/every-dimension-qualified? {one-judge 0.75} floor))
          "band 4 counts")
      (is (true? (logic/every-dimension-qualified? {one-judge 0.75 "grounding" 1.0} floor))
          "band 4 counts on every dimension")
      (is (false? (logic/every-dimension-qualified? {one-judge 0.75 "grounding" 0.5} floor))
          "one dimension at band 3 vetoes")
      (is (true? (logic/consistently-qualified? (vec (repeat 5 0.75)) 5 floor))
          "a window of band-4 occurrences counts")
      (is (false? (logic/consistently-qualified? [0.75 0.75 0.75 0.75 0.5] 5 floor))
          "one band-3 occurrence in the window vetoes"))))


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
          floor (:dimension-floor logic/default-harvest-config)
          window (:dimension-window logic/default-harvest-config)
          cwindow (:consistency-window logic/default-harvest-config)
          sweep (fn [f]
                  (vec (for [i (range 1 (inc (count scores)))
                             :let [p (subvec scores 0 i)]]
                         {:i i
                          :lifetime (logic/every-dimension-qualified?
                                      {one-judge (mean* p)} f)
                          :trailing (logic/every-dimension-qualified?
                                      {one-judge (mean* (take-last window p))} f)
                          :consistent (logic/consistently-qualified? p cwindow f)})))
          positions (sweep floor)
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
            floor (:dimension-floor logic/default-harvest-config)
            window (:dimension-window logic/default-harvest-config)
            lifetime (ontology/get-tree-class-judge-averages ctx class-id)
            trailing (ontology/get-tree-class-judge-recent-averages ctx class-id window)]
        (is (= 22 (count scored)) "twenty-two real scored occurrences")
        (is (= 5 (count (filter zero? (take 10 scored))))
            (str "the early block of engine-defect zeros is present, got " (pr-str scored)))
        (is (pos? (count (remove some? prefix)))
            (str "the real prefix really interleaves UNSCORED occurrences (skip-not-zero), got "
                 (count (remove some? prefix)) " of " (count prefix)))
        (is (= (queries/occurrence-scores ctx class-id) scored)
            "the real event path reproduces the banked sequence exactly")

        (is (< (Math/abs (- (get lifetime one-judge) 0.5795454545454546)) 1.0E-9)
            (str "lifetime mean, got " lifetime))
        (is (false? (logic/every-dimension-qualified? lifetime floor))
            "the LIFETIME mean cannot clear the floor — the mechanism ADR 0029 replaced")
        (is (= {one-judge 0.825} trailing)
            (str "the trailing-10 mean clears the floor, got " trailing))
        (is (true? (logic/every-dimension-qualified? trailing floor))
            "the TRAILING window earns eligibility the lifetime mean cannot express")

        (let [metrics {:occurrences (read-models/get-consolidation-total ctx :tree-class class-id)
                       :occurrence-scores (queries/occurrence-scores ctx class-id)}]
          (is (true? (logic/harvest-candidate?
                       (assoc metrics :judge-trailing-averages trailing)
                       logic/default-harvest-config)))
          (is (false? (logic/harvest-candidate?
                        (assoc metrics :judge-trailing-averages lifetime)
                        logic/default-harvest-config))
              "the ONLY difference is trailing-vs-lifetime"))

        (processors/maybe-harvest! ctx class-id)
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
            floor (:dimension-floor logic/default-harvest-config)
            window (:dimension-window logic/default-harvest-config)
            trailing (ontology/get-tree-class-judge-recent-averages ctx class-id window)
            occ-scores (queries/occurrence-scores ctx class-id)]
        (is (= 90 (count scored)) "ninety real scored occurrences")
        (is (= scored occ-scores) "the real event path reproduces the banked sequence exactly")
        (is (true? (logic/consistently-qualified?
                     occ-scores (:consistency-window logic/default-harvest-config) floor))
            (str "the last five occurrences all clear the floor, got "
                 (pr-str (vec (take-last 5 occ-scores)))))
        (is (= {one-judge 0.725} trailing)
            (str "the trailing-10 mean has fallen below the floor, got " trailing))
        (is (false? (logic/every-dimension-qualified? trailing floor))
            "quantum/window below the floor is a REAL distinction — eligibility is lost")
        (is (true? (logic/every-dimension-qualified?
                     {one-judge (mean* (take-last window (subvec scored 0 22)))} floor))
            "…and it DID qualify at its 22nd scored occurrence — enforcement is continuously earned")
        (is (false? (logic/harvest-candidate?
                      {:occurrences (read-models/get-consolidation-total ctx :tree-class class-id)
                       :judge-trailing-averages trailing
                       :occurrence-scores occ-scores}
                      logic/default-harvest-config))
            "the gate rejects it")
        (processors/maybe-harvest! ctx class-id)
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
        (is (= 156 (read-models/get-consolidation-total ctx :tree-class class-id))
            "the whole real history was seeded")
        (is (= logic/recent-occurrence-bound (count retained))
            (str "the retained sequence is capped at the bound, got " (count retained)))
        (is (<= 29 logic/recent-occurrence-bound)
            "the bound exceeds the measured worst-case span needed for 10 scored occurrences")
        (is (not (instance? clojure.lang.APersistentVector$SubVector retained))
            (str "the retained sequence is a VIEW over an unbounded root vector — the count is "
                 "bounded but the memory is not (CC-22): " (class retained)))
        (is (= {one-judge 0.275}
               (ontology/get-tree-class-judge-recent-averages
                 ctx class-id (:dimension-window logic/default-harvest-config)))
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
            agg (#'processors/tree-class-aggregate-metrics ctx class-id)]
        (is (= (:judge-averages agg) lifetime)
            (str "parity oracle holds. aggregate=" (:judge-averages agg) " read-model=" lifetime))
        (is (not= lifetime trailing)
            (str "and the two accessors really are different projections: "
                 lifetime " vs " trailing))))))


(deftest cc24b-coherence-abstention-is-observable-in-the-gate-report
  (testing "a class with NO winning-shape evidence at all must be REPORTED as NOT-MEASURABLE — RR-21
             replaced the pre-RR-21 all-trees ABSTAINED verdict with this — never silently reported
             as coherent or rejected; and RR-21 made the WHOLE clause report-only, so none of its
             three verdicts (:not-measurable / :qualified / :rejected) gates promotion"
    (let [base {:occurrences 12
                :judge-trailing-averages {one-judge 0.75}
                :occurrence-scores (vec (repeat 12 0.75))}
          not-measurable (assoc base :winning-shape-coherence
                                 {:successful-campaigns 12 :successful-shape-observations 0
                                  :distinct-successful-shapes 0 :ratio nil :status :not-measurable})
          qualified (assoc base :winning-shape-coherence
                            {:successful-campaigns 12 :successful-shape-observations 12
                             :distinct-successful-shapes 3 :ratio 1/4 :status :qualified})
          rejected (assoc base :winning-shape-coherence
                           {:successful-campaigns 12 :successful-shape-observations 12
                            :distinct-successful-shapes 11 :ratio 11/12 :status :rejected})
          report-a (logic/harvest-gate-report not-measurable logic/default-harvest-config)
          report-b (logic/harvest-gate-report qualified logic/default-harvest-config)
          report-c (logic/harvest-gate-report rejected logic/default-harvest-config)]
      (is (= :not-measurable (get-in report-a [:coherence :verdict]))
          (str "no winning-shape evidence -> NOT-MEASURABLE, got " (pr-str report-a)))
      (is (= :qualified (get-in report-b [:coherence :verdict]))
          (str "real shapes -> a real verdict, got " (pr-str report-b)))
      (is (= :rejected (get-in report-c [:coherence :verdict]))
          (str "a grab-bag is still REPORTED rejected, got " (pr-str report-c)))
      (is (true? (:candidate? report-a)) "not-measurable never gates promotion")
      (is (true? (:candidate? report-b)) "qualified never gated promotion either")
      (is (true? (:candidate? report-c)) "REJECTED no longer gates promotion — report-only rollout")
      (is (= (logic/harvest-candidate? not-measurable logic/default-harvest-config)
             (:candidate? report-a))
          "the report agrees with the gate it reports on")
      (is (= (logic/harvest-candidate? rejected logic/default-harvest-config)
             (:candidate? report-c))))))

(deftest cc24b-coherence-abstention-is-logged-by-maybe-harvest
  (testing "…and the report really reaches an operator: maybe-harvest! emits the gate report, and
             on this fixture (real fingerprints on every bookend) it says QUALIFIED — RR-21's
             not-measurable case (no shape evidence at all) is proven separately, in
             cc24b-shapeless-class-logs-the-abstention"
    (with-gate-ctx [ctx]
      (let [{:keys [class-id]} (seed-scored-class!
                                 ctx (single-judge-occurrences (repeat 12 1.0)))
            [logs _] (capture-mulog #(processors/maybe-harvest! ctx class-id))
            reports (filter #(= :ai.obney.orc.ontology.core.harvest/harvest-gate-report (:event %)) logs)]
        (is (= 1 (count reports))
            (str "exactly one gate report is logged, got " (pr-str (mapv :event logs))))
        (is (= :qualified (get-in (first reports) [:pairs :coherence :verdict]))
            (str "shapes present -> qualified, got " (pr-str (first reports))))))))

(deftest cc24b-shapeless-class-logs-the-abstention
  (testing "the SHAPELESS case measured in both real stores: no tree-execution evidence at all, so
             winning-shape-coherence is NOT-MEASURABLE (RR-21 replaced the all-trees ABSTAINED
             verdict this test originally pinned with this) — and the log SAYS so, report-only"
    (with-gate-ctx [ctx]
      (let [class-id (random-uuid)
            parent-id (random-uuid)
            host (random-uuid)]
        (seed-parent-behavior! ctx parent-id)
        (record-tree-class-desc! ctx class-id good-body)
        (dotimes [i 12]
          (let [tick (random-uuid)
                node-id (random-uuid)]
            (cp/process-command
              (assoc ctx :command
                     (cond-> {:command/name :ontology/assign-task-class
                              :command/id (random-uuid)
                              :command/timestamp (time/now)
                              :source-sheet-id host
                              :source-tick-id tick
                              :source-node-id node-id
                              :assigned-tree-id class-id
                              :confidence 0.95
                              :top-candidates []
                              :reasoning "test"
                              :was-fresh-mint? false}
                       (zero? i) (assoc :behavioral-subtrees
                                        [{:behavior-id parent-id :confidence 0.9 :reasoning "x"}]))))
            (judge-score! ctx host tick one-judge 1.0)
            (verdict-occurrence! ctx host tick node-id class-id :success)))
        (Thread/sleep 250)
        (let [m (queries/winning-shape-coherence ctx class-id)]
          (is (= 12 (:successful-campaigns m)) "the measured reality: 12 successful campaigns")
          (is (zero? (:successful-shape-observations m)) "…and no shape evidence at all")
          (is (nil? (:ratio m)))
          (is (= :not-measurable (:status m))))
        (let [[logs _] (capture-mulog #(processors/maybe-harvest! ctx class-id))
              report (first (filter #(= :ai.obney.orc.ontology.core.harvest/harvest-gate-report (:event %)) logs))]
          (is (some? report) "a gate report is logged")
          (is (= :not-measurable (get-in report [:pairs :coherence :verdict]))
              (str "the not-yet-measurable verdict is VISIBLE, got " (pr-str report)))
          (is (true? (get-in report [:pairs :candidate?]))
              "and the verdict is unchanged — report-only, never a gate"))))))


(defn- land-campaign!
  "Land one full campaign — classify, the given Phase-2 bookends IN ORDER,
   then a verdict — WITHOUT driving any processor, so several campaigns'
   occurrence events can be made to land in the store as a BACKLOG before the
   harvest-check handler ever processes any of them. `bookends` is a vector
   of [fingerprint status] pairs, mirroring the propagated RR-21 contract
   test's own `campaign!`/`bookend!` fixture shape."
  [ctx class-id bookends verdict]
  (let [sheet (random-uuid) tick (random-uuid) node (random-uuid)]
    (classify! ctx sheet class-id tick)
    (doseq [[fingerprint status] bookends]
      (cp/process-command
        (assoc ctx :command
               {:command/name :sheet/record-rlm-tree-execution-completion
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :sheet-id (random-uuid)
                :tick-id (random-uuid)
                :source-sheet-id sheet
                :source-tick-id tick
                :trajectory []
                :total-usage {:total-tokens 0}
                :tree-fingerprint fingerprint
                :status status
                :duration-ms 1})))
    (verdict-occurrence! ctx sheet tick node class-id verdict)))

(deftest rr21-coherence-report-is-bounded-to-its-own-occurrence
  (testing "each :ontology/shape-coherence-reported reflects the measure strictly AS OF its own
             triggering occurrence's durable position, not the store's CURRENT state at processing
             time — reproduces the scheduling-race finding deterministically: all three occurrence
             events land in the store as a BACKLOG (no processor registered) before the harvest-
             check handler processes any of them, then the SAME registered handler-fn is driven
             directly, once per occurrence, in durable order — exactly a processor that only starts
             consuming after the backlog already exists, with no async/pubsub timing involved"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            handler-fn (:handler-fn (get @tp/processor-registry* :ontology/on-tree-class-check-harvest))]
        (land-campaign! ctx class-id [["shape-x" :failure] ["shape-a" :success]] :success)
        (land-campaign! ctx class-id [["shape-a" :success]] :failure)
        (land-campaign! ctx class-id [["shape-b" :success]] :success)
        (let [occurrences (->> (into [] (es/read (:event-store ctx)
                                                 {:tenant-id (:tenant-id ctx)
                                                  :types #{:ontology/tree-class-occurrence-recorded}}))
                               (filter #(= class-id (:assigned-tree-id %))))
              _ (is (= 3 (count occurrences)) "all three occurrence events really landed before any processing")
              _ (doseq [event occurrences] (handler-fn (assoc ctx :event event)))
              reports (->> (into [] (es/read (:event-store ctx)
                                             {:tenant-id (:tenant-id ctx)
                                              :types #{:ontology/shape-coherence-reported}}))
                           (filter #(= class-id (:tree-class %)))
                           (sort-by :verdict-occurrences))]
          (is (= 3 (count reports)) "one durable report per occurrence, even under a backlog")
          (is (= [1 2 3] (mapv :verdict-occurrences reports))
              "each report's OWN position — never the backlog's final count of 3 for every report")
          (is (= [1 1 2] (mapv :successful-campaigns reports))
              "the first two reports must not see the THIRD campaign's success, which lands after them durably")
          (is (= [1 1 2] (mapv :successful-shape-observations reports)))
          (is (= [1 1 2] (mapv :distinct-successful-shapes reports))
              "shapes A and B never share a report until the third occurrence brings B in")
          (is (= [1 1 1] (mapv :ratio reports)))
          (is (= [:rejected :rejected :rejected] (mapv :status reports))
              "1/1, 1/1 and 2/2 are all > the 0.5 max-shapes-ratio")
          (is (= (mapv :event/id occurrences) (mapv :source-occurrence-event-id reports))
              "each report names the exact occurrence event it was bounded to, in durable order"))))))
