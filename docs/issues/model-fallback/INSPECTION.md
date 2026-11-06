# Configured fallback acceptance record

Status: accepted for standard-node fallback. The wrapper-only sections below record earlier work; the final standard-node acceptance supersedes that scope.

## Obligations

4 obligations, 4 covered, 0 uncovered.

| Obligation | Required observable evidence | Current disposition |
| --- | --- | --- |
| MODEL-FALLBACK-1 | Public constructor validation; common contracts/privacy; candidate override boundaries; build/export/snapshot preservation; overlapping configurations | Verified by the focused public configuration/runtime tests |
| MODEL-FALLBACK-2 | Ordered three-candidate recovery with valid false; full exhaustion with safe reasons; distinct temporary/all-transport policies; permanent/schema/unknown denial | Verified by the focused public configuration/runtime tests |
| MODEL-FALLBACK-3 | Tenant/tick/item isolation, exact canonical input and write attribution; duplicate/stale delivery; no completed effect replay | Verified by the focused public configuration/runtime tests |
| MODEL-FALLBACK-4 | Physical retry counts; actual family call budget and original absolute deadline refusal | Verified by the focused public configuration/runtime tests |

## Independent inspection procedure

Read source changes, public test entry points and full request/result evidence. Check that each claimed path actually runs, no assertion merely matches non-success, and the harness exits nonzero on failure. Re-run the focused proof once on the final source. Local HTTP responses are deliberately scripted failure fixtures, not semantic model evaluation; real SIO serialization, Grain commands, datastore and projections must remain in the path. No mocked router/predict call counts as HTTP evidence.

Compare spec and implementation in weed check mode. Classify all divergences: spec bug, code bug, aspirational design or intentional gap. Keep pending obligations unchecked. Verify no existing test was narrowed and no implementer changed the root-owned Allium spec. Preserve safe typed error evidence without logging arbitrary request-bearing exception data.

Check the durable and optimized execution paths. A deliberate durable boundary for configured fallback is acceptable; an ungated optimized path is not. Inspect source attribution for mapped outputs rather than trusting shared blackboard values. Repeated delivery controls must prove no additional provider start, not only equal final output.

Fresh-agent skill reproduction follows the public recipe only, without copying a private application admission function. It must configure models and inspect exact selection/failure reasons through public APIs.

## Specification checkpoint

Standalone `orc-service.allium`: zero errors, 2 existing warnings and 43 informational diagnostics. Analyse: zero findings. These are baseline diagnostics, not a clean warning-free result. A full-spec final comparison remains pending.

## Scope limits

Ordinary blocking model generation only. No streaming failover, production deployment, external-provider availability/quality, arbitrary native-client classification or multi-server load claim. No SIO change is assumed. Existing CI covers the wider regression gate after the reviewed change is pushed.

## Findings during implementation

The configuration checkpoint exercised public build/publish/export/rebuild and reported 3 tests, 58 assertions, 4 failures, 0 errors. Published snapshots lost candidate options and the new policy; DSL export/rebuild lost options; an unknown top-level `:polciy` key was ignored. These are code defects against MODEL-FALLBACK-1, not model failures. The original log is `/private/tmp/model-fallback-proof/configuration-01.log`. Fix and verification pending. The configuration harness corrected its rebuilt workflow identity key from `:name` to `:workflow-name`; this was a harness correction, not a product fix.

Root source inspection also requires canonical completion ordering after the latest owned candidate start, runtime validation of imported/manual policy trees, and preservation of ordinary fallback definition hashes. New policy validation must not rely only on the convenience constructor.

The corrected configuration checkpoint passed 5 tests/83 assertions, including four local HTTP requests with overlapping old/new published versions. Further malformed-retry assertions and the shared-store tenant proof remain pending final acceptance.

Fresh-agent forward test: the corrected standalone recipe exited zero, with primary503→backup valid false (2 requests) and primary401 refused (1 request), upstream action once each. Root independently parsed its saved EDN, asserted requested model order and HTTP status, and compared the public composite diagnosis with canonical completion. The recipe did not read feature implementation/tests. Its first run omitted required query envelope metadata and edited its script during loading; those harness errors are recorded separately and not counted as accepted evidence. The skill must show query ID/timestamp, full projected trace paths and bounded projection waiting.

The first deadline witness failed its exact expected-error assertion: the client timed out with provider-error and no HTTP status, so the temporary-only policy refused candidate0. Root inspected the saved completion records; this is expected fail-closed policy behavior, not proof of next-candidate deadline admission. The expiry witness is being changed to the explicit all-transport policy so it can exercise that admission boundary. No production classifier change is warranted by this harness finding.

The first root combined run executed 11 tests/287 assertions: 286 passed and one
failed because the new test expected a completed trace after family budget
cancellation. Root inspected both ticks: each is cancelled and has the exact
`LLM call budget exceeded: 1/1` reason in its durable cancellation event; the
public execute result carries the same error. This is a test translation bug,
not a lost provider failure. The corrected assertion requires the public error
and matching cancellation reasons in both ticks, while other controls still
require their exact public trace diagnosis. No existing assertion was removed.

## Final independent acceptance

The corrected root run exited zero: **11 tests, 288 assertions, zero failures or
errors** across `configured-model-fallback-test`,
`configured-model-fallback-configuration-test`, and the existing
`scoped-model-alternative-e2e-test`. The asserted fixture counts total 39 local
HTTP requests: 7 base controls, 14 ordered/mapped/retry requests, 4 replay/expiry,
4 overlapping-version requests, 3 shared-store tenant requests, and 7 existing
regression requests. No paid model calls. The JVM exited normally.

Root inspected source and full saved events. Actual deadline refusal appears on
the second candidate with `Execution deadline exhausted before provider attempt`,
while only the first candidate sent HTTP. Concurrent same-event replay goes
through Grain's real processor; its atomic result-event/checkpoint append supplies
the CAS boundary. Each mapped item retains its exact canonical task inputs and
attributed outputs. Shared-store tenant checks use identical sheet/node IDs with
distinct live task IDs, proving one tenant's failure does not advance the other.
Overlapping published versions keep their respective model lists and one
preceding fixture effect each. The option-preservation proof is not a guarantee
that arbitrary provider privacy settings reach or are honored by every backend.

Weed check: the three added LeafExecutor invariants map to public constructor and
import validation, the exact canonical completion/transition handler, inherited
provider admission hooks, and recorded/projected outcomes. The identified
configuration code defects are fixed. The cancelled-tick trace expectation and
the original expiry witness were test translation bugs, corrected against actual
events. No unresolved divergence remains within this slice; streaming, custom
native classification, production migration and model quality are intentional
scope exclusions. No subagent edited the root-owned Allium specification, and no
existing test assertion was weakened.

Full-spec independent check/analyse: zero errors, 39 baseline warnings and 114
informational diagnostics, zero analysis findings. Patch hygiene passes. CI owns
the broader suite; no repeated local aggregate run was used for this slice.

Evidence archive: canonical `orc-skills/experiments/model-fallback/configured-models/`.
The acceptance log, source hashes, synthetic HTTP/event receipts and fresh-agent
report are retained there. Library tests themselves contain the durable
regression assertions and no hardcoded private artifact output path.

## Standard-node revision — initial findings (historical)

The earlier wrapper acceptance did not establish this revised scope. The findings below preceded the final integrated verification recorded in the next section.

Initial findings:
- Published researcher snapshots omitted researcher settings; preserving new fallback fields exposed and repaired this existing omission.
- Runtime snapshot decoding separately dropped those settings; configuration proof and execution proof are distinct.
- Duplicate active starts sent no duplicate HTTP but could poison the original result; this must be repaired, not relaxed in the test.
- Researcher sticky selection scope initially omitted mapped execution context. Root inspection requested per-run isolation and stable configuration hashing.
- Inline generation options previously did not reach execution. Once strict options did, the inline helper's unconditional `:any` output schema failed preflight. It now needs declared blackboard contracts, not weaker strict validation.
- The pinned client's asynchronous HTTP503 failure lost status. Root reproduced via local HTTP (one failed status assertion), then repaired safe structured evidence in an isolated client checkout. Root loopback test passed 1 test/5 assertions; an added category assertion awaits the final run.
- Provider preflight passed 1 test/7 assertions after correcting the root's missing test classpath. No network calls were used for preflight.

Source and artifact locations: ORC `/private/tmp/orc-fallback-pr`; client `/private/tmp/litellm-node-fallback`; focused logs under `/private/tmp/fallback-*`, `/private/tmp/standard-fallback-*`, `/private/tmp/researcher-fallback-*`. These are temporary execution artifacts, not a promoted portable proof archive yet.

## Standard-node independent acceptance

Root independently executed the final integrated set: **20 tests, 371 assertions, zero failures/errors**, process exit0. Source and client SHA256 manifests matched after execution. Configuration, ordinary/decision/condition, researcher, journal, stream and original scoped-alternative regressions ran together. Allium check/analyse retained 39 baseline warnings/114 information, zero errors/findings. No broad repository suite was rerun.

5 engine obligations covered, 0 uncovered within NODE-FALLBACK-1 through 5. Fresh-agent recipe NODE-FALLBACK-6 follows separately. Restart evidence is actual SQLite close/reopen and new runtime after a saved quantum, not a hard-killed separate process. Controlled providers establish mechanics, not model quality or production capacity.

Resolved code bugs: snapshot publication/decoding omissions, inline option/schema propagation, missing failure projection fields, active duplicate poisoning, mapped selection scope, unstable fingerprints, journal vector projection, physical receipt recovery, stream status loss and missing provider identity. Test/harness corrections: old child-leaf assertions moved to journal records, required query/command envelopes, classpath/loopback permissions, stale client override, and an unsuitable truncated-stream fixture. No assertions were relaxed to conceal those failures. Root strengthened the SSE check after a superficially passing status test hid rejected completion events.

The companion client changes live at `/private/tmp/litellm-node-fallback`, based on 2b6781b8f98105fe07d17b289138103e5dd3a047. Integrated proof used an explicit local dependency override. The reviewed published client pin must replace that override before release. SIO is unchanged. Publication now uses client commit `bc65561e974d5e35b80d3f842e190231476fdd10`, reviewed in https://github.com/ObneyAI/litellm-clj/pull/3. The owner authorized publication after local acceptance; neither PR is merged.

Fresh-agent acceptance completed independently: ordinary503 selects backup (2 HTTP), auth401 refuses (1), recursive prime emits a specialist that inherits its backup and finishes (4). Root inspected saved results, exact model order, public refusal evidence and child history. The recipe gap was a missing query-envelope example; the portable reference now includes it. **6 obligations, 6 covered, 0 uncovered** within the specified fixture scope.

## Published dependency checkpoint

The focused check against the Git dependency at `bc65561e974d5e35b80d3f842e190231476fdd10`, without a local override, passed **5 tests, 37 assertions, zero failures/errors**. It covers ordinary, decision, condition, researcher prime/inline/emitted fallback and streaming. The loaded resource resolved from the exact Git SHA. This supplements the 20-test integrated acceptance above.
