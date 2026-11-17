# Verify the runtime through its public boundaries

Use this reference when starting an application or proving researcher recovery. A runtime that constructs successfully may still have missing authority, provider, scheduler or shutdown wiring. Execute the smallest relevant public behavior before relying on it.

## Startup and the first workflow

Register domain schemas, commands, queries, read models and processors. Register the real provider and restore trusted tool capabilities before starting workers that can replay unfinished events. Put those capabilities into the poller context; adding them only to a later execute call does not reconfigure existing workers.

Run a typed public code workflow through build-workflow! and execute. Its effect must pass an authorized command, appear as a canonical event and agree with a public projected query. Reject missing claims and a different tenant. An empty result or a direct host call without an actual tree tick does not establish execution wiring. Use a separately labeled controlled transport to inspect model setup; it does not prove live model quality.

Read the actual serialized first model request. Check complete relevant schemas, tool purpose, argument/result meanings and effective provider options. A field name occurring somewhere in a request does not prove its full contract arrived. Decode the request and compare the relevant exact schema/catalog data, then inspect the model's actual use. If an input is previewed, prove how the researcher retrieves its full value before relying on it.

## Execution result and durable tick have different shapes

| Public boundary | What it reports | How to interpret it |
| --- | --- | --- |
| execute | Behavior result, such as {:status :success ...} | Returned work result; verify canonical values and effects too. |
| get-tick | Lifecycle, such as {:status :completed :root-status :success ...} | Completed means the tick ended. Inspect root-status to distinguish success from failure/partial/timeout/blocked. |
| Domain query | Projected application result | Verify the expected operation identity, exact payload and authorization. |
| Value-log public reads | Native resolved execution values | Resolve forwarded/file-backed values through the runtime-aware path, not raw event :value assumptions. |

A monitor checking get-tick :status for :success can wait until its deadline after a successful tick has already completed. Inspect the consuming revision's real public shapes and preserve both lifecycle and behavior outcome. Do not use a terminal-status check as proof that all tool/provider bodies have exited.

## Automatic recovery requires an actual scheduler

Loading ORC registers its periodic recovery task. Starting a tenant poller alone does not start that task. Grain periodic/start-periodic-triggers! starts the registered schedules using application-owned append-fn and tenant-ids-fn; retain and stop its returned handles. The public API starts all registered triggers, so choose scheduler ownership and background processor topology deliberately.

For an automatic-recovery claim, start the original store/tenant and restored provider/tool capabilities in a fresh process, enable the owned scheduler and observe the original tick before any manual resume or new execute call. Require canonical recovery trigger, lease eligibility, accepted ownership and continuation evidence. After automatic completion, explicit resume calls can test idempotence; they cannot retroactively prove automatic recovery.

A real process interruption needs an observed accepted frontier: exact original tick and tenant, accepted generated child and leaf start, reconstructible quoted source and hash units, successful tool receipts, zero active tool/provider transport bodies, and enough original deadline remaining. Read the actual code to establish useful work. A generic code executor label does not prove the paused function is pure, useful or source-reconstructible.

Reopen the same unfinished execution. Preserve the absolute deadline, family budget and cumulative attempt ledger. Prove the generated function is reconstructed in the new process, completed effects are not repeated and repeated resume adds no completion/effect. Retain every host invocation separately from actual external effects and receipt hits; cached effects must not conceal repeated caller invocation. Keep uncertain pre-receipt crashes separate from completed-receipt recovery.

The inspected runtime can automatically continue a campaign while failing an original generated leaf because its ephemeral function is missing after restart. A later model-created replacement may complete the task using surviving observations. Preserve the original failed node, replacement child and final domain result separately. Calling the whole run successful must not erase the failed source-reconstruction obligation.

## Application progress and logical operation identity

Recovering an ORC frontier and restoring the application's working context are separate obligations. A fresh runtime may resume the correct node while a process-local progress atom is empty. For typed blackboard state, prove that the paused leaf receives its original assessment through real persisted reads; for domain-owned state, prove the equivalent authorized projection. Restore trusted capabilities before workers replay events. Do not copy a saved test snapshot into the runtime as proof of recovery.

Choose interruption windows that exercise the work in question: inside a delegated consultation, after a completed controller pass, before final delivery, or after a result append but before the code completion. Preserve original roots, nested child identities, current invocation/pending evidence, consumed passes, absolute deadlines and family budgets. Legitimate later child invocations and controller re-ticks can still occur; distinguish them from regeneration of completed work. Honor the actual latest leaf and parent leases, including renewals.

A resumed start gets a new event ID. A logical application operation should retain identity across that retry while distinguishing separate controller iterations and multiple effects within one node. At the actual consuming pin, derive its address from tenant, original root/tick, node, full execution context and an explicit effect slot. Verify the event/context shape the code leaf actually receives; execution metadata can live in its processor event rather than declared inputs. Replay the same operation through the application's command boundary, compare exact persisted payload/receipt, and reject a conflicting payload. External systems still need their own idempotency or reconciliation contract.

Capture a phase's raw execution result before secondary projection checks. Resolve its domain result by the exact returned receipt and root/tenant scope, rather than revision alone or the latest record. Failure diagnostics should preserve partial family evidence and the primary failed result even when another read fails. A missing final artifact on a failed root must not become a misleading secondary projection error.

Two controlled fresh-process finalization windows and two mid-continuation windows demonstrated these mechanisms with actual SQLite, cache, poller, ORC/SIO and synthetic model content. The original atom-loss failure remains evidence. In the mid-continuation proof, both roots resumed correctly; the harness exited1 because a chained equality compared wire captures with differently shaped native captures. Exact saved-artifact comparisons within each channel established no added calls, without rerunning the workflow. Preserve such measurement failures and their correction separately from application defects. These checkpoints do not establish automatic scheduling, arbitrary interruption, external exactly-once effects, production scale or model semantic reliability.

A separate actual-store capture check distinguished two roots at the same revision by their returned receipts and preserved an original failed root when its projection read and rejection-file save were deliberately failed. Normal, revised, unavailable-evidence and exhausted callback artifacts remained attributable. Its observer initially exhausted the heap by retaining previous full snapshots and creating another whole serialized string. Streaming full evidence and releasing persisted session graphs completed the isolated missing observation at the same heap size; completed cases were not repeated. Persist full artifacts while keeping the active monitor's retained metadata small. This supports the tested capture mechanisms, not a claim that arbitrary diagnostics or external effects cannot fail.

## Shutdown and query-only reconstruction

Stop schedules and close work admission while stores and capabilities are still available. Join actual launched ORC work, tools and transport callbacks, then stop poller, cache, event store and pub/sub. A canceled future is not an exited body. If the application drain fails, retain the services needed by active work and complete cleanup after the body exits. Startup unwind must attempt every already-owned resource, preserving the original failure and any cleanup failure.

For a fresh projection proof, close and snapshot the event store after drain. Keep that snapshot immutable. Copy it into a fresh writable query directory and use a fresh projection cache in a fresh JVM. Load domain/read-model/query registrations and preserve the tenant; start no poller, scheduler, provider, tool server or workflow. Public queries and native value-log readback must agree with canonical events. Verify the original snapshot digest and event sequence are unchanged.

Capture taken before drain can precede final trace or processor checkpoint events. Preserve that capture, inspect any added canonical facts, and compare the complete closed log; do not silently delete the additional facts. When comparing saved EDN against native reads, restore its actual Java time tags consistently. TaggedLiteral and JavaTime objects can print the same value while comparing unequal.


## Recover an effect that committed before its leaf returned

Use the stable business operation and tenant as the receipt identity. Bind the receipt to the actual admitted owner, root and prerequisite observation. The application command can reconcile an identical committed receipt and refuse a changed owner or observation. On a resumed leaf, return that original receipt through the ordinary completion path. Record final delivery against the original root’s canonical result.

In a process interruption proof, independently inspect the durable receipt and open leaf before stopping the owned worker. Reconstruct trusted capabilities and start ordinary recovery schedules in a fresh runtime against the same store. Verify the original start ID on the resumed leaf, unchanged prior work, one effect, one delivery and an empty subsequent recovery. Two real SQLite runs on the merged pin passed this frontier for a synthetic command-owned effect. An independent external service needs its own receipt and reconciliation proof. Verified fixture (historical local experiment; private archive not bundled)

For a fresh-cache read, copy the closed SQLite file and compare canonical rows as well as the original snapshot hash. Grain’s pinned SQLite startup runs bounded `ANALYZE` on the copy. Both deliveries matched, and all 20,533 canonical event rows stayed identical. SQLite refreshed 22 bytes in the copy’s planner statistics. Readback evidence (historical local experiment; private archive not bundled)
