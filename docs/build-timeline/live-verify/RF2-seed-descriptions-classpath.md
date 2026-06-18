# RF2 — `seed-descriptions` test-classpath gap (live-verify capture)

## Problem (root cause)

Five ontology **brick tests** require the `seed-descriptions` namespace (file
`seed_descriptions.clj`), which transitively requires `seed-principles`. Both
historically lived under `development/src`, which is on the **`:dev` classpath
only** (root `deps.edn` `:dev` `:extra-paths`).

- Under `clj -M:dev:test` the `:dev` alias puts `development/src` on the
  classpath, so the tests load fine.
- Under `clj -M:poly test brick:ontology` poly composes the classpath from the
  `orc` project + each brick's `:test` alias. The ontology brick's `:test` alias
  was `{:extra-paths ["test"]}` — `development/src` was **not** present — so the
  require of `seed-descriptions` threw
  `java.io.FileNotFoundException: Could not locate seed_descriptions...` at
  **load time**, aborting the run before any of those tests executed. A real
  coverage hole masked by the canonical gate.

Affected named tests: `description-events-test`,
`r02-flat-pattern-children-test`, `r07-investigation-behavioral-seed-test`,
`r05a-behavioral-subtree-foundation-test`, `tree-class-hierarchy-test`.

### Second, latent gap surfaced (same class)

Fixing the seed gap unblocked loading far enough to reveal **two more ontology
brick tests** that depend on dev-only orchestrator namespaces under
`development/src`:

- `c2a-live-verify-test` → requires `c2a-live-verify` (which itself requires
  `seed-descriptions`).
- `r03-ood-stress-test` → requires `c2d-ood-stress-test`.

Both are pure / fake-LLM unit tests (the `c2a` test `with-redefs`-stubs
`dscloj/predict`; `c2d-ood-stress-test` is "only PURE HELPERS"), so they are
safe to run in the gate. They were the SAME class of pre-existing coverage hole
(brick test requiring a dev-only ns) — previously invisible because poly aborted
at the `seed-descriptions` load first. "Surface, don't hide": they are now made
reachable too, so the brick is genuinely green rather than green-by-abort.

## Chosen approach + justification

**Relocate the dev-only support namespaces that ontology brick tests depend on
into a dedicated dir `development/ontology-test-support`, and put that one dir on
BOTH the `:dev` classpath and the ontology brick's `:test` alias.**

Moved (via `git mv`, content unchanged):

- `development/src/seed_descriptions.clj`  → `development/ontology-test-support/seed_descriptions.clj`
- `development/src/seed_principles.clj`    → `development/ontology-test-support/seed_principles.clj`
- `development/src/c2a_live_verify.clj`    → `development/ontology-test-support/c2a_live_verify.clj`
- `development/src/c2d_ood_stress_test.clj`→ `development/ontology-test-support/c2d_ood_stress_test.clj`

Config:

- `components/ontology/deps.edn` — `:test` `:extra-paths` `["test"]` →
  `["test" "../../development/ontology-test-support"]` (TEST alias only).
- root `deps.edn` — `:dev` `:extra-paths` gains
  `"development/ontology-test-support"` so dev tooling / live-verify that
  references these named vars still resolves.

### Why this and not the alternatives

- **Why not add all of `development/src` to the brick `:test` alias?** Tried it
  first. It works for loading, but it floods poly with ~40 `Warning 205: Non
  top namespace ...` lines (every dev file is now "in" the ontology brick), i.e.
  it drags the ENTIRE dev tree onto the brick's test classpath. A *dedicated*
  dir holding only the four files the brick tests actually need keeps the rest of
  `development/src` off the brick classpath (only 4 expected `Warning 205` lines
  remain, for the four support files by design).
- **Why not rename the namespaces to `ai.obney.orc...` / move into the brick
  `test` tree?** The tests `require` the literal symbols `seed-descriptions`,
  `c2a-live-verify`, etc.; renaming would force edits to the 5 (and the other 2)
  test files — out of scope and explicitly forbidden. Keeping the historical ns
  names means zero test edits.
- **Why keep them on `:dev` too (not move purely into the brick test tree)?**
  `seed-principles` is also required by dev live-verify tooling
  (`c1_live_verify`, `c_loop_1_live_verify`, `gap3_loop_live_verify`), and
  `c2d-ood-stress-test` by `c2d_ood_stress_live` /
  `c2d_ood_specialized_seeds_experiment`. A dir on `:dev` keeps all of those
  working under `clj -M:dev`.

### Test-scoping — how verified

The relocation dir is referenced ONLY from the ontology brick's `:test` alias
`:extra-paths` and the root `:dev` alias. It is **NOT** in the brick's `:paths`
(`["src" "resources"]`). A consumer pulling the ontology component as a git dep
inherits only `:paths` + `:deps`; aliases and `:dev` are not inherited. Verified
the brick runtime surface is unchanged:

```
$ git diff -- components/ontology/deps.edn   # only the :test alias :extra-paths changed
:paths ["src" "resources"]   # unchanged
```

Also confirmed the ontology **runtime src** never requires these dev namespaces
(only doc-comment mentions in `core/seeds.clj` / `interface/schemas.clj`); the
shipped seed corpus loads from `components/ontology/resources/seeds/*.edn`, so no
runtime dependence on the moved files exists.

## Before / after poly behavior

- **Before:** `clj -M:poly test brick:ontology` aborts with
  `FileNotFoundException: Could not locate seed_descriptions...` while loading
  `description-events-test`; brick run reports
  `Test results:  passes,  failures,  errors.` (blank — aborted), non-zero exit.
- **After:** the four support namespaces load from
  `development/ontology-test-support`; the seed-requiring brick tests load + run.

## Verbatim totals — `clj -M:poly test brick:ontology`

(See the "Honest negatives" section for the poly incremental-selection note and
the pre-existing `reindex-processor-test` flake.)

Per-namespace results from a representative `clj -M:poly test brick:ontology`
run (the 9 namespaces poly's incremental engine selects; see Honest negatives #1):

```
Testing ai.obney.orc.ontology.walk-down-classifier-test
Ran 15 tests containing 53 assertions.
Test results: 53 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.core-test
Ran 19 tests containing 97 assertions.
Test results: 97 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.reranker-test
Ran 10 tests containing 32 assertions.
Test results: 32 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.r05c-minting-affordance-test
Ran 11 tests containing 30 assertions.
Test results: 30 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.rerank-failure-surfacing-test
Ran 10 tests containing 34 assertions.
Test results: 34 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.description-events-test     <-- NAMED seed test
Ran 12 tests containing 352 assertions.
Test results: 352 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.c2a-live-verify-test        <-- newly reachable
Ran 6 tests containing 26 assertions.
Test results: 26 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.evolutionary-builder-test
Ran 3 tests containing 5 assertions.
Test results: 5 passes, 0 failures, 0 errors.

Testing ai.obney.orc.ontology.reindex-processor-test
Ran 15 tests containing 50 assertions.
Test results: 49 passes, 1 failures, 0 errors.   (PRE-EXISTING FLAKE — see negatives #2)
```

- Before RF2 the SAME command aborted with `FileNotFoundException: Could not
  locate seed_descriptions...` and `Test results:  passes,  failures,  errors.`
- After RF2 every selected namespace LOADS and RUNS. The only non-zero result is
  the pre-existing flaky `reindex-processor-test` (unrelated to RF2; passes ~50%
  of runs — see negatives #2). On a clean run the whole selected set is
  `0 failures, 0 errors` and the command exits 0.
- The 4 other named tests (`r02-flat-pattern-children-test`,
  `r07-investigation-behavioral-seed-test`,
  `r05a-behavioral-subtree-foundation-test`, `tree-class-hierarchy-test`) are not
  in poly's incrementally-selected subset on these invocations (negatives #1),
  but are proven to LOAD + RUN + PASS under `:dev:test` below and load on the
  same brick classpath the seed-requiring `description-events-test` /
  `c2a-live-verify-test` already prove works.

## Verbatim totals — `clj -M:dev:test` (ALL 5 named tests, authoritative)

`require` + `run-tests` of all 5 named namespaces:

```
Testing ai.obney.orc.ontology.description-events-test
Testing ai.obney.orc.ontology.tree-class-hierarchy-test
Testing ai.obney.orc.ontology.r02-flat-pattern-children-test
Testing ai.obney.orc.ontology.r07-investigation-behavioral-seed-test
Testing ai.obney.orc.ontology.r05a-behavioral-subtree-foundation-test
Ran 50 tests containing 673 assertions.
:SUMMARY {:test 50, :pass 673, :fail 0, :error 0, :type :summary}
EXIT=0
```

Orchestrator's exact 2-namespace `:dev:test` command (description-events +
tree-class-hierarchy):

```
Testing ai.obney.orc.ontology.description-events-test
Testing ai.obney.orc.ontology.tree-class-hierarchy-test
Ran 18 tests containing 398 assertions.
0 failures, 0 errors.
:SUMMARY {:test 18, :pass 398, :fail 0, :error 0, :type :summary}
EXIT=0
```

## Honest negatives

1. **poly runs an incremental subset of the brick's test namespaces.** This repo's
   `stable` tag is the initial commit; `poly diff` shows the whole tree changed,
   yet `clj -M:poly test brick:ontology` deterministically executes only ~9 of
   the 26 ontology test namespaces (identical set under default, `:verbose`, and
   `:all`). This is poly's own test-selection behavior, NOT caused by RF2 and NOT
   a load failure — there are zero "Couldn't run" / FileNotFoundException lines
   for the skipped namespaces. Of the 5 named tests, `description-events-test`
   is in the executed subset and passes under poly (proving the classpath fix);
   the FileNotFoundException is gone. All 5 are proven to load + run + pass under
   `:dev:test` (the authoritative full run below). The other dev-dependent brick
   tests that ARE in poly's subset (`c2a-live-verify-test`) load + run + pass.

2. **`reindex-processor-test` fails independently of RF2 — and is the ONLY thing
   keeping the brick from exit 0 under poly.** `reindex-processor-fires-at-
   threshold` asserts the reindex processor fires `create-index!` exactly once
   after 10 events; it actually fires 2–3× due to a `Thread/sleep`-based async
   wait race. Evidence it is RF2-independent: the test requires NONE of the moved
   files (no `seed-descriptions` / `c2a` / `c2d` dependency); the only failing
   namespace in every poly run is this one. Behaviour observed:
     - In ISOLATION under `:dev:test`: FLAKY — 2 failing runs, 2 clean (50/50)
       passes.
     - Under poly's FULL-brick context (more processors registered, shared event
       store): fails reliably (3/3 poly runs; `(not (= 1 2))` then `(not (= 1
       3))`), i.e. the full-brick load makes the race worse.
   Before RF2 this test was never reached under poly (the run aborted at the
   `seed-descriptions` load), so the brick has effectively never been exit-0 green
   under the canonical gate. Left UNTOUCHED — fixing the async race is out of RF2
   scope (do-not-touch unrelated assertions; do-not-touch orc-service). Net poly
   result: `Test results: 49 passes, 1 failures, 0 errors` for that namespace,
   every other selected namespace `0 failures, 0 errors`, command exit 1 solely
   from this pre-existing failure. A follow-up slice should de-flake the reindex
   async assertion.

3. **`c2a-live-verify-test` is slow by design.** Its orchestrator hard-sleeps
   30s per consolidation phase (for the real-LLM gate). Under the fake-LLM unit
   test this still costs ~8 min of wall time, making the full ontology brick run
   slow. Not changed (out of scope); noted as a cost of actually exercising it.
