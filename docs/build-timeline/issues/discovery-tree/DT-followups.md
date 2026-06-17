# Discovery-tree — tracked follow-ups (not lost; gate the real DT10 build, not the node slices)

These are honest gaps surfaced during the node slices. They do NOT block the
node-correctness slices (DT1-DT9), but they DO gate a fully-comprehensive,
production real build (DT10 / the BRYC head-to-head). Logged here so nothing is
lost. Each likely becomes its own fix-slice before DT10.

## F1 — Embed stage scale (surfaced by DTscale-1)
At thousands of concepts, the embed stage is the next bottleneck after the dedup
fix: ~92s for ~3K concepts, because each concept is one `:ontology/concept-embedded`
event + a `get-concept-by-uri` projection. Completes, but is the dominant stage at
scale. Fix direction: batch the embed (one event for many concepts, or batch the
projection read) so embed is sub-linear-ish, not per-concept-projection.
Evidence: `docs/build-timeline/live-verify/` DTscale-1 capture.

## F2 — Scope-SET resolution completeness for SQL (surfaced by DT4-grounding)
When a goal scope requires a cross-table value SET (e.g. all institution ids in a
region via a sub-query), the model under-pages the sub-query — it resolved only
7 of 115 Louisiana institutions, so the scoped extraction UNDER-counts. Field
grounding + scope APPLICATION are correct; the gap is resolving the full scope
SET. (CSV in-row scope, e.g. a code-prefix match, has no sub-query and works.)
Fix direction: when the model resolves a scope set via the SQL `query` tool, it
must page the full result (count + stream-all the scope sub-query), or the
transform seam supplies the resolved scope set deterministically from a paged
query. This is the same "page to completeness" theme as the extract-to-coverage
scaffolding (DT3) — apply it to scope-set resolution too.
Evidence: `docs/build-timeline/live-verify/DT4-grounding.md` adversarial verdict.

## Also watch
- ColBERT index time grows with corpus (V16 scaled the timeout; confirm it holds
  at the full real graph in DT10).
