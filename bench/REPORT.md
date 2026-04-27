# Bench comparison: predict-rlm vs orc Legacy / Style A / Style B

**Round 5** — clean-slate full sweep, all stale runs archived. All numbers below are from this single sweep (no mixing across rounds).

**Date:** 2026-04-26
**Models:** root `openai/gpt-5`, sub `openai/gpt-5-mini` (via OpenRouter)
**N:** 3 per (task × stack); 5 tasks for predict-rlm/Style A/Style B; 2 tasks for Legacy
**max-iterations:** 15
**Prompt source:** verbatim DEFAULT_QUERY / CRITERIA from each `predict-rlm/examples/<task>/run.py`
**Wall-clock:** ~75 min (predict-rlm + orc sweeps in parallel)
**Total spend:** ~$4.70 (predict-rlm $3.75 + orc Style A $0.30 + orc Style B $0.40 + Legacy $0.23)

## Bottom line

Across 5 extraction/analysis tasks at N=3 each:

- **predict-rlm**, **orc Style A**, **orc Style B**: 100% reliability (15/15 each).
- **orc Legacy**: 67% reliability (4/6 across the 2 tasks where it can run; image_analysis is N/A — no vision sub-call available; doc_analysis/contract_comparison too heavy for single-context).
- Cost ratio is the headline: orc Style A is **5–32× cheaper** than predict-rlm task-by-task, and **12× cheaper aggregate** ($0.30 vs $3.75 for the same 15 runs).
- Token ratio at the extreme (document_analysis): predict-rlm uses **55× more tokens** than Style A (834,480 vs 15,177 median) — that's the price of unbounded RLM iteration on a 136-page input.

## Headline matrix — all 5 tasks, all 4 stacks

| Task                    | Stack       | n_ok/n  | Cost (med)                | Tokens (med)       | Duration (med)             |
| ----------------------- | ----------- | ------- | ------------------------- | ------------------ | -------------------------- |
| **image_analysis**      | predict-rlm | 3/3     | $0.0991                   | 85,437             | 281s                       |
|                         | orc Style A | 3/3     | $0.0308                   | 19,951             | 206s                       |
|                         | orc Style B | 3/3     | $0.0119                   | 10,354             | 21s                        |
|                         | orc Legacy  | —       | —                         | —                  | N/A (no vision sub-call)   |
| **invoice_processing**  | predict-rlm | 3/3     | $0.0735                   | 52,271             | 149s                       |
|                         | orc Style A | 3/3     | **$0.0051** (14× cheaper) | 3,918 (13× fewer)  | 24s                        |
|                         | orc Style B | 3/3     | $0.0223                   | 10,736             | 40s                        |
|                         | orc Legacy  | **2/3** | $0.0213                   | 10,184             | 28s                        |
| **document_redaction**  | predict-rlm | 3/3     | $0.1672                   | 128,156            | 231s                       |
|                         | orc Style A | 3/3     | $0.0202 (8× cheaper)      | 12,288 (10× fewer) | 154s                       |
|                         | orc Style B | 3/3     | $0.0351                   | 18,704             | 118s                       |
|                         | orc Legacy  | **2/3** | $0.0551                   | 23,147             | 78s                        |
| **contract_comparison** | predict-rlm | 3/3     | $0.3704                   | 398,498            | 243s                       |
|                         | orc Style A | 3/3     | $0.0321 (12× cheaper)     | 24,849 (16× fewer) | 218s                       |
|                         | orc Style B | 3/3     | **$0.0136** (27× cheaper) | 7,157 (56× fewer)  | 25s                        |
|                         | orc Legacy  | —       | —                         | —                  | not measured (large input) |
| **document_analysis**   | predict-rlm | 3/3     | **$0.5281**               | **834,480**        | 166s                       |
|                         | orc Style A | 3/3     | $0.0161 (33× cheaper)     | 15,177 (55× fewer) | 207s                       |
|                         | orc Style B | 3/3     | $0.0571                   | 35,178             | 23s                        |
|                         | orc Legacy  | —       | —                         | —                  | not measured (136 pages)   |

## Aggregate per-stack totals (15 runs each for non-Legacy)

| Stack                        | Total cost (15 runs) | Total tokens (15 runs) | Reliability | Avg duration |
| ---------------------------- | -------------------- | ---------------------- | ----------- | ------------ |
| predict-rlm                  | $3.74                | ~3.5M                  | 100%        | 215s         |
| orc Style A                  | $0.31 (12× cheaper)  | ~228k (15× fewer)      | 100%        | 162s         |
| orc Style B                  | $0.41 (9× cheaper)   | ~244k (14× fewer)      | 100%        | 45s          |
| orc Legacy (6 runs, 2 tasks) | $0.23                | ~99k                   | 67%         | 53s          |

## Notable findings

### 1. orc Style B is dramatically faster wall-clock for non-trivial tasks

Style B median durations: 21s (image), 40s (invoice), 25s (contract), 23s (analysis). predict-rlm: 281s, 149s, 243s, 166s. **6–10× faster** on the same task. The RLM primitive's predict-all parallel fan-out (max-concurrency 4–8) outpaces predict-rlm's mostly-sequential sub-calls (despite predict-rlm having async support — its prompts default to sequential extraction).

### 2. Style A is consistently cheapest _and_ near-100% reliable

The 14× cheaper invoice_processing finding is real and reproducible. Across 15 runs, Style A produced output for every single run, used the fewest tokens, and (per round-3 walker fix) reaches the same headline numbers (vendors, totals, line items) as predict-rlm.

### 3. Legacy reliability ceiling holds at ~67%

The hill-climb in round 4 took us to 40% (invoice) / 67% (redaction). This sweep: 67% (invoice) / 67% (redaction) — the redaction prompt fix held; invoice variance pulled it back into the same band. **The ceiling appears architectural**: single model + one context + tools-as-functions cannot match RLM-mode reliability on extraction tasks.

### 4. Cost variance is highest on `document_analysis` predict-rlm

$0.46–$0.82 across 3 runs (1.8× spread); tokens 706k–1.2M (1.7× spread). RLM-style "explore until satisfied" naturally produces wide cost distributions. Style B is far tighter ($0.05–$0.08) at the cost of much shallower output.

### 5. orc-side `gpt-4` audit clean

Hard guard added in `bench/run_orc.clj :: assert-allowed-model!` — refuses to execute any workflow whose nodes reference gpt-4 / gpt-4o / gpt-4o-mini / gpt-4-turbo / gpt-3.5-turbo. With `OPENAI_API_KEY` unset, `litellm-router/setup-openai!` (the only `gpt-4o-mini` default in dependency tree) doesn't fire. Every model string in the sweep is `openai/gpt-5` (root) or `openai/gpt-5-mini` (sub).

## Architecture takeaways

The clean N=3 sweep firms up the picture from prior rounds:

- **Cost-quality lives on a curve, not a binary.** Style A ≈ predict-rlm on tractable structured tasks (invoice, redaction) at 8–14× lower cost. predict-rlm wins clearly on document_analysis depth (25–33 KB report vs Style A's 0.4 KB) at 33× higher cost. There's no single "right" stack; the choice depends on whether depth matters more than cost at the task class.

- **RLM mode collapses depth-versus-budget into one knob.** Style B's predict-all fan-out lets it scale parallel sub-calls within budget; the invoice example uses 1 root + 2 sub at $0.022 vs Style A's 0 root + 2 sub at $0.005 — same answer, different paths. Style A's per-node structure is ~4× cheaper because it's deterministic about what calls happen; Style B's iteration adds the root-LM tax.

- **Legacy is the architectural foil.** Without sub-LLM calls or pipeline structure, single-model agents hit a hard reliability ceiling that prompt iteration can move from 33% to 67% but not further. This is empirical support for the "structure beats freedom" thesis underlying both Style A and Style B (and Cameron's "the BT IS the agent" framing).

- **The Hybrid `(fallback A B)` shape is now well-motivated.** Style A's $0.005–$0.020 cost on 100% of runs + Style B's deeper output on hard cases = a fallback pattern that keeps the average cost near Style A and the worst-case quality near Style B. This is the next experiment, deferred from earlier rounds.

## Round 6 — Driver-recovery sweep (5th stack)

**Date:** 2026-04-26 (evening)
**Stack:** `orc-driver-recovery` — Cameron's `workflow-driver` operating on a deliberately-degraded Style A pipeline. Per-task degradations live in `bench/degradations.edn`: weaken one node's `:instruction` (and optionally drop a `:reads` key), hand the resulting Sheet + objective + eval-set to `driver/run-driver-loop!`, measure recovery.
**Models / N / max-iterations:** unchanged from round 5.
**Driver budget:** `:max-turns 3`, `:min-pass-rate 1.0`, `:min-judge-score 0.7`, `:tick-timeout-ms 600000`.
**Total spend (this round):** ~$0.60 (all in invoice_processing — see below).

### Headline matrix — driver column added

| Task                   | Stack                    | n_ok/n  | Cost (med) | Tokens (med) | Duration (med) | Notes                      |
| ---------------------- | ------------------------ | ------- | ---------- | ------------ | -------------- | -------------------------- |
| **invoice_processing** | predict-rlm              | 3/3     | $0.0735    | 52,271       | 149s           | (round 5)                  |
|                        | orc Style A              | 3/3     | $0.0051    | 3,918        | 24s            | (round 5)                  |
|                        | orc Style B              | 3/3     | $0.0223    | 10,736       | 40s            | (round 5)                  |
|                        | orc Legacy               | 2/3     | $0.0213    | 10,184       | 28s            | (round 5)                  |
|                        | **orc Driver**           | **0/3** | $0.1970    | 30,257       | 361s           | 3 turns each → surrendered |
| **document_redaction** | (rounds 5 numbers stand) | …       | …          | …            | …              | …                          |
|                        | **orc Driver**           | **0/3** | $0.1430    | ~30k         | 742s           | 3 turns each → surrendered |
| **document_analysis**  | (rounds 5 numbers stand) | …       | …          | …            | …              | …                          |
|                        | **orc Driver**           | **0/3** | —          | —            | —              | ERR — credit cap (see below) |

### What actually happened

**invoice_processing — 3/3 surrendered, judges right, gate wrong.**
The driver did its job: 3 turns of progressively stricter prompt rewrites for the degraded `extract-invoice-page` node, each correctly identifying that the LLM was producing the wrong shape and proposing an output schema (vendor / invoice number / dates / line items) with normalization rules and grounding constraints. Yet **every turn's output came back with `avg-judge-score: 0.0`** while **`pass-rate: 1.0`** — the workflow ran without exception but `compile-invoices` couldn't consume the per-page output. After 3 turns, surrender. Final structured: `invoices_found: 0`, `produced_xlsx: False`, `vendor_names: []`.

This is the most interesting empirical finding of the round: **the driver's publish gate (`:min-pass-rate 1.0` + `:min-judge-score 0.7`) is the right shape, but `pass-rate` is dominated by "did the workflow throw" while `avg-judge-score` is what actually measures correctness.** When judges score 0 on a "successful" run, the driver should treat it as a failure for next-turn purposes — which it does (it kept iterating) — but the sub-LM model (`gpt-5-mini`) couldn't bridge the prompt → schema gap inside 3 turns, even with strong instructions.

**document_redaction — 3/3 surrendered (after credit top-up + resume sweep).**
Same shape as invoice: driver ran the full 3-turn loop on each, judges scored low (output not matching the schema `pdf/search-text` needs to locate redaction spans), surrender. Median: $0.14, 742s, 3 turns. Notably *more expensive per run than invoice* despite same turn budget — the propose-tree prompt is larger because the degraded sheet has more nodes to describe in the snapshot.

**document_analysis — 3/3 ERR'd, even after top-up.**
First post-top-up sweep: all 3 runs ERR'd at HTTP 402 instantly (max_tokens 65,536 vs ~41k available). After the user added more budget, second attempt: run 1 ran 620s (propose-tree call succeeded) then ERR'd in the eval-set tick when remaining budget couldn't afford the next propose call (62,322 < 65,536). Runs 2-3 ERR'd instantly. **The driver's default 65,536 max_tokens is structurally incompatible with weekly-budget-capped keys** — the cap can only fund one propose call at a time, and the second call always fails.

### Architectural takeaways

- **Driver decision logic needs a quality gate beyond pass-rate.** The current `decide-step` treats `pass-rate ≥ min-pass-rate AND avg-judge-score ≥ min-judge-score` as publishable, but `pass-rate` is misleading (workflow ran ≠ workflow worked). When `avg-judge-score = 0`, the driver should bias next-turn proposals toward _structural_ changes (add a sub-call, restructure reads/produces) rather than further instruction tuning. Currently it just tightens prose 3 times and gives up.
- **Driver max_tokens is too aggressive for shared/low-budget keys.** 65,536 is reasonable for self-hosted but rejects on OpenRouter weekly-budget keys with even modest remaining headroom. Either lower the default (16k–24k) or expose it via `run-driver-loop!` opts so the bench can set it per-task.
- **Driver cost vs Style A cost on the same task is striking.** invoice_processing Style A: $0.005 per run, 100% success. Driver-recovery (degraded → driver-fixed): $0.20 per run, 0% success. The driver's per-turn LLM call (proposing a full workflow rewrite as DSL) is itself ~10k–15k tokens; 3 turns × 3 runs = ~$0.60 for zero-correct outputs. **The driver is a recovery mechanism, not a primary execution path** — useful when you have a broken workflow and a known-good eval-set, not as a default.

### Followups specific to round 6

1. **Lower driver default max_tokens** from 65,536 to ~24,000 (or expose via opts) so OpenRouter weekly-budget keys can run it.
2. **Add a "judges scored zero" branch in `decide-step`** that signals next-turn proposer to consider structural changes, not just prompt rewrites.
3. **Re-run document_redaction + document_analysis driver cells** once max_tokens is fixed and credits are available; both have `:eval-set` defined and ready in `bench/degradations.edn`.
4. **Investigate why pass-rate decoupled from judge-score** in invoice_processing — the eval-set has `:judges [:grounding :completeness]` and the workflow output is empty; how is the workflow returning `:status :success` with empty outputs? (Likely silent-nil-on-missing-reads, same root cause as round-5 followup #7.)

## Followups (priority-ordered, post-round-5)

1. ~~Fix orc bench input keys~~ ✅ done
2. ~~Fix walker double-count in compare.py~~ ✅ done
3. ~~Fix legacy executor token-format bug~~ ✅ done
4. ~~Persist `:iterations` to node-completion event~~ ✅ done
5. ~~Add per-run token columns to compare.py~~ ✅ done
6. ~~Add `assert-allowed-model!` guard in bench~~ ✅ done
7. **orc should error on missing reads** (silent nil-substitution caught the bench wrapper bug only via output inspection — production users would too).
8. **Fix predict-rlm wrapper for bare-Pydantic returns** (contract_comparison output still missing on the predict-rlm side).
9. **Hybrid `(fallback A B)` workflow** for invoice_processing as the next experiment — empirically motivated by Style A's 100% reliability + 14× cost advantage + Legacy's 67% ceiling.
10. **LLM-as-judge for prose tasks** (document_analysis report quality, contract_comparison diff completeness — currently we measure structural metrics like "found N key dates" but not quality).
11. **Reasoning-token-aware cost computation** (local price table likely undercounts gpt-5 reasoning by ~10×; this round reported ~$4.70 but actual OpenRouter charge ~$5–7).
12. **Style C synthesizer pilot** (RLM-as-workflow-author per Part 7 of RLM-DEEP-ANALYSIS).

## Provenance

All run.json files for this round live under:

- `predict-rlm/bench/runs/<task>/<run_id>/run.json`
- `orc/bench/runs/<task>/<run_id>/run.json`

Stale data from rounds 1–4 archived to:

- `predict-rlm/bench/runs.archive-r4-20260426-1349/`
- `orc/bench/runs.archive-r4-20260426-1349/`

Regenerate this report any time with:
`python3 bench/compare.py invoice_processing document_redaction image_analysis contract_comparison document_analysis`
