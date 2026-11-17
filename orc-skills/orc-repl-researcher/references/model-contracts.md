# Researcher model contracts and bounded recovery proof

This reference follows the model-contract campaign on ORC base `9116dc99`, SIO `242769ba` and client `bc65561e`, with explicit local repairs. At that historical checkpoint, the fallback API source was published in open, unmerged PRs and the additional researcher prompt, finite-node option and null-option adapter repairs were local and unpublished. These repairs are now integrated in merged ORC `4966c8e35e3bb5939570bfc645eac52a8d18eb6e` with SIO `e61e11a9` and client `f5fa99d8`; the separate PR #50 timeout correction remains unmerged. Preserve the original proof scope below. Verify loaded resources and dependency overrides. See [model portability](../../orc-model-sio/references/model-portability.md) for the complete source/status table.

## SIO owns the response envelope

The confirmed prompt mismatch was generic: the prime was told to emit marker syntax while strict JSON mode required a JSON object. A scripted strict response still succeeded, so success alone did not establish that its instruction was internally consistent.

The local repair asks for raw Clojure in the `code` **field value**: no markdown fences, language tags or marker/delimiter lines inside that value. It tells the model to use the output contract's response format. SIO supplies marker, tool or strict JSON envelopes. Application instructions describe task strategy and code requirements; do not embed a competing transport format.

The public runtime acceptance exercised marker, function, node-strict and provider-strict modes across prime, inline and emitted work: 104 assertions passed independently. Later live prime and specialist requests used this repair. That establishes the generic instruction fix and observed role behavior, not a guarantee that every model selects the best strategy.

## Inspect the role actually exercised

Prime, inline SCI helpers and emitted ordinary leaves are distinct request paths. A model asked to use inline work can instead emit a tree. Record actual subcalls, child starts and compiled leaves rather than counting the instruction as role coverage. In the campaign, Haiku and Luna chose emitted work under generic inline instructions; explicit direct inline API probes later exercised the inline mechanism successfully. Keep both observations.

For every actual call, verify effective model/provider, inherited or overridden options, complete input values, input meanings, response schema, decoded values and canonical result. A registered prime provider alone does not prove the fresh worker/poller context carries it. Capture strict mode, completion allowance and provider/privacy restrictions after the adapter. A constructor retaining `:options` is not proof its executor forwards them.

The prime sees previews and metadata rather than necessarily every full input. Pass the required typed values into the actual specialist. Compare the complete serialized input section, including nested values, false, zero, empty collections and null; field labels or one identifier can miss lost context. A fixture returning a constant expected answer can hide such loss.

## Same domain value, different current wire shape

The controlled composition used the same logical `summary` map in both paths:

| Specialist path | Strict response root observed | Input rendering observed |
| --- | --- | --- |
| Inline `llm` writing `summary` | `{"summary":{"ids":[],"total":0,"run-id":"..."}}` | Full input values rendered as EDN with `any` input guides |
| Emitted ordinary LLM writing `summary` | `{"ids":[],"total":0,"run-id":"..."}` | Explicit typed input guides and JSON-rendered values |

Both decoded to the same typed canonical map under `:summary`. This is current library construction, not a reason for model-specific application parsers. Inspect the actual compiled contract before writing a response fixture. The initial fixture accidentally supplied the emitted flat shape to inline strict mode and was correctly rejected; that was a harness error, not evidence that strict decoding lost fields.

## Typed composition and saved-frontier recovery

The public proof executed a delegated strict producer → researcher → inline or emitted specialist → typed consumer with a real SQLite store and processors. The complete item vector survived each boundary, including nested owner maps, false activity, zero quantity, empty tags and required annotation null.

After the first research quantum saved its typed summary and the next continuation start was durable, only that next dispatch was held. The old runtime/store/cache closed; a fresh runtime reopened the same store and the public recovery scan resumed the original tick. The prime finalized from saved work. Each variant made four HTTP calls: producer, prime iteration zero, specialist, then prime iteration one after reopen. Existing completed `(tick,node,execution-context)` counts and event IDs stayed unchanged. No completed producer or specialist was re-executed.

Independent final run `1196394a-7462-4df5-b091-095884726d17` passed 128 checks and eight loopback calls across both variants, including whole specialist wire inputs, exact downstream typed values and completed-work counts. The fixture provided valid model programs. This proves saved-frontier composition and recovery mechanics; it is not a hard-kill test, autonomous live-model planning, file-store coverage, interruption inside an unsaved model/effect call or multi-server recovery. The older hydration and delegated-budget findings in [source status](source-status.md) must still be checked individually on the consuming revision.

To reproduce this class of proof in a new application, retain the original tick, canonical saved frontier, immutable iteration records, completed child IDs and physical request count before/after reopen. Use a fresh cache so an in-memory projection cannot make replay appear correct. Verify the receiving consumer's actual inputs, not only the root's printed result. Treat provider calls still in flight and side effects with uncertain completion as separate recovery obligations.

## Correlate real reservations, not invented identities

Ordinary producer/emitted-leaf calls use accepted provider-attempt start/completion events and invocation UUIDs. Prime and inline calls with fallbacks disabled can instead rely on checkpointed `:sheet/reserve-provider-call`. Observe that command only after the original processor accepts it, then join its computed `:invocation-identity`, iteration, campaign, budget root, ownership epoch and attempt ordinal to `:sheet/provider-call-reserved`. Preserve actual tenant/tick/node context. The command's campaign-sheet identity is verified against its canonical campaign tick; it is not persisted in the reservation event itself.

Do not turn a physical HTTP UUID into a claimed ordinary provider invocation UUID. The final transport callback must retain the exact structural owner lexically across asynchronous completion, returning the original response unchanged. Source-pinned diagnostic wrappers are not stable public extension APIs. Drain all tracked bodies and restore wrappers before closing the owned JVM. See [structural ownership](../../orc-observe-debug/references/evidence-path.md#structural-http-attempt-ownership).

Use the standard-node fallback API for eligible outages once its source is loaded. The same contract and effective generation options must survive candidate selection and specialist inheritance. Preserve semantic false, malformed output, cancellation and budget exhaustion as their actual outcomes. Strict-compatible streaming is a separate unsupported path at this campaign pin; these nonstreaming proofs do not establish it.

## Fallback profile and response recovery

Use the normal configured alternatives on the existing researcher and specialist defaults; the local [retained-profile repair](../../orc-model-sio/references/retained-fallback-profiles.md#keep-researcher-scopes-separate) now has separate checkpointed prime and inline physical-response crash proofs. A completed enclosing effect, a saved decoded physical response and a fully completed generated child are distinct cutpoints. Emitted saved-response SIGKILL recovery now has its own bounded proof; interrupted emitted requests without a saved response still fail current ownership admission. Read the linked boundary before claiming recovery; neither result is inferred from inline success.

An explicit checkpoint opt-out does not ask for another response format. The later fresh-candidate repair preserves the primary effective profile across compatible backups without custom author parsing, while leaving opt-out restart guarantees unchanged. Compare actual envelopes and described fields even when the task's native result is correct: a fixture can answer both marker and function requests successfully and conceal protocol drift.
