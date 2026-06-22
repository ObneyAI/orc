## What to build

Restructure `docs/SELF-LEARNING-MANUAL.md` → `docs/PATTERN-RECORDING.md`. This is the manual control path: explicitly recording patterns via commands and injecting them into future LLM prompts. Add a clear "when to use this vs SELF-IMPROVING-LOOP.md" decision callout at the top. The doc is valuable standalone but needs to stop being named as if it's the manual/reference for the entire self-learning system.

## Read first

1. `docs/SELF-LEARNING-MANUAL.md` — full file
2. `docs/SELF-IMPROVING-LOOP.md` — DOC-14 output (the automated path to cross-reference)
3. `components/ontology/src/ai/obney/orc/ontology/interface.clj` — `find-self-patterns`, `build-actionable-context`
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run `(ontology/record-tree-strength ctx {:tree-id ... :pattern-uri "success:ExamplePattern" :confidence 0.9 ...})`. Then run `(ontology/find-self-patterns ctx tree-id)`. Then run `(ontology/build-actionable-context ctx tree-id {:domain-description "..."})`. Capture all three outputs.

## TDD cycle

- **Red:** Doc named "SELF-LEARNING-MANUAL" which reads as the documentation manual for the self-learning system, not as the guide to the manual-control path. No "when to use this" decision callout.
- **Green:** Rename. Add decision callout. Move the simpler pattern (record → retrieve → inject) to the top before the domain-specific examples.
- **Refactor:** Verify all 3 API calls produce the correct output shape. Confirm the domain-agnostic framing comes before the specific examples.

## Acceptance criteria

- [ ] File renamed to `docs/PATTERN-RECORDING.md` (git mv)
- [ ] Decision callout at top: "**When to use this:** You want to explicitly record what your workflow has learned and inject it into future prompts — and you want direct control over what gets recorded. If you want the system to observe and learn automatically, see [SELF-IMPROVING-LOOP.md](SELF-IMPROVING-LOOP.md)."
- [ ] Simpler pattern shown first: record-tree-strength → find-self-patterns → build-actionable-context → inject string into `:instruction`
- [ ] Domain-agnostic framing before the drone/legal/sales examples
- [ ] All 3 API calls verified with captured REPL output
- [ ] Cross-reference to SELF-IMPROVING-LOOP.md for the automated path
- [ ] `grep -r "SELF-LEARNING-MANUAL" docs/` returns only this issue file
- [ ] All references updated to `docs/PATTERN-RECORDING.md`

## Do NOT touch

The drone/legal/sales domain examples — they are practical and valuable, just need the domain-agnostic framing first. Any component source.

## Live QA the orchestrator runs

Run all 3 API examples in sequence. Confirm `build-actionable-context` output is a plain string. Confirm `find-self-patterns` returns the rich `:context-conditions` shape. Confirm recording a pattern then retrieving it works end-to-end.

## Blocked by

Wave 1 complete, DOC-14 (SELF-IMPROVING-LOOP.md must be complete so the cross-reference can point to it accurately).

## Handoff note

Use `git mv docs/SELF-LEARNING-MANUAL.md docs/PATTERN-RECORDING.md` to preserve git history. The name "PATTERN-RECORDING.md" was chosen deliberately to signal "I record patterns" vs the old name which implied "I am the manual for the self-learning system."