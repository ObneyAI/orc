# Give the runtime model the construction contract

The agent authoring a researcher and the model running that researcher have different contexts. Reading an agent skill does not load it into the runtime prime model. Put the applicable construction rules, exact local schemas, tool catalog and completion criteria in the researcher's instruction, declared inputs or supported context. Inspect the serialized request to confirm they arrived.

Teach the model how to assemble supported work. Let it choose the actions, decomposition and semantic conclusions from the actual task and observations. An example of executable syntax is guidance; a supplied answer, fabricated observation or canned analysis is not.

## What the prime needs before its first emission

| Boundary | Model-visible information |
| --- | --- |
| Goal | Observable result, required evidence, honest unknowns, allowed effects and final write contract. |
| Inputs | Exact item schema, identity and reference meanings, where full values can be inspected. |
| Generated palette | Supported emitted tags, distinct from authored DSL and inline SCI helpers. |
| Code | Quoted reconstructible function source; execution-map bindings; return a map under every declared write key. |
| Tools | Exact allowed name, purpose, applicable situation, argument/result schemas, authority, failure protocol and replay policy. |
| Map | Schema for the fresh `:as` key, schema for the `:into` collection of augmented originals, primitive child with explicit writes and bounded concurrency. |
| Models | Semantic work belongs in typed LLM children; node-specific rationale first; actual supported model and options boundary. |
| Repair | Inspect accepted child results, retain successful values/effects, target failed work, rejoin and verify complete identity coverage. |
| Completion | Child `:final` exposes child values; outer `final!` returns the native declared result only after checking it. |

Supply exact relevant schemas, rather than only saying “use schemas.” Describe nested fields and collection meanings; forbid `:any` when it would hide the task's real contract. Keep a runtime catalog equal to the trusted caller's actual contracts. Do not rely on an unrelated project's examples, or on a built-in example that contradicts the inspected compiler.

## A primitive tool map with complete schemas

The following is a construction example for an already bound `evidence/read` capability. It neither registers that tool nor supplies task observations. The application must provide its real described contract and trusted three-argument caller. `:items` must have been stored from actual discovery before emission.

```clojure
;; Evaluated by the researcher. Schemas remain data; only function source is quoted.
(let [id [:string {:min 1 :description "Opaque source identity, preserved exactly."}]
      ref [:string {:min 1 :description "Exact retrieval reference obtained from discovery."}]
      item [:map {:closed true :description "One discovered source and its authority reference."}
            [:source-id id] [:reference ref]]
      observation
      [:map {:closed true :description "Authoritative evidence returned for the requested source."}
       [:source-id id]
       [:quantity [:int {:description "Exact observed quantity; no model arithmetic."}]]
       [:content [:string {:description "Complete source evidence for later semantic work."}]]]
      augmented
      [:map {:closed true :description "Original item plus its named successful observation."}
       [:source-id id] [:reference ref] [:observation observation]]]
  (emit-tree!
   [:sequence
    [:map-each
     {:from :items :as :item :into :observed-items :max-concurrency 2
      :output-schemas
      {:item item
       :observed-items
       [:vector {:description "Original source items augmented with completed observations."}
        augmented]}}
     [:code
      {:reads [:item] :writes [:observation]
       :output-schemas {:observation observation}
       :fn
       '(fn [{:keys [inputs call-tool-fn tool-context]}]
          (let [item (:item inputs)
                result (call-tool-fn "evidence/read"
                         {:source-id (:source-id item) :reference (:reference item)}
                         tool-context)]
            (when-not (= (:source-id item) (:source-id result))
              (throw (ex-info "Requested source identity was not returned"
                             {:kind :source-identity-mismatch})))
            {:observation result}))}]]
    [:final {:keys [:observed-items]}]]))
```

Use the actual source fields and result contract. A well-formed observation for a different source fails provenance. A tool returning literal operational error data needs explicit failure handling; it is not an unknown or negative domain fact. A thrown caller error fails the generated leaf. Keep useful error identity without exposing sensitive host messages.

Verify this example through the consuming compiler before using it as a model's reference. Quoting the function separately lets the surrounding tree contain computed schema data. Quoting the whole literal tree is another supported source-bearing form. Do not quote a tree that still refers to local schema variable names: the quote preserves those names as symbols rather than inserting their schema values. Neither form makes a runtime closure durable. See [palettes](palettes.md).

## Compose stages around primitive maps

A parallel map's child must be a primitive code or LLM leaf with explicit writes. Put multistep work into successive joined stages: gather observations, gather an independent status if needed, join by exact identity, perform semantic assessment, then compute/synthesize the aggregate. Compatible deterministic tool calls may share one code leaf when they intentionally share a failure/retry unit; that leaf must return its complete named result. If a later call can fail and the earlier observation must survive, publish/join the earlier write in a separate stage first. External success alone does not preserve a value that the failed leaf never returned.

Do not put a sequence/fallback under a parallel map and depend on implicit composite writes. That collection seam can misattribute values. Use an authored delegate for a reusable complex specialist when the authored surface is appropriate; emitted delegation is unsupported at the inspected pin.

For a later semantic map, preserve the complete augmented input schema and add the leaf's named rationale/result writes to its aggregate schema. A `[:map ...]` result containing a field named `:reasoning` is different from declaring the LLM's leading rationale write. Show the real output order and contracts in the request.

Carry authoritative IDs, references, quantities, source text and observed status through deterministic joins. Have semantic children add their own rationale, interpretation and evidence limitations under separate named writes. Assemble the final source record from original observations plus those judgments; compute arithmetic from the observed quantities. This keeps the ownership of facts explicit and gives repair a clear seam. When a model must produce an optional/nullable value, verify its actual wire schema and parsed native value before relying on it.

Require each semantic child to declare its node-specific rationale as the first write, with a concrete described schema, then its substantive output. Preserve meaningful map/collection descriptions as well as leaf field descriptions. Inspect accepted compiled contracts and the emitted model request: a model can generate working code while omitting requested descriptions or rationale. Give an explicit model assignment when the stage requires one, and verify the effective supported options separately from source options.

After a partial map, derive missing identities from actual input coverage and item events. Preserve the complete successful item records before planning the next tree. Repair only missing/failed work and its dependents, merge by identity, and recheck frequency and cardinality. A nonempty compacted vector is insufficient completion evidence.

Completion checks need actual execution. Before delivery/finalization, compare expected and observed identity frequencies/cardinality, source/reference provenance, required nonempty values, and independently computed arithmetic. Record that verification result through a declared write or domain guard. A model's statement that it checked coverage, or a final call that merely returns stored values, is insufficient proof. Interpret prose semantically against full evidence; do not use matching words or phrases as a substitute for that review.

## Prevent misleading recovery

An emitted source string is not proof of accepted compilation or execution. A preflight rejection may have no child tick or nodes to inspect. Confirm the actual rejection and schemas before calling it a tool failure or diagnosing code scope.

At the inspected revision, precise preflight causes can be absent from the next prime history. A model may then speculate that generated tools are impossible or move all work into serial inline calls. Preserve that failed run and the actual source; do not accept the speculation as an ORC limitation. The supported generated tool seam is the injected `call-tool-fn` above.

Treat original `:generated-tree-source` as executable-source evidence. A normalized serialized tree can turn list forms into vector-shaped data for storage/display. Compare the original source and actual compiler outcome before diagnosing invalid parentheses from a preview.

Read [source status](source-status.md) for current compiler/recovery gaps. Check task values, real tool receipts, accepted source, compiled schemas, model requests and native final output separately. An instruction being present does not prove the model followed it.
