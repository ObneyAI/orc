# V08 — Graph A1 build harness (old builder, strongest honest config)

**Type:** HITL · **Milestone:** M3 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

Build the baseline graph A1: a minimal harness on an ORC `main` checkout/worktree
that runs the OLD evolutionary builder on the 5 official sources at its STRONGEST
honest config, and saves the resulting graph artifact for side-by-side use from
this branch. "Strongest honest config" (discipline 8): embeddings ON, the
crosswalk CIP↔SOC extracted as explicit relationship edges, IPEDS foreign-key
extraction enabled. This is the unbiased same-builder-lineage baseline — NOT a
strawman.

HITL: needs the user's source files + setting up a `main` worktree; the user
confirms the build config is genuinely the strongest fair version of the old
builder before it's used as the baseline.

## Acceptance criteria

- [ ] A `main` worktree builds graph A1 from the 5 official sources (Excel→CSV
      where the old builder needs it) via the old evolutionary builder.
- [ ] Strongest-honest-config controls are ON and documented: embeddings,
      crosswalk-as-edges, FK extraction. (Discipline 8 — no hobbling.)
- [ ] The A1 graph is CONNECTED across sources (program↔CIP↔SOC↔earnings links
      present), not per-source piles — spot-checked + counted.
- [ ] The artifact is saved in a form loadable from this branch for V10/V11.
- [ ] Graph-structure stats captured: nodes, edges by type, cross-source links,
      properties per concept, coverage.
- [ ] HITL sign-off: the user agrees A1 is a fair, strong baseline.
- [ ] Live verify: the build runs end-to-end with real embeddings (real LLM where
      the old builder uses it); captured.

## Blocked by

Sources gathered (the 5 official files); a `main` worktree.

## Cross-references

- PRD module M-Compare; `ontology-workflow-before-after.md` (old builder
  capability + caveats); the entity_resolver / graph_evolver / csv+sql sheets on
  `main`. This A1 also supplies the old-sheets baseline G2 was missing.

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
