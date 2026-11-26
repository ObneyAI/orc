# Evidence, improvement and promotion

## Useful pattern advice

A portable description records a capability and its context, not a magic task-specific answer. For example:

```clojure
{:capabilities ["Extract typed quantities and qualitative states from supplied passages."
                "Compute source-grounded rollups with code."]
 :strengths [{:trait "Semantic extraction precedes deterministic arithmetic."
              :good-when "Source records express quantities in prose and the result needs exact totals and provenance."
              :recommended-pattern "A sequence extracts typed per-source facts with an LLM, then computes totals with code."}]
 :weaknesses [{:trait "Asserted arithmetic and lost provenance can hide a bad result."
               :avoid-when "A summary merely claims a total or discards the source records."
               :recommended-alternative "Retain ids and exact quotations; compute from validated typed facts."}]
 :avoid-when ["Inputs already contain complete structured facts; use deterministic transformation directly."]
 :summary "Human-authored advice; live usefulness and learning provenance are separate evidence."}
```

Validate the actual description schema when recording this body. Evidence counts/confidence must describe real evidence, not make an authored fixture appear harvested. A source pattern must include its full executable source if it is offered as a worked example; truncated code is not an executable proof.

Curated guidance may legitimately be retrieved before any recorded execution. Distinguish that authorship from judged episode support and successful campaign verdicts. Classification fitness measures task fit; it is not the strength's evidence confidence or an accuracy guarantee. At the inspected pin the renderer uses unconditional proven/observed/shipped headings and fences any recommended pattern as Clojure, even when its recorded evidence count is zero or its content is prose. Inspect the actual block. Keep useful curated advice, label its basis accurately, provide parseable complete DSL when promising a worked example, and put only actual historical tasks in fields presented as shipped uses. Do not repair this by inventing evidence or by phrase-matching recommendations into a content kind.

When designing from advice, check that intermediate schemas fit the task. For generated `:map-each`, cover the item key, child outputs and aggregate key in the supported schema declarations. A typed leaf alone does not type coordinator-created keys. Reusing a sequence is appropriate when it fits the preconditions; parallel work needs isolated per-item context and meaningful collection semantics.

## Complete evidence chain

| Stage | Retain and verify |
| --- | --- |
| Task | Exact goal, inputs, output contract, pin, model/options, budgets and allowed capabilities. |
| Retrieval | Indexed source bodies/versions, metadata, query and candidates. |
| Classification | Actual reranker request/response, reasoning, chosen identities or deferral and durable assignment. |
| Exposure | Injection row, candidate IDs/versions, arm, rendered block/hash and its exact presence/absence in the actual sent messages. |
| Design | Researcher reasoning, generated source, schemas, explicit choices and iteration history. |
| Execution | Phase 2 bookends, child ticks, exact node reads/writes, tool effects and completion statuses. |
| Result | Values resolved through public value-log readers and independent expected facts/effects. |
| Accounting | Every physical attempt/retry, refused reservation, response/error, usage/cost and elapsed time. |

Use `orc/get-tick ctx tick-id` and public value readers such as `orc/value-log-read-tick-events` and `orc/value-log-resolve-writes`. The latter needs the execution's completion identity, not just a blackboard key. This prevents conflating different writers, map items or successive writes. Reopen a persisted store and verify relevant projections when claiming durable evidence.

A generated-tree source alone is not execution. A successful sandbox iteration that merely emitted a tree does not mean that tree passed validation or completed. A root success flag alone is not correct task work. Require the expected output values and meaningful action evidence. If the model explains a failure, verify its account against the real error and I/O.

## Controlled reuse experiment

Start with a small task having independently known facts and deterministic computation. Freeze a corpus with explicit provenance. Use the same task, researcher contract, generation instruction, models, code/tool catalog, source pin, limits and corpus in both arms. Change only guidance exposure through the context holdout.

Keep a sequential or randomized allocation record appropriate to the design. Do not silently drop failed controls or budget-exhausted runs. Capture prompt length and token/cost differences: whole-block guidance tests a package, not the value of a single claim.

An inspected apparatus-sized pair at the source boundary in the companion reference had these results:

- Guided arm: real structural and behavioral classification selected seeded descriptions; the complete recorded block reached the model. The researcher emitted and executed LLM extraction followed by code aggregation, then inspected/finalized the stored report. Independent per-source facts, quotations and totals passed, and reopening the store reproduced the public values.
- Holdout: the same classification and task ran without the block. The researcher emitted per-record mapping trees without schemas for coordinator keys, repeatedly failed before actual child work, attempted to finalize absent data and exhausted its iteration limit. The failed control is part of the evidence.

The seeds were human-authored and closely matched the task. This establishes current mechanics and one useful guided execution. It does not establish general superiority, learning from previous execution, robustness to novel domains, reflection, harvest or a statistical effect. Test paraphrases, near-domain confounders and different goals before drawing those broader conclusions.

Later fresh-author pairs at three and five sources confirmed actual retrieval, classification and complete treatment exposure, but both arms failed before executing their generated dossier work. Retrieved advice did not supply missing coordinator schemas or correct named writes. Retain these failed trials alongside the earlier narrow success; applicability scores and complete exposure do not certify executable tree construction or task quality.

## Learning across runs requires more

An across-run claim needs every link below, with correctly attributed evidence:

1. A real execution exposes a particular useful behavior or failure. Preserve its emitted source and actual terminal result.
2. Relevant quality evidence identifies the same node/execution. For feedback-driven reflection, use a banded judge with actionable feedback; score-only monitoring does not provide that reasoning.
3. A deliberate consolidation request produces a durable body or claim change, or a clearly recorded refusal/failure/skip/unchanged outcome. Inspect selected and excluded evidence.
4. The refreshed index makes the changed version retrievable.
5. A later related task receives that exact version in its actual model request.
6. Its generated tree and trace adopt appropriate advice, and an independent task check succeeds. A confounder should avoid unsuitable advice.
7. A frozen control or claim holdout supports attribution beyond ordinary model variation or a changed instruction.

Consolidation is storage and reflection over descriptions, not model-weight training. Current request thresholds and budgets are configurable, including `:ontology/set-consolidation-threshold`, `:ontology/set-consolidation-budget` and `:ontology/set-evidence-token-budget`. Choose evidence windows for the actual model and deadline; do not copy a large source default without measuring its assembled request.

Inspect registration as well as events: a stored consolidation request does not start an unregistered consolidator. Completion/outcome queries and orphan handling provide accounting, not proof that the resulting advice is correct. When multiple researchers share a host tick, verify node attribution before combining judge feedback into a class lesson.

## Harvested advice and callable behaviors

At the inspected source boundary, default harvest eligibility requires at least ten qualified terminal occurrences, a trailing ten-occurrence score mean of at least `0.75` in each scored dimension, and each of the last five scored occurrences at least `0.75`, plus description, parent linkage and a one-time guard. `0.75` is a normalized band threshold, not a claim of 75% accuracy. Failed shapes can still be valuable weakness evidence, but harvesting a useful worked pattern should prefer terminal-success corroboration.

A successful gate creates behavioral description/catalog material with linkage and recommended source. It does not automatically register a callable runtime specialist. Proving the gate opens and proving advice helps another task are separate experiments. Label fabricated scores or occurrence fixtures as gate tests, never live quality learning.

For manual promotion, retrieve complete source and node contracts, review supported DSL/operators, repair the behavior's actual defects, verify effects and outputs on suitable inputs, then register an authored subtree. Use `:delegate` as the composition seam and describe when it fits. Test invocation and its public values after registration. Keep that executable identity distinct from its ontology advice identity.

## Literature distilled into this workflow

Recursive language models put context outside the prompt and let a model inspect it through code and recursive calls. CodeAct treats executable code as an action representation. Neither guarantees correct semantic interpretation or durable external effects. Preserve operational identities and structured evidence around model-directed actions. [RLM paper](https://arxiv.org/abs/2512.24601), [author implementation](https://github.com/alexzhang13/rlm), [CodeAct paper](https://arxiv.org/abs/2402.01030).

Behavior trees compose through success/failure/running outcomes. A sequence needs handoff preconditions; a fallback needs coherent goal and failure meaning; parallel branches need compatible effects. A memory composite's progression is not process durability. These principles guide emitted trees but do not prove an LLM's conclusions. [Behavior Trees in Robotics and AI](https://arxiv.org/html/1709.00084v6).

Voyager illustrates verified, described executable reuse: verification, storage, retrieval and actual execution are all necessary evidence. ORC description retrieval is analogous advice retrieval; it is not automatically the same executable skill library. [Voyager paper](https://arxiv.org/html/2305.16291v2), [author SkillManager](https://github.com/MineDojo/Voyager/blob/main/voyager/agents/skill.py).

Source controls for consolidation and harvest: [consolidator](https://github.com/ObneyAI/orc/blob/1851212396cced2fc4533fda30b824bd4b691390/components/ontology/src/ai/obney/orc/ontology/core/consolidator.clj), [harvest](https://github.com/ObneyAI/orc/blob/1851212396cced2fc4533fda30b824bd4b691390/components/ontology/src/ai/obney/orc/ontology/core/harvest.clj).
