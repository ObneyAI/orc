# ORC Ontology Architecture

> **Status:** Authoritative as of 2026-06-10. Produced from the
> [2026-06-10 architecture grill session](build-timeline/grill-sessions/2026-06-10-orc-ontology-architecture.md).
> Supersedes the *framing* (not the API reference) in [ONTOLOGY.md](ONTOLOGY.md),
> which describes the system as a "three-layer semantic knowledge system" —
> that description covers one *application* of the system, not the system itself.

## The Frame

ORC's ontology element is a **general-purpose ontology substrate**: an
event-sourced concept graph that takes disparate data sources and turns them
into a unifying structure which can be continuously added to, reasoned over,
and retrieved from — by any application, for any knowledge domain.

Everything else that ships in the component — the failure/success taxonomy,
tree profiles, Living Descriptions, the R-Inject classifier, behavioral
mints — is an **application** of that substrate. The most important one
(ORC's self-improving loop) ships in the box, which historically caused the
docs to describe the application as if it were the system. It is not.

```
┌────────────────────────────────────────────────────────────────┐
│                        APPLICATIONS                            │
│                                                                │
│  Built-in (ship with ORC)          External (consumer-built)   │
│  ┌──────────────────────┐          ┌────────────────────────┐  │
│  │ Self-improving loop  │          │ Transcript corrections │  │
│  │ MCP knowledge gateway│          │ Student recommendations│  │
│  │ Site registry (dormant)         │ User / convo memory    │  │
│  └──────────────────────┘          │ ... any domain         │  │
│                                    └────────────────────────┘  │
├────────────────────────────────────────────────────────────────┤
│              GENERAL-PURPOSE ONTOLOGY SUBSTRATE                │
│                                                                │
│   Concepts · Relationships · Ontology-scoping · Retrieval      │
│   Serialization · Growth (dedupe + auto-embed on write)        │
├────────────────────────────────────────────────────────────────┤
│                      WRITE-SIDE ADAPTERS                       │
│                                                                │
│   Evolutionary builder (ORC BT: csv/json/sql/text/unified)     │
│   Direct commands (create-concept / create-relationship)       │
│   Static initializer · Application recorders (profiles,        │
│   descriptions, mints)                                         │
├────────────────────────────────────────────────────────────────┤
│                    GRAIN (event store + CQRS)                  │
│   Events are canon · graph is a cached projection              │
└────────────────────────────────────────────────────────────────┘
```

Real deployments grounding this frame:

- **obney-ops-workshop** — transcript correction from garbled speech, plus
  Q&A over clients, concepts, and technology
- **daryls-area51** — recommendations for high-school students (schools,
  programs, careers, outcomes) built by unifying IPEDS, PSEO, Louisiana wage
  data, and O*NET — then enriched continuously with advisor lived
  experience, advisee experience, and podcast transcripts
- **ORC itself** — the self-improving loop: an intelligent few-shot injector
  that lets tree design learn from past successes and failures
- **Planned** — user memory, conversation memory, any knowledge domain

The substrate's primary goal: *the graph gets richer and richer, allowing
more informed downstream use — but only because the ontology can be
generally reasoned over and added to.*

## Conceptual Model

### What the substrate IS

| Primitive | Description |
|-----------|-------------|
| **Concept** | A node: URI + label + scope + arbitrary metadata + (optional) embedding vector. URIs are namespaced strings (`person:tavidee-hoskins`, `failure:Hallucination`, `tree-class:<uuid>`). |
| **Relationship** | A typed, directed edge between concepts. SKOS predicates (`skos:broader` / `skos:narrower` / `skos:related`) get first-class graph semantics; arbitrary predicates (`behavior:composes-into`, `member-of`, …) are supported without schema changes. |
| **Ontology-scope** | Multi-tenant separation by `ontology-id`. One event store hosts many ontologies; queries filter by one or more ontology-ids. |
| **Retrieval** | A configurable signal stack fused by RRF. Default signals: graph BFS (spreading activation), dense embeddings (MiniLM 384-dim via DJL), ColBERT late-interaction. Any subset is selectable per query (`:signals` + per-signal `:weights` on `hybrid-search`). Keyword signals (BM25/SPLADE) are planned additions to the same stack. Each signal is projected from the event store. |
| **Serialization** | On-demand TTL/SKOS/OWL export from read-model state — events are the source of truth; Turtle is a view. |
| **Growth posture** | Continuously addable. New writes are compared against the existing graph and deduplicated. Fields are automatically reviewed for embed-worthiness and embedded (or late-embedded) so new knowledge is semantically searchable by default. Embedding models are swappable per scope (`configure-embedding-model`). |

### What the substrate is NOT

- **Not a knowledge-graph database.** Events are canon; the graph is a
  *cached projection* (see Invariants). There is no separate graph store to
  administer.
- **Not the judges.** Judges are evaluators in the evaluation component.
  One built-in application (the self-improving loop) grounds judge results
  in failure concepts — that grounding is application logic.
- **Not the three-layer Failure/Success/Problem taxonomy.** That taxonomy is
  the *seed corpus of the self-improving loop* — one application's starting
  knowledge, loaded only if you call `initialize-static-ontology`.
- **Not the evolutionary builder.** The builder is one write-side adapter
  (see below). The substrate functions without it — hand-authored concepts
  via direct commands are equally first-class.

### Vocabulary

| Term | Meaning |
|------|---------|
| **Substrate** | The general-purpose system: primitives table above. |
| **Application** | Any consumer of the substrate — internal or external. All applications have the same standing. |
| **Built-in application** | An application that ships inside the ORC repo (self-improving loop, MCP gateway, site registry). |
| **Evolutionary builder** | The write-side adapter (itself an ORC behavior tree) that LLM-discovers ontology structure from sources. "Evolutionary" historically also described the substrate's growth posture — in this doc, the substrate is "the substrate" and the builder is "the builder". |
| **Ontology section / view** | A curated read-model projection over the graph (e.g., a correction lexicon). Sections are read-side; the write-side canon is always the ORC-format graph. |

## Write-Side Adapters

All writes flow through Grain commands → events. Adapters, in order of
ergonomic weight:

1. **Evolutionary builder** — `build-ontology-from-sources` routes a source
   to the matching extraction sheet: `csv_ontology`, `json_ontology`,
   `sql_ontology`, `ontology_exploration` (text), or `unified_ontology`
   (auto-detect). Each sheet is an ORC behavior tree that LLM-reasons over
   the source: discovery → comparison-to-existing → dedupe → definition
   generation → relationship discovery → embedding → ColBERT indexing. This
   is the front door for "I have heterogeneous data; build/grow my graph."
2. **Direct commands** — `:ontology/create-concept`,
   `:ontology/create-relationship`. Hand-authored knowledge; no LLM in the
   loop. Used for seed corpora, programmatic writers, and application
   recorders that know exactly what they're writing.
3. **Static initializer** — `:ontology/initialize-static-ontology` seeds a
   predefined concept set (today: the self-improving loop's failure/success/
   problem taxonomy).
4. **Application recorders** — tree-strength/weakness/problem-mapping
   recording, Living Description commands, behavioral-subtree mints. These
   are self-improving-loop write paths that ride the same primitives.

## Onboarding Paths (Sovereignty)

**ORC-format is always the goal.** There is one sovereignty mode and two
ways to arrive:

- **Greenfield** — no existing graph. The builder constructs the ontology
  directly from disparate sources. (daryls-area51: IPEDS + PSEO + wage data
  + O*NET unified from scratch.)
- **Brownfield** — the app already has a graph (RDF, YAML entity files,
  etc.). That graph is **seed knowledge**: ingest it *through* the builder
  so every field gets reasoned over and embedded, then grow it with new
  sources from there. Pre-structured input doesn't skip the pipeline — it
  rides it.

> **Recorded shortcut, with cost:** obney-ops-workshop copied its
> pre-built RDF directly into ORC form instead of passing it through the
> builder. Structurally fine (it landed in ORC format) — but the
> reasoning/embedding pass was skipped, which is exactly what the
> brownfield path exists to provide. Treat direct-copy as a bootstrap
> expedient, not a pattern.

Once knowledge is in ORC form, app-specific lifecycle (ratification,
validation, candidate states) layers *around* the graph as the app's own
commands and read-models referencing concept URIs. The app's "entity
substrate" and the ORC-format ontology are the same graph — "canonical app
substrate" means canonical *within that deployment*, and that substrate IS
the ORC-format ontology. Curated views (a correction lexicon, a trusted
subset) are read-model projections, never second stores.

## Built-In Applications

### 1. Self-improving loop (flagship)

ORC's own use of the substrate: learn from execution history to improve
future tree design. Comprises the failure/success/problem seed taxonomy,
judge-result→failure-concept classification, tree profiles
(strengths/weaknesses/solves), node-type learning, Living Descriptions with
consolidation, the R-Inject classifier (`classify-task` /
`classify-behaviors`), behavioral-subtree minting, pattern discovery, and
learned-rule extraction. Documented in
[SELF-IMPROVING-LOOP.md](SELF-IMPROVING-LOOP.md) and
[LIVING-DESCRIPTIONS.md](LIVING-DESCRIPTIONS.md).

**Dormant by default.** The write side is gated on
`living-description-enabled?` (default `false`) plus per-node opt-ins
(`:rlm {:auto-classify? true}`, explicit `:judges` attachments). A consumer
who never flips the flag gets a pure substrate — verified live: with the
flag off, zero judge/score events fire.

### 2. MCP knowledge gateway

13 MCP tools exposing read-side surfaces (concept search, retrieval, tree
profiles, embeddings) to external agents — Claude Code or any MCP client.
Makes any ontology section queryable from outside the JVM. See
[ONTOLOGY-MCP.md](ONTOLOGY-MCP.md).

### 3. Site registry (dormant)

The earliest application of the record-learnings-then-retrieve pattern:
an agent-browser workflow surfed real-estate sites, and the ontology
recorded per-site interaction procedure (which buttons, how to scroll,
site trust) so the workflow could self-adjust per site. Dormant; likely
reabsorbed by the self-improving loop once agent-browser tools land in RLM
mode (behavioral mints + Living Descriptions for "interacting with site X"
cover the same need). Purpose preserved here so the disposition decision
can be made deliberately when browser tooling arrives.

## Invariants

1. **Events are canon; the graph is a cached projection.** Grain's
   read-model-processor-v2 provides two-tier caching out of the box: an L1
   in-process cache and an L2 LMDB cache with watermark-based incremental
   projection — repeated queries replay only events newer than the cached
   watermark, never the full stream. **Consumers must wire a `:cache`
   (LMDB kv-store) into their context** to get L2; without it, cold
   projections re-replay per process start.
2. **All writes are Grain commands → events.** No bare event-store appends.
   Multi-ontology scoping rides event tags + the `ontology-id` field.
3. **Retrieval signals are projections.** Embedding vectors, ColBERT
   indexes, and the graph itself are all rebuilt/rebuildable from the event
   stream.
4. **Applications are dormant by default.** The substrate is what loads
   active. Flags and per-node opt-ins are the activation surface for
   built-in applications.

## Integration Surface (for consumers)

```clojure
;; deps.edn — today, one consumable unit:
obneyai/orc {:git/url "https://github.com/ObneyAI/orc.git"
             :git/sha "..." :deps/root "projects/orc"}

(require '[ai.obney.orc.ontology.interface :as ontology])

;; Greenfield: build from sources (the builder reasons, dedupes, embeds, indexes)
(ontology/build-ontology-from-sources ctx
  {:ontology-id my-ontology-id
   :sources [{:type :json :data ...} {:type :csv :data ...}]})

;; Query: scoped, configurable-signal retrieval
(ontology/hybrid-search ctx
  {:query-text "college advising programs in louisiana"
   :ontology-id my-ontology-id
   :signals #{:graph :embedding :colbert}   ;; any subset
   :weights {:graph 1.0 :embedding 1.0 :colbert 1.0}})

;; Traverse
(ontology/get-concepts ctx {:ontology-id my-ontology-id :scope :person})
(ontology/get-narrower-concepts ctx "concept:advising")

;; Export
(ontology/export-turtle ctx {:scope :all})
```

The self-improving loop, judges, R-Inject, and Living Descriptions activate
only via their own opt-ins — see
[SELF-IMPROVING-LOOP.md](SELF-IMPROVING-LOOP.md) for that surface.

## Strategic Direction: Grain-Style Decomposition

ORC will decompose into independently-consumable units following grain's
pattern: components stay in one repo; **`projects/` defines the consumable
units**; consumers pick via `:deps/root`. Target carving (to be validated by
an entanglement audit before any split):

- `orc-ontology` — the substrate (concept graph, retrieval, embeddings,
  serialization; likely bundles colbert as a retrieval signal)
- `orc-ontology-builder` — the evolutionary builder sheets (bridge
  component; needs the tree engine)
- `orc-core` — the behavior-tree engine + DSL
- `orc-repl-researcher` — SCI sandbox + RLM mode (likely shares deps with
  the MCP builder)
- `orc-judges` — evaluation + rolling judges + scoring/feedback
- `orc-self-improving` — Living Descriptions, R-Inject, consolidator, mints
- `orc-gepa` — instruction optimization

**Known blocker (verified):** the ontology component's core + interface is
clean (depends only on colbert + grain), but all 5 builder sheets reference
`orc-service` (they are ORC BTs), and `orc-service` depends back on
`ontology` (the R-Inject wedge). A standalone `orc-ontology` project
therefore requires first extracting the sheets to an `ontology-builder`
bridge component. **Sequence: entanglement audit → extract sheets →
`projects/orc-ontology` → remaining carve.** The audit also tests the
repl-researcher/MCP-builder shared-deps hypothesis and the judges/GEPA
seams.

## Gap Matrix

Current vs intended, confirmed 2026-06-10. Rows are *findings with
priority*, not slice designs — slicing happens via PRD/issues after this
doc's review.

| # | Gap | Layer | Evidence | Priority |
|---|-----|-------|----------|----------|
| 1 | **Doc drift** — ONTOLOGY.md + FUTURE-VISION.md present the self-improving loop's framing as the system; the substrate framing was absent from ORC docs until this document | Docs | ONTOLOGY.md L3-24; FUTURE-VISION.md L237-285, L539-555 | **HIGH** — this doc resolves the frame; a reframe pass over ONTOLOGY.md follows |
| 2 | **ontology-id scoping partial** — scoped: `get-concepts`, `semantic-search-concepts`, `hybrid-search(-batch)`; unscoped: `get-concept-by-uri`, `get-narrower/broader-concepts`, `concept-statistics`, tree-profile finders. URI collisions across ontologies resolve silently | Substrate | read_models.clj:803 vs :822-877 | **MEDIUM** |
| 3 | **Stale docstring** — interface-level `get-concepts` hides the working `:ontology-id`/`:ontology-ids` params | Substrate | interface.clj:75 | LOW |
| 4 | **Keyword retrieval signal** (BM25/SPLADE) not shipped; stack today = graph/embedding/colbert | Substrate | retrieval.clj:833 | MEDIUM (recorded direction) |
| 5 | **Brownfield onboarding recipe missing** — the ingest-through-builder path for pre-existing graphs has no documented recipe; the ops-workshop direct-copy skipped reasoning/embedding with no record of what was skipped | Builder | 2026-06-10 grill Q3 | **MEDIUM** |
| 6 | **Downstream drift** — ops-workshop ADR 0002 reads as two-stores-with-sync; confirmed end-state is one ORC-format graph with app lifecycle around it. Fix lands downstream | Consumer | ADR 0002 vs grill Q4 | LOW (revisit note) |
| 7 | **Site registry disposition** — dormant; likely reabsorbed by self-improving loop + agent-browser RLM tools; decide when browser tooling lands | Application | commands.clj:1021-1094 | LOW |
| 8 | **Project decomposition** — grain-style `projects/` carving; blocker and sequence per Strategic Direction above | Packaging | sheets→orc-service circularity (verified) | **HIGH (strategic, phased — audit first)** |
| 9 | **Self-improving-loop OOD force-fit** — application-layer; classifier force-fits at high confidence on out-of-distribution tasks; tracked in its own investigation | Application | `development/bench/ood-stress-results/HANDOFF.md`; SELF-IMPROVING-LOOP.md L440-485 | Tracked separately |

## Related Documents

- [ONTOLOGY.md](ONTOLOGY.md) — component API reference (framing superseded
  by this doc; reference material still valid)
- [ONTOLOGY-MCP.md](ONTOLOGY-MCP.md) — MCP gateway integration guide
- [SELF-IMPROVING-LOOP.md](SELF-IMPROVING-LOOP.md) — the flagship built-in
  application, external-consumer entry point
- [LIVING-DESCRIPTIONS.md](LIVING-DESCRIPTIONS.md) — description-evolution
  architecture inside the self-improving loop
- [FUTURE-VISION.md](FUTURE-VISION.md) — roadmap (Theme 8 / Phase 4a
  describe the self-improving application's history)
- [Grill session record](build-timeline/grill-sessions/2026-06-10-orc-ontology-architecture.md)
  — the decision log behind this document
