# GC-15 — Survey cost: the next LLM lever (~18%)

**Type:** AFK (perf) · **Blocked by:** GC-13 (✅) · **Status:** documented follow-on; only pursue if the comprehensive build needs to be faster still.

## Why this exists

The GC-13 per-phase profile showed the post-GC-13 cost order on a completing build:

```
  model-extract   55.6%  ← FIXED by GC-13 (bounded-parallel container extraction)
  survey          17.9%  ← THIS — the next-largest LLM lever
  cq-gate          5.3%
  reconcile        4.8%
  …
```

`delegate-survey!` runs the per-source Survey `:llm` (`ontology-survey/…@v1`) once per source (~20 s/source measured). For 3 sources that is ~60 s; for the maximal 5-source build it grows. It is the next-largest LLM lever after extraction.

## Fix directions (to scope when picked up — MEASURE first, do not assume)

1. **Parallelize survey across sources.** Sources are surveyed in a loop before the accumulating per-source build; the surveys are INDEPENDENT (each profiles its own source), so they can run with bounded concurrency — REUSE the GC-13 `bounded-parallel-map` rather than a new primitive (re-orchestrate, not rewrite). Verify the surveys are genuinely independent (no cross-source accumulation in survey) before parallelizing.
2. **Cheaper/looser survey.** Is the full `:llm` survey needed per source, or can a lighter profile suffice for already-structured sources (SQL/CSV with declared schema)? Measure the survey's marginal value before trimming.

## Acceptance criteria

- [ ] Survey wall-clock on the comprehensive build is materially reduced WITHOUT degrading the downstream model-spec/extraction quality (verify the graph is still complete + connected + the gate still answers — no false-green from a degraded survey).
- [ ] If parallelized: surveys stay correctly attributed to their source (the GC-13 order-preserving + honest-failure properties), proven on a real multi-source build.

## Disciplines

The 13 Core Disciplines apply verbatim (see the GC-13 handoff). #1 measure don't assume (re-profile after GC-13); #4/#9 no false-green / adversarial (a faster survey that degrades extraction is a FAIL); #8 reuse `bounded-parallel-map`; JVM hygiene.
