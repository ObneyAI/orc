# V14 — TTL ingest brownfield recognition + no-false-green — LIVE VERIFY

**Status:** done, awaiting HITL sign-off. **Date:** 2026-06-15.
**Branch:** `feature/ontology-architecture`.
**Slice:** `docs/build-timeline/issues/ontology-verification/V14-ttl-ingest-brownfield-recognition.md`.
**Driver:** `development/src/v14_brownfield_ingest.clj`.
**No LLM, no embedding** — rdflib parse + URDNA2015 canonicalize + local
Grain commands + projection read-back only (no OpenRouter key required).

---

## What the V02 early read surfaced (the bug, root-caused)

The shipped S09 `ingest-ttl!` classified a subject as a concept ONLY when
its `rdf:type` set contained `skos:Concept`. The production BRYC TTL has
ZERO `skos:Concept` subjects — its 2,509 individuals are typed with DOMAIN
classes (`edu:EducationalProgram` ×1599, `cip:CIPCode` ×447,
`onet:Occupation` ×434, plus the small `edu:Awardlevel`/`edu:Discipline`/
`edu:Sector`). Result: 0 concepts → 0 relationships, yet the report
returned `:ingested? true :anomaly nil` — a silent zero-ingest / false
green.

A SECOND defect was found while fixing the first (root-caused, not
hand-waved): brownfield concepts carry plain (untagged) `rdfs:label`s.
`collect-labels` was emitting `{:value …}` entries with no `:lang`, which
violate the `:labels` event-schema (`[:map [:value :string] [:lang :string]]`).
The resulting concept-created event failed command validation and silently
never persisted — the concept was COUNTED in the report but ABSENT from the
projection. This is exactly the silent-loss class V14 targets, so it was
fixed in the same slice: language-tagged labels go to `:labels`; a plain
`rdfs:label` becomes the single `:label`.

## The fix (root cause)

1. **Broadened recognition** (`concept-subject?`): `skos:Concept` OR any
   non-OWL-meta domain class. Meta types excluded: `owl:Ontology`,
   `owl:Class`, `owl:DatatypeProperty`, `owl:ObjectProperty`,
   `owl:AnnotationProperty`, `rdf:Property`, `rdfs:Class`, and the property
   characteristics `owl:FunctionalProperty`/`owl:TransitiveProperty`/
   `owl:SymmetricProperty` (the S09 bundle's property declarations — the
   regression guard). A caller-supplied `:concept-types` set takes
   precedence (matched exact OR by IRI local-name, so a prefixed
   `"edu:EducationalProgram"` matches the raw
   `<http://example.org/education#EducationalProgram>` form).
2. **No false green**: when N typed non-meta subjects exist but 0 are
   recognized, the report carries `:recognized 0 :typed-subjects N` and an
   `:anomaly` string.
3. **Label faithfulness**: lang-tagged → `:labels`; plain → `:label`. Same
   for comments.

All recognition is STRUCTURAL (type-set / IRI-segment membership) — no
label string-matching.

## Live verify — REAL ingest over the 45 MB production BRYC TTL (VERBATIM)

Source: `area_51/ontology_exploration/output/louisiana_programs_full.ttl`
(47,148,625 bytes).

`ingest-ttl!` report (verbatim, minus the per-command bodies):

```clojure
{:ingested? true,
 :ontology-id #uuid "7695b6e8-4972-4f9b-89dd-5834274ad09a",
 :triples-parsed 119348,
 :recognized 2509,
 :typed-subjects 2509,
 :anomaly nil,
 :counts {:concept 2509,
          :ontology-metadata 1,
          :relationship 6190,
          :equivalence 0, :disjointness 0, :characteristic 0,
          :sub-property 0, :chain-axiom 0}}
;; ingest wall-ms: 7070
```

**Projection read-back** (proves it landed in the event-sourced graph, not
just the report — read via `rm/get-concepts` / `rm/get-relationships`):

```
projected concepts:        2509
projected relationships:   6190
concept URIs by namespace: {:cip 447, :edu 1628, :soc 434}
sample concept:      {:uri "<http://example.org/cip#cip_42_2804>",
                      :label "Industrial and Organizational Psychology.",
                      :scope :custom}
sample relationship: {:source-uri "<http://example.org/education#102_Concord_Ave_1453>",
                      :target-uri "<http://example.org/education#Apprenticeship>",
                      :predicate  "<http://example.org/education#hasSector>"}
```

**Concept count = 2509 matches the source type distribution exactly**:
1599 `edu:EducationalProgram` + 447 `cip:CIPCode` + 434 `onet:Occupation`
+ 11 `edu:Awardlevel` + 10 `edu:Discipline` + 8 `edu:Sector` = 2509. The
163 OWL-meta subjects (97 DatatypeProperty + 58 ObjectProperty + 7 Class +
1 Ontology) were correctly EXCLUDED; the 1 `owl:Ontology` landed as
ontology-metadata. The by-namespace tally reads `:edu 1628`
(1599+11+10+8), `:cip 447`, `:soc 434` — the occupations are typed
`onet:Occupation` but carry `soc:`-prefixed URIs.

Before this fix the same run produced `:concept 0 :relationship 0
:ingested? true :anomaly nil` (V02 §1).

## Synthetic tests (the floor)

`components/ontology/test/ai/obney/orc/ontology/v14_brownfield_recognition_test.clj`
— 4 tests / 18 assertions, all green:

- brownfield domain-class TTL → non-zero concepts + relationships, proven
  via projection read-back
- caller-supplied `:concept-types` honored (restricts recognition)
- 0-of-N recognition surfaces `:recognized 0 :typed-subjects N :anomaly …`
- OWL-meta subjects NOT misclassified (only the domain individual lands)

## Regression sweep (no-regression guard)

- S09 TTL round-trip suite: **15 tests / 46 assertions, 0 fail / 0 error**
  (identical to pre-V14 baseline; G1-FORWARD still 5 concepts, round-trip
  semantic-diff still EQUIVALENT).
- V14 suite: **4 tests / 18 assertions, 0 fail / 0 error**.
- S17 deterministic-skeleton suite: **14 tests / 41 assertions, 0 fail /
  0 error**.

## Reproduce

```clojure
clj -M:dev
(require '[v14-brownfield-ingest :as v])
(def r (v/run!))
```
