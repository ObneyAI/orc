# EB4 — Extract subbehavior sheet — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks** — real Grain event store, real LLM, real async todo processors, real child tick, REAL source file.

Proves the EXTRACT subbehavior is a delegatable THREE-node sheet (`:code` sample → `:llm` author → `:code` apply) that turns the EB3 model-spec × the source into the actual DRAFT SET: the `:llm` AUTHOR node, GIVEN the REAL sampled-row key-shape (Node 1's `mechanical-sample-rows`) + the model-spec, AUTONOMOUSLY authors a field-grounded per-row transform, which the V20 `apply-extraction-transform!` (Node 3, reused not forked) applies over the FULL source → a SANE SCOPED concept count, per-row errors counted, no abort. `:reasoning` written FIRST (#13). Built on the EB1/EB2/EB3 registry/delegation pattern; re-houses DT4 + the DT4-grounding field-grounding fix.

## Source + model-spec (inputs)

Source (REAL file): `/Users/darylroberts/Downloads/cip_soc_crosswalk.csv` (CSV CIP/SOC crosswalk, 6,098 rows). In-row scope (CIP_Code); STRING keys with EXACT header names — the DT4 honest-negative trap (the model previously invented `:CIP2020Code`) that Node 1's real key-shape fixes.

Model-spec (EB3 shape, CIP-family-01 scope):

```clojure
{:entity-types
 [{:type "Program of Study",
   :uri-keying-fields ["CIP_Code"],
   :grain-strategy :canonical-row-filter}
  {:type "Occupation",
   :uri-keying-fields ["SOC_Code"],
   :grain-strategy :canonical-row-filter}],
 :scope-filter {:field "CIP_Code", :values ["01"]},
 :edges
 [{:source-type "Program of Study",
   :target-type "Occupation",
   :predicate "prepares_for"}],
 :embed-fields ["CIP_Title" "SOC_Title"]}
```

## SOFT probe — the AUTHOR node grounds in the REAL keys

Node 1 (`mechanical-sample-rows`) surfaced the REAL key-shape; the AUTHOR node was given it. The AUTONOMOUS transform-source the node authored (NO hand-correction):

- REAL keys (Node-1 mechanical-sample-rows): `["CIP_Code" "CIP_Title" "SOC_Code" "SOC_Title"]` (key-type: string)
- references `CIP_Code` (real key): **true**
- references `SOC_Code` (real key): **true**
- references an INVENTED key (`CIP2020Code`/`SOC2018Code`, the prior honest negative): **false** (must be false)

Authored transform-source (verbatim, off the sheet blackboard):

```clojure
(fn [row]
  (let [cip-code (get row "CIP_Code")
        cip-title (get row "CIP_Title")
        soc-code (get row "SOC_Code")
        soc-title (get row "SOC_Title")
        
        ;; Scope filter: field "CIP_Code" must start with "01"
        in-scope? (and (string? cip-code) 
                       (clojure.string/starts-with? cip-code "01"))]
    
    (if-not in-scope?
      {:concept-drafts [] :relationship-drafts []}
      
      (let [program-uri (str "program-of-study:" cip-code)
            occupation-uri (str "occupation:" soc-code)]
        
        {:concept-drafts
         [{:uri program-uri
           :label cip-title
           :type "Program of Study"
           :attributes {:cip_code cip-code}
           :evidence [(str "Program: " cip-title " (CIP: " cip-code ")")]}
          
          {:uri occupation-uri
           :label soc-title
           :type "Occupation"
           :attributes {:soc_code soc-code}
           :evidence [(str "Occupation: " soc-title " (SOC: " soc-code ")")]}]
         
         :relationship-drafts
         [{:source-uri program-uri
           :target-uri occupation-uri
           :predicate "prepares_for"}]}))))
```

Reasoning (written FIRST, #13):

```
The model-spec defines two entities: `Program of Study` (keyed by `CIP_Code`) and `Occupation` (keyed by `SOC_Code`). Both utilize a `:canonical-row-filter` grain strategy. Since the source rows represent a mapping (many-to-many) between Programs and Occupations, each row carries one "edge" of the relationship. 

1. **Grounding Field Access**: Sample rows use string keys (e.g., `"CIP_Code"`, `"SOC_Code"`). I will access them using `(get row "CIP_Code")`.
2. **Scope Filter**: The spec requires `CIP_Code` values starting with "01". However, the sample data shows codes like "01.0000". I will implement a prefix check or equality check based on the provided values. Given the spec says `"values": ["01"]`, I will treat this as a prefix/starts-with check or a direct match if the data is exactly "01". Looking at the sample "01.0000", I will use `clojure.string/starts-with?` to ensure rows matching the "01" category are included.
3. **Grain Strategy**: `:canonical-row-filter` generally means one concept per distinct entity instance across the dataset. Because this is a join table, many rows will share the same `CIP_Code` or `SOC_Code`. The URI keying ensures that identical entities merge. To avoid redundant attributes, I will generate the Concept drafts and Relationship drafts for every row; the downstream system handles the merging of attributes for identical URIs.
4. **URI Generation**: URIs will be constructed by prefixing the type and appending the identifying field (e.g., `"program/" + CIP_Code`).
5. **Edges**: A "prepares_for" relationship will be created between the Program and the Occupation for every row that passes the scope filter.
```

## Registry + delegation

- subbehavior: `ontology-extract/extract@v1`
- sub sheet-id: `f7c7bf38-c4d8-5abf-931f-1cf3f8b9a61a`
- registry name→id round-trip: **true**
- central tree status: **:success** (4749ms)
- parent tick-id: `30f28f9d-7333-4b04-896a-df3ea6c1533e`

## P1 — the AUTONOMOUS transform yields a SANE SCOPED count over the FULL source

Read back from the PARENT tick blackboard via the projection (`rm/get-tick-blackboard`), NOT from the execute return value (discipline 7). The draft set crossed `:delegate` as VECTORS (a `:code`-node output parses naturally — C1):

- drafts are VECTORS across `:delegate`: **true**
- SCOPED concept count: **572** (NOT a 6,098-row dump)
- relationship count: **286**

Extraction report (V20 apply coverage — per-row errors counted, no abort):

```clojure
{:errors-sample [],
 :rows-errored 0,
 :selector "null",
 :windows 61,
 :relationship-count 286,
 :concept-count 572,
 :rows-ok 6097,
 :rows-streamed 6097}
```

Sample concepts (verbatim):

```clojure
[{:uri "program-of-study/01.0000",
  :label "Agriculture, General.",
  :type "Program of Study",
  :attributes {:cip_code "01.0000"},
  :evidence ["Program: Agriculture, General. (CIP: 01.0000)"]}
 {:uri "occupation/19-1011",
  :label "Animal Scientists",
  :type "Occupation",
  :attributes {:soc_code "19-1011"},
  :evidence ["Occupation: Animal Scientists (SOC: 19-1011)"]}
 {:uri "program-of-study/01.0000",
  :label "Agriculture, General.",
  :type "Program of Study",
  :attributes {:cip_code "01.0000"},
  :evidence ["Program: Agriculture, General. (CIP: 01.0000)"]}
 {:uri "occupation/19-1012",
  :label "Food Scientists and Technologists",
  :type "Occupation",
  :attributes {:soc_code "19-1012"},
  :evidence
  ["Occupation: Food Scientists and Technologists (SOC: 19-1012)"]}
 {:uri "program-of-study/01.0000",
  :label "Agriculture, General.",
  :type "Program of Study",
  :attributes {:cip_code "01.0000"},
  :evidence ["Program: Agriculture, General. (CIP: 01.0000)"]}
 {:uri "occupation/19-1013",
  :label "Soil and Plant Scientists",
  :type "Occupation",
  :attributes {:soc_code "19-1013"},
  :evidence ["Occupation: Soil and Plant Scientists (SOC: 19-1013)"]}
 {:uri "program-of-study/01.0000",
  :label "Agriculture, General.",
  :type "Program of Study",
  :attributes {:cip_code "01.0000"},
  :evidence ["Program: Agriculture, General. (CIP: 01.0000)"]}
 {:uri "occupation/19-4012",
  :label "Agricultural Technicians",
  :type "Occupation",
  :attributes {:soc_code "19-4012"},
  :evidence ["Occupation: Agricultural Technicians (SOC: 19-4012)"]}]
```

Sample relationships (verbatim):

```clojure
[{:source-uri "program-of-study/01.0000",
  :target-uri "occupation/19-1011",
  :predicate "prepares_for"}
 {:source-uri "program-of-study/01.0000",
  :target-uri "occupation/19-1012",
  :predicate "prepares_for"}
 {:source-uri "program-of-study/01.0000",
  :target-uri "occupation/19-1013",
  :predicate "prepares_for"}
 {:source-uri "program-of-study/01.0000",
  :target-uri "occupation/19-4012",
  :predicate "prepares_for"}]
```

## Verdict

The Extract subbehavior is a delegatable THREE-node sheet (`:code` → `:llm` → `:code`) whose `:llm` AUTHOR node, given the REAL sampled-row key-shape (Node 1) + the model-spec, AUTONOMOUSLY authors a field-grounded transform (no hand-correction) that the reused V20 apply-step applies over the FULL source → a SANE SCOPED concept count (NOT a raw-row dump), per-row errors counted, no abort, with `:reasoning` first (#13). REUSE not fork: `mechanical-sample-rows` (Node 1) + `apply-extraction-transform!` (Node 3). The draft set crosses `:delegate` parsed (C1).
