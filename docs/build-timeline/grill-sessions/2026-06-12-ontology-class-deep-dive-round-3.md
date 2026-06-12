---
type: grill-session
date: 2026-06-12
session: ontology-class-deep-dive-round-3
status: complete
method: grill-with-docs
repo: orc
branch: feature/ontology-architecture
prior-sessions:
  - 2026-06-10-orc-ontology-architecture (round 1 — the frame)
  - 2026-06-10-ontology-improvements-round-2 (round 2 — 25-candidate triage)
inputs:
  - Class 2: Semantic Web course (RDF/SPARQL/RDFS/OWL/SHACL/LOT) — transcript + TTL artifacts
  - docs/build-timeline/research/2026-06-10-ontology-improvement-candidates.md (+ class-2 D-bucket appendix)
  - docs/build-timeline/research/2026-06-12-class-deep-dive-categories.md (category map + five-agent findings)
output: six locked decisions + D12 addendum; D10/D11/D12 added; gap row #2 promoted; C7 re-tiered
next: /to-prd when user gives the go (more classes may land first)
---

# Grill Session: Ontology Improvements — Round 3 (class-informed)

Round 3 walks the design forks sharpened by the class-2 deep-dive: five
parallel agents grounded each category in the actual codebase before the
grill, surfacing one critical correctness discovery (Q1) that reordered
the agenda.

---

## Q&A Log

### Q1 — Cross-section scoping inconsistency (the critical discovery)

Agent-verified: embedding + ColBERT retrieval filter by `ontology-id`, but
graph BFS does not — `expand-concept-neighborhood` walks the merged
concepts graph across ALL sections. Consequences: (1) isolation leak — one
section's BFS can wander into another's subgraph; (2) cross-section
fusion loss — BFS-only hits carry no embedding/ColBERT rank, so RRF
silently buries them.

**Options:** (A) scope BFS by ontology-id by default, consistent with the
other signals; multi-section as explicit `:ontology-ids` opt-in widening
ALL THREE signals together; alignment-section registry drives
auto-widening so consumers don't need to know alignment IDs. (B) leave
BFS global, document it.

**User answer:** "yes a"

**Decision:** (A) with registry-driven auto-widening. Gap-matrix row #2
(ontology-id scoping partial) **promoted to HIGH** and absorbs the BFS
fix — one slice fixes scoping across query fns AND retrieval signals.

### Q2 — The SHACL fork → b′ (EDN-SHACL bridge) + the events-first invariant

Cat-1 agent recommended (b) SHACL-as-source-of-truth interpreted
internally (consumer-authored shapes). Main-session refinement adopted:
**b′** — shapes authored as **SHACL-shaped EDN** (source of truth,
Malli-validated, in-JVM interpreted, consumer-authorable from day one),
exported as real SHACL TTL (standards interchange kept), TTL-shape
ingestion deferred until demanded; `:code` predicate escape hatch for the
two standard-SHACL-inexpressible lints (naming, roles-vs-classes);
unified lint registry (built-ins + consumer shapes, severity-leveled);
violations evented as `:ontology/lint-violation`.

**User answer (verbatim, load-bearing):** "yes i agree but it is super
important to maintain grain and orc disciplines with commands and events
for instance when we are building the graph we do so with event schemas
which then can be projected and cached as a usable graph or exported to
ttl or if someone comes in with a ttl we should be able to take it apart
into the format grain/orc needs and could reproject that same ttl from
the ingestion events the same way as exporting a ttl built from disparate
data sources events"

**Decision:** b′ adopted, PLUS the **round-trip invariant** made
canonical → **NEW CANDIDATE D10: TTL round-trip adapter.** TTL ingestion
is a first-class write adapter decomposing TTL into the standard event
vocabulary; re-projection must reproduce a semantically equal TTL
(triple-set equivalence — executable acceptance test
`ingest(ttl) → events → export ≍ ttl`). The brownfield onboarding path is
now precisely TTL→events. The Q4 representation gaps become BLOCKERS for
this invariant, not polish.

### Q3 — Derived edges: the three-way split

**Decision (user: "yes i agree"):**

| Edge class | Strategy |
|---|---|
| Transitive hierarchy (broader/narrower, any transitive-marked predicate) | Traversal-time (status quo — BFS closes bidirectionally, decay-bounded) |
| Binary chains (P∘Q→R) | Query-time synthesis in a post-BFS step, emitted into the RRF pool — ephemeral, read-side |
| Audited learned rules | Event-time `:ontology/derived-edge-created` with `:source-edges` + `:rule-id` for cascade retraction |

Rejected: eager projection-time chain materialization (concepts*
projection is stateless between events; pending-chain index would break
watermark incrementality under out-of-order arrival — agent-verified).
Chain DEFINITIONS are axiom events (Q4).

### Q4 — Representation schema bundle (8 parts, one WITH-REBUILD package)

**Decision (user: "yes confirm!"):** all additive optional fields/events:
(1) language-tagged multiple labels + B15 trap-lint; (2) datatyped
attributes; (3) QUDT-style quantity+unit `{:value 75 :unit "kg"}`;
(4) ordered-sequence convention (immediatelyFollows + transitive follows);
(5) edge metadata schema'd — confidence-class/evidence/valid-from-to
promoted from the `:properties` bag AND serialized (today `:properties`
is never exported); (6) axioms-as-data events — disjointness sets,
property characteristics, property hierarchies, chain definitions;
(7) equivalence events with `:kind :same-as | :equivalent-class |
:equivalent-property`, tagged to the alignment section; (8) annotations —
`:comment` ≠ `:description`, `:see-also`, `:is-defined-by`,
`:model-guidance` (D7), ontology-level metadata (title/version/license/
creator). **Gate for the whole bundle: the D10 round-trip test.**

### Q5 — CQ/negation posture + retrieval primacy + D11

Cat-6 agent: closed-world negation (NOT EXISTS) is MISSING and blocks
common CQ shapes ("vegan recipes"). **Decision — three layers,
cheapest-first:** (1) the LLM judge IS the closed-world evaluator over
fetched neighborhoods (evidence-auditable via `:evidence-uris`); (2) two
deterministic helpers — `filter-by-label-pattern`, `absent-in-graph?` —
plus a dedicated `exists?`; (3) export-to-triplestore remains the
documented escape hatch for heavy transitive-negation workloads.
Principle recorded: *we don't rebuild SPARQL; retrieval assembles honest
evidence and the judge does query-language work.*

**User answer (verbatim, load-bearing):** "yes! This sounds great (but
remember i think the primary retrieval is graph bfs and colbert/embedding
so we need to keep in mind how we are using sparql if we are and if these
are tool sets that can be given to repl researchers etc"

**Captured:** retrieval primacy — BFS + embeddings + ColBERT IS the
stack; SPARQL exists only at the boundary (export escape hatch +
capability checklist). **NEW CANDIDATE D11: ontology tools for
repl-researchers** — the retrieval surface as sandbox primitives
(`graph-search`, `neighborhood`, `get-concept`, `exists?`,
`absent-in-graph?`, `filter-by-label-pattern`, `classify-*`). Payoffs:
the rebuilt builder's recursive-RLM discovery phases use these tools to
compare-against-existing interactively; CQ evaluation can itself be a
recursive-RLM task; consumers' repl-researchers get "query the ontology"
as a capability. Tier: WITH-REBUILD (builder-facing subset), NEXT
(general sandbox exposure).

### Q6 — ORSD/CQ attachment

**Decision (user confirmed):** zero new machinery —
`record-ontology-spec` + `record-cq-evaluation` mirror the descriptions
pattern (command → ontology-id-tagged event → current+history
projection); CQ results `{:cq-index :answerable? :confidence
:evidence-uris :gaps :evaluated-at}` give the pass-rate health metric;
check-before-mint hooks the evolutionary builder's graph-merge phase
(post entity-resolution), recording Q4-#7 equivalence events into the
alignment section instead of re-minting.

### Addendum — D12: the graph orientation card

**User prompt (paraphrase):** large documents in the RLM sandbox get a
preview so the model can use its tools intelligently; a graph needs the
equivalent — "showing the t-box or some other document or preview."

**Decision (via AskUserQuestion):** **D12 — graph orientation card**,
four layers: (1) identity — ORSD spec, ontology metadata, section +
alignment registry (what the model may widen into per Q1); (2) T-Box
digest — scopes, classes w/ counts, predicates w/ counts +
characteristics, axiom summary (all from Q4 axiom events + projections);
(3) content sample — top-N concepts by degree/evidence + representative
neighborhoods; (4) tool affordances — available D11 tools + worked
one-liners. Generation: **deterministic skeleton + optional
budget-knobbed LLM prose layer** (Tier-1/Tier-2 split), cached, refreshed
on reindex, injected whenever D11 tools are granted. **C7 (Leiden
communities + per-community summaries) re-tiered RECORDED → NEXT** — the
card's content layer is its first concrete consumer.

---

## Decisions Summary

| # | Topic | Decision |
|---|-------|----------|
| 1 | BFS scoping | Uniform ontology-id scoping across all 3 signals; registry-driven auto-widening; gap row #2 → HIGH |
| 2 | Validation shapes | b′ EDN-SHACL source of truth, TTL export, unified lint registry, evented violations |
| 3 | Round-trip invariant | D10 TTL→events adapter; `ingest → events → export ≍ source` as executable gate; brownfield = TTL→events |
| 4 | Derived edges | Traversal-time hierarchy / query-time chains / event-time audited rules; chain defs as axiom events |
| 5 | Representation | 8-part additive schema bundle, WITH-REBUILD, gated by the round-trip test |
| 6 | CQ/negation | Judge as closed-world evaluator + 2 helpers + exists?; triplestore escape hatch; no SPARQL in ORC |
| 7 | RLM tooling | D11 ontology tools as sandbox primitives; builder discovery + CQ eval use them |
| 8 | ORSD/CQ | Descriptions-pattern attachment, zero new machinery; check-before-mint at graph-merge |
| 9 | Orientation | D12 graph orientation card, deterministic + optional prose; C7 → NEXT |

## Linked Artifacts

- Category map + agent findings: `../research/2026-06-12-class-deep-dive-categories.md`
- Candidates synthesis (A-D buckets): `../research/2026-06-10-ontology-improvement-candidates.md`
- Frame: `../../ARCHITECTURE-ONTOLOGY.md`
- Prior rounds: `2026-06-10-orc-ontology-architecture.md`, `2026-06-10-ontology-improvements-round-2.md`
- **Next:** `/to-prd` on the user's go (additional class grills may precede it)
