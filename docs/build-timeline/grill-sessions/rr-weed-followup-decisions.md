# Grill — five design questions left open by the RR-26 whole-spec weed pass

Run as `/grill-with-docs` after RR-26 closed. Each question was first checked against every prior grill
(the RR-durable dossier), the RR PRD, the ADRs (`docs/adr` and the ontology component's ADRs on
`feature/ontology-architecture`), `CONTEXT.md`, and the judge-unification grill and Gap-1..8 issues, so that
nothing already decided was re-decided. Provenance for every construct comes from `git log -S` on the spec.

## D1 — `skipped` node status: retired

Spec since the initial import (2026-08-05) declared `NodeExecutionStatus.skipped`, `running -> skipped`, and
`rule NodeSkipped { when: ControlFlowSkipsNode … }`. No producer ever existed: the engine never starts an untaken
branch, so no node execution exists to mark; the only code traces were a node-trace schema enum value and a
read-model `:skip-count` that could only read zero. No grill, ADR, PRD or glossary term covered it.

Decision: retire. The status, transition, rule and stimulus are removed from `specs/orc-service.allium` (tended in
this session; Allium baseline re-derived). The code follow-up — drop `:skipped` from the node-trace schema enum and
the dead `:skip-count` tally — is a slice. The alternative (start-and-skip an execution for every untaken branch so
traces enumerate what could have run) was rejected because it would redefine "node execution" from "a node that
ran" to "a node that was considered" for every trace consumer. No glossary change (the concept is gone); no ADR
(easily reversed, not surprising once the untaken-branch model is stated).

## D2 — consumer-facing `ResumeWorkflowExecution`: retired

Prior decision honoured, not reopened: the RR PRD and dossier decided recovery is automatic
(`ActiveCampaignsRecoverAutomatically`), and the code implements it — the recovery scan runs from the periodic
trigger, the public interface exposes only the scan, and the per-execution resume command is internal. The spec's
`rule ResumeWorkflowExecution` (consumer stimulus) and the `WorkflowExecutionAccess` surface entry
`ResumeWorkflowExecution(consumer, execution)` predate that decision (2026-08-06) and described a capability the
interface does not offer. Both are removed (tended this session); `RecoveryFrontierReconstructed` had no listener
and goes with them. The alternative — exposing a per-execution operator resume — was rejected as a second recovery
path to keep correct under the same fences with nothing in the arc asking for it. No glossary change (the frontier
term already exists); no ADR.

## D3 — `evaluate_many` (hardcoded low-score threshold): retired

The spec's `TraceJudge.evaluate_many` parameterised `low_score_threshold`; the implementation `evaluate-traces` took
no threshold and hardcoded 0.7 — a hardcoded quality gate, which this project forbids. Codebase fact that settled the
shape of the decision: nothing calls it (no caller in any component, base or dev path beyond its own interface and a
never-dispatched `:evaluation/evaluate-batch` schema entry). Decision: retire the operation from the contract (tended
this session, with the now-unreferenced `EvaluationStatistics` value) and delete the dead implementation and its
schema entries as a slice. If batch evaluation is wanted later it returns through the loop with a real caller and the
threshold as configuration. Rejected alternative: honour the parameter and keep an API nobody uses. No glossary
change; no ADR.

## D4 — default LLM judges populate `dimensions`: honour the guarantee

`ActionableFeedback` (specs/evaluation.allium, since 2026-08-05) guarantees dimension-specific feedback on every
successful score. On the live processor path `invoke-llm-judge` hardcodes `:dimensions []` for grounding,
reasoning, completeness and instruction-following — four of the five default judges — while each already emits
named evidence lists (grounded/ungrounded claims, requirements met/missed, reasoning strengths/weaknesses, aspects
covered/missing). Neither the judge-unification grill nor Gap-8 decided this. Decision: keep the guarantee as
written and make the code honour it — each default judge projects its own evidence lists into one or two dimensions
named in its own vocabulary, carrying the judge's score; a code slice, no spec change. Rejected alternative: relax
the guarantee to match today's code. No glossary change ("dimension" already means a scored aspect of a judgement);
no ADR.

## D5 — the `occurrences` trigger parameter: split into two named quantities

Three ontology rules shared `when: TreeClassOccurrenceRecorded(tree_class, occurrences)` (since the CC-1..CC-7 work,
2026-08-07) while the event carries no such field and the rules read different quantities: promotion reads the
class's LIFETIME verdict count; description consolidation reads the count SINCE THE LAST DESCRIPTION FIRED, which
resets. Both thresholds are ten, so the two schedules looked identical while differing. Prior decisions honoured:
CC-15 aligned the description threshold to the delta counter; RR-19 counts recurrence at the verdict. Decision: the
trigger carries only the class; each rule names what it reads — `lifetime_occurrences` for promotion and the
coherence report, `occurrences_since_last_description` for description consolidation (tended this session, with a
comment stating the two schedules). Two glossary terms follow. Rejected alternative: keep the shared nominal
parameter and annotate it. No ADR (an ambiguity, not a trade-off).

## D3 (extension) — `evaluate_all` folded into the retirement

The single-trace synchronous sibling `evaluate_all` also has no non-test caller. With the user's assent it is retired from `TraceJudge` in the same session (tended; `ScoreWithFeedback` stays because `evaluate` and `gate_output` still return it). The code deletion is RR-29.

## Slices

D1 → RR-28, D3 → RR-29, D4 → RR-30 (all AFK, independent). D2 and D5 were spec-only and are complete.
