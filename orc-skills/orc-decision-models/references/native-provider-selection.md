# Select a provider for the actual decision

Use this when a tree mixes generation and finite decisions, or when a completion decision needs a different provider. These are optional configurations for the inspected API surface, not a model ranking. All eight options below were exercised through public ORC completion decisions on the same three compact cases; the observations and one native validation rejection are recorded below. A catalog entry or successful registration alone establishes none of that evidence.

## Separate generation from the native protocol

| Work | Provider configuration | ORC node |
| --- | --- | --- |
| Write or revise an artifact, question or critique | Conversational provider with a supported structured-output mode | Typed `llm` |
| Choose among supplied routes/specialists or retain a boolean answer | Registered native provider with `:protocol :decision`, or a suitable conversational provider | `llm-decision`, exactly one answer write |
| Boolean eligibility controlling BT status | The same supported finite protocol | `llm-condition`, no writes |

The inspected ordinary-model catalog lists `tools`, `tool_choice` and `structured_outputs` for each of these requested generation options:

- [`deepseek/deepseek-v4.1-flash`](https://openrouter.ai/deepseek/deepseek-v4.1-flash)
- [`xiaomi/mimo-v2.6-flash`](https://openrouter.ai/xiaomi/mimo-v2.6-flash)
- [`z-ai/glm-5.3-flash`](https://openrouter.ai/z-ai/glm-5.3-flash)
- [`openai/gpt-6-luna`](https://openrouter.ai/openai/gpt-6-luna)

Model-level support is not a guarantee for every routed endpoint. The endpoints API exposes provider-specific supported parameters and individual tool-choice modes: [DeepSeek](https://openrouter.ai/api/v1/models/deepseek/deepseek-v4.1-flash/endpoints), [Xiaomi](https://openrouter.ai/api/v1/models/xiaomi/mimo-v2.6-flash/endpoints), [Z.ai](https://openrouter.ai/api/v1/models/z-ai/glm-5.3-flash/endpoints), [Luna](https://openrouter.ai/api/v1/models/openai/gpt-6-luna/endpoints). Verify the selected endpoint and actual request/parsed value using [model/SIO](../../orc-model-sio/SKILL.md). Choose sampling options supported by that model and justified by the task; do not impose a universal temperature.

The following native alternatives use OpenRouter's `POST /api/alpha/decisions` with `model`, `state` and named `questions`:

| Model identity | Primary API/catalog reference |
| --- | --- |
| `typesafe/jev-1.14` | [Jev integration](https://openrouter.ai/docs/guides/community/jev), [model page](https://openrouter.ai/typesafe/jev-1.14) |
| `openai/gpt-6-luna-decisions` | [Luna Decisions](https://openrouter.ai/openai/gpt-6-luna-decisions) |
| `cloudflare/clef` | [Clef](https://openrouter.ai/cloudflare/clef) |
| `cloudflare/clef-flash` | [Clef Flash](https://openrouter.ai/cloudflare/clef-flash), [Decisions request guide](https://openrouter.ai/docs/guides/community/multimodal-decisions) |

The frozen ORC adapter maps boolean to `noul` and enum to `choice`. It does not acquire Score, Box, arbitrary JSON generation or multimodal state construction merely because a provider exposes them. Read [Jev validation semantics](jev.md) and [decision contracts](contracts-and-observability.md) for answer validation, confidence and unsupported primitives.

## Optional registration pattern

The registration key may be a keyword, but the inspected authored DSL's per-node `:model` selector must be a string, such as `"decision-jev14"`, not `:decision-jev14`. The execution context's `:llm-provider` keyword is a different field. Check the registered node and actual decision path during preflight; compiling only ordinary generation contracts does not validate native node selection.

This function only registers configurations when called. Supply credentials from trusted application configuration; do not place them in blackboards or request captures.

```clojure
(require '[ai.obney.orc.llm.interface :as llm]
         '[ai.obney.orc.orc-service.interface :as bt])

(def native-models
  {:decision-jev14 "typesafe/jev-1.14"
   :decision-luna "openai/gpt-6-luna-decisions"
   :decision-clef "cloudflare/clef"
   :decision-clef-flash "cloudflare/clef-flash"})

(defn register-native-providers! [api-key]
  (doseq [[provider-name model-id] native-models]
    (llm/register-provider! provider-name
      {:provider :openrouter
       :protocol :decision
       :model model-id
       :config {:api-key api-key}})))

;; Add this node to a workflow that declares these described keys.
(def completion-node
  (bt/llm-decision "assess-completed-work"
    :model "decision-jev14"
    :instruction "Assess the parent goal using the actual completed artifacts, current evidence, remaining criteria and projected effect receipts. Choose the described continuation."
    :reads [:completion-context]
    :writes [:next-action]))
```

`:model "decision-jev14"` selects the **registered name**. Substituting a native model identity into an unchanged chat configuration does not switch protocols. Change the registered name per decision node when a different supported provider fits its evidence and error costs. Keep the execution/poller default conversational provider separately configured for generation. Ordinary chat nodes inherit that provider or specify an actual provider model ID; they do not resolve its alias through the native decision-name mechanism.

Declare `:next-action` as a described finite enum such as satisfied, revise-owner, consult-known-specialist, wait or handoff. The completion packet includes the parent goal, actual outputs from the whole selected subtree or parallel join, relevant source/artifact revisions and effect receipts. Generate new freeform feedback only when the next action needs it. Route through exact guards and the [bounded authored controller](../../orc-compose-specialists/references/authored-controller.md); a finite answer cannot itself create a new capability or authorize an effect.

## Effective context must contain the complete evidence

The model page advertises 65,536 tokens for Clef and 24,576 for Clef Flash, but both pages explicitly state that **Workers AI currently reads only roughly the first 2,000 text-state tokens** after truncation; images are counted separately. This caveat concerns the Workers AI path. The inspected [Clef endpoints](https://openrouter.ai/api/v1/models/cloudflare/clef/endpoints) and [Clef Flash endpoints](https://openrouter.ai/api/v1/models/cloudflare/clef-flash/endpoints) separately list PrimeIntellect at 16,384 tokens. Do not transfer one provider's limit or truncation behavior to another without verification. [Clef documentation](https://openrouter.ai/cloudflare/clef), [Clef Flash documentation](https://openrouter.ai/cloudflare/clef-flash).

The inspected endpoints list Jev 1.14 at 262,144 tokens and Luna Decisions at 1,050,000. These are catalog limits, not a demonstrated guarantee that every supplied fact affected an answer. Recheck the current endpoint metadata and route before relying on any number. [Jev endpoints](https://openrouter.ai/api/v1/models/typesafe/jev-1.14/endpoints), [Luna Decisions endpoints](https://openrouter.ai/api/v1/models/openai/gpt-6-luna-decisions/endpoints).

The inspected native request builder sends `model`, `state` and `questions`; it does not automatically forward ordinary chat provider-routing options. Verify an actual supported native routing/transport configuration before claiming an upstream provider is pinned. If the route cannot guarantee the complete packet is usable, select a model/route that can.

Build a compact packet that preserves every acceptance criterion, necessary qualification and authoritative fact. If that complete packet cannot fit the selected route's effective context, choose a capable route or perform a separately validated decomposition. Do not drop required facts to fit a fast classifier. Merely placing a decisive fact at the beginning does not make an incomplete packet sufficient.

Distinguish four pieces of evidence: the packet is complete; the actual outgoing request includes it; the selected provider accepts it without known truncation; and the observed decision responds correctly to the relevant facts. Request inclusion establishes only the second. Use focused counterfactual checks when context use is material; a successful HTTP response or plausible route is not proof that the model read the whole state. The [context-flow contract](../../orc-blackboard-contracts/references/context-flow.md) and [input-field guide](../../orc-model-sio/references/input-field-guide.md) cover the ORC boundary.

## Source and verification boundary

### Small executed completion check

Each provider received the same original internal-briefing goal, actual cost and delivery artifacts, proposed briefing, qualified evidence and observed effects. Three fixed cases distinguished authoritative confirmation, a forecast falsely called confirmed, and an honestly unresolved forecast. Expectations were defined independently and never sent as answers. This was a compact completion decision, not a full workflow or an accuracy benchmark.

| Provider | Public node results on supported / violated / insufficient |
| --- | --- |
| DeepSeek V4.1 Flash | All three expected answers accepted |
| MiMo V2.6 Flash | All three expected answers accepted |
| GLM 5.3 Flash | All three expected answers accepted |
| GPT-6 Luna | All three expected answers accepted |
| Jev 1.14 | All three expected answers accepted |
| GPT-6 Luna Decisions | All three expected answers accepted |
| Clef | All three expected answers accepted |
| Clef Flash | Expected raw labels on all three; the supported response was rejected by ORC's confidence/distribution validation, so only two nodes succeeded |

There were 24 physical requests, with no repeat-until-pass selection. Native state was at most 1,641 UTF-8 bytes and the entire native body at most 4,949 bytes. Actual request inclusion and returned assessments were inspected. These small complete packets avoid the documented long-state caveat for these cases; they do not prove behavior on long contexts. Clef Flash's rejected answer is an integration limitation to investigate, not a semantic negative or a silently accepted answer. Keep the original assessment and failure facts; do not discard confidence merely to force success.

The rejection was independently replayed: the choice and distribution were valid, but reported confidence disagreed with the TypeSafe formula ORC applies. Published provider implementations do not establish a universal confidence formula, and the hosted route's intended semantics remain unverified. Changing or removing confidence in a diagnostic fixture isolates the boundary; it is not a production correction. Verify provider semantics before applying confidence validation or thresholds across different decision families.

Ordinary generation on more complex contracts exposed separate required-field failures under the tested function configuration. Passing these small enum decisions does not qualify the same model for every generation schema. See [structured-output boundaries](../../orc-model-sio/references/structured-output-boundaries.md).

The configuration mapping was inspected in ORC `9f7c27357b9f9bcf765fbd86baaedf5911261cd8`, `components/llm/src/ai/obney/orc/llm/core.clj`: `decision-route`, `output-kind`, `decision-instructions`, `decision-request`, `default-decision-transport`. The paired SIO input-field-guide source is `a6013e524f36b0b1e9e49cc270c7e1de8a097c36`. This branch places declared input meanings in native question instructions and values in state; older omission findings belong to their original pins.

Public model pages and captured model/endpoint metadata support the options and provider constraints above. The small live checks establish their listed observations, with all original failures retained. Existing authored-tree runs establish specific routing/data transfer and domain receipt observations; ambiguous generated wording remains a semantic limitation. No general ranking of task accuracy, calibration, cost, latency or recovery is established.
