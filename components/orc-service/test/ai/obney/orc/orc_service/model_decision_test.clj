(ns ai.obney.orc.orc-service.model-decision-test
  "Generated from contract ModelDecision (specs/orc-service.allium):
   DecisionKindFollowsDeclaredWrite, DecisionOffersEveryOptionWithItsMeaning,
   ValidAssessmentSucceeds, ConfidenceFloorIsExplicit,
   DecisionSharesLeafExecutionPolicy, DecisionRecordIsDurable.

   Public DSL → build → execute → events/trace detail, with only the provider
   seam (`llm/predict`) injected. The fake answers under whatever output name
   the decision offers (the first declared output of the module it receives),
   so these tests do not pin the module's internal naming. It honours the
   predictor's dual return shape (bare outputs unless :with-metadata?)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn do-lookup [_] {:did "lookup"})
(defn do-research [_] {:did "research"})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.model-decision-test/" function-name))

(def ^:private resolved-model "resolved/decision-model")

(def ^:private route-descriptions
  {"lookup" "Retrieve an existing catalog fact."
   "research" "Investigate a question that needs new evidence."
   "clarify" "The request is too ambiguous to act on; ask the user."})

(def ^:private route-schema
  (into [:enum {:descriptions route-descriptions}] (keys route-descriptions)))

(defn- fake-predict
  "Answers are consumed in order; the last repeats. ::missing omits the
   answer; a Throwable makes the provider attempt fail."
  [answers calls]
  (let [remaining (atom answers)]
    (fn [_provider module inputs options]
      (swap! calls conj {:module module :inputs inputs :options options})
      (let [answer (first @remaining)
            _ (swap! remaining #(if (next %) (next %) %))]
        (when (instance? Throwable answer) (throw answer))
        (let [output-name (-> module :outputs first :name)
              outputs (if (= ::missing answer) {} {output-name answer})]
          (if (:with-metadata? options)
            {:outputs outputs
             :usage {:prompt_tokens 5 :completion_tokens 1 :total_tokens 6}
             :model resolved-model
             :raw-response (pr-str outputs)}
            outputs))))))

(defn- run-workflow
  [ctx workflow inputs answers & execute-options]
  (let [calls (atom [])]
    (with-redefs [llm/predict (fake-predict answers calls)]
      (let [sheet-id (sheet/build-workflow! ctx workflow)
            result (apply sheet/execute
                          (assoc ctx :llm-provider :deterministic-provider)
                          sheet-id inputs execute-options)]
        {:result result :calls calls}))))

(defn- build-outcome
  "::built when the workflow builds, ::rejected when the build is refused
   (by exception or by a non-identifier result)."
  [ctx workflow]
  (try
    (let [r (sheet/build-workflow! ctx workflow)]
      (if (uuid? r) ::built ::rejected))
    (catch Exception _ ::rejected)))

(defn- routing-workflow
  "Decide a route, then dispatch on it with ordinary guards."
  [workflow-name & {:as decision-options}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:request :string :route route-schema :did :string})
    (sheet/sequence "main"
      (apply sheet/llm-decision "route"
             (mapcat identity
                     (merge {:instruction "Which operation addresses the request?"
                             :reads [:request]
                             :writes [:route]}
                            decision-options)))
      (sheet/fallback "dispatch"
        (sheet/sequence "lookup-route"
          (sheet/condition "is-lookup" :check {:key :route :op :equals :value "lookup"})
          (sheet/code "lookup" :fn (fq "do-lookup") :writes [:did]))
        (sheet/sequence "research-route"
          (sheet/condition "is-research" :check {:key :route :op :equals :value "research"})
          (sheet/code "research" :fn (fq "do-research") :writes [:did]))
        (sheet/condition "is-clarify" :check {:key :route :op :equals :value "clarify"})))))

(defn- decision-completion [ctx result]
  (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                       (some? (:decision %)))
                 (h/read-tick-events ctx (:trace-id result)))))

(defn- decision-detail [ctx result node-name]
  (let [trace-id (:trace-id result)
        _ (h/settle-until!
           #(some? (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                           [:query/result :trace])))
        trace (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                      [:query/result :trace])
        node-id (some #(when (= node-name (:name %)) (:id %))
                      (sheet/get-nodes-for-sheet ctx (:sheet-id trace)))
        node-trace (first (filter #(= node-id (:node-id %)) (:node-traces trace)))]
    (is (some? node-trace) "the decision appears in the execution trace")
    (:query/result
     (h/run-query ctx {:query/name :sheet/node-trace-detail
                       :trace-id trace-id
                       :trace-instance-id (:trace-instance-id node-trace)}))))

(def ^:private request {:request "What was our Q3 revenue?"})

;; ---------------------------------------------------------------------------
;; DecisionKindFollowsDeclaredWrite
;; ---------------------------------------------------------------------------

(deftest enum-decision-writes-its-answer-and-routes
  (testing "a valid enum answer is written to the declared key and guards route on it"
    (h/with-async-test-context [ctx]
      (let [{:keys [result calls]}
            (run-workflow ctx (routing-workflow "md-enum-route") request ["lookup"])]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= "lookup" (get-in result [:outputs :route])))
        (is (= "lookup" (get-in result [:outputs :did])))
        (is (= 1 (count @calls)))))))

(deftest boolean-decision-false-is-a-successful-assessment
  (testing "a boolean decision writes false and succeeds"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "md-boolean"
                       (sheet/blackboard {:request :string :answerable :boolean})
                       (sheet/llm-decision "answerable"
                         :instruction "Can the request be answered from the catalog?"
                         :reads [:request] :writes [:answerable]))
            {:keys [result]} (run-workflow ctx workflow request [false])]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (false? (get-in result [:outputs :answerable])))))))

(deftest non-finite-answer-key-is-rejected-at-build
  (testing "a decision whose answer key is neither boolean nor finite cannot be built"
    (h/with-async-test-context [ctx]
      (is (= ::rejected
             (build-outcome
              ctx
              (sheet/workflow "md-open-text"
                (sheet/blackboard {:request :string :route :string})
                (sheet/llm-decision "route"
                  :instruction "Which operation?" :reads [:request] :writes [:route]))))))))

;; ---------------------------------------------------------------------------
;; DecisionOffersEveryOptionWithItsMeaning
;; ---------------------------------------------------------------------------

(deftest model-is-offered-every-option-with-its-description
  (testing "the provider invocation carries every option id, its description and the instruction"
    (h/with-async-test-context [ctx]
      (let [{:keys [calls]}
            (run-workflow ctx (routing-workflow "md-offered") request ["lookup"])
            offered (pr-str (:module (first @calls)))]
        (doseq [[option-id description] route-descriptions]
          (is (str/includes? offered option-id) option-id)
          (is (str/includes? offered description) description))
        (is (str/includes? offered "Which operation addresses the request?"))))))

(deftest answer-outside-the-offered-set-is-never-written
  (testing "an unknown option fails the node with a structured failure kind and writes nothing"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]}
            (run-workflow ctx (routing-workflow "md-unknown") request ["delete-everything"])
            detail (decision-detail ctx result "route")]
        (is (= :failure (:status result)))
        (is (not (contains? (:outputs result) :route)))
        (is (nil? (get-in result [:outputs :did])) "no route ran")
        (is (keyword? (:failure-kind detail)))))))

(deftest runtime-options-come-from-a-declared-read-key
  (testing "options discovered at run time are offered and their identities kept verbatim"
    (h/with-async-test-context [ctx]
      (let [catalog [{:id "rpt/Q3-2026#rev" :description "Third-quarter revenue report"}
                     {:id "rpt/Q2-2026#rev" :description "Second-quarter revenue report"}]
            workflow (sheet/workflow "md-runtime-options"
                       (sheet/blackboard
                        {:request :string
                         :catalog [:vector [:map [:id :string] [:description :string]]]
                         :report :string})
                       (sheet/llm-decision "pick-report"
                         :instruction "Which listed report is requested?"
                         :reads [:request :catalog] :options-from :catalog
                         :writes [:report]))
            inputs (assoc request :catalog catalog)
            {:keys [result calls]} (run-workflow ctx workflow inputs ["rpt/Q3-2026#rev"])
            offered (pr-str (:module (first @calls)))]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= "rpt/Q3-2026#rev" (get-in result [:outputs :report])))
        (doseq [{:keys [id description]} catalog]
          (is (str/includes? offered id))
          (is (str/includes? offered description))))
      (testing "an id not in the run-time catalog is not written"
        (let [workflow (sheet/workflow "md-runtime-options-unknown"
                         (sheet/blackboard
                          {:request :string
                           :catalog [:vector [:map [:id :string] [:description :string]]]
                           :report :string})
                         (sheet/llm-decision "pick-report"
                           :instruction "Which listed report is requested?"
                           :reads [:request :catalog] :options-from :catalog
                           :writes [:report]))
              {:keys [result]} (run-workflow ctx workflow
                                             (assoc request :catalog
                                                    [{:id "a" :description "A"}])
                                             ["b"])]
          (is (= :failure (:status result)))
          (is (not (contains? (:outputs result) :report))))))))

;; ---------------------------------------------------------------------------
;; ValidAssessmentSucceeds
;; ---------------------------------------------------------------------------

(deftest declared-none-option-is-a-successful-assessment
  (testing "choosing the declared clarify option succeeds and writes it"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]}
            (run-workflow ctx (routing-workflow "md-clarify") request ["clarify"])]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= "clarify" (get-in result [:outputs :route])))
        (is (nil? (get-in result [:outputs :did])))))))

(deftest missing-answer-fails-and-writes-nothing
  (testing "a response without an answer fails with a structured kind; nothing is written"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]}
            (run-workflow ctx (routing-workflow "md-missing") request [::missing])
            detail (decision-detail ctx result "route")]
        (is (= :failure (:status result)))
        (is (not (contains? (:outputs result) :route)))
        (is (keyword? (:failure-kind detail)))))))

;; ---------------------------------------------------------------------------
;; ConfidenceFloorIsExplicit
;; ---------------------------------------------------------------------------

(deftest floor-with-no-reported-confidence-abstains
  (testing "with a floor configured, an answer reporting no confidence writes the abstention option"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]}
            (run-workflow ctx (routing-workflow "md-floor"
                                                :min-confidence 0.6
                                                :abstain "clarify")
                          request ["lookup"])
            record (:decision (decision-completion ctx result))]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= "clarify" (get-in result [:outputs :route])))
        (is (nil? (get-in result [:outputs :did])))
        (is (true? (:abstained? record)))
        (is (= "lookup" (:set-aside record)) "the set-aside answer is recorded")))))

(deftest no-floor-writes-the-answer-as-obtained
  (testing "with no floor, an answer with no reported confidence is written as obtained"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]}
            (run-workflow ctx (routing-workflow "md-no-floor") request ["research"])
            record (:decision (decision-completion ctx result))]
        (is (= "research" (get-in result [:outputs :route])))
        (is (false? (:abstained? record)))
        (is (not (contains? record :confidence))
            "no confidence is synthesised from a bare answer")))))

(deftest abstention-option-must-be-offered
  (testing "an abstention option outside the offered set is rejected at build"
    (h/with-async-test-context [ctx]
      (is (= ::rejected
             (build-outcome ctx (routing-workflow "md-bad-abstain"
                                                  :min-confidence 0.6
                                                  :abstain "give-up")))))))

;; ---------------------------------------------------------------------------
;; DecisionSharesLeafExecutionPolicy
;; ---------------------------------------------------------------------------

(deftest decision-node-model-reaches-provider
  (h/with-async-test-context [ctx]
    (let [{:keys [result calls]}
          (run-workflow ctx (routing-workflow "md-model" :model "vendor/router")
                        request ["lookup"])]
      (is (= :success (:status result)))
      (is (= "vendor/router" (get-in (first @calls) [:options :model]))))))

(deftest decision-consumes-shared-call-budget
  (testing "a second decision cannot invoke the provider once the budget is spent"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "md-budget"
                       (sheet/blackboard {:request :string :a :boolean :b :boolean})
                       (sheet/sequence "both"
                         (sheet/llm-decision "first" :instruction "First?"
                           :reads [:request] :writes [:a])
                         (sheet/llm-decision "second" :instruction "Second?"
                           :reads [:request] :writes [:b])))
            {:keys [result calls]} (run-workflow ctx workflow request [true]
                                                 :llm-call-budget 1)]
        (is (= 1 (count @calls)))
        (is (not= :success (:status result)))
        (is (not (contains? (:outputs result) :b)))))))

;; ---------------------------------------------------------------------------
;; DecisionRecordIsDurable
;; ---------------------------------------------------------------------------

(deftest decision-record-is-durable-and-retrievable
  (testing "the completion records offered options, answer, model and usage; detail exposes it"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]}
            (run-workflow ctx (routing-workflow "md-record") request ["lookup"])
            completion (decision-completion ctx result)
            record (:decision completion)
            detail (decision-detail ctx result "route")]
        (is (some? completion))
        (is (= (set (keys route-descriptions)) (set (:offered record))))
        (is (= "lookup" (:answer record)))
        (is (false? (:abstained? record)))
        (is (= resolved-model (:model completion)))
        (is (= 6 (get-in completion [:usage :total-tokens])))
        (is (= request (:inputs detail)))
        (is (= {:route "lookup"} (:outputs detail)))
        (is (= record (:decision detail)))))))

;; ---------------------------------------------------------------------------
;; A finite answer on a conversational model is requested as a structured
;; (function-calling) response by default. Found live: in marker mode a model
;; that answers with the bare option id (no field marker) is unparseable,
;; because the marker parser's whole-text fallback covers only string outputs.
;; An explicit node option still wins.
;; ---------------------------------------------------------------------------

(deftest decision-requests-structured-output-by-default
  (h/with-async-test-context [ctx]
    (let [{:keys [calls]} (run-workflow ctx (routing-workflow "md-structured") request ["lookup"])]
      (is (true? (get-in (first @calls) [:options :use-function-calling?]))))
    (let [{:keys [calls]} (run-workflow ctx (routing-workflow "md-structured-override"
                                                              :options {:use-function-calling? false})
                                        request ["lookup"])]
      (is (false? (get-in (first @calls) [:options :use-function-calling?]))
          "an explicit node option wins"))))

;; ---------------------------------------------------------------------------
;; Boolean decisions may abstain to false (ModelDecision: "false is an offered
;; option of a yes/no decision")
;; ---------------------------------------------------------------------------

(defn- holds-workflow [workflow-name & {:as decision-options}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:claim :string :holds :boolean})
    (apply sheet/llm-decision "holds"
           (mapcat identity (merge {:instruction "Does the supplied claim hold?"
                                    :reads [:claim] :writes [:holds]}
                                   decision-options)))))

(defn- run-with-confidence
  "Like `run-workflow`, but the provider reports `confidence` beside the
   answer (the native-decision evidence shape), when non-nil."
  [ctx workflow inputs answer confidence]
  (with-redefs [llm/predict
                (fn [_provider module _inputs options]
                  (let [output-name (-> module :outputs first :name)]
                    (if (:with-metadata? options)
                      (cond-> {:outputs {output-name answer}
                               :usage {:prompt_tokens 5 :completion_tokens 1 :total_tokens 6}
                               :model resolved-model
                               :raw-response (pr-str {output-name answer})}
                        confidence (assoc :decisions {(name output-name) {:confidence confidence}}))
                      {output-name answer})))]
    (let [sheet-id (sheet/build-workflow! ctx workflow)]
      (sheet/execute (assoc ctx :llm-provider :deterministic-provider) sheet-id inputs))))

(def ^:private claim {:claim "Water boils at 100C at sea level"})

(deftest boolean-decision-can-abstain-to-false
  (testing "`:abstain false` is an offered option and the workflow builds"
    (h/with-async-test-context [ctx]
      (is (= ::built
             (build-outcome ctx (holds-workflow "md-bool-abstain-false"
                                                :min-confidence 0.7 :abstain false))))))
  (testing "an abstention value outside the offered options is still rejected"
    (h/with-async-test-context [ctx]
      (is (= ::rejected
             (build-outcome ctx (holds-workflow "md-bool-abstain-bogus"
                                                :min-confidence 0.7 :abstain "maybe"))))))
  (testing "below the floor, true is set aside and false is written"
    (h/with-async-test-context [ctx]
      (let [result (run-with-confidence ctx (holds-workflow "md-bool-below-floor"
                                                            :min-confidence 0.7 :abstain false)
                                        claim true 0.2)
            record (:decision (decision-completion ctx result))]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (false? (get-in result [:outputs :holds])))
        (is (true? (:abstained? record)))
        (is (true? (:set-aside record)) "the set-aside answer is recorded"))))
  (testing "no reported confidence under a floor also abstains to false"
    (h/with-async-test-context [ctx]
      (let [result (run-with-confidence ctx (holds-workflow "md-bool-no-conf"
                                                            :min-confidence 0.7 :abstain false)
                                        claim true nil)
            record (:decision (decision-completion ctx result))]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (false? (get-in result [:outputs :holds])))
        (is (true? (:abstained? record))))))
  (testing "a semantic false above the floor is a successful decision, not an abstention"
    (h/with-async-test-context [ctx]
      (let [result (run-with-confidence ctx (holds-workflow "md-bool-semantic-false"
                                                            :min-confidence 0.7 :abstain false)
                                        claim false 0.95)
            record (:decision (decision-completion ctx result))]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (false? (get-in result [:outputs :holds])))
        (is (false? (:abstained? record)))))))
