# Tools composed into behavior trees

## Expand from the goal

A tool is an action capability; its implementation can itself be a behavior tree. Start with the parent postcondition, discover its prerequisites, and compose actions that establish them. The [BT textbook](https://arxiv.org/html/1709.00084v6) supplies sequences, prioritized alternatives, explicit success conditions and recursive goal decomposition. See [BT assembly](../../orc-design-tree/references/bt-assembly.md) for ORC status and reactivity differences and [specialist design](../../orc-design-tree/references/specialist-design.md) for child contracts.

| Action count/dependency | Useful composition |
| --- | --- |
| Zero: goal already holds | Check projected state; report the established goal without repeating the effect |
| One known action | Guard eligibility, invoke authored `tool` or guarded code capability, verify result/effect |
| Several required ordered actions | Sequence with explicit output-to-input dependencies |
| Later action depends on observed result | Invoke, persist/inspect observation, then make a fresh semantic decision or prepare new arguments |
| Several independent actions | Parallel with independent outputs, compatible effects and declared completion policy |
| Reusable multi-action subgoal | Delegate; child owns its action chain and local recovery |
| Unknown action chain | Bounded researcher exploration, with observations and goal checks |

Do not force every task into search/read/save or a universal planning template. Those are examples of dependencies, not a prescribed project architecture. A static chain is appropriate when its order is known. A fresh route after an observation is appropriate when the observation changes what is useful or eligible. [ReAct](https://arxiv.org/html/2210.03629v3) motivates action–observation updates; it does not require copying its prompt format into ORC.

## A result-dependent chain

Use an authored `tool` leaf when flat declared reads match the capability arguments; use `code` for nested maps, transformations or explicit multiple calls. Read [current invocation](current-invocation.md) for executable forms and raw-result validation before single-write unwrapping.

```clojure
(defn read-source [{:keys [inputs call-tool-fn]}]
  ;; Native contracts guard this caller; host authority and useful effect remain separate.
  {:source (call-tool-fn "read-source" (:read-args inputs))})

(orc/code "read-source"
  :fn "app.tools/read-source"
  :reads [:read-args] :writes [:source]
  :tool-contracts
  {"read-source" {:arguments [:map [:source-id :string]]
                  :result [:map [:id :string] [:content :string]]}})
```

Define described schemas for `:read-args` and `:source`, and configure the real caller through runtime `:call-tool-fn` or a trusted `:tool-caller-fn` builder. Configure execution/poller context; delegates inherit the tool context. No application-specific caller shim is required. The host interprets denied/rejected actions, and a valid returned map proves only the declared observation. Exact projections establish the parent postcondition.

For a standalone application, the [runtime reference](../../orc-runtime-bootstrap/references/runtime.md) shows public Git dependencies, scheduler/poller lifetimes and capability injection. If the capability calls Grain directly, the [domain reference](../../orc-grain-application/references/contracts.md) supplies an explicit guard and full command/query envelopes. `:authorized?` metadata is evaluated by enforcing request adapters; direct processors do not execute it.

For a grounded note, a discovery action returns source IDs, a read action consumes a returned ID, an LLM uses the actual evidence to write new note arguments, and an authorized command saves them. The saved-note projection is the success evidence. The discovery observation may instead justify another query, a different source, or honest no-evidence termination. Semantic decisions choose among these outcomes; code checks exact source membership and effect preconditions.

Verify this with an opaque ID created at runtime and absent from initial prompts and fixtures. Require the later arguments and domain event to carry that ID. A fixed ID in a scripted demonstration proves wiring but cannot establish that the model consumed the observation. Likewise, newly generated query/note content must be inspected as actual model output, not supplied by a hidden fixture.

Controlled live runs demonstrated both ordinary semantic routing and a hybrid with an injected finite decision followed by real LLM argument generation. The latter generated new query/note text, an integer amount and a structured label, while preserving the source ID from discovery through nested retrieval and the final projected note. Exact HTTP bodies showed capability descriptions, field meanings and the new observation. The finite response was synthetic; this establishes hybrid wiring, not Jev semantic quality. The local source capability was synthetic and did not rank real documents, so no retrieval-quality conclusion follows.

## Recursive specialists

A root can delegate `produce-grounded-note`; that child can delegate `obtain-evidence`; the grandchild can call several code tools. Each boundary exposes only its useful input/output contract. Build grandchildren first, then children, then root with returned sheet IDs. The same evidence child should work for another caller with different purpose inputs, without knowing either caller's next node. See [composition](../../orc-compose-specialists/SKILL.md) for exact key transfer, parsed-map requirements and stable sheet identities.

Models select specialists from descriptions of capabilities and evidence contracts. Host branches resolve IDs to prebuilt delegates. Do not assume `:target-sheet-id` dynamically reads a blackboard field. Ordinary LLMs can prepare unrestricted child inputs; a decision model can select finite purpose/source options when those are genuinely supplied choices. A delegate is a child tick with lineage and an isolated blackboard, not merely another function in one prompt.

## Alternatives, failure and repair

A fallback expresses eligible ways to establish the same goal. Selection guards prevent an unrelated tool running merely because the chosen tool failed. An already-satisfied branch and a no-tool branch also need explicit guards. Distinguish tool invocation success, valid result, partial useful work and completed parent goal.

For a recoverable invalid proposal, give the next model call the validation feedback and relevant contract, retain prior useful observations, and bound repair. A semantic work check must inspect the current artifact; a typed code condition can guard its verdict. Budget exhaustion returns incomplete/deferred/failure according to the actual contract, never success prose. Define separate budgets for provider retries, node retries, researcher iterations and business repair; replay of a completed effect is not another repair attempt.

The [ordinary two-attempt pattern](../../orc-design-tree/references/bounded-repair.md) makes one semantic revision concrete without a loop API: assess the first artifact, guard an eligible second method on its typed verdict, carry actual feedback, then reassess and verify the effect. Code functions return writes; returning an error/status map under a declared write can succeed as data. The host caller must interpret rejected commands/results before returning a successful tool value.

ORC sequence can continue on `:partial`; fallback stops on `:partial`. Decide explicitly whether a partial source set is sufficient for the parent. A parallel parent completing early does not establish cancellation of already-started effects. Keep parallel writes disjoint, including explanation fields. Per-item collection should carry source IDs; verify supported `:preserve-failures?` behavior in the consuming revision if aligned failed-item results are required, rather than inferring identity from a compacted vector.

When aggregating incomplete work, carry observed failure kinds alongside failed source/action identities when the runtime exposes them. Absence of a review proves missing coverage, not a retrieval/load/provider cause. If the cause is unknown, say that the review is unavailable; do not infer a diagnosis from the missing item. Inspect actual generated gap explanations as well as correct IDs and coverage flags.

## Monitoring recursive work

Follow root/child/grandchild tick lineage, node instance and attempt identities, declared reads/writes, actual caller arguments/results, and accepted domain events. Check domain projections independently of workflow status. Use `get-family-usage` for registered provider calls across descendants; custom native HTTP clients still need their own accounting. A tick returning success cannot compensate for an absent effect or an unsupported fact in the final answer.
