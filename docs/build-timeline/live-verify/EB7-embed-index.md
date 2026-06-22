# EB7 — Embed+Index subbehavior sheet — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain event store, real DJL MiniLM embeddings, real ColBERT bridge (when up), real child tick. NO LLM (EB3 already committed the `embed-fields` signal — embed+index is deterministic orchestration).

Proves the EMBED+INDEX subbehavior is a delegatable single-`:code`-node sheet that, on a built graph, RESOLVES the embed-worthy fields (the Model's `embed-fields` signal, else the heuristic schema scan), EMBEDS the in-scope concepts (reused `embed-concepts-batch!` to compute + the `:ontology/embed-concept` command to LAND), and ColBERT-INDEXES them via the `:colbert/create-index` COMMAND so the index is RESOLVABLE — BY DEFAULT, with NO caller wiring the embed/index fns (the P2 fix). Built on the EB1-EB6 registry/delegation pattern.

## The ColBERT root-cause this verify pins down

`colbert-indexer/index-concepts!` forwards `colbert/create-index!` (the interface fn), which builds the PLAID index on disk but does NOT emit the `:colbert/index-created` event — so `get-index` cannot resolve it and a subsequent ColBERT hybrid-search fails with 'Index not found'. EB7 dispatches the `:colbert/create-index` COMMAND (which emits the index-created event) and registers the per-ontology mapping with the SAME landed id, so the index is actually RESOLVABLE. This verify exercises the real ColBERT signal end-to-end to prove it.

## Setup (inputs)

A real graph landed via `compile-discovery-source!` WITHOUT embedding or indexing (so EB7 is what fires embed + ColBERT-index — proving GUARANTEED-by-default):

```clojure
[{:uri "entity:nurse",
  :label "Registered Nurse",
  :description
  "Provides direct patient care in hospitals and clinics across many departments."}
 {:uri "entity:engineer",
  :label "Software Engineer",
  :description
  "Designs builds and maintains large scale software systems and services."}
 {:uri "entity:teacher",
  :label "Elementary School Teacher",
  :description
  "Educates young children in core academic subjects like reading and mathematics."}
 {:uri "entity:electrician",
  :label "Electrician",
  :description
  "Installs maintains and repairs electrical wiring and power systems safely."}
 {:uri "entity:pharmacist",
  :label "Pharmacist",
  :description
  "Dispenses medication and advises patients on safe and effective drug usage."}
 {:uri "entity:analyst",
  :label "Financial Analyst",
  :description
  "Evaluates investments market trends and corporate budgets for decisions."}
 {:uri "entity:chef",
  :label "Executive Chef",
  :description
  "Prepares meals plans menus and manages a restaurant kitchen and staff."}
 {:uri "entity:officer",
  :label "Police Officer",
  :description
  "Enforces laws investigates crimes and protects public safety in communities."}
 {:uri "entity:architect",
  :label "Architect",
  :description
  "Designs buildings prepares blueprints and oversees construction projects."}
 {:uri "entity:scientist",
  :label "Data Scientist",
  :description
  "Builds predictive models from large and complex datasets using statistics."}]
```

EB3 `embed-fields` signal (the fields the Model committed): `["label" "description"]`

## Registry + delegation

- subbehavior: `ontology-embed-index/embed-index@v1`
- sub sheet-id: `f112fbbf-acb4-5108-b13a-8e01c6041936`
- registry name→id round-trip: **true**
- central tree status: **:success** (8989ms)
- parent tick-id: `1c0282c0-44a6-4195-a6b5-37fdb8aa7fa8`
- ontology-id: `139c376b-8521-4e13-a137-4e698740c677`
- concepts landed (before EB7): **10**

## GUARANTEED-by-default embed (events LANDED — projection read-back, #7)

The delegated `:code` node embedded the in-scope concepts on the resolved fields and the `:ontology/concept-embedded` events LANDED — read back from the projection (NOT a return value):

- resolved embed-fields: `[:description :label]` (source: **:model-signal**)
- report `embedded-count`: **10**
- report `embeddings-read-back-count`: **10**
- embeddings read back from the projection (independent): **10**

## GUARANTEED-by-default ColBERT index (RESOLVABLE)

- ColBERT index-id (registered for the ontology): `bb29f186-402c-496d-a72c-3bacddfaac1e`
- index document count: **10**
- index-skipped-reason (nil = a real index built): `nil`
- ColBERT bridge up: **true**

## Hybrid-search returns LABELED, semantically-correct hits

### EMBEDDING signal (real MiniLM) — query: `caring for patients in a hospital`

```clojure
[{:uri "entity:nurse",
  :label "Registered Nurse",
  :score 0.01639344262295082}
 {:uri "entity:pharmacist",
  :label "Pharmacist",
  :score 0.016129032258064516}
 {:uri "entity:electrician",
  :label "Electrician",
  :score 0.015873015873015872}
 {:uri "entity:officer", :label "Police Officer", :score 0.015625}
 {:uri "entity:architect",
  :label "Architect",
  :score 0.015384615384615385}]
```

### COLBERT signal (real bridge) — query: `writing and maintaining software programs`

```clojure
[{:uri "entity:engineer",
  :label "Software Engineer",
  :score 0.01639344262295082}
 {:uri "entity:electrician",
  :label "Electrician",
  :score 0.016129032258064516}
 {:uri "entity:architect",
  :label "Architect",
  :score 0.015873015873015872}
 {:uri "entity:chef", :label "Executive Chef", :score 0.015625}
 {:uri "entity:scientist",
  :label "Data Scientist",
  :score 0.015384615384615385}]
```

## Full embed+index report (verbatim, off the parent blackboard)

```clojure
{:embed-fields-source :model-signal,
 :concepts-considered 10,
 :embed-fields-used [:description :label],
 :ontology-id #uuid "139c376b-8521-4e13-a137-4e698740c677",
 :index-document-count 10,
 :index-id #uuid "bb29f186-402c-496d-a72c-3bacddfaac1e",
 :embeddings-read-back-count 10,
 :index-skipped-reason nil,
 :embedded-count 10}
```

## Verdict

The Embed+Index subbehavior is a delegatable single-`:code`-node sheet that, on a built graph, embeds + ColBERT-indexes the concepts BY DEFAULT — driven by the Model's `embed-fields` — with NO caller wiring (the P2 fix). The embed events LANDED (projection read-back, #7); the ColBERT index is RESOLVABLE (via the command path, not the event-skipping interface fn); and a real hybrid-search returns labeled, semantically-correct hits on the embedding AND ColBERT signal. REUSE not fork (`embed-concepts-batch!` / the heuristic detector / `:colbert/create-index` command / `emit-colbert-indexed-event!`). The report crosses `:delegate` parsed (C1). F1 (per-concept embed-event batching) remains the open scale follow-up.
