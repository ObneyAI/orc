(ns ai.obney.orc.evaluation.rr33-judges-told-the-contract-test
  "RR-33 — judges are told the producer's contract.

   Two things the judge is told are misleading (grill D7):

     1. the response is described as free-form producer output when it is
        the node's typed write map — the field set is fixed by the
        workflow's typed blackboard, not chosen by the producer;
     2. a code node's task is an empty string (the DSL's `code` takes no
        instruction), so the judges' 'No instruction provided' fallback
        never fires and the judge is told nothing useful about the task.

   Cycle 1-2 assert the module + instruction-builder text describe
   `:response` as declared output fields, not free-form producer output.
   Cycles 3-6 drive the live processor path (RR-31's pattern) and assert
   the composed task in priority order: instruction, else criteria, else
   the declared write keys, else the sentinel — and that a node WITH an
   instruction still sends exactly that instruction (RR-31's guard)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Cycle 1 — RED: response framing, tier-1 module.
;; =============================================================================

(deftest rr33-tier1-module-response-description-names-declared-fields
  (testing "build-tier1-module's :response input describes the producer's declared output fields (one value per field, fixed by the workflow), not free-form producer output"
    (let [build-tier1-module @#'judges/build-tier1-module
          module (build-tier1-module "instr" [] false)
          response-input (first (filter #(= :response (:name %)) (:inputs module)))]
      (is (some? response-input) "the module must declare a :response input")
      (is (str/includes? (:description response-input)
                         "declared output fields, one value per field")
          (str "description must name the declared-output-fields framing. Got: "
               (pr-str (:description response-input))))
      (is (str/includes? (:description response-input)
                         "Judge the values, not the object shape")
          (str "description must tell the judge to grade values, not object shape. Got: "
               (pr-str (:description response-input)))))))

;; =============================================================================
;; Cycles 3-6 — task composition, live processor path (mirrors RR-31's
;; run-judge-capturing-instruction!, but captures `inputs` (the typed input
;; VALUES the judge is sent), not the instruction template.
;; =============================================================================

(defn- set-living-description-enabled! [ctx]
  (h/run-and-apply! ctx {:command/name :ontology/set-living-description-enabled
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :enabled? true})
  (Thread/sleep 100))

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

(defn- run-judge-capturing-inputs!
  "Drive one default LLM judge through the live processor path with the
   given `judge-config` (carrying :type and, optionally, :criteria) and an
   optional :node-instruction. Returns the `inputs` map (the typed VALUES)
   handed to the stubbed `llm/predict`, or nil if the judge never called it."
  [ctx {:keys [judge-config fake-llm-outputs host-writes node-instruction]}]
  (set-living-description-enabled! ctx)
  (let [judge-type (:type judge-config)
        sheet-id (-> (h/run-and-apply!
                       ctx {:command/name :sheet/create-sheet
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :name (str "rr33-task-" (name judge-type) "-" (random-uuid))})
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
        _ (when node-instruction
            (h/run-and-apply!
              ctx {:command/name :sheet/set-node-instruction
                   :command/id (random-uuid)
                   :command/timestamp (time/now)
                   :sheet-id sheet-id
                   :node-id node-id
                   :instruction node-instruction}))
        _ (h/run-and-apply!
            ctx {:command/name :sheet/set-node-judges
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :node-id node-id
                 :judges ["the-judge"]})
        tick-id (random-uuid)
        captured-inputs (atom nil)
        stub-predict (fn [_provider _module inputs _options]
                      (reset! captured-inputs inputs)
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
    @captured-inputs))

(def ^:private grounding-fake-outputs
  {:level 4
   :reasoning "Adversarial review: claims trace to the source."
   :grounded-claims ["cited"]
   :ungrounded-claims []
   :feedback "Well grounded."})

(def ^:private instruction-following-fake-outputs
  {:level 4
   :reasoning "Adversarial compliance audit: directives satisfied."
   :requirements-met ["followed the format"]
   :requirements-missed []
   :feedback "Compliant."})

;; -----------------------------------------------------------------------------
;; Cycles 3-4 — RED then GREEN: no instruction, declared :criteria present ->
;; the task the judge sees is the criteria.
;; -----------------------------------------------------------------------------

(deftest rr33-grounding-task-uses-criteria-when-no-instruction
  (testing "grounding judge, node has NO instruction, judge declares :criteria -> :producer_instruction equals the criteria verbatim"
    (h/with-async-test-context [ctx]
      (let [criteria "Every routing claim must cite the ticket"
            inputs (run-judge-capturing-inputs!
                     ctx {:judge-config {:type :grounding :criteria criteria}
                          :fake-llm-outputs grounding-fake-outputs
                          :host-writes {:answer "The ticket says billing."}})]
        (is (some? inputs) "the judge must call llm/predict")
        (is (= criteria (:producer_instruction inputs))
            (str "with no instruction and a declared :criteria, :producer_instruction must equal the criteria. Got: "
                 (pr-str (:producer_instruction inputs))))))))

(deftest rr33-tier1-task-uses-criteria-when-no-instruction
  (testing "instruction-following judge, node has NO instruction, judge declares :criteria -> :instruction equals the criteria verbatim"
    (h/with-async-test-context [ctx]
      (let [criteria "Must follow triage protocol accurately"
            inputs (run-judge-capturing-inputs!
                     ctx {:judge-config {:type :instruction-following :criteria criteria}
                          :fake-llm-outputs instruction-following-fake-outputs
                          :host-writes {:answer "Part 1... Part 2..."}})]
        (is (some? inputs) "the judge must call llm/predict")
        (is (= criteria (:instruction inputs))
            (str "with no instruction and a declared :criteria, :instruction must equal the criteria. Got: "
                 (pr-str (:instruction inputs))))))))

;; -----------------------------------------------------------------------------
;; Cycle 5 — RED then GREEN: no instruction, no criteria -> the task names
;; every declared write key and is never blank.
;; -----------------------------------------------------------------------------

(deftest rr33-grounding-task-names-declared-writes-when-no-instruction-no-criteria
  (testing "grounding judge, node has NO instruction and the judge declares NO criteria -> :producer_instruction names the declared write keys and is not blank"
    (h/with-async-test-context [ctx]
      (let [inputs (run-judge-capturing-inputs!
                     ctx {:judge-config {:type :grounding}
                          :fake-llm-outputs grounding-fake-outputs
                          :host-writes {:answer "The ticket says billing." :category "billing"}})
            task (:producer_instruction inputs)]
        (is (some? inputs) "the judge must call llm/predict")
        (is (not (str/blank? task)) "the task must never be blank")
        (is (str/includes? task "answer") (str "task must name write key :answer. Got: " (pr-str task)))
        (is (str/includes? task "category") (str "task must name write key :category. Got: " (pr-str task)))
        (is (not (str/includes? task "No instruction provided"))
            "the sentinel must not fire when write keys are known")))))

(deftest rr33-tier1-task-names-declared-writes-when-no-instruction-no-criteria
  (testing "instruction-following judge, node has NO instruction and the judge declares NO criteria -> :instruction names the declared write keys and is not blank"
    (h/with-async-test-context [ctx]
      (let [inputs (run-judge-capturing-inputs!
                     ctx {:judge-config {:type :instruction-following}
                          :fake-llm-outputs instruction-following-fake-outputs
                          :host-writes {:summary "done" :status "ok"}})
            task (:instruction inputs)]
        (is (some? inputs) "the judge must call llm/predict")
        (is (not (str/blank? task)) "the task must never be blank")
        (is (str/includes? task "summary") (str "task must name write key :summary. Got: " (pr-str task)))
        (is (str/includes? task "status") (str "task must name write key :status. Got: " (pr-str task)))
        (is (not (str/includes? task "No instruction provided"))
            "the sentinel must not fire when write keys are known")))))

;; -----------------------------------------------------------------------------
;; Cycle 6 — guard: a node WITH an instruction still sends exactly that
;; instruction as the task (RR-31's byte-identical guards cover the
;; instruction TEMPLATE; this guards the composed TASK field, including
;; when :criteria is ALSO declared — instruction wins).
;; -----------------------------------------------------------------------------

(deftest rr33-grounding-task-is-exact-instruction-when-present
  (testing "grounding judge, node HAS an instruction -> :producer_instruction equals that instruction exactly, even with :criteria also declared"
    (h/with-async-test-context [ctx]
      (let [instruction "Summarize the ticket in one sentence."
            inputs (run-judge-capturing-inputs!
                     ctx {:judge-config {:type :grounding :criteria "irrelevant criteria"}
                          :fake-llm-outputs grounding-fake-outputs
                          :host-writes {:answer "The ticket says billing."}
                          :node-instruction instruction})]
        (is (some? inputs) "the judge must call llm/predict")
        (is (= instruction (:producer_instruction inputs))
            (str "with an instruction present, :producer_instruction must be exactly that instruction. Got: "
                 (pr-str (:producer_instruction inputs))))))))

(deftest rr33-tier1-task-is-exact-instruction-when-present
  (testing "instruction-following judge, node HAS an instruction -> :instruction equals that instruction exactly, even with :criteria also declared"
    (h/with-async-test-context [ctx]
      (let [instruction "Summarize the ticket in one sentence."
            inputs (run-judge-capturing-inputs!
                     ctx {:judge-config {:type :instruction-following :criteria "irrelevant criteria"}
                          :fake-llm-outputs instruction-following-fake-outputs
                          :host-writes {:answer "Part 1... Part 2..."}
                          :node-instruction instruction})]
        (is (some? inputs) "the judge must call llm/predict")
        (is (= instruction (:instruction inputs))
            (str "with an instruction present, :instruction must be exactly that instruction. Got: "
                 (pr-str (:instruction inputs))))))))

;; =============================================================================
;; Cycle 2 — RED then GREEN: response framing, grounding module.
;; =============================================================================

(deftest rr33-grounding-module-response-description-names-declared-fields
  (testing "build-grounding-module's :response input describes the producer's declared output fields (one value per field, fixed by the workflow), not free-form producer output"
    (let [build-grounding-module @#'judges/build-grounding-module
          module (build-grounding-module "instr")
          response-input (first (filter #(= :response (:name %)) (:inputs module)))]
      (is (some? response-input) "the module must declare a :response input")
      (is (str/includes? (:description response-input)
                         "declared output fields, one value per field")
          (str "description must name the declared-output-fields framing. Got: "
               (pr-str (:description response-input))))
      (is (str/includes? (:description response-input)
                         "Judge the values, not the object shape")
          (str "description must tell the judge to grade values, not object shape. Got: "
               (pr-str (:description response-input)))))))
