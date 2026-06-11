# Issue 002: Add label-field to JSON extraction LLM prompt and schema

## Parent

PRD: `docs/prd/2024-06-02-grain-schema-compliance.md`

## What to build

Extend the JSON ontology extraction pipeline to request a `label-field` from the LLM during entity type discovery. This tells us which JSON field should be used as the human-readable label for each individual.

Changes needed:
1. Update the "discover-entity-types" LLM prompt to request `label-field` in the output JSON
2. Update the blackboard schema to include `[:label-field {:optional true} :string]` in the entity-types vector schema
3. The LLM example output should show the new field

The `label-field` is optional because the LLM may not always identify one, and we have fallback behavior (handled in Issue 003).

## Acceptance criteria

- [ ] LLM prompt for "discover-entity-types" requests `label-field` property
- [ ] Prompt includes example showing `label-field` in output JSON
- [ ] Blackboard schema for entity-types includes `[:label-field {:optional true} :string]`
- [ ] Existing JSON extraction still works (backward compatible prompt)
- [ ] LLM response with `label-field` passes schema validation

## Blocked by

None - can start immediately (parallel with Issue 001)
