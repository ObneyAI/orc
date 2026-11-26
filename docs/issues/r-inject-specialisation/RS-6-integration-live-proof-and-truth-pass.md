# RS-6 — Integration, live proof and truth pass

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

The whole-spec integration slice, orchestrator solo. The classify-only sweep harness reads the three-state outcome, the assignment provenance and the coverage verdict and reports them per instruction and in aggregate beside the June-era flags. The 21-task corpus is run twice on the final tree: the sanity checks match at 1.00 and covered, uncovered leaf matches mint children under their shape leaf, and the second pass derives identical child identities. One bounded full-bench run on three off-domain tasks is recorded as observation. The user docs that call the out-of-distribution symptom resolved are corrected to state the current behaviour. A full `/weed` over the ontology spec, cross-entity tests, and the obligation convergence check close the arc.

## Acceptance criteria

- [x] Harness reads outcome, provenance and verdict; both sweep passes recorded with findings
- [x] Sanity checks 1.00 and covered; uncovered leaf matches mint children; identical identities across passes (18 of 21; the three that moved are itemised in the findings)
- [x] One full-bench observation on three tasks recorded
- [x] Docs corrected; `/weed` divergences classified and tended; every `MintDomainChild` and `DomainVerdict` obligation covered; Allium error-free by severity count

## Verification

The whole-spec integration slice, orchestrator solo. A new sweep harness drives every corpus task through the live
auto-classify wedge and the live R-Inject render with a fresh sheet and tick, so a mint is durable and the second
pass on the same store must land on the child the first pass minted; per task it records the classified or deferred
event body (the three-state outcome, the provenance, the verdict, the label, the children considered, any
deferral), the payload's domain map and the rendered block, and aggregates them beside the June-era flags. The
three-state outcome was not on the classified event, only in memory; it is now recorded as an optional field the
wedge forwards, applied with its assertion in one pass rather than red-first (the assertion fails on the pre-change
event by construction) and reported here.

Weed check mode over the arc's constructs, run read-only against the code, reported seventeen divergences. One was a
code bug that the arc's own decision D7 depends on: the reranker's candidate schema and instruction accept a
class's existing domain-child labels for reuse, and nothing in production filled that field, so label reuse was
coincidence and a variant label minted a sibling. The labels are now read from the class's narrower concepts at
candidate enrichment, red-first (two guards, including the string-form identifier retrieval hands back; the first
green was blocked by a wrong keyword comparison against a helper that returns a string name, caught by the red run).
Two latent code gaps were closed red-first: a domain-axis deferral could be recorded twice for one occurrence, and
the checkpointed commit ordered the domain deferral before the assignment while the wedge dispatches it after; both
paths now agree and the deferral is once per occurrence. The remaining divergences were tended in the spec: the
three domain rules trigger on the decision-time shape match, read the class's domain children and the canonical
label through named black-box functions, ensure the child concept and its edge, and assign the task in all three
cases; the verdict's fields are optional; the search result carries the verdict; the outcome enum carries the three
domain provenances; the description body carries the harvested label and the recommended pattern; the classifier's
thresholds are in the config block; the invariants describe the per-axis deferral rate, the same-occurrence domain
axis, the deferral carve-out and the recorded outcome; and the orc-service spec gains an invariant stating the
atomic classification-effects set. The obligation plan grew from eighteen to twenty domain obligations; the two new
ones (the child concept's declared fields) have their birth-suite assertions. Convergence: every one of the twenty
is covered by a named suite (`specs/COVERAGE.md`), and Allium holds at 0 errors on both specs with unchanged
warning and information counts.

Two live sweep passes, twice. Run A, before the outcome was recorded on the event: pass 1 minted fifteen domain
children (five first, ten siblings) and pass 2 landed on the identical child for twelve, identity stable on fifteen
of twenty-one; two ticks were withheld by a fitness-axis reranker fallback, visible as deferrals. Run B, on the final
tree: pass 1 minted fifteen (five first, ten siblings), pass 2 landed on the identical child for fourteen of them
and on fifteen in all, identity stable on eighteen of twenty-one; both sanity checks matched their seeded class at
1.00 and `covered` in both passes; with children present the reranker called the parent `covered` on seventeen of
twenty-one (the D7b finding, live), and the judged label decided. The three that did not converge are itemised in
the findings: one label variant coined despite the shown sibling, one bundle-to-walk-down move with no domain axis,
and one novel task fresh-minted in both passes. The shape-broad corpus still absorbs the off-domain tasks as
confident shape matches; each now lands on its own labelled child under that shape with an edge, a label and its
signature as first evidence.

One bounded full-bench observation, three off-domain tasks through the real bench runner with auto-classify on:
all three campaigns succeeded (43 to 66 seconds, 57 to 104 thousand tokens); each was classified as a first domain
child under the shape that absorbed it with a partial verdict, the child was minted before the render, the prepend
carried the parent's entry and the child line, and the campaign's terminal verdict was recorded as an occurrence on
the child — its first evidence. Two observations are recorded for the roadmap rather than fixed: a newborn child is
retrievable at zero occurrences as if curated (the marathon task matched the recipe-scaling child minted one task
earlier and minted a grandchild under it), and the observation harness reads claims by sheet where they are tagged
by target. A first attempt failed before any task ran on an inconsistent on-disk retrieval index left by the
previous JVM (the known two-rebuilder artifact); the index is derived data and was rebuilt.

Docs truth pass: the RLM guide describes the domain-coverage verdict, domain children and the waterfall render, and
both guides describe the injection record in place of the retired trace sidecar; the self-improving-loop status no
longer calls the off-domain symptom resolved by the emergence loop, and states what that loop fixed, what the later
sweep still showed, and how classification-time domain children now handle it. Two harness defects were found and
fixed along the way: the birth suite's dispatch helper appended every event twice (RS-5's inspection), and the
sweep harness read the mint event by the wrong tag (the mint is tagged by the child, not the tick).

On the final tree the ontology brick passes in each owning project graph as its own JVM (83 namespaces, 709 tests /
3991 assertions each, 0 failures) and the complete two-project `orc-service` brick passes with exit 0 in 69 minutes
45 seconds under a 3 GB heap cap (132 namespaces per graph, 2142 tests / 11972 assertions across both, 0 failures,
0 errors). Allium: ontology spec 0 errors, 8 warnings, 43 information diagnostics, 0 analyse findings; orc-service
spec 0 errors, 2 warnings, 0 analyse findings — both unchanged from before the arc.

## Spec obligations covered

All eight new obligations plus the classification contract's existing invariants — the convergence check.

## Docs truth-pass list (accumulated by inspections)

- `docs/RLM-GUIDE.md` "Pattern injection via R-Inject": silent on the domain-child waterfall (newborn: parent entry
  plus child line; consolidated: child primary, parent as shape context) and on the domain-aware SPECIALIZE bullet;
  the sample prepend shows pre-four-move copy; it still claims a `/tmp/r-inject-trace-<sheet-id>.edn` sidecar that the
  injection record replaced.
- `docs/SELF-IMPROVING-LOOP.md`: the same retired sidecar in three places; the "resolved" claim about specialisation.
- `components/ontology/test/.../el4_harvest_test.clj` docstring names the wrong harvest topics.
- `components/ontology/src/.../interface.clj` `get-description` docstring omits the `:tree-class` granularity.

## Test seams

Seam 6 — live; plus every seam above re-run on the final tree.

## Blocked by

RS-1 through RS-5.

## Handoff plan

Orchestrator solo; no brief.

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
- Nothing is exempt: this slice may touch any file to close a divergence, but every change is reported.

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
