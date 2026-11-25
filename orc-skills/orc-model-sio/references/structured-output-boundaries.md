# Verify the complete structured-output path

The parser/metadata limitations below describe historical source. Structured-contract repairs are integrated in mainline ORC at the collection’s baseline; read [the verified repair path](structured-contract-repairs.md) and [current integration status](model-portability.md#current-authoring-and-source-checkpoint) before applying an older limitation. Generated model-visible meanings, paired strict parsing, null preservation and execution-owned observations still require checking the actual loaded revisions.

Read the consuming source and [model setup](../SKILL.md) first. A blackboard contract, model-facing schema, server-side constrained generation, parsing, typed write validation and semantic correctness are separate boundaries. Trace all of them before attributing a failure to ORC or a provider.

## Diagnose with the declared contract

1. Verify the runtime provider, actual requested model ID and protocol. An ordinary chat provider alias is not a node model ID; registered native decision names have a separate resolution path.
2. Inspect the actual SIO module and flattened output names. Explicit map writes become individual output fields and are later reassembled. Names must be unique after flattening. Keep required keys and descriptions; do not silently rename them or substitute loose maps.
3. Capture the serialized request after the provider adapter. Check required fields, types, descriptions, optional/null representation, tool selection and any requested strict mode. A router-level map can contain options the adapter drops.
4. Compare the provider's actual argument/content JSON to that exact schema before inspecting the parsed blackboard. If raw arguments already omit or misspell a required name, parsing cannot legitimately invent it. If valid raw values change during parsing, minimize that request/parse mismatch instead.
5. Reproduce through public prediction and an isolated public node. A captured response replay removes network/model variation; a minimal corrected fixture is a diagnostic control, not a production repair or a passing live run.

## The inspected function path

At ORC `9f7c27357b9f9bcf765fbd86baaedf5911261cd8`, SIO `a6013e524f36b0b1e9e49cc270c7e1de8a097c36`, and litellm-clj `0570e9459b8751d4a0c3dc1cd48595804426a84d`:

| Configuration | Verified boundary |
| --- | --- |
| `:use-function-calling? true` | SIO supplies `submit_response` with properties and required names. It does not automatically add function `strict` or close the outer parameter object. |
| `:force-tool-choice? true` | Requests the named function. This does not guarantee schema-conforming arguments. It remains an opt-in with schema/provider compatibility concerns. |
| Bare `:strict?` / `:structured-output?` | These are not implemented switches for this path. |
| Explicit tool `:strict true` | The pinned OpenRouter adapter drops this function field while rebuilding the tool definition. |
| Ordinary `:response-format` | The pinned OpenRouter adapter does not forward it. Other adapters can differ. |
| Raw `:extra-body` provider fields | They can reach the wire, but do not automatically derive a schema or switch ORC's response parser. A JSON-content response to forced function mode is rejected as a missing tool call. |

These are source and public fake-HTTP observations, not a live strict-endpoint certification. Four preserved real responses had missing required fields or underscored names instead of declared hyphenated names. Exact replay failed; correcting only those fixture fields succeeded. SIO preserved the literal contract, and ORC prevented those invalid writes from becoming accepted domain results. This does not establish that a correctly configured strict endpoint ignored its schema.

A separate real response contained every required field in JSON content but no tool call. The configured function parser did not consume that channel. Check response location as well as field presence; do not report correct content-channel keys as lost blackboard fields. Force-tool-choice requests a response channel, while strict-schema settings concern its argument structure. Their support and effects must be verified independently.

OpenRouter documents an explicit JSON-schema response mode and endpoint-specific enforcement; verify current provider support and routing rather than inferring enforcement from a model catalog capability. [Structured output documentation](https://openrouter.ai/docs/guides/features/structured-outputs).

## Authoring consequence

Required presence and nullable value are distinct. On the inspected stack, function parsing turns a missing required nullable field into present nil; direct prediction can then accept it, while an ordinary leaf rejects even explicitly supplied valid null. The leaf's generated description can also label `:maybe` as optional despite required presence. This is a parser/executor/description discrepancy, not permission to invent values. Verify both absent and explicit-null cases at the public leaf before relying on that contract.

An isolated strict JSON-content prototype demonstrates deriving an enforcement request from the same blackboard contract. Its provider-level mode is experimental, not a released ORC option. A production default still needs capability selection, a supported schema compiler, paired parsing, optional/null preservation, terminal/refusal handling and event-compatible failure categories. Do not copy a prototype flag into an unmodified runtime. Token exhaustion is separate; see [reasoning and completion budgets](reasoning-and-completion-budgets.md).

Use an output mode the complete request/response stack implements, and keep typed validation. Do not add undocumented flags, duplicate schemas manually as a routine workaround, globally alias returned keys, or remove required fields to hide failures. If stronger automatic enforcement is needed, hand off the integration: derive the supported provider schema from the same contract, preserve strict settings through the adapter, route to a compatible endpoint, and decode the selected response protocol while retaining nullable/optional semantics.

Application instructions describe the task; the blackboard remains the source of field names, types and descriptions. Repeating a field list in every prompt is not a structured-output integration. A direct prediction harness that disables validation for already-serialized inputs must still validate returned outputs before using them. Otherwise it can incorrectly appear more reliable than a public ORC node that rejects the same invalid data.

After verifying setup, an eligible alternate model can attempt the **same** typed task inside a bounded `fallback`. Keep successful sibling work and effect receipts. Separate this execution repair from a valid semantic decision that asks for more evidence. See [authored goal solving](../../orc-design-tree/references/authored-goal-solving.md#keep-execution-repair-distinct-from-semantic-continuation). Neither retries nor schema validity establish useful task completion.
