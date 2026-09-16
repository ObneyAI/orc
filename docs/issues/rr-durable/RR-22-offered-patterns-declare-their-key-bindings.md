# RR-22: Offered patterns declare their key bindings

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The corpus tells a model it may ADOPT a reference pattern. For any pattern containing inline code that move is
impossible — the logic was replaced by a placeholder — so the corpus's own top recommendation silently degrades into
'reconstruct from scratch and hope'. That is the cold-start failure reappearing exactly where the corpus exists to prevent
it.

RR-6 makes the code real. This slice finishes the job: a pattern declares what it reads and writes, so binding it to a new
task is a mechanical rebind rather than a re-derivation.

Behaviours stay **advice**. The model authors every tree; nothing here compels it down a pipeline.

## Acceptance criteria

- [x] A pattern offered to a model contains runnable code, not a placeholder
- [x] A pattern is offered WHOLE: the prompt renderer never truncates the pattern text. Ratified during
  RR-19 close-out — R-Inject's `format-principle-entry` clips every worked example to 1,200 characters, which
  elides exactly the code `OfferedPatternsAreUsable` requires to be present; the cap is removed, not raised,
  and no emergency bound is reintroduced without a measured incident and a grill decision
- [x] A pattern declares the keys it reads and writes
- [x] A model shown a pattern can adopt it against different keys without re-deriving its logic
- [x] Nothing in this slice executes a behaviour on the model's behalf

## Spec obligations covered

None. This slice repairs behaviour that predates the campaign model; its proof is the existing suite plus the acceptance criteria above.

## Verification

An offered pattern is now usable. R-Inject renders a class's worked pattern whole:
the 1,200-character truncation of `:recommended-pattern` in `format-principle-entry`
is removed and no emergency bound replaces it (ratified during RR-19 close-out; a
6,000-character pattern reaches the model verbatim). The pattern's key bindings are
derived from its exact source by a pure ontology function
(`pattern-key-bindings`): the keys it reads that no earlier node wrote (its external
inputs), every key it writes, and the outputs its final names; a source that does
not parse or whose code was elided declares nothing rather than lying. The assembled
strength entry carries those bindings additively as `:pattern-reads`,
`:pattern-writes` and `:pattern-outputs` (absent on entries without a pattern;
existing fields byte-identical; the reranker's compact allowlist untouched), and the
rendering names them as advice to rebind, never as a mandate. A public proof through `sheet/execute` shows a default-checkpointed researcher whose scripted provider is shown the whole pattern and its bindings, copies the pattern byte-for-byte (identical shape fingerprint, no re-derivation) and bridges its declared output to the new task's own key with one final call, finishing successfully.
Nothing executes a behaviour on the model's behalf.

Independent inspection reran the subagent's proof and drove an adversarial probe
(nested map-each and parallel branches, read-before-write ordering, duplicate keys,
read-eval refusal, reader-macro code, non-string input, elided code, a 40,000-character
pattern rendered whole, and weaknesses never receiving a bindings line). Two gaps in the
binding derivation were found and fixed test-first by the orchestrator: `:from` on
`:map-each`, `:chunk-document` and `:aggregate` is a read the pattern makes and was not
declared, and a key listed twice inside one node's `:reads` was declared twice. The
cycle-5 wording in the brief ("the emitted tree's shape fingerprint equals the offered
pattern's" after rebinding) was underspecified: a shape fingerprint deliberately keeps
`:reads`/`:writes` keys (CONTEXT.md: a shape is structure independent of instructions and
code, not of keys), so a mechanical adoption is a whole-pattern copy plus a bridge at the
task boundary; the implementer's resolution is ratified as the meaning of "adopt against
different keys".

Focused results on the final tree: the two contract namespaces plus the live adopt proof and the assembly properties seam 10 tests / 55 assertions, the R-Inject, formatting, seed-validation, reranker-contract and RR-20 seams 50 tests / 565 assertions, the checkpointed researcher namespace 39 / 352, the adversarial probe 2 / 21, all 0 failures. The ontology brick passes in
both owning project graphs (662 tests / 3786 assertions in each graph, run solo in fresh JVMs — 6 minutes 16 seconds and 6 minutes 2 seconds). The complete two-project `orc-service`
brick passes with exit 0 in 71 minutes 9 seconds under
`-J-Djava.awt.headless=true` (123 namespaces and 1044 tests / 5834 assertions in each project graph, 0 failures, 0 errors). Allium remains at the
characterized twelve-spec baseline of 115 information diagnostics, 35 warnings, 0
errors and zero analyse findings. The issue names no generated obligation
(`OfferedPatternsAreUsable` is a contract invariant without a plan entry): coverage is
`0 obligations, 0 covered, 0 uncovered`; the contract namespaces end at
4 tests / 23 assertions green from 14 failures at RED (two assertions added by the orchestrator's inspection), with no weakened test and no generated mock, stub, TODO or
skeleton.

Weed check mode: no RR-22 divergence against `OfferedPatternsAreUsable`. Classified findings:
`:pattern-reads`/`:pattern-writes`/`:pattern-outputs` and the derivation rules (external
reads, `:from` as a read, outputs from the final) are implementation-level detail beneath
the invariant's "what it reads and writes is declared" (intentional gap); the per-item
key a map-each child reads is declared as an external read because nothing in the pattern
writes it (intentional — the model rebinds it with the collection); the strength count cap
in R-Inject (`traits-cap`) limits how many patterns are shown, never a pattern's length
(intentional).

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) for the offered form, Seam-1 (public execution via `with-async-test-context`) for a live adopt.

## Blocked by

RR-6.

## Handoff plan

**Handoff is crafted AFTER RR-6 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the durable source form, since a declared binding is meaningless without runnable code
  - how a recorded tree is read back, which the binding declaration accompanies

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
