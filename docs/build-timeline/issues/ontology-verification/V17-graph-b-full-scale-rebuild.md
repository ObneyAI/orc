# V17 — Graph B full-scale rebuild (coverage-comparable + earnings→program bridged)

**Type:** AFK · **Milestone:** M3 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`
**Supersedes (as the comparison artifact):** V09's 434-concept representative sample.

## What to build

V09 proved the new builder's pipeline end-to-end but produced a driver-capped
434-concept SAMPLE (LIMIT 150 programs, 30 SOC rows, hand-fed offsets, and
driver-prescribed join keys). That is neither coverage-comparable to the old
graphs (A2 production ≈2,509 concepts via V14 / ~14K original) NOR a fair test of
the EVOLUTIONARY BUILDER — V09's connectivity was substantially driver-engineered
through prescriptive prompts. V17 re-runs the build so it (a) covers the full
Louisiana program set and (b) genuinely tests whether the builder DISCOVERS the
cross-source connections on its own.

**Core principle (per HITL direction): NO hardcoded joins, keys, offsets, column
indices, table names, or crosswalk recipes in the driver prompts.** We are testing
the recursive-RLM evolutionary builder — it must DISCOVER how the sources connect.
The driver gives the builder the 5 sources, the per-format source-exploration
tools (V03/04/05/06), and the DOMAIN GOAL — then the builder explores, finds the
keys/crosswalks each source provides, decides which entities are the same real
thing, mints shared/canonical URIs so they merge, and designs the extraction. We
OBSERVE and capture what it discovers; we do not feed it the answer.

The domain goal handed to the builder (a goal, not a recipe):

> Build a comprehensive, connected ontology over these Louisiana
> education-and-career sources: the educational programs offered, the fields of
> study they belong to, the occupations those fields lead to, the institutions
> that offer them, and the earnings / wage outcomes associated with them. Cover
> the Louisiana program set comprehensively. Where different sources refer to the
> same real-world entity, merge them by minting the same canonical identifier.
> Find and use whatever shared keys or crosswalk information the sources
> themselves provide to connect across sources.

Two things change versus V09, BOTH expressed as goals not mechanics:

1. **Comprehensive coverage, not a sample.** The goal asks for the full Louisiana
   program set. The builder decides how to retrieve it (it explores the sources;
   it is NOT told a `LIMIT`, a `STABBR` filter, or which table holds completions).
   The extraction transforms it designs are deterministic maps over full
   results — full coverage costs no per-row LLM.

2. **The earnings→program connection is the builder's to discover.** V09 left
   earnings and programs in disjoint institution clusters (PSEO institutions
   OPEID-keyed; IPEDS programs UNITID-keyed). Whether the builder bridges them is
   now a TEST RESULT, not a driver instruction. The bridge IS discoverable from
   the sources alone (one of the official sources internally carries both key
   encodings), so a capable builder CAN find it — but the driver must NOT name
   that source, that column, or the translation. If the builder discovers the
   bridge → strong positive result. If it does not → an HONEST negative result we
   report as-is (never patched by a hardcoded join).

## Acceptance criteria

- [ ] **No hardcoded connections in the driver.** The driver prompts contain NO
      join keys, OPEID/UNITID translation, table/column names, row offsets, LIMIT
      values, or crosswalk recipes — only the domain goal + the sources + the
      exploration tools. The captured driver prompts are auditable to confirm
      this. (Cross-source linking that appears in graph B is the builder's
      discovery, not the driver's instruction.)
- [ ] Graph B built at comprehensive scale: concept count on the order of the old
      graphs (thousands, not hundreds), covering the full LA program set — reached
      by the builder's own retrieval decisions.
- [ ] Connected program↔CIP↔SOC, with a real multi-hop path read back from the
      graph; 0 dangling edges.
- [ ] **Earnings→program connection reported as a TEST RESULT:** did the builder
      discover the bridge between earnings (OPEID-keyed source) and programs
      (UNITID-keyed source)? Capture the answer honestly with evidence — the
      builder's reasoning trace where it found (or did not find) the shared key,
      and the resulting edge count. If discovered → show the program→…→earnings
      path. If not → state it plainly as a builder limitation; do NOT add a
      driver-side join to manufacture it.
- [ ] Earnings/wages are queryable concept attributes at scale.
- [ ] Auto-embed + ColBERT index complete over the full graph (no silent
      timeout/throughput failure; if a scale wall is hit — embed throughput, dedup
      at scale, ColBERT, LMDB — root-cause + fix it, never mask).
- [ ] Skeleton reaches terminal `:complete`; all 7 stages run.
- [ ] Graph-structure stats captured (same schema as V09) + the full graph B
      saved as a loadable artifact for V10/V12.
- [ ] Same embedding model + ColBERT config as A1 (fairness).
- [ ] Predecessor suites stay green.
- [ ] Live verify: full real build end-to-end; captured verbatim, including the
      builder's discovery trace (how it decided to connect the sources).

## Blocked by

V09 (proven pipeline + transforms to scale up). V01/V06/V07/V16 (done).

## Cross-references

- V09 capture + driver: `docs/build-timeline/live-verify/V09-graph-b-build.md`,
  `development/src/v09_graph_b_build.clj`.
- The earnings-join residual this closes: V09 capture + `V02-mode-a-early-read.md`
  outcome-vertical failure.

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
