# Bench comparison: predict-rlm vs orc Legacy / Style A / Style B

**Date:** 2026-04-26 (round 3 — adds Legacy stack + walker bug fix)
**Models:** root `openai/gpt-5`, sub `openai/gpt-5-mini` (via OpenRouter)
**N:** 3 per (task × stack), with extras for legacy reliability sampling
**max-iterations:** 15
**Prompt source:** verbatim DEFAULT_QUERY / CRITERIA from each `predict-rlm/examples/<task>/run.py` (so all stacks face the same question)

## Bottom line

Two earlier-round findings were artifacts of a bench bug — fixed in this round:

1. **Walker double-counted** (1st report): `_walk_for_invoices` and friends recursed into the predict-rlm `trace` field, counting each invoice/redaction once in `result.invoices` and again in `trace.steps[].predict_calls[].calls[].output`. After scoping to `result` only (`compare.py :: _result_scope`), all stacks reconcile to the same shape.
2. **Wrong input keys** (2nd report): the bench was sending `:document-paths` / `:invoice-paths` / `:contract-paths` but pipelines read `:documents` / `:invoices` / `:contracts`. orc treated missing keys as nil and the LLM dutifully reported "no documents provided" while orc still emitted `:status :success`. (Filed as orc gap: silent nil-substitution on missing reads.)

After both fixes, **the three RLM-mode stacks (predict-rlm, Style A, Style B) reach parity on the canonical extraction tasks**. The newly-added **Legacy stack** is the architectural baseline: it shows what one model + one context + tools-as-functions + text-pattern submit can do without the iteration discipline that RLM-mode (or pipeline structure) provides.

## Headline matrix — focus tasks

The two tasks where we have full 4-stack data with high-confidence baselines:

### invoice_processing — 2 PDFs, 5 + 6 line items per invoice, $34,804.30 total

| Stack | n_ok / n | Median cost | Output (when ok) | Verdict |
|---|---|---|---|---|
| predict-rlm | 3 / 3 | $0.075 | 2 inv, [5,6] LI, $34,804 — exact | reference |
| orc Style A | 3 / 3 | $0.006 (12.5× cheaper) | 2 inv, [5,6 or 5,7] LI, $34,804 — pipeline occasionally splits the discount line | parity, with one-off line-item variance |
| orc Style B | 3 / 3 | $0.022 (3.4× cheaper) | 2 inv, [5,6] LI, $34,804 — identical to predict | **parity, full reliability** |
| **orc Legacy** | **3 / 7** (43%) | $0.000 (executor token-format bug) | When ok: 2 inv, [5,6] LI, $34,804 — identical to predict. When not: ERR or placeholder strings | parity *when it works*, but reliability collapses |

**Reading**: invoice_processing is structurally tractable for all four architectures. Cost-quality ranks Legacy < Style A < Style B < predict-rlm (left = cheaper). Reliability ranks Legacy ≪ everyone else (43% vs 100%).

### document_redaction — 1 PDF, 6 pages, 9-category PII

| Stack | n_ok / n | Median cost | Median redactions | Coverage | Verdict |
|---|---|---|---|---|---|
| predict-rlm | 3 / 3 | $0.114 | 83 (range 69–97) | 9 categories, all 6 pages | reference |
| orc Style A | 3 / 3 | $0.019 (6× cheaper) | 69 (range 65–75) | 6–9 categories | parity on count, mild category drift (Title Case) |
| orc Style B | 5 / 6 (83%) | $0.033 | 68 / 73 / 0 | 9–10 categories when ok; 1/6 still shortcuts to 0 | mostly parity, occasional shortcut |
| **orc Legacy** | **2 / 6** (33%) | $0.000 | 23, 26 (3× LESS than predict) | covers only 4 of 6 pages | partial coverage when ok; high failure rate |

**Reading**: legacy mode finds *real* PII when it works, but its single model can't span the depth of a multi-page document. Coverage drops to ⅓ vs the RLM-mode stacks; reliability drops to ⅓.

## What Legacy mode showed us

The Legacy stack is the empirical foil that makes the RLM-mode results meaningful. Three architectural costs of "one model + one context + tools" come out clearly:

1. **Reliability collapses on extraction tasks.** 33–43% of legacy runs ERR or produce placeholder garbage; the other RLM-mode stacks are 83–100% reliable on the same prompts. Failure modes:
   - `(let [paths :invoices …)` — model treats the substituted variable name as a keyword
   - `(let [parser-fn …) … invoices  ;; ← YOUR EXTRACTION HERE …]` — model copy-pastes skeleton placeholders into a binding form, gets "let requires even number of forms"
   - `(ns sandbox (:require [clojure.string :as str]))` — SCI rejects `require`
   - `Double/parseDouble`, `System/getProperty`, `(.method obj …)` — Java interop blocks
2. **Depth ceiling on multi-page work.** Document_redaction legacy covered 4 of 6 pages, finding 23–26 PII items vs predict-rlm's 69–97 across 6 pages. The single-model context can only attend to so much before it commits an answer.
3. **Cost meter is broken in legacy mode** (orc bug, separate followup). `executor.clj :: execute-legacy-repl-researcher` reads `(:prompt_tokens usage 0)` (snake_case) from the dscloj/predict response, but modern dscloj normalizes to kebab-case; the snake-case lookup hits the default `0`, so `total-usage` accumulates to 0 and our bench cost computes to $0.00. Tokens were spent — they just don't surface in the trace. The unit test (`repl_researcher_test.clj :: usage-tracking-test`) passes because it mocks dscloj/predict to return snake-case explicitly.

## What this means for the architecture conversation

Cameron's frame ("the behavior tree IS the agent") gets stronger after this round. Legacy ≈ "RLM agent without the BT or the RLM primitives." Each architectural addition we measure gives a real lift:

| Architecture | What it adds | Effect (vs Legacy) |
|---|---|---|
| Legacy | iterative coding agent, mcp-tools, text-pattern submit | baseline |
| Style B (RLM-mode repl-researcher) | metadata-only context + predict / predict-all / final! primitives | reliability ↑↑ (33% → 83%), depth ↑ (3× redactions on same task) |
| Style A (explicit pipeline) | structural decomposition into nodes, per-node judges, deterministic orchestration | reliability ↑↑↑ (33% → 100%), cost ↓↓ (6–12× cheaper than predict-rlm) |
| predict-rlm | the full RLM primitive (Python-shaped) — reference for "bitter-lesson-proof" agent | reliability ↑↑↑ (100%), highest cost, deepest output |

The Hybrid path (Style A pipeline with an RLM `(fallback …)` branch on quality failure) is now empirically motivated:
- Cheap path = Style A (real work, ~$0.006–$0.020)
- Fallback path = Style B or even predict-rlm (deeper work, $0.022–$0.114)
- Expected outcome on mixed inputs: Style A cost on the easy ones + RLM cost only when needed

## Other tasks (status from round 2 — not re-run for legacy)

| Task | predict-rlm | orc Style A | orc Style B | Legacy status |
|---|---|---|---|---|
| image_analysis | 2/3 produce full A–Z counts (T:155, total 1343) — $0.05–$0.25 | 2/3 counts but header text only (T:11) — $0.013 | 0/3 (rigid signature blocks rigorous query) — $0.011 | **N/A** — needs vision sub-call; no legacy-compatible image tool exists |
| contract_comparison | 49–135 sub-calls; output unrecoverable (predict-rlm wrapper bug) | 4–15 detailed diffs with significance reasoning — $0.013 | 0–17 diffs, varying — $0.041 | not measured this round |
| document_analysis | 137–215 dates, 108–556 entities, 25–33 KB report — $0.402 | 6–8 dates, 12–15 entities, 0.4 KB report — $0.024 (60× shallower) | 3/3 ERR (credit exhaustion in earlier round) | not measured; legacy single-context vs 136-page input would be even worse |

## Cost roll-up (this session, all rounds)

| Stack | Spend | Notes |
|---|---|---|
| predict-rlm | $3.57 | 15 runs, all real work, all completed |
| orc Style A | $0.26 | 15 runs (post-fix) — real work on 4 of 5 tasks |
| orc Style B | $0.40 + ~$0.20 (round-3 retest) | mixed reliability on document_redaction; otherwise parity |
| orc Legacy | $0.00 (orc executor token-format bug) — actual usage estimated < $0.20 | 13 runs across the 2 focus tasks |
| **Reported total** | ~$4.43 | |
| **Actual OpenRouter total** | $61.44 (round 1+2 combined; round 3 < $1) | LiteLLM undercounts gpt-5 reasoning tokens by ~10× — separate followup |

## Followups (priority-ordered)

1. ~~Fix orc bench input keys~~ ✅ done
2. ~~Fix walker double-count in compare.py~~ ✅ done (`_result_scope`)
3. **Fix legacy executor token-format bug** (`executor.clj :: execute-legacy-repl-researcher` reading `:prompt_tokens` instead of `:prompt-tokens`) — silent cost-undercount; trivial fix.
4. **orc should error on missing reads** (silent nil-substitution caught the bench wrapper bug only via output inspection — production users would too).
5. ~~Persist `:iterations` to node-completion event~~ ✅ done
6. **Fix predict-rlm wrapper for bare-Pydantic returns** (contract_comparison output still missing).
7. **Hybrid `(fallback A B)` workflow** for invoice_processing as the next experiment — now empirically motivated by Legacy data.
8. **LLM-as-judge for prose tasks** (document_analysis report quality, contract_comparison diff completeness).
9. **Reasoning-token-aware cost computation** (10× cost undercount makes budget tracking unsafe).
10. **Style A + RLM-fallback hybrid on a "weird vendor" input** (deliberate Style-A breakage to demonstrate the fallback firing).
11. **Style C synthesizer pilot** (RLM-as-workflow-author, per Part 7 + Cameron's framing).

## What this proved about the comparison framework

- **Cost-only metrics lie when one stack might shortcut.** Walker double-count + wrong input keys both produced misleading reports until per-task structural extractors caught them.
- **Process metrics, structural outputs, and quality judgment are three separate layers.** This bench did 1 + 2 well; layer 3 (LLM-as-judge for prose) still pending.
- **Re-runnable matters.** Every fix in this session prompted a re-run. With `run-parallel` + `compare.py --since` filter, regenerating analysis from disk is a one-liner.
- **Schema parity is load-bearing.** Both stacks emit the same `run.json` shape; `compare.py` is stack-agnostic. That single decision made the bench tractable.
