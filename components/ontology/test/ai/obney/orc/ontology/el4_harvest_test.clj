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
            [dscloj.core :as dscloj]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            ;; Register the :evaluation/record-judge-score command handler +
            ;; its schemas so judge-score! actually emits :judge/score-emitted.
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; ---------------------------------------------------------------------------
;; Stub the consolidator's reflection LLM so autonomous consolidations fired
;; by the threshold processor complete fast + deterministically (same reason
;; as consolidation_trigger_test — avoid real OpenRouter + retry-loop bleed).
;; ---------------------------------------------------------------------------
(defn- stub-predict-fixture [f]
  (with-redefs [dscloj/predict
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
(defn- create-context []
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
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

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

;; ---------------------------------------------------------------------------
;; Event-stream fixtures — real commands, explicit sheet-ids so the
;; sheet -> tree-class JOIN is deterministic.
;; ---------------------------------------------------------------------------
(defn- classify! [ctx sheet-id tree-class-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :source-sheet-id sheet-id
            :source-tick-id (random-uuid)
            :source-node-id (random-uuid)
            :assigned-tree-id tree-class-id
            :confidence 0.95
            :top-candidates []
            :reasoning "test"
            :was-fresh-mint? false})))

(defn- judge-score! [ctx sheet-id judge-name score]
  (cp/process-command
    (assoc ctx :command
           {:command/name :evaluation/record-judge-score
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :node-id (random-uuid)
            :tick-id (random-uuid)
            :judge-name judge-name
            :judge-config {}
            :score score
            :feedback ""
            :dimensions []})))

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
            sheet-b1 (random-uuid)]
        ;; class-a: two sheets, judge :quality scored twice + :fit once
        (classify! ctx sheet-a1 class-a)
        (classify! ctx sheet-a2 class-a)
        (judge-score! ctx sheet-a1 "quality" 0.8)
        (judge-score! ctx sheet-a2 "quality" 0.6)   ;; quality mean = 0.7
        (judge-score! ctx sheet-a1 "fit" 0.9)       ;; fit mean = 0.9
        ;; class-b: one sheet, judge :quality once
        (classify! ctx sheet-b1 class-b)
        (judge-score! ctx sheet-b1 "quality" 0.2)   ;; quality mean = 0.2
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

;; ===========================================================================
;; SLICE 2 — the conservative harvest GATE (pure fn truth-table)
;; ===========================================================================

(def ^:private good-class
  "Recurring + well-scored + coherent."
  {:occurrences 12 :judge-average 0.85 :distinct-tree-shapes 3})

(deftest slice2-gate-passes-the-good-class
  (testing "recurring + well-scored + coherent -> harvest-candidate? true"
    (is (true? (harvest/harvest-candidate? good-class harvest/default-harvest-config)))))

(deftest slice2-gate-fails-below-occurrences
  (testing "below the occurrence floor -> false (not recurring enough)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :occurrences 5)
                  harvest/default-harvest-config)))))

(deftest slice2-gate-fails-below-judge-average
  (testing "below the judge-average floor -> false (not well-scored)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :judge-average 0.6)
                  harvest/default-harvest-config)))))

(deftest slice2-gate-fails-grab-bag
  (testing "too many distinct tree-shapes relative to occurrences -> false (grab-bag, not a coherent cluster)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :distinct-tree-shapes 11)
                  harvest/default-harvest-config)))))

(deftest slice2-gate-fails-nil-judge-average
  (testing "no judge signal at all -> false (cannot be well-scored)"
    (is (false? (harvest/harvest-candidate?
                  (assoc good-class :judge-average nil)
                  harvest/default-harvest-config)))))

(deftest slice2-knobs-are-tunable
  (testing "loosening the config flips a class that fails under defaults to pass"
    (let [borderline {:occurrences 6 :judge-average 0.7 :distinct-tree-shapes 4}]
      (is (false? (harvest/harvest-candidate? borderline harvest/default-harvest-config))
          "fails under the HIGH default bar")
      (is (true? (harvest/harvest-candidate?
                   borderline
                   {:min-occurrences 5 :min-judge-average 0.65 :max-shapes-ratio 0.8}))
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
  "One real observation of a tree-class: classify + judge-score + tree
   execution, all on the SAME sheet so the joins line up."
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
    (judge-score! ctx sheet-id "quality" score)
    (cp/process-command
      (assoc ctx :command
             {:command/name :sheet/record-rlm-tree-execution-completion
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :sheet-id sheet-id
              :tick-id tick-id
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
