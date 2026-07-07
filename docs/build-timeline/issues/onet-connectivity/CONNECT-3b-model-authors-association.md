# CONNECT-3b — model AUTHORS the association-spec (discovery) + rating on the edge

**Type:** HITL-leaning (LLM discovery) · **Blocked by:** CONNECT-3a (landed `9d24d8d9`)

## What to build
CONNECT-3a added the deterministic associative FOLD (spec with `:predicate`+`:element-entity-type` → shared element nodes + key→element edges). This slice makes the MODEL DISCOVER when to author that spec (not hardcoded — test-the-builder), and lands the rating on the edge.

1. **Grain guidance** — extend `cagg/aggregation-author-guidance` (container_aggregate.clj:504) with a THIRD case. Today: (A) tall table → roll up to an ATTRIBUTE (top-N/list), (B) one-row-per-entity → per-row transform. Add: **(C) tall table whose ELEMENT values are a SHARED REFERENCEABLE ENTITY** — the SAME element value recurs across MANY DIFFERENT entity keys (the element is a reusable "thing" with its own identity, e.g. a capability many entities share), AND it is itself an entity you'd want to reach/traverse — then author an ASSOCIATION-spec: the aggregation-spec PLUS `:predicate <the relationship verb, e.g. requires/performs>` + `:element-entity-type <the element's entity-type>`. This mints the element as a SHARED node + a key→element EDGE (a traversable graph) instead of burying it in an attribute list. Keep (A) for element values that are ENTITY-SPECIFIC labels (unique to one key — names/titles/one-off measures) → attribute. **Domain-agnostic:** the signal is structural (element value shared across different keys) + semantic (is it a referenceable entity) — name NO domain column/type.
2. **parse-aggregation-spec** (container_aggregate.clj:454) — accept + pass through `:predicate` + `:element-entity-type` (tolerating the C1 string/normalized forms like the other fields).
3. **Rating on the edge** — associate-finalize currently drops the value onto the draft `:attributes`, which does NOT survive into the edge `:properties` (keyword-keyed schema; the compile path drops non-`:properties` edge fields — CONNECT-3a noted this). Thread the `:value-col` value onto the relationship-draft as keyword-keyed `:properties` so `relationship-draft->command` lands it (occupation→skill edge carries the proficiency/importance rating).

## Acceptance criteria
- [ ] PROTOTYPE (first, test-the-builder): given a REAL O*NET junction sample (Skills: SOC + Element Name + Data Value + Scale ID) + the new guidance, the model AUTHORS an association-spec (`:predicate` + `:element-entity-type`) — it DISCOVERS that the element is a shared entity → edges, not hardcoded. Run it a few times (LLM variance) and report the hit-rate.
- [ ] The guidance keeps (A)/(B) intact: an entity-specific-label tall table (alternate titles) still → attribute list; a one-row-per-entity sheet still → per-row transform.
- [ ] parse-aggregation-spec round-trips the association fields.
- [ ] The occupation→element edge carries the rating in `:properties`.
- [ ] LIVE bounded O*NET build: occupations carry `requires`/`performs` EDGES to shared skill/task nodes (edge-scan: occupation cross-sheet edge-participation > 0; BFS occupation↔skill↔occupation traversable) — DISCOVERED by the model, not fed.
- [ ] Ontology brick gate green; collect/top-N attribute behavior unregressed.

## Disciplines (verbatim — a subagent MUST NOT skip these)
- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids. Reproduce → minimize → fix the actual cause. Rule out the harness.
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer bullets. Test behavior through public interfaces.
- **Injected-capability seam pattern** for effects.
- **Durable tests AND live QA** — the PROTOTYPE + live O*NET build is the proof the model DISCOVERS it; turn the deterministic parts into durable tests.
- **Test the builder, don't feed it the answer** — give the model the sample + guidance; NEVER hardcode "Skills→association" or the predicate/type. The associative recognition must be the model's OWN discovery.
- **Dispatch to fresh agents, then INDEPENDENTLY + ADVERSARIALLY verify** — re-run the prototype, re-read the edges from the store, confirm occupation↔skill↔occupation traverses.
- **Report faithfully** — including the LLM hit-rate and any run where the model chose attribute over association. No hardcoded domain matching. Commit-LOCAL only. JVM hygiene (`pgrep -f`, one at a time, detached, 0 orphans).
