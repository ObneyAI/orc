# Final integration verification

## Independently executed checkpoints

| Check | Result | Evidence |
|---|---|---|
| Repaired grading/options, identity, managed boundaries, partial budget | 13 tests, 111 assertions, zero failures/errors | /private/tmp/gepa-root-final-fields-green.log |
| Fresh SQLite preparation | 25 assertions, zero failures/errors | /private/tmp/gepa-root-repaired-cold-prepare.log |
| Separate-JVM SQLite recovery | 66 assertions, zero failures/errors | /private/tmp/gepa-root-repaired-cold-recover.log |
| Canonical GEPA Polylith gate | 36 namespace runs across two projects; 156 tests, 936 assertions, zero failure/error markers; exit 0 | /private/tmp/gepa-root-poly.log |
| ORC duplicate-delivery, metric retry, periodic recovery, durable result integrations | 6 tests, 94 assertions, zero failures/errors | /private/tmp/gepa-root-repaired-integration.log |

Allium check/analyse retains 114 informational diagnostics, 39 warnings, zero errors, and no analysis findings. Specification corrections preserve historical accounting while making managed aggregate publication non-charging, retain a complete winner at exhausted capacity, and record canonical grading and persisted reflection options.

## Open acceptance work

The live GEPA run is bounded and its raw evidence is under /private/tmp/orc-gepa-repaired-live-evidence. Captured requests carry max_tokens 65536. Initial responses are HTTP 200 with tool_calls; later requests 9 and 10 record IOException and no response. A thread capture shows the test waiting for terminal optimization state with no active HTTP request. Do not infer the IOException cause from its class alone. The existing observer records only the class; inspect durable node errors after fixture cleanup before another paid attempt. Current live assertions have not completed.

The command audit identified two hypotheses needing public-command RED proof: omitting accounting-mode may bypass managed evidence validation, and submitted feedback/output maps may differ from durable task results. Concurrent boundary admission must also be considered when fencing legacy publication. New regression work is pending the JVM slot; no acceptance claim covers these cases yet.

Named processor startup before task-manifest creation needs its new deterministic integration test. Grain's named startup catch-up already supplies the mechanism; the earlier test helper omits GEPA processor names, so that helper alone cannot establish a production recovery failure.

Full deterministic/live gates, complete obligation mapping, wire/event review, PR46 read equivalence, PR47 caching verification, and merges remain pending. No new commits or pushes have been made during these checkpoints.

## Live interruption finding

The resumability test reached a real task transport timeout: the original node completed with failure-kind transport-failure and error "OpenRouter request timed out after 30000 ms" after two HTTP attempts. GEPA then emitted task-recovery-required with workflow-failed and preserved seven charged attempts rather than inventing a grade. Its completed-status assertion failed after the test wait. The first fixture's durable events were saved in context-0.edn. Root stopped its owned JVM (PID4456, exit143) after that fixture completed to avoid further paid calls with the same insufficient transport setting; remaining test results are incomplete, not green.

Pinned OpenRouter code reads :timeout from provider configuration with a30000ms default. The GEPA live fixture now registers120000ms explicitly through an optional transport-options helper arity; other fixtures retain their original defaults. This is separate from max_tokens65536 and the GEPA metric timeout. A fresh live proof remains pending.

The additional public-command omission regression reproduced a managed aggregate validation defect: five tests,67assertions,3failures,zeroerrors. Omitting accounting-mode accepted an unfinished result and charged once more. Its minimal repair and further evidence/CAS checks are in progress; do not treat earlier managed-boundary green as covering this omitted-field path.

## Verified corrected live resumability

The targeted rerun passed one test and37assertions with zero failures/errors; JVM exited0. Log: /private/tmp/gepa-root-live-resume-timeout-fixed.log. Full capture: /private/tmp/orc-gepa-timeout-fixed-evidence. After the fixture finished, independent artifact inspection counted24requests and24responses, all with max_tokens65536 and transport timeout120000. The earlier in-flight status count of23preceded completion of the last capture.

Durable evidence contains20original tree starts,20task-call completions,4proposer-call completions,4candidate aggregates,6subsample aggregates, exactly1optimization completion,0optimization failures and0task-recovery-required events. The terminal event records final score1.0 and20charged metric calls. Reported provider response costs sum to0.09738225. This is one targeted live acceptance scenario, not the full live suite or final-commit CI.

The expanded independent deterministic checkpoint passed23tests230assertions, including named startup before manifest, healthy-owner recovery avoidance, ready aggregate/terminal guards, canonical grading and partialbudget behavior. Log: /private/tmp/gepa-root-terminal-final.log. Further completion-vs-failure terminal command arbitration tests are in progress after source inspection found that fail-optimization lacked an atomic terminal fence and complete-optimization did not reject an already failed run.


## Terminal command arbitration

Independent root execution passed 18 tests and 187 assertions with zero failures
or errors after the completion/failure command repair. Evidence:
`/private/tmp/gepa-root-terminal-pair-final.log`. The regression first failed four
assertions: an already failed optimization could complete, and an overlapping
failure could append after completion. Both commands now reject prior terminal
facts inside the event-store CAS, as well as checking the current summary.
The tests force both command orderings at the real append boundary and retain
the existing managed-result and partial-budget contracts.

A full aggregate deterministic gate is running from this repaired source at
`/private/tmp/gepa-root-final-aggregate.log`; no passing result is claimed yet.
The additional custom-metric cold-recovery driver is prepared but unexecuted.
Its new CI job runs the preparation and recovery modes in separate JVMs, because
the CLI driver is not discovered as a normal `deftest` by the aggregate runner.

Root initially passed an unsupported `--format json` option to Allium. The CLI
treated it as two paths and reported missing files. Correct invocations without
that option independently confirmed the unchanged diagnostic baseline; the
invalid invocation is not used as verification evidence.


## Final focused acceptance and CI handoff

Root independently executed the added restart modes in separate JVMs:

| Scenario | Preparation | Recovery | Evidence prefix |
|---|---|---|---|
| Missing and restored custom metric | 10 assertions passed | 40 assertions passed | /private/tmp/gepa-root-final-custom- |
| Process interrupted inside a running pure leaf | 12 assertions passed | 30 assertions passed | /private/tmp/gepa-root-final-running- |

All four processes exited zero. Custom recovery recorded attention for the missing
registration, then restored the same identity and exact feedback, with one original
tree and one charge. Running-tree recovery preserved the completed leaf's entire
event, retained its output and original tick, and resumed only the interrupted
pure leaf. This does not assert exactly-once execution of external effects.

The local aggregate was deliberately stopped to avoid duplicating the full CI
gate after the user's request to reduce repeated verification. It exited 143
after 78 completed namespaces, 687 tests and 3,668 assertions without recorded
failures or errors. It is incomplete, not a passing aggregate result. CI owns the
complete deterministic and live gates on the pushed revision. No additional
local full-suite or paid-model repetition is required before that push.

The crash manifest stores the retained event's complete printed representation
as a string, so tagged timestamps cannot manufacture an outer-manifest reader
failure. The reopened event is compared verbatim. The interrupted start's UUID
is also checked against the resumed start. The CI recovery job executes all six
preparation/recovery modes; YAML parsing verified that entry.

The aggregate CI budget is 180 minutes. The preceding completed aggregate took
approximately 118 minutes, and PR46's aggregate reached its 120-minute limit
without recorded assertion failures. This changes the job deadline, not its
selected tests or assertions.
