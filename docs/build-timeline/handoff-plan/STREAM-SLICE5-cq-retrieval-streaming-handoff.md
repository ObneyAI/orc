# Handoff — STREAM Slice 5: CQ-retrieval streaming (bounded top-K cosine, byte-invariant)

**Parent plan:** `/Users/darylroberts/.claude/plans/precious-sleeping-kurzweil.md`. Slices 0-4 landed (`724fea3d`). This eliminates the HEAVIEST resident set — the CQ/semantic retrieval materializes the ENTIRE embedding-vector map for an in-memory cosine scan.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene; detached `nohup … &` for gate.

## Design decision (DEVIATES from the plan's "ColBERT-first" — read the why)
The plan floated routing CQ retrieval through the disk ColBERT index instead of the in-memory scan. That is a **compute** optimization that CHANGES retrieval results (drops the embedding signal from the RRF fusion → CQ verdicts could shift → needs live-QA + risks the acceptance). The initiative's goal is **bounded MEMORY**. A **streaming top-K cosine** achieves bounded memory AND is **byte-invariant** (identical top-K → identical CQ evidence → identical verdicts, zero live-QA risk). So THIS slice = stream the cosine; ColBERT-first is a documented FOLLOW-UP (a compute-perf change, out of scope). Do the safe, invariant memory fix now.

## Part A — stream `semantic-search-concepts` (`retrieval.clj` 686-739)
Today: `concept-embeddings (rm/get-all-concept-embeddings ctx filter-opts)` (line 719, materializes ALL vectors) → `(embedding/search-concepts-by-embedding query-embedding concept-embeddings :limit … :min-similarity …)` (line 724, in-memory cosine over the whole map). Replace with a STREAMING top-K:
- Fold `concept-stream/reduce-concept-embeddings ctx ontology-id` (respecting the scope/ontology filter — note the `filter-opts` may carry `:scope`/`:ontology-ids`; `reduce-concept-embeddings` is ontology-id scoped + body-filtered, so honor the same filter the map version applied — if `filter-opts` is richer than ontology-id, replicate that predicate in the rf, or keep the map version for the rare multi-scope call and note it).
- For each streamed `(uri, vector)`: compute `cosine(query-embedding, vector)` reusing the SAME cosine fn `search-concepts-by-embedding` uses (find it in the `embedding` component — reuse, do NOT re-implement the metric), keep only those `≥ min-similarity`, maintain a **bounded top-`limit` structure** (a sorted set / a fixed-size min-heap by similarity — never accumulate all scored pairs). Discard each vector after scoring.
- Emit the same enriched result shape (`{:uri :similarity :label :description :scope}` via `static/get-concept-by-uri`). **The top-K set MUST be byte-identical to the full-scan version** (top-K by similarity is order-invariant — same K, same tie-breaking as `search-concepts-by-embedding`; MATCH its tie-break/sort exactly).

## Part B — the existence check (`retrieval.clj:1431`)
`(seq (rm/get-all-concept-embeddings ctx))` — materializes the whole vector map just to test "any embeddings exist?". Replace with a short-circuit over `reduce-concept-embeddings` (e.g. `(pos? (reduce-concept-embeddings ctx id (fn [n _ _] (reduced (inc n))) 0))` or a `some`-style early-exit) — never materialize. Confirm the scope/args match.

## Part C — the CQ-gate concept load (`cq_runner.clj` ~193-201)
`get-concepts-fn {}` (the structural-existence graph signal) → `concept-stream/reduce-concepts` with a `:project-fn` keeping the fields the CQ layer-1/evidence reads (grep `cq_runner` for the concept field accesses — likely `:uri :label :description`/`:labels`). Byte-identical concept set. If `get-concepts-fn` is injected (a seam), thread the streamed version through the injection point.

## Do NOT
Change the cosine METRIC, the RRF fusion weights, the top-K/tie-break, or ColBERT (the ColBERT-first routing is the follow-up). Touch caps/ingestion (6-7). Reuse `concept-stream/*` + the existing cosine fn — do NOT re-implement. No domain names.

## TDD (tests first, red→green)
1. **Streaming top-K == full-scan top-K:** on a fixture of concepts+embeddings + a query vector, assert the streamed `semantic-search-concepts` returns the SAME ordered `[{:uri :similarity …} …]` (same K, same order, same similarities) as the pre-conversion full-map version. This is the byte-invariance guard.
2. **Bounded:** the fold never holds more than the top-K + one vector (prove via the accumulator shape — it carries ≤ `limit` scored entries, no full vector list).
3. **Existence check (Part B):** returns the same boolean as `(seq (get-all-concept-embeddings …))` without materializing.
4. **CQ concept load (Part C):** byte-identical concept set to `get-concepts-fn {}` (single-ontology); the existing cq_runner tests stay green (same verdicts).
5. Existing retrieval + cq_runner + hybrid-search tests stay green.

## Gate + hygiene
`clj -M:poly test brick:ontology` green (detached). ONE JVM at a time; 0 orphan this-repo JVMs after; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: tracers red→green (final gate line, exit 0); the `semantic-search-concepts` streaming diff (top-K structure + the reused cosine fn) + the existence-check + cq_runner diffs + the project-fn field set; quote the top-K byte-invariance assertion (streamed == full-scan) + the bounded-accumulator proof; confirm the cosine metric/RRF/top-K tie-break are UNCHANGED; existing retrieval/cq tests green; the ColBERT-first follow-up noted; anything not verified; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — reuse the exact cosine fn + tie-break; root-cause any result change. 2. Verify QUALITY: a streamed top-K that differs from the full scan (order/tie-break) is wrong — assert byte-identity. 3. Instrument if results diverge. 4. Live/real (the retrieval + cq tests) is the proof. 5. No silent fallback. 6. TDD, tests first. 7. No hardcoded domain matching; reuse the metric. 8. Re-orchestrate — stream the scan, reuse the cosine fn + `concept-stream`; don't fork or change the metric/fusion. 9. Adversarial: hunt a top-K that reorders vs the full scan (tie-break mismatch). 10. Deterministic — top-K + verdicts identical. 11. JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic. 13. n/a.
