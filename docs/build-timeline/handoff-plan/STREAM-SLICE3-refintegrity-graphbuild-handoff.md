# Handoff — STREAM Slice 3: ref-integrity + graph-build streaming (+ `reduce-relationships`)

**Parent plan:** `/Users/darylroberts/.claude/plans/precious-sleeping-kurzweil.md`. Foundation (`18e114b0`) + embed-diff (`c64839b9`) landed. This converts the graph-build / ref-integrity consumers off `(vals (project))`, and GENERALIZES the foundation with `reduce-relationships`.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene; detached `nohup … &` for gate.

## The Slice-2 scoping finding (READ — it governs this slice)
`(filter #(= id (:ontology-id %)) (rm/get-concepts ctx {}))` reads the UNSCOPED URI-keyed projection then filters — **last-writer-wins across ontologies** (if two ontologies mint the same URI, the unscoped map keeps only the last landed). `concept-stream/reduce-concepts` folds the `[:ontology id]`-**tag-scoped** stream — it gets ALL of THIS ontology's concepts regardless of cross-ontology URI collision, i.e. it is **more correct**, and **equivalent for a single-ontology store** (the normal build). Requirement: the conversion must be **byte-identical on a single-ontology fixture** (the tested/normal case); the multi-ontology-collision difference is a documented CORRECTNESS improvement, not a regression — assert single-ontology equivalence, and add a test showing the tag-scoped read is correct where the unscoped-filter would drop/misattribute a colliding URI.

## What to build
### A. Generalize the foundation — add `reduce-relationships` to `concept_stream.clj`
Analogous to `reduce-concepts`: fold the REGISTERED `:ontology/relationships` reducer (via `rmp/global-read-model-registry`) over a `[:ontology id]`-tag-scoped windowed `es/read` stream, then reduce `rf`/`init` over the relationship VALUES; support `:project-fn` + `:window`. FIRST verify relationship events carry the `[:ontology id]` tag (grep `create-relationship` in `commands.clj` — concepts are tagged `#{[:ontology id] [:concept cid]}`; confirm relationships are `#{[:ontology id] …}`). If relationships are NOT `[:ontology id]`-tagged, scope the same way `reduce-concept-embeddings` does (type-scoped + body `:ontology-id` filter) — match the real tag shape, don't assume.

### B. Convert `deterministic_skeleton.clj` `normalize-stage` (~278-296)
- `concepts` (line 280) → `reduce-concepts` with a `:project-fn` keeping ONLY what `referential-integrity-report` + the counts need — READ `referential-integrity-report` to pin the field set (likely just `:uri` for the endpoint-existence SET, maybe `:label`). Drop heavy `:attributes`.
- `relationships` (line 282) → `reduce-relationships` (project to the endpoint fields the report needs — `:source-uri`/`:target-uri`/`:predicate`).
- `:concepts-count`/`:relationships-count`/`:referential-integrity` must be IDENTICAL on a single-ontology fixture. (The `es/read` at line 287 is already tag-scoped — leave it, or note it's the same pattern.)

### C. Convert the graph builder — `retrieval.clj` `build-concept-graph` (search it)
If it does `(rm/get-concepts …)`/`(rm/get-relationships …)` to build adjacency in memory, convert to `reduce-concepts`/`reduce-relationships`. Adjacency (broader/narrower/related) is O(edges) metadata — acceptable; project to the graph fields only (uri + edge fields), drop heavy attributes. If `build-concept-graph` is only used by a path not on the OOM-critical build, note it and prioritize normalize-stage.

## Do NOT
Touch the dedup concept load (Slice 4 — `candidate-pairs`/`dedup-stage`), CQ-retrieval (Slice 5), caps/ingestion (6-7). Reuse `concept-stream/*`. No domain names. Do NOT change ref-integrity's LOGIC — only its data source.

## TDD (tests first, red→green)
1. **`reduce-relationships` state-invariance** (in `concept_stream_test`): folds the SAME relationship set as `(vals (rmp/project :ontology/relationships {:tags …}))` on a fixture; multi-page windowing; reuses the registered reducer.
2. **normalize-stage byte-preserving (single-ontology):** `:concepts-count`/`:relationships-count`/`:referential-integrity` identical to the pre-conversion `(filter … (get-concepts))` version on a single-ontology fixture with a dangling-endpoint relationship (so ref-integrity has something to report).
3. **Scoping correctness:** a two-ontology fixture where ontology B mints a URI that ontology A also has → the tag-scoped `reduce-concepts` for A returns A's concept (not B's last-writer version); document this as the correctness win.
4. Existing `deterministic_skeleton` / graph-build tests stay green.

## Gate + hygiene
`clj -M:poly test brick:ontology` green (detached). ONE JVM at a time; 0 orphan this-repo JVMs after; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: tracers red→green (final gate line, exit 0); the `reduce-relationships` API + the normalize-stage/build-concept-graph diffs + the project-fn field sets; quote the single-ontology byte-equivalence assertion (counts + ref-integrity identical) AND the multi-ontology scoping-correctness assertion; the relationship tag shape you verified; existing tests green; anything not verified; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — verify the relationship tag shape; root-cause any behavior change. 2. Verify QUALITY: a conversion that changes counts or ref-integrity on a single-ontology build is wrong — assert byte-equivalence. 3. Instrument if symptoms resist. 4. Live/real (real read models on the brick gate) is the proof. 5. No silent fallback. 6. TDD, tests first, behavior through public fns. 7. No hardcoded domain matching. 8. Re-orchestrate — reuse/generalize `concept-stream`, don't fork; don't change ref-integrity logic. 9. Adversarial: prove single-ontology byte-equivalence AND the multi-ontology correctness win on real fixtures. 10. Deterministic streaming — verify state-invariance. 11. JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic. 13. n/a.
