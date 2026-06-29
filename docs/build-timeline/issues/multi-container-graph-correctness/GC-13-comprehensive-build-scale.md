# GC-13 — Comprehensive-build scale: parallelize serial container extraction

**Type:** AFK (perf) · **Blocked by:** — · **Does NOT block GC-11c** (which ran on a small completing build).
**Status:** root cause MEASURED (the earlier hypothesis was falsified — see below). Ready to build.

## Why this exists

The reduced-cap 6/5 build (`:ipeds :crosswalk :onet`, max-containers 6, max-windows 5) does NOT complete in a bounded run (~22-25 min). The SMALL build (2 sources, max 2/2) COMPLETES. This issue is the path to the MAXIMAL-COVERAGE comprehensive build (for the A2-vs-B artifact).

## MEASURED root cause (the earlier hypothesis was WRONG)

The docked version of this issue HYPOTHESIZED "ColBERT PLAID re-indexing is likely the biggest single lever." **A direct per-phase profile of a completing build FALSIFIED that.** Wrapping every phase with timing (the `delegate-*` / `run-cq-gate!` seams + the private `embed-concepts!` / `index-concepts!`) over the small 3-source build gave:

```
Phase                            calls  total-ms   %
  model-extract                     5    189613   55.6%   ← LLM authoring + extraction
  survey                            3     60916   17.9%   ← LLM survey per source
  cq-gate                           1     17971    5.3%
  reconcile                         6     16250    4.8%
  synth-vocab                       1      7438    2.2%
  derive-cqs                        1      6503    1.9%
  embed+index (delegate total)      5      4525    1.3%
  __embed-concepts (MiniLM)         5      4236    1.2%
  __index-concepts (ColBERT PLAID)  5         4    0.0%   ← FOUR MILLISECONDS
```

**ColBERT PLAID indexing is 4 ms (0.0%).** Building the hypothesized "incremental ColBERT index" would save ~4 ms. The real cost is **LLM authoring: model-extract (55.6%) + survey (17.9%) ≈ 74%.**

The model-extract cost is dominated by `orchestrate-extract-containers` (the MC-5 `:code` orchestrator), which runs the per-container SAMPLE→AUTHOR→APPLY unit **SERIALLY** — one `dsl/execute` child tick per container in a `mapv` (the inline comment at the seam: "the Extract step runs up to default-max-containers SERIALLY"). Because each container's AUTHOR is a ~10 s `:llm` call, serial extract time grows LINEARLY with container count — which is exactly why 6 containers/source (3× the small build) blows past the timeout.

## The fix — parallelize the per-container child ticks (bounded)

Replace the serial `mapv` over containers in `orchestrate-extract-containers` with a **bounded-concurrency** parallel map over the SAME `dsl/execute` child-tick mechanism. This is safe and was de-risked empirically:

- Each per-container child tick is ALREADY fully isolated: its OWN `child-tick-id`, its OWN blackboard (read back via `get-tick-blackboard`), its OWN resilience gate + #13 reasoning ("no cross-container trample"). Accumulation is a post-hoc `mapcat` over the result maps (each keyed by `:container`) — NOT a `:map-each` leaf, so the Discipline-12 map-each race does NOT apply.
- The executor already runs tick executions in a `(future …)` on the unbounded cached `soloExecutor` pool (thread-per-execution), and the `:parallel` node already drives concurrent child executions against the shared event store — so concurrent child ticks neither starve a bounded pool nor corrupt the store.

**PROTOTYPE PROOF (measured, not assumed):** swapping the serial `mapv` for a parallel map and re-running the small build produced an **IDENTICAL 447 concepts, 0 dangling** (no scramble, no lost drafts — concurrent Grain appends + per-container attribution are correct) while **model-extract dropped from ~38 s/call to ~21 s/call (~44%)** and total wall-clock fell 341 s → 212 s — at just 2 containers/source. The win scales with container count, so the 6/5 build gets a far larger absolute saving.

### Design points to get right (NOT optional)

1. **BOUNDED concurrency, not unbounded.** The prototype used plain `pmap` (bound ≈ ncpu+2). Production MUST cap concurrency (a `max-extract-concurrency` knob, default a small N like 4–6) so a source with many containers does not fire dozens of simultaneous `:llm` calls (provider rate limits, memory). Reuse an existing bounded-concurrency primitive if the repo has one (e.g. the `:map-each` `:max-concurrency` machinery) rather than hand-rolling — re-orchestrate, not rewrite (Discipline 8).
2. **Order-preserving, honest per-container failure isolation.** Results must stay attributed to their container (the result map already carries `:container`, but keep collection order stable). A single container's child tick THROWING must surface as that container's HONEST failure entry in `:extraction-report` (status/diagnosis) — never crash the whole batch, never silently drop a container (no false-green, #4/#5). This is the same honest-per-container accounting the serial version already produces; preserve it exactly.
3. **The per-container 280 000 ms `:timeout-ms` stays per child tick.** Parallelism does not change it.
4. **Survey (17.9%) is a SEPARATE smaller lever — out of scope here.** This slice is the per-container extract parallelization only. Note survey as a follow-on if 6/5 still doesn't complete after this lands.

## Acceptance criteria

- [ ] `orchestrate-extract-containers` runs the per-container child ticks with BOUNDED concurrency (a configurable cap, sane default); a single-container source is unaffected (N=1).
- [ ] **Unit (TDD red→green):** the bounded-parallel helper preserves result ORDER, respects the concurrency BOUND (a probe with timed thunks proves ≤ N run at once), and isolates a thrown thunk (that slot becomes an honest failure, the batch completes). Reverting to serial keeps these green (behavior through the public seam); reverting the BOUND check goes RED.
- [ ] **Live equivalence (the load-bearing proof, Discipline 4/9):** a real multi-container build's drafts are EQUIVALENT to the serial baseline — same concept set within LLM variance, 0 dangling, every container represented in `:extraction-report`, NO scrambled/misattributed drafts — AND model-extract per call is materially faster. An induced per-container failure is surfaced (honest `:extraction-report` entry), never dropped or misattributed.
- [ ] **The 6/5 build now COMPLETES in a bounded run**, its CQ-gate answers the cross-source CQ, and the connected graph reads back (the maximal-coverage acceptance toward the A2-vs-B artifact). If it still times out, root-cause the NEXT dominant phase (survey) honestly — do not declare success on a still-timing-out build.
- [ ] Domain/format-agnostic (the parallelization names no field/container); recursive-only RLM untouched; ontology brick gate green; 0 orphan this-repo JVMs after every live run.

## Disciplines

The 13 Core Disciplines (verbatim, binding — see the GC-11c handoff for the full list) apply. The load-bearing ones here: **#1** never assume — the ColBERT hypothesis was MEASURED false, so measure the new dominant phase too; **#2/#9** adversarial — hunt for draft scramble/loss under concurrency, do not trust "same count"; **#4/#5** no false-green / no silent drop — an honestly-failed container is surfaced, a still-timing-out build is a FAIL; **#6** TDD red→green on the bounded helper; **#8** re-orchestrate not rewrite — reuse the existing child-tick + bounded-concurrency machinery; **#12** map-each-primitive caution — this is NOT a map-each leaf, but confirm the isolation holds under real per-item failure; **#11** JVM hygiene (bounded runs, kill orphans, confirm 0 after).
