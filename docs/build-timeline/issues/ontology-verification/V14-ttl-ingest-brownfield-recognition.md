# V14 — TTL ingest: brownfield concept-type recognition + no-false-green

**Type:** AFK · **Milestone:** M3 (unblocks A2 / Mode-A real path) · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`
**Surfaced by:** V02 Mode-A early read (`docs/build-timeline/live-verify/V02-mode-a-early-read.md` §6) — a real, root-caused bug, not a hypothetical.

## What to build

`ingest-ttl!` must faithfully ingest a brownfield TTL whose individuals are typed
with DOMAIN classes (not `skos:Concept`), and it must NEVER return a success
report when it recognized zero concepts out of a graph full of typed subjects.

Two distinct defects, both confirmed against the real 45 MB production BRYC TTL:

- **(a) Concept-type recognition is hardcoded to `skos:Concept`.** A subject is
  classified as a concept ONLY when its `rdf:type` set contains `skos:Concept`.
  The production graph has ZERO `skos:Concept` subjects — its 2,480 individuals
  are typed `edu:EducationalProgram` (×1599), `cip:CIPCode` (×447),
  `onet:Occupation` (×434), etc. Result: 0 concepts ingested, hence 0
  relationships (relationships only emit from recognized concept subjects).
- **(b) Silent-loss return shape.** With 0 concepts recognized the report still
  returns `:ingested? true` with `:anomaly nil`. A consumer checking only
  `:ingested?` gets a false green on a total data loss. This directly violates
  the rebuild's "round-trip-faithful, not silently lossy" contract.

The fix (root-cause, not a workaround):

1. **Broaden recognition.** Recognize a subject as a concept when EITHER it is
   typed `skos:Concept` (existing behavior, preserved) OR it is typed with a
   non-meta domain/`owl:Class`-typed class (i.e. its type is not one of the
   structural/OWL-meta types: `owl:Ontology`, `owl:Class`, `owl:DatatypeProperty`,
   `owl:ObjectProperty`, `owl:AnnotationProperty`, `rdf:Property`, `rdfs:Class`).
   Additionally accept a **caller-supplied concept-type set** via an opt
   (e.g. `:concept-types #{"edu:EducationalProgram" "cip:CIPCode" "onet:Occupation"}`)
   that, when present, takes precedence — so a consumer can be explicit about
   which classes are their concepts.
2. **No false green.** When the graph contains N typed non-meta subjects but the
   recognizer matched 0 of them as concepts, the report must surface that fact
   (a warning/anomaly field such as `:recognized 0 :typed-subjects N` or an
   `:anomaly`), NOT `:ingested? true :anomaly nil`. A consumer must be able to
   detect "recognized nothing" without inspecting counts.

This unblocks the M3 A2 leg (ingesting the real production BRYC graph into the
new substrate) and the brownfield "bring your own graph, we improve + extend it"
proof. It also lets V02's read be re-validated on the REAL ingest path rather
than its driver-side adapter.

## Acceptance criteria

- [ ] `ingest-ttl!` over the real production BRYC TTL ingests the domain-class
      individuals as concepts → non-zero `:concept` count matching the source
      type distribution (≈2,480 typed individuals: 1599 `edu:EducationalProgram`
      + 447 `cip:CIPCode` + 434 `onet:Occupation` + the smaller `edu:*` classes),
      and non-zero `:relationship` count from the edges on those subjects
      (`edu:hasCIPCodeEntity`, `cip:leadsToOccupation`, `edu:hasSector`, …).
- [ ] A caller-supplied `:concept-types` set is honored (only those classes are
      treated as concepts when supplied).
- [ ] When 0 of N typed non-meta subjects are recognized, the report surfaces a
      warning/anomaly — NO `:ingested? true :anomaly nil` false green.
- [ ] Pre-existing `skos:Concept`-typed graphs still ingest exactly as before
      (no regression — the existing S09 TTL round-trip suite stays green).
- [ ] OWL-meta subjects (`owl:Ontology`, property/class declarations) are NOT
      misclassified as concepts.
- [ ] Live verify: a real `ingest-ttl!` run over the real 45 MB TTL, captured —
      counts + a projection read-back proving the concepts/relationships landed
      in the event-sourced graph (not just returned in the report).

## Blocked by

None — independent file (`ttl_ingest.clj`); parallel-safe with V15 (`retrieval.clj`)
and V16 (`colbert/bridge.clj`).

## Cross-references

- Bug report with verbatim evidence: `docs/build-timeline/live-verify/V02-mode-a-early-read.md` §1, §6.
- The S09 TTL round-trip suite that must stay green (the no-regression guard).

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
