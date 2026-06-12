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
