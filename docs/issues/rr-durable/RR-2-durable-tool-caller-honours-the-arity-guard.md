# RR-2: Durable tool caller honours the arity guard

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Preserve the existing two-argument host contract where no checkpointed effect
identity must cross the boundary, and classify callable arity without invoking
the host. A context-aware three-argument caller receives tool context.

RR-7 subsequently ratified a narrower safety boundary: an effectful tool inside
checkpointed execution must use the three-argument caller so ORC can supply the
stable idempotency key. The earlier goal of making checkpointing invisible to a
two-argument effectful caller is superseded at that boundary; compatibility
remains outside it.

## Acceptance criteria

- [x] A host supplying a two-argument tool caller remains supported outside the checkpointed effect boundary
- [x] A checkpointed host supplying a three-argument tool caller receives the tool context and stable idempotency key
- [x] A missing or two-argument checkpointed effectful caller is rejected before model dispatch, effect claim, or tool invocation, with an actionable arity/idempotency error
- [x] A checkpointed campaign rejects an effectful tool that has not declared checkpoint safety before configuration can dispatch its effect

Inspection evidence: the initial non-invoking arity classifier was falsified by
a Var-backed two-argument host, because `clojure.lang.Var` advertises every IFn
arity. The corrected implementation dereferences a bound Var once so preflight
and dispatch inspect the same callable. Independent tests covered two-, three-,
dual-, variadic-, incompatible-, and Var-backed callables. The complete
recursive namespace passed 54 tests with 229 assertions, and the existing
non-checkpointed tool-caller namespace passed 1 test with 8 assertions, with no
failures or errors. Allium remained at the documented 0-error baseline; weed
classified the mismatch as a code bug and found no specification divergence.
Coverage: `0 obligations, 0 covered, 0 uncovered`.

RR-7 contract-closure evidence: the new public tracer first failed 5/5
assertions because the provider and tool both ran and durable claims were
written. After the preflight fence, it passes while provider calls, tool calls,
raw claims, and projected claims all remain zero. An adversarial follow-up RED
proved that an absent caller previously let the workflow report success after
one provider call and two durable claims; the same preflight now rejects that
configuration. The focused RR-7 namespace passes 21 tests / 154 assertions and
all eight affected namespaces pass 168 / 836.

## Spec obligations covered

None. This slice repairs behaviour that predates the campaign model; its proof is the existing suite plus the acceptance criteria above.

## Test seams

Seam-1 (public execution via `with-async-test-context`) with a fake tool caller of each arity.

## Blocked by

None — can start immediately.

## Handoff plan

**Pre-writable now.** No dependency on an upstream slice's produced API, so the handoff brief can be written before the arc starts. The orchestrator runs `/propagate` scoped to the obligations above, confirms the generated tests are RED, and seeds the TDD cycle list with them.

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
