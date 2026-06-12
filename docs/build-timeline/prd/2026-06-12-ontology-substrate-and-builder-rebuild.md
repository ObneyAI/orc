---
type: prd
date: 2026-06-12
title: Ontology Substrate + Evolutionary Builder Rebuild
status: ready-for-issues
branch: feature/ontology-architecture
decision-sources:
  - docs/ARCHITECTURE-ONTOLOGY.md
  - docs/build-timeline/grill-sessions/2026-06-10-orc-ontology-architecture.md
  - docs/build-timeline/grill-sessions/2026-06-10-ontology-improvements-round-2.md
  - docs/build-timeline/grill-sessions/2026-06-12-ontology-class-deep-dive-round-3.md
  - docs/build-timeline/research/2026-06-10-ontology-improvement-candidates.md
  - docs/build-timeline/research/2026-06-12-class-deep-dive-categories.md
next: /to-issues into docs/build-timeline/issues/ on user's go
---

# PRD: Ontology Substrate + Evolutionary Builder Rebuild

## Problem Statement

Teams building on ORC need a general-purpose ontology substrate they can
trust as the knowledge backbone of real products — a graph that takes
disparate data sources (CSV exports, JSON APIs, SQL databases, raw text,
existing RDF) and turns them into one unifying, continuously-growing
structure that can be reasoned over, queried, validated, and added to
forever. Real consumers exist today: transcript correction and
client/concept Q&A in one app, school/program/career recommendations for
high-school students in another (unifying IPEDS, PSEO, wage data, O*NET
with advisor lived experience and podcast transcripts), and ORC's own
self-improving loop.

But the current system falls short of that trust in specific, verified
ways:

1. **Isolation is leaky.** Graph traversal ignores ontology scoping while
   the other retrieval signals enforce it — one ontology's search can
   wander into another's subgraph, and legitimate cross-ontology results
   silently lose ranking fusion.
2. **The builder predates the engine's own best patterns.** The
   extraction pipelines are sequential LLM chains written before
   recursive RLM matured — no per-section parallelism, no adversarial
   verification pass, no deterministic structural validation, and
   validation findings never feed back into extraction.
3. **Extracted graphs are semantically thin.** No units on quantities, no
   disjointness or property characteristics, no language-tagged labels,
   no ordered sequences, no closure semantics — so downstream reasoning
   over the graph is silently incomplete, and the classic LLM-extraction
   mistakes (roles modeled as classes, name-implied semantics, vacuous
   restrictions) go uncaught.
4. **Export is lossy and ingestion is absent.** Relationship metadata is
   never serialized, labels export with a hardcoded language, axioms
   don't exist to export — and a consumer arriving with an existing
   RDF/TTL graph has no first-class path into the event-sourced world.
5. **There is no acceptance test for "the build works."** A build that
   ran is not a build that can answer the questions the consumer needs
   answered. Nothing captures those questions or evaluates against them.
6. **Validation has no portable contract.** Quality checks are internal;
   a consumer can neither author their own business-rule validations nor
   re-verify a graph independently with standard tooling.
7. **Knowledge carries no support metadata.** Nothing records how
   well-evidenced a concept is, when it was last reinforced, or that new
   information contradicted it.
8. **Agents can't use the graph intelligently.** Repl-researchers have no
   ontology tool set and no orientation artifact — the graph equivalent
   of a large-document preview — so even if given tools they'd explore
   blind.

## Solution

Rebuild the ontology element in phases, substrate-correctness first,
per the locked decisions from three grill rounds:

**Phase 1 — Substrate correctness + representation.** Make ontology
scoping uniform across all three retrieval signals with an
alignment-section registry for deliberate cross-ontology queries. Ship
the eight-part representation schema bundle (language-tagged labels,
datatyped attributes, quantity+unit, sequences, schema'd edge metadata,
axioms-as-data, equivalence-with-kind, annotations) together with the
TTL round-trip adapter — ingestion decomposes TTL into the standard
event vocabulary, and re-projection reproduces a semantically equal TTL.
One executable gate pins both: `ingest(ttl) → events → export ≍ source`.

**Phase 2 — Validation + knowledge hygiene.** A unified lint registry
whose shapes are SHACL-shaped EDN (consumer-authorable, in-JVM
interpreted, exported as real SHACL for independent re-validation), a
tiered dedup cascade with hard correctness guards and equivalence-kind
verdicts, always-on deterministic evidence tracking, and ORSD/CQ
contracts: every ontology can carry its purpose, scope, and competency
questions, and a judge-based runner evaluates the built graph against
its CQs — pass-rate over time is the ontology's health metric.

**Phase 3 — The builder rebuild.** A hybrid: a hand-authored
deterministic skeleton owns the substrate contracts (parsing, dedup,
validation, event emission, embedding, indexing) while discovery phases
become recursive-RLM tasks — the model designs extraction per source,
guided by an ontology-discovery seed corpus that encodes the
bench-proven patterns and the course-derived modeling discipline.
Self-improvement (corpus injection, judges, living descriptions) is
strictly opt-in via the R-Inject boundary; LLM use itself is
unconditional. The old sheets remain as the regression baseline until
the new path beats them on a real extraction bench.

**Phase 4 — Agent integration.** The retrieval surface becomes sandbox
tool primitives for repl-researchers, fronted by a graph orientation
card — the large-document-preview equivalent for graphs — so models use
the tools intelligently. The builder's own discovery phases are the
first consumer.

A NEXT-tier tail (chain synthesis, LLM concept consolidation, HITL
review surfaces, temporal retrieval, communities, incremental
maintenance) follows once the rebuild stabilizes.

## User Stories

**External consumer — greenfield ontology (recommendations-style app):**
1. As an app developer, I want to build an ontology from multiple
   disparate sources (CSV, JSON, SQL, text) in one call, so that my
   domain knowledge unifies into a single graph without per-format
   custom code.
2. As an app developer, I want to declare my ontology's purpose, scope,
   and competency questions at build time, so that extraction is bounded
   by what I actually need and I can verify the result answers my
   questions.
3. As an app developer, I want extracted quantities to carry their units,
   so that wage data in dollars and durations in minutes don't collapse
   into meaningless bare numbers.
4. As an app developer, I want to keep adding sources over time (new
   datasets, advisor experiences, podcast transcripts), so that the graph
   gets richer without rebuild-from-scratch.
5. As an app developer, I want new information about existing concepts to
   reinforce or contradict them visibly, so that I can trust how
   well-supported any fact is.

**External consumer — brownfield (existing RDF/TTL):**
6. As a team with an existing RDF graph, I want to ingest my TTL file and
   have it decomposed into ORC's event format, so that my graph becomes a
   first-class, growable ORC ontology rather than a frozen import.
7. As a team with an existing RDF graph, I want re-exporting my ingested
   graph to produce semantically the same TTL, so that nothing was
   silently lost or mangled on the way in.
8. As a team with an existing RDF graph, I want subsequent builds from
   new sources to extend my ingested graph (dedup against it, link to
   it), so that brownfield and greenfield growth are the same motion.

**Substrate-as-database consumer (no self-learning):**
9. As a consumer using the ontology purely as a knowledge store, I want
   the self-improving machinery fully dormant unless I opt in, so that I
   pay no classification/judge/reranker overhead.
10. As a consumer using the ontology as a store, I want my queries scoped
    to my ontology by default, so that other tenants'/sections' data can
    never leak into my results.
11. As a consumer with several related ontologies, I want to deliberately
    query across specific sections — with alignment links honored and
    ranking fusion intact — so that cross-domain questions work without
    breaking isolation by default.
12. As a consumer, I want equivalences my dedup discovers recorded in a
    separate alignment section, so that my core sections stay clean and
    the links are independently loadable/droppable.

**Ontology maintainer:**
13. As a maintainer, I want every build to produce a validation report
    with severity levels (info/warning/violation) and human-readable
    messages, so that I can triage extraction quality at a glance.
14. As a maintainer, I want to author my own validation shapes in the
    same format as the built-in ones, so that my domain's business rules
    (e.g., "every CorrectionRule must carry at least one example") are
    enforced at build time.
15. As a maintainer, I want lint violations stored as events, so that
    validation health has a queryable history like everything else.
16. As a maintainer, I want CQ pass-rate over time per ontology, so that
    I can see whether growth cycles are improving or degrading the
    graph's fitness for purpose.
17. As a maintainer, I want contradiction markers when new evidence
    conflicts with stored facts, so that conflicts surface for review
    instead of being silently overwritten.
18. As a maintainer, I want the classic LLM-extraction mistakes
    (role-as-class, missing disjointness, vacuous universal patterns,
    name-implied semantics, double values on functional properties,
    language tags on numbers) flagged automatically, so that modeling
    discipline doesn't depend on prompt luck.

**Repl-researcher / agent consumer:**
19. As an agent author, I want ontology query tools (search,
    neighborhood, existence, absence, classification) available as
    sandbox primitives, so that my repl-researchers can ground their
    reasoning in the graph.
20. As an agent author, I want my agent to receive a graph orientation
    card (purpose, schema digest, content sample, tool affordances) when
    granted ontology tools, so that it explores intelligently instead of
    blind — exactly like a large-document preview.
21. As an agent author, I want absence checks (closed-world "is there no
    X related to Y?") available, so that exclusion-style questions
    (vegan recipes, unreviewed items) are answerable.

**ORC itself (the self-improving builder):**
22. As the ORC project, I want the builder's discovery phases to be
    recursive-RLM tasks using the same ontology tools, so that extraction
    design adapts per source instead of being hard-coded.
23. As the ORC project, I want bench-proven extraction patterns shipped
    as an ontology-discovery seed corpus, so that when a consumer opts
    into self-improvement, the builder's designs get better with use —
    and the patterns are data, not hand-edits.
24. As the ORC project, I want a determinism knob (pin a recorded
    discovery tree), so that consumers needing reproducible builds can
    have them without giving up adaptive discovery elsewhere.
25. As the ORC project, I want the old extraction sheets kept as a
    regression baseline until the new path beats them on the extraction
    bench, so that the rebuild is verified, not vibes.

**Downstream validator / interop:**
26. As an external auditor, I want the ontology's validation shapes
    exported as standard SHACL alongside its TTL, so that I can re-verify
    the graph with pySHACL/GraphDB without ORC in the loop.
27. As a data engineer with SPARQL workloads, I want a complete,
    faithful TTL export I can load into any triplestore, so that heavy
    analytical queries have a supported path without ORC growing a query
    language.
28. As an integrator, I want concepts to optionally carry model-guidance
    annotations, so that LLMs consuming the graph downstream get usage
    hints the way humans get comments.

**Cross-cutting:**
29. As any consumer, I want every write to flow through commands and
    events with the graph as a cached projection, so that the entire
    ontology is auditable, rebuildable, and consistent with how the rest
    of my Grain app works.
30. As any consumer, I want ordered sequences from my sources (steps,
    episodes, stages) represented traversably in the graph, so that
    order-dependent knowledge survives extraction.
31. As any consumer, I want multilingual labels supported in the schema
    from day one, so that adding a second language later is data entry,
    not a migration.
32. As any consumer, I want per-signal contribution caps in fused
    retrieval, so that one over-expanding signal cannot drown the others.

## Implementation Decisions

### Cross-cutting invariants (bind every module)

- **Events-first:** all writes are commands → schema-validated events;
  the usable graph is a cached projection (Grain's two-tier
  watermark-incremental read-model cache); TTL export is a view; TTL
  ingestion is event decomposition. Nothing bypasses the event store.
- **The R-Inject layer is THE self-improvement opt-in boundary.** LLM and
  repl-researcher use inside the ontology mechanism is unconditional
  (budget-knobbed); what's optional is auto-classification + corpus
  prepend + judges + living-description evolution. Consumers can use the
  ontology "as a database" with all of that dormant.
- **Recursive-only RLM** for all new repl-researcher work; terminal mode
  is slated for eventual retirement.
- **Retrieval primacy:** graph BFS + embeddings + ColBERT (RRF-fused,
  signal-configurable) IS the retrieval stack. SPARQL exists only at the
  boundary (export escape hatch); no SPARQL engine inside ORC; no
  embedded DL reasoner; no open-world inference.
- **Axioms are data + lint rules, never an inference engine.**
- Retrieval-facing text remains self-contained; no hardcoded phrase
  matching as quality gates; default model gemini-3-flash-preview;
  reuse existing retry primitives.

### M1 — Retrieval scoping + alignment registry

- Ontology-id scoping becomes uniform across ALL retrieval signals;
  graph BFS gains the same default scoping the embedding and ColBERT
  signals already enforce.
- Multi-section queries are an explicit opt-in that widens all three
  signals together, preserving ranking fusion for cross-section results.
- An alignment-section registry records which alignment sections serve
  which primary sections; queries auto-widen through the registry so
  consumers benefit from alignments without knowing their IDs.
- The remaining unscoped query functions (URI lookups, hierarchy
  accessors, statistics) gain the same optional scoping parameters.

### M2 — Representation schema bundle (additive, eight parts)

1. Language-tagged multiple labels (and comments), existing single-label
   field retained for compatibility.
2. Datatyped attribute values where typed.
3. Quantity + unit structured values for numeric data with units.
4. Ordered-sequence convention: a direct "immediately-follows" predicate
   plus a transitive-marked "follows" predicate (graph-native; no RDF
   list vocabulary).
5. Edge metadata promoted to named optional fields — confidence class
   (extracted/inferred/ambiguous), evidence quotes, valid-from/valid-to
   — and serialized on export (today's open metadata bag is never
   exported).
6. Axioms-as-data events: disjointness sets, property characteristics
   (functional/transitive/symmetric/inverse), property hierarchies, and
   chain definitions — projected into the graph, consumed by lints and
   traversal, exported as proper OWL.
7. Equivalence events carrying a kind discriminator —
   `:same-as | :equivalent-class | :equivalent-property` — tagged to the
   alignment section. (Individual-level vs class-level identity have
   different semantics; the dedup verdict must say which it asserts.)
8. Annotations: comment distinct from definition, see-also,
   is-defined-by, model-guidance (LLM-facing usage hints), and
   ontology-level metadata (title, version, license, creator).

### M3 — TTL round-trip adapter

- TTL ingestion is a first-class write adapter: parse → decompose into
  the standard event vocabulary (concepts, relationships, axioms,
  annotations, equivalences) → same projection/embedding/indexing as any
  other write.
- Re-export from those events must reproduce a semantically equal TTL —
  triple-set equivalence, since serialization ordering varies.
- This defines the brownfield onboarding path precisely: existing graphs
  enter as events and grow like everything else. The reasoning/embedding
  enrichment pass operates on the ingested events like any other write.
- Prototype-grade gate (from the grill): `ingest(ttl) → events → export
  ≍ source`.

### M4 — Lint registry + EDN-SHACL validator

- Validation shapes are SHACL-shaped EDN — source of truth, Malli-
  validated, interpreted in-JVM, consumer-authorable. Sketch (from the
  grill, decision-rich parts only):

  ```clojure
  {:shape/type :node-shape
   :target-class <uri-or-class>
   :severity :violation            ; :info | :warning | :violation
   :message "..."
   :deactivated false
   :property [{:path <predicate>
               :min-count 1 :max-count 1
               :qualified-value-shape {...} :qualified-min-count 1
               :not {...}}]
   :code <predicate-fn>}           ; escape hatch for shapes standard
                                   ; SHACL cannot express
  ```

- Interpreter subset phased: counts/severity/message/deactivated/not →
  qualified value shapes → the `:code` escape hatch covers
  naming-convention and roles-vs-classes lints.
- Built-in lints and consumer shapes live in ONE registry; all violations
  are emitted as events so validation health is queryable history.
- Export: shapes emit as real SHACL TTL alongside the ontology's TTL, so
  external tools re-validate independently. TTL-shape ingestion is
  deferred until demanded.
- Built-in lint set (each traceable to course-verified failure modes):
  disjointness violations, missing-disjointness warnings on sibling
  sets, universal-without-existential patterns, closure-axiom absence on
  complete enumerations, roles-vs-classes, naming-convention
  consistency, name-implied-semantics check, functional-property double
  values, language tags on non-linguistic literals, dangling
  relationship endpoints, single-parent assertion discipline.

### M5 — Dedup cascade + check-before-mint

- Tiered, cheapest-first: exact normalization → entropy gate → MinHash/
  LSH blocking → string-similarity verification → focused LLM merge/keep
  verdict ONLY in the ambiguity band.
- Hard guards: disjointness KEEP-guard is the first gate (concepts under
  disjoint classes never merge); the LLM verdict carries the explicit
  "differ in any number, negation, or entity → KEEP" rule.
- Verdicts output the equivalence KIND (M2 #7) — merge-as-same-individual
  vs equivalent-class vs distinct — and numeric/negation differences are
  positive evidence for "different-from", not merely evidence against
  merging.
- Type-based blocking (already proven in one sheet) is promoted to all
  paths.
- Concept-pair co-occurrence counts are RECORDED as events from day one;
  using them for context-aware disambiguation activates later when data
  has accumulated.
- Check-before-mint: at the builder's graph-merge stage, candidates are
  searched against the deployment's other sections (and, later, external
  vocabularies); on a hit, the equivalence is recorded into the
  alignment section instead of minting a duplicate.

### M6 — Evidence Tier-1 (deterministic, always-on)

- The compare-to-existing path maintains, for free: evidence-count
  (re-encountering a concept in a new source IS evidence), source refs,
  last-reinforced-at, and contradiction markers when new info conflicts
  with stored fields (never silent overwrite).
- Tier-2 (LLM-driven freshness trends, refine-not-overwrite definition
  evolution, contradiction-resolution proposals) is ontology-mechanism
  functionality with its own budget/cadence knobs — NOT gated on the
  R-Inject opt-in — and ships in the NEXT tail.

### M7 — ORSD + competency questions

- Build spec formalized as an ORSD-shaped map (from the grill):

  ```clojure
  {:purpose "..." :scope "..."
   :intended-uses [...]
   :competency-questions ["..."]
   :natural-language-statements ["..."]
   :non-functional {...}}
  ```

- Stored WITH the ontology via the proven descriptions pattern (command
  → ontology-id-tagged event → current+history projection): a
  record-ontology-spec command, zero new machinery.
- CQs are the ontology's persistent contract: injected into discovery
  context at build time (scoping), and re-evaluated on every grow cycle.
- CQ evaluation: retrieval assembles evidence (BFS neighborhood +
  hybrid search), an LLM judge scores answerability; per-CQ result event
  shape: `{:cq-index :answerable? :confidence :evidence-uris :gaps
  :evaluated-at}`. Pass-rate over time = the ontology's health metric.
- Negation posture (three layers, cheapest-first): the judge is the
  closed-world evaluator over fetched evidence (primary); deterministic
  helpers `exists?`, `absent-in-graph?`, `filter-by-label-pattern` for
  simple cases; export-to-triplestore for genuinely heavy workloads.

### M8 — Builder rebuild (hybrid, Path 2)

- **Deterministic skeleton (hand-authored, owns substrate contracts):**
  source parsing/normalization with content-hashing (skip unchanged
  sources), the M5 dedup cascade + check-before-mint, the M4 validation
  pass, event emission, embedding, ColBERT indexing.
- **Discovery phases (recursive-RLM only):** structure analysis →
  entity-type discovery → relationship/axiom discovery. The model
  designs extraction per source, using M9's ontology tools to
  interrogate the existing graph interactively.
- **Seeds, not hand-edits:** bench-proven patterns (per-section map-each
  with bounded concurrency, adversarial verify-then-finalize,
  hierarchical synthesis) are authored as ontology-discovery tree-class
  seeds. Discovery guidance encodes the course-derived discipline:
  closure axioms when sources enumerate completely, single-parent
  assertion, value-partitions for enum-shaped attributes,
  roles-vs-classes, goal/scope context from the ORSD, sequence and
  quantity+unit capture, statement-level provenance quotes.
- **Self-improvement strictly opt-in:** with R-Inject off, discovery
  still runs full recursive-RLM — it just doesn't learn across runs.
  With it on, the loop improves the builder's own designs over time.
- **Determinism knob:** pin a recorded discovery tree for reproducible
  builds.
- **Old sheets are the regression baseline** until the RLM path beats
  them on the extraction bench (Testing Decisions), then they remain as
  fallback until deliberately retired.

### M9 — RLM ontology tools + graph orientation card

- Tool primitives (builder-facing subset first): graph-search (hybrid,
  scoped), neighborhood (BFS expand), get-concept, exists?,
  absent-in-graph?, filter-by-label-pattern, classify-task/behaviors.
- The orientation card — injected whenever the tools are granted — four
  layers: identity (ORSD, ontology metadata, section + alignment
  registry), T-Box digest (scopes, classes with counts, predicates with
  counts + characteristics, axiom summary), content sample (top-N
  concepts by degree/evidence + representative neighborhoods),
  tool affordances with worked one-liners.
- Generation: deterministic skeleton from projections (always current,
  cached, refreshed on reindex); optional budget-knobbed LLM prose layer
  (the Tier-1/Tier-2 split applied to previews).
- General sandbox exposure (any consumer's repl-researchers) follows in
  the NEXT tail; CQ evaluation may itself become a recursive-RLM task
  using these tools.

### M10 — NOW rider

- Per-source contribution caps before RRF fusion (small retrieval
  robustness fix, independent of everything else).

### Phase tail (NEXT, after the rebuild stabilizes)

Tier-2 LLM concept consolidation · derived binary-chain query-time
synthesis (transitive hierarchy stays traversal-time — already free;
audited learned rules as derived-edge events with source-edge cascade
retraction) · ambiguous-edge HITL review surfaces · temporal time-window
retrieval · Leiden communities + per-community summaries (feeding the
orientation card's content layer) · affected-set incremental maintenance
· general D11 sandbox exposure · orientation-card prose layer ·
TTL-shape ingestion.

## Testing Decisions

- **What a good test is here:** behavior through public interfaces,
  integration-style — build/ingest/query/validate through the same
  commands and fns consumers use; tests survive internal refactors. No
  mocking the event store; mocks only for LLM calls in unit tiers, with
  live runs mandatory before any module is declared done (synthetic
  green is floor, live verify is ceiling — established project
  discipline).
- **All ten modules get coverage**, mirroring named prior art: the
  descriptions-event test pattern for M7's spec/CQ events; the
  consolidation-trigger pattern for M6's counters/markers; the colbert
  commands-test pattern for M3's adapter command; the reindex-processor
  pattern for M9's card refresh; the recursive-rlm test pattern for
  M8's discovery phases; existing retrieval/classifier test files for
  M1's scoping (including a regression test proving BFS no longer
  crosses sections un-asked, and one proving multi-section queries keep
  fusion).
- **Hard gate G1 (gates M2+M3):** the round-trip test — a fixture TTL
  exercising every bundle feature (multilingual labels, datatypes,
  quantities, sequences, edge metadata, axioms, equivalences,
  annotations) ingests to events and re-exports to a semantically equal
  triple set. This single test pins the schemas AND the serializer.
- **Hard gate G2 (gates M8):** the extraction bench — sources with
  known-good expected graphs, scored on concept/relationship precision +
  recall, with the old sheets as the baseline the RLM path must beat.
  Bench runs are real (live LLM + ColBERT), per the
  hand-authored-needs-real-task-verification rule.
- M4's lints each get positive + negative fixtures (a graph that
  violates, a graph that passes); consumer-authored-shape tests prove
  the registry treats external shapes identically to built-ins.
- M5's cascade gets adversarial fixtures: near-duplicates that MUST
  merge, look-alikes that MUST NOT (number/negation/entity variants,
  disjoint-class pairs), with verdict-kind assertions.

## Out of Scope

- **Grain-style project decomposition** (orc-ontology / orc-core /
  orc-judges / etc. carving) — confirmed strategic direction, but it
  proceeds audit-first under its own future PRD.
- A SPARQL endpoint or query engine inside ORC.
- An embedded DL reasoner / open-world inference of any kind.
- Site-registry disposition (dormant built-in application; revisit when
  agent-browser tooling lands).
- The downstream consumer-repo ADR reconciliation (lands in that repo).
- Multi-language EXTRACTION work (the schema admits language tags from
  day one; extracting non-English sources is future work).
- The publishing pipeline (docs generation, permanent identifiers,
  prefix-collision checks) — recorded direction; export schema must not
  preclude it.
- Rolling judges / evaluation-component changes (judges are consumed
  as-is by CQ evaluation).

## Further Notes

- Three resource PDFs remain pending-confirmatory (LOT methodology
  paper, ORSD worked solution, course glossary + cheatsheets) — blocked
  by a tooling outage during research; their substance is covered by
  transcripts already synthesized. Fold-in may add color, not
  decisions.
- The user is continuing ontology coursework; further classes feed
  additional grill rounds. This PRD is amendable — new candidates enter
  via grill records and re-tiering, not ad-hoc scope creep.
- Module M8's seed corpus authoring must respect the
  descriptions-self-contained rule: seed bodies carry substance
  verbatim, no internal slice names or file paths.
- When the ARCHITECTURE-ONTOLOGY.md revision happens (post-first-arc),
  it gains: the round-trip invariant, the retrieval-primacy statement,
  the reification-sidestep advantage, and "not restriction-based
  inference" under what-the-substrate-is-NOT.
