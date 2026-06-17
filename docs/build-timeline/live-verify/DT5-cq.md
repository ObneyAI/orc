# DT5 — Requirements / competency-question node (graph-level) — LIVE VERIFY

**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks.**
**Type:** HITL — the derived CQs below are surfaced for human review/override.

A GRAPH-LEVEL node that runs AFTER every source is profiled: it derives competency questions from the GOAL ⨯ the source profiles (grounded in what the sources contain + anchored to the goal), persists them as the S14 ORSD spec build!'s S15 exit-criterion judges, and surfaces them for HITL review. A consumer-supplied CQ set overrides/seeds the derived set.

GOAL (anchors the CQs; the scope lives here, not in the node):

> Build an ontology connecting fields/programs of study to the occupations they prepare people for, so we can recommend occupations for a Louisiana student's chosen program and trace which programs lead to a target occupation.

PROFILES consumed (graph-level — the node reasons over ALL sources' DT2 profiles, read tolerantly):

- CSV crosswalk profile (captured DT2, prose-string value shapes)
- IPEDS SQL profile (LIVE DT2 this run — status **:ok**)

```clojure
;; CSV crosswalk profile (consumed)
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

;; IPEDS SQL profile (consumed, live)
{:entity-candidates
 ["Postsecondary Institutions"
  "Admissions Data"
  "Educational Programs"
  "Student Completion and Awards"
  "Institutional Finances"
  "Library Collections"
  "Human Resources and Faculty Salaries"],
 :identifying-keys
 {:Financial Aid ["UNITID"],
  :Awards/Completions ["UNITID" "CIPCODE" "MAJORNUM" "AWLEVEL"],
  :Library Data ["UNITID"],
  :Postsecondary Institutions ["UNITID"],
  :Admissions Data ["UNITID"],
  :Campus Locations ["UNITID" "CAMPUSID"],
  :Student Enrollment ["UNITID" "EFALEVEL"],
  :Educational Programs ["UNITID" "CIPCODE"]},
 :scope-fields
 ["STABBR"
  "CITY"
  "SECTOR"
  "CONTROL"
  "HBCU"
  "YEAR"
  "INSTSIZE"
  "LOCALE"
  "OBEREG"],
 :linking-keys ["UNITID" "OPEID" "CIPCODE" "FIPS" "EIN" "CBSA"],
 :grain-signals
 ["AWLEVEL (Award Level)"
  "CIPCODE (Program Category)"
  "GENDER (Men/Women/Unknown)"
  "RACE/ETHNICITY (AIAN, ASIA, BKAA, HISP, NHPI, WHIT, 2MOR, UNKN, NRAL)"
  "AGE Group"
  "Attendance Status (Full-time/Part-time)"
  "Academic Rank (ARANK)"],
 :sample
 [{:STABBR "AL",
   :ADMSSN 6092,
   :UNITID 100654,
   :SECTOR 1,
   :INSTNM "Alabama A & M University",
   :ENRLT 1724,
   :APPLCN 8907,
   :CITY "Normal"}
  {:UNITID 100663,
   :INSTNM "University of Alabama at Birmingham",
   :CITY "Birmingham",
   :STABBR "AL",
   :SECTOR 1,
   :SATVR50 650,
   :ACTCM50 27}
  {:UNITID 101295,
   :CIPCODE "01",
   :AWLEVEL 1,
   :CTOTALT 7,
   :CWHITT 7,
   :MAJORNUM 1}]}
```

---

## A. DERIVED competency questions (for HITL review)

node status: **:ok** · origin: **:derived** · spec-recorded?: **true**

The DERIVED CQs (VERBATIM — this is the HITL review surface):

1. What are the specific occupational titles (SOC) available to a student who completes a 'Agriculture, General' (CIP 01.0000) program in Louisiana?
   - rationale: This question directly supports the GOAL of recommending occupations for a chosen program by linking 'CIP_Code'/'CIP_Title' from Profile 1 to 'SOC_Title' via the 'CIPCode' linking-key.
2. Which educational programs (CIP codes) offered by 'Alabama A & M University' (UNITID 100654) lead to the occupation of 'Animal Scientists' (SOC 19-1011)?
   - rationale: This traces programs to a target occupation (GOAL) by connecting Profile 2's institution data (UNITID, INSTNM) to Profile 1's mapping data using 'CIPCODE' as the bridge.
3. For a target occupation like 'Food Scientists and Technologists' (SOC 19-1012), which institutions (INSTNM) in a specific region or state (STABBR) granted awards (AWLEVEL) in the corresponding fields of study?
   - rationale: This addresses the tracing of programs to a target occupation (GOAL) while filtering by geographic fields (STABBR) and award levels (AWLEVEL) present in Profile 2, linked to Profile 1 via 'SOC_Code'.
4. What is the total number of completions (CTOTALT) in programs that prepare students for the 'Animal Scientists' (SOC 19-1011) occupation across all institutions in the dataset?
   - rationale: This leverages the quantitative 'CTOTALT' from Profile 2 to provide labor-supply insights for specific occupations defined in Profile 1, linked by 'CIPCODE'.
5. Which CIP codes are associated with more than five different SOC codes, and which institutions (UNITID) offer these high-versatility programs?
   - rationale: This identifies 'versatile' programs (many-to-many relationship noted in Profile 1's grain signals) and identifies where they are taught (Profile 2), satisfying the goal of recommending multiple career paths for a single field of study.
6. Can a student residing in a specific locale (LOCALE) find an institution (INSTNM) that offers a program (CIPCODE) leading to the 'Food Scientists and Technologists' (SOC 19-1012) occupation?
   - rationale: This satisfies the tracing requirement of the GOAL by connecting occupational targets (SOC) from Profile 1 to educational institutions (INSTNM) and their environmental context (LOCALE) in Profile 2 via 'CIPCODE'.

### Persisted as the ORSD spec build! reads (S14 projection read-back)

`ontology/get-ontology-spec` for the derive ontology-id returns the spec whose `:competency-questions` are EXACTLY the derived CQs — this is the same projection build!'s S15 exit-criterion-stage reads:

```clojure
{:competency-questions
 ["What are the specific occupational titles (SOC) available to a student who completes a 'Agriculture, General' (CIP 01.0000) program in Louisiana?"
  "Which educational programs (CIP codes) offered by 'Alabama A & M University' (UNITID 100654) lead to the occupation of 'Animal Scientists' (SOC 19-1011)?"
  "For a target occupation like 'Food Scientists and Technologists' (SOC 19-1012), which institutions (INSTNM) in a specific region or state (STABBR) granted awards (AWLEVEL) in the corresponding fields of study?"
  "What is the total number of completions (CTOTALT) in programs that prepare students for the 'Animal Scientists' (SOC 19-1011) occupation across all institutions in the dataset?"
  "Which CIP codes are associated with more than five different SOC codes, and which institutions (UNITID) offer these high-versatility programs?"
  "Can a student residing in a specific locale (LOCALE) find an institution (INSTNM) that offers a program (CIPCODE) leading to the 'Food Scientists and Technologists' (SOC 19-1012) occupation?"],
 :purpose
 "Build an ontology connecting fields/programs of study to the occupations they prepare people for, so we can recommend occupations for a Louisiana student's chosen program and trace which programs lead to a target occupation."}
```

PROOF of persistence: spec :competency-questions == derived CQs? **true**

---

## B. Consumer override (HITL seed/override)

node status: **:ok** · origin: **:supplied** · spec-recorded?: **true**

Consumer SUPPLIED these CQs (derivation SKIPPED):

1. Which specific occupations can a Louisiana graduate of a given program enter?
2. Which programs at Louisiana institutions lead to a given target occupation?

The ORSD spec for the supplied ontology-id carries the SUPPLIED CQs (override took effect):

```clojure
{:competency-questions
 ["Which specific occupations can a Louisiana graduate of a given program enter?"
  "Which programs at Louisiana institutions lead to a given target occupation?"],
 :purpose
 "Build an ontology connecting fields/programs of study to the occupations they prepare people for, so we can recommend occupations for a Louisiana student's chosen program and trace which programs lead to a target occupation."}
```

PROOF of override: spec :competency-questions == supplied CQs? **true**

---

## Verdict (adversarial — to be confirmed by the HITL reviewer)

GROUNDED: each derived CQ should be answerable from what the profiles show the sources contain (the crosswalk's CIP↔SOC linking + the IPEDS institution/program fields). A CQ about a thing no profile mentions would be an ungrounded defect — review for it.

GOAL-ANCHORED (not self-fulfilling): the CQs should test the GOAL's core connection (program ↔ occupation, scoped to Louisiana) — NOT merely paraphrase the extraction. Review that the questions exercise the goal's intent, especially the CROSS-SOURCE link via the shared codes.

HITL-REVIEWABLE + OVERRIDABLE: the derived CQs + per-CQ rationale are surfaced above (A); the supplied-override path (B) lets a reviewer replace them. Both persist as the S14 ORSD spec the S15 gate judges.

DOMAIN-AGNOSTIC (discipline 12): the CQ prompt body names NO industry concept — verified by test `cq-prompt-is-domain-agnostic` rendering the prompt with a neutral goal. The only domain reference is the runtime goal.
