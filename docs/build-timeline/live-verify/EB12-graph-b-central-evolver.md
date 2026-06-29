# EB12 — Graph B via the CURRENT architecture (central evolver) — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain, real OpenRouter (`google/gemini-3-flash-preview`), real local embeddings (all-MiniLM-L6-v2), real ColBERT. The composed central evolver (`run-central-evolver!`) `:delegate`s the EB2-EB9 subbehaviors; CQ-gate in-process with the real judge.

Ontology-id: `224866bc-2a11-4a64-a12e-dd288f781941`. Sources: [:ipeds :crosswalk :onet].
Status: **:failed-cq** mode **:greenfield** (340601 ms). Budget/source: `{:max-iterations 16, :total-budget-ms 600000, :max-retries 3}`.

## The no-hardcoding audit — the only per-source text is the DOMAIN GOAL

```
DOMAIN GOAL — build a comprehensive, connected ontology over these Louisiana education-and-career sources: the educational programs offered, the fields of study they belong to, the occupations those fields lead to, the institutions that offer them, and the earnings / wage outcomes associated with them.

Cover the Louisiana program set COMPREHENSIVELY. Where the source you are exploring is large, PAGE through it (use the :offset window affordance of the sampling tools) until you have covered the entities the goal asks for — do not settle for the first window. The deterministic transform you design runs over ALL the rows you gather at no extra cost, so retrieve the full relevant set, not a token sample.

Where different sources refer to the same real-world entity, MERGE them by minting the SAME canonical identifier (a stable, shareable id derived from the code system the source itself uses), so a concept this source contributes and a concept another source contributes for the same real thing resolve to ONE node. FIND and USE whatever shared keys or crosswalk information the sources THEMSELVES provide to connect across sources — explore the source to discover what those keys are; they are not given to you.

Carry any numeric OUTCOME a concept has (earnings, wages, employment, growth, tuition, percentiles) in :attributes as native numbers so they stay queryable.

This is ONE of several sources that together form the connected graph; mint your concepts so they will link up with the others by shared canonical id.

```

## Graph-structure stats (A2-vs-B comparable)

```clojure
{:cross-source-links
 {[:fieldofstudy "identified-by" :cipcode] 59,
  [:occupation "identified-by" :cipcode] 37,
  [:fieldofstudy "identified-by" :soccode] 59,
  [:programtooccupationmapping "identified-by" :soccode] 200,
  [:programtooccupationmapping "maps_from_field" :fieldofstudy] 200,
  [:programtooccupationmapping "identified-by" :cipcode] 200,
  [:programtooccupationmapping "maps_to_occupation" :occupation] 200,
  [:occupation "identified-by" :soccode] 37},
 :concepts-with-attributes 701,
 :dangling-edge-count 0,
 :cross-source-link-total 992,
 :relationship-count 992,
 :graph-health
 {:fragmented? false,
  :fragmented-identity-count 0,
  :fragmented-identities []},
 :every-edge-endpoint-resolves true,
 :concept-count 701,
 :relationships-by-predicate
 {"identified-by" 592,
  "maps_from_field" 200,
  "maps_to_occupation" 200},
 :sample-dangling-edges [],
 :axiom-count 6,
 :earnings-or-wage-bearing-concepts 0,
 :concepts-by-kind
 {:educationalinstitution 309,
  :programtooccupationmapping 200,
  :cipcode 59,
  :fieldofstudy 59,
  :soccode 37,
  :occupation 37}}
```

## Connectivity proof (multi-hop read-back)

```clojure
{:no-complete-chain true,
 :roles-detected
 {:program :programtooccupationmapping,
  :cip :cipcode,
  :soc :programtooccupationmapping,
  :institution :educationalinstitution,
  :earnings nil},
 :program-count 200,
 :note
 "No program->field->occupation chain — see cross-source-links for where it broke."}
```

## Earnings→program verdict (MEASURED OUTCOME)

```clojure
{:earnings-concept-count 0,
 :earnings-edge-count 0,
 :earnings-edges-by-link {},
 :earnings-connects-to-program-side? false,
 :connects-to-kinds #{}}
```

## CQ verdict + loop trace (the OBJECTIVE)

```clojure
{:cq-verdict
 [{:cq-index 0,
   :event-id #uuid "019f14ea-52fe-7028-97c1-6fad62b65c9b",
   :cq-text
   "Which educational institutions offer programs within a specific CIP (Classification of Instructional Programs) code in Louisiana?",
   :reasoning
   "The retrieved graph contains numerous 'EducationalInstitution' entities (e.g., 107992, 107974) and 'CIPCode' / 'FieldOfStudy' entities. However, there are no relationship edges connecting institutions to the programs they offer (no 'offers' or 'has_program' edges). Furthermore, the graph does not contain location information (e.g., state: Louisiana) for the specific institutions listed. Therefore, it is impossible to determine which institutions are in Louisiana and which programs they offer.",
   :gaps
   ["offers_program relationships between educationalinstitution and fieldofstudy or cipcode"
    "state or location attributes for educationalinstitution entities"],
   :layer :layer-3-explicit-unknown,
   :verdict :unknown,
   :judged-by? true,
   :evaluated-at "2026-06-29T14:45:28.830282-05:00",
   :evidence-uris []}
  {:cq-index 1,
   :event-id #uuid "019f14ea-60e1-70a0-ae9a-230f9756312d",
   :cq-text
   "What are the mapped SOC (Standard Occupational Classification) occupations for a given educational program's CIP code?",
   :reasoning
   "The knowledge graph contains numerous 'programtooccupationmapping' nodes that explicitly map CIP codes (e.g., 01.0401, 01.0205, 01.0601) to SOC occupations (e.g., 19-4013, 49-3011, 25-1041). The evidence shows both the relationship nodes and the specific identified-by links between CIP codes and SOC codes.",
   :layer :layer-2-semantic-exists,
   :verdict :pass,
   :judged-by? true,
   :evaluated-at "2026-06-29T14:45:32.385097-05:00",
   :evidence-uris
   ["programtooccupationmapping/01.0401/19-4013"
    "programtooccupationmapping/01.0205/49-3011"
    "programtooccupationmapping/01.0601/25-1041"
    "programtooccupationmapping/01.0205/49-3041"
    "programtooccupationmapping/01.1004/25-1041"
    "programtooccupationmapping/01.0307/45-1011"
    "programtooccupationmapping/01.0307/45-2021"]}
  {:cq-index 2,
   :event-id #uuid "019f14ea-6f8f-708b-895c-4db90c69d7c9",
   :cq-text
   "What are the average earnings or wage outcomes for graduates of a specific program at a particular institution?",
   :reasoning
   "The retrieved graph contains information about educational institutions, fields of study, and mappings between programs and occupations. However, it does not contain any attributes or edges representing earnings, wages, or financial outcomes for graduates of these programs. There is no explicit closure signal regarding earnings data, so the answer is unknown.",
   :gaps
   ["earnings attributes"
    "wage-outcome attributes"
    "graduate-salary data per program-institution pair"],
   :layer :layer-3-explicit-unknown,
   :verdict :unknown,
   :judged-by? true,
   :evaluated-at "2026-06-29T14:45:36.143344-05:00",
   :evidence-uris []}
  {:cq-index 3,
   :event-id #uuid "019f14ea-80a0-705d-a7bc-a69a548e4b20",
   :cq-text
   "Which institutions provide programs that lead to a specific occupation (SOC code) based on the CIP-to-SOC crosswalk?",
   :reasoning
   "The knowledge graph contains numerous 'ProgramToOccupationMapping' entities that link CIP codes (programs) to SOC codes (occupations) based on the crosswalk. It also contains numerous 'EducationalInstitution' entities. However, there are no edges in the retrieved context that connect specific institutions to either the programs they offer or the occupation mappings. While we have the components (Institutions, Programs, and Mappings), the relational link that would identify which institution provides which program is missing.",
   :gaps
   ["edges connecting EducationalInstitution to FieldOfStudy/Program (e.g., 'offers' or 'provides')"
    "edges connecting EducationalInstitution to ProgramToOccupationMapping"],
   :layer :layer-3-explicit-unknown,
   :verdict :unknown,
   :judged-by? true,
   :evaluated-at "2026-06-29T14:45:40.512773-05:00",
   :evidence-uris
   ["programtooccupationmapping/01.1004/25-1041"
    "programtooccupationmapping/01.0307/45-1011"
    "educationalinstitution/107992"
    "educationalinstitution/102368"]}
  {:cq-index 4,
   :event-id #uuid "019f14ea-8d83-702c-99c5-d4f43671cc48",
   :cq-text
   "How do enrollment numbers (e.g., male vs. female) vary across different educational programs or institutions for a specific academic year?",
   :reasoning
   "The retrieved evidence identifies numerous educational institutions and fields of study (programs), and maps programs to potential occupations. However, the graph contains no attributes or relationships describing enrollment quantitative data, gender distributions, or specific academic years for these entities. Without specific enrollment-related attributes or a closure signal regarding such data, the graph is silent on the question.",
   :gaps
   ["enrollment count attributes (e.g., total_enrollment, male_enrollment, female_enrollment)"
    "academic year attributes"],
   :layer :layer-3-explicit-unknown,
   :verdict :unknown,
   :judged-by? true,
   :evaluated-at "2026-06-29T14:45:43.811309-05:00",
   :evidence-uris
   ["educationalinstitution/107992"
    "fieldofstudy/01.0102"
    "programtooccupationmapping/01.1004/25-1041"]}],
 :graph-health
 {:unknown-rate 0.8,
  :unknown-count 4,
  :fail-rate 0.0,
  :layer-counts
  {:layer-3-explicit-unknown 4, :layer-2-semantic-exists 1},
  :ontology-id #uuid "224866bc-2a11-4a64-a12e-dd288f781941",
  :judge-share 1.0,
  :last-evaluation-ts "2026-06-29T14:45:43.811309-05:00",
  :total-cqs 5,
  :pass-rate 0.2,
  :fail-count 0,
  :pass-count 1},
 :cq-loop
 {:iterations 2,
  :termination-reason :budget-exhausted,
  :unanswerable-cqs
  ["Which educational institutions offer programs within a specific CIP (Classification of Instructional Programs) code in Louisiana?"
   "What are the average earnings or wage outcomes for graduates of a specific program at a particular institution?"],
  :history
  [{:route-reasoning
    "The failing competency question requires a relationship between three key elements: Educational Institutions, Programs (identified by CIP codes), and Geography (Louisiana). The graph health indicates a high \"unknown\" rate (80%), meaning the entities may exist in isolation, but the specific predicate connecting an Institution to a CIP-coded Program is likely missing or was not captured during the initial extraction phase. Since the data source for such a question (typically IPEDS or similar higher-education datasets) explicitly contains these mappings, the failure is most likely due to a failure to extract the \"offers\" relationship or the specific \"CIP code\" attribute from the source rows. Capturing this link is a prerequisite for answering the CQ.",
    :close-status :ok,
    :route :extract,
    :iteration 1,
    :after 1693,
    :graph-grew? false,
    :newly-unanswerable
    ["Which educational institutions offer programs within a specific CIP (Classification of Instructional Programs) code in Louisiana?"],
    :failing-cq
    "Which educational institutions offer programs within a specific CIP (Classification of Instructional Programs) code in Louisiana?",
    :before 1693}
   {:route-reasoning
    "The high \"unknown-rate\" (80%) combined with the specific failure of the CQ regarding earnings/wage outcomes Suggests that while the graph understands the basic structure of institutions and programs (evidenced by the single passing CQ), it lacks the specific numerical or quantitative property data for graduate outcomes. In knowledge graph construction, if the source material contains these statistics but they are not present in the graph, it typically indicates a failure to map and capture these specific literal values during the initial pass. The \"extract\" route is most appropriate here because we need to re-scan the source for the missing attributes (earnings/wages) associated with the already identified entities (programs/institutions) to populate the missing properties.",
    :close-status :ok,
    :route :extract,
    :iteration 2,
    :after 1693,
    :graph-grew? false,
    :newly-unanswerable
    ["What are the average earnings or wage outcomes for graduates of a specific program at a particular institution?"],
    :failing-cq
    "What are the average earnings or wage outcomes for graduates of a specific program at a particular institution?",
    :before 1693}]}}
```

## Retrievability — labeled hybrid-search hits

```clojure
{"psychology bachelor's degree"
 [{:uri "programtooccupationmapping/01.0509/25-1194",
   :label
   "Farrier Science. -> Career/Technical Education Teachers, Postsecondary",
   :score 0.01639344262295082}
  {:uri "programtooccupationmapping/01.0999/25-1041",
   :label
   "Animal Sciences, Other. -> Agricultural Sciences Teachers, Postsecondary",
   :score 0.016129032258064516}
  {:uri "programtooccupationmapping/01.0901/25-1041",
   :label
   "Animal Sciences, General. -> Agricultural Sciences Teachers, Postsecondary",
   :score 0.015873015873015872}
  {:uri "programtooccupationmapping/01.0504/25-1194",
   :label
   "Dog/Pet/Animal Grooming. -> Career/Technical Education Teachers, Postsecondary",
   :score 0.015625}
  {:uri "occupation/25-1194",
   :label "Career/Technical Education Teachers, Postsecondary",
   :score 0.015384615384615385}],
 "social work program" [],
 "registered nurse occupation"
 [{:uri "programtooccupationmapping/01.0907/45-1011",
   :label
   "Poultry Science. -> First-Line Supervisors of Farming, Fishing, and Forestry Workers",
   :score 0.01639344262295082}
  {:uri "programtooccupationmapping/01.0306/45-1011",
   :label
   "Dairy Husbandry and Production. -> First-Line Supervisors of Farming, Fishing, and Forestry Workers",
   :score 0.016129032258064516}
  {:uri "programtooccupationmapping/01.0606/45-1011",
   :label
   "Plant Nursery Operations and Management. -> First-Line Supervisors of Farming, Fishing, and Forestry Workers",
   :score 0.015873015873015872}
  {:uri "programtooccupationmapping/01.0606/37-3011",
   :label
   "Plant Nursery Operations and Management. -> Landscaping and Groundskeeping Workers",
   :score 0.015625}
  {:uri "programtooccupationmapping/01.0905/45-1011",
   :label
   "Dairy Science. -> First-Line Supervisors of Farming, Fishing, and Forestry Workers",
   :score 0.015384615384615385}],
 "computer science engineering"
 [{:uri "programtooccupationmapping/01.0106/15-1232",
   :label
   "Agricultural Business Technology/Technician. -> Computer User Support Specialists",
   :score 0.01639344262295082}
  {:uri "occupation/15-1232",
   :label "Computer User Support Specialists",
   :score 0.016129032258064516}],
 "clinical psychologist earnings" []}
```
