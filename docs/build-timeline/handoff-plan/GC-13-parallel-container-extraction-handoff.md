# Handoff — GC-13: parallelize the serial per-container extraction (the measured bottleneck)

**Issue:** [`../issues/multi-container-graph-correctness/GC-13-comprehensive-build-scale.md`](../issues/multi-container-graph-correctness/GC-13-comprehensive-build-scale.md)
**Type:** AFK (perf) · **Blocked by:** — · **Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` is a shell env var only, never committed/printed.

## What's already established (so this is implement-the-proven-fix, not discover-it)

A per-phase profile of a completing build MEASURED the bottleneck (the prior "ColBERT is the lever" hypothesis was FALSE — ColBERT index = 4 ms / 0.0%):
- **model-extract = 55.6%** of wall-clock; its cost is `orchestrate-extract-containers` running the per-container SAMPLE→AUTHOR→APPLY unit **SERIALLY** (one `dsl/execute` child tick per container in a `mapv`), each AUTHOR a ~10 s `:llm` call → serial time grows linearly with container count → the 6/5 build times out.
- **PROTOTYPE PROOF:** swapping the serial `mapv` for a parallel map produced an **IDENTICAL 447 concepts, 0 dangling** (no scramble/loss) while model-extract dropped ~38 s→~21 s/call (~44%), total 341 s→212 s — at 2 containers/source. The win scales with container count.

So the fix is known and de-risked. Your job: implement it as a BOUNDED-concurrency, honest-failure-isolating, TDD'd change — not the unbounded `pmap` the prototype used.

## The exact change

In `components/ontology/src/ai/obney/orc/ontology/core/extract_subbehavior.clj`, `orchestrate-extract-containers` (~line 1413): the `results (mapv (fn [container] … (dsl/execute child-ctx sub-sheet-id …) …) containers)` loop currently runs containers serially. Make it run with **bounded concurrency** (a `max-extract-concurrency` knob, default a small N like 4–6; thread it from `inputs` like `max-containers`/`max-windows` so the caller can tune it, with a sane default when unset).

Each child tick is ALREADY isolated (own `child-tick-id`, own blackboard, own resilience gate + #13 reasoning); accumulation is the existing post-hoc `mapcat`/`canonicalize`/MC-6 logic — leave ALL of that untouched. Concurrency is SAFE: executor runs each tick in a `(future …)` on the unbounded cached `soloExecutor`; the `:parallel` node already drives concurrent child executions against the shared store. This is NOT a `:map-each` leaf, so the Discipline-12 race does not apply — but you must KEEP the honest per-container accounting.

**Non-negotiables:**
1. **Bounded**, not unbounded — reuse an existing bounded-concurrency primitive if one exists (the `:map-each` `:max-concurrency` machinery / any repo util) rather than hand-rolling; re-orchestrate not rewrite (#8).
2. **Order-preserving + honest failure isolation** — results stay attributed to their `:container`; a child tick THROWING becomes that container's HONEST `:extraction-report` failure entry (status/diagnosis), never crashes the batch, never silently drops a container (#4/#5). The serial version's per-container report semantics must be preserved byte-for-byte in shape.
3. Per-container `:timeout-ms 280000` stays per child tick. A single-container source (N=1) behaves exactly as before.

## /prototype (already done — reproduce if you want confidence)

The orchestrator-level prototype is proven (above). You MAY re-run a small parallel build to re-confirm before formalizing, but the real deliverable is the bounded + TDD'd version.

## TDD cycle list (red → green → refactor, one at a time, behavior through the public seam)

1. **Bounded-parallel helper — order preservation:** N thunks returning their index → results come back in input order. RED first (no helper), GREEN with it.
2. **Bounded-parallel helper — concurrency BOUND:** with bound = K and thunks that record concurrent-entry count (a shared counter + small sleep), assert peak concurrency ≤ K. Reverting the bound (running unbounded) turns this RED.
3. **Bounded-parallel helper — failure isolation:** one thunk throws → its slot is an honest failure marker, the OTHER thunks still complete and stay correctly ordered/attributed (batch does not abort).
4. **Orchestrator integration (deterministic, stubbed child-tick seam):** with the per-container execution stubbed (no real LLM), `orchestrate-extract-containers` over N containers returns the SAME accumulated drafts + the SAME `:extraction-report` shape as the serial path, and a stubbed-failing container is surfaced (not dropped). This guards attribution/accumulation without an LLM.

(The LIVE same-drafts-as-serial + speedup proof is the orchestrator's `/inspect-orc` live-QA, below — it cannot be a deterministic unit test.)

## Do NOT touch

- The per-container unit (`extract-per-container-def`), canonicalization (GC-1), MC-6 cross-container relating, hierarchy (GC-10), the `:extraction-report` shape — only the container-driving loop changes.
- The CQ-gate, embed/index, survey, reconcile, axiom. The eb12 driver. Anything outside `orchestrate-extract-containers` + the new helper + its tests.
- No ColBERT "incremental index" work — it was measured irrelevant (4 ms).

## Live-QA the orchestrator (me) will run after you return (`/inspect-orc`)

Re-run your unit suite myself; revert the bound to confirm the boundedness test goes RED; then a REAL multi-container build (in-memory, real Grain + OpenRouter + sources) and compare drafts to the SERIAL baseline — same concept set within LLM variance, **0 dangling, every container present in `:extraction-report`, NO scrambled/misattributed drafts**, model-extract per call materially faster; induce a per-container failure and confirm it's surfaced honestly (not dropped/misattributed); then the **6/5 build COMPLETES in a bounded run** with the CQ-gate answering. Ontology brick gate green; 0 orphan this-repo JVMs.

## Dependency rule

This slice is self-contained (one seam + a helper). Nothing downstream depends on a new API. If you find the bounded-concurrency primitive must be a shared util used elsewhere, STOP and flag it — do not refactor other call sites in this slice.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (0 concepts / dangling / scrambled drafts / a dropped container = FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces, not implementation details.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the existing child-tick mechanism + any existing bounded-concurrency primitive; do NOT fork a parallel pipeline.
9. Adversarial qualitative verdict — hunt for where the output is WRONG (draft scramble/loss under concurrency); surface honest negatives, not masking.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the contracts AND the LLM-reasoning quality.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Parallel `:map-each` leaf must be a PRIMITIVE (ORC-PRINCIPLES §14) — this change is a `:code`-orchestrator fan-out, NOT a map-each leaf, but confirm the per-container isolation holds under a REAL induced per-item failure (no scramble, no bleed, failure surfaced).
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts) — the per-container AUTHOR already does; confirm it still holds under parallel child ticks (each has its own blackboard).
