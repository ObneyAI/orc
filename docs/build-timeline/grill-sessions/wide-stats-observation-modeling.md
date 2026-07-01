# Design — Context-aware entity/attribute modeling + order-independent reformation (wide-stats)

**Status:** design locked (grill 2026-07-01). Supersedes the GC-16 measure-explosion guardrail (which regressed clean sources — see below). Companion ADR: [`../../adr/0001-mention-log-rederived-view.md`](../../adr/0001-mention-log-rederived-view.md).

## The problem (root-caused with live evidence)

The multi-source ontology builder mis-models WIDE DENORMALIZED STATS sources. The pseo Excel (each row = institution × program × cohort × geography × industry, with ~90 numeric measure columns) makes the Model `:llm` treat the statistics as a per-row ENTITY → a cross-product explosion (~6000 concepts from a 2/2 sliver) that crashes embedding and fragments the graph.

The GC-16 attempt — a deterministic post-Model guardrail that DETECTS a "measure-explosion" entity-type and REPAIRS the model-spec — FAILED across three rounds of live verification:
- It could not distinguish pseo (a genuine per-row explosion) from O\*NET (an occupation that LEGITIMATELY has dozens of numeric skill/knowledge ratings). Column-count and collapse-ratio are the wrong signals — both sources "look wide."
- Because it MUTATED the model-spec and fired on ONET too, it regressed the clean 3-source build (447 → 775–1010 concepts; canonicalization broke → fragmentation), and earnings-carriage was a coin-flip across model variance (0 vs 689 earnings-bearing run-to-run).

## The resolving insight (the right discriminator)

The literature has the correct, domain-agnostic discriminator (Kimball grain doctrine; W3C RDF Data Cube; W3C n-ary relations):

> **Reify measures as a node iff they are qualified by MORE THAN ONE subject dimension; otherwise they are plain data-property attributes of the single entity.**

- **O\*NET occupation**: skill/knowledge ratings are qualified by the occupation ALONE (one subject) → **flat data-property attributes**. Not an explosion.
- **pseo earnings**: qualified by program × cohort × geography × industry (multiple subjects) → a **reified Observation node per grain-tuple**, its ENTITY dimensions (program, institution) resolved to existing entities, its measures as literals.

This is the signal the structural checker lacked. It cleanly separates the two cases and it is standards-backed.

## Locked decisions (the grill)

**Q1 — Target shape.** A **reified Observation concept per grain-tuple** for multi-qualified stats: measures as native-number attributes on the observation; ENTITY dimensions (program, institution) as edges to the RESOLVED existing entities; low-cardinality qualifiers (cohort year, percentile band, geo level, industry) as attributes on the observation. (RDF Data Cube / n-ary pattern. Single-qualified stats — O\*NET — stay flat attributes.)

**Q2 — Decision locus.** The **Model `:llm` decides** entity vs attribute vs observation, front-loaded with (a) a graph-context preview and (b) the grain/reify principle. The **deterministic layer never mutates** the model-spec — it only PROVIDES context and MEASURES/validates (minting-rate, ontology-conformance, flag/abstain). This removes the exact failure mode of GC-16; front-loaded planning beats downstream repair (two independent 2025–26 findings).

**Q3 — Graph-context snapshot.** Reuse the **S20 orientation-card** renderer (TBox digest: existing entity types + keying-fields + predicates; plus a bounded ABox sample), threaded into the Model's `:reads` via a pre-Model step mirroring the GC-6 vocabulary pattern. Schema-focused — the decision needs "does a `program` entity already exist to attach observations to?" Re-orchestrate, don't rebuild.

**Q4 — Dimension resolution.** An Observation's entity-dimensions resolve to existing entities by reusing **reconcile + the GC-11 linking-key spine** (canonicalize-onto-existing), not a new path.

**Q5 — Order-independence.** Build **Layer B reformation now** (not just the pragmatic spine-connect) — the full living-graph, order-independent convergence.

**Q6 — Reformation architecture (ADR).** **Incremental re-derived view over an append-only mention log.** Every extraction is stored as an immutable MENTION (fits ORC's event sourcing). The entity-vs-attribute ROLE + canonical identity are a DERIVED view, RE-DERIVED for the AFFECTED subgraph when new evidence arrives. "Demote earnings-entity → program observation" is the view re-deriving with IPEDS present — never a retraction. CALM-correct convergence, kept tractable by re-deriving only the touched region. See the ADR for why this (not in-place compensating events) is required for true order-independence.

**Q7 — Reformation trigger.** Incremental re-derivation of the affected region (mentions sharing a dimension/linking-key value with the new source), triggered both after each source lands AND by the CQ-objective loop when a CQ needs a role-change (extend the existing loop, don't fork).

## Why this is defensible (grounding)

- **Grain / measures-are-attributes-of-a-grain:** Kimball dimensional modeling; W3C RDF Data Cube (`qb:Observation`/`qb:dimension`/`qb:measure`).
- **Reify-iff-multi-subject-dimension:** W3C n-ary relations note.
- **Extract-then-canonicalize-onto-existing-schema (don't mint):** EDC (Zhang & Soh, EMNLP 2024) + ontology-grounded KGC (KDD 2024).
- **Order-independence requires monotonicity / re-derived view over an append-only log:** CALM theorem (Hellerstein, PODS 2010; Hellerstein & Alvaro, CACM 2020).
- **Front-loaded planning beats downstream repair; in-place repair loops degrade:** two independent 2025–26 multi-agent KGC findings.
- (Two 2026 preprints — DIAL-KG, an RPI multi-agent paper — were flagged unvetted and are NOT leaned on.)

## What ORC already has vs what's new

ALREADY SHIPPED (reuse): read-models for concepts/relationships/axioms (TBox+ABox); the S20 orientation-card graph preview; GC-6 pre-Model context threading + the ROUTE node (graph-context → `:llm` decision precedent); C1 structured-schema `:delegate` threading; reconcile + GC-11 linking-key spine; reify-on-demand in the TTL serializer; the CQ-objective loop.

NEW (to build): a `mention` event + the derivation projection (concepts/roles as a re-derived view); the graph-context pre-Model subbehavior; the Model prompt's grain/reify block; the Observation target shape in the model-spec + extraction; the incremental re-derive step + its trigger; the deterministic minting-rate/conformance MEASUREMENT (non-mutating).

## Magnitude + recommended slicing (thin vertical, proof-point first)

This is a significant re-architecture (mention-log + re-derived view touches the core build path), NOT a patch. Build it as thin tracer-bullet slices, each live-verified, NEVER big-bang:

1. **Graph-context preview into the Model** (Layer A, standalone value): the pre-Model snapshot + the grain/reify prompt block. Verify: pseo models earnings as Observations w/ resolved dimensions; O\*NET unchanged; clean 3-source stays at the 447 baseline (the GC-16 regression gate). This slice alone likely fixes pseo when sources are ordered entity-first.
2. **Mention event + re-derived role/identity view** (the CALM substrate): store extractions as mentions; derive concepts. Verify parity with today's graph on the clean sources (byte-for-baseline).
3. **Incremental re-derive of the affected region** on source-land. Verify the ORDER-INDEPENDENCE ACCEPTANCE: build `[pseo, ipeds, onet, …]` and `[ipeds, onet, pseo, …]` → the SAME final graph (same concepts/edges/roles within LLM variance).
4. **CQ-loop re-derive route** — a failing role-dependent CQ triggers re-derivation. Verify a program→earnings CQ that fails pre-reform passes post-reform.
5. **Non-mutating measurement** — minting-rate / ontology-conformance metric surfaced honestly (never gates by mutating).

## Acceptance (the whole effort)

- pseo: earnings modeled as Observations, dimensions resolved to existing program/institution entities (not duplicated), measures as native numbers — earnings-bearing > 0, sane concept count, no whole-row bloat.
- O\*NET + the other clean sources: UNCHANGED vs the GC-13 baseline (3-source 2/2 = 447 concepts / 338 rels / 0 dangling) — no regression.
- **Order-independence:** two different source orders → the same final graph (within LLM variance).
- The full 5-source build COMPLETES and the CQ-gate answers the earnings/financial CQs.
- Domain-agnostic throughout (no baked pseo/ONET column names); recursive-only RLM; events-first; JVM hygiene.

## Glossary (ubiquitous language — used verbatim in code/tests/docs)

- **Grain** — what one row of a source represents (its compound identifying key = the set of dimensions).
- **Dimension** — a who/what/where/when qualifier of a measure (e.g. program, institution, cohort, geography, industry). An **entity-dimension** is one that is (or should be) a first-class entity (program, institution); a **qualifier-dimension** is low-cardinality context kept as an attribute (cohort year, percentile).
- **Measure** — a numeric value true at the grain (an earnings percentile, a count). Has no independent existence apart from its grain.
- **Observation** — a reified node representing the measures at one grain-tuple; carries measures as attributes and edges to its entity-dimensions.
- **Mention** — an immutable, append-only record of one extracted assertion + provenance. Concepts/roles are a re-derived VIEW over the mention log.
- **Reformation / re-derivation** — recomputing the entity-vs-attribute role + canonical identity of the affected subgraph as new evidence (mentions) arrives, so the graph converges regardless of source order.
