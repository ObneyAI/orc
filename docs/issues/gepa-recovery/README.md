# GEPA recovery walkthrough

## 1. Reproduced defect

Public resume and registered subscriber deliver one subsample start twice. Both read an empty completed-task ledger and launch independent ticks. Root reproduced four real code-node trees and four metric calls for two logical tasks. The local final-result CAS emits one aggregate with two reported calls, hiding duplicate work. Existing checks passed 4 tests / 28 assertions because they checked publication, not exclusivity.

The relevant committed GEPA paths are unchanged from main1851212; this defect predates reconciliation. No SIO, prompt or schema repair is justified by this race.

## 2. Approved behavior

Durable ownership before effects, original tick recovery, metric identity preservation, task-based atomic budget admission, compatible adoption of unfinished legacy work, periodic recovery, safe bounded metric retries, and complete comparisons only. Default metric limits: three attempts, five minutes per attempt. Built-in read-only judges are safe to retry; custom metrics require explicit declaration. Unknown unsafe work gets a visible recovery handoff.

## 3. Independent slices

1. Task ledger and accounting: 01-task-ledger.md.
2. Metric identity/configuration and one bounded invocation: 02-metric-identity.md.
3. Public read-only durable execution result: 03-durable-tree-result.md.

Root owns specs, integration, strong resume regression, cold recovery and live evidence. Integration handoff follows inspection of actual produced APIs; no assumed interface is pre-written.

## 4. Integration and acceptance

Wire the slices into GEPA task orchestration, final-result commands, all public budget views and Grain recovery scheduling. No aggregate until all required tasks are complete. Preserve completed legacy aggregate totals and provenance. Never silently substitute a metric after restart.

GEPA-RECOVERY obligations are tracked unchecked in the deterministic E2E checklist. Allium baseline is 114 info / 39 warnings / 0 errors, with no analysis findings. Full final deterministic and live gates must pass on the same commit before merging #45 and then closing superseded #42/#44. Preserve #43, SIO/client pins, unrelated ontology and all untracked research.

## 5. Evidence

Original root captures are preserved outside the source repository in ../../evidence/gepa-recovery/ (relative to the runtime directory). These contain exact command results, immutable tree/node/value events and task records. Future live capture must preserve requests/responses and join them to task/tick/node/attempt identity; no credentials or fabricated evidence.

## Current checkpoint

The original duplicate-delivery regression first failed five of 17 assertions:
four real trees and metric calls ran for two logical tasks. Durable task ownership
now passes that public resume/subscriber regression. A separate two-process SQLite
proof passed 25 preparation and 66 recovery assertions, preserving original work
before dispatch, after tree completion, and after grading before publication.

Independent checkpoints include the canonical GEPA Polylith gate (156 tests,
936 assertions across two projects), ORC recovery integrations (6 tests,
94 assertions), and the expanded aggregate/terminal boundary set (23 tests,
230 assertions). All passed at their recorded checkpoints. The Polylith result
predates the final publication guards and must be repeated on the final code.

The targeted real-model resumability test passed 1 test and 37 assertions.
Captured requests and responses preserve 24 model calls, 20 original workflow
starts, 20 completed task calls, one optimization completion, and no recovery
attention events. The completed score was 1.0 with 20 charged metric calls.
An earlier run exposed a fixture transport deadline of 30 seconds despite a
65,536-token cap. The GEPA live fixture now explicitly uses a 120-second
transport deadline; production defaults are unchanged. Exact evidence and
limitations are recorded in [final verification](09-final-verification.md).

The final terminal-command checkpoint passed 18 tests and 187 assertions.
Custom-metric recovery passed 10 preparation and 40 recovery assertions in
separate processes. Interruption during a running tree passed 12 preparation
and 30 recovery assertions, retaining the completed leaf and original tick.
The twelve recovery obligations now have independent evidence; full final CI
and merge verification remain pending.

The local aggregate was stopped intentionally after 78 namespaces, 687 tests
and 3,668 assertions without recorded failures. It is incomplete. To avoid
repeating the same broad gate locally and in CI, the pushed revision's CI owns
the complete deterministic and live gates. No further local broad sweep is planned.

The integration order is #45, then #46 (read-path performance), then #47 (opt-in
prompt caching). Both successors must be refreshed onto the repaired foundation.
PR #46's historical aggregate job exceeded its two-hour execution limit; the
captured log contains no recorded assertion failure before cancellation. That
incomplete gate neither proves a regression nor establishes a passing suite.

No final merge is complete. Preserve dependency pins and unrelated ontology work.
Only one owned test JVM runs at a time; other terminals remain untouched.
