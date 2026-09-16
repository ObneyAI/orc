# RR-25: Documentation truth pass

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Three statements in the codebase are false and would mislead whoever reads them next.

The idempotency key supplied to tools is described as provider deduplication. **No LLM provider supports one** — and the
vendor SDKs ship complete idempotency plumbing with the header name unset, so a reasonable code read concludes support
exists. It does not. The key is real and useful at our own boundary; it must stop being described as anything else.

The self-improving loop documentation says the promotion mechanism is not shipped on this branch. It is, and it is live.

One event's documentation claims it fires per emitted tree. It fires once per campaign.

## Acceptance criteria

- [x] No code comment, doc or spec describes the idempotency key as provider deduplication
- [x] The key's real purpose — recognising an already-dispatched call on resume — is stated where it is used
- [x] The promotion mechanism is documented as shipped
- [x] The event's documented cadence matches its actual cadence
- [x] ADR 0004 is referenced from the places that previously carried the false claim

## Spec obligations covered

None. This slice repairs behaviour that predates the campaign model; its proof is the existing suite plus the acceptance criteria above.

## Verification

The documentation truth pass rewrote every live sentence that the arc had made false,
without touching the historical records that must keep saying what was believed at the
time. No live code comment, docstring or doc describes the idempotency key as provider
deduplication: the two provider-call sites in the executor now state that the key lets a
resumed campaign recognise an already-dispatched call at our own boundary, that no LLM
provider reads or honours it, and cite ADR 0004. `docs/SELF-IMPROVING-LOOP.md` and
`docs/GETTING-STARTED.md` describe harvest as live, recurrence as counted at the campaign's
verdict occurrence (the first terminal completion in durable order, whatever its status,
blocked included; cancellation records none), coherence over winning shapes as report-only,
and classification as once per campaign. `docs/RLM-GUIDE.md` describes worked patterns as
offered whole with their declared read/write/output bindings and no longer mentions a
pattern cap. The tree-class evidence gate's docstring names the verdict occurrence as its
counter's source. `:rlm/tree-generated` is documented at its actual cadence — once per
campaign, at the terminal boundary, carrying the last tree — in the orc-service processor
comment, the RLM guide's event table and judge walkthrough, the evaluation component's
Gap-7b grader comment and processor docstring, and `docs/EVALUATION-COMPONENT.md`.

Independent inspection re-ran the contract namespace, read every changed sentence against
the RR-19 … RR-24 Verification sections and the code they describe, re-swept the live tree
for the issue's phrases, and classified every remaining `idempot` mention (all describe our
own replay, CAS and recovery idempotency, or the tool-boundary contract; none a provider).
It found and fixed four defects the implementer's pass had left: the Gap-7b tree-shape
grader in the evaluation component still said the event fires "per Phase 1 emit-tree! call"
and "for each intermediate emit-tree!", and `docs/EVALUATION-COMPONENT.md` repeated it — the
contract regex had a hole that let that phrasing through, so it was strengthened, shown RED
against the old text, and the prose rewritten; the rewritten harvest paragraph enumerated
the verdict statuses as success, failure and timeout, omitting blocked and cancellation's
non-verdict; the executor comment stated what documentation says instead of what the key is,
and its "provider deduplication" phrase passed the grep only because of a line break; and
the rewrite carried forward an "ADR 0015" citation that resolves to nothing in this tree
(and to an unrelated ontology ADR in the main checkout) — dropped from the rewritten
sentence. The implementer's change list also omitted its own RLM-guide event-table and
judge-walkthrough edits; both were read and verified true.

Focused results on the final tree: the contract namespace 4 tests / 15 assertions, 0
failures (from 12 failures at RED). Comment edits touch `.clj` files, so the ontology brick
was rerun in both owning project graphs (77 namespaces, 668 tests / 3803 assertions each,
exit 0) and the complete two-project `orc-service` brick passes with exit 0 in 88 minutes
46 seconds under `-J-Djava.awt.headless=true` (127 namespaces, 1055 tests / 5897 assertions
per graph, 0 failures, 0 errors; the two new namespaces are this slice's contract and
RR-26's Seam-7 race test). The first ontology graph started two minutes before the two
comment-only executor and evaluation edits; the second ontology graph, the orc-service
brick and the evaluation brick ran on the final tree; `project:orc brick:evaluation` passes with exit 0 in 3
minutes 4 seconds (8 namespaces, 115 tests / 454 assertions, 0 failures, 0 errors). Allium remains at the characterized
twelve-spec baseline of 115 information diagnostics, 35 warnings, 0 errors and zero analyse
findings; no `.allium` file changed. The slice carries no `allium plan` obligation — it
changes prose, not behaviour — so coverage is `0 obligations, 0 covered, 0 uncovered`, with
no weakened test and no generated mock, stub, TODO or skeleton. Zero orphan JVMs after every
run.

Weed check mode: no RR-25 divergence — `IdempotentResume` and
`CheckpointSafeToolsShareIdempotencyKeys` already describe our boundary, and prose was moved
toward the landed code, never the reverse. Classified findings: the pre-existing "ADR 0011",
"ADR 0015" and "ADR 0016" references in the top-level docs resolve to nothing in this tree
(stale references from another ADR series — a documentation cleanup outside this arc,
recorded for the user); the `:sheet->class` docstring in the ontology read models remains
true (that map is classification-fed; only the recurrence window is verdict-fed).

## Test seams

Documentation only — verified by review, plus a grep proving the false phrasing is gone.

## Blocked by

RR-7.

## Handoff plan

**Handoff is crafted AFTER RR-7 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the final naming of the idempotency key as implemented, so docs describe what exists

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
