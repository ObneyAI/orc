# RR-Durable — durable, self-describing repl-researcher campaigns

Make a campaign survive, describe itself, and feed what it records back into the loop that is supposed to learn from it.

**PRD:** [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
**Grill log:** [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md) — decisions G1–G19, research findings R1–R8
**ADRs:** [0004 — campaign effects are at-least-once and attributable](../../adr/0004-campaign-effects-are-at-least-once-and-attributable.md) · [0005 — recursive campaigns are checkpointed by default](../../adr/0005-recursive-campaigns-are-checkpointed-by-default.md)
**Specs:** `specs/orc-service.allium`, `specs/ontology.allium`, `specs/evaluation.allium` — the current ORC-service plan contains 275 obligations; slice briefs name the exact campaign obligations they own
**Branch:** `feature/rr-durable-self-learning`, rebased onto merged `main` @ `1b6f95cb`

## Relationship to PR #36

PR #36 shipped checkpointing as an **opt-in** feature and landed the runtime foundation this arc builds on. Its merge
included the repaired root retick and inline-function checkpoint paths plus provider deadlines, concurrent terminal
fencing, and monotonic trace publication:

- Root `:running` re-ticks were dropped for **every** workflow, opt-in or not (CI catches this).
- Checkpointing died on any tree containing inline code, destroying the campaign's entire history (CI **cannot** catch
  this — the regression test went in with the fix).

The merge is complete. The remaining RR defects are now isolated to the opt-in campaign path or its learning consumers,
which is precisely why **RR-15, the default-on flip, remains the last spine slice**. The moment checkpointing is the
recursive default, every opt-in-only defect becomes a default-path defect. The flip is the reward for the repairs, not
the start of them.

## Slices

### Family A — Foundation repairs

Inherited defects. No blockers; all pre-writable.

| Slice | Type | Blocked by |
|---|---|---|
| [RR-1](RR-1-phase-1-budget-branch-and-phase-2-repair-turn-restored.md) — Phase-1 budget branch and Phase-2 repair turn restored | AFK | — |
| [RR-2](RR-2-durable-tool-caller-honours-the-arity-guard.md) — Durable tool caller honours the arity guard | AFK | — |
| [RR-3](RR-3-a-running-child-is-not-a-failure-and-a-replayed-child-keeps-.md) — A running child is not a failure; a replayed child keeps its duration | AFK | — |

### Family B — Durable spine (producers)

| Slice | Type | Blocked by |
|---|---|---|
| [RR-P1](RR-P1-prototype-capture-generated-code-as-durable-source.md) — *Prototype:* capture generated code as durable source | HITL | — |
| [RR-4](RR-4-resume-state-and-the-iteration-record-become-separate-facts.md) — Resume state and the iteration record become separate facts | AFK | — |
| [RR-5](RR-5-iteration-record-content-and-the-evidence-digest.md) — Iteration record content and the evidence digest | AFK | RR-4 |
| [RR-6](RR-6-generated-code-becomes-durable-source.md) — Generated code becomes durable source | AFK | RR-P1, RR-4 |
| [RR-P2](RR-P2-prototype-claim-epoch-compare-and-swap-against-the-real-stor.md) — *Prototype:* claim-epoch compare-and-swap against the real store | HITL | — |
| [RR-7](RR-7-claim-epoch-claim-before-effect-and-content-derived-action-i.md) — Claim epoch, claim-before-effect, content-derived identity | AFK | RR-P2 |
| [RR-8](RR-8-lease-wired-recovery-recognises-campaigns-and-the-scan-runs-.md) — Lease wired; recovery recognises campaigns; the scan runs itself | AFK | RR-7 |
| [RR-9](RR-9-drain-and-every-campaign-operation-bounded.md) — Drain, and every campaign operation bounded | AFK | RR-8 |
| [RR-10](RR-10-indeterminate-effects-resolved-by-callee-participation.md) — Indeterminate effects resolved by callee participation | AFK | RR-7 |
| [RR-11](RR-11-call-budget-derived-from-durable-claims-usage-counted-once.md) — Call budget derived from durable claims; usage counted once | AFK | RR-7 |
| [RR-12](RR-12-map-each-survivors-are-rejoined-not-re-run.md) — Map-each survivors are rejoined, not re-run | AFK | — |
| [RR-13](RR-13-blocked-outcomes-are-recorded-and-rejoinable.md) — Blocked outcomes are recorded and rejoinable | AFK | — |
| [RR-14](RR-14-sandbox-delta-with-periodic-full-snapshots.md) — Sandbox delta with periodic full snapshots | AFK | RR-4 |
| [RR-15](RR-15-checkpointing-on-by-default.md) — **Checkpointing on by default** | AFK | RR-4, RR-14 + whole spine |

### Family C — Evidence consumers

The harvest / living-description / recursion upgrades. Each reads what the spine produces.

| Slice | Type | Blocked by |
|---|---|---|
| [RR-16](RR-16-the-live-stream-becomes-a-projection-of-the-durable-record.md) — The live stream becomes a projection of the durable record | AFK | RR-5 |
| [RR-17](RR-17-the-trace-api-surfaces-the-iteration-record-and-judges-recei.md) — The trace API surfaces the record; judges receive it | AFK | RR-5 |
| [RR-18](RR-18-classification-happens-once-per-campaign.md) — Classification happens once per campaign | AFK | — |
| [RR-19](RR-19-recurrence-is-counted-at-outcome-not-intent.md) — Recurrence is counted at outcome, not intent | AFK | — |
| [RR-20](RR-20-the-worked-pattern-is-keyed-on-outcome-and-shape.md) — The worked pattern is keyed on outcome and shape | AFK | RR-5, RR-6 |
| [RR-21](RR-21-convergence-measured-over-winning-shapes-reported-before-it-.md) — Convergence over winning shapes, reported before it gates | AFK | RR-20 |
| [RR-22](RR-22-offered-patterns-declare-their-key-bindings.md) — Offered patterns declare their key bindings | AFK | RR-6 |
| [RR-23](RR-23-tag-scope-the-hot-evidence-queries.md) — Tag-scope the hot evidence queries | AFK | — |
| [RR-24](RR-24-behaviour-mints-carry-iteration-provenance.md) — Behaviour mints carry iteration provenance | AFK | RR-7 |

### Family D — Close-out

| Slice | Type | Blocked by |
|---|---|---|
| [RR-25](RR-25-documentation-truth-pass.md) — Documentation truth pass | AFK | RR-7 |
| [RR-26](RR-26-whole-spec-integration-and-the-measurement-pass.md) — **Whole-spec integration and the measurement pass** | HITL | all |

## Handoff and prototype cycle

**Pre-writable now** — no dependency on an upstream slice's produced API, so briefs can be written before the arc
starts: RR-1, RR-2, RR-3, RR-P1, RR-4, RR-P2, RR-12, RR-13, RR-18, RR-19, RR-23.

**Deferred by rule** — every other slice consumes an earlier slice's *real produced API*, so its brief is crafted
**after** that blocker lands and is inspected, from the actual signatures. Each issue names the exact signatures its
brief will need. A brief written against a guessed signature sends a subagent down a path that does not exist; this rule
is not traded away for speed.

**Two prototypes, both HITL, both able to falsify a ratified decision:**

- **RR-P1** — if generated code can only be captured by a string-authoring convention, the DSL surface the model is
  taught changes, which needs re-grilling rather than an in-flight call. **Ratified:** quoted forms preserve exact
  source and can be compiled after capture; the string fallback is unnecessary. See the
  [finding](../../build-timeline/prototype-findings/RR-P1-generated-code-source-capture.md).
- **RR-P2** — if the compare-and-swap predicate cannot express the epoch condition atomically on a backend we ship, the
  fence's state guarantee is unavailable and the decision returns to the grill. **Ratified:** in-memory, SQLite and
  Postgres all rejected superseded epochs before append under their real atomic boundary. See the
  [finding](../../build-timeline/prototype-findings/RR-P2-real-store-claim-epoch-cas.md).

The current Allium CLI does not emit executable obligations for these prototype questions. Their falsification criteria,
evidence and ratified/falsified result therefore live in the two prototype issues and this ledger; after either result is
accepted, the orchestrator tends the resulting contract into the specification before any dependent handoff is written.

**Every brief** seeds its TDD cycle list from `/propagate` scoped to that slice's obligations, with the generated tests
confirmed **RED** before dispatch. A generated test that is green before implementation is a finding, not success.
Generated tests are contract and are never weakened to pass — the spec is fixed and re-propagated instead.

**After every dispatched slice:** `/inspect-orc`, including the spec-conformance gate, `/weed` check-mode with
classified divergences, and the obligation audit. Allium's internal verify is the claim; `/inspect-orc` is the
falsification.

## Test seams

Existing seams preferred, highest first. Two are new and are built rather than skipped.

| | Seam | Prior art |
|---|---|---|
| Seam-1 | Public execution via `with-async-test-context` | `running_retick_test`, `checkpointed_researcher_test` |
| Seam-2 | Restart: stop processors, reopen store, restart processors | the hand-driven SQLite restart test, promoted |
| Seam-3 | Durable evidence via event-store reads | existing checkpoint-event assertions |
| Seam-4 | Ontology consumers over a synthesized event stream | `el4_harvest_test`, `consolidator_test` |
| Seam-5 | Judge runtime | `judge_runtime_test` |
| Seam-6 | Executor unit — **last resort**, deadline arithmetic and codecs only | the checkpoint tests |
| Seam-7 | **Concurrency (new)** — two workers racing one frontier | none; the platform's own fencing test is sequential and single-threaded |
| Seam-8 | **Gated live provider (new)** — real kill mid-campaign | the gated single-process journey, extended |

## Measurement is a deliverable

Sandbox growth, iteration-record volume, the evidence density constant for the new observation kind, worst-case quantum
duration, and the convergence ratio's real distribution are **measured and written into the specs as config** — not
guessed. Guessing is what produced the 6.4 MB reflection failure. The existing density constants are documented as
measured, with the explicit rule that under-prediction is fixed with new anchors and never a bigger constant.
