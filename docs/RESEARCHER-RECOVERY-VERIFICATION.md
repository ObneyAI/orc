# Verify researcher recovery contracts

These checks cover checkpointed code correction, mixed delegates, setup refusal
feedback, output capture, configuration round trips, source-bound functions,
model authority after recovery and typed timeout evidence. They use public
ORC/Grain execution, SQLite and local scripted HTTP responses. They make no
hosted model calls.

## Run the focused checks

From the repository root:

```sh
cd projects/orc-service
clojure -J-Xmx1536m -J-XX:MaxMetaspaceSize=768m \
  -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -Sdeps '{:aliases {:researcher-repair-check {:extra-paths ["../../components/orc-service/test" "../../components/llm/test"] :extra-deps {dk.ative/docjure {:mvn/version "1.21.0"} obneyai/grain-event-store-sqlite-v3 {:git/url "https://github.com/ObneyAI/grain.git" :git/sha "dbf5b5229aad09785e40f71e96adf65c90f971d5" :deps/root "projects/grain-event-store-sqlite-v3"}}}}}' \
  -M:researcher-repair-check ../../development/researcher-recovery/verify.clj
```

An optional final argument selects a report directory. Otherwise, the runner
creates a temporary directory and prints its path. It records each namespace's
result, the selected timeout-replay control, loaded source hashes and the JVM
identity. It exits nonzero on a failed assertion or execution error. It checks
the production component graph and the owning test dependencies, without
loading the full application project or its unrelated integrations.

The ordinary component tests also include the portable restart regressions.
The composed researcher test starts a local provider and two owned JVMs. It
kills the first at a pending generated-code boundary, then recovers the original
researcher and child in the second. It verifies the completed prefix once,
original function/source identities, model authority, deadline and call budget,
and canonical output containing false and nil. Both child processes must stop.
Its local protocol script checks transport and recovery, not model reasoning.

## Proof scope

The packaged command passes **66 tests and 1,378 assertions**, with zero
failures or errors. The composed restart regression contributes 47 assertions
to that total. The source guard verifies all 64 production resources before
execution. The report preserves each namespace's separate result and the
selected timeout replay check.

These counts describe this source revision. The earlier combined 63-test run
and the later 16-test timeout check remain historical evidence; their overlapping
assertions are not added to the current total.

The specifications retain 39 warnings and 116 informational diagnostics, with
zero errors and zero process findings. `allium check specs` exits 1 for this
baseline; `allium analyse specs` exits 0. The coverage ledger records the three
existing GEPA diagnostic sources. The warning baseline is not a clean check.

The checks do not establish arbitrary captured closures, compiled-sheet export
and import of callable metadata, every restart window, hosted semantic quality
or exactly-once effects in external systems. Restoring a function requires the
admitted source metadata; older ephemeral-only functions cannot supply missing
source. Cleanup retains bindings when ownership observations are unavailable.

The version-1 source format accepts one EDN-readable `fn` or `fn*` form. It does
not serialize runtime objects inside the form. Generated code that needs a
pattern can construct it with `re-pattern` inside the function instead of
storing a regex object in its source form. Captured and shadowed global bindings
remain unsupported; refusal occurs before the generated body executes.
