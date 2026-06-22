## What to build

Restructure `docs/RLM-GUIDE.md` so that recursive-is-default and the composition pattern (`:repl-researcher` inside a larger tree, not just as the root) are the first things a reader sees. The current guide opens with Phase 1/Phase 2 mechanics before orienting the reader on when and why to use RLM.

## Read first

1. `docs/RLM-GUIDE.md` — full file
2. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` line 2176 — recursive-default code
3. `docs/GETTING-STARTED.md` Phase 6 — what was introduced there (don't duplicate)
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run a `:repl-researcher` node inside a larger tree (pre-process llm → repl-researcher → post-process llm pattern). Capture the output confirming the researcher's writes reach the parent blackboard.

## TDD cycle

- **Red:** Guide opens with Phase 1/Phase 2 mechanics. Recursive-default is buried in a configuration table. Terminal-deprecation is a footnote. Composition pattern (researcher inside a tree) is secondary.
- **Green:** Restructure opening: (1) when to use RLM → (2) recursive is the default, terminal is deprecated → (3) composition pattern first → (4) configuration reference.
- **Refactor:** Verify `executor.clj:2176` matches description. Verify `mint-behavior!` sandbox primitive is in scope when recursive=true. Verify `:auto-classify?` section clearly distinguishes it from GEPA.

## Acceptance criteria

- [ ] Opening sentence: "Recursive mode is the default. Every `:repl-researcher` node runs recursively unless you explicitly set `:rlm {:recursive? false}`."
- [ ] Source citation: `executor.clj line 2176: recursive-mode? (not= false (:recursive? (:rlm node)))`
- [ ] Terminal-deprecation noted prominently with evidence from PRD: "preserved as escape hatch; will be removed after bench migration"
- [ ] Composition pattern shown FIRST (pre-process → :repl-researcher → post-process), before root-only examples
- [ ] Phase 1 sandbox primitives listed plainly; drill-down primitives (`tree-detail`, `tree-failures`) noted as recursive-only
- [ ] `:auto-classify?` section clearly labeled "for RLM tree designers — NOT for static `orc/llm` nodes (see GEPA-GUIDE.md for static instruction optimization)"
- [ ] Common pitfalls section reviewed for accuracy — SCI sandbox footguns still relevant?
- [ ] Cross-reference to GETTING-STARTED Phase 6 at the top: "For a progressive introduction to RLM see Phase 6 of GETTING-STARTED.md"

## Do NOT touch

`docs/GETTING-STARTED.md`. Any component source.

## Live QA the orchestrator runs

Run the composition example (pre-process llm → repl-researcher → post-process llm). Verify it returns `:success` and the researcher's declared `:writes` keys appear in the final `:outputs`. Read `executor.clj:2176` verbatim — confirm matches doc. Run `(emit-tree! ...)` sandbox primitive — confirm it works in recursive mode.

## Blocked by

Wave 1 complete.

## Handoff note

The guide is 817+ lines and covers a lot of important ground. Do not shorten it by removing content. The restructuring is about opening orientation and section order, not content reduction. The Phase 2 tree DSL node types section and sandbox primitives table are valuable reference and should stay.