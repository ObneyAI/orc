# Category C — Self-Improving Loop for RLM

> **Status:** Ready for slicing (`/to-issues` will produce vertical tracer-bullet slices)
> **Branch:** main (and feature/core-orc-upgrades — currently in sync)
> **Slices:** C-1 (detailed), C-2 / C-3 / C-4 (sketched; sub-grills required before slicing)

## Cross-references to prior work

This PRD builds directly on foundations already on `main`:

- **`docs/prd/rlm-recursive-emit-tree.md` (R-1 + R-2 — DONE):** Provides the `:tree-results` accumulating vector and drill-down primitives (`tree-detail`, `tree-trajectory`, `tree-failures`, `node-output`, `node-input-profile`). These are the **data layer**; Category C is the **learning layer** on top.
- **`docs/prd/phase2-observability-layer.md` (O01-O03 — DONE):** Per-node usage events, `:rlm-tree-node-completed` with `:input-profile`, `:rlm-tree-execution-completed` bookend with `:trajectory`. These are the per-tree-execution evidence that any future automated principle-extractor in C-3 will consume.
- **`docs/prd/category-d-resilience.md` (D-003, D-008 — DONE):** `:partial` status from map-each failures with `:failure-indices`/`:failure-reasons`; budget-aware Phase 2 timeout. These are the failure-mode evidence sources that hand-authored principles in C-1 will reference.
- **`docs/FUTURE-VISION.md`:** Phase 4a (Ontology Knowledge System — Anterior-inspired) is the architectural target. B.3 (Tree-Level Ontology Profile) defines the strengths/weaknesses/domain-knowledge shape. Category C is Phase 4a's first concrete implementation pass.
- **`docs/SELF-LEARNING-MANUAL.md`:** Already-shipped `:ontology/record-tree-strength` and `:ontology/record-tree-weakness` commands plus the `ontology/get-tree-profile` read-model API. These are the storage + retrieval primitives this PRD reuses verbatim.
- **`docs/FEEDBACK-LOOP.md`:** Describes the continuous improvement cycle (execution → evaluation → classification → ontology learning → context retrieval → context injection → improved execution). C-1 wires the *context retrieval + injection* legs into RLM specifically; C-3 wires the *evaluation + classification* legs.
- **`docs/GEPA-INTEGRATION-PLAN.md`:** GEPA's reflection (`gepa/core/reflection.clj`, `ReflectiveExample` builder) and proposer (`gepa/core/proposer.clj`, `propose-new-instruction`) are the reuse candidates for C-3's automated principle extraction. C-1 does NOT touch GEPA; C-3 does.

Critical context: GEPA, the ontology, rolling metrics, and tree profiles are *already built infrastructure* on `main`. Category C is largely a wiring + audit project, not new capability invention. The user's stated discipline applies: **"infrastructure existence is not quality"** — adversarially audit each component before relying on it. C-1's HITL audit is the embodiment of that discipline.

## Problem Statement

After a Phase 2 tree runs in recursive RLM mode, the model has access to its own execution history via `:tree-results` and the R-2 drill-down primitives — but this is only *intra-session* learning. The model designing trees today has no access to what worked or didn't work on similar tasks in prior sessions. Each new repl-researcher tick re-discovers tree-design lessons that the system has already paid the LLM cost to learn.

Concrete pain:

- A developer runs the legal-issue-detection benchmark; the model emits a tree that exhausts sub-LLM rate limits due to unbounded `:map-each` concurrency. They see the failure, debug it, conclude that bounding `:max-concurrency` to 3 would have worked. **The system forgets this lesson**: the next run of the same task class — even by the same developer 5 minutes later — has zero memory of the prior failure.
- The same lesson would generalize across many task classes (any chunked-document extraction at scale). Today nothing in the system captures the lesson at the abstraction level that *generalizes* — only at the level of "this specific run of this specific tree."
- We have the infrastructure (ontology component with strengths/weaknesses/domain-knowledge, GEPA reflection/proposer, tree-profile read model, `build-ontology-examples-section` injection hook in `executor.clj`) but **none of it is wired into the RLM tree-execution feedback loop**. The infrastructure was built for static-workflow optimization, not for the recursive-RLM use case.
- "Principles, not entities" is the load-bearing constraint: the unit of learning must be a *reusable rule* (e.g., "bound map-each concurrency when extracting from chunked text"), not an entity-specific correction ("don't forget Sam Altman as a person entity in the May 15 article"). The latter generalizes nowhere; the former is what makes the system self-improving.

## Solution

A four-slice initiative that wires the existing ontology storage + retrieval infrastructure into the RLM tree-design loop, plus the missing principle-extraction reflection step. Land the data layer first (C-1) with **hand-authored seed principles**, validate the principle shape is actually useful via a rigorous 3-way live-verify, *then* build the automated principle generation (C-3) on a validated foundation.

C-1 — the immediate next slice — ships the storage + retrieval + injection pipeline for hand-authored principles. The model in Phase 1 RLM sees a new "## Principles for this task class" section in its system prompt, populated from hand-authored entries in the ontology read-model. The 3-way live-verify proves the model actually *acts* on the injected principles (not just that the pipe is connected).

C-2, C-3, C-4 are sketched here at the architectural-decision level; each will get its own sub-grill before slicing.

## Proposed Slicing

This PRD breaks into four vertical tracer-bullet slices. Only C-1 is detailed; C-2-4 are sketched.

```
C-1 — Principle storage + retrieval + injection (hand-authored seed)
  └── DETAILED HERE. Acceptance criteria, live-verify design, deliverables enumerated.
      Blocks: nothing. Ready for /to-issues immediately.

C-2 — Automatic task-class classification
  └── SKETCHED. Open decisions remain — sub-grill required.
      Replaces manual :task-class-id assignment with a computed fingerprint.
      Blocks: C-1 (uses C-1's storage layer unchanged).

C-3 — Automated principle extraction + judge audit
  └── SKETCHED. Open decisions remain — sub-grill required.
      Adapts GEPA proposer for RLM principle extraction; adversarial audit of
      existing 4 judges; possibly adds structural (tree-shape) judges.
      Blocks: C-1 (writes into C-1's storage shape).

C-4 — Cross-tree pattern reuse + node-type learning
  └── SKETCHED. Open decisions remain — sub-grill required.
      Per FUTURE-VISION 4a.6 (node-type learning across :llm/:map-each/:code)
      and 4a.10 (LLM pattern discovery for new failure subtypes).
      Blocks: C-1, C-3.
```

## User Stories

### As the LLM running in Phase 1 RLM

1. As an LLM designing trees in Phase 1 RLM, I want to see hand-curated principles from prior task-class work surfaced in my system prompt, so that I can apply lessons that previous sessions learned without rediscovering them.
2. As an LLM, I want principles formatted with explicit "when it applies" context guards, so that I can decide which principles are relevant to my current task before applying any of them.
3. As an LLM, I want principles to carry the "good pattern" as a tree-DSL snippet, so that I can directly imitate the recommended structure rather than translate from natural language.
4. As an LLM, I want principles to be optional context (not commands), so that I can override them with my own reasoning when the principle doesn't fit the specifics of the current task.
5. As an LLM, I want the principles section to be absent when no principles exist for the current task class, so that I don't waste prompt budget on empty headers.

### As the ORC consumer (developer building workflows)

6. As an ORC consumer, I want my repl-researcher node to accept a `:task-class-id` config field, so that I can opt my workflow into cross-session principle reuse.
7. As an ORC consumer, I want to opt out of principle injection by simply not setting `:task-class-id` (and `:principles-fn`), so that legacy callers using `:rlm true` are unaffected.
8. As an ORC consumer, I want to override the default principle retrieval logic via a `:principles-fn` hook, so that I can implement custom filtering, scoring, or composition (e.g., "only show top-3 principles by confidence").
9. As an ORC consumer, I want hand-authoring a new principle to be one command call (`:ontology/record-tree-strength` or `:ontology/record-tree-weakness`), so that I don't need to learn a new API just for RLM principles.
10. As an ORC consumer, I want the principle storage to be the same surface as the existing self-learning system (`ontology/get-tree-profile`), so that I can use the existing ORC tools to inspect what principles exist.
11. As an ORC consumer, I want the live-verify result of C-1 to give me confidence that injecting principles actually moves the model's behavior, so that I trust the system enough to author my own principles for my own task classes.

### As the developer hand-authoring seed principles for C-1

12. As a seed-principle author, I want to assign one stable UUID per benchmark task class once, so that all related principles share a key without me needing to write a classifier.
13. As a seed-principle author, I want to ground each principle in a real benchmark failure (with trace IDs as evidence), so that I can defend why this principle should exist when reviewers ask.
14. As a seed-principle author, I want the encoding to be a small (~10-line) Clojure map per principle, so that authoring 5-10 of them is feasible in one sitting.
15. As a seed-principle author, I want the principle's text to read like a *rule* (not an *entity correction*), so that the principle composes across multiple unrelated entities in future tasks.
16. As a seed-principle author, I want to be able to write *both* "do this" (strengths) and "avoid this" (weaknesses) principles, so that I capture the full failure-and-success picture for a task class.

### As the live-verify operator

17. As a live-verify operator running the 3-way comparison, I want a deterministic baseline failure (Run X) to reproduce in ≥2 of 2 attempts, so that I know the original failure is real before claiming I fixed it.
18. As a live-verify operator, I want Run Y (principle injected) to either resolve the failure OR produce a visibly different tree, so that I can confirm the principle is load-bearing.
19. As a live-verify operator, I want Run Z (wrong `:task-class-id`) to behave like Run X, so that I can confirm task-class matching actually scopes principles correctly — they're not just always-on.
20. As a live-verify operator, I want the comparison output (3 EDN run-records) saved to `development/bench/generalization-results/` (gitignored), so that I can inspect tree-by-tree what the model did differently in Y vs X vs Z.

### As the future C-2 implementer (automatic classifier)

21. As the C-2 implementer, I want C-1's storage schema to be unchanged when I add the automatic classifier, so that I only need to add the classifier — not migrate existing principles.
22. As the C-2 implementer, I want the `:task-class-id` field to keep accepting a UUID, so that I can compute one from task features (`:reads`/`:writes`/instruction text) without changing the consumer API.

### As the future C-3 implementer (automated extraction)

23. As the C-3 implementer, I want C-1 to have validated that the principle shape is useful before I automate generation against it, so that my proposer isn't producing junk into a shape that doesn't help the model.
24. As the C-3 implementer, I want C-1's live-verify scenario to also be reusable as a C-3 evaluation harness (does my automated principle move the model the way the hand-authored one did?), so that I can compare automated vs hand-authored quality.

### As the future judge-quality auditor (plan-doc issue 011)

25. As the judge-quality auditor, I want to know *before* C-3 lands whether the existing 4 rubric-based judges produce signal useful for tree-structure decisions (vs. only output-content), so that I can decide whether C-3 needs to add structural judges or modify rubrics.
26. As the judge-quality auditor, I want my work scoped INTO C-3 as a first-class deliverable (not a post-hoc cleanup), so that the "infrastructure existence is not quality" discipline is enforced where the judges actually get wired to RLM.

### As the maintainer keeping the system honest

27. As the maintainer, I want C-1 to leave the existing `ontology/record-tree-strength`/`record-tree-weakness` command surface unchanged, so that I don't have to coordinate schema migrations across an already-shipped ontology component.
28. As the maintainer, I want the new `:principles-fn` hook to be symmetrical with the existing `:examples-fn` hook (same signature shape, same calling convention), so that the codebase has one mental model for "pluggable RLM prompt-augmentation."
29. As the maintainer, I want the "## Principles for this task class" prompt section to be omitted entirely when no principles apply, so that empty headers don't degrade the model's prompt-following.
30. As the maintainer, I want C-1 to have a documented HITL audit checkpoint (manual inspection of an injected prompt before claiming the slice done), so that we don't claim "principles work" based on the mechanical pipe being connected.

## Implementation Decisions

### Architecture

**1. Three-layer separation is preserved.**
- *Storage layer*: existing ontology event-store + tree-profile read model. UNCHANGED.
- *Retrieval layer*: new `:principles-fn` hook on the repl-researcher node; default impl queries `ontology/get-tree-profile`.
- *Injection layer*: new `build-rlm-principles-section` pure function in the executor; produces a structured prompt block.

This mirrors `build-ontology-examples-section`'s existing pattern for tree examples — keeping principles and examples in independent injection paths so they can evolve separately.

**2. Reuse, don't replace.** No new commands. No new event types. No new schemas. The `:ontology/record-tree-strength` and `:ontology/record-tree-weakness` commands accept domain-agnostic fields exactly so that new domains (like "rlm-tree-design") can be added without schema changes. C-1 exercises that design intent.

**3. Hand-authored seed before automation.** Validated by the user's discipline ("infrastructure existence is not quality") and matched by FUTURE-VISION's seeding decision ("Static Core + LLM Discovery"). Principles get authored against real benchmark failures; we live-verify they move the model; *then* C-3 automates extraction with a validated target.

### Principle encoding (decision-rich snippet from the grill)

Every C-1 hand-authored principle is one `:ontology/record-tree-strength` or `:ontology/record-tree-weakness` command. The decision-bearing shape:

```clojure
{:command/name :ontology/record-tree-strength
 :tree-id <task-class-uuid>                  ; key (forward-compat with C-2)
 :pattern-uri "success:BoundedMapEach"       ; named pattern reference
 :confidence 1.0                             ; hand-authored = full confidence
 :domain-type "rlm-tree-design"              ; new domain value for this PRD
 :context-conditions {:task-class :doc-extraction
                      :input-shape :large-chunked-text
                      :symptom :rate-limit-exhausted}
 :action-taken {:tree-pattern :map-each-with-concurrency-3
                :snippet "[:map-each {... :max-concurrency 3} [:llm {...}]]"}
 :expected-outcome "Avoid sub-LLM rate-limit errors on chunked extraction"
 :evidence-trace-ids [<tick-id-of-failure>]}
```

For weaknesses, the same shape but with `:failure-context` and `:attempted-action` instead of `:context-conditions` and `:action-taken` (per the existing `record-tree-weakness` schema).

### Storage key (decision-rich)

`:tree-id` field in the command body is a **task-class UUID**, not a sheet UUID. The existing command schema accepts any UUID; semantically we're repurposing it as a task-class key. C-2 will populate this UUID computationally via a classifier; C-1 assigns by hand at seed time.

Five UUIDs will be assigned for the existing benchmark task classes: doc-extraction, risk-analysis, contract-comparison, legal-issue-detection, document-analysis. Stored in a Clojure namespace (`development/src/seed_principles.clj` or similar) as named constants.

### Retrieval hook (decision-rich)

New optional config on the repl-researcher node, under the existing `:rlm` map:

- `:task-class-id` — UUID identifying which task class this repl-researcher call is for. Manually set in C-1; computed automatically in C-2.
- `:principles-fn` — optional function `(fn [context] [...])` returning a vec of principle maps. If absent, no principles are injected. If present and returns empty, the "Principles" prompt section is omitted entirely.

The default implementation of `:principles-fn` (provided by the executor when the user doesn't override) does:

```
(fn [_] (let [profile (ontology/get-tree-profile ctx task-class-id)]
          (concat (:strengths profile)
                  (:weaknesses profile)
                  (:domain-knowledge profile))))
```

Users can override for custom filtering (top-N by confidence, semantic similarity, etc.) without changing the executor.

### Injection format (decision-rich)

A new pure function `build-rlm-principles-section` in the executor takes the node + context and returns either nil (no principles to inject) or a markdown-formatted prompt block. The block format:

```
## Principles for this task class

### When extracting entities from chunked documents (3 evidence runs)
**Good pattern**: bound :max-concurrency on :map-each
```clojure
[:map-each {... :max-concurrency 3} [:llm {...}]]
```
**Why**: Sub-LLM rate limits exhaust on unbounded concurrency

### Avoid: sequential summarization of long legal docs (2 evidence runs)
**Prefer**: parallel chunk-summarize → aggregate
```clojure
[:sequence [:chunk-document {...}] [:parallel {... :map-each ...}] [:aggregate ...]]
```
**Why**: Sequential pipeline produces 4x wall-time vs parallel for >10 chunks
```

Each principle's stored fields map to the visible block:
- `:context-conditions` → "When ..." header text
- `:expected-outcome` → contextualization / "Why" line
- `:action-taken.snippet` → the code block content
- `:pattern-uri` → optional reference symbol
- `:evidence-trace-ids` count → "(N evidence runs)" suffix

Strengths and weaknesses are visually distinguished by header style ("When X" for strengths, "Avoid: X" for weaknesses).

### Live-verify scenario (decision-rich)

The 3-way comparison test, all three runs must hold before C-1 is declared done:

- **Run X (control)**: node configured WITHOUT `:principles-fn`. Verify the original benchmark failure reproduces deterministically across ≥2 of 2 attempts.
- **Run Y (principle injected)**: same node config, plus `:task-class-id` set + `:principles-fn` retrieving the hand-authored principle. Pass if EITHER the failure mode is resolved, OR the model's iter-2+ generated tree visibly applies the principle's recommended pattern (verified by reading the iteration history).
- **Run Z (principle injected, wrong `:task-class-id`)**: same as Y but with a deliberately mismatched UUID. Verify the principle is NOT applied; behavior matches X. This proves task-class matching is load-bearing — not the principle being silently always-on.

The 3-way structure rules out the two common false-positive modes:
- "Feature added, kinda works" — Run Z controls for that.
- "The original failure was flaky" — Run X controls for that.

### HITL audit checkpoint (decision-rich)

C-1 has a non-mechanical step: before claiming "live-verify passed," a human must read the formatted prompt that gets sent to the model in Run Y and confirm:

1. The principle reads like a *rule* the model can apply, not noise.
2. The "Good pattern" snippet is valid tree-DSL.
3. The "When ..." context guard is concrete enough to match-or-not-match new tasks.
4. The format doesn't overwhelm the prompt (compare token cost to baseline).

If the human inspection fails any of these, iterate on the format BEFORE re-running the live-verify. This is the practical embodiment of "infrastructure existence is not quality."

### What does NOT change

- The `:ontology/record-tree-strength` and `:ontology/record-tree-weakness` command schemas.
- The `:ontology/tree-strength-recorded` and `:ontology/tree-weakness-recorded` event schemas.
- The `ontology/tree-profiles` read model and its public API (`get-tree-profile`, `find-trees-by-problem`, etc.).
- The existing `:examples-fn` hook or `build-ontology-examples-section` function (the new `:principles-fn` is parallel, not a replacement).
- Recursive RLM mode (R-1 + R-2) — `:principles-fn` works identically in recursive and terminal modes; only Phase 1 prompt-building is affected.
- The GEPA component — C-1 does not touch it. C-3 will.

### Critical project rules called out

1. **Trace bugs to root cause** — no fallback logic, no bypasses. If a principle doesn't move model behavior in Run Y, root-cause whether the format is wrong, the principle text is wrong, the retrieval isn't matching, or the principle was never actually relevant. Don't ship "almost works."
2. **Mocks for dev iteration only; live runs MANDATORY before declaring slice done.** C-1's acceptance criteria require all 3 runs of the live-verify to pass with a real LLM (gemini-2.5-flash for cost).
3. **Never assume infrastructure quality.** The HITL audit checkpoint exists precisely because writing `record-tree-strength` events and getting them back via `get-tree-profile` is the trivial part; the hard part is producing principles that actually teach the model. The audit is first-class, not post-hoc.
4. **Never truncate model-authored output back to the model.** Already enforced for iteration history (just fixed); applies equally here. If a principle's "Good pattern" snippet is long, render it in full — do not abbreviate.

## Testing Decisions

### Good-test criteria

Tests verify externally-observable behavior of the prompt-building and retrieval-hook contracts. Not implementation details. A test should survive any internal refactor that preserves the contract.

For C-1 specifically:

- "When `:principles-fn` is unset, the prompt contains no Principles section" — test verifies the prompt STRING, not the function-call-count.
- "When `:principles-fn` returns N entries, the prompt contains N rendered principle blocks" — test verifies block count by parsing markdown, not by inspecting the internal data structure.
- "When `:principles-fn` returns empty, the prompt is identical to the no-`:principles-fn` case" — test verifies absence, again at the prompt-string level.

### Modules tested directly

1. **`build-rlm-principles-section`** — pure function, takes node + retrieval result, returns prompt-string or nil. Unit tests with fixture data; no LLM, no event store.
2. **The default `:principles-fn` implementation** — pure-ish function (takes context, queries read model, returns vec). Tested with an in-memory event store seeded with known events; verifies the right principles come back for a given `:task-class-id`.
3. **Integration: `execute-repl-researcher-rlm` with `:principles-fn` set** — uses the same with-test-ctx pattern as `recursive_rlm_test.clj`. Asserts the system prompt contains the expected principle block.

### Live verification (MANDATORY)

The 3-way comparison defined above. Uses a real LLM (gemini-2.5-flash), a real event store seeded with the hand-authored principle, and a real benchmark task. Run output saved as EDN to `development/bench/generalization-results/` (gitignored).

This is not optional. Per the project rule "mocks for dev iteration; live runs required before declaring done." A C-1 slice with only passing unit tests is not done.

### Prior art

- Pure-deep-module testing pattern: `recursive_rlm_drill_down_test.clj` (R-2 unit tests with fixture event vectors).
- Integration test with `with-test-ctx`: `recursive_rlm_test.clj` (R-1 integration tests with mock `dscloj/predict`).
- Live-verify script pattern: `development/src/recursive_rlm_drill_down_live_verify.clj` (R-2's adaptive task live-verify).
- 3-way comparison structure: D-003's `d003_live_verify.clj` (`_tight` vs `_generous` variants) is the closest precedent — though only 2-way; C-1's 3-way structure is a deliberate addition.

## Out of Scope

The following are *out of scope for this PRD* (Category C as a whole limits to the 4 slices C-1 through C-4; the items below are not part of any of those slices either):

- **Personality layer** (FUTURE-VISION Theme 7 / Phase 6.3-6.5) — orthogonal. Soul.md and customer-facing message generation.
- **Conversational debugging agent** (FUTURE-VISION Theme 5 / Phase 5) — orthogonal. "Talking to traces" via a chat interface.
- **Skills → Trees pipeline** (FUTURE-VISION Theme 1 / Phase 6.1-6.2) — orthogonal. Compiling user-defined skills into ORC trees.
- **Tree hierarchy graph** (FUTURE-VISION Theme 6 / Phase 3.4) — orthogonal. Parent/child specialization relationships between trees.
- **Rolling-metric threshold alerts and auto-analysis triggers** (FUTURE-VISION Phase 2.3-2.4) — orthogonal to Category C; covered by separate work on the rolling-metrics read model.
- **TTL/SKOS export of the ontology** (FUTURE-VISION 4a.7) — already implemented in the ontology component; no work needed in Category C.

The following are *deferred to later slices within Category C* (sketched in this PRD but not detailed):

- **C-2 — Automatic task-class classification.** Replaces manual `:task-class-id` assignment. Open decisions before slicing: hash-of-features vs. LLM classifier vs. structured tag mapping. Sub-grill required. Does NOT change C-1's storage shape.
- **C-3 — Automated principle extraction + judges audit.** Wires `:tree-results` entries through `evaluation/evaluate-trace`; adapts GEPA's `propose-new-instruction` for principle generation; adversarially audits whether the 4 existing rubric-based judges produce useful signal for tree-structure decisions (plan-doc issue 011). May add structural judges (tree-shape, efficiency). Sub-grill required.
- **C-4 — Cross-tree pattern reuse + node-type learning.** Implements FUTURE-VISION 4a.6 (shared learnings across all `:llm` nodes, all `:map-each` nodes, etc.) and 4a.10 (LLM pattern discovery for new failure subtypes). Ontology-wide retrieval via embedding similarity + graph traversal (the RRF infrastructure already exists). Sub-grill required.

The following are *explicitly out of scope for C-1 specifically* (will likely land in later C-slices but not C-1):

- Programmatic generation of principles from `:tree-results` entries (C-3).
- Automatic computation of `:task-class-id` from task features (C-2).
- Cross-task-class principle retrieval (C-4).
- Confidence updates / impact-score tracking on hand-authored principles (C-3 territory once automated evidence accumulates).
- Pareto-frontier management of competing principles for the same task class (C-3 territory).
- Embedding-based principle similarity (C-4).
- Anti-pattern explicit storage (currently encoded via `record-tree-weakness`'s `:attempted-action`; C-3 may introduce a dedicated counter-example field).

## Risk Register

**R1 — Hand-authored principles may not actually move model behavior.**
Mitigation: Q7's 3-way live-verify forces us to prove they do. If Run Y fails to differ from Run X, we iterate on the principle text + injection format BEFORE claiming C-1 done. The HITL audit checkpoint is the first guard; the 3-way comparison is the second.

**R2 — The `record-tree-strength`/`record-tree-weakness` command surface was originally designed for one example domain (sales-outreach, per SELF-LEARNING-MANUAL). Reusing it for "rlm-tree-design" via domain-agnostic fields may strain the shape.**
Mitigation: `build-rlm-principles-section` is a thin layer. If the field mapping doesn't compose well, we have one place to revise (the injection function), not a full schema migration. If the strain is severe, we revisit C-1's Q4 decision and possibly add a dedicated `:ontology/record-rlm-principle` command — but only after concrete pain emerges.

**R3 — The default `:principles-fn` queries `ontology/get-tree-profile`, a read model. First-call latency may be non-trivial.**
Mitigation: Time the call during live-verify. Per SELF-LEARNING-MANUAL's L2-cache notes, this is likely a non-issue. If it becomes hot, add a memoization layer.

**R4 — C-1's live-verify depends on a real reproducible failure. If recent benchmark runs are all `:success`, C-1 cannot 3-way-verify.**
Mitigation: Scope the failure reproduction INTO C-1 — not as a prerequisite. The failure can be manufactured (e.g., a task with bounded budget that forces `:timeout`, or unbounded `:map-each` on a known rate-limited model) without compromising the verification logic.

**R5 — Cross-slice contract risk.** The principle schema decided in C-1 (Q4, Q5, Q6) is the shape every subsequent slice writes against.
Mitigation: Explicit acknowledgment in this PRD that the shape is *provisional* until C-1's 3-way verify passes. If the live-verify forces a shape change, C-2/C-3/C-4 retrospect accordingly. Better to take the schema-revision pain in C-1 than in C-3 when automated tooling already depends on it.

**R6 — Token-cost regression on every recursive RLM call.**
The new prompt section adds tokens. For a session that emits many recursive trees, this compounds.
Mitigation: Measure prompt-length delta in live-verify. If significant, consider truncation (filter to top-N principles by relevance) at the retrieval layer — never at the injection layer (per the "no truncating model output" rule, principles authored to be shown to the model must be shown in full once chosen).

**R7 — HITL audit may reveal the format is wrong AFTER the 3-way live-verify passes.**
This is actually the desired outcome of an honest audit — but it means C-1 may need 2-3 iterations of format + re-verify before truly landing.
Mitigation: Budget for it. C-1's acceptance criteria explicitly require the HITL audit to pass, not just the 3-way numerical comparison.

## Further Notes

### Why C-1 is detailed and C-2/C-3/C-4 are sketched

The grill that produced this PRD focused depth on C-1 because:

1. C-1 is the immediate-next-slice. C-2/C-3/C-4 have known *direction* but open *implementation decisions* that need their own grills.
2. C-1's storage-and-retrieval contract IS the foundation. All downstream slices depend on it. Locking C-1 well unblocks the rest.
3. The "infrastructure existence is not quality" caveat applied hardest to C-1 because it's the slice that connects the existing ontology component to the RLM use case. C-3's "is GEPA proposer suitable for RLM principle extraction?" question is real and important but only meaningful after C-1 validates the principle target shape.

When `/to-issues` runs against this PRD, expected output:

- C-1: one detailed issue with full acceptance criteria and live-verify scenario.
- C-2, C-3, C-4: three "TBD" stub issues that note the architectural intent + the open decisions + the need for a sub-grill before implementation.

### Why we're starting Category C now (priority context)

Per the user's stated ordering across the recent work:
- R-1 + R-2 were positioned as "a foundational detour before Category C" — not a re-prioritization. With R-1 + R-2 shipped, Category C is the natural continuation.
- The current `:tree-results` data layer is rich (status, partial-summary, failure-reasons, trajectory, input-profile) — the data exists to support principle-extraction; we just need to wire it up.
- The existing infrastructure (ontology, GEPA, evaluation, rolling metrics, tree profiles) was largely built in earlier work but never wired into the RLM tree-execution feedback loop. Category C is exactly that wiring + the missing audit.

### Open decisions deferred to sub-grills

C-2 sub-grill must decide:
- Fingerprint computation strategy: hash-of-features, structured-tag mapping, LLM classifier, or hybrid.
- How input-profile features (from O03) factor into the fingerprint.
- Whether the fingerprint is purely deterministic (same input → same UUID always) or stable-but-relaxed (allow tolerance, e.g., similar but not identical writes-keys still match).

C-3 sub-grill must decide:
- Whether to reuse GEPA's `propose-new-instruction` verbatim, adapt it, or build an RLM-specific proposer alongside.
- Adversarial audit of the existing 4 rubric-based judges (Grounding, InstructionFollowing, Reasoning, Completeness) — do they produce signal useful for tree-structure decisions, or are they too output-content-focused?
- Whether to add structural judges (tree-shape quality, efficiency, redundancy detection).
- When does reflection run? Per-tree, per-session, threshold-triggered, or all-of-the-above.
- What's the relationship between an automated-extracted principle and a hand-authored one in the same `:strengths`/`:weaknesses` list? Confidence-difference? Provenance tag?

C-4 sub-grill must decide:
- Node-type-level principle storage shape — where do "all `:llm` nodes benefit from X" lessons live, vs. task-class-level ones?
- Retrieval composition when multiple sources match (task-class + node-type + similar-task-class via embeddings).
- Whether anti-patterns from `:failure` traces get surfaced separately from `:weakness`-recorded ones.

### Connection to the FEEDBACK-LOOP.md cycle

`docs/FEEDBACK-LOOP.md` describes the 7-stage continuous improvement cycle (execution → evaluation → classification → ontology learning → context retrieval → context injection → improved execution). This PRD's slices map to it as follows:

- C-1 wires stages 5 (context retrieval) + 6 (context injection) for RLM specifically, with stages 1-4 covered by hand-authored seed.
- C-3 wires stages 2-4 (evaluation, classification, ontology learning) with automated extraction.
- C-2 enables stage 5 (retrieval) to operate without manual `:task-class-id` assignment.
- C-4 broadens stage 5 (retrieval) to use cross-task-class and node-type-level signals.

After all four slices land, the full cycle runs for RLM trees with zero human intervention required.
