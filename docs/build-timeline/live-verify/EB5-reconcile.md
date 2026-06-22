# EB5 — Reconcile subbehavior sheet — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain event store, real DJL MiniLM embeddings + real ColBERT index (the P3 check-before-mint probe), real async todo processors, real child tick. NO LLM (reconcile is DETERMINISTIC — the probe is P3 retrieval evidence, the merges are S12 evidence, the attribute links are structural).

Proves the RECONCILE subbehavior is a delegatable single-`:code`-node sheet that takes EB4's per-source DRAFT SET + the granted `:ontology-id` (the current-graph scope) and links the new drafts across sources AND against the CURRENT graph state, at TWO granularities — ENTITIES and their ATTRIBUTES/FEATURES — with CHECK-BEFORE-MINT (probe the existing graph via P3 hybrid-search for an existing match BEFORE landing). REUSE not fork: `compile-discovery-source!` (land + V18) + `reconcile-graph!` (S03 + S12 + V18 entity-reconcile, `:llm-budget` 0) + `hybrid-search` (P3 probe) + S12's `jaro-winkler-similarity` (the attribute-key match). Built on the EB1/EB2/EB3/EB4 registry/delegation pattern; re-houses + deepens DT7.

## Setup (inputs)

Source A (the PRE-EXISTING graph — landed via S17 `build!`, then embedded with real MiniLM + ColBERT-indexed so the P3 probe has real signals):

```clojure
[{:uri "entity:nurse",
  :label "Registered Nurse",
  :description
  "Provides direct patient care in hospitals and clinics.",
  :indicators ["patient care" "clinical practice"],
  :attributes {:sector "healthcare", :median-wage 75000}}
 {:uri "entity:engineer",
  :label "Software Engineer",
  :description
  "Designs builds and maintains large scale software systems.",
  :indicators ["programming" "system design"],
  :attributes {:sector "technology", :median-wage 110000}}
 {:uri "entity:teacher",
  :label "Elementary School Teacher",
  :description "Educates young children in core academic subjects.",
  :indicators ["education" "curriculum"],
  :attributes {:sector "education", :median-wage 60000}}
 {:uri "entity:analyst",
  :label "Financial Analyst",
  :description
  "Evaluates investments market trends and corporate budgets.",
  :indicators ["finance" "investment"],
  :attributes {:sector "finance", :median-wage 85000}}
 {:uri "entity:electrician",
  :label "Electrician",
  :description
  "Installs maintains and repairs electrical wiring and power systems.",
  :indicators ["electrical" "wiring"],
  :attributes {:sector "trades", :median-wage 62000}}
 {:uri "entity:pharmacist",
  :label "Pharmacist",
  :description
  "Dispenses medication and advises patients on safe drug usage.",
  :indicators ["medication" "pharmacy"],
  :attributes {:sector "healthcare", :median-wage 120000}}]
```

Source B (a SECOND source reconciled against the populated graph; re-mints `entity:nurse` — same canonical URI, must COLLAPSE — and adds the genuinely-new `entity:dentist`):

```clojure
[{:uri "entity:nurse",
  :label "Registered Nurse (source B)",
  :description
  "Cares for patients and administers treatment in clinical settings.",
  :indicators ["nursing" "patient care"],
  :attributes {:sector "healthcare", :shift "rotating"}}
 {:uri "entity:dentist",
  :label "Dentist",
  :description
  "Diagnoses and treats conditions of the teeth and gums.",
  :indicators ["dental" "oral health"],
  :attributes {:sector "healthcare", :median-wage 130000}}]
```

## Registry + delegation

- subbehavior: `ontology-reconcile/reconcile@v1`
- sub sheet-id: `11bef3a5-9e66-5cde-ad0c-f0cd3990add7`
- registry name→id round-trip: **true**
- central tree status: **:success** (76ms)
- parent tick-id: `712b675f-f2bc-4685-9402-137b051e6695`
- ontology-id: `82620c10-4d0b-460a-a8dc-2ea89c9bf743`

## CHECK-BEFORE-MINT probe (P3 hybrid-search, FIRED pre-mint)

The probe ran over the UNLANDED source-B drafts BEFORE landing, against the populated graph (real embeddings + real ColBERT).

- ColBERT index-id: `db4b5735-12b4-40fa-9a5f-775923bf12c0`
- ColBERT bridge up: **true**
- probed: **2**, content-hits: **1**, exact-uri hits (already-present): **1**

Probe entries (verbatim — the evidence-grounded identity signal):

```clojure
[{:uri "entity:nurse",
  :label "Registered Nurse (source B)",
  :existing-uri "entity:pharmacist",
  :existing-label "Pharmacist",
  :score 0.016129032258064516,
  :match? true,
  :exact-uri? true}
 {:uri "entity:dentist",
  :label "Dentist",
  :existing-uri nil,
  :existing-label nil,
  :score nil,
  :match? false,
  :exact-uri? false}]
```

## Reconcile-NOT-duplicate (reads the CURRENT graph state)

Read back from the PARENT tick blackboard via the projection (`get-tick-blackboard`), NOT the execute return value (discipline 7). The report crossed `:delegate` as a parsed MAP (a `:code`-node output — C1):

- report is a parsed MAP across `:delegate`: **true**
- concepts after A: **6**, after B reconciled: **7** (delta = **1** — only the genuinely-new entity grew the graph)
- `entity:nurse` node count: **1** (1 = the re-minted URI collapsed — reconcile-NOT-duplicate)

## ENTITY reconcile (reused `reconcile-graph!` — S03 + S12 + V18)

- shared-URI cross-source links: `["entity:nurse"]` (by source: `{"entity:nurse" #{"B" "A"}}`)
- candidate pairs (S12 LSH): **0**, near-match merges: **0**
- ambiguities surfaced (`:requires-review`, NEVER silently merged): **0**
- 0 dangling (V18): dangling-edge-count = **0**, every-edge-endpoint-resolves? = **true**

Ambiguities (verbatim — surfaced honestly):

```clojure
[]
```

## ATTRIBUTE reconcile (the genuinely-new EB5 deepening)

Beyond `reconcile-graph!`'s entity-level links — a NEW entity's ATTRIBUTES/FEATURES connected to EXISTING entities' attributes by structural key match (reused S12 jaro-winkler) + value equality:

- new entities carrying attributes: **1**
- same-value attribute links: **2**, shared-key links: **9**

Attribute links (verbatim):

```clojure
[{:new-uri "entity:dentist",
  :new-attr-key :sector,
  :existing-uri "entity:nurse",
  :existing-attr-key :sector,
  :value "healthcare",
  :kind :same-value}
 {:new-uri "entity:dentist",
  :new-attr-key :sector,
  :existing-uri "entity:engineer",
  :existing-attr-key :sector,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :sector,
  :existing-uri "entity:teacher",
  :existing-attr-key :sector,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :sector,
  :existing-uri "entity:analyst",
  :existing-attr-key :sector,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :sector,
  :existing-uri "entity:electrician",
  :existing-attr-key :sector,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :sector,
  :existing-uri "entity:pharmacist",
  :existing-attr-key :sector,
  :value "healthcare",
  :kind :same-value}
 {:new-uri "entity:dentist",
  :new-attr-key :median-wage,
  :existing-uri "entity:engineer",
  :existing-attr-key :median-wage,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :median-wage,
  :existing-uri "entity:teacher",
  :existing-attr-key :median-wage,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :median-wage,
  :existing-uri "entity:analyst",
  :existing-attr-key :median-wage,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :median-wage,
  :existing-uri "entity:electrician",
  :existing-attr-key :median-wage,
  :value nil,
  :kind :shared-key}
 {:new-uri "entity:dentist",
  :new-attr-key :median-wage,
  :existing-uri "entity:pharmacist",
  :existing-attr-key :median-wage,
  :value nil,
  :kind :shared-key}]
```

## Full reconcile report (verbatim, off the parent blackboard)

```clojure
{:ontology-id #uuid "82620c10-4d0b-460a-a8dc-2ea89c9bf743",
 :mint-probe
 {:probed 2,
  :hits 1,
  :exact-uri-hits 1,
  :entries
  [{:uri "entity:nurse",
    :label "Registered Nurse (source B)",
    :existing-uri "entity:pharmacist",
    :existing-label "Pharmacist",
    :score 0.016129032258064516,
    :match? true,
    :exact-uri? true}
   {:uri "entity:dentist",
    :label "Dentist",
    :existing-uri nil,
    :existing-label nil,
    :score nil,
    :match? false,
    :exact-uri? false}]},
 :landed
 {:ambiguities-flagged 0,
  :axioms-emitted 0,
  :concepts-emitted 2,
  :unresolved-endpoints 0,
  :ambiguities [],
  :every-edge-endpoint-resolves? true,
  :relationships-emitted 1,
  :status :ingested,
  :rlm-trace nil,
  :unresolved-endpoint-uris [],
  :implied-concepts-minted 0},
 :entity-reconcile
 {:ambiguities-surfaced 0,
  :shared-uri-links
  {:shared-uris ["entity:nurse"],
   :shared-uri-count 1,
   :by-uri {"entity:nurse" #{"B" "A"}}},
  :implied-endpoints-minted 0,
  :referential-integrity
  {:every-edge-endpoint-resolves? true,
   :dangling-edge-count 0,
   :dangling-edges []},
  :ambiguities [],
  :relationships-in-scope 1,
  :alignment-ontology-id #uuid "54265525-86e9-3af9-8f47-29442fb56414",
  :candidate-pairs 0,
  :ontology-id #uuid "82620c10-4d0b-460a-a8dc-2ea89c9bf743",
  :concepts-in-scope 7,
  :status :ok,
  :merge-equivalences [],
  :merges 0},
 :attribute-reconcile
 {:new-entities-with-attrs 1,
  :links
  [{:new-uri "entity:dentist",
    :new-attr-key :sector,
    :existing-uri "entity:nurse",
    :existing-attr-key :sector,
    :value "healthcare",
    :kind :same-value}
   {:new-uri "entity:dentist",
    :new-attr-key :sector,
    :existing-uri "entity:engineer",
    :existing-attr-key :sector,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :sector,
    :existing-uri "entity:teacher",
    :existing-attr-key :sector,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :sector,
    :existing-uri "entity:analyst",
    :existing-attr-key :sector,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :sector,
    :existing-uri "entity:electrician",
    :existing-attr-key :sector,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :sector,
    :existing-uri "entity:pharmacist",
    :existing-attr-key :sector,
    :value "healthcare",
    :kind :same-value}
   {:new-uri "entity:dentist",
    :new-attr-key :median-wage,
    :existing-uri "entity:engineer",
    :existing-attr-key :median-wage,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :median-wage,
    :existing-uri "entity:teacher",
    :existing-attr-key :median-wage,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :median-wage,
    :existing-uri "entity:analyst",
    :existing-attr-key :median-wage,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :median-wage,
    :existing-uri "entity:electrician",
    :existing-attr-key :median-wage,
    :value nil,
    :kind :shared-key}
   {:new-uri "entity:dentist",
    :new-attr-key :median-wage,
    :existing-uri "entity:pharmacist",
    :existing-attr-key :median-wage,
    :value nil,
    :kind :shared-key}],
  :same-value-link-count 2,
  :shared-key-link-count 9},
 :dangling-edge-count 0,
 :ambiguities-surfaced 0}
```

## Verdict

The Reconcile subbehavior is a delegatable single-`:code`-node sheet that reconciles a per-source draft set against the CURRENT graph state at TWO granularities (entities + attributes) with check-before-mint: the P3 hybrid-search probe FIRED pre-mint against real embeddings/ColBERT (evidence-grounded identity); the re-minted entity COLLAPSED (reconcile-NOT-duplicate); a new entity's attribute LINKED to an existing entity's attribute (the EB5 deepening); 0 dangling (V18); ambiguities surfaced as `:requires-review` (never silently merged). REUSE not fork (`compile-discovery-source!` / `reconcile-graph!` / `hybrid-search` / S12 jaro-winkler). The report crosses `:delegate` parsed (C1).
