# Issue 001: Add normalize-ontology-id and apply to build-from-sources

## Parent

PRD: `docs/prd/2024-06-02-grain-schema-compliance.md`

## What to build

Add a private `normalize-ontology-id` function to the evolutionary builder that converts string ontology-ids to deterministic UUIDs using `java.util.UUID/nameUUIDFromBytes`. Apply this normalization at the entry points (`build-from-sources` and `evolve`) so that all downstream tag creation uses valid UUIDs.

The normalization should:
- Pass through existing UUIDs unchanged
- Convert strings to deterministic UUIDs (same string = same UUID every time)
- Be applied once at entry, not at each tag creation site

This fixes the Grain schema violation where tags like `[:ontology "my-string"]` fail validation because entity-ids must be UUIDs.

## Acceptance criteria

- [ ] Private `normalize-ontology-id` function exists in evolutionary builder
- [ ] Function converts strings to UUIDs deterministically (`(= (normalize "foo") (normalize "foo"))`)
- [ ] Function passes UUIDs through unchanged
- [ ] `build-from-sources` normalizes ontology-id before any tag creation
- [ ] `evolve` normalizes ontology-id before any tag creation
- [ ] Unit test verifies deterministic conversion
- [ ] Unit test verifies UUID passthrough
- [ ] All events emitted have valid `[:ontology uuid]` tags

## Blocked by

None - can start immediately
