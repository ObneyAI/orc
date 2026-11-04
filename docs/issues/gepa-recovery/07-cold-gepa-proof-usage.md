# Cold GEPA recovery proof usage

Run from this reconciled ORC worktree. Coordinate the sole JVM lease first.
These commands must run sequentially in separate JVMs; neither creates a child JVM.

```sh
clojure -J-Xmx1g -M:dev:test -m ai.obney.orc.orc-service.gepa-cold-recovery-test prepare /private/tmp/gepa-cold-proof.edn
clojure -J-Xmx1g -M:dev:test -m ai.obney.orc.orc-service.gepa-cold-recovery-test recover /private/tmp/gepa-cold-proof.edn
```

The prepare command refuses to overwrite an existing manifest. Use a new explicit
manifest path for another independent run. It retains its dedicated temporary
SQLite directory for inspection; neither command removes other artifacts.

Prepare builds a real code-node sheet and three candidate evaluations. The saved
states stop after ownership admission, after the original tree completes but
before GEPA records its output, and after the actual default metric finishes but
before aggregate publication. Input, instructions, stable call/tick/boundary IDs,
metric identity and datasets are durable. No result scores are invented.

Recover checks that its process ID differs from prepare, opens the same SQLite
store with a fresh cache, and requests public GEPA recovery. Ordinary sheet and
recovery processors perform the work. Frontier and proposer processors are
excluded to isolate the three evaluation crash boundaries without starting
unrelated optimization/model work. The injected lease clock advances beyond the
old owners' leases; execution itself uses real ORC processors and durable ticks.

Assertions cover original identity and exact inputs/outputs, one original tick
and code-node completion per task, one metric attempt per task, preserved finished
scores, complete aggregates, consistent accounting in five public views, and
no added work or events after repeated recovery requests. Each command prints
assertion counts and exits nonzero for failures/errors. A 150-second watchdog
bounds each process; prepare closes its processors, store, pubsub and cache before
returning.

The custom metric registration-loss policy remains covered by separate metric
runtime tests; this fixture does not claim a cold-process custom-registration
proof. These are deterministic execution/recovery checks, not live-provider QA.

## Executed evidence

The first prepared fixture is retained at `/private/tmp/gepa-cold-proof.edn`.
Prepare exited 0 with 25 passing assertions; recovery in a distinct JVM exited 0
with 66 passing assertions. Logs are `/private/tmp/gepa-cold-prepare.log` and
`/private/tmp/gepa-cold-recover.log`. No assertions failed or errored.

That store is now recovered. To independently repeat the entire two-process
experiment, use a new manifest path and execute both commands. Running the
recovery CLI on an already-recovered fixture is not the initial-recovery test:
its first assertion expects three recovery requests. The original recovery run
already asserts that subsequent public recovery requests append no events.

The first fixture also exposed a separate grading defect: the default metric
looked up string output keys before falling back to the first string value,
whereas durable tree outputs use keyword keys. Its recorded finished score was
0.0 even though the answer matched the expected value. The recovery proof
preserved that actual recorded score, without inventing a replacement or grading
again. This finding was handed to the root agent for a focused grading repair;
it does not invalidate the execution/recovery identity proof. A fresh fixture
after that repair should record the corrected grade.
