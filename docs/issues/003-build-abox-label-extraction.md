# Issue 003: Update build-abox-fn to extract labels with fallback chain

## Parent

PRD: `docs/prd/2024-06-02-grain-schema-compliance.md`

## What to build

Update the `build-abox-fn` in the JSON ontology sheet to include a `:label` field in each individual. The label should be extracted using a graceful fallback chain:

1. **First**: Use the LLM-specified `label-field` from the entity type
2. **Second**: Fall back to the first `source-field` if label-field is missing
3. **Third**: Fall back to synthetic label (`"{type}-{index}"`) as last resort

The function must handle both keyword and string keys when extracting from JSON items (e.g., both `:name` and `"name"`).

This fixes the Grain schema violation where `:evolutionary/abox-extracted` events require `:label` in each individual but the current implementation omits it.

## Acceptance criteria

- [ ] `build-abox-fn` output includes `:label` in every individual
- [ ] Labels extracted using LLM-specified `label-field` when present
- [ ] Falls back to first `source-field` when `label-field` is nil
- [ ] Falls back to synthetic `"{type}-{idx}"` when no field extraction works
- [ ] Handles both keyword keys (`:name`) and string keys (`"name"`)
- [ ] Unit test verifies label extraction with label-field present
- [ ] Unit test verifies fallback to source-field
- [ ] Unit test verifies fallback to synthetic label
- [ ] A-box individuals pass Grain schema validation

## Blocked by

- Issue 002 (needs label-field in schema before it can be used)
