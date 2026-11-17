# Recovery, ownership and limits

## Keep the mechanisms separate

| Situation | Who chooses or performs the continuation | Evidence to require |
| --- | --- | --- |
| Child failure or useful partial result | Model reasons over observations and emits smaller follow-up work | Original failed child, preserved successes, actual diagnostic values, strict repaired result |
| Iteration timeout | Runtime retries from the preceding accepted checkpoint within allowance | Discarded incomplete local mutations, attempt identity, remaining deadline/budget |
| Researcher yields | Processors continue the same node frontier | Same campaign, durable iteration, successful ancestors/siblings preserved |
| Owned process dies | Startup/periodic recovery scans tenant frontiers after lease eligibility | Same tenant/tick/node, reconstructed context, new accepted epoch, unchanged absolute deadline |
| Generated child already exists | Runtime rejoins its stable child tick | Same child identity, terminal/running state, no new child substituted |
| Blocked or cancelled | Terminal status under current contract | Durable terminal fact; no claim of an approval wait/unblock protocol |

Recursive RLM is the default; current checkpoint mode defaults on for recursive researchers. A work quantum defaults to one reasoning iteration, with per-iteration durable records/resume state. Max iterations defaults to ten; iteration attempt allowance defaults to three. Explicit `:rlm {:checkpointed? true ...}` also supplies preventive source guidance that the omitted-flag prompt currently misses. It does not change the actual default checkpoint mode.

Budget a final observation/finalization turn as well as design and repair. A successful child emitted on the last allowed iteration can still leave the researcher unable to call `final!`. Repeated preflight rejections consume the same bounded reasoning allowance. Monitor remaining iterations/calls and choose coherent batch work where appropriate. A campaign that exhausts its allowance is terminal; an explicit new behavior may reuse canonically verified retained values, but that is a new recovery execution, not automatic resumption or success of the ended campaign.

Durable state includes sandbox values, next iteration/frontier, usage, source, timing and an absolute campaign deadline. Full snapshots/deltas have integrity checks and registered durable codecs. Unknown JVM objects or closures are rejected rather than silently stringified. Code source must be reconstructible, and process-local trusted tool/model capabilities must be recreated at startup.

Quoted source is necessary but does not establish every recovery path. At the inspected pin, an in-flight generated child can be rejoined before its process-local inline-function registry is reconstructed. A real restart then failed the original arithmetic leaf, preserved its completed input collection and allowed the researcher to emit a smaller replacement tree. Report that as automatic campaign continuation with model-driven repair, not successful reconstruction of the original function. Verify both boundaries separately; do not switch to terminal mode or repeat completed tools to conceal this gap. See [source status](source-status.md).

Startup/periodic recovery uses persisted leases and epoch comparison to accept one durable outcome. The default execution lease is 90 seconds; context `:orc/execution-lease-ms` is a deployment choice. Do not set it below valid work durations without testing ownership races. Epoch fencing does not imply instant interruption of noncooperative stale work or exactly-once provider spend. Grain platform ownership/drain enhancements proposed in an older arc were dropped; the optional ORC callback is inert without an application supplier.

## Focused model-driven repair

Inspect `get-var :tree-results`; read failures/detail when summary evidence is insufficient. `tree-detail` nodes have UUID `:node-id` and a **map** of `:writes`. That is different from a vector of write names. Pass the actual UUID to `node-output`, rather than converting to a string or using an invented `:id` field. Diagnostic helpers operate on the most recent child by default. Repeated item executions need canonical execution context/attempt, not UUID alone.

Preserve indexed map successes and locate the failed item. Check actual aggregation shape and source provenance. Re-emit missing extraction, failed tool, verification or synthesis only when its inputs remain valid. A successful retrieval need not be repeated just because a sibling failed. A tool/model failure does not authorize unrelated recovery or a fabricated answer. Keep the final contract strict; do not broaden schemas to `:any`, coerce missing numeric values to zero, or accept nil required outputs to obtain green status.

File-backed and forwarded intermediate values currently have a reproduced survivor-collection gap. Canonical public reads can resolve them while the model's survivor map cannot. See [source status](source-status.md). Preserve evidence and stop promising that path until its consuming revision passes the storage-mode proof. This limitation does not invalidate the working inline focused-repair path.

## Tool effects and physical attempts

Checkpointed tools declare `:checkpoint-safe? true` and receive three-argument calls with a stable `:orc/idempotency-key`, effective deadline and execution context. A completed effect receipt is reused. The external callee must bind identity to the original payload and deduplicate/reconcile mutations. An effect accepted externally before its receipt is stored is still an uncertain-outcome window.

Logical effect identity, engine reservation and physical HTTP dispatch are distinct. An interrupted reservation may be spent/indeterminate before transport. A missing response may have incurred provider work. Budget actual attempts across descendants and retries, and retain unknown reservations conservatively. Family usage counts reported usage; compare with actual credential-free dispatch captures. Repeated request text does not prove duplicate ownership.

## Apply all relevant limits

Configure supported model parameters separately from:

- Provider request timeout.
- Iteration computation/model timeout and retry allowance.
- Generated child phase timeout.
- Absolute campaign duration.
- Enclosing tree/delegate deadline and tick bound.
- Physical family call budget and an application cost ceiling where needed.
- Concurrency, JVM heap, projection-cache capacity and external tool resource limits.

A longer client wait does not extend an inner campaign deadline. Restart must not reset deadline or accumulated spend. Generated per-LLM options are currently dropped, so source `:max-tokens`/`:max-retries` cannot establish their effective policy. Inspect actual projected nodes and provider arguments, and use truthful externally enforced experiment limits rather than claiming compiler support.

Test a process kill at a chosen accepted checkpoint and an uncertain pre-receipt window separately. Rejoining after a committed receipt does not prove automatic safe replay across every crash point. Stop only owned processes, save their PID/boundary, and inspect the original campaign rather than creating a replacement tick.


## State across saved quanta

For a value that must survive a saved quantum or a fresh SCI context, use the researcher's durable keyed variable operations (`store!` and `get-var`) under the intended typed contract. A generated top-level `def` binding is not by itself proof that the value is checkpointed. In the saved-quantum fallback probe, relying on that lexical/global binding made the fixture lose state; using the actual durable variable path allowed the next iteration to consume the saved value. Local helper definitions within one evaluation remain useful; do not mistake an SCI helper for an application tool registration. Verify the resumed value and subsequent request rather than only inspecting generated source.
