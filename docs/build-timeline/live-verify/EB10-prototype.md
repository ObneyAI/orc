# EB10 — Central evolver loop — PROTOTYPE (3 proofs)

**Branch:** `feature/ontology-architecture`. Proof (a) is REAL (real Grain + real OpenRouter `:delegate` to >=2 subbehaviors); proofs (b)/(c) exercise the route-and-close + honest-terminate LOOP LOGIC deterministically (stubbed CQ verdict + stubbed route/close seams) — the loop branching with no LLM/ColBERT.

## (a) :delegate to >=2 REAL subbehaviors end-to-end

```clojure
{:survey-status :success,
 :survey-profile
 {:entity-candidates
  ["Educational Program (Field of Study)"
   "Occupation (Work Role)"
   "Program-to-Occupation Linkage"],
  :identifying-keys
  {"Educational Program" ["CIP2020Code"],
   "Occupation" ["SOC2018Code"],
   "Linkage" ["CIP2020Code" "SOC2018Code"]},
  :scope-fields ["CIP2020Code" "SOC2018Code"],
  :linking-keys ["CIP2020Code" "SOC2018Code"],
  :grain-signals
  ["One program (CIP) maps to multiple occupations (SOC), creating multiple rows per program entity."],
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
     "program_title" "Computer Science",
     "occupation_code" "15-1252",
     "occupation_title" "Software Developers"}],
   :returned 3,
   :requested 10,
   :offset 0,
   :capped? false},
  :embed-worthy-fields ["CIP2020Title" "SOC2018Title"]},
 :mx-status :success,
 :model-spec-is-map? true,
 :concept-draft-count 6,
 :delegated-2-subbehaviors? true}
```

## (b) route-and-close — fail -> route :extract -> focal re-invoke -> re-gate PASS

```clojure
{:status :complete,
 :termination-reason :cq-gate-passed,
 :route-taken :extract,
 :close-calls 1,
 :gate-calls 2,
 :route-and-close-passed? true}
```

## (c) honest-terminate — unanswerable -> route :terminate -> surfaced, no spin

```clojure
{:status :failed-cq,
 :termination-reason :all-remaining-unanswerable,
 :unanswerable-cqs ["Which planet is this program on?"],
 :route-calls 1,
 :close-calls 0,
 :honest-terminate? true}
```

## Verdict

ALL PROOFS PASS?: **true**
