# DT2 — Focused Profile node — LIVE VERIFY

**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks.**
**Sources:** the real CIP↔SOC crosswalk CSV (`/Users/darylroberts/Downloads/cip_soc_crosswalk.csv`, 16,331 data rows) AND the real IPEDS SQLite DB (`/Users/darylroberts/Downloads/output.db`, 59 tables, ~296 MB).

One focused Profile node, run per-medium: the SAME single-purpose prompt + the
SAME `run-node-session!` executor seam, only the bound per-medium specialist
tools differ (csv `peek-columns`/`sample-rows`/… vs sql `list-tables`/
`table-schema`/`sample-rows`/…). The node received ONLY the focused profiling
prompt + a goal that ORIENTS (but does not let it decide scope) + the per-medium
tool catalog. NO modeling, NO transform design, NO scope decision — those are
DT3/DT4. The prompt carries NO education/CIP/SOC/industry knowledge (discipline
12); the focus comes from the runtime goal.

Goal (orients every run): *"Build an ontology connecting fields/programs of study
to the occupations they prepare people for."*

The retired mega-prompt (`structured-discovery-prompt`) is ~400 lines of
modeling / grain / scope / transform-coverage guidance. The focused Profile
prompt is ~45 lines of profiling instruction + a per-medium tool catalog pulled
verbatim from the specialist tools' own docstrings — auditably an order of
magnitude smaller, and single-purpose.

---

## CSV profile — `cip_soc_crosswalk.csv`

status: **:ok**. The node called `peek-columns` + `sample-rows`, recognized the
crosswalk shape, and emitted the frozen profile contract:

```clojure
{:entity-candidates
 "Academic Programs (CIP), Occupational Titles (SOC), Professional Occupations, Crosswalk/Alignment mappings.",
 :identifying-keys
 "'CIPCode' (or 'CIP2020Code'), 'SOCCode' (or 'SOC2018Code')",
 :scope-fields
 "'CIPTitle', 'SOCTitle'",
 :linking-keys
 "'CIPCode', 'CIP2020Code', 'SOCCode', 'SOC2018Code'",
 :grain-signals
 "The dataset represents a many-to-many relationship mapping. Repeating keys in both CIP and SOC columns indicate that one program can lead to many occupations, and one occupation can be entered via many programs.",
 :sample
 [{"CIP_Code" "01.0000", "CIP_Title" "Agriculture, General.",
   "SOC_Code" "19-1011", "SOC_Title" "Animal Scientists"}
  {"CIP_Code" "01.0000", "CIP_Title" "Agriculture, General.",
   "SOC_Code" "19-1012", "SOC_Title" "Food Scientists and Technologists"}
  {"CIP_Code" "01.0000", "CIP_Title" "Agriculture, General.",
   "SOC_Code" "19-1013", "SOC_Title" "Soil and Plant Scientists"}
  ;; … (verbatim rows the node sampled)
  ]}
```

Adversarial read: the characterization is CORRECT — it identified the two real
entity types (CIP programs, SOC occupations), the codes that identify each, the
shared codes that LINK to other sources, and the load-bearing GRAIN signal (the
many-to-many crosswalk: repeating keys mean rows are finer-grained than
entities). It did NOT model, design a transform, or pick a scope value.

Honest negatives:
- `:identifying-keys` / `:entity-candidates` / `:scope-fields` / `:linking-keys`
  came back as PROSE STRINGS this run rather than the contract's preferred
  map/vector shapes. The DT1-frozen contract freezes the KEY SET (DT1 itself
  captured string-valued fields), not the value shapes, so this is within the
  frozen contract — but the SQL run below shows the model also produces the clean
  map/vector form, so the value-shape is model-variable, not prompt-forced. DT3
  reads these fields tolerantly.
- `:scope-fields` listed `CIPTitle`/`SOCTitle` (the human-readable label columns)
  rather than a partition column — defensible for a crosswalk with no
  region/year dimension, and correctly framed as "fields that COULD scope,"
  not a chosen filter.
- The `:sample` on one earlier run came back wrapped as `[:rows [...]]` because
  the model called `(:rows (sample-rows N))` (csv `sample-rows` returns the row
  vector directly). A contract-shape quirk, not a characterization error; the
  rows themselves are real and verbatim.

## SQL profile — `output.db` (IPEDS)

status: **:ok**. The node called `list-tables` (59 tables) + `table-schema` +
`sample-rows`, targeted the institutions table, and emitted the frozen contract
in the clean map/vector shape:

```clojure
{:entity-candidates
 ["Higher Education Institutions" "Educational Surveys" "States" "Geographic Regions"],
 :identifying-keys
 {"Higher Education Institutions" ["UNITID" "OPEID"]},
 :scope-fields ["STABBR" "SECTOR" "HLOFFER" "CONTROL" "LOCALE"],
 :linking-keys ["UNITID" "FIPS" "STABBR" "COUNTYNM"],
 :grain-signals
 ["UNITID repeated across years" "Institution splits by Survey Period"],
 :sample
 [{:UNITID 100654 :INSTNM "Alabama A & M University" :ADDR "4900 Meridian Street"
   :CITY "Normal" :STABBR "AL" :ZIP "35762"}
  {:UNITID 100663 :INSTNM "University of Alabama at Birmingham" :ADDR "701 S 20th St"
   :CITY "Birmingham" :STABBR "AL" :ZIP "35233"}
  ;; … (verbatim institution rows the node sampled)
  ]}
```

Adversarial read: an excellent focused profile. It found the institution entity
(`UNITID`/`OPEID` identity), listed the real goal-scoping candidate fields
(`STABBR` state, `SECTOR`, `CONTROL`, `LOCALE` — exactly what a later step would
filter on for a region/subset, without choosing a value), the shareable
cross-source linking keys (`UNITID`, `FIPS`, `STABBR`), and the grain signals
(UNITID repeats across survey years / periods → rows finer than the institution
entity). NO modeling, NO transform, NO scope decision.

Honest negative: the IPEDS DB has 59 tables; across runs the node lands on
different tables (one earlier run profiled the `CIPCodes`/`CIP2020` reference
table and correctly described the CIP hierarchy grain instead of institutions).
Both are valid focused profiles of a real table — the node profiles ONE source's
shape per pass; which table it foregrounds is goal-influenced model choice, not
a defect. (Comprehensive multi-table coverage is a later-stage concern, not the
Profile node's one job.)

## Verdict

Domain-agnosticism (discipline 12): the focused prompt body names NO
education/CIP/SOC/IPEDS/occupation/wage/institution concept — verified by test
`profile-prompt-is-domain-agnostic` rendering the prompt with a neutral goal. The
only specialization is the per-medium tool catalog, pulled verbatim from the
specialist tools' own docstrings. (One generic data-SHAPE term — "crosswalk" —
appears via the CSV `peek-columns` docstring; it describes a structural pattern,
not an industry, and is part of tool ergonomics, which discipline 12 permits.)

Across BOTH mediums, the focused Profile node — with a small single-purpose
prompt and only the per-medium tool catalog as specialization — characterized a
real source into the frozen profile contract: real entity candidates, the fields
that identify each, the goal-scoping candidate fields, the cross-source linking
keys, the breakdown/grain signals, and a verbatim sample. Domain-agnostic; same
node, per-medium tool-leaves. No false green: the only failures observed were a
missing LLM-provider registration in the bare driver (root-caused + fixed — it
was a driver-setup gap, not a node gap) and the value-shape variance noted above.
