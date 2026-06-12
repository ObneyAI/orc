---
type: research-plan
date: 2026-06-12
session: class-deep-dive-categories
status: agents-dispatched
branch: feature/ontology-architecture
inputs:
  - Class 1 (ontology engineering methodology + OWL/Protégé) — transcript + ProtegeOWLTutorial PDF (read)
  - Class 2 (Semantic Web stack: RDF/SPARQL/RDFS/OWL/SHACL/LOT) — transcript + TTL artifacts (read) + PDFs (pending)
  - research synthesis: 2026-06-10-ontology-improvement-candidates.md (A/B/C/D buckets)
  - grill records: 2026-06-10 rounds 1-2
next: fold agent findings into the synthesis → /grill-with-docs round 3
---

# Class Deep-Dive Categories

The two courses + resources split into six targeted research sections.
Each states WHY it deserves a deeper pass, its CONNECTION to our open
decisions, and what the subagent dig is scoped to. Agents work the
codebase + on-disk artifacts; transcript synthesis stays with the main
session (agents don't share its context).

## Category 1 — Validation & Rules layer (SHACL-centric)

**Sources:** SHACL transcript chapters; `film_shacl.ttl`,
`film_shacl (1).ttl`, `film_shacl_sparql.ttl`, `film_shacl_rules.ttl`,
`shacl_validation.py` (Downloads).

**Why deeper:** D1 contains an unresolved fork — (a) lints authored as
`:code` with SHACL export, (b) SHACL as source-of-truth interpreted
internally (which would let CONSUMERS author their own validation shapes
that our builder runs), (c) `:code`-only. Option (b)'s consumer-shapes
upside is real and unexamined.

**Connections:** A3 validation library · B4/B6/B13/B3 lints · Q3
axioms-as-lints · round-2 severity/message/deactivation vocabulary.

**Agent dig:** catalog the full constraint-component vocabulary in the 4
SHACL artifacts; map every planned ORC lint to a SHACL component (or flag
inexpressible ones — roles-vs-classes likely needs SPARQL-constraints);
audit ORC's existing Malli validation for overlap/conflict.

## Category 2 — Inference-without-reasoner (derived edges)

**Sources:** OWL transcript (property chains, transitive/symmetric/
inverse, functional dangers); `sh:TripleRule`/`sh:SPARQLFunction` in
`film_shacl_rules.ttl`; recipe-solution property chains.

**Why deeper:** D2 has an unresolved materialization fork — derived edges
as events (auditable, denormalized, needs invalidation) vs computed in
the concepts projection (rebuildable, watermark-incremental for free) vs
query-time. Needs grounding in our actual projection code.

**Connections:** D2 · C10 typed relations + property hierarchies · Q3
transitive-traversal hints · retrieval BFS cost.

**Agent dig:** read `read_models.clj` concepts* projection + `retrieval.clj`
BFS; sketch where chain-shortcut computation fits in projection flow and
what watermark-incremental implies for it; re-read /tmp/hindsight
`link_creation.py` + /tmp/graphify `affected.py` for how they maintain
derived/inferred links incrementally.

## Category 3 — Requirements & lifecycle (LOT/ORSD/CQ/publishing)

**Sources:** LOT transcript chapters; orsd-template/orsd-sro (PDFs
pending); Widoco/w3id publishing lessons.

**Why deeper:** D5 needs the exact ORSD field schema as a Malli shape and
a decision on where it LIVES (events tagged to ontology-id, per B1's
persistent-contract precedent). CQ evaluation results need an event
shape.

**Connections:** B1 CQ contract · B2/D5 build spec · B8 evaluation loop ·
D9 publishing.

**Agent dig:** read ORC's `build-ontology-from-sources` param surface +
descriptions/CQ-adjacent read-model patterns; propose where an ORSD map
+ per-CQ evaluation events would attach with least new machinery
(reusing the descriptions-event pattern).

## Category 4 — Reuse, alignment & multi-ontology composition

**Sources:** ontology-matching/alignment + reuse lessons (LOV, QUDT,
Time, SKOS, FOAF, schema.org); moviebase tbox variants.

**Why deeper:** D3 (alignment sections) + D4 (check-before-mint) need
grounding: how is equivalence representable in our CURRENT relationship
events; can an alignment section be cleanly scoped/loaded/dropped by
ontology-id today; what does QUDT-style quantity+unit structure demand
from extraction.

**Connections:** D3 · D4 · B9 abstraction levels · dedup
sameAs-vs-equivalentClass distinction.

**Agent dig:** read ontology-id scoping in `read_models.clj`/commands +
relationship event schema; verify arbitrary predicates like
`owl:equivalentClass` flow through today; read `json_ontology.clj`'s
value extraction to assess quantity+unit gap concretely.

## Category 5 — Substrate representation details

**Sources:** RDF transcript (literals, language tags, datatypes, blank
nodes, collections, reification); `sparql-moviebase.ttl`,
`moviebase-tbox*.ttl`, `film_abox.ttl`, `solution-tbox.ttl`.

**Why deeper:** produce a concrete representation-gap table: does our
concept/relationship event schema admit language-tagged labels, datatyped
literals, ordered sequences (D6), quantity+unit nodes, annotation-grade
metadata (B14, D7) — TODAY? Which are schema additions vs conventions?

**Connections:** D6 sequences · B15 language tags + trap-lint · B14
annotation export · D7 model-guidance · reification-sidestep advantage.

**Agent dig:** read the moviebase/film TTLs for the concrete patterns;
audit `interface/schemas.clj` ontology event schemas field-by-field;
output the gap table.

## Category 6 — Query-surface mapping (SPARQL ↔ our retrieval)

**Sources:** SPARQL transcript (SELECT/ASK/CONSTRUCT/DESCRIBE, FILTER/
OPTIONAL/UNION/VALUES, aggregates, GROUP BY/HAVING, subqueries,
modifiers).

**Why deeper:** B1's CQ evaluation is essentially natural-language →
query-plan; SPARQL's pattern vocabulary is the canonical checklist of
query capabilities a CQ-answerer needs. Mapping it against our retrieval
surface tells us exactly which CQ shapes we can already answer and which
would silently fail.

**Connections:** B1 CQ evaluation · D8 export-to-triplestore posture ·
retrieval configurability.

**Agent dig:** read `retrieval.clj` + interface query fns; produce a
capability map (SPARQL feature → ORC equivalent → have/partial/missing)
flagging gaps that would block CQ answering (aggregation over the graph,
value filtering, existence checks, optional joins, union).

## Main-session work (not delegable — transcript lives here)

- ORSD exact field-set distillation (Cat 3) from the LOT lessons
- SHACL fork recommendation shaping (Cat 1) for the grill
- Sequence/QUDT prompt-guidance wording (Cat 5) for discovery seeds

---

# Agent Findings (2026-06-12, five parallel digs)

## Cat 1 — SHACL: agent recommends fork (b), source-of-truth interpreted internally

- Construct catalog from the four TTL artifacts: NodeShape/targetClass,
  PropertyShape (path, min/maxCount, maxExclusive), severity/message/
  deactivated, sh:not, qualifiedValueShape+qualifiedMinCount, sh:sparql
  (+HAVING aggregates), sh:TripleRule + sh:condition, sh:SPARQLFunction.
- Lint expressibility: 3/8 clean (disjointness, functional-double,
  dangling endpoints), 3/8 via sh:sparql (universal-without-existential,
  language-tag misuse, closure presence), 2/8 inexpressible in standard
  SHACL (naming conventions, roles-vs-classes) — those remain :code-only.
- Overlap audit: Malli validates EVENT SHAPE at write time; SHACL-style
  lints validate GRAPH SEMANTICS; anti-recency validates description-body
  evolution. Three orthogonal layers, confirmed non-overlapping.
- Agent's fork verdict: **(b)** — interpret a core subset internally
  (~500-1000 LOC) so consumers can author their own shapes our builder
  runs; phase the subset (counts/severity/message/deactivated/not →
  qualified shapes → sh:sparql).
- **Main-session refinement for the grill:** an EDN-SHACL bridge —
  shapes authored as SHACL-shaped EDN (Malli-validatable, trivially
  interpretable in-JVM, no TTL parser dependency), exported as real SHACL
  TTL; TTL-shape ingestion later. Bridges (a) and (b).

## Cat 2 — Derived edges: per-edge-class verdict

- concepts* projection is stateless between events; full transitivity at
  projection time would need a pending-chain index + breaks
  watermark-incrementality under out-of-order arrivals.
- BFS already does traversal-time closure (bidirectional edge mirroring,
  decay-bounded) — transitive hierarchy is FREE at retrieval.
- Verdict: **transitive hierarchy → traversal-time (status quo); binary
  chains (P∘Q→R) → query-time synthesis in a post-BFS step; audited
  learned rules → event-time `:ontology/derived-edge-created` carrying
  `:source-edges` for cascade retraction.** Lazy projection-time
  materialization only if/when invalidation cost demands it.

## Cat 3+4 — ORSD/CQ + alignment: one critical discovery

- **ORSD/CQ: zero new machinery.** `record-ontology-spec` +
  `record-cq-evaluation` mirror the descriptions pattern exactly
  (command → tagged event → current+history projection). Schemas slot
  into interface/schemas.clj alongside existing description events.
- **CRITICAL — cross-section scoping is inconsistent across retrieval
  signals (NEW HIGH GAP ROW):** the embedding + ColBERT stages filter by
  ontology-id, but **graph BFS does not** — `expand-concept-neighborhood`
  walks the merged concepts graph across ALL sections. Two consequences:
  (1) multi-tenant isolation leak — section A's BFS can wander into
  section B; (2) cross-section results from BFS lose RRF fusion because
  the other signals filtered them out. Alignment sections (D3) are
  feasible TODAY only by explicitly passing `:ontology-ids
  [primary alignment]`; discovery ("what is this equivalent to?") needs
  either that convention or a `query-with-alignments` wrapper. The BFS
  scoping fix is load-bearing regardless of alignment work.
- Check-before-mint hook located: evolutionary_builder.clj graph-merge
  phase (post entity-resolution, ~line 730) — equivalence check against
  `{:ontology-ids all-section-ids}` then either reuse canonical URI +
  emit alignment event, or mint.
- **QUDT gap confirmed concretely:** column/value extraction detects only
  :integer/:number/:boolean/:string; units appear nowhere (sql sheet even
  regexes a FIELD NAME "unitid"); serialization emits bare xsd types.

## Cat 5 — Representation gap table (schema additions bundle)

| Need | Status today |
|---|---|
| Language-tagged, multiple labels | CONVENTION-ONLY (single :label string; export hardcodes @en) |
| Datatyped literals on concept attributes | PARTIAL (metrics typed; no general attribute datatypes) |
| Quantity + unit (QUDT-style) | ABSENT |
| Ordered sequences (follows/immediatelyFollows) | ABSENT (no order field; pattern-only fix possible) |
| Edge-level metadata | PARTIAL-NATIVE (`:properties` open map EXISTS on relationship events — but is never serialized to TTL, and confidence/temporal aren't schema'd fields) |
| Annotations (comment vs definition, seeAlso, isDefinedBy, ontology-level metadata) | CONVENTION-ONLY/ABSENT |
| Disjointness + property characteristics as axioms | ABSENT |
| sameAs/equivalentClass assertions | ABSENT (canonical-uri-assigned is adjacent, not equivalent) |
| Nested anonymous substructures | HAVE (inlined maps — and event-sourced edges sidestep reification entirely) |

Export round-trip is the weak face: `:properties` never serialized,
no language tags, no axioms, scheme metadata hardcoded.

## Cat 6 — SPARQL↔retrieval capability map

- HAVE: triple match, UNION (RRF/set union), VALUES, DISTINCT/LIMIT/
  OFFSET/ORDER, aggregates + GROUP BY/HAVING (clojure-side), composition,
  DESCRIBE (BFS neighborhood), CONSTRUCT (graph/TTL export).
- PARTIAL: FILTER (no regex helper), OPTIONAL (procedural), property
  paths (BFS + predicate enumeration, no syntax), ASK (no dedicated fn).
- **MISSING: closed-world negation (NOT EXISTS)** — blocks CQ shapes like
  "vegan recipes" / "trees with no documented weaknesses".
- Posture for CQ evaluation: **push negation + complex joins to the LLM
  judge over fetched neighborhoods** (the judge IS our closed-world
  evaluator), add two lightweight helpers (`filter-by-label-pattern`,
  `absent-in-graph?`), and keep export-to-triplestore (D8) for genuinely
  heavy transitive-negation workloads.
