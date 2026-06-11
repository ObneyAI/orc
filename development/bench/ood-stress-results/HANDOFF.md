# R04 OOD Stress — Handoff for Future Agents

> **Read this first.** The C-Loop-3 R04 verification surfaced a real
> architectural question about how the self-improving loop's classifier
> distinguishes in-distribution from out-of-distribution work. The work
> below is the evidence chain a future agent can pick up to extend, not
> a finished investigation.

## What R04 was supposed to verify

Per `docs/issues/c2d-followups/C-Loop-3-r04-novel-task-fresh-mint-verification.md`:

- Run 21 deliberately-OOD instructions through the R-Inject classifier
- Track how often the model successfully classifies tasks vs falls
  back to fresh-mint
- Verify that fresh-mints produce SUBSTANTIVE behavior bodies (not
  boilerplate)
- Verify that minted concepts persist and resurface on related future
  tasks

## What R04 actually found

### Sweep 1 — baseline (no specialized seeds)

Run dir: `2026-06-09_140413-classify-only/`

| Outcome | Count | Rate |
|---|---:|---:|
| direct-match (≥0.9 confidence) | 14 | 66.7% |
| walk-down fired | 20 | 95.2% |
| fresh-mint | 1 | 4.8% |
| rerank-failure | 1 | 4.8% |

**The only fresh-mint was a reranker failure, not a quality judgment.**
14 of 21 deliberately-OOD instructions force-fit to an existing
tree-class at confidence ≥0.9 with reasoning that was structurally
plausible (per-item processing → ChunkedExtraction; iterative refinement
→ ValidationLoop) but semantically wrong (e.g., haiku-for-security-
findings matched ResearchThenSynthesize at 0.90).

Full per-instruction analysis: `2026-06-09_140413-classify-only/FINDINGS.md`.

### Sweep 2 — with 2 specialized behavioral seeds

Run dir: `2026-06-09_153303-with-specialized-seeds/`

Hypothesis intended to test: **if the corpus included DOMAIN-SPECIALIZED
behavioral seeds (vs the 12 abstract behaviors today), would OOD tasks
classify differently?**

**Methodological finding (read FINDINGS-V2.md for full detail):** the
specialized seeds were emitted under `:scope :behavioral-subtree` which
the indexer routes to a separate ColBERT granularity from what
`classify-task` queries. The OOD orchestrator only invokes
`classify-task` (structural classifier) — `classify-behaviors`
(behavioral classifier) was not called.

The result: the experimental seeds were never queried, the sweep
measured reranker noise on the SAME structural-classifier results as
Sweep 1. The hypothesis was NOT tested by this run.

The corrected experiment requires extending
`c2d_ood_stress_live.clj` to call BOTH `classify-task` AND
`classify-behaviors` per instruction. See FINDINGS-V2.md's "corrected
experiment design" section.

## The architectural question this surfaces

The current 12 behavioral subtree seeds describe SHAPE:

- Research, Extraction, Analysis, Synthesis, Ideation, Design, Critique,
  Validation, Code-building, Transformation, Classification, Investigation

These are reusable across domains BUT they're shape-broad. The reranker
matches structural patterns (per-item processing, draft-then-validate,
hypothesize-then-measure) without seeing domain specialization (what's
being analyzed, what tools/flows are needed, what output schema is
required).

**The hypothesis we surfaced and need to verify-or-falsify:**

Specialized seeds would carry the same shape as their parent abstract
behavior BUT pin domain-specialization. Example hierarchy:

```
Analysis (abstract — current corpus entry)
  │
  ├─ Analysis-of-legal-documents — per-section chunking + citation extraction
  ├─ Analysis-of-source-code — AST traversal + pattern matching
  ├─ Analysis-of-training-progression — load tracking + adaptation modeling
  └─ Analysis-of-game-balance-via-simulation — matchup simulation + adjustment

Validation (abstract — current corpus entry)
  │
  ├─ Validation-of-LLM-output-against-schema — Malli-driven shape check
  ├─ Validation-of-schedule-against-hard-constraints — per-constraint :code check
  └─ Validation-of-property-test-against-invariants — randomized input + invariant assertion
```

Each child preserves the shape (read structured input → emit structured
findings; produce result → check against rules → emit pass/fail) but
specializes the domain (legal text vs source code vs physiology data).

## Where the work is

### Files in this branch

```
development/src/
  c2d_ood_stress_test.clj                  — pure helpers (load-corpus, classify-outcome,
                                              aggregate-metrics, markdown-summary,
                                              persist-run!) — covered by RED-then-GREEN
                                              tests in r03_ood_stress_test.clj
  c2d_ood_stress_live.clj                  — live orchestrator: classify-only sweep over the
                                              21-task corpus, persistence to ood-stress-results/
  c2d_ood_specialized_seeds_experiment.clj — sweep 2 setup: emits 2 specialized behavioral
                                              seeds via :ontology/record-tree-description,
                                              forces ColBERT re-index, re-runs the sweep

development/bench/ood-corpus/
  behavioral-fresh-mint-001/002/003-*.txt  — form-constrained / cross-modal tasks
  code-001 through 004                     — software engineering tasks
  conv-001 through 004                     — conversational/tradeoff tasks
  data-001 through 004                     — data engineering tasks
  domain-001 through 004                   — domain-specialized tasks
                                              (game, recipe, marathon, symphony)
  extra-001, extra-002                     — SANITY CHECKS (legal, contract) that
                                              should direct-match at 1.00

development/bench/ood-stress-results/
  HANDOFF.md                               — this file
  2026-06-09_140413-classify-only/         — sweep 1 (baseline)
    OOD-RESULTS.md                         — auto-generated per-instruction table
    FINDINGS.md                            — hand-authored analysis of sweep 1
    ood-result-<slug>.edn                  — verbatim envelopes per instruction
    ood-combined.edn                       — full corpus + results + metrics
  2026-06-09_<HHMMSS>-with-specialized-seeds/ — sweep 2 (experimental)
    OOD-RESULTS.md
    FINDINGS-V2.md                         — comparison + hypothesis verdict
    (same per-instruction shape)
```

### Existing project artifacts to know about

```
docs/issues/c2d-followups/
  C-Loop-3-r04-novel-task-fresh-mint-verification.md
    — the issue file that scoped R04. Notes the 21-task corpus was
      stashed (stash@{0}); turns out it wasn't, so we hand-authored
      anew here.

components/ontology/test/.../r03_ood_stress_test.clj
  — unit tests for the c2d_ood_stress_test pure helpers. All green.

components/ontology/resources/seeds/behavioral-subtrees.edn
  — the 12 abstract behavioral seeds that ship with the library.
    THIS is what the experimental seeds in
    c2d_ood_specialized_seeds_experiment.clj are testing extension of.
```

### Project-memory notes that frame this

```
project_hierarchical_tree_classes_direction  (2026-05-26)
  — Caller flagged this gap before R04 ran. R04's sweep is fresh
    evidence supporting the direction. Quote: "the flat tree-class
    corpus makes fresh-mint rare in practice. Tentative C-2d:
    walk-down classifier via SKOS broader/narrower."

project_judge_system_unification             (2026-06-03)
  — Judge unification shipped in Gap-1 through Gap-8. R04's evidence
    is INPUT to a possible Gap-9 (judge-grounded classification
    rerank — using judge scores to discriminate between high-shape-
    fit / low-domain-fit corpus entries).
```

## How a future agent could pick this up

### Step 1 — read the two sweep result dirs

Start with `FINDINGS.md` (sweep 1) and `FINDINGS-V2.md` (sweep 2).
Sweep 1's verdict on the classify-task force-fitting OOD content is
solid evidence. Sweep 2's verdict is methodological: the experiment
didn't actually test what it intended to. The per-instruction EDNs
are the source of truth — disagree if the evidence supports a
different read.

### Step 2 — extend the orchestrator before re-experimenting

`c2d_ood_stress_live.clj` needs `classify-behaviors` invocation
alongside `classify-task` to test the user's hypothesis correctly.
The classifier already exists in
`ai.obney.orc.ontology.core.task-classifier/classify-behaviors`. The
extension:

```clojure
(defn- run-classify-one!
  [ctx {:keys [slug instruction]}]
  (let [start (System/currentTimeMillis)
        structural (classifier/classify-task ctx
                     {:task-signature instruction :threshold 0.6})
        ;; NEW: also call classify-behaviors and capture
        behavioral (classifier/classify-behaviors ctx
                     {:task-signature instruction :threshold 0.6
                      :structural-context (:assigned-tree-id structural)})
        elapsed (- (System/currentTimeMillis) start)]
    {:slug slug
     :envelope structural        ; preserved name for compat
     :behavioral-envelope behavioral   ; NEW field
     :outcome (ood/classify-outcome structural)
     :behavioral-outcome (ood/classify-outcome behavioral)   ; NEW
     :elapsed-ms elapsed
     :parent-chain []}))
```

Then re-run with the same 2 specialized behavioral seeds AND check the
behavioral-envelope's top-candidates for their UUIDs.

### Step 2 — decide which path to pursue

The evidence supports one of these forward paths (or a combination):

**Path A — Specialized seeds (hierarchical corpus)**

If sweep 2 shows specialized seeds correctly attracted in-domain tasks
AND didn't attract irrelevant ones, the next slice is **author N
specialized children per abstract behavior**. Sized roughly:

- 12 abstract × ~3-5 specializations each = 36-60 new seed bodies
- Plus the schema work to wire `:parent-behavior` into the descriptions
  read-model + retrieval logic
- Plus updating the consolidator to handle parent-child body promotion
- Plus updating the R-Inject prepend assembler to render hierarchical
  results
- Estimated 1-2 weeks of focused work, dependent on judge-quality
  evidence

**Path B — Judge-grounded rerank discrimination**

If specialized seeds DIDN'T discriminate in sweep 2 (i.e., the reranker
matched them on shape regardless of specificity), the gap is in the
RERANKER's awareness of corpus-coverage. The next slice is **add a
judge that scores classification confidence against actual seed
coverage** — i.e., a meta-judge that says "yes the reranker thinks
this is a 0.95 match, but the matched seed's :representative-uses
don't include the task's domain, so DOWN-WEIGHT this match." This is
adjacent to Gap-7/Gap-7b's per-event judge runtime.

**Path C — Accept the OOD findings as current capability**

Document the limitation transparently in user docs (already done — see
`docs/SELF-IMPROVING-LOOP.md`'s "Current capabilities & limitations"
section). Don't build hierarchy until production traffic surfaces a
specific OOD pain point worth addressing.

### Step 3 — extend the corpus if pursuing Path A

The 21-task corpus is a starting point, not exhaustive. Specifically,
the following gaps are known:

- Multi-step domain tasks (e.g., "build a deployment runbook" — uses
  multiple specialized behaviors composed)
- Vision/multimodal tasks (R04 corpus is text-only)
- Conversational followups (R04 corpus is single-shot)
- Long-running tasks where the model would naturally fan out across
  multiple emit-tree iterations

### Step 4 — run the FULL bench/run! sweep

The classify-only sweep here is fast and answers structural questions
(what does the classifier think?). It does NOT answer behavioral
questions (does the model actually mint? does the model produce a
useful output despite the wrong classification?). To answer those,
run the same 21-task corpus through full `bench/run!` (~20-40 min,
~$5-20 OpenRouter spend). Add a `run-full-sweep!` to
`c2d_ood_stress_live.clj` — the per-instruction wrapper around
`runner/run!` should populate `:r-inject-trace` and capture the
model's actual emitted tree + final outputs.

## Honest framing for users

The CURRENT self-improving loop:
- Works correctly for in-distribution tasks (sanity checks 1.00)
- Produces structurally-accurate-but-semantically-thin classifications
  for OOD tasks
- Has a mint affordance the model rarely uses today (1/21 in our sweep,
  and that was a reranker failure not a quality decision)
- Is documented in `docs/SELF-IMPROVING-LOOP.md` with a CURRENT
  CAPABILITIES + LIMITATIONS section that names this honestly

The corpus of 12 abstract behavioral seeds + 23 structural tree-class
seeds is the alpha-stage starting point. Consumer feedback on what
OOD patterns matter to their workflows will guide which specializations
get authored first (Path A) or whether the judge-rerank discrimination
approach (Path B) is the better leverage point.

## Open questions for the next agent

1. **Does the reranker match on shape regardless of seed specificity?**
   Sweep 2's `FINDINGS-V2.md` should answer this for the 2 specialized
   seeds we tested. If yes, Path B is more important than Path A.

2. **Does the model's downstream Phase-2 behavior compensate for
   wrong-fit prepends?** Full `bench/run!` sweep would answer.

3. **Is fresh-mint suppression today caused by the reranker's
   confidence threshold (0.6) being too low for OOD tasks?** Could
   raise the threshold and see if fresh-mint rate increases. But
   that's a parameter tweak, not an architectural fix.

4. **Are there behavioral patterns OUTSIDE the 12 abstract categories
   the corpus should add as PEER behaviors (not specializations)?**
   E.g., "Form-constrained authorship" (covers haiku-audit, recipe-
   iambic-pentameter, melody-encoding), "Physical-process scaling"
   (covers recipe-scaling, manufacturing scaling). If so, the gap is
   horizontal coverage, not just hierarchical depth.

5. **Does the consolidator have anti-recency safeguards strong enough
   to handle adversarial OOD evidence?** If a few OOD tasks force-fit
   to "Analysis," does the consolidator's reflection step on
   Analysis's body get poisoned? Gap-6 work claims yes but R04
   adversarial conditions weren't exercised through the consolidator
   yet.

## Reproducing the experiment

```bash
# Sweep 1 (baseline)
OPENROUTER_API_KEY=... clj -M:dev -e "
(require '[runner])
(runner/start!)
(require '[c2d-ood-stress-live :as live] :reload)
(live/run-classify-only! (deref @(requiring-resolve 'runner/system-state)))
(runner/stop!)
"

# Sweep 2 (with specialized seeds)
OPENROUTER_API_KEY=... clj -M:dev -e "
(require '[c2d-ood-specialized-seeds-experiment :as exp])
(exp/run-experiment!)
"
```

Each takes ~5-10 min wall-clock and produces a new dated dir under
`development/bench/ood-stress-results/`.
