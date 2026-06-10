---
type: grill-session
date: 2026-06-10
session: orc-ontology-architecture
status: complete
method: grill-with-docs
repo: orc
branch: feature/ontology-architecture
prior-sessions:
  - obney-ops-workshop/docs/build-timeline/grill-sessions/2026-05-28-transcript-correction-pipeline.md
  - obney-ops-workshop/docs/build-timeline/grill-sessions/2026-06-01-orc-ontology-integration.md
  - obney-ops-workshop/docs/build-timeline/grill-sessions/2026-06-05-orc-pipeline-into-ops-workshop.md
doc-bundle:
  - docs/FUTURE-VISION.md
  - docs/ONTOLOGY.md
  - docs/ONTOLOGY-MCP.md
  - the three prior grill sessions above
output: docs/ARCHITECTURE-ONTOLOGY.md (drafted immediately after this session)
next: /to-prd then /to-issues (after ARCHITECTURE-ONTOLOGY.md review)
---

# Grill Session: ORC Ontology Architecture — Surface-Area + Gap Matrix

A `/grill-with-docs` session locking in shared understanding of what the ORC
ontology element IS, what it SHOULD be capable of, and where current ≠
intended — before designing any next slice. This session validates and
sharpens the frame first articulated in the 2026-06-01 workshop grill:

> "The failure/success stuff is the ontology system specific to self
> improving systems but the ontology system at its base is a general graph
> ontology builder."

## Context

**Prior state:**
- The repl-researcher arc (RLM mode, R-Inject, Living Descriptions, judges
  on any sheet) just landed on main (`df7df2d1`) — merged with main's
  Cambot-driven Grain v3 schema-compliance arc (evolutionary builder sheets,
  ontology-id scoping, batched hybrid-search).
- The canonical ORC docs (ONTOLOGY.md, FUTURE-VISION.md Theme 8/Phase 4a)
  frame the ontology as a three-layer Failure/Success/Problem self-learning
  system. The general-purpose framing existed only in workshop grill
  sessions — never in ORC's own docs. That mismatch drove this session.

**Real consumers grounding the discussion:**
- **obney-ops-workshop** — transcript corrections from garbled speech + Q&A
  over clients/concepts/technology
- **daryls-area51** — recommendations for high-school students (schools,
  programs, careers, outcomes) built by unifying IPEDS, PSEO, Louisiana
  wage data, O*NET, plus advisor lived experience, advisee experience, and
  podcast transcripts into one continuously-growing graph
- **ORC itself** — the self-improving loop ("an intelligent few-shot
  injector that lets us learn from past experiences")
- **Future** — user memory, conversation memory, "really anything"

---

## Q&A Log

### Q1 — What does "evolutionary ontology" denote?

**Options:** (A) narrow: just the 5 source-extraction builder sheets;
(B) wide: the entire general-purpose substrate including discovery /
dedupe / embed / index machinery, with the sheets as entry-point adapters;
(C) some other boundary.

**User answer:** "yes exactly b, it is meant to be basically another orc bt
specialized in building general purpose ontologies that then can be used by
the application in various ways."

**Decision:** **(B) wide reading.** The substrate is the system. Load-bearing
additions from the answer:
- The evolutionary builder is **itself an ORC BT** — a consumer of the
  substrate that writes into it.
- The substrate's purpose: "take disparate data sources and turn them into a
  unifying structure which then can continuously be added to and used in a
  variety of ways with grain disciplines like read models that help project
  that graph."
- Architectural invariant: **events → projected graph** ("the ontology feeds
  the event store and our graph is then projected from there (needs to be
  cached so we dont have to reload it on every request)").
- The graph "gets richer and richer allowing for more informed
  recommendations but this is only possible if the ontology can be generally
  reasoned over and added to. That is the primary goal of the ontology
  system."

### Q2 — Is the evolutionary builder a primitive of the substrate, or a write-side adapter?

**Options:** (A) builder-as-primitive (the substrate's identity is
LLM-discovery-from-sources); (B) builder-as-adapter (substrate primitives
are deeper: Concept, Relationship, Ontology-scope, Retrieval, Serialization,
Growth posture — the builder is one of several write paths).

**User answer:** "i agree with b" — plus a critical sharpening on retrieval:

> "We are in fact using rrf as default with graph bfs + embedding + colbert
> but it should be possible that we can use whatever we want (ie JUST graph
> bfs or just embeddings or just colbert or graph + colbert, graph +
> embedding, etc) eventually we will also add keyword searching like bm25 or
> splade too but the idea is that retrieval can be configurable based on the
> situation and each can be projected from the event store. We can change
> embedding models if wanted as well and yes fields are reviewed to see if
> they would be good to embed for search purposes and goes ahead and
> automatically embeds/late embeds the relevant fields to make them
> semantically searchable by default."

**Decision:** **(B) builder-as-adapter.** Retrieval is a **configurable
signal stack** (RRF over any subset of {graph BFS, embeddings, ColBERT};
BM25/SPLADE planned; embedding models swappable; auto-detection +
auto-embedding of embed-worthy fields by default).

**Code verification:** `hybrid-search` (retrieval.clj:833) already ships
`:signals #{:graph :embedding :colbert}` subset selection + per-signal
`:weights` + `:ontology-id`/`:ontology-ids` scoping. The configurability
intent is shipped at this entry point.

**Vocabulary resolution:** "evolutionary" was doing two jobs — describing
the substrate's growth posture AND naming the builder adapter. Canonical
terms going forward: **"general-purpose ontology substrate"** (the system)
and **"evolutionary builder"** (the LLM-source-discovery write adapter).

### Q3 — Sovereignty: what happens when a downstream app has its own graph?

The 2026-06-05 session had REVERSED 2026-06-01's migrate-into-ORC decision
(ops-workshop ADR 0002: "one entity graph; the correction ontology is a
promoted view over it... the app substrate is canonical").

**User answer (clarifying the apparent reversal):** "the confusion here is
the obney-ops-workshop had yaml files with the rdf already built. We wanted
to pass that through the evolutionary ontology to basically just put that
graph into orc form (we cheated by just copying it instead of passing it
through the system but normally it maybe should so all the appropriate
fields are reasoned over/embedded if needed) then it makes it possible to
use the ontology substrate to then add to/maintain that apps ontology given
it is in orc format. SO the answer is maybe c but using the orc ontology
system is always the goal and if the app already has some graph substrate
the idea is to tack that on to a new growing ontology as existing knowledge
or it is the knowledge start and the ontology system can take other
disparate sources to build on the apps graph that exists already."

**Decision:** **ONE sovereignty mode (ORC-format is always the goal) with
two onboarding paths:**
- **Greenfield** — no existing graph → the builder constructs the ontology
  from disparate sources (daryls-area51).
- **Brownfield** — app has an existing graph/RDF → that graph is **seed
  knowledge**, normally ingested *through* the builder so fields get
  reasoned-over + embedded; the substrate grows it from there.

The ops-workshop direct-copy was a **shortcut** (structurally fine — ended
up in ORC form — but skipped the reasoning/embedding pass). "Views"
(correction lexicon, ontology *sections*) are **curated read-model
projections over the ORC-format graph** — standard Grain; write-side canon
is the ORC ontology.

### Q4 — ops-workshop ADR 0002 end-state

**Options:** (A) the app's entity graph IS an ORC-format ontology section,
with app lifecycle (ratification/validation/candidates) as app commands +
read-models layered around it referencing concept URIs; (B) genuinely
separate app-shaped store with ORC-format mirror + sync seam.

**User answer:** "100% a"

**Decision:** **(A).** ADR 0002's "app substrate is canonical" means
canonical *within the deployment* — and that substrate IS the ORC-format
graph. Downstream revisit note recorded (gap #6).

### Q5 — Vocabulary + decomposition of the built-in machinery

**User answer:** "this sounds perfect" — with the site-registry purpose
explained:

> "[the apartments ontology] was meant to be an orc workflow that surfs real
> estate sites with an agent browser and the ontology was meant to record
> the learnings of the workflow so that it could update itself on how to
> interact with a website, what buttons should be pressed, how scrolling
> should be done etc etc so that it could 'self-learn' or adjust to the
> websites it will be accessing regularly... maybe eventually the
> failure/success ontology will feed this if the model has some basic agent
> browser tools it can craft trees with the right tool actions in rlm mode
> and the self learning ontology will record those website actions... so
> maybe that older implementation is dead but do you see what the ontology
> purpose was there and how it is consistent with what we are wanting to use
> it for?"

**Decision:** Canonical vocabulary: **substrate** (the general system) /
**applications** (consumers of it) / **built-in applications** (the ones
shipped inside the component). Decomposition of the box:
1. **Self-improving loop** (flagship built-in application) — failure/success/
   problem static taxonomy (its seed corpus, NOT "the system"), judge→failure
   classification, tree profiles, node-type learning, Living Descriptions +
   consolidation, R-Inject classifier, behavioral-subtree mints, pattern
   discovery, learned-rule extraction.
2. **MCP knowledge gateway** — 13 MCP tools exposing read-side surfaces to
   external agents.
3. **Site registry** (dormant built-in application) — per-site procedural
   learning for browser automation; the earliest application of the
   record-learnings-then-retrieve pattern; likely reabsorbed by the
   self-improving loop once agent-browser tools land in RLM mode. Purpose
   preserved; disposition deferred.

Framing sentence adopted: *"ORC ships with the substrate plus built-in
applications. Everything an external consumer builds (transcript
corrections, recommendations, user memory) is an application with exactly
the same standing."*

### Q6 — Packaging: single component vs decomposition

**Verified:** substrate-only consumption is behaviorally clean — the
self-improving loop's write side is gated on `living-description-enabled?`
(defaults **false**; live-verified 2026-06-09: flag off → zero judge events).
But packaging-wise a consumer inherits everything (DJL/PyTorch, colbert
bridge, seed resources, auto-registered processors, site registry).

**User answer:** "yes i totally agree this has been fine for a while but
eventually i do want to seperate orc into its components just like grain is
so the users can choose what gets pulled in. Maybe we can start that here
with the ontology component if we will alredy be addressing other things but
i do want to make it a goal to eventually seperate the dependencies for
users just like grain... where the repl researcher is seperate (sci sandbox)
with the mcp builder (i think they need the same things) the self learning
bits, the ontology general, the rolling judges and their whole
scoring/feedback system, the gepa component etc shoudl all likely be
seperated and documented just like grain so users can choose to have as
minimal dependencies as needed (though i'm not sure how tangled these things
are right now)"

**Code verification (the first entanglement):** grain's pattern = components
stay in one repo; `projects/` defines consumable units; consumers pick via
`:deps/root`. ORC today ships only `projects/orc`. The ontology component's
core + interface is CLEAN (depends only on colbert + grain) — but **all 5
evolutionary builder sheets reference orc-service** (they're ORC BTs), and
orc-service depends back on ontology (the R-Inject wedge) → circularity that
blocks a standalone `orc-ontology` project containing the sheets. Resolution
shape (recorded, not designed): extract sheets to an `ontology-builder`
bridge component depending on both.

**Decision:** Single component acceptable now, with the gating contract
documented. **Strategic direction confirmed: ORC decomposes into grain-style
`projects/` consumable units** — entanglement audit FIRST (also tests the
repl-researcher+mcp-builder shared-deps hypothesis and judges/GEPA seams),
then phased carving. User: "yes i completely agree" (audit-first; split as
follow-on slices).

---

## Decisions Summary

| # | Topic | Decision |
|---|-------|----------|
| 1 | Frame | Wide reading — the general-purpose substrate is the system; the evolutionary builder is an ORC BT entry point into it |
| 2 | Primitives | Concept, Relationship (SKOS + arbitrary predicates), Ontology-scope (ontology-id), Retrieval (configurable signal stack), Serialization (TTL/SKOS/OWL), Growth posture (continuous, deduplicating, auto-embedding) |
| 3 | Builder status | Write-side adapter, not primitive; one of several write paths (direct commands, static init, application recorders) |
| 4 | Retrieval | RRF over configurable signal subset; BM25/SPLADE planned; swappable embedding models; auto-embed by default |
| 5 | Vocabulary | "general-purpose ontology substrate" (system) vs "evolutionary builder" (adapter); substrate / applications / built-in applications |
| 6 | Sovereignty | ORC-format always the goal; greenfield (build) + brownfield (ingest-as-seed) onboarding; views = read-model projections |
| 7 | ADR 0002 end-state | (A) app entity graph IS an ORC-format ontology section; app lifecycle layers around it |
| 8 | Built-ins | Self-improving loop (flagship; static taxonomy = its seed corpus) + MCP gateway + site registry (dormant) |
| 9 | Packaging | Single component now; gating contract documented; grain-style decomposition is a confirmed strategic direction, audit-first |
| 10 | Caching | NOT a gap — Grain rmp-v2 two-tier cache (L1 atom + L2 LMDB, watermark-incremental); consumers wire `:cache` |

---

## Gap Matrix (confirmed)

| # | Gap | Layer | Evidence | Priority |
|---|-----|-------|----------|----------|
| 1 | Doc drift: canonical docs frame the self-improving loop as THE system; substrate framing absent from ORC docs | Docs | ONTOLOGY.md L3-24; FUTURE-VISION L237-285, L539-555 vs 2026-06-01 grill Q2 | **HIGH** — resolved by ARCHITECTURE-ONTOLOGY.md; ONTOLOGY.md reframe follows |
| 2 | ontology-id scoping partial: `get-concept-by-uri`, `get-narrower/broader-concepts`, `concept-statistics`, tree-profile finders unscoped; silent URI collisions across ontologies | Substrate | read_models.clj:803 (scoped) vs :822-877 (unscoped) | **MEDIUM** |
| 3 | Stale public docstring hides working scoping params on `get-concepts` | Substrate | interface.clj:75 | LOW |
| 4 | Keyword retrieval signal (BM25/SPLADE) not shipped | Substrate | retrieval.clj:833 (signals = graph/embedding/colbert) | MEDIUM (direction) |
| 5 | Brownfield onboarding path undocumented; ops-workshop direct-copy skipped reasoning/embedding with no record of what was skipped | Builder | Q3 answer; 2026-06-05 session | **MEDIUM** |
| 6 | Downstream drift: ops-workshop ADR 0002 reads as two-stores-with-sync; confirmed end-state is one ORC-format graph | Consumer | ADR 0002 vs Q4 "100% a" | LOW (revisit note, lands downstream) |
| 7 | Site registry: dormant built-in application; purpose preserved; likely reabsorbed by self-improving loop + agent-browser RLM tools | Application | commands.clj:1021-1094 | LOW (deferred until browser tooling) |
| 8 | ORC project decomposition (strategic): grain-style `projects/`; blocker = builder sheets → orc-service → ontology circularity; resolution shape = `ontology-builder` bridge component; sequence = audit → extract sheets → `projects/orc-ontology` → remaining carve | Packaging | sheets/*.clj grep; projects/ = `orc` only | **HIGH (strategic, phased — audit first)** |
| 9 | Self-improving-loop OOD force-fit (application layer; already tracked): high-confidence force-fit on out-of-distribution tasks | Application | `development/bench/ood-stress-results/HANDOFF.md`; SELF-IMPROVING-LOOP.md L440-485 | Tracked separately |

**Verified NOT gaps** (stated as invariants in the architecture doc):
- Projection caching (Grain rmp-v2 two-tier cache, watermark-incremental)
- Retrieval signal configurability (`:signals` + `:weights` shipped)
- Embedding model swappability + auto-embed field detection
- Substrate-only consumption behaviorally clean (flag-gated, live-verified)

---

## Linked Artifacts

- **Output doc:** `docs/ARCHITECTURE-ONTOLOGY.md` (drafted next, this branch)
- **Prior grills:** the three obney-ops-workshop sessions listed in frontmatter
- **OOD investigation:** `development/bench/ood-stress-results/HANDOFF.md`
- **Next:** `/to-prd` then `/to-issues` after the architecture doc is reviewed
