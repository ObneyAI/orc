# Handoff — CONNECT-3a: the associative-junction fold (element nodes + occupation→element edges)

**Issue:** `docs/build-timeline/issues/onet-connectivity/CONNECT-3-junction-grain-element-nodes.md` (read the FINALIZED design). **The load-bearing connectivity fix.** Observation build (oid 398a54e1) PROVED: junction sheets aggregate to occupation attribute-lists (`:top_skills` etc.) → 0 edges, 0 element nodes, BFS dead. This slice adds the DETERMINISTIC associative mode so a SOC→Element junction mints SHARED element nodes + occupation→element edges — a traversable graph. Model-authoring of the spec is the FOLLOW-UP (CONNECT-3b); THIS tracer is the deterministic fold + landing + a BFS-traversal proof.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene; detached `nohup … &` for gate.

## Read first
- `container_aggregate.clj` — the streaming fold to REUSE (`aggregate-init`/`aggregate-step`/`aggregate-finalize`; the spec `{:key-col :element-col :value-col :attr-name}` at :208-210; top-N accumulates `{key -> [{:element :value}]}` — exactly the shape association needs). The associative mode is a NEW `aggregate-finalize` branch, NOT a fork of the step/fold.
- `vocabulary_binding.clj` `canonical-types`/GC-1 uri-keying — how a shared entity gets a canonical URI (so the same element across keys is ONE node).
- `retrieval.clj` `build-concept-graph` + `expand-concept-neighborhood` (BFS over relationships) — the traversal you must prove works on the new edges.
- `rlm_discovery.clj` `apply-aggregation-transform!` — how the fold is driven + how concept-drafts/relationship-drafts flow to landing (association must emit BOTH).

## The change (deterministic — no LLM in this tracer)
Add an ASSOCIATION mode to the aggregation path, signalled by the spec carrying `:predicate` + `:element-entity-type` (in place of `:attr-name`). The group-by fold (`aggregate-step`) is UNCHANGED (accumulate `{key -> [{:element :value}]}`, keeping the value-col rating). A new `aggregate-finalize` branch (or a sibling `associate-finalize`) emits, instead of one attribute-draft per key:
1. **Element concept-drafts** — DISTINCT element values across ALL keys → one draft each: `{:uri <canonical element URI via GC-1 uri-keying on the element value> :label <element> :entity-type <:element-entity-type> :attributes {…}}`. Dedup: the same element from many keys = ONE concept-draft.
2. **Relationship-drafts** — one per (key, element) pair: `{:source-uri <key-entity-URI> :target-uri <element-entity-URI> :predicate <:predicate> :attributes {<value-col-name> <value>}}` (rating rides the edge).
Both flow through `apply-aggregation-transform!` → landing (which already lands concept-drafts + relationship-drafts). Bounded/streaming preserved (reuse the chunk-pager; the accumulator is keys×elements — same as today's fold; canonical dedup keeps element nodes ≈ distinct elements).

Keep the existing collect/top-N attribute modes byte-identical (association is opt-in via `:predicate`/`:element-entity-type`; absent → today's behavior).

## TDD (tests FIRST, red→green, one at a time)
1. **Associative finalize → nodes + edges:** a Skills-shaped fixture — rows `{SOC, Element, DataValue}` for a few occupations sharing some skills — folded with an association-spec `{:key-col "SOC" :element-col "Element" :value-col "DataValue" :predicate "requires" :element-entity-type "skill" :key-entity-type "occupation"}` → `aggregate-finalize` returns concept-drafts (one DISTINCT skill per element, canonical URI) + relationship-drafts (one occupation→skill edge per row, `:attributes {"DataValue" <v>}`). RED (today: attribute draft, no edges).
2. **Canonical dedup:** a skill shared by 3 occupations → ONE skill concept-draft, THREE occupation→skill edges (not 3 skill nodes).
3. **Edge carries the rating:** the value-col value is on the edge `:attributes`.
4. **Attribute modes unchanged:** a spec with `:attr-name` (no `:predicate`) still produces the collect/top-N attribute draft, byte-identical (existing container_aggregate tests green).
5. **BFS traversal (the load-bearing proof):** land the fixture's occupations + the associative fold's concept-drafts + relationship-drafts into a real in-memory store; build the concept graph (`build-concept-graph`) and run BFS (`expand-concept-neighborhood`) from occupation A → assert it reaches skill S → reaches occupation B (a DIFFERENT occupation sharing S). This proves occupation↔skill↔occupation is now traversable (it was not — 0 edges before).

## Do NOT
Fork the fold/step (reuse `aggregate-step`); change collect/top-N attribute behavior; touch CONNECT-1/2, the model-authoring/discovery prompt (that's CONNECT-3b — this tracer drives the fold with a hand-built spec to prove the MECHANISM, per "test the builder later, prove the deterministic brick now"), MC-6, or the excel relations. Elements must be CANONICAL/shared (no per-(occupation,element) instance nodes — that's the over-mint we're avoiding). No hardcoded domain names.

## Gate + hygiene
`clj -M:poly test brick:ontology` green (detached). ONE JVM at a time; 0 orphan this-repo JVMs; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: tracers red→green (final gate line, exit 0); the associative-finalize diff (reusing the fold) + the spec fields that trigger it; quote the nodes+edges assertion, the canonical-dedup assertion (1 skill node / N edges), and THE BFS-traversal assertion (occupation A → skill → occupation B); confirm collect/top-N attribute modes byte-identical; existing container_aggregate + ontology gate green; anything not verified; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — root-cause; rule out the harness. 2. Verify QUALITY: the BFS-traversal proof (occupation↔skill↔occupation) is THE gate, not "edges exist" — a graph you can't traverse isn't the fix. 3. Instrument if a case resists. 4. Live/real: the BFS runs on the real graph builder + real store, not a mock. 5. No silent fallback. 6. TDD, tests first, one at a time, behavior through public fns. 7. No hardcoded domain matching — the association is spec-driven, names no O*NET column. 8. Re-orchestrate — reuse the aggregation fold + GC-1 canonical URIs + the real build-concept-graph/BFS; don't fork. 9. Adversarial: a shared skill must be ONE node with N edges (not N nodes); attribute modes must stay byte-identical. 10. Deterministic. 11. JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic (general system tested with O*NET). 13. n/a.
