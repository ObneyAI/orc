## What to build

Restructure `docs/COLBERT-INTEGRATION.md` so that "optional upgrade" is the first thing a reader sees. The current doc reads as documentation for a required integration. The graceful-degradation path (ColBERT absent → 2-signal RRF still works) must be immediately visible.

## Read first

1. `docs/COLBERT-INTEGRATION.md` — full file
2. `components/ontology/src/ai/obney/orc/ontology/core/retrieval.clj` — `resolve-colbert-search-fn` graceful nil pattern
3. `docs/COMPONENT-MAP.md` — Layer 5 framing (optional upgrade)
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run `ontology/hybrid-search` WITHOUT ColBERT present. Confirm it works on 2 signals (graph + embedding) and returns results. Capture output.

## TDD cycle

- **Red:** Doc opens with the three-signal architecture without first establishing that ColBERT is optional. Graceful degradation not mentioned until deep in the doc.
- **Green:** Add "optional upgrade" framing at the top. Show 2-signal vs 3-signal. Verify graceful degradation.
- **Refactor:** Verify `resolve-colbert-search-fn` from `retrieval.clj` matches the graceful-nil description.

## Acceptance criteria

- [ ] Opening: "ColBERT is an optional third retrieval signal (Layer 5). ORC's ontology component works fully without it using graph BFS + DJL embeddings. Add ColBERT when you need late-interaction token-level matching for larger corpora."
- [ ] 2-signal vs 3-signal example: `hybrid-search` with `:signals #{:graph :embedding}` (no ColBERT) vs `#{:graph :embedding :colbert}` (with ColBERT) — both verified
- [ ] Graceful degradation shown with captured output: no ColBERT, no exception, 2-signal results returned
- [ ] Python requirement clearly stated upfront: requires `.venv-colbert` Python environment
- [ ] "When to add ColBERT" guidance: when 2-signal retrieval is insufficient for corpus size and semantic complexity
- [ ] Dynamic resolution note: ColBERT is resolved via `(find-ns 'ai.obney.orc.colbert.interface)` — graceful nil when component absent from classpath

## Do NOT touch

Any component source. `docs/COMPONENT-MAP.md`.

## Live QA the orchestrator runs

Run `hybrid-search` with ColBERT component absent. Confirm returns results from graph + embedding signals. Confirm no exception thrown. Read `retrieval.clj` `resolve-colbert-search-fn` — confirm it returns `nil` gracefully when colbert ns not present.

## Blocked by

Wave 1 complete.

## Handoff note

The existing doc has good technical detail about ColBERT capabilities (late-interaction, PLAID indexing, reranking, training). Preserve all of that — it's after the optional-upgrade framing. Only the opening needs restructuring.