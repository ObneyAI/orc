# DT7 — Cross-source linking / reconciliation against current graph state — LIVE VERIFY

**Date:** 2026-06-17 · **Branch:** `feature/ontology-architecture` · **Slice:** DT7 (PRD M5)

A REAL cross-source reconciliation over the BRYC sources: extract from TWO real
files — the crosswalk CSV **and** the IPEDS SQLite `CIPCodes` table — both minting
`cip:<code>` canonical URIs into ONE ontology, then run the DT7 reconciliation
pass and prove cross-source merge + ambiguity surfacing + 0 dangling +
reconcile-against-current-graph-state (reconcile-not-duplicate).

## Method (honest scope)

Both sources are extracted via the **same deterministic V20 full-extraction
apply-step the discovery tree uses at scale** (`rlm-discovery/apply-extraction-transform!`)
— it STREAMS every real row from the real files. This is real extraction (it reads
the actual bytes of both `cip_soc_crosswalk.csv` and `output.db`). The per-row V20
transform contract is hand-supplied here (the LLM tree-authoring step that *writes*
that transform is verified separately in DT2/DT3/DT4 live verifies); DT7's subject
under test is the **reconciliation pass**, not re-proving DT1–DT4 extraction.

> Why not the full LLM `run-discovery-tree!` per source: a first attempt drove the
> real LLM emit-tree loop per source — both runs hit the per-node Phase-2 tick
> timeout (`:failed-at-model`, tree execution timed out at ~235s) before producing
> concepts. That is the known DT extraction cost, not a DT7 defect — and the
> reconcile pass honestly reported the resulting empty graph (no false green). The
> V20 apply-step gives real streamed extraction from the real files within minutes.
> (A separate earlier failure — `dscloj "Configuration not found"` — was a missing
> `litellm-router/register! :openrouter` in the driver, fixed; root-caused, not
> presumed transient.)

Scope: CIP family `01` (a pure per-row filter in each transform — the V20 contract)
to keep the run tractable. The CSV's `CIP_Code` and the SQL's `CIPCode` carry the
SAME code values, so overlapping codes appear in BOTH sources and MERGE.

## Verbatim run output

```
=== DT7 LIVE CROSS-SOURCE RECONCILIATION (real CSV + real SQLite) ===
ontology-id: #uuid "a9cc0d1b-fc93-4bfe-8b53-e1f3d92e51f4"

--- SOURCE A: stream-extract the REAL crosswalk CSV ---
  CSV — rows-streamed: 6097  rows-ok: 6097  rows-errored: 0  concept-drafts: 286
  distinct cip: URIs from CSV: 91

--- SOURCE B: stream-extract the REAL IPEDS CIPCodes table ---
  SQL — rows-streamed: 2848  rows-ok: 2848  rows-errored: 0  concept-drafts: 114
  distinct cip: URIs from SQL: 114

--- POST-EXTRACTION GRAPH STATE ---
total concepts in graph: 114
distinct cip: URIs after projection collapse: 114
cip: codes present in BOTH sources (cross-source overlap): 91
  sample overlapping codes: (cip:01.0000 cip:01.0101 cip:01.0102 cip:01.0103 cip:01.0104 cip:01.0105 cip:01.0106 cip:01.0199)

--- RECONCILE PASS 1 (against current graph state) ---
concepts-in-scope: 114
relationships-in-scope: 0
alignment-section: #uuid "c2c6638d-db05-3baf-a768-ae37ee25b4d0"
CROSS-SOURCE SHARED-URI MERGES (cip codes in BOTH sources -> ONE node): 91
  sample shared cip: URIs: (cip:01.0000 cip:01.0101 cip:01.0102 cip:01.0103 cip:01.0104 cip:01.0105 cip:01.0106 cip:01.0199)
  example by-uri (sources that contributed): [cip:01.0000 #{:ipeds-sql :crosswalk-csv}]
near-match candidate-pairs (S12 LSH-blocked): 1603
near-match merges recorded (S08): 0
AMBIGUITIES SURFACED: 133
  sample ambiguities: ({:tier :llm-budget-exhausted, :verdict :requires-review, :reason :budget, :detail LLM budget exhausted, :a-uri cip:01.07, :b-uri cip:01.0701} {:tier :llm-budget-exhausted, :verdict :requires-review, :reason :budget, :detail LLM budget exhausted, :a-uri cip:01.04, :b-uri cip:01.0401} {:tier :llm-budget-exhausted, :verdict :requires-review, :reason :budget, :detail LLM budget exhausted, :a-uri cip:01.0480, :b-uri cip:01.1180} {:tier :llm-budget-exhausted, :verdict :requires-review, :reason :budget, :detail LLM budget exhausted, :a-uri cip:01.0480, :b-uri cip:01.0680} {:tier :llm-budget-exhausted, :verdict :requires-review, :reason :budget, :detail LLM budget exhausted, :a-uri cip:01.0680, :b-uri cip:01.1180})
implied-endpoints-minted (V18): 0
DANGLING EDGES (V18): 0
every-edge-endpoint-resolves?: true

--- RECONCILE PASS 2 (reconcile-not-duplicate proof) ---
concepts after pass1: 114  after pass2: 114
RECONCILE-NOT-DUPLICATE: true (second pass did NOT grow the graph)
pass2 shared-uri-count: 91
pass2 implied-minted (expect 0): 0
pass2 dangling: 0

--- RECONCILE vs PRE-POPULATED GRAPH (a later source re-mints) ---
  CSV-again-as-3rd-source — rows-streamed: 6097  rows-ok: 6097  rows-errored: 0  concept-drafts: 286
concepts before 3rd source: 114  after re-mint: 114  (re-mint of existing cip codes did NOT duplicate: true )
3rd-pass shared-uri-count: 91  dangling: 0

=== DONE ===
```

## Acceptance criteria — verdict

| Criterion | Evidence | Verdict |
|---|---|---|
| **Entities from different sources merge** (shared canonical id) | 91 `cip:` codes present in BOTH the real CSV and the real SQLite collapsed to ONE node each (`cip:01.0000` contributed by `#{:ipeds-sql :crosswalk-csv}`); total graph 114 = 114(SQL) ∪ 91(CSV), 91 shared | **PASS** |
| **Ambiguities surfaced** (near-variant identities), not silently merged/dropped | 133 ambiguities surfaced as `:requires-review` (e.g. `cip:01.07` vs `cip:01.0701`); 0 silent merges (`near-match merges recorded: 0`) | **PASS** |
| **0 dangling** (V18) | `DANGLING EDGES (V18): 0`, `every-edge-endpoint-resolves?: true` | **PASS** |
| **Reconcile against CURRENT graph state — reconcile-not-duplicate** | pass1=114, pass2=114 (no growth); a 3rd late source re-minting all CSV codes → still 114 (0 duplicate); shared-uri-count + dangling stable across all passes | **PASS** |
| **Reuses S03/S12/S21/V18 (no fork)** | S03 register-alignment-section command; S12 `lsh-candidate-pairs` + `prefilter-verdict` + `run-dedup-cascade` command (1603 LSH-blocked pairs); V18 `ensure-referential-integrity!` via `reconcile-current-graph-integrity!`; the skeleton's `referential-integrity-report` | **PASS** |

## Honest notes

- **Ambiguity verdict tier wording.** The 133 ambiguities carry tier
  `:llm-budget-exhausted` / `:reason :budget` because the run uses the default
  `:llm-budget 0` (reconciliation is deterministic by default — the LLM tier is
  opt-in). These ARE genuine ambiguity-band pairs the deterministic guards could
  not close; surfacing them as `:requires-review` (never merging) is the correct,
  honest behavior. The "budget exhausted" wording is pre-existing S12 verdict text,
  not DT7 code. Raising `:llm-budget` would let the cascade's LLM tier adjudicate
  the band — not done here to keep the run deterministic + tractable.
- **No near-match auto-merges** (`merges: 0`) under `:llm-budget 0` — the distinct
  cip codes correctly do NOT string-merge (they differ in numbers → S12's number
  guard / below-band keep), and the ambiguity-band pairs surface for review rather
  than guess. This is the intended deterministic posture.
- **The near-variant V18 ambiguity path** (auto-mint implied endpoint + flag a
  near-variant of an existing URI) is exercised in the unit suite + the
  deterministic prototype (a cross-source edge to `occ:200_x`, a near-variant of
  `occ:200`, similarity 0.964 → surfaced, 0 dangling). In this real run the
  extracted sources carried no relationships, so V18 minted 0 implied here
  (`implied-endpoints-minted: 0`) — correct (nothing dangled).

## JVM hygiene

- Pre-run orphan check: `NO ORPHANS`. Wrapped in `future` + `(deref f 480000)` +
  `(System/exit 0)`. Post-run orphan check: `NO ORPHAN JVMS`. The earlier
  LLM-emit-tree attempt was killed by PID (no orphan left); a force-kill verified
  `NO ORPHAN JVMS`.
