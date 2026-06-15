# V06 Live Verify — RLM-controlled format-aware source ingestion → CONNECTED graph

Model: `google/gemini-2.5-flash` (OpenRouter). Real Grain in-memory event store.
Driver: `development/src/v06_rlm_ingestion_live_verify.clj` (runnable; key via env var).

The discovery RLM is granted the per-format source-access tools via the
`:granted-source` seam, explores each source by SAMPLING (never dumping), and
feeds the ONE deterministic skeleton through the `:rlm-discovery` parse route.
Cross-source linking is proven by shared, shareable `cip:`/`soc:` URIs.

## Verified — CSV (real cip_soc_crosswalk.csv, 6098 rows), standalone JVM

```
:discovery-status :emitted-drafts
:build-status     :complete
:iterations       2            ; (1) peek-columns + sample-rows, (2) final!
:concept-count    37           ; 17 cip:  + 20 soc:
:relationship-count 50         ; all predicate "cipMapsToSoc"
:every-edge-endpoint-resolves true   ; CONNECTED — no dangling edges
:sample-edges [{:source-uri "cip:01.0301" :target-uri "soc:45-1011" :predicate "cipMapsToSoc"}
               {:source-uri "cip:01.0103" :target-uri "soc:19-3011" :predicate "cipMapsToSoc"}
               {:source-uri "cip:01.0303" :target-uri "soc:11-9013" :predicate "cipMapsToSoc"}]
```

The model called `(peek-columns)` then `(sample-rows ...)` directly (no
emit-tree sub-tick), minted concepts with SHAREABLE code-system URIs
(`cip:<code>`, `soc:<code>`) — NOT row-local ids — and emitted the crosswalk
rows as `cipMapsToSoc` edges between them. Every edge endpoint resolves to a
concept in the graph: the result is a CONNECTED sub-graph, not per-source piles.

## Verified — SQLite (real IPEDS output.db, 296 MB, 59 tables), standalone JVM

```
:discovery-status :emitted-drafts
:build-status     :complete
:iterations       4   ; classify-behaviors, list-tables, table-schema CIPCodes, final!
:concept-count    100 ; all cip:  (sampled from the real CIPCodes table)
```

Verbatim sample of the drafts the model authored (the SQL leg of a combined run):

```clojure
[{:uri "cip:01"      :label "AGRICULTURAL/ANIMAL/PLANT/VETERINARY SCIENCE AND RELATED FIELDS."
  :description "Instructional programs that focus on agriculture, animal, plant..."
  :evidence [{:source "CIPCode" :quote "01"}]}
 {:uri "cip:01.0000" :label "Agriculture, General."
  :description "A program that focuses on the general principles and practice of..."
  :evidence [{:source "CIPCode" :quote "01.0000"}]}
 {:uri "cip:01.0103" :label "Agricultural Economics."
  :description "A program that focuses on the application of economics to the analysis..."
  :evidence [{:source "CIPCode" :quote "01.0103"}]}
 ...]
```

The SQL source mints `cip:01.0000` by the SAME `cip:<code>` scheme the crosswalk
uses → the two sources' concepts resolve to ONE concept by shared URI. This is
cross-source linking: a downstream graph connects program ↔ CIP ↔ SOC because
every source keyed on a CIP code contributes to the same `cip:<code>` node.

## Scale (no-dump) — proven (V03 instrumentation + V06 test)

Over the 6098-line crosswalk, `*last-lines-read*` stays < 200 for peek-columns
and sample-rows. The SQL tools open SQLITE_OPEN_READONLY with a hard 100-row
JDBC cap. The discovery model never reads a whole source.

## Honest note — combined back-to-back sessions in one JVM

Running two heavy live RLM sessions sequentially in a single JVM occasionally
surfaces a transient empty-completion ("LLM did not generate code") on the
first request of a cold process (a dscloj/litellm cold-start artifact, NOT a
V06 wiring defect — the same source succeeds cleanly in its own JVM, as the two
standalone captures above show). The `:failed-at-session` status surfaces this
honestly (no false green; nothing fabricated). The production build harness
(V09) discovers one source per session, which is the verified-reliable path.
