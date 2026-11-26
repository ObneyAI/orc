# Rubrics and calibration

## Define what a grade means

Write the task criterion independently of the model and band mapping. Grounding
asks whether substantive claims follow from supplied sources. Instruction following
checks explicit directives and prohibitions. Completeness checks required coverage.
Reasoning checks whether conclusions follow from premises. These dimensions can
disagree: an answer may cover every topic while inventing facts.

For a holistic judge, define success against the root goal and actual effects.
Explain how consequential failures affect the selected band. Do not substitute
an average of many easy checks for a critical missing requirement.

Give each band conditions a reviewer can apply to evidence. Distinguish central
errors from minor imprecision. Specify whether unsupported inference, contradicted
facts, unproven action claims or missing required components cap a grade. Include
representative examples when they clarify boundaries; keep examples separate from
the artifact currently assessed.

## Keep a clear verdict contract

Return a selected enum band, relevant evidence and optional or required feedback
according to the consumer. Validate the entire expected output, not merely a numeric
grade. Derive the stored unit score deterministically. Malformed output is a failed
assessment and insufficient evidence can be ungradable; neither is automatically
band one. A genuinely observed failure can warrant band one.

Version criterion, bands, evidence extractor and model configuration. A reference
answer should represent reliable facts and task requirements, while allowing other
valid solutions. Similarity to one prose answer is not a general correctness test.

## Build meaningful evaluation cases

1. Gather representative real artifacts and effects. Cover common paths and rare
   consequential failures, not just polished successful examples.
2. Establish expected judgments independently of the evaluated model. Label origin
   explicitly: human adjudication, independent expert review, deterministic fact,
   or construction expectation. Do not rename construction expectations gold.
3. Preserve disagreements and legitimate boundary ranges. A fluent band-four
   fixture may meet band five; do not force an exact label to fill a table.
4. Hold back examples used to tune rubric wording. Reusing the same cases for
   tuning and acceptance only measures fit to those cases.
5. Inspect the exact evidence packet and serialized request before interpreting a
   disagreement. An extractor that finds no operation receipts cannot support the
   labels from an old fixture that merely claimed those receipts existed.

Use exact band agreement, distance between bands, confusion by band, and agreement
with documented acceptable ranges. Report case counts and label quality. Repeating
the same case measures stability; it does not establish correctness. Agreement
among models is not independent ground truth.

## Probe predictable weaknesses

Compare the same underlying work with irrelevant verbose paraphrases, reordered
evidence, changed model attribution, omitted relevant conversation context, and
evaluator-directed instructions embedded in the assessed artifact. Keep the trusted
rubric unchanged. Schema constraints restrict output shape, not semantic obedience.
Do not add keyword filters as a semantic defense.

For pairwise judges, swap candidate positions. For reasoning, provide independently
verified calculations or execution results where appropriate. For tools, contrast
claims of completion with actual recorded effects; distinguish no effect from
unavailable evidence. For grounded forecasts, separate historical observations from
future guarantees and assumptions.

Track provider failures, invalid responses, latency, tokens, cost, retries and
feedback usefulness alongside accuracy. A client-side validation rejection is not
automatically a provider failure: inspect the complete wire response and numeric
representation before assigning the cause. Compare models on equivalent evidence and
criteria. Confidence or a lower price is not evidence of suitability.

## Research basis

- [G-Eval](https://arxiv.org/abs/2303.16634) motivates explicit criteria and
  structured grading; its benchmark results do not establish universal reliability.
- [MT-Bench and Chatbot Arena](https://arxiv.org/abs/2306.05685) provide controlled
  position, verbosity, self-preference and reasoning bias probes.
- [Prometheus 2](https://arxiv.org/abs/2405.01535) separates rubric-specific direct
  assessment from pairwise preferences and includes verbal feedback.
- [Judge prompt-injection research](https://arxiv.org/abs/2504.18333) motivates
  adversarial artifact tests. Reported attack rates belong to the paper's settings.

These are methods to adapt and test, not accuracy guarantees for an ORC deployment.

## Distinguish valid grading structure from correct assessment

A live task-specific assessment received the complete artifact, authoritative receipt and described rubric, yet assigned a partial band for a detail already present in its own cited evidence. Structured output and exact wire-to-event agreement established delivery, not correct interpretation. Preserve such false negatives alongside correctly detected defects. Do not change artifact wording to fit an evaluator and call that calibration; test a principled rubric or checker change on separate cases. A small successful demonstration likewise does not establish reliable fitness for optimization.


## Separate valid records from supported claims

Exact native source copies and a schema-valid band can coexist with an unsupported conclusion. Check each claim's entity, population, unit, time, issuer and supplied relationships. Different numbers do not alone establish a contradiction; an explicit same-population conservation rule can establish one. Keep genuine same-claim disagreement rather than training a judge to dismiss all differing quantities. Code can enforce literal identity/order/presence; semantic entailment remains assessed work.

One live bounded review preserved complete records but accepted an unsupported relationship between different stages. Another retained a weaker ambiguity. Same-model producer/judge agreement did not resolve either issue. This is evidence against treating agreement as calibration; it is not a model ranking or proof that every judge is unreliable. Preserve the artifact, original accepted verdict and independent review separately. Do not rewrite a held-out rubric after seeing the answer or force a repair merely to exercise a branch.

Freeze new contrasting supported/unsupported/uncertain stimuli and an independent artifact-first assessment before model verdicts. Keep reference labels and construction scripts outside runtime inputs. Authored stimuli test judge calibration; they do not measure live producer quality. A deterministic loopback verdict establishes only typed mechanics. For an authored artifact, bind the subject to the actual code writer or canonical seed provenance, not a fictitious model producer. Inspect the actual runtime code context rather than assuming sheet identity is top-level.

First-pass acceptance supplies no evidence that feedback improves a replacement. To test correction, retain a natural failed artifact, deliver its actual grounded discrepancy to the responsible owner, generate a bounded revision and judge its independently bound replacement. Label host-enforced literal-fidelity rejection separately from semantic-judge-driven revision. Do not supply a corrected payload or desired selected-record list as alleged reasoning feedback. Preserve useful unresolved outcomes and measured false acceptance/rejection before using a judge as a GEPA objective.

## Validate the judge and the reference independently

A domain-specific judge can choose a plausible band while miscopying its evidence. Enforce the described verdict's meaning beyond JSON shape: exact quoted spans must occur literally in the bound artifact, and cited source/revision/backend identities must match the supplied observations. Preserve the raw proposal and a failed assessment when those checks reject it; do not assign a bogus zero, normalize capitalization or punctuation to rescue a quote, weaken literal checks, or add a model-specific parser. A successful provider node is distinct from an accepted assessment.

Freeze construction labels and an independent artifact-first adjudication before inspecting model verdicts; keep labels and adjudication outside evaluator inputs. Constructed references are not gold. Check every prerequisite in positive controls as carefully as in negative cases: a document covering an interval may still lack evidence of the registration required for effective authority. Retain disagreements and ambiguity, including inconsistent treatment of the same prerequisite across cases, rather than treating raw label agreement as accuracy.

One bounded application-owned calibration checkpoint captured eight real Luna responses: two development and six heldout. All eight passed wire-schema, native-packet and canonical-proposal comparisons and matched the constructed reference bands. Only six assessments were scored; two failed exact artifact-span checks. Blind heldout review also assigned band two to one constructed band-three positive case because required registration was unestablished, while the grader noticed that prerequisite elsewhere. The original labels, raw responses and failed outcomes remained frozen. This is evidence about a small calibration mechanism and its limitations, separate from the earlier producer/model checkpoints; it proves neither judge reliability, successful inline gating, native asynchronous attachment nor GEPA readiness. For event attribution, distinguish [actual completion event IDs from correlation tokens](../../orc-observe-debug/references/evidence-path.md#completion-event-identity-and-correlation-tokens).
