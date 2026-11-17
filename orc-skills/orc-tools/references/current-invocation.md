# Current tool invocation contracts

Source-inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`: public `dsl/tool`, `dsl/code`, executor `execute-tool`/`execute-code`/`resolve-node-tool-caller`, and `core/tool_invocation.clj`. Recheck consuming source and exercise public ticks before treating an example as integration proof.

## One fixed tool call

```clojure
(orc/tool "find-source"
  :tool "search"
  :reads [:query :limit]
  :writes [:search-result]
  :tool-contracts
  {"search" {:arguments [:map {:closed true}
                          [:query [:string {:min 1 :description "Search expression"}]]
                          [:limit [:int {:min 1 :max 8 :description "Maximum candidates"}]]]
             :result [:map [:hits [:vector [:map
                                            [:id [:string {:description "Opaque identity returned by discovery"}]]
                                            [:summary [:string {:description "Candidate summary, not full-source evidence"}]]]]]]}})
```

Register described blackboard schemas for query, limit and search-result. Provide a trusted runtime `:call-tool-fn` or declared `:tool-caller-fn` builder. The caller receives `"search"` and `{:query query :limit limit}`. Nil reads are omitted; non-nil false/zero are retained. The tool name is authored, never taken from the blackboard. Exactly one write is required.

The declared result schema validates raw caller return. If it returns `{:search-result value}`, declare the wrapped schema; the leaf then writes `value`. If it returns another map, the whole map is written. This unwrapping is based on the declared write key, not generic `:result` conventions.

## Nested arguments and explicit composition

```clojure
(defn retrieve [{:keys [inputs call-tool-fn]}]
  (when-not call-tool-fn
    (throw (ex-info "A retrieval caller is required" {})))
  {:source (call-tool-fn "read-source" (:read-args inputs))})

(orc/code "read-source"
  :fn "app.tools/retrieve"
  :reads [:read-args]
  :writes [:source]
  :tool-caller-fn "app.tools/build-caller"
  :tool-contracts
  {"read-source" {:arguments [:map {:closed true}
                              [:source-id [:string {:description "Opaque discovered source identity"}]]]
                  :result [:map [:id :string] [:content :string]]}})
```

`build-caller` is a trusted application FQN taking `(blackboard context)` and returning `(fn [tool-name args] ...)` or context-aware three-argument form. Its context carries node/tick/deadline identity. Under checkpointed researcher-generated effects the actual host caller must accept `(tool-name args tool-context)`; the contract wrapper's apparent arity does not satisfy this requirement.

Use the supplied built-in `:call-tool-fn`; the older custom `:app/call-tool!` shim is not required. The host dispatches only permitted catalog IDs and checks authority and domain constraints. Schemas do not authorize calls. Enforced validation preserves original values; it does not normalize arguments into different caller payloads.

An ordinary authored tool invokes its resolved caller with two arguments. If the trusted host requires inherited context, the builder must close over it and forward it explicitly; logging the builder's context proves only that the builder received it. For example:

```clojure
(defn build-caller [_blackboard context]
  (when-let [host (:call-tool-fn context)]
    (fn
      ([tool args] (host tool args (:tool-context context)))
      ([tool args effect-context] (host tool args effect-context)))))
```

The explicit three-argument branch preserves checkpoint effect identities when that path supplies them. Verify the actual host body receives the expected context through every delegate level. Returning an unchanged three-argument caller from a builder does not adapt the ordinary two-argument invocation.

At this pin a code leaf with zero declared writes fails even if its function returns an empty map. Use a `condition` for a typed predicate, or return a described, non-nil verification fact from a code node and declare its write. A no-output procedural guard is not a supported successful code leaf here.

## Failure boundaries

- Invalid arguments never dispatch. Invalid result is rejected after dispatch; prior effects still exist.
- Contract violations are structured `ex-info` with `:orc.tool/outcome` and tool identity. Code may catch and deliberately translate them.
- Code/tool host exceptions retain their original message at the consumer boundary; sanitize sensitive failures before throwing.
- Researcher failures become safe model-facing error data; only `:orc.tool/message` deliberately exposes host text.
- An error-looking returned map is not automatically a failed code leaf. Interpret command rejection or tool-specific failure before writing successful evidence.
- Check literal MCP `isError=true` before treating a result as evidence, including when otherwise valid data fields are present. A schema failure on an error-only envelope does not prove the flag is handled.
- Absent contracts or `:untyped` remain pass-through. Missing caller is an explicit tool failure, not a fake success.

## Recursive and parallel usage

Delegates inherit tool context/caller/MCP session and effective family time/call budget, while blackboards remain isolated. Prove actual inherited caller and trusted identity through child ticks. Use `get-family-usage` for registered model accounting. Code can call multiple tools, but explicit leaves or delegates make useful intermediate observations, recovery and budget ownership inspectable.

Parallel branches require independent output keys, compatible effects and a declared completion policy. Verify overlap with actual caller start/end evidence, then verify both projected outcomes. Parent early success does not cancel effects already started. Execution leases protect ordinary recovery ownership; they are not durable pending judge records or external exactly-once guarantees.

## Invocation versus cancellation and relevance

Actual tool calls, native enforcement and nominal recursive/parallel effects worked. Family cancellation still has a trace/publication gap even when budget admission correctly refuses the excess call. Researcher tool success also does not establish that generated selection used relevant evidence. Read [current proofs and limits](current-proofs-and-limits.md) and keep strict projected postcondition checks.
