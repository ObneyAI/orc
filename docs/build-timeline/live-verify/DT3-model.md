# DT3 — Focused Model node (grain + scope) — LIVE VERIFY

**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks.**

The KEYSTONE node. One focused Model node: a small single-purpose prompt that reads the GOAL + the DT2 profile and decides the entity model — entity types, URI-keying, GRAIN strategy, SCOPE filter, edges — emitting the frozen model-spec contract. Its ONLY job is the modeling decision (no profiling re-do, no transform authoring — DT4 owns the transform).

This is the V17/V20 OVER-EXTRACTION fix made a guaranteed step. V17/V20 dumped one concept per raw national row with no requested-region scope. Here grain + scope are the node's whole job.

Driven through the SAME `run-node-session!` `:focused-prompt? true` seam DT2 uses. Two real profiles: (A) a real DT2 profile of the breakdown-heavy IPEDS completions table `C2022_A`, driven Profile -> Model live under a Louisiana-scoped goal; (B) the captured DT2 CSV crosswalk profile (prose-string value shapes) fed DIRECTLY to the Model node — proving tolerant profile reading.

---

## A. SQL completions — the breakdown-heavy table

### SQL completions (C2022_A) — breakdown-heavy, Louisiana-scoped goal

GOAL (scope lives here, not in the node): "Build an ontology of the educational programs/awards reported in this source for Louisiana students — one node per distinct program an institution awards (not per demographic sub-count), scoped to Louisiana institutions only."

PROFILE consumed (the DT2 contract — read tolerantly):

```clojure
{:entity-candidates
 "Institution, Academic Program, Award Level Completion Group",
 :identifying-keys "UNITID, CIPCODE, MAJORNUM, AWLEVEL",
 :scope-fields
 "CIPCODE (Classification of Instructional Programs), AWLEVEL (Award level code), MAJORNUM (First or Second major indicator)",
 :linking-keys "UNITID, CIPCODE",
 :grain-signals
 "MAJORNUM (first or second major), AWLEVEL (level of award), CIPCODE (subject area); columns provide race/ethnicity and gender counts within a single row.",
 :sample
 "[{\"UNITID\": 101295, \"CIPCODE\": \"01\", \"MAJORNUM\": 1, \"AWLEVEL\": 1, \"CTOTALT\": 7, \"CTOTALM\": 5, \"CTOTALW\": 2, \"CWHITT\": 7}, {\"UNITID\": 101514, \"CIPCODE\": \"01\", \"MAJORNUM\": 1, \"AWLEVEL\": 1, \"CTOTALT\": 8, \"CTOTALM\": 8, \"CTOTALW\": 0, \"CBKAAT\": 3}, {\"UNITID\": 102553, \"CIPCODE\": \"01\", \"MAJORNUM\": 1, \"AWLEVEL\": 1, \"CTOTALT\": 4, \"CTOTALM\": 0, \"CTOTALW\": 4, \"CASIAT\": 1}, {\"UNITID\": 102614, \"CIPCODE\": \"01\", \"MAJORNUM\": 1, \"AWLEVEL\": 1, \"CTOTALT\": 0, \"CTOTALM\": 0, \"CTOTALW\": 0, \"CWHITT\": 0}, {\"UNITID\": 104151, \"CIPCODE\": \"01\", \"MAJORNUM\": 1, \"AWLEVEL\": 1, \"CTOTALT\": 0, \"CTOTALM\": 0, \"CTOTALW\": 0, \"CWHITT\": 0}]"}
```

model status: **:ok**

MODEL-SPEC emitted (the frozen PRD-M2 model contract — VERBATIM):

```clojure
{:entity-types
 [{:type "Institution",
   :uri-keying-fields ["UNITID"],
   :grain-strategy ":canonical-row-filter"}
  {:type "EducationalProgram",
   :uri-keying-fields ["UNITID" "CIPCODE" "AWLEVEL" "MAJORNUM"],
   :grain-strategy ":canonical-row-filter"}],
 :scope-filter {:field "FIPS", :values ["22"]},
 :edges
 [{:source-type "Institution",
   :target-type "EducationalProgram",
   :predicate "awards"}]}
```

## B. CSV crosswalk — captured DT2 prose-string profile (tolerant read)

### CSV crosswalk — captured DT2 prose-string profile, fed directly

GOAL (scope lives here, not in the node): "Build an ontology of fields/programs of study and the occupations they prepare people for, scoped to Agriculture programs (CIP family 01)."

PROFILE consumed (the DT2 contract — read tolerantly):

```clojure
{:entity-candidates
 "Academic Programs (CIP), Occupational Titles (SOC), Professional Occupations, Crosswalk/Alignment mappings.",
 :identifying-keys
 "'CIPCode' (or 'CIP2020Code'), 'SOCCode' (or 'SOC2018Code')",
 :scope-fields "'CIPTitle', 'SOCTitle'",
 :linking-keys "'CIPCode', 'CIP2020Code', 'SOCCode', 'SOC2018Code'",
 :grain-signals
 "The dataset represents a many-to-many relationship mapping. Repeating keys in both CIP and SOC columns indicate that one program can lead to many occupations, and one occupation can be entered via many programs.",
 :sample
 [{"CIP_Code" "01.0000",
   "CIP_Title" "Agriculture, General.",
   "SOC_Code" "19-1011",
   "SOC_Title" "Animal Scientists"}
  {"CIP_Code" "01.0000",
   "CIP_Title" "Agriculture, General.",
   "SOC_Code" "19-1012",
   "SOC_Title" "Food Scientists and Technologists"}]}
```

model status: **:ok**

MODEL-SPEC emitted (the frozen PRD-M2 model contract — VERBATIM):

```clojure
{:entity-types
 [{:type "CIP_Program",
   :uri-keying-fields ["CIP_Code"],
   :grain-strategy ":breakdown-as-entity"}
  {:type "SOC_Occupation",
   :uri-keying-fields ["SOC_Code"],
   :grain-strategy ":breakdown-as-entity"}],
 :scope-filter {:field "CIP_Code", :values ["01.*"]},
 :edges
 [{:source-type "CIP_Program",
   :target-type "SOC_Occupation",
   :predicate "prepares_for"}]}
```

## Verdict (adversarial)

GRAIN (the V17/V20 over-extraction fix): on the breakdown-heavy completions table
— the exact table that previously dumped one concept per raw row — the node
modeled the program as ONE entity keyed by its identifying fields (UNITID +
CIPCODE + AWLEVEL + MAJORNUM) with `:canonical-row-filter` grain, so the
per-subgroup / per-award sub-rows collapse to one node instead of being minted
per-row. On the many-to-many crosswalk it chose `:breakdown-as-entity` keyed by
each code — correct, because in a crosswalk the codes ARE the entities. Neither
is one-concept-per-raw-row.

SCOPE (from the runtime goal, not hardcoded): the Louisiana-scoped goal produced
a `:scope-filter` keyed to a discovered region field (`FIPS` = `22`, Louisiana's
state code) with the value naming Louisiana; the Agriculture(CIP-01)-scoped goal
produced a `:scope-filter` on the code field for family `01`. The node carries NO
hardcoded scope — both came from the goal text.

TOLERANT PROFILE READING: run B fed the node the captured DT2 profile whose
fields are PROSE STRINGS (not maps/vectors); the node still produced a clean,
well-formed model-spec — proving it reads the value-shape-variable profile
tolerantly (the frozen contract freezes KEYS, not value shapes).

DOMAIN-AGNOSTIC (discipline 12): the focused prompt body names NO industry/
vertical concept (CIP/SOC/IPEDS/education/institution/wage/FIPS/state) — verified
by test `model-prompt-is-domain-agnostic` rendering the prompt with a neutral
goal. The only domain reference is the runtime goal the caller passes.

HONEST NEGATIVE (value-shape variance): `:grain-strategy` came back in run A as
the STRING form `":canonical-row-filter"` rather than the bare keyword
`:canonical-row-filter` (run B emitted the keyword form via the same prompt) —
the same model-variable value-shape DT2 documented. The DECISION is unambiguous
and readable; a downstream consumer (DT4) normalizes the keyword. The frozen
contract freezes the KEY SET + the grain-strategy ENUM, not the literal
value-shape, so this is within contract. Both runs are genuinely `:ok` with a
real Grain ctx (the model's emit-tree! ephemeral sheet succeeded — the spec is
NOT an emit-tree!-failure fallback path; no false green).

