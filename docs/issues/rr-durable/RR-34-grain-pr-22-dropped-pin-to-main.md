# RR-34 — Grain PR #22 dropped: pin to Grain main and retire the proofs that only it could pass

## Parent

The arc's merge gate (RR-15, README). Grain PR #22 was dropped by its maintainer on 2026-09-15 as speculative:
"sometimes work will get duplicated or run a little longer than is ideal when leases change ownership." ORC never
relied on it for correctness (ADR 0004; the campaign frontier epoch is the fence, G7).

## What to build

Every Grain pin points at Grain `main` (`dbf5b5229aad09785e40f71e96adf65c90f971d5`, which also carries the SQLite
event-store performance merge). The two integration tests that could only pass with the PR's options — the live-poller
drain tracer of DET-E2E-270 (it passed the PR's ownership predicate to Grain's poller) and DET-E2E-271 (it sized the
PR's reassignment interval) — are deleted; DET-E2E-271 is retired in the checklist and DET-E2E-270 re-scoped to ORC's
own context seam, which nothing supplies in production. The researcher executor's injectable ownership capability
stays as an ORC seam (removing it is a separate hygiene decision; it is guarded and inert without a supplier). The
full gate set runs on the repinned tree so the arc is proven with no PR #22 code anywhere on the classpath.

## Acceptance criteria

- [x] No pin references the PR head; every Grain dependency resolves to Grain main
- [x] No test passes `:lease-check-fn` to Grain's poller or `:reassignment-interval-ms` to the control plane
- [x] The ORC-seam ownership tests (initial loss, compatibility boundaries, non-cooperative stale owner losing the epoch CAS, execution-budget monitor) are green unchanged
- [x] Evaluation, orc-service and per-project ontology gates green on the repinned tree; 0 orphan JVMs
- [x] Ledger: DET-E2E-270 re-scoped, DET-E2E-271 retired, RR-8 annotated, README merge gate closed

## Spec obligations covered

None new; the campaign obligations already covered must stay green on the repinned tree.

## Verification

Nine pin files (22 sites) moved from the PR head to Grain main, on this worktree and on the specialisation worktree.
Two tests deleted from the bounded-campaign suite: the live-poller drain tracer (it passed the PR's ownership predicate
to Grain's poller, which main ignores) and the reassignment-interval sizing test (the option no longer exists). A
repo-wide search finds neither option in any test. The ORC-seam ownership tests — initial loss, compatibility
boundaries, the non-cooperative stale owner losing the epoch compare-and-swap, and the execution-budget monitor suite —
are unchanged and green, which is the proof that mattered: a stale node's late result is still rejected by the campaign
frontier epoch with nothing supplied by Grain. The executor's injectable ownership capability remains as an inert seam
and is stated as such in DET-E2E-270 and RR-8. On the repinned tree (every Grain dependency at Grain main `dbf5b522`, no PR #22 code on the classpath): the focused handover suites pass (88 tests / 693 assertions), the evaluation brick passes in both projects (286 / 1146), the complete two-project `orc-service` brick passes with exit 0 in 74 minutes 4 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1058 tests / 5884 assertions per graph — two tests and 80 assertions fewer than before, exactly the two deleted PR-only tests), and the ontology brick passes in each owning project graph run as its own JVM (78 namespaces, 675 / 3823 each); 0 failures, 0 orphan JVMs.


**CI finding (the one thing the local gates could not see).** The pull request's CI job failed twice, identically, on
26 assertions in `deterministic-value-storage-e2e-test`, a namespace untouched by the arc that passed in every local
run including CI's exact aggregate command. Instrumenting the assertions to carry the run's error showed the LLM leaf
failing with "simulated process crash after reservation" — a fault injected by the RR-11 test
`sqlite-reopen-keeps-a-pre-return-reservation-spent`, which installed its crash stub with `with-redefs` inside a
future and waited one second for that future. On the slower CI runner the future outlived the wait, the test's second
`with-redefs` then unwound in the wrong order, and the crash stub became the permanent root binding of `llm/predict`
for the rest of the JVM: exactly the three tests that stub the router beneath `llm/predict` failed, every other test
was unaffected, and local runs never hit it only because their namespace order ran the victim before the culprit.
Proven mechanically (the root binding was not restored after the budget suite; after the fix it is), fixed by
installing and removing the stub on the test thread and asserting the crashed run returned first, and re-proven in
CI's namespace order. The instrumented assertions stay: a failed execute now reports why.

## Test seams

The existing handover suites on ORC's context seam; the full brick gates.

## Blocked by

None — the maintainer's decision.

## Handoff plan

Orchestrator solo (pins, deletions, ledger, gates).

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

- `specs/*.allium`; the researcher executor's ownership seam (kept, guarded); Grain itself.

## Report back

Gate results on the repinned tree; what was deleted and why; what remains inert and where that is stated.
