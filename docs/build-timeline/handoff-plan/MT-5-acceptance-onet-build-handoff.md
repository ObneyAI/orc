# Handoff — MT-5: acceptance — comprehensive O\*NET build + A2-vs-B readiness

**Issue:** [`../issues/meaningful-multi-table-extraction/MT-5-acceptance-onet-build.md`](../issues/meaningful-multi-table-extraction/MT-5-acceptance-onet-build.md)
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` = shell env var only. **Run ONE bounded build at a time; `pgrep -f 'clojure|cpcache'` JVM hygiene; confirm 0 this-repo orphan JVMs after every run.**
**Blocked-by:** MT-4 + MT-4b (LANDED) — occurrence-merge (intra + cross batch) so occupations carry cross-container attributes as ONE node.

## Nature of this slice
This is the `/inspect-orc` ACCEPTANCE for the whole MT line — mostly a LIVE comprehensive build + measurement, plus a small pure verdict fn (the `/tdd`). Re-orchestrate: exercise the COMPOSED pipeline (the real central-evolver), do NOT fork. The old per-row pipeline OOMed at ~148k drafts/source; MT-1..MT-4b collapse O\*NET to ~1016 occupation nodes, so a comprehensive build is now tractable — verify that.

## The A2 reference baseline (MEASURED — the comparison anchor)
`/Users/darylroberts/Desktop/Code/daryls-area51/.bryc-graph-cache-with-embeddings.json` — 880 occupation nodes (`soc:soc_XX_XXXX`). Coverage of the four facets:
- **topSkills: 40/880 (4%)** · **topKnowledge: 40/880 (4%)** — A2's skills/knowledge are ~96% EMPTY. THIS is what graph B must beat.
- `labor:jobZone`: 412/880 (46%) · `labor:riasecCode`: 412/880 (46%).
Graph B already showed ~894/1016 (~88%) populated topSkills in the MT-4 live slice — so B should CRUSH A2 on skills/knowledge (the whole point).

## Acceptance criteria (measure each on the REAL build; honest negatives)
1. **One occupation node type, NO junction/observation nodes** — the ability×activity / *-to-* bridge tables are DROPPED (MT-2), not modeled. Scan the landed graph's concept URIs/kinds: any bridge/observation-kind node is a FAIL.
2. **Occupations carry populated flat attributes** — topSkills + topKnowledge (MT-3 aggregation) at minimum; jobZone + riasecCode as available. Quantify B's % populated per facet vs the A2 baseline above. B MUST beat A2's 4% on skills/knowledge; a completed build with ~0 populated topSkills is a FAIL (#4).
3. **Occupation count sane** — ~1000 O\*NET-SOC occupations (not per-row fragments, not one occupation fragmented across type-names).
4. **Reproducible across ≥2 clean solo runs** — stable occupation count + populated coverage. One bounded build at a time; `pgrep -f` hygiene between runs (contention → native-OOM misread as failure).
5. **Domain/format-agnostic implementation** — scan the IMPLEMENTATION (the `components/ontology` MT-1..MT-4b code), NOT the driver/analysis, for baked O\*NET/CIP/SOC column or entity names (#12). The driver + this acceptance analysis MAY name O\*NET fields (they read the domain).
6. **Unblocks A2-vs-B** — this slice proves B's O\*NET is honest; the full A2-vs-B comparison harness lives in the EB12/BRYC docs.

## The change (shape)
- **A pure `onet-acceptance-verdict`** (the `/tdd` deliverable, in the MT-5 driver `development/src/`): `(onet-acceptance-verdict {:occupations [...] :junction-node-count n} a2-baseline)` → `{:pass? bool :reasons [{:criterion :pass? :detail}]}`. Encodes: occupation-count-sane, no-junction-nodes, skills-coverage-beats-A2, the four-facet coverage. PURE — testable on fixtures (a synthetic good graph → pass; a 0-skills / junction-bearing / fragmented graph → fail). This is the "how could it pass while wrong?" guard (#2/#9).
- **A comprehensive O\*NET-only build driver** — REUSE `eb12_graph_b_central_evolver.clj` `run! {:only [:onet] :max-containers N}` (the real composed central-evolver: survey → model → MT select/aggregate → reconcile/merge → embed). Read the occupation nodes + attributes back from the projection, measure per-facet coverage, run the verdict, print B-vs-A2. Raise `:max-containers` enough that the occupation-centric containers (Occupation Data, Skills, Knowledge, and — for jobZone/riasec — Job Zones, Interests) are SELECTED; report which containers were selected (honest coverage).

## TDD cycle list (tests FIRST, red→green, behavior through the PUBLIC verdict fn)
1. **`onet-acceptance-verdict` passes a good graph:** ~1000 occupations, populated topSkills on ≫4%, 0 junction nodes → `:pass? true`, each criterion green.
2. **It FAILS honestly on each adversarial shape:** a 0-populated-topSkills graph → fail (beats-A2 criterion false); a graph with a junction/observation node → fail; a fragmented graph (occupation count wildly off, or one SOC under two type-names) → fail. (The no-false-green heart — #2/#9.)

(The LIVE proof — the real comprehensive build ≥2× beating A2 — is the `/inspect-orc`, not a unit test.)

## Live-QA (`/inspect-orc` — the orchestrator runs this, adversarially — the bulk of MT-5)
- Run the comprehensive O\*NET-only build MYSELF (real Grain + real LLM + real db_30_1_excel). Read the projection back.
- Assert: ~1000 occupation nodes, 0 junction/observation nodes, topSkills/topKnowledge populated ≫ A2's 4%, no fragmentation. Quantify B-vs-A2 per facet.
- Run it a SECOND clean solo time → stable count + coverage (reproducible; not a one-off / contention artifact).
- Scan the MT-1..MT-4b implementation for domain leakage (grep the impl for O\*NET/CIP/SOC column/entity literals).
- Report the HONEST verdict — a junction node, an empty-attribute occupation, or a fragmented count is surfaced AS-IS. Ontology brick gate green; 0 orphan this-repo JVMs (`pgrep -f`).

## Do NOT touch
- The MT-1..MT-4b production code (this slice EXERCISES + VERIFIES it; only fix a root-caused defect the acceptance surfaces, with its own test).
- The A2 reference cache (read-only).
- No baked O\*NET/CIP/SOC names in any production code you touch.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (a completed build with 0 populated topSkills or junction nodes is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — the acceptance exercises the composed pipeline; do NOT fork.
9. Adversarial qualitative verdict — hunt for junction nodes, empty attributes, or fragmentation masked as a "completed" build; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic selection/merge AND the LLM-authored aggregation on the real build.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — scan the IMPLEMENTATION (not the driver) for O\*NET/CIP/SOC leakage.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
