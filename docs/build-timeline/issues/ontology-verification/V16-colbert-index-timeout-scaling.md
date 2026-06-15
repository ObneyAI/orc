# V16 — ColBERT index-creation timeout scaling (+ LMDB map-size handoff note)

**Type:** AFK · **Milestone:** M3 (gates V09 graph-B build at scale) · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`
**Surfaced by:** V02 Mode-A early read (`docs/build-timeline/live-verify/V02-mode-a-early-read.md` §2, §7) — a real scale boundary hit at ~2.5K docs.

## What to build

ColBERT index creation (`:create_index`) must not silently time out at the
60-second default on real-scale corpora. At ~2,509 documents the bridge call
returned `{:error "Bridge call timed out after 60000ms for method :create_index"}`,
so the ColBERT signal silently dropped out of hybrid-search (RRF still runs on
the remaining 2 signals, so it does NOT error — it just quietly under-retrieves).
The full 5-source graph B will be larger than 2.5K concepts, so this blocks a
fair head-to-head: the new side would under-retrieve without any failure signal.

Root cause: the bridge applies a single default request timeout (60 s) to ALL
methods. That default is tuned for query latency; index creation is O(corpus
size) and legitimately takes longer. (The training path already uses a 24-hour
timeout — index creation needs a similarly scale-appropriate budget.)

The fix (root-cause, not a bare bigger constant):

1. **Index creation gets a scale-appropriate timeout** — either corpus-size-aware
   (scales with document count) or a generous configurable timeout for
   `:create_index` specifically, with a floor of several minutes. The query path
   keeps its short default (don't blanket-raise all methods).
2. **No silent drop.** If index creation DOES exceed even the raised budget, that
   must surface as a real error the caller can see — never a quiet RRF-on-2-signals
   degenerate path presented as success (discipline 4: no false green).
3. **LMDB map-size — handoff note, not a code fix here.** The default in-memory
   LMDB cache map-size (10 MB) `MapFull`-crashes on a real graph (2.5K concepts ×
   384-dim embeddings); V02's driver bumps it to 4 GB. The map-size lives in the
   Grain `kv-store-lmdb` dependency (not this repo's components), so V09's build
   harness must set an adequate map-size. Capture this requirement explicitly in
   the V09 handoff / build-harness config — it is a known prerequisite, not a
   surprise to rediscover at build time.

## Acceptance criteria

- [ ] `:create_index` over a corpus of ≥2,509 documents COMPLETES (no 60s
      timeout) in a real ColBERT live verify — captured with the doc count and
      elapsed time.
- [ ] The index-creation timeout is scaled/configurable (root cause addressed),
      NOT a single larger hardcoded constant applied to every bridge method.
- [ ] The query path retains its short default timeout (no regression to query
      latency expectations).
- [ ] If index creation exceeds even the raised budget, the caller receives a
      surfaced error — there is no silent "RRF on 2 signals" success masking a
      missing ColBERT index.
- [ ] The LMDB map-size prerequisite is documented as an explicit V09
      build-harness config requirement (with the observed 10 MB→`MapFull` failure
      and the 4 GB working value from V02).
- [ ] Existing ColBERT suite stays green (no regression to query-path behavior).

## Blocked by

None — independent file (`colbert/core/bridge.clj`); parallel-safe with V14
(`ttl_ingest.clj`) and V15 (`retrieval.clj`).

## Cross-references

- Bug report with the verbatim timeout error + LMDB `MapFull` finding:
  `docs/build-timeline/live-verify/V02-mode-a-early-read.md` §2 + §7 items 3–4.
- Gates V09 (graph B build) — the new builder over the 5 sources will exceed 2.5K
  concepts; without this, the ColBERT signal silently drops from hybrid-search and
  the comparison is unfair.

## Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation)

1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.

### Verification-phase additions (binding for this initiative)

8. No strawman / unbiased baseline. The old side (graph A1) always runs at its STRONGEST honest config (embeddings on, crosswalk CIP↔SOC extracted as edges, FK extraction). Never hobble or weaken the old system to make the new one look better — beating a weakened baseline proves nothing.
9. Adversarial qualitative verdict. The comparison is judged on the ACTUAL verbatim information returned, per vertical — actively hunt for where the NEW system is WORSE. "Both completed" is not a pass; no false-better.
10. "Deterministic skeleton" ≠ LLM-free. The ontology is DISCOVERED BY LLMs (recursive-RLM discovery + LLM dedup/CQ judges) inside the deterministic skeleton; verify BOTH the deterministic contracts AND the LLM-discovery quality.
11. Standing ops rules: the real OpenRouter key is passed as a shell env var ONLY, never committed; never truncate model-authored output when capturing/comparing (pass verbatim); retrieval-facing descriptions are self-contained (no file paths / SHAs / slice names); HITL audit every changed/added file by path before any commit; branch = feature/ontology-architecture; one commit per slice; co-author `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
