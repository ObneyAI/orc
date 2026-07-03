# MT-7b — Vocabulary proposal path (explicit new-type admission, never silent freelancing)

## Parent
[Meaningful multi-table extraction](README.md) · Design: [ADR-0001 canonical vocabulary binding](../../../../components/ontology/docs/adr/0001-canonical-vocabulary-binding.md) (settled — do not re-litigate) · Terms: [`components/ontology/CONTEXT.md`](../../../../components/ontology/CONTEXT.md) §Vocabulary & identity.

## What to build
The **vocabulary proposal** — the only legitimate way the canonical vocabulary grows mid-build (the survey samples containers; an unsampled container can genuinely hold a type the model-spec missed):

1. **Explicit proposal block in the AUTHOR contract** — instead of a declared type, the author may declare a NEW type: its name + its identifying key field(s) drawn from REAL sampled columns. Deterministically validated on receipt: the keying fields must exist in the container's sample; the normalized name must not collide with an existing type/alias (a collision IS the existing type — snap to it).
2. **Proposals are LOCAL during the concurrent extract** — the proposing container's drafts use the proposed type; NO shared mutable vocabulary across concurrent child ticks (no race machinery, order-independent results).
3. **Post-extract deterministic reconciliation** in the orchestrator:
   - normalized-NAME collision between proposals → auto-merged (first-accepted name wins; variants recorded as aliases);
   - same keying-field set, DIFFERENT names → surfaced `:requires-review`, **never auto-merged** (two distinct types can share a key field; the post-landing dedup cascade with retrieval evidence is the right court).
4. **Admitted proposals join the vocabulary before canonical-URI minting** (an admitted proposal is just an alias snap before canonicalize — the existing machinery unchanged), and the whole proposal ledger (proposed / admitted / merged / requires-review) is surfaced in the extraction report — observable, no false-green.

## Acceptance criteria
- [ ] A container holding an entity type the model-spec missed extracts via an explicit proposal (drafts land under the proposed type) instead of failing or freelancing. Verified LIVE on a real source.
- [ ] Two containers proposing name-variants of the same type (case/separator) → ONE admitted type, variants as aliases, drafts merged under one URI scheme. TDD through the public orchestrator seam with controlled fakes.
- [ ] Two proposals sharing a keying-field set under different names → BOTH kept + surfaced `:requires-review` — never auto-merged.
- [ ] An invalid proposal (keying field absent from the sample / name colliding with a declared type) is deterministically rejected-to-snap or failed honestly — never admitted as a duplicate.
- [ ] The proposal ledger appears in the extraction report. Domain/format-agnostic (no domain names in code). Ontology brick gate green; 0 orphan JVMs.

## Type
AFK.

## Blocked by
MT-7a — the enforcement seam this extends. Per the dependency rule, the MT-7b handoff is written AFTER MT-7a lands + is inspected, from 7a's REAL seam signatures (do not pre-write it).

## /prototype
Not required — the mechanics are deterministic set logic on explicit proposal data (the failed keying-resolution prototype does not apply here: proposals CARRY their keying fields, and the space is a handful of proposals, not thousands of drafts).

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (a proposal auto-merge that collapses two DISTINCT same-key types reads as "deduped" but is an over-merge).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause (an invalid proposal is rejected/failed honestly; a keying-collision is surfaced, never silently resolved).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; NO hardcoded phrase matching / NO fuzzy proposal matching — normalized-exact name + explicit keying-set logic only.
8. Re-orchestrate, NOT rewrite — proposals ride the existing author contract + orchestrator accumulation; the admission is an alias snap before the existing canonicalize; do NOT fork.
9. Adversarial qualitative verdict — hunt for a duplicate type admitted, an over-merged proposal pair, or a proposal ledger that hides a rejection; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the proposal content is LLM-authored; the admission/dedup is deterministic; verify BOTH on real runs.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — proposals are validated structurally against the runtime sample; name NO O\*NET/CIP/SOC column or entity; this is the general system TESTED with O\*NET, not built FOR it.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
