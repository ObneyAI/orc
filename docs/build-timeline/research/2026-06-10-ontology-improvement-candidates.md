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

- ProtegeOWLTutorial PDF distillation (tool outage) — expected to reinforce
  B3-B6 with additional pitfall detail (existential/universal traps, covering
  axioms, OWA consequences); fold in when extraction unblocks.
