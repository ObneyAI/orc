# Source status and proof boundaries

Inspected ORC main: `1851212396cced2fc4533fda30b824bd4b691390`.
Recheck the consuming revision; this is a research snapshot, not a permanent API promise. Source files below are under `components/orc-service/src/ai/obney/orc/orc_service/`.

The gaps below belong to this snapshot. For later source-pinned findings, read [model contracts and composed recovery](model-contracts.md): it records the local prompt repair and bounded public-runtime proofs without declaring every historical gap fixed. Publication status and dependency compatibility must be rechecked on the consuming checkout.

## Proven capabilities at this pin

Real model runs demonstrated a researcher inside an authored delegated behavior with a downstream typed consumer; dynamic discovery/retrieval/command-backed recording; quoted emitted arithmetic; independent generated tools followed by semantic synthesis; adaptive map reviews with changed collection size; two independent authored researcher stages; and focused repair that repeated the failed tool without replaying successful search/retrieval.

Controlled public tests separately establish inline intermediate and map collection recovery, actual drill-down after checkpointing, cancellation/blocked/deadline controls, and reconstruction after an owned JVM kill. These prove mechanics for tested boundaries, not universal model task quality or safe replay across every uncertain effect window.

A later real interruption distinguished automatic task recovery from reconstruction of an in-flight generated child. The original tick resumed automatically, preserved all three completed MCP retrievals without another host invocation, and a smaller model-generated follow-up delivered the native total and report. The original interrupted arithmetic leaf first failed because its ephemeral function was absent in the fresh JVM. Thus that run proves continuation and focused repair, while failing the original-function reconstruction contract. An accepted iteration checkpoint and an unfinished generated child are different restart boundaries.

## Reproduced gaps

| Finding | Proof and practical effect | Owner/source |
| --- | --- | --- |
| File-backed intermediate recovery | Inline control succeeds; memory-protocol and production LocalFileStore canonical reads resolve the value, but focused repair receives nil. Parallel collected values show the same difference. | `core/executor.clj` survivor collector reads raw written `:value`; `core/value_storage.clj` uses references. |
| Forwarded value inventory | Authored delegate public resolved writes contain the value, but survivor collection omits referenced events. This is a narrower seam proof, not an emitted delegate journey. | Survivor collector versus `core/value_log.clj` canonical reference resolution. |
| Generated LLM options lost | Authored positive control preserves token ceiling/decimal temperature; generated projected leaf/predict arguments do not. With max-retries zero, controlled failure causes one authored dispatch versus two generated. | `core/rlm_tree_executor.clj` compile LLM case. |
| Schema preflight cause lost from next prime request | Missing-schema tree starts no child. Receipt contains exact rejection; next prime module/inputs omit precise cause/preflight marker/intended-work guidance. | Inner tree catch versus outer summary/catch in `core/executor.clj`. |
| Default preventive quoted-source hint missing | Omitted flag and explicit true both checkpoint; only explicit true receives the preventive prompt hint. | Prompt's raw flag test versus default-aware `core/researcher_mode.clj`. |
| Guide palette wider than compiler | Some guide emitted fallback/condition examples are unsupported. | `docs/RLM-GUIDE.md` versus `core/rlm_dsl.clj`/tree compiler. |
| Zero-write code construction/runtime mismatch | Public construction accepts empty writes; empty-map return fails at execution. A typed boolean receipt succeeds and both traces reopen. Support versus construction rejection requires an explicit contract decision. | `core/executor.clj` code output reconciliation. |
| Ordinary model leaf in delegated researcher specialist | A delegated target containing a checkpointed researcher plus an ordinary authored LLM fails the latter before dispatch under a positive family budget. Its reservation command has nil campaign identity/iteration/epoch and fails schema validation. Direct sequence, no-budget and noncheckpointed controls pass. | Delegate target-wide checkpoint flag in `core/todo_processors.clj`, versus ordinary `provider-policy-options` campaign admission. |
| Syntax error after emission masks recoverable failure | Plain syntax-error recovery and corrected emission controls succeed. A valid `emit-tree!` followed by a stray delimiter fails checkpoint encoding after one prime; no child starts and no repair request occurs. An engine-created lazy tree value remains in the error-path sandbox snapshot. | `core/rlm_dsl.clj` code schema construction and `core/executor.clj` recoverable-error checkpoint path. |
| In-flight generated function missing after JVM restart | Quoted source and native collected observations survive, but the already-started leaf retains a process-local `ephemeral-fn-*` key. Fresh recovery fails that original leaf before the researcher can generate a replacement. Successful task repair does not establish function reconstruction. | `core/rlm_tree_executor.clj` registry, code compilation and existing-child rejoin; `core/executor.clj` function resolution; `core/runtime.clj` abandoned-node recovery. |
| Generated-child trace source revision can remain stale | Native child completion and parent delivery pass, but the final trace counts seven source facts where the canonical child has eight. The RLM completion bookend arrives after the assembler's read and does not trigger a refresh. | `core/rlm_tree_executor.clj` completion bookend; `core/todo_processors.clj` trace assembly and refresh subscriptions. |
| Conversational nullable scalar parsing | Live semantic status outputs and controlled public predictor cases produce string `false`/`null` for nullable booleans, failing native validation. Nonnullable booleans and object-contained JSON values are positive controls. | Pinned SIO marker parser/type renderer; [model boundary](../../orc-model-sio/references/live-findings.md#current-nullable-scalar-boundary). |

These are repair evidence, not reasons to broaden schemas or fake task completion. No engine fixes were applied by this skill campaign. Validate storage, policy and feedback seams on the consuming revision before promising them.

The delegated mixed-node finding does not make terminal mode the recommended alternative. Preserve recursive/checkpointed design, positive budgets and intended authored delivery. Reproduce the consuming revision before relying on mixed delegated model stages. If blocked, preserve the failed root and canonical successful outputs; any explicit separate delivery continuation is a different execution, not automatic resumption or a repaired original tree. Do not disable budgets or fabricate researcher identities to get past reservation validation.

## Consumer pitfalls, not established engine defects

- `get-input` is a loading operation for original blackboard inputs. Reading computed `:tree-results` this way overwrites it with nil. Correct `get-var`/drill-down works under checkpointing.
- A map item's aggregate is an augmented input map plus named child writes. Expecting a bare vector of reviews creates a false measurement failure.
- Parallel siblings consuming each other's pending writes produce incorrect work even with success status; put dependent synthesis after the join.
- A budget guard that rejects every absent output-token option can invent transport failures. Bound requests conservatively against provider limits or use an explicit supported application-wide policy; preserve the actual payload and label any change.
- Source strings containing API names, version increments and unchanged task status do not prove actual diagnostics, guidance injection or improved quality.
- Named SCI tools return error data, sometimes with only `:error`; generated code's injected caller can throw. Match literal protocol fields at the correct invocation surface, not prose, and preserve successful work during repair.
- A successful external call followed by a failure in the same code leaf may leave no durable returned write for the earlier observation. Separate stages when earlier work must survive later failure; do not mistake a host receipt for a collected blackboard value.
- Working generated code can still omit requested descriptions, model assignment, leading rationale or final verification. Inspect compiled contracts and actual model requests; native status and correct arithmetic alone do not prove the complete authoring contract.
- Code consumers must use the actual declared blackboard key type in `:inputs`; a string-only accessor can read nil from a valid keyword-keyed native assessment. Inspect public resolved reads before blaming the delegate/researcher.
- Fixture-specific semantic truths belong in experimental verification, not hardcoded reusable delivery logic. Correct numeric fields do not certify unsupported methodology rules or narrative claims about unknown facts.

Unsupported emitted delegation/nested researchers/finite nodes are palette boundaries. Unknown `get-input` fail-closed behavior and richer repeated-instance drill APIs are possible ergonomics/extensions, not silently repaired capabilities. Manual specialist/tool promotion and stronger reuse/calibration require their own explicit contracts and held-out proof.
