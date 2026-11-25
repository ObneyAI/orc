# Compose custom judge behavior trees

## Select the smallest adequate tree

| Structure | Appropriate use | Important seam |
| --- | --- | --- |
| One semantic grade plus validation | Evidence is already complete and criterion is narrow | Model chooses band; code validates and maps |
| Audit → grade → validate | Explicit evidence inventory helps review | Audit is fallible evidence, not authority |
| Delegate to specialist | Reusable criterion or evidence expertise | Typed parent/child reads and writes |
| Independent parallel checks → reconcile | Criteria need independent reviews of one immutable snapshot | Each branch owns distinct outputs |
| Retrieve → per-claim checks → final band | Source-intensive factual assessment | Missing checks remain in coverage |

Each shape is a real BT. A specialist can delegate again or route to evidence tools.
Sequence steps that depend on earlier findings or new evidence. Parallelize only
independent work, preserve attribution, and assemble findings before the final
holistic grade. More steps or consensus do not inherently improve judgment.

Use fallback only for declared recovery. A provider error must not fall through to
a successful highest grade. Bound retries, alternative retrieval and disagreement
resolution. Never erase failed checks from the denominator.

## Define evidence before node assembly

An application-owned packet should describe:

- Scope and exact subject/execution identities.
- Exact producer instruction and delivered artifact.
- Resolved producer inputs and source contents.
- Actual tool effects or immutable references that evidence tools can resolve.
- Evidence readiness and limitations.
- Rubric identity/revision and permitted assessment use.

Validate subject and receipt ownership at the evidence boundary: a nonempty-looking map alone does not establish tenant, execution, artifact or effect identity. Trusted internal assembly and acceptance of externally supplied evidence require different verification.

Use nested typed schemas, descriptions and bounded collections. JSON text can
carry an exact recorded value when the integration boundary requires text, but
describe its role clearly and preserve the original structured artifact separately.
Do not use `:any` to avoid thinking about the evidence contract.

## Ordinary ORC nodes

Build `workflow`, `blackboard`, `sequence`, `llm`, `code`, `parallel` and `delegate`
through `ai.obney.orc.orc-service.interface`. Give each LLM a leading scoped
rationale write, followed by its typed result: for example,
`[:source-review-rationale :source-audit]`, `[:task-review-rationale :task-audit]`
and `[:grading-rationale :grade]`. A rationale is a concise explanation tied to
observable evidence, not a request to expose private deliberation. Grade fields
include supporting evidence, a selected discrete `:level` with a provider-compatible wire schema, and useful feedback.
A code leaf validates those values and writes `:score`,
`:feedback` and optional fully populated `:dimensions`.

Use function calling for structured nested outputs where supported, and verify the
parent blackboard projection contains a parsed value. Send models only supported
parameters. Describe criterion and band meanings in model-visible instruction or
typed rubric inputs; verify the actual provider payload rather than assuming every
input description is rendered in every SIO mode.

For a delegate, build the specialist first, pass its actual sheet ID and declare
parent/child reads and writes.
The public build returns the sheet UUID directly; `get-root-node` exposes its
identity under `:id`. Verify these actual values instead of guessing result keys:

```clojure
(let [sheet-id (orc/build-workflow! ctx specialist-workflow)
      root (orc/get-root-node ctx sheet-id)]
  (assert (uuid? sheet-id))
  (assert (uuid? (:id root)))
  {:sheet-id sheet-id :node-id (:id root)})
```

 Give reusable judges stable versioned names. Changing
a workflow under the same name can rebuild nodes and disturb separate attachment
configuration. Read back the registered definitions and attachments.

Independent parallel audits can write `:source-audit` and `:task-audit`; a later LLM
selects a band from both and the original evidence. Scope every reasoning/output
key independently. Declare every rationale/result field with a concrete schema
and model-visible description. For parallel per-item checks, keep a primitive explicit-write
leaf; do not assume a composite map child collects results safely.

## Current native custom-judge adapter

Build the evaluation workflow first. Declare its host evidence fields exactly:

- `:host-inputs`: the assessed leaf's recorded read values.
- `:host-outputs`: its recorded write values.
- `:host-instruction`: the recorded instruction, possibly empty for code leaves.
- `:host-trace`: a node-ID map, or a vector containing that map at the inspected pin.

Type these fields for the actual host leaf. For example, if that leaf reads `:packet`
and writes `:data`, declare maps containing those specific keys. An adapter code
leaf copies the actual `:data` into the evaluation packet's response and forwards
it to a delegated judge. Do not grade a stale draft carried in input `:packet`
when the host's actual output differs.

The custom workflow writes a numeric unit `:score` and string `:feedback`; dimensions
need names, weights, scores and feedback. Validate all dimensions yourself because
current native normalization supplies invented defaults for missing fields.

Declare a `:custom` judge with the built evaluation `:sheet-id`; attach its name to
an eligible leaf. Public command attachment names are strings. The DSL can carry
judge declarations and `:judges`, but runtime enablement still needs verification.
See [evidence and runtime](evidence-and-runtime.md) for the flag limitation.

## Source-pinned dependency starting point

```clojure
{:paths ["src"]
 :deps {obneyai/orc-evaluation
        {:git/url "https://github.com/ObneyAI/orc.git"
         :git/sha "490816267b76b0428c53437052980a25f5905209"
         :deps/root "projects/orc-evaluation"}}}
```

This supplies the evaluation and service components at the inspected revision;
the consumer supplies its runtime, store and provider configuration. It is a
reproduction starting point, not a declaration that this revision satisfies all
intended judging contracts. Adding ontology packages can require aligned dependency
overrides when tools.deps resolves shared component names through different Git
worktrees. Inspect the resolved graph before attributing a dependency error to a BT.

## Methods for larger judges

[FActScore](https://arxiv.org/abs/2305.14251) suggests decomposing factual claims and
checking retrieved evidence; its claim precision differs from completeness and
holistic task quality. [ChatEval](https://arxiv.org/abs/2308.07201) studies evaluator
roles and bounded discussion; extra rounds did not uniformly improve results.

These are design inspirations. A retrieved-evidence judge and debate judge need
their own live validation before being presented as proven ORC quality improvements.

## Verify ordinal wire compatibility

A valid local schema can still be incompatible with a provider's tool schema. A controlled Gemini/OpenRouter run returned empty `submit_response` arguments for an integer enum. Repeating the same request reproduced it; changing only the level schema to a bounded integer produced complete grade/evidence/feedback. For that tested path, use `[:int {:min 1 :max 5}]` plus all five model-visible band descriptions and strict post-response integer/range validation, or a provider-supported string enum with an explicit host mapping. Both retain finite band selection. Preserve the failed request and control; do not infer a general model failure from empty arguments or claim every provider behaves identically.

## Reject provider contract violations explicitly

A live provider returned nested `document_id` values where its submitted tool
schema required `document-id`. Public ORC/SIO validation rejected the artifact;
the assessment retained unavailable evidence without inventing a band. An explicit
canonical field vocabulary across schemas, model descriptions and host identity
checks made the captured response valid without renaming arbitrary response keys.
This does not prove hyphenated names are universally unsupported. Preserve the
original contract and failing wire receipt when adapting a specific integration.

A separate parallel source reviewer returned HTTP 200 with
`MALFORMED_FUNCTION_CALL` and no content or tool arguments. One successful sibling
cannot substitute for that failed check. Keep the holistic grade unavailable,
inspect the full credential-free provider envelope, and use a declared bounded
recovery or explicit model configuration change. Replaying exact failed responses
through public LLM/SIO/ORC boundaries is more informative than a synthetic success
fixture. A later recovery run is separate evidence, not a successful original run.

## Inspect registered contracts, not just the builder source

After building every parent and specialist, read its actual blackboard definitions
through `orc/get-blackboard-by-key`. Confirm every schema has its own meaningful
`:description`, including the parent task, source collection, intermediate audit
maps and final outcome. Described nested fields alone do not describe the owning
blackboard key. A generated type summary does not explain its role. Then inspect
actual provider requests: SIO mode and flattened map outputs can change where
those descriptions appear. Verify the model received both the rubric meanings and
actual source/artifact values.

Ordinary chat/SIO work belongs in `llm` leaves. A manual `llm/predict` inside a code
leaf is a distinct integration and needs explicit usage/model/error capture;
native ORC LLM accounting does not automatically observe that call. For supported
native boolean/Choice judgments, use a registered decision provider with
`llm-decision` or `llm-condition`; see [decision model setup](../../orc-decision-models/SKILL.md).
Unsupported native surfaces such as Score require an explicit client and their own
accounting seam. A different provider protocol does not itself require a code node.

## Revision context

The cited `4908162` observations are historical. Decision/tool APIs were inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`; see [current source status](../../SOURCE-STATUS.md) for the public integration checkpoint. Preserve those original experiment claims at their original pin; use current owning skills for supported integration.
