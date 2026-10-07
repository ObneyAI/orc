# Judge assessments are durable Grain work with their own identity, outcome and purpose

ORC judged completed nodes from a processor that started background futures and
recorded only successful scores. Each score was keyed by sheet, node, tick and
judge name.

That design caused six problems:
- Three map-each executions of one node produced three judgments but kept one score.
- A judge that failed left no trace.
- Score-only results were stored with empty feedback.
- Learning consumers could not tell monitoring scores from feedback-backed ones.
- Built-in judges called the model from code, outside node accounting.
- Explicitly attached judges ran only when Living Descriptions were enabled.

We decided the following:
- **Every judge is a behaviour.** It is a workflow reading a rubric value from its blackboard.
- **Every judgment is a durable assessment.**
  - It is identified by the subject completion it assesses, the judge, and the judge's revision.
  - It is requested by a command that fences replay before any judging starts.
  - It ends as scored, failed or ungradable, with nothing invented.
- **A judge declares its purposes.** Only learning judges, which must require feedback, emit the score records that the learning loops read.
- **Attaching a judge is what enables it.**

## Considered options

- **Keep the score tuple and add the execution context to it.** Rejected. This fixes repeated executions only. Failures, purposes, rubric revisions and replay-before-work would each need another patch to the same handler.
- **Keep built-in judges as functions and fix their outputs.** Rejected. That leaves two execution paths for judges, so every assessment feature would be built twice. Built-in model calls would also stay outside budgets, retries and accounting.
- **Replace the legacy score event with assessment events everywhere.** Rejected for now. Harvest, the consolidator and the evidence guard read `:judge/score-emitted`. Emitting it only for scored outcomes of learning judges keeps those consumers unchanged.
- **A separate monitoring switch.** Rejected. An attachment is already a deliberate per-node choice. A second switch would recreate the bug where an attached judge silently does nothing.

## Consequences

- Assessment identity changes when a judge's behaviour, rubric or declared model changes. A judge must be revised, not redeclared under a new name, for its history to stay comparable.
- Work done on behalf of an assessment carries a durable origin and is never auto-assessed. Judge composition (delegates, parallel checks, tools) is unaffected.
- Assessments still execute as soon as they are requested. Capacity limits, worker claims, restart recovery and drain remain an open question for the next stage. Until then a crash leaves requested assessments visibly pending, not lost.
- Historical score events are never rewritten. They show band, revision and node version as absent.
