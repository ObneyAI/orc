# RS-7 handoff — realistic-traffic tooling (corpus generator + large-sample harness), one bundle

Plan: `docs/issues/domain-child-convergence/PLAN.md` — read "Part A — realistic-traffic experiment (RS-7)" whole; it
is the spec for this bundle. Work in `/Users/darylroberts/Desktop/Code/orc-convergence-arc` (branch
`feature/domain-child-convergence`, HEAD `51caa798`). Runner: `clojure -J-Djava.awt.headless=true -J-Xmx1600m
-M:dev:test -e "…"` from THIS worktree, one JVM at a time, 0 orphans after every run; never kill a JVM you did not
start. Do not run `poly test`. Do not commit, push or stash. Never edit `specs/*.allium`. Never write the API key
anywhere; live calls read `OPENROUTER_API_KEY` from the environment only.

## Goal

Everything needed to run a 400-task, two-pass, resumable classify-only sweep with structured ground truth, plus a
30-campaign end-to-end subset, and to compare a baseline run against a post-fix run on the same corpus — as ONE
bundle, tested together. The briefs (`briefs.edn`) are authored by the orchestrator in parallel; build against the
brief shape in the plan and a small fixture set of 3 briefs you write for tests only (mark them `:fixture? true`).

## Read first

- PLAN.md Part A (corpus layout, manifest, generator, flags, harness, reindex policies, metrics, e2e subset).
- `development/src/rs6_specialisation_sweep.clj` (`classify-one!`, `run-convergence!`, `convergence-analysis`,
  `persist-pass!`, `run-full-bench-observation!`, `sheet-events`), `development/src/rs6_usefulness_bench.clj`
  (`child-state`), `development/src/rs_p1_coverage_probe.clj` (`install-probe!` — the alter-var-root tee idiom),
  `development/bench/runner.clj` (`start!`, `create-context` skip-procs, `seed-corpus-and-build-index!`,
  `drive-projectors!`, `config`, `register-models!`, `declared-models`), `components/ontology/test/ai/obney/orc/ontology/test_support/c2d_ood_stress_test.clj`
  (`load-corpus`), `components/ontology/src/ai/obney/orc/ontology/core/reranker.clj` (`rerank!` — where the orc
  execute result's `:usage` is discarded), `components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj`
  (`maybe-rebuild!`, `force-rebuild!`, the reindex processor), the colbert read-model accessor for the active index,
  `components/gepa/src/ai/obney/orc/gepa/core/todo_processors.clj` `make-llm-fn` (the `llm/predict` module shape),
  `development/src/diagnose_llm_hang.clj` (a minimal predict call).

## The exact change

1. `development/src/traffic_corpus_gen.clj`: `generate! [briefs-path out-dir opts]` (two `llm/predict :openrouter`
   calls per brief × 5 variants, style cards, prior instances as "do not reuse", NO label/group/brief-id in the
   prompt, JSON array output parsed with clojure.data.json, per-brief usage + prompt sha recorded);
   `flag-near-dups! [corpus-dir manifest]` (within-group token-set Jaccard > 0.6 → `:repeat`, cross-group > 0.35 →
   `:ambiguous-truth`, ColBERT nearest neighbour from another group via `colbert/rerank` with no index →
   `:neighbour-other-group`, in-domain variant missing the brief's output-kind tokens → `:output-kind-missing`);
   `freeze! [corpus-dir]` (computes `corpus-sha256` over sorted accepted `(slug, instruction-sha256)` and writes it
   into `manifest.edn`); the interleaved 3-digit slug scheme `NNN-<group>-vVV` with a seeded round-robin order
   recorded in the manifest. Flags are review aids only — never a metric.
2. `development/src/rs7_traffic_sweep.clj`: `run-pass! [ctx {:corpus-path :manifest-path :results-dir :pass
   :reindex-policy :retry-errors? :corpus-filter}]` with atomic per-record writes (`.tmp` + ATOMIC_MOVE), resume
   (skip `:ok`, retry `:error` when asked), a 180 s per-task future timeout recorded as `:error :harness-timeout`,
   an events snapshot every 25 tasks and `restore! [snapshot]`; `install-tee!` (alter-var-root on the orc execute
   var and `reranker/rerank!`) capturing `:usage`, `:rerank-calls`, `:timed-out?` per slug via a `::task-slug` key
   on the wedge ctx (add an optional ctx-extra arg to `rs6-specialisation-sweep/classify-one!`, no behaviour
   change); `:index-id-at-classify`, `:newborn-in-index?`, `:reached-via`; reindex policies `:none`,
   `{:every-k K}` (synchronous `force-rebuild!`), `:processor` (`runner/start-reindex-processor!`, new, starts the
   skipped processor after seeding); `run.edn` (commit via `git rev-parse HEAD`, dirty flag, corpus sha, task
   order, policy, parallelism, models incl. `reranker/resolve-model`, thresholds, jvm args, timestamps) and
   `tree-before.edn`/`tree-after.edn` (every `tree-class:` concept `{:uri :label :broader :provenance}`);
   `analyse [results-dir manifest]` (the metrics in Part A, ground truth = manifest group, deferrals counted
   explicitly, never `:domain-label`); `compare-runs [dir-a dir-b]` (refuses mismatched corpus sha or order; writes
   `COMPARISON.md`); `run-e2e! [ctx {:manifest-path :groups :results-dir}]` (v01 + last accepted variant per
   selected group, fresh store, v01s first; records per Part A); markdown renderers `TRAFFIC.md`.
3. `development/bench/runner.clj`: make `drive-projectors!` public; add `start-reindex-processor!` and
   `active-index-id`. No other runner behaviour changes.
4. Tests (synthetic records, no LLM, no ColBERT index): `development/test/rs7_traffic_sweep_test.clj` (or under
   `components/ontology/test/.../test_support/` if `development/test` is not on the test path — check `deps.edn`):
   slug parsing; interleave order; resume skips `:ok` and retries `:error`; `analyse` on hand-built records including
   deferred rows and in-domain groups; `compare-runs` refuses mismatched corpora and reports changed slugs;
   `flag-near-dups!` on three fixture instructions; `freeze!` sha stability. Plus ONE live smoke:
   `(run-pass! …)` on a 6-task fixture corpus with `:reindex-policy {:every-k 3}` proving `:usage` is non-nil,
   `:rerank-calls` ≥ 2, `:index-id-at-classify` present, and kill-and-restore reproduces `tree-before.edn`
   (drive the kill by stopping the JVM mid-pass from a second shell — document exactly how you did it).

## Bundle testing discipline (user direction)

Write the whole bundle, then test it TOGETHER: one red run of the synthetic suite before implementing the pure
functions (capture the RESULT line), then implement, then green; then the live smoke. Do not run a JVM per tiny
change. Report every RESULT line and the smoke's record files verbatim.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** Reproduce → minimise → fix the actual cause; rule out the
  harness itself.
- **Test behavior through public interfaces**, so tests survive refactors. Injected capabilities default to the real
  implementation and are faked in tests.
- **Durable tests AND live QA.** The smoke run is mandatory before "done".
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.
- **No regex or phrase matching over model-authored prose — in tests or in production metrics.** Ground truth is the
  manifest's group; flags are review aids.
- **Never write the API key anywhere.**

## Do NOT touch

`specs/*.allium`; anything under `components/*/src` (this bundle is development-only plus `runner.clj`);
`rs6_specialisation_sweep.clj` beyond the optional ctx-extra arg; the existing result directories.

## Report back

Files created/changed; RESULT lines (red, green, smoke) verbatim; one smoke record EDN verbatim; the `run.edn` of
the smoke; how the kill-and-restore was driven and what it showed; anything you could NOT verify; the orphan-JVM
check (`pgrep -fl orc-convergence-arc`).
