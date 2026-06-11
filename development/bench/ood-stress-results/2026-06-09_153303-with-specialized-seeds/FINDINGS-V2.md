# OOD Stress — Sweep 2 (With Specialized Seeds) Findings + Methodological Correction

**Run date:** 2026-06-09 15:33
**Mode:** classify-only sweep over the SAME 21-task corpus, AFTER
emitting 2 specialized behavioral seeds and forcing a ColBERT
re-index.
**Specialized seeds added:**
- `iterative-tuning-via-simulated-feedback` (UUID `6c2e7de5-c880-3f14-8c8d-be9b6dd75db5`)
- `code-investigation-by-hypothesis-ranking` (UUID `2fb78e99-d568-361f-8f58-943185120749`)

## Sweep 2 results compared to Sweep 1 baseline

| Metric | Sweep 1 (baseline) | Sweep 2 (with specialized) | Δ |
|---|---:|---:|---:|
| Total | 21 | 21 | 0 |
| Direct-match (≥0.9 confidence) | 14 | 12 | -2 |
| Walk-down fired | 20 | 20 | 0 |
| Fresh-mint | 1 | 1 | 0 |
| Rerank-failure | 1 | 1 | 0 |

The high-level outcome distribution barely moved. Some per-task
confidences shifted:

| Task | Sweep 1 conf | Sweep 2 conf | Δ |
|---|---:|---:|---:|
| domain-001-game-balance | 0.85 | 0.65 | -0.20 |
| domain-002-recipe-scaling | 0.90 | 0.70 | -0.20 |
| domain-004-symphony | 0.95 | 0.85 | -0.10 |
| conv-002-architecture-recommendation | 0.75 | 0.85 | +0.10 |
| conv-003-junior-debug-strategy | 0.85 | 0.92 | +0.07 |
| extra-001-legal | 1.00 | 1.00 | 0 |
| extra-002-contract | 1.00 | 1.00 | 0 |
| (others) | (unchanged or ±0.03) | | |

The sanity-check tasks both still match at 1.00 — the migration didn't
break in-distribution classification.

## Adversarial check — did the experimental seeds appear?

**They appeared in ZERO of 21 EDNs.** I grepped each per-instruction
EDN for the experimental seed UUIDs (`6c2e7de5...` and `2fb78e99...`)
and found no matches.

Top-candidates surfaced for `domain-001-game-balance` (the task most
specifically targeted by the iterative-tuning seed):

```
target-id 00000000-c1c1-4001-b005-d0c0a0a0a0a5  (risk-analysis seed)
target-id 1c0f6cd8-6187-3d8e-993e-2df630968760  (parallel-classify-aggregate seed)
target-id 24bfab75-94ed-35f8-9323-ec6e33b0440d  (some pre-existing seed)
target-id c1b27691-4a1b-3ee2-bb73-595b155b99be  (some pre-existing seed)
```

The specialized seed I authored specifically to match this task did
not appear.

## Root cause — wrong classifier was being tested

I dug into `todo_processors.clj:293` and found `effective-granularity`:

```clojure
(defn- effective-granularity
  [d]
  (let [scope (-> d :body :scope)]
    (if (= scope :behavioral-subtree)
      :behavioral-subtree
      (:granularity d))))
```

The indexer routes seeds with `:scope :behavioral-subtree` in their
body to a SEPARATE ColBERT metadata granularity — `:behavioral-subtree`
— even though they're emitted via the `:ontology/record-tree-description`
command (which would otherwise put them under `:tree-fingerprint` scope).

`classify-task` (the classifier the OOD orchestrator calls) queries
`:granularity :tree-fingerprint`. Behavioral subtrees live under
`:granularity :behavioral-subtree`. The two pools don't intersect.

**My experimental seeds were emitted, the index re-built, but they
were never QUERIED by classify-task because the OOD orchestrator only
exercised the structural-classifier path.**

## What the sweep DID measure

What I actually observed is reranker non-determinism on the same
structural-classifier path:

- Sweep 1 + Sweep 2 used identical corpus for structural classification
  (23 tree-classes + 12 behavioral seeds, the latter being irrelevant
  to classify-task as shown above)
- The reranker is gemini-3-flash-preview at temperature 0.2 — non-zero
- Confidence shifts of ±0.20 are within the normal reranker variance
  band for borderline matches
- Tasks with clear top-1 matches (sanity checks) stayed pinned at 1.00,
  exactly as expected from reranker stability for in-distribution tasks

## What the sweep did NOT measure — the user's actual hypothesis

The user's hypothesis was: **specialized behavioral seeds attract
their in-domain tasks at high confidence and DON'T attract irrelevant
OOD tasks**. To actually test this, the orchestrator needs to call
`classify-behaviors` (which queries `:granularity :behavioral-subtree`)
and check whether the experimental seeds surface as top-N for the
in-domain tasks.

## The corrected experiment design

A future agent picking this up should:

1. **Extend `c2d_ood_stress_live.clj`** to call BOTH `classify-task`
   AND `classify-behaviors` per instruction, capturing BOTH envelopes
   in the per-instruction result map.

2. **Rerun the experiment** with the same 2 specialized behavioral
   seeds. The check becomes: does `iterative-tuning-via-simulated-
   feedback` appear in the top-3 behavioral candidates for
   `domain-001-game-balance`? Does `code-investigation-by-hypothesis-
   ranking` appear for `code-002` / `code-004`? At what confidence?

3. **Compare classify-behaviors results**:
   - Baseline (12 abstract behaviors)
   - With 2 specialized behaviors added

4. **If the specialized seeds DO attract their in-domain tasks** at
   confidence noticeably higher than the abstract Critique/Investigation/
   Analysis seeds do, the hypothesis is supported. The next slice is
   authoring more specializations across all 12 abstract behavior
   categories.

5. **If the specialized seeds DON'T discriminate** (i.e., abstract
   Critique still wins on game-balance even with iterative-tuning in
   the pool), the gap is in the RERANKER — its scoring prefers
   shape-broad descriptions because those have broader vocabulary
   coverage matching task signatures. The next slice would be
   adjusting the reranker intent prompt to emphasize specialization
   over coverage.

## Combined honest takeaway from both sweeps

| Finding | Evidence | Source |
|---|---|---|
| Structural classifier (classify-task) force-fits OOD content at high confidence | 14/21 direct-match in Sweep 1 | FINDINGS.md |
| Fresh-mint via classify-task only fires on reranker failure, not quality judgment | 1/21 fresh-mint in Sweep 1, was a rerank-fallback | FINDINGS.md |
| Behavioral seeds (specialized or not) are routed to a separate ColBERT scope and don't affect classify-task results | Sweep 2 baseline classify-task vs sweep 1 — confidences shifted within reranker noise | This document |
| The user's hypothesis (specialized behavioral seeds attract in-domain content) WAS NOT TESTED by this experiment | Experimental seed UUIDs didn't appear in any of 21 result EDNs | This document |
| The corrected experiment requires extending the orchestrator to also call classify-behaviors | (out of scope for this run) | This document, "corrected experiment design" |
| Sanity-check tasks (legal, contract) STILL match at 1.00 across both sweeps — classifier works correctly for in-distribution content | extra-001 + extra-002 stayed at 1.00 | OOD-RESULTS.md (both sweeps) |

## Status

- Sweep 1: complete, evidence saved
- Sweep 2: complete, but didn't test the hypothesis as intended
- Corrected sweep 3: not run — handed off to future agent via
  `HANDOFF.md` in the parent results directory
