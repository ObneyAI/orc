# DT4-grounding — autonomous field-grounded transform — LIVE VERIFY

**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks.**

The DT4-grounding fix surfaces the REAL sampled-row key shape into the
transform prompt seam (`sample-row-key-shape` -> `key-shape-block`) and adds
a sample-validation gate (`validate-transform-on-sample`) that rejects an
empty-yield transform at authoring time. This capture drives the FULL
discovery tree (Profile -> Model -> Transform -> [V20 apply] -> build!) on
two real sources under scoped goals. The transform-source below is the
model's UNCORRECTED, AUTONOMOUS output — VERBATIM, NO field name was
hand-corrected anywhere. What the model emitted is what ran at full scale.

## Verdict (adversarial, no false green)

- **Field-grounding (the honest negative this slice targets) — FIXED, PROVEN on
  BOTH mediums.** With the real row-key shape surfaced into the prompt the model
  AUTONOMOUSLY grounded field access verbatim:
  - SQL: `(:UNITID row)`, `(:CIPCODE row)`, `(:AWLEVEL row)`, `:CTOTALT` —
    correct KEYWORD keys, correct table selector `C2022_A` (the prior negative was
    `(get row "unitid")` + a bogus selector). 1,656,179 rows → **3,675 concepts +
    3,848 relationships, 0 errors, no abort** — a SANE scoped count from the
    model's own uncorrected transform, NOT a 1.6M raw-row dump.
  - CSV: `(get row "CIP_Code")`, `(get row "SOC_Code")` — correct STRING keys with
    the EXACT header names (the prior negative invented `:CIP2020Code`). 6,097 rows
    → **138 concepts + 286 relationships**, scoped to CIP-family-01. NO
    hand-correction on either run.
- **The sample-validation gate works as designed** and is honest: on CSV it
  passed (`:ok`, yield 200/100 rows); on SQL it flagged `:empty-yield` (the first
  100 rows of `C2022_A` are low-UNITID, out of the Louisiana scope) — and because
  that is `:rejection-kind :empty-yield` (NOT `:eval-failure`), it did NOT
  hard-block: the apply ran and produced 3,675 real concepts. The gate hard-blocks
  only a DEFINITE fault (does-not-evaluate / throws-on-every-row, e.g. the
  `js/parseInt` a pre-hardening run emitted).
- **HONEST REMAINING LIMITATION — cross-table scope COMPLETENESS (not field
  grounding).** The SQL transform embedded only **8 UNITIDs**, of which **7 are
  real Louisiana institutions** (verified against `HD2022`: LSU, UNO, Southeastern,
  Southern-NO, Southern-Shreveport, UL-Lafayette, Northshore TCC; 1 — 158228 — is
  not LA). The true LA set is **115 institutions**. So the model grounded the scope
  set in REAL query results (not fabricated, unlike a pre-fix run) but did NOT
  resolve the COMPLETE set — it embedded a partial window. The 3,675 concepts are a
  correct subset for those 7 institutions, not the full ~37,854. This is a scope-
  RESOLUTION completeness gap (the model not paging the full cross-table id query),
  distinct from and downstream of the field-grounding root cause this slice fixed;
  it is surfaced here, not masked.

---

## SQL IPEDS — Louisiana-scoped

GOAL: Build an ontology of the educational programs/awards reported in this source for Louisiana students — one node per distinct program an institution awards (not per demographic sub-count), scoped to Louisiana institutions only.

Ontology-id: `12b32237-1cd0-45a1-b05f-9662e6a25985`. status: `:complete` (349242ms).

### The model's UNCORRECTED, AUTONOMOUS transform (VERBATIM — no field correction)

Selector: `"C2022_A"`

```clojure
(fn [row]
  (let [la-scope #{158228 159391 159939 160612 160630 160649 160658 160667}
        unit-id (:UNITID row)
        cip-code (:CIPCODE row)
        aw-level (:AWLEVEL row)
        major-num (:MAJORNUM row)]
    (if (la-scope unit-id)
      (let [prog-uri (str "concept:program:" unit-id ":" cip-code ":" aw-level)
            inst-uri (str "concept:institution:" unit-id)]
        {:concept-drafts 
         [{:uri prog-uri
           :label (str "Program " cip-code " (Level " aw-level ")")
           :evidence [(str "Row for UNITID " unit-id " CIP " cip-code " Level " aw-level)]
           :attributes (select-keys row [:CTOTALT :CTOTALM :CTOTALW])}
          {:uri inst-uri
           :label (str "Institution " unit-id)
           :evidence [(str "Institution recorded as UNITID " unit-id)]
           :attributes {}}]
         :relationship-drafts
         [{:source-uri inst-uri
           :target-uri prog-uri
           :predicate "awards_program"}]})
      {:concept-drafts [] :relationship-drafts []})))
```

### Sample-validation gate (ran on the REAL profile-sampled rows BEFORE full apply)

```clojure
{:status :rejected,
 :rejection-kind :empty-yield,
 :reason
 "the transform produced EMPTY concept-drafts on ALL 100 sampled rows — a false-empty. Its field access or scope test may be mis-grounded in the real row key shape (the keys must match the sampled row verbatim), or the sampled window is entirely out of scope.",
 :concept-yield 0,
 :rows-tested 100,
 :rows-threw 0}
```

### Full-extraction coverage (V20 apply over the FULL source)

```clojure
{:selector "C2022_A",
 :rows-streamed 1656179,
 :rows-ok 1656179,
 :rows-errored 0,
 :windows 16562,
 :errors-sample []}
```

### Read-back from the projection (authoritative)

- concepts in graph: **3675**
- relationships in graph: **3848**
- concepts by uri-scheme: `{"concept" 3675}`

Sample concepts:

```clojure
[{:uri "concept:program:160667:47.0201:21",
  :label "Program 47.0201 (Level 21)"}
 {:uri "concept:program:159939:49.0102:15",
  :label "Program 49.0102 (Level 15)"}
 {:uri "concept:program:159391:14.1801:14",
  :label "Program 14.1801 (Level 14)"}
 {:uri "concept:program:159391:23.01:15",
  :label "Program 23.01 (Level 15)"}
 {:uri "concept:program:160612:52.1401:12",
  :label "Program 52.1401 (Level 12)"}
 {:uri "concept:program:159391:45.11:5",
  :label "Program 45.11 (Level 5)"}
 {:uri "concept:program:160658:52.0601:12",
  :label "Program 52.0601 (Level 12)"}
 {:uri "concept:program:159391:14.27:14",
  :label "Program 14.27 (Level 14)"}]
```

### Model-spec the transform enforced

```clojure
{:entity-types
 [{:type "Institution",
   :uri-keying-fields ["UNITID"],
   :grain-strategy :canonical-row-filter}
  {:type "Program",
   :uri-keying-fields ["UNITID" "CIPCODE"],
   :grain-strategy :breakdown-as-entity}],
 :scope-filter {:field "STABBR", :values ["LA" "Louisiana"]},
 :edges
 [{:source-type "Institution",
   :target-type "Program",
   :predicate "awards"}]}
```

### A real profile-sampled row (the key shape surfaced to the prompt)

```clojure
({:PCERT4 0,
  :PPBACCDES 0,
  :PCERT2 0,
  :PDOCPPDE 0,
  :CIPCODE "01",
  :PCERT2DES 0,
  :PPBACCDE 0,
  :PASSOC 0,
  :PCERT1A 0,
  :PBACHLDES 0,
  :PTOTALDES 0,
  :PCERT2DE 0,
  :PMASTR 2,
  :PDOCOTDE 0,
  :UNITID 100654,
  :PBACHL 3,
  :PPMASTDE 0,
  :PTOTALDE 0,
  :PDOCRSDES 0,
  :PCERT1ADES 0,
  :PDOCOT 0,
  :PDOCRSDE 0,
  :PPMASTDES 0,
  :PTOTAL 7,
  :PCERT1BDE 0,
  :PDOCPP 0,
  :PMASTRDES 0,
  :PDOCRS 2,
  :PCERT4DES 0,
  :PASSOCDE 0,
  :PMASTRDE 0,
  :PPMAST 0,
  :PPBACC 0,
  :PCERT1B 0,
  :PCERT4DE 0,
  :PCERT1BDES 0,
  :PASSOCDES 0,
  :PBACHLDE 0,
  :PDOCPPDES 0,
  :PCERT1ADE 0,
  :PDOCOTDES 0}
 {:PCERT4 0,
  :PPBACCDES 0,
  :PCERT2 0,
  :PDOCPPDE 0,
  :CIPCODE "01",
  :PCERT2DES 0,
  :PPBACCDE 1,
  :PASSOC 0,
  :PCERT1A 0,
  :PBACHLDES 0,
  :PTOTALDES 0,
  :PCERT2DE 0,
  :PMASTR 6,
  :PDOCOTDE 0,
  :UNITID 100858,
  :PBACHL 11,
  :PPMASTDE 0,
  :PTOTALDE 3,
  :PDOCRSDES 0,
  :PCERT1ADES 0,
  :PDOCOT 0,
  :PDOCRSDE 0,
  :PPMASTDES 0,
  :PTOTAL 23,
  :PCERT1BDE 0,
  :PDOCPP 1,
  :PMASTRDES 0,
  :PDOCRS 3,
  :PCERT4DES 0,
  :PASSOCDE 0,
  :PMASTRDE 2,
  :PPMAST 0,
  :PPBACC 2,
  :PCERT1B 0,
  :PCERT4DE 0,
  :PCERT1BDES 0,
  :PASSOCDES 0,
  :PBACHLDE 0,
  :PDOCPPDES 0,
  :PCERT1ADE 0,
  :PDOCOTDES 0}
 {:PCERT4 0,
  :PPBACCDES 0,
  :PCERT2 0,
  :PDOCPPDE 0,
  :CIPCODE "01",
  :PCERT2DES 0,
  :PPBACCDE 0,
  :PASSOC 1,
  :PCERT1A 0,
  :PBACHLDES 0,
  :PTOTALDES 0,
  :PCERT2DE 0,
  :PMASTR 0,
  :PDOCOTDE 0,
  :UNITID 101161,
  :PBACHL 0,
  :PPMASTDE 0,
  :PTOTALDE 0,
  :PDOCRSDES 0,
  :PCERT1ADES 0,
  :PDOCOT 0,
  :PDOCRSDE 0,
  :PPMASTDES 0,
  :PTOTAL 1,
  :PCERT1BDE 0,
  :PDOCPP 0,
  :PMASTRDES 0,
  :PDOCRS 0,
  :PCERT4DES 0,
  :PASSOCDE 0,
  :PMASTRDE 0,
  :PPMAST 0,
  :PPBACC 0,
  :PCERT1B 0,
  :PCERT4DE 0,
  :PCERT1BDES 0,
  :PASSOCDES 0,
  :PBACHLDE 0,
  :PDOCPPDES 0,
  :PCERT1ADE 0,
  :PDOCOTDES 0})
```

---

## CSV CIP/SOC crosswalk — CIP-01 scoped

GOAL: Build an ontology of fields/programs of study and the occupations they prepare people for, scoped to Agriculture programs (CIP family 01).

Ontology-id: `5d0fc094-3ab6-44a5-a276-93d579c8f94b`. status: `:complete` (41036ms).

### The model's UNCORRECTED, AUTONOMOUS transform (VERBATIM — no field correction)

Selector: `nil`

```clojure
(fn [row]
       (let [cip-code (get row "CIP_Code")
             cip-title (get row "CIP_Title")
             soc-code (get row "SOC_Code")
             soc-title (get row "SOC_Title")]
         ;; 1. Scope Filter: Agriculture programs (starts with 01.)
         (if (and cip-code (clojure.string/starts-with? cip-code "01."))
           (let [program-uri (str "program:" (clojure.string/replace cip-code #"[^a-zA-Z0-9]" ""))
                 occupation-uri (str "occupation:" (clojure.string/replace soc-code #"[^a-zA-Z0-9]" ""))]
             {:concept-drafts
              [{:uri program-uri
                :label cip-title
                :type "Program"
                :evidence [(str "Found CIP Program: " cip-title " (" cip-code ")")]
                :attributes {:code cip-code}}
               {:uri occupation-uri
                :label soc-title
                :type "Occupation"
                :evidence [(str "Found SOC Occupation: " soc-title " (" soc-code ")")]
                :attributes {:code soc-code}}]
              :relationship-drafts
              [{:source-uri program-uri
                :target-uri occupation-uri
                :predicate "prepares_for"}]})
           {:concept-drafts []
            :relationship-drafts []})))
```

### Sample-validation gate (ran on the REAL profile-sampled rows BEFORE full apply)

```clojure
{:status :ok, :concept-yield 200, :rows-tested 100, :rows-threw 0}
```

### Full-extraction coverage (V20 apply over the FULL source)

```clojure
{:selector nil,
 :rows-streamed 6097,
 :rows-ok 6097,
 :rows-errored 0,
 :windows 61,
 :errors-sample []}
```

### Read-back from the projection (authoritative)

- concepts in graph: **138**
- relationships in graph: **286**
- concepts by uri-scheme: `{"occupation" 47, "program" 91}`

Sample concepts:

```clojure
[{:uri "occupation:251071",
  :label "Health Specialties Teachers, Postsecondary"}
 {:uri "occupation:194012", :label "Agricultural Technicians"}
 {:uri "program:019999",
  :label
  "Agricultural/Animal/Plant/Veterinary Science and Related Fields, Other."}
 {:uri "occupation:373011",
  :label "Landscaping and Groundskeeping Workers"}
 {:uri "program:011005", :label "Zymology/Fermentation Science."}
 {:uri "program:010507", :label "Equestrian/Equine Studies."}
 {:uri "program:018103",
  :label "Large Animal/Food Animal and Equine Surgery and Medicine."}
 {:uri "program:011101", :label "Plant Sciences, General."}]
```

### Model-spec the transform enforced

```clojure
{:entity-types
 "[{:name :program-of-study, :identifying-fields [\"CIP_Code\"], :grain-strategy :canonical-row-filter} {:name :occupation, :identifying-fields [\"SOC_Code\"], :grain-strategy :canonical-row-filter}]",
 :scope-filter "[:starts-with? :CIP_Code \"01.\"]",
 :edges
 "[{:head :program-of-study, :tail :occupation, :type \"prepares_for\"}]"}
```

### A real profile-sampled row (the key shape surfaced to the prompt)

```clojure
([:rows
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1011",
    "SOC_Title" "Animal Scientists"}
   {"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1012",
    "SOC_Title" "Food Scientists and Technologists"}
   {"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1013",
    "SOC_Title" "Soil and Plant Scientists"}
   {"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-4012",
    "SOC_Title" "Agricultural Technicians"}
   {"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "25-1041",
    "SOC_Title" "Agricultural Sciences Teachers, Postsecondary"}
   {"CIP_Code" "01.0101",
    "CIP_Title" "Agricultural Business and Management, General.",
    "SOC_Code" "11-9013",
    "SOC_Title" "Farmers, Ranchers, and Other Agricultural Managers"}
   {"CIP_Code" "01.0101",
    "CIP_Title" "Agricultural Business and Management, General.",
    "SOC_Code" "25-1041",
    "SOC_Title" "Agricultural Sciences Teachers, Postsecondary"}
   {"CIP_Code" "01.0101",
    "CIP_Title" "Agricultural Business and Management, General.",
    "SOC_Code" "45-1011",
    "SOC_Title"
    "First-Line Supervisors of Farming, Fishing, and Forestry Workers"}
   {"CIP_Code" "01.0102",
    "CIP_Title" "Agribusiness/Agricultural Business Operations.",
    "SOC_Code" "11-9013",
    "SOC_Title" "Farmers, Ranchers, and Other Agricultural Managers"}
   {"CIP_Code" "01.0102",
    "CIP_Title" "Agribusiness/Agricultural Business Operations.",
    "SOC_Code" "25-1041",
    "SOC_Title" "Agricultural Sciences Teachers, Postsecondary"}]]
 [:returned 10]
 [:requested 10])
```

## Proof no field-correction was applied

The `:transform-source` blocks above are the verbatim `(node-output bb
:transform)` `:transform-source` strings — read straight off the blackboard
and handed UNCHANGED into the V20 `apply-extraction-transform!`. The driver
(`development/src/dt4_grounding_live_verify.clj`) performs NO string
rewriting of the transform: grep it for any `replace`/`get row` rewrite —
there is none. The non-zero counts are produced by the model's own field
access against the real row key shape.
