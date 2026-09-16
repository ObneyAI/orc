# RR-31 handoff — Judges see the node's resolved reads and its declared criteria

Issue: `docs/issues/rr-durable/RR-31-judges-see-resolved-reads-and-declared-criteria.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc` (branch `feature/rr-durable-self-learning-arc`). Run
tests with `JVM_XMX=1600m zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`; a copy of the runner is at `.rr-durable-notes/run-focused.sh`). ONE JVM at a time; never kill a
JVM whose working directory is not this worktree (another team's benchmark runs on this machine); confirm 0 orphan JVMs
after every run. Do not run `poly test` or any whole-brick build (the orchestrator runs the gates). Do not commit, push
or stash. Never edit `specs/*.allium`.

## Goal

Two things a judge is shown are wrong on the live processor path, and both were found by running the real triage
workflow against real judges (`.rr-durable-notes/RR29-live-run-judge-input-findings.md`, findings 1 and 2; the
deterministic reproduction is `.rr-durable-notes/probe_judge_inputs.clj` with its log beside it — run it first if you
want to see the defect with your own eyes):

1. every judge attached to an ordinary workflow node receives `:inputs {}`, although the node's completion records
   `:read-keys` and `:read-sources` and the value log resolves them to the real values;
2. the `:criteria` a workflow declares on a built-in judge never reaches the judge; the four LLM judges always run on
   their rubric's built-in criteria.

Fix what the judge is shown. Do not change scores, feedback, dimensions, provenance, rubric stances, scales or gating.

## Read first

1. Spec excerpt, verbatim (`specs/evaluation.allium`):

       value TraceEvidence {
           sheet_identifier: String
           node_identifier: String
           tick_identifier: String
           instruction: String
           inputs: Any
           outputs: Any
           ...
       }

       contract TraceJudge {
           evaluate: (judge: Judge, trace: TraceEvidence) -> ScoreWithFeedback
           ...
           @invariant JudgesSeeTheWorkNotOnlyItsResult
               -- A judge scoring work that was carried out over several iterations
               -- receives an account of those iterations — what was attempted, what
               -- each attempt produced or failed with, and how the work recovered — not
               -- only the final values it wrote. ...
       }

2. `components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj`:
   - `find-started-inputs` (line 56) — the Gap-7 reach-back; note it reads every `:sheet/node-execution-started`
     event for the tenant with no tick tag.
   - `build-trace-data` (line 104) — takes `:inputs` from the completion event, else the reach-back; resolves
     `:outputs` through `orc/value-log-writes-for` over `orc/value-log-read-tick-events`. Inputs must follow the same
     pattern.
   - `invoke-llm-judge` (line 195) — builds `executor-ctx {:inputs {:trace-data trace-data}}` and binds
     provider/model from `judge-config`; `judge-config` is otherwise unused here.
3. `components/orc-service/src/ai/obney/orc/orc_service/core/value_log.clj` `resolve-reads` (~line 324), exported as
   `orc/value-log-resolve-reads` — `(resolve-reads runtime-source tenant-id tick-id completion)` returns the
   `{read-key value}` map for exactly that node execution (recorded `:read-sources` win; map-each iteration seeds and
   tick seeds fall back). `runtime-source` is the event store, as `value-log-read-tick-events` is already called with
   `(:event-store ctx)`.
4. `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` around the root-start emit
   (~line 1850): "`:inputs` carries only what cannot be resolved from the tick blackboard — execution context and
   map-each item overrides." This is why the event's `:inputs` is empty for ordinary nodes and why the started-event
   reach-back only helps the direct-tick / researcher paths.
5. `components/evaluation/src/ai/obney/orc/evaluation/core/judges.clj`:
   - `call-grounding-judge-llm` (~line 378) and `call-tier1-judge-llm` (~line 407): each fetches its rubric with
     `rubrics/get-tier1-rubric` and composes the instruction from the rubric's `:criteria`/`:stance`/`:scale`.
   - the four judge functions `grounding-judge` (489), `instruction-following-judge` (527), `reasoning-judge` (561),
     `completeness-judge` (590): each reads `trace-data` from `(get-in executor-ctx [:inputs :trace-data])`.
   - `build-grounding-instruction` (~line 180) / `build-tier1-instruction` (~line 313): "WHAT TO EVALUATE:\n" criteria.
6. `components/orc-service/src/ai/obney/orc/orc_service/core/dsl.clj` `judges` (line 431) — the documented config
   shape with `:criteria`; `docs/EVALUATION-COMPONENT.md` ~line 108-118 — the promise.
7. Existing tests to mirror: `rr30_default_judge_dimensions_test.clj` (live-path driver with a stubbed `llm/predict`
   and a read-back of the emitted event) and `gap7-build-trace-data-reaches-back-for-inputs` in
   `judge_runtime_test.clj` (hand-built events — keep it green).

## The exact change

**Inputs.** In `build-trace-data`, when the completion carries `:read-keys`, resolve `:inputs` with
`(orc/value-log-resolve-reads (:event-store ctx) (:tenant-id ctx) tick-id event)`; merge any non-empty direct
`:inputs` (execution context / map-each overrides) over nothing — the resolved reads are the inputs. Keep today's
direct-inputs / started-event reach-back only for completions that record no `:read-keys`. Scope the reach-back's
event read to the tick (`:tags #{[:tick tick-id]}`) — it is already filtered on tick-id in memory.

**Criteria.** In `invoke-llm-judge`, pass the judge configuration's `:criteria` (when a non-blank string) to the judge:
`executor-ctx {:inputs {:trace-data trace-data :criteria criteria}}`. Each of the four judge functions forwards it to
its `call-*-judge-llm` as a `:criteria` option; the call functions override the rubric's `:criteria` with it before
composing the instruction (`(cond-> rubric criteria (assoc :criteria criteria))`). Absent criteria → exactly today's
instruction, byte for byte.

## TDD cycle list

1. **RED** `rr31_judges_see_resolved_reads_test.clj`: through the live processor path — build a small workflow with
   `sheet/workflow` (a sequence with one `sheet/llm` node that `:reads [:ticket-message]`, `:writes [:category]`, a
   judge attached) — stub `llm/predict` for the node, capture the judge invocation with
   `with-redefs-fn {#'ai.obney.orc.evaluation.core.judge-runtime/invoke-judge …}` (the probe shows the shape), execute
   with `sheet/execute`, and assert the captured `trace-data`'s `:inputs` equals `{:ticket-message "…"}`. RED today
   (`{}`). Use `h/with-async-test-context` from `ai.obney.orc.orc-service.test-helpers` exactly as the probe does.
2. GREEN: resolve reads in `build-trace-data`.
3. **RED then GREEN** guard: a hand-built completion with no `:read-keys` but a matching started event still yields
   the started event's inputs (extend alongside `gap7-build-trace-data-reaches-back-for-inputs`, do not weaken it);
   and the reach-back's read carries the tick tag (assert on the query the event store receives, or on a store seeded
   with a same-node started event under a different tick that must NOT be returned).
4. **RED** criteria, grounding: invoke the live path with a judge declared `{:type :grounding :criteria "Every
   routing claim must cite the ticket"}`; capture the module/instruction handed to the stubbed `llm/predict` and assert
   the instruction contains the declared criteria verbatim after "WHAT TO EVALUATE:". RED today.
5. GREEN for grounding; then RED→GREEN for instruction-following, reasoning, completeness one at a time.
6. **RED then GREEN** guard: a judge declared without `:criteria` produces an instruction identical to a direct
   `build-tier1-instruction` / `build-grounding-instruction` of the unmodified rubric (byte-identical).
7. Regression: every namespace under `components/evaluation/test`, plus `ai.obney.orc.ontology.consolidator-test`,
   `ai.obney.orc.ontology.consolidation-trigger-test`, and the orc-service `judges-test`.

Propagate note (orchestrator): `allium plan` lists `entity-fields.TraceEvidence` and
`contract-signature.TraceJudge.evaluate` as already covered; the invariant is prose. Coverage line expected:
`2 obligations, 2 covered, 0 uncovered`, with the executed-workflow test as the new evidence for `inputs`.

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

- `specs/*.allium`; rubric stances, scales and bands; score gating; weights and composite aggregation; the
  heuristic-structural and custom judges; RR-30's dimension projection; the consolidator.
- What the judge is told the producer's response is (the write-map JSON framing) and the empty-instruction sentinel
  for instruction-less nodes — both are grill Q7, decided separately. Leave `(or (:instruction trace-data) …)` as is.

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; the captured `trace-data` for the executed-workflow
test (inputs shown) and one composed instruction with declared criteria; the coverage line; what you could NOT verify;
the orphan-JVM check.
