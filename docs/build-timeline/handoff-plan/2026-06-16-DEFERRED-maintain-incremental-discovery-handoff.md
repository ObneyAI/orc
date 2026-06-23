# DEFERRED — Maintain / Incremental-Discovery Branch (Pillar 4) — Handoff

**Status:** DEFERRED (do not implement yet). **Created:** 2026-06-16.
**Branch:** `feature/ontology-architecture`. **Local-only** working note — not committed unless the user says so.

A future agent should be able to work from THIS doc + the discovery-tree ADR/PRD
(to be written from the same grill; see "Companion artifacts" below) without
rebuilding context. Self-contained on purpose.

---

## 0. Why this is deferred (and what it is)

We are mid-redesign of ontology discovery: replacing the monolithic open-ended
`run-discovery!` loop + ~400-line mega-prompt with a **cohesive discovery
behavior tree**. We decided to build the **greenfield** (build-from-scratch) path
now — it's all the BRYC head-to-head needs — and to make the design
**maintain-aware** so the **maintain / incremental** branch (Pillar 4) drops in
later without a refactor. THIS handoff captures that deferred maintain build.

**Pillar 4 "maintain"** = discovery must support re-running over updated/new
sources and reconciling against an EXISTING graph, not just building once.

---

## 1. The redesign this belongs to (decided context — the parent design)

Replacing the open-ended `run-discovery!` with a discovery behavior tree. Locked
grill decisions (these are the parent design; the maintain branch plugs into it):

- **Hybrid orchestration** — a fixed core node-sequence (structurally guaranteed
  steps) + RLM-chosen branches at the edges. *Maintain is one of those edge
  branches* (see §3).
- **One tree shape, per-medium tool-leaves** — same steps for every source;
  format specialists (csv/sql/excel/text) differ only in the tools bound at the
  leaves. "Separate tool users, same goal."
- **Three focused reasoning nodes** (per source): (1) **Profile**, (2) **Model +
  grain + scope**, (3) **Transform-design + sample-validate**. Each a small
  focused prompt (this is what kills the mega-prompt), independently testable.
- **Static-now, promotable-later nodes** — node prompts are static focused
  prompts now, but each node's prompt-assembly goes through ONE clean seam shaped
  to later source from `classify-behaviors`/corpus and participate in minting.
  **The user is actively reworking the MINTING process in another terminal** —
  the promotion seam must be a clean interface boundary, NOT coupled to current
  minting internals. (This affects maintain too — see §4 open decisions.)
- **Tree owns the adaptive CQ-loop; `build!` stays an intact deterministic
  sub-call.** The existing skeleton `build!` runs the deterministic spine
  (`normalize → dedup S12 → evidence S13 → validate S10/11 → auto-embed P2 →
  index → CQ S15`) and already surfaces its CQ verdict to the caller:
  `:status` is `:complete | :failed-cq | :failed-validation | :failed-at-<stage>`,
  with `:graph-health` and `:exit-criterion` on the result. The tree reads that
  verdict and branches.
- **Goal upfront; CQs derived AFTER profiling.** The goal/intent orients every
  node from the start, but the formal competency questions (the CQ gate) are
  derived AFTER profiling — grounded in the real source profiles, goal-anchored,
  HITL-reviewable. The CQ-loop distinguishes "re-extract to close the gap" from
  "honestly unanswerable from these sources" and surfaces the latter (the V17
  honest-negative ethos) rather than spinning.
- **Two-level structure:** a GRAPH-LEVEL orchestration (profile-all → derive CQs
  → extract-all → link → CQ-gate → loop) wrapping the PER-SOURCE sub-tree
  (profile → model → transform → extract).

**Reused infrastructure (NOT rebuilt — maintain reuses the same):**
- V06 / V19 source tools — per-medium leaves incl. `count-rows` + `stream-all`.
- V20 deterministic full-extraction apply-step — the transform → full-source
  bridge (streams the whole source via `stream-all`, applies the per-row
  transform, counts per-row errors).
- V18 referential integrity — always-on; auto-mints implied entities + surfaces
  near-variant ambiguities (reuses S12's similarity).
- S12 dedup cascade; S03 alignment registry; S13 evidence/provenance; S21 hybrid
  retrieval (embeddings + ColBERT); S15 CQ runner; S14 ORSD spec store.

---

## 2. Why maintain is mostly already within reach

The reconciliation substrate maintain needs ALREADY EXISTS — maintain is largely
"run the per-source discovery sub-tree against an EXISTING graph and let the
existing machinery reconcile new against old":

- **S12 dedup cascade** — merge new vs existing concepts by URI → embedding →
  LLM judge.
- **S03 alignment registry** — cross-source (and, for maintain, cross-RUN)
  identity alignment.
- **V18 referential integrity** — implied-entity mint + ambiguity surfacing,
  already always-on.
- **S21 embeddings / ColBERT** — near-match for "is this new thing the same as an
  existing thing?".

So the bulk of maintain is orchestration around machinery that already works on
the greenfield path.

---

## 3. The single load-bearing seam to preserve during the greenfield build

**Write the cross-source-linking / reconciliation stage against the CURRENT GRAPH
STATE — read existing concepts/relationships from the projection — NOT assuming an
empty graph.**

If the greenfield build honors this one constraint, the maintain branch becomes:
1. a **front-of-tree greenfield-vs-maintain condition** (the hybrid edge-branch:
   "does a graph already exist for this ontology-id / target?"), and
2. **reusing the same reconciliation stage** — which, because it already reads
   current graph state, naturally merges a fresh per-source extraction into the
   existing graph instead of into an empty one.

That's the whole point of "maintain-aware design now": there is exactly ONE thing
the greenfield implementer must not hardcode (an empty-graph assumption in
linking/reconciliation). Everything else maintain needs is additive.

---

## 4. The genuinely-NEW work maintain needs (the actual maintain scope)

Beyond reusing the above, maintain must add:

- **(a) Change handling / upsert semantics** — new / updated / removed entities
  across re-runs. *Open decision:* when an entity present in the prior graph is
  ABSENT from the new source pass, do we delete it, deprecate/tombstone it, or
  leave it (additive-only)? Default lean: deprecate-not-delete (preserve
  provenance), but decide at build time.
- **(b) Versioning / provenance across runs** — extend the S13 evidence ledger so
  each assertion records which RUN asserted it; a concept/edge can carry evidence
  from multiple runs over time.
- **(c) Idempotency** — re-running an UNCHANGED source must not duplicate or churn
  the graph (no new events when nothing changed). This is the key correctness
  test for maintain.
- **(d) CQ re-gate** — run the S15 CQ gate against the UPDATED graph; a maintain
  run can regress CQ pass-rate (a new source could introduce contradictions), so
  the gate must run post-merge, not just post-extract.
- **(e) Conflict resolution** — when a new run CONTRADICTS an existing fact
  (different value for the same attribute/edge), surface it as an ambiguity for
  the alignment/dedup layer vs auto-resolve by recency/confidence. *Open
  decision.*

---

## 5. Open decisions to resolve AT maintain-build time (do not decide now)

- Deletion / deprecation / tombstone policy for entities absent from a re-run.
- Maintain mode: per-source incremental vs full-resync-with-diff.
- How **minting** interacts with maintain — pending the user's in-flight minting
  rework; the node-promotion seam (static → living behavior) may change, and
  maintain should reconcile minted behaviors/concepts across runs too. Revisit
  once the new minting process lands.
- Conflict-resolution policy (recency vs confidence vs always-surface).

---

## 6. /tdd intent for the eventual maintain build

- Vertical tracer bullets; one test → one implementation; behavior through public
  interfaces.
- **Real-LLM live verify mandatory** before "done"; no false green; root-cause
  everything (no "transient/flaky").
- The **idempotency test** (§4c) is the headline live-verify: re-run an unchanged
  source → zero graph churn; then change one source row → exactly that delta
  appears.
- **Domain/industry-agnostic** (discipline 12) — no vertical tuning; format
  specialists are fine.
- Grain/ORC discipline — all writes via commands → schema-validated events;
  read-models project; no bare event-store appends; recursive-only RLM.
- Branch `feature/ontology-architecture`; one commit per slice; co-author
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## 7. Blocked by

- The **greenfield discovery-tree slices** (to be authored from the same grill)
  must land + be verified first — maintain reuses their reconciliation stage.
- The **user's minting rework** (in flight, another terminal) may reshape the
  node-promotion seam; revisit §5 once it lands.

---

## 8. Companion artifacts (read these alongside this handoff)

- **Discovery-tree ADR + local PRD/issues** — to be written from the in-progress
  grill (the parent redesign). Will live under `docs/build-timeline/` (ADR in the
  ADR location; PRD under `docs/build-timeline/prd/`; slices under
  `docs/build-timeline/issues/ontology-verification/`). Replace this bullet with
  the exact paths once written.
- Greenfield reuse targets already in-repo + verified: V06/V19 (source tools),
  V18 (`d16c5c10`, referential integrity), V20 (apply-step — proven by prototype
  at 22,151 concepts / 0 dangling; see `docs/build-timeline/issues/ontology-verification/V20-scaffolding-and-deterministic-full-extraction.md`).
- The honest-negative ethos + the over-extraction lesson: `docs/build-timeline/live-verify/V17-graph-b-full-scale.md`.

---

## 9. Suggested skills for the future session

- **/tdd** — implement the maintain slices (idempotency test first as the tracer
  bullet).
- **/to-issues** — break the maintain scope (§4) into vertical slices once the
  greenfield tree + the minting rework have landed.
- (If scope/decisions in §5 are still open) **/grill-with-docs** — resolve the
  deletion/conflict/minting-interaction decisions before building.

---

**Do not implement from this handoff.** It exists so the maintain context isn't
rebuilt from scratch later. Pick it up after the greenfield discovery-tree lands.
