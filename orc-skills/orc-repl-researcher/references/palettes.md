# Three palettes, three kinds of execution

| Surface | Intended use | Current boundary |
| --- | --- | --- |
| Authored public ORC DSL | Guaranteed outer methodology, routes, tools, specialists | Full authored control including fallback/conditions/decisions/delegate/researcher |
| Inline SCI helpers | Inspection, bounded subcalls, mechanical processing within a reasoning turn | Helpers include llm/sequence/parallel/map-each/fallback/condition/code; helper execution is not identical to an emitted durable sheet |
| Emitted vector tree | Model-created work with real child ticks/node events | sequence, parallel, map-each, llm, code, final, chunk-document, aggregate |

The emitted compiler does not accept delegate, nested researcher, fallback, condition, llm-decision or tool tags at the inspected pin. Older guide examples list some unsupported tags. Verify `core/rlm_dsl.clj` and `core/rlm_tree_executor.clj` in the consuming revision. Use authored structure around adaptive work and bound tools inside generated code. A finite structured `:llm` result is possible, but that does not establish native decision-provider support in this compiler.

## Inspect full values rather than guess from previews

`get-input` reads declared full inputs; `store!`/`get-var` keep computed values; `list-vars` exposes variable previews. Full documents remain outside the prime prompt until inspected or passed to subwork. Inline sub-LLM reads currently use full values despite one contradictory preview sentence in the module. Inline sub-LLM output fields use generic contracts, unlike specific emitted `:output-schemas`; use the latter or an explicit validation boundary when strong typing is required.

Schemas must be serializable Malli forms. Do not place runtime predicates or captured closures in persisted definitions. Every new emitted write needs a concrete schema. Declare map `:as` binding and `:into` collection schema on map options as well as leaf schemas. `:max-concurrency` is the emitted option; authored map uses `:parallel`. Do not copy historical `:max_concurrency` spelling.

For map input items, the aggregate holds augmented originals, e.g. `{:id ... :content ... :review {...}}`, when the leaf writes `:review`. Define the actual augmented schema and extract named reviews after collection completes. Verify identities, duplicates, count and item execution context; successful last-item output is not the collection.

Use `get-input` only for original blackboard keys. It unconditionally copies the blackboard value into the sandbox, including nil for an absent key. Calling `(get-input :tree-results)` therefore erases computed history at this pin. Read history and all stored/generated values with `get-var`. A live false drill-down alarm was traced to this exact consumer error; the primitives themselves worked under checkpoint resume.

Local SCI bindings do not become child inputs. If a reasoning turn computes a missing-item vector in a `let`, call `store!` on that vector before emitting a tree that reads it. `emit-tree!` accepts one tree argument; an invented second argument map does not inject child inputs. The generated child receives referenced sandbox values, not the reasoning turn's lexical scope. Keep the stored name, emitted `:from`/`:reads`, producing `:writes` and later extraction name identical; a misspelled key can silently yield nil or an empty collection.

## A complete emitted map contract

This data example shows where all schemas belong; adapt the evidence/criterion to the goal. Do not omit the item binding because the input collection already has a schema. The compiler requires that fresh key independently. Built-in examples at the inspected pin do not consistently show it.

```clojure
[:map-each
 {:from :packets :as :packet :into :reviewed-packets :max-concurrency 2
  :output-schemas
  {:packet [:map {:closed true :description "One original source packet."}
            [:id :string] [:content :string] [:quantity :int]]
   :reviewed-packets
   [:vector {:description "Original packets augmented with each completed leaf's named writes."}
    [:map {:closed true}
     [:id :string] [:content :string] [:quantity :int]
     [:review-rationale :string]
     [:review [:map {:closed true}
               [:id :string]
               [:readiness [:enum "ready" "review-required"]]
               [:reason :string]]]]]}}
 [:llm
  {:instruction "Review the full packet against the stated release requirements, preserve its identity, and identify actual missing evidence."
   :reads [:packet] :writes [:review-rationale :review]
   :output-schemas
   {:review-rationale [:string {:description "Brief evidence-based assessment before the result."}]
    :review [:map {:closed true :description "One semantic assessment of this source packet."}
             [:id [:string {:description "Exact original identity."}]]
             [:readiness [:enum {:description "Ready only when all release requirements are evidenced."} "ready" "review-required"]]
             [:reason [:string {:description "Located evidence or missing prerequisites."}]]]}}]]
```

The consuming goal must actually supply those release requirements. Both emitted and authored maps identify the collection with `:from`; emitted concurrency is `:max-concurrency`, whereas the authored constructor's option is `:parallel`. A following code node can extract `(mapv :review (:reviewed-packets inputs))`; run it after the map join and declare its write schema. Mechanical extraction does not author the reviews.

For partial work, inspect item failures through events or drill-down: the ordinary result can compact away failed items and omit top-level failure indices. Before a downstream report is treated as complete, compare identity frequencies and cardinality against the original input. A code coverage gate may reject an incomplete collection and report the missing identities. This literal identity check is not a semantic message gate. Store the missing items, repair only them, merge verified results, and check coverage again before synthesis/finalization. A nonempty report over two successful items does not complete a three-item request.

## Source-bearing emission

```clojure
;; Evaluated inside the researcher SCI environment.
(emit-tree!
 (quote
  [:sequence
   [:code {:fn (fn [{:keys [inputs]}]
                 {:quantity-total (reduce + (map :quantity (:packets inputs)))})
           :reads [:packets] :writes [:quantity-total]
           :output-schemas
           {:quantity-total [:int {:description "Exact sum of observed packet quantities."}]}}]
   [:final {:keys [:quantity-total]}]]))
```

This illustrates literal arithmetic, not a complete semantic research method. The fresh key has a described schema and the original function form survives quoting. Use a registered fully qualified host function for reviewed reusable effects. Do not emit already evaluated function objects or assume local `defn` persists into another iteration/JVM.

`emit-tree!` hands generated work back to the engine. Recursive mode returns another reasoning turn with child evidence. `[:final {:keys [...]}]` marks child outputs; researcher `(final! {...})` completes the declared outer contract. These are different completion boundaries. Inspect actual results between them. A preflight-rejected tree had no child execution; do not call it completed work or accept the source vector as proof.

Generated LLM `:model` and node `:retry` are forwarded at this pin, but per-leaf `:options` are not. Inspect [source status](source-status.md). Prime options remain a different path. Do not claim a generated token/temperature/provider-retry policy from source text alone.
