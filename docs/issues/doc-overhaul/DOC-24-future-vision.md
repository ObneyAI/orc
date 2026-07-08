## What to build

Review `docs/FUTURE-VISION.md`. Audit every "What's NOT Built Yet" item against the current codebase. Move visibly stale sections to `docs/archived/FUTURE-VISION-2025.md`. Preserve the forward-looking vision sections that remain accurate (subbehavior specialists, substrate-as-product framing, promotion engine).

## Read first

1. `docs/FUTURE-VISION.md` — full file (1680 lines)
2. `docs/COMPONENT-MAP.md` — DOC-01 output (what IS shipped)
3. `docs/GETTING-STARTED.md` — what was used in the guide
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

No — audit and restructure only.

## TDD cycle

- **Red:** "What's NOT Built Yet" section (lines ~40-48) lists GEPA optimization loop, rolling average monitoring, tree self-description — all of which are now shipped. This is a version control / changelog problem not a content problem.
- **Green:** For each item in the "Not Built Yet" section: verify from source (shipped or not). Tag items. Archive the stale dated content.
- **Refactor:** Confirm remaining "coming soon" items are actually not in main branch.

## Acceptance criteria

- [ ] Every "What's NOT Built Yet" item audited: search codebase to confirm shipped or not-shipped
- [ ] Stale items that are now shipped either updated to "shipped — see [doc]" or moved to `docs/archived/FUTURE-VISION-2025.md`
- [ ] Reserved vision sections preserved in the main doc: subbehavior-specialist evolutionary builder, substrate-as-product framing, tree-class auto-assignment, metric-driven harvest (vs volitional minting), delegate-to-stored promotion engine
- [ ] Current "coming soon" items (as of this issue) accurately listed: pluggable judge scales, EB4-EB12 subbehavior specialists, BFS scoping fix, terminal RLM full removal, `:tree-class` auto-assignment
- [ ] Date-tagged sections that have clearly outlived their relevance moved to archive
- [ ] No claim about a "future" feature that is already in the main branch `components/`

## Do NOT touch

Any component source.

## Live QA the orchestrator runs

For each item marked as "now shipped" in the doc: find the file in `components/` that ships it. For each item marked "coming soon": confirm absence in the main branch source.

## Blocked by

Wave 1 complete.

## Handoff note

FUTURE-VISION.md is 1680 lines. Focus the audit on the "What's NOT Built Yet" table and "Current State Assessment" sections — these are the most likely to be stale. The vision themes (rolling metrics, self-description, etc.) may be partly shipped and partly aspirational; tease them apart accurately.