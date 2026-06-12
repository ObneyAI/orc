---
type: research-synthesis
date: 2026-06-10
session: ontology-improvement-candidates
status: draft (pre-grill)
branch: feature/ontology-architecture
inputs:
  - development/bench/predict_rlm_comparison/reports (modern RLM tree patterns)
  - development/bench/r_inject_reports
  - components/ontology/src/.../sheets/* (current builder pipelines)
  - area_51/ontology_exploration/HINDSIGHT_ANALYSIS.md (prior analysis — conclusions adopted, not redone)
  - github.com/vectorize-io/hindsight (cloned /tmp/hindsight)
  - github.com/safishamsi/graphify (cloned /tmp/graphify)
  - Ontology-engineering course, class 1 (KM foundations + iOD methodology + OWL semantics)
  - ProtegeOWLTutorialP4_v1_3.pdf — PENDING (extraction blocked by tool outage; class transcript covers the same OWL-methodology ground; fold in when unblocked)
next: /grill-with-docs round 2 → PRD → issues
---

# Ontology Improvement Candidates — Research Synthesis

Improvement/gap candidates for the general-purpose ontology substrate and the
evolutionary builder, synthesized from bench evidence, two external
graph-knowledge systems, a prior internal analysis, and formal
ontology-engineering methodology. Organized in three buckets. Each candidate:
what it is → source → the gap it addresses. Priorities deferred to the grill.

---

## Bucket A — Builder tree modernization (bench evidence vs shipped sheets)

The 5 builder sheets are older-style sequential LLM chains
(extract → classify → define → relate → validate → serialize). Modern
RLM-designed trees in bench are propose-verify-refine designs. Verified gaps:

| # | Candidate | Evidence |
|---|-----------|----------|
| A1 | **Per-section `:map-each` with bounded `:max-concurrency` in extraction phases.** Sheets run single-pass `:llm` over the whole source; bench trees chunk + parallelize everywhere (per-page redaction, per-section legal issues). Large sources face attention/token degradation today. | json_ontology.clj:368-386 single-pass vs every bench report |
| A2 | **Adversarial verify-then-finalize second pass.** Sheets validate at the end (quality issues noted, at best conditional auto-fix) but never re-extract with priors. Bench's two-pass pattern (propose → adversarial re-read with prior results) catches misses. | json_ontology.clj:434-448; ontology_exploration.clj:494-504 |
| A3 | **Deterministic `:code` structural validation.** Guarantee invariants cheaply: every relationship endpoint references an extracted concept; no orphan URIs; enum-constrained fields. ontology_exploration's causal validation (149-188) is the seed of this — generalize to the whole output. | bench redaction's apply/verify loop |
| A4 | **Shared pre-built code library across sheets.** build-tbox / build-abox / serialize / dedupe are reimplemented inline per sheet (csv/json/sql each have their own). Extract to shared fns; sheets reference by qualified symbol like bench trees do. | json_ontology.clj:183-246 et al. |
| A5 | **Hierarchical synthesis for large aggregates** (>50 concepts) instead of one-shot serialization synthesis. | bench contract-comparison 4-stage synthesis |
| A6 | **OPEN DESIGN QUESTION: hand-authored pipeline vs recursive-RLM builder.** The sheets predate mature RLM mode. Should the builder become a recursive-RLM task (goal-only, model designs the extraction tree per source, with the corpus seeding builder-patterns)? Or a hybrid: deterministic skeleton (parse, dedupe, serialize) + RLM-designed discovery phases? Bench proves models design good extraction trees; the self-improving loop could then improve the builder itself. | the whole bench suite |

What the sheets already do well (preserve): typed blackboards everywhere,
type-based blocking dedup (ontology_exploration.clj:94-102), causal-relation
validation + normalization, ChainOfThought reasoning fields (currently written
but never read — A2 would finally consume them).

---

## Bucket B — Methodology layer (ontology-engineering course)

The course's iOD methodology (Goal & Scope → Information Gathering → Initial
Structuring → Formalization → Deployment → Evaluation) maps onto the builder,
which today implements roughly the middle (gathering → structuring →
formalization-lite) with nothing at either end.

| # | Candidate | What it adds |
|---|-----------|--------------|
| B1 | **Competency questions (CQs) as first-class build artifacts.** Optional `:competency-questions` on `build-ontology-from-sources`. At build time they scope/shape discovery prompts; at evaluation time the built graph is queried per-CQ and an LLM judge scores answerability. This is the course's scope-setting + evaluation device, and it is exactly ORC-shaped (rolling judges against CQs; scores feed the self-improving loop). | Closes both ends of the methodology loop |
| B2 | **Goal & Scope params.** Optional `:domain-of-interest`, `:aims`, `:out-of-scope`, `:assumptions` on the build call — injected into discovery prompts (bounding extraction) and recorded as build provenance. Cheap, high-leverage prompt context. | The builder currently discovers without declared purpose |
| B3 | **Roles-vs-classes discipline in discovery prompts.** The classic taxonomy mistake (subclassing Person into Employee/Student instead of Role instances) is exactly the kind LLM extraction makes. Encode the heuristic + a `:code` lint (class whose instances obviously change membership over time → flag). | Course's taxonomy-integrity rule |
| B4 | **Disjointness assertion pass.** Sheets never emit disjointness. A post-discovery LLM pass proposing disjoint sibling sets (+ event support for storing them) materially improves graph semantics and later validation. | Course: disjoint siblings are load-bearing for correct classification |
| B5 | **Primitive vs defined classes — DECIDE formality level.** The course's heavyweight apparatus (restrictions, necessary-and-sufficient conditions, automated classification, OWA reasoning). QUESTION for grill: do we want restriction-style definitions in the substrate, or is lightweight + retrieval-first the right ceiling, with TTL export as the bridge to heavyweight external tools? Per-ontology formality knob? | Avoid cargo-culting OWL rigor where retrieval is the actual use |
| B6 | **Naming-convention enforcement.** One convention per ontology (CamelCase or snake), validated by `:code` lint at build time. Trivial; prevents silent URI inconsistency across sources/builds. | Course pitfall list |
| B7 | **Statement-level provenance ("term pool" trail).** Carry the source statements/quotes that generated each concept as evidence fields. Partially exists (source-id); the course's statements-log discipline says keep the actual sentences. Feeds C5's review queue and B1's CQ evaluation. | Documentation + auditability |
| B8 | **Evaluation → next-cycle feedback (lessons learnt).** The course closes Evaluation back into Goal & Scope. Substrate translation: per-ontology quality observations that persist and shape the NEXT build/grow cycle — i.e., extend the Living-Descriptions consolidation pattern beyond ORC self-learning to built ontologies themselves. | The loop exists for tree design; not for ontology builds |
| B9 | **Abstraction levels (foundational/middle/user).** When multiple ontologies coexist, shared mid-level concepts (events, roles, temporal notions) could be reused across ontology-ids rather than re-discovered per build. Light-touch: a shared "common" ontology section consulted during compare-to-existing. | Cross-ontology reuse; dedup across sections |

---

## Bucket C — Substrate mechanisms (hindsight + graphify + HINDSIGHT_ANALYSIS)

| # | Candidate | Source | Gap addressed |
|---|-----------|--------|---------------|
| C1 | **Co-occurrence-backed entity disambiguation.** Persist concept-pair co-occurrence counts (events); use as context signal when compare-to-existing hits ambiguous names. | hindsight entity_resolver.py | Dedup compares labels/embeddings without contextual evidence |
| C2 | **Evidence counts + freshness trend on concepts.** proof-count, source refs, history, stable/strengthening/weakening/stale trend; refine-not-overwrite on new evidence. Direct extension of our living-descriptions evidence-count pattern to general concepts. | hindsight consolidation/ | Nothing tracks whether a definition still holds or how supported it is |
| C3 | **Focused LLM merge/keep verdict for near-duplicates.** Embedding probe → top-5 → tiny per-pair LLM call with explicit "differ in any number/negation/entity → KEEP" guard. | hindsight _DEDUP_PROMPT; graphify _llm_tiebreak | One-shot dedupe risks collapsing genuinely distinct concepts |
| C4 | **Tiered dedup cascade.** exact-norm → entropy gate → MinHash/LSH blocking → Jaro-Winkler → LLM only in the ambiguity band; + type-based blocking (already shipped in ontology_exploration — promote to all sheets). | graphify dedup.py; HINDSIGHT_ANALYSIS #2 | LLM/embedding cost paid on pairs cheap string methods settle |
| C5 | **Edge confidence labels + ambiguous-edge review queue.** EXTRACTED / INFERRED / AMBIGUOUS provenance class on relationships; AMBIGUOUS edges surface to HITL review. | graphify ARCHITECTURE.md | Relationships are unweighted assertions; no triage for shaky inferences |
| C6 | **Temporal validity on concepts/relations.** occurred/valid start-end, supersession visibility, "as of when" retrieval. | hindsight temporal_extraction; HINDSIGHT_ANALYSIS #4 | Continuously-growing graph has no time dimension |
| C7 | **Community detection + per-community summaries.** Leiden over the concept graph → emergent sub-domains → summary docs as retrieval units (GraphRAG-style) + same-community dedup prior. | graphify cluster.py/wiki.py | No emergent structure or summarization layer between concept and whole-graph |
| C8 | **Incremental affected-set maintenance.** Content-hash sources; re-extract only changed; BFS typed edges for the impact set needing re-embed/re-define. | graphify cache.py/affected.py | Builder is batch-oriented; continuous growth needs change-driven partial rebuilds |
| C9 | **Per-source caps before RRF fusion.** Cap each signal's contribution so one over-expanding arm (graph BFS) can't crowd the fused pool. | hindsight fusion.py | Cheap retrieval robustness fix |
| C10 | **Causal/typed relations as first-class, everywhere.** causes/enables/prevents with evidence quotes. Already partially shipped (ontology_exploration causal pass; behavior:composes-into). Promote to all extraction paths + retrieval traversal. | hindsight link_creation; HINDSIGHT_ANALYSIS #1 | broader/narrower/related can't express reasoning chains |

---

## Cross-cutting observations

1. **The patterns rhyme.** Hindsight's observation-consolidation (C2) IS our
   Living-Descriptions consolidator pattern, independently evolved for general
   facts. B8 (evaluation feedback) and C2 are the same mechanism viewed from
   methodology and systems angles — strong signal it's the right next
   investment.
2. **A6 is the biggest fork in the road.** Modernizing the sheets in place
   (A1-A5) vs rebuilding the builder as recursive-RLM. The self-improving loop
   could then improve the builder itself — eating our own dog food at the
   substrate level.
3. **B1 (competency questions) is the sleeper.** It's cheap, it's
   methodology-canonical, it gives every built ontology an executable
   acceptance test, and it plugs directly into judges + the self-improving
   loop. Almost everything in C becomes more valuable when CQs define "good."
4. **HITL surfaces recur** (C5 review queue, B7 provenance). The
   obney-ops-workshop ratification machinery is the natural downstream consumer.

## Pending

- ~~ProtegeOWLTutorial PDF distillation (tool outage)~~ → **COMPLETE 2026-06-10
  (later that day).** Pages 1-108 read page-by-page. Reinforcements + new
  candidates folded into the appendix below.

---

## Appendix — ProtegeOWLTutorial reinforcements + new candidates

The Manchester tutorial (Horridge et al., v1.3) confirmed and substantially
**sharpened** the bucket-B candidates, plus surfaced six new ones rooted in
hard-won OWL practice. Strongest reinforcements first.

### Strong reinforcements

**B3 (roles vs classes) — VINDICATED.** Footnote p23: *"if we had named
HamTopping Ham, then this could have implied to human eyes that anything
that is a kind of ham is also a kind of MeatTopping... class names
themselves carry no formal semantics in OWL."* LLMs read implicit
membership into class names the reasoner never sees. The lint sharpens: flag
class names whose surface form implies relationships not asserted.

**B4 (disjointness) — RAISED TO LOAD-BEARING.** The ProbeInconsistentTopping
example (pp 49-53): without disjointness, defined-class classification
produces silently wrong answers. *"OWL Classes are assumed to 'overlap'. We
therefore cannot assume that an individual is not a member of a particular
class simply because it has not been asserted to be a member of that
class."* For LLM extraction this is the difference between meaningful and
meaningless inference. Disjoint-siblings extraction stops being a "nice
addition" and becomes **a correctness gate**.

**B5 (formality ceiling) — CONFIRMED with a load-bearing footnote.** Pp 36
on domain/range: *"the fact that domain and range conditions do not behave
as constraints and the fact that they can cause 'unexpected' classification
results... we generally advise against doing this."* Domain/range are
AXIOMS the reasoner uses for classification, NOT validators. Reinforces our
"axioms-as-data + separate :code lints" strategy — you cannot rely on
domain/range to validate extraction input.

### New candidates (PDF only)

| # | Candidate | Source | Gap |
|---|-----------|--------|-----|
| **B10** | **Closure axioms emitted by extraction.** When a source describes what reads as a complete enumeration (no "etc.", "such as", "including"), discovery emits a universal restriction `prop only (a ∪ b ∪ c)` alongside the existentials. Without this, downstream defined-class inference is silently incomplete. | pp 62-66 (the famous OWA + Margherita-as-Vegetarian example) | **THE biggest LLM-extraction trap.** A discovery LLM will list toppings/properties without saying "and only these" — the reasoner then can't classify the result. |
| **B11** | **Single-parent assertion discipline ("ontology normalisation").** Assert each class with at most one parent; let consumers/reasoners compute multi-inheritance. Discovery that finds multiple potential parents picks the most specific single one + emits the others as restrictions, not parents. | p57: *"construct the class hierarchy as a simple tree... no more than one superclass... helps to keep the ontology in a maintainable and modular state."* | LLMs will assert messy multi-parent hierarchies that bake in errors. |
| **B12** | **Value-Partition design pattern for enums.** Every enum-shaped field (severity, status, category, spiciness) → partition class + N disjoint covered subclasses + functional accessor property. The canonical OWL pattern for "exactly one of these." Currently we'd represent enums as Malli `[:enum ...]` which validates but doesn't surface in the graph. | pp 67-70 (the SpicinessValuePartition pattern, called out explicitly as "a design pattern... proven solution"). | Enum semantics lost on export; no graph-level reasoning over alternative values. |
| **B13** | **"Universal without existential = bug" lint.** If discovery emits `only` on property X without a corresponding `some` on X, flag — universal restrictions are vacuously satisfied by individuals with no relationship at all. | p99: *"particularly unusual (and probably an error)... only participate... and also those individuals that do not participate in any... relationships."* | Trivially-satisfiable extraction artifacts that look meaningful but aren't. |
| **B14** | **Annotation properties as first-class export.** rdfs:label, rdfs:comment, owl:versionInfo, owl:priorVersion, owl:backwardsCompatibleWith, rdfs:seeAlso, rdfs:isDefinedBy. Our `label`/`description`/`source-id`/`created-at` ARE annotation properties; surface them as such in TTL export. The owl:priorVersion/backwardsCompatibleWith pair is exactly hindsight's observation-history pattern in OWL form. | pp 95-97 + annotation-property semantics constraints ("cannot have domain/range") | TTL export today doesn't capture provenance/version metadata in a standards-compliant way. |
| **B15** | **Multi-language labels.** rdfs:label supports language tags (e.g. `"Pizza"@en`, `"Pizza"@it`). For consumers extracting from multi-lingual sources (international podcast/document corpora, area51 advisor experiences in mixed languages), real future need. | p96: *"rdfs:label can also be used to provide multi-lingual names for ontology elements."* | RECORDED. No near-term consumer requires it but the schema needs to admit it from day one to avoid retrofit. |

### Refinements to existing candidates

- **C4 (dedup cascade) gets a UNA twist.** Per p101: *"Cardinality restrictions
  rely on 'counting' distinct individuals... rather than being viewed as an
  error, it will be inferred that two of the names refer to the same
  individual."* Our dedup is also resolving the UNA question in our favor —
  the system DECIDES whether two names refer to the same thing, and that
  decision is recorded as a `sameAs` event. The cascade IS the UNA-resolver.
  This sharpens C3's "differ in number/negation/entity → KEEP" guard:
  numeric/negation differences are evidence for `differentFrom`, not just
  evidence-against-merge.

- **C10 (typed relations) gets property hierarchies.** Per pp 27-28: properties
  can have sub-properties. `hasTopping ⊑ hasIngredient` means asserting
  `A hasTopping B` automatically gives `A hasIngredient B`. For us: a
  property hierarchy on extracted relations (`composes-into` and
  `member-of` as sub-properties of a generic `relates-to`?) would compress
  retrieval traversal and give consumers a generic "any relationship"
  query.

### Methodology principles reinforced for the new doc

The tutorial's "ontology normalisation" + "open world + closure axioms" +
"disjointness is not optional" + "names carry no formal semantics" form a
coherent discipline that should land **explicitly in the ARCHITECTURE doc's
"what the substrate is NOT" section** when revised post-PRD: we are not
inferring class membership via DL restrictions, but we ARE relying on
disjointness + closure to make the data we extract internally consistent for
the consumer's downstream reasoning (whether that's our embedding-based
classification or an external Protégé/reasoner workflow).

### Tier placement for the new candidates

- **B10 closure axioms** → WITH-REBUILD. **Highest-impact correctness item
  in the new set** — extraction without closure produces silently incomplete
  ontologies, exactly the failure mode our consumers won't notice until they
  query.
- **B11 single-parent + B13 universal-without-existential** → WITH-REBUILD,
  ride the same :code lint library as B3/B4/B6.
- **B12 value partitions** → WITH-REBUILD for the schema, NEXT for full
  enum-discovery (model needs to recognize "enum-shaped" attributes).
- **B14 annotation-property export** → WITH-REBUILD. TTL export already has
  to be touched if any of A3/A4 land.
- **B15 multi-language labels** → RECORDED. Schema admits language-tagged
  values from day one; no extraction work until a consumer pulls.
- Refinements to C4/C10 → adopt within their existing tier slots.

The PDF reading **does not change** the Q6 triage's tier assignments — it
reinforces the WITH-REBUILD arc and confirms why B-bucket items belong
there.

---

## Appendix 2 — Class 2: Semantic Web course (RDF/SPARQL/RDFS/OWL/SHACL/LOT)

Added 2026-06-12. Second course fed in: Semantic Web stack end-to-end —
linked data principles, RDF/Turtle mechanics, SPARQL (all four query
types), RDFS, OWL property semantics in practice (chains, functional
dangers, alignment), **SHACL** (closed-world validation + advanced-feature
rules), and the **LOT methodology** (ORSD requirements documents,
competency-question formalization, ontology reuse, publishing). Concrete
artifacts read: `film_shacl_rules.ttl` (sh:TripleRule + sh:condition +
sh:SPARQLFunction vocabulary), `solution-tbox.ttl` (RDFS domain/range +
multilingual labels + seeAlso/isDefinedBy). LOT paper PDF + ORSD
solution + glossary/cheatsheets pending-confirmatory (tool outage;
transcript covers their substance).

### Major reinforcements

**Q3/B5 (axioms-as-lints, no reasoner) — VINDICATED BY THE FIELD ITSELF.**
The course's own arc lands exactly where round 2 did: RDFS domain/range
"are by no means constraints" (they *infer* classes onto subjects/objects
— the hasBrother example types Christopher Nolan as Person *in addition
to* Director rather than erroring); OWL functional properties under OWA
silently infer `sameAs` between the Wachowski sisters instead of flagging
the double assignment; the instructor's stated fix for ALL of it is
**SHACL — closed-world validation with severity levels**. Our
"deterministic :code lints, no DL reasoner" decision is the same
conclusion practitioners reached after getting burned by OWA.

**B1/B2 (CQs + goal/scope) — FORMALIZED BY LOT/ORSD.** The Linked Open
Terms methodology's Ontology Requirements Specification Document is the
canonical container for exactly what we adopted: purpose, scope,
implementation language, intended users, intended uses, **functional
requirements as competency questions + natural-language statements +
tabular concept/relation/attribute info**, non-functional requirements,
glossary. And LOT's evaluation step = "validate the CQs via SPARQL
queries against the built ontology" — our CQ → hybrid-search + judge
evaluation is the same move with LLM-era tooling.

**Data-first discovery posture — VALIDATED.** The instructor's personal
workflow: build an **exemplary ABox first** ("I do not focus on how the
Tbox is structured... I only focus on which data need to be in the
ontology"), then find reusable ontologies, then fill gaps with a new
TBox. Our evolutionary builder is data-first by construction — formal
methodology agrees.

**Event-sourced edges sidestep reification — ARCHITECTURAL ADVANTAGE to
state explicitly.** The course's treatment of statements-about-statements
(reification = complex + slow; RDF-star = better but OWL-incompatible;
"or use a property graph") is a problem we simply don't have: our
relationships are events carrying arbitrary metadata natively, so edge
confidence (C5), statement provenance (B7), and temporal validity (C6)
need no reification machinery. Goes in the ARCHITECTURE doc's "what the
substrate is NOT / advantages" section.

**Dedup verdict outputs need an equivalence-kind distinction.** Course
rule: `owl:sameAs` is for individuals ONLY; class-level identity uses
`owl:equivalentClass` (sameAs on classes causes unintentional inheritance
merging). Our dedup cascade verdicts (C3/C4) should record WHICH
equivalence kind they assert — concept-as-class merges vs
individual-instance merges are different events with different downstream
semantics.

**B15 (language tags) sharpened with a trap-lint.** Same literal with
different language tags = different values (the release-year@en vs @de
trap). Lint: language tags on non-linguistic values (numbers, dates,
codes) are extraction errors.

### New candidates (D-bucket)

| # | Candidate | Source | Gap addressed |
|---|-----------|--------|---------------|
| **D1** | **SHACL as the lint interchange format.** The A3 validation library's rules (B4 disjointness, B6 naming, B13 universal-without-existential, B3 roles-vs-classes, functional-double-value, language-tag misuse) compile to **SHACL shapes** as an export artifact alongside TTL: `sh:NodeShape`/`sh:PropertyShape` with `sh:severity` (Info/Warning/Violation), `sh:message`, `sh:deactivated`. Internal representation stays `:code`; SHACL is the standards-track interchange so any consumer can re-validate with pySHACL/GraphDB without ORC in the loop. | SHACL chapter + film_shacl.ttl artifacts | Lints today would be ORC-internal only; consumers get no portable validation contract |
| **D2** | **Derived-edge materialization (property-chain shortcuts).** OWL property chains (actsIn ∘ hasActor → collaboratedWith; recipe-step chains propagating usesIngredient up to the recipe) exist precisely to make queries cheap — the instructor: chains "make defining the Sparql queries much easier." Our version: deterministic, event-sourced derived edges (SHACL-TripleRule-shaped: subject/predicate/object + condition) materialized at write time or as a projection, so graph-BFS retrieval doesn't re-traverse chains. Extends round-2 C10's property hierarchies. | OWL property-chain lessons + sh:TripleRule in film_shacl_rules.ttl | Retrieval pays traversal cost for relationship patterns that could be precomputed |
| **D3** | **Alignment sections (link sets).** Keep cross-ontology equivalence statements (`equivalentClass`/`equivalentProperty`/`sameAs`) in a SEPARATE ontology section rather than polluting either aligned section — the course's alignment-ontology best practice mapped to our ontology-id scoping. Both sections stay clean; the alignment section is independently loadable/droppable. | Ontology-matching lesson | Cross-section links today would land inside one section, muddying both |
| **D4** | **Reuse pass in discovery (check-before-mint).** Before minting a new concept, the builder checks candidates against (a) other ontology sections in the same deployment (B9's shared mid-level) and (b) optionally, well-known external vocabularies (schema.org, QUDT for quantities/units, Time, FOAF, SKOS) — recording an equivalence into the alignment section (D3) instead of re-minting. The course's "always look for reusable ontologies first" discipline, automated. QUDT's quantity-value pattern is specifically relevant: extracted quantities should carry value + unit as a structured node, not a bare literal with implicit units. | Reuse lesson + recipe-project solution | Builder currently discovers everything from scratch; quantities lose their units |
| **D5** | **ORSD-shaped build spec.** B1+B2's optional build params formalized as an ORSD-shaped map: `{:purpose :scope :intended-uses :competency-questions :natural-language-statements :non-functional ...}` — stored with the ontology (persistent contract, extending round-2's B1 decision), injected into discovery, and usable later for auto-generated ontology documentation (Widoco-style). | LOT/ORSD lessons | Our params were ad-hoc; ORSD is the canonical container the field already converged on |
| **D6** | **Ordered-sequence extraction pattern.** Sequences in sources (steps, episodes, stages) get the graph-native pattern: `immediatelyFollows` (direct) + `follows` (transitive) object properties — NOT RDF list vocabulary (rdf:first/rest blank-node chains, which the course shows and immediately disclaims as painful). Discovery prompt guidance + a seed pattern. | RDF collections lesson + recipe solution's allotrope follows pattern | Extracted sequences today have no canonical representation |
| **D7** | **Model-guidance annotations on concepts.** The instructor mints a custom annotation property `ChatGPT_explanation` — LLM-facing guidance stored IN the ontology, surfaced when an AI consumes it. That's the Living-Descriptions insight arrived at from the opposite direction. Substrate-level: an optional `model-guidance` annotation field on any concept, included in retrieval payloads. Cheap, converges two traditions. | Annotation-properties lesson | Concept bodies carry definitions for humans; nothing standard carries guidance-for-models at substrate level |
| **D8** | **SPARQL interop posture: export-to-triplestore.** We do NOT build a SPARQL endpoint; consumers with SPARQL workloads export TTL (which must therefore be complete — reinforces B14) into GraphDB/any triplestore. Document the mapping: our BFS expansion ≈ DESCRIBE, projections ≈ CONSTRUCT views, existence checks ≈ ASK, stats fns ≈ aggregates. | SPARQL chapters | Heads off "build SPARQL into ORC" scope creep with a deliberate documented answer |
| **D9** | **Publishing pipeline.** For ontologies meant for external reuse: TBox/ABox file separation in export, Widoco-style doc generation (feeds from D5's ORSD + B14's annotations), w3id permanent identifiers, prefix-collision check (prefix.cc), HTTP-URI base mapping for 4-star linked-data compliance. | Publishing lessons + linked-data 5 stars | RECORDED — no consumer publishes yet, but export schema should not preclude it |

### Tier placement (D-bucket)

- **D1 SHACL lint export** → WITH-REBUILD (it's the A3 library's export
  face; designing lints SHACL-shaped from day one costs nothing extra)
- **D2 derived edges** → NEXT (needs the rebuilt write path stable;
  design alongside C10)
- **D3 alignment sections** → WITH-REBUILD (schema + scoping conventions
  land with the rebuild; trivially small)
- **D4 reuse pass** → WITH-REBUILD for cross-section check-before-mint
  (rides the dedup cascade); NEXT for external-vocabulary matching;
  QUDT-style quantity+unit structure WITH-REBUILD (extraction schema)
- **D5 ORSD build spec** → WITH-REBUILD (it's the final shape of B1+B2)
- **D6 sequence pattern** → WITH-REBUILD (discovery guidance + seed)
- **D7 model-guidance annotation** → WITH-REBUILD (one optional field)
- **D8 SPARQL posture** → documentation row in ARCHITECTURE doc revision
- **D9 publishing** → RECORDED

Class 2 also does not move any existing tier — it thickens WITH-REBUILD
and hands us standards-track vocabulary (SHACL severity/message/
deactivated; ORSD sections; alignment link sets) instead of inventing our
own.
