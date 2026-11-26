# Current and historical model boundary findings

## Current finite nodes

At ORC `1851212396cced2fc4533fda30b824bd4b691390`, the old condition metadata mismatch below is repaired. Use `llm-condition` for boolean eligibility and `llm-decision` for one finite answer; both retain model/evidence/usage and participate in family policy. Registered `:protocol :decision` providers support Noul/Choice; Score remains a custom protocol. The finite conversational path defaults to function calling, while ordinary `llm` defaults to markers. Descriptions still require verification at the actual outgoing boundary. See [decision setup](../../orc-decision-models/references/jev.md).

## Current nullable scalar boundary

At the SIO pin `d6d27f9bd59e2ac071396fc7942eabcdd4eabd12`, the conversational marker path has a reproduced nullable-scalar parsing defect. Both `[:maybe :boolean]` and `[:maybe {:description "..."} :boolean]` leave literal `false` and `null` responses as strings, so native schema validation fails. Nonnullable boolean controls parse correctly; a JSON object containing a nullable boolean also parses correctly. These are different representation paths, not proof that nullable observations are unusable in code or every request mode is broken.

The source marks `:maybe` as a complex spec, then its JSON parser recognizes object/array strings only. The type renderer additionally takes the second element as the child, which mistakes a schema properties map for the described wrapper's child. A live generated semantic map hit these failures for actual observed statuses; retries consumed the attempt allowance without correcting the parsing contract.

Inspect the actual module field, rendered type, response representation and native parsed value. Keep observed nullable facts in deterministic joins and let semantic nodes add judgments under their own writes. When the task truly requires model-produced nullable scalars, verify that mode through the public predictor before promising it. Provider-supported function calling/native JSON has its own description and parsing boundaries; source options on generated leaves are not currently forwarded. Do not silently cast error strings into domain facts, drop useful schema descriptions, or disable validation to claim success.

Source: pinned SIO `src/sio/core.clj` (`complex-spec?`, `parse-output`, `parse-json-value`, `spec->type-str`), and ORC's public LLM predictor. Recheck the installed source after a repair.

## Historical experiments

The following sections preserve observations and reproductions from their original pins. They are not instructions to retain repaired workarounds on the merged revision.

# Live findings at the model boundary

Verified through the nREPL MCP against ORC main `490816267b76b0428c53437052980a25f5905209`, SIO `d6d27f9bd59e2ac071396fc7942eabcdd4eabd12`, and litellm-clj `0570e9459b8751d4a0c3dc1cd48595804426a84d`. Recheck the consuming revision; do not assume a feature-branch fix is present on main.

## Native condition regression

The metadata fix and regression are committed at feature-branch revision `c00bda7e4aa509602b47419a7d9b4f579568a89f`, but are absent from the checked main. The failure mechanism is described below; for portable boolean/enum routing and Jev's distinct native protocol use [decision models](../../orc-decision-models/SKILL.md).

`executor/execute-llm-condition` requests `:validate? false` and the node model, then reads `[:outputs :result]`. `llm/predict` returns direct parsed outputs unless `:with-metadata? true` is set. A real provider response containing `True` therefore yielded `:result false`; a public native-condition workflow failed for the proposition “Two plus two equals four.” A fake provider returning `{:result true}` independently reproduced the same mismatch.

The public `llm-condition` constructor and processor do not provide a per-node metadata-option workaround. At this pin use a typed `llm` result, materialize its boolean/enum if needed, then compare that value with a deterministic `condition`. Natural-language meaning stays in the LLM. Keep the true/false regression open until the target library is fixed.

Even after the metadata fix, inspect the processor: this main pin's native condition completion omits full inputs, boolean outputs and usage/model metadata; exceptions preserve a message but lose structured provider evidence. Its invocation also bypasses the ordinary leaf budget/retry path. Do not claim native-condition monitoring parity with an LLM leaf without verifying those seams.

Source: ORC `components/orc-service/.../core/executor.clj` `execute-llm-condition`; `core/todo_processors.clj` `execute-condition-node`; `components/llm/.../core.clj` `predict`; `core/dsl.clj` `llm-condition`.

## Request options can fail before transport

At these pins, `litellm.schemas/CompletionRequest` makes temperature optional but validates a supplied value as `[:double {:min 0 :max 2}]`; `:max-tokens` is an integer. Independent validation accepted an omitted temperature and `0.0`, and rejected Clojure's integer literal `0`. During live setup, a node configured with `:temperature 0` failed with `Invalid request format` before transport; the final HTTP POST attempt count was zero. This was provider request validation, before response parsing or model judgment, even though the node's input/output contracts were valid.

Choose options per model/provider capabilities. `0.0` represents an optional chosen zero; it is not a required or universal default. Omit unsupported options at the node and verify the adapter's final serialized request. The pinned OpenRouter adapter copies the absent temperature into its request map as nil, yielding `"temperature": null`; absence in node options is therefore not proof of absence on the wire. The type/omission probes establish this local request behavior, not compatibility with every model or provider.

Source: pinned litellm-clj `src/litellm/schemas.clj` (`Temperature`, `MaxTokens`, `CompletionRequest`), `src/litellm/core.clj` (`completion`, validation before transformation/transport), `src/litellm/providers/core.clj` (`validate-request`) and `src/litellm/providers/openrouter.clj` (`transform-request-impl`).

## Description visibility depends on request mode

Independent inspection of four real leaf requests/responses at these pins found the expected blackboard input description in the marker user message, but absent from the function-mode user message. The function output description was present in the `submit_response` schema. All four calls had correlated request/response evidence and typed outputs; this establishes the observed rendering and parsing path, not general semantic accuracy.

Pinned ORC `llm/core.clj` explains the difference: `marker-request` calls `sio/spec->prompt`, whereas `function-request` combines the node instruction with `input-section` names/values and separately calls `sio/outputs->tool-definition` on output fields. Input descriptions retained by `build-module` therefore do not automatically reach the function user message. Keep the described blackboard contract, and explicitly put semantic input requirements and capability/argument descriptions in the function node's instruction or its actual catalog values. Inspect the final message and output tool schema independently when changing modes. Neither a valid SIO signature nor successful output parsing proves the model received those input meanings.

Source: pinned ORC `components/llm/src/ai/obney/orc/llm/core.clj` (`input-section`, `marker-request`, `function-request`); SIO `src/sio/core.clj` (`spec->prompt`, `outputs->tool-definition`).

### Nested collection descriptions in markers

A later independent public researcher application at the same pin emitted semantic leaves over complete3and4record collections. Root decoded their raw serialized HTTP requests and verified the top-level collection description present but both exact nested input descriptions absent; nested names/types and complete source values remain visible. The marker output declaration also shows field names/types without every nested semantic property. Do not generalize top-level description visibility to all nested fields. Put critical field meaning in trusted instruction/described values and inspect the full actual message/schema for the selected mode. This is observed rendering, not proof that all nested contracts or all modes omit descriptions.

## Proven live paths

- A marker-mode Gemini semantic assessment recognized an institution alias, returned a described enum/map, and routed through a deterministic guard to a delegated code tool. A GPT model override answered from the persisted source fact.
- Parallel transcript specialists used different models and noncolliding flattened fields. GPT's function request contained `submit_response`, numeric/string properties, required fields and forced tool choice. Parsed GPA and evidence were reassembled into a map and passed to the parent.
- A typed semantic completion assessment rejected an unsupported fact after source failure. This is a core routing condition, not a claim of statistical evaluation accuracy.
- Provider wire usage matched durable completion usage summed across root and child ticks. Root `execute` usage alone omitted the delegated extraction calls.

## Capture errors safely

The first transport hook excluded headers but stored exception messages. Hato's asynchronous HTTP error message contained its complete request, including the authorization header. This was a capture-harness defect, not an ORC model-routing defect.

Store allowlisted fields only: exception class and numeric status if actually available. Do not persist transport exception messages, whole ex-data or throwable dumps. A future wrapper can lack status even when its cause had it; report status as unavailable unless safely extracted. Verify synchronous and asynchronous failures with a dummy credential, and scan saved artifacts for the real credential without printing it.

Capture the request before calling the transport so synchronous failures still have request evidence. Associate node ID, an invocation identity and attempt at the executor boundary; carry them into the transport capture. A root label alone cannot disambiguate parallel/retried calls. OpenRouter's Hato request decodes response JSON, so the outgoing request string is exact serialized JSON while the saved response is parsed JSON containing verbatim model content/tool arguments.

## Verify ordinal wire compatibility

A valid local schema can still be incompatible with a provider's tool schema. A controlled Gemini/OpenRouter run returned empty `submit_response` arguments for an integer enum. Repeating the same request reproduced it; changing only the level schema to a bounded integer produced complete grade/evidence/feedback. For that tested path, use `[:int {:min 1 :max 5}]` plus all five model-visible band descriptions and strict post-response integer/range validation, or a provider-supported string enum with an explicit host mapping. Both retain finite band selection. Preserve the failed request and control; do not infer a general model failure from empty arguments or claim every provider behaves identically.

## Merged provenance observation

At ORC `1851212396cced2fc4533fda30b824bd4b691390`, a fresh public router experiment independently exposed ordinary generation completion commands recording the configured node model instead of the predictor's resolved model. Native decision completions preserve the resolved identity. Retain both event and actual predictor/provider metadata as distinct evidence; no shared engine patch was made. The current [decision proof brief](../../orc-decision-models/references/current-proofs-and-limits.md) distinguishes this from historical metadata failures above.
