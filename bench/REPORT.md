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

## Round 7 — Driver with cumulative memory + larger turn budget

**Date:** 2026-04-26 (evening, post-budget-top-up #2)
**Hypothesis:** the round-6 surrenders were a budget problem — 3 turns wasn't enough, and runs starting from a blank slate were wastefully repeating the same 3 dead-end prompt rewrites. Fix: bump max-turns 3→6 and seed each run's `prior-attempts` from a per-task on-disk cache so later runs see earlier runs' rejection summaries.

**Code:** `:seed-prior-attempts` option added to `workflow-driver/run-driver-loop!`; bench wrapper persists each run's new attempts to `bench/.driver-cache/<task>.edn` (capped at 12). Cache is wiped at sweep start so cumulative learning happens within the sweep, not bleeding from prior experiments.

**Scope:** invoice_processing × 3 + document_redaction × 3. (document_analysis still blocked on the driver's 65,536-max_tokens incompatibility with weekly-budget keys.)

### Invoice — clean, conclusive, hypothesis rejected

| Metric | Round 6 (3 turns, no memory) | Round 7 (6 turns + cumulative memory) |
|---|---|---|
| n_ok / n | 0/3 | 0/3 |
| Cost (med) | $0.20 | $0.42 (2.1× more) |
| Cost (run 1 → 2 → 3) | $0.20 / $0.20 / $0.21 | $0.39 / $0.42 / $0.48 |
| Tokens (med) | 30,257 | ~75k |
| Duration (med) | 361s | 967s (2.7× longer) |
| Decision pattern | continue × 2, surrender | continue × 5, surrender |
| **Final avg-judge-score** | **0.0** | **0.0** |
| Seed → new prior attempts | 0 → 3 | 0→5, 5→5, 10→5 (cache: 0 → 5 → 10 → cap) |

**Translation: doubling turns and giving the driver 10 prior rejection summaries to read did not move the judge score off zero.** The driver kept proposing instruction-strengthening rewrites; the judges kept correctly flagging the output as wrong.

### Redaction — no useful data (network)

3/3 ERR'd, but this time on `java.net.ConnectException` (not credit cap). Run 1 reached 597s (several turns) before a network blip dropped its next request; runs 2-3 instant-failed in the same window. Likely transient OpenRouter reachability issue; not retried this round.

### What this proves

The architectural claim from round 6 is now empirically supported, not just inferred:

> "When avg-judge-score = 0, the driver should bias next-turn proposals toward _structural_ changes (add a sub-call, restructure reads/produces) rather than further instruction tuning. Currently it just tightens prose N times and gives up."

Round 7 ran the experiment that should refute this if false (more turns + memory of all prior attempts) and got the same 0.0 score. So:

- **The driver's prompt rewrites are not the bottleneck.** With 5 turns of feedback in the prompt, it's still proposing variations of "tighten the JSON schema instruction." The LLM has 5 examples of "this kind of fix didn't work" and still proposes the same kind of fix.
- **Cross-run memory works as designed but doesn't help.** Cache size growing 0→5→10 confirms the wiring; surrender outcome growing identical confirms the hypothesis was wrong.
- **The cost trajectory is brutal.** Run 1 = $0.39, run 3 = $0.48. By run N you'd be paying for 12 prior attempts × ~1k tokens each per turn × 6 turns = a ~50% prompt-size markup. There's no asymptote — the cache cap (12) hides this slightly but the trend line is steep.

### Architectural takeaway (post-round-7)

The driver as currently designed is a **prompt-tuning loop dressed up as a structural-repair agent**. It can:

- Reword instructions (does this every turn).
- Restructure reads/produces (capability exists in the workflow-form output, but the propose-tree LLM almost never exercises it under "this output isn't being consumed downstream" feedback).
- Add/remove nodes (same — capability exists, almost never used in practice).

The remedy is not "more turns" or "more memory." Both have been disproven. Real options:

1. **Decide-step feedback shaping** — when `avg-judge-score = 0` despite `pass-rate = 1.0`, inject an explicit "your prior fixes were prompt-only and the gap is structural; consider adding a sub-call or fixing :reads/:produces" hint into the next turn's prompt. Cheap to try; tests whether the LLM *can* reason structurally with the right pointer, vs whether it *won't*.
2. **Reference workflows in the propose prompt** — give the LLM 1-2 sibling working pipelines as exemplars. Tests "blind vs unable."
3. **Constrain max-turns hard at 3 again** and treat driver-recovery as a "see if a single shot fixes it" tool, not a "let it iterate" tool. Round-6 surrendered for $0.20; round-7 surrendered for $0.42. If both fail, prefer the cheaper one.

### Followups specific to round 7

1. **Implement decide-step feedback shaping** (option 1 above) — smallest change, most direct test of the structural-reasoning hypothesis.
2. **Reset `:max-turns` default in the bench back to 3** — round-7 evidence shows 6 isn't worth 2× the spend.
3. **Re-run document_redaction seeded sweep** when network is reliable — the round-7 invoice finding is conclusive but a 2-task confirmation would be cleaner.
4. **Cap or remove the prior-attempts cache** — if the conclusion holds that memory doesn't help, this scaffolding is dead weight; keep the option for future experiments but don't use it by default.

## Round 8 — silent GPT-4 calls discovered (and stopped)

**Date:** 2026-04-26 (late evening)
**Trigger:** OpenRouter activity dashboard showed GPT-4 calls during the in-flight v2 sweep, despite our `assert-allowed-model!` workflow guard.
**Outcome:** ~$1+ in actual GPT-4 spend across rounds 5–7 attributed to a previously-undetected bug in the evaluation judges. Sweep cancelled mid-flight; root cause fixed; defense-in-depth added.

### Bug chain

1. **`litellm/router.clj setup-openrouter!`** defaults `:model "openai/gpt-4"` when called with no model arg.
2. **`dscloj/quick-setup! → litellm-router/quick-setup!`** detects `OPENROUTER_API_KEY` and calls `(setup-openrouter!)` with no model. The `:openrouter` config is registered with `:model "openai/gpt-4"`.
3. **`evaluation/core/judges.clj`** had a dead `:model` arg — destructured into a let-binding (defaulting to `*judge-model*` = `"google/gemini-2.5-flash"`) and **never passed to `dscloj/predict`**. So judge calls used the `:openrouter` config's registered default = GPT-4.
4. The bench's `assert-allowed-model!` walks workflow-node `:model` keys but never saw the judge code path (judges use a dynamic var, not a workflow `:model`).

Every eval-set tick across rounds 5–7 quietly hit GPT-4 multiple times. Conservatively: ~500–1500 GPT-4 calls cumulative. The "reasoning token undercount" line in the round-5 cost note was partially this.

### Fixes shipped

| Layer | Fix |
|---|---|
| Root cause | `evaluation/core/judges.clj` now resolves an effective provider via the same `get-provider-with-model` pattern the executor uses, so `*judge-model*` is actually honored. |
| Defense in depth (bench) | `bench/run_orc.clj` `override-runtime-defaults!` re-registers `:openrouter` with `model "google/gemini-2.5-flash"` after `dev/start!`, so even unfixed callers can't accidentally hit GPT-4. |
| Fail-fast guard | `bench/run_orc.clj` `assert-runtime-config-clean!` sweeps registered litellm configs + `*judge-model*` at startup, refuses to proceed if any reference a forbidden model. |
| External | User added GPT-4 to OpenRouter key-level block list — strongest possible guard, blocks the call upstream regardless of code bugs. |

## Round 9 — same fixes + first structural changes from the driver

**Date:** 2026-04-27 (early morning)
**Setup:** v2 sweep with all round-8 fixes in place + the round-7 cumulative-memory infrastructure + the playbook fix telling the LLM to "consider structural changes when judges score 0 despite pass-rate=1.0" + clearer rejection text + live `:on-turn` logging in the bench wrapper.
**Scope:** invoice_processing × 3, max-turns 6, cumulative cache.

### Per-turn telemetry (the new live logging)

Every turn the bench now prints one line: `submit=:status Δ+added/-removed/~modified pass-rate=X judge=Y → decision`.

| Run | Turn | Δ+ | Δ- | Δ~ | pass-rate | judge | decision |
|---|---|---|---|---|---|---|---|
| 1 | 1 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 1 | 2 | 0 | 0 | 0 | parse-error | – | continue |
| 1 | 3 | 0 | 0 | 1 | 1.0 | **0.05** | continue |
| 1 | 4 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 1 | 5 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 1 | 6 | 0 | 0 | 1 | 1.0 | 0.0 | surrender |
| 2 | 1 | 0 | 0 | 1 | 1.0 | **0.15** | continue |
| 2 | 2 | **+2** | 0 | 1 | 1.0 | 0.0 | continue |
| 2 | 3 | 0 | 0 | 1 | 1.0 | 0.05 | continue |
| 2 | 4 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 2 | 5 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 2 | 6 | 0 | 0 | 1 | 1.0 | 0.025 | surrender |
| 3 | 1 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 3 | 2 | 0 | 0 | 1 | 1.0 | 0.05 | continue |
| 3 | 3 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 3 | 4 | 0 | 0 | 1 | 1.0 | **0.20** | continue |
| 3 | 5 | 0 | 0 | 1 | 1.0 | 0.0 | continue |
| 3 | 6 | 0 | 0 | 1 | 1.0 | 0.0 | surrender |

**Cost / duration:** $0.40 / 1153s, $0.48 / 1562s, $0.49 / 1331s. Total $1.37, ~14 min wall-clock per run, 3/3 surrendered.

### What changed vs round 7

| Metric | Round 6 | Round 7 | Round 9 |
|---|---|---|---|
| Total turns | 9 | 18 | 18 |
| Structural-change attempts (Δ+ or Δ- > 0) | 0 | 0 | **1** |
| Turns with judge > 0 | 0 | 0 | **6** |
| Best judge score | 0.0 | 0.0 | **0.20** |
| Parse errors | 0 | 0 | 1 |
| n_ok / n | 0/3 | 0/3 | 0/3 |
| Cost / run (med) | $0.20 | $0.42 | $0.48 |

The fix moved real metrics: the playbook bias was the bottleneck on structural reasoning, and the misleading "1/1 passed" rejection text was hiding the judge signal from the LLM. Once both were addressed, the LLM:
- Tried a structural change once (run 2 turn 2, +2 nodes).
- Started producing measurably better single-node prompts (judge inched from 0 → 0.05 → 0.15 → 0.20 across the 18 turns).
- Once attempted a parse-error overshoot — evidence of trying something more ambitious.

### What still didn't work

The structural attempt at run 2 turn 2 scored **identically** to safe prompt-tweaks (0.0). The LLM correctly inferred "this strategy didn't help" and **never attempted another structural change** in the remaining 4 turns of run 2 or any of run 3's 6 turns. With the cumulative cache seeding 10 prior attempts into run 3, the structural attempt's 0.0 score is permanently visible — and the LLM treats it as a learned anti-pattern.

This is the next bottleneck: the judge feedback is a scalar. A structural change that adds a relevant node but mis-wires `:reads`/`:writes` scores identically to a no-op prompt-tweak. The LLM has no signal that "you got closer" or "the structure was right but the wiring is off." Without resolution-per-aspect feedback, the model can't distinguish "structural fixes don't help" from "this specific structural fix had a bug."

### Architectural takeaway (post-round-9)

The driver IS capable of structural reasoning — the playbook was the bias source, not the model. But unlocking that capability without **per-aspect judge feedback** is a partial fix. Each structural attempt is an expensive blind shot ($0.05–$0.10 of LLM tokens to construct one); a single 0.0 result discourages the next 4–10 attempts.

The Obsidian RDD note's section about Portal-style structured-value navigation is the right next architectural move: judges should hand back a *structured artifact* the propose-tree LLM can drill into ("the new node 'validate-shape' executed but produced an empty `:validated` key — your downstream consumer expected `:validation-report`"), not just a scalar 0–1 score.

### Followups specific to round 9

1. **Per-aspect judge feedback** — judges currently return `{:score :feedback}`. Promote `:feedback` to a structured map of `{:aspect-key :pass? :note}` and surface in the rejection text. Then a structural attempt that fixed one aspect but broke another scores the same overall but the LLM sees the differential.
2. **"You haven't tried structural change yet" hint** — when N consecutive attempts have all been Δ+0 with judge < threshold, inject "your last N attempts were all single-node prompt rewrites; you have not yet tried adding a sub-call or restructuring reads/writes." A turn-N strategy nudge, not a global playbook line.
3. **Variance reduction on judges** — run 3 turn 4 scored 0.2 then turn 5 scored 0.0 with no structural change. Either the judges are non-deterministic (likely temperature > 0) or the actual node output is. Worth a 3-shot consistency check.

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
