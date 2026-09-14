# RR-11: Call budget derived from durable reservations; usage counted once

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

A campaign resumed in a fresh process gets a **fresh call budget**, because the counter is process-local — so the
shared-budget guarantee is false across exactly the restart this arc introduces.

Derive the count from durable provider-call reservations instead, keeping the in-process counter as a hot cache rebuilt
on resume. A reservation is appended immediately before each actual provider attempt and consumes one slot even if a
crash leaves the outcome unknown. It is deliberately separate from `EffectClaim`: one logical provider effect may make
multiple physical retry attempts in the same ownership epoch, while the claim CAS correctly permits only one claim for
that logical action and epoch. Campaign reservations carry the current ownership epoch and are rejected when it is
superseded. A checkpoint alone is insufficient: the budget spans a trace family, and a child tree's provider calls live
in their own executions.

Second defect, same area: usage is accumulated on every yield from a cumulative total, so an N-quantum campaign
contributes roughly N·u1 + (N-1)·u2 + … Count once per completed iteration.

## Acceptance criteria

- [x] A campaign resumed in a fresh process continues on its already-spent budget
- [x] The budget counts every physical provider attempt in the whole trace family, including same-epoch retries and provider calls inside generated children
- [x] Usage for an N-quantum campaign equals usage for the same work run in one quantum
- [x] The cache is rebuilt from durable reservations on resume and never diverges from them
- [x] A failed durable reservation read or append fails closed before provider dispatch
- [x] The cost of the family-walk read is measured; the caching strategy follows the measurement

## Spec obligations covered

- `entity-relationship.WorkflowExecution.provider_call_reservations`
- `entity-fields.ProviderCallReservation`
- `rule-success.ProviderCallIsReservedBeforeInvocation`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.1`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.2`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.3`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.4`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.5`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.6`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.7`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.8`
- `rule-failure.ProviderCallIsReservedBeforeInvocation.9`
- `invariant.ProviderCallReservationIdentitiesAreUnique`
- `invariant.ProviderCallReservationsBelongToTheirTraceFamily`
- `invariant.ProviderCallReservationsBelongToTheirNodeExecution`
- `invariant.ProviderCallReservationAttemptsAreNonNegative`
- `invariant.CampaignProviderCallReservationsAreAttributable`
- `invariant.ProviderCallReservationsNeverExceedBudget`

## Test seams

Seam-2 (restart: stop processors, reopen store, restart processors) for the restart case, Seam-3 (durable evidence: event-store reads) for derivation, Seam-1 (public execution via `with-async-test-context`) for the usage arithmetic.

## Blocked by

RR-7.

## Handoff plan

**Handoff is crafted AFTER RR-7 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the provider-call reservation record and its budget-root/invoking-execution linkage
  - the trace-family linkage used to walk from a campaign to its children's reservations

The orchestrator then runs `/propagate` scoped to the obligations above, confirms RED, and seeds the TDD cycle list.

## Verification

DET-E2E-273 exercises the schema-validated command/event/projection path and
observes the durable reservation before provider entry. It covers same-epoch
provider retries, parallel final-slot contention, duplicate delivery, stale
ownership and relationship rejection, outer/inline/generated/deeper descendant
calls, real SQLite stop/reopen automatic recovery after a pre-return crash,
cache eviction and stale-high/stale-low repair, durable read/append failures,
once-counted usage, and exact non-checkpointed compatibility.

The focused namespace passed five consecutive runs (80 tests, 580 assertions)
before the independent inspection added a cross-root identity regression; the
final focused result is 17 tests and 117 assertions. Eight affected namespaces
passed 185 tests and 1,177 assertions. `clojure -M:poly test brick:orc-service`
then passed every namespace in both consuming project contexts with exit 0.
Temporary local Grain overrides were removed after the run.

The required real-SQLite measurement used Mac OS X 26.6 aarch64 and JVM
21.0.11, with 10 warmups, 30 samples, and one store read per sample. For
family/reservation/returned-event shapes 1/1/2, 10/10/20, 100/10/110,
100/100/200, 1,000/10/1,010, and 1,000/1,000/2,000, respectively, measured
p50/p95/p99=max microseconds were 429.292/942.375/1,436;
633.584/763.667/920.750; 442.208/635.417/778.417;
2,465.709/3,002.250/5,005.625; 516.041/742.750/2,497.708; and
10,654.333/12,433.750/13,635.458. Returned reservation density, rather than
family size alone, dominated cost, so the implementation hydrates lazily on the
first attempted call instead of eagerly scanning every recovered campaign.

Independent `/inspect-orc` and `/weed` check mode found no unresolved RR-11
spec/code divergence. `allium check specs` and `allium analyse specs` matched
the characterized baseline exactly: 12 specs, 144 diagnostics (109 information,
35 warning, 0 error), and 0 process findings. Obligation audit: `18 obligations,
18 covered, 0 uncovered`. No generated mock, stub, TODO, or skeleton remains.

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
