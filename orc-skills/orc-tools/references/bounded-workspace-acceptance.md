# Bounded workspace acceptance and read authority

Apply these distinctions when the agent can edit the implementation exercised by its tools:

- Keep acceptance assertions in the trusted owner, outside the editable code's interpreter. Distinguish failed assertions from premature child exit or invalid transport.
- Audit parent artifact collection independently of child confinement: rooted no-follow traversal, explicit rejection of links/special files and bounded reads also apply to manifests and hashing.
- Separate complete source-read authority from edit concurrency. An unchanged file's complete observed bytes remain usable after an unrelated file changes. The proposed edit still needs the current workspace revision and target hash; a changed target needs a matching full read. Validate the entire batch before mutation.
- Verify the actual final protected acceptance and exact owned snapshot/native revision. Schema-valid output and successful transport do not establish business acceptance.

## Evidence and limits

The retained-read root verification (historical local experiment; private archive not bundled) reproduced the old false conflict and independently exercised seven actual host calls: retained A after unrelated B changed was accepted; changed A without a matching read, stale global edits and mixed-coverage batches were refused. This was focused host/capability evidence, not a new full ORC end-to-end run.

The host root verification (historical local experiment; private archive not bundled) preserves the parent scratch-manifest canary failure and corrected confinement proof. The hosted preparation review (historical local experiment; private archive not bundled) records source-freeze and zero-call preparation separately from live completion.

The closed workspace baseline in workspace outcome report (historical local experiment; private archive not bundled) used 38 physical Luna calls and 226 generated fields. Actual protected acceptance passed 11/11; only three permitted implementation files changed. Root matched the owned final snapshot to the native final revision and qualified owned runtime/host shutdown. No custom model parser was added. The model corrected its initially mis-sequenced probe; the original observations remain evidence.

The independent capture audit (historical local experiment; private archive not bundled) found request/wire/canonical-write correspondence for all 38 calls. Registered and wire schema descriptions were not literally identical; assertions matched. A separate native `llm/predict` return artifact was not captured: canonical writes are the published parsed boundary. This audit alone does not establish semantic acceptance.

These are one bounded fixture/model baseline and targeted controls. They do not establish arbitrary-code security, large-codebase performance, all-model reliability, crash-atomic filesystem edits or app crash recovery. An explicit continuation is a separate experiment; no continuation outcome is inferred here.

## External tool receipts during fresh transfer

Validate real nonempty tool receipts as well as generated arguments. If an external JSON contract declares a typed dictionary with `additionalProperties`, preserving it as a described Malli `:map-of` is different from forcing a closed record with zero named fields. A consumer converter that does the latter can reject a successful confined execution after it has already produced effects. Do not treat that rejection as malformed model output or repeat the effect blindly.

The fresh-author transfer result (historical local experiment; private archive not bundled) preserves an actual failed workspace attempt caused by this conversion. Its minimal generic dictionary correction then passed real confined scratch-output/canonical-readback verification without a paid rerun. That closes the boundary reproduction, not the unfinished workspace repair. The broader experiment's earlier11/11 repair results remain separate evidence. No per-model input/output helper was added.
