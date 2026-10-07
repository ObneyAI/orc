# Judge Architecture

> **Role of this doc:** the design behind ORC judges: what a judge is, why a judgment is a durable assessment, the `criterion x stance x bands` rubric, and what the engine guarantees. The reference (declaring judges, evidence, queries) is [`EVALUATION-COMPONENT.md`](EVALUATION-COMPONENT.md). The decision record is [ADR 0008](adr/0008-judge-assessments-are-durable-grain-work.md).
>
> Every code block marked `;; docs-example: <id>` is run by `components/evaluation/test/ai/obney/orc/evaluation/docs_examples_test.clj`.

---

## 1. Why judges matter

<p align="center"><img src="media/judges.gif" alt="A panel of judges writing evidence and raising score cards" width="760"></p>
<p align="center"><sub>Judges write their evidence before choosing a band, then record the assessment. <i>Illustrative.</i></sub></p>

A judge grades recorded work. What its results are for depends on its declared purposes:

```
node completes
    └─► assessment requested (durable)
            └─► judge runs, as a workflow
                    └─► assessment scored | failed | ungradable
                            ├─► performance per node version   (every judge)
                            │       └─► opt-in alerts
                            └─► :judge/score-emitted           (learning judges, scored, with feedback)
                                    └─► consolidator ─► Living Description
                                    └─► harvest, instruction optimization
```

Monitoring judges tell you how a node version is performing. Learning judges also feed the loops that change ORC's behaviour: Living Descriptions, harvest and instruction optimization. **Noise in a learning judge propagates into every one of them**: a miscalibrated judge does not just give a bad number, it teaches bad lessons. That is why a learning judge must require feedback, why the score comes from a described band, and why nothing is ever invented when a judge cannot grade.

---

## 2. Design decisions

### Every judge is a behaviour

A judge is an ordinary ORC workflow, whether it ships with ORC or you wrote it. Its model call is therefore budgeted, retried, accounted and model-selectable like any other node, and a judge may delegate, run parallel checks and call tools. The earlier design called the model from plain code outside node accounting, which left two execution paths for judges and doubled every assessment feature. There is now one run path.

### Every judgment is a durable assessment

An assessment is identified by the completion it assesses, the judge, and the judge's revision. A command requests it before any judging starts, so replaying or redelivering a completion never creates a second one. It ends scored, failed or ungradable, with the reason. The earlier design keyed a score by sheet, node, tick and judge. Three executions of one node in one run kept one score, and a judge that failed left no trace.

Assessments still execute as soon as they are requested. Capacity limits, worker claims and restart recovery are open: until then a crash leaves a requested assessment visibly pending, not lost.

### Attaching a judge enables it

An attachment is already a deliberate per-node choice. A second switch would recreate the bug where an attached judge silently does nothing. (The Living Description flag gates only the five default judges of `:repl-researcher` nodes.)

### The rubric is data, with three separate parts

A rubric is a value on the judge's blackboard:

- the **criterion**: what to evaluate;
- the **stance**: how the reviewer behaves;
- the **bands**: what each level of the ordered scale means, plus whether feedback is required.

Editing the rubric changes what the next grading call asks and creates a new judge revision. It never changes the judge's tree. Keeping the three parts apart lets you tune the criterion without recalibrating the bands, and the other way round.

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

### Discrete bands, and a score the model never reports

The model chooses one described band. ORC derives the score: `(band - lowest) / (highest - lowest)`, so band 4 of 1 to 5 is 0.75. A model asked for a continuous number has no structural reason to use the whole range, and a named band gives it something to anchor to. The built-in judges use five bands, and a rubric you write may use any number of at least two.

### A stance that looks for flaws

The built-in judges take an adversarial reviewer stance: the grounding judge defends the position that the output is not grounded until the source shows otherwise, the instruction-following judge is a compliance auditor, the reasoning judge an adversarial logician, the completeness judge a coverage auditor. The stance is wording you can replace through the rubric. This document makes no claim about how many bands stricter it grades: calibrate a judge on your own traces, by reading its assessments against work you have graded yourself.

### Reason before the verdict

The feedback form of each built-in judge writes its reasoning first, then the evidence lists of its type, then the band, then the feedback. Field order in the structured output is generation order, so the model commits to a band after it has written its reasons.

| Judge | Evidence lists it writes |
|---|---|
| Grounding | grounded and ungrounded claims |
| Instruction-following | requirements met and missed |
| Reasoning | strengths and weaknesses |
| Completeness | aspects covered and missing |

### Nothing is invented

A missing band, a band outside the rubric, blank feedback that the rubric requires, an unresolved model, a provider failure or a missed deadline each end as a failed assessment with a reason. An exact tie between bands ends ungradable. None becomes a score. Blank required feedback is asked for once more, as a malformed answer, before it fails with `:missing-feedback`.

### A grade always comes with its coverage

A mean over the assessments that happened to succeed hides the ones that did not. Performance and composites always report how many assessments were expected and how many were scored, failed, ungradable or pending.

### Performance per node version

A node's effective definition when it ran is its **node version**. Every outcome of every assessment contributes to performance for that version, always, and versions are never blended. Alerts on a threshold crossing are opt-in per judge, record once per episode, and start nothing: no training runs because a threshold was crossed.

### Purposes

A learning judge's scored outcomes with feedback also emit the legacy `:judge/score-emitted` event, so the consumers that read it (Living Descriptions, harvest, the consolidator) are unchanged. A monitoring judge's results never reach them, because its feedback may be absent. Replacing the legacy event everywhere was rejected for now.

### Patterns evaluated and deferred

| Pattern | Why it is deferred |
|---|---|
| **Ensemble and hierarchical verification** | A second model verifying each verdict roughly doubles inference cost per assessment. Deferred until judge variance is shown to limit what the learning loops can do. |
| **Logprob extraction** | Needs provider logprob access, which is not uniform, and fits token-space scores, not a structured feedback form. |
| **Pairwise GEPA metrics** | GEPA's reflective dataset is score-based. Pairwise comparison needs a new proposer input format. |

---

## 3. What the engine guarantees

Each guarantee is exercised by a test in `components/evaluation/test/ai/obney/orc/evaluation/`.

| Guarantee | Test namespace |
|---|---|
| A judge runs as a workflow, with the rubric and the evidence on its blackboard | `judges_are_behaviours_test` |
| One assessment per subject, judge and revision; redelivery requests nothing | `assessment_lifecycle_test` |
| Three executions of a node are three assessments | `assessment_lifecycle_test` |
| An attached judge runs with the Living Description flag off | `assessment_lifecycle_test` |
| A score-only or monitoring judge never emits the learning record | `assessment_lifecycle_test` |
| An exact tie is ungradable, never a score | `judges_are_behaviours_test` |
| Work done for an assessment is never auto-assessed | `assessment_origin_test` |
| Judges on composites, delegates and the root see the whole; opt-in family and child evidence | `behaviour_judging_test`, `judged_composites_durable_test` |
| A composite is recorded once per subject, with coverage | `behaviour_judging_test` |
| Performance per node version, alerts once per episode | `node_performance_test` |
| Published versions are judged through the draft node's attachments | `published_versions_judged_test` |
| Every example in the judge docs runs | `docs_examples_test` |

---

## 4. Building a judge

### A built-in judge with your own rubric

The cheapest route. Keep the built-in behaviour and evidence lists and replace the rubric, the criterion alone (`:criteria`) or the whole rubric:

```clojure
;; docs-example: criteria
(def criteria-judges
  {:grounded {:type :grounding
              :criteria "Every claim must trace to a field of the ticket."}})
```

### A custom workflow

When you need your own evaluation (a deterministic rule, several steps, external tools), write a workflow and name it with `:type :custom` and `:sheet-id`. It reads the evidence by declaring blackboard keys and writes a `:band` (with a rubric) or a `:score`:

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

### Running a judge inline

A judge is an ordinary workflow, so you can also `:delegate` to it inline and branch on what it writes with a `condition` node. That is plain workflow composition outside the assessment record: nothing is requested, stored or counted in performance.

---

## 5. What reads judge output

**Living Descriptions** and **harvest** read `:judge/score-emitted`, which only learning judges emit. See [`LIVING-DESCRIPTIONS.md`](LIVING-DESCRIPTIONS.md).

**GEPA** scores candidates with `make-judge-metric`, which calls the built-in judge functions directly on a stable grading task and returns `{:score :feedback}` with the weakest dimension's feedback first. It does not read or write assessments, so purposes do not apply to it. The judges grade against a stable task, not the candidate instruction. If they graded against the candidate instruction, instruction-following would reward a deliberately bad instruction the producer faithfully obeys. See [`GEPA-GUIDE.md`](GEPA-GUIDE.md).

---

## 6. Not yet provided

- **Capacity and recovery for assessments:** limits, worker claims and drain on restart.
