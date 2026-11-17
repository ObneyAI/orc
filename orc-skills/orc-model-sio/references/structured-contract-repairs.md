# Structured contracts and provider observations — merged repair path

These capabilities are integrated in merged ORC source snapshot `250de6e6de2ad95647ef6da4c69220c2bd631279`, whose LLM component pins SIO `242769baf16d2645473fbd3b38650af65f893236` and litellm-clj `2b6781b8f98105fe07d17b289138103e5dd3a047`. Source review confirms `sio.structured-output/compile-contract`, `output-guide`, `parse-output`, enum option descriptions, and ORC `provider-attempts`. Inspect the consuming revisions before using them; older checkouts may lack this path. Integration does not make strict output the default or certify every provider. The isolated `fix/structured-contract-repairs` experiments below remain historical runtime evidence, not new tests at the merged pin.

A bounded live OpenRouter/InferenceNet run verified an ordinary strict LLM leaf, preserved required null, omitted an optional absent value, retained returned reasoning separately, selected it into a typed packet and consumed it in a later code node. A separate JVM reconstructed identical events, trace, ledger and writes from SQLite. This proves this path on that endpoint; it does not certify every model, streaming mode or researcher lifecycle.


### Select a complete output protocol

On a revision containing the structured-contract repairs, blocking conversational OpenRouter calls can select `:output-mode :json-schema`. Configure it on the registered provider profile, or override the selected mode in a node's `:options`:

```clojure
(sheet/llm "prepare"
  :instruction "Prepare the requested result using the supplied evidence."
  :reads [:request :evidence]
  :writes [:prepare-reasoning :proposal]
  :options {:output-mode :json-schema})
```

The declared, described blackboard remains the contract. ORC builds the SIO signature; SIO derives the strict JSON schema and its matching decoder together. SIO also renders an output guide from that same compiled schema into the model-visible message. ORC sends the identical schema in JSON-schema response format through the OpenRouter adapter and parses the returned content using the paired contract. The guide and response format are generated views of one contract, not independently maintained schemas. Do not duplicate field lists or manually maintain a second provider schema in task instructions. Verify field names, descriptions and null/absence meaning in both the model-visible message and serialized response format, then verify actual returned values and durable node output. A schema supplied for response enforcement does not by itself prove its descriptions appear in the model-visible messages.

This mode is explicit, not a new universal default. On a provider profile without `:output-mode`, ordinary leaves retain their existing marker default and explicit function mode remains available. Direct prediction has its separate provider-capability default. Do not invent `:output-mode :markers` or `:strict?` flags; the repaired selector supports the strict mode or absence of a selected mode. A nil node setting inherits a strict provider profile rather than cancelling it. Use an appropriately configured provider profile when a workflow requires a legacy path.

Strict mode owns its response schema and response channel. Do not combine it with manually supplied tools, forced tool choice or a competing response-format/stream override. Provider routing must honor required parameters. The repair rejects conflicting controls instead of silently sending one protocol and parsing another. The current ORC strict path is implemented for the OpenRouter adapter; do not infer equivalent support from another adapter accepting a similarly named option.

### Supported contracts and explicit rejection

The bounded compiler supports strings, booleans, integer/floating numbers, null, scalar enums, nullable values, vectors and explicitly closed finite maps, recursively. It preserves supported descriptions and bounds. Keyword enum values use their canonical JSON spelling and decode back to the declared host value. Object property/output name collisions are errors. Values still cross their original application-schema validation after decoding; compiler support does not establish semantic task correctness.

Open/dynamic maps, unsupported unions/wrappers/custom constraints and unsupported schema properties fail before dispatch. Do not loosen a contract to make it compile. Use a supported representation that preserves domain meaning or a verified alternate output protocol.

Required presence and nullable value are independent:

- A required nullable field must occur; explicit null is a value, not a missing field.
- An optional nonnullable field uses a required nullable wire slot. Its wire null sentinel is decoded to absence.
- An already-nullable optional field is rejected in this strict protocol because one null token cannot represent both absence and an explicitly supplied null. This is an unsupported wire encoding, not a claim that the domain contract is invalid.

Strict parsing requires one complete JSON object, every required wire slot and no undeclared properties. It does not guess aliases, wrap scalar values into arrays, strip arbitrary quotes, consume a valid prefix followed by trailing data or synthesize missing fields. Refusal and incomplete generation remain failures even if some response fragment is parseable. Inspect finish reason and token headroom; a schema does not reserve enough tokens for the complete answer.

Strict streaming is explicitly unsupported in the repaired ORC path and fails before provider dispatch. Do not route it through the legacy marker streaming parser. Request/response profile support must be verified at the selected endpoint; controlled transport tests are not live endpoint certification.

### Numeric constraints and request order

The repaired compiler sends authored numeric bounds; it does not synthesize machine integer extrema into provider schemas. For example, `[:int {:min 0}]` sends `minimum: 0` without an invented maximum. Explicit `:min`/`:max` constraints remain intact, and local exact conversion plus original Malli validation still reject overflow, fractional integers and invalid domain values. Do not replace a missing domain maximum with an arbitrary small one merely to satisfy an endpoint.

This distinction came from a same-endpoint reproduction: the original generated cost schema failed, removing only the automatically added integer maxima succeeded, and restoring them reproduced failure. Earlier endpoint switching had hidden that request-generation contribution. Endpoint-specific errors require checking the actual generated schema before treating model fallback or an application failure report as the solution. The probe establishes that constraint's effect for the tested request, not the provider's internal implementation or universal schema support. Very large explicitly authored bounds still need endpoint verification.

Root and nested property maps preserve authored order even beyond eight fields. Otherwise a host hash-map conversion can move the leading rationale after task outputs. Check actual serialized property order and generated guide, not just `required` array order. This preserves request fidelity; it does not establish private model reasoning order.

### Legacy contracts after the repair

Final marker and function parsers preserve omitted required keys as absent. Explicit nullable null stays present. Progressive marker parsing retains partial placeholders for streaming consumers; completion must use final parsing and required-presence validation.

Nullable marker scalar JSON decodes once, including strings with embedded quotes and JSON null. Ordinary string output retains its literal string behavior. ORC must parse the wire schema it rendered for optional outputs, normalize the documented optional representation, then validate the original application contract. Do not apply global quote stripping.

A completion marker alone is not an empty answer. An explicit empty string marker remains an authored value and succeeds only if its schema permits it. Valid null survives canonical writes, downstream reads and reconstruction; missing values do not become invented null. Test `contains?` as well as value equality when checking this distinction.

## Minimal strict generation setup

Start a tenant-scoped runtime using [runtime bootstrap](../../orc-runtime-bootstrap/SKILL.md), with the same provider alias in execution and processor contexts. This example assumes that `ctx` already contains those runtime capabilities. Choose a supported model and endpoint; the tested endpoint is an example, not a universal routing default.

```clojure
(require '[ai.obney.orc.llm.interface :as llm]
         '[ai.obney.orc.orc-service.interface :as bt])

(llm/register-provider! :extractor
  {:provider :openrouter
   :model "deepseek/deepseek-v4.1-flash"
   :output-mode :json-schema
   :config {:api-key (System/getenv "OPENROUTER_API_KEY")}})

(def definition
  (bt/workflow "extract-supplied-facts"
    (bt/blackboard
      {:request [:string {:description "All supplied facts and the requested extraction."}]
       :extraction-rationale [:string {:description "Brief factual basis; distinguish supplied facts from unavailable information."}]
       :answer [:map {:closed true :description "Only facts supported by the request."}
                [:source-id [:string {:description "The exact supplied source identity."}]]
                [:status [:maybe {:description "The supplied status, or null when no status was supplied."} :string]]
                [:reference {:optional true} [:string {:description "A supporting reference only when supplied."}]]]})
    (bt/llm "extract"
      :instruction "Extract the supplied facts and explain their basis. Do not infer missing information."
      :reads [:request] :writes [:extraction-rationale :answer]
      :retry {:max-attempts 1 :backoff-ms []}
      :options {:max-retries 0 :max-tokens 32768 :timeout-ms 300000
                :extra-body {:provider {:only ["InferenceNet"] :allow_fallbacks false}}})))

(def sheet-id (bt/build-workflow! ctx definition))
(def result
  (bt/execute ctx sheet-id {:request "Source SAMPLE-17; no status or reference supplied."}
              :timeout-ms 360000 :max-ticks 1 :llm-call-budget 1))
```

The large completion cap is a ceiling for this reasoning-model example; configure it for the actual model and task. Inspect actual HTTP and durable writes rather than treating this declaration as proof of successful generation.

## Provider observations

### Three separate evidence channels

1. **Declared rationale:** a described blackboard output, requested as part of the task and composed through normal reads/writes.
2. **Provider-returned observations:** the provider's returned reasoning text, summary, opaque details, refusal, finish reason, response/model identity and reported usage, when available.
3. **Canonical attempt outcome:** whether that owned provider attempt passed or failed the execution contract.

A rationale key does not collect the provider channel. Provider observations are not complete internal model reasoning and do not prove facts, permission, tool execution or business completion. Preserve text, summary and opaque/encrypted details distinctly; do not decode encrypted blocks or feed them to reviewers as readable thinking.

On the repaired blocking conversational prediction path, `:with-metadata? true` includes `:provider-observations` alongside outputs/usage/model/raw-response. Post-response parsing or validation exceptions retain that observation envelope in exception data. Failures before a response exists must not fabricate observations. `:raw-response` still refers to the response content channel; it is not the complete provider message or serialized request.

The OpenRouter response adapter preserves normalized returned reasoning/detail/refusal fields and usage details. The observation envelope is allowlisted and excludes request headers and arbitrary request dumps. Exact outgoing HTTP body verification remains a separate instrumented transport boundary.

### Query exact execution-owned attempts

Ordinary model execution persists provider attempt starts and outcomes through Grain commands. Each record belongs to a canonical node-start event, sheet/tick/node, execution context, node-attempt ordinal, provider-attempt ordinal and invocation UUID. Select the real execution; identical model text or response IDs cannot identify parallel calls reliably.

```clojure
(bt/provider-attempts ctx
  {:tick-id tick-id
   :node-start-id node-start-event-id
   ;; Optional: narrow to one admitted invocation.
   :invocation-id invocation-id})
```

The result is a Grain query response; inspect `:query/result` or the returned anomaly. Rows contain ownership fields and ledger `:status`. A completed row carries `:outcome`, whose own `:status` is success or failure in the current `execute-ai` integration and may include `:provider-observations`. The ledger schema also permits timeout/cancelled outcomes; that schema capacity is not proof that every timeout or cancellation path records one. A completed failed attempt is still a completed ledger record. Native decision or other paths without provider observations must leave that field absent.

A start records an admitted invocation before dispatch. Local preflight can still fail after admission and before any HTTP request, so a start or invocation count is not automatically a physical HTTP-attempt count. Correlate actual transport dispatch separately. An outcome is durable before retry/return. If start persistence fails, no call should dispatch. If outcome persistence fails after a call, the start remains pending and execution fails without automatically repeating the call. A `:started` row therefore means no durable terminal outcome is known; it does not establish that no provider work occurred, that the model is still running, or that retry is safe.

Observation usage is evidence, not a second accounting stream. Continue using canonical ORC usage/family accounting for totals; do not add the observation's usage again. Reasoning tokens may already be included in completion tokens. Preserve reported breakdowns without inventing unavailable counts.

Embedded ledger capabilities require the trusted runtime's tenant context. Public Grain request adapters additionally enforce the registered authorization predicate. Do not manufacture authentication claims inside ordinary tree processors to satisfy a request-facing rule, and do not treat direct command/query calls as authorization-enforcing HTTP adapters.

### Select downstream context explicitly

An authored code leaf may query the exact attempt and select a typed, described evidence packet for the next specialist. Include execution/response provenance, observation kind, finish/outcome status and the relevant readable explanation. Give the specialist its actual artifact, goal, qualified source facts and task criteria alongside that packet. Avoid dumping all provider metadata into every blackboard read.

Resolve the producer structurally: obtain its node ID from `bt/get-nodes-for-sheet` after building the sheet, and retain it in the host context available to execution and processors. For a workflow with exactly one model leaf, select that sole `:executor :ai` node and reject a non-singleton result. With several producers, retain each explicitly identified node ID. In recursive or parallel work also supply the producer's exact execution context; do not choose a start by response text, node name alone, or whichever event happened last.

This helper selects the unique successful invocation for an exact producer execution. A recovery policy inspecting a failed invocation should instead select its explicit invocation ID and preserve its failure outcome.

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as event-store])

(defn select-provider-explanation [ctx tick-id producer-node-id execution-context]
  (let [read-result (event-store/read (:event-store ctx)
                                     {:tenant-id (:tenant-id ctx) :tags #{[:tick tick-id]}
                                      :types #{:sheet/node-execution-started}})
        _ (when (and (map? read-result) (:cognitect.anomalies/category read-result))
            (throw (ex-info "Canonical start read failed" read-result)))
        starts (filter #(and (= :sheet/node-execution-started (:event/type %))
                             (= producer-node-id (:node-id %))
                             (= execution-context
                                (bt/value-log-exec-context (:inputs %))))
                       (into [] read-result))]
    (when-not (= 1 (count starts))
      (throw (ex-info "Producer execution is missing or ambiguous" {})))
    (let [node-start-id (:event/id (first starts))
          query (bt/provider-attempts ctx {:tick-id tick-id :node-start-id node-start-id})]
      (when (:cognitect.anomalies/category query)
        (throw (ex-info "Provider observation query failed" query)))
      (let [successful (filter #(and (= :completed (:status %))
                                    (= :success (get-in % [:outcome :status])))
                               (:query/result query))]
        (when-not (= 1 (count successful))
          (throw (ex-info "Successful provider invocation is missing or ambiguous" {})))
        (let [row (first successful)
              outcome (:outcome row)
              observation (:provider-observations outcome)
              choice (first (:choices observation))]
          {:kind :provider-returned-explanation
           :tick-id tick-id :node-start-id node-start-id :invocation-id (:invocation-id row)
           :outcome :success
           :observation-available (contains? outcome :provider-observations)
           :response-id (:response-id observation)
           :resolved-model (:model observation)
           :finish-reason (some-> (:finish-reason choice) name)
           :reasoning-present (contains? choice :reasoning-content)
           :reasoning-details-present (contains? choice :reasoning-details)
           :reasoning-details-nonempty (boolean (seq (:reasoning-details choice)))
           :reasoning-text (:reasoning-content choice)})))))
```

Declare the packet as a concrete blackboard key, for example:

```clojure
[:map {:closed true :description "Qualified explanation from one exact successful provider invocation; not proof of task correctness."}
 [:kind [:enum :provider-returned-explanation]]
 [:tick-id [:uuid {:description "Producer tick identity."}]]
 [:node-start-id [:uuid {:description "Canonical producer node-start event identity."}]]
 [:invocation-id [:uuid {:description "Exact admitted provider invocation identity."}]]
 [:outcome [:enum {:description "Validated attempt outcome, not business success."} :success]]
 [:observation-available [:boolean {:description "Whether the invocation has a returned observation envelope."}]]
 [:response-id [:maybe {:description "Provider response identity, if returned."} :string]]
 [:resolved-model [:maybe {:description "Model identity reported in this response."} :string]]
 [:finish-reason [:maybe {:description "Reported provider finish reason, if returned."} :string]]
 [:reasoning-present [:boolean {:description "Whether reasoning text was present, including an explicit null."}]]
 [:reasoning-details-present [:boolean {:description "Whether structured reasoning details were present."}]]
 [:reasoning-details-nonempty [:boolean {:description "Whether details contain any blocks; blocks can be opaque and remain in the ledger."}]]
 [:reasoning-text [:maybe {:description "Verbatim provider-returned text when available; absent and explicit null are distinguished by reasoning-present."} :string]]]
```

The selector code leaf returns `{:provider-explanation (select-provider-explanation ...)}` and declares `:writes [:provider-explanation]`. The receiving leaf declares that key in `:reads` alongside its task and artifact inputs. Pass producer identity through a trusted host capability or an explicitly typed execution record; do not ask a model to invent event IDs. An execution context is the value from `bt/value-log-exec-context` for that producer, including its iteration/map identity, not an arbitrary empty map. This helper is deliberately strict about ambiguous repeated starts.

Ordinary rationale composition remains useful even when no provider reasoning is returned. Missing or opaque-only reasoning should be represented explicitly; neither means an empty rationale was generated. Provider observations do not automatically become blackboard outputs, and naming a write `reasoning` does not create such a mapping.

### Scope boundaries

These repairs establish blocking conversational prediction metadata and ordinary execution attempt capture. Do not claim native decision prose, streamed provider-reasoning capture or a complete researcher prime/inline/generated-call lifecycle from those proofs. A native decision protocol retains its own finite answer/probability evidence. Generated or researcher calls need their own actual canonical execution-owner and capture-path verification. Strict JSON streaming remains unsupported. Verify every additional execution path before expanding the skill's claims.


## Ordinary LLM outputs can include described enum choices

An ordinary `llm` node can write prose, enum decisions and other supported structured fields in the same response. Native decision nodes are useful when finite answers alone suffice; they do not own enum types. For example, declare an assessment with `[:enum {:description "Whether the supplied evidence settles this obligation" :descriptions {"supported" "Actual evidence establishes the named criterion" "insufficient" "A required observation is missing"}} "supported" "insufficient"]` alongside a described rationale or follow-up-question field.

The repaired strict compiler preserves per-option `:descriptions` as model-visible meanings in the same schema/guide used for enforcement. Outer field and nested entry descriptions coexist with the option meanings. Wire membership and exact enum decoding stay unchanged. This includes supported nested, optional and nullable enum structures; arbitrary unsupported schema shapes remain unsupported. Inspect the consuming source: an earlier strict compiler rejected this annotation before dispatch even though native decisions accepted it. Do not remove option meanings to bypass that error or force field names with prompt hacks. Update the compatibility boundary, then verify the actual request and canonical output. This enum-description support is present in the SIO revision pinned by the merged snapshot above; retain dependency verification for older consumers.
