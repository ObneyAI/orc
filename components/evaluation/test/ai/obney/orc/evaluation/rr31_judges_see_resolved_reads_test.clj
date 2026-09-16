(ns ai.obney.orc.evaluation.rr31-judges-see-resolved-reads-test
  "RR-31 — judges see the node's resolved reads and its declared criteria.

   Two things a judge is shown are wrong on the live processor path
   (`.rr-durable-notes/RR29-live-run-judge-input-findings.md`, findings 1
   and 2):

     1. every judge attached to an ordinary workflow node receives
        `:inputs {}`, although the node's completion records :read-keys /
        :read-sources and the value log resolves them to the real values;
     2. the :criteria a workflow declares on a built-in judge never reaches
        the judge — the four LLM judges always run on their rubric's
        built-in criteria.

   This suite drives the LIVE processor path (sheet/build-workflow! ->
   sheet/execute) with a stubbed `llm/predict` and a captured
   `invoke-judge`, per the handoff's TDD cycle list."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.core.judge-runtime :as jr]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Fixture workflow — one sequence, one sheet/llm node that reads
;; :ticket-message and writes :category, a grounding judge attached.
;; =============================================================================

(def rr31-workflow
  (sheet/workflow "rr31-resolved-reads"
    (sheet/blackboard
      {:ticket-message :string
       :category :string})
    (sheet/judges
      {:grounding-judge {:type :grounding}})
    (sheet/sequence "rr31-pipeline"
      (sheet/llm "classify"
        :model "google/gemini-2.5-flash"
        :instruction "Classify the ticket into one category."
        :reads [:ticket-message]
        :writes [:category]
        :judges ["grounding-judge"]))))

(defn- set-living-description-enabled! [ctx]
  (h/run-and-apply! ctx {:command/name :ontology/set-living-description-enabled
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :enabled? true})
  (Thread/sleep 100))

;; =============================================================================
;; Cycle 1 — RED: the judge must see the node's resolved read as :inputs,
;; not {}.
;; =============================================================================

(deftest rr31-judge-sees-resolved-reads-through-live-workflow
  (testing "a judge attached to a workflow node that declares :reads receives those reads' resolved values as its trace-data :inputs, on the real live processor path"
    (h/with-async-test-context [ctx]
      (set-living-description-enabled! ctx)
      (let [sheet-id (sheet/build-workflow! ctx rr31-workflow)
            captured (atom [])
            stub-predict (fn [_provider _module _inputs _options]
                           {:outputs {:category "billing"}
                            :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})
            capture (fn [_ctx _judge-config trace-data]
                      (swap! captured conj trace-data)
                      {:score 0.5 :feedback "probe" :dimensions []})]
        (with-redefs-fn {#'llm/predict stub-predict
                         #'jr/invoke-judge capture}
          (fn []
            (let [result (sheet/execute ctx sheet-id
                                        {:ticket-message "URGENT: billing error on my account."}
                                        :timeout-ms 60000)]
              (is (= :success (:status result))
                  (str "execution must succeed. Got: " (pr-str result)))
              (is (h/settle-until! (fn [] (seq @captured)) :timeout-ms 20000)
                  "the judge must be invoked")
              (is (= {:ticket-message "URGENT: billing error on my account."}
                     (:inputs (first @captured)))
                  (str "the judge must see the node's resolved :ticket-message read, not {}. Got: "
                       (pr-str (:inputs (first @captured))))))))))))

;; =============================================================================
;; Cycles 4-6 — the judge configuration's declared :criteria reaches the LLM
;; judge's composed instruction. Live processor path (mirrors RR-30's
;; driver): declare-judge -> create-node -> set-node-judges ->
;; complete-node-execution, with `llm/predict` stubbed to capture the
;; module's :instructions string.
;; =============================================================================

(defn- wait-for-score-event [ctx tick-id]
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

(defn- run-judge-capturing-instruction!
  "Drive one default LLM judge through the live processor path with the
   given `judge-config` (carrying :type and, optionally, :criteria).
   Returns the :instructions string of the ORC LLM module handed to the
   stubbed `llm/predict`, or nil if the judge never called it."
  [ctx {:keys [judge-config fake-llm-outputs host-writes]}]
  (set-living-description-enabled! ctx)
  (let [judge-type (:type judge-config)
        sheet-id (-> (h/run-and-apply!
                       ctx {:command/name :sheet/create-sheet
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :name (str "rr31-criteria-" (name judge-type) "-" (random-uuid))})
                     :command-result/events first :sheet-id)
        _ (h/run-and-apply!
            ctx {:command/name :sheet/declare-judge
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :judge-name "the-judge"
                 :judge-config judge-config})
        node-id (-> (h/run-and-apply!
                      ctx {:command/name :sheet/create-node
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :sheet-id sheet-id
                           :type :leaf})
                    :command-result/events first :node-id)
        _ (h/run-and-apply!
            ctx {:command/name :sheet/set-node-judges
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :node-id node-id
                 :judges ["the-judge"]})
        tick-id (random-uuid)
        captured-instruction (atom nil)
        stub-predict (fn [_provider module _inputs _options]
                      (reset! captured-instruction (:instructions module))
                      {:outputs fake-llm-outputs :usage {:total-tokens 1}})]
    (Thread/sleep 150)
    (with-redefs [llm/predict stub-predict]
      (h/run-and-apply!
        ctx {:command/name :sheet/complete-node-execution
             :command/id (random-uuid)
             :command/timestamp (time/now)
             :sheet-id sheet-id
             :tick-id tick-id
             :node-id node-id
             :node-type :leaf
             :status :success
             :writes host-writes})
      (wait-for-score-event ctx tick-id))
    @captured-instruction))

(def ^:private grounding-fake-outputs
  {:level 4
   :reasoning "Adversarial review: claims trace to the source."
   :grounded-claims ["cited"]
   :ungrounded-claims []
   :feedback "Well grounded."})

;; -----------------------------------------------------------------------------
;; Cycle 4 — RED: grounding
;; -----------------------------------------------------------------------------

(deftest rr31-grounding-judge-instruction-carries-declared-criteria
  (testing "a grounding judge declared with :criteria is invoked with an instruction containing that criteria verbatim after WHAT TO EVALUATE:"
    (h/with-async-test-context [ctx]
      (let [criteria "Every routing claim must cite the ticket"
            instruction (run-judge-capturing-instruction!
                          ctx {:judge-config {:type :grounding :criteria criteria}
                               :fake-llm-outputs grounding-fake-outputs
                               :host-writes {:answer "The ticket says billing."}})]
        (is (some? instruction) "the judge must call llm/predict")
        (is (str/includes? instruction (str "WHAT TO EVALUATE:\n" criteria))
            (str "instruction must contain the declared criteria verbatim after WHAT TO EVALUATE:. Got: "
                 (pr-str instruction)))))))

;; -----------------------------------------------------------------------------
;; Cycle 5 — instruction-following
;; -----------------------------------------------------------------------------

(def ^:private instruction-following-fake-outputs
  {:level 4
   :reasoning "Adversarial compliance audit: directives satisfied."
   :requirements-met ["followed the format"]
   :requirements-missed []
   :feedback "Compliant."})

(deftest rr31-instruction-following-judge-instruction-carries-declared-criteria
  (testing "an instruction-following judge declared with :criteria is invoked with an instruction containing that criteria verbatim after WHAT TO EVALUATE:"
    (h/with-async-test-context [ctx]
      (let [criteria "Must follow triage protocol accurately"
            instruction (run-judge-capturing-instruction!
                          ctx {:judge-config {:type :instruction-following :criteria criteria}
                               :fake-llm-outputs instruction-following-fake-outputs
                               :host-writes {:answer "Part 1... Part 2..."}})]
        (is (some? instruction) "the judge must call llm/predict")
        (is (str/includes? instruction (str "WHAT TO EVALUATE:\n" criteria))
            (str "instruction must contain the declared criteria verbatim after WHAT TO EVALUATE:. Got: "
                 (pr-str instruction)))))))

;; -----------------------------------------------------------------------------
;; Cycle 5 — reasoning
;; -----------------------------------------------------------------------------

(def ^:private reasoning-fake-outputs
  {:level 4
   :reasoning "Adversarial logic review: chain holds."
   :reasoning-strengths ["sound chain"]
   :reasoning-weaknesses []
   :feedback "Sound reasoning."})

(deftest rr31-reasoning-judge-instruction-carries-declared-criteria
  (testing "a reasoning judge declared with :criteria is invoked with an instruction containing that criteria verbatim after WHAT TO EVALUATE:"
    (h/with-async-test-context [ctx]
      (let [criteria "Every inference must trace to a stated premise"
            instruction (run-judge-capturing-instruction!
                          ctx {:judge-config {:type :reasoning :criteria criteria}
                               :fake-llm-outputs reasoning-fake-outputs
                               :host-writes {:answer "Because X, therefore Y."}})]
        (is (some? instruction) "the judge must call llm/predict")
        (is (str/includes? instruction (str "WHAT TO EVALUATE:\n" criteria))
            (str "instruction must contain the declared criteria verbatim after WHAT TO EVALUATE:. Got: "
                 (pr-str instruction)))))))

;; -----------------------------------------------------------------------------
;; Cycle 6 — guard: a judge declared WITHOUT :criteria composes an
;; instruction byte-identical to the unmodified rubric's — the pre-RR-31
;; instruction, unchanged.
;; -----------------------------------------------------------------------------

(deftest rr31-grounding-judge-no-criteria-instruction-byte-identical
  (testing "call-grounding-judge-llm with NO :criteria composes an instruction byte-identical to build-grounding-instruction of the unmodified rubric"
    (let [expected (judges/build-grounding-instruction (rubrics/get-tier1-rubric :grounding))
          captured (atom nil)
          stub-predict (fn [_provider module _inputs _options]
                        (reset! captured (:instructions module))
                        {:outputs grounding-fake-outputs})]
      (with-redefs [llm/predict stub-predict]
        (judges/call-grounding-judge-llm {:inputs "src" :outputs "resp" :instruction "task"}))
      (is (= expected @captured)
          (str "no-criteria instruction must be byte-identical to today's. Got: " (pr-str @captured))))))

(deftest rr31-tier1-judge-no-criteria-instruction-byte-identical
  (testing "call-tier1-judge-llm with NO :criteria composes an instruction byte-identical to build-tier1-instruction of the unmodified rubric, for every tier-1 judge type"
    (doseq [rubric-key [:instruction-following :reasoning :completeness]]
      (let [expected (judges/build-tier1-instruction (rubrics/get-tier1-rubric rubric-key))
            captured (atom nil)
            stub-predict (fn [_provider module _inputs _options]
                          (reset! captured (:instructions module))
                          {:outputs {}})]
        (with-redefs [llm/predict stub-predict]
          (judges/call-tier1-judge-llm rubric-key [] {:inputs "src" :outputs "resp" :instruction "task"}))
        (is (= expected @captured)
            (str rubric-key " no-criteria instruction must be byte-identical to today's. Got: "
                 (pr-str @captured)))))))

;; -----------------------------------------------------------------------------
;; Cycle 5 — completeness
;; -----------------------------------------------------------------------------

(def ^:private completeness-fake-outputs
  {:level 4
   :reasoning "Adversarial coverage audit: nearly complete."
   :aspects-covered ["scope covered"]
   :aspects-missing []
   :feedback "Comprehensive."})

(deftest rr31-completeness-judge-instruction-carries-declared-criteria
  (testing "a completeness judge declared with :criteria is invoked with an instruction containing that criteria verbatim after WHAT TO EVALUATE:"
    (h/with-async-test-context [ctx]
      (let [criteria "Must produce urgency, sentiment, category, and routing decision"
            instruction (run-judge-capturing-instruction!
                          ctx {:judge-config {:type :completeness :criteria criteria}
                               :fake-llm-outputs completeness-fake-outputs
                               :host-writes {:answer "Report covers scope, budget, timeline."}})]
        (is (some? instruction) "the judge must call llm/predict")
        (is (str/includes? instruction (str "WHAT TO EVALUATE:\n" criteria))
            (str "instruction must contain the declared criteria verbatim after WHAT TO EVALUATE:. Got: "
                 (pr-str instruction)))))))
