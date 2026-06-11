# PRD: ORC Grain Schema Compliance Fixes

## Problem Statement

The ORC evolutionary ontology builder produces events that fail Grain schema validation in two ways:

1. **Tags use string ontology-ids instead of UUIDs** - Grain's event store schema requires tags to be `[:tuple :keyword :uuid]`, but ORC creates tags like `[:ontology "my-ontology"]` with string IDs.

2. **A-box individuals missing required `:label` field** - The JSON extraction pipeline creates individuals with `{:uri :type :properties}` but the Grain schema for `:evolutionary/abox-extracted` requires `:label` in each individual.

These schema violations cause commands to return anomalies instead of successfully persisting events, breaking downstream consumers like Cambot that rely on ORC for ontology extraction.

## Solution

Fix ORC to produce Grain-compliant events:

1. **Normalize ontology-ids to UUIDs** - Convert string ontology-ids to deterministic UUIDs using `UUID/nameUUIDFromBytes` at the entry points of the evolutionary builder.

2. **Add label-field to JSON extraction** - Extend the LLM prompt to explicitly request which field should be used as the label, then use that in A-box construction with a graceful fallback chain.

## User Stories

1. As an ORC consumer, I want the evolutionary builder to accept string ontology-ids, so that I can use human-readable identifiers without worrying about UUID conversion.

2. As an ORC consumer, I want ontology-id conversion to be deterministic, so that the same string always produces the same UUID for consistent querying.

3. As an ORC consumer, I want events to pass Grain schema validation, so that commands complete successfully without returning anomalies.

4. As an ORC consumer, I want JSON-extracted individuals to have meaningful labels, so that the ontology is human-readable and useful for downstream processing.

5. As an ORC consumer, I want label extraction to degrade gracefully, so that extraction never fails even if the LLM doesn't identify a label field.

6. As an ORC developer, I want the UUID normalization to be centralized, so that all tag creation points use consistent IDs.

7. As an ORC developer, I want unit tests for the normalization function, so that I can verify UUID generation is deterministic.

8. As an ORC developer, I want integration tests that verify full schema compliance, so that regressions are caught before merge.

9. As a Cambot developer, I want to use ORC's evolutionary builder via commands, so that I follow proper Grain patterns (commands emit events).

10. As a Cambot developer, I want the ORC integration test to pass, so that I can confidently use ORC for ontology extraction from turnover data.

11. As an ORC consumer using CSV extraction, I want my existing working extraction to remain unaffected, so that this fix doesn't introduce regressions.

12. As an ORC consumer using SQL extraction, I want my existing working extraction to remain unaffected, so that this fix doesn't introduce regressions.

13. As an LLM prompt engineer, I want the JSON extraction prompt to clearly request label-field, so that the LLM understands what's being asked.

14. As an ORC developer, I want the blackboard schema updated to include label-field, so that the pipeline validates correctly.

## Implementation Decisions

### UUID Normalization

- Add a private `normalize-ontology-id` function in the evolutionary builder module
- Use `java.util.UUID/nameUUIDFromBytes` for deterministic string-to-UUID conversion
- Apply normalization once at entry points (`build-from-sources`, `evolve`), not at each tag creation site
- The normalized UUID flows through the entire pipeline after initial conversion

### Label Field Extraction

- Extend the LLM prompt for "discover-entity-types" to request a `label-field` property
- Update the blackboard schema to include `[:label-field {:optional true} :string]` in entity-types
- Update `build-abox-fn` to extract labels using a fallback chain:
  1. Use the LLM-specified `label-field` if present
  2. Fall back to first `source-field` if label-field is missing
  3. Fall back to synthetic label (`{type}-{index}`) as last resort
- Handle both keyword and string keys when extracting from JSON items

### Scope Limitation

- Only `json_ontology.clj` needs the A-box label fix
- CSV and SQL extractors already include `:label` in individuals (verified)
- Text extraction has no A-box (concepts only)

### Schema Updates

- Add `:label-field` to the entity-types vector schema in the JSON ontology blackboard
- No changes needed to Grain schemas (they're already correct - ORC was producing non-compliant data)

## Testing Decisions

### What Makes a Good Test

Tests should verify behavior through public interfaces, not implementation details. The tests should:
- Call entry points (`build-from-sources`, command processor)
- Verify output structure matches Grain schemas
- Not mock internal functions or test private helpers directly

### Modules to Test

1. **UUID Normalization** (unit test)
   - Verify string → UUID conversion is deterministic
   - Verify UUIDs pass through unchanged
   - Verify the resulting UUID is valid for Grain tags

2. **JSON A-box Label Extraction** (unit test)
   - Verify `build-abox-fn` output includes `:label` field
   - Verify fallback chain works (label-field → source-field → synthetic)

3. **Full Pipeline Integration** (integration test)
   - Run JSON extraction through command processor
   - Verify all emitted events pass Grain schema validation
   - Verify no anomalies returned

### Prior Art

- Existing tests in `json_ontology_test.clj` for structure analysis
- Existing tests in `evolutionary_builder_test.clj` for pipeline behavior
- Cambot's `ontology_seed_test.clj` provides consumer integration test pattern

## Out of Scope

- Changes to Grain schema definitions (the schemas are correct; ORC was wrong)
- Fixes to CSV, SQL, or text extractors (they already work correctly)
- Backward compatibility with existing string-based ontology-id queries (no existing users)
- ColBERT or embedding indexing changes
- Changes to the `evolve` incremental mode beyond UUID normalization

## Further Notes

- This fix unblocks Cambot's ORC integration test (`seed-via-orc-and-query-person`)
- The deterministic UUID approach means `"transcript-corrections"` always maps to the same UUID, preserving query semantics
- The LLM prompt change is additive (requesting one more field) and shouldn't affect existing extraction quality
