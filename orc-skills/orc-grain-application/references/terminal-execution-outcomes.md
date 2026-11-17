# Reporting a terminal execution failure

An authored tree can exhaust bounded generation attempts while successful parallel work remains available. A success-path code node may never run in that case. When the application requires a durable operational result, a Grain processor can observe terminal failure and issue a reporting command.

First diagnose the failed request. Exhausted attempts do not establish a model limitation: inspect the serialized contract, provider response, parser and exact attempt ownership using [model/SIO guidance](../../orc-model-sio/SKILL.md). A reporting path does not fix an invalid generated request.

## Application pattern

1. Register the application's root tick, sheet, original goal, domain idempotency identity and exact artifact producer identities before execution. Identify nodes at assembly time; do not rediscover ownership by matching prose or node display names at reporting time.
2. Subscribe to tree-tick completion through Grain. Check canonical **root failure**, registration, sheet identity and tenant. The completion event type can also occur for running status. Child completion or an unrelated root must not create an application outcome.
3. Derive a typed operational outcome from canonical execution events and public projections. Preserve the engine's failure status, actual retained artifacts and producer provenance. Include failed invocation references when available; code failures may have none. Never invent a successful artifact or generate a reassuring semantic handoff to hide failed execution.
4. Keep goal assessment separate from execution status. State which evidence establishes the assessment. In the tested sample, `:not-assessed` means the reporter found no committed domain result; it does not prove that no semantic checker ran. The sample does not recover an uncommitted intermediate verdict. If that verdict matters, give it its own canonical provenance and explicit reporting rule. An already committed domain result may remain valid despite a later execution failure. Preserve it and its identity without repeating the effect.
5. Enforce authority in the path actually invoked. Use a tenant-scoped command, validated events, CAS/idempotency, projection and query. A checkpoint-after processor can replay; concurrent identical delivery must produce one outcome. Authorization metadata alone does not guard direct command calls.
6. Keep the worker alive until the application outcome projection is observed. Prove fresh-process reconstruction from a closed event store without invoking models or tools.

## Define the retention and recovery scope

Explicitly choose which successful producers supply retained artifacts. An initial parallel delegate's output is not automatically the latest revised specialist result. For recursive workflows, define selection by obligation, revision and canonical execution identity before claiming latest-work recovery.

This pattern was exercised for settled ordinary root failure using the real runtime, event store, processor and public commands with controlled HTTP responses. It covered concurrent reporting, authority, tenant isolation, existing committed results, code-only failure and fresh-process reconstruction. It does not establish recovery for cancellation, timeout, interrupted workers or unfinished parallel descendants. Those need their own contracts and focused verification.

The sample relies on the unreleased provider-attempt APIs described in [the structured-contract repair path](../../orc-model-sio/references/structured-contract-repairs.md). Verify the loaded revision before using those calls. The general command/event/projection pattern is application-owned, not a built-in ORC business outcome.
