(ns ai.obney.orc.evaluation.rr30-default-judge-dimensions-test
  "RR-30 — default judges carry dimension-specific feedback.

   The ActionableFeedback guarantee (specs/evaluation.allium): every
   successful score carries dimension-specific feedback suitable for
   human diagnosis and downstream instruction optimization. On the live
   processor path, `invoke-llm-judge` (judge_runtime.clj) hard-codes
   `:dimensions []` even though each of the four default LLM judges
   already produces named evidence lists (judges.clj). This suite drives
   the live processor path end to end (sheet -> node -> declare-judge ->
   set-node-judges -> complete-node-execution) with a stubbed `llm/predict`
   and reads back the emitted `:judge/score-emitted` event, one judge at a
   time, per the handoff's TDD cycle list.

   Cycle order: grounding -> reasoning -> completeness ->
   instruction-following -> heuristic-structural/custom-judge guard."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [malli.core :as m]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.heuristic-structural :as heuristic-structural]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.llm.interface :as llm]))

;; =============================================================================
;; Test context (mirrors judge_runtime_test.clj / judge_async_command_test.clj)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr30-judge-dims-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :llm-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start (cond-> {:event-pubsub ps :topics topics
                                                 :handler-fn handler-fn :context base-ctx}
                                          (= "evaluation" (namespace proc-name))
                                          (assoc :processor-name proc-name)))))
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

(defn- set-living-description-enabled! [ctx enabled?]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/set-living-description-enabled
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :enabled? enabled?}))
  (Thread/sleep 100))

(defn- wait-for-score-event
  "Poll until a :judge/score-emitted event for `tick-id` lands, or the
   timeout elapses. Returns it (or nil on timeout)."
  [ctx tick-id]
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (let [evts (into [] (es/read (:event-store ctx)
                                   {:types #{:judge/score-emitted}
                                    :tenant-id (:tenant-id ctx)}))
            matching (first (filter #(= tick-id (:tick-id %)) evts))]
        (cond
          matching matching
          (>= (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 50) (recur)))))))

;; =============================================================================
;; Live-path driver: sheet -> declare-judge -> leaf node -> set-node-judges
;; -> complete-node-execution, with llm/predict stubbed. Returns the
;; matching :judge/score-emitted event (or nil on timeout).
;; =============================================================================

(defn- run-default-judge!
  [ctx {:keys [judge-type judge-name fake-llm-outputs host-writes]}]
  (set-living-description-enabled! ctx true)
  (let [sheet-id (-> (cp/process-command
                       (assoc ctx :command
                              {:command/name :sheet/create-sheet
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :name (str "rr30-" (name judge-type) "-" (random-uuid))}))
                     :command-result/events first :sheet-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/declare-judge
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :judge-name judge-name
                    :judge-config {:type judge-type}}))
        node-id (-> (cp/process-command
                      (assoc ctx :command
                             {:command/name :sheet/create-node
                              :command/id (random-uuid)
                              :command/timestamp (time/now)
                              :sheet-id sheet-id
                              :type :leaf}))
                    :command-result/events first :node-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/set-node-judges
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :node-id node-id
                    :judges [judge-name]}))
        tick-id (random-uuid)]
    (Thread/sleep 150)
    (with-redefs [llm/predict (fn [_provider _module _inputs _options]
                                {:outputs fake-llm-outputs
                                 :usage {:total-tokens 100}
                                 :model "fake-llm-judge"})]
      (cp/process-command
        (assoc ctx :command
               {:command/name :sheet/complete-node-execution
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :sheet-id sheet-id
                :tick-id tick-id
                :node-id node-id
                :node-type :leaf
                :status :success
                :writes host-writes}))
      (wait-for-score-event ctx tick-id))))

(defn- validate-score-emitted!
  "Assert the event validates against the registered :judge/score-emitted
   schema (which embeds DimensionScore for each entry in :dimensions)."
  [event]
  (let [schema (get @schema-util/registry* :judge/score-emitted)]
    (is (m/validate schema (select-keys event [:sheet-id :tick-id :node-id :judge-name
                                               :judge-config :score :feedback :dimensions
                                               :emitted-at]))
        (str "emitted event must validate against the :judge/score-emitted schema (DimensionScore included). Got: "
             (pr-str (m/explain schema event))))))

;; =============================================================================
;; Cycle 1 — grounding
;; =============================================================================

(deftest rr30-grounding-judge-emits-grounding-dimension
  (testing "the grounding judge's emitted score carries a 'Source Grounding' dimension built from its own grounded/ungrounded claims"
    (with-test-ctx [ctx]
      (let [event (run-default-judge!
                    ctx
                    {:judge-type :grounding
                     :judge-name "grounding"
                     :fake-llm-outputs {:level 4
                                        :reasoning "Adversarial review: claims trace to the source."
                                        :grounded-claims ["The answer cites the report's Q3 revenue figure directly"]
                                        :ungrounded-claims []
                                        :feedback "Well grounded overall."}
                     :host-writes {:answer "Q3 revenue was $4.2M per the report."}})]
        (is (some? event) "a :judge/score-emitted event must land for the grounding judge")
        (when event
          (validate-score-emitted! event)
          (is (seq (:dimensions event)) "dimensions must be non-empty")
          (let [dim (first (:dimensions event))]
            (is (= "Source Grounding" (:name dim)) "dimension is named in the grounding judge's own vocabulary (the rubric's name, the one the aggregate and the ontology classifier already know)")
            (is (= (:score event) (:score dim)) "the dimension's score equals the event's score")
            (is (str/includes? (:feedback dim) "Q3 revenue figure directly")
                (str "dimension feedback must mention the cited grounded claim. Got: " (pr-str dim)))))))))

;; =============================================================================
;; Cycle 2 — reasoning
;; =============================================================================

(deftest rr30-reasoning-judge-emits-reasoning-dimension
  (testing "the reasoning judge's emitted score carries a 'Reasoning Quality' dimension built from its own strengths/weaknesses"
    (with-test-ctx [ctx]
      (let [event (run-default-judge!
                    ctx
                    {:judge-type :reasoning
                     :judge-name "reasoning"
                     :fake-llm-outputs {:level 4
                                        :reasoning "Adversarial logic review: chain holds."
                                        :reasoning-strengths ["The inference chain traces cause to effect without gaps"]
                                        :reasoning-weaknesses []
                                        :feedback "Sound reasoning overall."}
                     :host-writes {:answer "Because X caused Y, the conclusion follows."}})]
        (is (some? event) "a :judge/score-emitted event must land for the reasoning judge")
        (when event
          (validate-score-emitted! event)
          (is (seq (:dimensions event)) "dimensions must be non-empty")
          (let [dim (first (:dimensions event))]
            (is (= "Reasoning Quality" (:name dim)) "dimension is named in the reasoning judge's own vocabulary (the rubric's name)")
            (is (= (:score event) (:score dim)) "the dimension's score equals the event's score")
            (is (str/includes? (:feedback dim) "traces cause to effect without gaps")
                (str "dimension feedback must mention the cited reasoning strength. Got: " (pr-str dim)))))))))

;; =============================================================================
;; Cycle 3 — completeness
;; =============================================================================

(deftest rr30-completeness-judge-emits-completeness-dimension
  (testing "the completeness judge's emitted score carries a 'Completeness' dimension built from its own covered/missing aspects"
    (with-test-ctx [ctx]
      (let [event (run-default-judge!
                    ctx
                    {:judge-type :completeness
                     :judge-name "completeness"
                     :fake-llm-outputs {:level 4
                                        :reasoning "Adversarial coverage audit: nearly complete."
                                        :aspects-covered ["Every required section of the report is addressed"]
                                        :aspects-missing []
                                        :feedback "Comprehensive coverage overall."}
                     :host-writes {:answer "Report covers scope, budget, and timeline."}})]
        (is (some? event) "a :judge/score-emitted event must land for the completeness judge")
        (when event
          (validate-score-emitted! event)
          (is (seq (:dimensions event)) "dimensions must be non-empty")
          (let [dim (first (:dimensions event))]
            (is (= "Completeness" (:name dim)) "dimension is named in the completeness judge's own vocabulary")
            (is (= (:score event) (:score dim)) "the dimension's score equals the event's score")
            (is (str/includes? (:feedback dim) "Every required section of the report is addressed")
                (str "dimension feedback must mention the cited covered aspect. Got: " (pr-str dim)))))))))

;; =============================================================================
;; Cycle 4 — instruction-following
;; =============================================================================

(deftest rr30-instruction-following-judge-emits-instruction-dimension
  (testing "the instruction-following judge's emitted score carries an 'Instruction Following' dimension built from its own met/missed requirements"
    (with-test-ctx [ctx]
      (let [event (run-default-judge!
                    ctx
                    {:judge-type :instruction-following
                     :judge-name "instruction-following"
                     :fake-llm-outputs {:level 4
                                        :reasoning "Adversarial compliance audit: directives satisfied."
                                        :requirements-met ["The response follows the requested three-part structure"]
                                        :requirements-missed []
                                        :feedback "Compliant overall."}
                     :host-writes {:answer "Part 1... Part 2... Part 3..."}})]
        (is (some? event) "a :judge/score-emitted event must land for the instruction-following judge")
        (when event
          (validate-score-emitted! event)
          (is (seq (:dimensions event)) "dimensions must be non-empty")
          (let [dim (first (:dimensions event))]
            (is (= "Instruction Following" (:name dim)) "dimension is named in the instruction-following judge's own vocabulary (the rubric's name; the ontology classifier is case-sensitive)")
            (is (= (:score event) (:score dim)) "the dimension's score equals the event's score")
            (is (str/includes? (:feedback dim) "follows the requested three-part structure")
                (str "dimension feedback must mention the cited met requirement. Got: " (pr-str dim)))))))))

;; =============================================================================
;; Cycle 5 — guard: heuristic-structural byte-identical; custom judge untouched
;; =============================================================================
;;
;; RR-30 changes ONLY `invoke-llm-judge`'s dispatch of the 4 default LLM
;; judges. `invoke-heuristic-structural` and `invoke-custom-judge` are
;; explicitly "do NOT touch". These guard tests drive both through the
;; SAME live processor path and assert their dimensions are unaffected.

(deftest rr30-heuristic-structural-dimensions-unchanged-guard
  (testing "heuristic-structural's emitted dimensions are byte-identical to its own pure evaluate-tree-structure output — untouched by the RR-30 LLM-judge projection"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [tree [:sequence [:llm {}] [:final {}]]
            expected (heuristic-structural/evaluate-tree-structure tree)
            sheet-id (-> (cp/process-command
                           (assoc ctx :command
                                  {:command/name :sheet/create-sheet
                                   :command/id (random-uuid)
                                   :command/timestamp (time/now)
                                   :name (str "rr30-guard-heuristic-" (random-uuid))}))
                         :command-result/events first :sheet-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id sheet-id
                        :judge-name "structure"
                        :judge-config {:type :heuristic-structural}}))
            node-id (-> (cp/process-command
                          (assoc ctx :command
                                 {:command/name :sheet/create-node
                                  :command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :sheet-id sheet-id
                                  :type :leaf}))
                        :command-result/events first :node-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id sheet-id
                        :node-id node-id
                        :judges ["structure"]}))
            tick-id (random-uuid)]
        (Thread/sleep 150)
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-id
                  :node-id node-id
                  :node-type :leaf
                  :status :success
                  :writes {:generated-tree-raw tree}}))
        (let [event (wait-for-score-event ctx tick-id)]
          (is (some? event) "a :judge/score-emitted event must land for the heuristic-structural judge")
          (when event
            (validate-score-emitted! event)
            (is (= (:dimensions expected) (:dimensions event))
                (str "heuristic-structural's dimensions on the emitted event must be byte-identical to its own pure "
                     "evaluate-tree-structure output (RR-30 must not touch this path). Expected: "
                     (pr-str (:dimensions expected)) " Got: " (pr-str (:dimensions event))))))))))

;; Custom judge fixture: returns :dimensions where ONE entry omits :weight,
;; exercising invoke-custom-judge's pre-existing norm-dims default (weight
;; 1.0) — that normalization logic is untouched by this slice.
(defn custom-judge-with-mixed-dims
  [{:keys [inputs] :as _executor-ctx}]
  (let [host-outputs (:host-outputs inputs)]
    {:score 0.77
     :feedback "custom judge verdict"
     :dimensions [{:name "coverage" :weight 0.5 :score 0.8 :feedback "covers the main ask"}
                  {:name "tone" :score 0.6 :feedback (str "answer: " (:answer host-outputs))}]}))

(def ^:private rr30-custom-host-io-schema
  [:map [:answer {:optional true} :string]])

(def ^:private rr30-custom-host-trace-schema
  [:vector [:map [:node-id {:optional true} :uuid]]])

(def ^:private rr30-custom-dimension-schema
  [:map
   [:name :string]
   [:score :double]
   [:feedback :string]
   [:weight {:optional true} :double]])

(defn- build-rr30-custom-eval-workflow!
  [ctx workflow-name]
  (let [workflow (orc/workflow workflow-name
                   (orc/blackboard
                     {:host-inputs rr30-custom-host-io-schema
                      :host-outputs rr30-custom-host-io-schema
                      :host-instruction :string
                      :host-trace rr30-custom-host-trace-schema
                      :score :double
                      :feedback :string
                      :dimensions [:vector rr30-custom-dimension-schema]})
                   (orc/code "eval"
                     :fn "ai.obney.orc.evaluation.rr30-default-judge-dimensions-test/custom-judge-with-mixed-dims"
                     :reads [:host-outputs]
                     :writes [:score :feedback :dimensions]))]
    (orc/build-workflow! ctx workflow)))

(deftest rr30-custom-judge-dimensions-unchanged-guard
  (testing "a custom judge's :dimensions pass through unmodified (including the pre-existing default-weight normalization) — untouched by the RR-30 LLM-judge projection"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [eval-sheet-id (build-rr30-custom-eval-workflow!
                            ctx (str "rr30-guard-custom-" (random-uuid)))
            host-sheet-id (-> (cp/process-command
                                (assoc ctx :command
                                       {:command/name :sheet/create-sheet
                                        :command/id (random-uuid)
                                        :command/timestamp (time/now)
                                        :name (str "rr30-guard-custom-host-" (random-uuid))}))
                              :command-result/events first :sheet-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "mixed-dims"
                        :judge-config {:type :custom :sheet-id eval-sheet-id}}))
            host-node-id (-> (cp/process-command
                               (assoc ctx :command
                                      {:command/name :sheet/create-node
                                       :command/id (random-uuid)
                                       :command/timestamp (time/now)
                                       :sheet-id host-sheet-id
                                       :type :leaf}))
                             :command-result/events first :node-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :node-id host-node-id
                        :judges ["mixed-dims"]}))
            tick-id (random-uuid)]
        (Thread/sleep 200)
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id host-sheet-id
                  :tick-id tick-id
                  :node-id host-node-id
                  :node-type :leaf
                  :status :success
                  :writes {:answer "a mixed-dims host answer"}}))
        (let [event (wait-for-score-event ctx tick-id)]
          (is (some? event) "a :judge/score-emitted event must land for the custom judge")
          (when event
            (validate-score-emitted! event)
            (is (= 2 (count (:dimensions event))) "both custom dimensions flow through")
            (let [by-name (into {} (map (juxt :name identity)) (:dimensions event))]
              (is (= {:name "coverage" :weight 0.5 :score 0.8 :feedback "covers the main ask"}
                     (get by-name "coverage"))
                  "a dimension WITH an explicit weight passes through byte-identical")
              (is (= {:name "tone" :weight 1.0 :score 0.6 :feedback "answer: a mixed-dims host answer"}
                     (get by-name "tone"))
                  "a dimension WITHOUT a weight is defaulted to 1.0 by the pre-existing (untouched) norm-dims logic"))))))))

;; =============================================================================
;; Cross-component guard — every default judge's dimension name is one the
;; ontology classifier maps to a failure concept. Found live: the first cut
;; named the dimension "Instruction following", the classifier's dictionary is
;; case-sensitive, and `classify-evaluation` returned a failure with a nil URI.
;; =============================================================================

(deftest rr30-default-judge-dimension-names-are-known-to-the-ontology-classifier
  (testing "each default LLM judge's projected dimension classifies to a failure URI when it scores low"
    (let [project-dimensions @#'ai.obney.orc.evaluation.core.judge-runtime/project-dimensions
          inner {:grounded-claims [] :ungrounded-claims ["x"]
                 :reasoning-strengths [] :reasoning-weaknesses ["x"]
                 :aspects-covered [] :aspects-missing ["x"]
                 :requirements-met [] :requirements-missed ["x"]}]
      (doseq [judge-type [:grounding :reasoning :completeness :instruction-following]]
        (let [dims (project-dimensions judge-type inner 0.25)
              classification (ontology/classify-evaluation {:score 0.25 :dimensions dims})]
          (is (= 1 (count dims)) (str judge-type " projects exactly one dimension"))
          (is (pos? (count (:failures classification)))
              (str judge-type " at 0.25 must classify as a failure"))
          (doseq [failure (:failures classification)]
            (is (and (string? (:uri failure)) (str/starts-with? (:uri failure) "failure:"))
                (str judge-type " dimension " (pr-str (:name (first dims)))
                     " must map to a failure concept URI, got " (pr-str (:uri failure))))))))))
