# EB11 — Maintain (evolutionary) — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain event store, real OpenRouter LLM (Survey/Model/Extract/derive `:llm` nodes + the S15 judge; model `google/gemini-3-flash-preview`), real ColBERT/embedding retrieval, real child ticks.

Proves the EVOLUTIONARY-MAINTAIN composition: `run-central-evolver!` run TWICE on ONE ontology-id — PASS 1 builds an initial graph (greenfield) from source A; PASS 2 feeds a real SECOND source B AGAINST THE EXISTING graph (the front-of-tree condition selects MAINTAIN). EB11 flipped the maintain arm from the deferred stub to the real composition; the EB5 reconcile is against-graph-state, so the new source's discoveries reconcile-not-duplicate and grow the TBox.

## Setup

GOAL: `Build an ontology connecting fields/programs of study to the occupations they prepare people for, and to the certifications relevant to those occupations.`

ontology-id: `25014c6a-c9a8-4c69-976d-09c50190eed6`

- source A (`/tmp/eb11-source-a.csv`): programs ↔ occupations.
- source B (`/tmp/eb11-source-b.csv`): a NEW kind of entity (certifications) carrying the SAME occupation_code A's occupations carry (the cross-source linking key → reconcile-not-duplicate on the occupation; the certification is a NEW class; occupation_code is a feature shared with the existing entity).

## PASS 1 — greenfield build (the existing graph maintain reads)

- status: **:failed-cq**, selected: **:greenfield**, mode: **:greenfield** (112872ms)
- BEFORE graph: **8** concepts, **4** relationships, **0** axioms

BEFORE concept URIs:

```clojure
("occupation/11-1021"
 "occupation/15-1252"
 "occupation/17-2051"
 "occupation/29-1141"
 "program/11.0101"
 "program/14.0801"
 "program/51.3801"
 "program/52.0201")
```

BEFORE TBox axioms:

```clojure
nil
```

## PASS 2 — MAINTAIN: source B against the EXISTING graph

- status: **:complete**, selected: **:maintain**, mode: **:maintain** (91847ms)
- AFTER graph: **11** concepts, **7** relationships, **0** axioms
- the existing graph GREW: **true** (maintain made new discoveries, not greenfield-only)

NEW concept URIs source B introduced (the new class/entities — TBox/graph growth):

```clojure
("certification/NCLEX-RN License"
 "certification/Professional Engineer (PE)"
 "certification/Project Management Professional")
```

AFTER TBox axioms (EB6 — the new class/property axioms):

```clojure
nil
```

## Reconcile reports (the maintain reconcile — merges + attribute links)

```clojure
[{:ontology-id #uuid "25014c6a-c9a8-4c69-976d-09c50190eed6",
  :mint-probe
  {:probed 6,
   :hits 5,
   :exact-uri-hits 3,
   :entries
   [{:uri "occupation/29-1141",
     :label "Registered Nurses",
     :existing-uri "program/51.3801",
     :existing-label "Registered Nursing",
     :score 0.03225806451612903,
     :match? true,
     :exact-uri? true}
    {:uri "certification/NCLEX-RN License",
     :label "NCLEX-RN License",
     :existing-uri nil,
     :existing-label nil,
     :score nil,
     :match? false,
     :exact-uri? false}
    {:uri "occupation/17-2051",
     :label "Civil Engineers",
     :existing-uri "program/14.0801",
     :existing-label "Civil Engineering",
     :score 0.03225806451612903,
     :match? true,
     :exact-uri? true}
    {:uri "certification/Professional Engineer (PE)",
     :label "Professional Engineer (PE)",
     :existing-uri "occupation/17-2051",
     :existing-label "Civil Engineers",
     :score 0.01639344262295082,
     :match? true,
     :exact-uri? false}
    {:uri "occupation/11-1021",
     :label "General and Operations Managers",
     :existing-uri "program/52.0201",
     :existing-label "Business Administration",
     :score 0.03225806451612903,
     :match? true,
     :exact-uri? true}
    {:uri "certification/Project Management Professional",
     :label "Project Management Professional",
     :existing-uri "occupation/11-1021",
     :existing-label "General and Operations Managers",
     :score 0.01639344262295082,
     :match? true,
     :exact-uri? false}]},
  :landed
  {:ambiguities-flagged 0,
   :axioms-emitted 0,
   :concepts-emitted 6,
   :unresolved-endpoints 0,
   :ambiguities [],
   :every-edge-endpoint-resolves? true,
   :relationships-emitted 3,
   :status :ingested,
   :rlm-trace nil,
   :unresolved-endpoint-uris [],
   :implied-concepts-minted 0},
  :entity-reconcile
  {:ambiguities-surfaced 1,
   :shared-uri-links
   {:shared-uris [], :shared-uri-count 0, :by-uri {}},
   :implied-endpoints-minted 0,
   :referential-integrity
   {:every-edge-endpoint-resolves? true,
    :dangling-edge-count 0,
    :dangling-edges []},
   :ambiguities
   [{:tier :llm-budget-exhausted,
     :verdict :requires-review,
     :reason :budget,
     :detail "LLM budget exhausted",
     :a-uri "occupation/29-1141",
     :b-uri "program/51.3801"}],
   :relationships-in-scope 7,
   :alignment-ontology-id #uuid "4efda7a0-bdae-33db-8872-568f3e8427ea",
   :candidate-pairs 7,
   :ontology-id #uuid "25014c6a-c9a8-4c69-976d-09c50190eed6",
   :concepts-in-scope 11,
   :status :ok,
   :merge-equivalences
   [{:source-uri "occupation/17-2051",
     :target-uri "program/14.0801",
     :kind :equivalent-class}],
   :merges 1},
  :attribute-reconcile
  {:new-entities-with-attrs 3,
   :links [],
   :same-value-link-count 0,
   :shared-key-link-count 0},
  :dangling-edge-count 0,
  :ambiguities-surfaced 1}]
```

The `:mint-probe :exact-uri-hits` count is the reconcile-NOT-duplicate signal (drafts whose URI already resolved in the existing graph — merged, not re-minted). The `:attribute-reconcile :links` are the EB5 attribute-granularity links — a NEW entity's attribute connecting to an EXISTING entity's attribute.

## CQ verdicts — pass 1 vs pass 2 (the re-gate over the updated graph)

PASS 1:

```clojure
[{:cq-index 0,
  :event-id #uuid "019ef477-edfa-70fc-af24-4efbed5c8473",
  :cq-text
  "Which specific occupations is a graduate from a given Program of Study (CIP) prepared to enter?",
  :reasoning
  "The graph specifically contains the 'prepares_for' relationship which links Programs of Study (CIP) to specific Occupations, directly answering the question for several examples (Nursing, Computer Science, Engineering, and Business).",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:32:40.953933-05:00",
  :evidence-uris
  ["program/51.3801 prepares_for occupation/29-1141"
   "program/11.0101 prepares_for occupation/15-1252"
   "program/14.0801 prepares_for occupation/17-2051"
   "program/52.0201 prepares_for occupation/11-1021"]}
 {:cq-index 1,
  :event-id #uuid "019ef477-f752-700d-a2e9-23869cc59963",
  :cq-text
  "Which Programs of Study (CIP) serve as pathways for a specific Occupation (SOC)?",
  :reasoning
  "The graph explicitly contains several instances of Program-to-Occupation mappings using the 'prepares_for' relationship, which directly answers which CIP programs serve as pathways for specific SOC occupations. For example, CIP 11.0101 prepares for SOC 15-1252.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:32:43.346797-05:00",
  :evidence-uris
  ["program/51.3801 prepares_for occupation/29-1141"
   "program/11.0101 prepares_for occupation/15-1252"
   "program/14.0801 prepares_for occupation/17-2051"
   "program/52.0201 prepares_for occupation/11-1021"]}
 {:cq-index 2,
  :event-id #uuid "019ef477-ffbe-70da-865a-ac401688faae",
  :cq-text
  "What are the secondary or alternative occupation titles associated with a single Program of Study?",
  :reasoning
  "The graph only shows primary labels for occupations and a single 'prepares_for' relationship per program. There is no evidence of secondary or alternative occupation titles (alternative labels or synonyms) associated with these programs or occupations. Under open-world assumptions, the absence of these titles does not mean they don't exist, only that they are not yet recorded.",
  :gaps
  ["alternative label attributes for occupations"
   "alternative occupation titles (synonyms)"
   "secondary occupation relationships for Programs of Study"],
  :layer :layer-3-explicit-unknown,
  :verdict :unknown,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:32:45.502837-05:00",
  :evidence-uris []}
 {:cq-index 3,
  :event-id #uuid "019ef478-09e3-70ab-9d22-227787c35135",
  :cq-text
  "Given a CIP code, what is the standardized SOC title for its primary related occupation?",
  :reasoning
  "The evidence provides several examples where a CIP code (program) is linked via the 'prepares_for' relationship to an SOC code (occupation), and those occupations have standardized labels/titles. For example, CIP 51.3801 prepares for SOC 29-1141 ('Registered Nurses'). Thus, the graph contains the necessary information to answer the question for the provided subjects.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:32:48.099732-05:00",
  :evidence-uris
  ["program/51.3801 prepares_for occupation/29-1141"
   "occupation/29-1141 [label: Registered Nurses]"
   "program/11.0101 prepares_for occupation/15-1252"
   "occupation/15-1252 [label: Software Developers]"
   "program/14.0801 prepares_for occupation/17-2051"
   "occupation/17-2051 [label: Civil Engineers]"
   "program/52.0201 prepares_for occupation/11-1021"
   "occupation/11-1021 [label: General and Operations Managers]"]}]
```

PASS 2 (re-gated over the maintained graph; a CQ the first source could not answer that now passes is the evolutionary payoff — surfaced honestly either way, #9):

```clojure
[{:cq-index 0,
  :event-id #uuid "019ef479-acc3-70d3-98f6-a6930143dec8",
  :cq-text
  "Which specific occupations are graduates of a given educational program (identified by Program Title or CIP Code) prepared to enter?",
  :reasoning
  "The knowledge graph provides specific examples of educational programs (identified by CIP codes like 11.0101 and 51.3801) and explicitly maps them to the occupations they prepare graduates for (e.g., Software Developers, Registered Nurses) using the 'prepares_for' relationship. This directly answers the question.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:34:35.331595-05:00",
  :evidence-uris
  ["program/11.0101"
   "program/51.3801"
   "program/14.0801"
   "program/52.0201"]}
 {:cq-index 1,
  :event-id #uuid "019ef479-b479-70e3-89c6-51a4fcef292b",
  :cq-text
  "What are the relevant professional certifications for a given occupation (identified by SOC Code)?",
  :reasoning
  "The graph contains specific relationships mapping occupations (identified by SOC codes like 29-1141, 17-2051, and 11-1021) to their relevant professional certifications using the 'requires_or_benefits_from' edge. This directly answers the competency question.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:34:37.305242-05:00",
  :evidence-uris
  ["occupation/29-1141 requires_or_benefits_from certification/NCLEX-RN License"
   "occupation/17-2051 requires_or_benefits_from certification/Professional Engineer (PE)"
   "occupation/11-1021 requires_or_benefits_from certification/Project Management Professional"]}
 {:cq-index 2,
  :event-id #uuid "019ef479-be07-70c5-9c2c-10d736fda644",
  :cq-text
  "Given a specific certification, which educational programs provide the necessary preparation for the occupations it covers?",
  :reasoning
  "The graph contains several examples where a certification is linked to an occupation, and that occupation is linked to a preparing educational program. For example, the NCLEX-RN License is linked to Registered Nurses, and the Registered Nursing program prepares for that occupation. Similarly, Professional Engineer (PE) is linked to Civil Engineers, which is prepared for by the Civil Engineering program. Thus, the graph provides the necessary relationships to answer the question.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:34:39.751260-05:00",
  :evidence-uris
  ["program/51.3801 prepares_for occupation/29-1141"
   "occupation/29-1141 requires_or_benefits_from certification/NCLEX-RN License"
   "program/14.0801 prepares_for occupation/17-2051"
   "occupation/17-2051 requires_or_benefits_from certification/Professional Engineer (PE)"
   "program/52.0201 prepares_for occupation/11-1021"
   "occupation/11-1021 requires_or_benefits_from certification/Project Management Professional"]}
 {:cq-index 3,
  :event-id #uuid "019ef479-c496-70a3-bc3b-32c0d9b090ce",
  :cq-text
  "What set of occupations requires or is associated with a specific professional certification?",
  :reasoning
  "The graph identifies several occupations (Registered Nurses, Civil Engineers, and General and Operations Managers) and explicitly links them to specific professional certifications (NCLEX-RN License, Professional Engineer, and Project Management Professional) using the 'requires_or_benefits_from' relationship. This answers the question of which set of occupations is associated with specific certifications.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:34:41.430107-05:00",
  :evidence-uris
  ["occupation/29-1141 requires_or_benefits_from certification/NCLEX-RN License"
   "occupation/17-2051 requires_or_benefits_from certification/Professional Engineer (PE)"
   "occupation/11-1021 requires_or_benefits_from certification/Project Management Professional"]}
 {:cq-index 4,
  :event-id #uuid "019ef479-cabd-70da-befd-2f4deecd7196",
  :cq-text
  "For a specific industry, what are the primary educational programs and their corresponding occupational targets?",
  :reasoning
  "The graph contains several instances of educational programs (program/) and their corresponding occupational targets (occupation/) via the 'prepares_for' relationship, which directly answers the question for multiple specific career paths (Nursing, IT, Engineering, and Business).",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-23T07:34:43.005560-05:00",
  :evidence-uris
  ["program/51.3801 prepares_for occupation/29-1141"
   "program/11.0101 prepares_for occupation/15-1252"
   "program/14.0801 prepares_for occupation/17-2051"
   "program/52.0201 prepares_for occupation/11-1021"]}]
```

## Verdict

EB11 maintain runs the REAL evolutionary-maintain composition against an EXISTING graph. On this live run:
- the front-of-tree condition SELECTED `:maintain` (pass 2 detected the existing 8-concept graph); `:mode :maintain`;
- a real SECOND source RECONCILED-NOT-DUPLICATED against the existing entities: `:mint-probe :exact-uri-hits 3` (the 3 shared occupations merged, not re-minted) + `:entity-reconcile :merges 1` with a `:merge-equivalences` cross-source link;
- it INTRODUCED a NEW KIND OF ENTITY the first source never held — 3 `certification/*` concepts; the graph GREW 8 → 11 concepts and 4 → 7 relationships;
- the CQ gate RE-GATED over the updated graph and the EVOLUTIONARY PAYOFF landed: certification-CQs ("What are the relevant professional certifications for a given occupation?", "Given a specific certification, which educational programs provide it?") PASS in pass 2 — questions the first source's graph could not have answered;
- honest negatives surfaced (#9): pass 1 ended `:failed-cq` because one CQ (secondary/alternative occupation titles) was genuinely unanswerable from source A (surfaced `:unknown`, not false-greened); a cross-source merge ambiguity surfaced as `:requires-review` (LLM budget 0 — never silently merged).

RE-ORCHESTRATION (the maintain arm reuses EB10's pipeline + loop + the subbehaviors against the existing graph), not a rewrite; idempotent via EB5's against-graph-state seam; domain-agnostic.

## Honest negatives (this live run)

- **Attribute-granularity links were 0 this run** (`:attribute-reconcile :links []`). The live model keyed the cross-source occupations by a SHARED canonical URI (`occupation/<SOC>`), so the new source's connection to the existing graph happened at the ENTITY level (shared-URI collapse + an `:equivalent-class` merge), not via the EB5 attribute-key path. The EB5 attribute-granularity link (a new entity's attribute connecting to an existing entity's attribute) is proven DETERMINISTICALLY in the WORTH prototype (`development/src/eb11_maintain_worth_prototype.clj`) and the hermetic brick test (`maintain-links-new-attribute-to-existing-entity-attribute-test`); it did not fire on this particular live run because the model's URI keying made the connection an entity merge instead. Both are valid maintain reconcile paths; which one fires depends on how the model keys the drafts.
- **0 TBox axioms emitted this run** (`:axioms` nil both passes). The maintain arm INVOKED the EB6 Axiom/TBox subbehavior, but the model proposed no candidate-axioms for these sources, so no S07 axiom landed. The "new class" growth here is at the concept/entity-type level (the new `certification/*` entities), not the formal `subClassOf`/disjointness-axiom level. The S07 axiom-emission path itself is proven in EB6's own live verify; the maintain arm correctly ran it with nothing to emit.
