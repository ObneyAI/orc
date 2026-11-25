# Durable family budgets and compatibility

Use this when a family mixes ordinary model nodes, decisions, delegates and researchers, or when bounded work resumes after a restart. Public configuration remains the root's `:llm-call-budget`; accounting metadata is an internal runtime fact, not a consumer flag to fabricate.

## Version matters

The inspected pre-repair stack used a process-local count for ordinary calls and durable campaign reservations for checkpointed researchers. Campaign-only hydration could discard ordinary spend. A real mixed cap5 run dispatched seven calls, and a fresh-JVM cap6 run dispatched a seventh call. Checkpoint opt-out could also continue work after public cancellation while a cleared hot counter appeared empty. Earlier campaign-only budget proofs therefore do not establish the whole-family contract.

The isolated repair routes bounded ordinary and researcher attempts through one durable admission command/CAS stream, indexed separately from ordinary tick traces. Fresh bounded roots record a complete-history version. Reservations identify real roots, node starts, item context and physical attempts; campaign calls additionally retain campaign epoch/effect ownership. No model-specific configuration or synthetic campaign is needed for an opted-out researcher. Checkpoint opt-out controls campaign recovery, not whether a configured cap is enforced.

An old bounded run without provably complete accounting refuses further admission instead of guessing zero or calling the failure ordinary exhaustion. Do not add the history marker to old events, reset counts or backfill by assumption. Resume only under an explicit compatible migration policy; otherwise preserve the evidence and report the compatibility failure. Unlimited operation and ordinary model failover are separate contracts.

## Inspect the actual boundary

Read canonical reservations and provider dispatch evidence through tenant-scoped public queries, including the full execution family. A reservation is spent even if the provider outcome becomes unknown. Do not treat a cached count, successful root return, request directory or checkpoint snapshot as complete physical-call evidence. In a reproduced opt-out failure, the public-return snapshot contained five requests but eight complete HTTP captures existed by fixture teardown. Capture through body completion and inspect ownership before reporting totals.

The local warm repair was independently verified with five physical calls/five reservations, denied continuation, active/root cancellation and the native result. An explicit opt-out case admitted four calls/four ordinary reservations with no invented campaign facts. A natural old-format resume made zero new calls; its fresh-format control resumed to seven calls/seven reservations without replaying eight completed nodes. These are bounded witnesses, not comprehensive concurrency, retries, ownership, crash or worker-quiescence proof.

## Cancellation and failure evidence

Child cancellation can interrupt the model worker that is still responsible for cancelling the root. The reproduced fault interrupted root database acquisition/publication. The local repair runs the coordinated terminal commands on a scoped owned task and synchronously joins it, restoring caller interruption afterward. It adds no mandatory application background hook or global pool. It has no separate backend deadline and cannot claim cleanup while backend work remains unresolved. A cancellation result or joined coordinator alone still does not prove every model/tool body exited.

The companion researcher metadata repair preserves available `:failure-kind`, sanitized `:provider-evidence` and configured fallback metadata in canonical completions. Incomplete history is `:budget-history-unavailable`. Inspect the failed node and the public execution-family `:failure :kind` and evidence. The existing root result continues to expose status/error; absence of a top-level kind is not evidence that the canonical kind is absent. Never infer typed classification by parsing error prose. No provider response evidence is invented for pre-provider refusal.

The normal transport witness exercised kind/evidence through a real SQLite researcher and one loopback503. Populated fallback metadata and all checkpoint terminal-recovery paths are separate proof gaps.

## Adoption status

The historical repair and metadata-delta proofs above retain their original private-candidate scope. Durable ordinary/campaign family accounting and incomplete-history refusal are now present in merged ORC `4966c8e35e3bb5939570bfc645eac52a8d18eb6e` ([ORC #48](https://github.com/ObneyAI/orc/pull/48)). The installed snapshot contains `budget_facts/accounting-version` 1, canonical ancestry resolution, ordinary versus campaign reservation validation, dedicated-root CAS admission and the `:budget-history-unavailable` refusal path. This corrects the earlier blanket private-only adoption statement. It does not rerun every historical cancellation/metadata proof or close the additional obligations below.

Identify the actually loaded `budget_facts`, `provider_call_reservations`, `todo_processors`, schemas and read model before relying on them. The historical metadata-only delta retains its separate immutable manifest; its old file hashes are provenance, not a requirement for later revisions to match.

Application dollar admission is a separate layer from ORC's physical-call cap. In the matched workspace campaign, a host correctly refused more business work to preserve final-answer reserves, then incorrectly closed the same gate needed by that final writer. An independently checked consumer candidate separates expected business exhaustion from unexpected capture failures; the latter still fence dispatch. Its real ORC/SQLite proof uses the actual shared hosted wrapper with controlled cost accounting. Full capture accounting and paid recovery remain open. See the scoped proof and limits (historical local experiment; private archive not bundled). Reserving final capacity alone does not prove it remains reachable through the application's failure path.

Live-owner contention, root/legacy-schema ownership attacks, full candidate/retry/replay charging, fresh-JVM exhaustion, anomaly injection and comprehensive compatibility remain separate obligations. Keep old failing tests visible until their contract translation is justified; do not weaken tests or relax caps merely to get a green suite.
