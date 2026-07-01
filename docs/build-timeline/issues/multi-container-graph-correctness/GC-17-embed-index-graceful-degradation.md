# GC-17 — Embed/index graceful degradation (no silent native-OOM JVM death)

**Type:** AFK (robustness) · **Blocked by:** — · **Status:** DOCKED follow-on (separated from GC-16 by user decision). Not blocking once GC-16 shrinks the graph.

## Why this exists

While root-causing GC-16, the full 5-source build was observed to **kill the JVM hard** during embedding — no Java `OutOfMemoryError` (so `-XX:+ExitOnOutOfMemoryError` never fired), no exception caught, no diagnosis, the process simply vanished. Cause: embedding + ColBERT-indexing ~6–8k bloated concepts exhausts NATIVE/off-heap memory (DJL/PyTorch + ColBERT PLAID), which aborts the process below the JVM's exception machinery.

GC-16 (modeling fix) removes the immediate trigger (the graph shrinks ~10×). But the FAILURE MODE remains: a genuinely large legitimate graph could still exhaust native memory and die silently. A build should never vanish without a diagnosis (Discipline 4/5 — no false-green, no silent failure).

## Fix directions (scope when picked up — measure first)

1. **Bounded/batched embed + index with backpressure** — embed in bounded batches (already batches of 32) but also CAP the in-flight native allocation; for ColBERT, index incrementally / in bounded chunks; surface a clear `:status :failed-at :embed/:index` with a diagnosis if memory is short, rather than dying.
2. **Pre-flight size guard** — if the concept count × avg-attribute-size exceeds a budget, fail FAST with an honest diagnosis (and a pointer to raise heap / reduce caps), not mid-embed.
3. **Native-memory headroom** — document/configure the off-heap budget (DJL `PYTORCH_*` / arena, ColBERT process memory) the build needs; surface it rather than relying on the JVM heap flag alone (it doesn't bound native memory).

## Acceptance criteria

- [ ] A build that would exhaust memory FAILS with an honest terminal + diagnosis (which phase, how big, what to do) — never a silent JVM disappearance.
- [ ] A bounded large build still completes; the bound is honest (logged), not a silent truncation of concepts.

## Disciplines

The 13 Core Disciplines apply verbatim (see the GC-13 handoff). #1 measure don't assume; #4/#5 no silent failure / no false-green; JVM hygiene.
