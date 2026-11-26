# Core ORC knowledge

This library distills behavior-tree theory, implementation inspection and runtime experiments into portable authoring guidance. It requires no private applications, talks or local programming guides. Use the consuming revision's source for API guarantees; distinguish demonstrated behavior from assumptions. The core scope is setup, trees, contracts, semantic decisions, custom tools, reusable specialists and operational evidence.

## Goals, control and recursive decomposition

[Behavior Trees in Robotics and AI](https://arxiv.org/html/1709.00084v6), particularly §§3 and 13–20, supplies node algorithms, explicit success conditions, backchaining, memory, safety and granularity. The [BT introduction](https://robohub.org/introduction-to-behavior-trees/) explains ticks and modular control. A successful subtree establishes an observable postcondition. Sequence required prerequisites and effects; order eligible alternative methods in a fallback; check an already-satisfied goal before repeating work when appropriate. Expand a prerequisite into a subbehavior when it has meaningful intermediate evidence, independent recovery or reuse. Stop at a bounded model judgment/action or deterministic capability.

A semantic router chooses an eligible capability from described alternatives. A fallback tries methods that can establish the same parent goal. A selected method failing does not authorize an unrelated capability. Ambiguity, no applicable method, unavailable evidence and provider failure need distinct outcomes where their recovery differs. See [BT assembly](orc-design-tree/references/bt-assembly.md) and [specialist design](orc-design-tree/references/specialist-design.md).

An ordinary tree can bound repair structurally with guarded methods for the same goal. The [two-attempt example](orc-design-tree/references/bounded-repair.md) permits one planned revision. When the next useful behavior depends on new evidence, use [authored goal solving](orc-design-tree/references/authored-goal-solving.md) and the [bounded controller](orc-compose-specialists/references/authored-controller.md): select and revisit known specialists, preserve the original obligation, and stop with an honest business outcome before the inherited engine cap. A code function returns writes; a status-looking return map does not control node execution. The supported repeated-child condition puts `:on-fail :running` inside `:check`.

Assess completion after an entire subtree or parallel join using the parent goal, actual outputs, current evidence and projected effects. A finite decision can choose satisfied, revise-owner, consult-known-specialist, wait or handoff; an ordinary LLM supplies new critique only when needed. Retain healthy work and reconcile completed effects before revisiting an obligation. The [Voyager/MASS reading](orc-design-tree/references/agent-design-literature.md) separates execution feedback, reusable capabilities and useful topology from unsupported claims that more agents, recursive composition or optimizer machinery automatically improve results.

Classical reactive ticking is not an implicit guarantee of ORC's event-driven runtime. A workflow tick is not a continuous polling/preemption controller; design later ticks/triggers for changing facts and verify cancellation separately. At the inspected pin, sequence continues on `:partial` and remains partial overall; fallback stops on `:partial`, proceeds after `:timeout`, and stops on `:blocked`. Parallel starts children concurrently, has distinct success/failure policies and checks failure first. These are revision-specific facts, not universal BT laws.

## Contracts and reusable specialists

The blackboard is a typed interface. Describe fields' meaning, provenance, freshness and constraints; use structured Malli maps for model-produced structures. Avoid `:any` as a substitute for contract design. Define supported node reads/writes and useful specialist results. Keep parallel writes disjoint, including explanation fields; preserve missing and partial output evidence. [Focused context flow](orc-blackboard-contracts/references/context-flow.md) retains the goal, relevant prior draft, actual feedback and qualified evidence. A typed missing-information request binds an answer to the original obligation and specialist to resume. Narrow context must remain sufficient; omission of a decisive fact is not an optimization.

Delegation starts a child tick with an isolated blackboard and lineage. Build dependencies from leaves upward and pass actual sheet IDs into delegates. The seam does not invent renaming, coercion or authority. A child can delegate again and remain useful to another caller without knowing its next node. Put domain focus in described runtime inputs where reuse is intended. See [blackboard contracts](orc-blackboard-contracts/SKILL.md) and [composition](orc-compose-specialists/SKILL.md).

Parallel per-item work needs primitive leaves with explicit outputs and stable source IDs. Do not infer identity from compacted result position. Inspect failed-item events; use a supported aligned-failure option only after verifying it. Human approval and external waits belong at explicit application boundaries, rather than being silently assumed inside one extraction tick.

## Models and semantic decisions

Ordinary LLM leaves perform bounded generation/judgment with typed outputs and per-node model configuration. Historical experiments found missing input descriptions on older function-mode paths. The frozen ORC `9f7c27357b9f9bcf765fbd86baaedf5911261cd8` / SIO `a6013e524f36b0b1e9e49cc270c7e1de8a097c36` input-field-guide stack now carries shown nested meanings in marker and function requests and native decision question instructions. See [input-field guide](orc-model-sio/references/input-field-guide.md) for exact scope, absence/null behavior and the separate marker scalar limitation. Preserve historical findings at their old pins; inspect the actual consuming mode rather than carrying an obsolete omission workaround forward. Choose supported sampling options per model. Durable node I/O is not the exact outgoing HTTP body: distinguish transport capture, parsed values and provider processing.

Decision models such as Jev offer supplied finite choices, boolean assessments and scoring contracts. The native state/questions protocol uses a registered `:protocol :decision` provider for supported boolean/Choice outputs; a chat model-ID substitution is insufficient. Native Score remains a custom-client contract. Preserve raw assessment separately from host policy and observed effects. Batch independent questions about one snapshot; sequence new decisions on evidence from earlier actions. Consume only selected-capability hypothetical argument heads. Ordinary LLMs or computation supply unrestricted queries, notes, numbers and nested structures. See [decision models](orc-decision-models/SKILL.md) and [model/SIO](orc-model-sio/SKILL.md).

For optional Jev 1.14, Luna Decisions and Clef configurations, read [native provider selection](orc-decision-models/references/native-provider-selection.md). Model-level feature flags do not establish endpoint behavior. Effective context limits can be smaller than advertised windows; a complete packet in the HTTP body is not proof the model read it. Choose a provider that can use the required evidence rather than silently removing facts. Live validation of these alternatives remains separate from source/catalog support.

At merged ORC `1851212396cced2fc4533fda30b824bd4b691390`, `llm-condition` supplies a boolean predicate and `llm-decision` supplies one finite answer retained as data. The earlier metadata mismatch is repaired. Valid false/none succeeds on the decision leaf; malformed/provider failure remains failure. Do not add a prose rationale write to this one-answer contract. Verify current positive/negative and failure evidence in the consuming revision.

Never classify meaning with regex, word lists or phrase matching in human/AI messages. Never manufacture knowledge work, clarification, completion or recovery with canned prose. Code may parse documented literal grammar, check exact IDs, validate schemas, enforce authorization or calculate. Semantic applicability, intent, grounding and completion need suitable model judgments on actual evidence.

## Tools, observations and recursive actions

Present model-visible capability IDs, purpose/applicability, described argument/result schemas, limits, effects and success evidence. Registry metadata alone does not establish what the model sees. The host maps selected IDs to authorized implementations and validates the selected schema/joint proposal, including source membership and freshness. Native declared contracts enforce arguments/results consistently across code/tool/researcher calls; the host still enforces authority and useful domain outcomes. Purpose/applicability must be included separately in actual model context.

An authored `tool` calls one fixed capability with flat read arguments and one output; a `code` action uses the guarded built-in caller for nested arguments or explicit multiple calls. A delegate can implement a tool requiring several actions and local recovery. A result-dependent chain prepares new arguments or makes a fresh decision on the actual observation. Independent actions may run in parallel with compatible effects and separate outputs; already-satisfied goals may require zero actions. Action count follows the goal and dependencies, rather than a one-call convention or universal stage template. See [orc-tools](orc-tools/SKILL.md).

[ReAct](https://arxiv.org/html/2210.03629v3) informs action–observation updates. [CodeAct](https://arxiv.org/html/2402.01030v4) motivates executable composition and feedback-driven correction. [Voyager](https://arxiv.org/html/2305.16291v2) illustrates verifying reusable executable capabilities. Distill these lessons without treating them as ORC APIs, copying prompt templates or transferring benchmark claims.

Checkpointed researcher execution and nested delegates are separate recursion mechanisms. Callers need explicit checkpoint safety and host-enforced replay/deadline handling. Quoted generated code can be reconstructed; temporary SCI functions are not automatically durable tools. The emitted-tree compiler has a narrower palette than the hand-assembled DSL. Read [researcher boundaries](orc-tools/references/researcher-tools.md) before promising generated behavior or restart safety.

## Events, effects and operational proof

Grain provides commands, events, processors, periodic triggers, read models and queries. Domain commands own writes behind an executable authorization boundary; the event store assigns event IDs/timestamps during append. Direct command/query processors validate full UUID/offset-date-time envelopes and invoke handlers without evaluating registry `:authorized?` metadata. Enforcing adapters evaluate it; direct host calls need an explicit guard. Claims alone do not enforce access. The [Grain example](orc-grain-application/references/contracts.md) shows guarded command → event → projection → query forms. Direct v3 operations are tenant-scoped. An action return is not proof an event landed: query projections and inspect originating events. Bind operation IDs to accepted payloads; sequential deduplication does not prove concurrent exactly-once behavior. Reconcile uncertain external effects before repeating mutations.

For standalone projects, [runtime setup](orc-runtime-bootstrap/references/runtime.md) supplies public Git dependencies and LMDB JVM flags. Load registrations, then start store/cache and tenant poller; decide registered periodic ownership explicitly with append/tenant-enumeration capabilities. Current ordinary execution leases distinguish healthy owned work from abandoned frontiers; still configure scheduler ownership and verify actual restart/recovery. The historical healthy-leaf duplication proof belongs to its older revision, not current setup advice. Code effects need capabilities in the asynchronous poller context. Stop schedules before quiescing work and closing poller/cache/store/pubsub. Inline canonical value storage is the default; explicit file-store mode stores all values externally with no automatic threshold or fallback and requires its backend package. Prove the selected setup in the consuming application.

See [runtime setup](orc-runtime-bootstrap/SKILL.md), [Grain application](orc-grain-application/SKILL.md) and [observability](orc-observe-debug/SKILL.md). Follow root/descendant ticks, attempts, node I/O, model bodies and caller results. Use `get-family-usage` for registered provider calls across descendants; custom native clients need explicit accounting. Recovery reconstructs the same store/processors and preserves original identities. Keep telemetry bounded outside execution.

## Judging as a composed behavior and monitoring contract

A quality judge assesses recorded work against a described criterion and finite
bands. Grounding, instruction following, reasoning and task coverage ask different
questions. A parent or selected-group judge needs its own root-goal rubric and
evidence; averaging child grades does not establish assembled task success.
Read [judging](orc-judges/SKILL.md) and its focused references for custom delegated
judges, independent parallel checks, calibration, decision-model scoring and reports.

[G-Eval](https://arxiv.org/abs/2303.16634),
[MT-Bench](https://arxiv.org/abs/2306.05685),
[Prometheus 2](https://arxiv.org/abs/2405.01535),
[FActScore](https://arxiv.org/abs/2305.14251) and
[ChatEval](https://arxiv.org/abs/2308.07201) inform criteria, bounded assessment,
feedback, fact decomposition and evaluator composition. Their benchmark outcomes
are not ORC accuracy guarantees. Probe boundary cases, verbosity, false action
claims and [evaluator-directed injection](https://arxiv.org/abs/2504.18333) in
untrusted artifacts. Inspect evidence extraction and the real provider body before
attributing disagreements to model quality.

A model selects a band; code validates the complete verdict and derives its score.
Jev's [native Score](https://docs.typesafe.ai/primitives/score) can assess without
prose through a code capability. Keep the maximum-probability band separate from
the provider's expected position, preserve quantized probabilities without silent
normalization, and leave exact maximum ties ambiguous. Score-only monitoring is
valid; feedback-dependent improvement requires real feedback.

Monitoring after completion and inline quality decisions have separate effects.
An inline checker can route repair before delivery; a background judge records
observations about work already performed. Durable reporting declares selected
subjects and expected judgments, preserves actual source/artifact/effect evidence,
and exposes pending, failed and ungradable coverage alongside successful bands.
Persist through Grain and reconstruct projections in a fresh process. Native
attachments, worker admission and restart recovery must be verified independently
of an application-owned reporting layer.

At the historical judging campaign's inspected pin, live built-in and custom delegated judges could persist results
under controlled enablement. Native attachments rejected composites/delegates,
required the Living Description flag, collapsed repeated node executions under one
score identity, and lacked the approved durable capacity-queue contract. Shipped judge
workflow construction also exposed recursive-schema registry loss. Read the
[source-status ledger](orc-judges/references/source-status.md); portable guidance
distinguishes these observed limitations from intended repairs.

## Evidence and limits

Source inspection, recorded upstream tests, independently executed deterministic integration, live ordinary-model experiments and held-out semantic evaluation are different evidence levels. Synthetic clients establish native request/validation/dispatch mechanics, not Jev accuracy, latency, cost or calibration. Live calls with synthetic domain tools prove the observed chain, not an external service integration. Generated-transform proof does not establish arbitrary generated-tool safety.

Prior core experiments exercised real ordinary models, semantic routes, delegated tools, parallel specialists, provider request captures and replay. Native decision experiments used synthetic clients over real ORC/Grain, including finite arguments and nested delegation. These informed false-success recovery safeguards and payload-bound replay guidance. Raw provenance and historical archives remain optional audit material; authors need neither to use these skills. New experiment reports must state their evidence tiers and verification gaps precisely.

Implementation anchors: ORC main `490816267b76b0428c53437052980a25f5905209`; Grain `dbf5b5229aad09785e40f71e96adf65c90f971d5`; SIO `d6d27f9bd59e2ac071396fc7942eabcdd4eabd12`; litellm-clj `0570e9459b8751d4a0c3dc1cd48595804426a84d`. Recheck target versions. Public protocol references: [MCP tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools), [Jev Choice](https://docs.typesafe.ai/primitives/choice).

## Revision context

The cited `4908162` observations are historical. Merged decision/tool APIs at ORC `1851212396cced2fc4533fda30b824bd4b691390` and the later authored/input-guide stack are distinguished in [the revision guide](SOURCE-STATUS.md). Preserve original experiment claims at their original pin; use the consuming checkout and current owning skills for integration.

## Integrated decisions and accountable tools

Bounded semantic assessments are data-producing work when a valid no/none is useful. Predicate status is a separate control contract: true succeeds and false fails. ORC now exposes both directly, allowing ordinary LLM and registered native boolean/Choice providers to share the execution policy. Model capability determines whether a head can select finite supplied arguments or must generate unrestricted values. Keep explanations separate from finite answers; no prose is fabricated for decision models.

Tool selection descriptions, argument/result schemas and effect authority answer different questions. Show capability purpose and argument meanings in actual model context, enforce declared representation at the invocation seam, and keep permissions, freshness and projected completion with the host. A tool leaf names one authored capability; code constructs dynamic/nested arguments or composes calls; delegates package reusable multi-action goals. Sequence result-dependent work and parallelize genuinely independent goals with isolated outputs. Family budgets and usage follow recursive execution, but external effect reconciliation remains separate.

Read [current decision/tool revision](SOURCE-STATUS.md) for source contract and retained limits. This update does not imply judge engine repairs or measured semantic accuracy. Historical campaigns keep their original source/model/evidence identities.

## Actual completion remains an independent obligation

Merged tool and decision paths ran live, including dynamic arguments, finite IDs, recursive specialists and independent parallel observations, with fresh-process domain replay. A real filesystem-MCP test nevertheless completed with inadequate evidence because generated selection used a filename/extension heuristic; a separately guided consumer passed. This illustrates why semantic relevance and final task completion need their own contracts even when invocation, types and checkpoint receipts work.

In the merged-revision campaign, shared model budget admission prevented an excess descendant call. At that pin, cancellation exposed missing child traces and interrupted root publication/delivery after commit; ordinary generation completion events retained configured rather than resolved model identity. Preserve those findings without assuming they reproduce in a later checkout. Retain raw terminal facts and predictor/provider evidence; do not invent normal completions or label timeout as prompt cancellation. See [tool evidence](orc-tools/references/current-proofs-and-limits.md) and [decision evidence](orc-decision-models/references/current-proofs-and-limits.md).

## Recursive researchers and design context

[orc-repl-researcher](orc-repl-researcher/SKILL.md) distills adaptive specialist placement, supported programming palettes, typed generated contracts, dependency-sensitive parallel work, exact observations, focused repair and durable ownership. [Researcher pattern reuse](orc-researcher-pattern-reuse/SKILL.md) covers configured catalogs/classification/R-inject and the evidence required before claiming learning or callable promotion. Recursive mode is primary. Public literature is distilled in [research foundations](orc-repl-researcher/references/literature.md); private projects or talks are not prerequisites. Current source gaps are explicit in [source status](orc-repl-researcher/references/source-status.md). A successful tick, a good-looking tree or a description version increase does not establish useful task completion or improved later work.

## Complex authored agents: evidence and remaining limits

An authored tree can coordinate parallel specialists, wait for a later user turn, revisit a responsible specialist with new evidence, retain independent artifacts, and drive dynamic versioned code edits through real tests. These mechanisms have been exercised with ordinary generation and native finite decisions. They do not establish general superiority over an agent loop, or make semantic completion automatic.

Three contract lessons matter beyond schema shape:

- **Evidence acquisition:** distinguish not requested, actually retrieved, observed unavailable and superseded. Give stopping/route assessments the admitted capabilities needed to resolve material open questions. A cautious negative answer can still be incomplete investigation.
- **Execution phase:** a historical proposal, a committed effect and a completed conversational turn are different facts. Supply actual host-projected receipts, current invocation phase and the completeness scope of the ledger. Do not ask models to infer effect validity from ambiguous counters or contradictory unlabelled prose.
- **Applicability:** a document version, an active policy version, a future condition and an already-observed revocation are different relationships. Preserve their authority and temporal scope. An available source in the request does not guarantee correct interpretation. A flawed critic can contaminate synthesis and a subsequent checker; checking alone is not independent proof.

Held-out authoring revealed that a plausible tree may hide argument choices in code, prescribe the demonstration itinerary in a prompt, or continue after semantic settlement. Review actual responsibility boundaries and generated arguments, not only the final answer. See [authored goal solving](orc-design-tree/references/authored-goal-solving.md), [evidence closure](orc-design-tree/references/evidence-closure.md), [context flow](orc-blackboard-contracts/references/context-flow.md), and [finite rejection diagnosis](orc-decision-models/references/tree-patterns.md).

Preserve first submissions and failures. Count assisted contract repairs separately from held-out successes. Fake executor tests prove control and transfer, actual strict compilation proves representation, real model/tool runs establish observed task behavior, and cold projections establish durable reconstruction. None alone proves the others. Broad cross-domain reliability, hostile-source resilience, crash-atomic external effects and concurrent exactly-once behavior remain separate verification obligations.


## Assessment readiness and specialist discovery

A judge can be a task-specific reusable behavior: gather exact effects, delegate criterion checks and reconcile a described band plus useful feedback. Keep the producer's goal fixed while its instructions vary. A custom metric can invoke that behavior directly; generic built-in dimensions are optional. [Judge readiness](orc-judges/references/gepa-readiness.md) separates artifact selection and evaluator failures from model grading quality; reflection options and adapter limits are described there.

[GEPA readiness](orc-judges/references/gepa-readiness.md) states current reflection behavior and adapter limits; the convenience judge metric's output extraction is the remaining limit. Optimization quality is task-specific. Explicit job-specific judge trials delivered complete evidence and persisted feedback; one correct artifact was still falsely graded partial. A separate fresh author successfully set up a two-case shipment assessment. Neither is a broad calibration study, and successful setup does not erase the false negative.

[Specialist discovery](orc-design-tree/references/specialist-discovery.md) separates registry eligibility, bounded graph/ColBERT retrieval, semantic choice and actual delegated quality. Eligibility before ranking prevents forbidden candidates from starving the shortlist; it does not solve relevance misses. In a small live trial, a comparison specialist correctly distinguished proposal from final authority. A numeric goal remained unresolved when retrieval excluded the calculation specialist. Do not label an honest unresolved result successful goal completion or infer superiority over direct selection.

## Authored continuation: mechanisms and observed boundaries

Nested composition, reevaluating a goal, revisiting a specialist, waiting for an external answer and recovering an interrupted process are separate mechanisms. A reusable child tree does not by itself install a trigger to resume a pending user question. Describe the owner and trigger of each continuation. Reactive and memory BT semantics also differ: see [Behavior Trees in Robotics and AI, §3.2](https://arxiv.org/html/1709.00084v6). Confirm the consuming ORC node's actual tick behavior instead of transferring robotics traversal assumptions unchanged.

[Voyager's skill retrieval and iterative prompting](https://arxiv.org/html/2305.16291v2) support carrying actual prior artifacts, observations and critique into correction. [MASS](https://arxiv.org/html/2502.02533v1) distinguishes prompt and topology choices; adding more specialists is not itself a quality guarantee. These are design inputs, not reproduced ORC benchmark results.

Pinned general-agent source provides useful comparisons. [Hermes child execution](https://github.com/NousResearch/hermes-agent/blob/20acba0376944d11ee970d6e734afd54bfd8045c/tools/delegate_tool_child_run.py#L479-L640) retains execution status, exit reason and schema verdict separately and can attempt schema correction. That does not establish semantic truth. [OpenClaw's agent loop](https://github.com/openclaw/openclaw/blob/3840d14ad2a369c20fbeaf3d1af737b39f2ed404/packages/agent-core/src/agent-loop.ts#L343-L475) continues from tool results and checks steering before exit; [its yield tool](https://github.com/openclaw/openclaw/blob/3840d14ad2a369c20fbeaf3d1af737b39f2ed404/src/agents/tools/sessions-yield-tool.ts#L99-L178) needs runtime-owned pending work. The reusable lesson is explicit continuation ownership, not that one product lacks focused specialists or durable delivery. No comparative runtime superiority has been demonstrated here.

An authored consent experiment showed why context and topology need separate inspection. Adding correctly transmitted approval policy did not make a selection path restricted to already authorized effects establish a pending proposal. A later assisted design separated desired-action selection, host prerequisite checks, durable pending dependency, actual question assessment, matched structured answer and effect revalidation. It completed the scoped consent/reservation mechanism while retaining an independent prior credit. Its verbose response and final partial semantic assessment remained quality gaps. Neither good schemas nor a working command guard proved the whole customer experience correct. Follow [context flow](orc-blackboard-contracts/references/context-flow.md) and [authored specialist control](orc-compose-specialists/references/authored-controller.md).

A separate three-call authored judge → owner → same-judge experiment rejected a shape-valid artifact on a substantive capacity constraint, passed exact criterion feedback and current evidence to its owner, and accepted the corrected artifact under the unchanged rubric. The accepted independent artifact was retained; that isolated experiment did not execute a parallel external effect. See [judge composition](orc-judges/references/compose-judges.md). Inline judging can control the current path; asynchronous monitoring remains a separate lifecycle.

The repeated judge also exposed a result-trace attribution defect at the inspected local ORC pin: combining a tick-local write index across a family made same-node child outputs collide. Canonical tick-specific writes matched the actual model outputs and survived cold replay. Use [observability guidance](orc-observe-debug/SKILL.md) to verify the current checkout and retain exact tick/node/context attribution, especially before deriving feedback or training datasets from execution history.

Fresh authors then built three distinct consumers from the candidate skills: a conversation with independent billing and scheduling specialists, an evidence investigation with a finite checker, and a file-reconciliation workflow with guarded tools. Real calls and canonical replay established observed two-turn consent, retained work, stale-write reconciliation and substantive correction. Setup assistance and explicit application continuations remain part of those results, so they are not an unassisted success rate.

The unavailable-evidence case illustrates a useful distinction: an author marked an answer complete despite unknown authority timing; its independent checker rejected that conclusion. A shared experiment allowance blocked correction before HTTP. An explicitly added continuation then produced a qualified answer and precise missing-information question under the unchanged instructions. It exceeded the original authored pass description, so this establishes the observed correction, not automatic recovery within the original bound. Ensure the correction owner receives the selected criterion's meaning, not merely an enum label whose explanatory metadata might not be serialized.

Small completion allowances can also defeat otherwise valid structured generation. In the observed reasoning-model call, a 3,500-token cap ended with incomplete JSON after 3,296 reasoning tokens. A later request with enlarged headroom completed; all accepted writes matched canonical events. This is a configuration and observation lesson, not a reason to weaken output contracts or clean model text. See [completion budgets](orc-model-sio/references/reasoning-and-completion-budgets.md).
