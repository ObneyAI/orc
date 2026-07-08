## What to build

Restructure `docs/ONTOLOGY.md` to open with the substrate/application reframing. The current doc frames the system as "a three-layer semantic knowledge system" — this is the self-improving loop's application of the substrate, not the system itself. The general-purpose substrate section must come first.

## Read first

1. `docs/ONTOLOGY.md` — full file
2. `/Users/darylroberts/Desktop/Code/orc/docs/ARCHITECTURE-ONTOLOGY.md` — the canonical substrate/application framing from the feature branch
3. `docs/GETTING-STARTED.md` Phase 5 — what was introduced there (don't duplicate)
4. `components/ontology/src/ai/obney/orc/ontology/core/retrieval.clj` — BFS scoping issue evidence (`expand-concept-neighborhood`)
5. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run `ontology/seed-baseline-corpus!` then `ontology/get-description` for one entry. Run `ontology/embed-text "contract clause"`. Run `ontology/semantic-search-concepts`. Capture output and verify against doc.

## TDD cycle

- **Red:** Doc opens "The ontology component provides a three-layer semantic knowledge system." This is the self-improving loop application, not the substrate.
- **Green:** Restructure: (1) substrate definition → (2) general-memory use cases → (3) evolutionary builder as general write adapter → (4) three-layer taxonomy as self-improving loop's application → (5) full API reference.
- **Refactor:** Verify BFS scoping known issue from `retrieval.clj`. Verify `ontology-id` isolation behavior from source.

## Acceptance criteria

- [ ] Opening: "The ontology component provides a general-purpose event-sourced concept graph. The failure/success/problem taxonomy is one application of this substrate — the self-improving loop's seed corpus."
- [ ] General-memory use cases shown: documents, user memory, any domain knowledge
- [ ] Evolutionary builder framed as "the general write adapter" — discovers ontology structure from CSV/JSON/SQL/text. NOT the system itself.
- [ ] `ontology-id` isolation explained: multiple separate graphs coexist in one event store; each is independently queryable
- [ ] **Known issue callout**: BFS scoping — `expand-concept-neighborhood` (graph BFS) currently walks all sections regardless of `ontology-id`. Use `hybrid-search` with explicit `:signals` until this is fixed. Verified from `retrieval.clj`.
- [ ] Three-layer taxonomy (failure/success/problem) positioned correctly: "the self-improving loop's built-in application; loaded via `initialize-static-ontology`"
- [ ] `tree-fingerprint is structural` note: fingerprint collapses `:instruction`; domain-specific specialist emergence needs `:tree-class` (not yet auto-assigned)
- [ ] "Coming soon" section: BFS scoping fix; subbehavior-specialist evolutionary builder

## Do NOT touch

`docs/GETTING-STARTED.md`. Any component source.

## Live QA the orchestrator runs

Read `components/ontology/src/.../core/retrieval.clj` — find `expand-concept-neighborhood` and confirm it does NOT filter by `ontology-id`. This is the evidence for the known-issue callout. Run `ontology/embed-text` — confirm DJL, not Python. Run `ontology/seed-baseline-corpus!` — confirm it completes.

## Blocked by

Wave 1 complete.

## Handoff note

The current ONTOLOGY.md has very detailed API reference content (1400+ lines) — much of it accurate. Do not delete API content. Restructure the opening framing, add the substrate/application section, add the known-issue callouts, and update the evolutionary builder framing. The detailed API reference sections can stay as-is if they're accurate.