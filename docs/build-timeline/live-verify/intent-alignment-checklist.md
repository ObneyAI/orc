# Ontology Intent-Alignment Checklist

> **Purpose:** Capture the CANONICAL DESIGN INTENT for the ORC evolutionary-ontology
> system from its source-of-truth design docs, so a peer agent can verify it against
> what was actually built. READ-ONLY distillation. This file is NOT a code-verification
> report — the "Status hint" column records only how the *architecture doc / grills*
> treat each item (shipped / planned / aspirational / flagged-gap).
>
> **Sources (verbatim quotes cited by doc + section/line):**
> - **ARCH** = `docs/ARCHITECTURE-ONTOLOGY.md`
> - **R1** = `docs/build-timeline/grill-sessions/2026-06-10-orc-ontology-architecture.md`
> - **R2** = `docs/build-timeline/grill-sessions/2026-06-10-ontology-improvements-round-2.md`
> - **R3** = `docs/build-timeline/grill-sessions/2026-06-12-ontology-class-deep-dive-round-3.md`
> - **PRD** = `docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md` (DERIVED — cited only to flag narrowing)

---

## The Yardstick: User's One-Paragraph Intent → Four Pillars

> "We should be able to take any source like csv, sql, text, etc. and discover a general
> ontology about it that can be used for any ontology retrieval. We auto find fields that
> need embedded via embedding model or colbert and these embeddings can be used to help
> search the graph as well (using BFS and embeddings/colbert late interaction) to discover,
> learn, maintain, and access knowledge."

- **P1** — ANY SOURCE (csv/sql/text/json/…) → a GENERAL ontology usable for ANY retrieval (not overfit to self-learning).
- **P2** — AUTO-DETECT which fields/values need embedding, and route embedding-model vs ColBERT appropriately.
- **P3** — SEARCH the graph by FUSING BFS + embedding similarity + ColBERT late-interaction.
- **P4** — Full lifecycle: DISCOVER, LEARN, MAINTAIN, ACCESS knowledge.

---

## P1 — ANY SOURCE → GENERAL ontology for ANY retrieval

| Intent item | Verbatim quote + citation | Source | Status hint |
|---|---|---|---|
| The substrate (not the self-learning app) is the system; general-purpose framing is load-bearing | "ORC's ontology element is a **general-purpose ontology substrate**: an event-sourced concept graph that takes disparate data sources and turns them into a unifying structure which can be continuously added to, reasoned over, and retrieved from — by any application, for any knowledge domain." | ARCH "The Frame" L11-14 | Shipped framing (doc asserts it as current identity) |
| Self-learning taxonomy is ONE application, NOT the system | "Everything else that ships in the component — the failure/success taxonomy … — is an **application** of that substrate. … the docs to describe the application as if it were the system. It is not." | ARCH "The Frame" L16-20 | Framing decision (doc resolves; ONTOLOGY.md reframe is a HIGH gap, see Gap #1) |
| General-vs-self-learning frame, original source statement | "The failure/success stuff is the ontology system specific to self improving systems but the ontology system at its base is a general graph ontology builder." | R1 (epigraph, L29-31, quoting 2026-06-01 grill) | Aspirational→adopted (the claim that drove the whole arc) |
| Wide reading confirmed: builder is an ORC BT building general-purpose ontologies | "yes exactly b, it is meant to be basically another orc bt specialized in building general purpose ontologies that then can be used by the application in various ways." | R1 Q1 answer L67-69 | Decision (wide reading B) |
| The builder routes csv/json/sql/text/unified — generic multi-format front door | "`build-ontology-from-sources` routes a source to the matching extraction sheet: `csv_ontology`, `json_ontology`, `sql_ontology`, `ontology_exploration` (text), or `unified_ontology` (auto-detect). … This is the front door for 'I have heterogeneous data; build/grow my graph.'" | ARCH "Write-Side Adapters" #1 L111-116 | Shipped (described as existing 5 sheets) — but see RISK #1: PRD rebuilds these, OLD-sheets-as-baseline |
| Greenfield: build a general ontology from disparate sources from scratch | "Greenfield — no existing graph. The builder constructs the ontology directly from disparate sources. (daryls-area51: IPEDS + PSEO + wage data + O*NET unified from scratch.)" | ARCH "Onboarding Paths" L133-136 | Shipped framing / grounded in real consumer |
| PRD restates P1 as the problem statement | "a graph that takes disparate data sources (CSV exports, JSON APIs, SQL databases, raw text, existing RDF) and turns them into one unifying, continuously-growing structure" | PRD "Problem Statement" L22-26 | Planned (rebuild target) |

**P1 verdict on the csv/sql/text question:** The grills/ARCH commit that the builder
(the existing 5 sheets, soon rebuilt) handles csv/json/sql/text/unified generically — it
is NOT framed as "the old sheets' job that the new builder drops." The PRD M8 rebuild
explicitly keeps multi-format and keeps old sheets as a regression baseline (PRD L430-432).
**Status hint: ARCH treats multi-format generic build as SHIPPED; PRD treats the *rebuilt*
generic build as PLANNED with old sheets retained as fallback.**

---

## P2 — AUTO-DETECT embed-worthy fields; route embedding-model vs ColBERT  ⚠ MOST AT RISK

| Intent item | Verbatim quote + citation | Source | Status hint |
|---|---|---|---|
| **The precise commitment** — fields auto-reviewed for embed-worthiness and auto-embedded by default | "fields are reviewed to see if they would be good to embed for search purposes and goes ahead and automatically embeds/late embeds the relevant fields to make them semantically searchable by default." | R1 Q2 answer L102-105 (verbatim user) | **Decision** — grill verifies the intent at this entry point |
| ARCH restates auto-embed in Growth posture | "Fields are automatically reviewed for embed-worthiness and embedded (or late-embedded) so new knowledge is semantically searchable by default. Embedding models are swappable per scope (`configure-embedding-model`)." | ARCH "Conceptual Model" Growth-posture row L78 | Shipped framing |
| Auto-embed listed as a substrate primitive (one-liner) | "Growth (dedupe + auto-embed on write)" | ARCH diagram L37 | Shipped framing |
| Round-2 confirms LLM/repl-researcher review inside the mechanism is unconditional (the "review" step can be model-driven, never self-learning-gated) | "it absolutely should use llms/repl-researcher rlm/recursive and reasoning in order to arrive at our evolving ontology mechanisms" | R2 Q4 correction L114-119 | Decision (mechanism-level, not R-Inject-gated) |
| ARCH lists embedding models as swappable | "Embedding models are swappable per scope (`configure-embedding-model`)." | ARCH L78 | Shipped |

**⚠ P2 RISK FINDING — the embedding-model-vs-ColBERT ROUTING choice is the part most at
risk of being LOST:**

- The user's yardstick says: *"We auto find fields that need embedded via embedding model
  **or** colbert."* This implies an **automatic per-field decision between two embedding
  mechanisms (dense embedding model vs ColBERT late-interaction)**.
- The grills' verbatim language (R1 Q2 L102-105) commits only to **auto-detecting which
  fields to embed** and **auto-embedding/late-embedding** them. The phrase "late embeds"
  is the closest the grill gets to ColBERT-specific routing, but it does **NOT explicitly
  say the system CHOOSES embedding-model vs ColBERT per field.**
- ARCH (L76-78) treats embedding and ColBERT as **two retrieval signals fused at query
  time** (`:signals #{:graph :embedding :colbert}`), and treats auto-embed as a single
  write-time posture. **There is no quoted commitment anywhere that the WRITE side routes a
  given field to dense-embedding vs ColBERT-indexing based on an automatic per-field
  judgment.** ColBERT indexing appears as a flat pipeline stage ("embedding → ColBERT
  indexing" — ARCH L115-116; PRD M8 L412), not as a per-field branch.
- **Conclusion:** The grills/ARCH faithfully capture "auto-detect embed-worthy fields +
  auto-embed by default." They do **NOT** clearly capture the yardstick's "embedding model
  **OR** colbert" *routing decision*. A verifier should treat the embed-vs-ColBERT routing
  as **either an unstated intent the design narrowed away, or a feature folded silently
  into "auto-embed."** Flag for the user.

---

## P3 — SEARCH = BFS + embedding similarity + ColBERT late-interaction (fused)

| Intent item | Verbatim quote + citation | Source | Status hint |
|---|---|---|---|
| Default retrieval is RRF over graph BFS + embeddings + ColBERT, configurable per query | "A configurable signal stack fused by RRF. Default signals: graph BFS (spreading activation), dense embeddings (MiniLM 384-dim via DJL), ColBERT late-interaction. Any subset is selectable per query (`:signals` + per-signal `:weights` on `hybrid-search`)." | ARCH "Conceptual Model" Retrieval row L76 | Shipped (code-verified in R1: "retrieval.clj:833 already ships") |
| User's configurability sharpening (any subset usable) | "We are in fact using rrf as default with graph bfs + embedding + colbert but it should be possible that we can use whatever we want (ie JUST graph bfs or just embeddings or just colbert or graph + colbert …) … and each can be projected from the event store." | R1 Q2 answer L96-105 | Decision (configurable signal stack) |
| Code verification of configurability | "`hybrid-search` (retrieval.clj:833) already ships `:signals #{:graph :embedding :colbert}` subset selection + per-signal `:weights` + `:ontology-id` scoping. The configurability intent is shipped at this entry point." | R1 Q2 "Code verification" L112-116 | Shipped (grill asserts code-verified) |
| **Retrieval primacy (R3)** — BFS + embeddings + ColBERT IS the stack; SPARQL only at boundary | "remember i think the primary retrieval is graph bfs and colbert/embedding so we need to keep in mind how we are using sparql if we are and if these are tool sets that can be given to repl researchers" | R3 Q5 answer L125-128 (verbatim user) | Decision (retrieval primacy) |
| Retrieval primacy captured as principle | "retrieval primacy — BFS + embeddings + ColBERT IS the stack; SPARQL exists only at the boundary (export escape hatch + capability checklist)." | R3 Q5 "Captured" L130-132 | Decision |
| "We don't rebuild SPARQL" principle | "we don't rebuild SPARQL; retrieval assembles honest evidence and the judge does query-language work." | R3 Q5 principle L123-124 | Decision (negation via judge, not SPARQL) |
| Keyword signals (BM25/SPLADE) are PLANNED additions to the same stack | "Keyword signals (BM25/SPLADE) are planned additions to the same stack." | ARCH L76 | Planned (also Gap #4, MEDIUM) |
| **R3 critical discovery: BFS does NOT scope by ontology-id** (isolation leak + fusion loss) | "embedding + ColBERT retrieval filter by `ontology-id`, but graph BFS does not — `expand-concept-neighborhood` walks the merged concepts graph across ALL sections." | R3 Q1 L33-39 | **Flagged GAP** — gap row #2 promoted to HIGH; PRD M1 fixes it (PLANNED) |
| Fix decided: uniform ontology-id scoping across all 3 signals | "yes a" → "(A) with registry-driven auto-widening. Gap-matrix row #2 … promoted to HIGH and absorbs the BFS fix" | R3 Q1 answer L41-49 | Decision / PLANNED (PRD M1 L260-271) |

**P3 verdict on "retrieval primacy":** Round 3 commits precisely that **BFS + embeddings +
ColBERT (RRF-fused, configurable) IS the retrieval stack**, that **SPARQL lives only at the
export boundary** (no SPARQL engine in ORC), and that the retrieval surface should become
**repl-researcher sandbox tools (D11)**. ARCH treats the fused stack as SHIPPED; the
ontology-id-scoping fix for BFS is a HIGH gap the doc admits and the PRD plans.

---

## P4 — Full lifecycle: DISCOVER · LEARN · MAINTAIN · ACCESS

| Verb | Intent item | Verbatim quote + citation | Source | Status hint |
|---|---|---|---|---|
| **DISCOVER** | Builder LLM-discovers ontology structure from sources | "the write-side adapter (itself an ORC behavior tree) that LLM-discovers ontology structure from sources." | ARCH Vocabulary "Evolutionary builder" L102 | Shipped (sheets); rebuild PLANNED (PRD M8) |
| **DISCOVER** | Discovery pipeline stages enumerated | "discovery → comparison-to-existing → dedupe → definition generation → relationship discovery → embedding → ColBERT indexing." | ARCH Write-Side Adapters #1 L115-116 | Shipped framing |
| **LEARN** | The graph gets richer only because it can be generally reasoned over and added to | "the graph gets richer and richer, allowing more informed downstream use — but only because the ontology can be generally reasoned over and added to." | ARCH "The Frame" L63-65 | Framing (primary goal statement) |
| **LEARN** | Original goal statement | "this is only possible if the ontology can be generally reasoned over and added to. That is the primary goal of the ontology system." | R1 Q1 answer L84-86 (verbatim user) | Decision |
| **LEARN** | Self-improving loop = optional cross-run learning (R-Inject layer is THE opt-in) | "The opt-in bundle is precisely the R-Inject layer … OFF: builder runs fully, just doesn't learn across runs. ON: the builder gets better and better." | R2 Q4 corrected model L128-134 | Decision (opt-in boundary) |
| **MAINTAIN** | Continuously addable + dedupe + auto-embed | "Continuously addable. New writes are compared against the existing graph and deduplicated. Fields are automatically reviewed for embed-worthiness and embedded …" | ARCH Growth-posture row L78 | Shipped framing |
| **MAINTAIN** | Evidence/freshness Tier-1 deterministic, always-on | "Tier 1 deterministic evidence tracking in compare-to-existing (evidence-count, source refs, last-reinforced-at, contradiction markers; always on, free)" | R2 Q4 L102-106 | Decision; PRD M6 (Tier-1 PLANNED, Tier-2 in NEXT tail) |
| **MAINTAIN** | CQ pass-rate over time = the ontology's health metric | "CQ pass-rate over time = the ontology's health metric." | R2 Q2 L76 | Decision; PRD M7 (PLANNED) |
| **MAINTAIN** | Incremental maintenance (content-hash now; affected-set later) | "C8 incremental maintenance | WITH-REBUILD (source content-hashing); NEXT (affected-set BFS re-embed/redefine)" | R2 Q6 triage L163 | Split: content-hash WITH-REBUILD, affected-set NEXT |
| **ACCESS** | Scoped, configurable-signal retrieval (see P3) | "Query: scoped, configurable-signal retrieval" / `hybrid-search` example | ARCH Integration Surface L227-232 | Shipped |
| **ACCESS** | MCP knowledge gateway makes any section queryable externally | "13 MCP tools exposing read-side surfaces … Makes any ontology section queryable from outside the JVM." | ARCH Built-In Apps #2 L179-182 | Shipped |
| **ACCESS** | Retrieval surface as repl-researcher sandbox tools (D11) | "NEW CANDIDATE D11: ontology tools for repl-researchers — the retrieval surface as sandbox primitives (`graph-search`, `neighborhood`, `get-concept`, `exists?`, `absent-in-graph?`, `filter-by-label-pattern`, `classify-*`)." | R3 Q5 L133-141 | Planned (PRD M9: builder-facing subset WITH-REBUILD, general exposure NEXT) |

---

## The Events-First Round-Trip Invariant (R3 — load-bearing)

| Intent item | Verbatim quote + citation | Source | Status hint |
|---|---|---|---|
| **User's verbatim events-first + TTL round-trip statement** | "it is super important to maintain grain and orc disciplines with commands and events for instance when we are building the graph we do so with event schemas which then can be projected and cached as a usable graph or exported to ttl or if someone comes in with a ttl we should be able to take it apart into the format grain/orc needs and could reproject that same ttl from the ingestion events the same way as exporting a ttl built from disparate data sources events" | R3 Q2 answer L64-71 (verbatim user) | **Decision** (made canonical) |
| Round-trip invariant → D10 adapter with executable gate | "NEW CANDIDATE D10: TTL round-trip adapter. TTL ingestion is a first-class write adapter … re-projection must reproduce a semantically equal TTL (triple-set equivalence — executable acceptance test `ingest(ttl) → events → export ≍ ttl`)." | R3 Q2 decision L73-81 | Planned (PRD M3 + hard gate G1) |
| Events are canon (R1 invariant) | "events → projected graph ('the ontology feeds the event store and our graph is then projected from there …')" | R1 Q1 L80-82 (verbatim user) | Decision / invariant |
| ARCH invariant #1 | "Events are canon; the graph is a cached projection." | ARCH Invariants #1 L197 | Shipped framing |
| Brownfield path now = TTL→events | "The brownfield onboarding path is now precisely TTL→events." | R3 Q2 L80 | Planned (PRD M3 L306-308) |

---

## Other Major Committed Design Decisions

| # | Intent item | Verbatim quote + citation | Source | Status hint |
|---|---|---|---|---|
| D1 | Builder rebuild = hybrid (deterministic skeleton + recursive-RLM discovery) | "i agree on the hybrid" … "Hybrid (iii), Path 2" | R2 Q1 L57-64 | Planned (PRD M8) |
| D2 | Recursive-only RLM; terminal mode slated for retirement | "the rlm/repl-researchers should be using recursive mode (the terminal mode needs to be retired eventually …)" | R2 Q1 answer L57-59 | Decision (direction) |
| D3 | Self-improving integration strictly opt-in; usable "as a database" | "i dont want it to be mandatory for the consumer and they can still use it 'as a database' without having to use the self-improving full pipeline." | R2 Q1 answer L59-60 | Decision; ARCH "Dormant by default" L171-176 (live-verified flag-off) |
| D4 | Competency questions as first-class build artifacts (two touchpoints, persistent) | "yes definitely! This sounds brilliant" → "Adopted, both touchpoints, persistent-contract semantics." | R2 Q2 L77-79 | Planned (PRD M7) |
| D5 | Formality ceiling: axioms-as-data + lints, NO reasoner | "lightweight stays the substrate ceiling — adopt formal axioms as data + lint rules, never as an embedded reasoning engine" | R2 Q3 L82-98 | Decision; PRD M4 + invariant L255 |
| D6 | Dedup tiered cascade + LLM verdict only in ambiguity band + KEEP guards | "full tiered cascade … → focused LLM merge/keep verdict ONLY in the ambiguity band, with the explicit 'differ in any number/negation/entity → KEEP' guard" | R2 Q5 L138-149 | Planned (PRD M5) |
| D7 | b′ EDN-SHACL validation: SHACL-shaped EDN source of truth, real SHACL export | "b′ — shapes authored as SHACL-shaped EDN (source of truth, Malli-validated, in-JVM interpreted …), exported as real SHACL TTL" | R3 Q2 L52-63 | Planned (PRD M4) |
| D8 | Derived edges: traversal-time hierarchy / query-time chains / event-time audited rules | "yes i agree" → three-way split table | R3 Q3 L83-96 | Decision; PRD phase-tail (mostly NEXT) |
| D9 | 8-part representation schema bundle, gated by round-trip test | "yes confirm!" → "all additive optional fields/events: (1) language-tagged labels … (8) annotations" | R3 Q4 L98-111 | Planned (PRD M2, gated by G1) |
| D10 | Negation: judge as closed-world evaluator + helpers; no SPARQL in ORC | "three layers, cheapest-first: (1) the LLM judge IS the closed-world evaluator … (3) export-to-triplestore remains the documented escape hatch" | R3 Q5 L114-124 | Planned (PRD M7) |
| D11 | Graph orientation card (D12): the large-doc-preview equivalent for graphs | "D12 — graph orientation card, four layers: (1) identity … (4) tool affordances" | R3 Addendum L153-171 | Planned (PRD M9) |
| D12 | ORSD/CQ attachment via descriptions pattern; check-before-mint at graph-merge | "zero new machinery — `record-ontology-spec` + `record-cq-evaluation` mirror the descriptions pattern" | R3 Q6 L143-151 | Planned (PRD M7) |
| D13 | Sovereignty: ORC-format always the goal; greenfield + brownfield onboarding | "ONE sovereignty mode (ORC-format is always the goal) with two onboarding paths" | R1 Q3 L141-153 | Decision; ARCH Onboarding Paths L130-155 |
| D14 | Multi-tenant ontology-scoping by ontology-id | "Multi-tenant separation by `ontology-id`. One event store hosts many ontologies …" | ARCH Ontology-scope row L75 | Shipped (partial — see Gap #2) |
| D15 | Serialization on-demand TTL/SKOS/OWL; events are source of truth | "On-demand TTL/SKOS/OWL export from read-model state — events are the source of truth; Turtle is a view." | ARCH Serialization row L77 | Shipped (but: axioms/edge-metadata not yet serialized — PRD M2 #5/#6) |
| D16 | Grain-style project decomposition (audit-first) | "i do want to make it a goal to eventually seperate the dependencies for users just like grain" | R1 Q6 L221-226 | Aspirational / strategic (Gap #8 HIGH; PRD Out-of-Scope L505-507) |
| D17 | Per-source caps before RRF (the one NOW-tier item) | "C9 per-source caps before RRF | NOW (independent retrieval fix)" | R2 Q6 L156 | Planned (PRD M10) |
| D18 | Old sheets as regression baseline; extraction bench is the prerequisite gate | "old sheets stay as regression baseline until RLM path beats them" | R2 Q1 L48-49 | Decision; PRD G2 hard gate |

---

## Items MOST AT RISK of being narrowed/lost (skeptical read)

1. **P2 embed-vs-ColBERT routing (HIGHEST RISK).** The yardstick says fields are embedded
   "via embedding model **or** colbert," implying an automatic per-field *choice of
   mechanism*. The grills (R1 Q2 L102-105) only commit to auto-detecting *which fields to
   embed* and auto-embedding/"late embedding" them. ARCH treats embedding and ColBERT as
   query-time signals (L76) and write-time as a flat "embedding → ColBERT indexing" pipeline
   (L115-116). **No quoted commitment that the write side routes a field to dense-embedding
   vs ColBERT by automatic judgment.** The routing decision may have been silently dropped
   or never specified. Verify against code; flag to user.

2. **Auto-embed-worthiness detection itself may be thinner than stated.** ARCH asserts it as
   SHIPPED ("Fields are automatically reviewed for embed-worthiness," L78) and R1 marks it a
   "Verified NOT gap" (R1 L282). But the grills offer no code line for the *detection*
   mechanism (only `hybrid-search` retrieval.clj:833 was code-verified, which is the *query*
   side, not write-side field selection). The write-side auto-detection claim rests on the
   user's statement, not a cited implementation. High-value verification target.

3. **The builder rebuild (P1) puts the generic multi-format front door in flux.** ARCH
   describes the 5 sheets as the SHIPPED generic front door (L111-116), but PRD M8 rebuilds
   discovery into recursive-RLM and keeps old sheets only "as the regression baseline until
   the new path beats them" (PRD L430-432, G2 L491-495). Risk: during the rebuild the
   *generic ANY-source* guarantee depends on unverified new code, with the proven path
   demoted to fallback. Verify the rebuilt builder actually handles csv/json/sql/text/unified.

4. **BFS ontology-id scoping leak (P3) is an ADMITTED correctness gap, not yet fixed.** R3 Q1
   (L33-39) found BFS walks across ALL sections; the fix is DECIDED (PRD M1) but PLANNED. Any
   "retrieval works" claim must confirm the BFS scoping fix actually landed, not just the
   embedding/ColBERT scoping.

5. **The round-trip invariant (events-first) is a hard gate (G1) but PLANNED, not shipped.**
   The user called it "super important" (R3 Q2 L64). Today ARCH admits axioms/edge-metadata
   are NOT serialized (PRD M2 #5 "today's open metadata bag is never exported," #6; PRD
   problem #4 "Export is lossy and ingestion is absent" L51-53). TTL *ingestion* does not yet
   exist as a first-class adapter. The full `ingest → events → export ≍ source` round trip is
   unverified.

---

## Where the Architecture Doc's OWN Gap Matrix admits pillar-relevant gaps

(ARCH "Gap Matrix" L274-290; mirrored in R1 L264-282)

- **Gap #1 (HIGH) — Doc drift (P1 framing):** "ONTOLOGY.md + FUTURE-VISION.md present the
  self-improving loop's framing as the system; the substrate framing was absent from ORC
  docs until this document" (ARCH L282). The general-purpose framing is asserted but the
  rest of the docs still contradict it.
- **Gap #2 (MEDIUM→HIGH, P3) — ontology-id scoping partial:** "scoped: `get-concepts`,
  `semantic-search-concepts`, `hybrid-search(-batch)`; unscoped: `get-concept-by-uri`,
  `get-narrower/broader-concepts`, `concept-statistics`, tree-profile finders. URI collisions
  across ontologies resolve silently" (ARCH L283). **Promoted to HIGH in R3** to absorb the
  BFS scoping fix.
- **Gap #4 (MEDIUM, P3) — Keyword retrieval signal not shipped:** "Keyword retrieval signal
  (BM25/SPLADE) not shipped; stack today = graph/embedding/colbert" (ARCH L285).
- **Gap #5 (MEDIUM, P1/P4) — Brownfield onboarding recipe missing:** "the ingest-through-
  builder path for pre-existing graphs has no documented recipe; the ops-workshop direct-copy
  skipped reasoning/embedding with no record of what was skipped" (ARCH L286). Directly
  relevant to the events-first/round-trip invariant now formalized in R3.
- **Gap #9 (tracked separately, P4-LEARN) — Self-improving-loop OOD force-fit:** "classifier
  force-fits at high confidence on out-of-distribution tasks" (ARCH L290). Bounds the LEARN
  pillar's reliability on the built-in application.

> **Note on P2:** The ARCH gap matrix does NOT list any gap for embed-worthiness
> auto-detection or embedding-vs-ColBERT routing — R1 explicitly files auto-embed under
> "Verified NOT gaps" (R1 L282). This is precisely why P2's routing nuance is the highest
> verification risk: the docs treat it as solved/non-issue, so a real gap there would be
> invisible to the doc's own self-assessment.
