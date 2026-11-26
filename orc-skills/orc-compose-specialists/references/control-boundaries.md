# Observable control boundaries

Use this when a working application needs hardening, independently retained work or more complex behavior-tree composition. More depth is useful only when it makes a real responsibility, recovery or evidence boundary explicit.

## Assign the responsibility

A code leaf can perform a bounded calculation, transform, authority check or effect capability, including the calls needed to reconcile one operation. Put meaningful method choice, independent work, guarded continuation and recovery composition in the tree when those paths need their own durable evidence. An entire host state machine wrapped in two delegates does not prove those ORC mechanisms.

Describe each method's goal, eligibility, native inputs/results and stopping reason. Try another method only when it can establish the same parent postcondition. A failed selected action never authorizes an unrelated effect or silently changed terms. Keep commercial/effect authority in explicit guards even when ORC owns the control.

Semantic agreement and method readiness are different questions. If the current method becomes unavailable, a judgment using only that method's facts must not prematurely rule out every eligible alternative. Provide the relevant alternative evidence or arrange an explicit method-selection/reassessment path. A new proposal with changed material terms is not an effect authorized by the old proposal.

## Retain independent work at its actual completion

Futures inside one code leaf provide code-level concurrency and one leaf completion. They do not create separate ORC parallel-node traces or guarantee each completed observation survives an interruption before the join.

Where independently retained progress matters, use disjoint typed branch outputs and verify each completion in ORC's canonical values before the join. When the application also needs progress across fresh invocations, record that work through its domain command/event/projection boundary with explicit ownership; do not duplicate an authoritative store without a reason. Scope progress to the current work/invocation, source and relevant evidence revision. Parallel workers must not read and replace the entire same state snapshot: use scoped progress events/reducers or another established atomic update boundary. Verify concurrent writes and projection read-back when that application boundary is used.

Keep healthy completed work outside a repeat boundary or guard its reuse within that boundary. Re-run only missing or invalidated work. Retained intake observations do not waive fresh validation before a consequential effect. Verify the executing revision's map-each and partial-result rules before relying on collected indices.

## Compose each mapped method as one child subtree

An authored map-each executes one child subtree. Supplying preparation and
semantic work as two direct children does not sequence them: only the first
subtree runs. Compose the ordered work inside that child, or use a reusable
delegate with an explicit result seam. For parallel maps, keep the primitive
child guidance and verify scoped collection before relying on a composite's
writes. A sequential two-item proof does not establish parallel fault recovery.

Declare the current item and each prepared value in the consuming leaf's reads.
A code leaf writing an entity name or source samples does not automatically pass
those values to the next model. Inspect the actual per-item model inputs and
canonical rows. A two-entity regression exposed both skipped enrichment and
missing context; fixing the subtree and reads restored the generated fields
without changing the aggregate schema or manufacturing values.

## Preserve current, historical and unavailable evidence

Persist or return new observations even when the semantic continuation asks a question or refuses an action. A newly read fact must not coexist with an old snapshot labelled current. Keep quoted terms, last-observed facts and freshly checked facts distinguishable in the model packet and user presentation.

Unavailable evidence is not evidence of a negative result. A failed capacity lookup does not establish zero capacity or a missed deadline. Use a concrete unknown/unavailable outcome with its reason and nullable facts, rather than invented zero/false values. Describe units, source scope and missing-information meanings in the actual nested contracts.

## Trace recovery separately from data restoration

While source evidence is unknown, retain the exact customer message and interpreted plan; clearing that checkpoint turns continuation into asking the human to repeat the request. A positively known constraint requiring a new owner decision is different from a service outage.

A host can correctly restore a receipt while the previous ORC tick remains failed. That proves recovered application state, not an observed ORC recovery path. Give on-demand reconciliation or saved-plan continuation an appropriate execution boundary when its work must be visible in ORC. Preserve stable business work/effect identity while distinguishing fresh invocations and historical failures.

Do not label a fresh recovery invocation as original-tick resume. Original-tick recovery needs the runtime's supported recovery mechanism, retained identities and an actual interruption/restart proof. A still-active or orphaned execution must be handled or surfaced explicitly, never silently duplicated.

A typed blocked/waiting/handoff result can be an honest terminal outcome for the current invocation. It does not mean the original business goal was achieved. Record both execution status and business outcome.

## Verify the control that is claimed

Inspect the actual registered tree, branch/node events, native reads/writes, domain progress and effect receipts. Show eligible alternative execution and changed-term refusal; independent durable branch completion; bounded unavailable outcomes; and reconciliation without repeated effects. Retain legitimate guard failures and their meanings rather than requiring every child to succeed.

A final zero-effect count can mean safe refusal, an earlier unrelated failure or a test that never reached its intended action. Check the prerequisite and selected path. For response-loss proof, verify the effect committed and a response actually dropped before claiming recovery. Keep unsuccessful measurements and their diagnosis.

The fresh-agent hardening evidence (historical local experiment; private archive not bundled) motivated this review: native seams and useful safety improved, while production choice, concurrency and recovery remained inside host code; a real alternate was not considered after the primary became full, and current-versus-historical facts diverged. The guided extension (historical local experiment; private archive not bundled) records the subsequent implementation and qualified proof. Reviewer-guided work does not establish unassisted skill performance; a later fresh attempt is needed to evaluate this guidance change.
