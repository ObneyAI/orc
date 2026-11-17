# Repeated goal solving with authored specialists

Use this when a goal requires choosing and revisiting **known** behaviors as evidence changes. The tree and callable catalog are authored; models decide meaning, applicability and sufficiency within those contracts. Use the [two-attempt pattern](bounded-repair.md) when one planned revision suffices. Use the [bounded controller](../../orc-compose-specialists/references/authored-controller.md) when the next useful specialist depends on the current unmet obligation.

## Start with the goal and its evidence

Define the requested result, acceptance criteria, relevant user-intent revision, required evidence and allowed effects. Identify where each fact is authoritative. A specialist returning successfully establishes only its local contract; accepting the root goal requires the actual artifact and any required projected effects.

Work backward from an unmet criterion: which known method could establish it, and what prerequisite does that method lack? A postcondition–precondition–action subtree checks whether the result already exists before trying an eligible method. Put the final goal check before rebuilding intermediate states that no longer matter. Split behaviors where an intermediate result can be reused, independently judged or revisited; keep inseparable work together.

For example, A may assess a request and discover missing source information. B obtains that information. A then receives the answer, its previous draft and the original acceptance criteria to finish its original obligation. The controller must retain that obligation across B's work; completing the retrieval is not completing the user's goal.

## Make each pass useful and bounded

An authored pass can:

1. Check current acceptance evidence and skip completed obligations.
2. Select an eligible known specialist for a specific unresolved criterion.
3. Build its focused request, invoke it, and reconcile that invocation's result.
4. Reassess the goal, recording progress, a specific missing prerequisite, or a terminal business outcome.

The order and nodes follow the domain. This is not a mandatory four-stage prompt pipeline. In particular, a specialist may own its own smaller composed method.

Select semantically from a described catalog: stable capability ID, useful result, prerequisites, required inputs, supported unavailable outcomes and permitted effects. Use [decision models](../../orc-decision-models/SKILL.md) for suitable finite choices, then exact guards for dispatch. A capability's name alone is insufficient meaning. Unknown or ineligible choices do not authorize invented tools or unrelated fallback methods. Read [tool contracts](../../orc-tools/SKILL.md) when the selected behavior invokes a capability.

Keep independent limits for business passes, repeated unchanged evidence, provider attempts, model calls and elapsed time. Define useful progress in terms of new evidence, closed criteria or changed candidate/assessment revisions; varied prose alone is not progress. Reaching a bound produces a useful unresolved result before the engine ceiling. It does not change the acceptance criteria.

## Reassess after the behavior has run

Completion assessment can follow a whole specialist subtree, a method containing several actions, or a parallel join. Give it the full goal, actual returned artifacts, current qualified evidence and projected effects. A child reporting success is one input to this assessment, not its answer.

| Decision | Next eligible action |
| --- | --- |
| satisfied | Verify the required effects/receipt, then finish the goal. |
| revise-owner | Send the responsible specialist its current artifact and specific unmet criterion. |
| consult-known-specialist | Construct a focused request for an eligible registered capability; retain the obligation to resume. |
| wait | Preserve the work and the missing authoritative user input or permission. |
| handoff | Record the useful partial result, observed blocker and remaining obligation. |

A native finite decision such as a supported Jev choice can select this outcome without prose. Use a separate LLM step only when useful freeform critique or repair instructions are needed. Keep those instructions attached to the actual assessed artifact/revision. The controller then dispatches an eligible route; the assessment is not permission to call any tool it names.

If goal acceptance depends on both parallel branches, put their join and reassessment inside the bounded controller. On its next pass, authoritative completion guards retain healthy branches and only the open obligation runs. A sibling can stay outside the repeat boundary when the solver does not depend on its output. Simply repeating the whole completed method would replay work unnecessarily.

Useful specialists and a suitable composition both matter. More agents are not automatically better, and task decomposition is not automatic iteration. The [agent-design literature](agent-design-literature.md) separates the relevant learning/composition ideas and their evidence; it does not require implementing an optimizer to use an authored controller.

## Carry the question and resume the obligation

Use a typed missing-information request with a request ID, original obligation, requester, precise question, blocking criterion, acceptable source/freshness, answer contract and intended resume target. The parent may answer from authoritative current state, invoke an eligible resolver or ask the user through the application. Preserve unavailable answers and their actual reasons.

Resume A with the matched answer, relevant prior artifact, feedback and qualified evidence. Do not infer which old result to resume from whichever blackboard key happens to be populated. Match invocation, obligation, request and evidence identities explicitly. See [context flow](../../orc-blackboard-contracts/references/context-flow.md) for concrete contract shapes and missing-value behavior.

## Acceptance and effects

### Keep execution repair distinct from semantic continuation

A valid `insufficient` decision can select a resolver. A failed provider call, missing required output, or rejected schema has supplied no valid semantic decision. Preserve that failure and use a bounded execution-repair method for the **same** obligation. For example, an authored `fallback` can try the same typed generation task with a compatible alternate model before returning an explicit unavailable outcome. Keep instruction, reads, writes and postcondition unchanged across such alternatives; do not route to an unrelated specialist just because its guard comes next.

Check the setup first: actual model identity, request mode, exact flattened keys, required fields, description visibility and parsed blackboard. Consult [model/SIO](../../orc-model-sio/SKILL.md). A local schema and a tool schema are not proof of server-side constrained decoding. Keep the declared contract; do not invent missing values, alias misspelled keys globally, or remove rationale fields merely to make a run pass.

Bound provider retries and alternate methods separately from semantic passes. Place recovery around the failing leaf or owning subbehavior so healthy siblings and completed effects survive. If all eligible methods fail, the application needs a durable execution-unavailable outcome linked to the observed failure and useful retained work. An outer success-only recorder will never run after its preceding sequence fails. Define and test this path explicitly when promised; a normal missing-evidence handoff does not prove execution-failure recovery.

Use semantic assessment for whether an artifact meets the goal, follows from the evidence and preserves material qualifications. Code checks explicit facts such as identity, revision, allowed source membership, exact retained quantity/reference and authorization. A schema-valid artifact with the right revision but the wrong source or proposal does not satisfy those literal constraints.

Revalidate facts that may change immediately before consequential effects. A check before a long model call is not current authority. Carry observed revisions to the command boundary; use the application's version/compare-and-set contract when atomic freshness matters. Retain stable operation identity and query the resulting receipt. After an uncertain call, reconcile that identity before trying the effect again. Neither a new invocation ID nor a new effect ID proves retry safety.

Keep completed independent work outside the repeated child. Revisiting A should not rerun a healthy parallel summary or repeat another specialist's completed mutation. Give parallel siblings separate writes and effect ownership. The controller should merge only the selected invocation's outputs, retain unaffected obligations, and confirm the final projected state.

A successful tree may have successfully produced a `needs-input`, `unavailable` or `exhausted` business result. Record that outcome and useful remaining work explicitly. Node `:success` does not turn it into an achieved user goal. Include the observed blocker in the durable application outcome, not only in low-level execution traces.

### Deliver useful work when the run stops

Return the actual artifact separately from its completion status and remaining work. A useful typed terminal packet can carry the original goal, evidence revision, reported outcome, artifact text and stage/currentness, pending consultation, retained findings, completed passes and next-work request. Preserve model-authored text verbatim. A continuation instruction is not the assessment artifact; do not substitute it when the run exhausts its allowance.

Currentness and acceptance are different facts. A current but unaccepted draft stays incomplete; stale work remains available with its stale status. A controller's achieved claim with a blank artifact, stale evidence or pending dependency is inconsistent completion. Its earlier work still belongs in the packet. A model-accepted label records acceptance by that controller; it does not establish independent semantic correctness.

Package retained work deterministically after normal termination, persist it through the application's command/query boundary, and expose that same typed value directly to the caller. Resolve the exact result receipt, rather than whichever result is latest. Keep operation receipts, event UUIDs and correlation tokens distinct. Do not spend another generation to invent a closing explanation after the budget is exhausted.

A bounded sandbox proof exercised accepted, eight-pass current-unaccepted and stale/pending exhaustion, no-draft stop, and whitespace-only invalid completion through actual SQLite/LMDB, ORC and SIO. One test passed 159 assertions with 47 synthetic HTTP calls and no additional finalization call. This establishes delivery wiring for that authored consumer, not live-model reasoning quality, failure paths that bypass finalization, production multiuser delivery or fresh-process recovery. A host must separately reconstruct any application-owned state needed by resumed code; ORC frontier recovery does not recreate a process-local atom.

### Give every assessment outcome a destination

Before implementing a completion check, map each declared semantic result to its next behavior and required context. A final `accept` guard with no rejected-result path detects a problem but cannot solve it. When revision is in scope, route rejection to the responsible specialist with the exact current artifact, failed criterion, qualified supporting evidence and useful feedback; reassess its replacement. When no eligible capability or further useful pass exists, preserve the useful work and return a described unresolved outcome. Do not use a bare exception for an ordinary no-match decision.

Exercise the promised continuation once through the actual public tree boundary: a rejected draft must reach its intended owner, and a fresh assessment must refer to the replacement. Controlled model responses can establish that wiring without repeatedly sampling a live model. A successful missing-information revisit does not prove that final quality rejection or provider failure also recovers. State which of these routes the application actually implements.

## Separate the mechanisms

For each mechanism the application promises, name its trigger and owner. Include only mechanisms the task needs; nesting a tree does not implicitly implement all of them.

| Mechanism | Trigger and owner | What must be demonstrated |
| --- | --- | --- |
| Nested composition | Parent dispatches a registered child | A delegates to smaller B through typed seams; this does not establish arbitrary self-referential cycles. |
| Re-tick | Supported condition returns `:running`; runtime advances within its cap | Another iteration consumes updated durable values. This is not continuous polling or automatic preemption. |
| Goal revisit | Controller receives an unmet prerequisite and resolves it | A → B → A retains one obligation; A receives B's new answer and relevant retained work. |
| Semantic repair | Inline assessment rejects a named criterion; controller dispatches its owner | Actual artifact and corrective evidence reach the owner; a fresh assessment checks the replacement. See [correction packets](../../orc-compose-specialists/references/authored-controller.md#carry-substantive-correction-to-the-owner). |
| Cross-turn wait | Application records a pending dependency and accepts a matching external answer/event | Persist the obligation, requested input, authorized actor and resume target; validate the answer against current intent/authority before resuming. A `wait` label or prose question does not install that trigger. |
| Provider retry | Executor's bounded execution-repair policy | Another physical attempt of the same logical call is accounted for without inventing business progress. |
| Process recovery | Restarted host reconstructs the runtime against the same store | Interrupted work resumes with original identities and reconciled effects. A new conversational invocation and a re-tick do not prove restart recovery. |

These are design and verification distinctions. The evidence below proves only its named paths; new cross-turn or recovery integrations need separate proof. For permission-dependent waits, apply the [authority-transition checklist](../../orc-blackboard-contracts/references/context-flow.md#apply-the-authority-transition-contract).

Blackboard retention is scoped execution state. Durable traces or receipts are not automatically a generic cross-conversation memory policy.

## Proof and counterexamples

For the behavior being added, inspect actual model requests, node inputs/outputs, child lineage, terminal events and domain queries. A useful minimal revisit proof shows A → B → A, the second A receiving fresh B evidence, an unaffected sibling running once, and the terminal business outcome matching the query projection. Controlled responses prove routing and transfer; real provider calls are needed to assess interpretation and useful generation.

Try the relevant counterexample: already-satisfied work still runs; unavailable evidence becomes an empty successful artifact; narrowing drops a source qualification; a stale result overwrites a newer invocation; revision equality hides a wrong source; a completed mutation repeats; or execution succeeds while the application outcome is unresolved. Choose probes from the actual contract, not a universal large test matrix.

## Source and proof boundary

The [Colledanchise–Ögren book](https://arxiv.org/html/1709.00084v6), §§3 and 13–20, supports success conditions, PPA/backchaining, reactivity/memory distinctions and granularity. The [planning-and-acting paper](https://arxiv.org/html/1611.00230), §§III–V-D, separates executing a reactive tree from expanding it and describes recovery under grounding/order assumptions. Neither proves LLM selection accuracy or convergence.

The runtime mapping was inspected at ORC `9f7c27357b9f9bcf765fbd86baaedf5911261cd8`, which contains main `1851212396cced2fc4533fda30b824bd4b691390`, with SIO `a6013e524f36b0b1e9e49cc270c7e1de8a097c36`. See the [controller source anchors](../../orc-compose-specialists/references/authored-controller.md#source-and-proof-boundary). Controlled and real-provider executions demonstrate A → B → A, fresh evidence reaching the resumed author, and retained independent work. Two fresh authors produced standalone consumers; first setup failures and assisted outcomes remain distinct. Three assisted cases produced verified domain outcomes; a fourth failed before handoff when both generation methods failed. The examples remain illustrative contracts to adapt, not universally verified applications. No general semantic superiority, restart recovery, or arbitrary recursive execution is established. Recheck the consuming revision and prove the actual application contract.


### Repaired-contract continuation

A later assisted run on the unreleased structured-contract repair branch verified the previously blocked unavailable-evidence path: parallel independent artifacts → joined semantic assessment → capacity tool → revisit delivery → explicit unresolved handoff. The resumed specialist received the complete prior artifact, unchanged original goal, current qualified source observation and focused feedback under the same obligation with a new invocation. The healthy cost artifact was retained exactly and never regenerated. Fresh-process reconstruction preserved all 1,982 canonical events and the projected result. This is evidence for that authored composition and context seam, not a new fresh-author first-attempt result.

Follow-up isolation found a contribution from our own new strict compiler: it added machine-maximum integer constraints absent from the authored blackboard. On the same endpoint, removing only those generated maxima restored a response and restoring them reproduced failure. The compiler now preserves authored bounds and retains exact local validation; see [structured-contract repairs](../../orc-model-sio/references/structured-contract-repairs.md#numeric-constraints-and-request-order). Earlier endpoint switching was not sufficient root-cause diagnosis. Preserve returned errors and distinguish no provider output from a semantic decision; inspect the generated request before changing the tree or loosening its domain contract. This continuation still leaves the separate all-methods-failed application handoff obligation open.

When auditing retention, first require that both artifacts exist and have the expected typed structure. Equality of two missing values proves nothing. Require a nonempty set of actual started ticks and the intended persisted outcome; a zero-exit audit can correctly verify a failed case without proving task success.

## Do not prescribe the demonstration itinerary

An authored tree owns responsibilities, eligibility, effects and stopping bounds. A semantic router chooses the needed next capability from current evidence. Describe each capability's goal and prerequisites; avoid prompts that demand a particular A→B→A order merely to demonstrate revisiting. If A legitimately discovers a missing fact, preserve that typed request and resume A after B answers. If the router already has sufficient evidence to select B directly, do not force an unnecessary visit to A.

Code can validate candidate identity, compute exact values, enforce authority and dispatch a selected tool. It must not quietly choose a fixture-specific semantic argument that the experiment claims the model selected. Show the actual model-produced choice/arguments and host validation separately. Exercise a different candidate or request without changing solver code.

A pass ceiling is a maximum, not an instruction to run every pass. Stop settled done/wait outcomes explicitly; after exhaustion retain actual completed work and the unresolved goal. Freeze tasks, first submissions and reviewer criteria before live execution. Label post-failure author assistance and same-task retuning; use a different held-out task to assess transfer.
