# PRD — Graph-grounded modeling + order-independent reformation

**Design:** [`../grill-sessions/wide-stats-observation-modeling.md`](../grill-sessions/wide-stats-observation-modeling.md) · **ADR:** [`../../adr/0001-mention-log-rederived-view.md`](../../adr/0001-mention-log-rederived-view.md)
**Supersedes:** the GC-16 measure-explosion guardrail (reverted — it regressed clean sources and could not converge).

## Problem Statement

When the ontology builder ingests a WIDE DENORMALIZED STATS source — a spreadsheet where each row is qualified by several dimensions and carries many numeric columns (the pseo earnings sheet: institution × program × cohort × geography × industry, ~90 measure columns) — it mis-models the statistics as a new ENTITY per row. The result is a cross-product explosion (~6000 concepts from a tiny sample) that crashes the build and fragments the graph. A person building the graph gets either a crash or a garbage graph, and cannot get the earnings data that a fair head-to-head against the hand-built reference graph requires.

The naive fix (a deterministic checker that detects "too many numeric columns" and rewrites the plan) does not work: it cannot tell a genuine per-row explosion (pseo earnings) apart from a LEGITIMATE multi-attribute entity (an O\*NET occupation that really does have dozens of numeric skill ratings). It fired on the good sources too, regressing them, and its success was a coin-flip across model runs.

Deeper still: the correct modeling of a stats source DEPENDS on what the graph already contains (are these earnings a feature of a program that already exists?), which DEPENDS on the order sources are processed. The same data modeled before vs after the programs source should end up the same, but today it does not.

## Solution

From the builder-operator's perspective: **the builder models new data logically against the graph it has already built, and it keeps adapting the graph as more sources arrive, so the final result is correct and independent of source order.**

Concretely:
- A stats source's numbers are recognized as **measures of a grain**, not new entities. When they are qualified by more than one subject dimension (pseo earnings), they become **Observation** nodes (one per grain-tuple) whose entity-dimensions (program, institution) link to the entities that already exist, and whose numbers are attributes. When they are qualified by a single subject (O\*NET occupation), they stay **flat attributes** of that entity. This distinction — *reify iff multi-subject-qualified* — is the discriminator the old checker lacked.
- The builder makes this call by giving the **Model a preview of the graph so far** (its schema + a sample) plus the grain principle, and letting it decide — rather than a blind checker rewriting the plan afterward.
- The builder stores every extraction as an immutable **mention** and treats the graph (including whether something is an entity or an attribute) as a **re-derived view** over those mentions. As new sources arrive, the affected part of the graph is **re-derived**, so an earlier mis-modeling self-corrects and the final graph is the **same regardless of source order**.

## User Stories

1. As a builder-operator, I want a wide stats source (pseo) to model its numbers as measures of the right entity, so the build does not explode or crash.
2. As a builder-operator, I want the earnings data to actually LAND (as queryable native-number values), so I can answer financial/outcome questions and compare fairly against the reference graph.
3. As a builder-operator, I want O\*NET (a legitimately multi-numeric entity) and the other clean sources to be UNCHANGED by this work, so nothing that already worked regresses.
4. As a builder-operator, I want the Model to see the schema already built when it models a new source, so it attaches to existing entities instead of minting duplicates.
5. As a builder-operator, I want earnings that are qualified by cohort/industry to keep that qualification (which program, which cohort), so the data is not silently flattened and lost.
6. As a builder-operator, I want the same graph whether I ingest pseo before or after IPEDS, so my results do not depend on an arbitrary source order.
7. As a builder-operator, I want an earlier modeling mistake (earnings minted as entities when pseo ran first) to self-correct once the programs source arrives, without me re-running from scratch.
8. As a builder-operator, I want the builder to never silently drop data when it re-forms the graph, so a re-derivation is safe.
9. As a downstream consumer, I want to ask "what are the earnings outcomes for graduates of this program?" and get an answer grounded in real Observation nodes linked to the program.
10. As a downstream consumer, I want measures attached at the correct grain (program-cohort), so I can distinguish a program's 1-year vs 5-year earnings.
11. As an evaluator running the head-to-head, I want graph B to carry pseo earnings so the financial vertical of the B-vs-A2 comparison is real, not a known gap.
12. As a builder-operator, I want an honest measurement of how often the builder mints a new entity type vs reuses an existing one, so "did the fix work?" is a number, not a vibe.
13. As a maintainer, I want the entity-vs-attribute decision to be domain-agnostic (no baked pseo/O\*NET column names), so it generalizes to any wide stats source.
14. As a maintainer, I want the re-derivation bounded to the affected region, so the build stays tractable at full scale.
15. As a maintainer, I want each slice live-verified on real Grain + real LLM + real sources before it is trusted, so unit-green does not mask a real-data failure (the trap the prior attempt fell into repeatedly).

## Implementation Decisions

- **Target shape (Q1):** a reified **Observation** concept per grain-tuple for multi-qualified stats — measures as native-number attributes, entity-dimensions as edges to resolved existing entities, low-cardinality qualifiers as attributes. Single-qualified stats stay flat attributes. (Follows Kimball grain / W3C RDF Data Cube / W3C n-ary.)
- **Decision locus (Q2):** the Model `:llm` decides entity-vs-attribute-vs-observation, front-loaded with graph-context + the grain/reify principle. The deterministic layer PROVIDES context and MEASURES/validates only — it NEVER mutates the model-spec (this is the exact fix for the prior regression; front-loaded planning beats downstream repair).
- **Graph-context (Q3):** reuse the S20 orientation-card renderer (TBox digest = existing entity types + keying-fields + predicates; bounded ABox sample), threaded into the Model's `:reads` via a pre-Model step mirroring the GC-6 vocabulary pattern.
- **Dimension resolution (Q4):** Observation entity-dimensions resolve to existing entities by reusing reconcile + the GC-11 linking-key spine.
- **Reformation architecture (Q6 / ADR):** entity-vs-attribute ROLE + canonical identity are a re-derived VIEW over an append-only MENTION log. Re-derivation is INCREMENTAL (the affected dimension/linking-key neighborhood). "Demote" = re-derivation, not retraction — the CALM-mandated route to order-independence.
- **Reformation trigger (Q7):** re-derive the affected region on source-land AND via the CQ-objective loop when a role-dependent CQ fails (extend the existing loop, no fork).
- **Reuse, don't rewrite:** orientation-card, GC-6 threading pattern, the ROUTE node precedent, reconcile + GC-11 spine, reify-on-demand serialization, the CQ-objective loop.

## Testing Decisions

- **Behavior through public seams, not implementation.** Test the model-spec/observation output of the modeling decision, the derived-view projection over a mention log, and the graph stats — not private helpers.
- **The load-bearing tests are LIVE, not unit.** Two are mandatory and cannot be faked with synthetic fixtures (the prior attempt passed synthetic units while failing on real data):
  - **Clean-source regression gate:** the 3-source (IPEDS/crosswalk/O\*NET) 2/2 in-memory build stays at the GC-13 baseline (447 concepts / 338 rels / 0 dangling). Any inflation is a fail.
  - **Order-independence acceptance:** building `[pseo, ipeds, onet, …]` vs `[ipeds, onet, pseo, …]` yields the SAME final graph (same concepts/edges/roles within LLM variance).
- **pseo earnings gate (live):** earnings-bearing > 0, modeled as Observations with dimensions resolved to existing entities (not duplicated), measures as native numbers, no whole-row bloat.
- **Prior art for the tests:** the GC-13 3-source baseline runs (`gc13_verify`-style), the GC-11c acceptance-verdict fixtures, the reconcile/spine tests, the orientation-card tests. Reuse these patterns.
- **A minting-rate / ontology-conformance metric** (non-gating, honest) so "did we reduce mis-minting?" is a measured number.

## Out of Scope

- The embed/index native-OOM graceful-degradation (docked as GC-17) — a separate robustness slice; this PRD assumes the graph shrinks enough to embed.
- A full GLOBAL re-derive on every source (rejected in the ADR for cost; we do incremental).
- Reducing the underlying extraction volume/variance (O\*NET 0↔1040 draft swings) — a separate reliability concern, noted but not solved here.
- The A2-vs-B head-to-head itself — this PRD unblocks it (by giving B earnings) but does not run it.
- Any baked pseo/O\*NET domain knowledge — explicitly forbidden.

## Further Notes

- **Magnitude:** this is a real re-architecture of the build path (mention log + re-derived view), not a patch — sliced into thin tracer bullets, each live-verified, never big-bang. Slice 1 (graph-context into the Model) delivers standalone value and de-risks the rest; it likely fixes pseo already when sources are ordered entity-first, unblocking the head-to-head while the order-independence slices land.
- **Grounding:** Kimball grain; W3C RDF Data Cube; W3C n-ary relations; EDC (extract-define-canonicalize, EMNLP 2024); CALM theorem (PODS 2010 / CACM 2020); two 2025–26 findings that front-loaded planning beats degrade-prone downstream repair. (Two 2026 preprints were flagged unvetted and not relied on.)
