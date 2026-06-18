# RF3 — `build-atomicity-test` sqlite brick-dep gap — live verify capture

**Slice:** RF3 (`docs/build-timeline/issues/rlm-finalization/RF3-build-atomicity-sqlite-brick-dep.md`)
**Change (sole code edit):** add `obneyai/grain-event-store-sqlite-v3` to
`components/orc-service/deps.edn` under `:aliases :test :extra-deps`
(test-scoped), mirroring the root `deps.edn` coordinates exactly
(`:git/url https://github.com/ObneyAI/grain.git`, `:git/sha 0ab49d7fd3847ce5ec485d29a6fbe292ba4e7856`,
`:deps/root projects/grain-event-store-sqlite-v3`).

## Root cause (diagnosed, not assumed)

`ai.obney.orc.orc-service.build-atomicity-test` (`build_atomicity_test.clj:31`)
requires `ai.obney.grain.event-store-sqlite-v3.interface`. That lib was declared
only in the ROOT `deps.edn` `:dev`/test classpath, not in the brick's own
`deps.edn`. Under `clj -M:poly test brick:orc-service`, poly runs the tests from
the **`orc` project** classpath (`projects/orc/deps.edn`), which assembles brick
libs from each brick's `:deps` (src) and `:aliases :test :extra-deps` (test) —
see poly `lib/core.clj` `brick-lib-deps` and `deps/lib_deps.clj` `->config`
(merges `:src` + `:test` lib-deps). The sqlite lib was in neither, so the
namespace require threw `FileNotFoundException: ai/obney/orc/orc_service/...` →
`event_store_sqlite_v3`.

Isolated reproduction of the FileNotFound on the `orc`-project test classpath
(BEFORE fix):

```
$ cd projects/orc && clj -M:test -e "(require 'ai.obney.orc.orc-service.build-atomicity-test)"
Execution error (FileNotFoundException) ...
Could not locate ai/obney/orc/orc_service/build_atomicity_test__init.class,
... on classpath.
```

After adding the test-scoped dep, the same require resolves (`:LOADED-OK`).

## Poly runner semantics (why the full brick gate is currently blocked by RF1, not RF3)

Poly's built-in runner (`clojure_test_test_runner/core.clj` `run-test-statements`)
iterates namespaces with `(doseq [ns ...] require + run-tests)` and **throws /
aborts at the FIRST namespace with `fail > 0` or `error`** (or a nil result from a
load throw). In this workspace the ordering reaches `repl-researcher-test` first,
which has **12 pre-existing failures (RF1's stale-mock domain)**. Poly therefore
aborts at `repl-researcher-test` and **never reaches `build-atomicity-test`** —
so on this RF1-not-yet-applied worktree the sqlite FileNotFound is *masked* behind
the RF1 failures. RF3 is independent of RF1 (issue: "independent of RF1/RF2"), but
the full-brick EXIT-0 acceptance can only be observed once RF1 has greened
`repl-researcher-test`. The orchestrator will cherry-pick RF3 onto a branch where
RF1 is applied.

## Runner 1 — `clj -M:poly test brick:orc-service`

**BEFORE fix (verbatim tail):**
```
Testing ai.obney.orc.orc-service.repl-researcher-test
...
Ran 8 tests containing 23 assertions.
12 failures, 0 errors.
Test results: 11 passes, 12 failures, 0 errors.
```
Exit code: `1`. Aborts at `repl-researcher-test`; never reaches build-atomicity.

**AFTER fix (verbatim tail):**
```
Testing ai.obney.orc.orc-service.repl-researcher-test
Ran 8 tests containing 23 assertions.
12 failures, 0 errors.
Test results: 11 passes, 12 failures, 0 errors.
```
Exit code: `1`. **Identical** — the 12 failures are the RF1 stale-mock failures
(unchanged by this edit; they pre-date it). No `FileNotFoundException`, no sqlite
error. RF3 changes only build-atomicity's dep reachability, not anything in the
namespace poly aborts on.

### RF3 proof under poly's own classpath (isolating the RF1 blocker)

Because poly aborts before reaching `build-atomicity-test`, the RF3 fix is proven
by replaying poly's *exact* statement sequence (require → use clojure.test →
run-tests) for `build-atomicity-test` on the **`orc`-project classpath** (
`projects/orc` `:deps` + `:test` + brick `src`/`resources`/`test` paths + the new
brick `:test` extra-dep), with the same `--add-opens` JVM opts the `:poly` alias
supplies:

```
$ cd projects/orc && clj -J--add-opens=java.base/java.nio=ALL-UNNAMED \
    -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    -Sdeps '{:aliases {:rf3 {:extra-paths [".../orc-service/src" ".../resources" ".../test"]
                             :extra-deps {obneyai/grain-event-store-sqlite-v3 {...root coords...}}}}}' \
    -M:test:rf3 -e "(require 'ai.obney.orc.orc-service.build-atomicity-test) (use 'clojure.test)
                    (run-tests 'ai.obney.orc.orc-service.build-atomicity-test)"

Testing ai.obney.orc.orc-service.build-atomicity-test
Ran 3 tests containing 17 assertions.
0 failures, 0 errors.
:SUMMARY {:test 3, :pass 17, :fail 0, :error 0, :type :summary}
```
Exit code: `0`. (An earlier run of this isolation showed 3 LMDB
`InaccessibleObjectException` errors — a *measurement artifact* of omitting the
`--add-opens` JVM opts the real `:poly`/`:dev` aliases provide; adding them
cleared it. Not a fix defect.) This is the behavior poly will exhibit at
`build-atomicity-test` once RF1 unblocks the run.

## Runner 2 — `clj -M:dev:test` direct (REAL Grain sqlite + lmdb stores)

```
$ clj -M:dev:test -e "(require 'clojure.test 'ai.obney.orc.orc-service.build-atomicity-test)
   (let [r (clojure.test/run-tests 'ai.obney.orc.orc-service.build-atomicity-test)]
     (println :SUMMARY r) (shutdown-agents) (System/exit (+ (:fail r) (:error r))))"

Ran 3 tests containing 17 assertions.
0 failures, 0 errors.
:SUMMARY {:test 3, :pass 17, :fail 0, :error 0, :type :summary}
```
Exit code: `0`. HikariCP / sqlite-jdbc connection lifecycle observed in the logs —
real durable backend exercised, not a mock.

## Test-scoping confirmation (3 independent ways)

1. **File placement:** the dep is under `:aliases :test :extra-deps`, not the
   top-level `:deps` (runtime/consumer surface). The brick `:deps` still contains
   only `DSCloj`, `mulog`, `sci`.
2. **`clj -M:poly libs`** marks the row test-scoped, matching the existing
   test-only `grain-control-plane`:
   ```
   library                              version  type   ?1   dev   ... orc-service col
   obneyai/grain-control-plane          0ab49d7  git    t    x          (test)
   obneyai/grain-core-v2                0ab49d7  git    x    x          (runtime)
   obneyai/grain-event-store-sqlite-v3  0ab49d7  git    t    x     ...  x   <- test (t), attributed to orc-service
   ```
   `t` = test scope (vs `x` = src/runtime). The sqlite dep is `t`, like
   `grain-control-plane`, NOT `x` like `grain-core-v2`.
3. **Consumer projection:** `projects/orc/deps.edn` top-level `:deps` (the
   consumer-facing surface) does not gain the dep; it only flows through the
   brick `:test` alias, which consumers don't activate.

## JVM hygiene

All runs bounded (each ended in `System/exit`; no nREPL left running). After all
runs: `ps aux | grep -i 'Desktop/Code/orc' | grep -iE 'java|clojure' | grep -v grep`
→ **0 orphan JVMs confirmed**.

## Honest negatives

- The full `clj -M:poly test brick:orc-service` gate does **not** EXIT 0 on this
  isolated worktree, because the pre-existing RF1 `repl-researcher-test` failures
  (12) abort poly before `build-atomicity-test` is reached. This is the RF1
  blocker, not RF3, and I was constrained to touch only `deps.edn`. RF3's own
  acceptance (build-atomicity LOADS + passes, no FileNotFound) is proven via the
  poly-classpath isolation above and the `:dev:test` runner; the full-gate EXIT 0
  will hold once RF3 is cherry-picked onto an RF1-applied branch.
