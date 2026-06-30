# GC-14 — Surface the `:max-extract-concurrency` knob to the build path

**Type:** AFK (small) · **Blocked by:** GC-13 (✅ committed `62598958`) · **Status:** documented follow-on, not yet built.

## Why this exists

GC-13 added bounded-concurrency per-container extraction with a `:max-extract-concurrency` input on `orchestrate-extract-containers` (default `default-max-extract-concurrency` = 5). The knob works when a caller passes it on the Extract sheet's inputs — BUT the central evolver's `delegate-extract` (`central_evolver.clj`, the `:delegate` whose `:reads` is `[:model-spec :source :max-containers :max-windows]`) does NOT forward `:max-extract-concurrency`, so the real build always runs at the default 5. That default is what made the 6/5 build complete in 16.2 min, so this is NOT blocking — it is a tunability gap.

## What to build

Thread `:max-extract-concurrency` from `run-central-evolver!` / the evolver config down through the per-source pipeline so the build can tune extract concurrency (e.g. a smaller bound for a rate-limited provider, a larger one for a fast provider with many containers). Add it to `delegate-extract`'s `:reads` (mirroring how GC-9 crossed `:max-containers`/`:max-windows`) and to the eb12 driver's `run!` opts.

## Acceptance criteria

- [ ] A build can set extract concurrency end-to-end (driver opt → evolver → the Extract orchestrate node), with the GC-13 default (5) preserved when unset.
- [ ] A unit/integration check that the value actually reaches `orchestrate-extract-containers` (not silently defaulted).
- [ ] Consider clamping the effective bound for the SQLite store to ≤ the event-store pool size (`:maximum-pool-size`, default 4) to avoid write contention — VERIFY whether contention is real first (GC-13's 6/5 SQLite run at bound 5 / pool 4 completed cleanly with 0 dangling, so this may be unnecessary; measure before adding).

## Disciplines

The 13 Core Disciplines apply verbatim (see the GC-13 handoff). Domain-agnostic; no false-green; TDD; JVM hygiene.
