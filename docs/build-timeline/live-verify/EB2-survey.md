# EB2 — Survey subbehavior sheet — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks** — real Grain event store, real LLM, real async todo processors, real child tick.

Proves the SURVEY subbehavior is a delegatable TERMINAL `:repl-researcher` sheet that profiles ANY source by shape into a STRUCTURED, parsed profile contract (incl. the embed-worthy-field signal) arriving correctly across `:delegate`. Built on the EB1 registry/delegation pattern; re-houses the DT2 Profile logic.

## Terminal mode (no recursion, no emit-tree, no Phase-2 sub-tick)

The Survey node sets `:rlm {:recursive? false :granted-source {…}}`. Terminal mode means the model's first `(final! …)` returns directly — there is NO `emit-tree!` and NO F3 Phase-2 sub-tick. The child tick listed below is the `:delegate` child (the Survey sheet itself), not a Phase-2 tree sub-tick.

## CSV medium (real csv source)

- subbehavior: `ontology-survey/survey@v1-csv-6ac6ebb5`
- sub sheet-id: `4c5a4772-4c99-55ce-95e7-fea7c8fccabd`
- registry name→id round-trip: **true**
- central tree status: **:success** (33034ms)
- parent tick-id: `e255a9e8-8a6c-4041-b5f9-243f22a90be4`
- child tick-id(s) (the delegated Survey run): `[#uuid "4ba8fc22-36ea-4546-89de-b4ccbb7ac3bc"]`

### Terminal mode (no Phase-2 sub-tick)

The Survey child tick carries NO `:tree-results` and NO `:generated-tree` — it finalized in Phase 1 without emitting a tree (terminal `:repl-researcher`):

```clojure
[{:child-tick-id #uuid "4ba8fc22-36ea-4546-89de-b4ccbb7ac3bc",
  :child-bb-found? true,
  :child-bb-keys
  [:goal
   :source-descriptor
   :profile
   :iteration-reasonings
   :iterations],
  :tree-results-present? false,
  :generated-tree-present? false,
  :terminal? true}]
```

### C1 — profile contract arrives PARSED (not a JSON string)

Read back from the PARENT tick blackboard via the projection (`rm/get-tick-blackboard`), NOT from the execute return value:

- profile is a PARSED MAP: **true**
- profile is a JSON STRING: **false** (must be false — that is the C1 failure mode)
- frozen contract keys present: `[:entity-candidates :identifying-keys :scope-fields :linking-keys :grain-signals :sample :embed-worthy-fields]`
- embed-worthy-field signal (EB7/P2 input): `["CIP2020Title" "SOC2018Title"]`

Profile contract (verbatim from the parent-tick projection):

```clojure
{:entity-candidates
 ["CIP Program (Classification of Instructional Programs)"
  "SOC Occupation (Standard Occupational Classification)"],
 :identifying-keys
 {"CIP Program (Classification of Instructional Programs)"
  ["CIP2020Code"],
  "SOC Occupation (Standard Occupational Classification)"
  ["SOC2018Code"]},
 :scope-fields [],
 :linking-keys ["CIP2020Code" "SOC2018Code"],
 :grain-signals
 ["The grain is the mapping relationship; one CIP code can occur in multiple rows if it prepares people for multiple SOC occupations."],
 :sample
 {:rows
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
    "SOC_Title" "Agricultural Sciences Teachers, Postsecondary"}],
  :returned 5,
  :requested 5,
  :offset 0,
  :capped? true},
 :embed-worthy-fields ["CIP2020Title" "SOC2018Title"]}
```

## SQL medium (real sql source)

- subbehavior: `ontology-survey/survey@v1-sql-226b54b9`
- sub sheet-id: `d0f06896-7227-5875-b082-c26e4b4dd5df`
- registry name→id round-trip: **true**
- central tree status: **:success** (10925ms)
- parent tick-id: `9068ce66-0e4b-476f-aa87-1cf2a3dbe7e9`
- child tick-id(s) (the delegated Survey run): `[#uuid "b3029818-e6d1-40bc-9524-e1df429a6994"]`

### Terminal mode (no Phase-2 sub-tick)

The Survey child tick carries NO `:tree-results` and NO `:generated-tree` — it finalized in Phase 1 without emitting a tree (terminal `:repl-researcher`):

```clojure
[{:child-tick-id #uuid "b3029818-e6d1-40bc-9524-e1df429a6994",
  :child-bb-found? true,
  :child-bb-keys
  [:goal
   :source-descriptor
   :profile
   :iteration-reasonings
   :iterations],
  :tree-results-present? false,
  :generated-tree-present? false,
  :terminal? true}]
```

### C1 — profile contract arrives PARSED (not a JSON string)

Read back from the PARENT tick blackboard via the projection (`rm/get-tick-blackboard`), NOT from the execute return value:

- profile is a PARSED MAP: **true**
- profile is a JSON STRING: **false** (must be false — that is the C1 failure mode)
- frozen contract keys present: `[:entity-candidates :identifying-keys :scope-fields :linking-keys :grain-signals :sample :embed-worthy-fields]`
- embed-worthy-field signal (EB7/P2 input): `["INSTNM" "ADDR" "CITY" "CIPTEXT"]`

Profile contract (verbatim from the parent-tick projection):

```clojure
{:entity-candidates
 ["Postsecondary Institution"
  "Academic Program (CIP Code)"
  "Degree Completion/Award Record"],
 :identifying-keys
 {"Postsecondary Institution" ["UNITID"],
  "Academic Program (CIP Code)" ["CIPCODE"],
  "Degree Completion/Award Record" ["UNITID" "CIPCODE" "AWLEVEL"]},
 :scope-fields
 ["STABBR"
  "SECTOR"
  "ICLEVEL"
  "CONTROL"
  "HLOFFER"
  "AWLEVEL"
  "MAJORNUM"],
 :linking-keys ["UNITID" "CIPCODE" "OPEID" "EIN"],
 :grain-signals
 ["Records in C2022_A are finer-grained than programs, as they are broken down by Award Level (AWLEVEL) and often student demographics (though C2022_A specifically focuses on totals by award level)."],
 :sample
 [{:NPRICURL
   "www.aamu.edu/admissions-aid/tuition-fees/net-price-calculator.html",
   :ADMINURL "https://www.aamu.edu/admissions-aid/index.html",
   :HOSPITAL 2,
   :DEATHYR -2,
   :LANDGRNT 1,
   :STABBR "AL",
   :GENTELE "2563725000",
   :CARNEGIE 16,
   :CHFNM "Dr. Daniel K. Wims",
   :CLOSEDAT "-2        ",
   :RPTMTH 1,
   :MEDICAL 2,
   :CYACTIVE 1,
   :CBSATYPE 1,
   :VETURL " ",
   :C21UGPRF 10,
   :CONTROL 1,
   :ICLEVEL 1,
   :TRIBAL 2,
   :UNITID 100654,
   :WEBADDR "www.aamu.edu/",
   :DEGGRANT 1,
   :SECTOR 1,
   :C21BASIC 18,
   :INSTNM "Alabama A & M University",
   :OPENPUBL 1,
   :DFRCGID 107,
   :LONGITUD -86.568502,
   :OPEID "00100200  ",
   :HDEGOFR1 12,
   :C15BASIC 18,
   :DFRCUSCG 1,
   :C21IPGRD 18,
   :GROFFER 1,
   :PSEFLAG 1,
   :UEIS "JDVGS67MSLH7",
   :NEWID -2,
   :LOCALE 12,
   :CSA 290,
   :PSET4FLG 1,
   :IALIAS "AAMU",
   :INSTSIZE 3,
   :F1SYSNAM
   "-2                                                                              ",
   :OBEREG 5,
   :APPLURL
   "https://www.aamu.edu/admissions-aid/undergraduate-admissions/apply-today.html",
   :COUNTYCD 1089,
   :EIN "636001109 ",
   :CHFTITLE "President",
   :F1SYSTYP 2,
   :UGOFFER 1,
   :CNGDSTCD 105,
   :CITY "Normal",
   :ACT "A",
   :F1SYSCOD "-2",
   :ATHURL " ",
   :HBCU 1,
   :FIPS 1,
   :CCBASIC 18,
   :POSTSEC 1,
   :OPEFLAG 1,
   :DISAURL
   "https://www.aamu.edu/administrativeoffices/VADS/Pages/Disability-Services.aspx",
   :COUNTYNM "Madison County",
   :ADDR "4900 Meridian Street",
   :HLOFFER 9,
   :CBSA 26620,
   :C21SZSET 14,
   :FAIDURL "https://www.aamu.edu/admissions-aid/financial-aid/",
   :INSTCAT 2,
   :C21IPUG 16,
   :ZIP "35762",
   :C18BASIC 18,
   :C21ENPRF 4,
   :LATITUDE 34.783368}
  {:NPRICURL
   "https://tcc.ruffalonl.com/University of Alabama at Birmingham/Freshman-Students",
   :ADMINURL "https://www.uab.edu/admissions/",
   :HOSPITAL 1,
   :DEATHYR -2,
   :LANDGRNT 2,
   :STABBR "AL",
   :GENTELE "2059344011",
   :CARNEGIE 15,
   :CHFNM "Ray L. Watts",
   :CLOSEDAT "-2        ",
   :RPTMTH 1,
   :MEDICAL 1,
   :CYACTIVE 1,
   :CBSATYPE 1,
   :VETURL "https://www.uab.edu/students/veterans",
   :C21UGPRF 9,
   :CONTROL 1,
   :ICLEVEL 1,
   :TRIBAL 2,
   :UNITID 100663,
   :WEBADDR "https://www.uab.edu/",
   :DEGGRANT 1,
   :SECTOR 1,
   :C21BASIC 15,
   :INSTNM "University of Alabama at Birmingham",
   :OPENPUBL 1,
   :DFRCGID 92,
   :LONGITUD -86.799345,
   :OPEID "00105200  ",
   :HDEGOFR1 11,
   :C15BASIC 15,
   :DFRCUSCG 1,
   :C21IPGRD 14,
   :GROFFER 1,
   :PSEFLAG 1,
   :UEIS "YND4PLMC9AN7",
   :NEWID -2,
   :LOCALE 12,
   :CSA 142,
   :PSET4FLG 1,
   :IALIAS "UAB",
   :INSTSIZE 5,
   :F1SYSNAM
   "The University of Alabama System                                                ",
   :OBEREG 5,
   :APPLURL "https://www.uab.edu/admissions/apply",
   :COUNTYCD 1073,
   :EIN "636005396 ",
   :CHFTITLE "President",
   :F1SYSTYP 1,
   :UGOFFER 1,
   :CNGDSTCD 107,
   :CITY "Birmingham",
   :ACT "A",
   :F1SYSCOD "101050",
   :ATHURL "https://www.uab.edu/registrar/students",
   :HBCU 2,
   :FIPS 1,
   :CCBASIC 15,
   :POSTSEC 1,
   :OPEFLAG 1,
   :DISAURL "https://www.uab.edu/students/disability/",
   :COUNTYNM "Jefferson County",
   :ADDR "Administration Bldg Suite 1070",
   :HLOFFER 9,
   :CBSA 13820,
   :C21SZSET 15,
   :FAIDURL "https://www.uab.edu/cost-aid/",
   :INSTCAT 2,
   :C21IPUG 14,
   :ZIP "35294-0110",
   :C18BASIC 15,
   :C21ENPRF 5,
   :LATITUDE 33.505697}
  {:NPRICURL "https://www2.amridgeuniversity.edu:9091/",
   :ADMINURL "https://www.amridgeuniversity.edu/admissions/",
   :HOSPITAL 2,
   :DEATHYR -2,
   :LANDGRNT 2,
   :STABBR "AL",
   :GENTELE "33438738777550",
   :CARNEGIE 51,
   :CHFNM "Michael C.Turner",
   :CLOSEDAT "-2        ",
   :RPTMTH 1,
   :MEDICAL 2,
   :CYACTIVE 1,
   :CBSATYPE 1,
   :VETURL "https://www.amridgeuniversity.edu/admissions/military/",
   :C21UGPRF 5,
   :CONTROL 2,
   :ICLEVEL 1,
   :TRIBAL 2,
   :UNITID 100690,
   :WEBADDR "https://www.amridgeuniversity.edu/",
   :DEGGRANT 1,
   :SECTOR 2,
   :C21BASIC 20,
   :INSTNM "Amridge University",
   :OPENPUBL 1,
   :DFRCGID 125,
   :LONGITUD -86.17401,
   :OPEID "02503400  ",
   :HDEGOFR1 12,
   :C15BASIC 20,
   :DFRCUSCG 2,
   :C21IPGRD 18,
   :GROFFER 1,
   :PSEFLAG 1,
   :UEIS "RB27R4GLDKE7",
   :NEWID -2,
   :LOCALE 12,
   :CSA 388,
   :PSET4FLG 1,
   :IALIAS "Southern Christian University  Regions University",
   :INSTSIZE 1,
   :F1SYSNAM
   "-2                                                                              ",
   :OBEREG 5,
   :APPLURL "https://www.amridgeuniversity.edu/myamridge/",
   :COUNTYCD 1101,
   :EIN "237034324 ",
   :CHFTITLE "President",
   :F1SYSTYP 2,
   :UGOFFER 1,
   :CNGDSTCD 102,
   :CITY "Montgomery",
   :ACT "A",
   :F1SYSCOD "-2",
   :ATHURL " ",
   :HBCU 2,
   :FIPS 1,
   :CCBASIC 21,
   :POSTSEC 1,
   :OPEFLAG 1,
   :DISAURL "https://www.amridgeuniversity.edu/studentservices/",
   :COUNTYNM "Montgomery County",
   :ADDR "1200 Taylor Rd",
   :HLOFFER 9,
   :CBSA 33860,
   :C21SZSET 6,
   :FAIDURL "https://www.amridgeuniversity.edu/financialaid/",
   :INSTCAT 2,
   :C21IPUG 20,
   :ZIP "36117-3553",
   :C18BASIC 20,
   :C21ENPRF 6,
   :LATITUDE 32.362609}
  {:CTOTALT 7,
   :CWHITM 5,
   :CHISPW 0,
   :CASIAW 0,
   :CNHPIT 0,
   :CAIANW 0,
   :CBKAAT 0,
   :CTOTALM 5,
   :CASIAT 0,
   :CIPCODE "01",
   :CUNKNW 0,
   :CBKAAW 0,
   :AWLEVEL 1,
   :UNITID 101295,
   :C2MORT 0,
   :CBKAAM 0,
   :CNHPIM 0,
   :CUNKNT 0,
   :C2MORW 0,
   :CNRALM 0,
   :CUNKNM 0,
   :CTOTALW 2,
   :CAIANM 0,
   :CAIANT 0,
   :CASIAM 0,
   :CWHITT 7,
   :MAJORNUM 1,
   :CNRALT 0,
   :C2MORM 0,
   :CHISPT 0,
   :CWHITW 2,
   :CHISPM 0,
   :CNHPIW 0,
   :CNRALW 0}
  {:CTOTALT 8,
   :CWHITM 5,
   :CHISPW 0,
   :CASIAW 0,
   :CNHPIT 0,
   :CAIANW 0,
   :CBKAAT 3,
   :CTOTALM 8,
   :CASIAT 0,
   :CIPCODE "01",
   :CUNKNW 0,
   :CBKAAW 0,
   :AWLEVEL 1,
   :UNITID 101514,
   :C2MORT 0,
   :CBKAAM 3,
   :CNHPIM 0,
   :CUNKNT 0,
   :C2MORW 0,
   :CNRALM 0,
   :CUNKNM 0,
   :CTOTALW 0,
   :CAIANM 0,
   :CAIANT 0,
   :CASIAM 0,
   :CWHITT 5,
   :MAJORNUM 1,
   :CNRALT 0,
   :C2MORM 0,
   :CHISPT 0,
   :CWHITW 0,
   :CHISPM 0,
   :CNHPIW 0,
   :CNRALW 0}
  {:CTOTALT 4,
   :CWHITM 0,
   :CHISPW 0,
   :CASIAW 1,
   :CNHPIT 0,
   :CAIANW 0,
   :CBKAAT 0,
   :CTOTALM 0,
   :CASIAT 1,
   :CIPCODE "01",
   :CUNKNW 0,
   :CBKAAW 0,
   :AWLEVEL 1,
   :UNITID 102553,
   :C2MORT 0,
   :CBKAAM 0,
   :CNHPIM 0,
   :CUNKNT 0,
   :C2MORW 0,
   :CNRALM 0,
   :CUNKNM 0,
   :CTOTALW 4,
   :CAIANM 0,
   :CAIANT 0,
   :CASIAM 0,
   :CWHITT 3,
   :MAJORNUM 1,
   :CNRALT 0,
   :C2MORM 0,
   :CHISPT 0,
   :CWHITW 3,
   :CHISPM 0,
   :CNHPIW 0,
   :CNRALW 0}
  {:CrossReferences "",
   :CIPTitle
   "AGRICULTURAL/ANIMAL/PLANT/VETERINARY SCIENCE AND RELATED FIELDS.",
   :CIPDefinition
   "Instructional programs that focus on agriculture, animal, plant, veterinary, and related sciences and that prepares individuals to apply specific knowledge, methods, and techniques to the management and performance of agricultural and veterinary operations.",
   :TextChange "yes",
   :CIPFamily "01",
   :Examples "",
   :Action "No substantive changes",
   :CIPCode "01"}
  {:CrossReferences "",
   :CIPTitle "Agriculture, General.",
   :CIPDefinition "Instructional content is defined in code 01.0000.",
   :TextChange "no",
   :CIPFamily "01",
   :Examples "",
   :Action "No substantive changes",
   :CIPCode "01.00"}
  {:CrossReferences "14.0301 - Agricultural Engineering.",
   :CIPTitle "Agriculture, General.",
   :CIPDefinition
   "A program that focuses on the general principles and practice of agricultural research and production and that may prepare individuals to apply this knowledge to the solution of practical agricultural problems.  Includes instruction in basic animal, plant, and soil science; animal husbandry and plant cultivation; soil conservation; and agricultural operations such as farming, ranching, and agricultural business.",
   :TextChange "no",
   :CIPFamily "01",
   :Examples "",
   :Action "No substantive changes",
   :CIPCode "01.0000"}],
 :embed-worthy-fields ["INSTNM" "ADDR" "CITY" "CIPTEXT"]}
```

## Verdict

Survey delegated against a real CSV AND a real SQL source emits the profile contract incl. the embed-field signal, arriving as a PARSED MAP on the parent blackboard (projection read-back) — across both media, one subbehavior, per-medium tool-leaves.

C1 satisfied — and root-caused honestly. The PRIMARY mechanism keeping the profile parsed is the terminal repl-researcher's `(final! {:profile <map>})` capturing a real Clojure map in the sandbox, persisted verbatim, then mapped verbatim across `:delegate` (`execute-delegate-node` does not stringify). The load-bearing enabler is the PROMPT: it forbids `emit-tree!` and requires real EDN data — routing the profile through an emitted tree's `:llm` leaf is exactly what turned it into a JSON string in the EB2 prototype's first run (proven, then fixed). The structured `[:map …]` blackboard schema (vs a bare `:map`) is defense-in-depth — load-bearing only if a value ever flows through the AI/`:llm` coercion path (EB1's finding), not the primary fix here.

## Honest negative — observed cold-start blank-completion intermittency

On one earlier run the SQL medium failed with the framework's `"LLM did not generate code"` error — the known gemini cold-start BLANK code-gen completion on the first iteration (the executor carries a dedicated retry for exactly this, `rr-max-retries`, default 1). It is intermittent and independent of the Survey logic (the same code produced a clean parsed-map profile on the surrounding runs, including the run captured above). Root-caused (not dismissed as variance): it is the documented marker-omission / blank-first-completion failure mode, not a contract or delegation defect. Plumbing the node's `:options {:max-retries N}` through to the executor's `rr-max-retries` would harden it further, but that touches the orc-service todo-processor (out of EB2 scope — node `:options` is not currently passed as the executor's `options` kwarg) and is flagged for a small follow-up.
