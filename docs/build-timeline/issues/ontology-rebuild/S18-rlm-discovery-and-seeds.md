# S18 — Recursive-RLM discovery + ontology-discovery seed corpus (G2-gated)

**Type:** **HITL** (seed-body review + bench sign-off) · **Phase:** 3 (gate) · **PRD module:** M8 · **Stories:** 22, 23, 24, 9

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The adaptive half of the hybrid builder: discovery phases (structure
analysis → entity-type discovery → relationship/axiom discovery) become
**recursive-RLM tasks** plugged into S17's discovery seam — the model
designs extraction per source, interrogating the existing graph through
S19's ontology tools instead of receiving a static dump.

- **Seeds, not hand-edits (Path 2):** bench-proven patterns (per-section
  map-each with bounded concurrency, adversarial verify-then-finalize,
  hierarchical synthesis) are authored as ontology-discovery tree-class
  seeds. Discovery guidance encodes the course-derived modeling
  discipline: closure axioms when sources enumerate completely,
  single-parent assertion, value-partitions for enum-shaped attributes,
  roles-vs-classes, ORSD goal/scope context (S14), sequence and
  quantity+unit capture (S05), statement-level provenance quotes (S06).
  Seed bodies are self-contained (substance verbatim — no internal slice
  names, no file paths) — **HITL: the user reviews every seed body**.
- **The R-Inject boundary holds:** with `:auto-classify?` OFF, discovery
  runs full recursive-RLM — designing and reasoning over trees — it just
  doesn't learn across runs. ON opts the builder into the self-improving
  loop (corpus prepend, judges, living descriptions). LLM use itself is
  unconditional, budget-knobbed. Recursive mode ONLY — no terminal.
- **Determinism knob:** a build can pin a previously-recorded discovery
  tree for reproducible output; the pinned tree is stored/referenced so
  CI builds don't inherit design variance.
- **G2 gate:** the RLM discovery path must BEAT the old-sheet baseline
  on the S16 bench. Until then the old path remains the default;
  **HITL: the user signs off the bench comparison before the default
  flips.**

## Acceptance criteria

- [ ] Discovery phases run as recursive-RLM through the S17 seam against
      all bench source formats; every generated tree + reasoning captured
      verbatim (never truncated) for review
- [ ] **G2:** bench scores for the RLM path exceed the old-sheet
      baseline on concept AND relationship precision/recall — real runs,
      captured, user-signed
- [ ] Adversarial quality review of outputs (not just scores): no
      hallucinated concepts/relationships in spot-checked builds; closure
      axioms emitted where sources enumerate completely; units captured;
      roles NOT modeled as classes — each verified against the actual
      source text
- [ ] R-Inject OFF: zero classification/judge/reranker/living-description
      events emitted during a build (instrumented count = 0); discovery
      still fully functional. ON: corpus prepend visible in the captured
      discovery context
- [ ] Determinism knob: two builds pinning the same recorded tree produce
      identical graphs (event-set equality)
- [ ] Seed corpus: every seed body **user-reviewed** (HITL), shape-valid,
      self-contained; seeds retrievable via the standard classify path
      when opted in
- [ ] Budget controls honored: discovery respects configured budgets with
      explicit logging of consumption

## Blocked by

- S17 (the seam), S19 (the tools discovery uses), S16 (the gate that
  judges it)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
