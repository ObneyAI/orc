# RF4 — de-flake `reindex-processor-fires-at-threshold` (tracer bullet · deferred)

**Type:** AFK · deferred · **Parent:** `docs/build-timeline/issues/rlm-finalization/README.md`

## What to build
Make `ai.obney.orc.ontology.reindex-processor-test/reindex-processor-fires-at-threshold`
deterministic. It is an async, `Thread/sleep`-based test that expects
`create-index!` to fire exactly once at the threshold but observes 2–3× under
load — flaky in isolation (~2 of 4 runs) and **reliably failing under poly's
full-brick context** (3/3). It is the SOLE remaining blocker to
`clj -M:poly test brick:ontology` exiting 0.

Pre-existing + RF-independent: introduced with the reindex processor feature
(`ce140292`, C-2b-1); it touches none of the RF2-relocated files. It was simply
never *reached* under poly before — the seed-load FileNotFound (RF2) aborted the
brick run first. RF2 un-masked it (same pattern as RF1 → build-atomicity → RF3).

The fix is test-determinism, not a product change: replace the wall-clock
`Thread/sleep` race with a deterministic wait on the actual condition (poll the
projection / await the processor's settle), or inject a clock/latch so the
threshold-fires-once invariant is asserted without timing nondeterminism. Do NOT
weaken the assertion (it must still prove the processor fires exactly once at the
threshold) and do NOT change the reindex processor's production behavior unless a
genuine product race is found (if so, root-cause it — discipline #5).

## Acceptance criteria
- [ ] `reindex-processor-fires-at-threshold` passes deterministically — 10/10 runs in isolation AND under poly's full-brick context.
- [ ] `clj -M:poly test brick:ontology` exits 0 (all selected namespaces green) — the seed gap (RF2) + this de-flake together green the ontology gate.
- [ ] The assertion still proves the once-at-threshold invariant (not weakened/deleted).
- [ ] If a real production race is found in the reindex processor, it is root-caused and fixed (not masked); otherwise production code is untouched.

## Blocked by
RF2 (the seed-load fix must land first, else poly never reaches this test). Independent of RF1/RF3.

## Notes / related decision
A separate call (track here): `c2a-live-verify-test` (~8 min; hard-sleeps 30s/phase)
and `r03-ood-stress-test` were made loadable by RF2 and now run in the poly gate.
They are live-verify / stress tests, not fast unit tests — a heavy addition to the
canonical gate. Decide whether to exclude them from the brick test run (relocate
out of the brick `test` tree, or gate behind an opt-in alias) so the canonical gate
stays fast. Folded here as a sibling decision; split into its own slice if it grows.

## Core Disciplines
Binding Core Disciplines block 1–13 — identical to RF1 (see
`docs/build-timeline/issues/rlm-finalization/RF1-terminal-final-bang.md`). Especially:
#1 (no "flaky/transient" hand-wave — diagnose the nondeterminism to root), #5 (no
band-aid; don't weaken the assertion), #4/#9 (verify under BOTH runners; no false
green), branch `feature/ontology-architecture`, one commit per slice, co-author
trailer, JVM hygiene.
