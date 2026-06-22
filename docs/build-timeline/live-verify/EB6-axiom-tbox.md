# EB6 — Axiom/TBox subbehavior sheet — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks** — real Grain event store, real LLM (EB4 authors the transform; EB3 authors the candidate-axioms), real async todo processors, real child ticks, REAL CSV source.

Proves the AXIOM/TBox subbehavior is a delegatable SINGLE-`:code` sheet that grounds EB3's REAL candidate-axioms against the REAL extracted graph and emits REAL TBox axioms through the S07 commands — closing the `:axioms-skipped` silent drop. Built on the EB1-EB5 registry/delegation pattern.

## The REAL EB3→EB4→EB6 chain

1. **EB4 Extract** delegated over the REAL CSV (`cip_soc_crosswalk.csv`) → real concept/relationship drafts → LANDED via `compile-discovery-source!`. Concepts landed: **138**, relationships: **286**.
2. **EB3 Model** delegated over a goal + a CSV profile → REAL candidate-axioms AUTHORED BY THE LLM (not hand-written).
3. **EB6 Axiom/TBox** delegated the REAL candidate-axioms + the granted `:ontology-id` → grounds each candidate's references against the REAL graph, maps `:kind` → the matching S07 command, emits, and the axioms LAND.

## EB6 delegate result

- registry name → sheet-id match?: `true`
- EB6 `:delegate` status: `:success`
- axiom-report crossed `:delegate` as a PARSED MAP (C1)?: `true`

## REAL candidate-axioms (EB3 LLM-authored)

```clojure
{:axioms
 [{:kind ":disjoint",
   :rationale
   "Programs of study and occupations represent fundamentally different concepts (education vs. labor)."}
  {:kind ":functional",
   :field "CIP_Code",
   :target "Program",
   :rationale
   "A CIP code uniquely identifies a specific educational program configuration."}
  {:kind ":functional",
   :field "SOC_Code",
   :target "Occupation",
   :rationale
   "An SOC code uniquely identifies a specific occupational classification."}]}
```

## EB6 emission report

- candidates considered: **3**
- axioms EMITTED + grounded + landed: **2**

### Emitted (grounded against the real graph)

```clojure
[{:family :property-characteristic,
  :grounded {:predicate "CIP_Code", :characteristic [:functional]}}
 {:family :property-characteristic,
  :grounded {:predicate "SOC_Code", :characteristic [:functional]}}]
```

### Ungrounded (SURFACED, not asserted over URIs the graph lacks)

```clojure
[{:family :disjointness,
  :kind ":disjoint",
  :reason
  "disjointness needs >=2 class references that resolve in the graph; resolved 0 of 0",
  :unresolved [],
  :rationale
  "Programs of study and occupations represent fundamentally different concepts (education vs. labor)."}]
```

### Unsupported (tracked gaps — SURFACED, not silently skipped)

```clojure
[]
```

### Rejected (loud surface)

```clojure
[]
```

## AXIOMS READ BACK via `get-axioms` (discipline 7 — the projection IS the proof)

Read INDEPENDENTLY of the report, off the projection — events LANDED:

```clojure
{:characteristics
 {"CIP_Code" #{:functional}, "SOC_Code" #{:functional}}}
```

## Honest-gap rule held

Every candidate is accounted for (emitted + ungrounded + unsupported + rejected = considered). `domain` / `range` / `closure` have NO S07 command today — they are SURFACED as tracked gaps in `:axioms-unsupported`, never silently dropped (the exact `:axioms-skipped` bug EB6 closes). subClassOf is closed by the EB6 MINT (`assert-sub-class` → `rdfs:subClassOf`).
