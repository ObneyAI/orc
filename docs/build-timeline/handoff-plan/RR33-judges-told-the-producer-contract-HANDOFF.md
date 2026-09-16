# RR-33 handoff — Judges are told the producer's contract

Issue: `docs/issues/rr-durable/RR-33-judges-told-the-producer-contract.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc` (branch `feature/rr-durable-self-learning-arc`). Run
tests with `JVM_XMX=1600m zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`; a copy of the runner is at `.rr-durable-notes/run-focused.sh`). ONE JVM at a time; never kill a
JVM whose working directory is not this worktree (another team's benchmark runs on this machine); confirm 0 orphan JVMs
after every run. Do not run `poly test` or any whole-brick build (the orchestrator runs the gates). Do not commit, push
or stash. Never edit `specs/*.allium`.

## Goal

Two things the judge is told are misleading (grill D7; live evidence in `.rr-durable-notes/rr31-e2e-live-run.log`,
Phase 6): the response is described as free-form producer output when it is the node's typed write map, so an
instruction-following judge docks a sentiment node for "returning a JSON object, not a single word"; and a code node's
task is an empty string, because the DSL's `code` takes no instruction (`orc_service/core/dsl.clj` line ~197) and the
runtime substitutes `""`, so the judges' "No instruction provided" fallback never fires. Fix what the judge is told.
Do not reshape the data, do not change scoring.

## Read first

1. `components/evaluation/src/ai/obney/orc/evaluation/core/judges.clj`:
   - `build-grounding-module` (~line 204): the three typed inputs and their descriptions; `:response` reads "The
     producer's output to evaluate for grounding."
   - `build-tier1-module` (~line 330): same for instruction / response / inputs (+ iteration_evidence).
   - `build-grounding-instruction` (~180) and `build-tier1-instruction` (~313): the composed instruction text ("You are
     given three inputs: `source` … `response` (what the producer wrote) …"). The description of what `response` IS
     may live here as well as in the module input description — both must agree.
   - `call-grounding-judge-llm` (~378) and `call-tier1-judge-llm` (~407): `response` is `(json/generate-string
     (:outputs trace-data))` when outputs is a map; `producer_instruction` / `instruction` is
     `(or (:instruction trace-data) "No instruction provided")` — never reached because trace-data carries `""`.
     RR-31 added the `:criteria` kwarg here; it is the declared criteria to fall back to.
2. `components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj` `build-trace-data` (~line 133):
   `:instruction (or (:instruction node) "")`. The node's declared writes are the keys of `:outputs` (resolved from
   the value log) and the completion event's `:write-keys`.
3. RR-31's tests in `components/evaluation/test/ai/obney/orc/evaluation/rr31_judges_see_resolved_reads_test.clj` —
   the capture pattern (`with-redefs [llm/predict …]` capturing `module` and `inputs`) and the byte-identical guards
   `rr31-grounding-judge-no-criteria-instruction-byte-identical` / `rr31-tier1-judge-no-criteria-instruction-byte-identical`,
   which must stay green for a node WITH an instruction.
4. `docs/EVALUATION-COMPONENT.md` "Custom Rubrics and Workflow Judges" — the documented meaning of `:criteria`.

## The exact change

**Response framing.** In both module builders, describe `:response` as: the producer's declared output fields as a
JSON object, one value per field; the field set is fixed by the workflow's typed blackboard, not chosen by the
producer; judge the values, not the object shape. Say the same in the two instruction builders where they explain the
inputs (keep everything else in those templates byte-identical — the guards check the instruction-present case).

**Task composition.** Carry the node's declared writes on trace-data (e.g. `:write-keys`, from the completion event or
the resolved outputs' keys) and let `build-trace-data` pass `:instruction` through as nil when the node has none
instead of `""`. In `call-grounding-judge-llm` and `call-tier1-judge-llm`, compose the task as: the instruction when
non-blank; else the declared `:criteria` when non-blank; else `"Produce the declared output fields: k1, k2, …"` from the
write keys; else the existing "No instruction provided" sentinel. Same rule in both.

## TDD cycle list

1. **RED** `rr33_judges_told_the_contract_test.clj`: instruction-following judge, capture the module; assert the
   `:response` input description says the fields are declared by the workflow and the judge should not grade the object
   shape (assert on two exact phrases you choose and keep). RED today.
2. GREEN: the module and instruction-builder descriptions for tier-1; then RED→GREEN the same for grounding.
3. **RED** task, no instruction + criteria: drive the live path (RR-30/31 test driver) with a `:leaf` node created
   without an instruction and a judge declared with `:criteria`; capture `inputs`; assert `:instruction` (tier-1) /
   `:producer_instruction` (grounding) equals the criteria. RED today (`""`).
4. GREEN.
5. **RED** task, no instruction, no criteria: assert the task names every declared write key and is not blank. GREEN.
6. **Guard** (RED then GREEN if you can make it RED; otherwise report it as a lock-in guard): a node WITH an instruction
   still sends exactly that instruction; RR-31's byte-identical guards stay green.
7. Regression: every namespace under `components/evaluation/test` plus `ai.obney.orc.orc-service.judges-test`.

Propagate note (orchestrator): no new obligations; `contract-signature.TraceJudge.evaluate` and
`entity-fields.TraceEvidence` stay green. Expected coverage line: `2 obligations, 2 covered, 0 uncovered`.

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

- `specs/*.allium`; rubric stances, scales and bands; score gating; weights and aggregation; RR-30's dimension
  projection; RR-31's read resolution and criteria threading (build on them); the heuristic-structural and custom
  judges; the consolidator; anything outside `components/evaluation/`.

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; one captured module (the response description) and
the three composed tasks (instruction / criteria / declared writes); the coverage line; what you could NOT verify; the
orphan-JVM check.
