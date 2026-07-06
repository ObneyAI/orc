# Handoff — MT-8: CQ-gate evidence must include concept attributes (the :unknown root cause)

**Parent:** the MT-7c acceptance finish line. **Blocked-by:** none (independent of the vocabulary line).
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene.

## The problem (root-caused live — don't re-derive)
The MT-7c bounded acceptance's run 1 COMPLETED the full pipeline (1016 occupations, zero fragmentation, **topSkills/topKnowledge coverage 0.88 vs A2's 0.045 — the headline win**), but the CQ-gate returned **:unknown on all 5 CQs** (`:budget-exhausted`). Root cause, confirmed by reading the code + the graph shape:

- The model chose the DENORMALIZED grain — skills/knowledge/job-zone/riasec are flat **`:attributes`** (a `[:map-of :keyword :any]`, per `interface/schemas.clj:232`) on each occupation concept (NO separate Skill nodes; run 1 schemes were only `{occupation, educationElement}`). `measure-b`/`a2-baseline` read exactly these key-value attributes to compute the 0.88.
- But `cq_runner/render-evidence-text` serializes every concept (both the retrieved hits AND the full in-scope enumeration) as ONLY `:uri [label: …] :description` — it **discards `:attributes` (and the legacy `:properties` bag)**. So for "what skills does occupation X have?", the judge is handed X's label but NOT X's `topSkills` attribute where the answer lives → it correctly concludes "the graph is silent" → `:unknown`. The judge WAS called (5 verdicts came back); it just never saw the facts.

## The change (targeted, BOUNDED — `render-evidence-text` in `cq_runner.clj`)
Include each concept's `:attributes` (and the legacy `:properties` bag) in the evidence text, so the judge can see attribute-borne facts:

1. **Retrieved-hits block (primary fix):** for each retrieved hit (limit 25 — the query-relevant subset), render its `:attributes`/`:properties` as readable `key: value` lines under the hit. This is the scale-safe target — 25 concepts, fully attributed.
2. **In-scope concepts block (bounded):** this renders ALL concepts (1016+). Adding full attributes to all of them risks blowing the judge's context. BOUND it: either (a) render attributes only for a capped number of concepts, or (b) render a compact `key` list (attribute NAMES, not full values) for the enumeration, or (c) leave the enumeration label-only and rely on the retrieved-hits attributes. YOUR call — but state the reasoning and keep the evidence text from ballooning unboundedly on a large graph (surface any cap honestly, GC-2 style). The retrieved-hits attributes (piece 1) are the load-bearing fix.
3. **Render values readably** (handle vectors/maps/scalars; a `topSkills` value may be a list). Skip nil/empty. Do NOT truncate a value's content mid-fact (#11) — if you must bound, bound the COUNT of attributes/concepts rendered, not the content of a rendered value, and say so.

DELIBERATELY NOT: no change to the judge prompt's :pass/:fail/:unknown semantics; no change to retrieval; no grain assumption (attributes OR edges both flow through — this just stops discarding the attribute half). Domain-agnostic: render whatever attribute keys exist; name no O\*NET field.

## /prototype
Not needed — the root cause is read-confirmed; the fix is a serialization change with a clear red test.

## TDD cycle (tests FIRST, red→green, PUBLIC `render-evidence-text` + `layer-2-or-3-verdict` with an injected judge)
1. **`render-evidence-text` includes retrieved-hit attributes:** a retrieved hit with `:attributes {:top-skills ["Active Listening" "Critical Thinking"]}` → the evidence string CONTAINS those values. RED first (current code omits them).
2. **`render-evidence-text` includes the `:properties` bag** likewise; nil/empty attributes are skipped cleanly.
3. **End-to-end via `layer-2-or-3-verdict` with an injected judge** that returns `:pass` iff the evidence text contains the skill value → with a concept carrying the skill attribute, the judge now RECEIVES it and the path yields `:pass` (before the fix: the value is absent → the injected judge can't see it). Proves the judge can now answer an attribute-borne CQ.
4. **Bounded-enumeration behavior:** a large concept set → the evidence text stays bounded per your chosen cap, and the cap is surfaced (not a silent truncation). Existing `cq_runner`/`validate_cq` tests stay green (cite them).

## Live-QA (the reviewer's `/inspect-orc`)
Re-run the MT-7c bounded acceptance: a completing run's skills-CQs should now resolve `:pass` (not `:unknown`) because the judge sees the topSkills attributes — turning the proven-but-unanswered graph into a passing semantic gate.

## Do NOT touch
The judge prompt semantics; retrieval; the grain; the vocabulary-binding line. No fuzzy matching; no domain names; no mid-value truncation.

## Core Disciplines (binding — verbatim)
1. NEVER assume; NEVER "flaky" — the :unknown is root-caused (evidence omits attributes). 2. Verify QUALITY not completion — a fix that dumps unbounded attributes and blows the judge context, or that fabricates a :pass without the value present, is wrong; test both the presence AND the bound. 3. Instrument to root cause. 4. Live REAL everything; the re-acceptance is the proof; no false green. 5. No silent fallback/truncation — bound by COUNT + surface it. 6. TDD, tests first, public fns, injected judge. 7. Assert via the rendered text + verdict; NO fuzzy/hardcoded matching. 8. Re-orchestrate — extend the serializer; do NOT fork the CQ path. 9. Adversarial verdict — hunt a fabricated :pass or an unbounded evidence blob. 10. Deterministic serialization around the LLM judge — verify both. 11. Key = env var; NEVER truncate a fact's value; JVM hygiene. 12. Domain-agnostic — render whatever attributes exist; name no O\*NET field. 13. `:reasoning` first on `:llm` nodes (none new here).
