# Portable model contracts: investigate each boundary

## Current authoring and source checkpoint

The completed eight-model comparison uses one typed authored tree, described contracts and standard strict JSON configuration. It adds no model-specific parser, output repair, input adapter or prompt branch. The final eight-row cohort table below records the individual models and proof limits.

The same production repairs are published in [ORC #49](https://github.com/ObneyAI/orc/pull/49), [SIO #15](https://github.com/ObneyAI/sio/pull/15) and [litellm-clj #4](https://github.com/ObneyAI/litellm-clj/pull/4). All three libraries are merged into main. ORC #49 landed through #48 at `4966c8e35e3bb5939570bfc645eac52a8d18eb6e`. The focused integration results below do not establish a complete aggregate-suite pass. Fixture alignment with these contracts and a later transport-timeout classification correction are tracked in [ORC #50](https://github.com/ObneyAI/orc/pull/50). It remains unmerged at this checkpoint and does not add a separate judge implementation or parser. The later historical sections retain their original private/published distinctions; they do not replace this current integration status.

| Library | Reviewed source | Mainline status |
| --- | --- | --- |
| ORC | `718edb1441732335a1bcd1c2faae4eb962518e1d` on PR #49 | Merged through #48; main `4966c8e35e3bb5939570bfc645eac52a8d18eb6e` has the same tree |
| SIO | `e61e11a9abc6fdad4e19f772d9e32e2edd2dcc3b` | Merged; main `07339f84db43c225e565fb8378067218fce2a58b` has the same tree |
| litellm-clj | `f5fa99d8df32d0830e972382e8fa6db233622022` | Merged through #3; main `2e5ad6334143c947d7139994e5d1fc25a943f404` has the same tree |

The frozen eight-model evidence predates the final trace correction. That correction adds accurate aggregate ownership/profiles and profiles exact forwarded references; it does not add a model parser or change the described model contracts. Independent final trace/value integration passes 18 tests and 255 assertions, including the unchanged CI fidelity oracle. Keep this later verification separate from the original model cohort.

### Inspect native values and declared shapes

At the predictor boundary, judges and ordinary nodes receive intact typed input values. SIO renders the final provider request. A test intercepting the predictor must compare the native value; a wire test checks the serialized representation. Do not add consumer serialization to satisfy an expectation from an older boundary.

For a supplied map, the declared nested contract retains optional field meanings even when those fields are absent from the supplied value. An absent top-level input stays omitted. Distinguish the shown evidence, declared contract and actual values when inspecting requests; a declared optional field is not an invented fact.

### Build and switch a model

Keep domain fields and their nested meanings in concrete Malli schemas. Choose strict output on the node or registered provider; the ordinary default does not automatically become strict. For example, after registering the provider and declaring typed `:request` and `:analysis` blackboard entries:

```clojure
(sheet/llm "analyze"
  :model "openai/gpt-6-luna"
  :instruction "Analyze the supplied evidence under the declared task contract."
  :reads [:request]
  :writes [:analysis]
  :options {:output-mode :json-schema :max-tokens 32768})
```

Switch the model ID while retaining the task, schemas, descriptions, tools and output handling. Adjust ordinary completion allowances and enclosing deadlines when a reasoning model needs more headroom. Omit generation settings unsupported by the selected route; do not add a per-model parser or silently change protocol after failure. Required null, absence, false, zero, empty records, literal strings and typed key identity belong to the library's paired contract.

For outage recovery, configure `:fallback-models` on the standard nodes using [configured fallback](configured-model-fallback.md); it does not require a separate wrapper node. Schema failure, unsupported capability, provider admission and a valid but wrong answer remain distinct outcomes. A model change preserves the supported protocol, not equal semantic quality or unrestricted provider schema support.

The verified set contains Gemini, Luna, Haiku, DeepSeek, GLM, MiMo, MiniMax and Qwen. Free Nemotron remains a separate privacy/capability block under `data_collection: "deny"`, not a passed member. The proof does not cover every model, provider route, researcher role or interruption boundary. Use the source pins actually loaded by the consumer.

## Historical source checkpoints and detailed evidence

Changing a model should preserve the authored typed contract. It does not imply identical provider envelopes, protocol support, schema limits or semantic quality. Diagnose six distinct boundaries: authored contract → compiled wire contract → endpoint admission → response channels → paired decoder → canonical ORC writes. Identify the first mismatch before changing prompts or schemas.

## Source and repair status

The initial boundary checkpoint used ORC base `9116dc99f64585563f64d6407dcb9183d88652a3`, SIO `242769baf16d2645473fbd3b38650af65f893236`, and client `bc65561e974d5e35b80d3f842e190231476fdd10`. ORC PR #48 and client PR #3 were published, open and unmerged at verification. Recheck their current states and the consuming dependency manifest. A fresh JVM's loaded resource URI/hash is stronger provenance than a branch label, particularly with `:local/root` overrides.

Three initial additional repairs were verified in isolated local checkouts. They are **unpublished campaign changes**, not capabilities established by those published pins alone:

| Repair | Confirmed cause and bounded evidence |
| --- | --- |
| OpenRouter option omission | Unset sampling settings became five null HTTP properties. Same-endpoint Luna paired requests rejected with them and succeeded without them; Haiku reproduced the issue. The generic adapter repair omits null settings and preserves supplied values. Independent focused acceptance: 17 tests / 124 assertions; later live role probes used the repaired client without rewriting captures. |
| Researcher response instructions | The prime requested marker syntax while SIO selected strict JSON. The repair describes only the raw Clojure value of `code`; SIO owns the envelope. Independent public runtime test: 104 assertions across marker, function, node-strict and provider-strict configurations, including inherited specialist paths. |
| Finite-node option forwarding | `llm-condition` retained authored options in the node but forwarded only runtime options, dropping strict mode, completion allowance and provider/privacy restrictions. The repair changes condition forwarding; the existing decision path serves as a control. Public condition/decision checks passed61 assertions, then seven live condition models preserved those options and returned typed false without a provider failure. This was incomplete plumbing in a newly exposed constructor API, not evidence of an older working option regressing. |

Do not copy local patches into application prompts or claim them released. Confirm the actual loaded repair before relying on its behavior. SIO's rich-shape compiler was unchanged at that initial checkpoint. Later private candidate revisions added the paired rich-shape and identity repairs described below; keep the original observations at their actual pins.

The current private candidate additionally implements ORC flattened-output collision aliases with exact typed-path reassembly, SIO nested typed-key aliases, tagged optional-nullable presence, bounded disjoint unions/finite literal dispatch, and typed dictionary entry codecs. Schema, guide and decoder share the paired plan; application authors do not implement these codecs. Direct SIO top-level duplicate output names, ambiguous unions, arbitrary dispatch functions and unsupported constraints still reject. Identity-specific SQLite producer→delegate→consumer tests and codec regressions support these claims; ordinary nested-map success alone does not. The later [complex model checkpoint](complex-model-portability.md) is separately scoped hosted evidence, not proof that every alias collision or provider grammar was exercised by every model.

## A focused investigation loop

1. Pin source, model, serving endpoint, effective mode, full original Malli contract, input values, completion allowance and enclosing deadlines. Distinguish node/provider defaults from explicit options. Include every model role, not only ordinary leaves.
2. Compile the exact module without a provider call. Retain field descriptions, compiled schema and any rejection. An executor fake bypasses this boundary. Check names after flattening and encoding, not only authored keys.
3. Capture the final serialized HTTP request after the provider adapter, preserving its options and full input values. Correlate each physical attempt structurally with tenant/tick/node/start/context; see [request ownership](../../orc-observe-debug/references/evidence-path.md#structural-http-attempt-ownership). A pre-adapter map or text match is insufficient.
4. Retain the actual response channels and finish state. Compare the response with the schema that was sent, then with the paired decoder's result. Inspect required presence, null, false, zero, empty collections, enum spelling and literal JSON-looking strings separately.
5. Resolve canonical writes for that exact completion and downstream reads. An HTTP 200, parsed value, completed node and useful task result are separate claims. A failed result containing `nil` does not prove a canonical nil write.
6. Use the smallest controlled comparison that separates hypotheses: same request with one option changed, captured-response replay, a malformed response control, or the same contract in another explicitly selected protocol. Label a laboratory wire intervention as such; it is not a shipped fix.
7. After a generic repair, exercise the real request builder/decoder and datastore once with the affected shape/path, then run a bounded live witness where provider behavior matters. Do not repeat broad matrices to regenerate reports. Freeze helpers while they execute and preserve failed harness runs separately.

Router capture establishes client → router bytes. It does not expose the router's private native-provider transformation. Provider metadata advertises capabilities; original typed validation remains necessary even when the route accepts strict JSON. Preserve privacy restrictions and endpoint pins instead of relaxing them to obtain a passing cell.

## Empty content, tool arguments and protocol differences

Null message content with populated `tool_calls` can be a complete function response. The tool's outer `arguments` string is normally JSON text. Decode that protocol envelope, then validate its values; do not mistake it for an empty answer or automatically parse every inner string as JSON. A field declared as a string may intentionally contain `{"literal":true}`.

Named tool choice selects a function; it does not make a tool schema strict. The inspected function requests lacked `strict:true`. DeepSeek changed `dispatch-id`/`ready?` to `dispatch_id`/`ready`, and Luna changed `ready?` to `ready`; original-schema validation correctly rejected them with no accepted result write. Gemini preserved the same names. Those observations do not establish that a properly enforced strict schema was ignored. The strict JSON cases preserved the original names.

Haiku's tool request rejected `ready?` under its tool-property naming rule, while the same strict JSON field was accepted. Forced-tool requests separately hit a router tool-choice compatibility rejection. Keep field-name rejection, tool-choice routing and schema decoding distinct. At that initial checkpoint, provider-compatible wire-name encoding and strict-tool profiles remained future work; application key aliases, quote stripping and permissive parsing are not repairs.

An incomplete response, refusal, admission rejection, capacity error and semantic false answer also require different handling. In particular a valid false `llm-condition` can make a behavior-tree branch fail while its provider invocation succeeded. Inspect `condition-answer` and `failure-kind` rather than treating every tree failure as model unavailability.

The captured rejection reason may be richer than current canonical failure evidence. This campaign found an HTTP 400 lost by the client invalid-request mapper, and a routing 404 normalized too coarsely as `model-not-found`. A normalized allowlisted status/reason repair is proposed. Do not recover arbitrary error bodies into durable events or broaden fallback admission from coarse status alone.

## Rich shapes and whole-schema limits

At SIO `242769ba`, the strict compiler has these measured boundaries:

| Authored shape | Observed compiler behavior | Repair direction at that checkpoint |
| --- | --- | --- |
| Required nullable / optional nonnull | Supported; nullable value and optional absence remain distinct | Preserve current paired behavior. |
| Optional nullable | `ambiguous-optional-null` | A presence-tagged codec must distinguish absent, present-null and present-value. Null alone cannot encode all three. |
| Disjoint `:or` | `unsupported-shape` | Paired union compilation/decoding; validate the original branch. Overlapping JSON representations need tags or proven identical decoding. |
| Finite literal-dispatch `:multi` | `unsupported-properties` for dispatch | Represent declared disjoint branches; arbitrary dispatch functions need separate representability treatment. |
| `:map-of` | `unsupported-shape` | Direct dynamic objects where supported, or reversible entry arrays/finite slots. Reject duplicate decoded keys and preserve key/size constraints. |
| Repeated flattened names / stripped namespaces | ORC strict module rejects duplicate output names before HTTP | Preserve outer write identities or a collision-safe reversible name map, including typed key identity. Direct SIO nested objects already round-trip repeated leaf names under distinct parents. |

These were compiler omissions or encoding ambiguities at the stated historical pin, not proof that complex trees are impossible. The later private compiler supplies the bounded paired plans described above, preserving original domain shapes and retaining local validation. Strict rejection controls alone did not prove those repairs, and the implemented codecs do not establish arbitrary-Malli or every-provider support. Do not silently weaken the schema or switch protocols after rejection.

Endpoint limits apply to the **whole compiled schema**, including automatically generated unions. In the measured Haiku route, 26 optional nonnull properties compiled into 26 nullable unions and were rejected with an explicit limit of 16 union-typed parameters; eight passed, and Luna accepted 26. This does not mean each field was individually unsupported.

A separate 80-required-string schema, with no unions and a 11,999-byte compiled schema, was rejected for grammar size by Haiku and Sonnet. Haiku's width-24 bracket passed, as did the width-80 schema on DeepSeek, Luna, Gemini and full-model Sol. Long-input controls with small schemas passed. There is no demonstrated universal 80-field maximum. Haiku's non-strict tool response with all 80 fields passed local validation; that does not certify strict enforcement or justify an automatic downgrade.

Vary schema width, nesting, optional/union count and input length independently when diagnosing admission. Keep exact schemas and native rejection evidence. A large task may warrant a semantically meaningful decomposition, but splitting fields solely to hide a compiler bug is not a generic repair.

## Fallback preserves the contract

Configure candidates through the existing node API in [configured model fallback](configured-model-fallback.md), once its loaded source and effective options are verified. The default temporary-failure policy is separate from task repair: valid negative answers, invalid output, cancellation and exhausted budget do not automatically admit another model.

A repaired-stack public SQLite witness injected a primary Sol 503 **without sending that request**, then made one real Luna request. Only `model` differed between the captured candidate bodies: strict schema, inputs, privacy and provider pin remained identical. The backup preserved false, zero and empty values; canonical receipts retained the temporary failure and selection. This proves the controlled failover path plus one real backup, not a naturally occurring primary outage or every candidate combination. Do not relax endpoint restrictions or change parsing rules when moving to a backup.

## What the campaign proves, and what remains separate

- Ordinary strict corpus and finite/prime/specialist live cells cover selected shapes and named endpoints, not arbitrary Malli or equal model quality. A requested inline strategy counts as inline coverage only when an actual inline subcall occurs; later constrained API probes prove the mechanism, not autonomous strategy quality.
- Two identical concurrent requests were joined to their exact canonical owners with out-of-order completion and zero crosswrites. That is one runtime/tenant and two conversations, not a production capacity limit or multi-server proof.
- Both inline and emitted composition survived a saved-frontier SQLite reopen with complete typed inputs, unchanged completed-work counts and eight total local HTTP calls across the variants. See [researcher recovery scope](../../orc-repl-researcher/references/model-contracts.md). This is not a hard kill or recovery during an unsaved model/effect call.
- The actual attached custom-judge lifecycle passed valid and malformed local responses, full host-input/trace/rubric wire comparisons and reopened assessment projection. A separate single live Luna judge also passed exact ownership and projection replay. These are attachment/contract mechanics, not judge calibration, all built-in judges or pending-judge crash recovery.
- Exact Qwen Flash subsequently passed matched direct HTTP, client, SIO composition and real ORC strict probes. The final pinned/auto pair both succeeded: automatic routing is a working profile, not a proven cure for shared-pool429s. The remaining nullable/array cases and condition, decision, prime, actual inline and emitted roles subsequently passed on this repaired stack. The emitted role recovered an invalid generated Clojure program through ordinary iteration feedback; strict JSON success does not guarantee executable code. One MiMo timeout remains unexplained despite two later identical-request successes.
- Paid Nemotron subsequently passed the same layer comparison and real ORC nested/name/literal contracts using DeepInfra fp4 at its advertised16384 completion maximum. The original free entry remains distinct: its tool probe was policy-rejected and it lacked an advertised strict endpoint. Privacy was not relaxed.

The source-pinned evidence families are `focused-boundaries`, `rich-contracts`, `concurrent-contracts`, `composed-resume`, `attached-judge`, `live/FINAL-REPORT.md` and `PROTOCOL-FINDINGS.md` in the model-contract campaign. They are provenance labels, not prerequisites for using this recipe. Preserve each new application's own exact source, inputs, requests, responses and durable evidence.

## Working OpenRouter profiles from the access investigation

On the repaired source stack above, ordinary strict generation worked with these settings, preserving original typed contracts and default reasoning behavior:

| Model | Completion allowance | Provider policy | Verified scope |
| --- | --- | --- | --- |
| `qwen/qwen3.8-flash` |32768| `require_parameters: true`, `data_collection: deny`; auto routing with `allow_fallbacks: true` | Direct HTTP, client, SIO composition, All nine baseline cases have successful witnesses across runs; condition/decision/prime/inline/emitted roles also passed. Pinned Alibaba passed the final direct comparison. |
| `nvidia/nemotron-3-ultra-550b-a55b` (paid) |16384| Same capability/privacy policy; `only: [deepinfra/fp4]`, `allow_fallbacks: false` | Same layer comparison; ORC scalar, nested and punctuation/literal contracts. Not the `:free` route. |

Use node `:options {:output-mode :json-schema :max-tokens ... :extra-body {:provider ...}}` with the actual model ID. Provider routing fallback selects an endpoint for the same model; ORC `:fallback-models` selects another model. They are different controls. Inspect endpoint capabilities and output maximum before choosing a profile: a larger output cap can exclude the available strict endpoint. Preserve sufficiently large reasoning/output headroom within the endpoint maximum. No custom parsing or thinking override was required for these witnesses.

Qwen direct HTTP429s carried `upstream_provider_shared_pool`, including a512-token control followed by a successful32768-token call. Identical strict requests also had different outcomes. This rules out changed library serialization as the explanation for those outcomes; it does not expose the provider scheduler or prove permanent availability. Raw native-provider requests beyond OpenRouter remain unobserved.

Historical provenance labels: `live/access-investigation/LAYER-REPORT.md` and `model-level/RESULTS.md` in the model-contract campaign. The profile table and limits above describe those checkpoints; the later complex model checkpoint remains separate. Those reports and their hashes belong to the private historical archive, which is not bundled or required.


## Same-source complex investigation: compatibility and evidence quality

A later investigation checkpoint ran the same frozen authored tree, fixtures, library graph, described contracts and 32,768-token allowance with Luna and MiMo. The 17 completed calls in each initial/revised journey had exactly matching module and sent-schema multisets; concurrent arrival order was matched separately by role. All 30 corrected Luna responses and all 27 completed MiMo responses across nominal/revised/unavailable-evidence cases passed their own exact-schema, adapter, native-input and nested-description checks. This is two-model evidence on this tree, separate from the older eight-model/56-response checkpoint, and does not establish equal reasoning quality. No consumer parser or model-specific helper was added.

MiMo's first attempt received three reported Darkbloom upstream shared-pool HTTP 429 errors before assistant output. It used `allow_fallbacks: false`. A separately recorded follow-up enabled the standard OpenRouter `provider.allow_fallbacks: true`, keeping the same MiMo model ID, strict schema, completion allowance, `require_parameters: true` and `data_collection: deny`. That follow-up received completed responses from several providers. This demonstrates a working availability profile, not guaranteed capacity or an isolated explanation of the provider scheduler. It is same-model provider routing, distinct from ORC model fallback. All failed-attempt reservations were retained without assuming zero charge. [OpenRouter provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) documents the control.

Actual tool selection, exact backend reads, return to the requesting owner, selective sibling retention and current returned reports worked in the completed MiMo trajectories. Nevertheless, its revised report inferred that no other authorization existed from one record's limited interval; the controller accepted that unsupported inference. In the unavailable-evidence case, its finance finding made a false hypothetical per-day budget claim. The report retained unknown authorization but carried misleading budget robustness wording and overbroad absence-of-prohibition wording. These assertions are in the original model outputs, not introduced by decoding. Preserve and assess them as content/quality-gate failures; do not repair them with model-specific string handling or count application `achieved` as evidence-grounded correctness. See the [bounded context evidence](../../orc-blackboard-contracts/references/context-evidence.md#second-model-investigation-observations).

## Same-source complex workspace cohort

Eight named runnable model families completed one complex authored workspace journey each on the same frozen tree, library graph, public task, described contracts, tools and privacy policy. Only ordinary model/profile and completion allowances differ. No per-model parser, output repair or input helper was added. Model decisions and valid repairs differ; contract fidelity does not promise identical reasoning.

| Model | Completion allowance | Actual calls | Protected tests |
| --- | ---: | ---: | --- |
| `google/gemini-3.8-flash` | 32768 | 21 | 13/13 |
| `openai/gpt-6-luna` | 32768 | 19 | 13/13 |
| `anthropic/claude-haiku-5.5` | 32768 | 27 | 13/13 |
| `deepseek/deepseek-v4.1-flash-1017` | 65536 | 25 | 13/13 |
| `z-ai/glm-5.3-flash` | 65536 | 30 | 13/13 |
| `xiaomi/mimo-v2.6-flash` | 32768 | 21 | 13/13 |
| `minimax/minimax-m3.1-flash` | 65536 | 25 | 13/13 |
| `qwen/qwen3.8-flash` | 65536 | 19 | 13/13 |

All187responses matched their sent schemas and canonical reads/writes. Each role retained its original nested descriptions; all six corresponding role modules/output contracts match across models. An independent standard-JSON comparison matched816output fields exactly, including nested values and generated code strings, without calling SIO's parser. Every journey passed13protected public-interface tests/40API calls, preserved the independent feature and delivered an answer citing its current acceptance receipt. All owned model/engine work joined and native resources closed. These eight successful cases costUSD2.4186143177; prior failures and unknown reservations remain separate in the ledger.

The tree used ordinary LLM leaves, typed code tools and delegates. It is not a new all-model finite-node, native function-calling, recursive researcher, arbitrary-schema, restart or production-scale proof. Earlier node/shape investigations remain separately scoped. The original v16 Gemini repaired the task but failed before delivery at a local size guard; a shared lossless consumer context variant resolved that measured duplicate-exposure problem. It does not become a required wrapper for ordinary model switching.

This cohort uses the private combined source stack described above, not the published fallback commit alone or a released mainline dependency graph. Follow the adoption map and verify actual integrated sources before relying on these private repairs. The free Nemotron route remains a separate privacy/capability block under data_collection deny and is not counted as passed. Older paid-route observations remain distinct.

Exact source-frozen outcomes, packet/description/value audits and original failures are recorded in the campaign's workspace-receipt-context-v17 evidence family; no fixture answers or raw records need to be installed as authoring instructions.

## Evidence availability after research workspace recovery

The temporary directory holding the later eight-model campaign was lost during a machine restart. Its 187 raw response artifacts and full campaign ledger containing 1,354 rows are not currently recovered. Surviving reports, source pins and hash-matched source recovery preserve provenance; the cohort counts above are historical reported evidence, not a currently replayable raw cohort. An earlier archived snapshot with 32 manifest entries remains independently hash-verified. A successor ledger carries the full last recorded exposure, including unknown-cost holds; it does not recreate lost request rows.

New persistent evidence for one Luna journey on merged ORC `4966c8e35e3bb5939570bfc645eac52a8d18eb6e` independently validates 15 of 15 raw responses against their exact complete nested schemas. Every request retains the complete original task and all supplied input meanings, with 9–166 distinct descriptions per request. The consumer still returned honest incomplete after selecting a consultation that was no longer procedurally eligible. Model transport fidelity, current action eligibility and completed work require separate evidence; see [recoverable controller selection](../../orc-compose-specialists/references/authored-controller.md#recover-an-ineligible-selection-without-losing-the-obligation).


### Persistent workspace experiment

Four successive runs of an evolving authored consumer on merged ORC `4966c8e35e3bb5939570bfc645eac52a8d18eb6e` captured 198 Luna responses. All match the exact sent schemas. Each request carries the original goal and declared input meanings. The audit joins the provider response and invocation to the node start, trace and canonical completion. It compares 680 native fields and applies declared optional-string omission and output grouping where relevant.

The first two attempts exposed consumer defects in action eligibility and result ownership. The third made an actual repair and delivered an incomplete result after 12 of 15 baseline cases passed. The fourth continued that goal from its completed canonical state, repaired optional origin output, passed all 15 baseline and 19 changed-dependency cases, and delivered the verified current revision. The experiment uses one model and the standard strict contract throughout. The earlier eight-model cohort provides the separate model comparison. Closed run and canonical audits (historical local experiment; private archive not bundled)

## Integrated multi-customer extension

A later frozen conversation tree at ORC4966c8e ran unchanged with Luna, MiniMax, DeepSeek and MiMo:138 real requests/486 generated fields independently matched native inputs, wire values and nested descriptions, strict schemas, adapter content and canonical writes. Completion allowance32768/request300s remained common; no per-model parsing was introduced. See the scoped matrix (historical local experiment; private archive not bundled).

All four preserved the supported transport contract, but task results differed: MiniMax completed a free booking the other runs left unfinished, while it did not attempt the changed-price booking tested by the other models. Some reply claims were unsupported. Preserve the distinction between delivered reply, faithful protocol and achieved goal; this one bounded run per model is not a reliability estimate or all-model guarantee.

## Fresh-author transfer after the integrated comparison

The later held-out transfer and matched comparison (historical local experiment; private archive not bundled) audited66 further real Luna calls. Fresh discovery and two-source conversation worked after explicitly counted construction/configuration interventions. The unfamiliar workspace attempt stopped at a consumer external-schema conversion bug, while all26 of its model calls preserved the declared transport contract. The fixed boundary has a real zero-provider tool proof; the failed repair was not rerun or reported as successful.

A matched custom ordinary discovery loop also reached the correct report, in the same12-call count as the authored tree. Keep tools, information, model, validation, authority and total generation budget explicit; one case does not establish architectural superiority. These results test skill transfer and composition separately from model portability.
