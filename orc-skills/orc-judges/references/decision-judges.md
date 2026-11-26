# Score-only decision judges

## Select the appropriate model path

A decision model is useful when the needed output is a finite grade and no new
prose is required. Use an ordinary LLM judge for explanations, arbitrary evidence
extraction or new written feedback. A custom BT can use both: evidence collection
by tools/LLMs, bounded semantic scoring, and an optional independently requested
feedback step whose provenance remains distinct.

Jev's native Score primitive consumes application state and typed questions.
Assigning its ID to an ordinary ORC chat node does not implement that protocol.
Invoke an injected native client from a `code` leaf, or delegate to a reusable
judge specialist wrapping it. Use [orc-decision-models](../../orc-decision-models/SKILL.md)
for transport/client ownership and finite-choice policies.

## Construct a described score question

Pin the requested and resolved model, endpoint and question schema. The documented
OpenRouter route is `POST https://openrouter.ai/api/v1/systemone`, model
`typesafe/jev-1.13`, using the inherited OpenRouter key. Verify real account access
before asserting availability. Never persist that key or auth headers.

A Score question has `type: "score"`, a criterion in `instructions`, and an ordered
`criteria` array of two to ten self-contained descriptions. The application state
contains the immutable producer task, artifact and evidence. Question IDs correlate
answers; their names do not supply semantic instructions.

Send one question per quality dimension. Independent questions about one evidence
snapshot can share a native request. That is provider batching, distinct from ORC
parallel nodes and concurrent effects. Dependent checks require another step after
new evidence exists.

## Validate and map the response

Check expected answer IDs, type, finite values, complete band-index probabilities,
range, returned mass and consistency with the legend/ordered descriptions. Compare
every legend entry with the submitted rubric; a valid grade against a different
legend is not the requested judgment. Preserve the original response and declare
representation-aware rounding tolerances. Invalid or missing responses
fail assessment validation; they do not indicate low work quality.

Native wire probabilities and the expected position can be rounded independently.
A valid five-band response can have returned mass 0.99; the provider expectation
can differ slightly from the weighted position calculated from rounded entries.
Strict exact-sum or a floating-point boundary comparison can falsely reject such
responses. Distinguish grossly invalid distributions from bounded quantization.
Choose tolerances from the provider's documented or verified representation, the
number of levels and probability precision. Retain returned mass, the provider's
expected position and the position calculated from the raw entries separately.
Do not silently renormalize or overwrite the native evidence to make validation pass.

For five bands, let `i` be the zero-based index with greatest probability:

- Main band: `i + 1`.
- Main unit score: `i / 4`.
- Secondary weighted score: native expected position divided by four.
- Retained evidence: all probabilities, native expectation, confidence and model.

An exact maximum tie in the returned probabilities is ambiguous and has no primary grade under this policy.
Do not select an arbitrary tied outcome or round the expectation. For example,
equal probability on lowest and highest bands has the same expectation as a
certain middle band, but a different assessment.

Confidence expresses distribution concentration, not verified probability of being
correct. Thresholds and near-tie policies require task-specific held-out evaluation;
do not invent a universal safe confidence threshold.

## Feedback and persistence

Jev does not generate explanation prose. Record feedback as absent in the owning
application contract. At the inspected ORC pin, native custom score-only workflows
are accepted but stored with empty-string feedback; this is compatibility behavior,
not an explicit optional-feedback guarantee. Preserve feedback-presence metadata
and exclude those records from feedback-required consumers.

Native code calls do not automatically contribute to ORC LLM usage accounting.
Capture their true request/response and persist attempt, usage, cost when supplied,
elapsed time and assessment identity through the application seam. Multiple rubric
questions in one request differ from multiple physical calls.

## Evidence status and sources

Native request mapping and band/tie validation are deterministic integration
contracts. Synthetic responses prove those mechanics only. Live model performance,
access, speed and cost require actual requests and independent reference judgments.
The inspected campaign executed 25 construction examples with each model and
real score-only ORC workflows with repeated executions and independent group
questions. These demonstrate actual plumbing and measured behavior on the small
examples. Construction ranges are not human-gold labels or held-out accuracy;
custom judge structures still need task-specific semantic evaluation.

[TypeSafe Score documentation](https://docs.typesafe.ai/primitives/score) specifies
the native distribution, weighted position and independent level descriptions.
[OpenRouter Jev documentation](https://openrouter.ai/docs/guides/community/jev)
specifies its access route and typed-model role. Recheck model availability and
request schema when configuring a production deployment.

## Preserve native wire keys across ORC writes

The pinned leaf validation applies a keyword key transformer. A code-node result containing `{"0" ... "4" ...}` under an explicit Malli `[:map ["0" ...] ...]` probability schema failed a real tick because the required string keys were missing after normalization. Validate the native response before adapting it. Carry probabilities as typed records such as `{:index 0 :probability 0.52}` in a vector, and preserve the original answer/probability map as JSON text or an immutable transport receipt. Verify the actual board value and projection. Direct Grain result recording is a separate boundary and must be tested independently. This is a verified compatibility issue at this pin; do not silently mutate the original native evidence.

## Registered provider distinction

At ORC `1851212396cced2fc4533fda30b824bd4b691390`, registered native boolean/Choice providers use ordinary execution accounting through `llm-decision`/`llm-condition`. This Score protocol still requires an explicit custom adapter and accounting. Choice routing validates selected answers within reported quantization and can accept rounded ties; the unique-maximum policy above belongs to this score-only judge, not every finite router. See [current Jev integration](../../orc-decision-models/references/jev.md).
