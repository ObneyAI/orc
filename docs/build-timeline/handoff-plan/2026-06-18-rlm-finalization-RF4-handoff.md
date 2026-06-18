# RF4 Handoff — de-flake `reindex-processor-fires-at-threshold` (+ c2a/r03 gate decision)

Fresh-context brief for RF4 (`docs/build-timeline/issues/rlm-finalization/RF4-reindex-processor-flaky.md`).
Implement via `/tdd`. Branch `feature/ontology-architecture` (you work DIRECTLY on it
— NOT in a worktree; the base must include RF1/RF2/RF3). DO NOT COMMIT — leave
changes staged; the orchestrator runs `/inspect-orc` then commits.

## The goal
`ai.obney.orc.ontology.reindex-processor-test/reindex-processor-fires-at-threshold`
must pass DETERMINISTICALLY (10/10 in isolation AND under poly's full-brick context),
so `clj -M:poly test brick:ontology` exits 0. It currently expects `create-index!`
to fire exactly ONCE after 10 description-updated events but observes 2–3× — flaky
in isolation, reliably failing under poly load.

## Read first
1. `components/ontology/test/ai/obney/orc/ontology/reindex_processor_test.clj` — the failing test (~L511–535) + its helpers: `with-test-ctx`, `stub-create-index!`, `emit-description-updated!`, `inject-index-created!`. Note it waits via `Thread/sleep 500`.
2. `components/ontology/src/ai/obney/orc/ontology/core/consolidator.clj` — the reindex `defprocessor` + the threshold-fire-and-reset logic (threshold default 10; `create-index!`; the events-since-last-rebuild counter + reset).
3. The `get-reindex-state` read model (`ontology/get-reindex-state` → `:events-since-last-rebuild`, `:index-built?`) — the projection a deterministic wait should poll.
4. `components/ontology/src/ai/obney/orc/ontology/core/colbert_indexer.clj` — `create-index!` (what's being counted).

## STEP 0 — root-cause the 2–3× fires (this dictates the fix; do NOT skip)
Instrument and determine WHICH it is:
- **(a) Test-timing race** — the processor is correct (fires once, resets once), but the test's `Thread/sleep 500` is nondeterministic: it samples `@calls` before the single fire settles, or the 10 events' async processing overlaps the sample window. → Fix is in the TEST.
- **(b) Production race** — the processor's "if counter ≥ threshold → fire + reset" is NOT atomic, so concurrent/overlapping event processing crosses the threshold more than once before the reset lands, firing `create-index!` 2–3×. → Fix is in the PROCESSOR (real bug).

Default to suspecting (b) until you've proven (a): "the test is flaky" is exactly the
hand-wave discipline #1 forbids. If `create-index!` genuinely fires 2–3× for one
threshold crossing in production, that's a real defect (wasted rebuilds / churn) and
must be fixed at the source.

## The fix (per Step 0)
- **If (a):** replace `Thread/sleep`-based sampling with a DETERMINISTIC wait — poll `(ontology/get-reindex-state ctx)` until `:events-since-last-rebuild` has reset to 0 (or until `@calls` reaches the expected count), with a bounded timeout that fails loudly if it never settles. Do NOT weaken the assertions (still prove fires-exactly-once + counter resets + index-name/collection shape). Apply the same deterministic-wait pattern to the sibling timer-threshold test if it shares the race.
- **If (b):** make the threshold check-and-reset ATOMIC in the processor (e.g. a compare-and-set / single-writer guard so exactly one crossing fires exactly one rebuild and the counter resets atomically). Root-cause it; no band-aid. Then the test passes deterministically without weakening it.

## Secondary — c2a/r03 gate decision (do AFTER the de-flake; surface if invasive)
RF2 made `c2a-live-verify-test` (~8 min, 30s/phase sleeps) and `r03-ood-stress-test`
loadable; they now run in the poly gate. They are live-verify/stress tests, not fast
unit tests. EXCLUDE them from the default `clj -M:poly test` gate via the cleanest
mechanism (investigate poly's test selection — likely relocate them out of the brick
`test` tree into `development/` as on-demand scripts, or gate behind an opt-in alias),
keeping them runnable on demand. If the mechanism is uncertain/invasive, do the
reindex de-flake (primary) and REPORT the proposed c2a/r03 approach for review rather
than forcing it — do not let it jeopardize the primary fix.

## Do NOT
- Weaken/delete the threshold assertion. Touch unrelated tests. Commit (leave staged).
- Create a git worktree (work on the feature branch directly).

## Verify (orchestrator independently re-runs)
- `reindex-processor-fires-at-threshold` 10/10 deterministic in isolation (`clj -M:dev:test` targeting the ns) AND under `clj -M:poly test brick:ontology`.
- `clj -M:poly test brick:ontology` EXITS 0 (seed gap already fixed by RF2; reindex now green; c2a/r03 excluded or surfaced).
- No regression elsewhere in the ontology brick.
- JVM hygiene: bounded runs; 0 orphan this-repo JVMs after (kill by PID if any).
- Capture `docs/build-timeline/live-verify/RF4-reindex-deflake.md`: the Step-0 verdict (a vs b) + evidence, the fix, dual-runner verbatim totals, the c2a/r03 decision/approach.

## Report back (raw data)
Step-0 verdict (a/b) + how you proved it; the exact change (test and/or processor);
verbatim totals from BOTH runners + the 10/10 determinism evidence; the c2a/r03
handling; "0 orphan JVMs"; honest negatives. DO NOT COMMIT. Disciplines block 1–13 binding.
