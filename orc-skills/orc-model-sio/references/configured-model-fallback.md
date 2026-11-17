# Configure fallback on existing model nodes

## Current adoption checkpoint

Configured fallback is integrated in merged ORC `4966c8e35e3bb5939570bfc645eac52a8d18eb6e`, whose dependency manifest selects SIO `e61e11a9abc6fdad4e19f772d9e32e2edd2dcc3b` and litellm-clj `f5fa99d8df32d0830e972382e8fa6db233622022`. The four standard node constructors retain the routing fields below. No separate wrapper or consumer parser is needed. The preceding open-PR status and detailed acceptance counts describe the original publication checkpoint; they are not the current adoption status. See [current source integration](model-portability.md#current-authoring-and-source-checkpoint) for the merged repair map. Researcher timeout classification in the later unmerged PR #50 remains a separate correction; do not claim that fix from this merged pin.

## Configure a node

```clojure
(orc/llm "assess"
  :provider :chat-primary
  :model primary-model
  :fallback-models [{:provider :chat-backup :model backup-model
                     :options {:max-tokens 32768}}]
  :fallback-policy :temporary-transport
  :instruction instruction
  :reads [:request :evidence]
  :writes [:assessment-reasoning :assessment]
  :options {:output-mode :json-schema :max-tokens 65536})
```

The same routing fields belong on `llm-condition`, `llm-decision` and `repl-researcher`. A condition's valid false remains a negative condition; a decision's valid false is a successful assessment. Neither is a provider outage. Keep finite choices, rubric, confidence/abstention semantics and output validation unchanged across candidates. Candidate protocol compatibility is checked before dispatch; this does not establish real-model availability or task quality.

An omitted candidate provider means the primary's effective provider. An explicit provider is authoritative; a conflicting legacy native-provider alias in `:model` is rejected. Legacy native alias resolution remains available when provider is omitted. Register every required provider in worker/recovery context, not just a transient caller context.

Candidates may change supported generation settings, not task instructions, reads/writes, output mode, privacy controls, runtime identity or allowances. Give all blackboard keys concrete schemas and descriptions. Completion examples above are illustrative; choose sufficient allowances for the real models.

## Researcher roles and inheritance

Prime settings live on the researcher node. Specialist defaults live alongside `:rlm :sub-model` as `:sub-provider`, `:sub-options`, `:sub-fallback-models`, and `:sub-fallback-policy`. These reach both inline and emitted calls. A child overriding only its primary still inherits backups; its own backup list replaces the inherited one, and `[]` disables backups. Declare inline output schemas in the blackboard before requesting strict output.

After a backup succeeds, later calls in that researcher run/role/effective configuration start there. Selection is durable and only moves forward; another user, mapped researcher or new run does not inherit it. Explicit configurations have their own selection scope. Switching retries the model request, not the research iteration or completed tools.

## Policy, retries and streams

Temporary transport is the default. Reliable timeout/connection/rate-limit categories and noncontradictory HTTP 5xx/408 can qualify. Bare429 is ambiguous between rate-limit and quota. Unknown, contradictory, auth/quota and configuration failures do not qualify. Explicit `:all-transport` accepts broader transport failures; it never converts schema failure, semantic negative, abstention, cancellation or exhausted limits into an outage.

With backups configured, the default is one physical request per candidate. Explicit bounded same-model retries precede switching. An exhausted chain must not restart through outer implicit node retries. Every physical request uses the original family call budget and absolute deadline.

A stream may switch before visible output only. Once raw text or field output is exposed, preserve the failure instead of replacing the partial answer. The durable first-output marker also constrains recovery. An interrupted model request with no recorded outcome can require another counted request; this is not permission to repeat completed external actions.

## Inspect evidence

Use full projected node traces rather than the lightweight execute return to read `:model-fallback` diagnosis. Inspect the owning call's candidate history and provider attempts for indices, providers, requested/resolved model identities, safe failure category/status, switching reason and physical request counts. Use normal Grain query envelopes with id and timestamp. Budget cancellation may lack a completed trace; inspect execute `:error`, tick cancellation and canonical cancellation reason rather than waiting forever.

For candidate history use the normal authenticated Grain query envelope:

```clojure
(qp/process-query
  (assoc ctx :query {:query/name :sheet/model-candidate-history
                    :query/id (random-uuid)
                    :query/timestamp (time/now)
                    :tick-id (:trace-id result)}))
;; :query/result is a vector of canonical projected candidate records.
```

`qp` is `ai.obney.grain.query-processor.interface`. `time` is `ai.obney.grain.time.interface`. For full trace use `:sheet/get-trace` with `:trace-id`; its node entries are at `[:query/result :trace :node-traces]`. Delegated child ticks have their own histories; obtain them from `orc/get-execution-family` rather than assuming the parent contains every request.

Compare serialized HTTP bodies with exact canonical node inputs, effective options, output schemas and returned values. For researchers inspect prime, inline and emitted requests separately. Do not infer working propagation from DSL declarations or simulated `llm/predict` responses. Never persist raw exception data/causes that can contain credentials.

## Evidence

Independent local acceptance passed 20 tests/371 assertions. A fresh agent configured an ordinary node, correctly diagnosed a refused401, and ran a recursive researcher with inherited specialist backups: seven HTTP requests total. See evidence and source manifests (historical local experiment; private archive not bundled). SQLite recovery closes/reopens the datastore/runtime after a saved quantum; this is not a separate-process hard-kill proof. Local fixtures establish routing/contracts/recovery mechanics, not semantic quality or production throughput.

For the later private effective-profile and exact-response recovery repair, read [retained fallback profiles](retained-fallback-profiles.md). Its ordinary proof does not extend the historical saved-quantum researcher test to hard-kill/profile recovery.

## Original publication checkpoint

This recipe describes [ORC PR #48](https://github.com/ObneyAI/orc/pull/48), commit `9116dc99f64585563f64d6407dcb9183d88652a3`: published PR source, open and unmerged at verification, not released mainline support. It supersedes the earlier `llm-fallback` wrapper recipe. Verify loaded source, provider registrations, and the dependency manifest before adoption. Streaming uses the pinned client repair in [client PR #3](https://github.com/ObneyAI/litellm-clj/pull/3), commit `bc65561e974d5e35b80d3f842e190231476fdd10`. Client PR #3 was also open and unmerged at verification. A focused run against this published Git dependency passed 5 tests and 37 assertions without a local override. Recheck both PR states before making a new publication claim. The [model-contract campaign](model-portability.md#fallback-preserves-the-contract) later exercised a synthetic primary failure followed by a real backup through a stack with additional local repairs; that is separate evidence, not proof those repairs are in these PR pins.

## Integrated ordinary-writer live witness

The later complex-tree fallback witness (historical local experiment; private archive not bundled) uses the merged4966c8e stack. A standard writer inherits its primary provider and sets `:fallback-models [{:model "minimax/minimax-m3.1-flash"}]` plus `:fallback-policy :temporary-transport`. Its injected undispatched primary503 advanced to a real MiniMax response through the same registered provider alias. No parser or new node type was needed.

Canonical history retained both candidate claims under the same node start/configuration, the typed primary reason and selected backup. Wire content stayed identical except model. Six successful responses matched canonical values; the seventh slot was the injected failure. An earlier completed credit remained single and the reply asked for permission without booking. This newly proves ordinary-writer integration, not new all-node, live-outage or fallback-crash coverage. Preserve unknown primary cost as a held reservation rather than inventing zero usage. The root launcher failed after qualified capture from a local variable shadowing `case`; its preserved evidence and separate correction are explicitly reported.
