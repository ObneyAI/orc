# V20 — General scaffolding + deterministic full-extraction

**Type:** AFK · **Milestone:** M3 (builder hardening; gates V21 re-run) · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`
**Blocked by:** V18 (referential integrity), V19 (count-rows + stream-all). Both committed + verified.
**Surfaced by:** V17 — the builder modeled programs as edges (no `program:` nodes) and took ~1 window per source despite an explicit paging instruction (no comprehensive coverage).

## What to build

Two general, domain-agnostic improvements so the evolutionary builder produces a
well-modeled graph at comprehensive scale on ANY source/medium/domain.

### Part 1 — General scaffolding (domain-agnostic guidance in the SHARED discovery prompt)

No domain specifics (discipline 12). Two principles added to the general goal/
scaffolding (the text every format specialist gets):

1. **Entity-as-node modeling.** Mint as CONCEPTS the entities that bear their own
   attributes or are referenced by other entities — do NOT represent a real entity
   only as an edge between two other concepts. A thing that has properties, or that
   you would retrieve / recommend / describe, is a NODE. (V17 modeled programs only
   as `offersProgram` edges, so there were no program nodes to recommend.)
2. **Extract-to-coverage.** Size the source (`count-rows`) and cover it
   (`stream-all`); design extraction as a TRANSFORM over rows, not a hand-picked
   sample. Reach the coverage the goal asks for.

### Part 2 — Deterministic full-extraction (the coverage guarantee that does NOT depend on the model looping)

The specialist designs the extraction on a SAMPLE; the deterministic skeleton then
applies it to the FULL source via V19's `stream-all`. Recommended seam (refine
during the prototype if a cleaner integration emerges, as long as the contract
below holds):

- The builder emits, instead of (or alongside) hand-picked literal drafts, an
  **extraction transform**: a model-authored pure function
  `(fn [row] -> {:concept-drafts [...] :relationship-drafts [...]})` plus the
  source selector (sheet/table) it applies to. The builder validates it on a
  sample within the discovery loop (sees sane drafts) before committing.
- A skeleton/discovery apply-step **streams the FULL source** (`stream-all`, bounded
  windows, per-call ceiling preserved), runs the transform per row, and COLLECTS the
  full draft set.
- **Per-row errors are caught + counted + surfaced** — a transform that works on the
  sample but throws on some later row must NOT abort the source; skip the bad row,
  count it, surface the count. If the failure RATE is high, that surfaces loudly (no
  false green — a transform that fails most rows is not a success).
- The collected full draft set flows through the EXISTING compile + V18 referential
  integrity (every endpoint resolves; implied entities + ambiguities surfaced).
- The transform is eval'd in the SAME sandbox the RLM already uses for model code —
  applying it over more rows is the same eval, just more rows. Avoid touching
  `rlm_sandbox.clj` if possible; note it explicitly if a binding seam is unavoidable.

Scope: csv / sql / excel (what the head-to-head needs). The text specialist's
registry/sandbox wire (V19 seam note) is a deferred follow-up — no text source is
among the 5, and it touches off-limits `rlm_sandbox.clj`.

## Acceptance criteria

- [ ] The builder designs a transform on a SAMPLE and the skeleton applies it over
      the FULL source — proven on a REAL source producing far more than a sample
      (e.g. full IPEDS Louisiana programs → thousands of concepts, not ~100).
- [ ] Per-row transform errors are caught, counted, and surfaced; a high failure
      rate surfaces loudly (no abort, no false green).
- [ ] The entity-as-node scaffolding yields NODES for attribute-bearing / referenced
      entities (the built graph has program-style concept nodes, not edge-only).
- [ ] Comprehensive coverage: `count-rows` ≈ extracted entities (minus surfaced
      skipped rows) for the exercised source.
- [ ] Integrates with V18: the full draft set compiles with every endpoint resolving.
- [ ] Domain-agnostic (discipline 12); no education/CIP/SOC specifics; format
      specialists preserved.
- [ ] Predecessor suites stay green (s17, s18, s12, v06, v18, v19, source-tools).
- [ ] Live verify: a real full-scale extraction over ≥1 real source (SQL or Excel),
      captured verbatim (transform, row count, drafts produced, error count,
      endpoint-resolution).

## Prototype (YES — novel seam)

Before TDD, prototype the apply-step: "given a model-authored transform validated on
a sample, does streaming the full source + applying it produce a comprehensive,
referentially-integral draft set (with per-row errors counted, not aborting)?"
Capture it, settle the transform representation, THEN TDD.

## Cross-references

- V19 seam notes (drive `stream-all` deterministically; text-registry deferred):
  `docs/build-timeline/issues/ontology-verification/V19-specialist-tool-ergonomics-and-streaming.md`.
- V18 referential integrity (the full draft set flows through it):
  `docs/build-timeline/issues/ontology-verification/V18-referential-integrity-always-on.md`.
- V17 misses this closes: `docs/build-timeline/live-verify/V17-graph-b-full-scale.md`.

## Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation)

1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.

### Verification-phase additions (binding for this initiative)

8. No strawman / unbiased baseline. The old side (graph A1) always runs at its STRONGEST honest config (embeddings on, crosswalk CIP↔SOC extracted as edges, FK extraction). Never hobble or weaken the old system to make the new one look better — beating a weakened baseline proves nothing.
9. Adversarial qualitative verdict. The comparison is judged on the ACTUAL verbatim information returned, per vertical — actively hunt for where the NEW system is WORSE. "Both completed" is not a pass; no false-better.
10. "Deterministic skeleton" ≠ LLM-free. The ontology is DISCOVERED BY LLMs (recursive-RLM discovery + LLM dedup/CQ judges) inside the deterministic skeleton; verify BOTH the deterministic contracts AND the LLM-discovery quality.
11. Standing ops rules: the real OpenRouter key is passed as a shell env var ONLY, never committed; never truncate model-authored output when capturing/comparing (pass verbatim); retrieval-facing descriptions are self-contained (no file paths / SHAs / slice names); HITL audit every changed/added file by path before any commit; branch = feature/ontology-architecture; one commit per slice; co-author `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
12. Domain/industry-agnostic. These fixes improve the GENERAL evolutionary builder — NO tuning toward the education/crosswalk example or any vertical (no baked-in CIP/SOC/OPEID knowledge or industry schema). The evolver's focus comes from the runtime goal/docs. Format/medium SPECIALISTS (CSV/SQL/Excel/text, each with their own tools + instructions + trees) ARE encouraged — improving a specialist's ergonomics is in scope; encoding a domain answer is not.
