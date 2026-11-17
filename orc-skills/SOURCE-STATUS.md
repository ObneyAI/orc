# Source compatibility and evidence boundaries

## Mainline baseline

This collection was imported against ORC [`4966c8e35e3bb5939570bfc645eac52a8d18eb6e`](https://github.com/ObneyAI/orc/commit/4966c8e35e3bb5939570bfc645eac52a8d18eb6e). Its dependency manifest pins SIO `e61e11a9abc6fdad4e19f772d9e32e2edd2dcc3b` and litellm-clj `f5fa99d8df32d0830e972382e8fa6db233622022`. This is a source checkpoint, not a promise that later revisions preserve every behavior.

Configured model fallback is integrated on ordinary LLM, condition, decision and researcher nodes using `:provider`, `:fallback-models` and `:fallback-policy`. It supersedes historical wrapper-based fallback advice. See the [configured fallback reference](orc-model-sio/references/configured-model-fallback.md) for supported policies and recovery boundaries. Strict output profiles and described native inputs must be configured and checked through the consuming source; schema validity, transport fidelity and task correctness are separate properties.

## Historical references

Some focused references retain earlier experiment pins, including ORC `1851212`, the input-field-guide stack `9f7c2735`, and earlier SIO/client revisions. Their observations belong to those exact stacks. Do not reintroduce an obsolete workaround solely because a historical paragraph describes it. Current API guidance must be checked against the loaded implementation. Source-pinned defect observations are not claims that every later revision retains the defect.

The conceptual guide and skill references preserve useful findings and limitations from controlled-provider tests, hosted model calls, datastore readbacks and fresh-author trials. A synthetic response proves mechanics, not model quality; a replay is not a fresh hosted run; a cold readback is not in-flight crash recovery. Private archives and raw captures are excluded from this public collection. Historical results are documented observations, not independently reproduced by the packaging change.

## Unpublished or separately pending work

The prompt-cache message-boundary improvement remains unpublished at this checkpoint. Mainline cache-enabled function/strict-JSON requests put the marked static instruction and dynamic data into two parts of one user message. A separately tested candidate puts them in two user messages under the same `:prompt-cache?` flag, without promoting instruction authority. Do not implement per-model parsing to imitate it or assume it has been adopted. See [cache boundaries](orc-prompt-caching/references/cache-boundaries.md).

[ORC PR #50](https://github.com/ObneyAI/orc/pull/50) remains open at this checkpoint; its timeout classification and fixture corrections are not part of the baseline above. Other references explicitly describing local prototypes or repairs remain qualified experiments unless the current source establishes adoption. This documentation import neither merges those changes nor changes runtime behavior.

## Maintaining the public collection

Maintain these skills in this repository alongside ORC changes. Update the owning reference and this compatibility summary when adoption changes. Keep supported recipes separate from historical evidence, and preserve negative results and known limits. The separate research library may retain private experiments; it is not a runtime or documentation dependency of this collection.
