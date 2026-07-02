# MT-4 — Within-source occurrence-merge (union attributes across containers)

## Parent
[Meaningful multi-table extraction](README.md)

## What to build
Guarantee that drafts of the SAME real-world entity arriving from DIFFERENT containers become ONE node with UNIONED attributes. Concretely: the occupation from `Occupation Data` (label + description) and the occupation from `Skills` (`topSkills`, via MT-3) and from `Knowledge` (`topKnowledge`), all keyed by the same occupation code, must land as ONE occupation node carrying label + description + topSkills + topKnowledge.

**VERIFY FIRST, build only if absent.** Grounding shows `create-concept` mints a fresh concept per draft and reconcile does attribute-LINKING (EB5), not obviously attribute-UNION across same-key drafts. So:
1. Drive two containers' drafts for the same key through the real reconcile/landing path and OBSERVE: one node with unioned attributes, or duplicates / dropped attributes?
2. If union already happens — add a durable test that guards it. If not — build the union (at reconcile or landing: same-canonical-URI drafts merge, attributes union; a genuine value conflict is surfaced, never silently overwritten).

## Acceptance criteria
- [ ] REAL 3-container O\*NET slice (`Occupation Data` + `Skills` + `Knowledge`): exactly ONE occupation node per SOC, carrying label + description + populated topSkills + topKnowledge. Verified by reading the projection back (no bare append).
- [ ] Attribute conflicts (same key, different value from two containers) are surfaced honestly (e.g. `:requires-review`), never silently overwritten — no false-green.
- [ ] No spurious duplicate occupation nodes for one SOC code.
- [ ] Domain-agnostic; TDD red→green through reconcile/landing public interfaces (two same-key drafts → one unioned node; a conflict → surfaced). Ontology brick gate green; 0 orphan JVMs.

## Blocked by
MT-3 (needs the aggregated per-container drafts to merge).

## Handoff focus
Read-first: `reconcile_subbehavior.clj` `reconcile-drafts!` + the EB5 attribute-linking (does it union same-key-draft attributes?); `commands.clj` `create-concept` (mints per draft — no URI dedup) + any `update-concept`/attribute-append command; the GC-1 canonical-URI minting (same real entity → same URI across containers is the precondition). Start with the VERIFICATION probe on real drafts before writing any merge code. Re-orchestrate — prefer reconcile/landing as the merge point; do NOT fork. One bounded build; `pgrep -f` hygiene.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse reconcile/landing; do NOT fork.
9. Adversarial qualitative verdict — hunt for a dropped attribute or a silent overwrite masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the merge is deterministic; verify it against real LLM-authored drafts.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — key/attribute union only; name NO O\*NET/CIP/SOC column or entity.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
