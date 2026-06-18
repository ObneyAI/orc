# RF3 — `build-atomicity-test` sqlite brick-dep gap (tracer bullet · deferred)

**Type:** AFK · deferred · **Parent:** `docs/build-timeline/issues/rlm-finalization/README.md`

## What to build
Make `ai.obney.orc.orc-service.build-atomicity-test` LOADABLE under
`clj -M:poly test brick:orc-service`. It requires
`ai.obney.grain.event-store-sqlite-v3.interface` (`build_atomicity_test.clj:31`),
but `obneyai/grain-event-store-sqlite-v3` is declared only in the ROOT `deps.edn`
`:dev`/test deps — NOT in `components/orc-service/deps.edn` — so under the brick
test classpath the require throws `FileNotFoundException` and poly exits non-zero
(the whole `brick:orc-service` gate goes red on a load error, even though all 34
other namespaces / 1370 assertions pass).

The fix is a brick-dependency declaration: add `grain-event-store-sqlite-v3` to
`components/orc-service/deps.edn` (test scope), mirroring the root declaration, so
the brick's own test classpath can resolve it.

Same CLASS of gap as RF2 (`seed-descriptions`): a test in a brick depends on
something only present on the root/`:dev` classpath, so `clj -M:poly test` can't
load it. Surfaced by RF1 — greening the 12 stale `repl-researcher-test` failures
un-blocked the poly run far enough to reach this load error (previously the run
never got past the failing namespace).

## Acceptance criteria
- [ ] `build-atomicity-test` loads + runs under `clj -M:poly test brick:orc-service` (no `FileNotFoundException: event_store_sqlite_v3`).
- [ ] `clj -M:poly test brick:orc-service` exits 0 with all namespaces green.
- [ ] No behavior change to `build-atomicity-test` itself; only its dep reachability.
- [ ] Verify the added dep is test-scoped (does not leak `grain-event-store-sqlite-v3` into the brick's runtime/consumer surface).

## Blocked by
None (independent of RF1/RF2). Deferred — test-infra/deps blast radius; kept out of
RF1 to keep that cycle a pure root-cause fix.

## Core Disciplines
Binding Core Disciplines block 1–13 — identical to RF1 (see
`docs/build-timeline/issues/rlm-finalization/RF1-terminal-final-bang.md`). In
particular: verify under BOTH runners (#4), no false green / no silently-skipped
or unloadable namespaces (#9), branch `feature/ontology-architecture`, one commit
per slice, co-author trailer, JVM hygiene (#11).
