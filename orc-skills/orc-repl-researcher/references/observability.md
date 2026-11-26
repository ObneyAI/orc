# Observe the complete researcher loop

Keep four linked records: the original goal/source/contract, each prime reasoning/code iteration, emitted source plus compiled child execution, and final task/effect acceptance. None substitutes for the others.

## Durable and public evidence

Use the tenant-scoped event store and public ORC/Grain queries. At the inspected pin:

- `:rlm/researcher-iteration-recorded` retains immutable generated code, emitted source/tree where present, status, timing, profiles, usage and variable deltas.
- `:rlm/researcher-resume-state-saved` retains reconstructible state/snapshot or delta and integrity/epoch context.
- Frontier claims, effect claimed/completed/indeterminate facts, action completions and provider reservations establish the accepted account.
- Child tree start/completion, node start/completion and execution-value facts establish actual work and its values.
- `:sheet/execution-traced` and public trace/node details are projections of canonical facts. Value references/file descriptors need canonical resolution before interpretation.

Reconcile parent researcher, generated child, authored delegates and original domain events. Domain operation facts may not carry tick tags; join their effect identity explicitly. Verify trace settlement rather than assume the returned execution result means every asynchronous projection is complete.

Use the current event names and public query shapes. At the inspected pin child starts use `:sheet/tree-tick-started`; a collector looking for an older `:sheet/tick-started` name can silently produce no child evidence. Assert that expected ticks/node traces are present before comparing them. `every?` over an empty audit is not proof of lineage, schemas, or correct model attribution. Read the compiled blackboard descriptions as well as types: an emitted model can omit descriptions or a leading rationale despite the instruction.

DSL names are not necessarily projected field values: generated LLM work is a `:leaf` with executor `:ai`, while a researcher is identified through the projected node's `:type`. Confirm the actual node schema before filtering a trace. A zero-count audit can be a wrong selector rather than absent execution.

For parallel work, inspect overlapping node/dispatch intervals or an injected host concurrency counter. Source tags or two threads alone do not prove concurrent execution. For map work retain item identity/index/path/attempt; compare the whole collection with an independent coverage oracle. The last node value is not the map result.

## Prime observations and drill-down

The prime uses previews and a compact history. Full input and sandbox values are separate. Use `get-input` for declared input keys and `get-var` for stored/generated keys and `:tree-results`; unknown `get-input` currently overwrites a same-named computed variable with nil.

Drill-down can supply failures, full tree detail, trajectory, node output and node input profile. Detail's `:nodes` is a vector of maps with `:node-id`, status and **`:writes` map**. Ordinary node output is the write map; a parse-failure output may be an envelope with writes, untruncated raw response and error. Keep result shapes explicit. A node-ID-only helper does not fully identify repeated map attempts; use canonical events/public instance queries for that question.

Record actual primitive invocations and returned data through an observation seam that preserves real implementations. Do not match code words to declare coverage: the call could be in an unexecuted branch, fail before evaluation, or return nil because the caller erased history. Separate harness defects from runtime defects and model task errors.

## Exact model boundary

The node instruction/input values, ORC/SIO module, adapter request, serialized HTTP body and remote response are different artifacts. Only the serialized body captures exactly what the adapter sent. Durable researcher code/trace does not automatically contain it. Capture the complete credential-free body and raw response before decoding at the final transport boundary, correlating tick/node/execution context/physical attempt with an independently verified seam.

Inspect actual descriptions, final schemas, tool catalog/contracts, source fragments, iteration outcomes and effective options. A generated leaf's source options are currently not forwarded. A registered prime model's options are a separate path. Preserve provider-reported model identity and usage, plus unknown/error attempts without headers or configuration secrets.

Keep raw transport bytes separate from decoded content. Providers may gzip a response: decoding compressed bytes directly as UTF-8 produces unreadable text even when the application successfully decoded the response. Preserve exact bytes (for example, base64 plus a digest), then derive decompressed text/parsed JSON in a separate artifact using the transport encoding. Do not alter the original response or place derived records where a capture collector could count them as physical attempts. Compare parsed response content with the provider result and durable iteration before claiming missing output.

Attribute calls using structural execution identity: tenant, root/family tick, node execution, role, logical invocation and physical attempt. A Grain completion/start event can reach several registered handlers that correctly skip it; wrapper entry alone does not prove that handler performed model work. Apply the real handler eligibility rules and corroborate dispatch with canonical execution facts. Preserve accepted child lineage across restart; never pick an arbitrary historical entry as the new dispatch proof.

For global capture hooks, isolate the JVM/run or provide reliable structural ownership; process-wide hooks during unrelated parallel work can misattribute calls. A REPL client response or tool warning is not proof the original evaluation failed. Diagnose its original result artifact and workers before rerunning.

Read [model/SIO](../../orc-model-sio/SKILL.md) and [observation](../../orc-observe-debug/SKILL.md) for request correlation, trace freshness and limits. Read [judges](../../orc-judges/SKILL.md) for evidence-scoped assessments. Judge source/context must be verified before interpreting feedback or consolidating it into future guidance.
