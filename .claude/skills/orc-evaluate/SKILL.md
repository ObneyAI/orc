---
name: orc-evaluate
description: Attach judges to ORC workflow nodes, read their assessments, and watch node performance
---

# ORC Evaluation (judges and assessments)

Grade what a node did, record the grade durably, and watch how each version of a node performs. Read `docs/EVALUATION-COMPONENT.md` for the reference and `docs/JUDGE-ARCHITECTURE.md` for the design.

Every code block marked `;; docs-example: <id>` below is run by
`components/evaluation/test/ai/obney/orc/evaluation/docs_examples_test.clj`, which also fails if a block
differs from the tested code.

## Require

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet]
         '[ai.obney.orc.evaluation.interface :as evaluation])
```

## What a judge is

A **judge** is a behaviour (an ordinary ORC workflow) that grades an **assessment subject**, one completed execution of a node. It is declared once under a name with:

- a **rubric**: the criterion, the reviewer's stance, a described **band** for every level, and whether feedback is required (`:feedback :required` or `:none`);
- a model (`:model`: a model id or a registered provider name; absent, it runs on the runtime provider);
- **purposes**: `:monitoring`, `:learning`, or both. A **learning judge** must require feedback. A rubric with `:feedback :none` can only monitor. If you declare no purposes, a judge gets both, or only `:monitoring` when its rubric needs no feedback;
- optionally an `:alert` (see Performance below).

The model picks a band. The score is derived from it, `(band - lowest) / (highest - lowest)`, and is never reported by the model.

Four judges ship as workflows: `:grounding`, `:instruction-following`, `:reasoning`, `:completeness`. `:type :custom` with a `:sheet-id` uses your own workflow.

Attaching a judge to a node enables it. Nothing else needs switching on. (The Living Description flag only gates the five default judges that `:repl-researcher` nodes get.)

## Attach a built-in judge

```clojure
;; docs-example: built-in-judge
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
```

Each execution of `classify` is a separate subject, so three executions give three assessments. An assessment is requested durably when the node completes and ends `:scored`, `:failed` or `:ungradable`. Until then its status is `:pending`. Delivering the same completion again never creates a second assessment.

A scored assessment carries `:band`, `:score`, `:feedback` (when the rubric requires it), `:judge-revision-number`, `:purposes` and `:model-provenance`. A failed or ungradable one carries `:reason` and `:message` and never a score.

Only the scored outcomes of learning judges that carry feedback also emit `:judge/score-emitted`, the record Living Descriptions and harvest read.

## Give a judge its own rubric, score only

```clojure
;; docs-example: monitoring-judge
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
```

The scored assessment has a band and a score and no `:feedback` key. A monitoring judge never reaches the learning loops.

## Write a custom judge

A custom judge is a workflow. The runtime hands it `:host-inputs`, `:host-outputs`, `:host-instruction` and, when the judge declares them as blackboard keys, `:rubric` and `:original-task`. With a rubric, write a `:band` (and `:feedback` when the rubric requires it). Without a rubric, write a `:score` in `[0,1]`.

```clojure
;; docs-example: custom-judge
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
```

A judge may delegate, run parallel checks and call tools. Work done for an assessment carries an **assessment origin** and is never itself assessed.

## Revise a judge

Change the rubric, purposes, model or behaviour and rebuild the workflow under the same name. The judge gets a new **judge revision**; it is never redeclared as a new judge. Results under different revisions are never blended.

```clojure
;; docs-example: revise-judge
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
```

An unchanged rebuild adds no revision.

## Judge a composite, a delegate or the whole tree

A judge attaches to any node. A judged composite makes its tree run durably. A composite judge reads its forwarded outputs. Two kinds of extra evidence are opt-in; a custom judge asks for them by declaring the blackboard key:

- `:host-family`: every node execution beneath the subject, delegated runs included (the **execution family**);
- `:child-assessments`: the settled assessments of the nodes beneath it. The parent's assessment waits until they have all ended.

```clojure
;; docs-example: composite-judge
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
```

## Failures are visible

A judge that cannot grade leaves a failed assessment with the reason. Nothing is invented.

```clojure
;; docs-example: failed-assessment
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
```

Reasons you may see: `:judge-execution-failed`, `:judge-model-unresolved` (declare a `:model` or configure the runtime provider), `:missing-feedback` (blank required feedback is retried once first), `:invalid-result`, `:invalid-dimensions`, `:deadline` (`:timeout-ms`, default 60000), and the node failure kind of the judge's own workflow. An exact tie between bands is `:ungradable` with reason `:tied-bands` and is not retried.

## Performance per node version

Every outcome of every assessment feeds performance, per **node version**, always. Queries:

- `evaluation/get-node-performance`: versions oldest first, each with one entry per judge and revision (counts, **coverage**, mean, trailing mean, band distribution), plus a rollup;
- `evaluation/get-low-performing`: node versions whose trailing mean is below a threshold, worst first;
- `evaluation/get-performance-trend`: a judge's most recent outcomes in order;
- `evaluation/get-assessment-report`: coverage and grades for an explicit set of assessments.

A grade always comes with its coverage: how many assessments were expected and how many were scored, failed, ungradable or still pending.

A judge may declare an `:alert {:below :window :min-coverage}`. Over a full trailing window it then records a **performance threshold crossing**, a recovery, or a coverage degradation or restoration, once per episode. Nothing starts training automatically.

```clojure
;; docs-example: performance
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
```

## Banded decisions in any workflow

`sheet/llm-decision` with `:bands-from` makes the model choose one band of a rubric read from the blackboard and writes the integer band. The answer key must be an integer schema, and `:bands-from` must also be in `:reads`.

```clojure
;; docs-example: banded-decision
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
```

A chat model is asked to choose among the bands as described string choices and the answer is checked against the offered bands. A native decision model (a Jev Score) is asked for a score; an exact tie yields no band and the node ends `:undecided`.

## Provider compatibility note

One OpenRouter Gemini route returned empty tool arguments for an integer enum output schema. Banded decisions therefore send bands as described string choices and validate membership locally, and the llm node used by feedback judges declares its band field as a bounded integer (`[:int {:min 1}]`). This is the only route known to need it.

## Testing judges

Stub the provider seam, `llm/predict`, with `with-redefs`, or use a deterministic custom judge workflow as above. `judges/*use-mock-llm*` only affects the retained synchronous judge functions, never the assessment path.

## Reference

- `docs/EVALUATION-COMPONENT.md`: reference
- `docs/JUDGE-ARCHITECTURE.md`: design
- `docs/GEPA-GUIDE.md`: GEPA's `:judges` metric
- `docs/LIVING-DESCRIPTIONS.md`: what learning judges feed
