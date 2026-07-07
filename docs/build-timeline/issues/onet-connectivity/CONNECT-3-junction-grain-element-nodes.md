# CONNECT-3 — Grain: junction sheets mint shared element nodes (so MC-6 can edge them)

**Type:** AFK (likely prompt/grain guidance) · **Blocked by:** CONNECT-2 — **craft this handoff AFTER CONNECT-2 lands and its MC-6 behavior is inspected** (dependency rule: don't pre-write it; observe whether occupation edges materialize once excel `:relations` exists, and exactly which grain the model must choose for the junction elements to be concepts MC-6 can join).

## FINALIZED design (observation build confirmed it, oid 398a54e1)
Empirical: with CONNECT-1+2, junction sheets (Skills/Knowledge/Tasks: SOC+Element+rating) AGGREGATE onto occupations as attribute LISTS (`:top_skills`/`:topKnowledgeAreas`/`:top_tasks`) → 1016 occupation nodes, **0 edges, 0 element nodes**. Data captured but NOT a traversable graph (BFS dead). Per the design decision (edges for SHARED ENTITIES, attributes for LITERALS — a graph with BFS needs edges; shared nodes dedup + are embeddable): a SOC→Element junction is an ASSOCIATIVE table → mint the Element as a **shared canonical node** (deduped, embeddable) + a **key→element EDGE** carrying the rating.

**Mechanism (reuse container_aggregate's streaming fold — NOT a fork):** the aggregation-spec is `{:key-col :element-col :value-col :attr-name}` (collect → attribute list; top-N → attribute). Add an ASSOCIATION mode signalled by `:predicate` + `:element-entity-type` (in place of `:attr-name`): the SAME group-by fold (key → [(element,value)]) but `aggregate-finalize` emits, instead of an attribute:
- **concept-drafts**: one DISTINCT element concept per element value (canonical URI via GC-1 uri-keying, entity-type = `:element-entity-type`, label = element) — deduped across ALL keys (Active Listening = ONE node, not 900).
- **relationship-drafts**: one edge per (key,element) pair `{:source-uri <key-entity>/<key> :target-uri <element-entity>/<element> :predicate <predicate> :attributes {<value-col> <value>}}` (the rating rides the edge).

Elements are embeddable (autodetect embed-fields), so retrieval gets BOTH semantic (ColBERT/embeddings on the skill node) AND BFS traversal (occupation↔skill↔occupation). Literals (Title/Description/SOC/dates) stay attributes.

## What to build (provisional — finalize after CONNECT-2)
MC-6 joins CONCEPTS across containers sharing a `:via` key (recovered from `:attributes`). A SOC→element junction sheet (Abilities/Skills/Knowledge/Task Statements: SOC + Element + rating) must therefore mint the ELEMENT as a shared concept carrying SOC — NOT aggregate it into an occupation attribute list (grain-strategy `canonical-row-filter` → `aggregate-finalize` returns only `:concept-drafts` with attributes, no element concept for MC-6), and NOT a SOC-less standalone node (breakdown-as-entity from a reference sheet, no shared key). The grain vocabulary is only `{:canonical-row-filter :breakdown-as-entity}` (discovery_tree.clj:124-129) — assess whether junction connectivity needs a new grain option (associative/junction) or GM-1 prompt guidance steering junction sheets to mint the element node + carry the SOC so MC-6 edges occupation↔element. Avoid over-minting (elements are SHARED/canonical — ~35 skills, not per-occurrence).

## Acceptance criteria (provisional)
- [ ] For a SOC→element junction sheet, the element is minted as a shared concept carrying the SOC key in `:attributes` (MC-6 can recover the `:via` value).
- [ ] No over-minting: shared elements collapse to canonical nodes (skill appears once, referenced by many occupations), not per-row/per-occurrence duplicates.
- [ ] Combined with CONNECT-2, MC-6 emits occupation↔element edges.
- [ ] Domain-agnostic; existing extraction paths + gates green.

## Disciplines (verbatim — a subagent MUST NOT skip these)
- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids. Reproduce → minimize → fix the actual cause. Rule out the harness.
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer bullets. Test behavior through public interfaces.
- **Injected-capability seam pattern** for effects.
- **Durable tests AND live QA** — drive the real O*NET junction sheets; turn what you verify into a durable test.
- **Dispatch to fresh agents, then INDEPENDENTLY + ADVERSARIALLY verify** — re-run the proof, try to break it.
- **Report faithfully.** No hardcoded domain matching. Commit-LOCAL only. JVM hygiene (`pgrep -f`, one at a time, 0 orphans).
