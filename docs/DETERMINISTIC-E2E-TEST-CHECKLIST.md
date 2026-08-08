# Deterministic End-to-End Workflow Test Checklist

This file tracks the deterministic end-to-end workflow tests proposed by the
documentation audit. These tests exercise real ORC workflow construction,
execution, event processing, projections, and public queries without asserting
LLM response quality.

Status convention:

- `[ ]` not implemented or not yet verified end to end
- `[x]` implemented and verified end to end

Do not mark a candidate complete solely because unit or component tests cover
some of its behavior. Completion requires an integration-shaped workflow test
that verifies the stated observable results.

## P0 — Core execution contract

- [x] **DET-E2E-001 — Sequence success.** Three code nodes append markers. Verify left-to-right output, execution-event order, success status, and all three node traces.
- [x] **DET-E2E-002 — Sequence fail-fast.** The middle node throws. Verify the third node never runs, the tick fails, prior writes remain traceable, and the failure identifies the middle node.
- [x] **DET-E2E-003 — Fallback first-child success.** Verify later branches never execute and only the selected branch's writes reach the result.
- [x] **DET-E2E-004 — Fallback recovery.** The first two branches fail and the third succeeds. Verify failures are traced, the third output wins, and the composite succeeds.
- [x] **DET-E2E-005 — Fallback exhaustion.** Every branch fails. Verify the composite and tick fail, every child was attempted once, and no undeclared output escapes.
- [x] **DET-E2E-006 — Condition true branch.** Verify a successful condition allows its guarded sequence to execute.
- [x] **DET-E2E-007 — Condition false with fallback.** Verify a false condition selects the alternative branch rather than becoming an erroneous tick failure.
- [x] **DET-E2E-008 — Condition operator matrix.** Verify equality, comparison, membership, presence, and boolean operators against boundary inputs.
- [x] **DET-E2E-009 — Parallel `:all` success.** Verify all outputs, overlapping execution intervals, and one trace per child.
- [x] **DET-E2E-010 — Parallel `:any` success.** One child succeeds while siblings fail. Verify policy status, complete child tracing, and the surviving output.
- [x] **DET-E2E-011 — Parallel majority.** Exercise odd- and even-sized child sets and verify exact success/failure thresholds.
- [x] **DET-E2E-012 — Parallel write conflict (characterization).** Two equal-version branches write the same key. Current behavior consistently selects the first sibling regardless of completion order. Concern: documentation says the highest version wins but does not specify the equal-version tie-break contract; do not treat first-sibling precedence as intentional until the product contract is decided.
- [x] **DET-E2E-013 — Map-each sequential.** Verify ordered transformation, output cardinality, and unique iteration trace identities at concurrency one.
- [x] **DET-E2E-014 — Map-each bounded parallel.** Verify input-aligned output order and that observed active workers never exceed the configured limit.
- [x] **DET-E2E-015 — Nested composites.** Execute sequence → parallel → map-each → fallback and verify the final value, complete node tree, parent instance IDs, and no missing executions.
- [x] **DET-E2E-016 — Delegate success.** Verify isolated child blackboard, selected reads/writes, parent/child trace lineage, and result transfer.
- [x] **DET-E2E-017 — Delegate child failure.** Verify delegate and parent failure, uncorrupted parent state, and queryable child failure.
- [x] **DET-E2E-018 — Delegate timeout.** Verify timeout status, bounded elapsed time, no late parent writes, and child trace termination.
- [x] **DET-E2E-019 — Nested delegates.** Execute parent → child → grandchild and verify trace lineage, one trace family, and final value propagation.
- [x] **DET-E2E-020 — Parallel delegates.** Verify concurrent child execution, blackboard isolation, no cross-child values, and distinct child ticks under one correlation ID.

## P0 — Failure, partial-result, and retry semantics

- [x] **DET-E2E-021 — Map-each partial result.** Fail selected primitive-leaf iterations. Verify `:partial`, compact ordered successes, and failed-item identity in events/traces.
- [x] **DET-E2E-022 — Map-each all items fail.** Verify aggregate status, empty-result behavior, and one failure trace per input.
- [x] **DET-E2E-023 — Partial result feeding downstream.** Verify downstream aggregation handles partial status explicitly and cannot silently present compacted results as complete.
- [x] **DET-E2E-024 — Map-each iteration isolation.** Verify each iteration's result and trace contain only that item's values despite shared key names.
- [x] **DET-E2E-025 — Composite map-each leaf guard.** Composite descendant writes are collected safely, including the code leaf's `:processed?` value for every successful item.
- [x] **DET-E2E-026 — Code-node retry succeeds.** Fail once then succeed. Verify attempt count, retry path, one final output, and trace/event representation.
- [x] **DET-E2E-027 — Retry exhaustion.** Verify the exact maximum attempt count and one terminal failure without duplicate completion events.
- [x] **DET-E2E-028 — Per-node timeout.** Verify timeout status, bounded duration, and absence of output.
- [x] **DET-E2E-029 — Tick timeout.** Runtime returns the trace identity, cancels the durable tick, and persists queryable timeout evidence before returning.
- [x] **DET-E2E-030 — Node timeout versus tick timeout.** Verify the smaller applicable budget controls termination.
- [x] **DET-E2E-031 — Cancellation during map-each.** Verify no new iterations start, active work settles, and no completion arrives after cancellation.
- [x] **DET-E2E-032 — Duplicate completion defense.** Duplicate child delivery does not rerun the leaf or emit a second parent completion.
- [x] **DET-E2E-033 — Nil output failure.** A nil declared code output fails the node and prevents downstream execution.
- [x] **DET-E2E-034 — Partial nil output.** Any nil declared write rejects the complete write and identifies the missing key.
- [x] **DET-E2E-035 — Exception sanitization.** Verify stable serializable public errors without implementation leakage.

## P1 — Workflow lifecycle and versioning

- [x] **DET-E2E-036 — Deterministic workflow identity.** Build the same named definition twice and verify identical sheet ID, no duplicate graph, and no additional effective events.
- [x] **DET-E2E-037 — Conflicting same-name definition.** Rebuild a name with changed behavior and verify the documented conflict/version behavior rather than silent mutation.
- [x] **DET-E2E-038 — Atomic build failure.** Invalid graph-wide references are rejected before the first event-sourced mutation, leaving no partial sheet.
- [x] **DET-E2E-039 — Concurrent identical builds.** Verify one identity, one coherent graph, and no duplicated children.
- [x] **DET-E2E-040 — Publish and execute version 1.** The pinned result and durable trace both identify executed version 1.
- [x] **DET-E2E-041 — Draft diverges from published version.** Draft and pinned-v1 behavior remain isolated and the pinned result identifies version 1.
- [x] **DET-E2E-042 — Publish version 2.** Versions increase monotonically, remain independently executable, and are identified in results and traces.
- [x] **DET-E2E-043 — Version diff.** Changed node function/retry configuration plus node and blackboard additions, removals, and schema modification are all reported between real published snapshots.
- [x] **DET-E2E-044 — Export/import round trip.** Equivalent graph, schemas, executors, configuration, and execution output are verified after import into clean infrastructure.
- [x] **DET-E2E-045 — DSL round trip.** DSL → event-sourced model → canonical DSL remains semantically equivalent with stable identity and deterministic node placement.
- [x] **DET-E2E-046 — Batch execution.** Real batch command returns input-aligned mixed results, independent durable trace IDs, and isolated sibling success around a deliberate failure.
- [x] **DET-E2E-047 — Batch pinned version.** Every item executes and reports v1 after v2 is published, matching durable trace metadata.

## P1 — Blackboard, schemas, and value storage

- [x] **DET-E2E-048 — Input schema rejection.** Invalid runtime inputs are rejected before tick seeds or execution events are persisted.
- [x] **DET-E2E-049 — Output schema rejection.** Invalid runtime outputs fail before canonical writes or downstream execution.
- [x] **DET-E2E-050 — Versioned blackboard writes.** Successive projected values have monotonic versions, separate executions observe the correct value, and each leaf's durable `:read-sources` resolves to the exact write it consumed.
- [x] **DET-E2E-051 — Undeclared read.** Workflow construction deterministically rejects the graph before execution and names the exact unknown key in `:reads`.
- [x] **DET-E2E-052 — Undeclared write.** Extra keys returned by a real code executor are filtered from public output, declared blackboard structure, and canonical write events.
- [x] **DET-E2E-053 — Structured nested values.** Typed vectors, nested maps, an absent optional field, and map-of values pass exactly through code, bounded map-each, and delegate nodes.
- [x] **DET-E2E-054 — File-store canonical values.** Every tick seed and code output is stored as a resolvable integrity descriptor without inline event bytes, and public hydration remains transparent.
- [x] **DET-E2E-055 — Tampered value reference.** Replacing stored bytes after successful execution makes resolution fail on descriptor size/hash integrity rather than returning corrupted data.
- [x] **DET-E2E-056 — Transitive reference resolution.** A large structured value crosses parent → middle → child using external storage and lightweight references without leaking descriptors through public output.
- [x] **DET-E2E-057 — Concurrent value isolation.** Ten synchronized executions preserve exact input/output alignment and produce ten distinct externally stored output references whose resolved value set matches exactly.
- [x] **DET-E2E-058 — Inline versus file-store equivalence.** Clean inline and file-store contexts produce identical public status/output and normalized durable trace semantics; only storage placement differs.
- [x] **DET-E2E-124 — Provider output normalization and rejection evidence.** A model-backed leaf presents keyword enum choices using canonical JSON spellings, decodes schema-equivalent JSON keyword and numeric representations before canonical blackboard validation, rejects noncanonical enum spellings and numeric strings without writing them, and exposes the exact rejected value through node trace detail under both inline and referenced value storage.
- [x] **DET-E2E-125 — Provider schema failure consumes the default retry.** A first schema-invalid structured response followed by a valid response succeeds after exactly two provider calls, while two invalid responses exhaust the default retry, persist only the final rejected output, and account for both attempts' usage.
- [x] **DET-E2E-126 — Unconstrained blackboard schemas are rejected atomically.** Workflow construction rejects top-level and nested `:any` schemas before authoring state changes, identifies every offending blackboard key, and directs the consumer to use the most specific schema that expresses the value's intent.
- [x] **DET-E2E-127 — Structurally unconstrained blackboard schemas are rejected.** Workflow construction and direct commands recursively reject `:some`, standalone or fieldless maps, and collections with missing or unconstrained item/value schemas; feedback identifies each key and exact schema path while fully specified structured schemas remain valid.
- [x] **DET-E2E-128 — Referenced blackboard schemas participate in workflow identity.** Building a workflow captures its recursively resolved blackboard schemas; changing a direct or transitive registry dependency rebuilds the same sheet with the new schema, an unrelated registry change remains a zero-event no-op, and execution validates against the captured schema.

## P1 — Observability and streaming

- [x] **DET-E2E-059 — Trace shape plus exact IO.** Summary traces retain read/write keys and value profiles without inline values, while `node-trace-detail` rehydrates each leaf's exact inputs and outputs.
- [x] **DET-E2E-060 — Failure trace.** A leaf failure inside sequence → parallel records error, failure status, duration, parent instance lineage, and exact failing input through supported detail lookup.
- [x] **DET-E2E-061 — Repeated-node identity.** Four bounded map-each invocations share one deterministic node ID and retain four unique child execution instance IDs with parent lineage.
- [x] **DET-E2E-062 — Trace-family query from descendant.** Root and delegated-child lookup return the identical two-trace family and root identity.
- [x] **DET-E2E-063 — Correlation query.** Two independent correlated roots plus their delegated children are grouped into two families while an uncorrelated root is excluded.
- [x] **DET-E2E-064 — Trace filters.** Mixed successful, failed, draft, published, and unrelated-sheet traces verify sheet scoping plus status, version, node, time, limit, and combined filtering.
- [x] **DET-E2E-065 — Streaming lifecycle.** One sequence exercising code, condition, fallback, parallel, map-each, and delegate nodes verifies started/completed lifecycle, progress taxonomy, child linkage, tick boundaries, identifiers, timestamps, and gapless sequence numbers.
- [x] **DET-E2E-066 — Streaming preserves engine result.** Fixed-tick subscribed and unsubscribed executions return identical semantic results and append identical normalized durable event sequences.
- [x] **DET-E2E-067 — Slow subscriber.** A non-reading one-slot subscriber cannot impede a 12-leaf workflow; all durable completions persist while the sliding channel retains only the terminal edge of the stream.
- [x] **DET-E2E-068 — Subscriber exception.** A consumer that throws after its first envelope does not affect successful workflow output or durable tick completion.
- [x] **DET-E2E-069 — Late subscription and reconnection.** Subscriptions created after completion receive no historical replay, reconnection remains ephemeral, and the completed execution is recoverable through its durable trace query.
- [x] **DET-E2E-070 — Stream payload cap.** A 40,000-character value is represented by a marked 16,384-character stream preview while public result and durable node detail return the exact value.
- [x] **DET-E2E-071 — Streaming cancellation.** Cancelling after the slow leaf starts promptly resolves the caller, emits exactly one cancellation plus terminal close, and prevents the later sequence child from starting.
- [x] **DET-E2E-072 — Concurrent stream isolation.** Concurrent subscriptions contain only their own tick envelopes and their durable traces retain distinct caller correlation IDs.

## P2 — Event sourcing and deterministic evaluation

- [x] **DET-E2E-073 — Command → event → projection.** A real code-only build and execution verifies that every scoped mutation event has a registered passing Malli schema and reconstructs sheet, nodes, blackboard, and durable trace through public queries.
- [x] **DET-E2E-074 — Projection replay.** Clean reductions of the complete event stream through the production sheet, node, blackboard, and trace reducers exactly match their live public query results.
- [x] **DET-E2E-075 — Judge opt-in disabled.** An attached heuristic structural judge produces no score event while Living Description evaluation remains disabled.
- [x] **DET-E2E-076 — Deterministic structural judge.** A known sequence → map-each → final tree emits the exact 0.5 structural score, two perfect dimensions, and the same projected judge-score entry.
- [x] **DET-E2E-077 — Multiple deterministic judges.** A throwing custom code judge is isolated while its sibling heuristic judge emits the sole valid score and the host execution remains successful.
- [x] **DET-E2E-078 — Judge score idempotency.** Re-appending the real leaf completion retains exactly one score for the sheet/node/tick/judge identity tuple.
- [x] **DET-E2E-079 — Custom code-only judge workflow.** Typed host fields reach a real evaluation sheet and emit its deterministic score/feedback, while the max-depth guard prevents that sheet from being sub-executed recursively.
- [x] **DET-E2E-080 — Composite score.** Two deterministic custom judges verify explicit 1:3 weighting, equal default weighting, one composite under duplicate completion, and idempotently projected component scores.

## P2 — Ontology and self-learning

- [x] **DET-E2E-081 — Seed bootstrap.** Baseline dispatch counts, stable child UUIDs, projected `skos:broader` edges, current descriptions, and two-run histories are verified.
- [x] **DET-E2E-082 — Record strength flow.** The real command, emitted event, rich profile projection, retrieval counts, and formatted actionable rule are verified.
- [x] **DET-E2E-083 — Record weakness flow.** The real command preserves failure/subtype, triggers, context, severity, and renders the projected avoidance rule.
- [x] **DET-E2E-084 — Ontology isolation.** Equal URIs and relationships remain independent under ontology-scoped identities; ambiguous unscoped lookup fails closed.
- [x] **DET-E2E-085 — String/UUID normalization.** String and deterministic UUID identifiers resolve to the same public query identity.
- [x] **DET-E2E-086 — Concept graph export.** A parent/child extension exports byte-stable, valid Turtle containing both concepts and the `skos:broader` relationship.
- [x] **DET-E2E-087 — Behavioral subtree minting.** Two real mints retain two provenance audits while sharing one derived identity, history, concept, and parent edge.
- [x] **DET-E2E-088 — Description consolidation event chain.** A stubbed deterministic reflection receives the prior body, replaces current text, increments v3→v4, and preserves both history entries.
- [x] **DET-E2E-089 — Reindex threshold.** Nine events remain below the default threshold; the tenth causes exactly one real command-path rebuild and resets the event counter.
- [x] **DET-E2E-090 — Immediate mint reindex.** One mint below threshold forces a rebuild containing the derived target and resets state.
- [x] **DET-E2E-091 — Harvester threshold crossing.** Nine anchored observations do not mint; the tenth clears the score/shape gate, and repeated harvest attempts retain exactly one minted audit.
- [x] **DET-E2E-092 — Cross-observation baseline.** Three distinct historical ticks on one reusable sheet retain their score events and project the exact cross-observation mean.

## P2 — MCP builder and deterministic tools

- [x] **DET-E2E-093 — MCP schema → workflow → execution.** Generated nodes retain their tool binding and tick-scoped MCP session, invoking the typed tool with the expected arguments.
- [x] **DET-E2E-094 — Required/optional schema propagation.** Nested required/optional shape reaches the generated blackboard and is enforced at runtime.
- [x] **DET-E2E-095 — Invalid generated workflow rejection.** Incompatible undeclared reads/writes are rejected by validation, with both missing keys reported before build or execution.
- [x] **DET-E2E-096 — Tool failure propagation.** A reconstructed in-process tool caller throws; the leaf and tick fail and both returned and durable completion errors retain the tool exception detail.
- [x] **DET-E2E-097 — PDF pipeline.** Both PDFBox fixtures retain exact page alignment across page count, 72-DPI PNG rendering, text extraction, and per-page metadata aggregation.
- [x] **DET-E2E-098 — Redaction pipeline.** Multi-page replacements, counts, order, categories, and applied targets are exact; a second pass changes nothing and reports zero applications.
- [x] **DET-E2E-099 — Invoice workbook pipeline.** Two normalized invoices produce the expected file, ordered sheets, summary rows/totals, and per-invoice line-item row counts.
- [x] **DET-E2E-100 — Consumer-owned code nodes.** A persisted workflow executes a qualified deterministic function supplied from a consumer-owned namespace, while an unavailable function fails explicitly.

## P3 — Complex cross-domain system journeys

These candidates extend beyond a single mechanism. Each must use real Grain
commands, events, processors, projections, and public interfaces for every
participating component; replacing a participating component with a mock is not.

Candidates marked **REAL-LLM** have an additional non-negotiable execution
contract:

- The selected model for every LLM role is
  `google/gemini-3.6-flash` through OpenRouter, except DET-E2E-102, which pins
  `google/gemini-3.1-pro-preview` after two live runs showed the default model
  completing without making its required contribution tool call. This includes workflow leaves,
  RLM researchers and subcalls, judges, classifiers, GEPA proposers,
  consolidators, and ontology extractors. Tests must record the resolved model
  in their durable trace/provenance assertions and must not silently fall back
  to a different model. Selection reviewed against OpenRouter's catalog on
  2026-08-06.
- They must call real models through OpenRouter. Mock, fake, stubbed, replayed,
  or scripted LLM, judge, classifier, proposer, consolidator, or extractor
  behavior does not satisfy the candidate.
- They must be skipped unless `ORC_OPENROUTER_E2E_TESTS=true` and a non-empty
  `OPENROUTER_API_KEY` are both present. The ordinary deterministic suite must
  neither contact OpenRouter nor fail because credentials are absent.
- Assertions must verify durable integration contracts, provenance, schemas,
  lifecycle, isolation, and bounded outcome properties. They must not require
  one exact natural-language response from a nondeterministic model.
- A skipped gated test remains unchecked in this checklist. Mark it complete
  only after an explicitly enabled real-OpenRouter run passes end to end.

For every candidate below, evaluate each semicolon-delimited prediction
independently against captured events, projections, traces, external-call
records, and public-query results. One contradicted prediction fails the test.
Missing evidence is inconclusive—not a pass—and leaves the checklist item open.
Record unexpected outcomes even when the final returned status is successful.

- [x] **DET-E2E-101 — REAL-LLM: Closed self-learning loop across executions.**
  - **Purpose:** Prove that evidence from one real execution changes only the intended later execution through the complete evaluation → ontology → classification → instruction-injection chain.
  - **Falsifiable predictions:** The first run persists at least one real `google/gemini-3.6-flash` model call and one judge result linked to its trace; the resulting ontology evidence references that trace ID; the second matching run's effective instruction contains the exact persisted guidance identifier/body version; a control workflow's effective instruction contains neither; every projected strength/weakness is supported by a durable evaluation event rather than model prose alone.

- [x] **DET-E2E-102 — REAL-LLM: Mint, index, retrieve, and reuse a novel behavior.**
  - **Purpose:** Prove that a behavior invented during one real RLM run becomes durable, searchable experience that a later independent run can actually reuse.
  - **Falsifiable predictions:** Exactly one mint audit is stored for the minted behavior identity; its description records the originating execution/model and survives public-query roundtrip; an index rebuild completes after the mint and its source corpus contains that identity; a later classifier returns the same identity above the configured threshold; the later run's effective instruction contains that behavior body and provenance; no pre-mint run can retrieve it.

- [x] **DET-E2E-103 — REAL-LLM: Evidence consolidation changes future guidance.**
  - **Purpose:** Prove that reaching the evidence threshold produces a new current description which is indexed and used, without destroying history.
  - **Falsifiable predictions:** Threshold-minus-one observations create no new description version; the threshold observation creates exactly one successor version; its evidence count equals the complete contributing set and its model provenance is `google/gemini-3.6-flash`; the retrieval corpus contains the successor and not the superseded body as current; the next matching execution receives the successor version; all prior versions remain queryable and ordered.

- [x] **DET-E2E-104 — REAL-LLM: Recency-biased consolidation is rejected end to end.**
  - **Purpose:** Prove that a contradictory recent burst cannot replace a description supported by broader historical evidence merely because an LLM proposes it.
  - **Falsifiable predictions:** The real consolidator call and proposed body are durably attributable to `google/gemini-3.6-flash`; the rejection event names the recency/evidence guard; the current description ID/version is unchanged; no index source document contains the rejected body; a later matching execution receives the pre-existing guidance; history retains auditable supporting and contradicting evidence for the rejected attempt.

- [x] **DET-E2E-105 — REAL-LLM: Recursive RLM targeted recovery journey.**
  - **Purpose:** Prove that a real recursive researcher can inspect a failed emitted tree, preserve successful work, perform focused recovery, and finish under one trace family.
  - **Falsifiable predictions:** The first emitted tree has at least one durable success and the deliberately induced leaf failure; a later OpenRouter turn invokes at least one drill-down primitive against that tree; the recovery tree does not rerun any already-successful leaf identity and contains work addressing the failed output; successful values from iteration one remain available while failed writes do not; cumulative usage/timing never decreases; one trace family contains both tree attempts and one terminal root completion.

- [x] **DET-E2E-106 — REAL-LLM: Nested budget propagation and exhaustion.**
  - **Purpose:** Prove that nested delegate, RLM, model-call, and emitted-tree budgets compose correctly and cannot be bypassed by deeper work.
  - **Falsifiable predictions:** The configured smallest budget is the first exhausted boundary within a documented tolerance; the terminal status and error identify that boundary; every descendant active at exhaustion receives cancellation and no new descendant starts afterward; each completed OpenRouter call has exactly one usage charge and uses `google/gemini-3.6-flash`; summed child usage equals the root's projected aggregate; no write or second terminal completion arrives after shutdown settles.

- [x] **DET-E2E-107 — Cancellation race across a delegated tree.**
  - **Purpose:** Characterize cancellation when durable completion and active work race across root, delegate, map-each, stream, and projection boundaries.
  - **Falsifiable predictions:** The deliberately released iteration has exactly one completion event and resolvable result; the blocked iteration and all not-yet-started inputs have no completion/output event; delegate and root each project exactly one terminal cancellation-compatible state; the stream contains one cancellation envelope followed by one terminal close and no later envelope; replay yields byte-equivalent normalized terminal projections.
  - **Resolved concern (2026-08-06):** Targeted Poly runs exposed live `:completed` versus replayed `:cancelled` under out-of-order asynchronous projection. Tick reduction now makes cancellation terminal and monotonic; the targeted namespace subsequently passed all 66 assertions, including byte-equivalent live/replay terminal state.

- [x] **DET-E2E-108 — Restart and resume an in-flight execution.**
  - **Purpose:** Prove that durable execution state is sufficient to continue safely after processor/runtime loss without repeating completed work or external effects.
  - **Falsifiable predictions:** Before restart, at least one leaf is durably complete and at least one is incomplete; after rebuilding from the same event stream, every pre-restart completion retains its original execution identity and count of one; an instrumented external effect from completed work is observed once total; each incomplete leaf completes at most once after resume; final public output and normalized projections equal an uninterrupted control run; the combined event stream contains one root terminal event.

- [x] **DET-E2E-109 — Publish while executions are in flight.**
  - **Purpose:** Prove that publishing a new workflow version cannot change the graph or behavior observed by already-running executions.
  - **Falsifiable predictions:** Pinned-v1 and pre-publication draft runs use one internally coherent pre-publication snapshot; pinned-v2 and post-publication latest runs use one coherent v2 snapshot; no trace contains node/configuration fingerprints from both versions; each result and trace reports the version/snapshot it actually used; replay preserves the same four assignments; repeated queries never reclassify an old execution as the newly published version.

- [x] **DET-E2E-110 — REAL-LLM: GEPA lifecycle to Pareto completion.**
  - **Purpose:** Prove that a real OpenRouter-driven GEPA run enforces Pareto and budget rules through the complete durable optimization lifecycle.
  - **Falsifiable predictions:** The seed and every proposed candidate have traceable evaluations and `google/gemini-3.6-flash` proposer provenance; every accepted candidate strictly improves the required comparison and every rejected candidate fails it; projected Pareto entries are mutually non-dominating on recorded per-example scores; each parent/merge reference resolves to an existing ancestor; total metric calls never exceed the configured budget; the run reaches exactly one terminal state with a selected candidate already present in the population.

- [x] **DET-E2E-111 — REAL-LLM: GEPA failure and resumability boundary.**
  - **Purpose:** Prove that a GEPA run interrupted between proposal and full evaluation resumes from durable state without double-spending calls or corrupting its population.
  - **Falsifiable predictions:** The interruption point contains one durable proposed candidate with no terminal evaluation; after reconstruction, that candidate retains its identity and reaches exactly one terminal evaluation; no already completed task-model, judge, or proposer call is repeated according to durable call IDs/usage; remaining budget after resume equals configured budget minus pre-restart completed calls; all model provenance resolves to `google/gemini-3.6-flash`; final population invariants match an uninterrupted control even if generated prose and scores differ.

- [x] **DET-E2E-112 — REAL-LLM: Optimized instruction publication isolation.**
  - **Purpose:** Prove that applying a real GEPA winner creates a new publishable workflow version without rewriting the optimized source version.
  - **Falsifiable predictions:** The winning candidate exists, has real OpenRouter provenance, and is linked to workflow v1; v1's canonical definition and instruction fingerprint are unchanged after application; published v2's targeted instruction fingerprint equals the winner's and differs from v1's; pinned-v1 execution traces report/use the original fingerprint while pinned-v2 traces report/use the winner; concurrent execution cannot mix the two; optimization provenance continues to name v1 as its source.

- [x] **DET-E2E-113 — MCP discovery to portable execution.**
  - **Purpose:** Prove that a generated MCP workflow remains self-contained and routes tools by stable identity rather than discovery order.
  - **Falsifiable predictions:** Both overlapping tool names coexist under distinct server-qualified identities; generated executor schemas exactly preserve required, optional, and nested fields from discovery; validation succeeds before build and publication; exported package contains every definition needed for import but no live session identity/secret; after reverse-order reconnection, each invocation reaches the originally bound server exactly once with schema-valid arguments; imported and source workflows produce equivalent normalized outputs and traces.

- [x] **DET-E2E-114 — MCP schema drift after publication.**
  - **Purpose:** Prove that server schema drift is visible and cannot silently reinterpret a published workflow contract.
  - **Falsifiable predictions:** The published workflow retains its captured schema fingerprint after the server changes; execution either succeeds against that captured compatible contract or terminates with an explicit schema/transport mismatch naming the changed field/type; no coerced or undeclared value reaches the blackboard; regeneration captures the new fingerprint and produces a distinct workflow/version identity; the original published definition and prior traces remain unchanged.

- [x] **DET-E2E-115 — REAL-LLM: Multi-source ontology build with duplicate and failure recovery.**
  - **Purpose:** Prove that a real LLM-backed batch ontology build handles heterogeneous overlap, duplicate registration, and extraction failure without publishing partial state.
  - **Falsifiable predictions:** The exact duplicate source produces one registered-source identity and one effective extraction; each successful extraction records `google/gemini-3.6-flash`, source identity, and non-empty structured output; the induced transport failure produces one failed build with no new current snapshot; retry reuses successful durable work where supported and reaches one completed build; overlapping concepts resolve to one canonical identity with mappings to every contributing source; the final Turtle parses, is byte-stable on repeat export, and contains no duplicate concept caused by the duplicate input.

- [x] **DET-E2E-116 — REAL-LLM: Incremental ontology build preserves canonical identity.**
  - **Purpose:** Prove that real LLM extraction can extend an ontology incrementally without losing canonical identities or historical provenance.
  - **Falsifiable predictions:** Baseline and incremental extractions record `google/gemini-3.6-flash` and their distinct source/build IDs; the renamed concept resolves to the baseline canonical ID rather than creating an unlinked duplicate; the extended concept retains its ID and gains only the intended new attributes/edges; exactly one new canonical concept is introduced for the truly novel item; prior source mappings remain queryable; replay reproduces the same normalized graph and byte-identical Turtle export.

- [x] **DET-E2E-117 — Atomic ColBERT rebuild under concurrent search.**
  - **Purpose:** Prove that index rebuild/activation is atomic for live readers and that failed activation cannot displace a valid artifact.
  - **Falsifiable predictions:** Every search result set matches either the complete expected old corpus or complete expected new corpus; no result combines old-only and new-only document identities; within each corpus, ranks are descending and stable for ties and batch outputs remain input-aligned; successful activation changes the active artifact exactly once; induced activation failure leaves the old artifact ID active, emits a visible failure, and yields old-corpus results on subsequent searches.

- [x] **DET-E2E-118 — Observability outage does not corrupt execution.**
  - **Purpose:** Prove that exporter blocking, partial acceptance, and failure remain isolated from workflow execution while preserving export integrity and bounded resource use.
  - **Falsifiable predictions:** All workflows return the same normalized outputs/statuses as an exporter-disabled control; durable trace event counts and order are unchanged; every acknowledged exported event appears exactly once by stable event ID and preserves correlation order; unacknowledged events are either retried or reported dropped according to the configured policy, never silently marked accepted; exporter failure is observable; queue/buffer occupancy never exceeds its configured bound; stopping the exporter/workflows terminates within a stated timeout.

- [x] **DET-E2E-119 — REAL-LLM: Cross-component projection replay equivalence.**
  - **Purpose:** Prove that the adaptive stack's public state is reproducible from its immutable events even when the original scenario contains real nondeterministic model outputs.
  - **Falsifiable predictions:** The source event stream records `google/gemini-3.6-flash` plus content/usage provenance for every model call; replay performs zero new OpenRouter calls; normalized workflow, execution, evaluation, ontology, description, index-source, and GEPA queries are exactly equal before and after replay; every cross-component reference resolves in both contexts; histories and terminal statuses have identical ordering/cardinality; any derived binary index may differ physically only if its source document set and public retrieval contract remain identical.

- [x] **DET-E2E-120 — REAL-LLM: Tenant isolation across the adaptive stack.**
  - **Purpose:** Prove that tenant identity scopes every adaptive artifact and access path, including real model-derived data with colliding human-readable identifiers.
  - **Falsifiable predictions:** Every command/event/model trace includes the initiating tenant and `google/gemini-3.6-flash` where applicable; tenant-A public queries, retrieval results, injected guidance, GEPA population, streams, exports, and value resolution contain zero tenant-B stable IDs or unique sentinel values, and vice versa; tenant-scoped identities differ where required, while a deliberately colliding deterministic workflow ID resolves to each tenant's own distinct content hash; direct lookup returns no foreign artifact (an absent ID is empty and a colliding ID resolves only tenant-local state); replay and index rebuild preserve the same isolation; per-tenant event/projection counts equal independent control runs.

- [x] **DET-E2E-121 — Custom ontology lifecycle, retry, and projection replay.**
  - **Purpose:** Prove that a consumer can create an empty ontology, add and update concepts, link valid endpoints, retry safely, and reconstruct identical public state from events.
  - **Falsifiable predictions:** Creation returns stable ontology, concept, and relationship identities; command-ID retry appends no duplicate mutation; duplicate semantic identities are rejected; invalid ontology, broader URI, endpoint, predicate, self-link, and empty update are rejected; typed provenance survives projection; clearing and rebuilding projections returns identical lifecycle and graph state.

- [x] **DET-E2E-122 — Custom ontology tenant isolation under colliding identities.**
  - **Purpose:** Prove every lifecycle mutation and lookup remains tenant-scoped even when ontology UUIDs, names, base URIs, and concept URIs collide.
  - **Falsifiable predictions:** Each tenant reads only its own ontology and concept state; a foreign ontology cannot be used for create, update, or relationship endpoints; a relationship cannot bridge tenant state; scoped URI lookup is deterministic; unscoped collision lookup returns no concept; replay preserves isolation.

- [x] **DET-E2E-123 — Manual ontology evolves through the unified graph lifecycle.**
  - **Purpose:** Prove an initially empty manually created ontology can be evolved from sources without losing or duplicating manually authored concepts.
  - **Falsifiable predictions:** Evolve recognizes lifecycle-only ontology state; manual concepts participate in URI deduplication and are preferred as existing canonical identities; extracted concepts and relationships join the same public graph; origin metadata distinguishes manual and extracted concepts; replay reconstructs the same unified graph.

## Recommended complex tranche

- [x] DET-E2E-101 — Closed self-learning loop across executions
- [x] DET-E2E-102 — Mint, index, retrieve, and reuse a novel behavior
- [x] DET-E2E-105 — Recursive RLM targeted recovery journey
- [x] DET-E2E-108 — Restart and resume an in-flight execution
- [x] DET-E2E-110 — Real-LLM GEPA lifecycle to Pareto completion
- [x] DET-E2E-113 — MCP discovery to portable execution
- [x] DET-E2E-117 — Atomic ColBERT rebuild under concurrent search
- [x] DET-E2E-119 — Cross-component projection replay equivalence
- [x] DET-E2E-120 — Tenant isolation across the adaptive stack

## Recommended first tranche

- [x] DET-E2E-002 — Sequence fail-fast
- [x] DET-E2E-004 — Fallback recovery
- [x] DET-E2E-009 through DET-E2E-011 — Parallel policy matrix
- [x] DET-E2E-014 — Bounded parallel map-each
- [x] DET-E2E-021 — Map-each partial-result semantics
- [x] DET-E2E-017 — Delegate failure isolation
- [x] DET-E2E-030 — Node-versus-tick timeout precedence
- [x] DET-E2E-036 and DET-E2E-038 — Build idempotency and atomicity
- [x] DET-E2E-041 — Published-version isolation
- [x] DET-E2E-049 — Schema failure before downstream execution
- [x] DET-E2E-066 — Streaming does not alter execution
- [x] DET-E2E-074 — Projection replay equivalence

## Implementation notes

- Prefer public workflow APIs and real in-memory Grain infrastructure.
- For DET-E2E-001 through DET-E2E-100, use deterministic code nodes, condition
  nodes, controlled clocks/latches, and scripted failures; do not verify
  LLM-generated content. For candidates marked **REAL-LLM**, follow the gated
  OpenRouter contract above instead and never substitute scripted model behavior.
- Assert both the returned result and durable evidence: events, projections,
  trace queries, exact node IO, lineage, and correlation where applicable.
- Avoid timing-only concurrency claims. Use latches and recorded active-worker
  counts, with elapsed time only as supporting evidence.
- Existing unit/integration coverage may supply helpers, but each checked item
  must prove its complete workflow-level contract.
