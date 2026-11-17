# Researcher setup and contracts

## Start a real runtime

Use the standalone public dependency/runtime recipe in [runtime bootstrap](../../orc-runtime-bootstrap/references/runtime.md). A researcher still requires the ordinary Grain v3 tenant store, projection cache, registries, async processors, provider registration and completion/trace queries. SQLite is useful for single-process durable experiments; production topology decides the store. Direct reads/appends require the tenant. Build definitions through ORC, rather than append imitation node events.

Provision JVM heap, LMDB projection map size/readers, request/body budgets and timeouts independently. Grain's 10 MiB default cache can fill during reconstruction. A larger heap does not enlarge LMDB. SCI shares the JVM; no ORC researcher-specific 10 MiB heap setting was found. An external REPL/sandbox has its own limits. Use isolated cache/index directories, track owned processes and drain workers before closing stores.

Enable the registered recovery periodic task and startup recovery when restart matters. Reconstruct the same tenant and trusted capabilities against the original store. A REPL client timeout is an observer timeout until canonical execution says otherwise; investigate the original tick before retrying a side effect.

## Public authored configuration

The example assumes a running `ctx` and registered provider; model and limits are configurable application choices. `orc` aliases `ai.obney.orc.orc-service.interface`.

```clojure
(def packet-schema
  [:map {:closed true :description "One source packet with stable provenance."}
   [:id [:string {:description "Opaque source identity; preserve unchanged."}]]
   [:content [:string {:description "Complete source evidence, not a generated summary."}]]])

(def analysis-schema
  [:map {:closed true :description "A grounded assessment of the supplied evidence."}
   [:source-ids [:vector {:description "Every source actually used."} :string]]
   [:report [:string {:description "Findings supported by those sources; identify missing evidence."}]]])

(defn research-definition [model limits]
  (orc/workflow "evidence-research-v1"
    (orc/blackboard
      {:packets [:vector {:description "Source packets available for adaptive examination."} packet-schema]
       :analysis analysis-schema})
    (orc/repl-researcher "investigate-evidence"
      :model model :reads [:packets] :writes [:analysis]
      :instruction
      "Inspect full packets, preserve source identity, and choose the needed analysis steps from the evidence. Use typed generated nodes for semantic work, bounded independent fan-out, and synthesis after dependencies join. Inspect actual child results, repair missing work only, and final! the complete analysis. Never invent sources."
      :max-iterations (:iterations limits)
      :options {:max-tokens (:prime-output-tokens limits) :max-retries 0}
      :rlm {:recursive? true :checkpointed? true :timeouts (:timeouts limits)})))

(def limits
  {:iterations 6 :prime-output-tokens 8000
   :timeouts {:provider-ms 90000 :iteration-ms 120000
              :phase2-ms 180000 :campaign-ms 300000}})

;; Construction and execution go through commands and real processors.
;; Inputs below come from the application, not invented example answers.
(let [sheet-id (orc/build-workflow! ctx (research-definition model limits))]
  (orc/execute ctx sheet-id {:packets source-packets}
               :tick-id (random-uuid) :timeout-ms 360000 :llm-call-budget 20))
```

The prime advertises declared final writes. Include essential input meaning and any non-obvious intermediate shape in described data/instructions; confirm serialized visibility. Do not assume it has seen every input field description merely because that description exists in a schema.

When a generated map is likely, supply the exact original item schema and explain the augmented aggregate shape in the researcher's instruction or retrieved design context. Use the complete contract placement in [palettes](palettes.md), including the fresh `:as` key. Teaching a model that schemas are required is weaker than giving it the actual schema it must preserve. For a small coherent input, a single typed semantic child can be appropriate; choose fan-out for independent work or context needs rather than requiring a map everywhere.

The node accepts `:mcp-tools`, `:tool-contracts`, `:tool-caller-fn`, `:browser-tools`, `:max-iterations`, `:rlm`, `:context`, and `:options` in addition to ordinary model/instruction/reads/writes. Supply `:browser-tools []` when browser capabilities are not needed. Optional reuse config is covered by [pattern reuse](../../orc-researcher-pattern-reuse/SKILL.md).

At this pin, authored `:context` is structured ontology/injection configuration, not an arbitrary prose string. Put task construction guidance in `:instruction` or appropriate declared inputs. Validate through `build-workflow!`, inspect the registered node, then confirm the actual serialized request; constructor success alone does not show runtime visibility.

## Bound tools with usable contracts

```clojure
{:mcp-tools ["source/retrieve"]
 :tool-contracts
 {"source/retrieve"
  {:arguments [:map {:closed true :description "Retrieve an already discovered source; not a search tool."}
               ["reference" [:string {:min 1 :description "Exact opaque reference returned by source discovery."}]]]
   :result [:map {:closed true}
            [:reference [:string {:description "Same source reference."}]]
            [:content [:string {:description "Authoritative complete source content."}]]]
   :checkpoint-safe? true}}}
```

Contracts have exactly arguments/result/checkpoint-safety fields at this pin; a standalone contract `:description` is rejected. Put purpose/applicability in schema properties and a model-visible capability catalog/instruction. Avoid permissive legacy argument-name specs. Verify invalid args are rejected before host dispatch and invalid results before they become observations.

The trusted host caller receives `(tool-name argument-map tool-context)`. It enforces name allowlisting, tenant/authority, argument/result contracts and effect policy. Current checkpoint mode requires three-argument arity and explicit safety. Verify actual operation identity and timeout fields at the invocation surface used; generated callers do not necessarily receive the inline timeout field. Close over the trusted original campaign deadline and enforce remaining time at the host body. Read [tools](../../orc-tools/SKILL.md) for caller-builder registration and public guarded invocation.

Install the trusted caller in the context used to start processors. Processors retain their startup capabilities; associating a replacement caller onto a later execute context does not reconfigure the already running workers. Verify the host implementation actually reached by a generated call before diagnosing an unknown-tool error.

For generated code the execution map contains `:inputs`, `:call-tool-fn` and `:tool-context`. Call with all three arguments. Obtain dynamic references/parameters from preceding observations; never invent an identifier or silently replace missing data with a constant. Deterministic exact transforms may be authored on demand, but mutations use authorized Grain commands/capabilities and verified projections.

Handle [tool failure surfaces](../../orc-tools/references/researcher-tools.md#handle-failure-at-the-invocation-surface-actually-used) explicitly: named inline tools return error data while the injected generated-code caller can throw. A try/catch around the named tool does not catch a returned failure map. Code acceptance/receipt nodes need a concrete write at this pin; an empty writes list fails even with an empty-map result. Put fixture truth in experiment verification and derive reusable checks from actual inputs/observations.

For an authored reusable specialist, build this child first, then pass its ID to an authored `delegate` with exact read/write keys. That isolates blackboards and records child lineage. A declared final write need not be every internal diagnostic key. Verify downstream consumption of the parsed typed value, not an EDN/prose summary substituted for it.
