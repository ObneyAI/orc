# Retained fallback profiles and replay boundaries

This is qualified guidance from a private, unreleased candidate. The normal authoring API remains `:provider`, actual model IDs, `:fallback-models`, `:fallback-policy`, and node `:options`; authors do not construct journal or plan identities. Read [configured fallback](configured-model-fallback.md) for those fields. Verify the consuming source and loaded dependencies before applying local repair guarantees.

## Choose an actual supported request path

The experimental paired function mode is `:options {:output-mode :function-calling}` or the corresponding provider default. It currently uses the OpenRouter adapter, including vendor model names routed through it. A direct ZAI/Kimi adapter has its existing legacy function path; setting `:options {:use-function-calling? true}` on the NODE selects that path. Putting that legacy flag at the top of provider registration did not select it in the inspected implementation. Paired mode and the legacy preference have different conversion behavior; neither setting makes an unsupported schema/protocol supported. Inspect actual tools/response_format/messages before interpreting a parsing failure.

## Preserve one effective ordinary fallback plan

At the inspected ordinary repair, the runtime records resolved candidates, response-profile version and supported nonsecret controls before dispatch. Compatible backups preserve the first selected conversational profile. This prevents a provider default from changing the protocol while the task appears successful. Known `stop`, reasoning-effort and thinking settings survive retention and supported overrides; existing accepted sampling/stream/cache identifiers also remain data. Retention does not establish adapter serialization or remote support: request/user IDs were retained without a claimed wire mapping, while supported ZAI/Kimi fields were observed at the HTTP body.

Availability fallback remains separate from task repair. A typed temporary transport failure can admit the backup. A schema-valid wrong source value, malformed answer, negative assessment, denied permission or exhausted budget does not become an outage merely because another model might answer differently.

## Replay known outcomes before resolving new providers

An exact recorded terminal outcome or completed physical response can replay after provider removal, with no additional HTTP request or call reservation. The completed prefix remains completed. Physical-response persistence, candidate completion, guarded effect completion and checkpoint incorporation are distinct cutpoints: closing one store after a fully saved turn does not prove the earlier crash windows.

Within retained plan schema-version1, an unfamiliar response-profile name can replay an exact completed result; it cannot authorize an unrecorded request. Incomplete unretained legacy work or ambiguous configuration history refuses with typed compatibility evidence. Do not clear the journal, recompute an old configuration ID using new rules, or change aliases to escape that refusal. Recovery must preserve the original association and current execution ownership.

Current ordinary plan and claim commands share an atomic operation fence. That proof does not cover concurrent old binaries that lack the fence. A current-command race test is not a rolling-deployment guarantee.

## Keep researcher scopes separate

Prime fallback selection belongs to a campaign role and authored configuration across iterations and saved quanta. Each iteration's module/inputs, logical request/effect and physical attempts remain separate. Giving each iteration a new resolved configuration can lose sticky selection; using one role ID as every request ID can replay the wrong response. Inline and emitted specialists add their own effect/child ownership boundaries.

The private candidate separately verifies checkpointed prime calls with configured alternatives: compatible primary/backup requests preserve the inherited paired function contract, the selected backup remains stable across saved quanta and registry drift, and later iterations retain distinct request identities. An actual process kill after a completed backup physical response, before enclosing effect completion, recovered on the same SQLite store in a fresh process with the aliases absent. Original response metadata and identity survived without new HTTP/reservations or repeated prefix effects; repeated resume added no events.

The later private inline repair separately passed an actual SIGKILL/fresh-process physical-response proof, with the original SQLite store, request/effect identity, native values, model metadata and budget retained after registry removal. Its unchanged authored specialist configuration keeps one plan and monotonic backup selection across saved quanta; later distinct inputs still produce distinct requests/results. Current-owner and append-race controls also preserve the original cross-child selection predicate. A typed incompatible legacy plan cannot escape through generic SCI repair into a new iteration/request bucket.

An identifiable known success at a nonzero physical retry ordinal can be incorporated without inventing missing earlier requests. Contradictory receipts or competing successes refuse. Missing earlier usage is labeled partial runtime-derived accounting; original provider metadata and saved prediction remain unchanged. Inline and ordinary AI selection have separate proofs. The later ordinary AI repair independently recovers one uniquely schema-accepted saved native response at a later ordinal, respects an explicit recorded schema rejection, and refuses ambiguous physical histories. Local ordinals can restart on different node attempts. Missing earlier usage remains partial runtime-derived accounting. Failed-only normal retry traversal is intentionally unchanged; this does not establish every historical retry sequence. The earlier fixture failures remain preserved as history; the later corrected combined focused gate passes, without establishing a broad release gate.

## Explicit checkpoint opt-out is not a protocol change

Recursive research remains checkpointed by default. On the later local repair, an explicitly opted-out fresh prime or inline call with configured alternatives uses the same effective-profile planner as retained work, so a backup cannot silently revert from paired function output to markers. No extra node, per-model parser or author-side output patch is needed. Primary-only behavior and explicit empty alternatives remain verified controls. The planner enforces its supported nonsecret option allowlist and profile compatibility; runtime validation/metadata/evidence flags retain their separate predictor meaning.

This establishes fresh request consistency, **not** durable opt-out recovery. Do not disable checkpoints to work around model configuration. Verify the consuming revision includes both the retained-role work and the fresh unprepared-candidate repair before relying on either guarantee. Direct vendor adapters, hosted semantic quality, full mapped-model concurrency and multiple-server handover still require their own evidence.

## Evidence boundary

Independent ordinary verification:28focused tests/335assertions and two actual SIGKILL/fresh-JVM scenarios, using Grain, SQLite and controlled loopback responses. Corrected historical source failed the option fixture before its allowlist repair. The final executable sources and failed fixture attempts are preserved separately. These runs prove bounded serialization/recovery mechanics, not hosted semantics, unlimited concurrency, native HTTP fallback or all researcher roles. A fresh author construction test remains separate from writing this reference.


Independent prime evidence is twelve new tests / 250 assertions passing within a root 45-test / 623-assertion run that preserves the same nineteen original fixture failures. The separate actual SIGKILL/fresh-process proof passes one test / 24 assertions. These are controlled HTTP recovery proofs, not a newly measured hosted-model cohort.

## Emitted recovery: saved response versus interrupted request

The later private emitted candidate independently passed82focused tests/1402assertions and an actual SIGKILL/fresh-process proof of a saved decoded physical response (1test/33assertions). The original child and SQLite store, native nested false/zero/null values, request identity and response metadata survived with emitted provider aliases removed. Only the legitimate outer finalizer called a provider after restart; completed prefix/tool effects were not repeated, and a second resume added no events. These are controlled local HTTP mechanics, not a hosted-model cohort.

The earlier kill before an in-flight backup response was saved exposed stale child ownership at durable budget and request admission. A later private repair binds the exact resumed child to verified current-parent execution ownership while fencing old workers. Separate actual SIGKILL/fresh-process checks pass for configured alternatives and primary-only nodes, preserving ticks, cap, deadline, native results and metadata. The uncertain interrupted request remains spent; completed prefix/tool effects do not repeat. Primary-only recovery invents no fallback plan or configuration. A forced child-before-parent ordering separately observes pending authority and then converges after actual parent reclaim. The configured parent-first fixture controls that ordering; the primary-only fixture does not add a scheduler barrier. These bounded local HTTP cutpoints do not establish rolling deployments or every generated tool's idempotence.

Replay requires declared-output acceptance, not merely a predictor reporting success. In the ordinary AI journal those can differ; historical same-ordinal receipts may also belong to distinct node attempts. A unique native saved result, ambiguous history and an uncompleted physical request require separate evidence.
