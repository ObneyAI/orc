# Verification Phase — Slice Dispatch Plan

How the 13 verification slices get implemented by focused subagents: per-slice
`/handoff` context, whether `/prototype` is needed, the parallel/serial wave
structure, and the binding after-each verification protocol. Parent:
`docs/build-timeline/issues/ontology-verification/` (the slices) +
`docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`.

Each subagent receives: its slice file (acceptance criteria + the SHA-identical
disciplines block — BINDING), the curated context below, the prototype directive,
and the standing instruction to implement via `/tdd` with real-LLM live verify
before declaring done. One commit per slice, audited by path.

---

## After-EACH verification protocol (binding — main thread, never skipped)

We never 100% trust a subagent. After every slice returns, BEFORE marking it done
or dispatching dependents:

1. **Combined regression sweep** — all prior verification-slice suites + the
   relevant rebuild suites green (the running ~1000-assertion sweep).
2. **Adversarial QUALITY review** (discipline 2/4) — read the actual tests +
   implementation; ask "how could this pass while still being wrong?"; confirm a
   REAL-LLM live verify happened and INSPECT the captured output (not just "tests
   green"); confirm no fallback masks a bug; confirm root-causing (no
   "transient"/"flaky" hand-waves).
3. **Inspect-what-we-expect** — verify each acceptance criterion is genuinely met
   by reading the real artifact (e.g. V03: watch a tool sample a real CSV; V06:
   see a CONNECTED graph from a real source; V12: read the actual per-vertical
   output), not by trusting the summary.
4. **Disciplines audit** — additions 8–11 honored (no strawman; adversarial
   verdict; deterministic-skeleton-wraps-LLM; key-as-env-var, no-truncation,
   self-contained, HITL-by-path, branch, one commit, co-author).
5. Only then: mark done, update the combined sweep, dispatch the next wave.

---

## Per-slice handoff dossier

### V01 — auto-embed field detection  · prototype: NO
- **Read:** the skeleton embed/index stages (the skip default); the existing
  embeddable-field detectors (heuristic schema scan + LLM data-sample analyzer +
  the old builder's auto-detect-colbert-fields path); S13/S17 skeleton test
  idiom; retrieval.clj (to prove embedded fields become retrievable).
- **Why no prototype:** the detection capability EXISTS; this is wiring it into
  the embed/index stages. A 5-min inline probe (detectors over a real concept
  set return sensible fields) is enough, then `/tdd`.

### V02 — Mode A early read  · prototype: NO (HITL checkpoint)
- **Read:** S09 TTL ingest; the `louisiana_programs_full.ttl` path; V01 (done);
  S19 tools + S20 card; the recorded BRYC outputs (`BRYC-GRAPH-ANALYSIS.md`) to
  compare against; the s18/s19 live-verify driver pattern.
- **Note:** this is a live run + a documented per-vertical read for HITL review —
  not a TDD feature. Do a quick scale check that the 45 MB TTL ingests.

### V03 — CSV source-access tools  · prototype: SOFT
- **Read:** `sandbox_tools.clj` (the S19 tool pattern: bindings builder,
  docstring-quality + isolation tests); the old `csv_ontology` sheet (extraction
  knowledge to port); the real `cip_soc_crosswalk.csv` as fixture.
- **Prototype (soft):** a small probe on the real crosswalk to settle the tool
  shapes + docstrings (the S19 lesson: a model must use them from the docstring
  alone). Own a per-format ns to stay parallel-safe with V04/V05.

### V04 — SQL/SQLite source-access tools  · prototype: SOFT
- **Read:** `sandbox_tools.clj` pattern; the old `sql_ontology` sheet (PRAGMA
  table/column/FK reads); IPEDS `output.db` as fixture.
- **Prototype (soft):** probe list-tables/schema/FK/sample/query on the real
  IPEDS DB; confirm `query` is read-only + bounded. Per-format ns.

### V05 — Excel source-access tools  · prototype: WORTH
- **Read:** `sandbox_tools.clj` pattern; PSEO `pseo_la.xlsx` + O*NET
  `db_30_1_excel` as fixtures.
- **Prototype (worth):** Excel streaming is the unknown — the OLD builder had NO
  Excel support. Probe sampling a 119 MB worksheet (xlsx = zipped worksheet XML)
  WITHOUT loading it. De-risk before TDD. Per-format ns.

### V06 — RLM-controlled ingestion wiring  · prototype: YES (keystone)
- **Read:** `rlm_discovery.clj` (discovery loop + `sources->blackboard` +
  `compile-discovery-source!`); the skeleton parse-stage; the V03/04/05 source
  tools; S18 discovery + S19 tool-grant patterns.
- **Prototype (yes):** the novel design. Question: "granted the source tools,
  does the discovery RLM explore a real structured source (crosswalk CSV + a
  small SQLite) and design extraction yielding a CONNECTED graph, WITHOUT loading
  the whole source?" Capture the trace; tune the source-exploration prompt; then
  TDD. Also wires the unified source-tool registry (the seam V03/04/05 left to
  V06 to avoid parallel conflicts).

### V07 — axiom-draft ingest  · prototype: NO
- **Read:** `compile-discovery-source!` (`:axioms-skipped` + the scope/
  confidence-class coercion precedent); the S07 axiom commands
  (assert-disjointness / assert-property-characteristic / sub-property / chain);
  the S18 coercion tests.
- **Why no prototype:** mechanical — coerce JSON-string enums → keywords, route
  to S07 commands. Follow the established coercion pattern.

### V08 — graph A1 build harness  · prototype: YES (exploratory spike) · HITL
- **Read:** ORC `main`'s `evolutionary_builder` + sheets + `entity_resolver` +
  `graph_evolver`; the cross-source-linking verdict in
  `ontology-workflow-before-after.md` (the old builder links cross-source ONLY
  with embeddings on + crosswalk-as-edges + FK extraction — "partial" otherwise);
  the 5 sources.
- **Prototype (yes):** per discipline 8, we must confirm A1 is a STRONG, fair
  baseline — spike the old builder on the 5 sources at the strongest honest
  config and verify it actually produces a CONNECTED graph (program↔CIP↔SOC↔
  earnings), not per-source piles. Find the config that makes the old side as
  good as it honestly gets. Runs on a `main` worktree — isolated from this branch.

### V09 — graph B build  · prototype: NO
- **Read:** the `discover-and-build!` entry; V01/V06/V07 (done); S17 skeleton; the
  5 sources.
- **Note:** an assembled full live build over the 5 sources; big real run. Same
  embedding model + ColBERT config as A1 (fairness control).

### V10 — graph-diff harness  · prototype: NO
- **Read:** the A1/B graph artifacts + the A2 cache JSON format;
  `read-models` get-concepts/get-relationships; the rubric dimensions in the
  run-design.
- **Note:** deterministic stats + delta harness; include metrics where the OLD
  graph might win (honest counting).

### V11 — old exploration re-run  · prototype: NO · HITL
- **Read:** daryls-area51 `bryc_graph_explorers.clj` + `bryc_explorer_workflows.clj`;
  the old ORC SHA env/deps; the A2 cache; the probe profiles.
- **Note:** environment setup (old SHA) + a live run capturing verbatim
  per-vertical output. Runs in daryls-area51 — isolated from this repo.

### V12 — new exploration  · prototype: WORTH
- **Read:** S19 tools + S20 card; graph B; the 5 vertical definitions from
  daryls-area51; the probe profiles.
- **Prototype (worth):** craft + test the 5 vertical exploration framings (career/
  financial/outcome/academic/preference) as RLM tasks over graph B; confirm they
  traverse cross-source links + produce gradeable per-vertical output before the
  full capture run.

### V13 — head-to-head report  · prototype: NO · HITL
- **Read:** V10 stats + V11/V12 verbatim outputs; the rubric + bar in the
  run-design; `replacement-readiness-audit.md` (to update with measured evidence).
- **Note:** synthesis + LLM-judge rubric + adversarial regression section +
  verdict; the user signs off.

---

## Sequencing — waves + critical path

**Independent tracks (start early, run alongside — isolated from this branch):**
- **V08** on an ORC `main` worktree (HITL: sources + worktree).
- **V11** in daryls-area51 at the old SHA (HITL: env setup).
These don't touch this branch's code, so they can proceed in parallel with M1/M2.

**Wave 1 (parallel — this-branch code, non-overlapping files):**
V01 (skeleton embed) · V03 (csv ns) · V04 (sql ns) · V05 (excel ns) · V07
(discovery compile). Five subagents. V03/04/05 each own a per-format namespace;
the unified source-tool registry is wired in V06 (avoids a shared-file conflict).

**Wave 2 (after Wave 1):**
- V06 (RLM ingestion wiring) — needs V03/04/05.
- V02 (Mode A early read, HITL checkpoint) — needs V01. Parallel-safe with V06
  (different files); V02 gives the early signal while V06 builds.

**Wave 3:** V09 (graph B) — needs V01, V06, V07.

**Wave 4 (parallel):** V10 (needs V08, V09) · V12 (needs V09).

**Wave 5:** V13 — needs V10, V11, V12. (HITL verdict.)

**Critical path:** (V03/04/05 ∥) → V06 → V09 → V12 → V13, with V08 feeding V10
and V11 feeding V13 on parallel tracks. ~5 sequential build stages.

**File-overlap guards:** V01↔V09 both touch the skeleton but V09 is downstream
(no conflict); V06↔V09 both touch discovery/skeleton but V09 is downstream;
V03/04/05 isolated by per-format ns + registry deferred to V06.

## Prototype summary

YES (keystone/exploratory): **V06** (RLM source ingestion), **V08** (fair-baseline
spike). WORTH: **V05** (Excel streaming), **V12** (vertical framings). SOFT:
**V03**, **V04** (tool-shape/docstring probes). NO: V01, V02, V07, V09, V10, V11, V13.
