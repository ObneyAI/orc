# Backbone — Intermittent SIGSEGV in the shared `:all-bricks` test JVM (lmdbjava native crash)

## Origin

Reported by Cameron in the `#orc` Slack thread on PR #29 ("A wedged run fails
distinctly at every run-promise deref seam and is never retried"), with a
concern raised separately: is PR #29's work exacerbating this?

## Verified mechanism (code-mapped)

- **Symptom**: `clojure -M:poly test project:orc :all-bricks` — the exact
  command CI's "ORC test suites" job runs — intermittently aborts the whole
  JVM with a native SIGSEGV, problematic frame
  `lmdbjava-native-library-<hash>.so`. Not a JVM exception; not catchable from
  Clojure. Core dump, `hs_err_pid<N>.log`, job exit 1.
- **Trigger point is consistent across both observed occurrences**: the crash
  lands within ~8s of `Testing ai.obney.orc.evaluation.judge-runtime-test`
  starting, always immediately after a long run of `ai.obney.orc.colbert.*`
  tests (colbert's own suite is itself LMDB-heavy: `index-store-test`,
  `corpus-test`, `index-search-test`, etc.) earlier in the same JVM process.
- **Frequency**: found on 2 of ~40 total CI runs in this repo's history — both
  on `attempt 1` of a run, both self-healed on `attempt 2` (rerun-clean). Rate
  ≈ 5%.
- **Causally independent of any single PR's diff.** Confirmed by finding the
  identical crash on PR #26's attempt 1 ("a claim-only holdout removes claim
  content and nothing else" — unrelated to LMDB, orc-service RLM injection
  work) predating PR #29 by 5 days. PR #29's own diff (executor.clj,
  rlm_tree_executor.clj, runtime.clj, streaming.clj — the wedge-liveness
  classifier) does not touch LMDB, grain-test-utils, or kv-store-lmdb in any
  way. **PR #29 is not exacerbating this** — the crash predates it and the
  diff has no plausible causal path to it.
  - The one measurable delta PR #29 introduces is marginal: one new test
    file (`pr4_run_liveness_test.clj`, 6 tests) that, like every other
    orc-service test using `with-async-test-context`, opens+closes one more
    LMDB env. That's roughly a 0.8% increase (6 of ~121 env-creating test
    namespaces already in the suite) — noise relative to a ~5% baseline
    crash rate, not a plausible driver of it.
  - Directly re-ran PR #29's own new test 6/6 clean, and the full
    `brick:orc-service` suite 0 failures/0 errors/no crash (9m44s, one
    sample — the crash is intermittent, so a clean run doesn't prove absence,
    but it's consistent with "unrelated, low base rate" rather than "this PR
    made it worse").

- **Root-cause candidates ruled out:**
  - *Stale lmdbjava version with a known fix available*: checked. Pinned
    version is `org.lmdbjava/lmdbjava` `0.9.2` (via grain's vendored
    `kv-store-lmdb` component). The one upstream defect matching this exact
    symptom class — lmdbjava#185, "closed Env causing unexpected SIGSEGV from
    later usage" — was fixed in `0.8.3` (Feb 2023), which `0.9.2` already
    contains. This is not "upgrade and the bug's proven gone." A newer patch,
    `0.9.3` (Feb 2026), exists but its changelog doesn't name a matching fix
    — bumping to it is cheap and can't hurt, but is not a proven fix.
  - *ORC-side direct resource leak (env/db never closed)*: checked. Every one of the
    ~121 test-context-creating namespaces goes through
    `orc-service/test_helpers.clj`'s `create-test-context` /
    `with-async-test-context`, whose teardown (`stop-context` /
    `stop-async-context`) unconditionally calls `kv/stop` (closes the `Dbi`
    then the `Env`) before deleting the temp dir. That audit established that
    resources are closed, but it did not establish that every nested future
    has stopped before the close. The later follow-up below falsifies the
    stronger interpretation that teardown was therefore safe.

- **Root-cause candidates NOT ruled out (genuine open question):**
  - `kv-store-lmdb/core.clj` already documents that ORC's threaded/futures
    workload exhausts LMDB's default 126 reader slots and had to bump
    `max-readers` to 1024 — i.e. this integration already has a known,
    previously-patched fragility under heavy concurrent churn. The
    reader-lock table's native mmap scales with `max-readers`; whether
    repeated open/close of many such envs across ~121 short-lived test
    contexts in one JVM process is what corrupts native state (rather than
    a genuine ORC-side bug) is plausible but unproven — would need either a
    tool like `Verifier`/ASAN or a much larger repro sample to pin down.
  - The crash could equally be collateral damage from a DIFFERENT native
    library's heap corruption surfacing on lmdbjava's next unrelated call
    (the same JVM process also loads DJL/ONNX Runtime native code for the
    colbert encoder — visible in the same log stream, e.g.
    `ai.djl.onnxruntime.engine.OrtEngine -- CUDA is not supported`). Not
    investigated further; would need a native-level tool (valgrind/ASAN) on
    a GH-runner-equivalent box, which is out of scope for this pass.

- **Same failure class as two already-documented, already-mitigated
  problems in this repo.** `workspace.edn` carries first-hand comments
  (`orc-colbert`, `orc-ontology`, `orc-evaluation`, `orc-gepa` project
  entries) describing this EXACT shape of bug for two OTHER native/JNI
  dependencies:
  - DJL tokenizers natives: `UnsatisfiedLinkError` when the same native lib
    loads in a second classloader within one test JVM.
  - SQLite JDBC driver: `DriverManager`/`ServiceLoader` only registers the
    driver in the FIRST project's classloader; a second run in another
    project's classloader gets "No suitable driver".
  Both were fixed the same way: `{:test {:include []}}` on every
  deps-only packaging project, so the composing brick's tests run exactly
  ONCE, in exactly one classloader, under the `orc` umbrella project only.
  **LMDB is not yet covered by this precedent** — no `workspace.edn` entry
  documents or mitigates it, despite it being the same underlying class of
  "shared mega-JVM, one native library, many short-lived call sites"
  fragility.

## Spec impact

None. This is test-harness/CI reliability — a native-library resource
lifecycle question about the shared `:all-bricks` test JVM, not a behavioral
obligation of any ORC domain entity, command, or rule. The `distill` skill's
own scoping criteria (exclude "infrastructure... not domain-level"; final
checklist: "No infrastructure (Redis, Kafka, S3, etc.)") rule this out as an
Allium spec candidate. No `.allium` file should model it. If a fix changes
observable production behavior (it shouldn't — the likely fixes are
test-topology-only), that fix's OWN spec impact would be assessed separately
at that time.

## Open decision (needs a real choice, not more digging)

Three candidate mitigations, not mutually exclusive:

1. **Extend the workspace.edn precedent to LMDB.** Add an explicit
   `{:test {:include []}}` (or equivalent) constraint isolating the
   colbert↔evaluation LMDB-churn boundary the same way DJL/JDBC already are,
   so `judge-runtime-test`'s LMDB opens don't share a JVM with colbert's LMDB
   opens. Directly targets the one thing we know for certain (trigger point
   is always colbert-tests-then-judge-runtime-test in one JVM).
2. **Bump lmdbjava 0.9.2 → 0.9.3.** Cheap, low-risk, not proven to fix this
   specific symptom (no matching changelog entry found), but worth doing
   regardless since it's free.
3. **Accept it as a known-flaky rerun-clears-it CI cost**, same posture
   already implicitly taken for the two live-LLM flakes found this session,
   and just document it (a workspace.edn comment, no behavior change) so the
   next person who hits it doesn't re-diagnose from scratch.

This is the actual open question for a grill, not "does the bug exist" (it
does, confirmed) or "did we cause it" (we didn't, confirmed) — it's a
cost/benefit call between (1) real engineering time isolating a rare native
crash vs (3) tolerating a ~5%-of-runs rerun cost, with (2) as free insurance
either way.

## Decision

Chose (1) + (2): isolate colbert into its own CI JVM, and bump lmdbjava.

## Implementation

- `workspace.edn`: `"orc" {:test {:exclude ["colbert"]}}`,
  `"orc-colbert" {:test {:include ["colbert"]}}` (previously `[]`). Mutually
  exclusive by construction — colbert's tests run in exactly one project's
  scope under every invocation mode, never duplicated, never sharing a
  classloader with `orc`'s suite.
- `.github/workflows/tests.yml`: new step "Run colbert brick tests (isolated
  JVM)" — `clojure -M:poly test project:orc-colbert :all-bricks` — runs
  BEFORE "Run aggregate ORC project tests", as its own separate `run:` step
  (a separate OS process / JVM, no shared classloader risk with the
  aggregate step regardless of invocation mode).
- `projects/orc/deps.edn` + `projects/orc-colbert/deps.edn`: both pin
  `org.lmdbjava/lmdbjava {:mvn/version "0.9.3"}` ahead of grain's transitive
  `0.9.2`. Confirmed resolved (not silently ignored): `clj -A:test -Spath`
  under `projects/orc-colbert` shows `lmdbjava-0.9.3.jar` on the classpath,
  and its transitive native-bundle dependency came along for free too —
  `native-0.9.33-5.jar`, up from the previously-resolved `0.9.29-1` (four
  patch releases newer; this is the artifact that actually contains the
  crashing `.so`).

## Verification (local, real runs — not CI-log-reading)

- `clj -M:poly check`: identical warning set to baseline (no new structural
  issues from the workspace.edn change).
- `clj -M:poly test project:orc-colbert :all-bricks`: colbert's suite now
  runs and passes here — 6 namespaces, 52+15+24+55+95+9+95 = 345 assertions,
  0 failures, 0 errors, 10s wall-clock.
- `clj -M:poly test project:orc :all-bricks` (the exact command CI's
  "ORC test suites" job runs): zero occurrences of
  `Testing ai.obney.orc.colbert.*` in the output — colbert is confirmed
  excluded — every remaining brick's tests still pass (0 failures, 0 errors
  across every summary block), 12m36s wall-clock (in line with the
  pre-change 9–17min baseline; the split did not blow up CI time).
- Honest limit: the SIGSEGV is ~5% intermittent. A single clean local run of
  the modified aggregate suite is consistent with "not obviously broken,"
  not proof the crash can no longer occur. The real test is CI over the next
  handful of runs. No claim of "fixed" beyond "isolated per the confirmed
  trigger mechanism, with a free version bump alongside."

## Follow-up: the isolation mitigation was incomplete

A later aggregate run falsified the original adjacency hypothesis. ColBERT ran
in its isolated JVM and passed; the aggregate JVM completed every displayed
namespace through `repl-researcher-mint-contract-test`, then crashed as
`gepa-integration-test` began. The native frame was `_mdb_txn_commit` in
`lmdbjava-native-library-*.so`, exit 134. Therefore ColBERT sharing a JVM with
evaluation was one pressure pattern, not the root lifecycle defect.

The missing ownership fence is code-mapped:

- terminal result delivery can release `sheet/execute` before trace storage is
  finished;
- `assemble-execution-trace` launched its store command with a raw, untracked
  `future` from inside a todo processor;
- `stop-async-context` stopped the processors and immediately closed the event
  store and LMDB cache. Grain's processor stop closes its input channel but
  does not join handler threads already dispatched through its core.async
  executor, nor can it join a future that a handler already detached;
- PR #36 did not originate that raw future, but its post-node and
  post-ephemeral trace refreshes cause the same assembly path to launch more
  often, making the latent lifetime race materially easier to expose.

The harness proof is deterministic rather than a lucky native-crash sample.
Before the fix, a gated owned worker showed teardown returning and closing the
cache while the worker was still blocked; the observed order was
`[:cache-closed :background-finished]`. A separate execution-shaped test showed
that trace storage bypassed an injected context supervisor entirely. Both
tests failed before implementation.

## Follow-up implementation

- Trace storage submits through the injected `:orc/submit-background!`
  capability. Library callers that do not provide lifecycle supervision retain
  the existing non-blocking future behavior.
- Async test contexts install an admission-fenced supervisor. Trace assembly
  acquires ownership before its first projection/cache read, and the nested
  store future acquires its own token before it can run.
- Teardown atomically closes admission before stopping processors. A handler
  dispatched late is rejected before it can touch LMDB; every handler or task
  admitted before the fence is drained while pubsub, event store, and LMDB
  remain alive. A token that cannot drain within the bounded teardown budget
  fails teardown without closing LMDB underneath it.

The three RED proofs are green after the implementation: trace writes traverse
the context supervisor, the gated order is now
`[:background-finished :cache-closed]`, and late trace work is rejected after
the admission fence closes. The intermittent native symptom still requires
repeated aggregate/CI observation; the supported claim is that the previously
unowned work-after-close path is now closed, not that one green run can prove a
native crash impossible.

Adversarial inspection found and rejected an incomplete first version of this
fix: it stopped processors and then snapshotted registered futures. Because
Grain processor stop does not join already-dispatched handlers, a late handler
could register after that snapshot. A third RED regression submitted trace work
while teardown was blocked at the drain; the work ran under the incomplete
design. With atomic admission closure it is rejected, and the regression passed
10/10 stress repetitions.
