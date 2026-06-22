## What to build

Restructure `docs/MCP-SHEET-BUILDER-GUIDE.md` to clearly frame this as a standalone Layer 8 capability with no dependency on the self-improving loop, ontology, or ColBERT. The current doc mixes in pattern-library and ontology references that obscure this.

## Read first

1. `docs/MCP-SHEET-BUILDER-GUIDE.md` — full file
2. `docs/COMPONENT-MAP.md` — Layer 8 framing
3. `components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/interface.clj`
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — connect to the nREPL MCP server, run the sheet builder analyzer, build a workflow from the MCP tool schemas, and execute it. Capture the workflow output.

## TDD cycle

- **Red:** Doc reads as if MCP Sheet Builder is part of the broader ontology/pattern-library infrastructure. Layer independence not stated. No "quick start from zero" path.
- **Green:** Add standalone framing at top. Clear dep callout (mcp-sheet-builder component only). Verify the quick start works end-to-end.
- **Refactor:** Confirm every code example runs. Remove any references that imply ontology/self-improving loop are required.

## Acceptance criteria

- [ ] Opening: "MCP Sheet Builder is a standalone capability (Layer 8 in COMPONENT-MAP.md). It connects to any MCP server, analyzes tool schemas, and generates executable ORC behavior-tree workflows. No ontology, ColBERT, or self-improving loop required."
- [ ] Dep callout: `mcp-sheet-builder` component only
- [ ] Quick start section: connect to nREPL MCP → analyze tools → generate workflow → build → execute — all in one sequence with verified output
- [ ] Generated workflow shown executed with `sheet/execute` — confirmed `:success`
- [ ] MCP registry pattern (multi-server) shown
- [ ] References to "pattern library" or "ontology context" clearly labeled as optional enhancements, not requirements

## Do NOT touch

Any component source.

## Live QA the orchestrator runs

Start nREPL MCP server. Run the sheet builder against it. Execute the generated workflow with a real input. Confirm `:status :success`.

## Blocked by

Wave 1 complete.

## Handoff note

The doc has 808 lines with excellent depth. This is a framing-and-labeling pass. Do not delete the advanced content (pattern matching, executor generation, custom tool calling). Add the standalone framing and Layer callout, then leave the rest intact.