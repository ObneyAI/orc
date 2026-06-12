# S19 — RLM ontology tools (builder-facing subset)

**Type:** AFK · **Phase:** 4 · **PRD module:** M9 · **Stories:** 19, 21

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The ontology retrieval surface exposed as sandbox tool primitives for
repl-researcher nodes (recursive mode), builder-facing subset first:

- `graph-search` — scoped hybrid search (BFS + embedding + ColBERT,
  honoring S02 scoping + S03 auto-widening)
- `neighborhood` — BFS expansion around a URI (the DESCRIBE-equivalent)
- `get-concept` — single-concept lookup with full body (labels,
  attributes, annotations, evidence metadata)
- `exists?` — cheap existence check (the ASK-equivalent)
- `absent-in-graph?` — closed-world absence over the projection (the
  deterministic negation helper)
- `filter-by-label-pattern` — regex/string filtering over labels
- `classify-task` / `classify-behaviors` — the existing classifier
  surface as tools

Tools follow the established sandbox-primitive conventions (named,
documented to the model with worked one-liners, results returned as
data). Ontology-id scoping is enforced INSIDE the tools — a sandboxed
model cannot escape its granted section(s) by crafting arguments
(adversarial isolation requirement). LLM/tool use here is
ontology-mechanism functionality — available regardless of the R-Inject
opt-in.

## Acceptance criteria

- [ ] Each tool callable from a recursive-RLM sandbox session and
      returns correct data against a seeded graph (per-tool integration
      tests through the sandbox, not direct fn calls)
- [ ] Isolation adversarial test: a sandbox granted section A cannot
      retrieve section B content through ANY tool, including crafted
      ontology-id arguments — the grant, not the argument, is
      authoritative
- [ ] `absent-in-graph?` + `exists?` verdicts verified against fixtures
      including the trap case (entity exists under a different URI
      spelling — absence verdicts must be scoped claims about the graph,
      and the tool docs given to the model say so)
- [ ] Tool documentation strings (what the model sees) reviewed for
      self-containedness — a model with NO other context can use each
      tool correctly from its docstring + example
- [ ] Live verify: a real recursive-RLM session using ≥4 of the tools to
      answer a question about a real graph; full transcript captured and
      adversarially reviewed for tool-use quality (right tool, right
      arguments, grounded conclusions)

## Blocked by

- S02 (scoped retrieval the tools wrap)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
