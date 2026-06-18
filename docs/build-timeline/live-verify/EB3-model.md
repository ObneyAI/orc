# EB3 — Model subbehavior sheet — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks** — real Grain event store, real LLM, real async todo processors, real child tick.

Proves the MODEL subbehavior is a delegatable SINGLE-`:llm` sheet that turns goal × the EB2 profile into a STRUCTURED model-spec (grain-strategy + scope-filter + embed-fields + candidate-axioms) arriving PARSED across `:delegate`, with `:reasoning` written FIRST — the V17/V20 over-extraction fix as a focused node. Built on the EB1/EB2 registry/delegation pattern; re-houses the DT3 grain/scope reasoning.

## Single `:llm` node (NOT a repl-researcher; F3 does not apply)

The Model body is ONE `:llm` node — single-turn reasoning over goal + profile. No tool session, no recursion, no F3 Phase-2 tick.

## Inputs

Delegated `goal` (SCOPED — names a subset) + a REAL breakdown-heavy EB2 SQL profile (captured verbatim from `EB2-survey.md`: grain-signals call out AWLEVEL/demographic breakdowns finer than the entity; scope-fields include STABBR). This is the V17/V20 over-extraction setup.

Goal:

```
Build a graph of the degree programs that institutions IN ALABAMA offer and the awards they confer, keyed by the codes that identify each institution and program. Only include institutions in the state of Alabama (state code AL).
```

Profile (the real EB2 SQL profile, verbatim shape):

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
 [{:STABBR "AL",
   :CONTROL 1,
   :UNITID 100654,
   :SECTOR 1,
   :INSTNM "Alabama A & M University",
   :OPEID "00100200  ",
   :EIN "636001109 ",
   :CITY "Normal",
   :ADDR "4900 Meridian Street"}
  {:STABBR "AL",
   :CONTROL 1,
   :UNITID 100663,
   :SECTOR 1,
   :INSTNM "University of Alabama at Birmingham",
   :OPEID "00105200  ",
   :EIN "636005396 ",
   :CITY "Birmingham",
   :ADDR "Administration Bldg Suite 1070"}
  {:CTOTALT 7,
   :CWHITM 5,
   :CIPCODE "01",
   :AWLEVEL 1,
   :UNITID 101295,
   :MAJORNUM 1,
   :CTOTALM 5,
   :CTOTALW 2}
  {:CTOTALT 8,
   :CWHITM 5,
   :CIPCODE "01",
   :AWLEVEL 1,
   :UNITID 101514,
   :MAJORNUM 1,
   :CTOTALM 8,
   :CTOTALW 0}],
 :embed-worthy-fields ["INSTNM" "ADDR" "CITY" "CIPTEXT"]}
```

## Registry + delegation

- subbehavior: `ontology-model/model@v1`
- sub sheet-id: `c086a505-b08f-5c1d-9edf-26aa1ebc567e`
- registry name→id round-trip: **true**
- central tree status: **:success** (6532ms)
- parent tick-id: `6b0925b9-bb3b-4eed-a5a9-82262b617645`

## C1 — model-spec arrives PARSED across `:delegate` (the `:llm` case)

Read back from the PARENT tick blackboard via the projection (`rm/get-tick-blackboard`), NOT from the execute return value. For an `:llm` node the STRUCTURED `[:map …]` `:writes` schema is the LOAD-BEARING fix (the executor flattens + reassembles the fields into a parsed map; a bare `:map` would arrive as a JSON string):

- model-spec is a PARSED MAP: **true**
- model-spec is a JSON STRING: **false** (must be false — that is the C1 failure mode)
- candidate-axioms is a PARSED MAP: **true**
- candidate-axioms is a JSON STRING: **false**
- model-spec frozen keys present: `[:entity-types :scope-filter :edges :embed-fields]`

## #13 — `:reasoning` written FIRST

`:reasoning` is the FIRST declared `:writes` key on the `:llm` node (chain-of-thought before the structured spec). It lands non-empty on the parent bb: present = **true**.

Reasoning (verbatim from the parent-tick projection):

```
The goal requires a graph of `Postsecondary Institutions` and `Academic Programs` linked by the awards (completions) they confer. 

1. **Grain Decision**: 
   - `Postsecondary Institution`: Keyed by `UNITID`. The grain is the institution itself. I will use `:canonical-row-filter`.
   - `Academic Program (CIP Code)`: Keyed by `CIPCODE`. This represents a standardized category of study. I will use `:canonical-row-filter`.
   - `Award/Completion`: The source (C2022_A) has rows broken down by `UNITID`, `CIPCODE`, and `AWLEVEL`. Since the goal asks for the awards conferred, and the data repeats for different award levels (e.g., Associate's vs Bachelor's) for the same program at the same school, each `AWLEVEL` record must be treated as a distinct entity to prevent collapse. I will use `:breakdown-as-entity` with `AWLEVEL` as part of the URI key.

2. **Scope Decision**: The goal explicitly restricts the scope to institutions in Alabama. The profile identifies `STABBR` as a scope-field. I will apply a `:scope-filter` for `STABBR` with value `["AL"]`.

3. **Embed-fields**: Based on the profile's `embed-worthy-fields`, `INSTNM` (Institution Name) and `CITY` provide high semantic value for retrieval.

4. **Axioms**: `UNITID` is a functional identifier for an Institution. Institutions and Programs are disjoint concepts.
```

## GRAIN — the V17/V20 over-extraction fix (NOT one-concept-per-row)

Each entity-type carries a `:grain-strategy` that normalizes onto the frozen enum `#{:breakdown-as-entity :canonical-row-filter}` — the model DECIDED a grain per entity rather than dumping one concept per raw breakdown row. all grains valid = **true**:

```clojure
[{:type "Postsecondary Institution",
  :raw-grain ":canonical-row-filter",
  :normalized :canonical-row-filter}
 {:type "Academic Program",
  :raw-grain ":canonical-row-filter",
  :normalized :canonical-row-filter}
 {:type "Award Conferral",
  :raw-grain ":breakdown-as-entity",
  :normalized :breakdown-as-entity}]
```

## SCOPE — keyed to a profile scope-field, value from the GOAL (NOT national)

- scope-filter present (non-nil): **true**
- scope-filter `:field`: `"STABBR"`
- that field is one the profile surfaced as a scope-field: **true**

```clojure
{:field "STABBR", :values ["AL"]}
```

## EB3 additions

- `:embed-fields` (P2 → EB7): `["INSTNM" "CITY" "ADDR"]`

- `:candidate-axioms` (→ EB6):

```clojure
[{:kind ":functional",
  :field "UNITID",
  :rationale
  "UNITID is the unique integrated postsecondary education data system identifier for an institution."}
 {:kind ":disjoint",
  :types ["Postsecondary Institution" "Academic Program"],
  :rationale
  "An organization that provides education is distinct from the conceptual classification of a field of study."}]
```

## Full model-spec (verbatim from the parent-tick projection)

```clojure
{:entity-types
 [{:type "Postsecondary Institution",
   :uri-keying-fields ["UNITID"],
   :grain-strategy ":canonical-row-filter"}
  {:type "Academic Program",
   :uri-keying-fields ["CIPCODE"],
   :grain-strategy ":canonical-row-filter"}
  {:type "Award Conferral",
   :uri-keying-fields ["UNITID" "CIPCODE" "AWLEVEL"],
   :grain-strategy ":breakdown-as-entity",
   :breakdown-key "AWLEVEL"}],
 :scope-filter {:field "STABBR", :values ["AL"]},
 :edges
 [{:source-type "Award Conferral",
   :target-type "Postsecondary Institution",
   :predicate "conferred_by"}
  {:source-type "Award Conferral",
   :target-type "Academic Program",
   :predicate "within_program"}],
 :embed-fields ["INSTNM" "CITY" "ADDR"]}
```

## C1 root cause — two framework-level fixes the prototype forced (not "variance")

C1 did NOT pass on the first delegate run; the model-spec arrived as a JSON
STRING. Both causes were chased to root (no "model variance" hand-wave):

1. **`:llm` per-field parsing.** An `:llm` node returns TEXT parsed against its
   `:writes` blackboard schema. The AI executor flattens a structured `[:map …]`
   write into separate DSCloj output fields and reassembles them — BUT a field
   typed `:any` is NOT parsed (it comes back as the raw EDN TEXT). The first
   schema typed every field `:any`, so even in isolation `:entity-types` arrived
   as the STRING `"[{:type …}]"`. Fix: type each flattened field with a CONCRETE
   collection spec (`[:vector …]` / `[:maybe [:map …]]`) so DSCloj parses it; leaf
   values stay `:any` for the DT3 value-shape tolerance. (In EB3's source file.)

2. **Partitioned-blackboard entity-id collision across sheets.** Even with the
   concrete schema, the model-spec arrived parsed in ISOLATION (3/3) but as a
   STRING across `:delegate` (3/3) — a deterministic split, not a race. Root
   cause: the `:sheet/blackboard` read model is partitioned by `:sheet-id`, and
   the read-model-processor-v2 partition cache requires GLOBALLY-UNIQUE
   entity-ids. It was keyed by the bare blackboard `:key` — and a key like `:goal`
   / `:model-spec` is NOT unique across sheets. Because `:delegate` maps
   `:reads`/`:writes` by the SAME key name on parent + sub, projecting the central
   tree's blackboard routed the sub-sheet's shared keys as cross-partition
   "moves", leaving the sub-sheet's snapshot blackboard EMPTY. The `:llm`
   executor then saw `:schema :any` (the empty-snapshot reconstruct), could not
   flatten, and returned the map as a string. Proven: reading the central sheet's
   blackboard before the sub's emptied the sub's partition; with distinct key
   names it did not. Fix: compose the blackboard entity-id with the sheet-id
   (`blackboard-entity-id` = `[sheet-id key]`) in the reducer + `:entity-id-fn`,
   and re-key `get-blackboard-by-key` back to `{key → entry}` so every consumer is
   unchanged. This is a pre-existing orc framework defect that ANY `:llm`
   subbehavior (and EB10's multi-subbehavior delegation, where subbehaviors share
   key names) would hit — EB3 is the first `:llm` subbehavior, so it surfaced
   here. Hermetic regression tests lock both the isolation and the
   delegate-survives-shared-key-names behavior (delegate-composition-test).

## Verdict

The Model subbehavior is a single-`:llm` delegatable sheet that turns goal + profile into a STRUCTURED model-spec (grain + scope + embed-fields + candidate-axioms) arriving PARSED across `:delegate` (C1, the `:llm` structured-schema case), with `:reasoning` first (#13) — the V17/V20 over-extraction fix as a focused node. C1 required two root-caused framework fixes (concrete per-field schema types + globally-unique blackboard entity-id); see above.
