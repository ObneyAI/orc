# ORC Agent Instructions

This is the canonical instruction entry point for every coding agent working in
this repository. Agent-specific files should point here and contain only
environment-specific additions. If another instruction file conflicts with
this one, stop and surface the conflict rather than silently choosing one.

## Project

ORC is a behavior-tree workflow execution library built on the Grain
event-sourcing framework. Its top namespace is `ai.obney.orc`. It is a library,
not an application: consumers provide their own event store, cache, web server,
authentication, and deployment configuration.

Before designing, changing, or verifying a workflow, read
[`docs/ORC-PRINCIPLES.md`](docs/ORC-PRINCIPLES.md). In particular, preserve the
events-first model, use `:delegate` as the composition seam, put deterministic
structure around LLM work, and verify projections as well as returned values.

## Allium Is the Default Behavioral Workflow

The specifications in [`specs/`](specs/) are the durable behavioral source of
truth. For work that adds, changes, documents, or investigates observable
behavior, use the local Allium skills in [`.agents/skills/`](.agents/skills/).
Do not treat specification work as optional cleanup after implementation.

Route work as follows:

- New behavior or unclear requirements: use `elicit`.
- Existing code without an adequate specification: use `distill`.
- Changes or corrections to an existing specification: use `tend`.
- Tests derived from a specification: use `propagate`.
- Suspected or actual spec/implementation drift: use `weed`.
- An end-to-end behavioral goal: use `allium` to drive the complete loop.

The normal spec-first loop is:

1. Gather intent with `elicit`, or update the relevant spec with `tend`.
2. Run `allium check specs`; resolve new structural diagnostics and explicitly
   track or justify any accepted diagnostics.
3. Use `propagate` to derive integration or end-to-end tests.
4. Run new tests before implementation and confirm that they fail for the
   missing behavior. A test that is already green may be covered or vacuous and
   must be investigated.
5. Implement the behavior through the normal public boundaries.
6. Run the relevant tests, then use `weed` to compare spec and code.
7. Run `allium check specs` and `allium analyse specs` and repeat until the spec,
   tests, and implementation agree with no unresolved questions.

For code-first reconciliation, use `distill`, review intended versus accidental
behavior, then continue with `propagate`, tests, and `weed`. A fresh distillation
must find no unrepresented behavior before broad reconciliation work is done.

Do not weaken, delete, skip, over-mock, or narrow a generated or existing test
merely to make the suite pass. A genuine failure is evidence: determine whether
the specification, implementation, or test translation is wrong and correct
that source. Escalate real product ambiguity instead of encoding a workaround.

Pure implementation refactors, build maintenance, and documentation edits that
cannot change observable behavior do not require inventing an Allium change.
They still require checking whether the work exposes drift in an existing spec.

## Test Obligation Tracking

Record every newly proposed deterministic end-to-end test in
[`docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md`](docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md)
before or while implementing it. Keep items unchecked until the complete
integration-shaped contract has been executed and verified. Do not infer
end-to-end completion from narrower unit coverage.

When behavioral scope or component coverage changes, update
[`specs/COVERAGE.md`](specs/COVERAGE.md). Never silently exclude a production
domain; document and justify exclusions in the ledger.

## Verification

Use the smallest relevant test command while iterating, then broaden verification
in proportion to the change:

```bash
clojure -M:poly test                    # changed bricks
clojure -M:poly test brick:orc-service  # one brick
clojure -M:poly test :all-bricks        # all bricks
allium check specs                      # structural specification checks
allium analyse specs                    # process and invariant analysis
git diff --check                        # patch hygiene
```

The current Allium diagnostic baseline and its interpretation are recorded in
`specs/COVERAGE.md`. Compare results with that ledger, not merely the process
exit code. Do not hide new diagnostics by assuming they are intentional, and do
not present the baseline as a clean check.

Tests are allowed to fail while exposing a concern. Report those failures
plainly and keep the associated checklist item open. Optimize for correct,
observable behavior and faithful specifications—not for a superficially green
suite.

## Grain and Repository Conventions

ORC uses Grain's current CQRS/event-sourcing DSL: v2 processors over the
tenant-scoped v3 event-store protocol.

- `defcommand` validates intent and emits events.
- `defreadmodel` projects events into state.
- `defquery` composes read models and returns data.
- `defprocessor` handles event-driven side effects.
- `defperiodic` emits scheduled triggers.
- `defschemas` registers Malli schemas for commands, queries, and events.

Construct appendable events with `event-store-v3/->event`; the v3 store assigns
their UUIDv7 IDs and timestamps atomically during append. Callers must not supply
`:event/id` or `:event/timestamp`. Every direct v3 event-store read or append
must include `:tenant-id`. Commands and queries exposed through adapters are deny-by-default
and therefore require an explicit `:authorized?` predicate.

Use public component interfaces across Polylith boundaries. Production code is
under `components/{component}/src/ai/obney/orc/`; tests live in the corresponding
`test` tree. Avoid bare event appends: exercise commands, schemas, events,
processors, projections, and public queries as appropriate to the behavior.

Useful focused documentation:

- [`docs/RLM-GUIDE.md`](docs/RLM-GUIDE.md) for `:repl-researcher` and RLM modes.
- [`docs/STREAMING.md`](docs/STREAMING.md) for ephemeral execution streaming.
- [`docs/SELF-IMPROVING-LOOP.md`](docs/SELF-IMPROVING-LOOP.md) for automatic
  classification and evolution.
- [`development/bench/README.md`](development/bench/README.md) for the RLM
  generalization benchmark.

## Instruction Maintenance

Keep shared rules here. Do not duplicate this document into `CLAUDE.md` or a
tool-specific prompt. When changing this harness, verify that all linked paths
exist, that advertised skills exist locally, and that agent-specific adapters
still point to this file.
