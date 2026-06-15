# V15 — hybrid-search result label enrichment from the event-sourced projection

**Type:** AFK · **Milestone:** M3 (gates V12 exploration quality + comparison fairness) · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`
**Surfaced by:** V02 Mode-A early read (`docs/build-timeline/live-verify/V02-mode-a-early-read.md` §2, §5) — confirmed against a real event-sourced graph.

## What to build

`hybrid-search` (and the other retrieval result-assembly paths) must populate
`:label` (and `:description`) on result maps from the **event-sourced concept
projection**, so results over an event-sourced graph carry human-readable labels
— not `:label nil`.

Root cause: the retrieval result-enrichment path resolves concept metadata via a
**static/in-memory concept lookup** (`get-concept-by-uri` against the static
store). For concepts created through the event-sourcing command path (the real
substrate — V02's graph, V09's graph B, any consumer build), that static store
is empty, so the lookup returns nil and `:label`/`:description` come back nil.
The model then has to spend an extra `get-concept` round-trip per hit to recover
the label, costing exploration iterations and tokens.

The fix (root-cause): enrich result maps from the event-sourced concepts
projection (the `ctx`-scoped read-model/query that the rest of the substrate uses
for concept state), so labels ride back on the result maps directly. Where the
static store is genuinely the right source (existing static-backed callers), that
path must keep working — prefer the event-sourced projection when a `ctx` is
available, fall back to static only when there is no event-sourced source.

## Acceptance criteria

- [ ] `hybrid-search` results over an **event-sourced** graph carry non-nil
      `:label` and `:description` resolved from the concept projection (verified
      on the kind of graph V02 exercised: concepts created via
      `:ontology/create-concept` commands).
- [ ] The label/description on a result map matches the concept's actual
      projected label/description (not a URI-derived placeholder).
- [ ] No regression for any existing static-store-backed retrieval path — those
      results stay labeled as before; the existing retrieval suite stays green.
- [ ] The fix is enrichment-source correctness, NOT a hardcoded label table or
      URI-string parsing (no phrase-matching / string-munging the URI into a
      label).
- [ ] Live verify: a real event-sourced graph (the V02-style adapter-built graph
      or a small real build), `hybrid-search` run with real embeddings, captured
      result maps showing non-nil labels on the hits.

## Blocked by

None — independent file (`retrieval.clj`); parallel-safe with V14 (`ttl_ingest.clj`)
and V16 (`colbert/bridge.clj`).

## Cross-references

- Bug report with verbatim `:label nil` evidence: `docs/build-timeline/live-verify/V02-mode-a-early-read.md` §2 (hybrid-search top-hits) + §5 gap list item 4.
- Gates V12 (new exploration) — labeled hits reduce the explorer's iteration/token
  cost and make the per-vertical output legible for the head-to-head.

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
