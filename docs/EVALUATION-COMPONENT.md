# Evaluation Component

Judges that grade what ORC nodes did, durable assessments of every judgment, and performance per node version.

> **Design.** The reasons behind the rubric, the discrete bands and the adversarial stance are in [JUDGE-ARCHITECTURE.md](JUDGE-ARCHITECTURE.md). This document is the reference.
>
> **Examples are tested.** Every block marked `;; docs-example: <id>` is run by `components/evaluation/test/ai/obney/orc/evaluation/docs_examples_test.clj`, and that test fails if a block differs from the code it runs.

<p align="center"><img src="media/judges.gif" alt="Judges scoring node outputs asynchronously" width="760"></p>
<p align="center"><sub>Judges grade the nodes that matter while the work keeps running. <i>Illustrative.</i></sub></p>

## Contents

1. [Concepts](#concepts)
2. [Attach a judge](#attach-a-judge)
3. [Declare a judge](#declare-a-judge)
4. [Rubrics and bands](#rubrics-and-bands)
5. [Built-in judges](#built-in-judges)
6. [Custom judges](#custom-judges)
7. [Evidence a judge reads](#evidence-a-judge-reads)
8. [Assessments and outcomes](#assessments-and-outcomes)
9. [Purposes: monitoring and learning](#purposes-monitoring-and-learning)
10. [Judging composites and delegates](#judging-composites-and-delegates)
11. [Revising a judge](#revising-a-judge)
12. [Models and providers](#models-and-providers)
13. [Performance and alerts](#performance-and-alerts)
14. [Banded decisions](#banded-decisions)
15. [Retained synchronous functions](#retained-synchronous-functions)
16. [Trace extraction](#trace-extraction)
17. [Feedback utilities](#feedback-utilities)
18. [Evaluation workflows](#evaluation-workflows)
19. [Source files](#source-files)

## Concepts

| Term | Meaning |
|---|---|
| **Judge** | A behaviour that grades recorded work against a rubric. Every judge is an ordinary workflow, built-in or custom. It may delegate, run parallel checks and call tools. |
| **Rubric** | The grading contract a judge reads: the criterion, the reviewer's stance, a description for every band, and whether feedback is required. |
| **Band** | One described level of the rubric's ordered scale. The model selects a band. The score is derived from it and is never reported by the model. |
| **Assessment subject** | The completed execution an assessment is about: one execution of a leaf, a composite, a delegate or the whole tree. Repeated executions of one node in one run are distinct subjects. |
| **Assessment** | One judge's judgment of one subject under one judge revision. |
| **Assessment outcome** | How an assessment ended: scored, failed, ungradable, or still pending. Only scored outcomes carry a score. |
| **Judge revision** | The identity of a judge's exact definition (behaviour, rubric, declared model) when it assessed something. Results under different revisions are never blended. |
| **Node version** | The node's effective definition when it ran. Performance is kept per node version. |
| **Monitoring judge** | A judge whose results describe how its subjects perform. Its feedback may be absent, so its results never reach the learning loops. |
| **Learning judge** | A judge whose results also feed Living Descriptions, harvest and instruction optimization. It must require feedback. |
| **Assessment origin** | The durable mark that an execution exists to perform an assessment. Work under it is never itself assessed automatically. |
| **Execution family** | Every node execution beneath one execution, in order, delegated workflows included. |
| **Coverage** | Beside any grade, how many assessments were expected and how many were scored, failed, ungradable or still pending. A grade is never reported without it. |
| **Performance threshold crossing** | An opt-in signal that a node version's trailing mean fell below a judge's declared threshold. |

A judge never changes how your tree runs. The node finishes and returns what it always returned. The assessment is requested durably when the node completes, and judging happens afterwards on the event log.

## Attach a judge

Declare the judge with `sheet/judges`, then name it on any node with `:judges`. Attaching a judge enables it. No flag has to be turned on.

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

`evaluation/get-assessments` returns the tenant's assessments, oldest request first. Narrow them with any of `:sheet-id :node-id :tick-id :judge-name :status`.

A scored assessment:

| Field | Meaning |
|---|---|
| `:status` | `:scored` |
| `:band`, `:score` | the chosen band and its derived score in `[0,1]`: `(band - lowest) / (highest - lowest)`. Band 4 of 1 to 5 is 0.75. |
| `:feedback` | present when the rubric requires feedback; never invented otherwise |
| `:dimensions` | for built-in judges, one named dimension summarising the evidence lists the judge wrote |
| `:judge-name`, `:judge-revision-number`, `:purposes` | which judge, which revision, what it serves |
| `:model-provenance` | the requested and resolved model and the usage of the judge's calls |

## Declare a judge

A judge is declared under a name. Fields of its config:

| Field | Meaning |
|---|---|
| `:type` | `:grounding`, `:instruction-following`, `:reasoning`, `:completeness` (built-in), or `:custom` |
| `:sheet-id` | for `:custom`: the workflow that grades |
| `:rubric` | `{:criterion :stance :bands {1 "..." 2 "..."} :feedback :required\|:none}`. Absent, a built-in judge uses its type's default rubric. |
| `:criteria` | on a built-in judge without a `:rubric`: replaces the default rubric's criterion |
| `:purposes` | a non-empty subset of `#{:monitoring :learning}`. Default: both, or only `:monitoring` when the rubric's feedback is `:none` |
| `:model` | a model id or a registered provider name |
| `:timeout-ms` | a positive integer; default 60000 |
| `:weight` | the judge's relative weight in the composite of a node's learning judges |
| `:alert` | `{:below :window :min-coverage}`, see [Performance and alerts](#performance-and-alerts) |
| `:assess-failures?` | a boolean; default false. A completion that failed, timed out or blocked is recorded ungradable (`:subject-failed`) without running the judge; true judges it like any other |

A declaration is rejected when a learning judge's rubric does not require feedback, when a rubric is malformed, or when an alert is malformed. A judge's `:provider` field has no effect and is logged when present: the runtime provider executes every node, so a model is chosen with `:model`.

Judges can attach to any node: leaf, composite, delegate or the root of a tree. Runs of published versions are judged through the draft node's attachments.

`:criteria` is the standard you would hand a reviewer. Be specific and measurable ("every claim must trace to a field of the ticket"), not general ("be good").

```clojure
;; docs-example: criteria
(def criteria-judges
  {:grounded {:type :grounding
              :criteria "Every claim must trace to a field of the ticket."}})
```

Several nodes may name one judge: `:judges ["common-grounding"]`.

## Rubrics and bands

A rubric's bands are contiguous integers, at least two, each with a non-blank description. The model chooses exactly one band. The criterion says what to evaluate, the stance says how the reviewer behaves, and the bands say what each level means. The built-in judges ship an adversarial stance and five described bands (1 to 5); see `judge-behaviours/default-rubric`.

Editing the rubric changes what the next grading call asks. It does not change the judge's behaviour. It does change the judge revision.

`:feedback :none` gives a score-only judge:

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

The assessment has `:band` and `:score` and no `:feedback` key.

## Built-in judges

Four judges ship as workflows (`components/evaluation/src/ai/obney/orc/evaluation/core/judge_behaviours.clj`):

| Type | Grades |
|---|---|
| `:grounding` | whether claims trace to what the node read; hallucination |
| `:instruction-following` | whether the output complies with the node's instruction |
| `:reasoning` | whether the reasoning is sound |
| `:completeness` | whether everything the task required is covered |

Each has two forms, chosen by the rubric:

- **Feedback form** (`:feedback :required`, the default): one llm node that writes its reasoning first, then the evidence lists for the judge's type, then the band, then the feedback.
- **Score-only form** (`:feedback :none`): one `sheet/llm-decision` node banded from the rubric that writes only the band.

A feedback judge whose feedback comes back blank or absent is asked once more, as a malformed answer. If it is still missing, the assessment fails with `:missing-feedback`. A band outside the rubric fails with `:invalid-result`.

A fifth type, `:heuristic-structural`, is deterministic and uses no model. It grades the shape of a tree a researcher produced. It is one of the five default judges attached to `:repl-researcher` nodes when the Living Description flag is on; that flag gates only those defaults, never an attachment you make yourself.

## Custom judges

A custom judge is a workflow named by `:sheet-id`. It reads the evidence by declaring blackboard keys. With a rubric it writes a `:band` (and `:feedback` when the rubric requires it). Without a rubric it writes a numeric `:score` in `[0,1]` and `:feedback`; a score outside `[0,1]` fails the assessment with `:invalid-result` and is never clamped.

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

Optional `:dimensions` (`[{:name :score :feedback [:weight]}]`) are validated; a dimension without a score fails the assessment with `:invalid-dimensions`.

## Evidence a judge reads

What the judge is handed:

| Blackboard key | Content |
|---|---|
| `:host-inputs` | the values the subject read, resolved from the durable value log for that exact execution |
| `:host-outputs` | the values the subject wrote, one per declared output field |
| `:host-instruction` | the node's instruction. A node without one is judged against the judge's `:criteria`, or a sentence naming its declared `:writes` |
| `:rubric` | the rubric value (criterion, stance, bands), when the judge has one |
| `:original-task` | the inputs the whole run started with, separate from the node's own instruction |
| `:host-family` | opt-in: the execution family of the subject |
| `:child-assessments` | opt-in: the settled assessments beneath the subject. The parent's assessment waits until they have all ended. |

A custom judge receives only the keys its blackboard declares. Built-in judges receive the first five. A leaf gets its own resolved reads and writes. A composite or delegate gets its forwarded outputs. `:host-family` is read (`orc/get-execution-family`) only for a judge that declares it.

## Assessments and outcomes

An assessment is identified by the completion it assesses, the judge, and the judge's revision. A command requests it before any judging starts, so delivering the same completion again requests nothing new. It then ends in one terminal event:

| Outcome | Event | Carries |
|---|---|---|
| scored | `:evaluation/assessment-scored` | band, score, feedback if required, dimensions |
| failed | `:evaluation/assessment-failed` | `:reason`, `:message` |
| ungradable | `:evaluation/assessment-ungradable` | `:reason`, `:message` |

Failed means the judgment could not be carried out. Ungradable means the evidence did not permit a grade. Reasons:

| Reason | Outcome | Meaning |
|---|---|---|
| `:judge-model-unresolved` | failed | no declared `:model` and no runtime provider; the message says how to fix it |
| `:missing-feedback` | failed | required feedback stayed blank after one retry |
| `:invalid-result` | failed | no band, a band outside the rubric, or a score outside `[0,1]` |
| `:invalid-dimensions` | failed | a dimension without a name or score |
| `:deadline` | failed | the judge did not finish within `:timeout-ms` |
| `:judge-execution-failed` | failed | the judge's workflow did not succeed |
| a node failure kind, such as `:provider-finish-error` | failed | the judge's own node failed that way |
| `:tied-bands` | ungradable | a decision model returned an exact tie between bands; it is not retried |
| `:subject-failed` | ungradable | the assessed execution failed, timed out or blocked, so there is nothing to grade; no judge ran (unless the judge declares `:assess-failures?`) |

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

Work done for an assessment is never auto-assessed, so judges that delegate or call tools do not recurse. Capacity limits, worker claims and restart recovery of requested assessments are not yet provided: until then a crash leaves a requested assessment visibly pending.

## Purposes: monitoring and learning

A judge declares what its results serve.

- A **monitoring judge**'s results describe performance. They are stored as assessments and feed [performance](#performance-and-alerts). Its feedback may be absent.
- A **learning judge**'s results also feed Living Descriptions, harvest and instruction optimization. It must require feedback. For each scored outcome with feedback it emits the legacy `:judge/score-emitted` event those loops read. Historical score events show band, revision and node version as absent.

A composite score (`:judge/composite-score-computed`) is recorded once per subject after all of its learning judges have settled. It needs at least two learning judges and is the mean over those that scored, weighted by `:weight`. It always carries its coverage and is marked `:partial` when any learning judge failed or was ungradable. A monitoring judge's score is never mixed into it.

GEPA's `:judges` metric is separate: it calls the built-in judge functions directly (see [Retained synchronous functions](#retained-synchronous-functions)) and does not read or write assessments.

## Judging composites and delegates

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

A judged composite makes its tree run durably. A tree with no judged composite keeps the faster ephemeral path.

## Revising a judge

Rebuild the workflow under the same name with a changed definition. The judge is revised: its revision number goes up and its history is kept. An unchanged definition adds no revision. Redeclaring a judge under a new name would break comparison with its history, so revise it instead.

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

## Models and providers

A judge's model resolves like any node's: the declared `:model` (a model id or a registered provider name), otherwise the runtime's provider. The engine has no built-in judge model. If neither resolves, the assessment fails with `:judge-model-unresolved` and a message saying to declare a `:model` or configure the runtime provider.

Because a judge is a workflow, its model calls are budgeted, retried and accounted like any other node, and each outcome carries `:model-provenance`.

**Provider compatibility.** One OpenRouter Gemini route returned empty tool arguments for an integer enum output schema. Banded decisions therefore send the bands as described string choices and validate membership locally. An llm node that writes a band, as the feedback judges do, declares the band field as a bounded integer (`[:int {:min 1}]`), not an enum. This is the only route known to need it. A provider that ends a call with a finish error fails the judge with `:provider-finish-error`.

## Performance and alerts

Performance per node version is always collected: every outcome of every assessment contributes. Versions of a node are reported separately and never blended, with a rollup across them beside.

| Function | Returns |
|---|---|
| `evaluation/get-node-performance` `{:sheet-id :node-id [:node-version] [:judge-name] [:window]}` | versions oldest first, each with one entry per judge and revision (`:scored :failed :ungradable :pending :total :coverage :mean-score :trailing-mean :band-distribution`), and a `:rollup` |
| `evaluation/get-low-performing` `{:below [:sheet-id] [:min-coverage] [:window]}` | node versions whose trailing mean is below `:below`, worst first. `:min-coverage` leaves out thinly covered cells |
| `evaluation/get-performance-trend` `{:sheet-id :node-id :judge-name [:node-version] [:window]}` | the most recent outcomes in order |
| `evaluation/get-assessment-report` `{:assessment-ids [...]}` or `{:subject-ids [...]}` | coverage and grades for an explicit set, by judge and revision |

The trailing mean covers the last `:window` scored outcomes (default 20).

An alert is opt-in. A judge that declares `:alert {:below :window :min-coverage}` is watched over the last `:window` outcomes of a node version, once that window is full (`:window` is at most 500). It records one of:

| Event | When |
|---|---|
| `:evaluation/performance-threshold-crossed` | coverage is enough and the mean fell below `:below` |
| `:evaluation/performance-threshold-recovered` | the mean is back at or above `:below` after a crossing |
| `:evaluation/performance-coverage-degraded` | fewer than `:min-coverage` of the window was scored |
| `:evaluation/performance-coverage-restored` | coverage is back after degrading |

Each is recorded once per episode, however fast outcomes arrive or how often they are redelivered. A signal never starts training: nothing subscribes to it.

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

## Banded decisions

`sheet/llm-decision` with `:bands-from` makes a model choose one band of a rubric held on the blackboard. It is what the score-only judges run, and it works in any workflow.

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

`:bands-from` must also be in `:reads`; the answer key must be an integer schema; `:options-from`, `:min-confidence` and `:abstain` cannot be combined with it. A chat model is offered the bands as described string choices and its answer is checked against them. A native decision model (a Jev Score) is asked for a score and the band is the uniquely most probable level; an exact tie selects no band, the node ends `:undecided`, and the assessment is `:ungradable`.

## Retained synchronous functions

The synchronous judge functions still exist and are what GEPA's judge metric calls: `evaluate-single`, `grounding-judge`, `instruction-following-judge`, `reasoning-judge`, `completeness-judge`. They run a judge on a trace map directly, record nothing, and are not assessments.

```clojure
;; docs-example: synchronous-functions
(defn mock-grounding-check []
  (judges/with-mock-llm
    (judges/evaluate-single
     :grounding
     {:inputs {:context "FAQ: The gym is open Monday to Friday, 6am to 10pm."}
      :response "The gym is open Monday to Friday."
      :instruction "Answer from the FAQ only."})))
```

They are configured by `judges/*judge-provider*` (default `:openrouter`) and `judges/*judge-model*` (default `google/gemini-2.5-flash`), set with `judges/with-judge-config`. `judges/with-mock-llm` returns canned results from these functions only. It has no effect on the assessment path: to run assessments without a model, stub `llm/predict` or use a deterministic custom judge, as `docs_examples_test.clj` does.

`evaluate-trace` and `evaluate-traces`, the synchronous all-judges calls, were removed. Aggregation across judges is the composite of a subject's learning judges. The handler-less queries `get-scores`, `get-low-scoring`, `get-trends` and `results-by-node` were also removed: use `get-assessments` and the performance queries.

`get-judge-scores` still returns the legacy score entries for a `(sheet-id, node-id, tick-id)`, which exist only for learning judges' scored outcomes with feedback.

## Trace extraction

Read the executions of LLM nodes back from the event store.

```clojure
;; docs-example: trace-extraction
(defn classify-traces [ctx sheet-id]
  (evaluation/get-llm-traces ctx {:sheet-id sheet-id :node-name "classify" :limit 50}))

(defn classify-stats [ctx sheet-id]
  (evaluation/get-node-stats ctx {:sheet-id sheet-id}))
```

`get-llm-traces` takes the context and returns one map per execution of an llm node: `:trace-id :sheet-id :node-id :node-name :inputs :outputs :instruction :model :duration-ms :status :executed-at`. `:node-name` is a substring match; `:node-id`, `:since` and `:limit` also narrow it. `get-node-stats` returns per node `:execution-count :success-count :failure-count :success-rate :avg-duration-ms`. `format-trace-for-evaluation` turns a trace into the `{:inputs :response :instruction}` map the retained synchronous functions read.

## Feedback utilities

`ScoreWithFeedback` and `MetricDimension` are value types used by the retained synchronous functions and by GEPA.

```clojure
;; docs-example: feedback-utilities
(defn feedback-examples []
  {:single (evaluation/->score-with-feedback 0.75 "Good but missing one key entity")
   :combined (evaluation/combine-dimension-scores
              [(evaluation/->metric-dimension "Grounding" 0.6 0.9 "Well grounded")
               (evaluation/->metric-dimension "Completeness" 0.4 0.5 "Missing cost info")])})
```

`render-feedback` and `aggregate-feedback-summary` build and summarise feedback text by hand. The `render-feedback` templates (`:missing-entity`, `:hallucination`, `:incomplete-coverage`, `:wrong-action`, `:instruction-not-followed`, `:reasoning-unclear`, `:sarcasm-missed`, `:score-miscalibrated`) are helpers for building feedback by hand. Built-in judges do not use them.

## Evaluation workflows

The component ships ORC workflows that run the retained judge functions over a trace map.

```clojure
;; docs-example: evaluation-workflows
(defn build-evaluation-workflows [ctx]
  {:grounding (sheet/build-workflow! ctx (evaluation/grounding-judge-sheet))
   :suite (sheet/build-workflow! ctx (evaluation/evaluation-suite))
   :batch (sheet/build-workflow! ctx (evaluation/batch-evaluation-suite))
   :selective (sheet/build-workflow!
               ctx (evaluation/selective-judge-suite [:grounding :reasoning]))})
```

`evaluation-suite` runs all four judges and aggregates; `batch-evaluation-suite` takes `{:traces [...]}`; `selective-judge-suite` runs only the judges you list. Executing one takes `{:trace-data {:inputs {...} :response "..." :instruction "..."}}`. Also: `instruction-judge-sheet`, `reasoning-judge-sheet`, `completeness-judge-sheet`. These are separate from assessments.

## Source files

| File | Purpose |
|---|---|
| `interface.clj` | public API |
| `core/judge_behaviours.clj` | the built-in judges as workflows; default rubrics |
| `core/judge_run.clj` | the one run path of a judge: evidence in, outcome out |
| `core/judge_runtime.clj` | requests assessments on completion, runs judges, composite, learning records |
| `core/assessments.clj` | assessment lifecycle and `get-assessments` |
| `core/commands.clj` | request and record-outcome commands |
| `core/performance.clj`, `core/node_version.clj` | performance per node version |
| `core/alerts.clj` | opt-in performance alerts |
| `core/rubrics.clj`, `core/scale.clj` | built-in criteria, stance and band wording; band to score |
| `core/heuristic_structural.clj` | the deterministic structural judge |
| `core/judges.clj`, `core/feedback.clj` | retained synchronous functions and value types |
| `core/trace_extraction.clj`, `core/sheets.clj` | trace queries; evaluation workflows |
