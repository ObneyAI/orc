# R-Inject benchmark suite — what changes when the model sees the corpus

ORC's recursive-RLM researcher (`:repl-researcher`) designs behavior trees that solve the task it's given. In its baseline form, the researcher sees only the task instruction + a primer on the available tree-DSL primitives. **R-Inject** is the wedge that — when `:auto-classify? true` is set on the researcher node — runs the structural + behavioral classifiers against the corpus of seeded tree-fingerprint descriptions, surfaces the top-confidence matches (with their full bodies — capabilities, strengths-with-DSL-snippets, weaknesses-with-fixes, representative-uses), and prepends that block to the researcher's Phase 1 prompt.

This benchmark suite re-runs the same 5 tasks the original ORC generalization suite (`bench/tasks/`) used, with R-Inject turned on. The question we wanted to answer: **does the model use the corpus, and what does the resulting tree, runtime cost, and output quality look like compared to the un-prepended baseline?**

## Headline result: R-Inject across the 5 tasks

The runs in this report are from **`2026-06-02`**, with `google/gemini-3-flash-preview` as both the main and sub model. All 5 tasks succeeded. The classifier matched every task at confidence 1.00 on its top structural candidate. Per-iteration reasoning explicitly named which corpus seeds the model adopted on every run.

| # | Task | Status | Phase 1 + Phase 2 tokens | Wall clock | Structural top match | Behavioral candidates surfaced |
|---|---|---|---:|---:|---|---:|
| 01 | Legal Issue Detection | ✓ | 43,198 | 58.4 s | `Legal-issue-detection` (1.00) | 3 |
| 02 | Contract Comparison | ✓ | 109,693 | 152.5 s | `Contract-comparison` (1.00) | 4 |
| 03 | Contract Comparison (validated) | ✓ | 129,582 | 69.7 s | `Contract-comparison` (1.00) | 5 |
| 04 | Risk & Obligation Analysis | ✓ | 244,018 | 228.3 s | `Risk-analysis` (1.00) | 5 |
| 05 | Document Analysis | ✓ | 254,627 | 361.7 s | `Document-analysis` (1.00) | 3 |

See [`01_legal-issue-detection.md`](01_legal-issue-detection.md) through [`05_document-analysis.md`](05_document-analysis.md) for per-task narrative, verbatim prepends, full classifier candidates, iteration reasoning, generated trees, and output samples.

## Comparison: R-Inject vs the baseline (no prepend, no classifier)

The original `bench/tasks/` reports captured the same 5 tasks running without R-Inject — same models, same documents, same instructions, but no corpus prepend and no classifier call. Putting them side by side:

| Task | Baseline tokens | R-Inject tokens | Δ tokens | Baseline wall | R-Inject wall | Δ wall |
|---|---:|---:|---:|---:|---:|---:|
| Legal Issue Detection | 5,706 | 43,198 | **+657%** | 8.8 s | 58.4 s | **+563%** |
| Contract Comparison | 64,336 | 109,693 | +71% | 35.6 s | 152.5 s | +329% |
| Contract Comparison (validated) | 84,337 | 129,582 | +54% | 26.7 s | 69.7 s | +161% |
| Risk & Obligation Analysis | 170,336 | 244,018 | +43% | 64.8 s | 228.3 s | +252% |
| Document Analysis | 212,066 | 254,627 | **+20%** | 126.3 s | 361.7 s | +186% |

**Two patterns to read out of this table:**

1. **The relative token overhead of R-Inject scales DOWN as document size grows.** The 7K-character employment agreement (legal-issue-detection) sees +657% tokens — most of the run's cost IS the prepend + classifier. The 280K-character RFP (document-analysis) sees only +20% — the prepend cost is small relative to the Phase 2 chunked extraction over 28 chunks. R-Inject costs more in absolute terms on every task, but the *proportional* burden is bounded by task size.

2. **Wall-clock overhead is larger than token overhead.** Adding R-Inject roughly doubles or triples task duration on every task. The reason: the trees R-Inject's prepend nudges the model toward are more *sophisticated* — more stages, more parallel sub-LLM calls, more typed transformations. The prepend doesn't just add tokens; it changes the *shape* of the work.

The classifier reranker calls themselves contribute roughly **15K-20K additional tokens** that don't appear in the `:usage` field but DO appear when summing the per-leaf-event `:node-trace`. For the legal task on a single-task confirmation run with the new `:node-trace` capture, the *true* total was ~53K tokens (`:node-trace-usage-total`), not the 35K shown in `:usage`. The classifier overhead is real and previously invisible.

## What the additional cost buys

Across all 5 tasks, the trees R-Inject produces share recognizable structural improvements over what the baseline researcher designed:

- **Typed `:output-schemas` on every extraction `:llm`.** The prepend's Extraction seed prescribes "NEVER ship extraction without a typed schema — downstream stages bind on field names and silent shape drift produces vacuous output." Every R-Inject tree declares typed maps with explicit field shapes. The legal task: per-finding `[:map [:category [:enum "issue" "ambiguity" "missing"]] [:description :string] [:citation :string] [:potential_impact :string]]`. The risk task: `[:map [:type [:enum "Financial" "Operational" "Compliance" "Reporting" "Timeline"]] [:text :string] [:citation :string]]`. Baselines tend to use `:string` or `[:vector :string]`.

- **Adversarial validation as a structural stage when the task calls for it.** Task 03 (validated comparison) added a dedicated `:llm` node with a distinct "antagonistic reviewer" persona that reads BOTH the draft analysis AND the source contracts to verify each claim. The Critique seed's `:strengths` body says "Use adversarial framing (skeptical reviewer / safety auditor) — never the same prompt persona as the producer." The model implemented that as a separate node with separate `:reads`.

- **Multi-output `:parallel` synthesis.** Several tasks split synthesis across multiple `:llm` calls when the task has independent output dimensions (executive-summary vs section-diffs vs impact-analysis). The Synthesis seed's "NEVER synthesize >5 substantive inputs in one call — do hierarchical clustering instead" principle visible in tree topology.

- **Per-iteration reasoning that NAMES the corpus pieces.** Every run's iteration-1 reasoning includes phrases like *"I am adopting the 'Legal-issue-detection' pattern from the corpus"* or *"This pattern mimics the 'Contract-comparison' and 'Critique' behavioral templates."* This is not retrospective labeling; the reasoning is produced *before* the code in the model's response (using the executor's new `:reasoning` output field), so the corpus reference is causally upstream of the design choice.

## What this run also surfaced

One run produced a more honest failure mode than R-Inject was designed to address: **task 04 (risk-analysis) returned `:status :success` but with two of its four declared outputs as `nil`** in an earlier run. The model's tree had two `:code` nodes with inline aggregation fns; one of them misunderstood the `:map-each` output shape (treating per-chunk `:extracted_items` as if it had `:obligations`/`:penalties` at the top level instead of nested). The downstream synthesis stages then *honestly* reported "there are no obligations or penalties identified" — which they were — but the resulting risk-matrix and executive-summary describe an empty input, not the actual RFP.

The prepend's discipline was visible in the *shape* the model chose (chunked extraction with typed schemas, deterministic aggregation, structured synthesis); the failure was in the *data-flow plumbing* of the inline `:code` fns. R-Inject can shape what the model designs; it can't catch every fn-body bug downstream. The full inline fn source is preserved in `:iterations[].code` on the saved EDN so post-hoc debugging is possible without re-running.

See [`04_risk-analysis.md`](04_risk-analysis.md) for the full diagnostic.

## What changed in ORC infrastructure to capture all this

Several pieces of telemetry are now first-class on every recursive-RLM tick completion, not bespoke to this bench:

- **`:node-trace`** — every leaf-node completion event (parent sheet + Phase 2 child sheets) with its own `:usage`, `:inputs`, `:writes`, `:duration-ms`. Built by `build-node-trace` in `components/orc-service/src/.../core/todo_processors.clj` and attached at `deliver-completion!`. Consumers (this bench, predict-rlm comparison, future eval harnesses) just propagate `(:node-trace result)` without composing their own event-store queries.

- **`:usage` now aggregates Phase 1 + Phase 2 in recursive mode.** Earlier, recursive-mode runs reported Phase 1 only because the Phase 2 sub-LLM tokens weren't summed into the per-iteration accumulator. The executor's recursive merge path (`executor.clj`) now adds `(:usage phase2-result)` to `@total-usage` at every Phase 2 completion. The discrepancy between `:usage` and `:node-trace-usage-total` is now bounded to classifier reranker overhead (the calls that fire before the researcher node starts).

- **`:r-inject-trace`** — verbatim prepend (with seed names in headers — `#### Top match — Legal-issue-detection (confidence: 1.00)`) + full classifier payload (all top-N candidates with their fitness scores + reasoning). Sidecar-written by `apply-r05-classifier-context` and read back into the saved EDN. Every report in this suite quotes its verbatim prepend.

- **`:iterations`** — per-Phase-1 iteration's full code (the original `(emit-tree! ...)` text the model emitted, **including the inline `:code` fn source** — no `<inline-fn>` sanitization at this level), plus result, stdout, error, vars-created. Lets reports show the actual Clojure body of every `:code` aggregation node — critical for debugging cases like risk-analysis where the bug was inside an inline fn.

- **`:iteration-reasonings`** — per-iteration reasoning text the model produced via the executor's new `:reasoning` output field. Lets reports quote the model's own causal narrative about which corpus pieces it adopted, which alternatives it rejected, and why.

## Reading order for the per-task reports

The reports are written to be read linearly through the suite OR jumped-to by task. Each report covers:

1. **Run summary** — status, wall clock, total tokens, iterations, source documents
2. **What the classifier retrieved** — all top-N candidates (above AND below the 0.6 display floor) with their fitness scores and reasoning
3. **The verbatim prepend** — full text of what the model received, with seed names in headers
4. **What the model designed** — the emitted tree
5. **What the model reasoned about its choices** — verbatim iteration reasoning
6. **Output quality** — verbatim samples with source verification
7. **What this run tells us** — task-specific synthesis of what R-Inject did vs didn't do
8. **Reproducing this report's data** — exact EDN paths for every claim

## Caveats

- The `bench/tasks/` baseline numbers cited above are from `2026-05-19` runs on an earlier ORC codebase state. Several intervening changes (R-Default recursive mode, the Phase 2 usage aggregation fix, parse-error diagnostic hints, the failure-recovery prompt nudge) would affect re-run results. The comparison frames R-Inject's overhead against the *originally-published* baseline numbers; a clean A/B with R-Inject toggled on and off on the current codebase would isolate the prepend's effect more precisely.

- All `:usage` numbers in this report's headline table are the executor's per-tick aggregation (Phase 1 + Phase 2 sub-LLM calls). The classifier reranker calls (~15-20K tokens) appear in `:node-trace-usage-total` but not `:usage`. When `:node-trace` is present on the saved EDN, the per-task reports use it for the headline number; when only `:usage` is present, the report cites that and notes the gap.

- Outputs were spot-checked against source documents; no hallucinations were surfaced in any of the spot-checks. This is a coarse quality signal, not an evaluation. A judge-based evaluation (cross-comparing R-Inject outputs against baseline outputs on the same documents) is the natural next step.
