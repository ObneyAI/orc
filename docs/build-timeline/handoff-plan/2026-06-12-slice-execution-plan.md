---
type: execution-plan
date: 2026-06-12
title: Slice Execution Plan — handoff context, prototype recommendations, sequencing
status: ready
branch: feature/ontology-architecture
references:
  - docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md
  - docs/build-timeline/issues/ontology-rebuild/ (20 slices + README)
  - docs/build-timeline/grill-sessions/ (3 records)
  - docs/build-timeline/research/ (synthesis + deep-dive findings)
  - docs/ARCHITECTURE-ONTOLOGY.md
skills:
  - /handoff — writes per-session handoff to /tmp (NOT committed); summarizes the conversation so a fresh agent picks up
  - /prototype — throwaway runnable code answering a SPECIFIC design question (logic or UI); deleted or absorbed when done
  - /tdd — vertical tracer bullets; one test → one impl through public interfaces
---

# Slice Execution Plan

Each of the 20 slices in `docs/build-timeline/issues/ontology-rebuild/`
will be handed off to a subagent via `/handoff`. This document plans:

1. **Per-slice handoff context** — exactly which decision-source docs
   each subagent must read, plus the one verbatim quote (when there is
   one) that pins the slice's load-bearing constraint
2. **Prototype recommendation** — which slices have a design question
   worth answering with throwaway code BEFORE /tdd, with the question
   stated precisely
3. **Sequencing strategy** — start-anywhere roots, parallelizable
   waves, hard dependencies
4. **Cross-cutting adversarial review checklist** — what every subagent
   must verify against, regardless of slice

## Skill chain per slice

The intended pattern, per slice:

```
/handoff <slice-title>
  → fresh agent loads handoff + the listed context docs
  → (optional, when prototype-worthy) /prototype <design question>
    → throws-away runnable code; the answer captured here in this plan
  → /tdd → vertical implementation against acceptance criteria
  → live verify per the disciplines block
  → audit-by-path commit
```

## Sequencing strategy

### Wave 1 — parallelizable roots (no inter-slice blockers)

These can all start immediately, each as its own handoff:

- **S01** per-source RRF caps (independent retrieval fix)
- **S02** uniform ontology-id scoping (correctness foundation)
- **S04** labels/datatypes/annotations
- **S05** quantities + sequences
- **S06** edge metadata
- **S07** axioms-as-data
- **S10** lint registry core
- **S14** ORSD spec storage
- **S16** extraction bench harness (HITL — needs user review cycle)

### Wave 2 — unlocked by Wave 1

- **S03** alignment registry — after S02
- **S08** equivalence events — after S03
- **S09** TTL round-trip gate — after S04–S08 (the bundle this gates)
- **S11** full lint set + SHACL export — after S10 + S07
- **S12** dedup cascade — after S07 + S08
- **S17** deterministic skeleton — after S12 + S10 (uses S16 for no-regression check)
- **S19** RLM ontology tools — after S02

### Wave 3 — final dependencies

- **S13** evidence Tier-1 — after S12
- **S15** CQ evaluation runner — after S14 + S19
- **S18** RLM discovery + seeds (HITL, G2-gated) — after S17 + S19 + S16
- **S20** orientation card — after S19 + S07 + S14

### Recommended first handoff: **S02**

- Highest correctness payoff (closes the isolation leak the Cat 3+4
  agent verified)
- Unblocks S03 → S08 → equivalences chain AND S19 → S20 agent path
- Worth prototyping (see below) — sets a good precedent for the
  prototype→/tdd discipline early in the arc

## Cross-cutting handoff context (give to EVERY subagent)

Every slice handoff must direct the receiving agent to read, in order:

1. The slice file itself (the disciplines block at its bottom is binding)
2. `docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md` —
   the slice's parent module + cross-cutting invariants section
3. `docs/ARCHITECTURE-ONTOLOGY.md` — frame and vocabulary

Plus the slice-specific extras called out below.

## Cross-cutting adversarial review checklist (every subagent uses this)

Before declaring any slice done, the subagent must self-review against:

- [ ] Did I diagnose every unexpected behavior to root cause, or did I
      explain something away as "transient" / "flaky" / "model variance"?
      (The disciplines block explicitly forbids the latter.)
- [ ] For each behavior I claim works, did I ask "how could this pass
      while still being wrong?" and write a test for that failure mode?
- [ ] Did I add explicit debug instrumentation during investigation, and
      is it captured in the commit (kept or deliberately removed with a
      note)?
- [ ] Are my live-verify runs against real services (Grain event store,
      real LLM, real ColBERT where applicable), with outputs captured —
      not synthesized fixtures?
- [ ] Did I fix root cause, or did I add a fallback that masks a bug?
- [ ] Are all writes commands → schema-validated events? No bare
      event-store appends?
- [ ] Are any RLM nodes I touched in recursive mode (no terminal)?
- [ ] Are quality gates judge-based / ontology-based / Malli-based — no
      hardcoded phrase lists?
- [ ] Have I checked the saved-memory disciplines that apply to the
      project (memory directory; especially the verification-live-runs,
      debug-to-root-cause, no-truncating-model-output,
      hand-authored-needs-real-task-verification rules)?

---

# Per-slice handoff plans

## S01 — Per-source RRF caps before fusion

- **Handoff context:** slice file, PRD §M10, the round-2 research doc
  C9 entry (small, the directly relevant paragraph)
- **Prototype:** NOT NEEDED. The cap-then-fuse pattern is mechanical;
  test the failure mode (over-expanding signal drowning others) with a
  unit fixture
- **Risk:** low — independent fn signature addition with default
- **Live verify:** one real hybrid-search run with per-signal pool sizes
  logged

## S02 — Uniform ontology-id scoping (FIRST RECOMMENDED)

- **Handoff context:** slice file, PRD §M1, grill round 3 Q1 verbatim,
  the Cat 3+4 deep-dive finding ("graph BFS does not respect
  ontology-id boundaries" — the load-bearing discovery)
- **Prototype: YES — recommended.** Design question: *"Does scoping the
  BFS layer break existing internal callers that rely on the silent
  cross-section behavior?"* Build a runnable probe that constructs
  two-section corpus, calls every BFS-using fn (find_self_patterns,
  hybrid_search, link-expansion paths in retrieval.clj), and prints what
  changes under (a) BFS-scoped default, (b) explicit `:ontology-ids`
  widening. Captures: any unexpected callers + the shape of fusion
  result-set under widening
- **Risk:** MEDIUM — the BFS code currently assumes a merged graph; the
  prototype tells you whether other consumers (R-Inject classifier?
  living-description corpus?) depend on the leak
- **Live verify:** real corpus with seeded two sections; scoped +
  multi-section hybrid-search runs with per-signal candidate logging

## S03 — Alignment-section registry + auto-widening

- **Handoff context:** slice file, PRD §M1, S02's prototype findings if
  any
- **Prototype: SOFT — useful but optional.** Design question: *"Does
  widening transitively chase alignment-of-alignment, and how do cycles
  break?"* A tiny in-memory probe with a fixture registry that traverses
  alignments — answer the cycle/transitive question with code before
  baking it into events
- **Risk:** LOW-MEDIUM — the cycle question is the only sharp edge
- **Live verify:** real two-section + alignment-section corpus; logged
  widened-id sets per query

## S04 — Labels / datatypes / annotations

- **Handoff context:** slice file, PRD §M2 (parts 1, 2, 8), Cat 5
  representation gap-table row for "language-tagged labels" and
  "annotations" (CONVENTION-ONLY today)
- **Prototype:** NOT NEEDED. Mechanical additive schema work
- **Risk:** LOW
- **Live verify:** capture a real extraction that produces a multi-lang
  label (build with a de/en source) and verify the round-trip

## S05 — Quantities + sequences

- **Handoff context:** slice file, PRD §M2 (parts 3, 4), Cat 5 gap-table
  rows ("QUDT-style quantities" = ABSENT; "ordered sequences" = ABSENT),
  the Q4 round-3 confirmation
- **Prototype: YES — recommended.** Design question: *"What's the
  minimal `{:value :unit}` shape that integrates cleanly into existing
  retrieval result payloads without breaking consumers expecting bare
  numeric attributes?"* Quick runnable probe: extract a fixture
  document with one quantity-with-unit + one unitless number, project,
  query, inspect the result shape. Iterate the schema until the call
  sites are clean.
- **Risk:** MEDIUM — touches the concept-attribute serialization path
  which the retrieval layer reads
- **Live verify:** real source containing both quantities + ordered
  steps; full capture

## S06 — Edge metadata schema'd + serialized

- **Handoff context:** slice file, PRD §M2 (part 5), Cat 5 gap-table
  row ("`:properties` open bag never serialized" — the load-bearing
  failure)
- **Prototype: YES — recommended.** Design question: *"RDF-star vs
  reified-on-demand for edge metadata in TTL export — which does our
  serializer cleanly support, and which do downstream consumers
  (pySHACL, GraphDB) parse correctly?"* Hand-build a 3-edge sample with
  metadata, emit both shapes, load each into pySHACL — report which one
  works
- **Risk:** MEDIUM — choice affects S09's round-trip equivalence test
- **Live verify:** real extraction edge with evidence quotes captured

## S07 — Axioms-as-data events + OWL export

- **Handoff context:** slice file, PRD §M2 (part 6), grill round 3 Q3
  derived-edges decision (transitive predicates → traversal-time
  closure; chain definitions stored as axiom events), the round-2 Q3
  formality ceiling decision (axioms-as-data + lint inputs, NEVER a
  reasoner)
- **Prototype: YES — recommended.** Design question: *"How do
  transitive-marked predicates compose with the existing BFS decay
  closure for broader/narrower?"* Probe: write a tiny fixture with one
  transitive-marked custom predicate, expand neighborhood from a seed,
  inspect the closure. Verify that broader/narrower behavior is
  unchanged AND that the new transitive predicate is followed
  identically. Catch interactions BEFORE writing tests.
- **Risk:** HIGH (interacts with BFS) — prototype mitigates
- **Live verify:** real graph with seeded axioms + transitive
  predicates; expansion behavior captured and adversarially reviewed
- **Important:** the "no inference" non-goal test (a fixture that WOULD
  reclassify under OWL semantics must remain unchanged) is the
  load-bearing correctness check — write it FIRST

## S08 — Equivalence events with `:kind`

- **Handoff context:** slice file, PRD §M2 (part 7), grill round 3 Q4
  (the equivalence-kind distinction — sameAs ≠ equivalentClass for
  inheritance reasons), Cat 5 row ("sameAs/equivalence between URIs"
  ABSENT)
- **Prototype:** NOT NEEDED. Schema + event + projection + 3 OWL
  predicates; mechanical
- **Risk:** LOW-MEDIUM — the OWL output distinction is what the
  adversarial test pins
- **Live verify:** equivalence emitted across alignment-section
  boundary; OWL export inspected

## S09 — TTL ingestion + G1 round-trip gate

- **Handoff context:** slice file, PRD §M3, grill round 3 Q2 verbatim
  (the user's events-first round-trip quote — CRITICAL — include this
  in the handoff verbatim), the round-3 grill record
- **Prototype: YES — CRITICAL.** Design question: *"What
  canonicalization makes `ingest(ttl) → export ≈ source` robust to
  blank-node ordering, lexicalization, and prefix expansion — and what
  does a 'root-cause-ready' diff actually look like when triples
  differ?"* The prototype IS the gate's design surface: hand-build a
  small TTL with every bundle feature, ingest naively, export,
  hand-write the canonicalizer + diff. Without this, the test is
  written against an unknown target — and a gate that lies is worse
  than no gate.
- **Risk:** HIGH — load-bearing for the entire representation arc
- **Live verify:** ingest a real published ontology excerpt; G1 round-trip
- **Note:** the prototype's deletion is the test harness's birth — the
  canonicalizer + diff format absorbed into S09's test infrastructure

## S10 — Lint registry + EDN-SHACL interpreter core

- **Handoff context:** slice file, PRD §M4, grill round 3 Q2 (b′ EDN
  bridge with `:code` escape hatch), Cat 1 agent finding (the SHACL
  construct catalog + lint expressibility mapping — verbatim, since it
  shapes the interpreter subset)
- **Prototype: YES — recommended.** Design question: *"Does the EDN
  shape's ergonomics survive contact with 3 real lints (one cleanly
  expressible, one needing sh:not, one `:code` escape) — and does the
  interpreter's traversal feel right against a real seeded graph?"*
  Build 3 hand-authored shapes (dangling endpoint, naming convention,
  one `:code` lint) and run them against a fixture graph. Iterate the
  EDN shape + interpreter pattern + violation event shape together.
- **Risk:** HIGH — the EDN-SHACL bridge is novel; ergonomics problems
  caught now save rewrites later
- **Live verify:** real extracted graph, registry runs, violation
  events emitted and queried

## S11 — Full built-in lint set + SHACL export

- **Handoff context:** slice file, PRD §M4, S10's prototype findings
  (locks the EDN shape), Cat 1 finding (lint mapping table — which
  lints need sh:sparql, which need `:code`)
- **Prototype: SOFT — useful at the SHACL-export boundary.** Design
  question: *"Does our SHACL TTL export parse correctly under pySHACL
  and GraphDB SHACL?"* Quick: export one registry, load into pySHACL,
  validate one graph, compare verdicts to ours. Catches export-format
  problems before assertion-level tests
- **Risk:** MEDIUM — external-validator interop is the open question
- **Live verify:** consumer-authored shape exported, externally
  re-validated, verdicts match

## S12 — Dedup cascade + check-before-mint

- **Handoff context:** slice file, PRD §M5, round-2 Q5 (full cascade
  decision + the "differ in number/negation/entity → KEEP" rule),
  Cat 3+4 finding (entity-resolver.clj hook location + QUDT gap
  evidence), hindsight/graphify research synthesis (round-2 C-bucket
  mechanisms)
- **Prototype: YES — CRITICAL.** Design question: *"Does the full
  cascade actually catch the adversarial near-duplicate cases (number
  variants, negation pairs, distinct entities sharing surface forms,
  disjoint-class pairs) AND not over-merge — at acceptable LLM cost?"*
  Build the cascade as throwaway, run it against a hand-authored
  adversarial pair set (≥20 pairs covering each failure mode), inspect
  every verdict + the per-tier cost. Tune the LLM prompt's
  number/negation/entity rule until verdicts are right. Without
  prototyping this, the cascade's correctness is a synthetic-pass risk.
- **Risk:** HIGH — adversarial-quality discipline depends on this
- **Live verify:** real multi-source build; verdict log per tier per
  candidate adversarially reviewed
- **Note:** prototype outputs (verdicts + LLM-prompt iterations)
  become the test fixtures for /tdd — absorbed, not deleted

## S13 — Evidence Tier-1

- **Handoff context:** slice file, PRD §M6, round-2 Q4 corrected
  Tier-1 / Tier-2 split, S12's cascade fixtures (the compare-to-existing
  path Tier-1 rides)
- **Prototype:** NOT NEEDED — mechanical, rides S12's plumbing
- **Risk:** LOW
- **Live verify:** real two-source build over overlapping content;
  evidence + contradiction output captured and reviewed

## S14 — ORSD spec storage

- **Handoff context:** slice file, PRD §M7, grill round 3 Q6 (zero new
  machinery — descriptions pattern), descriptions-event implementation
  in core/commands.clj as the precedent
- **Prototype:** NOT NEEDED. Mirrors a known-good pattern (descriptions
  events) — point the implementer at it explicitly
- **Risk:** LOW
- **Live verify:** record spec, retrieve current + history, project
  read-model

## S15 — CQ evaluation runner (judge-based)

- **Handoff context:** slice file, PRD §M7, grill round 3 Q5 verbatim
  (retrieval primacy + three-layer negation posture), Cat 6 capability
  map (especially the negation-MISSING finding), live-verify discipline
  from the project's memory
- **Prototype: YES — CRITICAL.** Design question: *"Do real LLM
  judgments over a fetched evidence neighborhood actually distinguish
  answerable / unanswerable / negation-shape CQs with grounded
  evidence-uris — and where's the boundary where the judge widens vs
  reports the bound?"* Hand-write 5 CQs over a seeded graph (lookup,
  count-ish, negation, contradiction-tolerance), run the judge with
  real LLM, hand-review every verdict + every cited evidence URI.
  Iterate the judge prompt until verdicts are correct and grounded.
- **Risk:** HIGH — judge quality IS the product
- **Live verify:** ≥5 real CQ evaluations against a real built graph,
  adversarially reviewed; pass-rate history projection sanity-checked
- **Note:** prototype CQs become the test corpus

## S16 — Extraction bench harness (HITL)

- **Handoff context:** slice file, PRD §M8/Testing, round-2 A6
  prerequisite-gate decision, the existing development/bench/ harness
  patterns as scaffolding precedent
- **Prototype: YES — CRITICAL (and HITL anyway).** Design question:
  *"Do the precision/recall metrics actually detect failure modes
  (missed concepts, hallucinated relationships, wrong endpoints) on a
  deliberately-broken produced graph — or do they score it acceptably?"*
  Build ONE source + expected graph + a hand-broken produced graph,
  score, inspect every match/mismatch. If the metric scores the broken
  graph "fine," fix the metric BEFORE writing the harness for real.
- **Risk:** HIGH — a metric that doesn't detect failures invalidates G2
- **HITL surface:** the prototype IS where the user reviews + signs off
  the expected-graph ground truth and the metric's sensitivity
- **Live verify:** old-sheet baseline scored on real bench runs and
  committed

## S17 — Builder deterministic skeleton

- **Handoff context:** slice file, PRD §M8, S12 + S10 (the integrated
  pieces), round-2 A6 hybrid decision
- **Prototype: SOFT.** Design question: *"Does the discovery-phase seam
  shape constrain S18's recursive-RLM in any way we haven't anticipated?
  What does its contract look like as a fn signature?"* Quick: stub the
  seam with the existing discovery logic plugged in; verify the seam's
  contract (inputs, outputs, side-effect expectations) is RLM-friendly
- **Risk:** MEDIUM — the seam contract is load-bearing for S18
- **Live verify:** full real build through the skeleton with existing
  discovery; no-regression bench check

## S18 — Recursive-RLM discovery + seed corpus (HITL, G2-gated)

- **Handoff context:** slice file, PRD §M8, round-2 A6 Path 2 sequencing
  (seeds-not-hand-edits), round-2 Q1 corrected R-Inject boundary,
  recursive-only RLM memory (project_recursive_only_direction.md), the
  no-truncating-model-output discipline (must capture full discovery
  trees), descriptions-self-contained discipline for seed bodies
- **Prototype: YES — CRITICAL (and HITL anyway).** Design question:
  *"Does hand-authored seed body N + recursive-RLM discovery on bench
  source M produce an extraction tree that's better than the
  old-sheet baseline AND adversarially-review-clean on output
  quality?"* This is the riskiest slice; prototype path: (a) hand-author
  2-3 candidate ontology-discovery seed bodies, (b) run recursive-RLM
  with R-Inject OFF on a small bench source, (c) capture the generated
  discovery tree verbatim (NO truncation per memory), (d) inspect
  adversarially: do the closure axioms appear when source enumerates?
  Are roles modeled as roles? Are units captured? Iterate seed bodies
  until output quality is correct, THEN measure G2 bench score.
- **Risk:** HIGHEST in the initiative
- **HITL surface:** prototype iterations + final seed bodies + bench
  comparison all user-reviewed
- **Live verify:** real bench runs across all formats; G2 must be met
  with user sign-off; budget-controls instrumented

## S19 — RLM ontology tools (builder-facing subset)

- **Handoff context:** slice file, PRD §M9, grill round 3 Q5 + D11
  resolution, Cat 6 capability map (which tools fill the SPARQL-feature
  gaps), existing sandbox-primitive patterns in rlm_sandbox.clj
- **Prototype: YES — recommended.** Design question: *"Can a recursive-
  RLM session with NO other context use each tool correctly from its
  docstring + worked example alone?"* For each tool, hand-write the
  docstring + a 1-line example, then run a small RLM task that requires
  using that tool. Adversarially review the model's tool-use pattern —
  did the docstring orient it correctly? Iterate docstrings until tool
  use is clean. The docstring IS the design surface.
- **Risk:** MEDIUM — tool-design problems caught now save corpus-pollution
  problems later (if S18's seed corpus is authored against confusing tools)
- **Live verify:** real recursive-RLM session using ≥4 tools; transcript
  reviewed for tool-use quality

## S20 — Graph orientation card (deterministic skeleton)

- **Handoff context:** slice file, PRD §M9, D12 resolution in round-3
  grill record, the existing reindex-trigger machinery
- **Prototype: YES — CRITICAL.** Design question: *"Rendered against a
  REAL seeded graph (≥50 concepts), does the card actually orient a cold
  reader on what the graph is, what's in it, and how to query it — or is
  it just stats?"* Build the four layers, render against a real graph,
  hand-review by reading the card cold (no other context) and asking
  "could I form a good first query from this?" Iterate the
  content-sample algorithm (top-N by degree vs by evidence-count vs
  hybrid; representative neighborhoods chosen how?) until orientation
  quality is correct.
- **Risk:** MEDIUM — content-sample quality is the load-bearing axis
- **Live verify:** real RLM session where the captured transcript shows
  the model USING card-derived information

---

# Summary: where /prototype matters most

| Slice | Why prototype | Question to answer |
|---|---|---|
| **S02** | Unknown internal callers depend on the leak | Does scoping break consumers? |
| **S05** | Schema shape ergonomics affect retrieval call sites | Minimal value+unit shape that doesn't break consumers? |
| **S06** | TTL export format is a downstream-interop choice | RDF-star vs reified — which parses externally? |
| **S07** | BFS interaction with new transitive markers | Does broader/narrower behavior survive? |
| **S09** | THE round-trip gate's design surface | What canonicalization + diff format makes G1 useful? |
| **S10** | Novel EDN-SHACL bridge ergonomics | Do 3 real lints feel right in this shape? |
| **S12** | Adversarial dedup quality at acceptable cost | Does cascade catch each failure mode + not over-merge? |
| **S15** | Judge quality IS the product | Do real verdicts distinguish CQ shapes with grounded evidence? |
| **S16** | A metric that lies invalidates G2 | Do precision/recall detect deliberately-broken graphs? |
| **S18** | The riskiest slice; output quality is the gate | Does seed body N produce adversarially-clean trees beating baseline? |
| **S19** | Tool-design propagates into S18's seeds | Can RLM use each tool from docstring alone? |
| **S20** | Orientation quality is subjective | Can a cold reader form a good first query from the card? |

Soft / situational: S03, S11, S17.

Mechanical (no prototype): S01, S04, S08, S13, S14.

# What stays at the build-timeline level (not duplicated per-slice)

- The full PRD is referenced, not duplicated
- The architecture doc is referenced, not duplicated
- Grill records are referenced by date + section
- The agent findings in `2026-06-12-class-deep-dive-categories.md`
  remain the source of truth for code-grounded evidence

Per the /handoff skill's own rule: handoffs do not duplicate durable
artifacts. They orient + delegate.
