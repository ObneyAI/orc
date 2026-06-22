# EB8 — Validate+CQ subbehavior sheet — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **No mocks** — real Grain event store, real OpenRouter LLM (CQ derivation + the S15 judge, model `google/gemini-3-flash-preview`), real ColBERT/embedding retrieval (the S15 three-layer runner), real child tick.

Proves the VALIDATE+CQ subbehavior is a delegatable `:llm` DERIVE → `:code` PERSIST → `:code` GATE sheet that, on a REAL profile + goal, DERIVES grounded goal-anchored competency questions, PERSISTS them as the S14 ORSD spec the S15 gate reads, and GATES the built graph with the S15 SEMANTIC retrieve-then-judge runner → per-CQ `:pass`/`:fail`/`:unknown` + graph-health. The derived CQs are SURFACED for HITL review; consumer-supplied CQs OVERRIDE the derived set. Built on the EB1-EB7 registry/delegation pattern. REUSE not fork (`cq-node-prompt` derivation body, `record-competency-questions!` persist, `evaluate-cqs!` gate).

## The fn-value boundary (honest split, not a mock)

The S15 judge is a Clojure FN VALUE (it closes over dscloj/OpenRouter), so it is NOT routed across the `:delegate` blackboard (which is event-sourced). Two real captures: **[A]** the DELEGATED sheet path (DERIVE + PERSIST cross `:delegate` end-to-end; the gate runs there with no LLM judge, surfacing Layer-1 + honest no-judge verdicts), and **[B]** the FULL GATE — `run-gate!` called directly with the REAL LLM judge on the persisted spec, capturing the full retrieve-then-judge verdicts + graph-health. Both are real; the split is the fn-value boundary, not a mock.

## Setup (inputs)

GOAL: `Build an ontology connecting fields/programs of study to the occupations they prepare people for.`

REAL profile(s) (the EB2/DT2 profile contract — the CQs are grounded in what these show the sources contain):

```clojure
[{:entity-candidates
  "Academic Programs (CIP), Occupational Titles (SOC), Crosswalk/Alignment mappings.",
  :identifying-keys "'CIPCode', 'SOCCode'",
  :scope-fields "'CIPTitle', 'SOCTitle'",
  :linking-keys "'CIPCode', 'SOCCode'",
  :grain-signals
  "A many-to-many mapping; one program leads to many occupations.",
  :sample
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1011",
    "SOC_Title" "Animal Scientists"}]}
 {:entity-candidates ["Higher Education Institutions" "States"],
  :identifying-keys
  {"Higher Education Institutions" ["UNITID" "OPEID"]},
  :scope-fields ["STABBR" "SECTOR"],
  :linking-keys ["UNITID" "STABBR"],
  :grain-signals ["UNITID repeated across years"],
  :sample
  [{:UNITID 100654, :INSTNM "Alabama A & M University", :STABBR "AL"}]}]
```

A real built graph the CQs are judged over (5 concepts):

```clojure
[{:uri "concept:program/agriculture",
  :label "Agriculture, General",
  :description
  "An academic program of study in general agriculture (CIP 01.0000)."}
 {:uri "concept:program/nursing",
  :label "Registered Nursing",
  :description
  "An academic program preparing students for nursing careers (CIP 51.3801)."}
 {:uri "concept:occupation/animal-scientist",
  :label "Animal Scientists",
  :description
  "An occupation studying animals and livestock (SOC 19-1011)."}
 {:uri "concept:occupation/nurse",
  :label "Registered Nurses",
  :description
  "An occupation providing patient care in hospitals (SOC 29-1141)."}
 {:uri "concept:institution/alabama-am",
  :label "Alabama A & M University",
  :description
  "A higher education institution in Alabama (UNITID 100654)."}]
```

## Registry + delegation

- subbehavior: `ontology-validate-cq/validate-cq@v1`
- sub sheet-id: `59094cf5-b189-55f0-a4e3-9645bba30e2f`
- registry name→id round-trip: **true**
- central tree status: **:success** (8394ms)
- parent tick-id: `984f07f7-92cd-4bc3-b08a-e1a435df9148`
- ontology-id: `d8703a38-a8d5-4471-9e41-009b0bcba65b`

## [A] DERIVED CQs — SURFACED FOR HITL REVIEW (verbatim)

The `:llm` DERIVE node derived these competency questions from the goal ⨯ the profile(s); they crossed `:delegate` as a parsed VECTOR (**true**) and are surfaced for human review/override:

1. Which occupational titles (SOC) are associated with a specific academic program code (CIP)?
2. What academic programs (CIP) provide the necessary preparation for a given occupation (SOC)?
3. Which higher education institutions offer programs that map to a specific occupation?
4. In a specific state, which occupations are supported by the available institutional programs?
5. For a specific academic institution, what is the full list of occupations its graduates are prepared for?

## [A] PERSISTED ORSD spec (read back via `get-ontology-spec`, #7)

The derived CQs ARE the ORSD spec's `:competency-questions` (the SAME spec build!'s S15 gate reads) — read back from the projection, NOT a return value:

```clojure
{:competency-questions
 ["Which occupational titles (SOC) are associated with a specific academic program code (CIP)?"
  "What academic programs (CIP) provide the necessary preparation for a given occupation (SOC)?"
  "Which higher education institutions offer programs that map to a specific occupation?"
  "In a specific state, which occupations are supported by the available institutional programs?"
  "For a specific academic institution, what is the full list of occupations its graduates are prepared for?"],
 :purpose
 "Build an ontology connecting fields/programs of study to the occupations they prepare people for."}
```

## [B] S15 GATE — per-CQ verdicts (real retrieve-then-judge)

The S15 runner judged the persisted CQs with the REAL LLM judge over the REAL graph (three-layer retrieve-then-judge). Per-CQ verdicts, read back via `get-cq-evaluation-latest` (#7):

```clojure
[{:cq-index 0,
  :event-id #uuid "019ef19a-20ab-701c-b6a3-893bea410f40",
  :cq-text
  "Which occupational titles (SOC) are associated with a specific academic program code (CIP)?",
  :reasoning
  "The graph contains relationships ('prepares-for') that map specific academic programs (CIP concepts like Agriculture and Nursing) to specific occupational titles (SOC concepts like Animal Scientists and Registered Nurses), thereby answering the question of which occupations are associated with specific programs.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-22T18:11:10.507112-05:00",
  :evidence-uris
  ["concept:program/agriculture prepares-for concept:occupation/animal-scientist"
   "concept:program/nursing prepares-for concept:occupation/nurse"]}
 {:cq-index 1,
  :event-id #uuid "019ef19a-26b9-70f4-8bed-b854f019c2b3",
  :cq-text
  "What academic programs (CIP) provide the necessary preparation for a given occupation (SOC)?",
  :reasoning
  "The graph explicitly contains relationships of the type 'prepares-for' mapping academic programs (CIP concepts) to occupations (SOC concepts), providing two specific examples that answer the query.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-22T18:11:12.057248-05:00",
  :evidence-uris
  ["concept:program/agriculture prepares-for concept:occupation/animal-scientist"
   "concept:program/nursing prepares-for concept:occupation/nurse"]}
 {:cq-index 2,
  :event-id #uuid "019ef19a-2c37-707d-8102-160bf5a1bdca",
  :cq-text
  "Which higher education institutions offer programs that map to a specific occupation?",
  :reasoning
  "The evidence shows that 'Alabama A & M University' (an institution) offers the 'Agriculture' program, and that program 'prepares-for' the occupation 'Animal Scientists'. This confirms that there are institutions offering programs that map to specific occupations.",
  :layer :layer-2-semantic-exists,
  :verdict :pass,
  :judged-by? true,
  :evaluated-at "2026-06-22T18:11:13.463746-05:00",
  :evidence-uris
  ["concept:institution/alabama-am"
   "concept:program/agriculture"
   "concept:occupation/animal-scientist"]}
 {:cq-index 3,
  :event-id #uuid "019ef19a-3386-7094-8bf6-cc24d3ed2c29",
  :cq-text
  "In a specific state, which occupations are supported by the available institutional programs?",
  :reasoning
  "The graph identifies that Alabama A & M University offers an Agriculture program which prepares students for the occupation of Animal Scientist. However, the graph does not explicitly link the institution or the programs to a 'state' entity or attribute. Without a location fact for the institution, we cannot definitively answer based on a 'specific state'.",
  :gaps
  ["state-location attributes for institutions (e.g., institution/alabama-am located-in concept:state/alabama)"],
  :layer :layer-3-explicit-unknown,
  :verdict :unknown,
  :judged-by? true,
  :evaluated-at "2026-06-22T18:11:15.334519-05:00",
  :evidence-uris
  ["concept:program/agriculture prepares-for concept:occupation/animal-scientist"
   "concept:institution/alabama-am offers concept:program/agriculture"]}
 {:cq-index 4,
  :event-id #uuid "019ef19a-3ad4-704e-af27-9c34d9d95c65",
  :cq-text
  "For a specific academic institution, what is the full list of occupations its graduates are prepared for?",
  :reasoning
  "The graph provides a partial path showing Alabama A & M University offers an Agriculture program which prepares graduates for the occupation of Animal Scientists. However, the graph is open-world and lacks any completeness or closure signals. Therefore, it is impossible to determine if this constitutes the 'full list' of occupations for which graduates of that specific institution are prepared.",
  :gaps
  ["Full list completeness assertion (closure axiom) for programs offered by Alabama A & M University"
   "Full list completeness assertion (closure axiom) for occupations prepared for by those programs"],
  :layer :layer-3-explicit-unknown,
  :verdict :unknown,
  :judged-by? true,
  :evaluated-at "2026-06-22T18:11:17.204518-05:00",
  :evidence-uris
  ["concept:institution/alabama-am"
   "concept:program/agriculture"
   "concept:occupation/animal-scientist"]}]
```

Graph-health metric (the `:unknown-rate` is first-class — NOT folded into fail-rate; it surfaces 'what does the graph not know yet'):

```clojure
{:unknown-rate 0.4,
 :unknown-count 2,
 :fail-rate 0.0,
 :layer-counts
 {:layer-2-semantic-exists 3, :layer-3-explicit-unknown 2},
 :ontology-id #uuid "d8703a38-a8d5-4471-9e41-009b0bcba65b",
 :judge-share 1.0,
 :last-evaluation-ts "2026-06-22T18:11:17.204518-05:00",
 :total-cqs 5,
 :pass-rate 0.6,
 :fail-count 0,
 :pass-count 3}
```

## [C] consumer-CQ OVERRIDE

A run with consumer-supplied CQs persisted the SUPPLIED set (NOT the derived set) — the HITL override path. Override held: **true**

Supplied (and persisted) CQs:

- Which institutions offer a given program of study?
- What occupation does a given program prepare students for?

## Verdict

The Validate+CQ subbehavior is a delegatable `:llm` DERIVE → `:code` PERSIST → `:code` GATE sheet that, on a real profile + goal, derives GROUNDED, GOAL-ANCHORED CQs (surfaced for HITL review), persists them as the ORSD spec the S15 gate reads (read back from the projection, #7), and gates the built graph with the S15 SEMANTIC retrieve-then-judge runner (per-CQ pass/fail/unknown + graph-health; `:unknown` first-class, never dropped). Consumer-supplied CQs OVERRIDE the derived set. The `:reasoning` is written FIRST on the DERIVE node (#13); validation is SEMANTIC (not lints / phrase matching, #7/#12); domain-agnostic (the CQs come from goal ⨯ profile, #12). REUSE not fork.
