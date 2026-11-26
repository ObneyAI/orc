# Jev through registered ORC providers

The integrated adapter at ORC `1851212396cced2fc4533fda30b824bd4b691390` supports boolean Noul and finite Choice. Recheck provider availability and protocol using [TypeSafe API](https://docs.typesafe.ai/api) and [OpenRouter native integration](https://openrouter.ai/docs/guides/community/jev). Native Score, multilabel policy and unrestricted writing remain separate integrations.

## Register and select

```clojure
(require '[ai.obney.orc.llm.interface :as llm]
         '[ai.obney.orc.orc-service.interface :as orc])

(llm/register-provider! :fast-decisions
  {:provider :openrouter
   :protocol :decision
   :model "typesafe/jev-1.13"
   :config {:api-key (System/getenv "OPENROUTER_API_KEY")}})

(def router
  (orc/workflow "request-router-v1"
    (orc/blackboard
      {:request [:string {:description "Current user task requiring selection of a method"}]
       :route [:enum {:description "Selected method, not evidence the task is complete"
                      :descriptions {"lookup" "Retrieve a known record from supplied state"
                                     "research" "Gather new evidence using a research specialist"
                                     "clarify" "Missing intent or identity prevents safe action"}}
               "lookup" "research" "clarify"]})
    (orc/llm-decision "select-method"
      :model "fast-decisions"
      :instruction "Choose the method that addresses the task using the supplied evidence."
      :reads [:request] :writes [:route])))
```

The DSL model string above names the registered provider, not its model identity. It selects that provider's configured native model even when the tick's default provider is conversational. Pass the default provider in execution and poller context as usual. Do not send Jev through unchanged chat completions by writing `:model "typesafe/jev-1.13"` on a conversational configuration. Credentials belong only in trusted runtime configuration.

Build the workflow, tick through public ORC APIs and inspect the recorded answer before extending it with exact guarded tool/delegate branches. `:min-confidence 0.6 :abstain "clarify"` is an optional Choice routing policy, not a recommended universal threshold. Evaluate the application's error costs and retained context first.

## Request and validation

The default adapter posts `{model,state,questions}` to OpenRouter `/api/alpha/decisions`. Declared input values become state; instruction and output description become question instructions; enum `:descriptions` become criteria. Inspect serialized body to establish what actually reached inference. State is data/text, not direct PDF or vision interpretation; obtain needed observations through tools first.

- **Noul:** one probability in `[0,1]`; greater than .5 maps true, less than .5 maps false. Exactly .5 is undecided and rejected. There is no separate confidence. A configured confidence floor therefore abstains even for an extreme valid probability; do not use it as Noul certainty policy. The public builder currently rejects `:abstain false`, so use described enum outcomes when explicit semantic abstention is needed rather than claiming that boolean configuration works.
- **Choice:** one offered literal identity; retain any reported distribution/confidence. The adapter validates exact coverage, finite ranges, probability mass, selected answer and confidence relationship when both fields exist. Default reported resolution is .01; rounded sums such as .99 and rounded maxima ties can be valid. A config may declare `:reported-resolution` to match its transport.
- **Score:** provider supports ordered rubrics, but the ORC registered finite-output adapter does not implement this primitive. Use [judge guidance](../../orc-judges/references/decision-judges.md) for an explicit custom adapter.

Choice selection within one reporting step of the rounded maximum can be consistent with the unrounded argmax. Do not reject every exact rounded tie as invalid on this routing path. Application uncertainty policy may separately abstain; preserve the original answer and distribution. Confidence without probabilities is retained as reported, not independently verified. Do not synthesize absent confidence or a one-hot distribution. Confidence remains distribution-derived, not calibrated probability of correct action. [Choice](https://docs.typesafe.ai/primitives/choice), [Noul](https://docs.typesafe.ai/primitives/noul), [Score](https://docs.typesafe.ai/primitives/score), [Confidence](https://docs.typesafe.ai/confidence).

## Multiple questions and custom clients

The lower-level `llm/predict` registered path maps each boolean/enum output to one question. `llm-decision` deliberately writes one answer, so use independent leaves for inspectable BT checks or call the lower-level API deliberately. Questions do not observe sibling answers. A source selection followed by reading and judging that source needs a new state and a sequence.

An explicit application client is still appropriate for unsupported native primitives or a different endpoint/contract. Inject real and fake transport capabilities, validate the actual surface, and own its retry/deadline/accounting seam. Such calls do not acquire registered-provider accounting simply because their code node is in ORC.

Capture requested and resolved model separately; configure adjustable options and recheck current [model limits](https://docs.typesafe.ai/models) and [limitations](https://docs.typesafe.ai/model-jaggedness/jev-1.13). Do not embed vendor speed/cost claims as correctness policy. Batch independent wide checks when useful, preserving coverage; refresh any argument depending on an earlier tool result.
