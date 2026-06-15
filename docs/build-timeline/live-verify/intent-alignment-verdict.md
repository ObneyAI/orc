# Intent-Alignment Verdict — Grills/Architecture vs What We Built

Checks the implementation against the ORIGINAL INTENT from the 3 grill sessions
+ `ARCHITECTURE-ONTOLOGY.md`, using the user's four pillars as the yardstick.
Intent capture (with citations) is in `intent-alignment-checklist.md`; this doc
is the implementation-reality cross-check + verdict.

**The yardstick (user's words):** "take any source like csv, sql, text, etc. and
discover a general ontology … usable for any ontology retrieval. We auto-find
fields that need embedded via embedding model or colbert and these embeddings …
search the graph (BFS + embeddings/colbert late interaction) to discover, learn,
maintain, and access knowledge."

**Headline:** we did NOT lose the goal on most of it — the substrate, the
fused retrieval, the discovery loop, and the discover/learn/maintain/access
lifecycle are all aligned (P3 is actually better than before). But there are
**two real intent-level gaps in the NEW builder path**, and they're exactly the
kind that a rushed rebuild leaves behind:

---

## P1 — Any source → general ontology for any retrieval

**Intent (R1 Q1, ARCH L11-14):** a general-purpose substrate that turns disparate
sources into a unifying structure usable by any application/domain — NOT overfit
to the failure/success/problem self-learning case.

**Reality:**
- The substrate IS general-purpose (event-sourced concept graph; `:custom` scope;
  the self-learning layer is an *implementation on top*, per the frame). ALIGNED.
- The OLD builder handles csv/json/sql/text via the 5 sheets (shipped, untouched).
- The NEW discovery+skeleton path was **live-proven on TEXT only** (S18). The
  skeleton's `parse-stage` has per-`:type` dispatch, but discovery designing a
  good extraction tree for **csv/sql/json is UNVERIFIED**.

**Verdict: ALIGNED in substrate + frame; the NEW path's multi-format generality
is UNPROVEN beyond text.** Not lost — but not yet demonstrated end-to-end for the
non-text sources the goal explicitly names.

---

## P2 — Auto-find fields that need embedding (embedding model OR ColBERT)  ← THE GAP

**Intent (R1 Q2, verbatim):** "fields are reviewed to see if they would be good
to embed … and goes ahead and automatically embeds/late embeds the relevant
fields to make them semantically searchable by default."

**Reality:**
- The auto-detection capability EXISTS and is decent:
  - `ontology/detect-embeddable-fields` (heuristic over a Malli schema),
  - `ontology/analyze-fields-for-embedding` (LLM examines real data samples),
  - `ontology/detect-embeddable-fields-heuristic`,
  - the OLD `evolutionary_builder.clj` wires `:auto-detect-colbert-fields? true`,
    `:colbert-fields nil ;; auto-detect`, `:embedding-fields nil ;; auto-detect`.
- **But the NEW deterministic skeleton (S17) does NOT use any of it.** Its
  `embed-stage` and `index-stage` (`deterministic_skeleton.clj:362-390`) are thin
  delegation: if no `:embed-fn`/`:reindex-fn` is supplied they **skip**; if
  supplied they call the caller's fn verbatim. They never invoke the
  auto-field-detection. So a graph built through the NEW path (discovery →
  skeleton) gets NO automatic embed/ColBERT field selection unless the caller
  hand-wires it.

**Verdict: PARTIAL / NOT-CARRIED-OVER. This is the clearest "lost sight of it in
the rebuild" finding.** The capability the goal names by name lives in the OLD
builder and was not integrated into the new skeleton. Fix: the skeleton's
embed/index stages should default to invoking `detect-embeddable-fields`
(heuristic, free) and, when budget allows, `analyze-fields-for-embedding`
(LLM) — then embed + ColBERT-index the detected fields — rather than skipping.

*(Note on routing: the grills committed to auto-detecting WHICH fields to embed,
and to indexing them for both embedding-similarity AND ColBERT late-interaction.
They did NOT commit to an either/or "embedding-model vs ColBERT" router per field
— both signals index the embed-worthy fields. So "route model vs ColBERT" is not
a missed commitment; "auto-detect + actually embed/index in the new path" is.)*

---

## P3 — Search the graph by BFS + embeddings + ColBERT late-interaction

**Intent (R3 Q5 retrieval primacy, ARCH L76):** primary retrieval is graph BFS +
embedding + ColBERT, RRF-fused.

**Reality:** `retrieval/hybrid-search` fuses graph BFS + embedding similarity +
ColBERT late-interaction via RRF, now with S01 per-source caps, S02 uniform
ontology-id scoping (the R3 BFS-isolation-leak gap — FIXED), and the S21 lexical
signal that bootstraps BFS from a text query. Live-proven in S19.

**Verdict: ALIGNED and IMPROVED.** The one admitted gap from the grills (BFS
scoping leak) was closed; lexical bootstrap was added on top.

---

## P4 — Discover / Learn / Maintain / Access

**Reality:**
- **Discover** — S18 recursive-RLM discovery (live-proven end-to-end) — BUT
  axiom drafts are dropped on ingest (`:axioms-skipped`), so discovered axioms
  don't reach the graph yet.
- **Learn** — R-Inject opt-in + consolidation + behavioral mint (shipped, alpha).
- **Maintain** — dedup cascade (S12, live 12/12) + evidence (S13) + lints
  (S10/11) + CQ health metric (S15, live 18/18). Strong.
- **Access** — hybrid-search + 8 RLM tools (S19) + orientation card (S20).

**Verdict: ALIGNED across all four verbs**, with the discover-axiom-ingest gap.

---

## Net verdict

We did NOT lose the core goal: a general-purpose, event-sourced ontology
substrate with fused BFS+embedding+ColBERT retrieval and a full
discover/learn/maintain/access lifecycle — all live-proven on the axes that
matter. The frame ("general substrate; self-learning is one implementation on
top") holds.

**Three intent-level gaps to close before the new path is the equal-or-better
default for the goal as stated:**
1. **P2 — wire auto-embed/ColBERT field detection into the new skeleton's
   embed/index stages** (today they skip; the capability exists in the old
   builder). *This is the one most worth fixing — it's named directly in the goal.*
2. **P1 — prove discovery on csv + sql** (only text is live-verified), so
   "any source" is demonstrated, not assumed.
3. **Discover-axiom ingest** — stop dropping discovered axioms (`:axioms-skipped`).

(Plus the previously-logged G2 old-sheets baseline — needed to *measure* "better
than the old path", not just assert it.)

None of these are regressions; all are "finish the new path to the full intent."
