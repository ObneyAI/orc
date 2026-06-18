# RF5 — separate live/slow integration tests from the ontology fast gate (decision + tracer)

**Type:** AFK · **needs a repo-owner decision** · **Parent:** `docs/build-timeline/issues/rlm-finalization/README.md`

## What surfaced
With RF2 (seed classpath) + RF4 (reindex de-flake) landed, the next blocker to an
exit-0 `clj -M:poly test brick:ontology` is **not a logic bug** — it's that the
ontology brick's canonical gate contains **live/slow integration tests** that don't
belong in a fast, hermetic unit gate:

- **`s17-deterministic-skeleton-test`** — drives `deterministic-skeleton/build!`
  through `:embed`/`:index`, which call the **live ColBERT Python bridge**. With no
  working bridge in the run environment it **STALLS** (JVM idle, frozen colbert
  subprocess). RF4-independent (0 refs to reindex/rebuild code). It only started
  being *selected* once RF4's source change expanded poly's incremental set.
- **`c2a-live-verify-test`** — ~9 min by design (30s/phase hard sleeps in its
  orchestrator). Passes, but bloats the gate.

`r03-ood-stress-test` is NOT in this bucket — measured ~45ms, a fast pure-helper
unit suite; it stays in the gate.

## The decision (repo-owner call)
How should the ontology brick separate **fast hermetic unit tests** (the canonical
`clj -M:poly test` gate — must be fast, deterministic, and pass WITHOUT a live
ColBERT bridge) from **live/slow integration tests** (ColBERT-bridge-dependent like
s17; sleep-heavy live-verify like c2a — run on demand / in a bridge-provisioned CI
lane)? Options to weigh:
- **(a) Relocate** the live/slow tests out of the brick `test` tree into
  `development/` (on-demand via `:dev:test`), matching how c2a's orchestrator already
  lives in dev. Clean; poly has no per-namespace exclude, so relocation is the only
  in-poly mechanism. Cost: they no longer run under `clj -M:poly test` at all.
- **(b) A test-selection convention** — tag live/slow tests (metadata) and run two
  lanes: a fast default gate that excludes them + a separate live lane that includes
  them (requires a runner that honors the tag; poly's built-in runner may not).
- **(c) Provision the ColBERT bridge** for the gate so s17 runs green there, and only
  pull c2a (the sleep-heavy one) out. Keeps s17 coverage in the gate at the cost of a
  bridge dependency.

## Acceptance (once the approach is chosen)
- [ ] `clj -M:poly test brick:ontology` runs fast, deterministically, and **exits 0** without requiring a live ColBERT bridge (or with the bridge, per option c).
- [ ] The live/slow tests remain runnable on demand (not deleted — coverage preserved).
- [ ] `r03` stays in the fast gate.
- [ ] No production code change; test-organization only.

## Blocked by
RF2 + RF4 (both landed). This is the last layer of the gate-trustworthiness cascade
(RF1 → build-atomicity/RF3 → seed-load/RF2 → reindex/RF4 → live-test-hygiene/RF5).

## Core Disciplines
Binding Core Disciplines block 1–13 — identical to RF1. Especially #4/#9 (no false
green; a stalled/skipped live test is not a pass), #11 (branch, one commit/slice,
co-author, JVM hygiene), and the cross-terminal alignment note: relocating files
under `development/` touches shared dev layout — coordinate / keep footprint minimal.
