# RR-16: The live stream becomes a projection of the durable record

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Two parallel accounts of a campaign exist and neither survives what we are building: ephemeral emits that store
nothing and no-op without a subscriber, and durable events emitted only at the very end. Mid-campaign there is nothing
durable to look at, and after a restart the live view is gone entirely.

They have already drifted — one event's documentation claims it fires per emitted tree when it fires once per campaign.

Once the iteration record exists, the durable store already contains an ordered account of a running campaign. Make the
live stream a **projection** of it, so what an observer watches and what the system reads back are the same thing. Retire
the terminal-only iterations event; it has no production consumer. Link lineage on the replay path too, or the projection
has a hole exactly where a restart happened.

Progress finer than one iteration stays ephemeral — a preview nothing is entitled to rely on.

Reconciliation finding: the terminal aggregate is no longer wholly
consumer-free. Trace assembly retains it as the compatibility source for an
explicitly non-checkpointed researcher, because that path does not write
per-attempt records. "Retire" therefore means stop producing and consuming it
on checkpointed/default campaigns, and document it as a compatibility-only
legacy event. Removing it from explicit opt-out would erase trace history and
contradict RR-15; changing that contract requires a separate ratified migration.

## Acceptance criteria

- [x] A subscriber watching a campaign sees iterations derived from durable records
- [x] What was watched live matches what is read back afterwards, asserted by comparison
- [x] Watching survives a restart
- [x] A rejoined child appears in the lineage
- [x] The terminal-only iterations event is retired from checkpointed/default campaigns and documented as compatibility-only for explicit opt-out
- [x] Sub-iteration progress remains ephemeral and is documented as non-authoritative

## Spec obligations covered

- `entity-fields.CampaignIteration`
- `invariant.OneIterationRecordPerAttempt`

## Test seams

Seam-1 (public execution via `with-async-test-context`) with a live subscriber, Seam-2 (restart: stop processors, reopen store, restart processors) for survival across restart, Seam-3 (durable evidence: event-store reads) for equivalence.

## Blocked by

RR-5.

## Handoff plan

**Handoff is crafted AFTER RR-5 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the iteration record's read API and ordering guarantees
  - the record's field names, which the projection maps onto stream envelopes

The orchestrator then runs `/propagate` scoped to the obligations above, confirms RED, and seeds the TDD cycle list.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions.
  Reproduce -> minimize -> fix the actual cause. Don't blame the network or the model — the cause is in the code or
  the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can
  fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red -> green -> refactor, one test at a time.** Vertical tracer-bullet slices, never
  horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive
  refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, network, storage)
  as capabilities that **default to the real impl and are faked in tests**.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing. Then
  turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's
  "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

Standing rules for this arc:

- **Never weaken a generated test to make it pass.** A `/propagate`-generated test is contract. If it is wrong, the
  spec is wrong: report it, the orchestrator `/tend`s and re-propagates.
- **A generated test green before you implement is a finding**, not success — already-covered or vacuous. Report it.
- **Default-path behaviour must not change** until RR-15 flips the default. Every slice before it keeps the
  non-checkpointed path byte-identical.

## Do NOT touch

- `specs/*.allium` — report divergences with a proposed classification (spec bug / code bug / aspirational design /
  intentional gap); never edit. The orchestrator is the only spec writer.
- Any slice not named in this brief. If you find a defect outside your slice, report it; do not widen the diff.
- `docs/prd/`, `docs/adr/`, the grill log — read-only inputs.
- Other worktrees under `~/Desktop/Code/orc*` — other work is live in them.

## Report back

- The `/propagate` coverage line verbatim: `N obligations, M covered, K uncovered`, with a reason for every
  uncovered one (infrastructure gap / unmappable / out of slice). No silent caps.
- Any propagate-emitted mock, stub or TODO skeleton, declared explicitly as a tracked gap.
- Proposed divergence classifications for anything where spec and code disagree.
- What you could NOT verify, stated plainly.

## Orchestrator gates (run after you report)

`/inspect-orc` against this slice: re-run the proof independently, re-read the code, try to break the claims, plus the
three ORC-specific gates — spec-conformance (`allium check` / `analyse` error-free by **severity count** on touched
specs), `/weed` check-mode with classified divergences, and the obligation audit (the coverage line above must survive
into the slice report). Allium's internal verify is the CLAIM; `/inspect-orc` is the falsification.

## Implementation and verification record

The live hub now tails `:rlm/researcher-iteration-recorded` and exports one
schema-validated `:rlm-iteration-recorded` envelope carrying the exact durable
identity and record payload. Grain's durable topics share one sliding tap
channel and forwarding loop, so a later root terminal publication cannot
overtake an earlier iteration publication and close the stream first.
Sub-iteration code, sandbox, Phase-2 and token activity remains ephemeral.

Recovery now relinks a completed generated child from the durable result's
`:trace-id` before replay returns. Default/checkpointed terminal completion no
longer writes the redundant `:rlm/researcher-iterations` aggregate; explicit
`:checkpointed? false` retains it as the trace compatibility source because
that mode has no per-attempt records.

The propagated cycles established:

- missing durable iteration envelopes, then a missing exported envelope schema,
  as separate REDs before Cycle 1 became green;
- a real SQLite close/reopen and fresh pre-resume subscription, green on its
  first run and classified as already covered by the general durable tap;
- a missing rejoined-child lineage RED, fixed at the completed Phase-2 replay
  branch;
- a checkpointed duplicate-aggregate RED while both checkpointed and explicit
  opt-out traces already retained iterations `[1 2]`;
- a deterministic cross-type ordering RED where a later root terminal closed
  the stream before an earlier delayed child iteration. One shared tap made the
  durable publication order structural.

Independent focused verification passes 5 tests and 31 assertions. The
streaming regression aggregate passes 17 tests and 144 assertions, and the
checkpoint/recovery/effect-claim/trace aggregate passes 95 tests and 845
assertions. Allium remains at the characterized 12-spec baseline of 115
information diagnostics, 35 warnings and 0 errors; analyse reports no findings.
`/weed` found no remaining RR-16 code/spec divergence after clarifying the
explicit opt-out compatibility scope. Coverage is `2 obligations, 2 covered, 0
uncovered`. No generated mock, stub, TODO or skeleton remains.

Inspection initially appeared to expose an RR-7 provider-identity defect, but a
dedicated minimise cycle proved the implementation correct and the replay
harness unfaithful. The original execution's command registry contributed the
registered mint contract to the canonical provider module and inputs; the
manual replay omitted that registry and therefore described a different
logical action. The lineage test now replays the same command registry and has
removed the legacy provider-result mirror that masked the mismatch. The
corrected lineage and real recovered-worker tests are green without a
production or Allium change.

Harness mistakes are retained in the record: one parent test-var command forced
exit zero and was discarded; the first ordering harness released at tap entry
rather than terminal delivery and was corrected before the real RED; one agent
used a mistyped local SQLite coordinate before rerunning correctly; and one
combined rerun named a nonexistent namespace and was stopped. None of those
commands contributes to the green counts above.

The complete `brick:orc-service` verification passed both consuming projects
with exit 0 in 72 minutes 52 seconds when run with
`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`. Two otherwise unmodified macOS
runs aborted with exit 134 in AppKit registration before Clojure could finish;
the crash report's faulting main-thread frames were `_RegisterApplication` and
`JRSAppKitAWT registerAWTAppWithOptions`. The deterministic MCP-tools namespace,
which loads the PDF/image path that reaches AWT, passed directly and in the
headless full run with 10 tests and 74 assertions. This distinguishes a local
GUI-harness abort from a test failure; the ordinary non-headless command is not
claimed green. Earlier LMDB and terminal-wrapper hypotheses were falsified, and
a perceived hang was measurement error caused by waiting on command approval
while the suite continued to advance. The final run included RR-16 itself at 5
tests and 31 assertions, recursive RLM at 54/234, judges at 18/42, researcher
effect claims at 33/315, and map-each recovery at 7/77, all green.
