## What to build

Restructure `docs/SELF-IMPROVING-LOOP.md` to: (1) move the alpha-state honest-assessment table to the top (not buried at the end), (2) absorb the three valuable sections from `FEEDBACK-LOOP.md` before it's archived — the 7-stage conceptual diagram, the batch evaluation operational workflows, and the troubleshooting table, (3) add an "Operations and debugging" section.

## Read first

1. `docs/SELF-IMPROVING-LOOP.md` — full file
2. `docs/FEEDBACK-LOOP.md` — identify and extract: 7-stage diagram (lines 14–58), batch evaluation workflows (lines 459–533), troubleshooting (lines 663–707)
3. `docs/GETTING-STARTED.md` Phase 6 — what was introduced there
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

No — restructure and content migration. No new code claims.

## TDD cycle

- **Red:** Alpha-state table buried at end. Operational workflows only in FEEDBACK-LOOP.md (stale). No "operations" section. `:auto-classify?` vs GEPA orthogonality not explicit.
- **Green:** Move alpha-state table near top. Migrate 3 sections from FEEDBACK-LOOP.md. Add orthogonality note.
- **Refactor:** Update any "Future Automated Loop" language from FEEDBACK-LOOP.md to reflect shipped features. Verify "solid today" claims against current source.

## Acceptance criteria

- [ ] "Honest status today" section (solid vs rough, with evidence) appears EARLY — within the first third of the doc, not at the end
- [ ] 7-stage conceptual diagram from FEEDBACK-LOOP.md migrated here (updated to include RLM and `:auto-classify?` in the pipeline)
- [ ] Batch evaluation operational workflows migrated here
- [ ] Troubleshooting table migrated here
- [ ] "Operations and debugging" section exists as a distinct named section
- [ ] `:auto-classify?` opt-in boundary clearly stated: exactly what fires when you turn it on (R-Inject, classification, reranker, corpus prepend, judges, Living Description evolution)
- [ ] GEPA vs `:auto-classify?` orthogonality note: "GEPA improves static instructions; `:auto-classify?` shapes dynamic RLM tree design. Neither requires the other."
- [ ] Updated FEEDBACK-LOOP.md content: "Future Automated Loop" sections rewritten to reflect shipped behavior
- [ ] Cross-reference to LIVING-DESCRIPTIONS.md for consolidation architecture detail

## Do NOT touch

`docs/LIVING-DESCRIPTIONS.md` (DOC-15's scope). `docs/FEEDBACK-LOOP.md` itself (DOC-18 archives it after this migration). Any component source.

## Live QA the orchestrator runs

Confirm the migrated 7-stage diagram describes the current system (not the pre-RLM version). For each "solid today" item in the alpha-state table: find the code or test that proves it. For each "rough today" item: find the OOD evidence or known issue.

## Blocked by

Wave 1 complete.

## Handoff note

The FEEDBACK-LOOP.md migration is a content-harvest operation: pull 3 specific sections, update them for current accuracy, embed them here. Do not pull stale "Future" sections that describe features now shipped as if they are still future. The FEEDBACK-LOOP.md file itself will be archived by DOC-18 after DOC-14 is done — do not archive it in this issue.