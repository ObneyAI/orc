# R-Bench: 5-benchmark recursive-mode verification sweep (HITL)

## Parent

`docs/prd/rlm-recursive-emit-tree.md` + `development/bench/predict_rlm_comparison/reports/recursive-mode-plan.md`.

## What to build

End-to-end verification that recursive mode (`:rlm {:recursive? true}`) is non-regressive vs terminal mode (`:rlm true`) across all 5 predict-rlm comparison benchmarks. The end-goal stated by the user is "always be recursive and emit-tree is never terminal" — this sweep is the gate that proves recursive is ready to become the default.

This is **HITL** because it spends real OpenRouter budget (~$3-7 for the full 5-benchmark sweep), produces fresh result EDNs, and the pass/fail decision involves comparing quality across runs (some judgment required, not pure mechanical match).

End-to-end behavior:
1. After R-3 + R-4 (R-5 optional) land, run all 5 benchmarks in recursive mode via the existing `run/recursive/<task>.clj` namespaces.
2. For each, capture result EDN under `results/<benchmark>-recursive_<timestamp>.edn`.
3. Compare each recursive run against its terminal headline EDN on:
   - `:status` matches `:success`
   - Output field shapes match (e.g., `:total-redactions` is an int, `:targets-applied` is a non-empty vec, etc.)
   - Output quality is within reasonable variance (counts within ~20%, ISO date formats preserved, headline findings still surfaced)
   - Wall-clock is ≤ 2× the terminal headline (recursive will be somewhat slower due to multiple Phase-1 LLM iterations; that's acceptable)
   - Tokens are ≤ 2× the terminal headline

Pass condition: 5/5 produce real outputs at quality matching terminal-mode within the bounds above.

Fail condition: any benchmark returns empty/degraded output OR wall-clock > 3× terminal. Diagnose before treating recursive as default.

## Acceptance criteria

- [ ] All 5 benchmarks run cleanly via `clj -M:dev:test -m predict-rlm-comparison.run.recursive.<task>` (no Fressian crash, no uncaught exceptions)
- [ ] Each recursive run produces non-empty outputs matching the task's `:writes` declaration
- [ ] Each recursive run's wall-clock is ≤ 2× the committed terminal headline
- [ ] Each recursive run's tokens are ≤ 2× the committed terminal headline
- [ ] Per-benchmark quality spot-check matches terminal-mode within variance bounds (specific tolerances in the per-report acceptance criteria below)
- [ ] Deep-dive document `reports/recursive-mode-experiment.md` (local-only) updated with the 5/5 results
- [ ] Decision: recursive can become always-on default, OR specific blockers documented for follow-up

### Per-benchmark quality bounds

| Benchmark | Terminal headline | Recursive must produce |
|---|---|---|
| image_analysis | 22-of-24 letters match predict-rlm | A-Z letter counts populated; ≥18-of-26 match within ±1 |
| document_redaction | 92 redactions / 100% strict recall (84/84) | ≥80 redactions; strict recall ≥80% (67/84) |
| invoice_processing | 11-of-12 line items / 2 invoices / $34,804.30 total | ≥9-of-12 line items; both invoices extracted; total within ±5% |
| contract_comparison | 4 key-differences incl. Domestic Content removal | ≥3 key-differences; Domestic Content removal identified |
| document_analysis | 19 dates + 13 entities | ≥12 dates + ≥4 entities (matches predict-rlm's published numbers) |

## Blocked by

- R-3 (inline-fn Fressian sanitization) — without this, all recursive runs trigger the 14-minute mystery silence and useful output is hit-or-miss
- R-4 (sub-model injection) — without this, the runs use the wrong model and the apples-to-apples claim doesn't hold

Optional: R-5 (history reuse hint) — would reduce token cost of multi-tree runs but isn't blocking.

## What to do AFTER this lands

If pass:
- Update `runner.clj` config default to `:recursive? true` (or expose a `:recursive-default` flag)
- Update the 5 task definitions if any need recursive-specific tuning
- Document the change in the bench README

If fail:
- File new R-* issues per the specific failure modes observed
- Re-prioritize fixes before retrying

## Cost / time estimate

- Total tokens budget: ~3-5x terminal-mode total (~3-15M tokens)
- Total wall-clock: ~15-30 minutes (vs ~10 min for terminal sweep)
- Total cost: ~$3-7 on OpenRouter pricing
