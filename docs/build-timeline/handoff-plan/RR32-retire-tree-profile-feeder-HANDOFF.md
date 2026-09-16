# RR-32 handoff — Retire the automatic tree-profile feeder; close the classifier's unknown-dimension hole

Issue: `docs/issues/rr-durable/RR-32-retire-the-automatic-tree-profile-feeder.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc` (branch `feature/rr-durable-self-learning-arc`). Run
tests with `JVM_XMX=1600m zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`; a copy of the runner is at `.rr-durable-notes/run-focused.sh`). ONE JVM at a time; never kill a
JVM whose working directory is not this worktree (another team's benchmark runs on this machine); confirm 0 orphan JVMs
after every run. Do not run `poly test` or any whole-brick build (the orchestrator runs the gates). Do not commit, push
or stash. Never edit `specs/*.allium`.

## Goal

Remove the code that pretends `:evaluation/trace-evaluated` exists (no producer has ever emitted it; RR-29 deleted its
last schema declaration), and stop the ontology classifier from emitting a failure with no URI for a dimension name it
does not know. The manual tree-profile path stays byte-identical.

## Read first

1. Spec excerpt, verbatim (`specs/ontology.allium`, the comment that replaced the two retired rules):

       -- Tree profiles are fed only by the consumer-facing strength and weakness
       -- recording commands. The automatic feeder that once classified a judged
       -- trace into a weakness and a high score into a strength was retired: it was
       -- triggered by an evaluation event that no producer ever emitted, and judge
       -- feedback reaches the loop through the consolidator's living descriptions
       -- instead (grill decision D6).

   `entity TreeProfile` (line ~555) and `rule ExtractLearnedRules` stay and must remain green.
2. `components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj` lines 71–181: `transform-evaluation-result`
   (private, one caller), `on-trace-evaluated`, `on-high-scoring-trace`, and the `defprocessor :ontology
   on-trace-evaluated` registration on topic `#{:evaluation/trace-evaluated}`. Delete all four. Nothing else in that
   file changes.
3. `components/ontology/src/ai/obney/orc/ontology/core/discovery.clj`: `get-low-scoring-evaluations` (reads the dead
   event, line ~42), `format-evaluation-for-discovery`, `load-failure-concepts`, `format-traces-for-analysis`,
   `build-discovery-workflow!`, `discover-patterns`. The whole namespace exists to analyse the dead event; delete it,
   the `run-pattern-discovery` command (`core/commands.clj` ~line 763, emits `:ontology/failure-subtype-discovered`),
   and the interface exports `get-low-scoring-evaluations`, `build-discovery-workflow!`, `discover-patterns`
   (`interface.clj` ~1970–2018). Check `interface/schemas.clj` for the command and event schemas and remove them if
   nothing else references them. Repo-wide search first: no test references any of these today (verify).
4. `components/ontology/src/ai/obney/orc/ontology/core/classifier.clj` lines 19–26 (`dimension->failure-uri`, six
   names) and 125–170 (`classify-evaluation`): a low-scoring dimension whose name is absent from the dictionary still
   produces `{:uri nil :base-uri nil …}`. Skip it instead. `find-specific-subtype` on a nil base-uri is the same hole.
5. `components/ontology/src/ai/obney/orc/ontology/core/static_ontology.clj` line ~524
   `get-failure-concept-for-dimension`: a four-entry `case` that does not know "Source Grounding" / "Reasoning
   Quality". Make it answer from the classifier's dictionary (or move the dictionary to one shared place both read) so
   they cannot drift. Its tests: `components/ontology/test/ai/obney/orc/ontology/core_test.clj` lines 60–66 and 282 —
   keep them green as written (they assert the four short names and nil for "Unknown").
6. `components/evaluation/src/ai/obney/orc/evaluation/core/judges.clj` `default-judge-dimension-names` (RR-30) — the
   live judges' dimension names ("Source Grounding", "Instruction Following", "Reasoning Quality", "Completeness"), and
   the RR-30 guard `rr30-default-judge-dimension-names-are-known-to-the-ontology-classifier` in
   `components/evaluation/test/.../rr30_default_judge_dimensions_test.clj` — must stay green.
7. Keep untouched: `defcommand :ontology classify-evaluation` (`core/commands.clj` ~317, public, still valid without the
   feeder), `record-tree-strength` / `record-tree-weakness`, `retrieval.clj`, `rule_extraction.clj`, the tree-profile
   read models and `docs/PATTERN-RECORDING.md`.

## TDD cycle list

1. **RED** `rr32_no_trace_evaluated_feeder_test.clj` (ontology test dir): assert no processor in
   `@tp/processor-registry*` (after requiring `ai.obney.orc.ontology.core.todo-processors`) declares topic
   `:evaluation/trace-evaluated`; assert the vars `on-trace-evaluated`, `on-high-scoring-trace`,
   `discovery/get-low-scoring-evaluations`, `discovery/discover-patterns` and the interface exports do not resolve
   (`ns-resolve` / `find-ns` nil); assert `:ontology/run-pattern-discovery` is not in the command registry. RED today.
2. GREEN: the deletions in read-first 2 and 3.
3. **RED** classifier: `classify-evaluation` with `{:score 0.2 :dimensions [{:name "Novelty" :score 0.1 :feedback "…"}
   {:name "Source Grounding" :score 0.1 :feedback "…"}]}` returns exactly one failure, for Source Grounding, with a
   string `failure:` URI, and `:primary-failure-uri` is that URI. RED today (two failures, one with nil URI).
4. GREEN: skip unknown names in `classify-evaluation`.
5. **RED** dictionary parity: for every key of the classifier's dictionary, `static/get-failure-concept-for-dimension`
   returns the same URI; for "Unknown" both return nil. RED today ("Source Grounding" → nil in static).
6. GREEN: one dictionary.
7. Regression: `ai.obney.orc.ontology.core-test`, `consolidator-test`, `consolidation-trigger-test`,
   `description-events-test`, every namespace under `components/evaluation/test`, and the orc-service
   `deterministic-ontology-e2e-test` and `end-to-end-integration-test` (the latter is gated and will skip; that is
   expected — say so).

Propagate note (orchestrator): the retired rules emit no obligations any more; `entity-fields.TreeProfile` and the
`ExtractLearnedRules` obligations stay green. Expected coverage line: `N obligations, N covered, 0 uncovered` for the
TreeProfile / learned-rules set — report the exact numbers `allium plan specs/ontology.allium` gives for those ids.

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

- `specs/*.allium` (already tended); `TreeProfile`, its manual commands, read models, retrieval, rule extraction,
  profile embedding, PATTERN-RECORDING.md; the consolidator and living descriptions; the judge runtime; RR-30's
  dimension vocabulary.

## Report back

Files changed and deleted; each cycle's RED and GREEN `RESULT` lines verbatim; the classifier's output for the cycle-3
input; the coverage line; anything in `docs/*.md` that still documents the deleted discovery path (list, do not edit —
the orchestrator rewrites docs); what you could NOT verify; the orphan-JVM check.
