# Handoff — MT-4: within-source occurrence-merge (union attributes across containers)

**Issue:** [`../issues/meaningful-multi-table-extraction/MT-4-occurrence-merge.md`](../issues/meaningful-multi-table-extraction/MT-4-occurrence-merge.md)
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` = shell env var only. **Run ONE bounded build at a time; `pgrep -f 'clojure|cpcache'` JVM hygiene; confirm 0 this-repo orphan JVMs after any live run.**
**Blocked-by:** MT-3 (LANDED, commit `af788b3a`) — the per-container aggregated drafts (occupation + topSkills from Skills, + topKnowledge from Knowledge, all same canonical URI) are what must merge.

## VERIFY-FIRST is DONE — the answer is BUILD (not a guard test)

`development/src/mt4_merge_probe.clj` drove 3 same-URI drafts per occupation (Occupation-Data label+description / Skills topSkills / Knowledge topKnowledge) through the REAL `delegate-reconcile!` + landing, then read the concepts projection back. **Grounded result (do NOT re-litigate):**

- ONE node per URI (no duplicates) — because the concepts read-model is keyed by `:uri`.
- **BUT the attributes are NOT unioned — `topSkills` was DROPPED.** The surviving node carried only the LAST same-URI draft's attributes (`topKnowledge`), not `topSkills`. Reconcile reported `:merges 0`, `:concepts-in-scope 2` — it collapsed the 6 drafts to 2 nodes but did NOT union their attributes.

**Root cause (pinned):** `rlm_discovery/compile-discovery-source!` (~1968) lands via `(doseq [c concepts] … :ontology/create-concept …)` — ONE `create-concept` event PER draft, no URI dedup/union. The concepts read-model (`read_models.clj` ~79) handles `:concept-created` with `(assoc state (:uri event) {…full map…})` — a full REPLACE keyed by URI. So N same-URI `create-concept` events → only the last survives → earlier drafts' attributes are silently lost. This is the O\*NET case exactly: Occupation Data + Skills + Knowledge are sheets of ONE source, so MT-2's orchestrator unions their drafts into ONE reconcile batch → intra-batch same-URI drafts.

## The change (shape — a pure union at the landing boundary, re-orchestrate not rewrite)

- **A pure `union-concept-drafts-by-uri`** (new — put it in `rlm_discovery.clj` next to `compile-discovery-source!`, or a small helper ns): collapse concept-drafts sharing `:uri` into ONE draft:
  - **`:attributes`** = the UNION across the group's drafts. A key present in ≥2 drafts with the SAME value → kept once. A key present with DIFFERENT values → a genuine CONFLICT: keep one deterministically AND surface it (do NOT silently overwrite — #5/#9); e.g. collect `{:uri :key :values […]}` conflict entries returned alongside, and/or stamp the merged concept so it reads as needing review. The acceptance wants conflicts surfaced honestly (`:requires-review`-style), never dropped.
  - **`:label` / `:description`** = prefer the ENTITY-DEFINING draft's — the draft that carries a non-blank `:description` (Occupation Data), so the real title/description win over a bare-code label from a measurement container. Fall back to first non-blank. (Deterministic; domain-agnostic — no column/entity names.)
  - Preserve the other draft fields (`:scope`, `:broader`, `:indicators`) from the entity-defining draft; keep the union total + pure (never throws).
- **Apply it in `compile-discovery-source!`** at the `concepts` binding (~1973), BEFORE the `doseq` emits — so exactly ONE `create-concept` per URI lands, carrying the unioned attributes. Surface the union summary (groups merged, conflicts) in the returned `:discovery-provenance` (observable, no false-green).
- **Scope:** this fixes the INTRA-BATCH case (the MT-4 acceptance = the O\*NET 3-container slice, one reconcile batch). The CROSS-BATCH case (a draft whose URI matches an ALREADY-LANDED concept from a PRIOR source's reconcile call — e.g. a later wages source adding an attribute to an occupation O\*NET created) is a SEPARATE, larger change (it needs a `concept-updated`/merge emission against the existing projection, not just a create). VERIFY it in the probe (extend it: land Occupation Data in one reconcile call, THEN Skills in a SECOND call, read back) and REPORT its status honestly. If it's also broken, it is an MT-5 follow-up (flag it) — do NOT silently scope it in or out.

## Read-first (in order)
1. `development/src/mt4_merge_probe.clj` — the verify-first probe + its grounded result (your starting evidence).
2. `ontology/core/rlm_discovery.clj` — `compile-discovery-source!` (~1968, the landing `doseq` — the fix site) + `validate-concept-draft!`/`normalize-concept-draft` (~1319/1351, the draft shape) + `concept-draft->command` (~1451).
3. `ontology/core/read_models.clj` — the `:concept-created` handler (~79, the URI-keyed `assoc` REPLACE that drops earlier attributes) + `:concept-updated` (~105, the `merge` path — relevant only if you tackle cross-batch).
4. `ontology/core/reconcile_subbehavior.clj` — the LAND → entity-reconcile flow (confirm the union belongs at landing, before entity-reconcile; do NOT fork reconcile).
5. `ontology/core/commands.clj` — `create-concept` (~308, mints a fresh id per draft; confirms no built-in URI dedup).

## TDD cycle list (tests FIRST, red→green, behavior through PUBLIC fns)
1. **`union-concept-drafts-by-uri` unions disjoint attributes (pure):** 3 same-URI drafts with disjoint attribute keys (label+desc / topSkills / topKnowledge) → ONE draft carrying label + description + topSkills + topKnowledge; a DIFFERENT-URI draft stays separate. (The heart — the O\*NET case.)
2. **Conflict is surfaced, never silently overwritten:** two same-URI drafts with the SAME attribute key but DIFFERENT values → the merged draft surfaces the conflict (kept-value + the surfaced alternatives / review flag), NOT a silent last-wins. Label/description prefer the description-bearing draft.
3. **Landing emits ONE create-concept per URI (through the public landing seam):** drive same-URI drafts through `compile-discovery-source!` against a real (or in-memory) event store; read the concepts projection back → ONE node per URI with unioned attributes (assert via the projection, no bare append — #7); provenance surfaces the merge count.

(The LIVE proof — a REAL O\*NET 3-container slice: one occupation node per SOC carrying label + description + populated topSkills + topKnowledge, read back from the projection — is the `/inspect-orc`.)

## Do NOT touch
- The MT-1/MT-2/MT-3 code (consume their drafts). The per-row/aggregating extract. The entity-reconcile S03/S12 alignment + V18 integrity (the union is a PRE-landing normalization; reconcile runs after, unchanged).
- The `create-concept` command shape / the read-model `:concept-created` handler (the fix is to emit ONE event per URI, not to change the projection semantics).
- No baked O\*NET/CIP/SOC column or entity names; the union is key/attribute-structural only (#12).

## Live-QA (`/inspect-orc` — the orchestrator runs this, adversarially)
- A REAL O\*NET slice: extract `Occupation Data` + `Skills` + `Knowledge` (MT-2 select + MT-3 aggregate), land via reconcile, read the projection → EXACTLY ONE occupation node per SOC carrying label + description + populated topSkills + topKnowledge (witnessed, not asserted). A dropped attribute or a duplicate node is a FAIL.
- Force a conflict (same key, two values) → it is surfaced, not silently overwritten.
- The cross-batch case status reported honestly. Ontology brick gate green; 0 orphan this-repo JVMs (`pgrep -f`).

## Dependency rule
If a later tracer needs an earlier one's REAL signature (the `union-concept-drafts-by-uri` return shape incl. the conflict channel, the provenance keys), land + inspect the earlier tracer first, then craft the next from the real signature.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (one node that silently dropped an attribute reads as success but is wrong).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (a dropped topSkills is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause (a value conflict is surfaced, never silently overwritten).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — a pre-landing union in `compile-discovery-source!`; do NOT fork reconcile.
9. Adversarial qualitative verdict — hunt for a dropped attribute or a silent overwrite masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the union is deterministic; verify it against REAL LLM-authored aggregated drafts.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — key/attribute union only; name NO O\*NET/CIP/SOC column or entity.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts). (MT-4 is deterministic — no new `:llm` node — so this binds only if you add one.)
