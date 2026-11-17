# Goal-directed, reusable specialists

Read this when a tree is becoming a flat list of prompts, when a methodology spans several independently observable tasks, or when deciding which behaviors deserve reusable sheets. Use [BT assembly](bt-assembly.md) for control-node semantics; this reference connects those controls to recursive ORC specialist design.

For a fixed catalog that must be selected and revisited as work reveals gaps, read [authored goal solving](authored-goal-solving.md). Nested decomposition, repeated invocation, semantic repair and process recovery need separate contracts and proof.

## Design from the useful result backward

State the root goal as an observable postcondition: for example, “a grounded recommendation proposal is recorded for review with attributable supporting evidence.” Define what proves it, including its projected domain state. Ask which methods can establish that result, what each requires, and which reusable behavior can establish each unmet requirement. Expand recursively until reaching directly observable conditions and executable actions.

A conceptual goal structure might be:

```text
Achieve a grounded review proposal
  Fallback
    current proposal already meets the goal?
    Sequence: evidence-backed recommendation method
      delegate obtain attributable evidence
      delegate produce grounded recommendation
      code record proposal through a domain command
      verify projected proposal satisfies the goal
    Sequence: explicit partial-review method, if parent goal permits it
      delegate identify usable evidence and unresolved gaps
      code record a partial review request
      verify the parent accepts this outcome
```

`Obtain attributable evidence` can itself choose between existing evidence, source retrieval and an eligible alternative source; each method may delegate to smaller reusable specialists. This decomposition is driven by useful intermediate goals, including local recovery and evidence boundaries. It does not require every workflow to have the same stages or every action to become a new sheet. A partial-review method is ineligible when the root requires a completed recommendation.

The classical basis is explicit success conditions, postcondition–precondition–action modules and backchaining. Those ideas inform the design; ORC's persisted asynchronous execution determines when guards are actually checked. See the [BT textbook](https://arxiv.org/html/1709.00084v6), §§13–20.

## Give each specialist a caller-independent contract

| Contract | What to specify |
| --- | --- |
| Goal | Useful postcondition and observable acceptance evidence |
| Inputs | Exact keys, explicit Malli schemas, descriptions, required history/source/configuration, evidence freshness |
| Outputs | Exact keys, typed artifact, provenance, gaps and declared availability/completion outcome |
| Effects | Commands/tools it may invoke, effect ownership, idempotence and projected read-back |
| Failure/recovery | Inapplicable method, unavailable evidence/provider, rejected semantic result, failed effect, partial work, timeout or blocked work; local recovery bounds |
| Execution | Suitable model/capability per leaf, time/budget limits and invocation lineage needed to diagnose it |
| Information need | Typed blocking question, original obligation, acceptable answer/source/freshness and known target to resume |

A specialist should establish its own goal and return its contract. It should not know which parent node runs next or write another specialist's private keys. Supply domain focus through described inputs where the behavior is intended for reuse; retain deliberate domain-specific capabilities where the application actually requires them. Use explicit `[:map ...]` or other structured schemas for LLM-produced map contracts and verify parsed values reach the caller. A valid invocation and a well-formed artifact are insufficient when the promised goal includes an external effect or semantic usefulness.

Keep local evidence availability distinct from global work state. A specialist that owns delivery and receives only delivery evidence must not infer that cost work is missing elsewhere. Give it the full goal for constraints, plus an explicit responsibility and visibility boundary: assess its own obligation and leave sibling completion to the parent join. When its task genuinely depends on a sibling result, pass that result or a typed completion/evidence summary deliberately. Do not solve this by broadcasting every branch's context to every specialist.

Keep one leaf when the work is one bounded task and splitting exposes no useful intermediate control or reusable result. Create a subtree when intermediate results matter, methods differ, recovery can target a subgoal, or independent reuse/testing is valuable. A registered specialist may delegate to smaller registered specialists; that does not establish arbitrary self-referential cycles. Hand assembly does not require a researcher merely to call known tools. Build model decisions around semantic questions and code around exact structural/effect facts.

## Preserve semantic work at the appropriate nodes

Human-message intent, implications of conversation, specialist eligibility, grounding and the meaning of an AI response must be evaluated semantically. Do not substitute regex, keyword/phrase lists, substring matches or fixed prose templates for those judgments. Do not construct a supposedly tailored human response by selecting canned prose where the task needs actual knowledge work. Use typed LLM actions and decisions, or a suitable finite decision model with explicit criteria; route their structured results through deterministic controls.

Exact enum guards, schemas, IDs, arithmetic, literal protocol parsing and serialization remain code responsibilities. They validate or route already explicit facts; they do not decide what free text means. A declared output schema constrains the representation, while an instruction defines the semantic work. Grounded generation still requires evidence and an honest missing-evidence path. For bounded semantic routers/checks, read [decision models](../../orc-decision-models/SKILL.md).

## Register nested specialists and bind delegate seams explicitly

Design from root goals downward; implement and register dependencies from the leaves upward. Build every referenced child sheet, obtain its actual returned sheet ID, then construct the parent's `delegate` with `:target-sheet-id` set to that ID. Apply the same process at every nested level. A parent workflow definition does not register separately referenced child workflows automatically. Reuse stable registered definitions during runs; avoid rebuilding one sheet name with differing content while ticks depend on it. Use separate versioned names when independently deployed contracts need to coexist.

At each seam, declare the exact parent `:reads` supplied to the isolated child tick and exact `:writes` returned from child outputs. The inspected delegate has no arbitrary rename map or value coercion. Align keys and types, or add an explicit transform before/after delegation. Input construction normalizes keys with `name`, and completion recognizes keyword/string forms; test qualified-key normalization and collisions rather than assuming namespaces are preserved. The child has its own blackboard and lineage. Its internal results do not automatically appear in the parent's public contract. This is a data-flow boundary, not an authorization boundary: child traces still expose execution evidence according to the application’s access policy.

Prove each child independently, then prove the actual parent-to-child inputs, nested invocation lineage and projected returned outputs. Verify map versus JSON-string shape at the receiving parent. Missing writes must remain visible as contract gaps. Follow [specialist composition](../../orc-compose-specialists/SKILL.md) for implementation and current public interfaces.

## Select, consult and revisit

A known specialist catalog describes the result each behavior can establish, eligibility, required evidence and allowed effects. A semantic selector chooses from that catalog for the actual open criterion; exact ID guards dispatch it. Do not ask every specialist to independently guess whether it is responsible, and do not interpret an unrelated route as recovery from a selected effect's failure.

If A needs information, retain its partial artifact and original obligation in a typed request. B may answer that narrow request; A's next invocation receives the matched answer, relevant prior draft and actual feedback. Check invocation and source revisions before merging. Untouched keys from old specialist runs are not current results. See [context flow](../../orc-blackboard-contracts/references/context-flow.md).

Assess completion after the whole selected behavior or parallel join, using actual outputs, effects and current evidence. The controller may accept, revise the responsible specialist, consult another eligible known specialist, wait or hand off. A finite decision can make that choice without generating a prose rationale; use a generation leaf for concrete repair feedback when needed. Preserve healthy completed work and effect receipts on later passes. Scope the repeat to unmet obligations, with business bounds and explicit stopping evidence; see [authored controller](../../orc-compose-specialists/references/authored-controller.md).

## Control structure owns an honest parent goal

A fallback's branches are eligible alternative methods for the same goal. Specialist routing is a semantic assessment followed by guarded branches; each branch must establish the declared route's outcome. Do not silently try an unrelated specialist merely because another failed. A recovery that returns an empty report or facts from model memory cannot satisfy a goal requiring retrieved, attributable evidence.

Use a sequence for required ordered subgoals and parallel for independent work with separate blackboard/effect ownership. In inspected ORC, `:partial` continues a sequence and stops a fallback; determine whether downstream contracts accept partial evidence and keep completion honest. Specify parallel success/failure policies and inspect already-started sibling effects after early completion. Give reasoning and artifact outputs separate keys per concurrent writer. For parallel per-item work, preserve source identity and the actual supported `map-each` collection contract; reuse the existing BT assembly/runtime findings rather than inventing isolated composite behavior.

Classical reactive controllers repeatedly reconsider guards and require appropriate stopping behavior. ORC executes event-sourced asynchronous workflow ticks; a single call does not promise continuous guard polling or cancellation of a running model/tool. If evidence or authorization can change, specify which later tick, explicit guard or application processor refreshes it and what happens to pending effects. A classical memory/decorator pattern is not evidence that corresponding DSL syntax exists. Recheck the target revision and test its behavior.

## General lessons for knowledge-work composition

- Assess intent from the actual request and relevant history, then route an inspectable typed result. Do not make every specialist independently guess whether it was selected. Keep route applicability, semantic completion and pending human permission as separate contracts.
- A specialist that promises a grounded artifact needs actual attributable source input, coverage and provenance. A successful extraction from partial sources cannot prove complete coverage. Parallel extractors own separate typed findings; synthesis consumes those findings with an explicit partial-data policy.
- Keep method definitions stable and source/domain criteria in runtime inputs. When contract variants must coexist, register distinct versioned names; do not oscillate one active sheet name between callers’ definitions.
- Structured schemas and field descriptions define model-facing contracts. Do not replace them with permissive maps or instructions asking for an improvised JSON format. Verify actual parsed values at the receiving delegate.
- Domain effects pass through authorized commands, emitted events and projected read-back. Bind operation identity to stable intent; an idempotent command returning an earlier projection is not proof of a newly requested effect. Reject conflicting replays or explicitly reconcile their outcome. Parent acceptance examines that evidence and the useful artifact; a persuasive child completion report is only a claim. Recover the unmet subgoal within a budget and stop honestly when it cannot be established.

## Public reading route and verification

1. Read [Robohub’s introduction](https://robohub.org/introduction-to-behavior-trees/) for statuses, composition and explicit success checks.
2. Read [Behavior Trees in Robotics and AI](https://arxiv.org/html/1709.00084v6), §3 for node algorithms and §§13–20 for goal-first design, implicit sequences, decision structure, safeguards, backchaining, memory and granularity. Apply the [BT assembly](bt-assembly.md) mapping to ORC before promising execution behavior.
3. In the consuming ORC source, read `docs/ORC-PRINCIPLES.md` §§1–3, 5–8, 10–14; `core/dsl.clj` `delegate`/`build-workflow!`; and `core/todo_processors.clj` `execute-delegate-node`/`complete-delegate-parent!`. This reference checked those seams at main `490816267b76b0428c53437052980a25f5905209`; recheck current source before implementation.
4. Test an already-satisfied goal, unavailable preferred method, eligible alternative, malformed child output, failed effect, partial evidence, nested child input/output transfer, changing state and parallel ownership. Inspect real events and projections. A tree sketch or successful synthetic call is evidence of design/wiring, not of real model quality or production correctness.

This guidance is self-contained; it requires no particular application, presentation or author’s local research folder.

## Revision context

The cited `4908162` observations are historical. Decision/tool APIs were inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`; see [current source status](../../SOURCE-STATUS.md) for the public integration checkpoint. Preserve those original experiment claims at their original pin; use current owning skills for supported integration.

Repeated authored invocation and input-field-guide observations use the later source pins and proof boundary in [authored goal solving](authored-goal-solving.md#source-and-proof-boundary). They do not establish automatic memory, arbitrary recursive delegation or restart recovery.

## Specify the consumer of each artifact

A child should know whether it is producing evidence for another specialist, a proposal for an operator, or an explanation for the end user. Define audience, purpose and allowed effect claims alongside the output schema; provide a runtime delivery brief when these vary. Route to [context flow](../../orc-blackboard-contracts/references/context-flow.md#audience-and-purpose-are-part-of-the-output-contract) for the contract. Keep diagnostic provenance accessible without forcing it into reader-facing prose. Use semantic revision of the actual artifact when necessary, never phrase-based cleanup or canned replacements.
