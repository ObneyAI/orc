# Behavior tree assembly: literature to ORC

Read this when selecting control structure, designing fallbacks, or claiming reactivity. The reference is the [Colledanchise and Ögren textbook](https://arxiv.org/html/1709.00084v6), especially §§3, 13–20. Use [authored goal solving](authored-goal-solving.md) for repeated specialist selection and the [controller reference](../../orc-compose-specialists/references/authored-controller.md) for exact current DSL. Source pins are below; recheck the target revision.

## Begin with goals, not stages

A node's Success is a claim about an observable goal. Write the postcondition, preconditions, and effect before selecting a node. Classical **postcondition–precondition–action (PPA)** structure is a useful default:

```text
Fallback: establish an attributable review case (complete or explicitly partial)
  Condition: a current review case meeting the accepted complete/partial contract exists
  Sequence: preferred method
    Condition: transcript is complete enough to analyze
    Delegate: extract and assess recommendations
    Code: record a review proposal through a command
  Sequence: alternative method
    Condition: transcript is partial but attributable
    Delegate: extract bounded findings with explicit gaps
    Code: record a partial review request
```

This parent explicitly accepts an incomplete review case with recorded gaps. If the goal instead requires a complete recommendation proposal, the partial branch cannot claim that goal; preserve an incomplete outcome or choose a method that closes the gaps. Neither prose nor a node status proves that the command established the required projected case.

The fallback is successful only when a child meets the *same parent goal*. A generic empty report, no-op action, or guessed default is not a recovery. Start from the top postcondition and work backward to the preconditions and actions that could establish it. Expose an intermediate behavior as a subtree when it has its own useful result, alternate method, recovery, reuse, or evidence boundary. Keep one leaf when splitting would expose no useful control point. This applies to LLM work as much as physical actions: one LLM leaf should have one bounded knowledge task, while a method with several independently checkable steps belongs in a tree.

## Standard control patterns and ORC's actual semantics

| Pattern | Classical BT reason | ORC use and proof obligation |
| --- | --- | --- |
| `sequence` | All ordered subgoals are required; stop on Failure or Running. | ORC advances after `:success` and also after `:partial`; a partial child leaves the final sequence `:partial`. Check downstream nodes can honestly use partial data. `:failure` and `:timeout` stop; `:blocked` stops the turn. |
| `fallback` | Ordered alternatives for one goal; stop on Success or Running. | ORC stops on `:success` **or `:partial`**. It tries the next child after `:failure` or `:timeout`; `:blocked` short-circuits. State each branch's eligibility and why its output satisfies the parent. |
| `parallel` | Independent subgoals can progress together; define an M-of-N threshold. | ORC starts all children concurrently. It has independent `:success-policy` (`:all`, `:any`, `:majority`) and `:failure-policy` (`:any`, `:all`), defaults `:all`/`:any`, and checks failure first. Do not import a classical M-of-N threshold mechanically. Give children disjoint writes, decide what early completion means for effects still in flight, and test `:partial`/`:blocked` explicitly. |
| explicit success condition | Skip an action when its postcondition already holds. | Put a condition or semantic decision first in a fallback, then the action. Check the authoritative read model for durable effects; a blackboard claim alone may be stale. |
| PPA/backchaining | Grow a goal-directed tree from conditions and methods. | Design the parent goal first, then each eligible method as a sequence; use delegates for reusable methods and code leaves for commands/effects. |
| bounded revisit | An unmet condition causes another eligible behavior to run. | Put the repeated pass in a child ending with `:check {:key :settled? :op :equals :value true :on-fail :running}`. Preserve the obligation, reconcile fresh outputs and finish a business outcome before the inherited engine cap. |
| priority/reactive checking | Higher priority guards can preempt ongoing lower priority work on later ticks. | ORC executes event-sourced, asynchronous workflow ticks and has bounded re-ticks for `:running`. Do not assume a single `execute` continuously polls guards or cancels in-flight LLM/side effects like a robot controller. Specify a new tick, explicit check, or application event/processor for facts that may change later. |
| memory nodes/decorators | Change rechecking, repetition, inversion, or timeout behavior. | Classical decorators and memory control nodes are not in ORC's hand-assembled DSL palette. Use ORC's actual node options, durable state, conditions, and bounded execution; do not invent decorator syntax. |

Classical conditions never return Running. ORC `llm-condition` maps a true model result to node `:success` and false to `:failure`; a provider error also yields `:failure` with an error. A fallback cannot infer from status alone whether the judgment was negative or unavailable. Use `llm-decision` when a finite answer should be retained, inspect structured provider failure evidence, and use deterministic guards plus eligible recovery when those cases must diverge. Ordinary typed `llm` assessments remain useful for generated feedback or multi-field evidence. A semantic gate is appropriate for meaning; `condition` is appropriate for an already typed value or an exact protocol fact. Never use regex, phrase matching or canned response templates to infer meaning in human or AI messages or to fabricate successful work. Exact protocol parsing, typed equality and schema serialization remain code tasks when their literal contract is explicit.

ORC's explicit Running condition is an implementation mechanism for a bounded asynchronous pass. It is not a claim of classical condition semantics, continuous guard polling, preemption, or a built-in reactive sequence. Source check: [BehaviorTree.CPP Sequence](https://github.com/BehaviorTree/BehaviorTree.CPP/blob/03ec870a07b384f0766bbfe81f77c64a9f994a2b/src/controls/sequence_node.cpp) retains its running child, while [ReactiveSequence](https://github.com/BehaviorTree/BehaviorTree.CPP/blob/03ec870a07b384f0766bbfe81f77c64a9f994a2b/src/controls/reactive_sequence.cpp) starts again at the first child and halts other running work. Its [SequenceWithMemory](https://github.com/BehaviorTree/BehaviorTree.CPP/blob/03ec870a07b384f0766bbfe81f77c64a9f994a2b/src/controls/sequence_with_memory_node.cpp) also retains its index after failure. Those different contracts cannot be inferred from the word “sequence” alone.

## Design questions for each assembly

1. What observable fact makes the subtree succeed, including an already-satisfied case? Is it in the blackboard, an ORC projection, or a domain read model?
2. What precisely does each failure mean: inapplicable method, bad model output, unavailable provider, failed effect, exhausted alternatives, timeout, blocked human step, or honest partial work? Which statuses does ORC actually route?
3. Is fallback order the intended policy? Put cheap, likely-to-fail preconditions early inside a method; order eligible methods by purpose and expected cost/success, not accidental code order.
4. Which inputs or world facts can change during the tick or between ticks? What re-evaluates them, and what happens to in-flight work? Guard irreversible actions with authoritative, fresh checks and idempotent commands.
5. Can parallel branches write or mutate the same thing? If so, serialize them or make their effects and ownership explicit. Does early success/failure leave work still running?
6. Does an LLM decision leave a typed, described, inspectable assessment and route (ordinary LLM reasoning, or host reason codes when a decision model generates no prose)? Is a negative judgment distinct from provider failure? Verify model selection and the actual request when needed.
7. Can an incomplete result be used honestly? ORC's `:partial` is a continuation in sequences and a stopping result in fallbacks, so downstream contracts must declare whether they accept it.

## Example failure probes

- Already achieved: the first condition succeeds and no effect runs.
- Inapplicable preferred method: its precondition fails; the eligible alternative runs.
- Model says “no”: compare against provider or schema failure; they must not silently share a harmful route.
- One parallel branch fails after another writes: inspect both effects and parent status; do not infer cancellation.
- Partial extraction: downstream receives only available findings and an explicit gap; the root remains partial unless a later step genuinely closes the gap.
- Changed external fact: trigger a new tick or application event and show which guard rechecks it.

The textbook explains the *design logic*; ORC's event log, statuses, concurrency, and projections decide the actual execution claim.

## Read from principles to reusable behavior

- For goals, alternatives, reactivity and granularity: read the public textbook §§3, 13–20. Validate each pattern against this ORC status table.
- For nested specialist boundaries, routing and contracts: read [specialist design](specialist-design.md). It contains the reusable contracts and general lessons needed to apply the patterns.
- For goal reassessment, A → B → A, information requests and retained completed work: read [authored goal solving](authored-goal-solving.md).
- For finite semantic decisions and native clients: read [decision-model tree patterns](../../orc-decision-models/references/tree-patterns.md).

Do not prescribe a fixed intake/plan/research/write/check pipeline. Derive the dependencies from the actual goal and constraints; reuse a method only when its postconditions and effect contract fit.

## Additional classical principles, translated deliberately

An implicit sequence organizes a fallback around the desired state and currently applicable progress. In ORC, obtain fresh evidence in an explicit repeated pass, new tick or application trigger; avoid replaying effects from stale blackboard state. A condition that was true earlier is not a durable guarantee. Check the final goal before restoring intermediate states that are no longer needed. The [planning-and-acting paper](https://arxiv.org/html/1611.00230), §§III–V-D, distinguishes repeating an existing behavior from extending its tree; neither runtime tree generation nor a larger roster is necessary for revisiting known work.

Decision structures expose eligible methods and make priority reviewable. Distinguish an assessment that selects a capability from a method fallback for the same goal. Classify once against the relevant state, persist the typed assessment, then guard dispatch; reclassify when that evidence changes, rather than asking each competing specialist to independently infer its route.

Safeguards belong before consequential actions, with authoritative checks at the command boundary. A guard before a long model call may become stale. When switching around noisy measurements, retain explicit mode/entry/exit policy to prevent repeated reversal; a model’s confidence alone does not justify hysteresis thresholds. A memory optimization requires a documented stability guarantee. Neither these policies nor a literature diagram imply built-in reactive-sequence, memory or decorator syntax.

A permission request or missing input is a useful declared outcome when the parent contract permits waiting. Preserve pending work and resume from a new verified state; do not confuse a handled request for permission with the objective being complete.

## Revision context

The original mapping inspected main `490816267b76b0428c53437052980a25f5905209`. Repeated-child execution, complete code writes and inherited caps were inspected at ORC `9f7c27357b9f9bcf765fbd86baaedf5911261cd8`, containing main `1851212396cced2fc4533fda30b824bd4b691390`; see [controller source/proof limits](../../orc-compose-specialists/references/authored-controller.md#source-and-proof-boundary). BehaviorTree.CPP comparison source is pinned in its links and was not executed. Preserve historical experiment claims at their original revisions; use the consuming checkout for execution guarantees and [the revision guide](../../SOURCE-STATUS.md) for shared context.

Current native condition/decision semantics and failure evidence are described in [decision contracts](../../orc-decision-models/references/contracts-and-observability.md). Valid false/none is successful data for `llm-decision`, while false is BT failure for `llm-condition`.
