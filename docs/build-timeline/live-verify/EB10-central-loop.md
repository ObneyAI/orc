# EB10 — Central evolver loop (the keystone) — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain event store, real OpenRouter LLM (Survey repl-researcher, Model/Extract/derive `:llm` nodes, the ROUTE `:llm` node, the S15 judge; model `google/gemini-3-flash-preview`), real ColBERT/embedding retrieval (Embed+Index + the S15 three-layer runner), real child ticks.

Proves the composed CENTRAL evolver tree runs the REAL EB2-EB9 subbehaviors via `:delegate` end-to-end to a CQ verdict on a REAL source, with CQ-satisfaction as the OBJECTIVE: the loop runs the S15 gate IN-PROCESS with the real LLM judge (the judge fn-value cannot cross `:delegate`), routes any failing CQ to the closing subbehavior (`:reasoning` first), re-invokes it focally, re-gates, and ALWAYS terminates with a surfaced reason. RE-HOUSE/REUSE (DT8 loop + DT9 greenfield-vs-maintain + `build!` + the subbehaviors via `:delegate`) — not a rewrite.

## Setup

GOAL: `Build an ontology connecting fields/programs of study to the occupations they prepare people for.`

REAL source (`/tmp/eb10-live-source.csv`): a programs↔occupations csv.

ontology-id: `1bce0867-c483-45e7-934c-227ad497084d`

## Result

- central evolver status: **:failed-cq** (93149ms)
- concepts landed: **10**
- greenfield-vs-maintain (DT9): **:greenfield**
- loop termination reason: **:budget-exhausted**

## Survey profiles (per source, via `:delegate`)

```clojure
[{:entity-candidates
  ["program_of_study"
   "occupation"
   "educational_institution"
   "industry"],
  :identifying-keys
  {"program_of_study" ["program_id" "program_name" "cip_code"],
   "occupation" ["soc_code" "occupation_title"],
   "educational_institution" ["institution_id" "institution_name"]},
  :scope-fields
  ["program_name"
   "occupation_title"
   "degree_level"
   "required_skills"
   "career_pathway"],
  :linking-keys ["cip_code" "soc_code" "program_id" "job_family_id"],
  :grain-signals
  ["One row per program-to-occupation mapping"
   "Each program may link to multiple occupations"
   "Occupations may be associated with multiple fields of study"],
  :embed-worthy-fields
  ["program_description"
   "occupation_description"
   "skills_summary"
   "tasks_performed"],
  :sample
  {:rows
   [{"program_code" "01.0000",
     "program_title" "Agriculture, General",
     "occupation_code" "19-1011",
     "occupation_title" "Animal Scientists"}
    {"program_code" "51.3801",
     "program_title" "Registered Nursing",
     "occupation_code" "29-1141",
     "occupation_title" "Registered Nurses"}
    {"program_code" "11.0101",
     "program_title" "Computer and Information Sciences",
     "occupation_code" "15-1252",
     "occupation_title" "Software Developers"}
    {"program_code" "52.0201",
     "program_title" "Business Administration and Management",
     "occupation_code" "11-1021",
     "occupation_title" "General and Operations Managers"}
    {"program_code" "14.0801",
     "program_title" "Civil Engineering",
     "occupation_code" "17-2051",
     "occupation_title" "Civil Engineers"}],
   :returned 5,
   :requested 10,
   :offset 0,
   :capped? false}}]
```

## Derived competency questions (surfaced for HITL review)

1. Which specific occupations are graduates of a given 'program_of_study' (identified by program_title or cip_code) prepared to enter?
2. Which 'programs_of_study' provide the necessary credentials for a specific 'occupation' (identified by occupation_title or soc_code)?
3. What are the common 'required_skills' shared between a 'program_of_study' and its associated 'occupations'?
4. Which 'occupations' are reachable from multiple distinct 'programs_of_study'?
5. What 'degree_level' is typically required for a 'program_of_study' to map to a specific 'occupation'?
6. For a given 'occupation', what are the primary 'career_pathway' options identified across different programs?

## CQ verdict — the OBJECTIVE (in-process S15 gate, real judge)

```clojure
[{:cq-index 0,
  :event-id #uuid "019ef276-a993-707a-a83a-caa2f3e070eb",
  :cq-text
  "Which specific occupations are graduates of a given 'program_of_study' (identified by program_title or cip_code) prepared to enter?",
  :reasoning
  "The knowledge graph explicitly contains the 'prepares_for' relationship between 'program_of_study' entities (identified by both titles and CIP codes) and specific 'occupation' entities, answering the competency question affirmatively.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-22T22:12:03.475521-05:00",
  :evidence-uris
  ["program_of_study/01.0000/Agriculture, General prepares_for occupation/19-1011/Animal Scientists"
   "program_of_study/51.3801/Registered Nursing prepares_for occupation/29-1141/Registered Nurses"
   "program_of_study/11.0101/Computer and Information Sciences prepares_for occupation/15-1252/Software Developers"
   "program_of_study/52.0201/Business Administration and Management prepares_for occupation/11-1021/General and Operations Managers"
   "program_of_study/14.0801/Civil Engineering prepares_for occupation/17-2051/Civil Engineers"]}
 {:cq-index 1,
  :event-id #uuid "019ef276-b422-702d-bfdb-d3e75a182a53",
  :cq-text
  "Which 'programs_of_study' provide the necessary credentials for a specific 'occupation' (identified by occupation_title or soc_code)?",
  :reasoning
  "The graph explicitly contains relationships mapping programs of study (identified by CIP codes like 11.0101) to specific occupations (identified by SOC codes like 15-1252) using the 'prepares_for' edge. This directly answers the question of which programs provide credentials/preparation for specific occupations.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-22T22:12:06.178269-05:00",
  :evidence-uris
  ["program_of_study/01.0000/Agriculture, General prepares_for occupation/19-1011/Animal Scientists"
   "program_of_study/51.3801/Registered Nursing prepares_for occupation/29-1141/Registered Nurses"
   "program_of_study/11.0101/Computer and Information Sciences prepares_for occupation/15-1252/Software Developers"
   "program_of_study/52.0201/Business Administration and Management prepares_for occupation/11-1021/General and Operations Managers"
   "program_of_study/14.0801/Civil Engineering prepares_for occupation/17-2051/Civil Engineers"]}
 {:cq-index 2,
  :event-id #uuid "019ef276-bac5-7002-82e1-70d01beb0941",
  :cq-text
  "What are the common 'required_skills' shared between a 'program_of_study' and its associated 'occupations'?",
  :reasoning
  "The retrieved graph identifies several 'program_of_study' entities and their associated 'occupation' entities via the 'prepares_for' relationship. However, there are no 'required_skills' attributes or relationships identified for any of these nodes. Therefore, it is impossible to determine which skills are shared between them.",
  :gaps
  ["required_skills attributes for program_of_study nodes"
   "required_skills attributes for occupation nodes"],
  :layer :layer-3-explicit-unknown,
  :verdict :unknown,
  :judged-by? true,
  :evaluated-at "2026-06-22T22:12:07.877069-05:00",
  :evidence-uris []}
 {:cq-index 3,
  :event-id #uuid "019ef276-c1f5-70d7-8d8e-3e477342caf8",
  :cq-text
  "Which 'occupations' are reachable from multiple distinct 'programs_of_study'?",
  :reasoning
  "The current graph shows a 1-to-1 mapping between programs of study and occupations for all five recorded pairs. No occupation in the retrieved set is currently linked to more than one program of study. Because the graph is open-world and incomplete, the absence of such links does not mean they do not exist; it simply means they have not been recorded yet.",
  :gaps
  ["additional prepares_for relationships linking multiple programs_of_study to the same occupation"],
  :layer :layer-3-explicit-unknown,
  :verdict :unknown,
  :judged-by? true,
  :evaluated-at "2026-06-22T22:12:09.717460-05:00",
  :evidence-uris []}
 {:cq-index 4,
  :event-id #uuid "019ef276-cb11-7036-ab3c-2d65eedb64b0",
  :cq-text
  "What 'degree_level' is typically required for a 'program_of_study' to map to a specific 'occupation'?",
  :reasoning
  "The graph contains mappings between 'program_of_study' and 'occupation' via the 'prepares_for' relationship, but it does not specify any 'degree_level' attribute or secondary relationship that defines the level of education required for these mappings. In an open-world graph, the absence of this attribute must be treated as unknown.",
  :gaps
  ["degree_level attributes or edges for program_of_study -> occupation mappings"],
  :layer :layer-3-explicit-unknown,
  :verdict :unknown,
  :judged-by? true,
  :evaluated-at "2026-06-22T22:12:12.049408-05:00",
  :evidence-uris []}
 {:cq-index 5,
  :event-id #uuid "019ef276-d3d6-70f5-9868-da6d9d455e2d",
  :cq-text
  "For a given 'occupation', what are the primary 'career_pathway' options identified across different programs?",
  :reasoning
  "The graph provides relationships between 'program_of_study' and 'occupation' (using 'prepares_for'), but it contains no entities or relationships corresponding to 'career_pathway'. Therefore, it is impossible to identify pathway options for any occupation.",
  :gaps
  ["career_pathway entities or attributes"
   "occupation belongs_to career_pathway edges"
   "program_of_study offers career_pathway edges"],
  :layer :layer-3-explicit-unknown,
  :verdict :unknown,
  :judged-by? true,
  :evaluated-at "2026-06-22T22:12:14.294385-05:00",
  :evidence-uris []}]
```

graph-health (the gate metric; `:unknown-rate` first-class):

```clojure
{:unknown-rate 0.6666666666666667,
 :unknown-count 4,
 :fail-rate 0.0,
 :layer-counts
 {:layer-2-semantic-exists 2, :layer-3-explicit-unknown 4},
 :ontology-id #uuid "1bce0867-c483-45e7-934c-227ad497084d",
 :judge-share 1.0,
 :last-evaluation-ts "2026-06-22T22:12:14.294385-05:00",
 :total-cqs 6,
 :pass-rate 0.3333333333333333,
 :fail-count 0,
 :pass-count 2}
```

## The per-iteration loop TRACE

termination-reason: **:budget-exhausted**, iterations: **2**, unanswerable-cqs: `["What are the common 'required_skills' shared between a 'program_of_study' and its associated 'occupations'?" "Which 'occupations' are reachable from multiple distinct 'programs_of_study'?"]`

```clojure
[{:route-reasoning
  "The competency question requires a comparison of 'required_skills' across two different entity types: 'program_of_study' and 'occupations'. The current graph health indicates a high \"unknown\" rate (66%), suggesting that while the entities might exist, the specific attribute ('required_skills') or the relationship connecting them is likely missing or not populated in a way that allows for intersection. Since the question asks for shared skills, this implies that the 'required_skills' should ideally be modeled as discrete entities or a consistent attribute format that can be reconciled across the program and the occupation. If the entities 'program_of_study' and 'occupations' already exist in the graph but the connection (the 'associated' link or the shared skill nodes) is missing, the most effective step is to reconcile these entities to establish the necessary links. However, if the skills were never pulled out of the source text as properties or related nodes for both entities, an extraction pass is needed. Given the phrasing \"shared between,\" this is fundamentally a data linkage/reconciliation gap between two sets of attributes that should point to the same conceptual skills.",
  :close-status :ok,
  :route :reconcile,
  :iteration 1,
  :after 15,
  :graph-grew? false,
  :newly-unanswerable
  ["What are the common 'required_skills' shared between a 'program_of_study' and its associated 'occupations'?"],
  :failing-cq
  "What are the common 'required_skills' shared between a 'program_of_study' and its associated 'occupations'?",
  :before 15}
 {:route-reasoning
  "The failing CQ asks for a count or identification of 'occupations' connected to multiple 'programs_of_study'. Based on the graph health metrics, we see a high \"unknown-rate\" (66.6%) specifically in the semantic and explicit layers. This indicates that while the entities 'occupations' and 'programs_of_study' may exist in the graph, the specific associative triples (links) required to traverse from a program to its resultant occupation are missing or not properly instantiated across the dataset. The problem is a lack of connectivity between established entity types rather than a definition error or a total lack of source data. Therefore, a reconciliation step is needed to resolve the relationships (links) between these existing nodes.",
  :close-status :ok,
  :route :reconcile,
  :iteration 2,
  :after 15,
  :graph-grew? false,
  :newly-unanswerable
  ["Which 'occupations' are reachable from multiple distinct 'programs_of_study'?"],
  :failing-cq
  "Which 'occupations' are reachable from multiple distinct 'programs_of_study'?",
  :before 15}]
```

## Verdict

The composed central evolver `:delegate`s to the REAL EB2-EB9 subbehaviors (Survey → Model→Extract → Reconcile → Axiom → Embed → derive-CQs) end-to-end to a CQ verdict on a real source. The CQ gate is the loop OBJECTIVE, run IN-PROCESS with the real judge; a failing CQ ROUTES (`:reasoning` first) to the closing subbehavior, re-invoked FOCALLY, re-gated; the loop ALWAYS terminates with a surfaced reason (here: **:budget-exhausted**). RE-HOUSE/REUSE, not a rewrite; domain-agnostic.
