# Issue 004: Integration test - verify full pipeline produces Grain-compliant events

## Parent

PRD: `docs/prd/2024-06-02-grain-schema-compliance.md`

## What to build

Create an integration test that verifies the complete JSON extraction pipeline produces events that pass Grain schema validation. This is the end-to-end verification that both fixes (UUID normalization and A-box labels) work together correctly.

The test should:
1. Provide sample JSON data with various field structures
2. Run through the command processor (`:ontology/build-from-sources`)
3. Verify all emitted events pass Grain schema validation
4. Specifically check:
   - Tags use UUIDs (not strings)
   - A-box individuals have `:label` fields
   - No anomalies returned from command processing

This test proves the fixes work and enables Cambot's blocked integration test to be unblocked.

## Acceptance criteria

- [ ] Integration test exists in ORC's test suite
- [ ] Test uses command processor (not direct builder calls)
- [ ] Test provides realistic JSON input data
- [ ] Test verifies no anomalies returned
- [ ] Test verifies all events have valid tags with UUID entity-ids
- [ ] Test verifies all A-box individuals have `:label` field
- [ ] Test passes with both string and UUID ontology-ids as input
- [ ] Cambot's `seed-via-orc-and-query-person` test can be unblocked after this passes

## Blocked by

- Issue 001 (UUID normalization)
- Issue 003 (A-box label extraction)
