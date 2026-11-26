# Evidence, attachment and runtime

## Read the actual subject

Use tenant, root tick, sheet, node and execution/trace-instance identities. Include
per-item and retry identity when nodes repeat. Follow delegated child lineage and
explicit operation IDs for domain effects. A root tick's event read does not
automatically include every independently tagged tool operation.

Extract recorded values for the assessed instance, not the latest blackboard.
Inspect the packet before model invocation and retain its artifact hash/revision.
Resolve source or effect references where the rubric requires their content.
Trusted IDs and immutable references establish provenance; they do not by themselves
tell the model what happened.

Distinguish:

- An observed failed or absent required effect: evidence that can justify a low band.
- Evidence still pending publication: wait according to a bounded policy.
- Missing evidence that prevents an assessment: ungradable.
- An extractor/query/provider failure: failed assessment with diagnostic evidence.

Treat producer artifacts as data. The rubric and evaluator's permissions belong to
trusted configuration. Do not let generated text choose its own grade or authority.

## Native support at the inspected pin

ORC `490816267b76b0428c53437052980a25f5905209` provides judge declarations,
leaf attachments, background completion handling, custom judge sub-ticks and
command-written score events. It does not fulfill every intended reliability or
assessment-scope contract. Read [source status](source-status.md).

Load evaluation schemas, command/processor registrations before starting the tenant
poller. Verify declaration and attachment through public projections. A configured
attachment is not proof that the completion processor ran or emitted a score.

## Merged attachment and lifecycle correction

Source review at `250de6e6de2ad95647ef6da4c69220c2bd631279` supersedes the historical enablement, attachment and identity restrictions below. Explicit attachments run without Living Description and can target any existing sheet node. Default researcher judges remain flag-gated; an explicit empty attachment disables defaults. Native assessment requests are durable before execution, identified by subject completion ID, judge name and judge revision, and terminate as scored, failed or ungradable. Legacy score identity is not the complete assessment identity.

The assessment-request processor still launches a process-local future and checkpoints after that launch. A pending request remains visible after interruption, but this does not establish automatic restart execution or a bounded worker queue. Keep that lifetime distinct from managed GEPA's task-ledger recovery, described in [GEPA readiness](gepa-readiness.md). Verify actual body draining separately.

The following flag experiment and identity/lifecycle observations describe the earlier inspected revision, not a current requirement to enable learning for monitoring.

The native handler checks the ontology Living Description flag. A lean evaluation
runtime with a successfully attached and directly executable custom judge produced
no automatic score despite processing the completion. A separate controlled flag
probe verified off/on behavior. Its learning processors were removed in an isolated
test registry; that is experimental isolation, not a production recipe. Do not
enable unrelated learning silently to obtain monitoring.

A monitoring-only application can explicitly run a judge BT or build its own Grain
assessment orchestration. Label that ownership and its guarantees. Durable pending
jobs, capacity admission, replay-safe invocation and any-scope assessment are intended
contracts that need implementation and proof where the engine does not supply them.

## Current custom evidence and identity gaps

Custom judges receive the four host fields described in
[composition](compose-judges.md). Their `host-trace` is a minimal identity summary,
not the full durable trace. A full tree/group packet is application-owned.

Native score identity uses sheet/node/tick/judge. Repeated map items can have
correct distinct evidence and run separate judges while only one native score
survives deduplication. Preserve assessment identities outside that tuple when
reporting individual executions. Do not interpret a single surviving score as
the whole group's evaluation.

Native writes are command-driven and tenant-scoped. Deduplicated score events do
not establish that provider invocations were deduplicated; invocation admission
happens earlier and needs its own policy and proof.

## Lifecycle and scheduling

Asynchronous futures keep slow model work off the handler path. They do not form
a durable bounded queue. A checkpoint after spawning work does not independently
establish restart recovery. Record pending work before effects when reliable
monitoring is required, and use bounded worker admission in its owning subsystem.

The native timeout wait does not cancel its judge future at this pin. ORC tick
cancellation is not a join of node or judge bodies. Use separate provider, judge,
workflow and shutdown deadlines, and prove actual worker exit before closing the
cache/store. A timed-out body may still consume requests or finish late.

Composition recursion through `delegate` is ordinary BT reuse. Attaching a judge to
judge executions creates a separate evaluation recursion problem. The bounded
host → judge A → judge B proof shows asynchronous depth context does not enforce
the intended confinement in that path. Do not enable self-attached judging without
a verified durable lineage/depth policy.

## Observe the provider boundary

Keep exact credential-free messages, tool schemas, native questions and response
bodies. Exclude headers/auth configuration, and reject inherited secret values
before evidence persistence. Correlate requests to the invocation and actual
structural owner. Dynamic bindings alone do not reach an already-running poller;
propagate metadata explicitly or use a narrowly isolated lexical invocation scope.

A controlled experiment can temporarily capture a provider adapter's HTTP boundary
in an owned JVM. That seam is not a production tracing API. Preserve the actual
adapter's async/cancellation contract, count retries and nested calls, and never
restore provider access while unjoined work can dispatch. Fail closed or terminate
the owned process when body ownership cannot be proved.

Use [orc-observe-debug](../../orc-observe-debug/SKILL.md) and
[orc-runtime-bootstrap](../../orc-runtime-bootstrap/SKILL.md) for the general
trace, event-store and lifecycle setup; keep judge-specific limitations explicit.

## Revision context

The cited `4908162` observations are historical. Decision/tool APIs were inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`; see [current source status](../../SOURCE-STATUS.md) for the public integration checkpoint. Preserve those original experiment claims at their original pin; use current owning skills for supported integration.
