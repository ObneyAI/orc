# RS-P2 — *Prototype:* a parent edge can be born from the claim path

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

A feasibility gate, not a slice. D3 assumes that recording a description slot carrying a parent at classification time makes the concept-graph projector emit the SKOS broader edge immediately. Today that projector listens to description-updated events whose body carries the parent, while the classification path writes claim deltas, not bodies. Against the real in-memory store, record a claim for a brand-new class with a parent and observe whether an edge appears after the projectors run; if not, identify which existing command does produce it and whether it can be driven from the wedge without a second writer of the body slot (CC-6). Record the finding.

## Acceptance criteria

- [x] A recorded probe against the real store showing whether a claim-path write with a parent yields the SKOS edge
- [x] If it does not, the exact existing command that does, and a written verdict on how RS-3 records the edge without a second body writer

## Verdict (probe `development/src/rs_p2_parent_edge_probe.clj`, run against the real in-memory store)

**No — the claim path cannot carry a parent, and the description projector would not act on it if it did.** The
claim command (`record-claim-deltas`) has no parent field and emits `claim-deltas-recorded`, which no projector reads
for hierarchy. The only hierarchy projector listens for `tree-description-updated` events whose body carries
`:parent-tree-id` **and** whose target type is `:tree-fingerprint`; the tree-class-scoped description command emits
`:target-type :tree-class`, which that projector ignores, and writing a body from the wedge would be the second body
writer CC-6 forbids.

**Yes — the command path works without any description.** Ensuring both concepts (the same lazy-create the projector
uses) and dispatching `create-relationship` with `skos:broader` from child to parent produced the edge: the parent's
narrower set contained the child immediately (Q-b). This is how RS-3 records the edge: a single ontology command that
ensures the two tree-class concepts and emits the relationship — either a new `record-domain-child-mint` command or
`assign-task-class` emitting it when the provenance is the domain-child mint — dispatched by the wedge beside the
signature claim it already records. One writer of the edge, no body written.

**Finding for RS-3 and RS-5 (code bug, C-2d-2 predates the scope split):** a child whose only description comes from a
`:tree-class` claim — the CV-1 signature route — is readable at `:tree-class` scope and absent at `:tree-fingerprint`
scope, and walk-down's child lookup reads `:tree-fingerprint`, so it returned no children for a parent that has the
edge (Q-c). Runtime-emergent classes describe themselves under `:tree-class` (C-Loop-1); the walk-down lookup must read
that scope (falling back to `:tree-fingerprint` for the seeded instances) or every domain child is invisible to the
walk that is supposed to reach it. RS-3 owns the lookup fix because reachability is its acceptance criterion; RS-5
proves it on synthesised recurrence.

**Untested here:** the seeded projector path (Q-a) was invoked with a hand-built event shape and produced nothing; it is
covered by the existing seeds suite and is not the path RS-3 uses, so it was not chased.

## Spec obligations covered

None (a prototype; it informs RS-3's brief).

## Test seams

The ontology test support (in-memory store, projectors), read-only against production code.

## Blocked by

None — can start immediately.

## Handoff plan

Orchestrator-run (HITL prototype).

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
- **No regex or phrase matching over model-authored prose — in tests or in production.** Assert on structured data the
  runtime emits: typed reranker fields, event bodies, identities, concept-graph edges, rendered values the test itself
  injected. The reranker is stubbed with a typed payload the test constructs; only the live sweep hears the real model.
- **Fitness keeps meaning shape-and-intent fit.** Coverage is a separate discrete verdict; nothing reads a domain
  judgement off the fitness number or off any similarity threshold.

## Do NOT touch

- `specs/*.allium` — the orchestrator is the only spec writer.
- Nothing in `components/` src: the probe is a throwaway under `development/` or a scratch test.

## Report back

- The `/propagate` coverage line verbatim: `N obligations, M covered, K uncovered`, with a reason for every
  uncovered one. No silent caps.
- Any propagate-emitted mock, stub or TODO skeleton, declared explicitly as a tracked gap.
- Proposed divergence classifications for anything where spec and code disagree.
- What you could NOT verify, stated plainly.

## Orchestrator gates (run after you report)

`/inspect-orc` against this slice: re-run the proof independently, re-read the code, try to break the claims, plus the
three ORC-specific gates — spec-conformance (`allium check` / `analyse` error-free by **severity count** on touched
specs), `/weed` check-mode with classified divergences, and the obligation audit (the coverage line above must survive
into the slice report). Allium's internal verify is the CLAIM; `/inspect-orc` is the falsification.
