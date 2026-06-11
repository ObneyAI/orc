# OOD Stress — Classify-Only Sweep Findings

**Run date:** 2026-06-09 14:04
**Corpus:** 21 hand-curated OOD instructions under
`development/bench/ood-corpus/`
**Mode:** classify-only (wedge invocation; no Phase 2 tree execution)
**Sweep latency:** ~7s mean per instruction (reranker + behavioral
classifier round-trip), ~150s wall-clock total

## The headline

**Fresh-mint fired on 1/21 instructions (4.8%) — and that 1 was a
reranker FAILURE, not a quality decision.** The classifier
force-fits every other OOD task into an existing tree-class at
confidence 0.75-1.00.

| Outcome | Count | Rate |
|---|---:|---:|
| direct-match (≥0.9 confidence) | 14 | 66.7% |
| walk-down fired | 20 | 95.2% |
| fresh-mint | 1 | 4.8% |
| rerank-failure | 1 | 4.8% |

Walk-down and direct-match overlap because most of the 20 walk-downs
landed on a tree-class whose top-1 fitness was ≥0.9. So the picture
is: **the system gave high confidence to a tree-class assignment on
20 of 21 OOD inputs**.

The two SANITY-CHECK tasks (`extra-001-legal-issue-sanity` and
`extra-002-contract-comparison-sanity`) BOTH classified correctly at
confidence 1.00 — the classifier works as designed for
in-distribution tasks. The OOD behavior is the surprise.

## Concrete examples of force-fit reasoning

The reranker is matching on STRUCTURAL shape — "per-item processing,"
"draft-then-validate," "compare two artifacts" — and ignoring the
WHAT (form-constrained creative writing, physiological programming,
musical reduction). It produces mechanically plausible reasoning at
high confidence.

**Example 1 — haiku for security findings → ResearchThenSynthesize @ 0.90**

The reranker reasoned (verbatim):
> "The user's task requires processing a list of 5 distinct findings
> into a vector of corresponding haikus. This matches the 'chunk →
> per-chunk :llm extraction → :aggregate' pattern described in
> ResearchThenSynthesize. Each finding acts as a chunk that requires
> an individual LLM transformation (haiku generation) before being
> aggregated into the final required map structure."

Structurally accurate. Semantically wrong — haiku composition with
form constraints is not what the ResearchThenSynthesize seed
describes. No corpus entry covers "form-constrained authorship," so
the reranker reasonably picked the structurally-closest match.

**Example 2 — marathon training plan → Scheduling @ 0.92**

Marathon training has a sequenced structure across 16 weeks. The
classifier matched it to the Scheduling tree-class. Plausible at the
"things-in-order" level; the physiological-progressive-overload
domain knowledge is invisible to the corpus.

**Example 3 — symphony-to-quartet arrangement → 153f1c69-... @ 0.95**

The same target-id `153f1c69-...` matched haiku-audit (0.90),
defend-controversial-choice memo (0.95), AND symphony reduction
(0.95). One tree-class is absorbing wildly different task types,
indicating either an over-broad seed summary or a behavioral category
the corpus lacks.

## What this finding means

**This is the same gap the existing project memory note
`project_hierarchical_tree_classes_direction` (2026-05-26) flagged:**

> "C-2c-3 surfaced that the flat tree-class corpus makes fresh-mint
> rare in practice."

This sweep is fresh evidence for that memory note, this time across
21 deliberately-OOD inputs. The classifier behavior is honest given
the seed coverage; the coverage itself is the limitation.

## What this finding does NOT mean

This sweep ran ONLY `classify-task` + `classify-behaviors`. It does
NOT tell us:

- Whether the MODEL, presented with the wrong-fit prepend, IGNORES
  the prepend and emits a sensible tree shape for the OOD task
- Whether the model calls `(mint-behavior! ...)` despite the
  classifier's no-fresh-mint signal
- Whether the resulting tree executes successfully on the OOD task
  and produces a reasonable answer

To answer those questions, the same 21 inputs need to be run through
FULL `bench/run!` so the recursive RLM Phase 2 execution and
mint-behavior decisions become observable.

## Acceptance criteria status (per the R04 issue file)

| Criterion | Status |
|---|---|
| All 21 instructions run as R-Inject tasks | ✓ via classify-only |
| Saved EDNs include r-inject-trace shape | ✓ per-instruction EDN under this dir |
| Tracker per instruction (fresh-mint? minted?) | ✓ via OOD-RESULTS.md table |
| At least 3-5 of 21 mint a new behavioral subtree | ✗ 0 via classify-only; pending full bench |
| No force-fit to existing pattern >0.9 when semantics unrelated | ✗ 14/21 direct-match at ≥0.9 on clearly-OOD content |
| Persistence check (minted behavior retrievable on related task) | ✗ pending — no mints in this sweep |
| Minted bodies coherent + principle-shaped | ✗ pending — no mints in this sweep |

## Recommended next actions

The classify-only finding is **strong evidence on its own**. Three
honest paths:

1. **Stop here and treat this as the R04 evidence.** The aggregate
   result (1/21 fresh-mints, 14/21 force-fits at high confidence on
   OOD content) is a defensible answer to "does the system recognize
   OOD inputs?" The answer is: today, no, at the classifier level —
   force-fitting is the dominant behavior.

2. **Run a SMALLER follow-up (e.g., 5 selected tasks) through FULL
   bench/run!** to surface whether the model's downstream behavior
   compensates for the wrong-fit prepend. Cost: ~5 × 30-60s task
   runs + ~$2-5. Answers whether mint-behavior! ever fires in
   practice on OOD content.

3. **Build the hierarchical tree-classes work (C-2d)** the user's
   memory note flagged. Adds behavioral-content-type granularity to
   the corpus so the classifier can recognize "form-constrained
   authorship" or "physiological programming" as missing categories.
   Substantial slice; outside R04's scope.

The honest finding stands regardless of which next path the user
picks.
