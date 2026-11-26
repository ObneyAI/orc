(ns ai.obney.orc.evaluation.docs-examples-test
  "Executes every code example in the judge documentation
   (.claude/skills/orc-evaluate/SKILL.md, docs/EVALUATION-COMPONENT.md,
   docs/JUDGE-ARCHITECTURE.md, docs/DSL-REFERENCE.md, docs/ORC-SERVICE-GUIDE.md, docs/GEPA-GUIDE.md).

   Each example sits between `docs-example-begin` / `docs-example-end` marker
   comments. The `docs-match-this-file` test requires that every marked region
   appears verbatim (comments and blank lines aside) in the documentation, and
   that every judge-example block in the documentation is such a region, so a
   doc example cannot drift from the code that runs here.

   Only the provider seam is faked: `llm/predict` for a conversational model.
   Everything else is the real flow: build -> execute -> completion -> request
   -> judging -> assessment."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

;; ---------------------------------------------------------------------------
;; Harness (not part of any documented example)
;; ---------------------------------------------------------------------------

(defn- grading-call? [module]
  (boolean (some #(= :band (:name %)) (:outputs module))))

(defn- stub-provider
  "A conversational provider. The grading call is recognised by the `band`
   field it must write; any other call is the host node's own."
  [calls {:keys [feedback-outputs score-only-band]}]
  (fn [_provider module inputs options]
    (swap! calls conj {:module module :inputs inputs :options options})
    (let [usage {:prompt_tokens 5 :completion_tokens 5 :total_tokens 10}]
      (cond
        (and (grading-call? module) (= :band (-> module :outputs first :name)))
        (let [outputs {:band (str score-only-band)}]
          (if (:with-metadata? options)
            {:outputs outputs :usage usage :model "stub/judge-model" :raw-response (pr-str outputs)}
            outputs))

        (grading-call? module)
        {:outputs feedback-outputs :usage usage :model "stub/judge-model"}

        :else
        {:outputs {:category "billing"} :usage usage :model "stub/host-model"}))))

(def ^:private grounded-answer
  {:reasoning "The category follows from the ticket text."
   :grounded-claims ["billing error"]
   :ungrounded-claims []
   :band 4
   :feedback "Well grounded in the ticket."})

(defn- settled
  "The assessments matching `filters` once `n` of them are no longer pending."
  [ctx filters n]
  (let [ours #(filterv (fn [a] (not= :pending (:status a)))
                       (evaluation/get-assessments ctx filters))]
    (is (h/settle-until! #(>= (count (ours)) n) :timeout-ms 60000)
        (str "expected " n " settled assessments for " (pr-str filters)))
    (ours)))

(def ^:private bb {:request :string :mid :string :answer :string})

(defn- events-of [ctx types]
  (into [] (es/read (:event-store ctx) {:types types :tenant-id (:tenant-id ctx)})))

(defn- node-id [ctx sheet-id n]
  (:id (some #(when (= n (:name %)) %) (sheet/get-nodes-for-sheet ctx sheet-id))))

;; ---------------------------------------------------------------------------
;; Example: a built-in judge on an llm node (monitoring + learning)
;; ---------------------------------------------------------------------------

;; docs-example-begin: built-in-judge
(def triage
  (sheet/workflow "docs-ticket-triage"
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:grounded {:type :grounding
                              :purposes #{:monitoring :learning}}})
    (sheet/llm "classify"
      :instruction "Classify the ticket into one category."
      :reads [:ticket-message]
      :writes [:category]
      :judges ["grounded"])))

(defn run-triage [ctx]
  (let [sheet-id (sheet/build-workflow! ctx triage)]
    (sheet/execute ctx sheet-id {:ticket-message "URGENT: billing error on my account."})
    sheet-id))

(defn scored-assessments [ctx sheet-id]
  (evaluation/get-assessments ctx {:sheet-id sheet-id :status :scored}))
;; docs-example-end

(deftest a-built-in-judge-scores-an-attached-node
  (h/with-async-test-context [ctx]
    (let [calls (atom [])]
      (with-redefs [llm/predict (stub-provider calls {:feedback-outputs grounded-answer})]
        (let [sheet-id (run-triage ctx)
              [a] (settled ctx {:sheet-id sheet-id} 1)]
          (is (= [a] (scored-assessments ctx sheet-id)))
          (is (= :scored (:status a)) (pr-str a))
          (is (= "grounded" (:judge-name a)))
          (is (= 1 (:judge-revision-number a)))
          (is (= 4 (:band a)))
          (is (= 0.75 (:score a)) "band 4 of 5: (4 - 1) / (5 - 1)")
          (is (= "Well grounded in the ticket." (:feedback a)))
          (is (= #{:monitoring :learning} (:purposes a)))
          (is (= "stub/judge-model" (:resolved-model (first (:model-provenance a)))))
          (testing "a learning judge's scored outcome also reaches the learning loops"
            (is (h/settle-until! #(= 1 (count (events-of ctx #{:judge/score-emitted}))))
                "legacy score record emitted")
            (is (= "Well grounded in the ticket."
                   (:feedback (first (events-of ctx #{:judge/score-emitted})))))))))))

;; ---------------------------------------------------------------------------
;; Example: a score-only monitoring judge with its own rubric
;; ---------------------------------------------------------------------------

;; docs-example-begin: monitoring-judge
(def category-rubric
  {:criterion "Is the category the one a support agent would choose?"
   :stance "Be strict: a plausible but wrong category is a failure."
   :bands {1 "Wrong category."
           2 "Defensible, but not the best category."
           3 "The category a support agent would choose."}
   :feedback :none})

(def monitored-triage
  (sheet/workflow "docs-monitored-triage"
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:category-check {:type :instruction-following
                                    :rubric category-rubric
                                    :purposes #{:monitoring}}})
    (sheet/llm "classify"
      :instruction "Classify the ticket into one category."
      :reads [:ticket-message]
      :writes [:category]
      :judges ["category-check"])))
;; docs-example-end

(deftest a-score-only-judge-monitors-without-feedback
  (h/with-async-test-context [ctx]
    (let [calls (atom [])]
      (with-redefs [llm/predict (stub-provider calls {:score-only-band 2})]
        (let [sheet-id (sheet/build-workflow! ctx monitored-triage)
              _ (sheet/execute ctx sheet-id {:ticket-message "URGENT: billing error."})
              [a] (settled ctx {:sheet-id sheet-id} 1)]
          (is (= :scored (:status a)) (pr-str a))
          (is (= 2 (:band a)))
          (is (= 0.5 (:score a)) "band 2 of 3: (2 - 1) / (3 - 1)")
          (is (not (contains? a :feedback)) "no feedback is invented")
          (is (= #{:monitoring} (:purposes a)))
          (Thread/sleep 300)
          (is (empty? (events-of ctx #{:judge/score-emitted}))
              "a monitoring judge never feeds the learning loops")
          (is (= 1 (count (filter #(= "category-check" (:judge-name %))
                                  (evaluation/get-assessments ctx {})))))
          (testing "the rubric the judge reads is the one declared"
            (let [grading (first (filter #(grading-call? (:module %)) @calls))]
              (is (str/includes? (pr-str grading) "The category a support agent would choose.")))))))))

;; ---------------------------------------------------------------------------
;; Example: a custom judge workflow (deterministic, no model)
;; ---------------------------------------------------------------------------

;; docs-example-begin: custom-judge
(defn label-judge
  "Grades the assessed node's `category` output by its length."
  [{:keys [inputs]}]
  (let [category (str (get-in inputs [:host-outputs :category]))]
    (if (<= (count category) 12)
      {:band 3 :feedback "A short label, as asked."}
      {:band 1 :feedback "The category is a sentence, not a label."})))

(def label-judge-workflow
  (sheet/workflow "docs-label-judge"
    (sheet/blackboard
     {:host-inputs [:map-of :keyword [:any {:description "Values the assessed node read"}]]
      :host-outputs [:map-of :keyword [:any {:description "Values the assessed node wrote"}]]
      :host-instruction [:string {:description "The assessed node's instruction"}]
      :rubric [:map [:bands [:map-of :int :string]]
               [:criterion {:optional true} :string]
               [:stance {:optional true} :string]]
      :band [:int {:description "The band chosen from the rubric"}]
      :feedback [:string {:description "Why this band"}]})
    (sheet/code "check"
      :fn "ai.obney.orc.evaluation.docs-examples-test/label-judge"
      :reads [:host-inputs :host-outputs :host-instruction :rubric]
      :writes [:band :feedback])))

(def label-rubric
  {:criterion "The category is a short label."
   :stance "Be strict."
   :bands {1 "Not a label." 2 "A long label." 3 "A short label."}
   :feedback :required})

(defn label-judged-workflow [judge-sheet-id]
  (sheet/workflow "docs-label-judged"
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:label {:type :custom
                           :sheet-id judge-sheet-id
                           :rubric label-rubric}})
    (sheet/code "classify"
      :fn "ai.obney.orc.evaluation.docs-examples-test/classify"
      :reads [:ticket-message]
      :writes [:category]
      :judges ["label"])))
;; docs-example-end

(defn classify [_] {:category "billing"})

(deftest a-custom-judge-workflow-scores-by-the-band-it-writes
  (h/with-async-test-context [ctx]
    (let [judge-sheet-id (sheet/build-workflow! ctx label-judge-workflow)
          sheet-id (sheet/build-workflow! ctx (label-judged-workflow judge-sheet-id))
          result (sheet/execute ctx sheet-id {:ticket-message "URGENT: billing error."})
          [a] (settled ctx {:sheet-id sheet-id} 1)]
      (is (= :success (:status result)) (pr-str result))
      (is (= :scored (:status a)) (pr-str a))
      (is (= 3 (:band a)))
      (is (= 1.0 (:score a)))
      (is (= "A short label, as asked." (:feedback a)))
      (is (= #{:monitoring :learning} (:purposes a))
          "a rubric that requires feedback defaults to both purposes"))))

;; ---------------------------------------------------------------------------
;; Example: revising a judge
;; ---------------------------------------------------------------------------

;; docs-example-begin: revise-judge
(def stricter-triage
  (sheet/workflow "docs-ticket-triage"
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:grounded {:type :grounding
                              :purposes #{:monitoring :learning}
                              :rubric {:criterion "Every claim traces to the ticket text."
                                       :stance "Be strict."
                                       :bands {1 "Fabricated." 2 "Partly supported."
                                               3 "Fully supported."}
                                       :feedback :required}}})
    (sheet/llm "classify"
      :instruction "Classify the ticket into one category."
      :reads [:ticket-message]
      :writes [:category]
      :judges ["grounded"])))
;; docs-example-end

(deftest rebuilding-with-a-changed-judge-revises-it
  (h/with-async-test-context [ctx]
    (let [calls (atom [])]
      (with-redefs [llm/predict (stub-provider calls {:feedback-outputs grounded-answer})]
        (let [sheet-id (run-triage ctx)
              [first-a] (settled ctx {:sheet-id sheet-id} 1)
              _ (is (= 1 (:judge-revision-number first-a)))
              revised-id (sheet/build-workflow! ctx stricter-triage)
              _ (is (= sheet-id revised-id) "the workflow name is the identity")
              judge (sheet/get-judge ctx sheet-id "grounded")
              _ (sheet/execute ctx sheet-id {:ticket-message "Another billing error."})
              all (settled ctx {:sheet-id sheet-id} 2)]
          (is (= 2 (count (:revisions judge))) "a revision was recorded, not a second judge")
          (is (= [1 2] (sort (map :judge-revision-number all))))
          (is (= 2 (count (distinct (map :assessment-id all))))
              "the two revisions are separate assessments")
          (testing "the next grading request carries the revised rubric"
            (let [grading (filter #(grading-call? (:module %)) @calls)
                  rubric-text (str (get-in (last grading) [:inputs :rubric]))]
              (is (str/includes? rubric-text "Every claim traces to the ticket text."))
              (is (not (str/includes? (str (get-in (first grading) [:inputs :rubric]))
                                      "Every claim traces to the ticket text.")))))
          (testing "a rebuild that changes nothing about the judge adds no revision"
            (sheet/build-workflow! ctx stricter-triage)
            (is (= 2 (count (:revisions (sheet/get-judge ctx sheet-id "grounded")))))))))))

;; ---------------------------------------------------------------------------
;; Example: judging a composite, with opt-in family and child-assessment evidence
;; ---------------------------------------------------------------------------

;; docs-example-begin: composite-judge
(defn step [{:keys [inputs]}] {:mid (str "mid:" (:request inputs))})
(defn finish [{:keys [inputs]}] {:answer (str "answer:" (:mid inputs))})

(defn step-judge [_] {:score 0.5 :feedback "The step ran."})

(defn pipeline-judge
  "Reads the whole execution family and the child assessments."
  [{:keys [inputs]}]
  (let [executed (mapv :node-name (:host-family inputs))
        children (mapv :status (:child-assessments inputs))]
    {:score (if (and (= #{"step" "finish"} (set executed)) (= [:scored] children)) 1.0 0.0)
     :feedback (str "Executed " (count executed) " nodes; child assessments: " children)}))

(def any-map [:map-of :keyword [:any {:description "Any value"}]])

(defn judge-workflow-with [workflow-name fn-name extra-keys]
  (sheet/workflow workflow-name
    (sheet/blackboard
     (merge {:host-inputs any-map
             :host-outputs any-map
             :host-instruction [:string {:description "The assessed node's instruction"}]
             :score :double
             :feedback [:string {:description "Why this score"}]}
            extra-keys))
    (sheet/code "judge" :fn fn-name
      :reads (into [:host-inputs :host-outputs :host-instruction] (keys extra-keys))
      :writes [:score :feedback])))

(defn judged-pipeline [ctx]
  (let [step-judge-id (sheet/build-workflow!
                       ctx (judge-workflow-with "docs-step-judge"
                                                "ai.obney.orc.evaluation.docs-examples-test/step-judge" {}))
        pipeline-judge-id (sheet/build-workflow!
                           ctx (judge-workflow-with
                                "docs-pipeline-judge"
                                "ai.obney.orc.evaluation.docs-examples-test/pipeline-judge"
                                {:host-family [:vector any-map]
                                 :child-assessments [:vector any-map]}))]
    (sheet/build-workflow! ctx
      (sheet/workflow "docs-judged-pipeline"
        (sheet/blackboard {:request :string :mid :string :answer :string})
        (sheet/judges {:step-check {:type :custom :sheet-id step-judge-id}
                       :pipeline-check {:type :custom :sheet-id pipeline-judge-id}})
        (sheet/sequence "pipeline" :judges ["pipeline-check"]
          (sheet/code "step" :fn "ai.obney.orc.evaluation.docs-examples-test/step"
            :reads [:request] :writes [:mid] :judges ["step-check"])
          (sheet/code "finish" :fn "ai.obney.orc.evaluation.docs-examples-test/finish"
            :reads [:mid] :writes [:answer]))))))
;; docs-example-end

(deftest a-judge-on-a-composite-reads-family-and-child-assessments
  (h/with-async-test-context [ctx]
    (let [sheet-id (judged-pipeline ctx)
          result (sheet/execute ctx sheet-id {:request "hello"} :timeout-ms 60000)
          [a] (settled ctx {:node-id (node-id ctx sheet-id "pipeline")} 1)]
      (is (= :success (:status result)) (pr-str result))
      (is (= :scored (:status a)) (pr-str a))
      (is (= 1.0 (:score a))
          "the pipeline judge saw every executed node and one scored child assessment")
      (is (= "Executed 2 nodes; child assessments: [:scored]" (:feedback a)) (pr-str a))
      (is (= #{"pipeline" "step"}
             (set (map (comp :name #(first (filter (fn [n] (= (:node-id %) (:id n)))
                                                   (sheet/get-nodes-for-sheet ctx sheet-id))))
                       (evaluation/get-assessments ctx {:sheet-id sheet-id}))))
          "the composite and the leaf were each assessed"))))

;; ---------------------------------------------------------------------------
;; Example: performance per node version, and an opt-in alert
;; ---------------------------------------------------------------------------

;; docs-example-begin: performance
(defn varying-category [{:keys [inputs]}]
  {:category (if (= "bad" (:ticket-message inputs))
               "I am not sure which category this ticket belongs to."
               "billing")})

(defn watched-workflow [judge-sheet-id]
  (sheet/workflow "docs-watched-triage"
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:label {:type :custom
                           :sheet-id judge-sheet-id
                           :rubric label-rubric
                           :alert {:below 0.75 :window 2 :min-coverage 1.0}}})
    (sheet/code "classify"
      :fn "ai.obney.orc.evaluation.docs-examples-test/varying-category"
      :reads [:ticket-message]
      :writes [:category]
      :judges ["label"])))

(defn performance-views [ctx sheet-id node-id]
  {:performance (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id node-id})
   :low (evaluation/get-low-performing ctx {:below 0.75 :sheet-id sheet-id})
   :trend (evaluation/get-performance-trend
           ctx {:sheet-id sheet-id :node-id node-id :judge-name "label"})})
;; docs-example-end

(deftest performance-is-collected-and-an-alert-signals-once
  (h/with-async-test-context [ctx]
    (let [judge-sheet-id (sheet/build-workflow! ctx label-judge-workflow)
          sheet-id (sheet/build-workflow! ctx (watched-workflow judge-sheet-id))
          id (node-id ctx sheet-id "classify")
          run! (fn [message n]
                 (sheet/execute ctx sheet-id {:ticket-message message})
                 (settled ctx {:sheet-id sheet-id} n))]
      (run! "good" 1)
      (run! "good" 2)
      (run! "bad" 3)
      (run! "bad" 4)
      (let [{:keys [performance low trend]} (performance-views ctx sheet-id id)
            [version] (:versions performance)
            [stats] (:judges version)]
        (is (= 1 (count (:versions performance))) "one node version")
        (is (= {:scored 4 :failed 0 :ungradable 0 :pending 0 :total 4} (select-keys stats [:scored :failed :ungradable :pending :total])))
        (is (= 1.0 (:coverage stats)))
        (is (= 0.5 (:mean-score stats)) "two scores of 1.0 and two of 0.0")
        (is (= 0.5 (:trailing-mean stats)) "the default window of 20 holds all four outcomes")
        (is (= "label" (:judge-name stats)))
        (is (= 1 (count (:rollup performance))))
        (is (= [1.0 1.0 0.0 0.0] (mapv :score trend)))
        (is (= ["label"] (mapv :judge-name low)) (pr-str low)))
      (testing "the alert records one crossing for the episode"
        (is (h/settle-until!
             #(= 1 (count (events-of ctx #{:evaluation/performance-threshold-crossed}))))
            "threshold crossed")
        (Thread/sleep 300)
        (is (= 1 (count (events-of ctx #{:evaluation/performance-threshold-crossed}))))
        (is (empty? (events-of ctx #{:evaluation/performance-threshold-recovered}))))
      (testing "the report names an explicit set of assessments"
        (let [ids (mapv :assessment-id (evaluation/get-assessments ctx {:sheet-id sheet-id}))
              report (evaluation/get-assessment-report ctx {:assessment-ids ids})]
          (is (= [{:judge-name "label" :judge-revision-number 1 :total 4 :scored 4
                   :failed 0 :ungradable 0 :pending 0 :coverage 1.0 :mean-score 0.5
                   :band-distribution {3 2 1 2}}]
                 report)))))))

;; ---------------------------------------------------------------------------
;; Example: a judge that cannot grade leaves a failed assessment
;; ---------------------------------------------------------------------------

;; docs-example-begin: failed-assessment
(defn cannot-grade [_] (throw (ex-info "this judge cannot grade" {})))

(defn failing-judged-workflow [judge-sheet-id]
  (sheet/workflow "docs-failing-judged"
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:broken {:type :custom :sheet-id judge-sheet-id}})
    (sheet/code "classify"
      :fn "ai.obney.orc.evaluation.docs-examples-test/classify"
      :reads [:ticket-message]
      :writes [:category]
      :judges ["broken"])))
;; docs-example-end

(deftest a-judge-that-cannot-grade-leaves-a-failed-assessment
  (h/with-async-test-context [ctx]
    (let [judge-sheet-id (sheet/build-workflow!
                          ctx (judge-workflow-with "docs-broken-judge"
                                                   "ai.obney.orc.evaluation.docs-examples-test/cannot-grade" {}))
          sheet-id (sheet/build-workflow! ctx (failing-judged-workflow judge-sheet-id))
          result (sheet/execute ctx sheet-id {:ticket-message "hi"})
          [a] (settled ctx {:sheet-id sheet-id} 1)]
      (is (= :success (:status result)) "the judged node is unaffected")
      (is (= :failed (:status a)) (pr-str a))
      (is (= :judge-execution-failed (:reason a)) (pr-str a))
      (is (string? (:message a)))
      (is (not-any? #(contains? a %) [:score :band])
          "a failed assessment never carries a score"))))

;; ---------------------------------------------------------------------------
;; Example: a banded decision inside any workflow
;; ---------------------------------------------------------------------------

;; docs-example-begin: banded-decision
(def support-bands
  {:criterion "How well are the claims supported by the evidence?"
   :stance "Be strict."
   :bands {1 "Unsupported facts" 2 "Partly supported facts" 3 "All facts supported"}})

(def banded
  (sheet/workflow "docs-banded-decision"
    (sheet/blackboard
     {:evidence :string
      :rubric [:map {:description "A grading rubric: ordered, described bands"}
               [:bands [:map-of :int [:maybe :string]]]
               [:criterion {:optional true} :string]
               [:stance {:optional true} :string]]
      :band :int})
    (sheet/llm-decision "grade"
      :instruction "Grade the evidence against the rubric."
      :reads [:evidence :rubric]
      :bands-from :rubric
      :writes [:band])))
;; docs-example-end

(deftest a-banded-decision-writes-the-chosen-band
  (h/with-async-test-context [ctx]
    (let [calls (atom [])]
      (with-redefs [llm/predict (stub-provider calls {:score-only-band 2})]
        (let [sheet-id (sheet/build-workflow! ctx banded)
              result (sheet/execute ctx sheet-id
                                    {:evidence "Revenue was 4.1M per the board report."
                                     :rubric support-bands})]
          (is (= :success (:status result)) (pr-str result))
          (is (= 2 (get-in result [:outputs :band]))))))))

;; ---------------------------------------------------------------------------
;; Example: criteria on a built-in judge
;; ---------------------------------------------------------------------------

;; docs-example-begin: criteria
(def criteria-judges
  {:grounded {:type :grounding
              :criteria "Every claim must trace to a field of the ticket."}})
;; docs-example-end

(deftest criteria-replace-the-built-in-criterion
  (h/with-async-test-context [ctx]
    (let [calls (atom [])]
      (with-redefs [llm/predict (stub-provider calls {:feedback-outputs grounded-answer})]
        (let [sheet-id (sheet/build-workflow!
                        ctx (sheet/workflow "docs-criteria-triage"
                              (sheet/blackboard {:ticket-message :string :category :string})
                              (sheet/judges criteria-judges)
                              (sheet/llm "classify"
                                :instruction "Classify the ticket into one category."
                                :reads [:ticket-message] :writes [:category]
                                :judges ["grounded"])))
              _ (sheet/execute ctx sheet-id {:ticket-message "URGENT: billing error."})
              [a] (settled ctx {:sheet-id sheet-id} 1)
              grading (first (filter #(grading-call? (:module %)) @calls))]
          (is (= :scored (:status a)) (pr-str a))
          (is (str/includes? (str (get-in grading [:inputs :rubric]))
                             "Every claim must trace to a field of the ticket.")))))))

;; ---------------------------------------------------------------------------
;; Example: the retained synchronous functions
;; ---------------------------------------------------------------------------

;; docs-example-begin: synchronous-functions
(defn mock-grounding-check []
  (judges/with-mock-llm
    (judges/evaluate-single
     :grounding
     {:inputs {:context "FAQ: The gym is open Monday to Friday, 6am to 10pm."}
      :response "The gym is open Monday to Friday."
      :instruction "Answer from the FAQ only."})))
;; docs-example-end

(deftest the-retained-synchronous-judge-runs-with-the-mock
  (let [result (mock-grounding-check)]
    (is (map? result) (pr-str result))
    (is (number? (get-in result [:grounding-result :score])) (pr-str result))))

;; ---------------------------------------------------------------------------
;; Example: feedback value types
;; ---------------------------------------------------------------------------

;; docs-example-begin: feedback-utilities
(defn feedback-examples []
  {:single (evaluation/->score-with-feedback 0.75 "Good but missing one key entity")
   :combined (evaluation/combine-dimension-scores
              [(evaluation/->metric-dimension "Grounding" 0.6 0.9 "Well grounded")
               (evaluation/->metric-dimension "Completeness" 0.4 0.5 "Missing cost info")])})
;; docs-example-end

(deftest feedback-value-types-combine-by-weight
  (let [{:keys [single combined]} (feedback-examples)]
    (is (= 0.75 (:score single)))
    (is (= "Good but missing one key entity" (:feedback single)))
    (is (< (Math/abs (- 0.74 (:score combined))) 1e-9) "0.6 * 0.9 + 0.4 * 0.5")
    (is (= 2 (count (:dimensions combined))))))

;; ---------------------------------------------------------------------------
;; Example: evaluation workflows over a trace map
;; ---------------------------------------------------------------------------

;; docs-example-begin: evaluation-workflows
(defn build-evaluation-workflows [ctx]
  {:grounding (sheet/build-workflow! ctx (evaluation/grounding-judge-sheet))
   :suite (sheet/build-workflow! ctx (evaluation/evaluation-suite))
   :batch (sheet/build-workflow! ctx (evaluation/batch-evaluation-suite))
   :selective (sheet/build-workflow!
               ctx (evaluation/selective-judge-suite [:grounding :reasoning]))})
;; docs-example-end

(deftest the-evaluation-workflows-build
  (h/with-async-test-context [ctx]
    (let [built (build-evaluation-workflows ctx)]
      (is (= #{:grounding :suite :batch :selective} (set (keys built))))
      (is (every? uuid? (vals built)))
      (is (= 4 (count (set (vals built)))) "four distinct workflows"))))

;; ---------------------------------------------------------------------------
;; Example: reading node executions back
;; ---------------------------------------------------------------------------

;; docs-example-begin: trace-extraction
(defn classify-traces [ctx sheet-id]
  (evaluation/get-llm-traces ctx {:sheet-id sheet-id :node-name "classify" :limit 50}))

(defn classify-stats [ctx sheet-id]
  (evaluation/get-node-stats ctx {:sheet-id sheet-id}))
;; docs-example-end

(deftest llm-node-executions-can-be-read-back
  (h/with-async-test-context [ctx]
    (let [calls (atom [])]
      (with-redefs [llm/predict (stub-provider calls {:feedback-outputs grounded-answer})]
        (let [sheet-id (run-triage ctx)
              _ (settled ctx {:sheet-id sheet-id} 1)
              _ (h/settle-until! #(seq (classify-traces ctx sheet-id)))
              [trace] (classify-traces ctx sheet-id)
              [stats] (classify-stats ctx sheet-id)]
          (is (= "classify" (:node-name trace)) (pr-str trace))
          (is (= {:ticket-message "URGENT: billing error on my account."} (:inputs trace)))
          (is (= "Classify the ticket into one category." (:instruction trace)))
          (is (= "classify" (:node-name stats)))
          (is (= 1 (:execution-count stats))))))))

;; ---------------------------------------------------------------------------
;; Example: declare and attach with commands
;; ---------------------------------------------------------------------------

;; docs-example-begin: attach-by-command
(defn declare-and-attach! [ctx sheet-id node-id]
  (doseq [command [{:command/name :sheet/declare-judge
                    :sheet-id sheet-id
                    :judge-name "my-grounding"
                    :judge-config {:type :grounding}}
                   {:command/name :sheet/set-node-judges
                    :sheet-id sheet-id
                    :node-id node-id
                    :judges ["my-grounding"]}]]
    (cp/process-command
     (assoc ctx :command (assoc command
                                :command/id (random-uuid)
                                :command/timestamp (time/now))))))
;; docs-example-end

(deftest a-judge-declared-by-command-attaches-to-a-composite
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow!
                    ctx (sheet/workflow "docs-attach-by-command"
                          (sheet/blackboard bb)
                          (sheet/sequence "pipeline"
                            (sheet/code "step" :fn "ai.obney.orc.evaluation.docs-examples-test/step"
                              :reads [:request] :writes [:mid])
                            (sheet/code "finish" :fn "ai.obney.orc.evaluation.docs-examples-test/finish"
                              :reads [:mid] :writes [:answer]))))
          pipeline (node-id ctx sheet-id "pipeline")
          calls (atom [])]
      (declare-and-attach! ctx sheet-id pipeline)
      (is (= ["my-grounding"] (:judges (first (filter #(= pipeline (:id %))
                                                      (sheet/get-nodes-for-sheet ctx sheet-id))))))
      (with-redefs [llm/predict (stub-provider calls {:feedback-outputs grounded-answer})]
        (let [result (sheet/execute ctx sheet-id {:request "hello"} :timeout-ms 60000)
              [a] (settled ctx {:node-id pipeline} 1)]
          (is (= :success (:status result)) (pr-str result))
          (is (= :scored (:status a)) (pr-str a))
          (is (= "my-grounding" (:judge-name a))))))))

;; ---------------------------------------------------------------------------
;; Example: judges in the way - an inline gate (hand-built from today's nodes)
;; ---------------------------------------------------------------------------

;; docs-example-begin: inline-gate
(def gate-judge
  (sheet/workflow "docs-gate-judge"
    (sheet/blackboard {:request :string :answer :string
                       :band :int :gate-feedback :string})
    (sheet/llm "review"
      :instruction "Grade the answer to the request from 1 (reject) to 4 (excellent). If the band is below 3, say what must change."
      :reads [:request :answer]
      :writes [:band :gate-feedback])))

(defn gated [gate-id]
  (sheet/workflow "docs-inline-gate"
    (sheet/blackboard {:request :string :answer :string
                       :band :int :gate-feedback :string})
    (sheet/fallback "attempts"
      (sheet/sequence "attempt-1"
        (sheet/llm "draft-1"
          :instruction "Answer the request."
          :reads [:request]
          :writes [:answer])
        (sheet/delegate "gate-1" :target-sheet-id gate-id
          :reads [:request :answer]
          :writes [:band :gate-feedback])
        (sheet/condition "accept-1" :check {:key :band :op :gte :value 3}))
      (sheet/sequence "attempt-2"
        (sheet/llm "draft-2"
          :instruction "Revise the answer so that it addresses the gate feedback."
          :reads [:request :answer :gate-feedback]
          :writes [:answer])
        (sheet/delegate "gate-2" :target-sheet-id gate-id
          :reads [:request :answer]
          :writes [:band :gate-feedback])
        (sheet/condition "accept-2" :check {:key :band :op :gte :value 3})))))
;; docs-example-end

(defn- gate-stub
  "Producer calls are recorded in `drafts`; the gate rejects the first draft
   with feedback and accepts the second."
  [drafts reviews]
  (fn [_provider module inputs options]
    (let [usage {:prompt_tokens 5 :completion_tokens 5 :total_tokens 10}
          output-names (set (map :name (:outputs module)))
          reply (fn [outputs]
                  (if (:with-metadata? options)
                    {:outputs outputs :usage usage :model "stub/model" :raw-response (pr-str outputs)}
                    outputs))]
      (if (contains? output-names :band)
        (let [n (count (swap! reviews conj inputs))]
          (reply (if (= 1 n)
                   {:band 1 :gate-feedback "Too vague: name the invoice number."}
                   {:band 4 :gate-feedback "Specific and complete."})))
        (let [n (count (swap! drafts conj inputs))]
          (reply {:answer (str "draft " n)}))))))

(deftest an-inline-gate-rejects-the-first-draft-and-accepts-the-second
  (h/with-async-test-context [ctx]
    (let [drafts (atom []) reviews (atom [])]
      (with-redefs [llm/predict (gate-stub drafts reviews)]
        (let [gate-id (sheet/build-workflow! ctx gate-judge)
              sheet-id (sheet/build-workflow! ctx (gated gate-id))
              result (sheet/execute ctx sheet-id {:request "Why was I charged twice?"}
                                    :timeout-ms 60000)]
          (is (= :success (:status result)) (pr-str result))
          (is (= 2 (count @drafts)) "a second attempt was made")
          (is (= 2 (count @reviews)) "each draft went through the gate")
          (is (= "draft 2" (get-in result [:outputs :answer])) "the accepted answer is the second draft")
          (is (= 4 (get-in result [:outputs :band])))
          (let [[first-draft second-draft] @drafts
                shows? (fn [inputs text]
                         (boolean (re-find (re-pattern text) (pr-str inputs))))]
            (is (not (shows? first-draft "Too vague"))
                "the first attempt had no feedback yet")
            (is (shows? second-draft "Too vague: name the invoice number.")
                "the second attempt was given the gate's rejection feedback")
            (is (shows? second-draft "draft 1")
                "and the answer it rejected"))
          (is (empty? (evaluation/get-assessments ctx {:sheet-id sheet-id}))
              "inline verdicts are ordinary node executions, not assessments"))))))

;; ---------------------------------------------------------------------------
;; The documentation can not drift from this file
;; ---------------------------------------------------------------------------

(def ^:private doc-files
  [".claude/skills/orc-evaluate/SKILL.md"
   "docs/EVALUATION-COMPONENT.md"
   "docs/JUDGE-ARCHITECTURE.md"
   "docs/DSL-REFERENCE.md"
   "docs/ORC-SERVICE-GUIDE.md"
   "docs/GEPA-GUIDE.md"
   "docs/GETTING-STARTED.md"])

(defn- repo-root []
  (loop [dir (.getCanonicalFile (io/file "."))]
    (cond
      (nil? dir) (throw (ex-info "repo root not found" {}))
      (.exists (io/file dir "docs" "JUDGE-ARCHITECTURE.md")) dir
      :else (recur (.getParentFile dir)))))

(defn- normalise
  "Code lines with comments and blank lines dropped and indentation trimmed."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % ";")))
       (str/join "\n")))

(defn- regions
  "{id normalised-text} for every marked example region of this file."
  []
  (let [src (slurp (io/file (repo-root) "components/evaluation/test/ai/obney/orc/evaluation/docs_examples_test.clj"))]
    (into {}
          (for [[_ id body] (re-seq #"(?s);; docs-example-begin: (\S+)\n(.*?);; docs-example-end" src)]
            [id (normalise body)]))))

(defn- doc-blocks
  "Every fenced clojure block that opens with a `;; docs-example: <id>` line:
   [[file id normalised-text] ...]."
  []
  (for [f doc-files
        [_ body] (re-seq #"(?s)```clojure\n(.*?)```" (slurp (io/file (repo-root) f)))
        :let [[_ id] (re-find #"^;; docs-example: (\S+)" body)]
        :when id]
    [f id (normalise body)]))

(deftest docs-match-this-file
  (let [regions (regions)
        blocks (doc-blocks)]
    (is (seq regions))
    (testing "every marked region is shown verbatim in the docs"
      (doseq [[id text] regions]
        (is (some (fn [[_ bid btext]] (and (= id bid) (= text btext))) blocks)
            (str "no documentation block reproduces example " id " verbatim"))))
    (testing "every docs-example block names a region and equals it"
      (doseq [[f id text] blocks]
        (is (contains? regions id) (str f ": unknown example " id))
        (is (= (get regions id) text) (str f ": example " id " differs from the tested code"))))))
