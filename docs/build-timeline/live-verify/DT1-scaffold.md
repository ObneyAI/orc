# DT1 — Discovery-tree scaffold + orchestration — LIVE VERIFY

**Date:** 2026-06-17. **Branch:** `feature/ontology-architecture`.
**Model:** `google/gemini-3-flash-preview` (real OpenRouter). **Source:** real CIP↔SOC crosswalk CSV (`cip_soc_crosswalk.csv`). **No mocks.** Run UNBOUNDED end-to-end on the merged+fixed engine (dscloj `41ef3e2` + DTscale-1 dedup fix).

Ontology-id: `98526717-3408-4729-9fb4-2ec271268e2d`. Budget: `{:max-iterations 12, :total-budget-ms 900000, :max-retries 3}`. Elapsed: 195758ms.

The tree ran Profile -> Model -> Transform -> [V20 apply-step] -> build! -> read CQ verdict, on ONE real source. The nodes are THIN (DT1 proves orchestration, not node intelligence). Each node received ONLY a domain goal + its focused step prompt + its predecessor's contract output.

## Orchestration result

- tree status: **:complete**
- nodes-run (fixed-core sequence, structurally guaranteed): `[:profile :model :transform]`
- build! status (intact deterministic skeleton, INVOKED UNCHANGED): **:complete**
- concepts in graph: **3011** ; relationships: **6097**

## Inter-node contract — VERBATIM node outputs (the FROZEN PRD M2 shapes)

### Profile node output

```clojure
{:entity-candidates
 ["CIP Program (Classification of Instructional Programs)"
  "SOC Occupation (Standard Occupational Classification)"],
 :identifying-keys
 {"CIP Program" ["CIP_Code"], "SOC Occupation" ["SOC_Code"]},
 :scope-fields [],
 :linking-keys ["CIP_Code" "SOC_Code"],
 :grain-signals
 ["Multiple rows per CIP_Code (mapping to different SOC codes)"
  "Multiple rows per SOC_Code (mapping to different CIP codes)"],
 :sample nil}
```

### Model node output

```clojure
{:entity-types
 "CIP Program (Key: CIP_Code), SOC Occupation (Key: SOC_Code)",
 :scope-filter
 "canonical-row-filter (Grain: CIP_Code + SOC_Code composite to handle the many-to-many crosswalk mapping)",
 :edges "CIP Program -> maps_to -> SOC Occupation"}
```

### Transform node output (the V20 extraction-transform shape)

```clojure
{:transform-source
 "(fn [row]\n     (let [cip-code (get row \"CIP_Code\")\n           cip-title (get row \"CIP_Title\")\n           soc-code (get row \"SOC_Code\")\n           soc-title (get row \"SOC_Title\")\n           cip-uri (str \"cip:\" cip-code)\n           soc-uri (str \"soc:\" soc-code)]\n       {:concept-drafts \n        [{:uri cip-uri\n          :label cip-title \n          :evidence [{:source \"CIP_Code\" :quote cip-code}\n                     {:source \"CIP_Title\" :quote cip-title}]}\n         {:uri soc-uri\n          :label soc-title\n          :evidence [{:source \"SOC_Code\" :quote soc-code}\n                     {:source \"SOC_Title\" :quote soc-title}]}]\n        :relationship-drafts\n        [{:source-uri cip-uri\n          :target-uri soc-uri\n          :predicate \"cipMapsToSoc\"\n          :confidence-class \"extracted\"\n          :evidence [{:source \"crosswalk-row\" :quote (str cip-code \"->\" soc-code)}]}]}))",
 :selector nil}
```

## V20 deterministic full-extraction coverage (apply-step over the FULL source)

```clojure
{:selector nil,
 :rows-streamed 6097,
 :rows-ok 6097,
 :rows-errored 0,
 :windows 61,
 :errors-sample []}
```

## build! CQ verdict surfaced onto the tree result (PRD M7)

graph-health:

```clojure
{:unknown-rate 0.0,
 :unknown-count 0,
 :fail-rate 0.0,
 :layer-counts {:layer-2-semantic-exists 2},
 :ontology-id #uuid "98526717-3408-4729-9fb4-2ec271268e2d",
 :judge-share 1.0,
 :last-evaluation-ts "2026-06-17T12:04:34.907455-05:00",
 :total-cqs 2,
 :pass-rate 1.0,
 :fail-count 0,
 :pass-count 2}
```

exit-criterion: `nil`

referential-integrity: `{:every-edge-endpoint-resolves? true, :dangling-edge-count 0, :dangling-edges []}`

## Read-back from the projection

Sample concepts:

```clojure
[{:uri "soc:53-4041", :label "Subway and Streetcar Operators"}
 {:uri "cip:04.0202", :label "Architectural Design."}
 {:uri "cip:61.0999",
  :label
  "Medical Genetics and Genomics Residency/Fellowship Programs, Other."}
 {:uri "cip:19.0203",
  :label "Consumer Merchandising/Retailing Management."}
 {:uri "cip:11.0999",
  :label "Computer Systems Networking and Telecommunications, Other."}
 {:uri "cip:52.1909", :label "Special Products Marketing Operations."}]
```

compile provenance (V18 referential integrity):

```clojure
{:ambiguities-flagged 0,
 :axioms-emitted 0,
 :concepts-emitted 12194,
 :unresolved-endpoints 0,
 :ambiguities [],
 :every-edge-endpoint-resolves? true,
 :relationships-emitted 6097,
 :status :ingested,
 :rlm-trace [],
 :unresolved-endpoint-uris [],
 :implied-concepts-minted 0}
```

## Branch points — present as NAMED no-op stubs (DT8/DT9 fill them)

```clojure
{:greenfield-vs-maintain
 {:branch :greenfield-vs-maintain,
  :taken? false,
  :reason :stub-not-yet-implemented,
  :selected :greenfield},
 :full-extract-vs-inline
 {:branch :full-extract-vs-inline,
  :taken? false,
  :reason :stub-not-yet-implemented,
  :selected :full-extract,
  :row-count nil,
  :sample-covers? false},
 :cq-reextract
 {:branch :cq-reextract,
  :taken? false,
  :reason :stub-not-yet-implemented,
  :build-status :complete,
  :graph-health
  {:unknown-rate 0.0,
   :unknown-count 0,
   :fail-rate 0.0,
   :layer-counts {:layer-2-semantic-exists 2},
   :ontology-id #uuid "98526717-3408-4729-9fb4-2ec271268e2d",
   :judge-share 1.0,
   :last-evaluation-ts "2026-06-17T12:04:34.907455-05:00",
   :total-cqs 2,
   :pass-rate 1.0,
   :fail-count 0,
   :pass-count 2}},
 :recovery
 {:branch :recovery,
  :taken? false,
  :reason :stub-not-yet-implemented,
  :failed-node nil,
  :error nil}}
```

